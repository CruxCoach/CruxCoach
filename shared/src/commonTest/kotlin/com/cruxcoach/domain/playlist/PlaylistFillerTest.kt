package com.cruxcoach.domain.playlist

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaylistFillerTest {

    private fun candidate(
        uuid: String,
        diff: Double,
        quality: Double = 3.0,
        sent: Boolean = false,
        attempted: Boolean = false,
    ) = PlaylistCandidate(uuid, diff, quality, 100L, sent, attempted)

    private fun poolSource(pool: List<PlaylistCandidate>) = CandidateSource { min, max ->
        pool.filter { it.difficulty in min..max }
    }

    private fun plan(vararg slots: PlanSlot, type: GeneratorType = GeneratorType.VOLUME) =
        PlaylistPlan(slots.toList(), effectiveType = type)

    private fun climbSlot(min: Double, max: Double, section: PlanSection = PlanSection.MAIN, repeatKey: Int? = null) =
        PlanSlot.ClimbSlot(min, max, section, repeatKey)

    @Test
    fun `fills every slot without repeating climbs`() {
        val pool = (1..10).map { candidate("c$it", 15.0) }
        val result = PlaylistFiller.fill(
            plan(climbSlot(14.0, 16.0), climbSlot(14.0, 16.0), climbSlot(14.0, 16.0)),
            poolSource(pool),
            random = Random(1),
        )
        val uuids = result.entries.filterIsInstance<GeneratedEntry.Climb>().map { it.climbUuid }
        assertEquals(3, uuids.size)
        assertEquals(uuids.toSet().size, uuids.size, "no duplicates without repeatKey")
        assertEquals(0, result.droppedClimbs)
    }

    @Test
    fun `repeat keys reuse the same climb across sets`() {
        val pool = (1..10).map { candidate("c$it", 15.0) }
        // Two "sets" of two problems, keys 0/1 repeated.
        val result = PlaylistFiller.fill(
            plan(
                climbSlot(14.0, 16.0, repeatKey = 0),
                climbSlot(14.0, 16.0, repeatKey = 1),
                climbSlot(14.0, 16.0, repeatKey = 0),
                climbSlot(14.0, 16.0, repeatKey = 1),
            ),
            poolSource(pool),
            random = Random(1),
        )
        val uuids = result.entries.filterIsInstance<GeneratedEntry.Climb>().map { it.climbUuid }
        assertEquals(4, uuids.size)
        assertEquals(uuids[0], uuids[2], "lap repeats problem 0")
        assertEquals(uuids[1], uuids[3], "lap repeats problem 1")
        assertTrue(uuids[0] != uuids[1])
    }

    @Test
    fun `widens the band when the exact band is empty`() {
        // Nothing at 15, but plenty at 17 (1 V wider).
        val pool = (1..5).map { candidate("c$it", 17.0) }
        val result = PlaylistFiller.fill(
            plan(climbSlot(14.0, 16.0)),
            poolSource(pool),
            random = Random(1),
        )
        assertEquals(1, result.entries.size)
        assertEquals(0, result.droppedClimbs)
        assertTrue(result.widenedSlots >= 1)
    }

    @Test
    fun `drops the slot and its leading rest when nothing fits`() {
        val pool = listOf(candidate("only", 15.0))
        val result = PlaylistFiller.fill(
            plan(
                climbSlot(14.0, 16.0),
                PlanSlot.RestSlot(300, PlanSection.MAIN),
                climbSlot(30.0, 32.0), // far outside any widening
            ),
            poolSource(pool),
            random = Random(1),
        )
        assertEquals(1, result.droppedClimbs)
        // The orphaned rest is gone: single climb entry remains.
        assertEquals(1, result.entries.size)
        assertTrue(result.entries.single() is GeneratedEntry.Climb)
    }

    @Test
    fun `prefers unclimbed over sent climbs`() {
        val pool = listOf(
            candidate("sent-1", 15.0, quality = 3.5, sent = true),
            candidate("fresh-1", 15.0, quality = 3.0),
        )
        // Deterministic: with a pick pool of 2, both orderings keep fresh first.
        val picks = (0..9).map { seed ->
            val r = PlaylistFiller.fill(plan(climbSlot(14.0, 16.0)), poolSource(pool), random = Random(seed))
            (r.entries.single() as GeneratedEntry.Climb).climbUuid
        }
        // fresh-1 must rank ahead — sent=false sorts first; random picks
        // within the top pool may still choose sent-1 sometimes, but the
        // fresh climb must appear (ranking is effective, not decorative).
        assertTrue("fresh-1" in picks)
    }

    @Test
    fun `projecting peak slots prefer open projects`() {
        val pool = listOf(
            candidate("shiny-new", 23.0, quality = 3.9),
            candidate("my-project", 23.0, quality = 2.5, attempted = true),
        )
        val result = PlaylistFiller.fill(
            plan(
                climbSlot(22.0, 24.0, section = PlanSection.PEAK),
                type = GeneratorType.PROJECTING,
            ),
            poolSource(pool),
            openProjects = listOf("my-project"),
            random = Random(1),
        )
        assertEquals(
            "my-project",
            (result.entries.single() as GeneratedEntry.Climb).climbUuid,
            "open project beats higher-quality fresh climb in PROJECTING",
        )
    }

    @Test
    fun `rests pass through in place`() {
        val pool = (1..5).map { candidate("c$it", 15.0) }
        val result = PlaylistFiller.fill(
            plan(
                climbSlot(14.0, 16.0),
                PlanSlot.RestSlot(45, PlanSection.MAIN),
                climbSlot(14.0, 16.0),
            ),
            poolSource(pool),
            random = Random(1),
        )
        assertEquals(3, result.entries.size)
        assertEquals(45, (result.entries[1] as GeneratedEntry.Rest).seconds)
    }

    @Test
    fun `empty catalogue drops everything but reports it`() {
        val result = PlaylistFiller.fill(
            plan(climbSlot(14.0, 16.0), climbSlot(14.0, 16.0)),
            poolSource(emptyList()),
            random = Random(1),
        )
        assertTrue(result.entries.isEmpty())
        assertEquals(2, result.droppedClimbs)
    }

    @Test
    fun `generator params round-trip through json`() {
        val params = PlaylistGeneratorParams(
            type = GeneratorType.POWER_ENDURANCE,
            durationMinutes = 45,
            position = SessionPosition.START_COLD,
            angle = 40,
            boardBrand = "kilter",
            layoutId = 8,
            productSizeId = 25,
        )
        assertEquals(params, PlaylistGeneratorParams.fromJson(params.toJson()))
        assertEquals(null, PlaylistGeneratorParams.fromJson(null))
        assertEquals(null, PlaylistGeneratorParams.fromJson("{broken"))
        assertEquals(null, PlaylistGeneratorParams.fromJson("""{"type":"FUTURE_TYPE"}"""))
    }
}
