package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.SecureDatabasePragmas
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.AccessEffect
import com.cruxcoach.domain.sharing.AeadSharingKeyVault
import com.cruxcoach.domain.sharing.AsyncAuthorityAttestationSigner
import com.cruxcoach.domain.sharing.AsyncDeviceManifestSigner
import com.cruxcoach.domain.sharing.AsyncLedgerCrypto
import com.cruxcoach.domain.sharing.AsyncOwnerPolicySigner
import com.cruxcoach.domain.sharing.AsyncSharingLedgerSigner
import com.cruxcoach.domain.sharing.AuthorityDeviceId
import com.cruxcoach.domain.sharing.DeviceId
import com.cruxcoach.domain.sharing.DeviceManifestBody
import com.cruxcoach.domain.sharing.DeviceManifestEntry
import com.cruxcoach.domain.sharing.DeviceManifestSigner
import com.cruxcoach.domain.sharing.DeviceRole
import com.cruxcoach.domain.sharing.InMemoryWrappingKeyStore
import com.cruxcoach.domain.sharing.LedgerCrypto
import com.cruxcoach.domain.sharing.LedgerEntryId
import com.cruxcoach.domain.sharing.ObjectId
import com.cruxcoach.domain.sharing.PeerId
import com.cruxcoach.domain.sharing.SharingCategory
import com.cruxcoach.domain.sharing.SharingCircle
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * FEAT-062 §2.4c: while a recovery preview is open, this install writes nothing
 * administrative — and asks nobody to sign anything either.
 *
 * ## What the lock is for
 *
 * A preview happens when a backup cannot be shown to be current. The file may be
 * missing every revocation made since it was taken, so the estate on screen may
 * be more permissive than the real one — and any change authored against it
 * would be authored against a history nobody can vouch for. Until the person
 * resolves that, with an import that gets past the gate or an explicit sovereign
 * reset, local administrative authorship stops.
 *
 * ## Why this test exists
 *
 * First the lock was checked in [SharingController.isAuthorised] and on the
 * manifest doors, but not on the path a permission change actually takes, so on
 * an install that held real authority a grant went through while it was set. The
 * test that should have caught it ran on a **fresh** install with no enrolled
 * device: it saw `RECOVERY_LOCKED`, but only because the write failed for want
 * of authority and the error was relabelled on the way out.
 *
 * Then the check moved onto the act-signing path — which refuses before *that*
 * prompt, but is not the first prompt of the call. A permission change signs the
 * ledger entry, and a policy change the policy entry, **before** the act that
 * authorises it exists: `restore` asks the owner's ledger signer once per
 * relationship in `signDeviceGenerations` and only then attests. So the person
 * was still sent to Amber, once per peer, for signatures that were then thrown
 * away with `RECOVERY_LOCKED`. The previous fixture could not see it, because it
 * counted only the device-attestation and root signers.
 *
 * So this fixture counts **every** signing identity separately — owner ledger,
 * owner policy, manifest, device attestation, root recovery — and the assertion
 * is not just that nothing was written but that nothing was asked. A refusal
 * that arrives after the prompt teaches people the prompt is noise, and with an
 * external signer that prompt is a whole other app.
 *
 * The fixture is otherwise deliberately the opposite of the masking one: a
 * device that is `PRIMARY`, signers that all answer, a peer already invited.
 * Nothing here refuses except the lock.
 */
class SharingPreviewLockTest {

    private companion object {
        const val OWNER = "npub1owner"
        val LAPTOP = AuthorityDeviceId("aaaa1111")
        val TABLET = AuthorityDeviceId("cccc3333")
    }

    private val alice = PeerId("npub1alice")
    private val recoveryCode = com.cruxcoach.domain.sharing.SharingRecoveryCode
        .fromEntropy(ByteArray(20) { 4 })
    private val aliceDevice = DeviceId("dev-alice-1")

    private lateinit var dbFile: java.io.File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repository: SecureDbSharingRepository
    private lateinit var controller: SharingController

    private class TaggedCrypto(private val identity: String) : LedgerCrypto {
        override fun sign(hash: ByteArray): ByteArray = (identity + ":").encodeToByteArray() + hash
        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
            signature.contentEquals((signerNpub + ":").encodeToByteArray() + hash)
    }

    /**
     * Counts prompts, so the test can say the person was never asked.
     *
     * [identity] is what the signature is tagged with, and several of these
     * sign as the owner — the ledger, the policy, the manifest and the root
     * challenge are all the owner's key in production. They are separate
     * *instances* so their prompts can be told apart, which is the whole point:
     * one shared counter cannot say which signer was woken up.
     */
    private class CountingCrypto(private val identity: String) : AsyncLedgerCrypto {
        var prompts = 0
            private set

        override suspend fun signCanonical(hash: ByteArray): ByteArray {
            prompts++
            return (identity + ":").encodeToByteArray() + hash
        }

        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
            signature.contentEquals((signerNpub + ":").encodeToByteArray() + hash)
    }

    private lateinit var ledgerCrypto: CountingCrypto
    private lateinit var policyCrypto: CountingCrypto
    private lateinit var manifestCrypto: CountingCrypto
    private lateinit var deviceCrypto: CountingCrypto
    private lateinit var rootCrypto: CountingCrypto

    /** Every identity that could be asked for a signature, counted apart. */
    private data class Prompts(
        val ownerLedger: Int,
        val ownerPolicy: Int,
        val manifest: Int,
        val deviceAttestation: Int,
        val rootRecovery: Int,
    )

    private fun prompts() = Prompts(
        ownerLedger = ledgerCrypto.prompts,
        ownerPolicy = policyCrypto.prompts,
        manifest = manifestCrypto.prompts,
        deviceAttestation = deviceCrypto.prompts,
        rootRecovery = rootCrypto.prompts,
    )

    private fun open() {
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}?foreign_keys=on").also { d ->
            SecureDatabasePragmas.STATEMENTS.forEach { runCatching { d.execute(null, it, 0).value } }
        }
        ledgerCrypto = CountingCrypto(OWNER)
        policyCrypto = CountingCrypto(OWNER)
        manifestCrypto = CountingCrypto(OWNER)
        deviceCrypto = CountingCrypto(LAPTOP.value)
        rootCrypto = CountingCrypto(OWNER)

        val signer = AsyncSharingLedgerSigner(ledgerCrypto)
        val policySigner = AsyncOwnerPolicySigner(policyCrypto)
        val attestationSigner = AsyncAuthorityAttestationSigner(deviceCrypto)
        // Verification is not a prompt, so the repository's verifiers are built
        // from a plain crypto: a counter that moved on a *read* would say
        // nothing about who was woken up.
        val ownerCrypto = TaggedCrypto(OWNER)
        repository = SecureDbSharingRepository(
            database = SecureDatabase(driver),
            vault = AeadSharingKeyVault(InMemoryWrappingKeyStore()),
            verifier = signer.verifier(),
            ownerPolicyVerifier = policySigner.verifier(),
            ownerNpub = OWNER,
            deviceManifestVerifier = DeviceManifestSigner(ownerCrypto).verifier(),
            attestationVerifier = attestationSigner.verifier(),
            localDeviceIdentity = { DeviceIdentity(LAPTOP, LAPTOP.value) },
            rootRecoveryVerifier = rootCrypto,
        )
        controller = SharingController(
            repository = repository,
            signer = signer,
            ownerPolicySigner = policySigner,
            ownerNpub = OWNER,
            authorityDevice = LAPTOP,
            authorityDevicePublicKey = LAPTOP.value,
            attestationSigner = attestationSigner,
            manifestSigner = AsyncDeviceManifestSigner(manifestCrypto),
            rootCrypto = rootCrypto,
            // A real keypair for the peer, so their acceptance is a real
            // signature rather than something this install asserted.
            peerSimulator = { peer -> TaggedCrypto(peer.value) },
        )
    }

    /** LAPTOP as PRIMARY: this install really can do everything below. */
    private fun enrol() {
        val ownerSigner = DeviceManifestSigner(TaggedCrypto(OWNER))
        repository.appendDeviceManifestEntry(
            ownerSigner.sign(
                DeviceManifestEntry(
                    id = LedgerEntryId("m-1"),
                    manifestSequence = 1,
                    authorityGeneration = 1,
                    parent = null,
                    signerNpub = OWNER,
                    signature = "",
                    body = DeviceManifestBody.DeviceEnrolled(LAPTOP, LAPTOP.value, DeviceRole.PRIMARY),
                ),
            )!!,
        )
    }

    @BeforeTest
    fun setUp() = runTest {
        val tmp = Files.createTempDirectory("cruxcoach-preview-lock-")
        dbFile = tmp.resolve("secure.db").toFile()
        open()
        SecureDatabase.Schema.create(driver)
        enrol()
        // A peer to act on, invited while nothing was locked.
        assertTrue(controller.invite(alice, SharingCircle.FRIENDS).isSuccess, "the fixture must be able to write")
        assertTrue(controller.changeGrant(alice, setOf(SharingCategory.VIDEOS)).isSuccess)
    }

    @AfterTest
    fun tearDown() {
        runCatching { driver.close() }
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    private data class Rows(
        val ledger: Int,
        val policy: Int,
        val attestations: Int,
        val manifest: Int,
    )

    private fun rows() = Rows(
        ledger = repository.loadWholeLedger().size,
        policy = repository.loadOwnerPolicyLedger().size,
        attestations = repository.loadAttestations().size,
        manifest = repository.loadDeviceManifest().size,
    )

    private fun lockPreview() = repository.setAdministrativeWritesLocked(
        locked = true,
        reason = "the backup could not be shown to be current",
        previewGeneration = 1,
    )

    /** One named local mutation, so a failure says which door was left open. */
    private class Mutation(val name: String, val run: suspend () -> SharingWriteResult)

    /**
     * Every locally authored administrative write, each runnable on its own.
     *
     * One per mutation class the controller exposes: a grant, a baseline, a
     * per-peer and a per-object rule, a circle, an invitation, an offer, the
     * device and delivery bodies, a revocation, the irreversible purge, the
     * recovery generation bump, and the three device-administration doors.
     */
    private fun localMutations(): List<Mutation> = listOf(
        Mutation("changeGrant") { controller.changeGrant(alice, setOf(SharingCategory.PRIVATE_NOTES)) },
        Mutation("setBaseline") {
            controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        },
        Mutation("setPeerRule") {
            controller.setPeerRule(alice, SharingCategory.VIDEOS, AccessEffect.DENY)
        },
        Mutation("setObjectRule") {
            controller.setObjectRule(alice, ObjectId("obj-1"), SharingCategory.VIDEOS, AccessEffect.DENY)
        },
        Mutation("setPeerCircle") { controller.setPeerCircle(alice, SharingCircle.ACQUAINTANCES) },
        Mutation("invite") { controller.invite(PeerId("npub1bob"), SharingCircle.FRIENDS) },
        Mutation("offer") {
            controller.offer(alice, SharingCircle.FRIENDS, setOf(SharingCategory.VIDEOS))
        },
        Mutation("revokeDevice") { controller.revokeDevice(alice, aliceDevice) },
        Mutation("markDeliveryUnclear") { controller.markDeliveryUnclear(alice, aliceDevice) },
        Mutation("confirmDelivery") { controller.confirmDelivery(alice, aliceDevice) },
        Mutation("revoke") { controller.revoke(alice) },
        // Irreversible, and it records the removal before deleting: doubly
        // something not to do against a history nobody can vouch for.
        Mutation("purge") { controller.purge(alice) },
        // Recovery's own device-generation bump. It signs one ledger entry per
        // relationship before it attests anything, so it is the sharpest case.
        Mutation("restore") { controller.advanceDeviceGenerations(recoveryCode, recoveryCode) },
        Mutation("enrolDevice") { controller.enrolDevice(TABLET, TABLET.value, DeviceRole.TRUSTED) },
        Mutation("changeDeviceRole") { controller.changeDeviceRole(LAPTOP, DeviceRole.TRUSTED) },
        Mutation("revokeOwnDevice") { controller.revokeOwnDevice(LAPTOP) },
        Mutation("enrolGenesisDevice") { controller.enrolGenesisDevice() },
    )

    // ------------------------------------------------------------- the lock

    @Test
    fun `an authorised install writes nothing administrative while a preview is open`() = runTest {
        val before = rows()
        lockPreview()

        localMutations().forEach { mutation ->
            assertEquals(
                SharingWriteResult.Failed(SharingWriteError.RECOVERY_LOCKED),
                mutation.run(),
                "${mutation.name} wrote, or reported the wrong reason, while a preview was open",
            )
        }

        assertEquals(before, rows(), "a locked install must leave every ledger exactly as it was")
        assertTrue(repository.administrativeWritesLocked(), "and the lock is still there afterwards")
    }

    /**
     * And nobody is asked to sign for any of it.
     *
     * Checked per mutation and per identity. An aggregate check over one
     * counter is what hid the owner-ledger prompt: `restore` refused with the
     * right error while asking the owner's signer once per relationship on the
     * way there.
     */
    @Test
    fun `no signer is asked for anything while a preview is open`() = runTest {
        lockPreview()

        localMutations().forEach { mutation ->
            val before = prompts()
            mutation.run()
            assertEquals(
                before,
                prompts(),
                "${mutation.name} asked for a signature the lock was always going to refuse",
            )
        }
    }

    /**
     * `restore` on its own, because it is the one named in the review.
     *
     * It signs a fresh device generation for **every** relationship before the
     * first act exists, so on a real install this was one Amber prompt per peer
     * — every one of them for an entry that would then be dropped.
     */
    @Test
    fun `restore asks nobody to sign while a preview is open`() = runTest {
        lockPreview()
        val before = prompts()

        val outcome = controller.advanceDeviceGenerations(recoveryCode, recoveryCode)

        assertEquals(SharingWriteResult.Failed(SharingWriteError.RECOVERY_LOCKED), outcome)
        assertEquals(
            before.ownerLedger,
            ledgerCrypto.prompts,
            "restore signed a device generation under the lock, before it ever asked whether it could",
        )
        assertEquals(before, prompts(), "and no other identity was woken up either")
    }

    /**
     * The counters have to be live, or every assertion above passes vacuously.
     *
     * So each identity is shown being asked on the *unlocked* fixture, by the
     * same doors the locked tests use.
     */
    @Test
    fun `every counted signer is really the one being asked`() = runTest {
        val start = prompts()
        assertTrue(controller.changeGrant(alice, setOf(SharingCategory.PRIVATE_NOTES)).isSuccess)
        assertTrue(ledgerCrypto.prompts > start.ownerLedger, "a grant is a signed ledger entry")
        assertTrue(deviceCrypto.prompts > start.deviceAttestation, "and an act by this device")

        val afterGrant = prompts()
        assertTrue(controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true).isSuccess)
        assertTrue(policyCrypto.prompts > afterGrant.ownerPolicy, "a baseline is a signed policy entry")

        val afterBaseline = prompts()
        assertTrue(controller.enrolDevice(TABLET, TABLET.value, DeviceRole.TRUSTED).isSuccess)
        assertTrue(manifestCrypto.prompts > afterBaseline.manifest, "an enrolment is a signed manifest entry")

        val afterEnrol = prompts()
        controller.recoverThisDevice(recoveryCode, recoveryCode)
        assertTrue(
            rootCrypto.prompts > afterEnrol.rootRecovery,
            "a recovery asks the root identity to sign the challenge",
        )
    }

    /** Reading is untouched: a preview exists so the person can look. */
    @Test
    fun `a locked install can still be read`() = runTest {
        lockPreview()

        assertEquals(1, controller.snapshot().peers.size)
        assertTrue(controller.deviceEstate().administrativeWritesLocked)
    }

    /** And once the lock is gone, the same writes work again. */
    @Test
    fun `clearing the lock restores every write`() = runTest {
        lockPreview()
        assertEquals(
            SharingWriteResult.Failed(SharingWriteError.RECOVERY_LOCKED),
            controller.changeGrant(alice, setOf(SharingCategory.PRIVATE_NOTES)),
        )

        repository.setAdministrativeWritesLocked(locked = false, reason = null, previewGeneration = null)

        assertTrue(controller.changeGrant(alice, setOf(SharingCategory.PRIVATE_NOTES)).isSuccess)
        assertTrue(controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true).isSuccess)
        assertTrue(controller.enrolDevice(TABLET, TABLET.value, DeviceRole.TRUSTED).isSuccess)
    }

    /**
     * The lock is about local *authorship*, not about listening.
     *
     * A preview exists because this install cannot show the estate is current.
     * Refusing to take in a peer's own signed entry would make that permanent:
     * inbound evidence is how an install catches up, and the one thing that
     * could eventually resolve the preview is evidence. So the peer's
     * acceptance still lands, carried by their signature and not by any
     * authority of ours.
     */
    @Test
    fun `a peer's own signed entry is still accepted while a preview is open`() = runTest {
        lockPreview()
        val before = rows()

        val accepted = controller.simulateAccept(alice, setOf(SharingCategory.VIDEOS), aliceDevice)

        assertTrue(accepted, "an inbound peer-signed entry is not this install authoring anything")
        assertEquals(before.ledger + 1, rows().ledger, "and it is on record")
        assertTrue(repository.administrativeWritesLocked(), "the lock is untouched by listening")
    }
}
