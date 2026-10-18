package com.cruxcoach.app.quantum

import com.cruxcoach.app.browse.BrowsePreferences
import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import com.cruxcoach.app.creator.ClimbDraftStore
import com.cruxcoach.app.creator.ClimbEditor
import com.cruxcoach.app.render.QuantumRender
import com.cruxcoach.app.sync.ImportResult
import com.cruxcoach.app.sync.ImporterFixture
import com.cruxcoach.app.sync.QuantumSnapshots
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.app.ui.CreatorScreenModel
import com.cruxcoach.app.ui.GradeFormatter
import com.cruxcoach.data.repository.BoardRepositoryImpl
import com.cruxcoach.domain.board.QuantumBoardModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The climb editor on a Quantum board: holds on the model's own mapping, taps within its touch radius. */
@OptIn(ExperimentalCoroutinesApi::class)
class QuantumCreatorTest {
    private val fx = ImporterFixture().also {
        requireImported(it.importer.importQuantumSnapshot(it.source("q.sqlite3", *QuantumSnapshots.MINIMAL)))
    }
    private val repository = BoardRepositoryImpl(fx.handle.database)
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    private fun requireImported(result: ImportResult) = assertTrue(result is ImportResult.Imported)

    private fun model(): CreatorScreenModel {
        val store = MemoryKeyValueStore().apply {
            putString(BrowsePreferences.BOARD_BRAND, "quantum")
            putString(BrowsePreferences.BOARD_LAYOUT_ID, "9103")
            putString(BrowsePreferences.BOARD_PRODUCT_SIZE_ID, "9203")
            putString(BrowsePreferences.BOARD_ANGLE, "40")
        }
        val editor = ClimbEditor(
            boardRepository = repository,
            drafts = ClimbDraftStore(repository, JvmHashing, FixedClock(1_790_000_000L)) { null },
            preferences = BrowsePreferences(store),
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            ioDispatcher = dispatcher,
        )
        return CreatorScreenModel(editor, GradeFormatter(false), dispatcher)
    }

    @Test
    fun `holds sit on the model mapping over a square image and taps pick the nearest diode`() = scope.runTest {
        val m = model()
        m.open(); advanceUntilIdle()
        val d2 = QuantumRender.normalized(QuantumBoardModel.M, 2_000, 2_000)

        // The three test diodes are ~0.013 apart: a tap just beside d2 must pick d2, not its neighbours.
        m.tapBoard(d2.x + 0.001f, d2.y + 0.001f); advanceUntilIdle()

        val s = m.currentState
        assertEquals(1f, s.boardAspect)
        assertEquals(listOf(3000002), s.holds.map { it.placementId })
        assertEquals(d2.x, s.holds.single().x, 1e-6f)
        assertEquals(d2.y, s.holds.single().y, 1e-6f)

        // Far outside every diode's 50 dp radius: nothing is picked.
        m.tapBoard(0.95f, 0.05f); advanceUntilIdle()
        assertEquals(1, m.currentState.holds.size)
        assertFalse(m.currentState.holds.any { it.placementId != 3000002 })
        m.close()
    }
}
