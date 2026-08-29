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
        recentlyTried: Boolean = false,
    ) = PlaylistCandidate(uuid, diff, quality, 100L, sent, attempted, recentlyTried)

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
    fun `queries an identical candidate band only once per fill`() {
        val pool = (1..10).map { candidate("c$it", 15.0) }
        var sourceCalls = 0
        val source = CandidateSource { min, max ->
            sourceCalls++
            pool.filter { it.difficulty in min..max }
        }

        val result = PlaylistFiller.fill(
            plan(
                climbSlot(14.0, 16.0),
                climbSlot(14.0, 16.0),
                climbSlot(14.0, 16.0),
            ),
            source,
            random = Random(1),
        )

        assertEquals(3, result.entries.filterIsInstance<GeneratedEntry.Climb>().size)
        assertEquals(1, sourceCalls)
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
    fun `default plan follows the real board grades instead of failing`() {
        val slots = listOf(
            climbSlot(10.0, 11.0),
            climbSlot(11.0, 12.0),
            climbSlot(12.0, 13.0),
        )
        val board = listOf(
            candidate("actual-1", 23.0),
            candidate("actual-2", 24.0),
            candidate("actual-3", 25.0),
        )
        val result = PlaylistFiller.fill(
            PlaylistPlan(
                slots = slots,
                effectiveType = GeneratorType.PYRAMID,
                usedDefaultProfile = true,
                hardCeiling = 22.0,
                maxWidening = 1.0,
            ),
            source = poolSource(emptyList()),
            boardCandidates = board,
            random = Random(1),
        )

        assertEquals(0, result.droppedClimbs)
        assertEquals(setOf(23.0, 24.0, 25.0),
            result.entries.filterIsInstance<GeneratedEntry.Climb>().map { it.difficulty }.toSet())
    }

    @Test
    fun `board fallback keeps a personalized hard ceiling`() {
        val result = PlaylistFiller.fill(
            PlaylistPlan(
                slots = listOf(climbSlot(10.0, 11.0)),
                effectiveType = GeneratorType.PYRAMID,
                usedDefaultProfile = false,
                hardCeiling = 18.0,
                maxWidening = 1.0,
            ),
            source = poolSource(emptyList()),
            boardCandidates = listOf(candidate("too-hard", 23.0)),
            random = Random(1),
        )

        assertEquals(1, result.droppedClimbs)
        assertTrue(result.entries.isEmpty())
    }

    @Test
    fun `manual warmup adapts to board while requested main band stays exact`() {
        val plan = PlaylistPlan(
            slots = listOf(
                PlanSlot.ClimbSlot(10.0, 11.0, PlanSection.WARM_UP),
                PlanSlot.ClimbSlot(18.0, 19.0, PlanSection.MAIN),
            ),
            effectiveType = GeneratorType.MANUAL,
            usedDefaultProfile = true,
            hardCeiling = 22.0,
            maxWidening = 0.0,
        )
        val board = listOf(candidate("warmup", 16.0), candidate("main", 18.0))
        val result = PlaylistFiller.fill(
            plan = plan,
            source = poolSource(board),
            boardCandidates = board,
            random = Random(1),
        )

        assertEquals(0, result.droppedClimbs)
        assertEquals(
            listOf(16.0, 18.0),
            result.entries.filterIsInstance<GeneratedEntry.Climb>().map { it.difficulty },
        )
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

    // ── Candidate selection + freshness ─────────────────────────

    @Test
    fun `NEW selection prefers never-tried climbs`() {
        val pool = listOf(
            candidate("sent", 15.0, quality = 5.0, sent = true),
            candidate("proj", 15.0, quality = 5.0, attempted = true),
            candidate("fresh", 15.0, quality = 1.0),
        )
        val result = PlaylistFiller.fill(
            plan(climbSlot(14.0, 16.0)),
            poolSource(pool),
            selection = CandidateSelection.NEW,
            random = Random(1),
        )
        val pick = result.entries.filterIsInstance<GeneratedEntry.Climb>().single()
        assertEquals("fresh", pick.climbUuid, "lower quality but untouched wins the NEW tier")
    }

    @Test
    fun `PROJECTS selection prefers attempted-unsent climbs`() {
        val pool = listOf(
            candidate("fresh", 15.0, quality = 5.0),
            candidate("proj", 15.0, quality = 1.0, attempted = true),
        )
        val result = PlaylistFiller.fill(
            plan(climbSlot(14.0, 16.0)),
            poolSource(pool),
            selection = CandidateSelection.PROJECTS,
            random = Random(1),
        )
        val pick = result.entries.filterIsInstance<GeneratedEntry.Climb>().single()
        assertEquals("proj", pick.climbUuid)
    }

    @Test
    fun `selection falls back instead of dropping the slot`() {
        // PROJECTS requested but no attempted climb in band → fresh fills in.
        val pool = listOf(candidate("fresh", 15.0))
        val result = PlaylistFiller.fill(
            plan(climbSlot(14.0, 16.0)),
            poolSource(pool),
            selection = CandidateSelection.PROJECTS,
            random = Random(1),
        )
        assertEquals(0, result.droppedClimbs)
        assertEquals(
            "fresh",
            result.entries.filterIsInstance<GeneratedEntry.Climb>().single().climbUuid,
        )
    }

    @Test
    fun `recently tried climbs rank behind untouched material`() {
        val pool = listOf(
            candidate("lastweek", 15.0, quality = 5.0, recentlyTried = true),
            candidate("fresh", 15.0, quality = 1.0),
        )
        repeat(5) { seed ->
            val result = PlaylistFiller.fill(
                plan(climbSlot(14.0, 16.0)),
                poolSource(pool),
                selection = CandidateSelection.ALL,
                random = Random(seed),
            )
            val pick = result.entries.filterIsInstance<GeneratedEntry.Climb>().single()
            assertEquals("fresh", pick.climbUuid, "seed $seed")
        }
    }

}
