package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.SecureDatabasePragmas
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.AeadSharingKeyVault
import com.cruxcoach.domain.sharing.AsyncLedgerCrypto
import com.cruxcoach.domain.sharing.AsyncOwnerPolicySigner
import com.cruxcoach.domain.sharing.AsyncSharingLedgerSigner
import com.cruxcoach.domain.sharing.DeviceId
import com.cruxcoach.domain.sharing.DeviceManifestSigner
import com.cruxcoach.domain.sharing.InMemoryWrappingKeyStore
import com.cruxcoach.domain.sharing.LedgerCrypto
import com.cruxcoach.domain.sharing.OwnerPolicySigner
import com.cruxcoach.domain.sharing.PeerId
import com.cruxcoach.domain.sharing.SharingCategory
import com.cruxcoach.domain.sharing.SharingCircle
import com.cruxcoach.domain.sharing.SharingLedgerSigner
import com.cruxcoach.domain.sharing.SharingRecoveryCode
import com.cruxcoach.domain.sharing.asAsync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FEAT-062 §10: what an external signer does to the write path.
 *
 * Signing used to be instant and local. Now it can take as long as somebody
 * takes to notice a prompt in another app, and it can end in "no". Three things
 * that were previously impossible become possible, and this is where they are
 * held shut:
 *
 *  - two mutations overlapping, each having read the same "next sequence"
 *    before either wrote;
 *  - a change made of two entries getting its first approval and not its
 *    second;
 *  - a signature coming back from an identity that is no longer the one the
 *    entry names.
 *
 * The stand-in signer here is scripted rather than fixed: each call can sign,
 * refuse, or suspend, which is the only way to drive the orderings a real
 * approval prompt produces.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharingExternalSignerTest {

    private companion object {
        // A real 64-character key: the backup envelope binds the file to the
        // owner identity and refuses anything that is not one.
        const val OWNER = "e75a185c019d09049d5fcb0e29a2cc9bfd016ec0f6d892fc98f6ffe0181a480d"
    }

    private lateinit var dbFile: java.io.File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: SecureDatabase
    private lateinit var repo: SecureDbSharingRepository
    private lateinit var controller: SharingController

    private val alice = PeerId("npub1alice")
    private val bob = PeerId("npub1bob")

    /**
     * A signer whose every call is decided by [script]: `true` signs, `false`
     * refuses, and suspending inside it holds the mutation exactly where a
     * real approval prompt would.
     */
    private class ScriptedCrypto(
        private val identity: String,
        private val script: suspend (Int) -> Boolean = { true },
    ) : AsyncLedgerCrypto {
        var calls = 0
            private set

        override suspend fun signCanonical(hash: ByteArray): ByteArray? {
            val n = calls++
            if (!script(n)) return null
            return (identity + ":").encodeToByteArray() + hash
        }

        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
            signature.contentEquals((signerNpub + ":").encodeToByteArray() + hash)
    }

    /**
     * Signs perfectly well — with the wrong key. This is what an identity that
     * changed while a prompt was open actually looks like: not a failure, but a
     * valid signature that does not belong to the entry claiming it.
     */
    private object WrongKeyCrypto : AsyncLedgerCrypto {
        override suspend fun signCanonical(hash: ByteArray): ByteArray =
            "somebody-else:".encodeToByteArray() + hash

        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
            signature.contentEquals((signerNpub + ":").encodeToByteArray() + hash)
    }

    private class TaggedCrypto(private val identity: String) : LedgerCrypto {
        override fun sign(hash: ByteArray): ByteArray = (identity + ":").encodeToByteArray() + hash
        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
            signature.contentEquals((signerNpub + ":").encodeToByteArray() + hash)
    }

    private fun open(
        ownerCrypto: AsyncLedgerCrypto = ScriptedCrypto(OWNER),
        backupCrypto: AsyncLedgerCrypto? = ScriptedCrypto(OWNER),
    ) {
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}?foreign_keys=on").also { d ->
            SecureDatabasePragmas.STATEMENTS.forEach { runCatching { d.execute(null, it, 0).value } }
        }
        database = SecureDatabase(driver)
        repo = SecureDbSharingRepository(
            database = database,
            vault = AeadSharingKeyVault(InMemoryWrappingKeyStore()),
            verifier = SharingLedgerSigner(TaggedCrypto(OWNER)).verifier(),
            ownerPolicyVerifier = OwnerPolicySigner(TaggedCrypto(OWNER)).verifier(),
            ownerNpub = OWNER,
            deviceManifestVerifier = DeviceManifestSigner(TaggedCrypto(OWNER)).verifier(),
            attestationVerifier = TestDeviceAuthority.attestationSigner().verifier(),
        )
        controller = SharingController(
            repository = repo,
            signer = AsyncSharingLedgerSigner(ownerCrypto),
            ownerPolicySigner = AsyncOwnerPolicySigner(ownerCrypto),
            ownerNpub = OWNER,
            authorityDevice = TestDeviceAuthority.DEVICE,
            authorityDevicePublicKey = TestDeviceAuthority.publicKeyOf(),
            attestationSigner = TestDeviceAuthority.attestationSigner(),
            peerSimulator = { peer -> TaggedCrypto(peer.value) },
            backupCrypto = backupCrypto,
        )
    }

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-external-signer-")
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

    private fun policySequences() = repo.loadOwnerPolicyLedger().map { it.policySequence }
    private fun peerSequences(peer: PeerId) = repo.loadLedger(peer).map { it.policySequence }

    // ------------------------------------------------------- serialisation

    /**
     * Five policy changes started at once, each suspending inside the signer.
     *
     * Without one-at-a-time they would all read the same next sequence while
     * the others were parked, and the ledger would end up with duplicates —
     * which the reducer reads as a fork and fails closed on.
     */
    @Test
    fun `overlapping policy changes each get their own sequence`() = runTest {
        open(ownerCrypto = ScriptedCrypto(OWNER) { yield(); true })

        val categories = SharingCategory.entries.take(5)
        categories.map { category ->
            launch { controller.setBaseline(SharingCircle.FRIENDS, category, true) }
        }.joinAll()

        val sequences = policySequences()
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), sequences.sorted())
        assertEquals(sequences.size, sequences.distinct().size, "no two entries may claim one sequence")
    }

    @Test
    fun `overlapping changes to one relationship each get their own sequence`() = runTest {
        open(ownerCrypto = ScriptedCrypto(OWNER) { yield(); true })
        controller.invite(alice, SharingCircle.FRIENDS)

        val before = peerSequences(alice).size
        listOf(
            SharingCategory.VIDEOS,
            SharingCategory.HEALTH_INFORMATION,
            SharingCategory.PRIVATE_NOTES,
        ).map { category ->
            launch { controller.changeGrant(alice, setOf(category)) }
        }.joinAll()

        val sequences = peerSequences(alice)
        assertEquals(before + 3, sequences.size)
        assertEquals((1L..sequences.size.toLong()).toList(), sequences.sorted(), "the ledger must have no gaps")
    }

    /** The projection has to survive the concurrency, not just the row count. */
    @Test
    fun `a relationship written by overlapping mutations still reduces`() = runTest {
        open(ownerCrypto = ScriptedCrypto(OWNER) { yield(); true })

        listOf(alice, bob).map { peer ->
            launch { controller.invite(peer, SharingCircle.FRIENDS) }
        }.joinAll()

        val relationships = repo.loadProjection().relationships
        assertEquals(setOf(alice, bob), relationships.keys)
        relationships.values.forEach { state ->
            assertNull(state.failClosedReason, "a concurrent write must not fail the ledger closed")
        }
    }

    // ------------------------------------------------------- partial writes

    /**
     * An invitation is two entries — the private circle assignment and the
     * offer — and with an external signer each is its own prompt. Approving the
     * first and walking away must leave nothing: a peer filed in a circle with
     * no offer is a relationship that exists, grants nothing, and cannot be
     * told apart from a deliberate one.
     */
    @Test
    fun `an invitation whose second signature is refused writes neither entry`() = runTest {
        open(ownerCrypto = ScriptedCrypto(OWNER) { call -> call == 0 })

        val result = controller.invite(alice, SharingCircle.FRIENDS)

        assertIs<SharingWriteResult.Failed>(result)
        assertEquals(SharingWriteError.SIGNER_UNAVAILABLE, result.error)
        assertTrue(repo.loadLedger(alice).isEmpty(), "half an invitation must not be stored")
        assertNull(repo.loadProjection().relationships[alice])
    }

    @Test
    fun `an invitation whose first signature is refused writes nothing`() = runTest {
        open(ownerCrypto = ScriptedCrypto(OWNER) { false })

        assertIs<SharingWriteResult.Failed>(controller.invite(alice, SharingCircle.FRIENDS))

        assertTrue(repo.loadLedger(alice).isEmpty())
    }

    @Test
    fun `a refused policy change leaves the policy exactly as it was`() = runTest {
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        val before = policySequences()

        open(ownerCrypto = ScriptedCrypto(OWNER) { false })
        val result = controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.PRIVATE_NOTES, true)

        assertIs<SharingWriteResult.Failed>(result)
        assertEquals(SharingWriteError.SIGNER_UNAVAILABLE, result.error)
        assertEquals(before, policySequences())
    }

    // ------------------------------------------------- the wrong signature

    /**
     * The signature is checked against the entry it belongs to before the entry
     * is stored. Storing it instead would put an entry in an append-only ledger
     * that can never verify, and the next read would fail the whole
     * relationship closed — permanently, because nothing can be taken back out.
     */
    @Test
    fun `a signature by the wrong key is never persisted`() = runTest {
        open(ownerCrypto = WrongKeyCrypto)

        val result = controller.invite(alice, SharingCircle.FRIENDS)

        assertIs<SharingWriteResult.Failed>(result)
        assertTrue(repo.loadLedger(alice).isEmpty(), "an entry that cannot verify must not reach the ledger")
    }

    @Test
    fun `a policy entry signed by the wrong key is never persisted`() = runTest {
        open(ownerCrypto = WrongKeyCrypto)

        val result = controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)

        assertIs<SharingWriteResult.Failed>(result)
        assertTrue(repo.loadOwnerPolicyLedger().isEmpty())
        assertFalse(SharingCategory.VIDEOS in repo.loadPolicy().baselines.effectiveFor(SharingCircle.FRIENDS))
    }

    // ---------------------------------------------------------- cancellation

    /**
     * A rotation cancels the coroutine the signing runs in. That must leave the
     * ledger exactly as it was — not a partial write, and not a rolled-back
     * one, because an append-only ledger has nothing to roll back.
     */
    @Test
    fun `a mutation cancelled while waiting for approval writes nothing`() = runTest {
        val approval = CompletableDeferred<Boolean>()
        open(ownerCrypto = ScriptedCrypto(OWNER) { approval.await() })

        val job = launch { controller.invite(alice, SharingCircle.FRIENDS) }
        runCurrent()
        job.cancel()
        job.join()

        assertTrue(repo.loadLedger(alice).isEmpty())
        assertNull(repo.loadProjection().relationships[alice])
    }

    /** A cancelled mutation must not leave the lock held against the next one. */
    @Test
    fun `a cancelled mutation does not block the next one`() = runTest {
        val approval = CompletableDeferred<Boolean>()
        open(ownerCrypto = ScriptedCrypto(OWNER) { if (calls() == 0) approval.await() else true })

        val job = launch { controller.invite(alice, SharingCircle.FRIENDS) }
        runCurrent()
        job.cancel()
        job.join()

        assertTrue(controller.invite(bob, SharingCircle.FRIENDS).isSuccess)
        assertEquals(2, repo.loadLedger(bob).size)
    }

    private var callCount = 0
    private fun calls() = callCount++

    // --------------------------------------------------------------- backup

    @Test
    fun `a refused backup signature writes no file`() = runTest {
        open(backupCrypto = ScriptedCrypto(OWNER) { false })
        val code = SharingRecoveryCode.fromEntropy(ByteArray(20) { it.toByte() })

        val outcome = controller.exportBackup(code)

        assertIs<SharingBackupWriteOutcome.Failed>(outcome)
        assertEquals(
            com.cruxcoach.domain.sharing.SharingBackupError.SIGNER_UNAVAILABLE,
            outcome.error,
        )
    }

    @Test
    fun `a backup is signed through the external seam like everything else`() = runTest {
        val backup = ScriptedCrypto(OWNER)
        open(backupCrypto = backup)
        controller.invite(alice, SharingCircle.FRIENDS)
        val code = SharingRecoveryCode.fromEntropy(ByteArray(20) { it.toByte() })

        assertIs<SharingBackupWriteOutcome.Written>(controller.exportBackup(code))

        assertEquals(1, backup.calls, "the envelope must be signed by the account identity, once")
    }

    // -------------------------------------------------------------- restore

    @Test
    fun `a restore that cannot be signed changes no device generation`() = runTest {
        controller.invite(alice, SharingCircle.FRIENDS)
        controller.simulateAccept(alice, setOf(SharingCategory.VIDEOS), DeviceId("phone"))
        val before = repo.loadProjection().relationships.getValue(alice).deviceGeneration
        val code = SharingRecoveryCode.fromEntropy(ByteArray(20) { it.toByte() })

        open(ownerCrypto = ScriptedCrypto(OWNER) { false })
        val result = controller.advanceDeviceGenerations(code, code)

        assertIs<SharingWriteResult.Failed>(result)
        assertEquals(SharingWriteError.SIGNER_UNAVAILABLE, result.error)
        assertEquals(before, repo.loadProjection().relationships.getValue(alice).deviceGeneration)
    }

    /**
     * A restore signs one entry per relationship, so with an external signer it
     * is several prompts. Refusing a later one must not leave some peers on a
     * new device generation and some on the old.
     */
    @Test
    fun `a restore refused halfway leaves every relationship untouched`() = runTest {
        controller.invite(alice, SharingCircle.FRIENDS)
        controller.invite(bob, SharingCircle.FRIENDS)
        val before = repo.loadProjection().relationships.mapValues { it.value.deviceGeneration }
        val code = SharingRecoveryCode.fromEntropy(ByteArray(20) { it.toByte() })

        // The first relationship is signed, the second refused.
        open(ownerCrypto = ScriptedCrypto(OWNER) { call -> call == 0 })
        assertIs<SharingWriteResult.Failed>(controller.advanceDeviceGenerations(code, code))

        assertEquals(before, repo.loadProjection().relationships.mapValues { it.value.deviceGeneration })
    }
}
