package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.android.ui.sharing.SharingViewModel
import com.cruxcoach.data.SecureDatabasePragmas
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.AeadSharingKeyVault
import com.cruxcoach.domain.sharing.AsyncDeviceManifestSigner
import com.cruxcoach.domain.sharing.AsyncOwnerPolicySigner
import com.cruxcoach.domain.sharing.AsyncSharingLedgerSigner
import com.cruxcoach.domain.sharing.AuthorityDeviceId
import com.cruxcoach.domain.sharing.DeviceManifestSigner
import com.cruxcoach.domain.sharing.DeviceRole
import com.cruxcoach.domain.sharing.InMemoryWrappingKeyStore
import com.cruxcoach.domain.sharing.LedgerCrypto
import com.cruxcoach.domain.sharing.PeerId
import com.cruxcoach.domain.sharing.SharingCircle
import com.cruxcoach.domain.sharing.SharingRecoveryCode
import com.cruxcoach.domain.sharing.asAsync
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * FEAT-062 §11: what a person can actually do after a read-only preview.
 *
 * The preview is the right answer — a fresh install cannot show a backup is
 * current, and applying it anyway would re-grant access that was withdrawn.
 * But it left the person on a dead end: their permissions visible, nothing
 * changeable, and the only way forward buried in an unrelated screen with no
 * hint that it was the way forward.
 *
 * So the preview offers the one thing that gets past it, with the price named
 * before the button exists, confirmed deliberately, and exactly once.
 */
class SharingPreviewRetryTest {

    private companion object {
        const val OWNER = "aa11bb22cc33dd44ee55ff66aa77bb88cc99dd00ee11ff22aa33bb44cc55dd66"
        val DEVICE = AuthorityDeviceId("preview-retry-device")
    }

    private val alice = PeerId("npub1alice")
    private val code = SharingRecoveryCode.fromEntropy(ByteArray(20) { (it * 5 + 1).toByte() })
    private val dispatcher = StandardTestDispatcher()

    private lateinit var tmp: java.io.File
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

    private fun open(file: java.io.File, device: AuthorityDeviceId = DEVICE) {
        driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}?foreign_keys=on").also { d ->
            SecureDatabasePragmas.STATEMENTS.forEach { runCatching { d.execute(null, it, 0).value } }
        }
        val db = SecureDatabase(driver)
        val ownerCrypto = TaggedCrypto(OWNER)
        val signer = AsyncSharingLedgerSigner(ownerCrypto.asAsync())
        val policySigner = AsyncOwnerPolicySigner(ownerCrypto.asAsync())
        val attestationSigner = TestDeviceAuthority.attestationSigner(device)
        repo = SecureDbSharingRepository(
            database = db,
            vault = AeadSharingKeyVault(InMemoryWrappingKeyStore()),
            verifier = signer.verifier(),
            ownerPolicyVerifier = policySigner.verifier(),
            ownerNpub = OWNER,
            deviceManifestVerifier = DeviceManifestSigner(ownerCrypto).verifier(),
            attestationVerifier = attestationSigner.verifier(),
            // Which device this install is, and the key it signs with — both
            // bound at construction rather than taken per commit.
            localDeviceIdentity = { DeviceIdentity(device, TestDeviceAuthority.publicKeyOf(device)) },
            // Bound at construction, matching what the controller signs with.
            rootRecoveryVerifier = ownerCrypto.asAsync(),
        )
        controller = SharingController(
            repository = repo,
            signer = signer,
            ownerPolicySigner = policySigner,
            ownerNpub = OWNER,
            authorityDevice = device,
            attestationSigner = attestationSigner,
            manifestSigner = AsyncDeviceManifestSigner(ownerCrypto.asAsync()),
            authorityDevicePublicKey = TestDeviceAuthority.publicKeyOf(device),
            rootCrypto = ownerCrypto.asAsync(),
            backupCrypto = ownerCrypto.asAsync(),
        )
        viewModel = SharingViewModel(controller).also { it.ioContext = dispatcher }
    }

    private fun enrol() {
        TestDeviceAuthority.enrol(repo, TaggedCrypto(OWNER), OWNER, DEVICE, DeviceRole.PRIMARY)
    }

    @BeforeTest
    fun setUp() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        tmp = Files.createTempDirectory("cruxcoach-preview-retry-").toFile()
        dbFile = java.io.File(tmp, "secure.db")
        open(dbFile)
        SecureDatabase.Schema.create(driver)
        enrol()
        controller.invite(alice, SharingCircle.FRIENDS)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        runCatching { driver.close() }
        tmp.deleteRecursively()
    }

    /** Exports, then moves to a fresh install and imports — which previews. */
    private suspend fun previewOnFreshInstall(): ByteArray {
        val written = controller.exportBackup(code)
        check(written is SharingBackupWriteOutcome.Written)
        driver.close()
        open(java.io.File(tmp, "restored.db"))
        SecureDatabase.Schema.create(driver)
        viewModel.importBackupBytes(written.bytes, code)
        return written.bytes
    }

    // ------------------------------------------------------- the offer

    @Test
    fun `a preview offers the reset that gets past it`() = runTest(dispatcher) {
        previewOnFreshInstall()

        assertTrue(viewModel.previewRetryAvailable.value, "the way forward has to be on the screen")
        assertTrue(repo.administrativeWritesLocked())
    }

    @Test
    fun `a successful import offers no retry`() = runTest(dispatcher) {
        val written = controller.exportBackup(code)
        check(written is SharingBackupWriteOutcome.Written)

        // A deliberate takeover applies, so there is nothing left to offer.
        viewModel.importBackupBytes(written.bytes, code, sovereignReset = true)

        assertTrue(!viewModel.previewRetryAvailable.value)
    }

    // ------------------------------------------------------- cancelling

    @Test
    fun `cancelling the retry changes nothing at all`() = runTest(dispatcher) {
        previewOnFreshInstall()
        val manifestBefore = repo.loadDeviceManifest()

        viewModel.cancelPreviewRetry()

        assertTrue(!viewModel.previewRetryAvailable.value, "the offer is withdrawn")
        assertEquals(manifestBefore, repo.loadDeviceManifest(), "and nothing was written")
        assertTrue(repo.administrativeWritesLocked(), "the preview lock stays until it is resolved")
    }

    /** After cancelling there is nothing held to retry with. */
    @Test
    fun `cancelling releases the pending attempt`() = runTest(dispatcher) {
        previewOnFreshInstall()

        viewModel.cancelPreviewRetry()
        assertNull(viewModel.confirmPreviewRetry(), "a confirm after a cancel has nothing to do")

        assertEquals(emptyList(), repo.loadAttestations(), "a confirm after a cancel does nothing")
    }

    // ------------------------------------------------------- confirming

    @Test
    fun `confirming applies the reset with the same file and code`() = runTest(dispatcher) {
        previewOnFreshInstall()

        viewModel.confirmPreviewRetry()?.join()

        assertEquals(DeviceRole.PRIMARY, repo.loadDeviceAuthority().roleOf(DEVICE))
        assertTrue(!repo.administrativeWritesLocked(), "the lock is resolved by the reset")
        assertTrue(!viewModel.previewRetryAvailable.value)
    }

    /**
     * Exactly once. A second confirm — a double tap, a recomposition — must not
     * run a second reset, which would mint another generation and fence the
     * device the first one just enrolled.
     */
    @Test
    fun `confirming twice runs the reset once`() = runTest(dispatcher) {
        previewOnFreshInstall()

        viewModel.confirmPreviewRetry()?.join()
        val generationAfterFirst = repo.loadDeviceAuthority().authorityGeneration
        val manifestAfterFirst = repo.loadDeviceManifest()

        viewModel.confirmPreviewRetry()?.join()

        assertEquals(generationAfterFirst, repo.loadDeviceAuthority().authorityGeneration)
        assertEquals(manifestAfterFirst, repo.loadDeviceManifest())
    }

    /** Nothing is held once the attempt is over, either way. */
    @Test
    fun `the pending attempt is released after it is used`() = runTest(dispatcher) {
        previewOnFreshInstall()
        viewModel.confirmPreviewRetry()?.join()
        val after = repo.loadDeviceManifest()

        viewModel.confirmPreviewRetry()?.join()

        assertEquals(after, repo.loadDeviceManifest())
        assertNull(viewModel.pendingRetryForTest())
    }
}
