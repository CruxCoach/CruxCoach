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

    /**
     * The scale's own unit: one Font grade, one difficulty point.
     *
     * 10 = 4a, 22 = 7a, 34 = 9a. Everything in this file and in the planner is
     * counted in these, and only these.
     *
     * The V-scale is a naming convention laid over the same numbers, and an
     * uneven one — V0 covers three points, V5 two, V9 and up a single each.
     * It used to appear here as a second unit (`DIFF_PER_V_GRADE = 2`), which
     * is right around V5 and moves twice as far as it claims at the top: the
     * band documented as "max − 3 V … max − 2 V" landed on V5–V7 for a V10
     * climber. The offsets below were re-derived directly in points instead,
     * so nothing converts and nothing can drift. They are calibrated for the
     * V4–V8 range the source protocols were written around; [VGradeOffsets]
     * exists only to name a difficulty for the user.
     */
    const val DIFF_PER_FONT_STEP = 1.0

    /** Absolute scale bounds (V0 … V17). */
    const val MIN_DIFFICULTY = 10.0
    const val MAX_DIFFICULTY = 34.0

    /** Fallback flash offset when the logbook has no flash data. */
    const val FLASH_FALLBACK_STEPS = 3 * DIFF_PER_FONT_STEP

    /**
     * Hard safety ceiling, in Font steps — never plan above max + 2 steps.
     *
     * Deliberately NOT expressed in V-grades. At the top of the scale one
     * V-grade is a single Font step, so a V-grade ceiling would sink below
     * [PROJECT_BAND_TOP_ABOVE_MAX] and clamp the projecting band the planner
     * had just built. Two steps is the old effective value and the highest any
     * mode plans; the filler may not widen past it.
     */
    const val CEILING_ABOVE_MAX_STEPS = 2 * DIFF_PER_FONT_STEP

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
    /**
     * Anchored on the repeatable FLASH, not on the working max.
     *
     * A 4x4 only works if the fourth lap still tops out, and what a climber
     * can do first try when fresh is the honest starting point for that —
     * pumped, they need a little under it. Deriving from the max instead
     * assumed a fixed max-to-flash gap that no climber actually has: a
     * project-focused climber with a distant flash got a band far too hard,
     * a mileage climber one too easy.
     */
    const val PE_BAND_LOW_BELOW_FLASH = 2 * DIFF_PER_FONT_STEP
    const val PE_BAND_HIGH_BELOW_FLASH = 1 * DIFF_PER_FONT_STEP

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

    /**
     * Warm-up ladder, defined entirely against the FIRST WORKING GRADE.
     *
     * Its job is to bridge the gap to the work set, so the max is none of its
     * business: it used to start "5 V below max" and separately refuse to come
     * within "3 V of the first work grade", two rules that had to be reconciled
     * with a min(). One reference point removes the reconciliation, and each
     * session type warms up where its own work begins — volume lower, hard
     * bouldering higher — with no special case.
     */
    // Six, not seven: with a two-step ladder the start has to share parity
    // with the end, or the top tier lands three below the work grade instead
    // of two and the ladder stops short of what it promises.
    const val WARMUP_START_BELOW_FIRST_WORK = 6 * DIFF_PER_FONT_STEP
    const val WARMUP_END_BELOW_FIRST_WORK = 2 * DIFF_PER_FONT_STEP
    const val WARMUP_STEP = 2 * DIFF_PER_FONT_STEP

    /** Within this of the work grade, one problem per tier instead of two. */
    const val WARMUP_TAPER_DISTANCE = 3 * DIFF_PER_FONT_STEP

    /** Per-tier tolerance when matching climbs to a planned grade (± half
     *  a V-grade keeps "a V5 slot" honest while accepting 6b vs 6b+). */
    const val SLOT_TOLERANCE = 1.0

    /** Tighter, for tiers that are only one step apart — see
     *  PlaylistPlanner.pyramidSlot. */
    const val PYRAMID_SLOT_TOLERANCE = 0.5

    /** END_OF_SESSION fatigue shift: everything two Font steps easier. */
    const val END_OF_SESSION_SHIFT = 2 * DIFF_PER_FONT_STEP

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

    /** From this many problems on, the block is long enough to want it. */
    const val VOLUME_MID_BREAK_MIN_PROBLEMS = 20

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

    /** What the mid-block break costs beyond the short rest it replaces.
     *  The count used to be divided out of the full duration before this was
     *  known, so an hour-long session planned 24 problems and then spent ten
     *  minutes it had already given away. */
    const val VOLUME_MID_BREAK_EXTRA_SECONDS =
        REST_VOLUME_MID_BREAK - REST_VOLUME_BETWEEN_PROBLEMS

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
    /** One hard problem with full rests is a legitimate short session; two
     *  was a floor that made the shortest slider setting overshoot threefold. */
    val LIMIT_COUNT = 1..6

    /** Hörst's window is 3-5 tries. Below three it stops being the protocol,
     *  so the problem count gives way before this does. */
    const val MIN_ATTEMPTS_PER_LIMIT_PROBLEM = 3

    /** Time on the wall for one attempt, seconds — the same figure
     *  estimatedMinutes uses, so the planner and the preview agree. */
    const val CLIMB_SECONDS = 60
    val PROJECT_COUNT = 1..3
    /**
     * Sets in an interval block, and problems in each.
     *
     * "4x4" is one point in this space, not the shape of the space: it is
     * four sets of four, and that is where the defaults sit. Offering only
     * the set count and nailing the four problems down made one half of the
     * protocol a control and the other half a law, which is not how anyone
     * trains — six sets of three and three sets of six are both real
     * sessions.
     */
    val PE_SETS = 1..8
    val PE_PROBLEMS_PER_SET_RANGE = 2..6

    /**
     * Tiers in a pyramid: 3 (6 climbs) through 6 (21).
     *
     * Two would not be a pyramid and seven puts the base six Font steps under
     * the apex, which is a different session with the same name. How many
     * actually fit also depends on the climber — see PlaylistPlanner, which
     * caps them so the base cannot be pushed under the bottom of the scale
     * and collapse two tiers onto the same grade.
     */
    val PYRAMID_TIERS = 3..6

    // ── How far the filler may stray from a band ─────────────────

    /** Default drift for types that want mileage more than a precise grade. */
    const val WIDEN_MAX_DEFAULT = 4 * DIFF_PER_FONT_STEP

    /** Interval work lives or dies on the window: two Font steps at most, or
     *  the laps stop being the thing that was prescribed. */
    const val WIDEN_MAX_INTERVAL = 2 * DIFF_PER_FONT_STEP

    /** Max-effort work is defined by being at the limit; drifting down turns
     *  it into ordinary hard bouldering without saying so. */
    const val WIDEN_MAX_MAX_EFFORT = 1 * DIFF_PER_FONT_STEP

    fun maxWideningFor(type: GeneratorType): Double = when (type) {
        GeneratorType.POWER_ENDURANCE, GeneratorType.PYRAMID -> WIDEN_MAX_INTERVAL
        GeneratorType.LIMIT, GeneratorType.PROJECTING -> WIDEN_MAX_MAX_EFFORT
        GeneratorType.VOLUME -> WIDEN_MAX_DEFAULT
        // None at all: a range the climber typed is an instruction, not a hint.
        GeneratorType.MANUAL -> 0.0
    }

    // ── Manual mode ──────────────────────────────────────────────

    val MANUAL_COUNT = 1..40
    val MANUAL_REPEATS = 1..8
    val MANUAL_REST_SECONDS = 0..600
    const val MANUAL_REST_STEP = 15
    const val MANUAL_DEFAULT_REST = 120
    const val MANUAL_DEFAULT_REPEAT_REST = 60

    /** Half the band a fresh manual session starts on, either side of the
     *  climber's work anchor — a sane place to begin adjusting from. */
    const val MANUAL_SEED_HALF_BAND = 2 * DIFF_PER_FONT_STEP
    /** The classic 4x4 default. */
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
