package com.cruxcoach.android.competition

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted, device-local copy of signed competition events.
 *
 * One preference entry per event avoids rewriting an ever-growing JSON array
 * after every attempt. Event order is deliberately not storage order: the
 * signed authority `seq`/`prev` chain is the only order the reducer trusts.
 */
@Singleton
class CompetitionLocalEventStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs by lazy {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            "competition_local_events_v1",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    @Synchronized
    fun put(compId: String, eventId: String, eventJson: String) {
        require(compId.matches(COMP_ID) && eventId.matches(EVENT_ID))
        check(prefs.edit().putString(key(compId, eventId), eventJson).commit()) {
            "competition event could not be stored"
        }
    }

    @Synchronized
    fun load(compId: String): List<String> {
        if (!compId.matches(COMP_ID)) return emptyList()
        val prefix = prefix(compId)
        return prefs.all.entries.asSequence()
            .filter { it.key.startsWith(prefix) }
            .mapNotNull { it.value as? String }
            .toList()
    }

    @Synchronized
    fun clear(compId: String) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix(compId)) }.forEach(editor::remove)
        check(editor.commit()) { "competition events could not be cleared" }
    }

    private fun key(compId: String, eventId: String) = "event:$compId:$eventId"
    private fun prefix(compId: String) = "event:$compId:"

    private companion object {
        val COMP_ID = Regex("^[0-9a-f]{16}$")
        val EVENT_ID = Regex("^[0-9a-f]{64}$")
    }
}
