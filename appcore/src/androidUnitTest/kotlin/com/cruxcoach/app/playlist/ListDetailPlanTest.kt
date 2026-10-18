package com.cruxcoach.app.playlist

import com.cruxcoach.app.links.PlaylistShareLink
import com.cruxcoach.app.logbook.CI_WAIT_MS
import com.cruxcoach.app.logbook.newPersonalRepo
import com.cruxcoach.app.ui.ListDetailScreenModel
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.NewListPlaybackStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Ports of Android `GroupAttemptsTest`, `PlaylistPlanRestEditorTest`,
 * `PlaylistClimbResolutionTest` (pinned-angle grade) and
 * `ListStartPlaysPlanTest`, plus the plan editor's presenter paths.
 */
class ListDetailPlanTest {

    // --- GroupAttemptsTest ---

    private var nextId = 1L
    private fun climb(uuid: String) = PlanStep(
        stepId = nextId++, isRest = false, restSeconds = null, climbUuid = uuid, angle = 40L, climb = null,
    )
    private fun rest(seconds: Long) = PlanStep(
        stepId = nextId++, isRest = true, restSeconds = seconds, climbUuid = null, angle = null, climb = null,
    )

    @Test
    fun `collapses attempt runs and keeps between-problem rests`() {
        // Limit structure: A r180 A r180 A | r300 | B r180 B
        val firstAttemptRest = rest(180)
        val secondAttemptRest = rest(180)
        val rows = groupAttempts(
            listOf(
                climb("a"), firstAttemptRest, climb("a"), secondAttemptRest, climb("a"),
                rest(300),
                climb("b"), rest(180), climb("b"),
            ),
        )
        assertEquals(3, rows.size)
        val first = rows[0] as PlanRow.Climb
        assertEquals(3, first.attemptCount)
        assertEquals(180L, first.attemptRestSeconds)
        assertEquals(listOf(firstAttemptRest.stepId, secondAttemptRest.stepId), first.attemptRestStepIds)
        assertEquals(300L, (rows[1] as PlanRow.Rest).step.restSeconds)
        assertEquals(2, (rows[2] as PlanRow.Climb).attemptCount)
    }

    @Test
    fun `distinct climbs stay separate rows`() {
        val rows = groupAttempts(listOf(climb("a"), rest(45), climb("b"), climb("c")))
        assertEquals(4, rows.size)
        assertEquals(1, (rows[0] as PlanRow.Climb).attemptCount)
    }

    @Test
    fun `back-to-back same climb without rest also groups`() {
        val rows = groupAttempts(listOf(climb("a"), climb("a")))
        assertEquals(1, rows.size)
        val g = rows.single() as PlanRow.Climb
        assertEquals(2, g.attemptCount)
        assertEquals(null, g.attemptRestSeconds)
    }

    @Test
    fun `trailing rest survives`() {
        val rows = groupAttempts(listOf(climb("a"), rest(60)))
        assertEquals(2, rows.size)
    }

    @Test
    fun `different inter-attempt rests remain visible instead of being mislabelled`() {
        val rows = groupAttempts(listOf(climb("a"), rest(30), climb("a"), rest(60), climb("a")))

        assertEquals(3, rows.size)
        assertEquals(2, (rows[0] as PlanRow.Climb).attemptCount)
        assertEquals(30L, (rows[0] as PlanRow.Climb).attemptRestSeconds)
        assertEquals(60L, (rows[1] as PlanRow.Rest).step.restSeconds)
        assertEquals(1, (rows[2] as PlanRow.Climb).attemptCount)
    }

    @Test
    fun `timed and immediate attempts do not collapse into one misleading rest group`() {
        val timedAfterImmediate = groupAttempts(listOf(climb("a"), climb("a"), rest(60), climb("a")))

        assertEquals(3, timedAfterImmediate.size)
        assertEquals(2, (timedAfterImmediate[0] as PlanRow.Climb).attemptCount)
        assertEquals(null, (timedAfterImmediate[0] as PlanRow.Climb).attemptRestSeconds)
        assertEquals(60L, (timedAfterImmediate[1] as PlanRow.Rest).step.restSeconds)
        assertEquals(1, (timedAfterImmediate[2] as PlanRow.Climb).attemptCount)

        val immediateAfterTimed = groupAttempts(listOf(climb("b"), rest(90), climb("b"), climb("b")))

        assertEquals(2, immediateAfterTimed.size)
        assertEquals(2, (immediateAfterTimed[0] as PlanRow.Climb).attemptCount)
        assertEquals(90L, (immediateAfterTimed[0] as PlanRow.Climb).attemptRestSeconds)
        assertEquals(1, (immediateAfterTimed[1] as PlanRow.Climb).attemptCount)
    }

    @Test
    fun `same climb at different angles remains separate`() {
        val rows = groupAttempts(
            listOf(climb("a").copy(angle = 20L), rest(60), climb("a").copy(angle = 40L)),
        )

        assertEquals(3, rows.size)
        assertEquals(1, (rows[0] as PlanRow.Climb).attemptCount)
        assertEquals(60L, (rows[1] as PlanRow.Rest).step.restSeconds)
        assertEquals(1, (rows[2] as PlanRow.Climb).attemptCount)
    }

    // --- PlaylistPlanRestEditorTest ---

    private fun climbAt(id: Long, uuid: String, angle: Long) =
        PlanStep(id, false, null, uuid, angle, null)

    private fun restWithId(id: Long, seconds: Long) = PlanStep(id, true, seconds, null, null, null)

    @Test
    fun `inserts rest directly after selected row without changing the remaining plan`() {
        val entries = listOf(
            climbAt(id = 11, uuid = "warmup", angle = 20),
            climbAt(id = 12, uuid = "project", angle = 40),
            restWithId(id = 13, seconds = 90),
        )

        val result = planWithRestInsertedAfter(steps = entries, afterStepId = 11, seconds = 180)!!

        assertEquals(listOf("warmup", null, "project", null), result.map { it.climbUuid })
        assertEquals(listOf(20L, null, 40L, null), result.map { it.angle })
        assertEquals(listOf(null, 180L, null, 90L), result.map { it.restSeconds })
    }

    @Test
    fun `inserted rest duration is kept inside the supported range`() {
        val entries = listOf(climbAt(id = 1, uuid = "a", angle = 40))

        val tooShort = planWithRestInsertedAfter(entries, 1, seconds = 1)!!
        val tooLong = planWithRestInsertedAfter(entries, 1, seconds = 9_999)!!

        assertEquals(10L, tooShort.last().restSeconds)
        assertEquals(3_600L, tooLong.last().restSeconds)
    }

    @Test
    fun `unknown insertion target leaves plan untouched`() {
        val entries = listOf(climbAt(id = 1, uuid = "a", angle = 40))

        assertNull(planWithRestInsertedAfter(entries, 99, seconds = 60))
    }

    // --- PlaylistClimbResolutionTest (pinned angle) ---

    private fun stats(uuid: String, difficulty: Double?, name: String = uuid, brand: String = "kilter", layout: Long = 1L) =
        ClimbWithStats(
            uuid = uuid, layoutId = layout, setterUsername = "setter", name = name, frames = "p1r12",
            framesCount = 1L, difficultyAverage = difficulty, qualityAverage = 3.0, ascensionistCount = 5L,
            origin = "kilter", source = "kilter", syncStatus = "synced", boardBrand = brand,
        )

    @Test
    fun `pinned step shows the grade of its own angle and never another angle's grade`() {
        val uuid = "aabbccdd00112233aabbccdd00112233"
        val anyAngle = stats(uuid = uuid, difficulty = 14.0)
        val at40 = stats(uuid = uuid, difficulty = 22.0)
        val pinned = mapOf((normUuidKey(uuid) to 40) to at40)

        assertEquals(22.0, gradeForPinnedAngle(anyAngle, pinned, uuid, 40L)?.difficultyAverage)
        assertNull(gradeForPinnedAngle(anyAngle, pinned, uuid, 45L)?.difficultyAverage)
        assertEquals(14.0, gradeForPinnedAngle(anyAngle, pinned, uuid, null)?.difficultyAverage)
    }

    // --- ListStartPlaysPlanTest ---

    private fun resolvedClimb(position: Long, uuid: String) =
        PlanStep(position, false, null, uuid, 40L, stats(uuid, 20.0))

    private fun resolvedRest(position: Long, seconds: Long) = PlanStep(position, true, seconds, null, null, null)

    @Test
    fun `a list with a training plan plays its tries and rests`() {
        // Two tries of "a" with rests between, then "b".
        val prepared = planPlayback(
            listOf(
                resolvedClimb(0, "a"), resolvedRest(1, 240), resolvedClimb(2, "a"), resolvedRest(3, 300),
                resolvedClimb(4, "b"),
            ),
            defaultAngle = 40,
        )

        assertEquals(
            listOf(
                PlaybackItem("a", 40, restAfterSeconds = 240),
                PlaybackItem("a", 40, restAfterSeconds = 300),
                PlaybackItem("b", 40),
            ),
            prepared.items,
        )
        assertEquals(1, prepared.boardCount)
    }

    @Test
    fun `a list without a plan plays once in its saved order`() {
        val members = listOf(
            ListMember("a", "2026-09-24", stats("a", 20.0)),
            ListMember("b", "2026-09-24", stats("b", 20.0)),
        )

        val prepared = memberPlayback(members, angle = 40, restSeconds = 0)

        assertEquals(listOf(PlaybackItem("a", 40), PlaybackItem("b", 40)), prepared.items)
    }

    @Test
    fun `unresolvable steps are skipped and their rests fold onto the previous climb`() {
        val prepared = planPlayback(
            listOf(
                resolvedRest(0, 30),
                resolvedClimb(1, "a"),
                resolvedRest(2, 60),
                PlanStep(3, false, null, "missing", 40L, null),
                resolvedRest(4, 90),
                resolvedClimb(5, "b"),
            ),
            defaultAngle = 40,
        )

        // The leading rest has no climb to own it; the missing climb drops out.
        assertEquals(listOf(PlaybackItem("a", 40, 150), PlaybackItem("b", 40)), prepared.items)
    }

    @Test
    fun `a plan over two board configurations reports both`() {
        val prepared = planPlayback(
            listOf(
                PlanStep(1, false, null, "a", 40L, stats("a", 20.0, layout = 1L)),
                PlanStep(2, false, null, "b", 40L, stats("b", 20.0, brand = "moonboard", layout = 2L)),
            ),
            defaultAngle = 40,
        )

        assertEquals(2, prepared.boardCount)
    }

    // --- Presenter: seeding, pinned grades, editor rows, share link ---

    private val serial = com.cruxcoach.app.logbook.SerialDispatcher().dispatcher

    private suspend fun <T> db(block: suspend () -> T): T = withContext(serial) { block() }

    /** Catalogue that carries different statistics per angle. */
    private class AngleLookup(private val byAngle: Map<Int, List<ClimbWithStats>>) : ListClimbLookup {
        override fun climbsByUuids(uuids: Collection<String>, angle: Int): List<ClimbWithStats> {
            val keys = uuids.map { normUuidKey(it) }.toSet()
            return byAngle[angle].orEmpty().filter { normUuidKey(it.uuid) in keys }
        }

        override fun climbsByUuidsAnyAngle(uuids: Collection<String>): List<ClimbWithStats> {
            val keys = uuids.map { normUuidKey(it) }.toSet()
            return byAngle.values.flatten().filter { normUuidKey(it.uuid) in keys }.distinctBy { normUuidKey(it.uuid) }
        }
    }

    private suspend fun ListDetailPresenter.await(predicate: (ListDetailState) -> Boolean) =
        try {
            withTimeout(CI_WAIT_MS) { state.first(predicate) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("state never matched: ${state.value}", e)
        }

    @Test
    fun `opening the plan editor seeds a plan once and grades steps at their pinned angle`() = runBlocking<Unit> {
        val repo = newPersonalRepo()
        val listId = db { repo.createClimbList("Limit") }
        db { repo.addClimbToList(listId, "aaaa0000aaaa0000aaaa0000aaaa0000") }
        db { repo.addClimbToList(listId, "bbbb0000bbbb0000bbbb0000bbbb0000") }
        val lookup = AngleLookup(
            mapOf(
                40 to listOf(
                    stats("aaaa0000aaaa0000aaaa0000aaaa0000", 18.0, name = "Alpha"),
                    stats("bbbb0000bbbb0000bbbb0000bbbb0000", 20.0, name = "Bravo"),
                ),
                50 to listOf(stats("aaaa0000aaaa0000aaaa0000aaaa0000", 24.0, name = "Alpha")),
            ),
        )
        val detail = ListDetailPresenter(
            repo, listId, lookup, defaultAngle = { 40 }, scope = CoroutineScope(serial), ioDispatcher = serial,
        )
        detail.await { !it.isLoading }

        detail.preparePlan()
        val seeded = detail.await { it.hasPlaybackPlan }
        assertEquals(listOf(false, true, false), seeded.planSteps.map { it.isRest })

        // A second open must not rebuild the plan the user has since edited.
        detail.duplicateClimb(seeded.planSteps.first().stepId)
        detail.await { it.planSteps.size == 4 }
        detail.preparePlan()
        // Queued behind the presenter's work on the one serial thread, so this
        // reads the rows after preparePlan has run.
        assertEquals(4, db { repo.getPlaybackSteps(listId).size })

        // Re-pin every step to 50°: only Alpha has statistics there.
        val repinned = detail.state.value.planSteps.map { NewListPlaybackStep(it.climbUuid, it.angle?.let { 50L }, it.restSeconds) }
        db { repo.replacePlaybackSteps(listId, repinned) }
        detail.refresh()
        val graded = detail.await { s -> s.planSteps.any { !it.isRest && it.angle == 50L } }
        val grades = graded.planSteps.filterNot { it.isRest }.map { it.climb?.name to it.climb?.difficultyAverage }
        assertTrue(grades.contains("Alpha" to 24.0))
        assertTrue(grades.contains("Bravo" to null))
        detail.close()
    }

    @Test
    fun `facade groups attempts and builds a share link that parses back`() = runBlocking<Unit> {
        val repo = newPersonalRepo()
        val listId = db { repo.createClimbList("Project") }
        val uuid = "aaaa0000aaaa0000aaaa0000aaaa0000"
        db { repo.addClimbToList(listId, uuid) }
        db {
            repo.replacePlaybackSteps(
                listId,
                listOf(
                    NewListPlaybackStep(uuid, 40L, null),
                    NewListPlaybackStep(null, null, 240L),
                    NewListPlaybackStep(uuid, 40L, null),
                ),
            )
        }
        val presenter = ListDetailPresenter(
            repo, listId, AngleLookup(mapOf(40 to listOf(stats(uuid, 18.0)))), defaultAngle = { 40 },
            scope = CoroutineScope(serial), ioDispatcher = serial,
        )
        val model = ListDetailScreenModel(presenter, "FRENCH", 40)
        presenter.await { it.planSteps.size == 3 }

        val ui = model.currentState
        assertEquals(1, ui.planRows.size)
        assertEquals(2, ui.planRows[0].attemptCount)
        assertEquals(240, ui.planRows[0].attemptRestSeconds)
        assertEquals(listOf(ui.planSteps[1].stepId), ui.planRows[0].attemptRestStepIds)
        assertEquals(listOf(240, 0), ui.playRestsAfter)
        assertFalse(ui.playHasMultipleBoards)
        assertTrue(ui.canAddRest)
        // The climb before a rest already has one; the last climb can take one.
        assertEquals(listOf(false, false, true), ui.planSteps.map { it.canAddRestAfter })

        val link = model.planShareLink()
        val parsed = PlaylistShareLink.parse(link.substringAfter("/l/"))!!
        assertEquals("Project", parsed.name)
        assertEquals(
            listOf(
                PlaylistShareLink.SharedStep.Climb("aaaa0000-aaaa-0000-aaaa-0000aaaa0000", 40),
                PlaylistShareLink.SharedStep.Rest(240),
                PlaylistShareLink.SharedStep.Climb("aaaa0000-aaaa-0000-aaaa-0000aaaa0000", 40),
            ),
            parsed.steps,
        )

        model.toggleEditMode()
        assertTrue(model.currentState.editMode)
        model.close()
    }
}
