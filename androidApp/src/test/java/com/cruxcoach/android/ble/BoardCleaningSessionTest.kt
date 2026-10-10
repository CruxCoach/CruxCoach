package com.cruxcoach.android.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BoardCleaningSessionTest {
    private val oldClimb = listOf(byteArrayOf(10, 20))
    private val cleaningClimb = listOf(byteArrayOf(30, 40))
    private val clear = listOf(byteArrayOf(0))
    private fun session() = BoardCleaningSession(today = { "2026-10-10" }).apply { attach("kilter-A") }
    private fun BoardCleaningSession.project(vararg positions: Int) = projected(ticket(), positions.toSet(), oldClimb)
    private suspend fun BoardCleaningSession.light() = start({ cleaningClimb }) { true }
    private suspend fun BoardCleaningSession.stop(cleaned: Boolean) = finish(cleaned, { clear }) { true }

    @Test fun `collects union of projected holds and persists across restart and board switches`() = runTest {
        var saved = emptyMap<String, CleaningDay>()
        val session = BoardCleaningSession(save = { saved = it }, today = { "2026-10-10" })
        session.attach("kilter-A")
        session.project(1, 2, 3)
        session.project(2, 4)
        assertEquals(4, session.state.value.holdCount)
        session.attach("kilter-B")
        assertEquals(0, session.state.value.holdCount)
        session.project(88)
        val restarted = BoardCleaningSession(saved, today = { "2026-10-10" })
        restarted.attach("kilter-A")
        var highlighted = emptySet<Int>()
        assertTrue(restarted.start({ highlighted = it; cleaningClimb }) { true })
        assertEquals(setOf(1, 2, 3, 4), highlighted)
        restarted.attach("kilter-B")
        assertEquals(1, restarted.state.value.holdCount)
    }

    @Test fun `stop restores bytes without counting cleaning and keeps collection`() = runTest {
        val session = session()
        session.project(1, 2)
        assertTrue(session.light())
        session.project(99) // A competing producer must never contaminate it.
        var restored = emptyList<ByteArray>()
        assertTrue(session.finish(false, { clear }) { restored = it; true })
        assertArrayEquals(oldClimb.single(), restored.single())
        assertEquals(2, session.state.value.holdCount)
        assertFalse(session.state.value.active)
    }

    @Test fun `cleaned resets only current board and next climb starts fresh`() = runTest {
        val session = session()
        session.project(1, 2)
        session.attach("kilter-B")
        session.project(8)
        session.attach("kilter-A")
        session.light()
        assertTrue(session.stop(true))
        assertEquals(0, session.state.value.holdCount)
        session.project(3)
        assertEquals(1, session.state.value.holdCount)
        session.attach("kilter-B")
        assertEquals(1, session.state.value.holdCount)
    }

    @Test fun `failed start or restore preserves collection and allows retry`() = runTest {
        val session = session()
        session.project(1, 2)
        assertFalse(session.start({ cleaningClimb }) { false })
        assertTrue(session.state.value.active)
        assertFalse(session.state.value.busy)
        assertFalse(session.finish(true, { clear }) { false })
        assertTrue(session.state.value.active)
        assertEquals(2, session.state.value.holdCount)
        assertTrue(session.light())
        assertTrue(session.stop(true))
        assertEquals(0, session.state.value.holdCount)
    }

    @Test fun `disconnection during restore cannot clear saved holds or restore another board`() = runTest {
        val session = session()
        session.project(1, 2)
        session.light()
        val pending = CompletableDeferred<Boolean>()
        val started = CompletableDeferred<Unit>()
        val ending = async {
            session.finish(true, { clear }) { started.complete(Unit); pending.await() }
        }
        started.await()
        session.attach(null)
        session.attach("kilter-A") // Even the same controller is a new GATT generation.
        pending.complete(true)
        assertFalse(ending.await())
        assertFalse(session.state.value.active)
        assertEquals(2, session.state.value.holdCount)
    }

    @Test fun `stale normal projection cannot enter another controller collection`() {
        val session = session()
        val ticket = session.ticket()
        session.attach("kilter-B")
        session.projected(ticket, setOf(8), oldClimb)
        assertEquals(0, session.state.value.holdCount)
    }

    @Test fun `day rollover hides yesterday but does not interrupt active cleaning`() = runTest {
        var date = "2026-10-10"
        val session = BoardCleaningSession(today = { date })
        session.attach("kilter-A")
        session.project(1, 2)
        session.light()
        date = "2026-10-11"
        session.refresh()
        assertEquals(2, session.state.value.holdCount)
        assertTrue(session.state.value.active)
        session.stop(false)
        assertEquals(0, session.state.value.holdCount)
        assertFalse(session.light())
        session.project(3)
        assertEquals(1, session.state.value.holdCount)
    }

    @Test fun `empty unsupported and disconnected targets never write`() = runTest {
        val session = session()
        assertFalse(session.start({ error("No encoding expected") }) { error("No write expected") })
        session.project(1)
        session.attach(null)
        assertFalse(session.light())
        assertFalse(session.stop(true))
    }

    @Test fun `after restart or unknown relay bytes finishing clears instead of restoring stale climb`() = runTest {
        val session = session()
        session.project(1)
        session.forgetProjection(session.ticket())
        session.light()
        var restored = emptyList<ByteArray>()
        session.finish(false, { clear }) { restored = it; true }
        assertArrayEquals(clear.single(), restored.single())
        assertEquals(1, session.state.value.holdCount)
    }
}
