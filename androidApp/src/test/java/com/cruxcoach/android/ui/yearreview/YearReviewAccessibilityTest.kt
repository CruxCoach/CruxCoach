package com.cruxcoach.android.ui.yearreview

import android.app.Application
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.domain.yearreview.YearReview
import com.cruxcoach.domain.yearreview.YearReviewComputer
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What a screen reader gets from the year in review (#64), checked on the
 * semantics tree: the review opens as a list when touch exploration is on,
 * every page there starts with a heading, the month chart is text, each share
 * button names its page, and the pages turn with accessibility actions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en")
class YearReviewAccessibilityTest {

    @get:Rule val compose = createComposeRule()

    private val review: YearReview = run {
        var id = 0
        val entries = (0 until 40).map { i ->
            val day = LocalDate(2026, 1, 3).plus(i * 8, DateTimeUnit.DAY)
            AscentWithClimb(
                uuid = "e${id++}", climbUuid = "c${i % 25}", angle = 40, isMirror = false, bidCount = 1L + i % 3,
                quality = null, difficulty = null, comment = null, climbedAt = "${day}T18:00:00", climbName = "Problem $i",
                climbFrames = "p1r12p2r13p3r13p4r14", difficultyAverage = 16.0 + i % 6, boardBrand = if (i % 4 == 0) "tension" else "kilter",
            )
        }
        YearReviewComputer.compute(YearReviewComputer.Input(entries = entries), 2026, LocalDate(2027, 1, 10), TimeZone.UTC)
    }

    @Test
    fun theReviewOpensAsAListWhenTouchExplorationIsOn() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val accessibility = shadowOf(context.getSystemService(AccessibilityManager::class.java))
        accessibility.setTouchExplorationEnabled(false)
        assertFalse(screenReaderOn(context))
        accessibility.setTouchExplorationEnabled(true)
        assertTrue(screenReaderOn(context))
    }

    @Test
    fun everyPageInTheListHasAHeadingItsTextAndANamedShareButton() {
        lateinit var pages: List<ReviewPage>
        compose.setContent {
            pages = reviewPages(review, GradeScale.FRENCH, TrainingContext(null, null, null))
            ReviewList(pages, "Your 2026", onClose = {}, onShare = {}, onPages = {})
        }
        compose.waitForIdle()
        assertTrue("a review with enough data has pages", pages.size >= 8)
        val list = compose.onNode(hasScrollToIndexAction())
        pages.forEachIndexed { i, page ->
            list.performScrollToIndex(i)
            compose.onNode(hasText(page.title) and isHeading()).assertExists("heading of ${page.kind}")
            compose.onNodeWithContentDescription("Share this page: ${page.title}").assertHasClickAction()
            compose.onNodeWithContentDescription("Show information about ${page.title}").assertHasClickAction()
            page.months?.let {
                // The month chart as rows of text, one per month.
                assertTrue("month chart as text", compose.onAllNodesWithText("January").fetchSemanticsNodes().isNotEmpty())
                assertTrue("month chart as text", compose.onAllNodesWithText("December").fetchSemanticsNodes().isNotEmpty())
            }
        }
        compose.onNodeWithContentDescription("Pages").assertHasClickAction()
        compose.onNodeWithContentDescription("Close").assertHasClickAction()
    }

    @Test
    fun thePagesTurnWithAccessibilityActionsAndSayWhereYouAre() {
        lateinit var pages: List<ReviewPage>
        var index by mutableIntStateOf(0)
        compose.setContent {
            pages = reviewPages(review, GradeScale.FRENCH, TrainingContext(null, null, null))
            ReviewPager(pages, index, onIndex = { index = it }, onClose = {}, onShare = {}, onList = {})
        }
        compose.waitForIdle()
        compose.onNode(hasContentDescription("Page 1 of ${pages.size}")).assertExists()
        compose.onNode(hasText(pages[0].title) and isHeading()).assertExists()
        compose.onNodeWithContentDescription("Show information about ${pages[0].title}").assertHasClickAction()

        fun action(label: String) = compose.runOnIdle {
            val node = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).fetchSemanticsNode()
            node.config[SemanticsActions.CustomActions].single { it.label == label }.action()
        }
        action("Next page")
        action("Next page")
        compose.waitForIdle()
        assertEquals(2, index)
        compose.onNode(hasContentDescription("Page 3 of ${pages.size}")).assertExists()
        compose.onNode(hasText(pages[2].title) and isHeading()).assertExists()
        action("Previous page")
        compose.waitForIdle()
        assertEquals(1, index)
        // The first page has no page before it.
        action("Previous page")
        action("Previous page")
        compose.waitForIdle()
        assertEquals(0, index)
        compose.onNodeWithContentDescription("List view").assertHasClickAction()
        compose.onNodeWithContentDescription("Share").assertHasClickAction()
    }

    @Test
    fun theNoteScrollsAtTheLargestFontSize() {
        lateinit var page: ReviewPage
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                page = reviewPages(review, GradeScale.FRENCH, TrainingContext(null, null, null)).first { it.kind == ReviewPageKind.HOLDS_TOUCHED }
                ReviewPager(listOf(page), 0, onIndex = {}, onClose = {}, onShare = {}, onList = {})
            }
        }
        compose.onNodeWithContentDescription("Show information about ${page.title}").performClick()
        compose.onNodeWithText(page.howCalculated!!).assertExists()
        compose.onNodeWithTag("info_dialog_content").assert(hasScrollAction())
    }

    @Test
    fun holdsTouchedIsAPageOfItsOwnWithItsNote() {
        lateinit var pages: List<ReviewPage>
        compose.setContent { pages = reviewPages(review, GradeScale.FRENCH, TrainingContext(null, null, null)) }
        compose.waitForIdle()
        val page = pages.single { it.kind == ReviewPageKind.HOLDS_TOUCHED }
        val sends = review.volume.sends
        assertEquals("${sends * 4}", page.hero!!.replace(",", ""))
        assertEquals("4 holds", page.rows.first().value)
        assertTrue(page.howCalculated!!.endsWith("Counted from all your $sends sends."))
    }
}
