package com.cruxcoach.app.nearby

import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Android `BoardStateManager` for this phone's own sends: dedup-free record, restore, 30-minute staleness. */
class LocalBoardClimbMemoryTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scopes = mutableListOf<CoroutineScope>()
    private val store = MemoryKeyValueStore()
    /** Wall clock in the far future so persisted timestamps look real. */
    private val epoch = 1_800_000_000_000L

    @AfterTest
    fun tearDown() = scopes.forEach { it.cancel() }

    private fun memory(): LocalBoardClimbMemory {
        val scope = CoroutineScope(SupervisorJob() + dispatcher).also { scopes += it }
        return LocalBoardClimbMemory(store, { epoch + scheduler.currentTime }, scope)
    }

    private fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    @Test
    fun `a confirmed send is live on its link until the link ends`() {
        val m = memory()
        assertNull(m.state.value)
        m.record("AC9F", 35, "Alpha", projectionSurvivesDisconnect = true, boardIdentifier = "board-1")
        val sent = assertNotNull(m.state.value)
        assertEquals("AC9F", sent.uuid)
        assertEquals(35, sent.angle)
        assertEquals("Alpha", sent.name)
        assertEquals("board-1", sent.liveOnBoard)

        m.linkEnded()
        assertEquals("", m.state.value?.liveOnBoard, "the climb stays, no longer live")
        assertEquals("AC9F", m.state.value?.uuid)
    }

    @Test
    fun `a restart restores the climb not live and with its original age`() {
        memory().record("AC9F", 40, "Alpha", projectionSurvivesDisconnect = false, boardIdentifier = "board-1")
        advance(10 * 60_000L)

        val restored = memory()
        val climb = assertNotNull(restored.state.value)
        assertEquals("AC9F", climb.uuid)
        assertEquals("", climb.liveOnBoard)
        assertFalse(climb.projectionSurvivesDisconnect)
        assertEquals(epoch, climb.sentAtMs, "not re-aged by the restore")

        // The remaining 20 minutes of the original 30, not another 30.
        advance(20 * 60_000L - 1)
        assertNotNull(restored.state.value)
        advance(1)
        assertNull(restored.state.value)
    }

    @Test
    fun `an idle wall stops being reported after 30 minutes and a stale climb is not restored`() {
        val m = memory()
        m.record("AC9F", 40, "", projectionSurvivesDisconnect = true, boardIdentifier = "board-1")
        advance(LocalBoardClimbMemory.STALE_MS - 1)
        assertNotNull(m.state.value)
        // A new send starts the 30 minutes again.
        m.record("D0E5", 40, "", projectionSurvivesDisconnect = true, boardIdentifier = "board-1")
        advance(LocalBoardClimbMemory.STALE_MS - 1)
        assertEquals("D0E5", m.state.value?.uuid)
        advance(1)
        assertNull(m.state.value)

        assertNull(memory().state.value, "older than 30 minutes: not restored")
    }

    @Test
    fun `a blank id or a corrupt store records and restores nothing`() {
        val m = memory()
        m.record("  ", 40, "", projectionSurvivesDisconnect = true, boardIdentifier = "board-1")
        assertNull(m.state.value)
        store.putString("ios_board_last_climb_uuid", "AC9F")
        store.putString("ios_board_last_climb_at", "not a number")
        assertNull(memory().state.value)
    }
}
