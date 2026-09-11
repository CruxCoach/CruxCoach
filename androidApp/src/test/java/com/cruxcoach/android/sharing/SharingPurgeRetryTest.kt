package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.SecureDatabasePragmas
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.AeadSharingKeyVault
import com.cruxcoach.domain.sharing.AsyncDeviceManifestSigner
import com.cruxcoach.domain.sharing.AsyncOwnerPolicySigner
import com.cruxcoach.domain.sharing.AsyncSharingLedgerSigner
import com.cruxcoach.domain.sharing.DeviceManifestSigner
import com.cruxcoach.domain.sharing.InMemoryWrappingKeyStore
import com.cruxcoach.domain.sharing.KeyHandle
import com.cruxcoach.domain.sharing.LedgerCrypto
import com.cruxcoach.domain.sharing.PeerId
import com.cruxcoach.domain.sharing.RelationshipStatus
import com.cruxcoach.domain.sharing.SealedPayload
import com.cruxcoach.domain.sharing.SharingCategory
import com.cruxcoach.domain.sharing.SharingCircle
import com.cruxcoach.domain.sharing.SharingKeyHandles
import com.cruxcoach.domain.sharing.SharingKeyVault
import com.cruxcoach.domain.sharing.SharingLedgerBody
import com.cruxcoach.domain.sharing.WrappedKey
import com.cruxcoach.domain.sharing.asAsync
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * FEAT-062 §2.4a: *Lokal löschen*, pressed twice, on the path a person actually
 * takes.
 *
 * [SharingPurgeFailureTest] holds the repository half of this: a removal that
 * cannot destroy its keys records nothing and can be run again. But it calls
 * `purgeLocalRelationshipData` directly, and the real path does something first
 * that cannot be rolled back — it appends a **signed, permanent**
 * `PurgeRequested` to an append-only ledger, which moves the relationship to
 * `PURGE_PENDING`, a terminal status.
 *
 * So the acceptance claim needs checking where the entry exists: after a failed
 * attempt the ledger already says a removal was asked for, and the second
 * attempt has to get past its own first act. If a terminal status absorbed the
 * retry's `PurgeRequested`, or admission refused it, the person would be left
 * exactly where the repository fix was supposed to stop them being left — told
 * the deletion failed, with no way to ask again.
 */
class SharingPurgeRetryTest {

    private companion object {
        const val OWNER = "npub1owner"
    }

    private val alice = PeerId("npub1alice")

    private lateinit var dbFile: java.io.File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repository: SecureDbSharingRepository
    private lateinit var controller: SharingController
    private lateinit var vault: FlakyVault
    private val keyStore = InMemoryWrappingKeyStore()

    private class TaggedCrypto(private val identity: String) : LedgerCrypto {
        override fun sign(hash: ByteArray): ByteArray = (identity + ":").encodeToByteArray() + hash
        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
            signature.contentEquals((signerNpub + ":").encodeToByteArray() + hash)
    }

    /** A keystore that refuses to destroy while [failing] is set. */
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

    @BeforeTest
    fun setUp() = runTest {
        val tmp = Files.createTempDirectory("cruxcoach-purge-retry-")
        dbFile = tmp.resolve("secure.db").toFile()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}?foreign_keys=on").also { d ->
            SecureDatabasePragmas.STATEMENTS.forEach { runCatching { d.execute(null, it, 0).value } }
        }
        val ownerCrypto = TaggedCrypto(OWNER)
        val signer = AsyncSharingLedgerSigner(ownerCrypto.asAsync())
        val policySigner = AsyncOwnerPolicySigner(ownerCrypto.asAsync())
        val attestationSigner = TestDeviceAuthority.attestationSigner()
        vault = FlakyVault(AeadSharingKeyVault(keyStore))
        repository = SecureDbSharingRepository(
            database = SecureDatabase(driver),
            vault = vault,
            verifier = signer.verifier(),
            ownerPolicyVerifier = policySigner.verifier(),
            ownerNpub = OWNER,
            deviceManifestVerifier = DeviceManifestSigner(ownerCrypto).verifier(),
            attestationVerifier = attestationSigner.verifier(),
            localDeviceIdentity = {
                DeviceIdentity(TestDeviceAuthority.DEVICE, TestDeviceAuthority.publicKeyOf())
            },
        )
        controller = SharingController(
            repository = repository,
            signer = signer,
            ownerPolicySigner = policySigner,
            ownerNpub = OWNER,
            authorityDevice = TestDeviceAuthority.DEVICE,
            authorityDevicePublicKey = TestDeviceAuthority.publicKeyOf(),
            attestationSigner = attestationSigner,
            manifestSigner = AsyncDeviceManifestSigner(ownerCrypto.asAsync()),
        )
        SecureDatabase.Schema.create(driver)
        TestDeviceAuthority.enrol(repository, ownerCrypto, OWNER)
        assertTrue(controller.invite(alice, SharingCircle.FRIENDS).isSuccess, "the fixture must write")

        // One key of alice's, so the destroy step has something to do and can
        // therefore fail.
        val handle = SharingKeyHandles.recipientCategory(alice, SharingCategory.VIDEOS)
        val key = repository.createDataKey(handle)
        repository.storeSealedItem(
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

    private fun tombstones() =
        SecureDatabase(driver).sharingQueries.selectTombstones(alice.value).executeAsList().map { it.kind }

    private fun purgeRequests() = repository.loadLedger(alice)
        .count { it.body is SharingLedgerBody.PurgeRequested }

    @Test
    fun `a purge whose keystore fails is reported to the person`() = runTest {
        vault.failing = true

        assertEquals(
            SharingWriteResult.Failed(SharingWriteError.REJECTED),
            controller.purge(alice),
            "a removal that destroyed nothing must not report success",
        )
    }

    /**
     * The first attempt's `PurgeRequested` is signed and permanent, so it is
     * still there — but the data it was about is too.
     */
    @Test
    fun `a failed purge leaves the request recorded and the data present`() = runTest {
        vault.failing = true

        controller.purge(alice)

        assertEquals(1, purgeRequests(), "the removal was asked for, and that is on the record")
        assertEquals(
            RelationshipStatus.PURGE_PENDING,
            assertNotNull(repository.loadProjection().relationships[alice]).status,
        )
        assertTrue("PURGED" !in tombstones(), "but nothing was removed, so nothing says it was")
        assertNotNull(
            repository.readWrappedKey(SharingKeyHandles.recipientCategory(alice, SharingCategory.VIDEOS)),
            "the key whose destruction failed is still there",
        )
    }

    /** The claim this whole change exists to support. */
    @Test
    fun `the person can press it again once the keystore recovers`() = runTest {
        vault.failing = true
        controller.purge(alice)

        vault.failing = false
        assertEquals(
            SharingWriteResult.Ok,
            controller.purge(alice),
            "the second attempt must sign, write and delete: ${repository.lastBatchRejection}",
        )
    }

    @Test
    fun `the retry really removes everything`() = runTest {
        vault.failing = true
        controller.purge(alice)

        vault.failing = false
        controller.purge(alice)

        assertNull(repository.loadProjection().relationships[alice], "the relationship is gone")
        assertTrue(repository.loadLedger(alice).isEmpty(), "and its signed history with it")
        assertTrue("PURGED" in tombstones(), "and the sticky tombstone is there this time")
        assertNull(
            keyStore.get(SharingKeyHandles.recipientCategory(alice, SharingCategory.VIDEOS)),
            "and the recipient key is destroyed",
        )
    }

    /**
     * A `PURGE_PENDING` relationship is terminal, and terminal states absorb
     * almost everything. The retry's own `PurgeRequested` must be one of the
     * exceptions, or the second attempt could never be authored at all.
     */
    @Test
    fun `a second purge request is admitted onto a terminal relationship`() = runTest {
        vault.failing = true
        controller.purge(alice)

        vault.failing = false
        controller.purge(alice)

        // The ledger is deleted by the successful purge, so the evidence that
        // the second request was admitted is that the removal completed at all
        // — checked above — plus the tombstone. What this asserts is the
        // narrower thing: the first attempt did not leave a state that refuses
        // writes.
        assertTrue("PURGED" in tombstones())
    }

    /** And a purge that works first time is unchanged by any of this. */
    @Test
    fun `a purge that succeeds first time still removes everything`() = runTest {
        assertEquals(SharingWriteResult.Ok, controller.purge(alice))

        assertNull(repository.loadProjection().relationships[alice])
        assertTrue("PURGED" in tombstones())
    }
}
