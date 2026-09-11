package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.SecureDatabasePragmas
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.AeadSharingKeyVault
import com.cruxcoach.domain.sharing.AsyncLedgerCrypto
import com.cruxcoach.domain.sharing.AuthorityDeviceId
import com.cruxcoach.domain.sharing.DeviceManifestBody
import com.cruxcoach.domain.sharing.DeviceManifestEntry
import com.cruxcoach.domain.sharing.DeviceManifestSigner
import com.cruxcoach.domain.sharing.DeviceRole
import com.cruxcoach.domain.sharing.InMemoryWrappingKeyStore
import com.cruxcoach.domain.sharing.LedgerCrypto
import com.cruxcoach.domain.sharing.LedgerEntryId
import com.cruxcoach.domain.sharing.OwnerPolicySigner
import com.cruxcoach.domain.sharing.RecoveryChallenge
import com.cruxcoach.domain.sharing.RootRecoveryAuthorization
import com.cruxcoach.domain.sharing.SharingLedgerSigner
import com.cruxcoach.domain.sharing.SharingRecoveryCode
import com.cruxcoach.domain.sharing.asAsync
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FEAT-062 §11: the recovery authorisation is checked where the writing
 * happens, against a key the caller did not choose.
 *
 * ## Three attempts, and what was wrong with each
 *
 * A private constructor was not a gate, because the companion factory beside it
 * was `internal` and so module-wide. A factory that took a `verified: Boolean`
 * was the removed boolean wearing a type. A factory that verified for itself
 * was still forgeable, because it took the **verifier** as a parameter — hand
 * it one that always says yes and it mints whatever you asked for.
 *
 * Each time, the mistake was the same: trying to make a *token* that could only
 * be created honestly, while leaving the creating code reachable from the whole
 * module. So the token is gone. [RootRecoveryAuthorization] is inert data —
 * anybody may construct one and it means nothing. What decides is the
 * repository, which owns the root key it was built with and checks the
 * signature itself before it writes anything.
 *
 * A fake verifier is injectable, but only through the repository's constructor,
 * which is where a test is supposed to say "pretend this key is the owner's".
 * It is not something an individual call can pass.
 */
class RootRecoveryAuthorizationTest {

    private companion object {
        const val OWNER = "npub1owner"
        val DEVICE = AuthorityDeviceId("aaaa1111")
        const val DEVICE_KEY = "pk-aaaa1111"
        const val GENERATION = 0L
    }

    private class TaggedCrypto(private val identity: String) : LedgerCrypto {
        override fun sign(hash: ByteArray): ByteArray = (identity + ":").encodeToByteArray() + hash
        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
            signature.contentEquals((signerNpub + ":").encodeToByteArray() + hash)
    }

    /** The forger's dream: says yes to anything. */
    private object AlwaysYes : AsyncLedgerCrypto {
        override suspend fun signCanonical(hash: ByteArray): ByteArray = ByteArray(0)
        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) = true
    }

    private lateinit var dbFile: java.io.File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repo: SecureDbSharingRepository

    private val ownerCrypto = TaggedCrypto(OWNER)
    private val code = SharingRecoveryCode.fromEntropy(ByteArray(20) { 3 })

    /** What this install says it is. Bound at construction, never per commit. */
    private var localIdentity: DeviceIdentity? = DeviceIdentity(DEVICE, DEVICE_KEY)

    private fun open(rootVerifier: AsyncLedgerCrypto? = ownerCrypto.asAsync()) {
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}?foreign_keys=on").also { d ->
            SecureDatabasePragmas.STATEMENTS.forEach { runCatching { d.execute(null, it, 0).value } }
        }
        repo = SecureDbSharingRepository(
            database = SecureDatabase(driver),
            vault = AeadSharingKeyVault(InMemoryWrappingKeyStore()),
            verifier = SharingLedgerSigner(ownerCrypto).verifier(),
            ownerPolicyVerifier = OwnerPolicySigner(ownerCrypto).verifier(),
            ownerNpub = OWNER,
            deviceManifestVerifier = DeviceManifestSigner(ownerCrypto).verifier(),
            rootRecoveryVerifier = rootVerifier,
            localDeviceIdentity = { localIdentity },
        )
    }

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-root-authorisation-")
        dbFile = tmp.resolve("secure.db").toFile()
        open()
        SecureDatabase.Schema.create(driver)
    }

    @AfterTest
    fun tearDown() {
        runCatching { driver.close() }
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    private fun digest(recoveryCode: String = code) =
        ownerCrypto.hash(SharingRecoveryCode.normalise(recoveryCode).encodeToByteArray())
            .joinToString("") { b -> ((b.toInt() and 0xff) + 0x100).toString(16).substring(1) }

    private fun authorisation(
        ownerNpub: String = OWNER,
        devicePublicKey: String = DEVICE_KEY,
        recoveryCodeDigest: String = digest(),
        generation: Long = GENERATION,
        sovereignReset: Boolean = false,
        nonce: String = "attempt-1",
        signWith: LedgerCrypto? = ownerCrypto,
    ): RootRecoveryAuthorization {
        val hash = ownerCrypto.hash(
            RecoveryChallenge.bytes(
                ownerNpub = ownerNpub,
                recoveryCodeDigest = recoveryCodeDigest,
                newDevicePublicKey = devicePublicKey,
                backupGeneration = generation,
                sovereignReset = sovereignReset,
                attemptNonce = nonce,
            ),
        )
        return RootRecoveryAuthorization(
            ownerNpub = ownerNpub,
            devicePublicKey = devicePublicKey,
            recoveryCodeDigest = recoveryCodeDigest,
            backupGeneration = generation,
            sovereignReset = sovereignReset,
            attemptNonce = nonce,
            signature = signWith?.sign(hash) ?: "garbage".encodeToByteArray(),
        )
    }

    private fun signed(id: String, seq: Long, parent: String?, generation: Long, body: DeviceManifestBody) =
        DeviceManifestSigner(ownerCrypto).sign(
            DeviceManifestEntry(
                id = LedgerEntryId(id),
                manifestSequence = seq,
                authorityGeneration = generation,
                parent = parent?.let { LedgerEntryId(it) },
                signerNpub = OWNER,
                signature = "",
                body = body,
            ),
        )!!

    /**
     * What a recovery actually writes: the opening move, then this device's
     * re-enrolment under the generation it establishes.
     */
    private fun plan(
        reset: Boolean = false,
        base: Long = 0,
        device: AuthorityDeviceId = DEVICE,
        deviceKey: String = DEVICE_KEY,
    ): List<DeviceManifestEntry> {
        val opening = if (reset) {
            DeviceManifestBody.SovereignReset
        } else {
            DeviceManifestBody.AuthorityRotated(base + 1, fenceExisting = true)
        }
        return listOf(
            signed("m-1", 1, null, base, opening),
            signed(
                "m-2", 2, "m-1", base + 1,
                DeviceManifestBody.DeviceEnrolled(device, deviceKey, DeviceRole.PRIMARY),
            ),
        )
    }

    private fun commit(
        authorisation: RootRecoveryAuthorization,
        entries: List<DeviceManifestEntry> = plan(),
    ) = repo.commitRootRecovery(
        manifestEntries = entries,
        relationshipEntries = emptyList(),
        attestations = emptyList(),
        rootAuthorised = authorisation,
    )

    // ------------------------------------------------- the owner's key decides

    @Test
    fun `the owner's own signature opens the door`() {
        assertTrue(commit(authorisation()), repo.lastBatchRejection.orEmpty())
        assertEquals(2, repo.loadDeviceManifest().size)
    }

    /**
     * The forge the review names. The claim is well formed, every field says
     * what the forger wants, and the signature is rubbish — because the forger
     * has no owner key. Nothing it can pass in changes which key is used.
     */
    @Test
    fun `a claim nobody signed writes nothing`() {
        assertTrue(!commit(authorisation(signWith = null)))

        assertEquals(emptyList(), repo.loadDeviceManifest(), "nothing was written")
        assertTrue(repo.lastBatchRejection.orEmpty().contains("authorisation"))
    }

    @Test
    fun `a signature from somebody who is not the owner writes nothing`() {
        assertTrue(!commit(authorisation(signWith = TaggedCrypto("npub1impostor"))))

        assertEquals(emptyList(), repo.loadDeviceManifest())
    }

    /**
     * And the fake verifier is only reachable where a test is meant to reach
     * it: the constructor. Wired there it does open the door, which is exactly
     * why it must not be a parameter of the call.
     */
    @Test
    fun `an always-yes verifier is injectable only at construction`() {
        driver.close()
        open(rootVerifier = AlwaysYes)

        assertTrue(commit(authorisation(signWith = null)), repo.lastBatchRejection.orEmpty())
    }

    @Test
    fun `an unwired verifier refuses every recovery`() {
        driver.close()
        open(rootVerifier = null)

        assertTrue(!commit(authorisation()))
        assertEquals(emptyList(), repo.loadDeviceManifest())
    }

    // ------------------------------------------ bound to one exact recovery

    @Test
    fun `a claim about another recovery does not verify`() {
        val real = authorisation()

        listOf(
            "a different owner" to real.copy(ownerNpub = "npub1someoneelse"),
            "a different device key" to real.copy(devicePublicKey = "pk-somebody-else"),
            "a different code" to real.copy(recoveryCodeDigest = digest(SharingRecoveryCode.fromEntropy(ByteArray(20) { 9 }))),
            "a different generation" to real.copy(backupGeneration = GENERATION + 1),
            "a reset nobody agreed to" to real.copy(sovereignReset = true),
            "a different attempt" to real.copy(attemptNonce = "attempt-2"),
        ).forEach { (what, tampered) ->
            assertTrue(!commit(tampered), "$what must not verify")
            assertEquals(emptyList(), repo.loadDeviceManifest(), "$what wrote something")
        }
    }

    /**
     * The device key is checked against the one this install holds, not only
     * against the signature. An authorisation the owner genuinely signed for
     * another device must not enrol this one.
     */
    @Test
    fun `an authorisation for another device is refused even though it verifies`() {
        assertTrue(!commit(authorisation(devicePublicKey = "pk-other")))

        assertEquals(emptyList(), repo.loadDeviceManifest())
    }

    // ------------------------------------------------------------ replay

    /**
     * One answer, one recovery.
     *
     * Every other term of the challenge is the same from attempt to attempt —
     * same file, same code, same generation — so without the nonce a signature
     * captured once would be a standing licence to redo the recovery.
     */
    @Test
    fun `the same authorisation cannot be spent twice`() = runBlocking {
        val once = authorisation()
        assertTrue(commit(once), repo.lastBatchRejection.orEmpty())
        val after = repo.loadDeviceManifest()

        assertTrue(!commit(once), "a spent authorisation is not a second licence")
        assertEquals(after, repo.loadDeviceManifest())
    }

    @Test
    fun `a spent authorisation stays spent across a restart`() {
        assertTrue(commit(authorisation()))
        val after = repo.loadDeviceManifest()

        driver.close()
        open()

        assertTrue(!commit(authorisation()))
        assertEquals(after, repo.loadDeviceManifest())
    }

    // -------------------- bound to the mutation, not merely to its own terms

    /**
     * A signature proves what the owner agreed to. It says nothing about what
     * the caller then went on to write.
     *
     * Every case below carries a *genuinely signed* authorisation and pairs it
     * with a commit that does something else. Each has to be refused, write no
     * rows, and leave the attempt unspent — an authorisation refused for one
     * mutation is still the owner's answer for the right one.
     */
    /**
     * There is no per-commit key to pass any more, so the way to ask this is to
     * take the identity away: an install that cannot say which device it is
     * cannot be the device a recovery re-enrols.
     */
    @Test
    fun `an install with no device identity cannot be recovered onto`() {
        localIdentity = null

        assertTrue(!commit(authorisation()))
        assertEquals(emptyList(), repo.loadDeviceManifest())
    }

    @Test
    fun `an authorisation for a reset cannot open a rotation`() {
        // Signed for a sovereign reset; the entries perform a plain rotation.
        assertTrue(!commit(authorisation(sovereignReset = true)))

        assertEquals(emptyList(), repo.loadDeviceManifest())
    }

    @Test
    fun `a reset the owner did not agree to is refused`() {
        assertTrue(!commit(authorisation(sovereignReset = false), entries = plan(reset = true)))

        assertEquals(emptyList(), repo.loadDeviceManifest())
    }

    @Test
    fun `the enrolled device must be the one this install holds`() {
        assertTrue(
            !commit(authorisation(), entries = plan(device = AuthorityDeviceId("zzzz9999"))),
            "a recovery may only enrol the device that is running it",
        )
        assertEquals(emptyList(), repo.loadDeviceManifest())
    }

    @Test
    fun `the enrolled public key must be the one this install holds`() {
        assertTrue(!commit(authorisation(), entries = plan(deviceKey = "pk-somebody-else")))

        assertEquals(emptyList(), repo.loadDeviceManifest())
    }

    /**
     * The generation is not the authorisation's to declare against whatever the
     * entries happen to say. A signature for one base cannot open a rotation
     * from another.
     */
    @Test
    fun `an authorisation for another base generation is refused`() {
        assertTrue(!commit(authorisation(generation = GENERATION + 5)))

        assertEquals(emptyList(), repo.loadDeviceManifest())
    }

    @Test
    fun `a rotation that does not advance the base by one is refused`() {
        val jumped = listOf(
            signed("m-1", 1, null, GENERATION, DeviceManifestBody.AuthorityRotated(9, fenceExisting = true)),
            signed("m-2", 2, "m-1", 9, DeviceManifestBody.DeviceEnrolled(DEVICE, DEVICE_KEY, DeviceRole.PRIMARY)),
        )

        assertTrue(!commit(authorisation(), entries = jumped))
        assertEquals(emptyList(), repo.loadDeviceManifest())
    }

    /** A refused commit leaves the answer usable for the mutation it was for. */
    @Test
    fun `a refused commit does not spend the attempt`() {
        val once = authorisation()
        assertTrue(!commit(once, entries = plan(reset = true)))

        assertTrue(commit(once), repo.lastBatchRejection.orEmpty())
    }
}
