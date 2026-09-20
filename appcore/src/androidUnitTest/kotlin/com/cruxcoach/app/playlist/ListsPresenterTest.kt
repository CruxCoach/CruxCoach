package com.cruxcoach.app.playlist

import com.cruxcoach.app.logbook.CI_WAIT_MS
import com.cruxcoach.app.logbook.newPersonalRepo
import com.cruxcoach.app.ui.ListDetailScreenModel
import com.cruxcoach.app.ui.ListsScreenModel
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.ListPlaybackAdvance
import com.cruxcoach.data.repository.ListPlaybackOrder
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.app.logbook.awaitValue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.withTimeout

class ListsPresenterTest {
    /**
     * One thread for both the presenter scope and its IO, so a state change
     * implies the database write that produced it has finished. On the shared
     * Default pool the two race, and an assertion that reads the repository
     * straight after awaiting state fails at random.
     */
    // Daemon threads that are never shut down: closing one while a presenter
    // still has work queued throws into its scope and fails an unrelated test.
    private val serialOwner = com.cruxcoach.app.logbook.SerialDispatcher()
    private val serial = serialOwner.dispatcher
    private val serialIo = serial


    /** Catalogue stand-in: the personal DB never holds climb metadata. */
    private class FakeLookup(climbs: List<ClimbWithStats>) : ListClimbLookup {
        private val byKey = climbs.associateBy { normUuidKey(it.uuid) }
        var anyAngleCalls = 0
        override fun climbsByUuids(uuids: Collection<String>, angle: Int): List<ClimbWithStats> =
            uuids.mapNotNull { byKey[normUuidKey(it)] }.filter { angle == 40 }

        override fun climbsByUuidsAnyAngle(uuids: Collection<String>): List<ClimbWithStats> {
            anyAngleCalls++
            return uuids.mapNotNull { byKey[normUuidKey(it)] }.distinct()
        }
    }

    private fun climb(uuid: String, name: String) = ClimbWithStats(
        uuid = uuid, layoutId = 1L, setterUsername = "setter", name = name, frames = "p1r12",
        framesCount = 1L, difficultyAverage = 18.0, qualityAverage = 3.0, ascensionistCount = 5L,
        origin = "kilter", source = "kilter", syncStatus = "synced",
    )

    /**
     * Runs a repository call on the same thread the presenters use for IO.
     *
     * One SQLDelight JDBC driver holds a single connection and a single current
     * transaction, so the test thread and a presenter touching it at the same
     * time corrupt each other's transaction state. Funnelling every database
     * call through one thread removes that race; it does not weaken anything
     * the tests assert.
     */
    private suspend fun <T> db(block: suspend () -> T): T = withContext(serial) { block() }

    private fun lists(repo: PersonalBoardRepository, lookup: ListClimbLookup = EmptyClimbLookup) =
        ListsPresenter(repo, lookup, CoroutineScope(serial), serial)

    // The presenter turns any load exception into LOAD_FAILED, so a wait that
    // never completes would otherwise surface as a bare timeout. Report the
    // state instead: it says whether the load failed and what it holds.
    private suspend fun ListsPresenter.await(predicate: (ListsState) -> Boolean) =
        try {
            withTimeout(CI_WAIT_MS) { state.first(predicate) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("ListsPresenter never matched; last state = ${state.value}", e)
        }

    private suspend fun ListDetailPresenter.await(predicate: (ListDetailState) -> Boolean) =
        try {
            withTimeout(CI_WAIT_MS) { state.first(predicate) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("ListDetailPresenter never matched; last state = ${state.value}", e)
        }

    @Test
    fun `both built-in lists exist and custom lists are created once per name`() = runBlocking<Unit> {
        val repo = newPersonalRepo()
        val p = lists(repo)
        val seeded = p.await { it.lists.size == 2 }
        assertEquals(setOf(true), seeded.lists.map { it.isBuiltin }.toSet())
        assertEquals(1, seeded.lists.count { it.isIgnored })

        p.showCreateDialog()
        p.updateNewListName("  Projects  ")
        p.createList()
        p.await { it.lists.size == 3 }
        assertFalse(p.state.value.showCreateDialog)
        assertEquals("Projects", p.state.value.lists.single { !it.isBuiltin }.name)

        p.updateNewListName("projects")
        p.createList()
        assertEquals(ListsError.DUPLICATE_NAME, p.state.value.error)
        assertEquals(3, p.state.value.lists.size)
        p.consumeError()

        p.updateNewListName("   ")
        p.createList()
        assertEquals(3, p.state.value.lists.size)

        val custom = p.state.value.lists.single { !it.isBuiltin }
        p.requestDeleteList(custom.id)
        p.confirmDeleteList()
        p.await { it.lists.size == 2 && it.deleteConfirmListId == null }
    }

    @Test
    fun `membership toggles across lists, favourites and ignored`() = runBlocking<Unit> {
        val repo = newPersonalRepo()
        val p = lists(repo)
        p.await { it.lists.size == 2 }
        p.showCreateDialog()
        p.updateNewListName("Projects")
        p.createList()
        val listId = p.await { it.lists.size == 3 }.lists.single { !it.isBuiltin }.id

        p.openClimb("climb-1", 45)
        p.await { it.membershipClimbUuid == "climb-1" }
        p.await { !it.isLoading }
        assertFalse(p.state.value.isFavorite)

        p.toggleList(listId)
        p.await { listId in it.membershipListIds }
        assertEquals(setOf("climb-1"), db { repo.getClimbListEntryUuids(listId, 50, 0).map { it.first }.toSet() })
        assertEquals(1L, db { repo.countClimbListEntries(listId) })

        p.toggleFavorite()
        p.await { it.isFavorite }
        assertTrue(db { repo.isClimbFavorited("climb-1") })
        assertTrue(db { repo.ensureFavoritesListExists() in p.state.value.membershipListIds })

        p.toggleIgnored()
        p.await { it.isIgnored }
        assertEquals(setOf("climb-1"), db { repo.getIgnoredClimbUuids() })

        p.toggleList(listId)
        p.await { listId !in it.membershipListIds }
        assertEquals(0L, db { repo.countClimbListEntries(listId) })

        // A new name creates the list and adds the climb in one step.
        p.updateNewListName("Warmup")
        p.createListAndAdd()
        val after = p.await { it.lists.size == 4 }
        val warmup = after.lists.single { it.name == "Warmup" }
        assertTrue(warmup.id in p.state.value.membershipListIds)
        assertEquals(1L, db { repo.countClimbListEntries(warmup.id) })
        assertEquals("", p.state.value.newListName)

        // An existing name only toggles membership; it never creates a second list.
        p.updateNewListName("warmup")
        p.createListAndAdd()
        p.await { warmup.id !in it.membershipListIds }
        assertEquals(4, p.state.value.lists.size)
    }

    @Test
    fun `list detail resolves members, renames and edits the plan`() = runBlocking<Unit> {
        val repo = newPersonalRepo()
        val listId = db { repo.createClimbList("Session") }
        db { repo.addClimbToList(listId, "AAA-111") }
        db { repo.addClimbToList(listId, "bbb-222") }
        db { repo.addClimbToList(listId, "missing") }
        val lookup = FakeLookup(listOf(climb("aaa111", "Alpha"), climb("BBB222", "Bravo")))
        val detail = ListDetailPresenter(
            repo, listId, lookup, defaultAngle = { 40 }, scope = CoroutineScope(serial),
            ioDispatcher = serial,
        )
        val loaded = detail.await { !it.isLoading }
        assertEquals("Session", loaded.name)
        assertFalse(loaded.isBuiltin)
        // Spelling differences must not lose a member.
        // The repository returns members newest-first; spelling differences must not lose one.
        assertEquals(listOf(null, "Bravo", "Alpha"), loaded.members.map { it.climb?.name })
        assertEquals(1, loaded.unavailableCount)
        assertFalse(loaded.hasPlaybackPlan)
        assertTrue(lookup.anyAngleCalls > 0)

        detail.showRenameDialog()
        assertEquals("Session", detail.state.value.renameValue)
        detail.updateRenameValue(" Power ")
        detail.confirmRename()
        detail.await { it.name == "Power" && !it.showRenameDialog }

        detail.resetPlanFromList()
        val planned = detail.await { it.planSteps.size == 5 }
        assertEquals(listOf(false, true, false, true, false), planned.planSteps.map { it.isRest })
        // No previous rests and no configured default: the three-minute fallback.
        assertEquals(listOf(180L, 180L), planned.planSteps.filter { it.isRest }.map { it.restSeconds })
        assertEquals(listOf(40L, 40L, 40L), planned.planSteps.filterNot { it.isRest }.map { it.angle })
        assertTrue(planned.hasPlaybackPlan)

        val firstClimbStep = planned.planSteps.first { !it.isRest }
        detail.addRest(45, afterStepId = firstClimbStep.stepId)
        val withRest = detail.await { it.planSteps.size == 6 }
        assertEquals(45L, withRest.planSteps[1].restSeconds)

        // Below the minimum, a rest is clamped rather than rejected.
        detail.showEditRests(listOf(withRest.planSteps[1].stepId))
        assertEquals(1, detail.state.value.editRestStepIds.size)
        detail.updateSelectedRestSeconds(2)
        val clamped = detail.await { it.planSteps[1].restSeconds == 10L && it.editRestStepIds.isEmpty() }
        assertEquals(6, clamped.planSteps.size)

        detail.removeStep(clamped.planSteps[1].stepId)
        detail.await { it.planSteps.size == 5 }

        detail.duplicateClimb(detail.state.value.planSteps.first { !it.isRest }.stepId)
        detail.await { it.planSteps.count { step -> !step.isRest } == 4 }

        detail.setOrderAndRest()
        detail.await {
            it.playbackOrder == ListPlaybackOrder.SHUFFLE &&
                it.playbackAdvance == ListPlaybackAdvance.AFTER_SEND && it.playbackRestSeconds == 120L
        }
        // The presenter updates state before it writes, so wait for the row.
        // Poll on the database thread: polling from here would be a second
        // thread on the one JDBC connection the presenter is writing through.
        awaitValue(ListPlaybackOrder.SHUFFLE) { db { repo.getClimbListById(listId)!!.playbackOrder } }
        awaitValue(ListPlaybackAdvance.AFTER_SEND) { db { repo.getClimbListById(listId)!!.playbackAdvance } }
        awaitValue(120L) { db { repo.getClimbListById(listId)!!.playbackRestSeconds } }

        detail.removeFromList("AAA-111")
        val removed = detail.await { it.members.size == 2 }
        // Removing membership also removes that climb's plan steps.
        assertTrue(removed.planSteps.none { it.climbUuid == "AAA-111" })

        detail.clearPlan()
        detail.await { it.planSteps.isEmpty() && !it.hasPlaybackPlan }
        detail.close()
    }

    private fun ListDetailPresenter.setOrderAndRest() {
        setPlaybackOrder(ListPlaybackOrder.SHUFFLE)
        setPlaybackAdvance(ListPlaybackAdvance.AFTER_SEND)
        setPlaybackRestSeconds(120)
    }

    @Test
    fun `built-in lists cannot be renamed and a missing list reports notFound`() = runBlocking<Unit> {
        val repo = newPersonalRepo()
        val favorites = db { repo.ensureFavoritesListExists() }
        val detail = ListDetailPresenter(repo, favorites, scope = CoroutineScope(serial), ioDispatcher = serial)
        val loaded = detail.await { !it.isLoading }
        assertTrue(loaded.isBuiltin)
        detail.showRenameDialog()
        detail.updateRenameValue("Mine")
        detail.confirmRename()
        detail.await { !it.showRenameDialog }
        assertEquals(loaded.name, db { repo.getClimbListById(favorites)?.name })

        val gone = ListDetailPresenter(repo, 9999L, scope = CoroutineScope(serial), ioDispatcher = serial)
        gone.await { it.error == ListDetailError.NOT_FOUND }
        assertNull(db { repo.getClimbListById(9999L) })
    }

    @Test
    fun `facade exposes plain codes and flags`() = runBlocking<Unit> {
        val repo = newPersonalRepo()
        val listId = db { repo.createClimbList("Session") }
        db { repo.addClimbToList(listId, "aaa") }
        val presenter = lists(repo)
        val model = ListsScreenModel(presenter)
        presenter.await { it.lists.size == 3 }

        val s = model.currentState
        assertEquals("none", s.errorCode)
        assertEquals(0L, s.deleteConfirmListId)
        val row = s.lists.single { !it.isBuiltin }
        assertEquals("Session", row.name)
        assertEquals(1L, row.climbCount)
        assertFalse(row.containsClimb)
        assertFalse(row.hasPlaybackPlan)

        model.openClimb("aaa", 40)
        presenter.await { it.membershipClimbUuid == "aaa" && it.membershipListIds.isNotEmpty() }
        assertTrue(model.currentState.lists.single { !it.isBuiltin }.containsClimb)
        model.requestDeleteList(row.id)
        assertEquals(row.id, model.currentState.deleteConfirmListId)
        model.dismissDeleteConfirm()

        val detail = ListDetailPresenter(
            repo, listId, FakeLookup(listOf(climb("aaa", "Alpha"))),
            scope = CoroutineScope(serial),
            ioDispatcher = serial,
        )
        val detailModel = ListDetailScreenModel(detail, "V_SCALE")
        detail.await { !it.isLoading }
        val d = detailModel.currentState
        assertEquals("list", d.orderCode)
        assertEquals("manual", d.advanceCode)
        assertEquals("none", d.errorCode)
        assertEquals("V4", d.members.single().grade)
        assertTrue(d.members.single().isAvailable)

        detailModel.setOrder("shuffle")
        detail.await { it.playbackOrder == ListPlaybackOrder.SHUFFLE }
        assertEquals("shuffle", detailModel.currentState.orderCode)
        detailModel.setOrder("nonsense")
        assertEquals("shuffle", detailModel.currentState.orderCode)
        detailModel.setAdvance("afterLog")
        detail.await { it.playbackAdvance == ListPlaybackAdvance.AFTER_LOG }
        assertEquals("afterLog", detailModel.currentState.advanceCode)

        var seen = 0
        val sub = detailModel.watch { seen++ }
        withTimeout(CI_WAIT_MS) { while (seen == 0) kotlinx.coroutines.delay(5) }
        sub.cancel()
        detailModel.close()
        model.close()
    }
}

/** Real-time waits: generous so a loaded machine cannot fail an otherwise correct test. */
private const val CI_WAIT_MS = 30_000L

/**
 * Two settings changed in quick succession: the second must survive.
 *
 * Writing a setting used to re-read the state at write time and to reload the
 * row afterwards, so the initial load could store the old values back and a
 * reload could flip the user's second toggle back in the UI.
 *
 * Virtual time only: no real-time waiting, so a loaded machine cannot turn a
 * correct result into a failure.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackSettingsRaceTest {

    @kotlin.test.Test
    fun `a rapid second settings change survives both the initial load and the write`() {
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
        kotlinx.coroutines.test.runTest(dispatcher) {
            val repo = com.cruxcoach.app.logbook.newPersonalRepo()
            val listId = repo.createClimbList("Session")
            val detail = ListDetailPresenter(
                repo,
                listId,
                scope = kotlinx.coroutines.CoroutineScope(dispatcher),
                ioDispatcher = dispatcher,
            )
            try {
                detail.setPlaybackOrder(com.cruxcoach.data.repository.ListPlaybackOrder.SHUFFLE)
                detail.setPlaybackAdvance(com.cruxcoach.data.repository.ListPlaybackAdvance.AFTER_LOG)
                advanceUntilIdle()

                val state = detail.state.value
                kotlin.test.assertEquals(com.cruxcoach.data.repository.ListPlaybackOrder.SHUFFLE, state.playbackOrder)
                kotlin.test.assertEquals(com.cruxcoach.data.repository.ListPlaybackAdvance.AFTER_LOG, state.playbackAdvance)

                val row = repo.getClimbListById(listId)!!
                kotlin.test.assertEquals(com.cruxcoach.data.repository.ListPlaybackOrder.SHUFFLE, row.playbackOrder)
                kotlin.test.assertEquals(com.cruxcoach.data.repository.ListPlaybackAdvance.AFTER_LOG, row.playbackAdvance)
            } finally {
                detail.close()
            }
        }
    }
}
