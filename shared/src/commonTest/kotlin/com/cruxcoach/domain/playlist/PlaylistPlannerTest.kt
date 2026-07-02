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
        assertEquals(20, climbs.size, "60 min / 3 min per problem")
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
    fun `limit plans few problems at and above max with long rests`() {
        val plan = PlaylistPlanner.plan(params(GeneratorType.LIMIT, duration = 60), profile)
        val climbs = plan.climbs()
        assertEquals(3, climbs.size, "60 min / 20 min per limit problem")
        assertTrue(climbs.all { it.minDifficulty == 22.0 && it.maxDifficulty == 24.0 },
            "limit band must be max … max+1V")
        assertEquals(2, plan.rests().size)
        assertTrue(plan.rests().all { it.seconds == TrainingRanges.REST_LIMIT_BETWEEN_PROBLEMS })
    }

    @Test
    fun `limit never plans above max plus one V-grade`() {
        val plan = PlaylistPlanner.plan(params(GeneratorType.LIMIT), profile)
        assertTrue(plan.climbs().all { it.maxDifficulty <= 22.0 + TrainingRanges.CEILING_ABOVE_MAX })
    }

    @Test
    fun `projecting plans 1-3 projects`() {
        val short = PlaylistPlanner.plan(params(GeneratorType.PROJECTING, duration = 25), profile)
        assertEquals(1, short.climbs().size)
        val long = PlaylistPlanner.plan(params(GeneratorType.PROJECTING, duration = 150), profile)
        assertEquals(3, long.climbs().size)
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
        // Band: max−3V … max−2V → [16, 18].
        assertTrue(climbs.all { it.minDifficulty == 16.0 && it.maxDifficulty == 18.0 })
        // Lap rests short, set rests long.
        assertEquals(3, plan.rests().count { it.seconds == TrainingRanges.REST_PE_BETWEEN_SETS })
        assertEquals(12, plan.rests().count { it.seconds == TrainingRanges.REST_PE_BETWEEN_LAPS })
    }

    // ── Pyramid ─────────────────────────────────────────────────

    @Test
    fun `pyramid builds 4-3-2-1 up to max`() {
        val plan = PlaylistPlanner.plan(params(GeneratorType.PYRAMID, duration = 60), profile)
        val climbs = plan.climbs()
        assertEquals(10, climbs.size, "4+3+2+1")
        // Tier centers: 16, 18, 20, 22 (apex = max).
        val centers = climbs.map { (it.minDifficulty + it.maxDifficulty) / 2 }
        assertEquals(listOf(16.0, 16.0, 16.0, 16.0, 18.0, 18.0, 18.0, 20.0, 20.0, 22.0), centers)
        // Apex slot is PEAK.
        assertEquals(PlanSection.PEAK, climbs.last().section)
    }

    @Test
    fun `long pyramid session adds the descent`() {
        val plan = PlaylistPlanner.plan(params(GeneratorType.PYRAMID, duration = 100), profile)
        val sections = plan.climbs().map { it.section }
        assertTrue(PlanSection.DESCENT in sections, "≥90 min pyramid descends again")
        // Descent mirrors the ascent minus apex: 2+3+4 = 9 extra climbs.
        assertEquals(19, plan.climbs().size)
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
        // Ladder starts 5 V below max (22 − 10 = 12) and stays below max.
        assertTrue(warmUps.all { it.maxDifficulty < 22.0 })
        assertEquals(12.0, (warmUps.first().minDifficulty + warmUps.first().maxDifficulty) / 2)
        // Transition rest after the ladder.
        val firstRest = plan.slots.indexOfFirst { it is PlanSlot.RestSlot }
        assertTrue(firstRest > 0)
        assertEquals(
            TrainingRanges.REST_AFTER_WARMUP,
            (plan.slots[firstRest] as PlanSlot.RestSlot).seconds,
        )
        // Warm-up minutes shrink the main set: 60 → ~42 main minutes = 2 problems.
        assertEquals(2, plan.climbs().count { it.section == PlanSection.PEAK })
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
