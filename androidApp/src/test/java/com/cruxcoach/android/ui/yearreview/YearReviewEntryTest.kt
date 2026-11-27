package com.cruxcoach.android.ui.yearreview

import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class YearReviewWindowTest {

    @Test
    fun theCardInvitesInDecemberAndJanuaryOnly() {
        assertNull(YearReviewWindow.invitedYear(LocalDate(2026, 11, 30)))
        assertEquals(2026, YearReviewWindow.invitedYear(LocalDate(2026, 12, 1)))
        assertEquals(2026, YearReviewWindow.invitedYear(LocalDate(2026, 12, 31)))
        assertEquals(2026, YearReviewWindow.invitedYear(LocalDate(2027, 1, 1)))
        assertEquals(2026, YearReviewWindow.invitedYear(LocalDate(2027, 1, 31)))
        assertNull(YearReviewWindow.invitedYear(LocalDate(2027, 2, 1)))
    }
}

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class YearShareImageTest {

    private val long = "Eine sehr lange Überschrift mit Donaudampfschifffahrtsgesellschaftskapitänsmütze ".repeat(4)

    @Test
    fun pagesAreStoriesAndTheSummaryIsSquareWhateverTheTextLength() {
        val rows = (1..12).map { ReviewRow(long, long, long, board = "kilter") }
        val page = ReviewPage(ReviewPageKind.HIGHLIGHTS, long, long, hero = long, heroLabel = long, lead = long, rows = rows, months = List(12) { it })
        val story = YearShareImage.render(page, header = long, wordmark = "CruxCoach", boardColors = mapOf("kilter" to 0xFFFF6D00.toInt()))
        assertEquals(1080, story.width)
        assertEquals(1920, story.height)

        val summary = YearShareImage.render(page.copy(kind = ReviewPageKind.SUMMARY), header = null, wordmark = "CruxCoach")
        assertEquals(1080, summary.width)
        assertEquals(1080, summary.height)
    }
}
