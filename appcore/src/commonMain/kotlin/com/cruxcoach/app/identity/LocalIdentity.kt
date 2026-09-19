package com.cruxcoach.app.identity

import com.cruxcoach.app.platform.Hashing
import com.cruxcoach.app.platform.SecretStore
import com.cruxcoach.app.util.toHex
import fr.acinq.secp256k1.Secp256k1

enum class IdentityFailure { SECRET_STORE_UNAVAILABLE, STORED_KEY_INVALID }

/** Public part of the active identity plus the material needed to open its SecureDB. Holds no Nostr secret. */
class ActiveIdentity(val pubkeyHex: String, val secureDbName: String, val secureDbKey: ByteArray)

class IdentityResult(val identity: ActiveIdentity?, val failure: IdentityFailure?, val created: Boolean)

/**
 * Local key custody. The Nostr private key and the database master key live only
 * in the [SecretStore] (Keychain). Naming and derivation follow Android:
 * `cruxcoach_secure_<pubkey[0..16]>.db`, key = HKDF-SHA256(master, salt = pubkeyHex, info = "cruxcoach-secure-db").
 */
class LocalIdentity(private val secrets: SecretStore, private val hashing: Hashing) {

    /**
     * Loads the identity, creating one only when the store verifiably holds none.
     * A stored but unusable key is an error, never a reason to mint a new identity:
     * that would silently orphan the user's encrypted logbook (same rule as NostrKeyStore).
     */
    fun loadOrCreate(): IdentityResult {
        var created = false
        var secret = secrets.read(NOSTR_KEY)
        if (secret == null) {
            secret = newSecretKey()
            if (!secrets.write(NOSTR_KEY, secret)) return IdentityResult(null, IdentityFailure.SECRET_STORE_UNAVAILABLE, false)
            // Read back: never continue with a key that was not durably stored.
            if (secrets.read(NOSTR_KEY)?.contentEquals(secret) != true) {
                return IdentityResult(null, IdentityFailure.SECRET_STORE_UNAVAILABLE, false)
            }
            created = true
        }
        if (secret.size != 32 || !Secp256k1.secKeyVerify(secret)) {
            return IdentityResult(null, IdentityFailure.STORED_KEY_INVALID, false)
        }
        var master = secrets.read(DB_MASTER_KEY)
        if (master == null) {
            master = hashing.randomBytes(32)
            if (!secrets.write(DB_MASTER_KEY, master)) return IdentityResult(null, IdentityFailure.SECRET_STORE_UNAVAILABLE, false)
        }
        if (master.size != 32) return IdentityResult(null, IdentityFailure.STORED_KEY_INVALID, false)

        val pubkeyHex = xOnlyPubkey(secret).toHex()
        secret.fill(0)
        val dbKey = hkdfSha256(hashing, master, pubkeyHex.encodeToByteArray(), INFO_SECURE_DB.encodeToByteArray(), 32)
        master.fill(0)
        return IdentityResult(ActiveIdentity(pubkeyHex, "cruxcoach_secure_${pubkeyHex.take(16)}.db", dbKey), null, created)
    }

    private fun newSecretKey(): ByteArray {
        while (true) {
            val candidate = hashing.randomBytes(32)
            if (Secp256k1.secKeyVerify(candidate)) return candidate
        }
    }

    companion object {
        const val NOSTR_KEY = "nostr_priv_key"
        const val DB_MASTER_KEY = "secure_db_master_key"
        const val INFO_SECURE_DB = "cruxcoach-secure-db"

        fun xOnlyPubkey(secret: ByteArray): ByteArray =
            Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(secret)).copyOfRange(1, 33)
    }
}

/** RFC 5869. */
fun hkdfSha256(hashing: Hashing, ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    require(length in 1..255 * 32)
    val prk = hashing.hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
    val out = ByteArray(length)
    var previous = ByteArray(0)
    var offset = 0
    var counter = 1
    while (offset < length) {
        previous = hashing.hmacSha256(prk, previous + info + byteArrayOf(counter.toByte()))
        val take = minOf(previous.size, length - offset)
        previous.copyInto(out, offset, 0, take)
        offset += take
        counter++
    }
    return out
}
