package com.cruxcoach.app.creator

import com.cruxcoach.app.browse.BrowsePreferences
import com.cruxcoach.app.browse.testing.BrowseTestDb
import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import com.cruxcoach.app.logbook.awaitValue
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.community.ClimbEditorState
import com.cruxcoach.domain.community.ClimbValidation
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * The editor runs against a real in-memory BoardDB through the `:shared`
 * repository, so the draft round trips exercise the actual SQL.
 *
 * One single-thread dispatcher backs both the presenter scope and its IO, and
 * the test's own repository calls go through [db]: a SQLDelight JDBC driver
 * holds one connection and one current transaction, so reading from the test
 * thread while the presenter writes corrupts both.
 */
class ClimbEditorTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val serial = executor.asCoroutineDispatcher()
    private val database = BrowseTestDb()

    @AfterTest
    fun tearDown() {
        runBlocking { withContext(serial) { database.close() } }
        serial.close()
        executor.shutdownNow()
    }

    private suspend fun <T> db(block: () -> T): T = withContext(serial) { block() }

    private fun editor(store: MemoryKeyValueStore = MemoryKeyValueStore()): ClimbEditor {
        store.putString(BrowsePreferences.BOARD_BRAND, "kilter")
        store.putString(BrowsePreferences.BOARD_LAYOUT_ID, "1")
        store.putString(BrowsePreferences.BOARD_PRODUCT_SIZE_ID, "10")
        store.putString(BrowsePreferences.BOARD_ANGLE, "40")
        return ClimbEditor(
            boardRepository = database.boardRepo,
            drafts = draftStore(),
            preferences = BrowsePreferences(store),
            scope = CoroutineScope(serial),
            ioDispatcher = serial,
        )
    }

    private fun draftStore() = ClimbDraftStore(
        boardRepository = database.boardRepo,
        hashing = JvmHashing,
        clock = FixedClock(1_790_000_000L),
        pubkeyProvider = { OWN_PUBKEY },
    )

    private suspend fun ClimbEditor.opened(): ClimbEditor {
        open()
        awaitValue(false) { state.value.isLoading }
        return this
    }

    /** Minimum publishable climb: start, one hand, finish. */
    private suspend fun ClimbEditor.paintValidClimb() {
        // START is the brush a fresh editor already holds; re-tapping its chip
        // would clear the brush rather than select it.
        toggleHold(1)
        toggleBrush(HoldRole.HAND); toggleHold(2)
        toggleBrush(HoldRole.FINISH); toggleHold(3)
        setName("Testaufgabe")
        awaitValue(true) { state.value.validationIssues.isEmpty() }
    }

    @Test
    fun paintsTogglesAndRemovesHolds() = runBlocking {
        val editor = editor().opened()
        // The fresh editor pre-selects the green Start chip, as on Android.
        assertEquals(HoldRole.START, editor.state.value.editor.activeBrush)

        editor.toggleHold(11)
        assertEquals(mapOf(11 to HoldRole.START), editor.state.value.editor.selectedHolds)

        // Same role again toggles the hold off.
        editor.toggleHold(11)
        assertEquals(emptyMap(), editor.state.value.editor.selectedHolds)

        editor.toggleHold(11)
        editor.toggleBrush(HoldRole.FOOT)
        editor.toggleHold(11)
        assertEquals(mapOf(11 to HoldRole.FOOT), editor.state.value.editor.selectedHolds)

        // Re-tapping the active chip clears the brush; taps then delete.
        editor.toggleBrush(HoldRole.FOOT)
        assertNull(editor.state.value.editor.activeBrush)
        editor.toggleHold(11)
        assertEquals(emptyMap(), editor.state.value.editor.selectedHolds)
        // A brushless tap on an empty hold stays a no-op.
        editor.toggleHold(99)
        assertEquals(emptyMap(), editor.state.value.editor.selectedHolds)
        editor.close()
    }

    @Test
    fun cyclesOneHoldThroughTheBrandPalette() = runBlocking {
        val editor = editor().opened()
        editor.cycleHoldRole(7)
        assertEquals(HoldRole.START, editor.state.value.editor.selectedHolds[7])
        editor.cycleHoldRole(7)
        assertEquals(HoldRole.HAND, editor.state.value.editor.selectedHolds[7])
        editor.cycleHoldRole(7)
        assertEquals(HoldRole.FINISH, editor.state.value.editor.selectedHolds[7])
        editor.cycleHoldRole(7)
        assertEquals(HoldRole.FOOT, editor.state.value.editor.selectedHolds[7])
        editor.cycleHoldRole(7)
        assertFalse(7 in editor.state.value.editor.selectedHolds)
        editor.close()
    }

    @Test
    fun movesTheRoleFromOneHoldToAnother() = runBlocking {
        val editor = editor().opened()
        editor.toggleBrush(HoldRole.FINISH)
        editor.toggleHold(4)
        editor.toggleBrush(HoldRole.HAND)
        editor.toggleHold(5)

        editor.moveHold(4, 5)
        // The moving hold wins the occupied target; the source is released.
        assertEquals(mapOf(5 to HoldRole.FINISH), editor.state.value.editor.selectedHolds)

        // A drop on the same hold and a drag from an empty hold are both no-ops.
        editor.moveHold(5, 5)
        editor.moveHold(42, 43)
        assertEquals(mapOf(5 to HoldRole.FINISH), editor.state.value.editor.selectedHolds)
        editor.close()
    }

    @Test
    fun undoAndRedoWalkTheEditHistory() = runBlocking {
        val editor = editor().opened()
        editor.toggleHold(1)
        editor.toggleHold(2)
        editor.setName("Erste Fassung")
        assertTrue(editor.state.value.canUndo)

        editor.undo()
        assertEquals("", editor.state.value.editor.name)
        assertEquals(setOf(1, 2), editor.state.value.editor.selectedHolds.keys)
        assertTrue(editor.state.value.canRedo)

        editor.undo()
        assertEquals(setOf(1), editor.state.value.editor.selectedHolds.keys)

        editor.redo()
        assertEquals(setOf(1, 2), editor.state.value.editor.selectedHolds.keys)

        // A fresh edit drops the redo branch.
        editor.toggleHold(3)
        assertFalse(editor.state.value.canRedo)
        editor.redo()
        assertEquals(setOf(1, 2, 3), editor.state.value.editor.selectedHolds.keys)

        // The stack is bounded; the oldest states fall off rather than growing.
        repeat(ClimbEditor.UNDO_DEPTH + 10) { editor.setName("Name $it") }
        repeat(ClimbEditor.UNDO_DEPTH + 40) { editor.undo() }
        assertFalse(editor.state.value.canUndo)
        editor.close()
    }

    @Test
    fun validationTracksEveryEditAndBlocksSaving() = runBlocking {
        val editor = editor().opened()
        val initial = editor.state.value.validationIssues
        assertTrue(ClimbValidation.Issue.NoStartHold in initial)
        assertTrue(ClimbValidation.Issue.NoFinishHold in initial)
        assertTrue(ClimbValidation.Issue.TooFewHolds in initial)
        assertTrue(ClimbValidation.Issue.NameMissing in initial)
        // The board seeds a publishable angle and the state a default grade.
        assertFalse(ClimbValidation.Issue.AngleMissing in initial)
        assertFalse(ClimbValidation.Issue.GradeMissing in initial)

        // Saving an invalid climb must not write anything.
        editor.saveAsDraft()
        assertEquals(0, db { database.boardRepo.getDraftClimbs(OWN_PUBKEY, "kilter") }.size)

        editor.paintValidClimb()
        assertEquals(emptyList(), editor.state.value.validationIssues)

        editor.setName("x".repeat(ClimbValidation.NAME_MAX_LENGTH + 1))
        assertTrue(editor.state.value.validationIssues.any { it is ClimbValidation.Issue.NameTooLong })
        editor.close()
    }

    @Test
    fun savesLoadsAndDeletesADraftThroughTheRealDatabase() = runBlocking {
        val editor = editor().opened()
        editor.paintValidClimb()
        editor.setDescription("Kante rechts")
        editor.setSetterGradeId(22)
        editor.setAngle(45)
        editor.saveAsDraft()
        val uuid = awaitValue(true) { editor.state.value.savedDraftUuid != null }
            .let { editor.state.value.savedDraftUuid!! }

        val rows = db { database.boardRepo.getDraftClimbs(OWN_PUBKEY, "kilter") }
        assertEquals(1, rows.size)
        assertEquals("Testaufgabe", rows.single().name)
        assertEquals("p1r12p2r13p3r14", rows.single().framesText)
        assertEquals(OWN_PUBKEY, rows.single().createdByPubkey)
        assertEquals(45 to 22, db { database.boardRepo.getClimbStatsForUuid(uuid) })

        // Re-saving the pinned draft updates the row instead of adding one.
        editor.setName("Testaufgabe v2")
        editor.saveAsDraft()
        awaitValue("Testaufgabe v2") {
            db { database.boardRepo.getDraftClimbs(OWN_PUBKEY, "kilter") }.single().name
        }

        // A second editor restores the draft with its stats.
        val second = editor().opened()
        second.loadDraft(uuid)
        awaitValue(uuid) { second.state.value.loadedDraftUuid }
        assertEquals("Testaufgabe v2", second.state.value.editor.name)
        assertEquals("Kante rechts", second.state.value.editor.description)
        assertEquals(45, second.state.value.editor.angle)
        assertEquals(22, second.state.value.editor.setterGradeId)
        assertEquals(
            mapOf(1 to HoldRole.START, 2 to HoldRole.HAND, 3 to HoldRole.FINISH),
            second.state.value.editor.selectedHolds,
        )
        assertEquals(emptyList(), second.state.value.validationIssues)

        second.deleteDraft(uuid)
        awaitValue(0) { db { database.boardRepo.getDraftClimbs(OWN_PUBKEY, "kilter") }.size }
        assertNull(second.state.value.loadedDraftUuid)
        editor.close()
        second.close()
    }

    @Test
    fun detectsADuplicateByCanonicalFramesHashButNotTheRowItIsEditing() = runBlocking {
        val store = draftStore()
        val existing = ClimbEditorState(
            selectedHolds = mapOf(1 to HoldRole.START, 2 to HoldRole.HAND, 3 to HoldRole.FINISH),
            name = "Original",
            angle = 40,
        )
        val existingUuid = db { store.saveDraft(existing, layoutId = 1L) }
        assertNotNull(existingUuid)

        val editor = editor().opened()
        // Painted in a different order: the hash is order-independent.
        editor.toggleBrush(HoldRole.FINISH); editor.toggleHold(3)
        editor.toggleBrush(HoldRole.START); editor.toggleHold(1)
        editor.toggleBrush(HoldRole.HAND); editor.toggleHold(2)
        editor.setName("Zufaellig gleich")
        editor.checkForDuplicate()
        awaitValue(existingUuid) { editor.state.value.duplicateOf?.uuid }

        // A different hold map is not a duplicate.
        editor.toggleBrush(HoldRole.FOOT)
        editor.toggleHold(9)
        editor.checkForDuplicate()
        awaitValue(null) { editor.state.value.duplicateOf?.uuid }

        // Editing the very row that owns the hash is not a duplicate either.
        editor.toggleHold(9)
        editor.loadDraft(existingUuid)
        awaitValue(existingUuid) { editor.state.value.loadedDraftUuid }
        editor.checkForDuplicate()
        awaitValue(null) { editor.state.value.duplicateOf?.uuid }
        editor.close()
    }

    @Test
    fun clearingTheEditorUnpinsTheLoadedDraftWithoutDeletingIt() = runBlocking {
        val editor = editor().opened()
        editor.paintValidClimb()
        editor.saveAsDraft()
        awaitValue(true) { editor.state.value.savedDraftUuid != null }

        editor.clearEditor()
        assertEquals(emptyMap(), editor.state.value.editor.selectedHolds)
        assertNull(editor.state.value.loadedDraftUuid)
        assertEquals(1, db { database.boardRepo.getDraftClimbs(OWN_PUBKEY, "kilter") }.size)

        // The wipe itself is undoable.
        editor.undo()
        assertEquals(3, editor.state.value.editor.selectedHolds.size)
        editor.close()
    }

    private companion object {
        val OWN_PUBKEY = "aa".repeat(32)
    }
}
