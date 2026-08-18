package com.cruxcoach.android.fips

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

internal interface RealmSecretStorage {
    fun secretHex(realmId: String): String?
    fun putSecret(realmId: String, secretHex: String)
    fun realmIds(): Set<String>
    fun remove(realmId: String)
}

/** Stable per-realm identity policy, separated so persistence can be JVM-tested. */
internal class FipsRealmCredentialLedger(
    private val storage: RealmSecretStorage,
    private val maxRealms: Int = 64,
    private val newSecret: () -> ByteArray = { ByteArray(32).also(SecureRandom()::nextBytes) },
) {
    @Synchronized
    fun activate(realmId: String): String {
        require(realmId.isNotBlank())
        storage.secretHex(realmId)?.takeIf { it.length == 64 }?.let { return it }
        val generated = newSecret().also { require(it.size == 32) }.toHex()
        if (storage.realmIds().size >= maxRealms) {
            // Realm IDs are public scope identifiers; deterministic bounded
            // eviction avoids retaining an unbounded set of secret identities.
            storage.realmIds().filter { it != realmId }.sorted().firstOrNull()?.let(storage::remove)
        }
        storage.putSecret(realmId, generated)
        return generated
    }

    /** Ending transport does not rotate membership identity on reconnect. */
    fun end(realmId: String) = Unit
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}

/** FIPS transport identity. It is intentionally unrelated to NostrKeyStore/account npub. */
@Singleton
class FipsRealmKeyStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "fips_realm_secure_v1", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
    private val ledger by lazy {
        FipsRealmCredentialLedger(object : RealmSecretStorage {
            override fun secretHex(realmId: String): String? {
                prefs.getString(secretKey(realmId), null)?.let { return it }
                // One-time migration from the former single-active-realm store.
                val legacy = prefs.getString(KEY_REALM, null) to prefs.getString(KEY_SECRET, null)
                if (legacy.first == realmId && legacy.second?.length == 64) {
                    putSecret(realmId, legacy.second!!)
                    prefs.edit().remove(KEY_REALM).remove(KEY_SECRET).commit()
                    return legacy.second
                }
                return null
            }
            override fun putSecret(realmId: String, secretHex: String) {
                prefs.edit().putString(secretKey(realmId), secretHex).commit()
            }
            override fun realmIds(): Set<String> = prefs.all.keys.asSequence()
                .filter { it.startsWith(SECRET_PREFIX) }.map { it.removePrefix(SECRET_PREFIX) }.toSet()
            override fun remove(realmId: String) {
                prefs.edit().remove(secretKey(realmId)).commit()
            }
        })
    }

    fun activate(realmId: String): String = ledger.activate(realmId)
    fun end(realmId: String) = ledger.end(realmId)

    private companion object {
        const val SECRET_PREFIX = "realm_secret:"
        const val KEY_REALM = "active_realm"
        const val KEY_SECRET = "active_secret"
        fun secretKey(realmId: String) = "$SECRET_PREFIX$realmId"
    }
}
