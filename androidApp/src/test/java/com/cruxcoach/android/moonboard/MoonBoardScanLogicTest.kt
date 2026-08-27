package com.cruxcoach.android.moonboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonBoardListTraversalTest {
    @Test
    fun `advances one page per fresh look at the list`() {
        val traversal = MoonBoardListTraversal(confirmations = 4)
        assertEquals(MoonBoardListStep.SCROLL, traversal.next("a|b|c", canScroll = true))
        assertEquals(MoonBoardListStep.SCROLL, traversal.next("d|e|f", canScroll = true))
        assertTrue(traversal.changed)
    }

    @Test
    fun `never scrolls twice on the same observation`() {
        val traversal = MoonBoardListTraversal(confirmations = 4)
        assertEquals(MoonBoardListStep.SCROLL, traversal.next("a|b|c", canScroll = true))
        // The tree still describes the page from before that scroll. Scrolling
        // again here would move two pages while only one was inspected, which
        // is how a date row gets jumped over.
        assertEquals(MoonBoardListStep.WAIT, traversal.next("a|b|c", canScroll = true))
        assertEquals(MoonBoardListStep.WAIT, traversal.next("a|b|c", canScroll = true))
        // Once it catches up, advancing resumes.
        assertEquals(MoonBoardListStep.SCROLL, traversal.next("d|e|f", canScroll = true))
    }

    @Test
    fun `a list that stops offering a forward scroll ends after confirmations`() {
        val traversal = MoonBoardListTraversal(confirmations = 3)
        assertEquals(MoonBoardListStep.WAIT, traversal.next("x|y", canScroll = false))
        assertEquals(MoonBoardListStep.WAIT, traversal.next("x|y", canScroll = false))
        assertEquals(MoonBoardListStep.EXHAUSTED, traversal.next("x|y", canScroll = false))
    }

    @Test
    fun `a list that offers scrolling but never moves still terminates`() {
        val traversal = MoonBoardListTraversal(confirmations = 3)
        assertEquals(MoonBoardListStep.SCROLL, traversal.next("a", canScroll = true))
        assertEquals(MoonBoardListStep.WAIT, traversal.next("a", canScroll = true))
        assertEquals(MoonBoardListStep.WAIT, traversal.next("a", canScroll = true))
        assertEquals(MoonBoardListStep.EXHAUSTED, traversal.next("a", canScroll = true))
    }

    @Test
    fun `new content resets the end-of-list confirmations`() {
        val traversal = MoonBoardListTraversal(confirmations = 3)
        traversal.next("a", canScroll = true)
        traversal.next("a", canScroll = true)
        assertEquals(MoonBoardListStep.SCROLL, traversal.next("b", canScroll = true))
        assertFalse(traversal.changed.not())
        assertEquals(MoonBoardListStep.WAIT, traversal.next("b", canScroll = true))
    }

    @Test
    fun `reset starts a fresh list`() {
        val traversal = MoonBoardListTraversal(confirmations = 2)
        assertEquals(MoonBoardListStep.SCROLL, traversal.next("a", canScroll = true))
        assertEquals(MoonBoardListStep.WAIT, traversal.next("a", canScroll = true))
        traversal.reset()
        assertEquals(MoonBoardListStep.SCROLL, traversal.next("a", canScroll = true))
    }
}

class MoonBoardBackRetryGateTest {
    @Test
    fun `event bursts cannot queue a second back during Moon transition`() {
        val gate = MoonBoardBackRetryGate(minimumIntervalMs = 1_200)
        gate.backRequested(nowMs = 10_000)

        assertEquals(1_120L, gate.remainingDelay(nowMs = 10_080))
        assertEquals(700L, gate.remainingDelay(nowMs = 10_500))
        assertEquals(1L, gate.remainingDelay(nowMs = 11_199))
        assertEquals(0L, gate.remainingDelay(nowMs = 11_200))
    }

    @Test
    fun `reset and a later request form independent retry windows`() {
        val gate = MoonBoardBackRetryGate(minimumIntervalMs = 1_200)
        gate.backRequested(nowMs = 5_000)
        gate.reset()
        assertEquals(0L, gate.remainingDelay(nowMs = 5_001))

        gate.backRequested(nowMs = 8_000)
        assertEquals(1_200L, gate.remainingDelay(nowMs = 8_000))
        assertEquals(0L, gate.remainingDelay(nowMs = 9_500))
    }
}

class MoonBoardSessionCollectorTest {
    // 19 Aug 2025 as the Moon app lists it: 4 problems, 1 completed, 7 tries.
    private val session = MoonBoardScreenParser.parseSession(
        "19 Aug 2025\n4 problems (1 completed, 7 tries)",
    )!!
    private val warmUp = "THE WARM UP PROBLEM\nSet by RussK @ 40°\n55,129 repeats\n" +
        "Setter 6A+/V3\nAny marked holds\nProject - (1 try) @40°"
    private val kat = "KAT IN THE HAT 🎩\nSet by leecujes @ 40°\n42,510 repeats\n" +
        "6A+/V3. Your grade 6A+/V3\nAny marked holds\nFlashed @40°"
    private val daReal = "DA REAL 6A+\nSet by Dennis Kunath @ 40°\n31,907 repeats\n" +
        "Setter 6B/V4\nAny marked holds\nProject - (1 try) @40°"
    private val jete = "JETE\nSet by petitgaaateau  @ 40°\n30,963 repeats\n" +
        "Setter 6A+/V3\nAny marked holds\nProject - (4 tries) @40°"

    @Test
    fun `a fully read session produces the exact Moon semantics and no deviations`() {
        val collector = MoonBoardSessionCollector(session)
        assertTrue(collector.observe(listOf("Back", "Logbook\n4 problems", warmUp, kat, daReal, jete)))
        assertEquals(4, collector.seen)

        val result = collector.finish(expected = 4)
        assertEquals(emptyList<MoonBoardDeviation>(), result.deviations)
        assertEquals(4, result.entries.size)
        assertEquals(1, result.entries.count { it.isSend })
        assertEquals(7, result.entries.sumOf { it.attempts })
        assertTrue(result.entries.all { it.climbedAt == "2025-08-19T12:00:00Z" })

        val open = result.entries.single { it.name == "JETE" }
        assertFalse(open.isSend)
        assertEquals(4, open.attempts)
        assertEquals("petitgaaateau", open.setter)
    }

    @Test
    fun `cards seen again on later scroll passes are not imported twice`() {
        val collector = MoonBoardSessionCollector(session)
        collector.observe(listOf(warmUp, kat))
        assertTrue(collector.observe(listOf(kat, daReal, jete)))
        assertFalse(collector.observe(listOf(kat, daReal, jete)))
        assertEquals(4, collector.seen)
        assertEquals(4, collector.finish(expected = 4).entries.size)
    }

    @Test
    fun `a session that really lists the same card twice imports it twice`() {
        val twice = MoonBoardScreenParser.parseSession(
            "19 Aug 2025\n2 problems (0 completed, 2 tries)",
        )!!
        val collector = MoonBoardSessionCollector(twice)
        collector.observe(listOf(warmUp, warmUp))
        assertEquals(2, collector.seen)
        val result = collector.finish(expected = 2)
        assertEquals(2, result.entries.size)
        assertEquals(emptyList<MoonBoardDeviation>(), result.deviations)
    }

    @Test
    fun `a partially read session imports what it has and names the shortfall`() {
        val collector = MoonBoardSessionCollector(session)
        collector.observe(listOf(warmUp, kat))
        val result = collector.finish(expected = 4)
        assertEquals(2, result.entries.size)
        assertTrue(MoonBoardDeviation.MissingProblems("19 Aug 2025", 2, 4) in result.deviations)
        // The one send it did read still matches Moon's own count, so the
        // shortfall is reported as missing problems, not as a wrong outcome.
        assertTrue(result.deviations.none { it is MoonBoardDeviation.SendMismatch })
        assertTrue(MoonBoardDeviation.TryMismatch("19 Aug 2025", 2, 7) in result.deviations)
    }

    @Test
    fun `an unreadable card is named instead of silently dropped`() {
        val unknown = "MYSTERY\nSet by Setter @ 40°\n7 repeats\nSetter 6C/V5\nRepeated @40°"
        val collector = MoonBoardSessionCollector(session)
        collector.observe(listOf(warmUp, kat, daReal, unknown))
        val result = collector.finish(expected = 4)
        // The three readable cards are still imported …
        assertEquals(3, result.entries.size)
        // … and the fourth is reported by name and outcome.
        assertEquals(mapOf("MYSTERY — Repeated @40°" to 1), result.unreadable)
        assertTrue(MoonBoardDeviation.UnknownWording("19 Aug 2025", 1) in result.deviations)
    }

    @Test
    fun `non-problem labels on the page are ignored`() {
        val collector = MoonBoardSessionCollector(session)
        assertFalse(
            collector.observe(
                listOf("Back", "Show menu", "Logbook\n4 problems", "Benchmarks", ""),
            ),
        )
        assertTrue(collector.isEmpty)
    }

    @Test
    fun `missing Moon count never authorizes destructive reconciliation`() {
        val noContract = MoonBoardScreenParser.parseSession("19 Aug 2025")!!
        val collector = MoonBoardSessionCollector(noContract)
        collector.observe(listOf(warmUp))

        val result = collector.finish(expected = null)
        assertEquals(1, result.entries.size)
        assertFalse(result.complete)
    }

    @Test
    fun `accessibility over-count fails exact validation`() {
        val one = MoonBoardScreenParser.parseSession(
            "19 Aug 2025\n1 problem (0 completed, 1 try)",
        )!!
        val collector = MoonBoardSessionCollector(one)
        collector.observe(listOf(warmUp, warmUp))

        val result = collector.finish(expected = 1)
        assertEquals(2, result.entries.size)
        assertFalse(result.complete)
        assertTrue(MoonBoardDeviation.ExcessProblems("19 Aug 2025", 2, 1) in result.deviations)
    }

    @Test
    fun `problem count alone is not enough to authorize a write`() {
        val noTotals = MoonBoardScreenParser.parseSession("19 Aug 2025\n1 problem")!!
        val collector = MoonBoardSessionCollector(noTotals)
        collector.observe(listOf(warmUp))

        assertFalse(collector.finish(expected = 1).complete)
    }
}


class MoonBoardDeltaSkipTest {
    private fun session(label: String) = MoonBoardScreenParser.parseSession(label)!!
    private val day = session("19 Aug 2025\n4 problems (1 completed, 7 tries)")

    @Test
    fun `a training day stored exactly as Moon describes it is skipped`() {
        assertTrue(canSkipSession(day, MoonBoardImportedDay(entries = 4, sends = 1, tries = 7)))
    }

    @Test
    fun `a day never imported is read`() {
        assertFalse(canSkipSession(day, null))
        assertFalse(canSkipSession(day, MoonBoardImportedDay(0, 0, 0)))
    }

    @Test
    fun `a day edited in Moon after the import is read again`() {
        // One more problem logged since the import.
        assertFalse(canSkipSession(day, MoonBoardImportedDay(entries = 3, sends = 1, tries = 7)))
        // A project that has since become a send.
        assertFalse(canSkipSession(day, MoonBoardImportedDay(entries = 4, sends = 0, tries = 7)))
        // An attempt count corrected in Moon.
        assertFalse(canSkipSession(day, MoonBoardImportedDay(entries = 4, sends = 1, tries = 6)))
    }

    @Test
    fun `more rows than Moon lists also forces a re-read`() {
        // Disagreement in either direction means the two no longer describe the
        // same training day, so it is verified rather than trusted.
        assertFalse(canSkipSession(day, MoonBoardImportedDay(entries = 5, sends = 1, tries = 7)))
    }

    @Test
    fun `a row without countable evidence is never skipped`() {
        val bare = session("19 Aug 2025\n4 problems")
        assertFalse(canSkipSession(bare, MoonBoardImportedDay(entries = 4, sends = 1, tries = 7)))
    }
}

class MoonBoardImportTallyTest {
    private fun stored(ascents: Int, projects: Int, duplicates: Int) = MoonBoardCsvImportResult(
        importedAscents = ascents,
        importedProjects = projects,
        duplicates = duplicates,
        foundEntries = ascents + projects + duplicates,
    )

    @Test
    fun `sums the per-day imports into one run summary`() {
        val tally = MoonBoardImportTally()
        tally.add(stored(ascents = 1, projects = 3, duplicates = 0))
        tally.add(stored(ascents = 2, projects = 2, duplicates = 1))
        tally.skipSession(problems = 6)

        val result = tally.toResult(
            sessionsScanned = 3,
            sessionsExpected = 83,
            problemsExpected = 382,
            warnings = listOf("some deviation"),
        )
        assertEquals(15, result.foundEntries)
        assertEquals(8, result.imported)
        // The skipped day's problems count as duplicates: Moon lists them and
        // CruxCoach already holds them.
        assertEquals(7, result.duplicates)
        assertEquals(1, result.sessionsSkipped)
        assertEquals(83, result.sessionsExpected)
        assertEquals(382, result.expectedEntries)
        assertEquals(listOf("some deviation"), result.warnings)
    }

    @Test
    fun `reset starts a new run`() {
        val tally = MoonBoardImportTally()
        tally.add(stored(1, 1, 1))
        tally.skipSession(4)
        tally.reset()
        assertEquals(0, tally.found)
        assertEquals(0, tally.duplicates)
        assertEquals(0, tally.sessionsSkipped)
    }
}
