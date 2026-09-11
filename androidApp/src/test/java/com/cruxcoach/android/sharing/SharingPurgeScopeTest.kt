package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.SecureDatabasePragmas
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.AuthorityAttestation
import com.cruxcoach.domain.sharing.AuthorityPairing
import com.cruxcoach.domain.sharing.AuthorityDeviceId
import com.cruxcoach.domain.sharing.DeviceManifestBody
import com.cruxcoach.domain.sharing.DeviceManifestEntry
import com.cruxcoach.domain.sharing.DeviceRole
import com.cruxcoach.domain.sharing.AeadSharingKeyVault
import com.cruxcoach.domain.sharing.CryptoEraseStep
import com.cruxcoach.domain.sharing.InMemoryWrappingKeyStore
import com.cruxcoach.domain.sharing.KeyHandle
import com.cruxcoach.domain.sharing.KeyScope
import com.cruxcoach.domain.sharing.LedgerEntryId
import com.cruxcoach.domain.sharing.ManifestContext
import com.cruxcoach.domain.sharing.ObjectId
import com.cruxcoach.domain.sharing.PeerId
import com.cruxcoach.domain.sharing.SharingCategory
import com.cruxcoach.domain.sharing.SharingLedgerBody
import com.cruxcoach.domain.sharing.SharingKeyHandles
import com.cruxcoach.domain.sharing.SharingLedgerEntry
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
 * FEAT-062 §9: whose data a purge is allowed to destroy.
 *
 * "Remove this person's data from this device" and "destroy the owner's own
 * data" are different acts, and only the first is on offer. The category and
 * object key handles are *global owner* keys: `CATEGORY:VIDEOS` is the key the
 * owner's own videos are sealed under, shared by every relationship allowed to
 * see them. Destroying it because one person was removed takes the owner's
 * videos with it, and every other recipient's along the way.
 *
 * So a purge may only ever destroy keys belonging to the person being purged.
 * Those have to be a distinct, peer-namespaced thing rather than the global
 * ones.
 */
class SharingPurgeScopeTest {

    private lateinit var dbFile: java.io.File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: SecureDatabase
    private lateinit var repo: SecureDbSharingRepository
    private val keyStore = InMemoryWrappingKeyStore()

    private val owner = "npub1owner"
    private val alice = PeerId("npub1alice")
    private val bob = PeerId("npub1bob")

    /** One object both people are allowed to see. */
    private val sharedObject = ObjectId("video-42")

    // The owner's own keys, as the current code names them. These are what the
    // owner's videos are actually sealed under.
    private val ownerCategoryKey = KeyHandle(KeyScope.CATEGORY, SharingCategory.VIDEOS.name)
    private val ownerObjectKey = KeyHandle(KeyScope.OBJECT, sharedObject.value)

    private fun open() {
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}?foreign_keys=on").also { d ->
            SecureDatabasePragmas.STATEMENTS.forEach { runCatching { d.execute(null, it, 0).value } }
        }
        db = SecureDatabase(driver)
        repo = SecureDbSharingRepository(
            database = db,
            vault = AeadSharingKeyVault(keyStore),
            verifier = { true },
            ownerPolicyVerifier = { true },
            ownerNpub = owner,
            deviceManifestVerifier = { true },
            attestationVerifier = { _, _ -> true },
        )
    }

    private val device = AuthorityDeviceId("purge-test-device")

    /** One enrolled PRIMARY, so an administrative write has an author. */
    private fun enrolDevice() {
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
    }

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-purge-scope-")
        dbFile = tmp.resolve("secure.db").toFile()
        open()
        SecureDatabase.Schema.create(driver)
        enrolDevice()
        offer(alice)
        offer(bob)
    }

    @AfterTest
    fun tearDown() {
        runCatching { driver.close() }
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    /**
     * Offers, with the act that authorises it.
     *
     * Every owner-administrative write needs one now; writing the entry alone
     * would exercise a path production no longer has.
     */
    private fun offer(peer: PeerId) {
        appendPaired(
            SharingLedgerEntry(
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
        )
    }

    /** Writes an entry together with the one act that authorises it. */
    private fun appendPaired(entry: SharingLedgerEntry) {
        val required = AuthorityPairing.requiredFor(entry.peer, entry.body)
        if (required == null) {
            repo.appendEntry(entry)
            return
        }
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

    private fun seedOwnerKeys() {
        val category = repo.createDataKey(ownerCategoryKey)
        repo.storeSealedItem(
            "owner-video", SharingCategory.VIDEOS, ownerCategoryKey, category,
            "the owner's own video".encodeToByteArray(),
        )
        val obj = repo.createDataKey(ownerObjectKey)
        repo.storeSealedItem(
            "owner-object", SharingCategory.VIDEOS, ownerObjectKey, obj,
            "one particular video".encodeToByteArray(),
        )
    }

    /**
     * The finding: the key the owner's own videos are sealed under is global,
     * and removing one person destroyed it.
     */
    @Test
    fun `purging one person leaves the owner's own category key intact`() {
        seedOwnerKeys()

        repo.purgeLocalRelationshipData(alice)

        assertNotNull(keyStore.get(ownerCategoryKey), "the owner's own category key must survive")
        val key = assertNotNull(repo.readWrappedKey(ownerCategoryKey), "its wrapped key row must survive")
        assertEquals(
            "the owner's own video",
            assertNotNull(repo.readOwnerSealedItem("owner-video", key)).decodeToString(),
            "the owner's own data must still open",
        )
    }

    @Test
    fun `purging one person leaves the owner's own object key intact`() {
        seedOwnerKeys()

        repo.purgeLocalRelationshipData(alice)

        assertNotNull(keyStore.get(ownerObjectKey), "the owner's own object key must survive")
        val key = assertNotNull(repo.readWrappedKey(ownerObjectKey))
        assertEquals(
            "one particular video",
            assertNotNull(repo.readOwnerSealedItem("owner-object", key)).decodeToString(),
        )
    }

    /**
     * The same category and the same object, shared with two people. Removing
     * one of them says nothing about the other.
     */
    @Test
    fun `purging one person leaves the other person's data readable`() {
        seedOwnerKeys()

        repo.purgeLocalRelationshipData(alice)

        assertNotNull(repo.loadProjection().relationships[bob], "bob's relationship must survive")
        assertNotNull(repo.readWrappedKey(ownerCategoryKey), "what bob may see must survive")
        assertTrue(repo.loadLedger(bob).isNotEmpty(), "bob's ledger must be untouched")
    }

    @Test
    fun `purging one person still removes that person`() {
        seedOwnerKeys()

        val outcome = repo.purgeLocalRelationshipData(alice)

        assertTrue(outcome.completed)
        assertNull(repo.loadProjection().relationships[alice])
        assertTrue(repo.loadLedger(alice).isEmpty())
        assertTrue(
            db.sharingQueries.selectTombstones(alice.value).executeAsList().any { it.kind == "PURGED" },
            "the tombstone must still be there",
        )
    }

    @Test
    fun `a purge destroys keys before it deletes rows`() {
        seedOwnerKeys()
        val order = mutableListOf<CryptoEraseStep>()

        repo.purgeLocalRelationshipData(alice) { order += it }

        assertEquals(CryptoEraseStep.DESTROY_KEYS, order.first())
    }

    // ------------------------------------------------- recipient key scope

    /**
     * A key wrapped *for a recipient* is the one thing a removal may destroy.
     * It has to be a different handle from the owner's, namespaced to exactly
     * one peer, or "destroy what belongs to Alice" cannot be expressed at all —
     * which is how the owner's own key came to be destroyed instead.
     */
    private fun seedRecipientKeys(peer: PeerId, marker: String) {
        val category = SharingKeyHandles.recipientCategory(peer, SharingCategory.VIDEOS)
        val key = repo.createDataKey(category)
        repo.storeSealedItem(
            "delivery-$marker", SharingCategory.VIDEOS, category, key,
            "delivered to $marker".encodeToByteArray(),
        )
        val obj = SharingKeyHandles.recipientObject(peer, sharedObject)
        val objKey = repo.createDataKey(obj)
        repo.storeSealedItem(
            "delivery-object-$marker", SharingCategory.VIDEOS, obj, objKey,
            "one video for $marker".encodeToByteArray(),
        )
    }

    @Test
    fun `purging one person destroys exactly that person's recipient keys`() {
        seedOwnerKeys()
        seedRecipientKeys(alice, "alice")
        seedRecipientKeys(bob, "bob")

        repo.purgeLocalRelationshipData(alice)

        val aliceCategory = SharingKeyHandles.recipientCategory(alice, SharingCategory.VIDEOS)
        val aliceObject = SharingKeyHandles.recipientObject(alice, sharedObject)
        assertNull(keyStore.get(aliceCategory), "alice's recipient category key must be destroyed")
        assertNull(keyStore.get(aliceObject), "alice's recipient object key must be destroyed")
        assertNull(repo.readWrappedKey(aliceCategory), "its wrapped row must be gone")
        assertNull(repo.readWrappedKey(aliceObject))
    }

    @Test
    fun `purging one person leaves the other person's recipient keys`() {
        seedOwnerKeys()
        seedRecipientKeys(alice, "alice")
        seedRecipientKeys(bob, "bob")

        repo.purgeLocalRelationshipData(alice)

        val bobCategory = SharingKeyHandles.recipientCategory(bob, SharingCategory.VIDEOS)
        val bobObject = SharingKeyHandles.recipientObject(bob, sharedObject)
        assertNotNull(keyStore.get(bobCategory), "bob's recipient category key must survive")
        assertNotNull(keyStore.get(bobObject), "bob's recipient object key must survive")
        val key = assertNotNull(repo.readWrappedKey(bobCategory))
        assertEquals(
            "delivered to bob",
            assertNotNull(repo.readOwnerSealedItem("delivery-bob", key)).decodeToString(),
        )
    }

    @Test
    fun `purging one person leaves the owner's keys even with recipient keys present`() {
        seedOwnerKeys()
        seedRecipientKeys(alice, "alice")

        repo.purgeLocalRelationshipData(alice)

        assertNotNull(keyStore.get(ownerCategoryKey), "the owner's category key is not alice's")
        assertNotNull(keyStore.get(ownerObjectKey), "the owner's object key is not alice's")
    }

    @Test
    fun `the sealed items of a purged person go with their keys`() {
        seedOwnerKeys()
        seedRecipientKeys(alice, "alice")
        seedRecipientKeys(bob, "bob")

        repo.purgeLocalRelationshipData(alice)

        val remaining = db.sharingQueries.selectAllSealedItems().executeAsList().map { it.item_id }
        assertTrue("delivery-alice" !in remaining, "alice's delivered ciphertext must be gone")
        assertTrue("delivery-object-alice" !in remaining)
        assertTrue("delivery-bob" in remaining, "bob's must stay")
        assertTrue("owner-video" in remaining, "the owner's must stay")
    }

    /**
     * Object ids are free text the owner types. One spelled to look like a
     * recipient id must still be the owner's, or a purge could be talked into
     * destroying the owner's own object.
     */
    @Test
    fun `an object id shaped like a recipient id is still an owner key`() {
        val forged = ObjectId("rcpt.v1|10:npub1alice|6:VIDEOS|")
        val handle = SharingKeyHandles.ownerObject(forged)
        repo.createDataKey(handle)

        repo.purgeLocalRelationshipData(alice)

        assertNotNull(keyStore.get(handle), "an owner key must never be mistaken for a recipient's")
    }

    /**
     * The one the previous round missed.
     *
     * Ownership was decided by a prefix on `key_id`, which is free-form — so a
     * handle carrying a *syntactically genuine* recipient id under an **owner**
     * scope was indistinguishable from the real thing. That is not a
     * hypothetical: it is any row written before the naming existed, and any
     * handle built directly rather than through the factory. The earlier
     * forgery test went through `ownerObject`, which prepended an owner marker,
     * so it never reached this path.
     *
     * Scope is the structural half of the answer: an owner scope can never
     * belong to a peer whatever its id says.
     */
    @Test
    fun `a raw owner OBJECT key carrying a genuine recipient id survives a purge`() {
        val recipientShaped = SharingKeyHandles.recipientObject(alice, sharedObject).id
        val legacy = KeyHandle(KeyScope.OBJECT, recipientShaped)
        repo.createDataKey(legacy)
        repo.storeSealedItem(
            "legacy-object", SharingCategory.VIDEOS, legacy,
            assertNotNull(repo.readWrappedKey(legacy)), "owner data".encodeToByteArray(),
        )

        repo.purgeLocalRelationshipData(alice)

        assertNotNull(keyStore.get(legacy), "an owner-scoped key is never a recipient's, whatever its id")
        val key = assertNotNull(repo.readWrappedKey(legacy))
        assertEquals("owner data", assertNotNull(repo.readOwnerSealedItem("legacy-object", key)).decodeToString())
    }

    @Test
    fun `a raw owner CATEGORY key carrying a genuine recipient id survives a purge`() {
        val recipientShaped = SharingKeyHandles.recipientCategory(alice, SharingCategory.VIDEOS).id
        val legacy = KeyHandle(KeyScope.CATEGORY, recipientShaped)
        repo.createDataKey(legacy)

        repo.purgeLocalRelationshipData(alice)

        assertNotNull(keyStore.get(legacy), "an owner-scoped key is never a recipient's, whatever its id")
    }

    @Test
    fun `a recipient handle names exactly one peer`() {
        val forAlice = SharingKeyHandles.recipientCategory(alice, SharingCategory.VIDEOS)

        assertTrue(SharingKeyHandles.belongsTo(forAlice, alice))
        assertFalse(SharingKeyHandles.belongsTo(forAlice, bob))
        assertFalse(SharingKeyHandles.belongsTo(SharingKeyHandles.ownerCategory(SharingCategory.VIDEOS), alice))
        assertFalse(SharingKeyHandles.belongsTo(SharingKeyHandles.ownerObject(sharedObject), alice))
    }

    /**
     * Ownership is the whole id, not a prefix of it. Trailing rubbish would
     * otherwise ride along on a real peer's prefix and be destroyed with them —
     * or, read the other way, let an attacker-chosen suffix decide what a purge
     * reaches.
     */
    @Test
    fun `a recipient id with anything appended belongs to nobody`() {
        val genuine = SharingKeyHandles.recipientCategory(alice, SharingCategory.VIDEOS)
        val extended = KeyHandle(genuine.scope, genuine.id + "extra")
        val truncated = KeyHandle(genuine.scope, genuine.id.dropLast(1))
        val wrongLength = KeyHandle(genuine.scope, genuine.id.replaceFirst("rcpt.v1|", "rcpt.v1|9"))

        assertFalse(SharingKeyHandles.belongsTo(extended, alice), "a suffix must not ride along")
        assertFalse(SharingKeyHandles.belongsTo(truncated, alice))
        assertFalse(SharingKeyHandles.belongsTo(wrongLength, alice))
    }

    @Test
    fun `a recipient key with a trailing suffix is not destroyed by that peer's purge`() {
        val genuine = SharingKeyHandles.recipientCategory(alice, SharingCategory.VIDEOS)
        val extended = KeyHandle(genuine.scope, genuine.id + "extra")
        repo.createDataKey(extended)

        repo.purgeLocalRelationshipData(alice)

        assertNotNull(keyStore.get(extended), "only a fully-formed recipient id names a peer")
    }

    // ------------------------------------------------------------- backup

    /**
     * A backup taken after a removal must not carry the removed person's keys
     * back onto a restored device — while everything that was never theirs has
     * to survive, or a restore would lose the owner's own data.
     */
    @Test
    fun `a backup taken after a purge carries no key of the purged person`() {
        seedOwnerKeys()
        seedRecipientKeys(alice, "alice")
        seedRecipientKeys(bob, "bob")

        repo.purgeLocalRelationshipData(alice)
        val payload = repo.collectBackupPayload()

        val exported = payload.dataKeys.map { KeyHandle(it.scope, it.id) }
        assertTrue(
            exported.none { SharingKeyHandles.belongsTo(it, alice) },
            "no key of the purged person may be exported",
        )
        assertTrue(
            exported.any { SharingKeyHandles.belongsTo(it, bob) },
            "the other person's keys must still be exported",
        )
        assertTrue(ownerCategoryKey in exported, "the owner's own key must still be exported")
        assertTrue(ownerObjectKey in exported)
        assertTrue(
            payload.sealedItems.none { it.itemId.endsWith("-alice") },
            "no ciphertext of the purged person may be exported",
        )
        payload.zeroizeKeys()
    }
}
