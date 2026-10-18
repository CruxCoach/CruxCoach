package com.cruxcoach.app.training

import com.cruxcoach.app.ui.ClimbingDayScreenModel
import com.cruxcoach.athlete.logic.BoardOverlap
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ClimbingDayEntry
import com.cruxcoach.athlete.model.ClimbingDayKind
import com.cruxcoach.athlete.model.ClimbingDaySource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Device test D45 (port of Android's ClimbingTileTest case and `AthleteService.deleteClimbingDayForUndo` /
 * `restoreClimbingDay`): a deleted climbing day comes back with "Undo", with its board-overlap choice.
 */
class ClimbingDayUndoTest {
    private lateinit var kit: TrainingKit
    private val repo get() = kit.repo
    private val service get() = kit.service

    @BeforeTest
    fun setUp() { kit = TrainingKit() }

    @AfterTest
    fun tearDown() = kit.close()

    private fun entry(id: String, minutes: Int) =
        ClimbingDayEntry(id, service.today().toString(), ClimbingDayKind.GYM_BOULDER, minutes, ClimbIntensity.HARD)

    @Test
    fun `a deleted climbing day comes back with Undo and its board overlap choice too`() {
        kit.standardAthlete()
        val overlapKey = BoardOverlap.key("gym")
        val overlap = BoardOverlap.Stored(40, countAnyway = true).encode()
        kit.db {
            service.saveClimbingDay(entry("gym", 90))
            repo.putSetting(overlapKey, overlap)
        }
        val today = TodayPresenter(service, kit.scope(), kit.dispatcher)
        kit.until("tile") { today.state.value.climbingEntriesToday == listOf("gym") }

        val sheet = ClimbingDayPresenter(service, kit.scope(), kit.dispatcher)
        val model = ClimbingDayScreenModel(sheet)
        model.load("gym")
        kit.until("form") { !sheet.state.value.loading }
        assertTrue(model.currentState.isEdit)
        assertNull(model.lastDeleted())
        model.delete()
        kit.until("deleted") { repo.climbingDay("gym") == null }
        kit.until("done") { model.currentState.done }
        // The entry's overlap choice went with it.
        assertTrue(kit.db { repo.setting(overlapKey) }.isNullOrEmpty())
        val undo = assertNotNull(model.lastDeleted())
        model.consumeDone()
        assertNull(model.lastDeleted())
        // The sheet is gone before the host offers "Undo": its scope has ended.
        model.close()
        kit.until("tile empty") { today.state.value.climbingEntriesToday.isEmpty() }

        undo.restore()
        kit.until("restored") { repo.climbingDay("gym") != null }
        assertEquals(90, kit.db { repo.climbingDay("gym")!!.minutes })
        assertEquals(overlap, kit.db { repo.setting(overlapKey) })
        kit.until("tile back") { today.state.value.climbingEntriesToday == listOf("gym") }
    }

    @Test
    fun `undo brings an entry back unchanged and writes no overlap it did not have`() {
        val synced = entry("hc", 75).copy(source = ClimbingDaySource.HEALTH_CONNECT, externalId = "ext-1", note = "Halle")
        kit.db { service.ensureReady(); service.saveClimbingDay(synced) }
        val stored = kit.db { repo.climbingDay("hc")!! }
        // Nothing to delete: nothing to undo.
        assertNull(kit.db { service.deleteClimbingDayForUndo("missing") })
        val deleted = assertNotNull(kit.db { service.deleteClimbingDayForUndo("hc") })
        assertNull(deleted.overlap)
        assertNull(kit.db { repo.climbingDay("hc") })
        kit.db { service.restoreClimbingDay(deleted) }
        // Exactly as it was, its last change included.
        assertEquals(stored, kit.db { repo.climbingDay("hc") })
        assertNull(kit.db { repo.setting(BoardOverlap.key("hc")) })
    }
}
