package com.cruxcoach.app.identity

import com.cruxcoach.app.platform.SecretStore
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.app.util.hexToBytesOrNull
import com.cruxcoach.app.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemorySecrets(var failWrites: Boolean = false) : SecretStore {
    val items = mutableMapOf<String, ByteArray>()
    override fun read(name: String) = items[name]?.copyOf()
    override fun write(name: String, value: ByteArray): Boolean { if (failWrites) return false; items[name] = value.copyOf(); return true }
    override fun delete(name: String) = items.remove(name) != null
}

class LocalIdentityTest {
    @Test
    fun `hkdf matches RFC 5869 test case 1 and 3`() {
        val okm = hkdfSha256(
            JvmHashing, "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b".hexToBytesOrNull()!!,
            "000102030405060708090a0b0c".hexToBytesOrNull()!!, "f0f1f2f3f4f5f6f7f8f9".hexToBytesOrNull()!!, 42,
        )
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm.toHex())
        val noSalt = hkdfSha256(JvmHashing, ByteArray(22) { 0x0b }, ByteArray(0), ByteArray(0), 42)
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8", noSalt.toHex())
    }

    @Test
    fun `first launch creates one identity and later launches reuse it`() {
        val secrets = MemorySecrets()
        val first = LocalIdentity(secrets, JvmHashing).loadOrCreate()
        assertTrue(first.created)
        val again = LocalIdentity(secrets, JvmHashing).loadOrCreate()
        assertFalse(again.created)
        assertEquals(first.identity!!.pubkeyHex, again.identity!!.pubkeyHex)
        assertTrue(first.identity!!.secureDbKey.contentEquals(again.identity!!.secureDbKey))
        assertEquals("cruxcoach_secure_${first.identity!!.pubkeyHex.take(16)}.db", first.identity!!.secureDbName)
    }

    @Test
    fun `known secret key yields the BIP-340 public key and Android's derived database key`() {
        val secrets = MemorySecrets()
        // BIP-340 test vector 0: secret 0x…03.
        secrets.items[LocalIdentity.NOSTR_KEY] = ByteArray(32).also { it[31] = 3 }
        secrets.items[LocalIdentity.DB_MASTER_KEY] = ByteArray(32) { 1 }
        val identity = assertNotNull(LocalIdentity(secrets, JvmHashing).loadOrCreate().identity)
        assertEquals("f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9", identity.pubkeyHex)
        val expected = hkdfSha256(JvmHashing, ByteArray(32) { 1 }, identity.pubkeyHex.encodeToByteArray(), "cruxcoach-secure-db".encodeToByteArray(), 32)
        assertTrue(expected.contentEquals(identity.secureDbKey))
    }

    @Test
    fun `an unusable stored key is an error and is never replaced`() {
        val secrets = MemorySecrets()
        secrets.items[LocalIdentity.NOSTR_KEY] = ByteArray(32) // zero is not a valid scalar
        val result = LocalIdentity(secrets, JvmHashing).loadOrCreate()
        assertNull(result.identity)
        assertEquals(IdentityFailure.STORED_KEY_INVALID, result.failure)
        assertTrue(secrets.items[LocalIdentity.NOSTR_KEY]!!.all { it == 0.toByte() })
    }

    @Test
    fun `a keychain that refuses writes yields no identity`() {
        val result = LocalIdentity(MemorySecrets(failWrites = true), JvmHashing).loadOrCreate()
        assertNull(result.identity)
        assertEquals(IdentityFailure.SECRET_STORE_UNAVAILABLE, result.failure)
    }
}
