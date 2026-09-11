package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.SecureDatabasePragmas
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.AeadSharingKeyVault
import com.cruxcoach.domain.sharing.AsyncDeviceManifestSigner
import com.cruxcoach.domain.sharing.AsyncOwnerPolicySigner
import com.cruxcoach.domain.sharing.AsyncSharingLedgerSigner
import com.cruxcoach.domain.sharing.AuthorityDeviceId
import com.cruxcoach.domain.sharing.DeviceCapability
import com.cruxcoach.domain.sharing.DeviceManifestSigner
import com.cruxcoach.domain.sharing.DeviceRole
import com.cruxcoach.domain.sharing.InMemoryWrappingKeyStore
import com.cruxcoach.domain.sharing.LedgerCrypto
import com.cruxcoach.domain.sharing.PeerId
import com.cruxcoach.domain.sharing.SharingCategory
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
import kotlinx.coroutines.test.runTest

/**
 * FEAT-062 §11: administering the owner's own devices.
 *
 * Enrolling, re-roling and revoking a device all write the *manifest*, which
 * only the owner's root identity signs. That is the line the whole model rests
 * on: a device that could write the manifest could promote itself, so the
 * device signature says who asked and the root signature says it was allowed.
 */
class SharingDeviceAdministrationTest {

    private companion object {
        const val OWNER = "npub1owner"
        val LAPTOP = AuthorityDeviceId("laptop-1111")
        val PHONE = AuthorityDeviceId("phone-2222")
        val TABLET = AuthorityDeviceId("tablet-3333")
    }

    private val alice = PeerId("npub1alice")

    private lateinit var dbFile: java.io.File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repo: SecureDbSharingRepository
    private lateinit var controller: SharingController

    private class TaggedCrypto(private val identity: String) : LedgerCrypto {
        override fun sign(hash: ByteArray): ByteArray = (identity + ":").encodeToByteArray() + hash
        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
            signature.contentEquals((signerNpub + ":").encodeToByteArray() + hash)
    }

    /** A root signer that can be made to refuse, as an external one would. */
    private class RootCrypto(private val identity: String) :
        com.cruxcoach.domain.sharing.AsyncLedgerCrypto {
        var refuses = false
        override suspend fun signCanonical(hash: ByteArray): ByteArray? =
            if (refuses) null else (identity + ":").encodeToByteArray() + hash
        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
            signature.contentEquals((signerNpub + ":").encodeToByteArray() + hash)
    }

    private lateinit var rootCrypto: RootCrypto

    private fun open(device: AuthorityDeviceId = LAPTOP) {
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}?foreign_keys=on").also { d ->
            SecureDatabasePragmas.STATEMENTS.forEach { runCatching { d.execute(null, it, 0).value } }
        }
        val db = SecureDatabase(driver)
        val ownerCrypto = TaggedCrypto(OWNER)
        rootCrypto = RootCrypto(OWNER)
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
            localDeviceIdentity = { DeviceIdentity(device, key(device)) },
            // Bound at construction, matching what the controller signs with.
            rootRecoveryVerifier = rootCrypto,
        )
        controller = SharingController(
            repository = repo,
            signer = signer,
            ownerPolicySigner = policySigner,
            ownerNpub = OWNER,
            authorityDevice = device,
            attestationSigner = attestationSigner,
            manifestSigner = AsyncDeviceManifestSigner(rootCrypto),
            rootCrypto = rootCrypto,
        )
    }

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-device-admin-")
        dbFile = tmp.resolve("secure.db").toFile()
        open()
        SecureDatabase.Schema.create(driver)
        TestDeviceAuthority.enrol(repo, TaggedCrypto(OWNER), OWNER, LAPTOP)
    }

    @AfterTest
    fun tearDown() {
        runCatching { driver.close() }
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    private fun key(device: AuthorityDeviceId) = TestDeviceAuthority.publicKeyOf(device)

    // ------------------------------------------------------------ enrolling

    @Test
    fun `a primary device may enrol another`() = runTest {
        val result = controller.enrolDevice(PHONE, key(PHONE), DeviceRole.TRUSTED)

        assertTrue(result.isSuccess)
        assertEquals(DeviceRole.TRUSTED, repo.loadDeviceAuthority().roleOf(PHONE))
    }

    @Test
    fun `a trusted device may not enrol anything`() = runTest {
        controller.enrolDevice(PHONE, key(PHONE), DeviceRole.TRUSTED)

        driver.close()
        open(PHONE)
        val result = controller.enrolDevice(TABLET, key(TABLET), DeviceRole.TRUSTED)

        assertEquals(SharingWriteResult.Failed(SharingWriteError.NOT_AUTHORISED), result)
        assertNull(repo.loadDeviceAuthority().roleOf(TABLET))
    }

    @Test
    fun `a read-only device may not enrol anything`() = runTest {
        controller.enrolDevice(PHONE, key(PHONE), DeviceRole.READ_ONLY)

        driver.close()
        open(PHONE)

        assertEquals(
            SharingWriteResult.Failed(SharingWriteError.NOT_AUTHORISED),
            controller.enrolDevice(TABLET, key(TABLET), DeviceRole.TRUSTED),
        )
    }

    /**
     * The root signer declining leaves the manifest alone. It is append-only,
     * so a half-written enrolment is not something a later action can tidy up.
     */
    @Test
    fun `a declined root signature enrols nothing`() = runTest {
        rootCrypto.refuses = true

        val result = controller.enrolDevice(PHONE, key(PHONE), DeviceRole.TRUSTED)

        assertEquals(SharingWriteResult.Failed(SharingWriteError.SIGNER_UNAVAILABLE), result)
        assertNull(repo.loadDeviceAuthority().roleOf(PHONE))
        assertEquals(1, repo.loadDeviceManifest().size)
    }

    // --------------------------------------------------------------- roles

    @Test
    fun `a role can be changed and takes effect at once`() = runTest {
        controller.enrolDevice(PHONE, key(PHONE), DeviceRole.READ_ONLY)

        assertTrue(controller.changeDeviceRole(PHONE, DeviceRole.TRUSTED).isSuccess)

        val manifest = repo.loadDeviceAuthority()
        assertEquals(DeviceRole.TRUSTED, manifest.roleOf(PHONE))
        assertTrue(manifest.can(PHONE, DeviceCapability.MUTATE_PERMISSIONS))
        assertTrue(!manifest.can(PHONE, DeviceCapability.ADMINISTER_DEVICES))
    }

    @Test
    fun `each role carries exactly the capabilities it says it does`() = runTest {
        controller.enrolDevice(PHONE, key(PHONE), DeviceRole.TRUSTED)
        controller.enrolDevice(TABLET, key(TABLET), DeviceRole.READ_ONLY)
        val manifest = repo.loadDeviceAuthority()

        assertEquals(
            setOf(
                DeviceCapability.READ,
                DeviceCapability.MUTATE_PERMISSIONS,
                DeviceCapability.ADMINISTER_DEVICES,
                DeviceCapability.SOVEREIGN_RESET,
            ),
            assertNotNull(manifest.roleOf(LAPTOP)).capabilities,
        )
        assertEquals(
            setOf(DeviceCapability.READ, DeviceCapability.MUTATE_PERMISSIONS),
            assertNotNull(manifest.roleOf(PHONE)).capabilities,
        )
        assertEquals(setOf(DeviceCapability.READ), assertNotNull(manifest.roleOf(TABLET)).capabilities)
    }

    // ------------------------------------------------------------ revoking

    @Test
    fun `one device can be revoked without touching the others`() = runTest {
        controller.enrolDevice(PHONE, key(PHONE), DeviceRole.TRUSTED)
        controller.enrolDevice(TABLET, key(TABLET), DeviceRole.TRUSTED)

        assertTrue(controller.revokeOwnDevice(PHONE).isSuccess)

        val manifest = repo.loadDeviceAuthority()
        assertNull(manifest.roleOf(PHONE))
        assertEquals(DeviceRole.TRUSTED, manifest.roleOf(TABLET))
        assertEquals(DeviceRole.PRIMARY, manifest.roleOf(LAPTOP))
    }

    @Test
    fun `a revoked device cannot change permissions any more`() = runTest {
        controller.enrolDevice(PHONE, key(PHONE), DeviceRole.TRUSTED)
        controller.invite(alice, SharingCircle.FRIENDS)
        controller.revokeOwnDevice(PHONE)

        driver.close()
        open(PHONE)

        assertEquals(
            SharingWriteResult.Failed(SharingWriteError.NOT_AUTHORISED),
            controller.changeGrant(alice, setOf(SharingCategory.VIDEOS)),
        )
    }

    /** Sticky, exactly as a peer's revoked device is. */
    @Test
    fun `a revoked device cannot be enrolled again`() = runTest {
        controller.enrolDevice(PHONE, key(PHONE), DeviceRole.TRUSTED)
        controller.revokeOwnDevice(PHONE)

        controller.enrolDevice(PHONE, key(PHONE), DeviceRole.PRIMARY)

        assertNull(repo.loadDeviceAuthority().roleOf(PHONE), "revocation does not wear off")
    }

    @Test
    fun `revoking is refused from a device that may not administer`() = runTest {
        controller.enrolDevice(PHONE, key(PHONE), DeviceRole.TRUSTED)

        driver.close()
        open(PHONE)

        assertEquals(
            SharingWriteResult.Failed(SharingWriteError.NOT_AUTHORISED),
            controller.revokeOwnDevice(LAPTOP),
        )
        assertEquals(DeviceRole.PRIMARY, repo.loadDeviceAuthority().roleOf(LAPTOP))
    }

    // --------------------------------------------------- advancing authority

    /**
     * The other half of what `TRUSTED` may not do (§11.3).
     *
     * Enrolling and revoking are covered above. Advancing the authority
     * generation is the third `ADMINISTER_DEVICES` change and the most
     * consequential: a generation bump fences every other device, so a role that
     * could reach it could lock the primary out of its own estate. The capability
     * table already says `TRUSTED` lacks it; this asserts what that means where
     * a person can actually reach it — the restore path, which signs a fresh
     * device generation for every relationship.
     */
    @Test
    fun `a trusted device may not advance the authority generation`() = runTest {
        controller.enrolDevice(PHONE, key(PHONE), DeviceRole.TRUSTED)
        controller.invite(alice, SharingCircle.FRIENDS)
        val before = repo.loadProjection().relationships.getValue(alice).deviceGeneration

        driver.close()
        open(PHONE)

        val code = com.cruxcoach.domain.sharing.SharingRecoveryCode.fromEntropy(ByteArray(20) { 7 })
        assertEquals(
            SharingWriteResult.Failed(SharingWriteError.NOT_AUTHORISED),
            controller.advanceDeviceGenerations(code, code),
        )
        assertEquals(
            before,
            repo.loadProjection().relationships.getValue(alice).deviceGeneration,
            "a device that may not administer must not move the device generation",
        )
    }

    /** And it is refused before anybody is sent to a signer for it. */
    @Test
    fun `a trusted device is refused a generation advance before signing anything`() = runTest {
        controller.enrolDevice(PHONE, key(PHONE), DeviceRole.TRUSTED)
        controller.invite(alice, SharingCircle.FRIENDS)

        driver.close()
        open(PHONE)
        val entriesBefore = repo.loadWholeLedger().size
        rootCrypto.refuses = true

        val code = com.cruxcoach.domain.sharing.SharingRecoveryCode.fromEntropy(ByteArray(20) { 7 })
        assertEquals(
            SharingWriteResult.Failed(SharingWriteError.NOT_AUTHORISED),
            controller.advanceDeviceGenerations(code, code),
            "the answer is 'you may not', not 'the signer said no'",
        )
        assertEquals(entriesBefore, repo.loadWholeLedger().size, "and nothing was written")
    }

    // ---------------------------------------------------------- the estate

    @Test
    fun `the estate lists every device with its role and capabilities`() = runTest {
        controller.enrolDevice(PHONE, key(PHONE), DeviceRole.TRUSTED)

        val estate = controller.deviceEstate()

        assertEquals(2, estate.devices.size)
        val phone = assertNotNull(estate.devices.firstOrNull { it.device == PHONE })
        assertEquals(DeviceRole.TRUSTED, phone.role)
        assertTrue(DeviceCapability.MUTATE_PERMISSIONS in phone.capabilities)
        assertTrue(!phone.isThisDevice)
        assertTrue(assertNotNull(estate.devices.firstOrNull { it.device == LAPTOP }).isThisDevice)
    }

    @Test
    fun `a fenced device is still listed so the screen can explain it`() = runTest {
        controller.enrolDevice(PHONE, key(PHONE), DeviceRole.TRUSTED)
        controller.recoverThisDevice(
            entered = com.cruxcoach.domain.sharing.SharingRecoveryCode
                .fromEntropy(ByteArray(20) { 5 }),
            expected = com.cruxcoach.domain.sharing.SharingRecoveryCode
                .fromEntropy(ByteArray(20) { 5 }),
        )

        val estate = controller.deviceEstate()
        val phone = assertNotNull(estate.devices.firstOrNull { it.device == PHONE })
        assertTrue(phone.fenced)
        assertTrue(phone.capabilities.isEmpty(), "a fenced device holds nothing, whatever its role says")
    }

    @Test
    fun `the estate says what this device may do`() = runTest {
        assertTrue(controller.deviceEstate().canAdministerDevices)

        controller.enrolDevice(PHONE, key(PHONE), DeviceRole.TRUSTED)
        driver.close()
        open(PHONE)

        assertTrue(!controller.deviceEstate().canAdministerDevices)
        assertTrue(controller.deviceEstate().canMutatePermissions)
    }

    @Test
    fun `the estate reports a recovery lock`() = runTest {
        assertTrue(!controller.deviceEstate().administrativeWritesLocked)

        repo.setAdministrativeWritesLocked(true, "a stale backup", 1)

        assertTrue(controller.deviceEstate().administrativeWritesLocked)
        assertEquals("a stale backup", controller.deviceEstate().recoveryLockReason)
    }

    @Test
    fun `an install with no enrolled device says so rather than looking empty`() = runTest {
        driver.close()
        dbFile.delete()
        open()
        SecureDatabase.Schema.create(driver)

        val estate = controller.deviceEstate()

        assertEquals(emptyList(), estate.devices)
        assertTrue(!estate.canMutatePermissions)
        assertTrue(estate.needsEnrolment, "an estate with no device has to be told apart from a full one")
    }
}
