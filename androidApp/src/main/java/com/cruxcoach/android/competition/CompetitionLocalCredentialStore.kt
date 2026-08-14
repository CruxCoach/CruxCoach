package com.cruxcoach.android.competition

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/** Opaque local participant continuity; deliberately neither account npub nor FIPS node key. */
@Singleton
class CompetitionLocalCredentialStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs by lazy {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(context, "competition_local_credentials_v1", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }

    @Synchronized fun getOrCreate(compId: String): String = prefs.getString(compId, null) ?: ByteArray(24)
        .also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        .also { prefs.edit().putString(compId, it).commit() }

    @Synchronized fun end(compId: String) { prefs.edit().remove(compId).commit() }
}
