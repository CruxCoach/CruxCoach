package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.data.repository.AscentWithClimb
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Design review of the top of the statistics, the tries until the send, the
 * board feel and the session recap: renders them with a made-up three-board
 * logbook into `CRUXCOACH_UI_REVIEW_DIR` (light theme with
 * `CRUXCOACH_UI_REVIEW_THEME=light`). Skipped unless that variable is set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de-w411dp-h1600dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatsHighlightsUiReviewTest {

    @get:Rule val compose = createComposeRule()

    private val dir: String? = System.getenv("CRUXCOACH_UI_REVIEW_DIR")
    private val prefix: String = System.getenv("CRUXCOACH_UI_REVIEW_PREFIX") ?: ""
    private val theme =
        if (System.getenv("CRUXCOACH_UI_REVIEW_THEME") == "light") DarkModeSetting.LIGHT else DarkModeSetting.DARK

    @Before
    fun onlyOnRequest() = Assume.assumeTrue(dir != null)

    private val today = LocalDate.parse("2026-11-01")
    private val clock = Clock.fixed(Instant.parse("2026-11-01T20:00:00Z"), ZoneOffset.UTC)
    private val scale = GradeScale.FRENCH
    private val boards = listOf("kilter", "moonboard", "tension")
    private var id = 0

    private fun entry(board: String, climb: String, day: LocalDate, difficulty: Double, tries: Int, isSend: Boolean) =
        AscentWithClimb(
            uuid = "r-${++id}", climbUuid = climb, angle = 40, isMirror = false, bidCount = tries.toLong(),
            quality = null, difficulty = difficulty.toLong(), comment = null, climbedAt = "${day}T18:00:00",
            climbName = "Problem $climb", climbFrames = "", difficultyAverage = difficulty, isSend = isSend,
            boardBrand = board,
        )

    /**
     * [count] problems on [board], one every [every] days back from [last],
     * grades rising over time; [harderBy] difficulty steps add tries like a
     * harder board does.
     */
    private fun problems(board: String, count: Int, every: Long, last: LocalDate, harderBy: Double, from: Double) =
        (0 until count).flatMap { i ->
            val day = last.minusDays((count - 1 - i) * every)
            val difficulty = from + (i % 6) + i / 14
            val wobble = listOf(1.0, 1.5, 0.6, 1.2, 0.8)[i % 5]
            val tries = (exp(0.25 * (difficulty + harderBy - 15)) * wobble).roundToInt().coerceAtLeast(1)
            val climb = "$board-$i"
            // Every third problem took attempts on an earlier day too.
            if (i % 3 == 2 && tries > 2) {
                listOf(
                    entry(board, climb, day.minusDays(7), difficulty, tries - 1, isSend = false),
                    entry(board, climb, day, difficulty, 1, isSend = true),
                )
            } else {
                listOf(entry(board, climb, day, difficulty, tries, isSend = true))
            }
        }

    private val logbook: List<AscentWithClimb> = buildList {
        addAll(problems("kilter", 70, 2, today.minusDays(1), 0.0, 14.0))
        addAll(problems("moonboard", 26, 5, today.minusDays(2), 1.6, 13.0))
        addAll(problems("tension", 12, 9, today.minusDays(3), -0.6, 14.0))
        // A busy last session with a new grade.
        (0 until 6).forEach { add(entry("kilter", "today-$it", today, 15.0 + it, 1 + it % 2, isSend = true)) }
        add(entry("kilter", "today-top", today.minusDays(14), 24.0, 3, isSend = false))
        add(entry("kilter", "today-top", today, 24.0, 2, isSend = true))
        add(entry("moonboard", "today-m", today, 18.0, 1, isSend = true))
        add(entry("moonboard", "today-open", today, 21.0, 4, isSend = false))
    }

    private val flashUuids = BoardStatsComputer.trueFlashUuids(logbook)
    private val glance = StatsHighlightsComputer.atAGlance(logbook, scale, flashUuids, clock)
    private val milestones = StatsHighlightsComputer.milestones(logbook, scale, weekStreak = 7)
    private val cross = CrossBoardStatsComputer.compute(logbook, StatsTimeInterval.ALL, scale, clock = clock)
    private val stats = BoardStatsComputer.computeStats(logbook, StatsTimeInterval.ALL, scale, clock = clock)
    private val byBoard = BoardStatsComputer.gradeProgressionByBoard(logbook, StatsTimeInterval.ALL, clock = clock)
    private val recap = StatsHighlightsComputer.sessionRecap(logbook, today, scale, flashUuids)!!
    private val zones = com.cruxcoach.domain.board.IntensityZoneEngine.computeZones(
        logbook.mapNotNull { it.difficultyAverage },
    )

    private fun shoot(name: String, before: () -> Unit = {}, content: @Composable () -> Unit) {
        var view: android.view.View? = null
        compose.setContent {
            view = LocalView.current
            CruxCoachTheme(theme) { Surface { Box(Modifier.padding(16.dp)) { content() } } }
        }
        compose.waitForIdle()
        before()
        compose.waitForIdle()
        compose.runOnIdle {
            val root = requireNotNull(view)
            val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(bitmap))
            java.io.File(dir!!).mkdirs()
            java.io.File(dir, "$prefix$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    /** The logbook top: the overview over the first day of the list. */
    @Test
    fun logbookTop() = shoot("logbook-top") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LogbookOverviewCard(glance, milestones, boards, onOpenStats = {})
            DayHeader(today.toString(), 9, onRecap = {})
        }
    }

    /** The statistics from the filters down to the grades. */
    @Test
    fun sheetTop() = shoot("stats-sheet-1") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            StatsIntervalChips(selected = StatsTimeInterval.ALL, onSelect = {}, onCustomDateRange = { _, _ -> })
            PeriodNumbers(stats, scale)
            BoardsCard(cross.feel!!, cross.boards, boards)
            ProgressCard(stats.gradeProgression, byBoard, boardFilter = null, allBoards = boards, gradeScale = scale)
            GradesCard(stats.gradeOutcomes, stats.gradePyramid, zones, GradesView.PROBLEMS, onSelect = {})
        }
    }

    /** The statistics from the tries down to the angles. */
    @Test
    fun sheetBottom() = shoot("stats-sheet-2") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CrossBoardSection(cross, CrossBoardView.TRIES_UNTIL_SEND, onViewSelect = {}, allBoards = boards)
            ActivityCard(ActivityView.CALENDAR, onSelect = {}, stats.activityMap, stats.daysPerWeek, stats.entriesOverTime, stats.sendsOverTime, StatsTimeInterval.ALL)
            ChartSection("Winkel-Verteilung") { BoardAngleDistChart(entries = stats.angleDistribution) }
        }
    }

    @Test
    fun oneBoard() {
        val kilter = logbook.filter { it.boardBrand == "kilter" }
        val one = StatsHighlightsComputer.atAGlance(kilter, scale, flashUuids, clock)
        val badges = StatsHighlightsComputer.milestones(kilter, scale, weekStreak = 7)
        val kilterStats = BoardStatsComputer.computeStats(kilter, StatsTimeInterval.ALL, scale, clock = clock)
        shoot("stats-one-board") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                LogbookOverviewCard(one, badges, listOf("kilter"), onOpenStats = {})
                PeriodNumbers(kilterStats, scale)
                ProgressCard(kilterStats.gradeProgression, emptyMap(), boardFilter = null, allBoards = listOf("kilter"), gradeScale = scale)
                GradesCard(kilterStats.gradeOutcomes, kilterStats.gradePyramid, zones, GradesView.PROBLEMS, onSelect = {})
            }
        }
    }

    @Test
    fun weekly() = shoot("stats-weekly") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ActivityCard(ActivityView.DAYS_PER_WEEK, onSelect = {}, stats.activityMap, stats.daysPerWeek, stats.entriesOverTime, stats.sendsOverTime, StatsTimeInterval.ALL)
            ActivityCard(ActivityView.SENDS, onSelect = {}, stats.activityMap, stats.daysPerWeek, stats.entriesOverTime, stats.sendsOverTime, StatsTimeInterval.ALL)
        }
    }

    @Test
    fun gradeSends() = shoot("stats-grade-sends") {
        GradesCard(stats.gradeOutcomes, stats.gradePyramid, zones, GradesView.SENDS, onSelect = {})
    }

    @Test
    fun periodNumbers30Days() = shoot("stats-period-30") {
        PeriodNumbers(BoardStatsComputer.computeStats(logbook, StatsTimeInterval.DAYS_30, scale, clock = clock), scale)
    }

    @Test
    fun gradesOpen() = shoot("stats-grades-open", before = {
        compose.onNodeWithText(stats.gradeOutcomes.last().grade).performClick()
    }) {
        GradesCard(stats.gradeOutcomes, stats.gradePyramid, zones, GradesView.PROBLEMS, onSelect = {})
    }

    @Test
    fun sendCurveOpen() = shoot("stats-send-curve-open", before = {
        compose.onNodeWithText("MoonBoard").performClick()
    }) {
        CrossBoardSection(cross, CrossBoardView.TRIES_UNTIL_SEND, onViewSelect = {}, allBoards = boards)
    }

    @Test
    fun sessionRecap() = shoot("session-recap") {
        SessionRecapImage(recap)
    }
}
