package com.cruxcoach.domain.playlist

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Which structural section of the session a slot belongs to. */
enum class PlanSection { WARM_UP, MAIN, PEAK, DESCENT }

/** Abstract slot plan — grades first, concrete climbs later (the
 *  BoardSesh-proven two-phase split: plan the curve, then fill it). */
sealed interface PlanSlot {
    val section: PlanSection

    /**
     * One climb at a target difficulty band.
     *
     * @param repeatKey slots sharing a non-null key are filled with the
     *  SAME climb (power-endurance laps repeat their problems each set).
     */
    data class ClimbSlot(
        val minDifficulty: Double,
        val maxDifficulty: Double,
        override val section: PlanSection,
        val repeatKey: Int? = null,
    ) : PlanSlot

    /** An explicit rest block — lands in the playlist as a rest entry. */
    data class RestSlot(
        val seconds: Int,
        override val section: PlanSection,
    ) : PlanSlot
}

/**
 * The planned session skeleton.
 *
 * @param downgradedFromType non-null when END_OF_SESSION forced a
 *  max-effort type down to VOLUME — the UI should say so.
 * @param usedDefaultProfile true when the logbook was too thin and the
 *  ~V5 default drove the grades — the UI must flag this.
 */
data class PlaylistPlan(
    val slots: List<PlanSlot>,
    val effectiveType: GeneratorType,
    val downgradedFromType: GeneratorType? = null,
    val usedDefaultProfile: Boolean = false,
    /**
     * Nothing in this session may be harder than this, whatever the filler has
     * to do to find climbs.
     *
     * The planner clamps every band to max + 1 V, but the filler widens a slot
     * that finds no candidates — symmetrically, and by up to four points. That
     * let the safety ceiling be walked straight past after the fact: a limit
     * slot at V10–V11 could be served a V15. The ceiling travels with the plan
     * so the widening has something to stop at.
     */
    val hardCeiling: Double = TrainingRanges.MAX_DIFFICULTY,
    /**
     * How far the filler may stray from a slot's band, in points.
     *
     * A global ceiling is not enough on its own: it stops a session going
     * *above* the climber, not a 4x4 band drifting from V7–V8 down to V4
     * because the catalogue was thin. Below the intended band the stimulus is
     * simply gone, and nothing was there to say so. Types whose whole point is
     * a narrow intensity window allow less drift than types that just want
     * mileage.
     */
    val maxWidening: Double = TrainingRanges.WIDEN_MAX_DEFAULT,
    /**
     * The bottom of the training range the climber chose, or null when there
     * is none. Working slots may not widen below it; the warm-up ladder lives
     * underneath it by design and is exempt — a grade range is a statement
     * about the work, and applying it to the ladder served "warm-up" climbs at
     * working grade.
     */
    val workFloor: Double? = null,
)

/**
 * Pure planner: (params, profile) → ordered slot plan with explicit rest
 * blocks. No I/O, no randomness — fully deterministic and unit-testable.
 * Grade math happens in Aurora difficulty points (one point = one Font
 * grade); recommended bands are clamped to [4a, peak + 2 points]. A slot's
 * bounds are GRADES: the filler matches a climb by the grade it is displayed
 * as, so "6a…6b" means every climb the app calls 6a, 6a+ or 6b.
 */
object PlaylistPlanner {

    fun plan(params: PlaylistGeneratorParams, profile: LogbookProfile): PlaylistPlan {
        val duration = params.durationMinutes.coerceIn(
            TrainingRanges.MIN_DURATION_MINUTES,
            TrainingRanges.MAX_DURATION_MINUTES,
        )

        // END_OF_SESSION forbids max-effort work (fatigued limit attempts
        // are an injury risk): LIMIT/PROJECTING downgrade to VOLUME and
        // every band shifts one V-grade easier.
        val fatigued = params.position == SessionPosition.END_OF_SESSION
        val downgraded = fatigued &&
            (params.type == GeneratorType.LIMIT || params.type == GeneratorType.PROJECTING)
        val effectiveType = if (downgraded) GeneratorType.VOLUME else params.type
        val shift = if (fatigued) TrainingRanges.END_OF_SESSION_SHIFT else 0.0

        // Two anchors: the PEAK (hardest-ever send — hard ceiling only)
        // and the outlier-robust work ANCHOR (repeatable/second-best max).
        // One lucky 7b against a 7a/7a+ background must plan a session
        // that CONSOLIDATES the 7b, not one that assumes 7b+ is in reach.
        val peak = profile.effectiveMax - shift
        val anchor = min(profile.effectiveRepeatableMax, profile.effectiveMax) - shift
        // Volume anchor: outlier-robust flash, never above the work anchor
        // (a flash "above" what you can repeatedly send is itself a fluke).
        val flashDiff = min(profile.effectiveRepeatableFlash - shift, anchor)

        // Warm-up ladder only when starting cold; its ACTUAL minutes come
        // off the main-set budget (a 2-problem easy-session warm-up must
        // not eat a flat 18-minute block).
        // Circular by nature: the pyramid's base tier depends on the main-set
        // budget, the budget depends on how long the ladder is, and the ladder
        // length depends on the base. Settled in two passes — the first buys a
        // ladder cost, the second the grade that cost actually implies.
        // The size the climber chose, clamped to what the type can carry.
        // Null means a playlist saved before the slider selected structure
        // directly, so the old division from the duration still applies.
        val size = params.structureSize?.coerceIn(effectiveType.structureRange())

        fun ladderFor(mainMinutes: Int) =
            if (params.position == SessionPosition.START_COLD) {
                buildWarmUpLadder(
                    // Never warm up past the work anchor: a projecting range
                    // starts above it, and a ladder hung off that would put
                    // limit-grade problems into the warm-up.
                    min(
                        params.targetMinDifficulty ?: firstWorkGrade(
                            effectiveType, anchor, flashDiff, mainMinutes, size, params,
                        ),
                        anchor,
                    ),
                )
            } else emptyList()

        // With an explicit size the pyramid's tiers no longer depend on the
        // budget, so the second pass has nothing left to discover.
        val warmUp = if (size != null) {
            ladderFor(duration)
        } else {
            ladderFor(max(duration - warmUpMinutes(ladderFor(duration)), 10))
        }
        val mainMinutes = max(duration - warmUpMinutes(warmUp), 10)

        val unconstrainedMain = when (effectiveType) {
            GeneratorType.VOLUME -> planVolume(mainMinutes, flashDiff, peak, size)
            GeneratorType.LIMIT -> planLimit(
                mainMinutes, anchor, peak, size,
                params.targetMinDifficulty, params.targetMaxDifficulty,
            )
            GeneratorType.PROJECTING -> planProjecting(mainMinutes, anchor, peak, size)
            GeneratorType.POWER_ENDURANCE -> planPowerEndurance(
                mainMinutes, flashDiff, size,
                params.problemsPerSet?.coerceIn(TrainingRanges.PE_PROBLEMS_PER_SET_RANGE)
                    ?: TrainingRanges.PE_PROBLEMS_PER_SET,
            )
            GeneratorType.PYRAMID -> planPyramid(
                minutes = mainMinutes,
                anchor = anchor,
                shape = params.pyramidShape,
                size = size,
                targetMin = params.targetMinDifficulty,
                targetMax = params.targetMaxDifficulty,
                climbsPerTier = params.pyramidClimbsPerTier,
            )
            GeneratorType.MANUAL -> planManual(params, anchor, size)
        }
        val main = constrainToTargetRange(
            unconstrainedMain,
            params.targetMinDifficulty,
            params.targetMaxDifficulty,
        )

        return PlaylistPlan(
            slots = (warmUp + main).normalizeRests(),
            effectiveType = effectiveType,
            downgradedFromType = if (downgraded) params.type else null,
            usedDefaultProfile = !profile.isPersonalized,
            // A range the climber set is theirs to set, exactly as in manual
            // mode: capping it at the profile ceiling as well left every slot
            // of a deliberately harder range without a single candidate. The
            // recommended range never exceeds that ceiling in the first place.
            hardCeiling = params.targetMaxDifficulty
                ?: (profile.effectiveMax + TrainingRanges.CEILING_ABOVE_MAX_STEPS),
            maxWidening = TrainingRanges.maxWideningFor(effectiveType),
            workFloor = params.targetMinDifficulty,
        )
    }

    // ── Sections ────────────────────────────────────────────────

    /**
     * Progressive ladder from six to two points below the first working
     * grade, in two-point tiers (Hörst: "boulder up" to near working
     * intensity). Easy tiers carry 2 problems; tiers within taper distance of
     * the working grade carry 1 (activation, not fatigue). Short rests
     * between problems, then the long transition rest.
     */
    private fun buildWarmUpLadder(firstWorkDiff: Double): List<PlanSlot> {
        val climbs = mutableListOf<PlanSlot.ClimbSlot>()
        var tier = clampLow(firstWorkDiff - TrainingRanges.WARMUP_START_BELOW_FIRST_WORK)
        val ceiling = firstWorkDiff - TrainingRanges.WARMUP_END_BELOW_FIRST_WORK
        while (tier <= ceiling && climbs.size < TrainingRanges.WARMUP_MAX_PROBLEMS) {
            val nearWork = firstWorkDiff - tier < TrainingRanges.WARMUP_TAPER_DISTANCE
            val perTier = if (nearWork) 1 else TrainingRanges.WARMUP_PROBLEMS_PER_TIER
            repeat(perTier) {
                if (climbs.size < TrainingRanges.WARMUP_MAX_PROBLEMS) {
                    climbs.add(climbSlot(tier, PlanSection.WARM_UP))
                }
            }
            tier += TrainingRanges.WARMUP_STEP
        }
        if (climbs.isEmpty()) {
            // Even a V0 climber warms up on something: one tier at the floor.
            repeat(TrainingRanges.WARMUP_PROBLEMS_PER_TIER) {
                climbs.add(climbSlot(TrainingRanges.MIN_DIFFICULTY, PlanSection.WARM_UP))
            }
        }
        val slots = mutableListOf<PlanSlot>()
        climbs.forEachIndexed { i, climb ->
            if (i > 0) {
                slots.add(
                    PlanSlot.RestSlot(TrainingRanges.REST_WARMUP_BETWEEN_PROBLEMS, PlanSection.WARM_UP)
                )
            }
            slots.add(climb)
        }
        slots.add(PlanSlot.RestSlot(TrainingRanges.REST_AFTER_WARMUP, PlanSection.WARM_UP))
        return slots
    }

    /** Actual wall-clock cost of the warm-up (1.5 min per problem, the
     *  same figure estimatedMinutes uses, plus its explicit rests). */
    private fun warmUpMinutes(warmUp: List<PlanSlot>): Int {
        if (warmUp.isEmpty()) return 0
        val restSeconds = warmUp.filterIsInstance<PlanSlot.RestSlot>().sumOf { it.seconds }
        val climbCount = warmUp.count { it is PlanSlot.ClimbSlot }
        return ceil(climbCount * 1.5 + restSeconds / 60.0).toInt()
    }

    private fun planVolume(
        minutes: Int,
        flashDiff: Double,
        maxDiff: Double,
        size: Int?,
    ): List<PlanSlot> {
        // Pay for the mid-block break before dividing the rest into problems.
        val breakCost =
            if (minutes >= 60) TrainingRanges.VOLUME_MID_BREAK_EXTRA_SECONDS else 0
        val count = size
            ?: ((minutes * 60 - breakCost) / TrainingRanges.VOLUME_CYCLE_SECONDS)
                .coerceIn(TrainingRanges.VOLUME_COUNT)
        // Whether the block is long enough to want a break is a question about
        // the block, not about a duration the climber may no longer be setting:
        // keyed on minutes, eight warmed-up problems got the ten-minute break
        // and thirty cold ones could miss it.
        val high = clamp(flashDiff, maxDiff)
        val low = clampLow(high - TrainingRanges.VOLUME_BAND_BELOW_FLASH)
        val mid = (low + high) / 2
        val slots = mutableListOf<PlanSlot>()
        for (i in 0 until count) {
            if (i > 0) {
                // Quality rest between every problem — volume without rests
                // degrades into accidental power-endurance and sloppy movement.
                // The mid-block break replaces (not stacks on) the short rest.
                val midBreak = count >= TrainingRanges.VOLUME_MID_BREAK_MIN_PROBLEMS &&
                    i == count / 2
                slots.add(
                    PlanSlot.RestSlot(
                        if (midBreak) TrainingRanges.REST_VOLUME_MID_BREAK
                        else TrainingRanges.REST_VOLUME_BETWEEN_PROBLEMS,
                        PlanSection.MAIN,
                    )
                )
            }
            // Progressive loading: the session ramps through the band —
            // first third eases in (lower half), last third finishes at
            // flash level (upper half), middle roams the full band.
            val (slotLow, slotHigh) = when {
                i < count / 3 -> low to mid
                i >= count - count / 3 -> mid to high
                else -> low to high
            }
            slots.add(PlanSlot.ClimbSlot(slotLow, slotHigh, PlanSection.MAIN))
        }
        return slots
    }

    /** Limit bouldering with EXPLICIT attempt structure: each problem
     *  appears [TrainingRanges.ATTEMPTS_PER_LIMIT_PROBLEM] times (same
     *  climb via repeatKey) with between-attempt rests, long rests between
     *  problems — the rests ARE the training, so they live in the list. */
    private fun planLimit(
        minutes: Int,
        anchor: Double,
        peak: Double,
        size: Int?,
        targetMin: Double? = null,
        targetMax: Double? = null,
    ): List<PlanSlot> {
        // A 21-minute block per problem with a floor of two problems meant the
        // shortest session the slider offers produced three times the time
        // asked for. One hard problem with full rests IS a session; below that
        // there is nothing honest to plan, so the attempt count gives way
        // first — Hörst's 3-5, not a fixed 5.
        val budget = minutes.coerceAtLeast(TrainingRanges.LIMIT_SLOT_MINUTES / 2)
        val count = size
            ?: (budget / TrainingRanges.LIMIT_SLOT_MINUTES).coerceIn(TrainingRanges.LIMIT_COUNT)
        var attempts = TrainingRanges.ATTEMPTS_PER_LIMIT_PROBLEM
        while (count == TrainingRanges.LIMIT_COUNT.first &&
            attempts > TrainingRanges.MIN_ATTEMPTS_PER_LIMIT_PROBLEM &&
            limitBlockMinutes(count, attempts) > budget
        ) {
            attempts--
        }
        // From the repeatable level up to the hardest send — and always at
        // least one grade past the anchor. Stopping at anchor + 1 told a
        // climber with a 7a in the book that hard bouldering ends at 6c: the
        // range has to reach their max to be believed, and to be hard.
        // A range the climber chose is ramped over in the same way, rather
        // than planned off the profile and then clipped flat against it.
        val low = targetMin ?: anchor
        val high = (targetMax ?: clamp(max(anchor + TrainingRanges.LIMIT_BAND_ABOVE_MAX, peak), peak))
            .coerceAtLeast(low)
        // A wide range is a ramp, not a lottery: the first problem sits at the
        // repeatable level, the last at the top, each in a two-grade window.
        val span = high - low
        return workBlocks(
            problems = count,
            attemptsPerProblem = attempts,
            attemptRest = TrainingRanges.REST_LIMIT_BETWEEN_ATTEMPTS,
            problemRest = TrainingRanges.REST_LIMIT_BETWEEN_PROBLEMS,
            low = low, high = high,
            bandFor = { p ->
                if (span <= TrainingRanges.LIMIT_BAND_ABOVE_MAX || count == 1) low to high
                else {
                    val start = low + floor(
                        (span - TrainingRanges.LIMIT_BAND_ABOVE_MAX) * p / (count - 1) + 0.5,
                    )
                    start to start + TrainingRanges.LIMIT_BAND_ABOVE_MAX
                }
            },
        )
    }

    /** Projecting: burns on each project, explicit like limit attempts.
     *  Band sits ABOVE the limit band (max+1…max+2 Font steps) — projects
     *  are multi-session difficulty, limit problems are session-sendable. */
    private fun planProjecting(
        minutes: Int,
        anchor: Double,
        peak: Double,
        size: Int?,
    ): List<PlanSlot> {
        val count = size
            ?: (minutes / TrainingRanges.PROJECT_SLOT_MINUTES)
                .coerceIn(TrainingRanges.PROJECT_COUNT)
        // One step above the limit band, off the same robust anchor — for
        // the outlier climber (one 7b, background 7a+) the project IS the
        // 7b…7b+ range, not 7c. The peak-based clamp() ceiling still holds.
        // Starts where hard bouldering ends: at the hardest send, or a grade
        // past the anchor if that is higher. A project below one's own max is
        // not a project.
        val low = clamp(max(anchor + TrainingRanges.PROJECT_BAND_LOW_ABOVE_MAX, peak), peak)
        val high = clamp(
            low + (TrainingRanges.PROJECT_BAND_TOP_ABOVE_MAX - TrainingRanges.PROJECT_BAND_LOW_ABOVE_MAX),
            peak,
        )
        return workBlocks(
            problems = count,
            attemptsPerProblem = TrainingRanges.BURNS_PER_PROJECT,
            attemptRest = TrainingRanges.REST_PROJECT_BETWEEN_BURNS,
            problemRest = TrainingRanges.REST_PROJECT_BETWEEN_PROJECTS,
            low = low, high = high,
        )
    }

    /** Rough wall-clock for a limit block, in minutes — rests dominate. */
    private fun limitBlockMinutes(problems: Int, attempts: Int): Int {
        val perProblem = attempts * TrainingRanges.CLIMB_SECONDS +
            (attempts - 1) * TrainingRanges.REST_LIMIT_BETWEEN_ATTEMPTS
        val between = (problems - 1) * TrainingRanges.REST_LIMIT_BETWEEN_PROBLEMS
        return (problems * perProblem + between) / 60
    }

    /**
     * Manual: exactly what was asked for.
     *
     * No profile, no protocol, no clamp to the safety ceiling — the climber
     * named a grade range, and overriding it would make the controls a
     * suggestion box. The scale's own bounds still apply, and the filler is
     * told not to widen, so a range the board cannot fill comes back short
     * and visibly rather than silently substituted.
     */
    private fun planManual(
        params: PlaylistGeneratorParams,
        anchor: Double,
        size: Int?,
    ): List<PlanSlot> {
        val seedLow = anchor - TrainingRanges.MANUAL_SEED_HALF_BAND
        val seedHigh = anchor + TrainingRanges.MANUAL_SEED_HALF_BAND
        val low = (params.manualMinDifficulty.takeIf { it > 0.0 } ?: seedLow)
            .coerceIn(TrainingRanges.MIN_DIFFICULTY, TrainingRanges.MAX_DIFFICULTY)
        val high = (params.manualMaxDifficulty.takeIf { it > 0.0 } ?: seedHigh)
            .coerceIn(low, TrainingRanges.MAX_DIFFICULTY)
        val count = (size ?: TrainingRanges.MANUAL_COUNT.first)
            .coerceIn(TrainingRanges.MANUAL_COUNT)
        val repeats = params.manualRepeats.coerceIn(TrainingRanges.MANUAL_REPEATS)

        val slots = mutableListOf<PlanSlot>()
        for (p in 0 until count) {
            if (p > 0 && params.manualRestSeconds > 0) {
                slots.add(PlanSlot.RestSlot(params.manualRestSeconds, PlanSection.MAIN))
            }
            for (attempt in 0 until repeats) {
                if (attempt > 0 && params.manualRepeatRestSeconds > 0) {
                    slots.add(PlanSlot.RestSlot(params.manualRepeatRestSeconds, PlanSection.MAIN))
                }
                slots.add(
                    PlanSlot.ClimbSlot(
                        minDifficulty = low,
                        maxDifficulty = high,
                        section = PlanSection.MAIN,
                        // Repeats mean the same problem, as everywhere else.
                        repeatKey = p.takeIf { repeats > 1 },
                    )
                )
            }
        }
        return slots
    }

    private fun workBlocks(
        problems: Int,
        attemptsPerProblem: Int,
        attemptRest: Int,
        problemRest: Int,
        low: Double,
        high: Double,
        bandFor: (Int) -> Pair<Double, Double> = { low to high },
    ): List<PlanSlot> {
        val slots = mutableListOf<PlanSlot>()
        for (p in 0 until problems) {
            if (p > 0) slots.add(PlanSlot.RestSlot(problemRest, PlanSection.PEAK))
            val (problemLow, problemHigh) = bandFor(p)
            for (attempt in 0 until attemptsPerProblem) {
                if (attempt > 0) slots.add(PlanSlot.RestSlot(attemptRest, PlanSection.PEAK))
                slots.add(PlanSlot.ClimbSlot(problemLow, problemHigh, PlanSection.PEAK, repeatKey = p))
            }
        }
        return slots
    }

    /** Sets of 4 problems (same problems every set, via repeatKey), short
     *  lap rests inside a set, long rests between sets. */
    private fun planPowerEndurance(
        minutes: Int,
        flashAnchor: Double,
        size: Int?,
        problemsPerSet: Int,
    ): List<PlanSlot> {
        val sets = size
            ?: (minutes / TrainingRanges.PE_SET_MINUTES).coerceIn(TrainingRanges.PE_SETS)
        val low = clampLow(flashAnchor - TrainingRanges.PE_BAND_LOW_BELOW_FLASH)
        val high = clampLow(flashAnchor - TrainingRanges.PE_BAND_HIGH_BELOW_FLASH)
        val slots = mutableListOf<PlanSlot>()
        for (set in 0 until sets) {
            for (problem in 0 until problemsPerSet) {
                if (problem > 0) {
                    slots.add(PlanSlot.RestSlot(TrainingRanges.REST_PE_BETWEEN_LAPS, PlanSection.MAIN))
                }
                slots.add(PlanSlot.ClimbSlot(low, high, PlanSection.MAIN, repeatKey = problem))
            }
            if (set < sets - 1) {
                slots.add(PlanSlot.RestSlot(TrainingRanges.REST_PE_BETWEEN_SETS, PlanSection.MAIN))
            }
        }
        return slots
    }

    /** Classic ascending pyramid (…4×, 3×, 2×, 1× apex) in one-grade steps;
     *  the shape decides whether the mirrored descent follows. */
    private fun planPyramid(
        minutes: Int,
        anchor: Double,
        shape: PyramidShape,
        size: Int? = null,
        targetMin: Double? = null,
        targetMax: Double? = null,
        climbsPerTier: Int? = null,
    ): List<PlanSlot> {
        // Apex one grade below the work anchor: every tier should top, but
        // the top one should take a few goes — see PYRAMID_APEX_BELOW_MAX.
        val apex = clampLow(anchor - TrainingRanges.PYRAMID_APEX_BELOW_MAX)
        // However many were asked for, but never more than there is room for:
        // below the bottom of the scale the tiers clamp onto each other and
        // the pyramid comes out with two identical steps.
        val requestedTiers = size ?: pyramidTiers(minutes)
        val tiers = if (targetMin != null && targetMax != null) {
            requestedTiers
        } else {
            requestedTiers.coerceAtMost(maxPyramidTiers(apex))
        }
        val base = targetMin ?: pyramidBaseFor(tiers, anchor)
        val top = targetMax ?: apex
        // The climber's choice, not a side effect of the duration. Anything
        // under 90 minutes used to be a half pyramid called a whole one.
        val withDescent = shape == PyramidShape.UP_AND_DOWN

        data class Tier(val diff: Double, val count: Int, val section: PlanSection)

        val ascent = (0 until tiers).map { i ->
            Tier(
                // Whole grades: a tier "between 6a+ and 6b" is a grade no climb
                // is displayed as, and would only ever be filled by widening.
                diff = if (tiers == 1) top
                else floor(base + (top - base) * i / (tiers - 1) + 0.5),
                count = climbsPerTier?.coerceIn(TrainingRanges.PYRAMID_CLIMBS_PER_TIER)
                    ?: (tiers - i),
                section = if (i == tiers - 1) PlanSection.PEAK else PlanSection.MAIN,
            )
        }
        val descent = if (withDescent) {
            // asReversed(), not reversed(): under a JDK-21 toolchain the
            // latter can resolve to java.util.List's SequencedCollection
            // member — NoSuchMethodError on Android API 26-34.
            ascent.dropLast(1).asReversed().map { it.copy(section = PlanSection.DESCENT) }
        } else emptyList()

        val slots = mutableListOf<PlanSlot>()
        (ascent + descent).forEach { tier ->
            // Rests scale with proximity to the apex: the base flows, the
            // mid tier breathes, the top Font step gets near-full recovery.
            val stepsBelowApex = (top - tier.diff) / TrainingRanges.PYRAMID_STEP
            val rest = when {
                stepsBelowApex <= 1.0 -> TrainingRanges.REST_PYRAMID_HIGH_TIER
                stepsBelowApex <= 2.0 -> TrainingRanges.REST_PYRAMID_MID_TIER
                else -> TrainingRanges.REST_PYRAMID_LOW_TIER
            }
            repeat(tier.count) {
                if (slots.isNotEmpty()) slots.add(PlanSlot.RestSlot(rest, tier.section))
                slots.add(
                    if (targetMin != null && targetMax != null) {
                        // The selected endpoints are tier centres, not the
                        // outer edges of fuzzy bands. Keeping them exact makes
                        // the visible steps genuinely equidistant; the filler
                        // may still widen within the same hard range when a
                        // board has no climb at the exact grade.
                        PlanSlot.ClimbSlot(tier.diff, tier.diff, tier.section)
                    } else {
                        pyramidSlot(tier.diff, tier.section)
                    }
                )
            }
        }
        return slots
    }

    /** Apply the user's grade range as a hard plan constraint. Candidate
     * loading applies the same bounds, so the filler cannot widen around it. */
    private fun constrainToTargetRange(
        slots: List<PlanSlot>,
        requestedLow: Double?,
        requestedHigh: Double?,
    ): List<PlanSlot> {
        if (requestedLow == null || requestedHigh == null) return slots
        val low = requestedLow.coerceIn(TrainingRanges.MIN_DIFFICULTY, TrainingRanges.MAX_DIFFICULTY)
        val high = requestedHigh.coerceIn(low, TrainingRanges.MAX_DIFFICULTY)
        return slots.map { slot ->
            if (slot !is PlanSlot.ClimbSlot) return@map slot
            val center = ((slot.minDifficulty + slot.maxDifficulty) / 2.0).coerceIn(low, high)
            val minDifficulty = max(slot.minDifficulty, low)
            val maxDifficulty = min(slot.maxDifficulty, high)
            if (minDifficulty <= maxDifficulty) {
                slot.copy(minDifficulty = minDifficulty, maxDifficulty = maxDifficulty)
            } else {
                slot.copy(minDifficulty = center, maxDifficulty = center)
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────

    /** Short sessions climb three tiers, longer ones four. Only used for
     *  playlists saved before the tier count became a control. */
    private fun pyramidTiers(minutes: Int): Int = if (minutes < 45) 3 else 4

    /** How many distinct tiers fit between the apex and the floor. */
    private fun maxPyramidTiers(apex: Double): Int =
        (((apex - TrainingRanges.MIN_DIFFICULTY) / TrainingRanges.PYRAMID_STEP).toInt() + 1)
            .coerceIn(TrainingRanges.PYRAMID_TIERS)

    /** The lowest tier of the pyramid — where the first working problem sits. */
    private fun pyramidBaseFor(tiers: Int, anchor: Double): Double {
        val apex = clampLow(anchor - TrainingRanges.PYRAMID_APEX_BELOW_MAX)
        return clampLow(apex - (tiers - 1) * TrainingRanges.PYRAMID_STEP)
    }

    /**
     * The grade the warm-up ladder has to reach up to.
     *
     * Every branch derives this from the same constants its plan* function
     * uses — except the pyramid, which used to rebuild the shape from a
     * constant `planPyramid` does not consult. That copy assumed four tiers,
     * so any pyramid short enough to build three ended the ladder 1.5 V below
     * the first working problem instead of 1 V: exactly the intensity jump
     * the ladder exists to remove.
     */
    private fun firstWorkGrade(
        type: GeneratorType,
        anchor: Double,
        flashDiff: Double,
        mainMinutes: Int,
        size: Int?,
        params: PlaylistGeneratorParams,
    ): Double =
        when (type) {
            GeneratorType.VOLUME -> clampLow(flashDiff - TrainingRanges.VOLUME_BAND_BELOW_FLASH)
            GeneratorType.POWER_ENDURANCE ->
                clampLow(flashDiff - TrainingRanges.PE_BAND_LOW_BELOW_FLASH)
            GeneratorType.PYRAMID -> pyramidBaseFor(
                size ?: pyramidTiers(mainMinutes), anchor,
            )
            GeneratorType.LIMIT, GeneratorType.PROJECTING -> anchor
            // The bottom of what the climber asked for — capped at the anchor
            // by the caller, like everything else.
            GeneratorType.MANUAL -> params.manualMinDifficulty.takeIf { it > 0.0 }
                ?: clampLow(anchor - TrainingRanges.MANUAL_SEED_HALF_BAND)
        }

    private fun climbSlot(
        center: Double,
        section: PlanSection,
        repeatKey: Int? = null,
        tolerance: Double = TrainingRanges.SLOT_TOLERANCE,
    ) = PlanSlot.ClimbSlot(
        minDifficulty = clampLow(center - tolerance),
        maxDifficulty = center + tolerance,
        section = section,
        repeatKey = repeatKey,
    )

    /**
     * Pyramid tiers sit one Font step apart, so the usual ±1 tolerance lets
     * neighbouring tiers draw the same difficulty — a pyramid that comes out
     * flat, or with its base harder than the step above it. Half a step keeps
     * the tiers distinguishable, which is the entire shape.
     */
    private fun pyramidSlot(center: Double, section: PlanSection) =
        climbSlot(center, section, tolerance = TrainingRanges.PYRAMID_SLOT_TOLERANCE)

    private fun clampLow(diff: Double): Double = max(diff, TrainingRanges.MIN_DIFFICULTY)

    /** Clamp into [4a, min(scale max, peak + 2 points)] — the safety ceiling
     *  no recommended band is planned past. */
    private fun clamp(diff: Double, userMax: Double): Double =
        min(
            min(diff, userMax + TrainingRanges.CEILING_ABOVE_MAX_STEPS),
            TrainingRanges.MAX_DIFFICULTY,
        ).let(::clampLow)

    /** Strip leading/trailing rests and collapse accidental doubles. */
    private fun List<PlanSlot>.normalizeRests(): List<PlanSlot> {
        val trimmed = dropWhile { it is PlanSlot.RestSlot }
            .dropLastWhile { it is PlanSlot.RestSlot }
        val out = mutableListOf<PlanSlot>()
        trimmed.forEach { slot ->
            if (slot is PlanSlot.RestSlot && out.lastOrNull() is PlanSlot.RestSlot) {
                val prev = out.removeAt(out.size - 1) as PlanSlot.RestSlot
                out.add(if (slot.seconds > prev.seconds) slot else prev)
            } else {
                out.add(slot)
            }
        }
        return out
    }
}

/**
 * Wall-clock estimate for a plan (UI preview): explicit rests + type-aware
 * time-on-the-wall per climb. Every mode now carries its rests as explicit
 * entries, so the per-climb figures are pure climb + reset time: volume/PE
 * problems are short flowing climbs, limit attempts and pyramid tries add
 * brushing/reading, project burns are the longest time-on-wall.
 */
fun PlaylistPlan.estimatedMinutes(): Int {
    val restSeconds = slots.filterIsInstance<PlanSlot.RestSlot>().sumOf { it.seconds }
    val climbMinutes = slots.filterIsInstance<PlanSlot.ClimbSlot>().sumOf { slot ->
        if (slot.section == PlanSection.WARM_UP) 1.5
        else when (effectiveType) {
            GeneratorType.VOLUME -> 1.0
            GeneratorType.POWER_ENDURANCE -> 1.0
            GeneratorType.LIMIT -> 1.0
            GeneratorType.PROJECTING -> 1.5
            GeneratorType.PYRAMID -> 1.5
            GeneratorType.MANUAL -> 1.0
        }
    }
    return ceil(climbMinutes + restSeconds / 60.0).toInt()
}
