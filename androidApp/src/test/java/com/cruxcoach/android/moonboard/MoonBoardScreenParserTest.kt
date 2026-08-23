package com.cruxcoach.android.moonboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixtures below are verbatim accessibility labels captured from the
 * official Moon app (com.trainingboard.moon) on an Android 9 device — including
 * its double spaces, emoji, thousands separators and the singular "1 try".
 */
class MoonBoardScreenParserTest {
    private val date = "2026-08-23T12:00:00Z"

    @Test
    fun `parses real official app semantic label`() {
        val parsed = MoonBoardScreenParser.parseDateLabel(
            "23 Aug 2026\n1 problem (1 completed, 1 try)",
        )
        assertEquals("2026-08-23T12:00:00Z", parsed)

        val entry = MoonBoardScreenParser.parseProblem(
            "SWEET AND ALT\nSet by 西檸雞 @ 40°\n1 repeat\n" +
                "7B+/V8. Your grade 7B/V8\nAny marked holds\nFlashed @40°",
            parsed!!,
        )
        assertNotNull(entry)
        assertEquals("SWEET AND ALT", entry!!.name)
        assertEquals("西檸雞", entry.setter)
        assertEquals(40, entry.angle)
        assertEquals(1, entry.attempts)
        assertTrue(entry.isSend)
    }

    @Test
    fun `parses every outcome wording the Moon app actually produces`() {
        val flashed = parse(
            "SURF N TURF\nSet by Kyle Knapp @ 40°\n405 repeats\n" +
                "6B+/V4. Your grade 6B+/V4\nAny marked holds\nFlashed @40°",
        )
        assertTrue(flashed.isSend)
        assertEquals(1, flashed.attempts)

        // Double space before "@" and an apostrophe in the problem name.
        val secondTry = parse(
            "HE'S A VIP\nSet by Sledgehammer  @ 40°\n2,033 repeats\n" +
                "6A+/V3. Your grade 6A+/V3\nAny marked holds\n2nd try @40°",
        )
        assertTrue(secondTry.isSend)
        assertEquals(2, secondTry.attempts)
        assertEquals("Sledgehammer", secondTry.setter)

        val thirdTry = parse(
            "RÉBUFFAT\nSet by Kyle Knapp @ 40°\n403 repeats\n" +
                "6B+/V4. Your grade 6B+/V4\nAny marked holds\n3rd try @40°",
        )
        assertTrue(thirdTry.isSend)
        assertEquals(3, thirdTry.attempts)

        // Moon's coarse "> 3 tries" always carries the exact total. Both the
        // send flag and that exact number have to survive the import.
        val fourTries = parse(
            "23 BOULDERFEST\nSet by Andrey onsight @ 40°\n448 repeats\n" +
                "6A+/V3. Your grade 6A+/V3\nAny marked holds\n> 3 tries - (4 tries) @40°",
        )
        assertTrue(fourTries.isSend)
        assertEquals(4, fourTries.attempts)

        val fiveTries = parse(
            "WHIPPED CREAM\nSet by Sawyer Hankins @ 40°\n474 repeats\n" +
                "7A/V6. Your grade 7A/V6\nAny marked holds\n> 3 tries - (5 tries) @40°",
        )
        assertTrue(fiveTries.isSend)
        assertEquals(5, fiveTries.attempts)
    }

    @Test
    fun `open projects keep their exact try count and never count as a send`() {
        // Singular "1 try", emoji in the name, and the highest count in the
        // account. A project must stay open no matter how often it was tried.
        val oneTry = parse(
            "FLAT MOON SOCIETY\nSet by Nick Wedge @ 40°\n488 repeats\n" +
                "Setter 7B+/V8\nAny marked holds\nProject - (1 try) @40°",
        )
        assertFalse(oneTry.isSend)
        assertEquals(1, oneTry.attempts)

        val threeTries = parse(
            "REANN AVOCADO 🥑\nSet by Alex @ 40°\n461 repeats\n" +
                "Setter 6A+/V3\nAny marked holds\nProject - (3 tries) @40°",
        )
        assertFalse(threeTries.isSend)
        assertEquals(3, threeTries.attempts)
        assertEquals("REANN AVOCADO 🥑", threeTries.name)

        val sixTries = parse(
            "LOW BALL CHOSS\nSet by Kyle Knapp @ 40°\n483 repeats\n" +
                "Setter 6C/V5\nAny marked holds\nProject - (6 tries) @40°",
        )
        assertFalse(sixTries.isSend)
        assertEquals(6, sixTries.attempts)
        assertEquals("Project - (6 tries)", sixTries.tries)
    }

    @Test
    fun `covers every attempt count from one to six`() {
        val expected = listOf(1, 2, 3, 4, 5, 6)
        val projects = expected.map { count ->
            parse(
                "P$count\nSet by Setter @ 40°\nSetter 6C/V5\n" +
                    "Project - ($count ${if (count == 1) "try" else "tries"}) @40°",
            )
        }
        assertEquals(expected, projects.map { it.attempts })
        assertTrue(projects.none { it.isSend })

        val sends = expected.map { count ->
            when (count) {
                1 -> parse("S1\nSet by Setter @ 40°\n6C/V5\nFlashed @40°")
                2 -> parse("S2\nSet by Setter @ 40°\n6C/V5\n2nd try @40°")
                3 -> parse("S3\nSet by Setter @ 40°\n6C/V5\n3rd try @40°")
                else -> parse(
                    "S$count\nSet by Setter @ 40°\n6C/V5\n> 3 tries - ($count tries) @40°",
                )
            }
        }
        assertEquals(expected, sends.map { it.attempts })
        assertTrue(sends.all { it.isSend })
    }

    @Test
    fun `older short outcome labels still parse`() {
        val sent = MoonBoardScreenParser.parseProblem(
            "A PROBLEM\nSet by Setter @ 25°\n6C/V5\n3rd try @25°",
            date,
        )!!
        assertTrue(sent.isSend)
        assertEquals(3, sent.attempts)
        assertEquals(25, sent.angle)

        val project = MoonBoardScreenParser.parseProblem(
            "PROJECT X\nSet by Setter @ 40°\n7A/V6\nProject @40°",
            date,
        )!!
        assertFalse(project.isSend)
        assertEquals(1, project.attempts)

        val numbered = MoonBoardScreenParser.parseProblem(
            "A PROBLEM\nSet by Setter @ 40°\n6C/V5\n2nd try - (3 tries) @40°",
            date,
        )!!
        assertTrue(numbered.isSend)
        assertEquals(3, numbered.attempts)
    }

    @Test
    fun `reads the session rows of the logbook date list`() {
        val session = MoonBoardScreenParser.parseSession(
            "21 Jul 2026\n4 problems (1 completed, 17 tries)",
        )!!
        assertEquals("2026-07-21T12:00:00Z", session.climbedAt)
        assertEquals(4, session.problems)
        assertEquals(1, session.completed)
        assertEquals(17, session.tries)

        // Leading zero and the singular "1 try" both occur in the live list.
        val zeroPadded = MoonBoardScreenParser.parseSession(
            "07 Aug 2026\n1 problem (1 completed, 1 try)",
        )!!
        assertEquals("2026-08-07T12:00:00Z", zeroPadded.climbedAt)
        assertEquals(1, zeroPadded.problems)

        assertNull(MoonBoardScreenParser.parseSession("Logbook\n83 entries, 382 problems"))
        assertNull(MoonBoardScreenParser.parseSession("Benchmarks"))
    }

    @Test
    fun `reads the completeness counts from both logbook headers`() {
        val list = MoonBoardScreenParser.parseHeader("Logbook\n83 entries, 382 problems")!!
        assertEquals(83, list.sessions)
        assertEquals(382, list.problems)

        val detail = MoonBoardScreenParser.parseHeader("Logbook\n4 problems")!!
        assertNull(detail.sessions)
        assertEquals(4, detail.problems)

        assertNull(MoonBoardScreenParser.parseHeader("Logbook"))
        assertNull(MoonBoardScreenParser.parseHeader("Hub\nTab 2 of 3"))
    }

    @Test
    fun `an unknown outcome is reported as a problem card instead of vanishing`() {
        val label = "MYSTERY\nSet by Setter @ 40°\n6C/V5\nAny marked holds\nRepeated @40°"
        // Structurally a problem card, so the scanner counts and names it …
        assertTrue(MoonBoardScreenParser.isProblemLabel(label))
        // … but it is never guessed into the logbook as a send or a project.
        assertNull(MoonBoardScreenParser.parseProblem(label, date))
    }

    @Test
    fun `ignores unrelated accessibility labels`() {
        assertNull(MoonBoardScreenParser.parseDateLabel("Logbook\n12 entries"))
        assertNull(MoonBoardScreenParser.parseProblem("Hub\nTab 2 of 3", date))
        assertFalse(MoonBoardScreenParser.isProblemLabel("Logbook\n83 entries, 382 problems"))
        assertFalse(MoonBoardScreenParser.isProblemLabel("Show menu"))
        assertFalse(MoonBoardScreenParser.isProblemLabel("21 Jul 2026\n4 problems (1 completed, 17 tries)"))
    }

    private fun parse(label: String): MoonBoardScreenEntry =
        requireNotNull(MoonBoardScreenParser.parseProblem(label, date)) { "unparsed: $label" }
}
