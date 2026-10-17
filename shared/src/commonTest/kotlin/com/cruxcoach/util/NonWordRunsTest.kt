package com.cruxcoach.util

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The expected values are what the JVM regex `[^\p{L}\p{N}<keep>]+` returns; the
 * same assertions run on the iOS simulator, where the regex engine differs.
 */
class NonWordRunsTest {
    @Test
    fun decimalCommasAndFractionsSurviveTheMealParserClass() {
        assertEquals("nüsse, 1,5 l. x", replaceNonWordRuns("nüsse, 1,5 l. x", "½¼¾.,", " "))
        assertEquals("3½ Äpfel ", replaceNonWordRuns("3½ Äpfel!!", "½¼¾.,", " "))
        assertEquals(" 250g skyr ", replaceNonWordRuns("(250g) skyr :-)", "½¼¾.,", " "))
    }

    @Test
    fun workoutPunctuationSurvivesTheWorkoutParserClass() {
        assertEquals(
            "Klimmzüge 5 5 20 kg / Ring-Dips (2x) & Plank 3 o'clock",
            replaceNonWordRuns("Klimmzüge 5×5 +20 kg / Ring-Dips (2x) & Plank 3 o'clock", "'/&()-", " "),
        )
    }
}
