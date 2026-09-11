package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.SecureDatabasePragmas
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.AeadSharingKeyVault
import com.cruxcoach.domain.sharing.AsyncLedgerCrypto
import com.cruxcoach.domain.sharing.AsyncOwnerPolicySigner
import com.cruxcoach.domain.sharing.AsyncSharingLedgerSigner
import com.cruxcoach.domain.sharing.DeviceId
import com.cruxcoach.domain.sharing.RecoveryDecision
import com.cruxcoach.domain.sharing.DeviceManifestSigner
import com.cruxcoach.domain.sharing.InMemoryWrappingKeyStore
import com.cruxcoach.domain.sharing.LedgerCrypto
import com.cruxcoach.domain.sharing.Nip01SignedEvent
import com.cruxcoach.domain.sharing.Nip01SigningEnvelope
import com.cruxcoach.domain.sharing.OwnerPolicySigner
import com.cruxcoach.domain.sharing.PeerId
import com.cruxcoach.domain.sharing.SharingCategory
import com.cruxcoach.domain.sharing.SharingCircle
import com.cruxcoach.domain.sharing.SharingLedgerBody
import com.cruxcoach.domain.sharing.SharingLedgerSigner
import com.cruxcoach.domain.sharing.SharingRecoveryCode
import com.cruxcoach.domain.sharing.SigningDomain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.security.MessageDigest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FEAT-062 §10: one user action is one write, or it is no write.
 *
 * Several things a person does from the screens are more than one signed entry.
 * Changing a baseline is a policy entry *plus* a `GrantChanged` for every
 * relationship the change reaches. Moving somebody into another circle is the
 * assignment *plus* their re-synced offer. Restoring a backup is the whole file
 * *plus* a fresh device generation per relationship.
 *
 * With a local key those were effectively instant, so writing them one at a
 * time was invisible. With an external signer every one of them is a separate
 * approval prompt, and the person can decline the third of five. Writing as we
 * went meant a declined prompt left the earlier entries behind: a policy that
 * had changed while the screen said the change had failed, a peer in a new
 * circle with the old offer, a restored backup with half the relationships on a
 * new device generation.
 *
 * So the rule these tests hold: every entry a user action needs is built and
 * signed **before** any of it is stored, and the storing is one transaction.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharingCompositeMutationTest {

    private companion object {
        const val OWNER = "e75a185c019d09049d5fcb0e29a2cc9bfd016ec0f6d892fc98f6ffe0181a480d"
    }

    private lateinit var dbFile: java.io.File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repo: SecureDbSharingRepository
    private lateinit var controller: SharingController

    private val alice = PeerId("aaaa111122223333444455556666777788889999aaaabbbbccccddddeeeeffff")
    private val bob = PeerId("bbbb111122223333444455556666777788889999aaaabbbbccccddddeeeeffff")
    private val carla = PeerId("cccc111122223333444455556666777788889999aaaabbbbccccddddeeeeffff")

    /**
     * Signs every request except the ones named in [refuse], and parks on
     * [gate] at request [gateAt]. Requests are counted across the whole user
     * action, so "refuse the second prompt" is expressible.
     */
    private class PromptCrypto(
        private val identity: String,
        private val refuse: Set<Int> = emptySet(),
        private val gateAt: Int = -1,
        private val gate: CompletableDeferred<Boolean>? = null,
    ) : AsyncLedgerCrypto {
        var calls = 0
            private set

        override suspend fun signCanonical(hash: ByteArray): ByteArray? {
            val n = calls++
            if (n == gateAt && gate != null) gate.await()
            if (n in refuse) return null
            return (identity + ":").encodeToByteArray() + hash
        }

        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
            signature.contentEquals((signerNpub + ":").encodeToByteArray() + hash)
    }

    private class TaggedCrypto(private val identity: String) : LedgerCrypto {
        override fun sign(hash: ByteArray): ByteArray = (identity + ":").encodeToByteArray() + hash
        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
            signature.contentEquals((signerNpub + ":").encodeToByteArray() + hash)
    }

    private fun open(
        crypto: AsyncLedgerCrypto = PromptCrypto(OWNER),
        file: java.io.File = dbFile,
    ) {
        driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}?foreign_keys=on").also { d ->
            SecureDatabasePragmas.STATEMENTS.forEach { runCatching { d.execute(null, it, 0).value } }
        }
        val database = SecureDatabase(driver)
        repo = SecureDbSharingRepository(
            database = database,
            vault = AeadSharingKeyVault(InMemoryWrappingKeyStore()),
            verifier = SharingLedgerSigner(TaggedCrypto(OWNER)).verifier(),
            ownerPolicyVerifier = OwnerPolicySigner(TaggedCrypto(OWNER)).verifier(),
            ownerNpub = OWNER,
            deviceManifestVerifier = DeviceManifestSigner(TaggedCrypto(OWNER)).verifier(),
            attestationVerifier = TestDeviceAuthority.attestationSigner().verifier(),
            // Which device this install is, and the key it signs with — both
            // bound at construction rather than taken per commit.
            localDeviceIdentity = { DeviceIdentity(TestDeviceAuthority.DEVICE, TestDeviceAuthority.publicKeyOf()) },
            // Bound at construction, matching what the controller signs with.
            rootRecoveryVerifier = crypto,
        )
        controller = SharingController(
            repository = repo,
            signer = AsyncSharingLedgerSigner(crypto),
            ownerPolicySigner = AsyncOwnerPolicySigner(crypto),
            ownerNpub = OWNER,
            authorityDevice = TestDeviceAuthority.DEVICE,
            authorityDevicePublicKey = TestDeviceAuthority.publicKeyOf(),
            // A recovery answers a root challenge and writes a manifest, so it
            // needs the root identity. This is the same scripted signer, so a
            // refusal reaches the challenge exactly as it reaches the rest.
            manifestSigner = com.cruxcoach.domain.sharing.AsyncDeviceManifestSigner(crypto),
            rootCrypto = crypto,
            attestationSigner = TestDeviceAuthority.attestationSigner(),
            peerSimulator = { peer -> TaggedCrypto(peer.value) },
            backupCrypto = crypto,
        )
    }

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-composite-")
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

    private fun policyEntries() = repo.loadOwnerPolicyLedger()
    private fun grantChanges(peer: PeerId) =
        repo.loadLedger(peer).count { it.body is SharingLedgerBody.GrantChanged }

    private fun circleOf(peer: PeerId) = repo.loadProjection().relationships[peer]?.circle
    private fun offeredOf(peer: PeerId) = repo.loadProjection().relationships[peer]?.offeredCategories

    private val code = SharingRecoveryCode.fromEntropy(ByteArray(20) { (it * 3 + 1).toByte() })

    // ------------------------------------------- policy change + offer sync

    /**
     * The policy entry is prompt one and the offer sync is prompt two. A
     * refusal at prompt two used to leave the policy changed while the screen
     * reported failure — the one state the whole design exists to prevent.
     */
    @Test
    fun `a baseline change refused at the offer sync writes no policy entry`() = runTest {
        controller.invite(alice, SharingCircle.FRIENDS)
        val policyBefore = policyEntries().size
        val grantsBefore = grantChanges(alice)

        // Prompt 0 signs the policy entry, prompt 1 is alice's GrantChanged.
        open(PromptCrypto(OWNER, refuse = setOf(1)))
        val result = controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)

        assertIs<SharingWriteResult.Failed>(result)
        assertEquals(SharingWriteError.SIGNER_UNAVAILABLE, result.error)
        assertEquals(policyBefore, policyEntries().size, "the policy must not have changed")
        assertEquals(grantsBefore, grantChanges(alice))
        assertTrue(
            SharingCategory.VIDEOS !in repo.loadPolicy().baselines.effectiveFor(SharingCircle.FRIENDS),
            "a failed change must leave the baseline exactly as it was",
        )
    }

    /**
     * Three relationships, so the offer sync is three prompts. Declining the
     * *last* one is the case that a "validate, then write as you go" loop gets
     * wrong: two peers already have their new offer.
     */
    @Test
    fun `a baseline change refused at the last peer writes nothing for any peer`() = runTest {
        listOf(alice, bob, carla).forEach { controller.invite(it, SharingCircle.FRIENDS) }
        val policyBefore = policyEntries().size
        val grantsBefore = listOf(alice, bob, carla).associateWith { grantChanges(it) }

        // Prompt 0 the policy, prompts 1..3 the three peers; the last is declined.
        open(PromptCrypto(OWNER, refuse = setOf(3)))
        val result = controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)

        assertIs<SharingWriteResult.Failed>(result)
        assertEquals(policyBefore, policyEntries().size)
        listOf(alice, bob, carla).forEach { peer ->
            assertEquals(grantsBefore.getValue(peer), grantChanges(peer), "$peer must be untouched")
        }
    }

    @Test
    fun `a baseline change and every offer it reaches land together`() = runTest {
        listOf(alice, bob).forEach { controller.invite(it, SharingCircle.FRIENDS) }

        assertTrue(controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true).isSuccess)

        assertTrue(SharingCategory.VIDEOS in repo.loadPolicy().baselines.effectiveFor(SharingCircle.FRIENDS))
        listOf(alice, bob).forEach { peer ->
            assertTrue(SharingCategory.VIDEOS in (offeredOf(peer) ?: emptySet()), "$peer was not re-offered")
        }
    }

    // --------------------------------------------------------- circle change

    /**
     * Moving somebody into another circle is the assignment plus their
     * re-synced offer. A refusal at the sync used to leave them in the new
     * circle with the old offer — which is a relationship whose stored circle
     * and stored offer disagree.
     */
    @Test
    fun `a circle change refused at the offer sync leaves the old circle`() = runTest {
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        controller.invite(alice, SharingCircle.ALL_OTHER_USERS)
        val circleBefore = circleOf(alice)
        val offeredBefore = offeredOf(alice)

        // Prompt 0 is the assignment, prompt 1 the re-synced offer.
        open(PromptCrypto(OWNER, refuse = setOf(1)))
        val result = controller.setPeerCircle(alice, SharingCircle.FRIENDS)

        assertIs<SharingWriteResult.Failed>(result)
        assertEquals(SharingWriteError.SIGNER_UNAVAILABLE, result.error)
        assertEquals(circleBefore, circleOf(alice), "the circle must not have moved")
        assertEquals(offeredBefore, offeredOf(alice))
    }

    @Test
    fun `a circle change and its new offer land together`() = runTest {
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        controller.invite(alice, SharingCircle.ALL_OTHER_USERS)

        assertTrue(controller.setPeerCircle(alice, SharingCircle.FRIENDS).isSuccess)

        assertEquals(SharingCircle.FRIENDS, circleOf(alice))
        assertTrue(SharingCategory.VIDEOS in (offeredOf(alice) ?: emptySet()))
    }

    @Test
    fun `downgrade refuses all exception removal when a later circle signature is declined`() = runTest {
        controller.invite(alice, SharingCircle.FRIENDS)
        assertTrue(controller.setPeerRule(alice, SharingCategory.PRIVATE_NOTES, com.cruxcoach.domain.sharing.AccessEffect.DENY).isSuccess)
        val before = repo.loadPolicy()
        val ledgerBefore = policyEntries().size
        // Clear-rule signature succeeds; circle assignment is then refused.
        open(PromptCrypto(OWNER, refuse = setOf(1)))
        assertIs<SharingWriteResult.Failed>(controller.setPeerCircle(alice, SharingCircle.ACQUAINTANCES, clearPersonalExceptions = true))
        assertEquals(before, repo.loadPolicy())
        assertEquals(ledgerBefore, policyEntries().size)
        assertEquals(SharingCircle.FRIENDS, circleOf(alice))
    }

    // ----------------------------------------------------------- cancellation

    /**
     * A rotation or a killed process cancels the coroutine between two prompts.
     * There is nothing to roll back if nothing was written, which is the point.
     */
    @Test
    fun `a composite change cancelled between prompts writes nothing`() = runTest {
        controller.invite(alice, SharingCircle.FRIENDS)
        val policyBefore = policyEntries().size
        val grantsBefore = grantChanges(alice)

        val gate = CompletableDeferred<Boolean>()
        open(PromptCrypto(OWNER, gateAt = 1, gate = gate))
        val job = launch { controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true) }
        runCurrent()
        job.cancel()
        job.join()

        assertEquals(policyBefore, policyEntries().size, "a cancelled action must write nothing")
        assertEquals(grantsBefore, grantChanges(alice))
    }

    // --------------------------------------------------------------- restore

    /**
     * Restoring is the file plus one fresh device generation per relationship.
     * The generations are signed, so they can be declined — and until this was
     * fixed the file had already been written by then, leaving a restored
     * install that reported failure and was nonetheless restored.
     */
    @Test
    fun `an import whose generation signature is refused restores nothing`() = runTest {
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        controller.invite(alice, SharingCircle.FRIENDS)
        controller.simulateAccept(alice, setOf(SharingCategory.VIDEOS), DeviceId("phone"))
        val exported = assertIs<SharingBackupWriteOutcome.Written>(controller.exportBackup(code)).bytes

        // A fresh install whose signer declines the device-generation entry.
        // Reading the envelope needs no signature, so the refusal is the first
        // and only prompt this import asks for.
        val fresh = Files.createTempDirectory("cruxcoach-composite-fresh-").resolve("secure.db").toFile()
        runCatching { driver.close() }
        open(PromptCrypto(OWNER, refuse = setOf(0)), file = fresh)
        SecureDatabase.Schema.create(driver)
        TestDeviceAuthority.enrol(repo, TaggedCrypto(OWNER), OWNER)

        val outcome = controller.importRecovery(exported, code, sovereignReset = true)

        // The first prompt is the root challenge, and declining it is the end
        // of it. Refusal is the fact; which credential failed is not something
        // the outcome distinguishes.
        assertEquals(RecoveryDecision.REFUSED, outcome.decision)
        assertTrue(!outcome.applied)
        assertTrue(repo.loadProjection().relationships.isEmpty(), "nothing may have been restored")
        assertTrue(repo.loadOwnerPolicyLedger().isEmpty(), "the policy ledger must be untouched too")
        assertNull(circleOf(alice))
        fresh.delete()
        fresh.parentFile?.delete()
    }

    @Test
    fun `an import that is fully signed restores everything at once`() = runTest {
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        controller.invite(alice, SharingCircle.FRIENDS)
        controller.simulateAccept(alice, setOf(SharingCategory.VIDEOS), DeviceId("phone"))
        val exported = assertIs<SharingBackupWriteOutcome.Written>(controller.exportBackup(code)).bytes

        val fresh = Files.createTempDirectory("cruxcoach-composite-ok-").resolve("secure.db").toFile()
        runCatching { driver.close() }
        open(file = fresh)
        SecureDatabase.Schema.create(driver)
        TestDeviceAuthority.enrol(repo, TaggedCrypto(OWNER), OWNER)

        // A fresh install cannot show the file is current, so applying it at
        // all is a deliberate takeover. Everything lands in one go or nothing
        // does — which is what this test is about.
        val outcome = controller.importRecovery(exported, code, sovereignReset = true)
        assertTrue(outcome.applied, "decision=${outcome.decision} batch=${repo.lastBatchRejection}")

        val state = repo.loadProjection().relationships.getValue(alice)
        assertTrue(state.awaitingRevokeSync, "a restore stays closed until revocations are re-checked")
        assertTrue(repo.loadOwnerPolicyLedger().isNotEmpty())
        fresh.delete()
        fresh.parentFile?.delete()
    }

    // --------------------------------------------------------- hung signer

    /**
     * Answers the first [answerFirst] requests correctly and then goes silent.
     *
     * Wired through the real [Nip55LedgerCrypto] rather than a stand-in,
     * because the bound being tested is that class's, and a stand-in with its
     * own timeout would only be testing itself.
     */
    private class PartlySilentSigner(private val answerFirst: Int) : Nip01EventSigner {
        var calls = 0
            private set

        override suspend fun sign(
            createdAt: Long,
            kind: Int,
            tags: List<List<String>>,
            content: String,
        ): Nip01SignedEvent? {
            if (calls++ >= answerFirst) awaitCancellation()
            val preimage = Nip01SigningEnvelope.preimage(
                OWNER,
                SigningDomain.entries.first { it.id == tags.single()[1] },
                content.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            )
            val digest = MessageDigest.getInstance("SHA-256").digest(preimage.encodeToByteArray())
            return Nip01SignedEvent(
                id = digest.joinToString("") { b -> ((b.toInt() and 0xff) + 0x100).toString(16).substring(1) },
                pubKey = OWNER,
                createdAt = createdAt,
                kind = kind,
                tags = tags,
                content = content,
                sig = "ab".repeat(64),
            )
        }
    }

    /**
     * Opens a controller whose signing really is [Nip55LedgerCrypto], with the
     * curve stubbed out. The repository verifies with the same object, so the
     * signatures it produces are the ones admission accepts.
     */
    private fun openWithRealCrypto(signer: Nip01EventSigner, timeoutMillis: Long) {
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}?foreign_keys=on").also { d ->
            SecureDatabasePragmas.STATEMENTS.forEach { runCatching { d.execute(null, it, 0).value } }
        }
        val database = SecureDatabase(driver)
        fun crypto(domain: SigningDomain) = Nip55LedgerCrypto(
            domain, { OWNER }, signer, { _, _, _ -> true }, timeoutMillis,
        )
        val relationship = AsyncSharingLedgerSigner(crypto(SigningDomain.RELATIONSHIP_LEDGER))
        val policy = AsyncOwnerPolicySigner(crypto(SigningDomain.OWNER_POLICY_LEDGER))
        repo = SecureDbSharingRepository(
            database = database,
            vault = AeadSharingKeyVault(InMemoryWrappingKeyStore()),
            verifier = relationship.verifier(),
            ownerPolicyVerifier = policy.verifier(),
            ownerNpub = OWNER,
            deviceManifestVerifier = DeviceManifestSigner(TaggedCrypto(OWNER)).verifier(),
            attestationVerifier = TestDeviceAuthority.attestationSigner().verifier(),
        )
        controller = SharingController(
            repository = repo,
            signer = relationship,
            ownerPolicySigner = policy,
            ownerNpub = OWNER,
            authorityDevice = TestDeviceAuthority.DEVICE,
            attestationSigner = TestDeviceAuthority.attestationSigner(),
        )
    }

    /**
     * A signer that stops answering partway through a composite action must not
     * hold the write path open. It times out, the action reports failure, and —
     * because nothing is stored until every entry is signed — the ledgers are
     * exactly as they were.
     */
    @Test
    fun `a composite action whose second prompt hangs times out and writes nothing`() = runTest {
        // Set the scene with a signer that answers everything.
        openWithRealCrypto(PartlySilentSigner(answerFirst = Int.MAX_VALUE), timeoutMillis = 20_000)
        controller.invite(alice, SharingCircle.FRIENDS)

        // The policy entry is answered; the offer sync is not.
        openWithRealCrypto(PartlySilentSigner(answerFirst = 1), timeoutMillis = 20_000)
        val policyBefore = policyEntries().size
        val grantsBefore = grantChanges(alice)

        val started = currentTime
        val result = controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)

        assertIs<SharingWriteResult.Failed>(result)
        assertEquals(SharingWriteError.SIGNER_UNAVAILABLE, result.error)
        assertEquals(20_000L, currentTime - started, "the action must give up, not wait for ever")
        assertEquals(policyBefore, policyEntries().size, "a timed-out action must write nothing")
        assertEquals(grantsBefore, grantChanges(alice))
    }

    /** A timed-out action must release the lock, or the screen stays dead. */
    @Test
    fun `a later change still works after one timed out`() = runTest {
        openWithRealCrypto(PartlySilentSigner(answerFirst = 0), timeoutMillis = 20_000)

        assertIs<SharingWriteResult.Failed>(
            controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        )

        openWithRealCrypto(PartlySilentSigner(answerFirst = Int.MAX_VALUE), timeoutMillis = 20_000)
        assertTrue(controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true).isSuccess)
        assertEquals(1, policyEntries().size)
    }
}
