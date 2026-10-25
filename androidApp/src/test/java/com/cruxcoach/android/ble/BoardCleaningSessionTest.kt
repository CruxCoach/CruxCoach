package com.cruxcoach.android.ble

import java.time.LocalDateTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BoardCleaningSessionTest {
    private val oldClimb = listOf(byteArrayOf(10, 20))
    private val cleaningClimb = listOf(byteArrayOf(30, 40))
    private val clear = listOf(byteArrayOf(0))
    private var clock = LocalDateTime.parse("2026-10-10T18:00")
    private var saved = emptyMap<String, CleaningMark>()

    private fun session(marks: Map<String, CleaningMark> = emptyMap(), key: String = "kilter-A", limit: Int = Int.MAX_VALUE) =
        BoardCleaningSession(marks, { saved = it }, now = { clock }).apply { attach(key, limit) }

    /** A logbook entry of 2026-10-10 at [time] whose climb lights [positions]. */
    private fun at(time: String, vararg positions: Int, date: String = "2026-10-10") =
        LoggedHolds(LocalDateTime.parse("${date}T$time"), positions.toSet())

    private fun BoardCleaningSession.log(vararg entries: LoggedHolds) =
        logged(ticket(), clock.toLocalDate().toString(), entries.toList())

    private fun BoardCleaningSession.project() = projected(ticket(), oldClimb)
    private suspend fun BoardCleaningSession.light() = start({ cleaningClimb }) { true }
    private suspend fun BoardCleaningSession.stop(cleaned: Boolean) = finish(cleaned, { clear }) { true }
    private val BoardCleaningSession.count get() = state.value.holdCount

    @Test fun `collection is the union of today's logged holds and sent climbs add nothing`() = runTest {
        val session = session()
        session.project()
        assertEquals(0, session.count)
        session.log(at("16:00", 1, 2, 3), at("17:00", 2, 4))
        assertEquals(4, session.count)
        session.project() // Sending (or auto-sending while browsing) is not using the holds.
        assertEquals(4, session.count)
        var highlighted = emptySet<Int>()
        assertTrue(session.start({ highlighted = it; cleaningClimb }) { true })
        assertEquals(setOf(1, 2, 3, 4), highlighted)
    }

    @Test fun `loading until the logbook is read and a read for an old connection is dropped`() {
        val session = session()
        assertTrue(session.state.value.loading)
        val ticket = session.ticket()
        session.attach("kilter-B")
        session.logged(ticket, "2026-10-10", listOf(at("17:00", 8)))
        assertTrue(session.state.value.loading)
        assertEquals(0, session.count)
        session.log()
        assertFalse(session.state.value.loading)
        assertFalse(session.state.value.allCleaned) // Nothing logged is not "all clean".
        session.attach(null)
        session.logged(session.ticket(), "2026-10-10", listOf(at("17:00", 8)))
        assertEquals(0, session.count)
        assertFalse(session.state.value.available)
    }

    @Test fun `cleaned keeps only later entries, survives a restart and stays per controller`() = runTest {
        val session = session()
        val morning = at("17:00", 1, 2)
        session.log(morning)
        session.light()
        assertTrue(session.stop(true))
        assertEquals(0, session.count)
        assertTrue(session.state.value.allCleaned)
        assertEquals(CleaningMark("2026-10-10", "2026-10-10T18:00", setOf(1, 2)), saved.values.single())

        clock = LocalDateTime.parse("2026-10-10T18:30")
        val evening = at("18:20", 2, 5) // Hold 2 was used again after the brush.
        session.log(morning, evening)
        assertEquals(2, session.count)
        assertFalse(session.state.value.allCleaned)

        val restarted = session(saved)
        restarted.log(morning, evening)
        assertEquals(2, restarted.count)
        val otherBoard = session(saved, key = "kilter-B")
        otherBoard.log(morning, evening)
        assertEquals(3, otherBoard.count)
    }

    @Test fun `stop restores bytes and keeps the collection`() = runTest {
        val session = session()
        session.project()
        session.log(at("17:00", 1, 2))
        assertTrue(session.light())
        session.project() // A competing producer must never replace the restore point.
        var restored = emptyList<ByteArray>()
        assertTrue(session.finish(false, { clear }) { restored = it; true })
        assertArrayEquals(oldClimb.single(), restored.single())
        assertEquals(2, session.count)
        assertFalse(session.state.value.active)
        assertTrue(saved.isEmpty())
    }

    @Test fun `failed start or restore preserves the collection and allows retry`() = runTest {
        val session = session()
        session.log(at("17:00", 1, 2))
        assertFalse(session.start({ cleaningClimb }) { false })
        assertTrue(session.state.value.active)
        assertFalse(session.state.value.busy)
        assertFalse(session.finish(true, { clear }) { false })
        assertTrue(session.state.value.active)
        assertEquals(2, session.count)
        assertTrue(saved.isEmpty())
        assertTrue(session.light())
        assertTrue(session.stop(true))
        assertEquals(0, session.count)
    }

    @Test fun `disconnection during restore cannot mark holds cleaned or restore another board`() = runTest {
        val session = session()
        session.log(at("17:00", 1, 2))
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
        assertTrue(saved.isEmpty())
        session.log(at("17:00", 1, 2))
        assertEquals(2, session.count)
    }

    @Test fun `deleted entries leave the collection at the next read`() {
        val session = session()
        session.log(at("16:00", 1, 2), at("17:00", 3))
        assertEquals(3, session.count)
        session.log(at("16:00", 1, 2))
        assertEquals(2, session.count)
        session.log()
        assertEquals(0, session.count)
    }

    @Test fun `an entry that arrives during a round keeps its holds after cleaned`() = runTest {
        val session = session()
        session.log(at("17:00", 1, 2))
        session.light()
        // An import of an earlier climb, read while the board shows the round.
        session.log(at("17:00", 1, 2), at("10:00", 2, 7))
        assertEquals(2, session.count) // The round keeps its own holds.
        assertTrue(session.stop(true))
        assertEquals(1, session.count) // Hold 7 was never lit; hold 2 was brushed.
    }

    @Test fun `an entry stamped ahead of the clock is still covered by cleaned`() = runTest {
        val session = session()
        session.log(at("18:05", 4))
        session.light()
        session.stop(true)
        assertEquals(0, session.count)
    }

    @Test fun `day rollover shows a new collection but does not interrupt active cleaning`() = runTest {
        clock = LocalDateTime.parse("2026-10-10T23:50")
        val session = session()
        session.log(at("22:00", 1, 2))
        session.light()
        clock = LocalDateTime.parse("2026-10-11T00:10")
        session.refresh()
        assertEquals(2, session.count)
        assertTrue(session.state.value.active)
        session.stop(false)
        assertEquals(0, session.count) // Yesterday's read no longer counts.
        assertFalse(session.light())
        session.log(at("00:05", 3, date = "2026-10-11"))
        assertEquals(1, session.count)
    }

    @Test fun `a round from before midnight marked cleaned leaves today's collection alone`() = runTest {
        clock = LocalDateTime.parse("2026-10-10T23:50")
        val session = session()
        session.log(at("22:00", 1, 2))
        session.light()
        clock = LocalDateTime.parse("2026-10-11T00:10")
        session.log(at("00:05", 1, date = "2026-10-11"))
        assertTrue(session.stop(true))
        assertTrue(saved.isEmpty())
        assertEquals(1, session.count)
    }

    @Test fun `marks of other days are dropped when a new mark is saved`() = runTest {
        val yesterday = mapOf("kilter-B" to CleaningMark("2026-10-09", "2026-10-09T20:00", setOf(9)))
        val session = session(yesterday)
        session.log(at("17:00", 1))
        session.light()
        session.stop(true)
        assertEquals(setOf("kilter-A"), saved.keys)
    }

    @Test fun `a yesterday mark does not hide today's entries`() {
        val yesterday = mapOf("kilter-A" to CleaningMark("2026-10-09", "2026-10-09T23:59", setOf(1, 2)))
        val session = session(yesterday)
        session.log(at("09:00", 1, 2))
        assertEquals(2, session.count)
    }

    @Test fun `empty, unsupported and disconnected targets never write`() = runTest {
        val session = session()
        session.log()
        assertFalse(session.start({ error("No encoding expected") }) { error("No write expected") })
        session.log(at("17:00", 1))
        session.attach(null)
        assertFalse(session.light())
        assertFalse(session.stop(true))
    }

    @Test fun `after a restart or unknown relay bytes finishing clears instead of restoring a stale climb`() = runTest {
        val session = session()
        session.project()
        session.forgetProjection(session.ticket())
        session.log(at("17:00", 1))
        session.light()
        var restored = emptyList<ByteArray>()
        session.finish(false, { clear }) { restored = it; true }
        assertArrayEquals(clear.single(), restored.single())
        assertEquals(1, session.count)
    }

    @Test fun `legacy sections advance only after confirmation and restore after the final section`() = runTest {
        val session = session(key = "legacy", limit = 2)
        session.project()
        session.log(at("17:00", 1, 2, 3), at("17:30", 4, 5))
        val sections = mutableListOf<Set<Int>>()
        session.start({ sections += it; cleaningClimb }) { true }
        assertEquals(2, session.state.value.displayedHoldCount)
        assertEquals(5, session.count)
        clock = LocalDateTime.parse("2026-10-11T00:01") // An active round keeps all its unfinished sections.
        assertTrue(session.stop(true))
        assertTrue(session.state.value.active)
        assertEquals(3, session.count)
        assertTrue(session.stop(true))
        assertEquals(1, session.state.value.displayedHoldCount)
        assertEquals(1, session.count)
        var restored = emptyList<ByteArray>()
        assertTrue(session.finish(true, { clear }) { restored = it; true })
        assertArrayEquals(oldClimb.single(), restored.single())
        assertFalse(session.state.value.active)
        assertEquals(listOf(setOf(1, 2), setOf(3, 4), setOf(5)), sections)
    }

    @Test fun `stopping after a confirmed section keeps only the unconfirmed holds, also after a restart`() = runTest {
        val session = session(key = "legacy", limit = 2)
        val entries = arrayOf(at("17:00", 1, 2, 3), at("17:30", 4, 5))
        session.log(*entries)
        session.light()
        assertTrue(session.stop(true)) // Holds 1 and 2 brushed, 3 and 4 lit.
        assertTrue(session.stop(false))
        assertEquals(3, session.count)
        val restarted = session(saved, key = "legacy", limit = 2)
        restarted.log(*entries)
        assertEquals(3, restarted.count)
        var highlighted = emptySet<Int>()
        restarted.start({ highlighted = it; cleaningClimb }) { true }
        assertEquals(setOf(3, 4), highlighted)
        clock = LocalDateTime.parse("2026-10-10T18:30")
        restarted.stop(false)
        restarted.log(*entries, at("18:20", 1))
        assertEquals(4, restarted.count)
    }

    @Test fun `failed next section retains the entire pending collection`() = runTest {
        val session = session(key = "legacy", limit = 2)
        session.log(at("17:00", 1, 2, 3))
        session.light()
        assertFalse(session.finish(true, { clear }) { false })
        assertEquals(3, session.count)
        assertEquals(2, session.state.value.displayedHoldCount)
        assertTrue(saved.isEmpty())
        assertTrue(session.stop(true))
        assertEquals(1, session.count)
        assertEquals(1, session.state.value.displayedHoldCount)
        session.stop(false)
        assertEquals(1, session.count)
    }
}
