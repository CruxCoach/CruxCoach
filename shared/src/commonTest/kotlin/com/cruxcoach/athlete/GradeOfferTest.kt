package com.cruxcoach.athlete

import com.cruxcoach.athlete.logic.LogbookSummaries
import com.cruxcoach.athlete.logic.LogbookSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** "Your logbook shows 7a – take it over?": only clearly higher, only once per grade, never to lower. */
class GradeOfferTest {

    private fun logbook(working: Double?, sends: Int = 20, fromCoach: Boolean = false) =
        LogbookSummary(sampleSize = sends, workingDifficulty = working, gradesFromCoach = fromCoach)

    private fun offer(summary: LogbookSummary, saved: String?, declined: String? = null) =
        LogbookSummaries.higherWorkingGrade(summary, saved, declined)

    @Test
    fun aClearlyHigherWorkingGradeIsOffered() {
        // 7a (22) in the logbook, 6c (20) saved.
        assertEquals("7a", offer(logbook(22.2), "6c"))
        assertEquals("7a", offer(logbook(21.6), "6c+"))
        // The same grade, a lower one, or under one sub-grade above: nothing.
        assertNull(offer(logbook(21.4), "6c+"))
        assertNull(offer(logbook(19.0), "6c+"))
        // Without a saved grade the logbook already sets the level; without a graded logbook there is nothing to offer.
        assertNull(offer(logbook(22.0), null))
        assertNull(offer(logbook(22.0, sends = 3), "6c"))
        assertNull(offer(logbook(22.0, fromCoach = true), "6c"))
    }

    @Test
    fun aNoHoldsForThatGradeAndAHigherOneAsksAgain() {
        assertNull(offer(logbook(22.0), "6c", declined = "7a"))
        assertNull(offer(logbook(21.0), "6b", declined = "7a"))
        assertEquals("7a+", offer(logbook(23.0), "6c", declined = "7a"))
    }
}
