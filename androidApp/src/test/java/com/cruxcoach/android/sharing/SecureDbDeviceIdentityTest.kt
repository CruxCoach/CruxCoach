package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.SecureDatabasePragmas
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.AeadSharingKeyVault
import com.cruxcoach.domain.sharing.InMemoryWrappingKeyStore
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FEAT-062 §11: this install's own signing identity.
 *
 * A device signs its administrative acts with a key nobody else holds, and the
 * manifest records the public half. Two things have to be true for that to mean
 * anything: the private half never leaves sealed storage, and the identity is
 * the *same one* after a restart — a device that mints a new key on every launch
 * is a device the manifest stops recognising the moment it is closed.
 */
class SecureDbDeviceIdentityTest {

    private lateinit var dbFile: java.io.File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var store: InMemoryWrappingKeyStore
    private lateinit var identity: SecureDbDeviceIdentity

    /**
     * Stands in for secp256k1, which is native and has no JVM implementation.
     *
     * Deliberately keeps the property the real curve has and the storage logic
     * depends on: a signature by one key does not verify under another. What is
     * *not* covered here is the curve binding itself, exactly as for
     * KeystoreWrappingKeyStore — see the class comment on DeviceKeys.
     */
    private object StandInKeys : DeviceKeys {
        private var counter = 0
        override fun newPrivateKey(): ByteArray = ByteArray(32) { (counter++ + it).toByte() }
        override fun publicKeyOf(privateKey: ByteArray) =
            ByteArray(32) { (privateKey[it].toInt() xor 0x5a).toByte() }
        override fun sign(hash: ByteArray, privateKey: ByteArray) =
            publicKeyOf(privateKey) + hash
        override fun verify(signature: ByteArray, hash: ByteArray, publicKey: ByteArray) =
            signature.contentEquals(publicKey + hash)
    }

    private fun open(keyStore: InMemoryWrappingKeyStore = store) {
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}?foreign_keys=on").also { d ->
            SecureDatabasePragmas.STATEMENTS.forEach { runCatching { d.execute(null, it, 0).value } }
        }
        store = keyStore
        identity = SecureDbDeviceIdentity(
            database = SecureDatabase(driver),
            vault = AeadSharingKeyVault(keyStore),
            keys = StandInKeys,
        )
    }

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-device-identity-")
        dbFile = tmp.resolve("secure.db").toFile()
        open(InMemoryWrappingKeyStore())
        SecureDatabase.Schema.create(driver)
    }

    @AfterTest
    fun tearDown() {
        runCatching { driver.close() }
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    @Test
    fun `an identity is minted on first use`() {
        val minted = identity.loadOrCreate()

        assertNotNull(minted)
        assertTrue(minted.device.value.isNotBlank())
        assertEquals(minted.device.value, minted.publicKey, "the id is the public key, so it is canonical")
    }

    @Test
    fun `the same identity comes back on every later call`() {
        val first = assertNotNull(identity.loadOrCreate())

        assertEquals(first, identity.loadOrCreate())
        assertEquals(first, identity.loadOrCreate())
    }

    /**
     * The one that matters. A device that mints a new key on each launch stops
     * being the device the manifest enrolled the moment the process ends.
     */
    @Test
    fun `the identity survives a restart`() {
        val before = assertNotNull(identity.loadOrCreate())
        val keyStore = store

        driver.close()
        open(keyStore)

        assertEquals(before, identity.loadOrCreate())
    }

    @Test
    fun `the private key is never stored in the clear`() {
        assertNotNull(identity.loadOrCreate())

        val rows = mutableListOf<String>()
        driver.executeQuery(
            null,
            "SELECT sealed_private_key, public_key FROM sharing_device_identity",
            { cursor ->
                while (cursor.next().value) {
                    rows += cursor.getString(0).orEmpty()
                    rows += cursor.getString(1).orEmpty()
                }
                app.cash.sqldelight.db.QueryResult.Unit
            },
            0,
        )

        assertEquals(2, rows.size)
        assertTrue(rows[0].isNotBlank())
        assertTrue(rows[0] != rows[1], "the sealed key must not be the public key")
    }

    @Test
    fun `device attestations hash canonical data with SHA256 before BIP340`() {
        assertNotNull(identity.loadOrCreate())
        val crypto = assertNotNull(identity.crypto())
        val canonical = "synthetic attestation with a canonical preimage longer than thirty two bytes".encodeToByteArray()
        assertTrue(crypto.hash(canonical).contentEquals(java.security.MessageDigest.getInstance("SHA-256").digest(canonical)))
        assertNull(crypto.sign(canonical))
        assertNotNull(crypto.sign(crypto.hash(canonical)))
    }

    @Test
    fun `it signs and its own verifier accepts`() {
        val minted = assertNotNull(identity.loadOrCreate())
        val crypto = assertNotNull(identity.crypto())

        val hash = ByteArray(32) { it.toByte() }
        val signature = assertNotNull(crypto.sign(hash))

        assertTrue(crypto.verify(signature, hash, minted.publicKey))
    }

    @Test
    fun `a signature does not verify against another key`() {
        assertNotNull(identity.loadOrCreate())
        val crypto = assertNotNull(identity.crypto())
        val hash = ByteArray(32) { it.toByte() }
        val signature = assertNotNull(crypto.sign(hash))

        assertTrue(!crypto.verify(signature, hash, "00".repeat(32)))
    }

    /**
     * Losing the wrapping key means the sealed private key cannot be opened.
     * The honest answer is no identity at all — a freshly minted one would
     * silently stop matching the manifest, which reads as "this device was
     * revoked" to everybody including its owner.
     */
    @Test
    fun `an unopenable identity is no identity rather than a new one`() {
        val before = assertNotNull(identity.loadOrCreate())

        driver.close()
        open(InMemoryWrappingKeyStore())

        assertNull(identity.loadOrCreate(), "it must not quietly mint a replacement")
        assertTrue(before.publicKey.isNotBlank())
    }

    @Test
    fun `no identity means no crypto rather than an unsigned one`() {
        driver.close()
        open(InMemoryWrappingKeyStore())
        identity.loadOrCreate()

        driver.close()
        open(InMemoryWrappingKeyStore())

        assertNull(identity.crypto())
    }
}
