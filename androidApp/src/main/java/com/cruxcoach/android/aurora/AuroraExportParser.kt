package com.cruxcoach.android.aurora

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses an Aurora email-export JSON string into [AuroraExportData].
 *
 * Lenient on unknown top-level keys (`follows`, `walls`, `blocks`,
 * `beta_links`, `agreements`, ...) — they're silently dropped at parse
 * time. Strict on required fields per [AuroraExportSchema] —
 * malformed input surfaces as a [Result.failure] with the
 * `SerializationException` message.
 *
 * Stream-parsing is **not** needed: Aurora's user-facing exports cap
 * out around a few MB even for power users (~1 MB for typical accounts).
 * We load the whole file into a String and decode in one pass.
 */
@Singleton
class AuroraExportParser @Inject constructor() {

    fun parse(json: String): Result<AuroraExportData> = try {
        Result.success(JSON.decodeFromString(AuroraExportData.serializer(), json))
    } catch (e: SerializationException) {
        Result.failure(e)
    } catch (e: IllegalArgumentException) {
        Result.failure(e)
    }

    companion object {
        private val JSON = Json {
            ignoreUnknownKeys = true
            // Aurora sometimes emits `null` where a non-nullable string
            // would be expected (e.g. a circuit description); coerce
            // those to defaults rather than throwing.
            coerceInputValues = true
            explicitNulls = false
        }
    }
}
