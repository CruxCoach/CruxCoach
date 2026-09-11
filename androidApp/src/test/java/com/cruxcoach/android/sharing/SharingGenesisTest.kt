package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.SecureDatabasePragmas
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.AeadSharingKeyVault
import com.cruxcoach.domain.sharing.AsyncDeviceManifestSigner
import com.cruxcoach.domain.sharing.AsyncLedgerCrypto
import com.cruxcoach.domain.sharing.AsyncOwnerPolicySigner
import com.cruxcoach.domain.sharing.AsyncSharingLedgerSigner
import com.cruxcoach.domain.sharing.AuthorityDeviceId
import com.cruxcoach.domain.sharing.DeviceCapability
import com.cruxcoach.domain.sharing.DeviceManifestSigner
import com.cruxcoach.domain.sharing.DeviceRole
import com.cruxcoach.domain.sharing.InMemoryWrappingKeyStore
import com.cruxcoach.domain.sharing.LedgerCrypto
import com.cruxcoach.domain.sharing.PeerId
import com.cruxcoach.domain.sharing.SharingCircle
import com.cruxcoach.domain.sharing.asAsync
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

/**
 * FEAT-062 §11: the first device on a fresh install.
 *
 * Without this the model is unreachable. Enrolling a device needs
 * `ADMINISTER_DEVICES`, which is held by devices the manifest names — and on a
 * fresh install the manifest names none. Every administrative path is then
 * closed for ever, on a release build, with no way out: the person can see
 * their permissions and change nothing.
 *
 * Genesis is the one entry the **root** identity authorises alone, because at
 * that moment there is by construction no device to authorise it. It is exactly
 * one entry, it enrols *this* install's own device as `PRIMARY`, and it is
 * atomic — a half-written genesis on an append-only ledger cannot be repaired.
 */
class SharingGenesisTest {

    private companion object {
        const val OWNER = "npub1owner"
    }

    private val alice = PeerId("npub1alice")

    private lateinit var dbFile: java.io.File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repo: SecureDbSharingRepository
    private lateinit var controller: SharingController
    private lateinit var rootCrypto: ScriptedRoot

    private class TaggedCrypto(private val identity: String) : LedgerCrypto {
        override fun sign(hash: ByteArray): ByteArray = (identity + ":").encodeToByteArray() + hash
        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
            signature.contentEquals((signerNpub + ":").encodeToByteArray() + hash)
    }

    /** The external root signer, as it behaves when somebody is holding it. */
    private class ScriptedRoot(private val identity: String) : AsyncLedgerCrypto {
        enum class Behaviour { SIGNS, REFUSES, TIMES_OUT, CANCELS }

        var behaviour = Behaviour.SIGNS
        var prompts = 0
            private set

        override suspend fun signCanonical(hash: ByteArray): ByteArray? {
            prompts++
            return when (behaviour) {
                Behaviour.SIGNS -> (identity + ":").encodeToByteArray() + hash
                Behaviour.REFUSES, Behaviour.TIMES_OUT -> null
                Behaviour.CANCELS -> throw CancellationException("the person walked away")
            }
        }

        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
            signature.contentEquals((signerNpub + ":").encodeToByteArray() + hash)
    }

    private val device = TestDeviceAuthority.DEVICE

    private fun open() {
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}?foreign_keys=on").also { d ->
            SecureDatabasePragmas.STATEMENTS.forEach { runCatching { d.execute(null, it, 0).value } }
        }
        val db = SecureDatabase(driver)
        val ownerCrypto = TaggedCrypto(OWNER)
        rootCrypto = ScriptedRoot(OWNER)
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
        )
        controller = SharingController(
            repository = repo,
            signer = signer,
            ownerPolicySigner = policySigner,
            ownerNpub = OWNER,
            authorityDevice = device,
            attestationSigner = attestationSigner,
            manifestSigner = AsyncDeviceManifestSigner(rootCrypto),
            authorityDevicePublicKey = TestDeviceAuthority.publicKeyOf(device),
            rootCrypto = rootCrypto,
        )
    }

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-genesis-")
        dbFile = tmp.resolve("secure.db").toFile()
        open()
        // A genuinely fresh install: schema, and nothing enrolled.
        SecureDatabase.Schema.create(driver)
    }

    @AfterTest
    fun tearDown() {
        runCatching { driver.close() }
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    // ------------------------------------------------ the state before genesis

    @Test
    fun `a fresh install holds no authority and says so`() {
        val estate = controller.deviceEstate()

        assertTrue(estate.needsEnrolment)
        assertTrue(!estate.canMutatePermissions)
        assertTrue(!estate.canAdministerDevices)
        assertTrue(estate.canEnrolGenesis, "the screen has to be able to offer the way out")
    }

    @Test
    fun `a fresh install cannot change a permission`() = runTest {
        assertEquals(
            SharingWriteResult.Failed(SharingWriteError.NOT_AUTHORISED),
            controller.invite(alice, SharingCircle.FRIENDS),
        )
    }

    // ------------------------------------------------------------- genesis

    @Test
    fun `genesis enrols this device as primary`() = runTest {
        assertTrue(controller.enrolGenesisDevice().isSuccess)

        val manifest = repo.loadDeviceAuthority()
        assertEquals(DeviceRole.PRIMARY, manifest.roleOf(device))
        assertEquals(1L, manifest.authorityGeneration)
    }

    @Test
    fun `genesis is exactly one manifest entry`() = runTest {
        controller.enrolGenesisDevice()

        assertEquals(1, repo.loadDeviceManifest().size)
        assertNull(repo.loadDeviceManifest().single().parent, "the first entry follows nothing")
    }

    @Test
    fun `the device can administer and mutate straight afterwards`() = runTest {
        controller.enrolGenesisDevice()

        val estate = controller.deviceEstate()
        assertTrue(!estate.needsEnrolment)
        assertTrue(estate.canMutatePermissions)
        assertTrue(estate.canAdministerDevices)
        assertTrue(!estate.canEnrolGenesis, "there is nothing left to bootstrap")
        assertTrue(controller.invite(alice, SharingCircle.FRIENDS).isSuccess)
    }

    @Test
    fun `genesis is signed by the root identity and not by the device`() = runTest {
        controller.enrolGenesisDevice()

        val entry = repo.loadDeviceManifest().single()
        assertEquals(OWNER, entry.signerNpub, "the manifest is the owner's, never a device's")
        assertTrue(rootCrypto.prompts > 0, "the root signer has to have been asked")
    }

    @Test
    fun `the enrolled key is this install's own`() = runTest {
        controller.enrolGenesisDevice()

        assertEquals(
            TestDeviceAuthority.publicKeyOf(device),
            assertNotNull(repo.loadDeviceAuthority().devices[device]).publicKey,
        )
        assertTrue(repo.loadDeviceAuthority().can(device, DeviceCapability.SOVEREIGN_RESET))
    }

    // --------------------------------------------------- zero writes on failure

    @Test
    fun `a refused root signature enrols nothing`() = runTest {
        rootCrypto.behaviour = ScriptedRoot.Behaviour.REFUSES

        val result = controller.enrolGenesisDevice()

        assertEquals(SharingWriteResult.Failed(SharingWriteError.SIGNER_UNAVAILABLE), result)
        assertEquals(emptyList(), repo.loadDeviceManifest())
        assertEquals(emptyList(), repo.loadAttestations())
        assertTrue(controller.deviceEstate().needsEnrolment)
    }

    @Test
    fun `a signer that never answers enrols nothing`() = runTest {
        rootCrypto.behaviour = ScriptedRoot.Behaviour.TIMES_OUT

        assertEquals(
            SharingWriteResult.Failed(SharingWriteError.SIGNER_UNAVAILABLE),
            controller.enrolGenesisDevice(),
        )
        assertEquals(emptyList(), repo.loadDeviceManifest())
    }

    @Test
    fun `a cancelled genesis enrols nothing`() = runTest {
        rootCrypto.behaviour = ScriptedRoot.Behaviour.CANCELS

        runCatching { controller.enrolGenesisDevice() }

        assertEquals(emptyList(), repo.loadDeviceManifest())
        assertTrue(controller.deviceEstate().needsEnrolment)
    }

    /** A failed genesis leaves the install able to try again. */
    @Test
    fun `a failed genesis can be retried`() = runTest {
        rootCrypto.behaviour = ScriptedRoot.Behaviour.REFUSES
        controller.enrolGenesisDevice()

        rootCrypto.behaviour = ScriptedRoot.Behaviour.SIGNS
        assertTrue(controller.enrolGenesisDevice().isSuccess)

        assertEquals(DeviceRole.PRIMARY, repo.loadDeviceAuthority().roleOf(device))
    }

    // ------------------------------------------------------------- restart

    @Test
    fun `genesis survives a restart`() = runTest {
        controller.enrolGenesisDevice()
        val manifest = repo.loadDeviceAuthority()

        driver.close()
        open()

        assertEquals(manifest, repo.loadDeviceAuthority())
        assertTrue(controller.deviceEstate().canAdministerDevices)
    }

    // -------------------------------------------------------- not a bypass

    /**
     * Genesis is only genesis. Once a manifest exists, the same call must not
     * be a way to mint a second primary without an ordinary, act-paired
     * enrolment.
     */
    @Test
    fun `genesis is refused once a manifest exists`() = runTest {
        controller.enrolGenesisDevice()
        val before = repo.loadDeviceManifest()

        val again = controller.enrolGenesisDevice()

        assertTrue(!again.isSuccess || repo.loadDeviceManifest() == before)
        assertEquals(before, repo.loadDeviceManifest(), "no second genesis")
    }

    @Test
    fun `a second device still needs an act-paired enrolment`() = runTest {
        controller.enrolGenesisDevice()
        val other = AuthorityDeviceId("other-device-9999")

        assertTrue(controller.enrolDevice(other, "pk-other", DeviceRole.TRUSTED).isSuccess)

        // Which means an act exists for it, unlike genesis.
        assertTrue(repo.loadAttestations().isNotEmpty())
    }
}
