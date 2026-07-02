package com.cruxcoach.domain.playlist

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Which training-session shape the generator builds. */
enum class GeneratorType {
    /** Grade pyramid up to (near) the user's max — e.g. 4×V3 3×V4 2×V5 1×V6. */
    PYRAMID,

    /** Kraftausdauer intervals (4x4-style): sets of sub-max problems with
     *  short lap rests and long set rests. */
    POWER_ENDURANCE,

    /** Many problems around flash level — density over intensity. */
    VOLUME,

    /** Limit bouldering: few problems at/above max, long rests. */
    LIMIT,

    /** Work open projects (attempted but unsent) near max. */
    PROJECTING,
}

/** Where in the training session the playlist will be climbed — shifts
 *  intensity and decides whether a warm-up ladder is prepended. */
enum class SessionPosition {
    /** Session start, not warmed up: prepend a warm-up ladder. */
    START_COLD,

    /** Already warmed up (or mid-session): plan as-is, no ladder. */
    WARMED_UP,

    /** End of session, fatigued: everything shifts easier and max-effort
     *  types (LIMIT/PROJECTING) downgrade to VOLUME — fatigued limit
     *  attempts are an injury risk, not training. */
    END_OF_SESSION,
}

/**
 * The full parameter snapshot a playlist was generated from. Persisted as
 * JSON in `climb_lists.generator_params` so "Neu generieren" can re-run
 * the exact same recipe later (fresh candidates, same shape).
 */
@Serializable
data class PlaylistGeneratorParams(
    val type: GeneratorType,
    val durationMinutes: Int,
    val position: SessionPosition,
    val angle: Int,
    val boardBrand: String,
    val layoutId: Int,
    /** Board size for the fit filter; 0 = no size filter. */
    val productSizeId: Int = 0,
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Null when the stored JSON is missing or unparseable (e.g. written
         *  by a future version with renamed enum values). */
        fun fromJson(raw: String?): PlaylistGeneratorParams? {
            if (raw.isNullOrBlank()) return null
            return try {
                json.decodeFromString(serializer(), raw)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
