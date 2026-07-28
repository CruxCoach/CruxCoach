package com.cruxcoach.domain.playlist

/**
 * Sports-science constants for the playlist generators, expressed in the
 * canonical Aurora difficulty scale (10 = V0/4a … 34 = V17/9a, +2 ≈ one
 * V-grade). Offsets are relative to the user's logbook-derived max
 * redpoint (`max`) and reliable flash grade (`flash ≈ max − 4` fallback).
 *
 * Rest physiology anchor: maximal-effort attempts drain the ATP-CP
 * (phosphagen) system, which resynthesizes to ~90 % in ≈3 min and fully
 * in ≈5 min — so anything labelled "max effort" rests 4–5 min, anything
 * sub-maximal rests less, and power-endurance deliberately rests LESS
 * than full recovery (incomplete recovery is the stimulus).
 *
 * Per-protocol sources (condensed):
 *  - Volume/movement: Lattice "perfect 1-hour session" — ~20 problems in
 *    the flash band with ~1.5 min quality rests, one long mid-block break.
 *  - Limit bouldering: Hörst limit protocol — problems at/above max,
 *    3–5 attempts each, FULL rest (here 4 min) between attempts, 5 min
 *    between problems; quality over pump, session ends when power drops.
 *  - Projecting: redpoint-burn practice — full ATP-CP recovery plus
 *    mental reset per burn (5 min), longer when switching projects
 *    (read + ticks, 6 min).
 *  - Power endurance: classic 4x4 (Moffatt et al.) — 4 problems climbed
 *    back-to-back (only the walk/queue-next between laps), then one
 *    work-matched 4-min set rest; grade 2–3 V below max so every lap
 *    tops out despite the pump.
 *  - Pyramid: 4-3-2-1 grade ladder; rests scale with proximity to the
 *    apex (base flows, apex gets near-full recovery).
 *  - Warm-up: progressive ladder, short flowing rests, one longer
 *    transition rest before the work sets (injury prevention).
 */
object TrainingRanges {

    /** One V-grade in difficulty points. */
    const val DIFF_PER_V_GRADE = 2.0

    /** One Font half-step (7a→7a+) in difficulty points — the finest
     *  granularity the Aurora scale carries. Work bands are tuned on
     *  THIS unit: V-grade offsets are too coarse (V8 spans 7b AND 7b+),
     *  Font steps let each session type hit its exact intensity. */
    const val DIFF_PER_FONT_STEP = 1.0

    /** Absolute scale bounds (V0 … V17). */
    const val MIN_DIFFICULTY = 10.0
    const val MAX_DIFFICULTY = 34.0

    /** Fallback flash offset when the logbook has no flash data:
     *  flash ≈ max − 2 V-grades. */
    const val FLASH_FALLBACK_OFFSET = 2 * DIFF_PER_V_GRADE

    /** Hard safety ceiling: never plan above max + 1 V-grade, any mode. */
    const val CEILING_ABOVE_MAX = 1 * DIFF_PER_V_GRADE

    // ── Per-type grade bands (Font-step granular offsets) ───────

    /** Volume: flash − 3 Font steps … flash ("at or just below flash",
     *  Lattice) — the old 2-V band reached needlessly easy terrain. */
    const val VOLUME_BAND_BELOW_FLASH = 3 * DIFF_PER_FONT_STEP

    /** Limit: max … max + 1 Font step — hard enough to need 3-5 tries,
     *  close enough to send within the session (Hörst). A full V above
     *  max is project territory, not limit bouldering. */
    const val LIMIT_BAND_ABOVE_MAX = 1 * DIFF_PER_FONT_STEP

    /** Projecting: max + 1 … max + 2 Font steps — deliberately ABOVE the
     *  limit band; a project is multi-session difficulty. (Open projects
     *  from the logbook still take precedence over fresh candidates.) */
    const val PROJECT_BAND_LOW_ABOVE_MAX = 1 * DIFF_PER_FONT_STEP
    const val PROJECT_BAND_TOP_ABOVE_MAX = 2 * DIFF_PER_FONT_STEP

    /** Power endurance: max − 3 V … max − 2 V (fresh: 1-2 tries; lap 4:
     *  barely topping — the classic 4x4 window). */
    const val PE_BAND_LOW_BELOW_MAX = 3 * DIFF_PER_V_GRADE
    const val PE_BAND_HIGH_BELOW_MAX = 2 * DIFF_PER_V_GRADE

    /** Pyramid: Font-step tiers (6c → 6c+ → 7a → 7a+) — the classic Font
     *  pyramid; V-grade tiers jump twice as far and skip the half grades
     *  boards actually carry. How far the base sits below the apex follows
     *  from the tier count, which the session length decides, so it is not a
     *  constant: see PlaylistPlanner.pyramidBase. */
    const val PYRAMID_STEP = 1 * DIFF_PER_FONT_STEP

    /** Pyramid apex sits 2 Font steps (1 V) below max: a session pyramid
     *  only works when every tier actually gets TOPPED — an apex at the
     *  all-time max is a limit session in disguise. */
    const val PYRAMID_APEX_BELOW_MAX = 2 * DIFF_PER_FONT_STEP

    /** Warm-up ladder: start 5 V below max — but never closer than 3 V to
     *  the first working grade (easy sessions warm up on easier terrain),
     *  and climb up to 1 V below the working grade (no intensity jump
     *  into the first work set). Tiers within taper distance of the work
     *  grade get one problem instead of two (progressive activation). */
    const val WARMUP_START_BELOW_MAX = 5 * DIFF_PER_V_GRADE
    const val WARMUP_START_BELOW_FIRST_WORK = 3 * DIFF_PER_V_GRADE
    const val WARMUP_TAPER_DISTANCE = 3 * DIFF_PER_V_GRADE

    /** Per-tier tolerance when matching climbs to a planned grade (± half
     *  a V-grade keeps "a V5 slot" honest while accepting 6b vs 6b+). */
    const val SLOT_TOLERANCE = 1.0

    /** END_OF_SESSION fatigue shift: everything 1 V-grade easier. */
    const val END_OF_SESSION_SHIFT = 1 * DIFF_PER_V_GRADE

    // ── Rests (seconds) — explicit playlist entries ─────────────

    /** Warm-up ladder: short, flowing (research: 30–90 s). */
    const val REST_WARMUP_BETWEEN_PROBLEMS = 60

    /** Transition from warm-up into the first work set. */
    const val REST_AFTER_WARMUP = 240

    /** Volume: quality rest between problems — enough to keep movement
     *  crisp (~2:1 rest:work at ~45 s climbing), short enough for
     *  ~20 problems/hour (Lattice pacing). */
    const val REST_VOLUME_BETWEEN_PROBLEMS = 90

    /** Volume mid-session break (Lattice: ~10 min after half the block). */
    const val REST_VOLUME_MID_BREAK = 600

    /** Limit: max attempts need near-FULL ATP-CP recovery — 4 min between
     *  attempts (3 min is the floor, 5 the ceiling), 5 min when moving to
     *  the next problem. */
    const val REST_LIMIT_BETWEEN_ATTEMPTS = 240
    const val REST_LIMIT_BETWEEN_PROBLEMS = 300

    /** Projecting: a redpoint burn costs more than a limit attempt
     *  (longer time-on-wall + mental reset) — 5 min between burns,
     *  6 min when switching projects (includes reading the next one). */
    const val REST_PROJECT_BETWEEN_BURNS = 300
    const val REST_PROJECT_BETWEEN_PROJECTS = 360

    /** 4x4: laps run back-to-back — the 30 s is queue-next + walk, NOT
     *  recovery (incomplete recovery is the stimulus); sets get one
     *  work-matched 4-min rest. */
    const val REST_PE_BETWEEN_LAPS = 30
    const val REST_PE_BETWEEN_SETS = 240

    /** Pyramid rests scale with intensity: base flows (90 s), the
     *  mid tier breathes (2.5 min), apex ± 1 V nearly fully recovers. */
    const val REST_PYRAMID_LOW_TIER = 90
    const val REST_PYRAMID_MID_TIER = 150
    const val REST_PYRAMID_HIGH_TIER = 240

    // ── Duration → count scaling ─────────────────────────────────


    /** Volume cycle: ~1 min on the wall + 90 s rest = 2.5 min/problem
     *  → ≈20 problems/hour of block time (Lattice pacing). */
    const val VOLUME_CYCLE_SECONDS = 150

    /** Limit block per problem: 5 attempts ≈ 1 min each + 4×4 min rests. */
    const val LIMIT_SLOT_MINUTES = 21

    /** Project block: 4 burns + 3×5 min rests + 6 min switch-over. */
    const val PROJECT_SLOT_MINUTES = 25

    /** 4x4 set: ~5 min of laps + 4 min set rest. */
    const val PE_SET_MINUTES = 10

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

    /** Warm-up ladder: 2 problems per easy tier (1 within taper
     *  distance), at most this many total. */
    const val WARMUP_PROBLEMS_PER_TIER = 2
    const val WARMUP_MAX_PROBLEMS = 8

    /** Default profile when the logbook is empty (≈ V5, mirrors
     *  IntensityZoneEngine's fallback) — the UI must flag this. */
    const val DEFAULT_MAX_DIFFICULTY = 20.0
}
