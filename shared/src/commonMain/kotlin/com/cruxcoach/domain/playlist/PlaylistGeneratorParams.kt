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

    /**
     * Few whole problems at or just above the working max, several tries
     * each, full rests between — what the UI calls "hard bouldering".
     *
     * Deliberately NOT classic limit bouldering, and named accordingly. That
     * protocol works a handful of moves at the absolute limit and stops when
     * power drops; a board problem is an indivisible unit, so nothing here can
     * offer three moves rather than a whole climb, and a mode that claimed to
     * would be making the same promise this rename removed. A climber who
     * wants that shape can build it in [MANUAL]: one or two problems above
     * their max, few tries, long rests.
     */
    LIMIT,

    /** Work open projects (attempted but unsent) near max. */
    PROJECTING,

    /**
     * Nothing derived: the climber states the grade range, how many problems,
     * how many tries each and how long the rests are.
     *
     * The other five read a profile and apply a protocol. This one does not —
     * so the range is a constraint rather than a starting point, and the
     * filler may not widen out of it. If the board has nothing in there, the
     * session comes up short and says so, which is the honest answer to a
     * question the climber asked precisely.
     */
    MANUAL,
}

/**
 * How far round the pyramid goes.
 *
 * The classic session pyramid climbs up to the apex and back down again; the
 * generator only ever added the descent for sessions of 90 minutes or more, so
 * everything shorter was half a pyramid presented as a whole one. Now the
 * climber chooses, and duration only decides how many tiers fit.
 */
enum class PyramidShape {
    /** Up to the apex and stop — a build-up, not a full pyramid. */
    ASCENDING,

    /** Up and back down, mirroring the ascent minus the apex. */
    UP_AND_DOWN,
}

/** What a session type counts, and the range the UI offers. */
fun GeneratorType.structureRange(): IntRange = when (this) {
    GeneratorType.VOLUME -> TrainingRanges.VOLUME_COUNT
    GeneratorType.LIMIT -> TrainingRanges.LIMIT_COUNT
    GeneratorType.PROJECTING -> TrainingRanges.PROJECT_COUNT
    GeneratorType.POWER_ENDURANCE -> TrainingRanges.PE_SETS
    GeneratorType.PYRAMID -> TrainingRanges.PYRAMID_TIERS
    GeneratorType.MANUAL -> TrainingRanges.MANUAL_COUNT
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

/** Which climbs the filler should PREFER when several fit a slot.
 *  A soft preference, not a hard filter — when the primary group has no
 *  candidate for a slot, the others fill in so the session never
 *  silently shrinks. */
enum class CandidateSelection {
    /** Never-tried climbs first (fresh stimulus — the default). */
    NEW,

    /** Attempted-but-unsent climbs first (work the open projects). */
    PROJECTS,

    /** No preference — quality decides. */
    ALL,
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
    /** Candidate preference (soft) — see [CandidateSelection]. */
    val selection: CandidateSelection = CandidateSelection.NEW,
    /** Pyramid only — see [PyramidShape]. */
    val pyramidShape: PyramidShape = PyramidShape.ASCENDING,
    /** Optional hard grade range chosen in the generator. Null keeps the
     * logbook-derived recommendation used by older saved playlists. */
    val targetMinDifficulty: Double? = null,
    val targetMaxDifficulty: Double? = null,
    /** Pyramid only: a fixed number of climbs on every grade tier. Null keeps
     * the classic 4-3-2-1 shape for older saved playlists. */
    val pyramidClimbsPerTier: Int? = null,
    /**
     * How big the session is, in whatever the type counts: problems for
     * volume and hard bouldering, projects, 4x4 sets, pyramid tiers.
     *
     * This is what the generator actually varies. [durationMinutes] used to be
     * the only input and was divided down to one of these — so the slider was
     * far finer than its effect (every setting from 40 to 150 minutes built
     * the same four 4x4 sets) and the arithmetic ran the wrong way round: the
     * climber set a time, the planner guessed a structure, and the estimate
     * then disagreed with the time they had set.
     *
     * Null keeps the old division, for playlists saved before this existed.
     */
    val structureSize: Int? = null,
    /**
     * Manual mode: the grade band, in Aurora points. Zero means "not set" and
     * the UI seeds it from the profile so the first screen is never empty.
     */
    val manualMinDifficulty: Double = 0.0,
    val manualMaxDifficulty: Double = 0.0,
    /** Manual mode: tries per problem — 1 is a straight lap list. */
    val manualRepeats: Int = 1,
    /**
     * Interval mode: problems inside one set. Four is the 4x4; null keeps it
     * for playlists saved before this was a control.
     */
    val problemsPerSet: Int? = null,
    /** Manual mode: seconds between problems, and between tries of one. */
    val manualRestSeconds: Int = TrainingRanges.MANUAL_DEFAULT_REST,
    val manualRepeatRestSeconds: Int = TrainingRanges.MANUAL_DEFAULT_REPEAT_REST,
    /** Relevant board-browser filters captured when the list was generated. */
    val minAscensionists: Int = 0,
    val browserMinDifficulty: Double = TrainingRanges.MIN_DIFFICULTY,
    val browserMaxDifficulty: Double = TrainingRanges.MAX_DIFFICULTY,
    val benchmarkOnly: Boolean = false,
    val originFilter: String = "ALL",
    val statusFilter: String = "ALL",
    val climbType: String = "BOULDER",
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
