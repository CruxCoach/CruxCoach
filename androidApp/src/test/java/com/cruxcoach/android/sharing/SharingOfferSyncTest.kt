package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.SecureDatabasePragmas
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.AccessEffect
import com.cruxcoach.domain.sharing.AsyncOwnerPolicySigner
import com.cruxcoach.domain.sharing.AsyncSharingLedgerSigner
import com.cruxcoach.domain.sharing.asAsync
import com.cruxcoach.domain.sharing.AeadSharingKeyVault
import com.cruxcoach.domain.sharing.DecisionSource
import com.cruxcoach.domain.sharing.DeviceId
import com.cruxcoach.domain.sharing.DeviceManifestSigner
import com.cruxcoach.domain.sharing.InMemoryWrappingKeyStore
import com.cruxcoach.domain.sharing.KeyHandle
import com.cruxcoach.domain.sharing.KeyScope
import com.cruxcoach.domain.sharing.LedgerCrypto
import com.cruxcoach.domain.sharing.ObjectId
import com.cruxcoach.domain.sharing.OwnerPolicySigner
import com.cruxcoach.domain.sharing.PeerId
import com.cruxcoach.domain.sharing.RelationshipStatus
import com.cruxcoach.domain.sharing.SharingCategory
import com.cruxcoach.domain.sharing.SharingCircle
import com.cruxcoach.domain.sharing.SharingLedgerBody
import com.cruxcoach.domain.sharing.SharingLedgerSigner
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FEAT-062 §1.4: the offer has to follow the policy, or nothing is ever
 * shareable by hand.
 *
 * The manual path was broken end to end. Inviting offered `emptySet()` because
 * there is no category picker, and changing a baseline or a person rule only
 * touched the owner-policy ledger — no `GrantChanged` was ever appended. Since
 * a category is only released once it is in `consentedCategories`, and consent
 * is clamped to what was *offered*, a hand-invited peer could accept and still
 * receive nothing, for ever, with no indication why.
 *
 * Enforcement was never wrong: the resolver consults the owner policy first, so
 * an unsynced offer denies rather than over-shares. What was broken is the
 * consent workflow on top of it.
 */
class SharingOfferSyncTest {

    private companion object {
        const val OWNER = "npub1owner"
    }

    private lateinit var dbFile: java.io.File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: SecureDatabase
    private lateinit var repo: SecureDbSharingRepository

    private fun db() = database
    private lateinit var controller: SharingController

    private val alice = PeerId("npub1alice")
    private val bob = PeerId("npub1bob")
    private val phone = DeviceId("dev-phone")

    private class TaggedCrypto(private val identity: String) : LedgerCrypto {
        override fun sign(hash: ByteArray): ByteArray = (identity + ":").encodeToByteArray() + hash
        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
            signature.contentEquals((signerNpub + ":").encodeToByteArray() + hash)
    }

    /** An identity that cannot sign, as an external signer cannot. */
    private object UnavailableCrypto : LedgerCrypto {
        override fun sign(hash: ByteArray): ByteArray? = null
        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) = true
    }

    private fun open(ownerCrypto: LedgerCrypto = TaggedCrypto(OWNER)) {
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}?foreign_keys=on").also { d ->
            SecureDatabasePragmas.STATEMENTS.forEach { runCatching { d.execute(null, it, 0).value } }
        }
        database = SecureDatabase(driver)
        val db = database
        val signer = AsyncSharingLedgerSigner(ownerCrypto.asAsync())
        val policySigner = AsyncOwnerPolicySigner(ownerCrypto.asAsync())
        repo = SecureDbSharingRepository(
            database = db,
            vault = AeadSharingKeyVault(InMemoryWrappingKeyStore()),
            verifier = SharingLedgerSigner(TaggedCrypto(OWNER)).verifier(),
            ownerPolicyVerifier = OwnerPolicySigner(TaggedCrypto(OWNER)).verifier(),
            ownerNpub = OWNER,
            deviceManifestVerifier = DeviceManifestSigner(TaggedCrypto(OWNER)).verifier(),
            attestationVerifier = TestDeviceAuthority.attestationSigner().verifier(),
        )
        controller = SharingController(
            repository = repo,
            signer = signer,
            ownerPolicySigner = policySigner,
            ownerNpub = OWNER,
            authorityDevice = TestDeviceAuthority.DEVICE,
            authorityDevicePublicKey = TestDeviceAuthority.publicKeyOf(),
            attestationSigner = TestDeviceAuthority.attestationSigner(),
            peerSimulator = { peer -> TaggedCrypto(peer.value) },
        )
    }

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-offer-sync-")
        dbFile = tmp.resolve("secure.db").toFile()
        open()
        SecureDatabase.Schema.create(driver)
        TestDeviceAuthority.enrol(repo, TaggedCrypto(OWNER), OWNER)
    }

    @AfterTest
    fun tearDown() {
        runCatching { driver.close() }
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    private fun offered(peer: PeerId) =
        assertNotNull(repo.loadProjection().relationships[peer]).offeredCategories

    private fun pending(peer: PeerId) =
        assertNotNull(repo.loadProjection().relationships[peer]).pendingConsentCategories

    private fun consented(peer: PeerId) =
        assertNotNull(repo.loadProjection().relationships[peer]).consentedCategories

    private fun grantChanges(peer: PeerId) =
        repo.loadLedger(peer).count { it.body is SharingLedgerBody.GrantChanged }

    // ------------------------------------------------------- the happy path

    @Test
    fun `an invited peer is offered what the policy already allows`() = runTest {
        controller.setBaseline(SharingCircle.ALL_OTHER_USERS, SharingCategory.PROFILE_AND_GOALS, true)
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)

        controller.invite(alice, SharingCircle.FRIENDS)

        assertEquals(
            setOf(SharingCategory.PROFILE_AND_GOALS, SharingCategory.VIDEOS),
            offered(alice),
            "an invitation must offer what the circle already grants, not nothing",
        )
        assertEquals(offered(alice), pending(alice))
    }

    @Test
    fun `invite then accept actually releases a category`() = runTest {
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        controller.invite(alice, SharingCircle.FRIENDS)

        assertTrue(controller.simulateAccept(alice, offered(alice), phone))

        assertEquals(setOf(SharingCategory.VIDEOS), consented(alice))
        val row = assertNotNull(controller.peerDetail(alice))
            .categories.first { it.category == SharingCategory.VIDEOS }
        assertEquals(AccessEffect.ALLOW, row.effectiveDecision.effect)
    }

    @Test
    fun `a peer invited to the widest circle is offered only that circle's grants`() = runTest {
        controller.setBaseline(SharingCircle.ALL_OTHER_USERS, SharingCategory.PROFILE_AND_GOALS, true)
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)

        controller.invite(bob, SharingCircle.ALL_OTHER_USERS)

        assertEquals(setOf(SharingCategory.PROFILE_AND_GOALS), offered(bob))
    }

    // --------------------------------------------------------- baseline sync

    @Test
    fun `widening a baseline puts the new category up for consent`() = runTest {
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        controller.invite(alice, SharingCircle.FRIENDS)
        controller.simulateAccept(alice, offered(alice), phone)

        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.HEALTH_INFORMATION, true)

        assertTrue(SharingCategory.HEALTH_INFORMATION in pending(alice))
        assertFalse(SharingCategory.HEALTH_INFORMATION in consented(alice))
        val row = assertNotNull(controller.peerDetail(alice))
            .categories.first { it.category == SharingCategory.HEALTH_INFORMATION }
        assertEquals(DecisionSource.CONSENT_EXPANSION_PENDING, row.effectiveDecision.source)
    }

    @Test
    fun `narrowing a baseline removes the category from consent at once`() = runTest {
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        controller.invite(alice, SharingCircle.FRIENDS)
        controller.simulateAccept(alice, offered(alice), phone)
        assertEquals(setOf(SharingCategory.VIDEOS), consented(alice))

        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, false)

        assertFalse(SharingCategory.VIDEOS in consented(alice))
        assertTrue(offered(alice).isEmpty())
    }

    @Test
    fun `a wider circle's baseline reaches the narrower circles' relationships`() = runTest {
        controller.invite(alice, SharingCircle.FRIENDS)
        controller.invite(bob, SharingCircle.ACQUAINTANCES)

        controller.setBaseline(SharingCircle.ALL_OTHER_USERS, SharingCategory.PROFILE_AND_GOALS, true)

        assertTrue(SharingCategory.PROFILE_AND_GOALS in offered(alice))
        assertTrue(SharingCategory.PROFILE_AND_GOALS in offered(bob))
    }

    @Test
    fun `a narrow circle's baseline does not reach a wider circle's relationship`() = runTest {
        controller.invite(alice, SharingCircle.FRIENDS)
        controller.invite(bob, SharingCircle.ALL_OTHER_USERS)

        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)

        assertTrue(SharingCategory.VIDEOS in offered(alice))
        assertFalse(SharingCategory.VIDEOS in offered(bob))
    }

    // ------------------------------------------------------ person rule sync

    @Test
    fun `a person allow offers that category to that peer only`() = runTest {
        controller.invite(alice, SharingCircle.FRIENDS)
        controller.invite(bob, SharingCircle.FRIENDS)

        controller.setPeerRule(alice, SharingCategory.PRIVATE_NOTES, AccessEffect.ALLOW)

        assertTrue(SharingCategory.PRIVATE_NOTES in offered(alice))
        assertFalse(SharingCategory.PRIVATE_NOTES in offered(bob))
    }

    @Test
    fun `a person deny withdraws the offer for that peer only`() = runTest {
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        controller.invite(alice, SharingCircle.FRIENDS)
        controller.invite(bob, SharingCircle.FRIENDS)
        controller.simulateAccept(alice, offered(alice), phone)

        controller.setPeerRule(alice, SharingCategory.VIDEOS, AccessEffect.DENY)

        assertFalse(SharingCategory.VIDEOS in offered(alice))
        assertFalse(SharingCategory.VIDEOS in consented(alice))
        assertTrue(SharingCategory.VIDEOS in offered(bob))
    }

    @Test
    fun `clearing a person rule falls back to the circle baseline`() = runTest {
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        controller.invite(alice, SharingCircle.FRIENDS)
        controller.setPeerRule(alice, SharingCategory.VIDEOS, AccessEffect.DENY)
        assertFalse(SharingCategory.VIDEOS in offered(alice))

        controller.setPeerRule(alice, SharingCategory.VIDEOS, null)

        assertTrue(SharingCategory.VIDEOS in offered(alice))
    }

    // ------------------------------------------------------ object rule sync

    @Test
    fun `an object allow opens the category even when the person rule denies it`() = runTest {
        controller.invite(alice, SharingCircle.FRIENDS)
        controller.setPeerRule(alice, SharingCategory.VIDEOS, AccessEffect.DENY)
        assertFalse(SharingCategory.VIDEOS in offered(alice))

        controller.setObjectRule(alice, ObjectId("v-1"), SharingCategory.VIDEOS, AccessEffect.ALLOW)

        assertTrue(
            SharingCategory.VIDEOS in offered(alice),
            "one released object means the category has something to consent to",
        )
    }

    @Test
    fun `an object deny alone does not offer the category`() = runTest {
        controller.invite(alice, SharingCircle.FRIENDS)

        controller.setObjectRule(alice, ObjectId("v-1"), SharingCategory.VIDEOS, AccessEffect.DENY)

        assertFalse(SharingCategory.VIDEOS in offered(alice))
    }

    @Test
    fun `clearing an object allow withdraws the offer again`() = runTest {
        controller.invite(alice, SharingCircle.FRIENDS)
        controller.setPeerRule(alice, SharingCategory.VIDEOS, AccessEffect.DENY)
        controller.setObjectRule(alice, ObjectId("v-1"), SharingCategory.VIDEOS, AccessEffect.ALLOW)
        assertTrue(SharingCategory.VIDEOS in offered(alice))

        controller.setObjectRule(alice, ObjectId("v-1"), SharingCategory.VIDEOS, null)

        assertFalse(SharingCategory.VIDEOS in offered(alice))
    }

    // ------------------------------------------------------------ no churn

    @Test
    fun `a policy change that alters nothing appends no grant entry`() = runTest {
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        controller.invite(alice, SharingCircle.FRIENDS)
        val before = grantChanges(alice)

        // Already denied by default; denying it explicitly changes no outcome.
        controller.setPeerRule(alice, SharingCategory.PRIVATE_NOTES, AccessEffect.DENY)

        assertEquals(before, grantChanges(alice), "an unchanged offer must not grow the ledger")
    }

    @Test
    fun `setting the same baseline twice appends only one grant entry`() = runTest {
        controller.invite(alice, SharingCircle.FRIENDS)
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        val after = grantChanges(alice)

        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)

        assertEquals(after, grantChanges(alice))
    }

    @Test
    fun `a revoked relationship is left alone by later policy changes`() = runTest {
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        controller.invite(alice, SharingCircle.FRIENDS)
        controller.revoke(alice)
        val before = grantChanges(alice)

        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.HEALTH_INFORMATION, true)

        assertEquals(before, grantChanges(alice), "a terminal relationship is not re-offered anything")
        assertEquals(
            RelationshipStatus.REVOKED,
            assertNotNull(repo.loadProjection().relationships[alice]).status,
        )
    }

    // -------------------------------------------------------- honest failure

    @Test
    fun `an identity that cannot sign reports failure rather than a silent no-op`() = runTest {
        driver.close()
        open(ownerCrypto = UnavailableCrypto)

        val result = controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)

        assertTrue(result is SharingWriteResult.Failed)
        assertEquals(SharingWriteError.SIGNER_UNAVAILABLE, result.error)
        assertTrue(repo.loadOwnerPolicyLedger().isEmpty(), "nothing may be written when nothing can be signed")
    }

    @Test
    fun `a successful policy change reports success`() = runTest {
        assertTrue(controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true).isSuccess)
        assertTrue(controller.invite(alice, SharingCircle.FRIENDS).isSuccess)
    }

    // ------------------------------------------------- object rule editing

    @Test
    fun `an object exception can be added and appears with its category`() = runTest {
        controller.invite(alice, SharingCircle.FRIENDS)

        assertTrue(controller.setObjectRule(alice, ObjectId("v-1"), SharingCategory.VIDEOS, AccessEffect.DENY).isSuccess)

        val rule = assertNotNull(controller.peerDetail(alice)).objectRules.single()
        assertEquals(ObjectId("v-1"), rule.objectId)
        assertEquals(SharingCategory.VIDEOS, rule.category)
        assertEquals(AccessEffect.DENY, rule.effect)
    }

    @Test
    fun `an object exception can be removed again`() = runTest {
        controller.invite(alice, SharingCircle.FRIENDS)
        controller.setObjectRule(alice, ObjectId("v-1"), SharingCategory.VIDEOS, AccessEffect.DENY)

        controller.setObjectRule(alice, ObjectId("v-1"), SharingCategory.VIDEOS, null)

        assertTrue(assertNotNull(controller.peerDetail(alice)).objectRules.isEmpty())
    }

    @Test
    fun `several object exceptions are kept apart`() = runTest {
        controller.invite(alice, SharingCircle.FRIENDS)
        controller.setObjectRule(alice, ObjectId("v-1"), SharingCategory.VIDEOS, AccessEffect.DENY)
        controller.setObjectRule(alice, ObjectId("n-1"), SharingCategory.PRIVATE_NOTES, AccessEffect.ALLOW)

        val rules = assertNotNull(controller.peerDetail(alice)).objectRules
        assertEquals(2, rules.size)
        assertEquals(
            setOf(SharingCategory.VIDEOS, SharingCategory.PRIVATE_NOTES),
            rules.map { it.category }.toSet(),
        )
    }

    @Test
    fun `an object rule survives a restart with its category intact`() = runTest {
        controller.invite(alice, SharingCircle.FRIENDS)
        controller.setObjectRule(alice, ObjectId("n-1"), SharingCategory.PRIVATE_NOTES, AccessEffect.ALLOW)
        driver.close()

        open()

        val rule = assertNotNull(controller.peerDetail(alice)).objectRules.single()
        assertEquals(SharingCategory.PRIVATE_NOTES, rule.category)
        assertEquals(AccessEffect.ALLOW, rule.effect)
    }

    @Test
    fun `moving a peer to another circle re-syncs what they are offered`() = runTest {
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        controller.invite(alice, SharingCircle.ALL_OTHER_USERS)
        assertFalse(SharingCategory.VIDEOS in offered(alice))

        assertTrue(controller.setPeerCircle(alice, SharingCircle.FRIENDS).isSuccess)

        assertTrue(
            SharingCategory.VIDEOS in offered(alice),
            "a circle change alters what the policy allows, so the offer has to follow",
        )
    }

    @Test
    fun `moving a peer to a wider circle withdraws what the narrower one granted`() = runTest {
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        controller.invite(alice, SharingCircle.FRIENDS)
        controller.simulateAccept(alice, offered(alice), phone)
        assertEquals(setOf(SharingCategory.VIDEOS), consented(alice))

        controller.setPeerCircle(alice, SharingCircle.ALL_OTHER_USERS)

        assertFalse(SharingCategory.VIDEOS in offered(alice))
        assertFalse(SharingCategory.VIDEOS in consented(alice))
    }

    // ------------------------------------- nothing happens without a signature

    /** Rebuilds the controller on an identity that cannot sign, keeping the data. */
    private fun reopenWithoutSigner() {
        driver.close()
        open(ownerCrypto = UnavailableCrypto)
    }

    @Test
    fun `a purge with no usable signer changes nothing at all`() = runTest {
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        controller.invite(alice, SharingCircle.FRIENDS)
        controller.simulateAccept(alice, offered(alice), phone)
        val handle = KeyHandle(KeyScope.CATEGORY, SharingCategory.VIDEOS.name)
        repo.createDataKey(handle)
        val ledgerBefore = repo.loadLedger(alice).size

        reopenWithoutSigner()
        val result = controller.purge(alice)

        val failed = assertIs<SharingWriteResult.Failed>(result, "a purge that cannot be recorded must not run")
        assertEquals(SharingWriteError.SIGNER_UNAVAILABLE, failed.error)
        assertNotNull(repo.loadProjection().relationships[alice], "the relationship row must survive")
        assertEquals(ledgerBefore, repo.loadLedger(alice).size, "no entry may be appended")
        assertEquals(0L, db().sharingQueries.countTombstones().executeAsOne(), "no tombstone may be written")
        assertNotNull(repo.readWrappedKey(handle), "the key must not be destroyed")
    }

    @Test
    fun `a revoke with no usable signer reports failure and leaves the state alone`() = runTest {
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        controller.invite(alice, SharingCircle.FRIENDS)
        controller.simulateAccept(alice, offered(alice), phone)

        reopenWithoutSigner()
        val result = controller.revoke(alice)

        assertTrue(result is SharingWriteResult.Failed)
        assertEquals(
            RelationshipStatus.ACCEPTED,
            assertNotNull(repo.loadProjection().relationships[alice]).status,
        )
    }

    @Test
    fun `revoking a device with no usable signer reports failure and revokes nothing`() = runTest {
        controller.invite(alice, SharingCircle.FRIENDS)
        controller.simulateAccept(alice, emptySet(), phone)

        reopenWithoutSigner()
        val result = controller.revokeDevice(alice, phone)

        assertTrue(result is SharingWriteResult.Failed)
        assertTrue(
            phone in assertNotNull(repo.loadProjection().relationships[alice]).authorisedDevices,
            "the device must still be authorised",
        )
    }

    @Test
    fun `changing a circle with no usable signer reports failure and keeps the old circle`() = runTest {
        controller.invite(alice, SharingCircle.ALL_OTHER_USERS)

        reopenWithoutSigner()
        val result = controller.setPeerCircle(alice, SharingCircle.FRIENDS)

        assertTrue(result is SharingWriteResult.Failed)
        assertEquals(
            SharingCircle.ALL_OTHER_USERS,
            assertNotNull(repo.loadProjection().relationships[alice]).circle,
        )
    }

    @Test
    fun `a restore with no usable signer advances no device generation`() = runTest {
        controller.invite(alice, SharingCircle.FRIENDS)
        controller.invite(bob, SharingCircle.FRIENDS)
        val code = controller.newRecoveryCode()
        val before = repo.loadProjection().relationships.mapValues { it.value.deviceGeneration }

        reopenWithoutSigner()
        val result = controller.advanceDeviceGenerations(code, code)

        assertTrue(result is SharingWriteResult.Failed)
        assertEquals(
            before,
            repo.loadProjection().relationships.mapValues { it.value.deviceGeneration },
            "a restore that cannot be signed must not half-apply across peers",
        )
        assertFalse(repo.loadProjection().relationships.values.any { it.awaitingRevokeSync })
    }

    @Test
    fun `a wrong recovery code is refused before anything is signed`() = runTest {
        controller.invite(alice, SharingCircle.FRIENDS)
        val code = controller.newRecoveryCode()
        val before = repo.loadLedger(alice).size

        val result = controller.advanceDeviceGenerations("WRONG-CODE-HERE-XXXX-YYYY-ZZZZ", code)

        assertTrue(result is SharingWriteResult.Failed)
        assertEquals(before, repo.loadLedger(alice).size)
    }

    @Test
    fun `a correct recovery code restores every relationship`() = runTest {
        controller.invite(alice, SharingCircle.FRIENDS)
        controller.invite(bob, SharingCircle.FRIENDS)
        val code = controller.newRecoveryCode()

        assertTrue(controller.advanceDeviceGenerations(code, code).isSuccess)

        repo.loadProjection().relationships.values.forEach {
            assertEquals(2L, it.deviceGeneration)
            assertTrue(it.awaitingRevokeSync)
        }
    }

    @Test
    fun `a successful purge still removes everything`() = runTest {
        controller.invite(alice, SharingCircle.FRIENDS)

        val result = controller.purge(alice)

        assertTrue(result.isSuccess)
        assertNull(repo.loadProjection().relationships[alice])
        assertEquals(1L, db().sharingQueries.countTombstones().executeAsOne())
    }
}
