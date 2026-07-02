package com.cruxcoach.domain.playlist

/**
 * Sports-science constants for the playlist generators, expressed in the
 * canonical Aurora difficulty scale (10 = V0/4a … 34 = V17/9a, +2 ≈ one
 * V-grade). Offsets are relative to the user's logbook-derived max
 * redpoint (`max`) and reliable flash grade (`flash ≈ max − 4` fallback).
 *
 * Sources (condensed): Lattice "perfect 1-hour session" (20 boulders at
 * flash / 3×20-min max projects / 3×2 limit format), Hörst limit-bouldering
 * & power-endurance protocols (limit ≤ max+1 grade, PE 2-3 grades below
 * max, work:rest 1:1 laps / 4-5 min sets), classic 4-3-2-1 grade pyramids,
 * competition time-motion studies (~30 s climbing / ~115 s rest per
 * attempt). See docs/specs entry for the full citation list.
 */
object TrainingRanges {

    /** One V-grade in difficulty points. */
    const val DIFF_PER_V_GRADE = 2.0

    /** Absolute scale bounds (V0 … V17). */
    const val MIN_DIFFICULTY = 10.0
    const val MAX_DIFFICULTY = 34.0

    /** Fallback flash offset when the logbook has no flash data:
     *  flash ≈ max − 2 V-grades. */
    const val FLASH_FALLBACK_OFFSET = 2 * DIFF_PER_V_GRADE

    /** Hard safety ceiling: never plan above max + 1 V-grade, any mode. */
    const val CEILING_ABOVE_MAX = 1 * DIFF_PER_V_GRADE

    // ── Per-type grade bands (offsets in difficulty points) ─────

    /** Volume: flash − 2 V … flash. */
    const val VOLUME_BAND_BELOW_FLASH = 2 * DIFF_PER_V_GRADE

    /** Limit / projecting: max … max + 1 V. */
    const val LIMIT_BAND_ABOVE_MAX = 1 * DIFF_PER_V_GRADE

    /** Power endurance: max − 3 V … max − 2 V. */
    const val PE_BAND_LOW_BELOW_MAX = 3 * DIFF_PER_V_GRADE
    const val PE_BAND_HIGH_BELOW_MAX = 2 * DIFF_PER_V_GRADE

    /** Pyramid: base 3 V-grades below the apex, 1-V steps. */
    const val PYRAMID_BASE_BELOW_APEX = 3 * DIFF_PER_V_GRADE

    /** Warm-up ladder: start 5 V below max, 1-V steps. */
    const val WARMUP_START_BELOW_MAX = 5 * DIFF_PER_V_GRADE

    /** Per-tier tolerance when matching climbs to a planned grade (± half
     *  a V-grade keeps "a V5 slot" honest while accepting 6b vs 6b+). */
    const val SLOT_TOLERANCE = 1.0

    /** END_OF_SESSION fatigue shift: everything 1 V-grade easier. */
    const val END_OF_SESSION_SHIFT = 1 * DIFF_PER_V_GRADE

    // ── Rests (seconds) — explicit playlist entries ─────────────

    /** Between warm-up ladder problems (research: 30–90 s). */
    const val REST_WARMUP_BETWEEN_PROBLEMS = 60
    const val REST_AFTER_WARMUP = 240
    const val REST_LIMIT_BETWEEN_ATTEMPTS = 180
    const val REST_LIMIT_BETWEEN_PROBLEMS = 300
    const val REST_PROJECT_BETWEEN_BURNS = 240
    const val REST_PROJECT_BETWEEN_PROJECTS = 300
    const val REST_PE_BETWEEN_LAPS = 45
    const val REST_PE_BETWEEN_SETS = 270
    const val REST_PYRAMID_LOW_TIER = 90
    const val REST_PYRAMID_HIGH_TIER = 240
    /** Volume mid-session break (Lattice: 10 min after ~10 problems). */
    const val REST_VOLUME_MID_BREAK = 600

    // ── Duration → count scaling (minutes per slot incl. its rests) ──

    const val WARMUP_MINUTES = 18
    const val VOLUME_SLOT_MINUTES = 3
    const val LIMIT_SLOT_MINUTES = 20
    const val PROJECT_SLOT_MINUTES = 22
    const val PE_SET_MINUTES = 12
    const val PYRAMID_SLOT_MINUTES = 4

    /** Session duration bounds (minutes). */
    const val MIN_DURATION_MINUTES = 20
    const val MAX_DURATION_MINUTES = 150

    // ── Count clamps per type ────────────────────────────────────

    val VOLUME_COUNT = 8..30
    val LIMIT_COUNT = 2..6
    val PROJECT_COUNT = 1..3
    val PE_SETS = 1..4
    const val PE_PROBLEMS_PER_SET = 4

    /** Limit bouldering: attempts per problem, as EXPLICIT playlist
     *  entries (same climb repeated) with between-attempt rests — the
     *  rests are the training structure, so they belong in the list. */
    const val ATTEMPTS_PER_LIMIT_PROBLEM = 5

    /** Projecting: burns per project, explicit like limit attempts. */
    const val BURNS_PER_PROJECT = 4

    /** Warm-up ladder: 2 problems per tier, at most this many total. */
    const val WARMUP_PROBLEMS_PER_TIER = 2
    const val WARMUP_MAX_PROBLEMS = 6

    /** Default profile when the logbook is empty (≈ V5, mirrors
     *  IntensityZoneEngine's fallback) — the UI must flag this. */
    const val DEFAULT_MAX_DIFFICULTY = 20.0
}
