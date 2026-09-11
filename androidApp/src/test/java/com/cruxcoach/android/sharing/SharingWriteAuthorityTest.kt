package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.SecureDatabasePragmas
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.AccessEffect
import com.cruxcoach.domain.sharing.AeadSharingKeyVault
import com.cruxcoach.domain.sharing.AsyncOwnerPolicySigner
import com.cruxcoach.domain.sharing.AsyncSharingLedgerSigner
import com.cruxcoach.domain.sharing.asAsync
import com.cruxcoach.domain.sharing.DeviceId
import com.cruxcoach.domain.sharing.DeviceManifestSigner
import com.cruxcoach.domain.sharing.InMemoryWrappingKeyStore
import com.cruxcoach.domain.sharing.LedgerCrypto
import com.cruxcoach.domain.sharing.LedgerEntryId
import com.cruxcoach.domain.sharing.OwnerPolicyBody
import com.cruxcoach.domain.sharing.OwnerPolicyEntry
import com.cruxcoach.domain.sharing.OwnerPolicySigner
import com.cruxcoach.domain.sharing.PeerId
import com.cruxcoach.domain.sharing.RelationshipStatus
import com.cruxcoach.domain.sharing.SharingCategory
import com.cruxcoach.domain.sharing.SharingCircle
import com.cruxcoach.domain.sharing.SharingLedgerBody
import com.cruxcoach.domain.sharing.SharingLedgerEntry
import com.cruxcoach.domain.sharing.SharingLedgerSigner
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FEAT-062 §2 + §5: every permission change on the way to disk is a signed
 * ledger append, and nothing else can move one.
 *
 * Baselines and per-person/per-object rules used to be written straight into
 * mutable rows. These tests pin the replacement: the controller has no
 * unsigned path, the stored policy is reconstructed from the ledger, the
 * cache is rewritten in the same transaction, and the owner cannot sign a
 * recipient's consent.
 */
class SharingWriteAuthorityTest {

    private companion object {
        const val OWNER = "npub1owner"
    }

    private lateinit var dbFile: java.io.File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: SecureDatabase
    private lateinit var repo: SecureDbSharingRepository
    private lateinit var controller: SharingController

    private val alice = PeerId("npub1alice")
    private val phone = DeviceId("dev-phone")

    /** Signature = identity tag + hash, so one identity's signature is not another's. */
    private class TaggedCrypto(private val identity: String) : LedgerCrypto {
        override fun sign(hash: ByteArray): ByteArray = (identity + ":").encodeToByteArray() + hash
        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
            signature.contentEquals((signerNpub + ":").encodeToByteArray() + hash)
    }

    private fun open(withSimulator: Boolean = true) {
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}?foreign_keys=on").also { d ->
            SecureDatabasePragmas.STATEMENTS.forEach { runCatching { d.execute(null, it, 0).value } }
        }
        db = SecureDatabase(driver)
        val ownerCrypto = TaggedCrypto(OWNER)
        val signer = AsyncSharingLedgerSigner(ownerCrypto.asAsync())
        val policySigner = AsyncOwnerPolicySigner(ownerCrypto.asAsync())
        repo = SecureDbSharingRepository(
            database = db,
            vault = AeadSharingKeyVault(InMemoryWrappingKeyStore()),
            verifier = signer.verifier(),
            ownerPolicyVerifier = policySigner.verifier(),
            ownerNpub = OWNER,
            deviceManifestVerifier = DeviceManifestSigner(ownerCrypto).verifier(),
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
            peerSimulator = if (withSimulator) {
                PeerSimulator { peer -> TaggedCrypto(peer.value) }
            } else {
                null
            },
        )
    }

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-sharing-authority-")
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

    // --------------------------------------------- policy goes via the ledger

    @Test
    fun `setting a baseline appends exactly one signed owner entry`() = runTest {
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)

        val ledger = repo.loadOwnerPolicyLedger()
        assertEquals(1, ledger.size)
        assertEquals(OWNER, ledger.single().signerNpub)
        assertTrue(ledger.single().signature.isNotEmpty())
    }

    @Test
    fun `every policy change is kept as history rather than overwritten`() = runTest {
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, false)
        controller.setPeerRule(alice, SharingCategory.VIDEOS, AccessEffect.DENY)

        assertEquals(3, repo.loadOwnerPolicyLedger().size)
        assertEquals(listOf(1L, 2L, 3L), repo.loadOwnerPolicyLedger().map { it.policySequence })
    }

    @Test
    fun `the policy is reconstructed from the ledger after a restart`() = runTest {
        controller.setBaseline(SharingCircle.ACQUAINTANCES, SharingCategory.TRAINING_HISTORY, true)
        controller.setPeerRule(alice, SharingCategory.VIDEOS, AccessEffect.DENY)
        driver.close()

        open()

        val policy = repo.loadPolicy()
        assertTrue(SharingCategory.TRAINING_HISTORY in policy.baselines.explicitFor(SharingCircle.ACQUAINTANCES))
        assertEquals(AccessEffect.DENY, policy.peers[alice]?.categoryRules?.get(SharingCategory.VIDEOS))
    }

    @Test
    fun `the cache rows are rewritten in step with the ledger`() = runTest {
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        assertEquals(1, db.sharingQueries.selectBaselines().executeAsList().size)

        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, false)
        assertTrue(db.sharingQueries.selectBaselines().executeAsList().isEmpty())
    }

    @Test
    fun `a tampered owner entry fails the whole policy closed`() = runTest {
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        driver.execute(null, "UPDATE sharing_owner_policy_entry SET signature = 'ff'", 0)

        val state = repo.loadOwnerPolicyState()
        assertNotNull(state.failClosedReason)
        assertTrue(
            repo.loadPolicy().baselines.effectiveFor(SharingCircle.FRIENDS).isEmpty(),
            "a policy that cannot be verified grants nothing",
        )
    }

    @Test
    fun `an owner entry from somebody else is refused before it is stored`() = runTest {
        // Something with the app's own code path, but not the owner's key.
        // Admission refuses it, so it never reaches the ledger at all — the
        // policy stays usable rather than being fail-closed for good.
        val forged = OwnerPolicyEntry(
            id = LedgerEntryId("forged"),
            policySequence = 1,
            authorityGeneration = 1,
            signerNpub = "npub1mallory",
            signature = "whatever",
            body = OwnerPolicyBody.CircleBaselineSet(
                SharingCircle.ALL_OTHER_USERS, SharingCategory.HEALTH_INFORMATION, true,
            ),
        )

        assertFalse(repo.tryAppendOwnerPolicyEntry(forged))
        assertFailsWith<IllegalStateException> { repo.appendOwnerPolicyEntry(forged) }

        assertTrue(repo.loadOwnerPolicyLedger().isEmpty(), "a refused owner entry leaves no row")
        assertNull(repo.loadOwnerPolicyState().failClosedReason)
    }

    @Test
    fun `a gap in the owner ledger fails the policy closed`() = runTest {
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        driver.execute(null, "UPDATE sharing_owner_policy_entry SET policy_sequence = 9", 0)

        assertNotNull(repo.loadOwnerPolicyState().failClosedReason)
    }

    @Test
    fun `an owner entry the build cannot parse fails the policy closed`() = runTest {
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        driver.execute(null, "UPDATE sharing_owner_policy_entry SET body_kind = 'cc.owner.FromTheFuture'", 0)

        assertNotNull(repo.loadOwnerPolicyState().failClosedReason)
        assertTrue(repo.loadPolicy().baselines.effectiveFor(SharingCircle.FRIENDS).isEmpty())
    }

    // ----------------------------------------------------- consent authority

    @Test
    fun `the controller cannot forge an acceptance with the owner key`() = runTest {
        controller.offer(alice, SharingCircle.FRIENDS, setOf(SharingCategory.VIDEOS))

        // Build the acceptance exactly as an owner-side forgery would: the
        // owner's signer over a recipient body.
        val forged = SharingLedgerSigner(TaggedCrypto(OWNER)).sign(
            SharingLedgerEntry(
                id = LedgerEntryId("forged"),
                peer = alice,
                // A reply names the entry it answers; the ledger is a DAG.
                policySequence = (repo.authorisedHead(alice)?.policySequence ?: 0L) + 1L,
                parent = repo.authorisedHead(alice)?.id,
                authorityGeneration = 1,
                resourceEpoch = 1,
                deviceGeneration = 1,
                signerNpub = OWNER,
                signature = "",
                body = SharingLedgerBody.RecipientAccepted(setOf(SharingCategory.VIDEOS), phone),
            )
        )!!

        assertFalse(controller.ingestPeerSignedEntry(forged), "an owner-signed acceptance must be refused")
        assertEquals(RelationshipStatus.PENDING, assertNotNull(controller.peerDetail(alice)).status)
    }

    @Test
    fun `an acceptance signed by the peer is ingested`() = runTest {
        controller.offer(alice, SharingCircle.FRIENDS, setOf(SharingCategory.VIDEOS))

        val genuine = SharingLedgerSigner(TaggedCrypto(alice.value)).sign(
            SharingLedgerEntry(
                id = LedgerEntryId("genuine"),
                peer = alice,
                // A reply names the entry it answers; the ledger is a DAG.
                policySequence = (repo.authorisedHead(alice)?.policySequence ?: 0L) + 1L,
                parent = repo.authorisedHead(alice)?.id,
                authorityGeneration = 1,
                resourceEpoch = 1,
                deviceGeneration = 1,
                signerNpub = alice.value,
                signature = "",
                body = SharingLedgerBody.RecipientAccepted(setOf(SharingCategory.VIDEOS), phone),
            )
        )!!

        assertTrue(controller.ingestPeerSignedEntry(genuine))
        assertEquals(RelationshipStatus.ACCEPTED, assertNotNull(controller.peerDetail(alice)).status)
    }

    @Test
    fun `without a peer simulator nothing can accept on the peer's behalf`() = runTest {
        driver.close()
        open(withSimulator = false)

        controller.offer(alice, SharingCircle.FRIENDS, setOf(SharingCategory.VIDEOS))

        assertFalse(controller.canSimulatePeer(alice))
        assertFalse(controller.simulateAccept(alice, setOf(SharingCategory.VIDEOS), phone))
        assertEquals(RelationshipStatus.PENDING, assertNotNull(controller.peerDetail(alice)).status)
    }

    @Test
    fun `a simulated acceptance is a real peer signature and survives verification`() = runTest {
        controller.offer(alice, SharingCircle.FRIENDS, setOf(SharingCategory.VIDEOS))
        assertTrue(controller.simulateAccept(alice, setOf(SharingCategory.VIDEOS), phone))

        val accepted = repo.loadLedger(alice).single { it.body is SharingLedgerBody.RecipientAccepted }
        assertEquals(alice.value, accepted.signerNpub)
        assertEquals(RelationshipStatus.ACCEPTED, assertNotNull(controller.peerDetail(alice)).status)
    }

    @Test
    fun `a person with an object rule can still be removed`() = runTest {
        controller.offer(alice, SharingCircle.FRIENDS, setOf(SharingCategory.VIDEOS))
        controller.setObjectRule(alice, com.cruxcoach.domain.sharing.ObjectId("v-1"), SharingCategory.VIDEOS, AccessEffect.DENY)

        val outcome = controller.purge(alice)

        assertTrue(outcome.isSuccess)
        assertNull(controller.peerDetail(alice))
    }

    // ------------------------------------------------ device authorisation

    @Test
    fun `the controller has no way to authorise a device with the owner key`() = runTest {
        controller.offer(alice, SharingCircle.FRIENDS, setOf(SharingCategory.VIDEOS))
        controller.simulateAccept(alice, setOf(SharingCategory.VIDEOS), phone)

        val ownerSigned = SharingLedgerSigner(TaggedCrypto(OWNER)).sign(
            SharingLedgerEntry(
                id = LedgerEntryId("owner-device"),
                peer = alice,
                // A reply names the entry it answers; the ledger is a DAG.
                policySequence = (repo.authorisedHead(alice)?.policySequence ?: 0L) + 1L,
                parent = repo.authorisedHead(alice)?.id,
                authorityGeneration = 1,
                resourceEpoch = 1,
                deviceGeneration = 1,
                signerNpub = OWNER,
                signature = "",
                body = SharingLedgerBody.DeviceAuthorized(DeviceId("dev-tablet")),
            )
        )!!

        assertFalse(controller.ingestPeerSignedEntry(ownerSigned))
        assertFalse(repo.tryAppendEntry(ownerSigned), "admission must refuse an owner-signed device authorisation")
        assertEquals(
            listOf(phone),
            assertNotNull(controller.peerDetail(alice)).devices.filter { it.authorised }.map { it.device },
        )
    }

    @Test
    fun `a peer-signed device authorisation is accepted`() = runTest {
        controller.offer(alice, SharingCircle.FRIENDS, setOf(SharingCategory.VIDEOS))
        controller.simulateAccept(alice, setOf(SharingCategory.VIDEOS), phone)

        assertTrue(controller.simulateAuthorizeDevice(alice, DeviceId("dev-tablet")))

        val devices = assertNotNull(controller.peerDetail(alice)).devices.filter { it.authorised }.map { it.device }
        assertEquals(setOf(phone, DeviceId("dev-tablet")), devices.toSet())
    }

    @Test
    fun `without a simulator no device can be authorised at all`() = runTest {
        controller.offer(alice, SharingCircle.FRIENDS, setOf(SharingCategory.VIDEOS))
        controller.simulateAccept(alice, setOf(SharingCategory.VIDEOS), phone)
        driver.close()
        open(withSimulator = false)

        assertFalse(controller.canSimulatePeer(alice))
        assertFalse(controller.simulateAuthorizeDevice(alice, DeviceId("dev-tablet")))
        assertEquals(
            listOf(phone),
            assertNotNull(controller.peerDetail(alice)).devices.filter { it.authorised }.map { it.device },
        )
    }

    @Test
    fun `a revoked device stays revoked even when the peer signs it again`() = runTest {
        controller.offer(alice, SharingCircle.FRIENDS, setOf(SharingCategory.VIDEOS))
        controller.simulateAccept(alice, setOf(SharingCategory.VIDEOS), phone)
        controller.simulateAuthorizeDevice(alice, DeviceId("dev-tablet"))
        controller.revokeDevice(alice, DeviceId("dev-tablet"))

        controller.simulateAuthorizeDevice(alice, DeviceId("dev-tablet"))

        val detail = assertNotNull(controller.peerDetail(alice))
        assertTrue(detail.devices.first { it.device == DeviceId("dev-tablet") }.revoked)
        assertFalse(detail.devices.first { it.device == DeviceId("dev-tablet") }.authorised)
    }

    // ------------------------------------------------- inbound admission

    /** Defaults to the sequence the ledger actually expects next. */
    private fun peerEntry(
        id: String,
        body: SharingLedgerBody,
        // A reply names the entry it answers; the ledger is a DAG.
        parent: LedgerEntryId? = repo.authorisedHead(alice)?.id,
        seq: Long = (repo.authorisedHead(alice)?.policySequence ?: 0L) + 1L,
        devGen: Long = 1,
    ) =
        SharingLedgerSigner(TaggedCrypto(alice.value)).sign(
            SharingLedgerEntry(
                id = LedgerEntryId(id),
                peer = alice,
                policySequence = seq,
                parent = parent,
                authorityGeneration = 1,
                resourceEpoch = 1,
                deviceGeneration = devGen,
                signerNpub = alice.value,
                signature = "",
                body = body,
            )
        )!!

    private fun storedRows(): Long =
        db.sharingQueries.selectLedgerForPeer(alice.value).executeAsList().size.toLong()

    @Test
    fun `an inbound entry with a broken signature is refused and stores nothing`() = runTest {
        controller.offer(alice, SharingCircle.FRIENDS, setOf(SharingCategory.VIDEOS))
        val before = storedRows()

        val tampered = peerEntry("in1", SharingLedgerBody.RecipientAccepted(setOf(SharingCategory.VIDEOS), phone))
            .copy(signature = "ff")

        assertFalse(controller.ingestPeerSignedEntry(tampered))
        assertEquals(before, storedRows(), "a refused entry must leave no row")
        assertEquals(RelationshipStatus.PENDING, assertNotNull(controller.peerDetail(alice)).status)
    }

    @Test
    fun `an inbound entry that skips a sequence is refused and stores nothing`() = runTest {
        controller.offer(alice, SharingCircle.FRIENDS, setOf(SharingCategory.VIDEOS))
        val before = storedRows()

        val gapped = peerEntry(
            "in2",
            SharingLedgerBody.RecipientAccepted(setOf(SharingCategory.VIDEOS), phone),
            seq = repo.nextSequence(alice) + 7,
        )

        assertFalse(controller.ingestPeerSignedEntry(gapped))
        assertEquals(before, storedRows())
    }

    @Test
    fun `an inbound entry with a stale device generation is refused and stores nothing`() = runTest {
        controller.offer(alice, SharingCircle.FRIENDS, setOf(SharingCategory.VIDEOS))
        val before = storedRows()

        // Correct sequence, wrong device generation — so this is genuinely the
        // generation rule being tested and not a gap rejection in disguise.
        val stale = peerEntry(
            "in3",
            SharingLedgerBody.RecipientAccepted(setOf(SharingCategory.VIDEOS), phone),
            devGen = 7,
        )

        assertFalse(controller.ingestPeerSignedEntry(stale))
        assertEquals(before, storedRows())
    }

    @Test
    fun `an inbound entry reusing a stored id with other content is refused`() = runTest {
        controller.offer(alice, SharingCircle.FRIENDS, setOf(SharingCategory.VIDEOS))
        val genuine = peerEntry("in4", SharingLedgerBody.RecipientAccepted(setOf(SharingCategory.VIDEOS), phone))
        assertTrue(controller.ingestPeerSignedEntry(genuine))
        val before = storedRows()

        val divergent = peerEntry("in4", SharingLedgerBody.RecipientDeclined)

        assertFalse(controller.ingestPeerSignedEntry(divergent))
        assertEquals(before, storedRows())
        assertEquals(RelationshipStatus.ACCEPTED, assertNotNull(controller.peerDetail(alice)).status)
    }

    @Test
    fun `an inbound entry that is byte-identical to a stored one is idempotent`() = runTest {
        controller.offer(alice, SharingCircle.FRIENDS, setOf(SharingCategory.VIDEOS))
        val genuine = peerEntry("in5", SharingLedgerBody.RecipientAccepted(setOf(SharingCategory.VIDEOS), phone))
        assertTrue(controller.ingestPeerSignedEntry(genuine))
        val before = storedRows()

        assertTrue(controller.ingestPeerSignedEntry(genuine), "a replay is not an error")
        assertEquals(before, storedRows(), "and it does not store a second row")
    }

    @Test
    fun `a refused inbound entry cannot permanently fail-close the relationship`() = runTest {
        controller.offer(alice, SharingCircle.FRIENDS, setOf(SharingCategory.VIDEOS))

        // A hostile message that would fail-close the ledger if it were stored.
        assertFalse(
            controller.ingestPeerSignedEntry(
                peerEntry("hostile", SharingLedgerBody.RecipientAccepted(setOf(SharingCategory.VIDEOS), phone))
                    .copy(signature = "00")
            )
        )

        // The genuine acceptance that arrives afterwards still works.
        assertTrue(controller.simulateAccept(alice, setOf(SharingCategory.VIDEOS), phone))
        assertEquals(RelationshipStatus.ACCEPTED, assertNotNull(controller.peerDetail(alice)).status)
    }

    @Test
    fun `a local append with a broken invariant fails hard rather than storing it`() = runTest {
        controller.offer(alice, SharingCircle.FRIENDS, setOf(SharingCategory.VIDEOS))
        val before = storedRows()

        val broken = SharingLedgerSigner(TaggedCrypto(OWNER)).sign(
            SharingLedgerEntry(
                id = LedgerEntryId("local-bad"),
                peer = alice,
                policySequence = 99,
                authorityGeneration = 1,
                resourceEpoch = 1,
                deviceGeneration = 1,
                signerNpub = OWNER,
                signature = "",
                body = SharingLedgerBody.RelationshipRevoked,
            )
        )!!

        assertFailsWith<IllegalStateException> { repo.appendEntry(broken) }
        assertEquals(before, storedRows())
    }

    // ------------------------------------------------------ honest naming

    @Test
    fun `the body preview keeps the owner's circle off anything peer-facing`() = runTest {
        controller.offer(alice, SharingCircle.FRIENDS, setOf(SharingCategory.VIDEOS))

        val preview = controller.nonTransmittableBodyPreview(alice)

        assertTrue(preview.isNotEmpty())
        SharingCircle.entries.forEach { circle ->
            assertTrue(preview.none { it.contains(circle.name) }, "the circle must not appear in ${'$'}preview")
        }
    }

    // --------------------------------------------------- identity end to end

    @Test
    fun `a peer invited by npub can afterwards sign with the matching hex identity`() = runTest {
        // The whole point of the parser: an npub typed into the invite field
        // and the hex the signature layer works with have to be one identity,
        // or the relationship can never be accepted.
        val npub = "npub1uadpshqpn5ysf82lev8zngkvn07szmkq7mvf9lyc7ml7qxq6fqxsmrqt2s"
        val hex = "e75a185c019d09049d5fcb0e29a2cc9bfd016ec0f6d892fc98f6ffe0181a480d"

        val parsed = SharingPeerIdParser.parse(npub)
        assertIs<PeerIdParseResult.Valid>(parsed)
        val peer = parsed.peer
        assertEquals(PeerId(hex), peer)

        controller.offer(peer, SharingCircle.FRIENDS, setOf(SharingCategory.VIDEOS))

        // Signed with the key the npub actually names.
        val acceptance = SharingLedgerSigner(TaggedCrypto(hex)).sign(
            SharingLedgerEntry(
                id = LedgerEntryId("acc"),
                peer = peer,
                // A reply names the entry it answers; the ledger is a DAG.
                policySequence = (repo.authorisedHead(peer)?.policySequence ?: 0L) + 1L,
                parent = repo.authorisedHead(peer)?.id,
                authorityGeneration = 1,
                resourceEpoch = 1,
                deviceGeneration = 1,
                signerNpub = hex,
                signature = "",
                body = SharingLedgerBody.RecipientAccepted(setOf(SharingCategory.VIDEOS), phone),
            )
        )!!
        assertTrue(controller.ingestPeerSignedEntry(acceptance), "the acceptance must verify")
        assertEquals(RelationshipStatus.ACCEPTED, assertNotNull(controller.peerDetail(peer)).status)

        // And so must a device authorisation, which is peer-signed too.
        val authorised = SharingLedgerSigner(TaggedCrypto(hex)).sign(
            SharingLedgerEntry(
                id = LedgerEntryId("dev"),
                peer = peer,
                // A reply names the entry it answers; the ledger is a DAG.
                policySequence = (repo.authorisedHead(peer)?.policySequence ?: 0L) + 1L,
                parent = repo.authorisedHead(peer)?.id,
                authorityGeneration = 1,
                resourceEpoch = 1,
                deviceGeneration = 1,
                signerNpub = hex,
                signature = "",
                body = SharingLedgerBody.DeviceAuthorized(DeviceId("dev-tablet")),
            )
        )!!
        assertTrue(controller.ingestPeerSignedEntry(authorised), "the device authorisation must verify")
        assertEquals(
            setOf(phone, DeviceId("dev-tablet")),
            assertNotNull(controller.peerDetail(peer)).devices.filter { it.authorised }.map { it.device }.toSet(),
        )
    }

    @Test
    fun `inviting the same person by npub and by hex is one relationship`() = runTest {
        val npub = "npub1uadpshqpn5ysf82lev8zngkvn07szmkq7mvf9lyc7ml7qxq6fqxsmrqt2s"
        val hex = "e75a185c019d09049d5fcb0e29a2cc9bfd016ec0f6d892fc98f6ffe0181a480d"

        val fromNpub = (SharingPeerIdParser.parse(npub) as PeerIdParseResult.Valid).peer
        val fromHex = (SharingPeerIdParser.parse(hex.uppercase()) as PeerIdParseResult.Valid).peer

        controller.offer(fromNpub, SharingCircle.FRIENDS, setOf(SharingCategory.VIDEOS))

        assertEquals(fromNpub, fromHex)
        assertEquals(1, controller.snapshot().peers.size, "one person must not become two relationships")
    }
}
