package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.SecureDatabasePragmas
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.AeadSharingKeyVault
import com.cruxcoach.domain.sharing.AuthorityAttestation
import com.cruxcoach.domain.sharing.AuthorityDeviceId
import com.cruxcoach.domain.sharing.AuthorityPairing
import com.cruxcoach.domain.sharing.DeviceManifestBody
import com.cruxcoach.domain.sharing.DeviceManifestEntry
import com.cruxcoach.domain.sharing.DeviceRole
import com.cruxcoach.domain.sharing.InMemoryWrappingKeyStore
import com.cruxcoach.domain.sharing.KeyHandle
import com.cruxcoach.domain.sharing.LedgerEntryId
import com.cruxcoach.domain.sharing.ManifestContext
import com.cruxcoach.domain.sharing.ObjectId
import com.cruxcoach.domain.sharing.PeerId
import com.cruxcoach.domain.sharing.SealedPayload
import com.cruxcoach.domain.sharing.SharingCategory
import com.cruxcoach.domain.sharing.SharingKeyHandles
import com.cruxcoach.domain.sharing.SharingKeyVault
import com.cruxcoach.domain.sharing.SharingLedgerBody
import com.cruxcoach.domain.sharing.SharingLedgerEntry
import com.cruxcoach.domain.sharing.WrappedKey
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FEAT-062 §2.4a: a removal that could not finish must still be a removal that
 * can be asked for again.
 *
 * The purge is the one irreversible thing on the screen, and it is made of
 * steps that are not all in the database: the Keystore aliases go first, then
 * the rows. Destroying a Keystore alias is a call into the platform and it can
 * fail — a key invalidated by a lock-screen change, a locked user, a vendor
 * keystore that simply throws.
 *
 * The tombstone was written *before* that pipeline ran and was left behind when
 * it failed. A tombstone is sticky by design, and every write door refuses a
 * peer that carries one, so the relationship ended up in a state with no way
 * out: its rows, its ledger and its ciphertext all still on the device, the
 * screen reporting a failure, and *no* second attempt possible — the retry was
 * refused by the very marker the failed attempt had left.
 *
 * "We could not delete your data, and now nobody can" is the worst answer this
 * feature has. So the whole local removal is one transaction: it either records
 * the tombstone and deletes everything, or it records nothing and can be run
 * again.
 */
class SharingPurgeFailureTest {

    private lateinit var dbFile: java.io.File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: SecureDatabase
    private lateinit var repo: SecureDbSharingRepository
    private val keyStore = InMemoryWrappingKeyStore()

    private val owner = "npub1owner"
    private val alice = PeerId("npub1alice")
    private val device = AuthorityDeviceId("purge-failure-device")
    private val sharedObject = ObjectId("video-42")

    /**
     * A vault that refuses to destroy while [failing] is set.
     *
     * Everything else is delegated, so the failure is exactly the one step that
     * can fail on a real device rather than a broken vault throughout.
     */
    private class FlakyVault(private val delegate: SharingKeyVault) : SharingKeyVault {
        var failing = false

        override fun createDataKey(handle: KeyHandle): WrappedKey = delegate.createDataKey(handle)

        override fun seal(key: WrappedKey, plaintext: ByteArray, aad: ByteArray): SealedPayload =
            delegate.seal(key, plaintext, aad)

        override fun open(key: WrappedKey, sealed: SealedPayload, aad: ByteArray): ByteArray =
            delegate.open(key, sealed, aad)

        override fun destroy(handle: KeyHandle) {
            if (failing) error("the keystore refused to destroy ${handle.scope}")
            delegate.destroy(handle)
        }

        override fun exportForBackup(key: WrappedKey): ByteArray? = delegate.exportForBackup(key)

        override fun importFromBackup(handle: KeyHandle, rawDataKey: ByteArray): WrappedKey =
            delegate.importFromBackup(handle, rawDataKey)
    }

    private lateinit var vault: FlakyVault

    private fun open() {
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}?foreign_keys=on").also { d ->
            SecureDatabasePragmas.STATEMENTS.forEach { runCatching { d.execute(null, it, 0).value } }
        }
        db = SecureDatabase(driver)
        vault = FlakyVault(AeadSharingKeyVault(keyStore))
        repo = SecureDbSharingRepository(
            database = db,
            vault = vault,
            verifier = { true },
            ownerPolicyVerifier = { true },
            ownerNpub = owner,
            deviceManifestVerifier = { true },
            attestationVerifier = { _, _ -> true },
        )
    }

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-purge-failure-")
        dbFile = tmp.resolve("secure.db").toFile()
        open()
        SecureDatabase.Schema.create(driver)
        repo.appendDeviceManifestEntry(
            DeviceManifestEntry(
                id = LedgerEntryId("m-1"),
                manifestSequence = 1,
                authorityGeneration = 1,
                parent = null,
                signerNpub = owner,
                signature = "m-sig",
                body = DeviceManifestBody.DeviceEnrolled(device, "pk", DeviceRole.PRIMARY),
            ),
        )
        offer(alice)
        // One key of alice's, so the destroy step has something to do and can
        // therefore fail.
        val handle = SharingKeyHandles.recipientCategory(alice, SharingCategory.VIDEOS)
        val key = repo.createDataKey(handle)
        repo.storeSealedItem(
            "delivery-alice", SharingCategory.VIDEOS, handle, key,
            "delivered to alice".encodeToByteArray(),
        )
    }

    @AfterTest
    fun tearDown() {
        runCatching { driver.close() }
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    private fun offer(peer: PeerId) {
        val entry = SharingLedgerEntry(
            id = LedgerEntryId("offer-${peer.value}"),
            peer = peer,
            policySequence = 1,
            authorityGeneration = 1,
            resourceEpoch = 1,
            deviceGeneration = 1,
            signerNpub = owner,
            signature = "sig",
            body = SharingLedgerBody.RelationshipOffered(setOf(SharingCategory.VIDEOS)),
        )
        val required = requireNotNull(AuthorityPairing.requiredFor(entry.peer, entry.body))
        val act = AuthorityAttestation(
            id = LedgerEntryId("act-${entry.id.value}"),
            scope = required.scope,
            subject = entry.id,
            device = device,
            parent = repo.currentAuthority()[required.scope]?.id,
            manifestContext = ManifestContext.of(repo.loadDeviceAuthority().frontier),
            authorityGeneration = 1,
            capability = required.capability,
            effect = required.effect,
            signature = "act-sig",
        )
        check(repo.commitAll(emptyList(), listOf(entry), listOf(act))) {
            "the fixture's entry was refused: ${repo.lastBatchRejection}"
        }
    }

    private fun tombstones() =
        db.sharingQueries.selectTombstones(alice.value).executeAsList().map { it.kind }

    @Test
    fun `a purge whose key destruction fails reports the failure`() {
        vault.failing = true

        val outcome = repo.purgeLocalRelationshipData(alice)

        assertFalse(outcome.completed, "a purge that could not destroy the keys did not complete")
    }

    /**
     * The finding. A failed attempt must not leave the sticky marker behind,
     * because that marker is what every write door refuses on.
     */
    @Test
    fun `a purge that could not finish leaves no tombstone behind`() {
        vault.failing = true

        repo.purgeLocalRelationshipData(alice)

        assertTrue(
            "PURGED" !in tombstones(),
            "a purge that destroyed nothing must not mark the relationship as purged",
        )
    }

    /** And what that marker costs: the relationship's data is still all there. */
    @Test
    fun `a purge that could not finish leaves the relationship exactly as it was`() {
        vault.failing = true

        repo.purgeLocalRelationshipData(alice)

        assertNotNull(repo.loadProjection().relationships[alice], "the relationship row must survive")
        assertTrue(repo.loadLedger(alice).isNotEmpty(), "and its signed history with it")
        assertNotNull(
            repo.readWrappedKey(SharingKeyHandles.recipientCategory(alice, SharingCategory.VIDEOS)),
            "a key row whose destruction was rolled back must still be there",
        )
    }

    /**
     * The one that matters to the person holding the phone: pressing it again
     * has to work.
     */
    @Test
    fun `a purge can be run again after one that could not finish`() {
        vault.failing = true
        repo.purgeLocalRelationshipData(alice)

        vault.failing = false
        val second = repo.purgeLocalRelationshipData(alice)

        assertTrue(second.completed, "the second attempt must complete: ${second.failedStep}")
        assertNull(repo.loadProjection().relationships[alice], "and remove the relationship")
        assertTrue(repo.loadLedger(alice).isEmpty(), "and its ledger")
        assertTrue("PURGED" in tombstones(), "and leave the sticky tombstone this time")
    }

    /**
     * A failed purge must not poison the ledger either.
     *
     * `isPurged` is checked by every write door, so a tombstone left by a failed
     * attempt would refuse the very entry the retry has to write first.
     */
    @Test
    fun `a relationship survives a failed purge as a writable relationship`() {
        vault.failing = true
        repo.purgeLocalRelationshipData(alice)

        val entry = SharingLedgerEntry(
            id = LedgerEntryId("grant-after-failed-purge"),
            peer = alice,
            policySequence = repo.nextSequence(alice),
            parent = repo.authorisedHead(alice)?.id,
            authorityGeneration = 1,
            resourceEpoch = 1,
            deviceGeneration = 1,
            signerNpub = owner,
            signature = "sig",
            body = SharingLedgerBody.GrantChanged(setOf(SharingCategory.VIDEOS)),
        )
        val required = requireNotNull(AuthorityPairing.requiredFor(entry.peer, entry.body))
        val act = AuthorityAttestation(
            id = LedgerEntryId("act-after-failed-purge"),
            scope = required.scope,
            subject = entry.id,
            device = device,
            parent = repo.currentAuthority()[required.scope]?.id,
            manifestContext = ManifestContext.of(repo.loadDeviceAuthority().frontier),
            authorityGeneration = 1,
            capability = required.capability,
            effect = required.effect,
            signature = "act-sig",
        )

        assertTrue(
            repo.commitAll(emptyList(), listOf(entry), listOf(act)),
            "a relationship whose purge failed must still take writes: ${repo.lastBatchRejection}",
        )
    }

    /**
     * The successful path is unchanged: the tombstone is still written, and it
     * is still there when the rows are gone.
     */
    @Test
    fun `a purge that completes still leaves the sticky tombstone`() {
        val outcome = repo.purgeLocalRelationshipData(alice)

        assertTrue(outcome.completed)
        assertEquals(listOf("PURGED"), tombstones())
        assertNull(repo.loadProjection().relationships[alice])
    }
}
