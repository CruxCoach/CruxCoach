package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.SecureDatabasePragmas
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.AeadSharingKeyVault
import com.cruxcoach.domain.sharing.AuthorityAttestation
import com.cruxcoach.domain.sharing.AuthorityAttestationSigner
import com.cruxcoach.domain.sharing.AuthorityDeviceId
import com.cruxcoach.domain.sharing.AuthorityPairing
import com.cruxcoach.domain.sharing.DeviceManifestBody
import com.cruxcoach.domain.sharing.DeviceManifestEntry
import com.cruxcoach.domain.sharing.DeviceManifestSigner
import com.cruxcoach.domain.sharing.DeviceRole
import com.cruxcoach.domain.sharing.InMemoryWrappingKeyStore
import com.cruxcoach.domain.sharing.LedgerCrypto
import com.cruxcoach.domain.sharing.LedgerEntryId
import com.cruxcoach.domain.sharing.ManifestContext
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FEAT-062 §2.4c: the write door checks the preview lock too, and *every* write
 * door does.
 *
 * [SharingPreviewLockTest] covers the controller, which refuses before anything
 * is signed — that is the check that keeps a person from being sent to their
 * signer for a change that was never going to be stored. This one covers the
 * second check the spec makes normative: the one immediately before storing.
 *
 * ## Why a second check, and why on these doors
 *
 * Signing can take as long as the person takes to approve it, so the decision
 * the controller made may be minutes old by the time the rows are written.
 * `commitAll` already re-reads the lock for exactly that reason, and says why in
 * as many words: a guard that covers only the common path is a guard with two
 * ways round it.
 *
 * It had two ways round it. The device-administration batch and the genesis
 * enrolment reach the database through their own doors, and neither read the
 * lock — so the guarantee held for a grant and a baseline but not for enrolling
 * a device, changing a role, revoking one, or establishing the first primary.
 * Those are the changes that decide who may make every other change.
 *
 * Each test below writes the *same* batch twice, once locked and once not, so a
 * refusal cannot be mistaken for a batch that was never admissible.
 */
class SharingPreviewLockWriteDoorTest {

    private companion object {
        const val OWNER = "npub1owner"
        val LAPTOP = AuthorityDeviceId("aaaa1111")
        val TABLET = AuthorityDeviceId("cccc3333")
    }

    private lateinit var dbFile: java.io.File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repository: SecureDbSharingRepository

    private class TaggedCrypto(private val identity: String) : LedgerCrypto {
        override fun sign(hash: ByteArray): ByteArray = (identity + ":").encodeToByteArray() + hash
        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
            signature.contentEquals((signerNpub + ":").encodeToByteArray() + hash)
    }

    private val ownerSigner = DeviceManifestSigner(TaggedCrypto(OWNER))
    private val laptopSigner = AuthorityAttestationSigner(TaggedCrypto(LAPTOP.value))

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-preview-lock-door-")
        dbFile = tmp.resolve("secure.db").toFile()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}?foreign_keys=on").also { d ->
            SecureDatabasePragmas.STATEMENTS.forEach { runCatching { d.execute(null, it, 0).value } }
        }
        repository = SecureDbSharingRepository(
            database = SecureDatabase(driver),
            vault = AeadSharingKeyVault(InMemoryWrappingKeyStore()),
            verifier = { true },
            ownerPolicyVerifier = { true },
            ownerNpub = OWNER,
            deviceManifestVerifier = ownerSigner.verifier(),
            attestationVerifier = laptopSigner.verifier(),
            localDeviceIdentity = { DeviceIdentity(LAPTOP, LAPTOP.value) },
        )
        SecureDatabase.Schema.create(driver)
    }

    @AfterTest
    fun tearDown() {
        runCatching { driver.close() }
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    private fun lockPreview() = repository.setAdministrativeWritesLocked(
        locked = true,
        reason = "the backup could not be shown to be current",
        previewGeneration = 1,
    )

    private fun unlock() = repository.setAdministrativeWritesLocked(false, null, null)

    /** LAPTOP as the first PRIMARY, signed by the owner's root key. */
    private fun genesis(): DeviceManifestEntry = requireNotNull(
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
        ),
    )

    /** An ordinary device-administration batch: enrol TABLET, asked for by LAPTOP. */
    private fun enrolTablet(): Pair<DeviceManifestEntry, AuthorityAttestation> {
        val manifest = repository.loadDeviceAuthority()
        val entry = requireNotNull(
            ownerSigner.sign(
                DeviceManifestEntry(
                    id = LedgerEntryId("m-2"),
                    manifestSequence = repository.nextManifestSequence(),
                    authorityGeneration = manifest.authorityGeneration,
                    parent = manifest.head,
                    signerNpub = OWNER,
                    signature = "",
                    body = DeviceManifestBody.DeviceEnrolled(TABLET, TABLET.value, DeviceRole.TRUSTED),
                ),
            ),
        )
        val required = requireNotNull(AuthorityPairing.requiredFor(entry.body))
        val act = requireNotNull(
            laptopSigner.sign(
                AuthorityAttestation(
                    id = LedgerEntryId("act-m-2"),
                    scope = required.scope,
                    subject = entry.id,
                    device = LAPTOP,
                    parent = repository.currentAuthority()[required.scope]?.id,
                    manifestContext = ManifestContext.of(manifest.frontier),
                    authorityGeneration = manifest.authorityGeneration,
                    capability = required.capability,
                    effect = required.effect,
                    signature = "",
                ),
            ),
        )
        return entry to act
    }

    // ------------------------------------------------------------- genesis

    @Test
    fun `the genesis door refuses while a preview is open`() {
        lockPreview()

        assertFalse(
            repository.commitGenesisManifest(genesis()),
            "a preview must stop this install establishing its first primary",
        )
        assertTrue(repository.loadDeviceManifest().isEmpty(), "and leave the manifest empty")
    }

    /** The same entry, to show the refusal was the lock and not the entry. */
    @Test
    fun `the genesis door accepts the same entry once the lock is gone`() {
        lockPreview()
        repository.commitGenesisManifest(genesis())

        unlock()

        assertTrue(repository.commitGenesisManifest(genesis()), repository.lastBatchRejection.orEmpty())
        assertEquals(1, repository.loadDeviceManifest().size)
    }

    // -------------------------------------------------- device administration

    @Test
    fun `the device administration door refuses while a preview is open`() {
        assertTrue(repository.commitGenesisManifest(genesis()))
        val (entry, act) = enrolTablet()
        lockPreview()

        assertFalse(
            repository.commitRecovery(listOf(entry), emptyList(), listOf(act)),
            "a preview must stop this install enrolling, re-roling or revoking a device",
        )
        assertEquals(1, repository.loadDeviceManifest().size, "and write no manifest entry")
        assertTrue(repository.loadAttestations().isEmpty(), "and no act either")
    }

    @Test
    fun `the device administration door accepts the same batch once the lock is gone`() {
        assertTrue(repository.commitGenesisManifest(genesis()))
        val (entry, act) = enrolTablet()
        lockPreview()
        repository.commitRecovery(listOf(entry), emptyList(), listOf(act))

        unlock()

        assertTrue(
            repository.commitRecovery(listOf(entry), emptyList(), listOf(act)),
            repository.lastBatchRejection.orEmpty(),
        )
        assertEquals(2, repository.loadDeviceManifest().size)
        assertEquals(
            DeviceRole.TRUSTED,
            repository.loadDeviceAuthority().devices[TABLET]?.role,
            "the tablet is enrolled once the preview is resolved",
        )
    }
}
