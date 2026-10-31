package com.cruxcoach.domain.spraywall

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SprayClimbHoldsTest {
    @Test
    fun encodesSortedByHoldIdAndReadsItBack() {
        val holds = mapOf(13L to SprayHoldRole.HAND, 2L to SprayHoldRole.START, 40L to SprayHoldRole.FINISH, 7L to SprayHoldRole.FOOT)
        val text = SprayClimbHolds.encode(holds)
        assertEquals("2:start;7:foot;13:hand;40:finish", text)
        assertEquals(holds, SprayClimbHolds.decode(text))
    }

    @Test
    fun skipsMalformedPairsInsteadOfDroppingTheClimb() {
        assertEquals(
            mapOf(1L to SprayHoldRole.START, 3L to SprayHoldRole.FINISH),
            SprayClimbHolds.decode("1:start;x:hand;2:wing;3:finish;;4"),
        )
        assertEquals(emptyMap(), SprayClimbHolds.decode(""))
    }
}

class SprayClimbRulesTest {
    private val wall = setOf(1L, 2L, 3L, 4L, 5L)

    @Test
    fun tapWithTheSameRoleTakesTheHoldOff() {
        val holds = mapOf(1L to SprayHoldRole.HAND)
        assertEquals(emptyMap(), SprayClimbRules.tap(holds, 1L, SprayHoldRole.HAND))
    }

    @Test
    fun tapWithAnotherRoleRepaintsTheHold() {
        val holds = mapOf(1L to SprayHoldRole.HAND)
        assertEquals(mapOf(1L to SprayHoldRole.FOOT), SprayClimbRules.tap(holds, 1L, SprayHoldRole.FOOT))
    }

    @Test
    fun aThirdStartHoldIsRefused() {
        val holds = mapOf(1L to SprayHoldRole.START, 2L to SprayHoldRole.START)
        assertNull(SprayClimbRules.tap(holds, 3L, SprayHoldRole.START))
        // Repainting one of the two start holds is still fine.
        assertNotNull(SprayClimbRules.tap(holds, 2L, SprayHoldRole.HAND))
    }

    @Test
    fun aClimbNeedsANameAStartAndAFinish() {
        val problems = SprayClimbRules.problems(" ", mapOf(1L to SprayHoldRole.HAND), wall)
        assertTrue(SprayClimbRules.Problem.NAME_MISSING in problems)
        assertTrue(SprayClimbRules.Problem.NO_START in problems)
        assertTrue(SprayClimbRules.Problem.NO_FINISH in problems)
        assertTrue(SprayClimbRules.Problem.TOO_FEW_HOLDS in problems)
    }

    @Test
    fun aCompleteClimbHasNoProblems() {
        val holds = mapOf(1L to SprayHoldRole.START, 2L to SprayHoldRole.HAND, 3L to SprayHoldRole.FINISH)
        assertEquals(emptyList(), SprayClimbRules.problems("Crimp line", holds, wall))
    }

    @Test
    fun aClimbKeepsAHoldThatCameOffButCannotGainOne() {
        val holds = mapOf(1L to SprayHoldRole.START, 9L to SprayHoldRole.HAND, 3L to SprayHoldRole.FINISH)
        // Hold 9 is gone; editing a climb that already had it is fine …
        assertEquals(emptyList(), SprayClimbRules.problems("Old line", holds, wall, originalHolds = setOf(1L, 9L, 3L)))
        // … adding it to a climb is not.
        assertEquals(listOf(SprayClimbRules.Problem.HOLD_GONE), SprayClimbRules.problems("New line", holds, wall))
    }

    @Test
    fun countsHoldsThatAreNoLongerOnTheWall() {
        val climb = climb("a", mapOf(1L to SprayHoldRole.START, 8L to SprayHoldRole.HAND, 9L to SprayHoldRole.FINISH))
        assertEquals(2, SprayClimbRules.lostHoldCount(climb, wall))
    }
}

class SprayClimbListTest {
    private val wall = setOf(1L, 2L, 3L)
    private val intact = climb("intact", mapOf(1L to SprayHoldRole.START, 2L to SprayHoldRole.FINISH), name = "Bravo", difficulty = 20, createdAt = 2)
    private val broken = climb("broken", mapOf(1L to SprayHoldRole.START, 7L to SprayHoldRole.FINISH), name = "alpha", difficulty = null, createdAt = 3)
    private val easy = climb("easy", mapOf(2L to SprayHoldRole.START, 3L to SprayHoldRole.FINISH), name = "Charlie", difficulty = 12, createdAt = 1)
    private val all = listOf(intact, broken, easy)

    @Test
    fun filtersByWhetherHoldsAreGone() {
        val lost = SprayClimbList.rows(all, wall, emptyMap(), SprayClimbFilter.LOST_HOLDS, SprayClimbSort.NEWEST)
        assertEquals(listOf("broken"), lost.map { it.climb.uuid })
        assertEquals(1, lost.single().lostHolds)
        val ok = SprayClimbList.rows(all, wall, emptyMap(), SprayClimbFilter.INTACT, SprayClimbSort.NEWEST)
        assertEquals(listOf("intact", "easy"), ok.map { it.climb.uuid })
    }

    @Test
    fun sortsByGradeWithProjectsLast() {
        val rows = SprayClimbList.rows(all, wall, emptyMap(), SprayClimbFilter.ALL, SprayClimbSort.GRADE)
        assertEquals(listOf("easy", "intact", "broken"), rows.map { it.climb.uuid })
    }

    @Test
    fun sortsByNameIgnoringCaseAndSearches() {
        assertEquals(
            listOf("broken", "intact", "easy"),
            SprayClimbList.rows(all, wall, emptyMap(), SprayClimbFilter.ALL, SprayClimbSort.NAME).map { it.climb.uuid },
        )
        assertEquals(
            listOf("easy"),
            SprayClimbList.rows(all, wall, emptyMap(), SprayClimbFilter.ALL, SprayClimbSort.NAME, query = "char").map { it.climb.uuid },
        )
    }

    @Test
    fun carriesTheSendCount() {
        val rows = SprayClimbList.rows(all, wall, mapOf("easy" to 3), SprayClimbFilter.ALL, SprayClimbSort.NEWEST)
        assertEquals(3, rows.first { it.climb.uuid == "easy" }.sends)
        assertEquals(0, rows.first { it.climb.uuid == "intact" }.sends)
    }
}

class SprayHoldEditorTest {
    private fun hold(id: Long, x: Float = 0.5f, y: Float = 0.5f, r: Float = 0.02f, removedAt: Long? = null) =
        SprayHold("w", id, x, y, r, addedAt = 0, removedAt = removedAt)

    @Test
    fun startsFromTheHoldsOnTheWallOnly() {
        val state = SprayHoldEditorState.from(listOf(hold(1), hold(2, removedAt = 5)))
        assertEquals(listOf(1L), state.holds.map { it.id })
    }

    @Test
    fun addPicksTheNewHoldAndUsesTheLastSize() {
        val state = SprayHoldEditorState.from(listOf(hold(1, r = 0.04f)))
            .add(0.1f, 0.2f)
        val added = state.selected
        assertNotNull(added)
        assertTrue(added.id < 0)
        assertEquals(0.04f, added.radius)
        assertEquals(0.1f, added.x)
    }

    @Test
    fun aDragIsOneUndoStep() {
        val start = SprayHoldEditorState(holds = listOf(EditableHold(1, 0.5f, 0.5f, 0.02f))).select(1)
        val moved = start.beginMove().moveSelected(0.6f, 0.6f).moveSelected(0.7f, 0.7f).moveSelected(1.4f, -1f)
        assertEquals(1f, moved.selected!!.x)
        assertEquals(0f, moved.selected!!.y)
        val undone = moved.undo()
        assertEquals(0.5f, undone.holds.single().x)
        assertFalse(undone.canUndo)
        assertEquals(1f, undone.redo().holds.single().x)
    }

    @Test
    fun resizeIsClampedAndRemembered() {
        val state = SprayHoldEditorState(holds = listOf(EditableHold(1, 0.5f, 0.5f, 0.02f))).select(1)
            .beginResize().resizeSelected(5f)
        assertEquals(SprayHoldEditorState.MAX_RADIUS, state.selected!!.radius)
        assertEquals(SprayHoldEditorState.MAX_RADIUS, state.add(0.1f, 0.1f).selected!!.radius)
    }

    @Test
    fun deleteAndUndoBringTheHoldBack() {
        val state = SprayHoldEditorState(holds = listOf(EditableHold(1, 0.5f, 0.5f, 0.02f))).select(1).deleteSelected()
        assertTrue(state.holds.isEmpty())
        assertNull(state.selectedId)
        assertEquals(listOf(1L), state.undo().holds.map { it.id })
    }

    @Test
    fun aNewActionClearsRedo() {
        val state = SprayHoldEditorState(holds = emptyList()).add(0.1f, 0.1f).undo()
        assertTrue(state.canRedo)
        assertFalse(state.add(0.2f, 0.2f).canRedo)
    }

    @Test
    fun theDiffSeesAddedChangedAndRemovedHolds() {
        val original = listOf(hold(1), hold(2), hold(3), hold(4, removedAt = 9))
        val edited = listOf(
            EditableHold(1, 0.5f, 0.5f, 0.02f), // unchanged
            EditableHold(2, 0.6f, 0.5f, 0.02f), // moved
            EditableHold(-1, 0.1f, 0.1f, 0.02f), // new
        )
        val diff = SprayHoldDiff.between(original, edited)
        assertEquals(listOf(-1L), diff.added.map { it.id })
        assertEquals(listOf(2L), diff.changed.map { it.id })
        // Hold 4 was already gone; only hold 3 comes off now.
        assertEquals(setOf(3L), diff.removedIds)
        assertTrue(SprayHoldDiff.between(original, SprayHoldEditorState.from(original).holds).isEmpty)
    }
}

class SprayWallGeometryTest {
    @Test
    fun fitsAPortraitPhotoIntoALandscapeView() {
        val fit = SprayWallGeometry.fit(1000f, 500f, 300, 600)
        assertEquals(250f, fit.width)
        assertEquals(500f, fit.height)
        assertEquals(375f, fit.left)
        assertEquals(0f, fit.top)
    }

    @Test
    fun mapsScreenPointsToThePhotoThroughZoomAndPan() {
        val fit = SprayWallGeometry.fit(1000f, 1000f, 1000, 1000)
        assertEquals(0.5f to 0.5f, SprayWallGeometry.toPhoto(500f, 500f, fit, 1f, 0f, 0f))
        // Zoomed 2x and shifted so the photo's centre sits at the view's top-left.
        assertEquals(0.5f to 0.5f, SprayWallGeometry.toPhoto(0f, 0f, fit, 2f, -1000f, -1000f))
        assertNull(SprayWallGeometry.toPhoto(1200f, 500f, fit, 1f, 0f, 0f))
        assertEquals(1f to 0.5f, SprayWallGeometry.toPhotoClamped(1200f, 500f, fit, 1f, 0f, 0f))
    }

    @Test
    fun picksTheNearestHoldWithinReach() {
        val fit = SprayWallGeometry.fit(1000f, 1000f, 1000, 1000)
        val holds = listOf(EditableHold(1, 0.50f, 0.5f, 0.02f), EditableHold(2, 0.53f, 0.5f, 0.02f))
        val hit = SprayWallGeometry.holdAt(holds, 0.52f, 0.5f, fit, 1f, 0f, { it.x }, { it.y }, { it.radius })
        assertEquals(2L, hit?.id)
        assertNull(SprayWallGeometry.holdAt(holds, 0.9f, 0.9f, fit, 1f, 10f, { it.x }, { it.y }, { it.radius }))
    }

    @Test
    fun keepsAZoomedPhotoInView() {
        assertEquals(0f to 0f, SprayWallGeometry.clampPan(50f, 50f, 2f, 100f, 100f))
        assertEquals(-100f to -100f, SprayWallGeometry.clampPan(-500f, -500f, 2f, 100f, 100f))
        assertEquals(0f to 0f, SprayWallGeometry.clampPan(-20f, 0f, 1f, 100f, 100f))
    }

    @Test
    fun shrinksLargePhotosOnTheirLongSide() {
        assertEquals(3072 to 2304, SprayWallGeometry.importSize(4000, 3000, 3072))
        assertEquals(800 to 600, SprayWallGeometry.importSize(800, 600, 3072))
    }
}

internal fun climb(
    uuid: String,
    holds: Map<Long, SprayHoldRole>,
    name: String = uuid,
    difficulty: Int? = 20,
    createdAt: Long = 0,
) = SprayClimb(uuid, "w", name, "", difficulty, holds, createdAt, createdAt)
