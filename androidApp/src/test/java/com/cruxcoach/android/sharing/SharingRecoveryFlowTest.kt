package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.SecureDatabasePragmas
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.AeadSharingKeyVault
import com.cruxcoach.domain.sharing.AsyncOwnerPolicySigner
import com.cruxcoach.domain.sharing.AsyncSharingLedgerSigner
import com.cruxcoach.domain.sharing.AuthorityDeviceId
import com.cruxcoach.domain.sharing.DeviceCapability
import com.cruxcoach.domain.sharing.DeviceManifestSigner
import com.cruxcoach.domain.sharing.DeviceRole
import com.cruxcoach.domain.sharing.InMemoryWrappingKeyStore
import com.cruxcoach.domain.sharing.LedgerCrypto
import com.cruxcoach.domain.sharing.PeerId
import com.cruxcoach.domain.sharing.RecoveryDecision
import com.cruxcoach.domain.sharing.SharingCategory
import com.cruxcoach.domain.sharing.SharingCircle
import com.cruxcoach.domain.sharing.SharingRecoveryCode
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
 * FEAT-062 §11: the whole recovery path, end to end and on disk.
 *
 * A restore takes over an estate, so it has to be atomic in the strongest
 * sense: the new generation, the new PRIMARY device, the fencing of the old
 * ones and both rotated epochs land together or none of them do. Half of that
 * is worse than none — an install with a new generation and no enrolled device
 * can change nothing and cannot even fix itself.
 */
class SharingRecoveryFlowTest {

    private companion object {
        // 64 hex characters: the envelope binds the ciphertext to the
        // owner's key and refuses anything that is not one.
        const val OWNER = "aa11bb22cc33dd44ee55ff66aa77bb88cc99dd00ee11ff22aa33bb44cc55dd66"
        val OLD_DEVICE = AuthorityDeviceId("old-device-1111")
        val NEW_DEVICE = AuthorityDeviceId("new-device-2222")
    }

    private val alice = PeerId("npub1alice")
    private val bob = PeerId("npub1bob")
    private val code = SharingRecoveryCode.fromEntropy(ByteArray(20) { (it * 7 + 3).toByte() })

    private lateinit var dbFile: java.io.File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repo: SecureDbSharingRepository
    private lateinit var controller: SharingController

    /** Stands in for a signer whose holder declines the prompt. */
    private object RefusingCrypto : com.cruxcoach.domain.sharing.AsyncLedgerCrypto {
        override suspend fun signCanonical(hash: ByteArray): ByteArray? = null
        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) = false
    }

    /**
     * Answers the first [signsBefore] prompts and then declines.
     *
     * Which is how a person behaves: they approve the challenge, see what the
     * recovery is about to do, and change their mind.
     */
    private class RootRefusingAfter(
        private val identity: String,
        private val signsBefore: Int,
    ) : com.cruxcoach.domain.sharing.AsyncLedgerCrypto {
        private var seen = 0
        override suspend fun signCanonical(hash: ByteArray): ByteArray? {
            seen++
            return if (seen <= signsBefore) (identity + ":").encodeToByteArray() + hash else null
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
        device: AuthorityDeviceId = OLD_DEVICE,
        rootRefuses: Boolean = false,
        rootSignsBefore: Int? = null,
    ) {
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}?foreign_keys=on").also { d ->
            SecureDatabasePragmas.STATEMENTS.forEach { runCatching { d.execute(null, it, 0).value } }
        }
        val db = SecureDatabase(driver)
        val ownerCrypto = TaggedCrypto(OWNER)
        val signer = AsyncSharingLedgerSigner(ownerCrypto.asAsync())
        val policySigner = AsyncOwnerPolicySigner(ownerCrypto.asAsync())
        val attestationSigner = TestDeviceAuthority.attestationSigner(device)
        val rootSigner: com.cruxcoach.domain.sharing.AsyncLedgerCrypto = when {
            rootRefuses -> RefusingCrypto
            rootSignsBefore != null -> RootRefusingAfter(OWNER, rootSignsBefore)
            else -> ownerCrypto.asAsync()
        }
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
            rootRecoveryVerifier = rootSigner,
        )
        controller = SharingController(
            repository = repo,
            signer = signer,
            ownerPolicySigner = policySigner,
            ownerNpub = OWNER,
            authorityDevice = device,
            // The key this install holds. A fresh one is not in any
            // manifest yet, and the import must still know its own key.
            authorityDevicePublicKey = TestDeviceAuthority.publicKeyOf(device),
            attestationSigner = attestationSigner,
            manifestSigner = com.cruxcoach.domain.sharing.AsyncDeviceManifestSigner(rootSigner),
            rootCrypto = rootSigner,
            backupCrypto = ownerCrypto.asAsync(),
            peerSimulator = { peer -> TaggedCrypto(peer.value) },
        )
    }

    @BeforeTest
    fun setUp() = runTest {
        val tmp = Files.createTempDirectory("cruxcoach-recovery-flow-")
        dbFile = tmp.resolve("secure.db").toFile()
        open()
        SecureDatabase.Schema.create(driver)
        TestDeviceAuthority.enrol(repo, TaggedCrypto(OWNER), OWNER, OLD_DEVICE)
        controller.invite(alice, SharingCircle.FRIENDS)
        controller.invite(bob, SharingCircle.ACQUAINTANCES)
    }

    @AfterTest
    fun tearDown() {
        runCatching { driver.close() }
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    /** A brand-new install: its own database file, nothing enrolled. */
    private fun freshInstall(
        device: AuthorityDeviceId,
        rootRefuses: Boolean = false,
        rootSignsBefore: Int? = null,
    ) {
        dbFile = dbFile.parentFile!!.resolve("restored-${device.value}-${dbFile.name}")
        dbFile.delete()
        open(device, rootRefuses, rootSignsBefore)
        SecureDatabase.Schema.create(driver)
    }

    private suspend fun restore(
        entered: String = code,
        sovereignReset: Boolean = false,
        device: AuthorityDeviceId = NEW_DEVICE,
    ): SharingRecoveryOutcome {
        // A recovery happens on the returning device, so that is the identity
        // the controller runs as. It enrols itself; enrolling a key it does not
        // hold would write a manifest it could not then act under.
        driver.close()
        open(device)
        // The product's own door. It reads the returning device's key from this
        // install rather than taking one, which is the whole point: a caller
        // that could name a key could write a manifest enrolling one nobody
        // holds.
        return controller.recoverThisDevice(
            entered = entered,
            expected = code,
            sovereignReset = sovereignReset,
        )
    }

    // ------------------------------------------------------------- restoring

    @Test
    fun `a restore mints a generation and enrols the returning device`() = runTest {
        val outcome = restore()
        assertEquals(null, repo.lastBatchRejection, "the recovery batch must not roll back")

        assertEquals(RecoveryDecision.RESTORE, outcome.decision)
        val manifest = repo.loadDeviceAuthority()
        assertEquals(2L, manifest.authorityGeneration)
        assertEquals(DeviceRole.PRIMARY, manifest.roleOf(NEW_DEVICE))
    }

    @Test
    fun `the devices that were there before are fenced`() = runTest {
        restore()

        val manifest = repo.loadDeviceAuthority()
        assertNull(manifest.roleOf(OLD_DEVICE), "an old device holds nothing after a restore")
        assertTrue(assertNotNull(manifest.devices[OLD_DEVICE]).fenced)
        assertTrue(!manifest.can(OLD_DEVICE, DeviceCapability.MUTATE_PERMISSIONS))
    }

    @Test
    fun `both epochs move for every relationship`() = runTest {
        val before = repo.loadProjection().relationships

        restore()

        val after = repo.loadProjection().relationships
        listOf(alice, bob).forEach { peer ->
            assertTrue(
                after.getValue(peer).resourceEpoch > before.getValue(peer).resourceEpoch,
                "the resource epoch has to retire the keys the lost device could open",
            )
            assertTrue(after.getValue(peer).deviceGeneration > before.getValue(peer).deviceGeneration)
        }
    }

    @Test
    fun `everything a restore did survives a restart`() = runTest {
        restore()
        val manifest = repo.loadDeviceAuthority()
        val projection = repo.loadProjection()

        driver.close()
        open(NEW_DEVICE)

        assertEquals(manifest, repo.loadDeviceAuthority())
        assertEquals(projection, repo.loadProjection())
    }

    @Test
    fun `the restored device can change permissions and the fenced one cannot`() = runTest {
        restore()

        assertTrue(controller.changeGrant(alice, setOf(SharingCategory.VIDEOS)).isSuccess)

        driver.close()
        open(OLD_DEVICE)
        assertEquals(
            SharingWriteResult.Failed(SharingWriteError.NOT_AUTHORISED),
            controller.changeGrant(alice, setOf(SharingCategory.PRIVATE_NOTES)),
        )
    }

    // ------------------------------------------------------------ refusals

    @Test
    fun `a wrong recovery code changes nothing`() = runTest {
        val before = repo.loadDeviceManifest()

        val outcome = restore(entered = "WRONG-CODE-HERE-XXXX-YYYY-ZZZZ")

        assertEquals(RecoveryDecision.REFUSED, outcome.decision)
        assertEquals(before, repo.loadDeviceManifest())
        assertEquals(1L, repo.loadDeviceAuthority().authorityGeneration)
    }

    // -------------------------------------------------------------- preview

    @Test
    fun `a stale backup is a preview that writes nothing`() = runTest {
        val manifestBefore = repo.loadDeviceManifest()
        val projectionBefore = repo.loadProjection()

        // A preview is about a *file*. A code-only recovery has no backup and
        // so nothing that could be out of date — recoverThisDevice recovers the
        // estate this install already has.
        val outcome = importOntoKnownEstate(sovereignReset = false)

        assertEquals(RecoveryDecision.PREVIEW_ONLY, outcome.decision)
        assertEquals(manifestBefore, repo.loadDeviceManifest())
        assertEquals(projectionBefore, repo.loadProjection())
    }

    @Test
    fun `a preview locks administrative writes`() = runTest {
        importOntoKnownEstate(sovereignReset = false)

        assertTrue(repo.administrativeWritesLocked())
        // An administrative write is what the lock is for: nothing may change
        // which devices exist while a preview is open.
        assertEquals(
            SharingWriteResult.Failed(SharingWriteError.RECOVERY_LOCKED),
            controller.enrolDevice(NEW_DEVICE, TestDeviceAuthority.publicKeyOf(NEW_DEVICE), DeviceRole.TRUSTED),
        )
    }

    @Test
    fun `the lock survives a restart`() = runTest {
        importOntoKnownEstate(sovereignReset = false)

        driver.close()
        open(OLD_DEVICE)

        assertTrue(repo.administrativeWritesLocked(), "a lock a restart forgets is not a lock")
        assertEquals(
            SharingWriteResult.Failed(SharingWriteError.RECOVERY_LOCKED),
            controller.enrolDevice(NEW_DEVICE, TestDeviceAuthority.publicKeyOf(NEW_DEVICE), DeviceRole.TRUSTED),
        )
    }

    @Test
    fun `a preview still lets the state be read`() = runTest {
        importOntoKnownEstate(sovereignReset = false)

        assertEquals(2, controller.snapshot().peers.size)
        assertNotNull(controller.peerDetail(alice))
    }

    @Test
    fun `a current backup clears a lock an earlier preview set`() = runTest {
        importOntoKnownEstate(sovereignReset = false)
        assertTrue(repo.administrativeWritesLocked())

        // Only an explicit reset gets past a file nothing can show is current.
        importOntoKnownEstate(sovereignReset = true)

        assertTrue(!repo.administrativeWritesLocked())
        assertTrue(controller.changeGrant(alice, setOf(SharingCategory.VIDEOS)).isSuccess)
    }

    // ------------------------------------------------------- sovereign reset

    @Test
    fun `a sovereign reset gets past a stale backup`() = runTest {
        val outcome = restore(sovereignReset = true)

        assertEquals(RecoveryDecision.SOVEREIGN_RESET, outcome.decision)
        assertEquals(2L, repo.loadDeviceAuthority().authorityGeneration)
        assertEquals(DeviceRole.PRIMARY, repo.loadDeviceAuthority().roleOf(NEW_DEVICE))
        assertTrue(!repo.administrativeWritesLocked())
    }

    @Test
    fun `a sovereign reset revokes every relationship`() = runTest {
        restore(sovereignReset = true)

        repo.loadProjection().relationships.values.forEach { state ->
            assertEquals(
                emptySet(),
                state.consentedCategories,
                "a reset takes the grants with it",
            )
        }
    }

    @Test
    fun `a sovereign reset still needs the recovery code`() = runTest {
        val outcome = restore(
            entered = "WRONG-CODE-HERE-XXXX-YYYY-ZZZZ",
            sovereignReset = true,
        )

        assertEquals(RecoveryDecision.REFUSED, outcome.decision)
        assertEquals(1L, repo.loadDeviceAuthority().authorityGeneration)
    }

    @Test
    fun `a sovereign reset leaves exactly one usable device`() = runTest {
        restore(sovereignReset = true)

        val manifest = repo.loadDeviceAuthority()
        val usable = manifest.devices.keys.filter { manifest.roleOf(it) != null }
        assertEquals(listOf(NEW_DEVICE), usable)
    }

    // --------------------------------------------------------- atomicity

    /**
     * The signer walking away partway through must leave the old estate whole.
     * An install with a new generation and no enrolled device holds no
     * authority at all and cannot enrol one either.
     */
    @Test
    fun `a refused signature partway through leaves the old estate intact`() = runTest {
        val manifestBefore = repo.loadDeviceManifest()
        val authorityBefore = repo.loadDeviceAuthority()

        driver.close()
        // A signer that answers the challenge and then stops: the person walked
        // away from the second prompt. Injected as the signer the controller
        // was built with, rather than as an index a caller hands the recovery.
        open(NEW_DEVICE, rootSignsBefore = 2)
        val outcome = controller.recoverThisDevice(
            entered = code,
            expected = code,
            sovereignReset = false,
        )

        assertTrue(!outcome.applied)
        assertEquals(manifestBefore, repo.loadDeviceManifest())
        assertEquals(authorityBefore, repo.loadDeviceAuthority())
        assertEquals(DeviceRole.PRIMARY, repo.loadDeviceAuthority().roleOf(OLD_DEVICE))
    }

    // --------------------------------------------------------- native gate

    @Test
    fun `the native rotation is reported as planned rather than done`() = runTest {
        val outcome = restore()

        assertTrue(outcome.nativeRotation.planned)
        assertTrue(!outcome.nativeRotation.performed)
    }

    // --------------------------------------- importing a file is a recovery

    /**
     * The production path a person actually uses.
     *
     * Picking a file and typing a code used to restore outright: no root
     * signature, no freshness gate, no fencing, no epoch rotation. So a stolen
     * backup plus its code was a complete takeover, and an *old* backup plus
     * its code silently re-granted access that had since been withdrawn. It is
     * a recovery, and it goes through the same gate as one.
     */
    /**
     * Exports and imports on the same install, as a deliberate takeover.
     *
     * A sovereign reset is the only thing that writes from a file: holding a
     * manifest is not evidence of being in step with anything, so a plain
     * import previews whatever number the file carries.
     */
    private suspend fun importOntoKnownEstate(sovereignReset: Boolean = true): SharingRecoveryOutcome {
        val written = controller.exportBackup(code)
        check(written is SharingBackupWriteOutcome.Written) {
            "export failed: ${(written as SharingBackupWriteOutcome.Failed).error}"
        }
        return controller.importRecovery(written.bytes, code, sovereignReset = sovereignReset)
    }

    private suspend fun exportThenImport(
        backupGeneration: Long = 1,
        sovereignReset: Boolean = false,
        device: AuthorityDeviceId = NEW_DEVICE,
    ): SharingRecoveryOutcome {
        val written = controller.exportBackup(code)
        check(written is SharingBackupWriteOutcome.Written) {
            "export failed: ${(written as SharingBackupWriteOutcome.Failed).error}"
        }
        val bytes = written.bytes
        driver.close()
        freshInstall(device)
        // The generation comes from the file's own signed manifest and the
        // device key from this install's identity, so a caller supplies
        // neither. A fresh target has no synced head, which is a preview — the
        // tests that want a restore import onto an install that knows the
        // estate.
        return controller.importRecovery(bytes = bytes, entered = code, sovereignReset = sovereignReset)
    }

    /**
     * On an install that already holds this estate, so there is a head to
     * compare the file against. A fresh one has none and previews instead —
     * see the fresh-install tests below.
     */
    @Test
    fun `a current backup imports as a full restore`() = runTest {
        val outcome = importOntoKnownEstate()

        assertEquals(RecoveryDecision.SOVEREIGN_RESET, outcome.decision)
        assertTrue(outcome.applied)
        // The device that recovers is the one running, and it re-enrols itself.
        assertEquals(DeviceRole.PRIMARY, repo.loadDeviceAuthority().roleOf(OLD_DEVICE))
        assertEquals(2L, repo.loadDeviceAuthority().authorityGeneration)
    }

    @Test
    fun `importing fences the devices the backup came from`() = runTest {
        // A second device, so there is something to fence that is not the one
        // doing the recovering.
        controller.enrolDevice(NEW_DEVICE, TestDeviceAuthority.publicKeyOf(NEW_DEVICE), DeviceRole.TRUSTED)
        assertEquals(DeviceRole.TRUSTED, repo.loadDeviceAuthority().roleOf(NEW_DEVICE))

        importOntoKnownEstate()

        assertNull(repo.loadDeviceAuthority().roleOf(NEW_DEVICE), "the other device loses its say")
        assertTrue(assertNotNull(repo.loadDeviceAuthority().devices[NEW_DEVICE]).fenced)
        assertEquals(DeviceRole.PRIMARY, repo.loadDeviceAuthority().roleOf(OLD_DEVICE))
    }

    @Test
    fun `importing rotates both epochs`() = runTest {
        val before = repo.loadProjection().relationships.mapValues { it.value.resourceEpoch }

        importOntoKnownEstate()

        val after = repo.loadProjection().relationships
        before.keys.forEach { peer ->
            assertTrue(after.getValue(peer).resourceEpoch > before.getValue(peer))
        }
    }

    /** A stale file is a preview. The file plus the code is never enough. */
    @Test
    fun `a stale backup imports as a read-only preview`() = runTest {
        val outcome = exportThenImport()

        assertEquals(RecoveryDecision.PREVIEW_ONLY, outcome.decision)
        assertTrue(!outcome.applied)
        assertTrue(repo.administrativeWritesLocked())
        assertEquals(
            SharingWriteResult.Failed(SharingWriteError.RECOVERY_LOCKED),
            controller.changeGrant(alice, setOf(SharingCategory.VIDEOS)),
        )
    }

    @Test
    fun `an explicit sovereign reset imports past a stale backup`() = runTest {
        val outcome = exportThenImport(sovereignReset = true)

        assertEquals(RecoveryDecision.SOVEREIGN_RESET, outcome.decision)
        assertTrue(outcome.applied)
        assertEquals(DeviceRole.PRIMARY, repo.loadDeviceAuthority().roleOf(NEW_DEVICE))
    }

    @Test
    fun `a wrong code imports nothing even with a valid file`() = runTest {
        val bytes = (controller.exportBackup(code) as SharingBackupWriteOutcome.Written).bytes
        driver.close()
        freshInstall(NEW_DEVICE)

        val outcome = controller.importRecovery(
            bytes = bytes,
            entered = "WRONG-CODE-HERE-XXXX-YYYY-ZZZZ",
        )

        assertEquals(RecoveryDecision.REFUSED, outcome.decision)
        assertTrue(!outcome.applied)
        assertEquals(emptyList(), repo.loadLedger(alice))
    }

    @Test
    fun `a refused root signature imports nothing`() = runTest {
        val bytes = (controller.exportBackup(code) as SharingBackupWriteOutcome.Written).bytes
        driver.close()
        freshInstall(NEW_DEVICE, rootRefuses = true)

        val outcome = controller.importRecovery(
            bytes = bytes,
            entered = code,
        )

        assertTrue(!outcome.applied)
        assertEquals(emptyList(), repo.loadDeviceManifest())
        assertEquals(emptyList(), repo.loadAttestations())
    }

    @Test
    fun `a damaged file imports nothing and leaves no preview lock`() = runTest {
        val bytes = (controller.exportBackup(code) as SharingBackupWriteOutcome.Written).bytes
        val damaged = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x7f).toByte() }
        driver.close()
        freshInstall(NEW_DEVICE)

        val outcome = controller.importRecovery(
            bytes = damaged,
            entered = code,
        )

        assertTrue(!outcome.applied)
        assertTrue(!repo.administrativeWritesLocked(), "an unreadable file is not a preview of anything")
    }

    // ------------------------------------- the import trusts no caller

    /**
     * A fresh install has never seen this estate, so it cannot tell a current
     * backup from a year-old one — and a year-old one re-grants access that has
     * since been withdrawn. Not knowing is treated exactly like knowing it is
     * old: a preview, and nothing written.
     */
    @Test
    fun `a fresh install with no synced head previews rather than restores`() = runTest {
        val written = controller.exportBackup(code)
        check(written is SharingBackupWriteOutcome.Written)
        driver.close()
        freshInstall(NEW_DEVICE)

        val outcome = controller.importRecovery(written.bytes, code)

        assertEquals(RecoveryDecision.PREVIEW_ONLY, outcome.decision)
        assertTrue(!outcome.applied)
        assertEquals(emptyList(), repo.loadLedger(alice), "a preview writes no history")
        assertTrue(repo.administrativeWritesLocked())
    }

    /**
     * Only an explicit root-signed sovereign reset gets past that, and it costs
     * the whole estate.
     */
    @Test
    fun `a fresh install can still take over with an explicit sovereign reset`() = runTest {
        val written = controller.exportBackup(code)
        check(written is SharingBackupWriteOutcome.Written)
        driver.close()
        freshInstall(NEW_DEVICE)

        val outcome = controller.importRecovery(written.bytes, code, sovereignReset = true)

        assertEquals(RecoveryDecision.SOVEREIGN_RESET, outcome.decision)
        assertTrue(outcome.applied)
        assertEquals(DeviceRole.PRIMARY, repo.loadDeviceAuthority().roleOf(NEW_DEVICE))
    }

    /**
     * Holding the estate the file came from changes nothing.
     *
     * The manifest says this device once had an estate at that generation, not
     * that it has seen what other devices did since. Same evidence on both
     * sides, so it is still a preview.
     */
    @Test
    fun `an install that already knows the estate still only previews a file`() = runTest {
        val written = controller.exportBackup(code)
        check(written is SharingBackupWriteOutcome.Written)

        val outcome = controller.importRecovery(written.bytes, code)

        assertEquals(RecoveryDecision.PREVIEW_ONLY, outcome.decision)
        assertTrue(!outcome.applied)
    }

    /**
     * Even a preview is a decision about somebody's data, and it locks writes.
     * It happens only after the root identity has actually signed for it.
     */
    @Test
    fun `a refused root signature leaves no preview lock either`() = runTest {
        val written = controller.exportBackup(code)
        check(written is SharingBackupWriteOutcome.Written)
        driver.close()
        freshInstall(NEW_DEVICE, rootRefuses = true)

        val outcome = controller.importRecovery(written.bytes, code)

        assertEquals(RecoveryDecision.REFUSED, outcome.decision)
        assertTrue(!repo.administrativeWritesLocked(), "nothing was proved, so nothing is claimed")
    }

    @Test
    fun `a wrong code leaves no preview lock`() = runTest {
        val written = controller.exportBackup(code)
        check(written is SharingBackupWriteOutcome.Written)
        driver.close()
        freshInstall(NEW_DEVICE)

        val outcome = controller.importRecovery(written.bytes, "WRONG-CODE-HERE-XXXX-YYYY-ZZZZ")

        assertEquals(RecoveryDecision.REFUSED, outcome.decision)
        assertTrue(!repo.administrativeWritesLocked())
    }

    /** The device enrolled is this install's own, never one a caller named. */
    @Test
    fun `the enrolled key is the local identity`() = runTest {
        val written = controller.exportBackup(code)
        check(written is SharingBackupWriteOutcome.Written)
        driver.close()
        freshInstall(NEW_DEVICE)

        controller.importRecovery(written.bytes, code, sovereignReset = true)

        assertEquals(
            TestDeviceAuthority.publicKeyOf(NEW_DEVICE),
            assertNotNull(repo.loadDeviceAuthority().devices[NEW_DEVICE]).publicKey,
        )
    }

    // ---------------------------------------------------- one transaction

    /** Every byte of the database file, so nothing partial can hide in it. */
    private fun dbDigest(): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(dbFile.readBytes()).joinToString("") { b ->
            ((b.toInt() and 0xff) + 0x100).toString(16).substring(1)
        }
    }

    /**
     * A recovery is one transaction or it is nothing.
     *
     * The payload used to land before the manifest was signed, so declining a
     * later prompt left the backup restored and the estate not — an install
     * carrying somebody's whole permission history with no device able to
     * change any of it, and no way to tell that had happened.
     */
    @Test
    fun `a signature refused after the challenge leaves the database untouched`() = runTest {
        val written = controller.exportBackup(code)
        check(written is SharingBackupWriteOutcome.Written)
        driver.close()
        // Answers the challenge, then declines the manifest it is shown.
        freshInstall(NEW_DEVICE, rootSignsBefore = 1)

        val before = dbDigest()
        val outcome = controller.importRecovery(written.bytes, code, sovereignReset = true)

        assertTrue(!outcome.applied)
        assertEquals(before, dbDigest(), "a refused recovery must not change a single byte")
    }

    @Test
    fun `everything a recovery does lands together`() = runTest {
        val written = controller.exportBackup(code)
        check(written is SharingBackupWriteOutcome.Written)
        driver.close()
        freshInstall(NEW_DEVICE)

        val o = controller.importRecovery(written.bytes, code, sovereignReset = true)
        assertTrue(o.applied, "decision=${o.decision} batch=${repo.lastBatchRejection} restore=${repo.lastRestoreRejection}")

        // The payload, the rotation, the enrolment and the lock release, all
        // observable at once because they were written at once.
        assertEquals(DeviceRole.PRIMARY, repo.loadDeviceAuthority().roleOf(NEW_DEVICE))
        assertTrue(repo.loadLedger(alice).isNotEmpty())
        assertTrue(repo.loadAttestations().isNotEmpty())
        assertTrue(!repo.administrativeWritesLocked())
    }

    // ------------------------------------- a local manifest is not a sync

    /**
     * Holding a manifest is not evidence that this install is up to date.
     *
     * It said only that *this device* once had an estate at this generation. A
     * backup taken at the same number could still be missing every revocation
     * made since, because nothing has ever synced — the transport is gated
     * shut, so no install can currently show it has seen another device's
     * changes at all.
     *
     * Until a real signed sync record exists, importing a file is a preview.
     */
    @Test
    fun `a file matching the local generation is still only a preview`() = runTest {
        val written = controller.exportBackup(code)
        check(written is SharingBackupWriteOutcome.Written)

        // Same install, same generation the file claims: this used to restore.
        val outcome = controller.importRecovery(written.bytes, code)

        assertEquals(RecoveryDecision.PREVIEW_ONLY, outcome.decision)
        assertTrue(!outcome.applied, "an unsynced install may not write from a file")
        assertTrue(repo.administrativeWritesLocked())
    }

    @Test
    fun `only an explicit sovereign reset writes from a file`() = runTest {
        val written = controller.exportBackup(code)
        check(written is SharingBackupWriteOutcome.Written)

        val outcome = controller.importRecovery(written.bytes, code, sovereignReset = true)

        assertEquals(RecoveryDecision.SOVEREIGN_RESET, outcome.decision)
        assertTrue(outcome.applied)
    }

    /**
     * The in-app recovery — code plus root signature, no file — is unaffected.
     * It restores this install's own history rather than importing somebody
     * else's snapshot of it.
     */
    @Test
    fun `the in-app recovery still restores without a file`() = runTest {
        val outcome = restore()

        assertEquals(RecoveryDecision.RESTORE, outcome.decision)
        assertTrue(outcome.applied)
    }
}
