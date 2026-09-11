package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.SecureDatabasePragmas
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.RecoveryDecision
import com.cruxcoach.domain.sharing.AeadSharingKeyVault
import com.cruxcoach.domain.sharing.DeviceManifestSigner
import com.cruxcoach.domain.sharing.AsyncOwnerPolicySigner
import com.cruxcoach.domain.sharing.AsyncSharingLedgerSigner
import com.cruxcoach.domain.sharing.asAsync
import com.cruxcoach.domain.sharing.AliasedWrappingKeyStore
import com.cruxcoach.domain.sharing.DeviceId
import com.cruxcoach.domain.sharing.InMemoryKeyAliasBackend
import com.cruxcoach.domain.sharing.KeyHandle
import com.cruxcoach.domain.sharing.KeyScope
import com.cruxcoach.domain.sharing.LedgerCrypto
import com.cruxcoach.domain.sharing.OwnerPolicySigner
import com.cruxcoach.domain.sharing.PeerId
import com.cruxcoach.domain.sharing.SharingBackupError
import com.cruxcoach.domain.sharing.SharingCategory
import com.cruxcoach.domain.sharing.SharingCircle
import com.cruxcoach.domain.sharing.SharingLedgerSigner
import com.cruxcoach.domain.sharing.SharingRecoveryCode
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FEAT-062 §8: a permission backup written to a real file and restored onto a
 * fresh install.
 *
 * The file goes through an actual temp file rather than a byte array in memory,
 * because "it round-trips" has to mean the thing a user would carry on a stick.
 */
class SharingBackupRoundTripTest {

    private companion object {
        const val OWNER = "e75a185c019d09049d5fcb0e29a2cc9bfd016ec0f6d892fc98f6ffe0181a480d"
        const val OTHER = "1111111111111111111111111111111111111111111111111111111111111111"
    }

    private lateinit var tmp: java.io.File
    private lateinit var dbFile: java.io.File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repo: SecureDbSharingRepository
    private lateinit var controller: SharingController
    private lateinit var keyBackend: InMemoryKeyAliasBackend

    private val alice = PeerId("aaaa111122223333444455556666777788889999aaaabbbbccccddddeeeeffff")
    private val phone = DeviceId("dev-phone")
    private val code = SharingRecoveryCode.fromEntropy(ByteArray(20) { (it * 3 + 2).toByte() })
    private val wrongCode = SharingRecoveryCode.fromEntropy(ByteArray(20) { (it * 11 + 6).toByte() })

    private class TaggedCrypto(private val identity: String) : LedgerCrypto {
        override fun sign(hash: ByteArray): ByteArray = (identity + ":").encodeToByteArray() + hash
        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
            signature.contentEquals((signerNpub + ":").encodeToByteArray() + hash)
    }

    private lateinit var ownerCrypto: LedgerCrypto
    private lateinit var ownerNpub: String

    /** Builds a fresh install: new database file and a new Keystore backend. */
    private fun open(file: java.io.File, owner: String = OWNER, backend: InMemoryKeyAliasBackend = InMemoryKeyAliasBackend()) {
        keyBackend = backend
        driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}?foreign_keys=on").also { d ->
            SecureDatabasePragmas.STATEMENTS.forEach { runCatching { d.execute(null, it, 0).value } }
        }
        val db = SecureDatabase(driver)
        val crypto = TaggedCrypto(owner)
        ownerCrypto = crypto
        ownerNpub = owner
        val signer = AsyncSharingLedgerSigner(crypto.asAsync())
        val policySigner = AsyncOwnerPolicySigner(crypto.asAsync())
        repo = SecureDbSharingRepository(
            database = db,
            vault = AeadSharingKeyVault(AliasedWrappingKeyStore(backend)),
            verifier = signer.verifier(),
            ownerPolicyVerifier = policySigner.verifier(),
            ownerNpub = owner,
            deviceManifestVerifier = DeviceManifestSigner(crypto).verifier(),
            attestationVerifier = TestDeviceAuthority.attestationSigner().verifier(),
            // Which device this install is, and the key it signs with — both
            // bound at construction rather than taken per commit.
            localDeviceIdentity = { DeviceIdentity(TestDeviceAuthority.DEVICE, TestDeviceAuthority.publicKeyOf()) },
            // Bound at construction, matching what the controller signs with.
            rootRecoveryVerifier = crypto.asAsync(),
        )
        controller = SharingController(
            repository = repo,
            signer = signer,
            ownerPolicySigner = policySigner,
            ownerNpub = owner,
            authorityDevice = TestDeviceAuthority.DEVICE,
            authorityDevicePublicKey = TestDeviceAuthority.publicKeyOf(),
            attestationSigner = TestDeviceAuthority.attestationSigner(),
            peerSimulator = { peer -> TaggedCrypto(peer.value) },
            backupCrypto = crypto.asAsync(),
            // A recovery needs the root identity, for the manifest it writes
            // and for the challenge it has to answer first.
            manifestSigner = com.cruxcoach.domain.sharing.AsyncDeviceManifestSigner(crypto.asAsync()),
            rootCrypto = crypto.asAsync(),
        )
    }

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("cruxcoach-sharing-backup-").toFile()
        dbFile = java.io.File(tmp, "secure.db")
        open(dbFile)
        SecureDatabase.Schema.create(driver)
        TestDeviceAuthority.enrol(repo, ownerCrypto, ownerNpub)
        // A JUnit fixture hook cannot suspend, and seeding is a sequence of
        // signed appends. The stand-in signer never actually suspends, so this
        // blocks for as long as the inserts take and no longer.
        runBlocking { seed() }
    }

    @AfterTest
    fun tearDown() {
        runCatching { driver.close() }
        tmp.deleteRecursively()
    }

    private suspend fun seed() {
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        controller.invite(alice, SharingCircle.FRIENDS)
        controller.simulateAccept(alice, setOf(SharingCategory.VIDEOS), phone)
    }

    private suspend fun exportToFile(): java.io.File {
        val result = controller.exportBackup(code)
        val bytes = assertIs<SharingBackupWriteOutcome.Written>(result).bytes
        return java.io.File(tmp, "backup.ccshare").also { it.writeBytes(bytes) }
    }

    /** A fresh install with its own database file and its own Keystore. */
    private fun freshInstall(owner: String = OWNER) {
        driver.close()
        open(java.io.File(tmp, "restored.db"), owner)
        SecureDatabase.Schema.create(driver)
        TestDeviceAuthority.enrol(repo, ownerCrypto, ownerNpub)
    }

    // -------------------------------------------------------- the round trip

    @Test
    fun `a backup restores the relationships onto a fresh install`() = runTest {
        val file = exportToFile()
        freshInstall()

        val result = controller.importRecovery(file.readBytes(), code, sovereignReset = true)

        assertTrue(result.applied, "decision=${result.decision} batch=${repo.lastBatchRejection}")
        // The relationship and its signed history come back. The *grant* does
        // not: a fresh install cannot show the file is current, so the only way
        // to apply it is a sovereign reset, and a reset revokes every grant.
        // Silently re-granting is precisely what it exists to prevent.
        val detail = assertNotNull(controller.peerDetail(alice))
        assertEquals(emptySet(), detail.consentedCategories, "a reset takes the grants with it")
        assertTrue(repo.loadLedger(alice).isNotEmpty(), "but the history is restored")
    }

    @Test
    fun `a backup restores the owner policy onto a fresh install`() = runTest {
        val file = exportToFile()
        freshInstall()

        controller.importRecovery(file.readBytes(), code, sovereignReset = true)

        assertTrue(
            SharingCategory.VIDEOS in repo.loadPolicy().baselines.explicitFor(SharingCircle.FRIENDS),
        )
    }

    @Test
    fun `restored sealed data can be read again through the re-wrapped key`() = runTest {
        val handle = KeyHandle(KeyScope.CATEGORY, SharingCategory.PRIVATE_NOTES.name)
        val key = repo.createDataKey(handle)
        repo.storeSealedItem("note-1", SharingCategory.PRIVATE_NOTES, handle, key, "Schulter schont sich".encodeToByteArray())
        val file = exportToFile()

        freshInstall()
        assertTrue(controller.importRecovery(file.readBytes(), code, sovereignReset = true).applied)

        val restoredKey = assertNotNull(repo.readWrappedKey(handle))
        assertEquals("Schulter schont sich", repo.readSealedItem("note-1", restoredKey)?.decodeToString())
    }

    @Test
    fun `the restored install mints a new device generation and stays closed`() = runTest {
        val file = exportToFile()
        freshInstall()

        controller.importRecovery(file.readBytes(), code, sovereignReset = true)

        val state = assertNotNull(repo.loadProjection().relationships[alice])
        assertEquals(2L, state.deviceGeneration)
        assertTrue(state.awaitingRevokeSync)
        assertTrue(state.authorisedDevices.isEmpty(), "an old device is never active again")
    }

    @Test
    fun `restoring the same backup twice changes nothing the second time`() = runTest {
        val file = exportToFile()
        freshInstall()
        controller.importRecovery(file.readBytes(), code, sovereignReset = true)
        val afterFirst = repo.loadLedger(alice).size

        val estateBefore = repo.loadDeviceAuthority()
        val second = controller.importRecovery(file.readBytes(), code, sovereignReset = true)

        // Whether the second one applies is not the point — the file is already
        // this install's history. What must not happen is the history growing a
        // second copy of itself, or the estate becoming unusable.
        assertEquals(afterFirst, repo.loadLedger(alice).size, "a second restore must not duplicate entries")
        if (!second.applied) {
            assertEquals(estateBefore, repo.loadDeviceAuthority(), "a refused second import changes nothing")
        }
        assertTrue(repo.loadDeviceAuthority().authorityGeneration > 0, "the estate stays usable")
    }

    @Test
    fun `the restored state survives closing and reopening the database`() = runTest {
        val file = exportToFile()
        freshInstall()
        controller.importRecovery(file.readBytes(), code, sovereignReset = true)
        val restoredDb = java.io.File(tmp, "restored.db")
        driver.close()

        open(restoredDb, backend = keyBackend)

        // What has to survive the restart is the estate the reset established
        // and the history it restored — not the grant, which the reset revoked.
        val state = assertNotNull(repo.loadProjection().relationships[alice])
        assertEquals(emptySet(), state.consentedCategories)
        assertTrue(repo.loadLedger(alice).isNotEmpty(), "the signed history is still there")
        assertTrue(repo.loadDeviceAuthority().authorityGeneration > 0, "and so is the estate")
    }

    // ------------------------------------------------------------ fail closed

    private fun assertNothingWasWritten() {
        assertTrue(repo.loadProjection().relationships.isEmpty(), "no relationship may be written")
        assertTrue(repo.loadOwnerPolicyLedger().isEmpty(), "no owner policy entry may be written")
        assertEquals(0L, repo.countTombstones(), "no tombstone may be written")
    }

    @Test
    fun `a wrong recovery code restores nothing`() = runTest {
        val file = exportToFile()
        freshInstall()

        val result = controller.importRecovery(file.readBytes(), wrongCode, sovereignReset = true)

        // A recovery says only that it was refused. Which credential was wrong
        // is not something the outcome distinguishes, and telling a caller
        // "the code was wrong, the file was fine" is an oracle.
        assertEquals(RecoveryDecision.REFUSED, result.decision)
        assertTrue(!result.applied)
        assertNothingWasWritten()
    }

    @Test
    fun `a backup from another identity restores nothing`() = runTest {
        val file = exportToFile()
        freshInstall(owner = OTHER)

        val result = controller.importRecovery(file.readBytes(), code, sovereignReset = true)

        assertEquals(RecoveryDecision.REFUSED, (result).decision)
        assertNothingWasWritten()
    }

    @Test
    fun `a tampered file restores nothing`() = runTest {
        val file = exportToFile()
        val bytes = file.readBytes().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        freshInstall()

        assertEquals(RecoveryDecision.REFUSED, (controller.importRecovery(bytes, code).decision))
        assertNothingWasWritten()
    }

    @Test
    fun `a file that is not a backup restores nothing`() = runTest {
        freshInstall()

        val result = controller.importRecovery("just some text".encodeToByteArray(), code)

        assertEquals(RecoveryDecision.REFUSED, (result).decision)
        assertNothingWasWritten()
    }

    @Test
    fun `a truncated file restores nothing`() = runTest {
        val file = exportToFile()
        val half = file.readBytes().let { it.copyOf(it.size / 2) }
        freshInstall()

        assertEquals(RecoveryDecision.REFUSED, (controller.importRecovery(half, code).decision))
        assertNothingWasWritten()
    }

    @Test
    fun `a purge tombstone in the backup keeps that peer from coming back`() = runTest {
        controller.purge(alice)
        val file = exportToFile()
        freshInstall()

        controller.importRecovery(file.readBytes(), code, sovereignReset = true)

        assertNull(repo.loadProjection().relationships[alice], "a purged peer must not be resurrected")
        assertTrue(repo.countTombstones() > 0, "the tombstone itself is restored")
    }

    @Test
    fun `an export with an invalid recovery code writes no file`() = runTest {
        val result = controller.exportBackup("not-a-code")
        assertIs<SharingBackupWriteOutcome.Failed>(result)
    }

    // ------------------------------------------------- the authority it carries

    /**
     * A backup that omits the manifest and the acts restores permission entries
     * that nothing authorises. The install then either refuses its own history
     * or, worse, accepts unattributed changes.
     */
    @Test
    fun `the payload carries the device manifest and the acts`() {
        val payload = repo.collectBackupPayload()

        assertTrue(payload.deviceManifest.isNotEmpty(), "the manifest has to travel with the entries")
        assertEquals(
            repo.loadDeviceManifest(),
            payload.deviceManifest,
            "byte for byte, or the signatures stop verifying",
        )
        assertEquals(repo.loadAttestations(), payload.attestations)
    }

    @Test
    fun `the payload carries the authority and recovery metadata`() {
        val payload = repo.collectBackupPayload()

        assertEquals(repo.loadDeviceAuthority().authorityGeneration, payload.authorityGeneration)
        assertEquals(repo.administrativeWritesLocked(), payload.administrativeWritesLocked)
    }

    @Test
    fun `a restored install can read its own authority back`() = runBlocking {
        val before = repo.loadDeviceAuthority()
        val acts = repo.loadAttestations()
        val code = controller.newRecoveryCode()
        val exported = (controller.exportBackup(code) as SharingBackupWriteOutcome.Written).bytes

        driver.close()
        java.io.File(tmp, "restored.db").let { file ->
            open(file)
            SecureDatabase.Schema.create(driver)
        }
        val outcome = controller.importRecovery(exported, code, sovereignReset = true)
        assertEquals(
            null,
            (outcome as? SharingRestoreOutcome.Failed)?.error?.name?.let {
                "$it / ${com.cruxcoach.domain.sharing.SharingBackupEnvelope.lastDecodeFailure}" +
                    " / ${repo.lastRestoreRejection} / ${repo.lastBatchRejection}"
            } ?: repo.lastRestoreRejection,
            "the import must not be refused",
        )
        assertIs<SharingRecoveryOutcome>(outcome)

        // A takeover onto a fresh install mints the next generation and enrols
        // this device; the acts the file carried are all still on record.
        assertTrue(outcome.applied, "decision=${outcome.decision} batch=${repo.lastBatchRejection}")
        assertTrue(repo.loadDeviceAuthority().authorityGeneration > before.authorityGeneration)
        assertTrue(repo.loadAttestations().containsAll(acts), "the file's acts survive")
    }

    /**
     * A backup carries public keys, never private ones. Reviving an old
     * device's signing key would make a stolen backup a working device.
     */
    @Test
    fun `no device private key travels in a backup`() = runBlocking {
        val code = controller.newRecoveryCode()
        val exported = (controller.exportBackup(code) as SharingBackupWriteOutcome.Written).bytes

        val payload = repo.collectBackupPayload()
        assertTrue(payload.deviceManifest.isNotEmpty())
        assertTrue(exported.isNotEmpty())

        driver.close()
        java.io.File(tmp, "restored2.db").let { file ->
            open(file)
            SecureDatabase.Schema.create(driver)
        }
        controller.importRecovery(exported, code, sovereignReset = true)

        // Restoring gives the install the *estate*, not the ability to act as
        // any device already in it.
        val identity = SecureDbDeviceIdentity(SecureDatabase(driver), keyBackend.let { AeadSharingKeyVault(AliasedWrappingKeyStore(it)) })
        val restoredIdentity = identity.loadOrCreate()
        assertTrue(
            restoredIdentity == null || repo.loadDeviceAuthority().roleOf(restoredIdentity.device) == null,
            "a restored install must not already be an enrolled device",
        )
    }

    @Test
    fun `a tampered manifest entry fails the whole import`() = runBlocking {
        val code = controller.newRecoveryCode()
        val exported = (controller.exportBackup(code) as SharingBackupWriteOutcome.Written).bytes
        val tampered = exported.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x40).toByte() }

        driver.close()
        java.io.File(tmp, "restored3.db").let { file ->
            open(file)
            SecureDatabase.Schema.create(driver)
        }

        val outcome = controller.importRecovery(tampered, code)

        assertEquals(RecoveryDecision.REFUSED, (outcome).decision)
        assertEquals(emptyList(), repo.loadDeviceManifest())
        assertEquals(emptyList(), repo.loadAttestations())
    }

    // ------------------------------------------------------ envelope version

    /**
     * A V1 file predates the device manifest and the acts, so every entry in it
     * is unattributed. It is refused rather than read: accepting it would mean
     * restoring permission changes with no authority behind them, which is the
     * state this whole model exists to remove.
     */
    @Test
    fun `a version 1 envelope is refused outright`() = runBlocking {
        val code = controller.newRecoveryCode()
        val exported = (controller.exportBackup(code) as SharingBackupWriteOutcome.Written).bytes
        val v1 = exported.copyOf().also { it[MAGIC_LENGTH] = 1 }
        val manifestBefore = repo.loadDeviceManifest()
        val actsBefore = repo.loadAttestations()

        val outcome = controller.importRecovery(v1, code)

        assertEquals(RecoveryDecision.REFUSED, (outcome).decision)
        assertEquals(manifestBefore, repo.loadDeviceManifest(), "a refused file writes nothing")
        assertEquals(actsBefore, repo.loadAttestations())
    }

    @Test
    fun `this build writes version 2`() = runBlocking {
        val code = controller.newRecoveryCode()
        val exported = (controller.exportBackup(code) as SharingBackupWriteOutcome.Written).bytes

        assertEquals(2, exported[MAGIC_LENGTH].toInt())
    }

    /**
     * The generation the payload claims has to be the one its own manifest
     * reduces to. A file whose header said "generation 9" while its manifest
     * only established 1 would drive the freshness gate — the thing that
     * decides preview versus restore — from a number nobody signed.
     */
    /**
     * Sealed as a real file and imported through the real door.
     *
     * There is no way to hand a decrypted payload to anything any more — that
     * was the bypass — so the lie is written into a file the way an attacker
     * with the recovery code would have to, and the import is what refuses it.
     */
    @Test
    fun `a file whose generation disagrees with its manifest restores nothing`() = runBlocking {
        val code = controller.newRecoveryCode()
        val payload = repo.collectBackupPayload()
        val lying = payload.copy(authorityGeneration = payload.authorityGeneration + 5)
        val sealed = com.cruxcoach.domain.sharing.SharingBackupEnvelope.write(
            lying, code, ownerNpub, ownerCrypto.asAsync(),
        ) as com.cruxcoach.domain.sharing.SharingBackupWriteResult.Written
        val manifestBefore = repo.loadDeviceManifest()
        val actsBefore = repo.loadAttestations()

        val outcome = controller.importRecovery(sealed.bytes, code, sovereignReset = true)

        assertTrue(!outcome.applied, "a generation nobody signed must not drive the freshness gate")
        assertEquals(manifestBefore, repo.loadDeviceManifest(), "and nothing is written")
        assertEquals(actsBefore, repo.loadAttestations())
    }

    @Test
    fun `a payload whose generation matches its manifest is accepted`() = runBlocking {
        val payload = repo.collectBackupPayload()

        assertEquals(
            repo.loadDeviceAuthority().authorityGeneration,
            payload.authorityGeneration,
            "what the file claims is what its own manifest establishes",
        )
    }

    /**
     * A Keystore that cannot re-wrap fails the whole recovery.
     *
     * The alternative is a restore that reports success while some data keys
     * are missing: the history says the person has access to their notes and
     * the notes cannot be opened. Failing is the honest answer, and because
     * everything is one transaction the failure costs nothing.
     */
    @Test
    fun `a vault that cannot re-wrap a key fails the recovery rather than half of it`() = runTest {
        val handle = KeyHandle(KeyScope.CATEGORY, SharingCategory.PRIVATE_NOTES.name)
        repo.createDataKey(handle)
        val file = exportToFile()

        freshInstall()
        val before = repo.loadDeviceManifest()
        keyBackend.failNextImport = true

        val outcome = controller.importRecovery(file.readBytes(), code, sovereignReset = true)

        assertTrue(!outcome.applied, "a key that cannot be re-wrapped is not a partial success")
        assertEquals(before, repo.loadDeviceManifest(), "and nothing else was written either")
        assertTrue(repo.loadProjection().relationships.isEmpty())
    }

    // ------------------------------------------- a late failure leaves nothing

    /**
     * The second key fails, after the first has already been re-wrapped.
     *
     * Re-wrapping mints a fresh Keystore alias per handle, and a Keystore entry
     * is not part of the SQL transaction — rolling the database back leaves the
     * alias behind. So a failed recovery has to destroy the handles it created,
     * and only those: an alias this install already had is somebody's live data
     * key, and destroying it would erase content the recovery never touched.
     */
    @Test
    fun `a key that fails late destroys only the handles that recovery created`() = runTest {
        val notes = KeyHandle(KeyScope.CATEGORY, SharingCategory.PRIVATE_NOTES.name)
        val videos = KeyHandle(KeyScope.CATEGORY, SharingCategory.VIDEOS.name)
        repo.createDataKey(notes)
        repo.createDataKey(videos)
        val file = exportToFile()

        freshInstall()
        // An owner key this install already holds, which the backup does not
        // mention and the recovery must not touch.
        val ownKey = KeyHandle(KeyScope.OBJECT, "pre-existing")
        val ownWrapped = repo.createDataKey(ownKey)
        val aliasesBefore = keyBackend.aliases()
        val digestBefore = dbDigest()

        keyBackend.failImportAfter = 1
        val outcome = controller.importRecovery(file.readBytes(), code, sovereignReset = true)

        assertTrue(!outcome.applied, "a key that cannot be re-wrapped fails the recovery")
        assertTrue(
            keyBackend.importsAttempted > 1,
            "the test has to reach the *second* key, or it proves nothing: " +
                "attempted=${keyBackend.importsAttempted}",
        )
        assertEquals(digestBefore, dbDigest(), "the database is byte-identical")
        assertEquals(aliasesBefore, keyBackend.aliases(), "no alias the recovery minted survives")
        assertEquals(
            ownWrapped.wrappedBytes.toList(),
            assertNotNull(repo.readWrappedKey(ownKey)).wrappedBytes.toList(),
            "and the key this install already had is untouched",
        )
    }

    /**
     * A relationship entry the ledger refuses fails the whole import.
     *
     * Its result used to be dropped on the floor, so a backup carrying an entry
     * this install would not admit restored everything *around* it and reported
     * success — a history with a hole in it that nothing downstream can see.
     */
    @Test
    fun `an entry the ledger refuses fails the whole import`() = runTest {
        val file = exportToFile()
        freshInstall()

        // Restore once, then purge alice. Her tombstone now makes every entry
        // about her inadmissible, so importing the same file again must fail
        // rather than quietly restore everything except her.
        assertTrue(controller.importRecovery(file.readBytes(), code, sovereignReset = true).applied)
        controller.purge(alice)
        val digestBefore = dbDigest()

        val outcome = controller.importRecovery(file.readBytes(), code, sovereignReset = true)

        assertTrue(!outcome.applied, "an entry the ledger refuses fails the whole import")
        assertEquals(digestBefore, dbDigest(), "a refused entry rolls the whole file back")
    }

    private fun dbDigest(): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(java.io.File(tmp, "restored.db").readBytes()).joinToString("") { b ->
            ((b.toInt() and 0xff) + 0x100).toString(16).substring(1)
        }
    }

    /**
     * FEAT-062 §12.1: the frontier an act bound survives the round trip.
     *
     * The context is inside the act's signature, so a backup that dropped it,
     * reordered it or re-derived it would produce a file whose acts no longer
     * verify — and a restore that "helpfully" rewrote it to the target's own
     * frontier would be asserting the signer had seen an estate it never saw.
     */
    @Test
    fun `the bound manifest context survives export and restore unchanged`() = runBlocking {
        val before = repo.loadAttestations()
            .associate { it.id.value to it.manifestContext }
        assertTrue(before.isNotEmpty(), "the fixture has to have written some acts")
        val code = controller.newRecoveryCode()
        val exported = (controller.exportBackup(code) as SharingBackupWriteOutcome.Written).bytes

        driver.close()
        java.io.File(tmp, "context-restored.db").let { file ->
            open(file)
            SecureDatabase.Schema.create(driver)
        }
        controller.importRecovery(exported, code, sovereignReset = true)

        val after = repo.loadAttestations().associate { it.id.value to it.manifestContext }
        before.forEach { (id, context) ->
            assertEquals(context, after[id], "act $id must keep the frontier its device signed")
        }
    }
}

private const val MAGIC_LENGTH = 9 // "CCSHAREBK"
