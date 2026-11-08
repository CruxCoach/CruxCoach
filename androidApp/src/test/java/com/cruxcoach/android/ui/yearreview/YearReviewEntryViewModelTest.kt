package com.cruxcoach.android.ui.yearreview

import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.domain.yearreview.YearReviewComputer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** When the year in review's card and statistics read the logbook (#64), and which year they keep. */
@OptIn(ExperimentalCoroutinesApi::class)
class YearReviewEntryViewModelTest {

    @Volatile private var today = LocalDate(2026, 12, 10)
    @Volatile private var writes = 0L
    @Volatile private var input = YearReviewComputer.Input()
    private val source = mockk<YearReviewSource> {
        every { zone } returns TimeZone.of("Europe/Berlin")
        every { today() } answers { today }
        every { writes() } answers { writes }
        coEvery { load() } answers { input }
    }
    private val preferences = mockk<UserPreferences> { every { yearReviewHiddenYears } returns MutableStateFlow(emptySet()) }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun theDecemberCardReadsTheLogbookAgainOnlyAfterItChanged() {
        input = days(2026, 3)
        val viewModel = YearReviewEntryViewModel(source, preferences)
        viewModel.checkCard()
        settle()
        // Scrolled back into view, back on the start screen: nothing new, nothing read.
        repeat(3) { viewModel.checkCard(); settle() }
        coVerify(exactly = 1) { source.load() }
        assertNull(viewModel.state.value.cardYear)

        // The eighth climbing day reaches the minimum.
        input = days(2026, 8)
        writes++
        viewModel.checkCard()
        await { viewModel.state.value.cardYear == 2026 }
        coVerify(exactly = 2) { source.load() }
    }

    @Test
    fun theStatisticsReadAgainWhenTheyOpenAfterANewSession() {
        today = LocalDate(2026, 6, 10)
        input = days(2025, 10) + days(2026, 2)
        val viewModel = YearReviewEntryViewModel(source, preferences)
        viewModel.startStats()
        await { viewModel.state.value.current?.volume?.climbingDays == 2 }
        viewModel.startStats()
        settle()
        coVerify(exactly = 1) { source.load() }

        input = days(2025, 10) + days(2026, 3)
        writes++
        viewModel.startStats()
        await { viewModel.state.value.current?.volume?.climbingDays == 3 }
    }

    @Test
    fun aYearTappedWhileTheStatisticsReadStaysPicked() {
        today = LocalDate(2027, 1, 10)
        input = days(2025, 10) + days(2026, 10)
        val viewModel = YearReviewEntryViewModel(source, preferences)
        viewModel.startStats()
        await { viewModel.state.value.selected?.year == 2026 }

        // A reload starts and waits on the logbook.
        val logbook = CompletableDeferred<Unit>()
        coEvery { source.load() } coAnswers { logbook.await(); input }
        viewModel.reload()
        // The chip for 2025 is tapped; its year is still being worked out when the reload goes on.
        val blockNext = AtomicBoolean(true)
        val tapWorking = CountDownLatch(1)
        val release = CountDownLatch(1)
        val todayCalls = AtomicInteger()
        every { source.today() } answers {
            if (blockNext.compareAndSet(true, false)) {
                tapWorking.countDown()
                release.await(5, TimeUnit.SECONDS)
            } else {
                todayCalls.incrementAndGet()
            }
            today
        }
        viewModel.select(2025)
        assertTrue(tapWorking.await(5, TimeUnit.SECONDS))
        try {
            logbook.complete(Unit)
            // The reload has read, shown the card and picked its year.
            await { todayCalls.get() >= 3 }
            settle()
        } finally {
            release.countDown()
        }
        await { viewModel.state.value.selected?.year == 2025 }
        settle()
        assertEquals(2025, viewModel.state.value.selectedYear)
        assertEquals(2025, viewModel.state.value.selected?.year)
    }

    /** [count] climbing days in [year], one send each, early in the year. */
    private fun days(year: Int, count: Int) = YearReviewComputer.Input(
        entries = (1..count).map { i ->
            AscentWithClimb(
                uuid = "$year-$i", climbUuid = "c$i", angle = 40, isMirror = false, bidCount = 1, quality = null, difficulty = null,
                comment = null, climbedAt = "$year-01-${(i + 1).toString().padStart(2, '0')}T18:00:00", climbName = "Problem $i",
                climbFrames = "", difficultyAverage = 20.0, boardBrand = "kilter",
            )
        },
    )

    private operator fun YearReviewComputer.Input.plus(other: YearReviewComputer.Input) = copy(entries = entries + other.entries)

    /** The view model works off the main thread too; lets it finish what it started. */
    private fun settle() = Thread.sleep(200)

    private fun await(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(10)
        }
    }
}
