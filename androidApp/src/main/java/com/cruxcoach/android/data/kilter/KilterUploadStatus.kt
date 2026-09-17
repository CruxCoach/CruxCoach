package com.cruxcoach.android.data.kilter

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class KilterUploadReason { NONE, DISABLED, AUTHENTICATION, WALL_CONTEXT, NETWORK, HTTP, CONFLICT, INTERNAL }

/** Only fixed categories and counts may cross the diagnostics boundary. */
@Serializable
data class KilterUploadStatus(
    val uploaded: Int = 0,
    val pending: Int = 0,
    val attempted: Int = 0,
    val reason: KilterUploadReason = KilterUploadReason.NONE,
    val httpStatus: Int? = null,
    val timestampMs: Long = System.currentTimeMillis(),
    val durationMs: Long = 0,
    val appVersion: String = "${com.cruxcoach.android.BuildConfig.VERSION_NAME} (${com.cruxcoach.android.BuildConfig.VERSION_CODE})",
    val trigger: KilterUploadTrigger = KilterUploadTrigger.MANUAL,
) {
    val failed: Boolean get() = reason != KilterUploadReason.NONE && reason != KilterUploadReason.DISABLED
}

@Serializable
enum class KilterUploadTrigger { MANUAL, ENABLED, NEW_LOG, APP_START }

class KilterLogConflictException : Exception("Existing Kilter log differs from local entry")

class KilterUploadException(val status: Int) : Exception("Kilter upload HTTP $status")

/** Bounded, expiring, secret-free upload history; no raw exceptions or API bodies. */
@Singleton
class KilterUploadDiagnostics @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("kilter_upload_diagnostics", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val history = runCatching {
        json.decodeFromString<List<KilterUploadStatus>>(prefs.getString("history", "[]") ?: "[]")
    }.getOrDefault(emptyList()).filter { it.timestampMs >= System.currentTimeMillis() - MAX_AGE }.takeLast(20).toMutableList()
    private val _latest = MutableStateFlow(history.lastOrNull())
    val latest = _latest.asStateFlow()

    init { prefs.edit().putString("history", json.encodeToString(history)).apply() }

    @Synchronized fun record(status: KilterUploadStatus) {
        history.removeAll { it.timestampMs < System.currentTimeMillis() - MAX_AGE }
        history.add(status)
        while (history.size > 20) history.removeAt(0)
        prefs.edit().putString("history", json.encodeToString(history)).apply()
        _latest.value = status
    }

    @Synchronized fun snapshot(): String = history
        .filter { it.timestampMs >= System.currentTimeMillis() - MAX_AGE }
        .joinToString("\n") { diagnosticLine(it) }

    @Synchronized fun clear() {
        history.clear()
        prefs.edit().remove("history").apply()
        _latest.value = null
    }

    companion object {
        private const val MAX_AGE = 7L * 24 * 60 * 60 * 1000
        fun diagnosticLine(s: KilterUploadStatus): String =
            "app=${s.appVersion} operation=logs.bulk trigger=${s.trigger} time=${s.timestampMs} durationMs=${s.durationMs} " +
                "attempted=${s.attempted} uploaded=${s.uploaded} pending=${s.pending} reason=${s.reason} http=${s.httpStatus ?: "none"}"
    }
}
