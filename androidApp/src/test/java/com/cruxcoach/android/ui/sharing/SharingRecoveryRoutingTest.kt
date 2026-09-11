package com.cruxcoach.android.ui.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.android.sharing.SecureDbSharingRepository
import com.cruxcoach.android.sharing.SharingController
import com.cruxcoach.android.sharing.SharingWriteError
import com.cruxcoach.android.sharing.TestDeviceAuthority
import com.cruxcoach.data.SecureDatabasePragmas
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.AeadSharingKeyVault
import com.cruxcoach.domain.sharing.AsyncDeviceManifestSigner
import com.cruxcoach.domain.sharing.AsyncLedgerCrypto
import com.cruxcoach.domain.sharing.AsyncOwnerPolicySigner
import com.cruxcoach.domain.sharing.AsyncSharingLedgerSigner
import com.cruxcoach.domain.sharing.AuthorityDeviceId
import com.cruxcoach.domain.sharing.DeviceManifestSigner
import com.cruxcoach.domain.sharing.DeviceRole
import com.cruxcoach.domain.sharing.InMemoryWrappingKeyStore
import com.cruxcoach.domain.sharing.LedgerCrypto
import com.cruxcoach.domain.sharing.OwnerPolicySigner
import com.cruxcoach.domain.sharing.SharingLedgerSigner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FEAT-062 §11.8: what the recovery screen actually does when it is pressed.
 *
 * Everything under §11.8 — a root signature, a new authority generation, this
 * device re-enrolled as the only one holding authority, the rest fenced — is
 * implemented and tested at the controller. The question this file asks is
 * narrower and was never asked: **is that the code the screen runs?**
 *
 * It was not. `SharingViewModel.restore` called a public `SharingController`
 * entry point that appends a `RestoreCompleted` per relationship and stops
 * there: no root challenge, no rotation, no enrolment, no fencing. The
 * feature's own `SharingRecoveryApiSurfaceTest` names `recoverThisDevice` and
 * `importRecovery` as the product's doors, and the product used neither.
 *
 * These tests drive the view model, because that is the seam the screen calls
 * and the one the defect lived at.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharingRecoveryRoutingTest {

    private companion object {
        const val OWNER = "e75a185c019d09049d5fcb0e29a2cc9bfd016ec0f6d892fc98f6ffe0181a480d"
        val LAPTOP = TestDeviceAuthority.DEVICE
        val PHONE = AuthorityDeviceId("phone-2222")
    }

    private val dispatcher = StandardTestDispatcher()
    private lateinit var dbFile: java.io.File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repo: SecureDbSharingRepository
    private lateinit var controller: SharingController
    private lateinit var viewModel: SharingViewModel

    private class TaggedCrypto(private val identity: String) : LedgerCrypto {
        override fun sign(hash: ByteArray): ByteArray = (identity + ":").encodeToByteArray() + hash
        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
            signature.contentEquals((signerNpub + ":").encodeToByteArray() + hash)
    }

    /**
     * Counts what it was asked to sign, and can be made to park or refuse.
     *
     * The count is the point for the root identity: "did anybody ask the owner
     * to authorise this" is exactly the question the legacy path answered with
     * silence.
     */
    private class CountingCrypto(
        private val identity: String,
        private val gate: CompletableDeferred<Boolean>? = null,
        private val refuse: Boolean = false,
    ) : AsyncLedgerCrypto {
        var prompts = 0
            private set

        override suspend fun signCanonical(hash: ByteArray): ByteArray? {
            prompts++
            if (gate != null && !gate.await()) return null
            if (refuse) return null
            return (identity + ":").encodeToByteArray() + hash
        }

        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
            signature.contentEquals((signerNpub + ":").encodeToByteArray() + hash)
    }

    private lateinit var rootCrypto: CountingCrypto
    private lateinit var manifestCrypto: CountingCrypto
    private lateinit var backupCrypto: CountingCrypto

    private fun open(
        rootGate: CompletableDeferred<Boolean>? = null,
        manifestRefuses: Boolean = false,
        backupGate: CompletableDeferred<Boolean>? = null,
        rootRefuses: Boolean = false,
    ) {
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}?foreign_keys=on").also { d ->
            SecureDatabasePragmas.STATEMENTS.forEach { runCatching { d.execute(null, it, 0).value } }
        }
        rootCrypto = CountingCrypto(OWNER, rootGate, refuse = rootRefuses)
        manifestCrypto = CountingCrypto(OWNER, refuse = manifestRefuses)
        val ownerCrypto = CountingCrypto(OWNER)
        // The backup envelope is signed by the owner's identity too; it gets
        // its own instance so an export can be parked without stalling the
        // ledger signer as well.
        backupCrypto = CountingCrypto(OWNER, backupGate)
        repo = SecureDbSharingRepository(
            database = SecureDatabase(driver),
            vault = AeadSharingKeyVault(InMemoryWrappingKeyStore()),
            verifier = SharingLedgerSigner(TaggedCrypto(OWNER)).verifier(),
            ownerPolicyVerifier = OwnerPolicySigner(TaggedCrypto(OWNER)).verifier(),
            ownerNpub = OWNER,
            deviceManifestVerifier = DeviceManifestSigner(TaggedCrypto(OWNER)).verifier(),
            attestationVerifier = TestDeviceAuthority.attestationSigner().verifier(),
            rootRecoveryVerifier = rootCrypto,
            localDeviceIdentity = {
                com.cruxcoach.android.sharing.DeviceIdentity(LAPTOP, TestDeviceAuthority.publicKeyOf())
            },
        )
        controller = SharingController(
            repository = repo,
            signer = AsyncSharingLedgerSigner(ownerCrypto),
            ownerPolicySigner = AsyncOwnerPolicySigner(ownerCrypto),
            ownerNpub = OWNER,
            authorityDevice = LAPTOP,
            authorityDevicePublicKey = TestDeviceAuthority.publicKeyOf(),
            attestationSigner = TestDeviceAuthority.attestationSigner(),
            manifestSigner = AsyncDeviceManifestSigner(manifestCrypto),
            rootCrypto = rootCrypto,
            backupCrypto = backupCrypto,
        )
        viewModel = SharingViewModel(controller).also { it.ioContext = dispatcher }
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val tmp = Files.createTempDirectory("cruxcoach-recovery-routing-")
        dbFile = tmp.resolve("secure.db").toFile()
        open()
        SecureDatabase.Schema.create(driver)
        TestDeviceAuthority.enrol(repo, TaggedCrypto(OWNER), OWNER, LAPTOP)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        runCatching { driver.close() }
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    /** A second device of the owner's, so fencing has something to fence. */
    private suspend fun enrolPhone() {
        controller.enrolDevice(PHONE, TestDeviceAuthority.publicKeyOf(PHONE), DeviceRole.TRUSTED)
        assertEquals(
            DeviceRole.TRUSTED,
            repo.loadDeviceAuthority().roleOf(PHONE),
            "the fixture needs a second device to fence",
        )
    }

    /** Generates a code and runs the screen's restore action with it. */
    private suspend fun TestScope.restoreThroughTheScreen(): Long {
        viewModel.newRecoveryCode()
        advanceUntilIdle()
        val code = assertNotNull(viewModel.recoveryCode.value, "the screen needs a code to restore with")
        val before = repo.loadDeviceAuthority().authorityGeneration
        viewModel.restore(code)
        advanceUntilIdle()
        return before
    }

    // ------------------------------------------------------ the restore door

    @Test
    fun `the visible restore action asks the owner to authorise it`() = runTest(dispatcher) {
        enrolPhone()
        val rootBefore = rootCrypto.prompts

        restoreThroughTheScreen()

        assertTrue(
            rootCrypto.prompts > rootBefore,
            "the screen restored without ever asking the owner's root key to authorise it",
        )
    }

    @Test
    fun `the visible restore action advances the authority generation`() = runTest(dispatcher) {
        enrolPhone()

        val before = restoreThroughTheScreen()

        assertTrue(
            repo.loadDeviceAuthority().authorityGeneration > before,
            "a restore that leaves the generation where it was has re-established nothing",
        )
    }

    @Test
    fun `the visible restore action leaves this device the only one with authority`() =
        runTest(dispatcher) {
            enrolPhone()

            restoreThroughTheScreen()

            val manifest = repo.loadDeviceAuthority()
            assertEquals(
                DeviceRole.PRIMARY,
                manifest.roleOf(LAPTOP),
                "the device that recovered must hold authority afterwards",
            )
            assertNull(
                manifest.roleOf(PHONE),
                "a device from before the restore must be fenced, not left able to write",
            )
        }

    @Test
    fun `a fenced device is still listed, so the screen can say why it stopped`() =
        runTest(dispatcher) {
            enrolPhone()

            restoreThroughTheScreen()

            val phone = assertNotNull(
                controller.deviceEstate().devices.firstOrNull { it.device == PHONE },
            )
            assertTrue(phone.fenced, "the estate has to be able to explain a device that went quiet")
            assertTrue(phone.capabilities.isEmpty())
        }

    /** No root signature, no restore — whatever else was proved. */
    @Test
    fun `a refused root signature restores nothing`() = runTest(dispatcher) {
        enrolPhone()
        // Re-opened against the same file, so the estate above is still there;
        // only the root signer changes.
        open(manifestRefuses = true)

        val before = restoreThroughTheScreen()

        assertEquals(
            before,
            repo.loadDeviceAuthority().authorityGeneration,
            "a recovery whose manifest could not be signed must change nothing",
        )
        assertEquals(DeviceRole.TRUSTED, repo.loadDeviceAuthority().roleOf(PHONE))
    }

    // -------------------------------------------------- device-action results

    /**
     * §2.4a: every mutation reports. These three went through a helper that
     * takes a `suspend () -> Unit`, so the `SharingWriteResult` each of them
     * returns was dropped on the floor — a refused signer, a recovery lock or a
     * device that may not administer all looked identical to success on the
     * device screen.
     */
    @Test
    fun `a refused own-device revocation is reported to the screen`() = runTest(dispatcher) {
        enrolPhone()
        repo.setAdministrativeWritesLocked(true, "a stale backup", 1)

        viewModel.revokeOwnDevice(PHONE)
        advanceUntilIdle()

        assertEquals(
            SharingWriteError.RECOVERY_LOCKED,
            viewModel.writeError.value,
            "a revocation the lock refused was invisible on the device screen",
        )
    }

    @Test
    fun `a refused role change is reported to the screen`() = runTest(dispatcher) {
        enrolPhone()
        repo.setAdministrativeWritesLocked(true, "a stale backup", 1)

        viewModel.changeDeviceRole(PHONE, DeviceRole.READ_ONLY)
        advanceUntilIdle()

        assertEquals(SharingWriteError.RECOVERY_LOCKED, viewModel.writeError.value)
    }

    @Test
    fun `a refused genesis enrolment is reported to the screen`() = runTest(dispatcher) {
        // A manifest already exists, so a genesis is refused as NOT_AUTHORISED.
        viewModel.enrolGenesisDevice()
        advanceUntilIdle()

        assertEquals(
            SharingWriteError.NOT_AUTHORISED,
            viewModel.writeError.value,
            "a genesis that could not run said nothing at all",
        )
    }

    @Test
    fun `a device action that succeeds reports no error`() = runTest(dispatcher) {
        enrolPhone()

        viewModel.changeDeviceRole(PHONE, DeviceRole.READ_ONLY)
        advanceUntilIdle()

        assertNull(viewModel.writeError.value)
        assertEquals(DeviceRole.READ_ONLY, repo.loadDeviceAuthority().roleOf(PHONE))
    }

    // ------------------------------------------------ the recovery-code race

    /**
     * The file is encrypted with the code that was showing when the export
     * started. If the code can be replaced while the signer is still being
     * waited for, the screen ends up showing a code that does not open the file
     * that was just written — and nothing says so.
     */
    @Test
    fun `a new code cannot replace the one an export is already using`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Boolean>()
        open(backupGate = gate)

        viewModel.newRecoveryCode()
        advanceUntilIdle()
        val exporting = assertNotNull(viewModel.recoveryCode.value)

        val export = kotlinx.coroutines.CoroutineScope(dispatcher).async { viewModel.exportBackupBytes() }
        advanceUntilIdle()

        // The person taps "new code" while the signer prompt is still open.
        viewModel.newRecoveryCode()
        advanceUntilIdle()

        assertEquals(
            exporting,
            viewModel.recoveryCode.value,
            "the code was rotated out from under a backup that is still being signed",
        )

        gate.complete(true)
        advanceUntilIdle()
        val bytes = assertNotNull(export.await(), "the export itself must still succeed")
        assertTrue(bytes.isNotEmpty())
    }

    @Test
    fun `a new code can still be generated when nothing is in flight`() = runTest(dispatcher) {
        viewModel.newRecoveryCode()
        advanceUntilIdle()
        val first = assertNotNull(viewModel.recoveryCode.value)

        viewModel.newRecoveryCode()
        advanceUntilIdle()

        assertNotNull(viewModel.recoveryCode.value)
        assertTrue(
            viewModel.recoveryCode.value != first,
            "rotating the code is the ordinary case and must still work",
        )
    }

    // ------------------------------------------------------ memory hygiene

    /**
     * §5: a preview keeps the file and the code in memory so the person can get
     * past it from where they are. Confirm and cancel both wipe the bytes; the
     * view model going away did not.
     */
    @Test
    fun `clearing the view model wipes a pending preview's bytes`() = runTest(dispatcher) {
        val bytes = ByteArray(64) { 7 }
        viewModel.newRecoveryCode()
        advanceUntilIdle()
        val code = assertNotNull(viewModel.recoveryCode.value)

        // A file that is not a backup previews nothing, so the retry is seeded
        // directly — the point here is the wipe, not how the retry got there.
        viewModel.seedPendingRetryForTest(bytes, code)
        assertNotNull(viewModel.pendingRetryForTest(), "the fixture needs something to wipe")

        SharingViewModel::class.java.getDeclaredMethod("onCleared")
            .apply { isAccessible = true }
            .invoke(viewModel)

        assertTrue(
            bytes.all { it == 0.toByte() },
            "a backup left in memory when the screen went away was never wiped",
        )
        assertNull(viewModel.pendingRetryForTest())
    }

    // ------------------------------------------- telling refusals apart

    /**
     * A declined signer is not a wrong code, and the screen must not say it is.
     *
     * Every non-applied outcome was mapped to `WRONG_CODE`, so somebody who
     * typed their code correctly and then declined the Amber prompt — or whose
     * prompt was cancelled by a rotation — was told the code was wrong. They
     * would retype a correct code for as long as they had patience.
     */
    @Test
    fun `a declined root signature is not reported as a wrong code`() = runTest(dispatcher) {
        open(rootRefuses = true)

        viewModel.newRecoveryCode()
        advanceUntilIdle()
        val code = assertNotNull(viewModel.recoveryCode.value)

        viewModel.restore(code)
        advanceUntilIdle()

        assertTrue(
            viewModel.recoveryMessage.value != SharingViewModel.RecoveryMessage.WRONG_CODE,
            "the code was correct and the signer refused; saying 'wrong code' sends the person " +
                "to fix the one thing that was right",
        )
        assertEquals(
            SharingViewModel.RecoveryMessage.SIGNER_REFUSED,
            viewModel.recoveryMessage.value,
        )
    }

    /** And a genuinely wrong code still says so. */
    @Test
    fun `a wrong code is still reported as a wrong code`() = runTest(dispatcher) {
        viewModel.newRecoveryCode()
        advanceUntilIdle()

        viewModel.restore("WRONG-CODE-HERE-XXXX-YYYY-ZZZZ")
        advanceUntilIdle()

        assertEquals(
            SharingViewModel.RecoveryMessage.WRONG_CODE,
            viewModel.recoveryMessage.value,
        )
    }

    @Test
    fun `a declined root signature is not a wrong code for a sovereign reset either`() =
        runTest(dispatcher) {
            open(rootRefuses = true)

            viewModel.newRecoveryCode()
            advanceUntilIdle()
            viewModel.startSovereignReset()
            advanceUntilIdle()

            assertEquals(
                SharingViewModel.RecoveryMessage.SIGNER_REFUSED,
                viewModel.recoveryMessage.value,
                "the reset path maps every refusal the same way the restore path did",
            )
        }

    // --------------------------------------------- wiping on every way out

    /**
     * The confirm path wiped its bytes on the line after the suspending import,
     * which is the one line a cancellation guarantees is never reached.
     */
    @Test
    fun `a cancelled preview retry still wipes its bytes`() = runTest(dispatcher) {
        val bytes = ByteArray(64) { 9 }
        viewModel.newRecoveryCode()
        advanceUntilIdle()
        val code = assertNotNull(viewModel.recoveryCode.value)
        viewModel.seedPendingRetryForTest(bytes, code)

        val job = assertNotNull(viewModel.confirmPreviewRetry())
        job.cancel()
        advanceUntilIdle()

        assertTrue(
            bytes.all { it == 0.toByte() },
            "a retry taken out of the field and then cancelled left the whole backup in memory, " +
                "and onCleared could no longer find it either",
        )
    }

    /**
     * A second import while an offer is standing replaced the retry and dropped
     * the old array on the floor.
     */
    @Test
    fun `replacing a standing preview offer wipes the file it replaces`() = runTest(dispatcher) {
        val first = ByteArray(64) { 3 }
        viewModel.newRecoveryCode()
        advanceUntilIdle()
        val code = assertNotNull(viewModel.recoveryCode.value)
        viewModel.seedPendingRetryForTest(first, code)

        // Anything that is not a readable backup resolves to a refusal, which
        // clears the offer — the path that used to drop the bytes silently.
        viewModel.importBackupBytes(ByteArray(32) { 1 }, code)
        advanceUntilIdle()

        assertNull(viewModel.pendingRetryForTest(), "the offer is gone")
        assertTrue(
            first.all { it == 0.toByte() },
            "the file the offer was holding was discarded without being wiped",
        )
    }

    /** Exactly-once on confirm is unchanged by any of the wiping. */
    @Test
    fun `a preview retry can only be confirmed once`() = runTest(dispatcher) {
        val bytes = ByteArray(64) { 5 }
        viewModel.newRecoveryCode()
        advanceUntilIdle()
        val code = assertNotNull(viewModel.recoveryCode.value)
        viewModel.seedPendingRetryForTest(bytes, code)

        assertNotNull(viewModel.confirmPreviewRetry())
        assertNull(viewModel.confirmPreviewRetry(), "a second tap must find nothing to do")
        advanceUntilIdle()
    }

    /**
     * The import path had the same conflation with a worse ending: every
     * non-preview failure became `FAILED` with no error, and the label for "no
     * error" is *the file is damaged*. So declining the prompt told the person
     * their backup was corrupt — about the one artefact they cannot replace.
     */
    @Test
    fun `a declined root signature does not report the backup as damaged`() = runTest(dispatcher) {
        viewModel.newRecoveryCode()
        advanceUntilIdle()
        val code = assertNotNull(viewModel.recoveryCode.value)
        val bytes = assertNotNull(viewModel.exportBackupBytes(), "the fixture needs a real backup")
        advanceUntilIdle()

        // Same database and same file; only the root signer changes.
        open(rootRefuses = true)
        viewModel.importBackupBytes(bytes, code)
        advanceUntilIdle()

        assertEquals(
            SharingViewModel.BackupMessage.FAILED,
            viewModel.backupMessage.value,
        )
        assertEquals(
            com.cruxcoach.domain.sharing.SharingBackupError.SIGNER_UNAVAILABLE,
            viewModel.backupError.value,
            "a declined prompt is not a damaged file, and the person can act on the difference",
        )
    }

    // ------------------------------------------- fail fast on the credential

    /**
     * A wrong code must not cost an Amber prompt.
     *
     * The root challenge was signed before `codeMatches` was allowed to stop
     * anything: the code was folded into `rootSigned` only *after* the signature
     * came back. So mistyping the code sent the person to another app to
     * authorise a recovery that had already been decided against — and the
     * thing they were being asked to authorise was a takeover of their estate.
     * Teaching people to approve prompts for attempts that cannot succeed is
     * the opposite of what a root signature is for.
     */
    @Test
    fun `a wrong code never reaches the root signer`() = runTest(dispatcher) {
        viewModel.newRecoveryCode()
        advanceUntilIdle()
        val before = rootCrypto.prompts

        viewModel.restore("WRONG-CODE-HERE-XXXX-YYYY-ZZZZ")
        advanceUntilIdle()

        assertEquals(
            before,
            rootCrypto.prompts,
            "the owner was asked to authorise a recovery whose code had already failed",
        )
        assertEquals(SharingViewModel.RecoveryMessage.WRONG_CODE, viewModel.recoveryMessage.value)
    }

    @Test
    fun `a wrong code writes nothing at all`() = runTest(dispatcher) {
        enrolPhone()
        val generation = repo.loadDeviceAuthority().authorityGeneration
        val manifest = repo.loadDeviceManifest().size
        viewModel.newRecoveryCode()
        advanceUntilIdle()

        viewModel.restore("WRONG-CODE-HERE-XXXX-YYYY-ZZZZ")
        advanceUntilIdle()

        assertEquals(generation, repo.loadDeviceAuthority().authorityGeneration)
        assertEquals(manifest, repo.loadDeviceManifest().size)
        assertEquals(DeviceRole.TRUSTED, repo.loadDeviceAuthority().roleOf(PHONE))
    }

    /** The same fail-fast for a sovereign reset, which is the destructive one. */
    @Test
    fun `a wrong code never reaches the root signer for a reset either`() = runTest(dispatcher) {
        viewModel.newRecoveryCode()
        advanceUntilIdle()
        val before = rootCrypto.prompts

        controller.recoverThisDevice(
            entered = "WRONG-CODE-HERE-XXXX-YYYY-ZZZZ",
            expected = assertNotNull(viewModel.recoveryCode.value),
            sovereignReset = true,
        )
        advanceUntilIdle()

        assertEquals(before, rootCrypto.prompts)
    }

    /**
     * And a local precondition that cannot be met is found before the prompt
     * too: nothing here needs the owner's key to discover that this install has
     * no signer wired.
     */
    @Test
    fun `a missing manifest signer is found before anybody is prompted`() = runTest(dispatcher) {
        val noManifestSigner = SharingController(
            repository = repo,
            signer = AsyncSharingLedgerSigner(CountingCrypto(OWNER)),
            ownerPolicySigner = AsyncOwnerPolicySigner(CountingCrypto(OWNER)),
            ownerNpub = OWNER,
            authorityDevice = LAPTOP,
            authorityDevicePublicKey = TestDeviceAuthority.publicKeyOf(),
            attestationSigner = TestDeviceAuthority.attestationSigner(),
            manifestSigner = null,
            rootCrypto = rootCrypto,
        )
        val code = com.cruxcoach.domain.sharing.SharingRecoveryCode
            .fromEntropy(ByteArray(20) { 11 })
        val before = rootCrypto.prompts

        val outcome = noManifestSigner.recoverThisDevice(code, code)

        assertEquals(
            before,
            rootCrypto.prompts,
            "an install with no manifest signer cannot recover, and does not need a signature to say so",
        )
        assertEquals(
            com.cruxcoach.android.sharing.SharingRecoveryRefusal.SIGNER_UNAVAILABLE,
            outcome.refusal,
        )
    }

    // ------------------------------------ every refusal carries its reason

    /**
     * The import's own PREVIEW_ONLY short path built its outcome by hand and
     * left `refusal` at its default, so the one claim the type makes — a
     * non-applied outcome always says why — was false on the path a stale
     * backup actually takes. The view model happened to survive it by reading
     * `decision` separately, which is the duplication the reason exists to
     * remove.
     */
    @Test
    fun `a preview says it is a preview`() = runTest(dispatcher) {
        viewModel.newRecoveryCode()
        advanceUntilIdle()
        val code = assertNotNull(viewModel.recoveryCode.value)
        val bytes = assertNotNull(viewModel.exportBackupBytes())
        advanceUntilIdle()

        // A fresh install has nothing to compare the file against, so freshness
        // is UNKNOWN and the gate offers a preview — the real stale path.
        driver.close()
        dbFile.delete()
        open()
        SecureDatabase.Schema.create(driver)
        TestDeviceAuthority.enrol(repo, TaggedCrypto(OWNER), OWNER, LAPTOP)

        val outcome = controller.importRecovery(bytes, code)

        assertEquals(
            com.cruxcoach.domain.sharing.RecoveryDecision.PREVIEW_ONLY,
            outcome.decision,
            "the fixture must actually produce a preview",
        )
        assertEquals(
            com.cruxcoach.android.sharing.SharingRecoveryRefusal.PREVIEW_ONLY,
            outcome.refusal,
            "a non-applied outcome that does not say why leaves every reader guessing",
        )
    }

    /**
     * The invariant itself, so a new construction cannot quietly reintroduce
     * the gap: nothing that did not apply may be silent about it.
     */
    @Test
    fun `no refusal on any recovery door is left unexplained`() = runTest(dispatcher) {
        val outcomes = mutableListOf<Pair<String, com.cruxcoach.android.sharing.SharingRecoveryOutcome>>()

        viewModel.newRecoveryCode()
        advanceUntilIdle()
        val code = assertNotNull(viewModel.recoveryCode.value)

        outcomes += "wrong code" to controller.recoverThisDevice("WRONG-CODE-HERE-XXXX-YYYY-ZZZZ", code)
        outcomes += "not a backup" to controller.importRecovery(ByteArray(32) { 1 }, code)
        val bytes = assertNotNull(viewModel.exportBackupBytes())
        advanceUntilIdle()
        outcomes += "preview" to run {
            driver.close(); dbFile.delete(); open()
            SecureDatabase.Schema.create(driver)
            TestDeviceAuthority.enrol(repo, TaggedCrypto(OWNER), OWNER, LAPTOP)
            controller.importRecovery(bytes, code)
        }
        open(rootRefuses = true)
        outcomes += "declined root" to controller.recoverThisDevice(code, code)

        outcomes.forEach { (name, outcome) ->
            assertTrue(!outcome.applied, "$name was expected to refuse")
            assertNotNull(
                outcome.refusal,
                "$name refused without saying why; the type promises a reason for every refusal",
            )
        }
    }
}
