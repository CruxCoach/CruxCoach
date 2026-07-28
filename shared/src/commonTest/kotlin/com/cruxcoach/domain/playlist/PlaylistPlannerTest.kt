package com.cruxcoach.domain.playlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaylistPlannerTest {

    // Profile: max V6 (diff 22), flash V4 (diff 18), plenty of data.
    private val profile = LogbookProfile(
        maxDifficulty = 22.0,
        flashDifficulty = 18.0,
        sampleSize = 40,
    )

    private fun params(
        type: GeneratorType,
        duration: Int = 60,
        position: SessionPosition = SessionPosition.WARMED_UP,
    ) = PlaylistGeneratorParams(
        type = type,
        durationMinutes = duration,
        position = position,
        angle = 40,
        boardBrand = "kilter",
        layoutId = 8,
    )

    private fun PlaylistPlan.climbs() = slots.filterIsInstance<PlanSlot.ClimbSlot>()
    private fun PlaylistPlan.rests() = slots.filterIsInstance<PlanSlot.RestSlot>()

    // ── Volume ──────────────────────────────────────────────────

    @Test
    fun `volume plans flash-band problems scaled by duration`() {
        val plan = PlaylistPlanner.plan(params(GeneratorType.VOLUME, duration = 60), profile)
        val climbs = plan.climbs()
        // Twenty, not twenty-four: the ten-minute mid-block break is now
        // subtracted before the hour is divided into 150-second cycles. It
        // used not to be, so the session planned four problems it had already
        // spent the time on — and Lattice's pacing is ~20 per hour anyway.
        assertEquals(20, climbs.size, "60 min minus the mid-break, over 150 s cycles")
        // Every problem is separated by an explicit quality rest.
        assertEquals(
            climbs.size - 1,
            plan.rests().count { r ->
                r.seconds == TrainingRanges.REST_VOLUME_BETWEEN_PROBLEMS ||
                    r.seconds == TrainingRanges.REST_VOLUME_MID_BREAK
            },
            "a rest between every pair of volume problems",
        )
        // Band: flash − 2V … flash → [14, 18].
        assertTrue(climbs.all { it.minDifficulty >= 14.0 - 0.001 && it.maxDifficulty <= 18.0 + 0.001 })
        // Long session gets the mid-block break.
        assertTrue(plan.rests().any { it.seconds == TrainingRanges.REST_VOLUME_MID_BREAK })
    }

    @Test
    fun `volume count clamps at 8 and 30`() {
        val short = PlaylistPlanner.plan(params(GeneratorType.VOLUME, duration = 20), profile)
        assertEquals(8, short.climbs().size)
        val long = PlaylistPlanner.plan(params(GeneratorType.VOLUME, duration = 150), profile)
        assertEquals(30, long.climbs().size)
    }

    // ── Limit / Projecting ──────────────────────────────────────

    @Test
    fun `limit plans explicit attempts per problem with long rests`() {
        val plan = PlaylistPlanner.plan(params(GeneratorType.LIMIT, duration = 63), profile)
        val climbs = plan.climbs()
        // 63 min / 21 min per problem = 3 problems × 5 explicit attempts.
        assertEquals(3, climbs.mapNotNull { it.repeatKey }.distinct().size, "3 distinct problems")
        assertEquals(15, climbs.size, "each problem carries its attempts as entries")
        assertTrue(climbs.all { it.minDifficulty == 22.0 && it.maxDifficulty == 23.0 },
            "limit band must be max … max + 1 Font step")
        // 4 attempt rests per problem + 2 between-problem rests.
        assertEquals(12, plan.rests().count { it.seconds == TrainingRanges.REST_LIMIT_BETWEEN_ATTEMPTS })
        assertEquals(2, plan.rests().count { it.seconds == TrainingRanges.REST_LIMIT_BETWEEN_PROBLEMS })
    }

    @Test
    fun `the chosen structure is what gets planned`() {
        // The slider sets sets/problems/tiers directly. It used to set minutes,
        // which the planner divided down — so most positions produced the same
        // session and the number on screen was not the one that mattered.
        (1..4).forEach { sets ->
            val plan = PlaylistPlanner.plan(
                params(GeneratorType.POWER_ENDURANCE).copy(structureSize = sets),
                profile,
            )
            assertEquals(
                sets * TrainingRanges.PE_PROBLEMS_PER_SET,
                plan.climbs().count { it.section != PlanSection.WARM_UP },
                "$sets sets",
            )
        }
    }

    @Test
    fun `every slider position builds a different session`() {
        // The failure this replaces: 40 through 150 minutes all gave four sets.
        val sizes = TrainingRanges.PE_SETS.map { sets ->
            PlaylistPlanner.plan(
                params(GeneratorType.POWER_ENDURANCE).copy(structureSize = sets),
                profile,
            ).climbs().size
        }
        assertEquals(sizes.distinct().size, sizes.size, "sessions were $sizes")
    }

    @Test
    fun `a playlist saved without a structure still plans from its duration`() {
        // Params persisted before the slider changed meaning carry no size.
        val plan = PlaylistPlanner.plan(params(GeneratorType.POWER_ENDURANCE, duration = 40), profile)
        assertTrue(plan.climbs().isNotEmpty())
    }

    @Test
    fun `the volume mid-block break is paid for out of the session`() {
        // The break replaces a 90-second rest but costs ten minutes; the count
        // was divided out of the full duration before that was known, so an
        // hour-long session planned problems it had already given the time to.
        val plan = PlaylistPlanner.plan(
            params(GeneratorType.VOLUME, duration = 60, position = SessionPosition.WARMED_UP),
            profile,
        )
        assertTrue(
            plan.estimatedMinutes() <= 66,
            "60-minute volume session estimated at ${plan.estimatedMinutes()} min",
        )
    }

    @Test
    fun `the 4x4 band hangs off the flash, not the working max`() {
        // A climber whose flash sits far below their max used to get a band
        // derived from the max via a fixed gap they do not have.
        val distantFlash = profile.copy(flashDifficulty = 14.0)
        val plan = PlaylistPlanner.plan(
            params(GeneratorType.POWER_ENDURANCE, position = SessionPosition.WARMED_UP),
            distantFlash,
        )
        val work = plan.climbs().filter { it.section != PlanSection.WARM_UP }
        // Stated as the rule, not as a number: the band hangs off whatever the
        // profile's robust flash resolves to, which is the point of the change.
        val flash = distantFlash.effectiveRepeatableFlash
        assertEquals(flash - TrainingRanges.PE_BAND_LOW_BELOW_FLASH, work.minOf { it.minDifficulty })
        assertEquals(flash - TrainingRanges.PE_BAND_HIGH_BELOW_FLASH, work.maxOf { it.maxDifficulty })
        assertTrue(flash < profile.effectiveRepeatableMax, "flash must sit clear of the max")
    }

    @Test
    fun `a short session does not become a long one`() {
        // 20 minutes of cold hard bouldering used to plan ~70: the two-problem
        // floor plus five mandatory attempts plus a warm-up ladder, none of it
        // fitting the budget the climber asked for.
        val plan = PlaylistPlanner.plan(
            params(GeneratorType.LIMIT, duration = 20, position = SessionPosition.START_COLD),
            profile,
        )
        val estimate = plan.estimatedMinutes()
        assertTrue(estimate <= 20 * 2.0, "20-minute session estimated at $estimate min")
    }

    @Test
    fun `estimated minutes track the requested duration`() {
        // The 75-min limit session must not preview as ~29 min (the bug
        // that hid attempt time inside an invisible per-problem block).
        listOf(
            GeneratorType.LIMIT to 75,
            GeneratorType.VOLUME to 60,
            GeneratorType.POWER_ENDURANCE to 48,
            GeneratorType.PYRAMID to 60,
        ).forEach { (type, duration) ->
            val plan = PlaylistPlanner.plan(params(type, duration = duration), profile)
            val estimate = plan.estimatedMinutes()
            assertTrue(
                estimate >= duration * 0.6 && estimate <= duration * 1.35,
                "$type/$duration min: estimate $estimate strays too far",
            )
        }
    }

    @Test
    fun `limit never plans above max plus one V-grade`() {
        val plan = PlaylistPlanner.plan(params(GeneratorType.LIMIT), profile)
        // One V-grade above 22 (V6) is V7 = 23 on the real table, not 22+2.
        val ceiling = 22.0 + TrainingRanges.CEILING_ABOVE_MAX_STEPS
        assertTrue(plan.climbs().all { it.maxDifficulty <= ceiling })
        assertEquals(ceiling, plan.hardCeiling)
    }

    @Test
    fun `a V-grade is not always two difficulty points`() {
        // The reason the bands moved to table lookups: multiplying by two is
        // right around V5 and doubles the distance at the top of the scale.
        // Two difficulty points, but only one V-grade: V5 (20-21) -> V6 (22).
        assertEquals(1, VGradeOffsets.distanceInGrades(20.0, 22.0))
        // And one point can be a whole grade of its own near the top.
        assertEquals(1, VGradeOffsets.distanceInGrades(22.0, 23.0))  // V6 -> V7
        assertEquals(1, VGradeOffsets.distanceInGrades(26.0, 27.0))  // V9 -> V10
        // Which is the whole point: the same two points move a different
        // distance depending on where you stand.
        assertEquals(2, VGradeOffsets.distanceInGrades(22.0, 25.0))  // V6 -> V8, three points
    }

    @Test
    fun `power endurance sits three to two V-grades below max, on the real scale`() {
        // A V10 climber (27) used to get 21..23 — V5 to V7 — from the linear
        // maths, three grades easier than the band claims.
        val strong = profile.copy(maxDifficulty = 27.0, flashDifficulty = 25.0)
        val plan = PlaylistPlanner.plan(
            params(GeneratorType.POWER_ENDURANCE, position = SessionPosition.WARMED_UP),
            strong,
        )
        val work = plan.climbs().filter { it.section != PlanSection.WARM_UP }
        val lowest = work.minOf { it.minDifficulty }
        val highest = work.maxOf { it.maxDifficulty }
        // Flash 25 demotes to 24 → band 22..23, which reads as V6..V7. The
        // old V-grade arithmetic multiplied by two off the MAX and produced
        // 21..23 — V5..V7, where a V10 climber never gets pumped.
        assertEquals(22.0, lowest, "band floor")
        assertEquals(23.0, highest, "band ceiling")
        assertTrue(
            VGradeOffsets.distanceInGrades(lowest, 27.0) <= 4,
            "lowest reads as ${VGradeOffsets.distanceInGrades(lowest, 27.0)} grades below max",
        )
    }

    @Test
    fun `projecting plans 1-3 projects with explicit burns`() {
        val short = PlaylistPlanner.plan(params(GeneratorType.PROJECTING, duration = 25), profile)
        assertEquals(1, short.climbs().mapNotNull { it.repeatKey }.distinct().size)
        assertEquals(TrainingRanges.BURNS_PER_PROJECT, short.climbs().size)
        val long = PlaylistPlanner.plan(params(GeneratorType.PROJECTING, duration = 150), profile)
        assertEquals(3, long.climbs().mapNotNull { it.repeatKey }.distinct().size)
        assertEquals(3 * TrainingRanges.BURNS_PER_PROJECT, long.climbs().size)
    }

    // ── Power endurance ─────────────────────────────────────────

    @Test
    fun `power endurance builds sets of 4 with repeat keys across sets`() {
        val plan = PlaylistPlanner.plan(params(GeneratorType.POWER_ENDURANCE, duration = 48), profile)
        val climbs = plan.climbs()
        // 48 min / 12 per set = 4 sets × 4 problems.
        assertEquals(16, climbs.size)
        // Same 4 repeat keys every set → laps repeat their problems.
        assertEquals(setOf(0, 1, 2, 3), climbs.mapNotNull { it.repeatKey }.toSet())
        assertEquals(4, climbs.count { it.repeatKey == 0 })
        // Band: repeatable flash − 2 … − 1. Flash 18 demotes to 17 (one
        // sparse flash may not set the band outright), so 15..16.
        assertTrue(
            climbs.all { it.minDifficulty == 15.0 && it.maxDifficulty == 16.0 },
            "band was ${climbs.first().minDifficulty}..${climbs.first().maxDifficulty}",
        )
        // Lap rests short, set rests long.
        assertEquals(3, plan.rests().count { it.seconds == TrainingRanges.REST_PE_BETWEEN_SETS })
        assertEquals(12, plan.rests().count { it.seconds == TrainingRanges.REST_PE_BETWEEN_LAPS })
    }

    // ── Pyramid ─────────────────────────────────────────────────

    @Test
    fun `pyramid climbs Font steps up to one V below max`() {
        val plan = PlaylistPlanner.plan(params(GeneratorType.PYRAMID, duration = 60), profile)
        val climbs = plan.climbs()
        assertEquals(10, climbs.size, "4+3+2+1")
        // Font-step tiers 17, 18, 19, 20 — apex = max − 2 points (1 V),
        // because every tier of a session pyramid should actually get
        // TOPPED, and half-grade steps match what boards actually carry.
        val centers = climbs.map { (it.minDifficulty + it.maxDifficulty) / 2 }
        assertEquals(listOf(17.0, 17.0, 17.0, 17.0, 18.0, 18.0, 18.0, 19.0, 19.0, 20.0), centers)
        // Apex slot is PEAK.
        assertEquals(PlanSection.PEAK, climbs.last().section)
    }

    @Test
    fun `outlier peak anchors limit at the repeatable max`() {
        // One lucky 7b (24) against a 7a+ (23) background: a limit session
        // must CONSOLIDATE the 7b (band 7a+…7b), not assume 7b+ is in
        // session reach.
        val outlier = profile.copy(maxDifficulty = 24.0, secondMaxDifficulty = 23.0)
        val limit = PlaylistPlanner.plan(params(GeneratorType.LIMIT), outlier).climbs()
        assertEquals(23.0, limit.first().minDifficulty)
        assertEquals(24.0, limit.first().maxDifficulty)
        // Projecting targets the step above: 7b…7b+ IS the project here.
        val proj = PlaylistPlanner.plan(params(GeneratorType.PROJECTING), outlier).climbs()
        assertEquals(24.0, proj.first().minDifficulty)
        assertEquals(25.0, proj.first().maxDifficulty)
    }

    @Test
    fun `extreme outlier peak cannot drag the band past the background level`() {
        // A single soft-graded 7b (24) over a 6c+ (19) background: the
        // whole session anchors at the background, the fluke send only
        // caps the ceiling.
        val fluke = profile.copy(maxDifficulty = 24.0, secondMaxDifficulty = 19.0)
        val limit = PlaylistPlanner.plan(params(GeneratorType.LIMIT), fluke).climbs()
        assertEquals(19.0, limit.first().minDifficulty)
        assertEquals(20.0, limit.first().maxDifficulty)
    }

    @Test
    fun `volume anchors on the repeatable flash with bounded demotion`() {
        // Top flash 18, second flash 14: the single 18 may pull the band
        // up at most one Font step past the second-best → anchor 17.
        val sparseFlash = profile.copy(flashDifficulty = 18.0, secondFlashDifficulty = 14.0)
        val plan = PlaylistPlanner.plan(params(GeneratorType.VOLUME), sparseFlash)
        assertTrue(plan.climbs().all { it.maxDifficulty <= 17.0 + 0.001 })
        // Confirmed double flash keeps the full anchor.
        val solidFlash = profile.copy(flashDifficulty = 18.0, secondFlashDifficulty = 18.0)
        val plan2 = PlaylistPlanner.plan(params(GeneratorType.VOLUME), solidFlash)
        assertTrue(plan2.climbs().any { it.maxDifficulty >= 18.0 - 0.001 })
    }

    @Test
    fun `flash above the repeatable max cannot inflate the volume band`() {
        // Fluke flash at 24 over a repeatable max of 20: volume caps at 20.
        val fluke = profile.copy(
            maxDifficulty = 24.0,
            secondMaxDifficulty = 20.0,
            flashDifficulty = 24.0,
            secondFlashDifficulty = 24.0,
        )
        val plan = PlaylistPlanner.plan(params(GeneratorType.VOLUME), fluke)
        assertTrue(plan.climbs().all { it.maxDifficulty <= 20.0 + 0.001 })
    }

    @Test
    fun `projecting band sits above the limit band`() {
        val limit = PlaylistPlanner.plan(params(GeneratorType.LIMIT), profile).climbs()
        val proj = PlaylistPlanner.plan(params(GeneratorType.PROJECTING), profile).climbs()
        // Limit: session-sendable (22…23); projecting: multi-session (23…24).
        assertEquals(23.0, proj.first().minDifficulty)
        assertEquals(24.0, proj.first().maxDifficulty)
        assertTrue(proj.first().minDifficulty > limit.first().minDifficulty)
    }

    @Test
    fun `the up-and-down pyramid descends again`() {
        val plan = PlaylistPlanner.plan(
            params(GeneratorType.PYRAMID, duration = 100)
                .copy(pyramidShape = PyramidShape.UP_AND_DOWN),
            profile,
        )
        val sections = plan.climbs().map { it.section }
        assertTrue(PlanSection.DESCENT in sections, "an up-and-down pyramid descends")
        // Descent mirrors the ascent minus apex: 2+3+4 = 9 extra climbs.
        assertEquals(19, plan.climbs().size)
    }

    @Test
    fun `the build-up pyramid stops at the apex, whatever the duration`() {
        // The descent used to appear by itself at 90 minutes, so anything
        // shorter was half a pyramid presented as a whole one — and anything
        // longer got a shape the climber never asked for.
        listOf(30, 100, 150).forEach { duration ->
            val plan = PlaylistPlanner.plan(
                params(GeneratorType.PYRAMID, duration = duration), profile,
            )
            assertTrue(
                plan.climbs().none { it.section == PlanSection.DESCENT },
                "$duration min build-up must not descend",
            )
        }
    }

    @Test
    fun `warm-up ladder reaches one V below the limit working grade`() {
        val plan = PlaylistPlanner.plan(
            params(GeneratorType.LIMIT, duration = 90, position = SessionPosition.START_COLD),
            profile,
        )
        val warmUp = plan.climbs().filter { it.section == PlanSection.WARM_UP }
        assertTrue(warmUp.isNotEmpty())
        // First work grade = max (22); ladder must climb to 20 (max − 1 V),
        // not stall 3 V short of the working intensity.
        val topCenter = warmUp.maxOf { (it.minDifficulty + it.maxDifficulty) / 2 }
        assertEquals(20.0, topCenter)
    }

    @Test
    fun `short pyramid warm-up reaches one V below its three-tier base`() {
        val plan = PlaylistPlanner.plan(
            params(GeneratorType.PYRAMID, duration = 45, position = SessionPosition.START_COLD),
            profile,
        )
        val warmUp = plan.climbs().filter { it.section == PlanSection.WARM_UP }
        assertTrue(warmUp.isNotEmpty())
        // A 45-minute pyramid loses its ladder minutes from the main budget
        // and builds THREE tiers, so the base sits higher than a four-tier
        // pyramid's would. The ladder has to stop just under the base it will
        // actually meet, whichever that is.
        val base = plan.climbs()
            .filter { it.section != PlanSection.WARM_UP }
            .minOf { (it.minDifficulty + it.maxDifficulty) / 2 }
        val topCenter = warmUp.maxOf { (it.minDifficulty + it.maxDifficulty) / 2 }
        assertEquals(
            TrainingRanges.WARMUP_END_BELOW_FIRST_WORK,
            base - topCenter,
            "ladder ended ${base - topCenter} below the first working problem",
        )
    }

    @Test
    fun `easy-session warm-up starts below the working grade not below max`() {
        val plan = PlaylistPlanner.plan(
            params(GeneratorType.VOLUME, duration = 60, position = SessionPosition.START_COLD),
            profile,
        )
        val warmUp = plan.climbs().filter { it.section == PlanSection.WARM_UP }
        assertTrue(warmUp.isNotEmpty())
        // Volume works at flash−2V…flash (14…18) → warm-up must sit BELOW
        // the working band's floor, not at it.
        val topCenter = warmUp.maxOf { (it.minDifficulty + it.maxDifficulty) / 2 }
        assertTrue(topCenter <= 13.0, "warm-up top was $topCenter")
    }

    // ── Warm-up / session position ──────────────────────────────

    @Test
    fun `cold start prepends a warm-up ladder below the working grade`() {
        val plan = PlaylistPlanner.plan(
            params(GeneratorType.LIMIT, duration = 60, position = SessionPosition.START_COLD),
            profile,
        )
        val warmUps = plan.climbs().filter { it.section == PlanSection.WARM_UP }
        assertTrue(warmUps.isNotEmpty(), "cold start must warm up")
        assertTrue(warmUps.size <= TrainingRanges.WARMUP_MAX_PROBLEMS)
        // The ladder hangs off the FIRST WORK GRADE now, not the max — its
        // job is to bridge that gap, and tying it to the max meant two rules
        // that had to be reconciled with a min().
        assertTrue(warmUps.all { it.maxDifficulty < 22.0 })
        val start = (warmUps.first().minDifficulty + warmUps.first().maxDifficulty) / 2
        assertEquals(22.0 - TrainingRanges.WARMUP_START_BELOW_FIRST_WORK, start)
        // Short rests between ladder problems + the long transition rest.
        val warmUpRests = plan.rests().filter { it.section == PlanSection.WARM_UP }
        assertEquals(
            warmUps.size - 1,
            warmUpRests.count { it.seconds == TrainingRanges.REST_WARMUP_BETWEEN_PROBLEMS },
            "a rest between every pair of warm-up problems",
        )
        assertEquals(
            1,
            warmUpRests.count { it.seconds == TrainingRanges.REST_AFTER_WARMUP },
            "one transition rest before the working set",
        )
        // Five ladder problems cost ~16 min, leaving ~44 for the main set
        // against a 21-min block: two problems. Tying the ladder to the first
        // work grade instead of the max made it three tiers rather than five,
        // which is where those minutes came back from.
        assertEquals(
            2,
            plan.climbs().filter { it.section == PlanSection.PEAK }
                .mapNotNull { it.repeatKey }.distinct().size,
        )
    }

    @Test
    fun `warmed up plans no ladder`() {
        val plan = PlaylistPlanner.plan(
            params(GeneratorType.VOLUME, position = SessionPosition.WARMED_UP), profile,
        )
        assertTrue(plan.climbs().none { it.section == PlanSection.WARM_UP })
    }

    @Test
    fun `end of session downgrades limit to volume and shifts easier`() {
        val plan = PlaylistPlanner.plan(
            params(GeneratorType.LIMIT, position = SessionPosition.END_OF_SESSION), profile,
        )
        assertEquals(GeneratorType.VOLUME, plan.effectiveType)
        assertEquals(GeneratorType.LIMIT, plan.downgradedFromType)
        // Shifted flash band: (18−2)−4 … (18−2) → nothing above 16.
        assertTrue(plan.climbs().all { it.maxDifficulty <= 16.0 + 0.001 })
    }

    @Test
    fun `end of session keeps volume as volume but easier`() {
        val normal = PlaylistPlanner.plan(params(GeneratorType.VOLUME), profile)
        val late = PlaylistPlanner.plan(
            params(GeneratorType.VOLUME, position = SessionPosition.END_OF_SESSION), profile,
        )
        assertNull(late.downgradedFromType)
        val maxNormal = normal.climbs().maxOf { it.maxDifficulty }
        val maxLate = late.climbs().maxOf { it.maxDifficulty }
        assertEquals(maxNormal - TrainingRanges.END_OF_SESSION_SHIFT, maxLate)
    }

    // ── Degenerate profiles ─────────────────────────────────────

    @Test
    fun `empty logbook uses defaults and flags it`() {
        val plan = PlaylistPlanner.plan(
            params(GeneratorType.VOLUME),
            LogbookProfile(maxDifficulty = null, flashDifficulty = null, sampleSize = 0),
        )
        assertTrue(plan.usedDefaultProfile)
        assertTrue(plan.climbs().isNotEmpty())
    }

    @Test
    fun `V0 climber never plans below the scale floor`() {
        val beginner = LogbookProfile(maxDifficulty = 11.0, flashDifficulty = 10.0, sampleSize = 10)
        GeneratorType.entries.forEach { type ->
            val plan = PlaylistPlanner.plan(
                params(type, position = SessionPosition.START_COLD), beginner,
            )
            assertTrue(
                plan.climbs().all { it.minDifficulty >= TrainingRanges.MIN_DIFFICULTY },
                "$type must clamp at V0",
            )
            assertTrue(plan.climbs().isNotEmpty(), "$type must still produce a plan")
        }
    }

    @Test
    fun `plans never start or end with a rest`() {
        GeneratorType.entries.forEach { type ->
            SessionPosition.entries.forEach { pos ->
                val plan = PlaylistPlanner.plan(params(type, position = pos), profile)
                assertTrue(plan.slots.first() is PlanSlot.ClimbSlot, "$type/$pos starts with a climb")
                assertTrue(plan.slots.last() is PlanSlot.ClimbSlot, "$type/$pos ends with a climb")
            }
        }
    }
}
