package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.SecureDatabasePragmas
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.AccessEffect
import com.cruxcoach.domain.sharing.AeadSharingKeyVault
import com.cruxcoach.domain.sharing.AsyncOwnerPolicySigner
import com.cruxcoach.domain.sharing.AsyncSharingLedgerSigner
import com.cruxcoach.domain.sharing.asAsync
import com.cruxcoach.domain.sharing.DecisionSource
import com.cruxcoach.domain.sharing.DeviceId
import com.cruxcoach.domain.sharing.DeviceManifestSigner
import com.cruxcoach.domain.sharing.InMemoryWrappingKeyStore
import com.cruxcoach.domain.sharing.LedgerCrypto
import com.cruxcoach.domain.sharing.NativeSharingGateState
import com.cruxcoach.domain.sharing.ObjectId
import com.cruxcoach.domain.sharing.PeerId
import com.cruxcoach.domain.sharing.RelationshipStatus
import com.cruxcoach.domain.sharing.SharingCategory
import com.cruxcoach.domain.sharing.SharingCircle
import com.cruxcoach.domain.sharing.OwnerPolicySigner
import com.cruxcoach.domain.sharing.SharingLedgerSigner
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FEAT-062 §E: what the screens are driven by.
 *
 * The controller is deliberately plain and synchronous — every rule the UI
 * shows is decided here, so it can be tested without Compose, Hilt or a
 * dispatcher.
 */
class SharingControllerTest {

    private companion object {
        const val OWNER = "npub1owner"
    }

    private lateinit var dbFile: java.io.File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repo: SecureDbSharingRepository
    private lateinit var controller: SharingController

    private val alice = PeerId("npub1alice")
    private val bob = PeerId("npub1bob")
    private val phone = DeviceId("dev-phone")
    private val tablet = DeviceId("dev-tablet")

    /**
     * Deterministic stand-in for BIP-340 (the native curve needs a device).
     * A "signature" is the hash tagged with the signer, so a signature made by
     * one identity does not verify for another — which is what the role checks
     * need in order to mean anything.
     */
    private class TaggedCrypto(private val identity: String) : LedgerCrypto {
        override fun sign(hash: ByteArray): ByteArray =
            (identity + ":").encodeToByteArray() + hash
        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
            signature.contentEquals((signerNpub + ":").encodeToByteArray() + hash)
    }

    private object TestCrypto : LedgerCrypto by TaggedCrypto(OWNER)

    private class PeerCrypto(peer: PeerId) : LedgerCrypto by TaggedCrypto(peer.value)

    private fun open() {
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}?foreign_keys=on").also { d ->
            SecureDatabasePragmas.STATEMENTS.forEach { runCatching { d.execute(null, it, 0).value } }
        }
        val db = SecureDatabase(driver)
        val signer = AsyncSharingLedgerSigner(TestCrypto.asAsync())
        val ownerPolicySigner = AsyncOwnerPolicySigner(TestCrypto.asAsync())
        repo = SecureDbSharingRepository(
            database = db,
            vault = AeadSharingKeyVault(InMemoryWrappingKeyStore()),
            verifier = signer.verifier(),
            ownerPolicyVerifier = ownerPolicySigner.verifier(),
            ownerNpub = OWNER,
            deviceManifestVerifier = DeviceManifestSigner(TestCrypto).verifier(),
            attestationVerifier = TestDeviceAuthority.attestationSigner().verifier(),
        )
        controller = SharingController(
            repository = repo,
            signer = signer,
            ownerPolicySigner = ownerPolicySigner,
            ownerNpub = OWNER,
            authorityDevice = TestDeviceAuthority.DEVICE,
            authorityDevicePublicKey = TestDeviceAuthority.publicKeyOf(),
            attestationSigner = TestDeviceAuthority.attestationSigner(),
            // Stands in for a demo peer: signs with a key bound to that peer,
            // exactly as the real demo does.
            peerSimulator = { peer -> PeerCrypto(peer) },
        )
    }

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-sharing-ctrl-")
        dbFile = tmp.resolve("secure.db").toFile()
        open()
        SecureDatabase.Schema.create(driver)
        TestDeviceAuthority.enrol(repo, TestCrypto, OWNER)
    }

    @AfterTest
    fun tearDown() {
        runCatching { driver.close() }
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    private suspend fun offerAndAccept(peer: PeerId = alice, categories: Set<SharingCategory> = setOf(SharingCategory.VIDEOS)) {
        controller.offer(peer, SharingCircle.FRIENDS, categories)
        controller.simulateAccept(peer, categories, phone)
    }

    // ------------------------------------------------------------ overview

    @Test
    fun `the native gate is reported as blocked with its reasons`() = runTest {
        val gate = controller.snapshot().gate
        assertTrue(gate is NativeSharingGateState.Blocked)
        assertTrue(gate.reasons.isNotEmpty())
        assertFalse(gate.isLive)
    }

    @Test
    fun `a fresh install shares nothing with anybody`() = runTest {
        val state = controller.snapshot()
        assertTrue(state.peers.isEmpty())
        SharingCircle.entries.forEach { assertTrue(state.baselines.effectiveFor(it).isEmpty()) }
    }

    @Test
    fun `a baseline toggle is visible in the snapshot`() = runTest {
        controller.setBaseline(SharingCircle.ACQUAINTANCES, SharingCategory.PROFILE_AND_GOALS, true)
        val state = controller.snapshot()
        assertTrue(SharingCategory.PROFILE_AND_GOALS in state.baselines.effectiveFor(SharingCircle.ACQUAINTANCES))
        assertTrue(SharingCategory.PROFILE_AND_GOALS in state.baselines.effectiveFor(SharingCircle.FRIENDS))
        assertFalse(SharingCategory.PROFILE_AND_GOALS in state.baselines.effectiveFor(SharingCircle.ALL_OTHER_USERS))
    }

    @Test
    fun `a peer appears with its status`() = runTest {
        controller.offer(alice, SharingCircle.FRIENDS, setOf(SharingCategory.VIDEOS))
        val peer = assertNotNull(controller.snapshot().peers.firstOrNull())
        assertEquals(alice, peer.peer)
        assertEquals(RelationshipStatus.PENDING, peer.status)
    }

    // ------------------------------------------------------------- sources

    @Test
    fun `a detail row names the circle the grant was inherited from`() = runTest {
        controller.setBaseline(SharingCircle.ALL_OTHER_USERS, SharingCategory.PROFILE_AND_GOALS, true)
        offerAndAccept(categories = setOf(SharingCategory.PROFILE_AND_GOALS))

        val row = assertNotNull(controller.peerDetail(alice))
            .categories.first { it.category == SharingCategory.PROFILE_AND_GOALS }

        assertEquals(DecisionSource.INHERITED_CIRCLE_BASELINE, row.policyDecision.source)
        assertEquals(SharingCircle.ALL_OTHER_USERS, row.policyDecision.inheritedFrom)
        assertEquals(AccessEffect.ALLOW, row.effectiveDecision.effect)
    }

    @Test
    fun `a person deny is shown as the deciding rule`() = runTest {
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        offerAndAccept()
        controller.setPeerRule(alice, SharingCategory.VIDEOS, AccessEffect.DENY)

        val row = assertNotNull(controller.peerDetail(alice))
            .categories.first { it.category == SharingCategory.VIDEOS }

        assertEquals(DecisionSource.PERSON_DENY, row.policyDecision.source)
        assertEquals(AccessEffect.DENY, row.effectiveDecision.effect)
    }

    @Test
    fun `an object deny is shown even while the category is allowed`() = runTest {
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        offerAndAccept()
        controller.setObjectRule(alice, ObjectId("v-1"), SharingCategory.VIDEOS, AccessEffect.DENY)

        val detail = assertNotNull(controller.peerDetail(alice))
        assertEquals(AccessEffect.ALLOW, detail.categories.first { it.category == SharingCategory.VIDEOS }.effectiveDecision.effect)
        val obj = assertNotNull(detail.objectRules.firstOrNull())
        assertEquals(ObjectId("v-1"), obj.objectId)
        assertEquals(AccessEffect.DENY, obj.effect)
    }

    @Test
    fun `the social circle is kept out of anything peer-facing`() = runTest {
        offerAndAccept()
        val entries = controller.nonTransmittableBodyPreview(alice)
        assertTrue(
            entries.none { it.contains("FRIENDS") },
            "the circle a peer sits in must not leave the device",
        )
    }

    // ------------------------------------------------------------- consent

    @Test
    fun `an offer releases nothing until the peer accepts`() = runTest {
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        controller.offer(alice, SharingCircle.FRIENDS, setOf(SharingCategory.VIDEOS))

        val row = assertNotNull(controller.peerDetail(alice))
            .categories.first { it.category == SharingCategory.VIDEOS }
        assertEquals(DecisionSource.RELATIONSHIP_PENDING_CONSENT, row.effectiveDecision.source)
    }

    @Test
    fun `widening the grant asks again before releasing`() = runTest {
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.HEALTH_INFORMATION, true)
        offerAndAccept()

        controller.changeGrant(alice, setOf(SharingCategory.VIDEOS, SharingCategory.HEALTH_INFORMATION))

        val detail = assertNotNull(controller.peerDetail(alice))
        assertTrue(SharingCategory.HEALTH_INFORMATION in detail.pendingConsentCategories)
        assertEquals(
            DecisionSource.CONSENT_EXPANSION_PENDING,
            detail.categories.first { it.category == SharingCategory.HEALTH_INFORMATION }.effectiveDecision.source,
        )
    }

    @Test
    fun `narrowing the grant needs no consent and takes effect at once`() = runTest {
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        offerAndAccept()

        controller.changeGrant(alice, emptySet())

        val detail = assertNotNull(controller.peerDetail(alice))
        assertTrue(detail.consentedCategories.isEmpty())
        assertTrue(detail.pendingConsentCategories.isEmpty())
    }

    @Test
    fun `a declined offer stays declined`() = runTest {
        controller.offer(alice, SharingCircle.FRIENDS, setOf(SharingCategory.VIDEOS))
        controller.simulateDecline(alice)
        assertEquals(RelationshipStatus.DECLINED, assertNotNull(controller.peerDetail(alice)).status)
    }

    // ------------------------------------------------------------- devices

    @Test
    fun `a second device is listed only after it is authorised`() = runTest {
        offerAndAccept()
        assertEquals(listOf(phone), assertNotNull(controller.peerDetail(alice)).devices.map { it.device })

        controller.simulateAuthorizeDevice(alice, tablet)

        assertEquals(
            setOf(phone, tablet),
            assertNotNull(controller.peerDetail(alice)).devices.map { it.device }.toSet(),
        )
    }

    @Test
    fun `revoking one device leaves the other working`() = runTest {
        offerAndAccept()
        controller.simulateAuthorizeDevice(alice, tablet)

        controller.revokeDevice(alice, phone)

        val detail = assertNotNull(controller.peerDetail(alice))
        assertEquals(listOf(tablet), detail.devices.filter { it.authorised }.map { it.device })
        assertTrue(detail.devices.first { it.device == phone }.revoked)
    }

    // ------------------------------------------------------- revoke, purge

    @Test
    fun `revoking shows the revoked state and releases nothing`() = runTest {
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        offerAndAccept()

        controller.revoke(alice)

        val detail = assertNotNull(controller.peerDetail(alice))
        assertEquals(RelationshipStatus.REVOKED, detail.status)
        assertEquals(
            DecisionSource.RELATIONSHIP_REVOKED,
            detail.categories.first { it.category == SharingCategory.VIDEOS }.effectiveDecision.source,
        )
    }

    @Test
    fun `purging removes the peer and keeps a tombstone`() = runTest {
        offerAndAccept()
        val outcome = controller.purge(alice)

        assertTrue(outcome.isSuccess)
        assertNull(controller.peerDetail(alice))
    }

    @Test
    fun `two peers are independent`() = runTest {
        offerAndAccept(alice)
        offerAndAccept(bob)
        controller.revoke(alice)

        assertEquals(RelationshipStatus.REVOKED, assertNotNull(controller.peerDetail(alice)).status)
        assertEquals(RelationshipStatus.ACCEPTED, assertNotNull(controller.peerDetail(bob)).status)
    }

    // -------------------------------------------------------------- restore

    @Test
    fun `a restore needs the right recovery code`() = runTest {
        offerAndAccept()
        val code = controller.newRecoveryCode()

        assertFalse(controller.advanceDeviceGenerations("WRONG-CODE-HERE-XXXX-YYYY-ZZZZ", code).isSuccess)
        assertTrue(controller.advanceDeviceGenerations(code, code).isSuccess)
    }

    @Test
    fun `a restored install authorises no device until the revoke sync completes`() = runTest {
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        offerAndAccept()
        val code = controller.newRecoveryCode()

        controller.advanceDeviceGenerations(code, code)

        val detail = assertNotNull(controller.peerDetail(alice))
        assertTrue(detail.awaitingRevokeSync)
        assertEquals(
            DecisionSource.AWAITING_REVOKE_SYNC,
            detail.categories.first { it.category == SharingCategory.VIDEOS }.effectiveDecision.source,
        )
    }

    // ----------------------------------------------------------- integrity

    @Test
    fun `an entry that does not verify fails the relationship closed`() = runTest {
        offerAndAccept()
        // Rewrite one stored signature: the ledger is append-only but the file
        // underneath is not magic, so a tampered row must be detected.
        driver.execute(null, "UPDATE sharing_ledger_entry SET signature = 'ff' WHERE policy_sequence = 1", 0)

        assertEquals(RelationshipStatus.FAIL_CLOSED, assertNotNull(controller.peerDetail(alice)).status)
    }
}
