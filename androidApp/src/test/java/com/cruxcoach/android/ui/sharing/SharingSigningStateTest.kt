package com.cruxcoach.android.ui.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.android.sharing.SecureDbSharingRepository
import com.cruxcoach.android.sharing.SharingController
import com.cruxcoach.android.sharing.SharingWriteError
import com.cruxcoach.data.SecureDatabasePragmas
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.AeadSharingKeyVault
import com.cruxcoach.domain.sharing.AsyncLedgerCrypto
import com.cruxcoach.domain.sharing.AsyncOwnerPolicySigner
import com.cruxcoach.domain.sharing.AsyncSharingLedgerSigner
import com.cruxcoach.domain.sharing.DeviceId
import com.cruxcoach.android.sharing.TestDeviceAuthority
import com.cruxcoach.domain.sharing.DeviceManifestSigner
import com.cruxcoach.domain.sharing.InMemoryWrappingKeyStore
import com.cruxcoach.domain.sharing.LedgerCrypto
import com.cruxcoach.domain.sharing.OwnerPolicySigner
import com.cruxcoach.domain.sharing.PeerId
import com.cruxcoach.domain.sharing.SharingCategory
import com.cruxcoach.domain.sharing.SharingCircle
import com.cruxcoach.domain.sharing.SharingLedgerSigner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
 * FEAT-062 §10: what the screens do while a signature is being waited for.
 *
 * With a local key a mutation was over before a finger left the glass. With an
 * external signer it is a prompt in another app, and the two things that fall
 * out of that are visible here: the screen has to say something is happening,
 * and a second tap on a button that has not visibly done anything yet must not
 * become a second permission change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharingSigningStateTest {

    private companion object {
        const val OWNER = "e75a185c019d09049d5fcb0e29a2cc9bfd016ec0f6d892fc98f6ffe0181a480d"
    }

    private val dispatcher = StandardTestDispatcher()
    private lateinit var dbFile: java.io.File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repo: SecureDbSharingRepository
    private lateinit var controller: SharingController
    private lateinit var viewModel: SharingViewModel

    private val alice = PeerId("aaaa111122223333444455556666777788889999aaaabbbbccccddddeeeeffff")

    /** Signs when told to; parks on [gate] when there is one. */
    private class GatedCrypto(
        private val identity: String,
        private val gate: CompletableDeferred<Boolean>? = null,
    ) : AsyncLedgerCrypto {
        override suspend fun signCanonical(hash: ByteArray): ByteArray? {
            if (gate != null && !gate.await()) return null
            return (identity + ":").encodeToByteArray() + hash
        }

        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
            signature.contentEquals((signerNpub + ":").encodeToByteArray() + hash)
    }

    /** Refuses its first [refusals] requests, then signs. */
    private class FlakyCrypto(
        private val identity: String,
        private val refusals: Int,
    ) : AsyncLedgerCrypto {
        private var calls = 0

        override suspend fun signCanonical(hash: ByteArray): ByteArray? {
            if (calls++ < refusals) return null
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

    private fun open(crypto: AsyncLedgerCrypto) {
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}?foreign_keys=on").also { d ->
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
        )
        controller = SharingController(
            repository = repo,
            signer = AsyncSharingLedgerSigner(crypto),
            ownerPolicySigner = AsyncOwnerPolicySigner(crypto),
            ownerNpub = OWNER,
            authorityDevice = TestDeviceAuthority.DEVICE,
            authorityDevicePublicKey = TestDeviceAuthority.publicKeyOf(),
            attestationSigner = TestDeviceAuthority.attestationSigner(),
            peerSimulator = { peer -> TaggedCrypto(peer.value) },
        )
        viewModel = SharingViewModel(controller).also { it.ioContext = dispatcher }
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val tmp = Files.createTempDirectory("cruxcoach-signing-state-")
        dbFile = tmp.resolve("secure.db").toFile()
        open(GatedCrypto(OWNER))
        SecureDatabase.Schema.create(driver)
        TestDeviceAuthority.enrol(repo, TaggedCrypto(OWNER), OWNER)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        runCatching { driver.close() }
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    private fun policyEntries() = repo.loadOwnerPolicyLedger().size

    // ------------------------------------------------------- signing state

    @Test
    fun `the screen is told while a signature is being waited for`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Boolean>()
        open(GatedCrypto(OWNER, gate))

        assertFalse(viewModel.signing.value)

        viewModel.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        advanceUntilIdle()
        assertTrue(viewModel.signing.value, "a mutation waiting on an external signer must be visible")

        gate.complete(true)
        advanceUntilIdle()
        assertFalse(viewModel.signing.value)
        assertEquals(1, policyEntries())
    }

    @Test
    fun `the signing state clears when the signature is refused`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Boolean>()
        open(GatedCrypto(OWNER, gate))

        viewModel.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        advanceUntilIdle()
        gate.complete(false)
        advanceUntilIdle()

        assertFalse(viewModel.signing.value, "a refusal must not leave the screen stuck")
        assertEquals(SharingWriteError.SIGNER_UNAVAILABLE, viewModel.writeError.value)
        assertEquals(0, policyEntries())
    }

    // -------------------------------------------------------- exactly once

    /**
     * The prompt is in another app, so the button sits there looking dead. A
     * second tap must not queue a second approval and a second ledger entry.
     */
    @Test
    fun `a second tap while the first is still signing changes nothing extra`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Boolean>()
        open(GatedCrypto(OWNER, gate))

        viewModel.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        advanceUntilIdle()
        viewModel.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        advanceUntilIdle()

        gate.complete(true)
        advanceUntilIdle()

        assertEquals(1, policyEntries(), "a double tap is one permission change, not two")
        assertFalse(viewModel.signing.value)
    }

    @Test
    fun `two different actions cannot overlap either`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Boolean>()
        open(GatedCrypto(OWNER, gate))

        viewModel.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        advanceUntilIdle()
        viewModel.setBaseline(SharingCircle.FRIENDS, SharingCategory.PRIVATE_NOTES, true)
        advanceUntilIdle()

        gate.complete(true)
        advanceUntilIdle()

        assertEquals(1, policyEntries(), "the second action was dropped, not queued behind the prompt")
    }

    @Test
    fun `an action is offered again once the previous one has finished`() = runTest(dispatcher) {
        open(GatedCrypto(OWNER))

        viewModel.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        advanceUntilIdle()
        viewModel.setBaseline(SharingCircle.FRIENDS, SharingCategory.PRIVATE_NOTES, true)
        advanceUntilIdle()

        assertEquals(2, policyEntries())
    }

    // ------------------------------------------- no navigation on refusal

    /**
     * Closing the detail screen is how the UI says "this person is gone". A
     * purge whose signature was refused has deleted nothing, so the screen has
     * to stay where it is, next to the reason.
     */
    @Test
    fun `a refused purge neither deletes nor navigates away`() = runTest(dispatcher) {
        open(GatedCrypto(OWNER))
        viewModel.invite(alice.value, SharingCircle.FRIENDS)
        advanceUntilIdle()
        viewModel.simulateAccept(alice, setOf(SharingCategory.VIDEOS), DeviceId("phone"))
        advanceUntilIdle()
        viewModel.openPeer(alice)
        advanceUntilIdle()
        assertNotNull(viewModel.detail.value)
        val entriesBefore = repo.loadLedger(alice).size

        val refusing = CompletableDeferred(false)
        open(GatedCrypto(OWNER, refusing))
        viewModel.openPeer(alice)
        advanceUntilIdle()

        val purged = viewModel.purge(alice)
        advanceUntilIdle()

        assertFalse(purged)
        assertNotNull(viewModel.detail.value, "a refused purge must not look like a completed one")
        assertEquals(SharingWriteError.SIGNER_UNAVAILABLE, viewModel.writeError.value)
        assertEquals(entriesBefore, repo.loadLedger(alice).size)
    }

    @Test
    fun `a refused invitation keeps what was typed and reports why`() = runTest(dispatcher) {
        open(GatedCrypto(OWNER, CompletableDeferred(false)))

        val accepted = viewModel.invite(alice.value, SharingCircle.FRIENDS)
        advanceUntilIdle()

        assertFalse(accepted, "the field may only clear once the ledger really has the entry")
        assertEquals(SharingWriteError.SIGNER_UNAVAILABLE, viewModel.writeError.value)
        assertNull(repo.loadProjection().relationships[alice])
    }

    // -------------------------------------------------------------- retry

    /**
     * Declining a prompt is an ordinary thing to do, and the next tap has to
     * work. The failure must clear itself rather than needing the screen to be
     * left and reopened.
     */
    @Test
    fun `a refused change can simply be tried again`() = runTest(dispatcher) {
        open(FlakyCrypto(OWNER, refusals = 1))

        viewModel.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        advanceUntilIdle()
        assertEquals(SharingWriteError.SIGNER_UNAVAILABLE, viewModel.writeError.value)
        assertEquals(0, policyEntries())
        assertFalse(viewModel.signing.value, "a refusal must leave the screen usable")

        viewModel.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        advanceUntilIdle()

        assertNull(viewModel.writeError.value, "a successful retry must clear the failure")
        assertEquals(1, policyEntries(), "the retry appended once, not twice")
    }

    /**
     * An invitation is two signatures. Refusing the second and retrying must
     * end with one relationship, not a stray circle assignment plus a second
     * complete invitation on top of it.
     */
    @Test
    fun `an invitation refused halfway and retried leaves one clean relationship`() = runTest(dispatcher) {
        open(FlakyCrypto(OWNER, refusals = 1))

        assertFalse(viewModel.invite(alice.value, SharingCircle.FRIENDS))
        advanceUntilIdle()
        assertNull(repo.loadProjection().relationships[alice])

        assertTrue(viewModel.invite(alice.value, SharingCircle.FRIENDS))
        advanceUntilIdle()

        assertEquals(2, repo.loadLedger(alice).size, "one invitation is exactly two entries")
        val state = assertNotNull(repo.loadProjection().relationships[alice])
        assertNull(state.failClosedReason)
        assertEquals(listOf(1L, 2L), repo.loadLedger(alice).map { it.policySequence })
    }

    // --------------------------------------------------- lifecycle restart

    /**
     * If the process goes away while a prompt is open, nothing was written —
     * there is no half-signed state to recover, because nothing is stored until
     * the signature is in hand. A rebuilt screen therefore starts clean rather
     * than showing a mutation that never completed.
     */
    @Test
    fun `a screen rebuilt after an abandoned signature starts clean`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Boolean>()
        open(GatedCrypto(OWNER, gate))

        // Launched the way the screen launches it, so the wait happens off to
        // the side rather than blocking this test.
        val pending = launch { viewModel.invite(alice.value, SharingCircle.FRIENDS) }
        advanceUntilIdle()
        assertTrue(viewModel.signing.value)

        // The process dies here: the prompt is never answered, the coroutine
        // never resumes, and the ViewModel is rebuilt over the same database.
        pending.cancel()
        val rebuilt = SharingViewModel(controller).also { it.ioContext = dispatcher }
        advanceUntilIdle()

        assertFalse(rebuilt.signing.value, "a rebuilt screen must not inherit a pending prompt")
        assertNull(rebuilt.writeError.value)
        assertNull(repo.loadProjection().relationships[alice], "nothing may have been written")
        assertTrue(rebuilt.state.value.peers.isEmpty())
    }
}
