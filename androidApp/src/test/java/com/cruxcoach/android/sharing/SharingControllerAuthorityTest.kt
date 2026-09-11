package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.SecureDatabasePragmas
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.AeadSharingKeyVault
import com.cruxcoach.domain.sharing.AsyncAuthorityAttestationSigner
import com.cruxcoach.domain.sharing.AsyncLedgerCrypto
import com.cruxcoach.domain.sharing.AsyncOwnerPolicySigner
import com.cruxcoach.domain.sharing.AsyncSharingLedgerSigner
import com.cruxcoach.domain.sharing.AuthorityDeviceId
import com.cruxcoach.domain.sharing.AuthorityEffect
import com.cruxcoach.domain.sharing.AuthorityScope
import com.cruxcoach.domain.sharing.DeviceManifestBody
import com.cruxcoach.domain.sharing.DeviceManifestEntry
import com.cruxcoach.domain.sharing.DeviceManifestSigner
import com.cruxcoach.domain.sharing.DeviceRole
import com.cruxcoach.domain.sharing.InMemoryWrappingKeyStore
import com.cruxcoach.domain.sharing.LedgerCrypto
import com.cruxcoach.domain.sharing.LedgerEntryId
import com.cruxcoach.domain.sharing.OwnerPolicySigner
import com.cruxcoach.domain.sharing.PeerId
import com.cruxcoach.domain.sharing.SharingCategory
import com.cruxcoach.domain.sharing.SharingCircle
import com.cruxcoach.domain.sharing.SharingLedgerSigner
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
 * FEAT-062 §11: a permission change now has to say which device made it.
 *
 * Every administrative mutation writes one device-signed, parent-linked act
 * alongside the permission entry, in one transaction. The capability is checked
 * against the manifest before anything is signed, and a refused or abandoned
 * signature leaves the database exactly as it was.
 */
class SharingControllerAuthorityTest {

    private companion object {
        const val OWNER = "npub1owner"
        val LAPTOP = AuthorityDeviceId("aaaa1111")
        val PHONE = AuthorityDeviceId("bbbb2222")
    }

    private val alice = PeerId("npub1alice")

    private lateinit var dbFile: java.io.File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repository: SecureDbSharingRepository
    private lateinit var controller: SharingController

    private class TaggedCrypto(private val identity: String) : LedgerCrypto {
        override fun sign(hash: ByteArray): ByteArray = (identity + ":").encodeToByteArray() + hash
        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
            signature.contentEquals((signerNpub + ":").encodeToByteArray() + hash)
    }

    /** A signer that answers however the test needs it to. */
    private class ScriptedCrypto(
        private val identity: String,
        var behaviour: Behaviour = Behaviour.SIGNS,
    ) : AsyncLedgerCrypto {
        enum class Behaviour { SIGNS, REFUSES, CANCELS, TIMES_OUT }

        var prompts = 0
            private set

        override suspend fun signCanonical(hash: ByteArray): ByteArray? {
            prompts++
            return when (behaviour) {
                Behaviour.SIGNS -> (identity + ":").encodeToByteArray() + hash
                Behaviour.REFUSES -> null
                Behaviour.CANCELS -> throw CancellationException("the person walked away")
                // What Nip55LedgerCrypto returns when its own timeout elapses:
                // no answer, rather than an exception.
                Behaviour.TIMES_OUT -> null
            }
        }

        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
            signature.contentEquals((signerNpub + ":").encodeToByteArray() + hash)
    }

    private lateinit var deviceCrypto: ScriptedCrypto

    /** The device's public key, as the manifest records it. */
    private val laptopKey get() = LAPTOP.value

    private fun open(device: AuthorityDeviceId = LAPTOP) {
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}?foreign_keys=on").also { d ->
            SecureDatabasePragmas.STATEMENTS.forEach { runCatching { d.execute(null, it, 0).value } }
        }
        val db = SecureDatabase(driver)
        val ownerCrypto = TaggedCrypto(OWNER)
        val signer = AsyncSharingLedgerSigner(ownerCrypto.asAsync())
        val ownerPolicySigner = AsyncOwnerPolicySigner(ownerCrypto.asAsync())
        deviceCrypto = ScriptedCrypto(device.value)
        val attestationSigner = AsyncAuthorityAttestationSigner(deviceCrypto)
        repository = SecureDbSharingRepository(
            database = db,
            vault = AeadSharingKeyVault(InMemoryWrappingKeyStore()),
            verifier = signer.verifier(),
            ownerPolicyVerifier = ownerPolicySigner.verifier(),
            ownerNpub = OWNER,
            deviceManifestVerifier = DeviceManifestSigner(ownerCrypto).verifier(),
            attestationVerifier = attestationSigner.verifier(),
        )
        controller = SharingController(
            repository = repository,
            signer = signer,
            ownerPolicySigner = ownerPolicySigner,
            ownerNpub = OWNER,
            authorityDevice = device,
            attestationSigner = attestationSigner,
        )
    }

    /** Enrols LAPTOP as PRIMARY and PHONE as READ_ONLY. */
    private fun enrol() {
        val ownerSigner = DeviceManifestSigner(TaggedCrypto(OWNER))
        listOf(
            DeviceManifestBody.DeviceEnrolled(LAPTOP, LAPTOP.value, DeviceRole.PRIMARY),
            DeviceManifestBody.DeviceEnrolled(PHONE, PHONE.value, DeviceRole.READ_ONLY),
        ).forEachIndexed { index, body ->
            val seq = index + 1L
            val signed = ownerSigner.sign(
                DeviceManifestEntry(
                    id = LedgerEntryId("m-$seq"),
                    manifestSequence = seq,
                    authorityGeneration = 1,
                    parent = if (seq == 1L) null else LedgerEntryId("m-${seq - 1}"),
                    signerNpub = OWNER,
                    signature = "",
                    body = body,
                ),
            )!!
            // Every ordinary mutation needs the act that asked for it. The
            // genesis does not: there is no device yet that could have.
            signed.parent?.let { parent ->
                val required = com.cruxcoach.domain.sharing.AuthorityPairing.requiredFor(signed.body)!!
                repository.appendAttestation(
                    com.cruxcoach.domain.sharing.AuthorityAttestationSigner(
                        TestDeviceAuthority.deviceCrypto(LAPTOP),
                    ).sign(
                        com.cruxcoach.domain.sharing.AuthorityAttestation(
                            id = LedgerEntryId("act-${signed.id.value}"),
                            scope = required.scope,
                            subject = signed.id,
                            device = LAPTOP,
                            parent = null,
                            manifestContext = com.cruxcoach.domain.sharing.ManifestContext.of(listOf(parent)),
                            authorityGeneration = 1,
                            capability = required.capability,
                            effect = required.effect,
                            signature = "",
                        ),
                    )!!,
                )
            }
            repository.appendDeviceManifestEntry(signed)
        }
    }

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-controller-authority-")
        dbFile = tmp.resolve("secure.db").toFile()
        open()
        SecureDatabase.Schema.create(driver)
        enrol()
    }

    @AfterTest
    fun tearDown() {
        runCatching { driver.close() }
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    // ------------------------------------------------------- happy path

    /**
     * The acts these fixtures wrote. The estate's own enrolment now carries one
     * too, and it is not what any of these tests are counting.
     */
    private fun storedActs() = repository.loadAttestations().filterNot { it.id.value.startsWith("act-m-") }

    @Test
    fun `an invite records which device issued it`() = runTest {
        assertTrue(controller.invite(alice, SharingCircle.FRIENDS).isSuccess)

        // An invite is two entries — the circle, then the offer — so it is two
        // acts, chained, both attributed to this device.
        val acts = storedActs()
        assertEquals(2, acts.size)
        assertTrue(acts.all { it.device == LAPTOP })
        assertTrue(acts.all { it.scope == AuthorityScope.peer(alice) })
    }

    @Test
    fun `the act names the entry it authorised`() = runTest {
        controller.invite(alice, SharingCircle.FRIENDS)

        val entries = repository.loadLedger(alice).map { it.id }.toSet()
        val acts = storedActs()
        assertTrue(acts.isNotEmpty())
        assertTrue(
            acts.all { it.subject in entries },
            "every act has to point at an entry that actually exists",
        )
    }

    @Test
    fun `a grant is permissive and a revoke is restrictive`() = runTest {
        controller.invite(alice, SharingCircle.FRIENDS)
        controller.changeGrant(alice, setOf(SharingCategory.VIDEOS))
        controller.revoke(alice)

        val effects = storedActs().sortedBy { it.subject.value }.map { it.effect }
        assertTrue(effects.contains(AuthorityEffect.PERMISSIVE))
        assertTrue(effects.contains(AuthorityEffect.RESTRICTIVE))
    }

    @Test
    fun `each act is linked to the one before it`() = runTest {
        controller.invite(alice, SharingCircle.FRIENDS)
        controller.changeGrant(alice, setOf(SharingCategory.VIDEOS))

        val acts = storedActs()
        assertTrue(acts.size >= 2)
        val parents = acts.mapNotNull { it.parent }.toSet()
        assertTrue(parents.isNotEmpty(), "a later act must build on an earlier one")
        assertTrue(
            parents.all { parent -> acts.any { it.id == parent } },
            "every parent has to be an act we actually hold",
        )
    }

    @Test
    fun `the winner of a scope is the most recent act in it`() = runTest {
        controller.invite(alice, SharingCircle.FRIENDS)
        controller.setPeerCircle(alice, SharingCircle.ACQUAINTANCES)

        val winner = repository.currentAuthority()[AuthorityScope.peer(alice)]
        assertNotNull(winner)
        assertEquals(LAPTOP, winner.device)
    }

    // ----------------------------------------------------- capability

    @Test
    fun `a read-only device changes nothing`() = runTest {
        driver.close()
        open(PHONE)

        val result = controller.invite(alice, SharingCircle.FRIENDS)

        assertEquals(SharingWriteResult.Failed(SharingWriteError.NOT_AUTHORISED), result)
        assertEquals(emptyList(), storedActs())
        assertEquals(emptyList(), repository.loadLedger(alice))
    }

    /**
     * The capability is settled before the signer is ever asked. Prompting a
     * person to approve something that will then be refused teaches them the
     * prompt is noise.
     */
    @Test
    fun `an unauthorised device is refused before anybody is asked to sign`() = runTest {
        driver.close()
        open(PHONE)

        controller.invite(alice, SharingCircle.FRIENDS)

        assertEquals(0, deviceCrypto.prompts)
    }

    @Test
    fun `a device the manifest does not name changes nothing`() = runTest {
        driver.close()
        open(AuthorityDeviceId("dddd4444"))

        assertEquals(
            SharingWriteResult.Failed(SharingWriteError.NOT_AUTHORISED),
            controller.invite(alice, SharingCircle.FRIENDS),
        )
        assertEquals(emptyList(), repository.loadLedger(alice))
    }

    // ---------------------------------------------- zero writes on refusal

    @Test
    fun `a refused device signature writes nothing at all`() = runTest {
        deviceCrypto.behaviour = ScriptedCrypto.Behaviour.REFUSES

        val result = controller.invite(alice, SharingCircle.FRIENDS)

        assertEquals(SharingWriteResult.Failed(SharingWriteError.SIGNER_UNAVAILABLE), result)
        assertEquals(emptyList(), storedActs())
        assertEquals(emptyList(), repository.loadLedger(alice))
        assertNull(repository.loadProjection().relationships[alice])
    }

    @Test
    fun `a cancelled device signature writes nothing at all`() = runTest {
        deviceCrypto.behaviour = ScriptedCrypto.Behaviour.CANCELS

        runCatching { controller.invite(alice, SharingCircle.FRIENDS) }

        assertEquals(emptyList(), storedActs())
        assertEquals(emptyList(), repository.loadLedger(alice))
    }

    @Test
    fun `a refusal partway through a multi-entry action leaves none of it`() = runTest {
        controller.invite(alice, SharingCircle.FRIENDS)
        val before = storedActs()
        val ledgerBefore = repository.loadLedger(alice)

        deviceCrypto.behaviour = ScriptedCrypto.Behaviour.REFUSES
        val result = controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, granted = true)

        assertEquals(SharingWriteResult.Failed(SharingWriteError.SIGNER_UNAVAILABLE), result)
        assertEquals(before, storedActs())
        assertEquals(ledgerBefore, repository.loadLedger(alice))
    }

    @Test
    fun `the same refusal leaves the reduced authority untouched`() = runTest {
        controller.invite(alice, SharingCircle.FRIENDS)
        val before = repository.currentAuthority()

        deviceCrypto.behaviour = ScriptedCrypto.Behaviour.REFUSES
        controller.changeGrant(alice, setOf(SharingCategory.VIDEOS))

        assertEquals(before, repository.currentAuthority())
    }

    @Test
    fun `a signer that never answers writes nothing either`() = runTest {
        deviceCrypto.behaviour = ScriptedCrypto.Behaviour.TIMES_OUT

        val result = controller.invite(alice, SharingCircle.FRIENDS)

        assertEquals(SharingWriteResult.Failed(SharingWriteError.SIGNER_UNAVAILABLE), result)
        assertEquals(emptyList(), storedActs())
        assertEquals(emptyList(), repository.loadLedger(alice))
        assertNull(repository.loadProjection().relationships[alice])
    }

    @Test
    fun `a timeout partway through a multi-entry action leaves none of it`() = runTest {
        controller.invite(alice, SharingCircle.FRIENDS)
        val acts = storedActs()
        val entries = repository.loadLedger(alice)

        deviceCrypto.behaviour = ScriptedCrypto.Behaviour.TIMES_OUT
        val result = controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, granted = true)

        assertEquals(SharingWriteResult.Failed(SharingWriteError.SIGNER_UNAVAILABLE), result)
        assertEquals(acts, storedActs())
        assertEquals(entries, repository.loadLedger(alice))
    }

    // --------------------------------------------------------- restart

    @Test
    fun `acts and their winners survive a restart`() = runTest {
        controller.invite(alice, SharingCircle.FRIENDS)
        controller.changeGrant(alice, setOf(SharingCategory.VIDEOS))
        val acts = storedActs()
        val winners = repository.currentAuthority()

        driver.close()
        open()

        assertEquals(acts, storedActs())
        assertEquals(winners, repository.currentAuthority())
    }

    // ------------------------------------------- no device, no authority

    /**
     * A build with no device identity wired cannot author anything. That is the
     * fail-closed direction and it is deliberate: the alternative is writing
     * unattributed changes, which is exactly the state this feature removes.
     */
    @Test
    fun `a controller with no device identity refuses to change permissions`() = runTest {
        driver.close()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}?foreign_keys=on")
        val db = SecureDatabase(driver)
        val ownerCrypto = TaggedCrypto(OWNER)
        val signer = AsyncSharingLedgerSigner(ownerCrypto.asAsync())
        val policySigner = AsyncOwnerPolicySigner(ownerCrypto.asAsync())
        val repo = SecureDbSharingRepository(
            database = db,
            vault = AeadSharingKeyVault(InMemoryWrappingKeyStore()),
            verifier = signer.verifier(),
            ownerPolicyVerifier = policySigner.verifier(),
            ownerNpub = OWNER,
        )
        val headless = SharingController(
            repository = repo,
            signer = signer,
            ownerPolicySigner = policySigner,
            ownerNpub = OWNER,
        )

        assertEquals(
            SharingWriteResult.Failed(SharingWriteError.NOT_AUTHORISED),
            headless.invite(alice, SharingCircle.FRIENDS),
        )
        assertEquals(emptyList(), repo.loadLedger(alice))
    }

    // ----------------------------------------------------- unused helpers

    @Test
    fun `the manifest is what the controller reads its own role from`() = runTest {
        assertEquals(DeviceRole.PRIMARY, repository.loadDeviceAuthority().roleOf(LAPTOP))
        assertEquals(DeviceRole.READ_ONLY, repository.loadDeviceAuthority().roleOf(PHONE))
        assertEquals(laptopKey, repository.loadDeviceAuthority().devices[LAPTOP]?.publicKey)
    }
}
