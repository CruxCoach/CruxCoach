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
    var realmId: String?
    var secretHex: String?
    fun clear()
}

/** Pure rotation policy, separated so persistence/rotation can be JVM-tested. */
internal class FipsRealmCredentialLedger(
    private val storage: RealmSecretStorage,
    private val newSecret: () -> ByteArray = { ByteArray(32).also(SecureRandom()::nextBytes) },
) {
    @Synchronized
    fun activate(realmId: String): String {
        require(realmId.isNotBlank())
        val existing = storage.secretHex
        if (storage.realmId == realmId && existing?.length == 64) return existing
        val generated = newSecret().also { require(it.size == 32) }.toHex()
        // Deliberately keep only the active realm: returning after a realm switch is a rotation.
        storage.clear()
        storage.realmId = realmId
        storage.secretHex = generated
        return generated
    }

    @Synchronized
    fun end(realmId: String) {
        if (storage.realmId == realmId) storage.clear()
    }

    fun activeRealm(): String? = storage.realmId
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
            override var realmId: String?
                get() = prefs.getString(KEY_REALM, null)
                set(value) { prefs.edit().putString(KEY_REALM, value).commit() }
            override var secretHex: String?
                get() = prefs.getString(KEY_SECRET, null)
                set(value) { prefs.edit().putString(KEY_SECRET, value).commit() }
            override fun clear() { prefs.edit().clear().commit() }
        })
    }

    fun activate(realmId: String): String = ledger.activate(realmId)
    fun end(realmId: String) = ledger.end(realmId)
    fun activeRealm(): String? = ledger.activeRealm()

    private companion object {
        const val KEY_REALM = "active_realm"
        const val KEY_SECRET = "active_secret"
    }
}
