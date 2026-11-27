package com.cruxcoach.android.ui.board

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.data.repository.Board_sessions
import com.cruxcoach.domain.board.IntensityZoneEngine
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Design review of the logbook statistics: every screen and state of the
 * logbook top, the statistics sheet, the session recap and the end-of-session
 * summary, rendered with made-up logbooks into `CRUXCOACH_UI_REVIEW_DIR`.
 * Skipped unless that variable is set.
 *
 * Each screen is drawn in every variant: dark and light, English and German,
 * font scale 1.0 and 2.0, 320 dp and 411 dp wide (`<screen>/<variant>.png`).
 * `pair-*.png` puts two variants of the same size side by side and cuts them
 * into pieces that are easy to look at. `CRUXCOACH_UI_REVIEW_ONLY` limits the
 * run to variants whose name contains it (e.g. `de-fs2.0`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h7000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatsUiReviewTest {

    @get:Rule val compose = createComposeRule()

    private val dir: String? = System.getenv("CRUXCOACH_UI_REVIEW_DIR")
    private val only: String? = System.getenv("CRUXCOACH_UI_REVIEW_ONLY")

    @Before
    fun onlyOnRequest() = Assume.assumeTrue(dir != null)

    private data class Variant(val dark: Boolean, val locale: String, val fontScale: Float, val width: Int) {
        val name = "${if (dark) "dark" else "light"}-$locale-fs$fontScale-w$width"
    }

    /** Widths in dp; CRUXCOACH_UI_REVIEW_WIDTHS (e.g. "360") renders others. */
    private val widths: List<Int> =
        System.getenv("CRUXCOACH_UI_REVIEW_WIDTHS")?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: listOf(411, 320)

    private val variants = buildList {
        for (fontScale in listOf(1f, 2f)) for (width in widths) {
            for (dark in listOf(true, false)) for (locale in listOf("de", "en")) add(Variant(dark, locale, fontScale, width))
        }
    }.filter { only == null || only in it.name }

    /** Two variants of each size side by side: every theme and language once per size. */
    private val pairs = listOf(1f, 2f).flatMap { fontScale ->
        widths.map { width ->
            val swap = (fontScale == 2f) != (width == 320)
            "fs$fontScale-w$width" to listOf(
                Variant(dark = true, locale = if (swap) "en" else "de", fontScale, width),
                Variant(dark = false, locale = if (swap) "de" else "en", fontScale, width),
            )
        }
    }

    // ------------------------------------------------------------------ data

    private val today = LocalDate.parse("2026-11-01")
    private val clock = Clock.fixed(Instant.parse("2026-11-01T20:00:00Z"), ZoneOffset.UTC)
    private val scale = GradeScale.FRENCH
    private val boards = listOf("kilter", "moonboard", "tension")
    private var id = 0

    private val names = listOf(
        "Crimp Theory", "Blue Monday", "Slab Happy", "Sloper Season", "Left Hand Path", "Tiny Pockets",
        "Heel Hook Heaven", "Morning Coffee", "Big Moves Only", "Compression Club", "Pinch Me", "Dyno Daze",
    )

    private fun entry(
        board: String, climb: String, day: LocalDate, difficulty: Double, tries: Int, isSend: Boolean,
        angle: Long = 40, comment: String? = null, name: String? = null,
    ) = AscentWithClimb(
        uuid = "r-${++id}", climbUuid = climb, angle = angle, isMirror = false, bidCount = tries.toLong(),
        quality = if (isSend) 3 else null, difficulty = difficulty.toLong(), comment = comment,
        climbedAt = "${day}T18:${(id % 60).toString().padStart(2, '0')}:00",
        climbName = name ?: names[climb.hashCode().mod(names.size)], climbFrames = "", difficultyAverage = difficulty,
        isSend = isSend, boardBrand = board,
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

    /** Three boards over half a year, with a busy last session and a new grade. */
    private val logbook: List<AscentWithClimb> = buildList {
        addAll(problems("kilter", 70, 2, today.minusDays(1), 0.0, 14.0))
        addAll(problems("moonboard", 26, 5, today.minusDays(2), 1.6, 13.0))
        addAll(problems("tension", 12, 9, today.minusDays(3), -0.6, 14.0))
        (0 until 6).forEach { add(entry("kilter", "today-$it", today, 15.0 + it, 1 + it % 2, isSend = true)) }
        add(entry("kilter", "today-top", today.minusDays(14), 24.0, 3, isSend = false))
        add(entry("kilter", "today-top", today, 24.0, 2, isSend = true, comment = "Finally! The last move went first go today."))
        add(entry("moonboard", "today-m", today, 18.0, 1, isSend = true, angle = 25))
        add(entry("moonboard", "today-open", today, 21.0, 4, isSend = false, angle = 25))
    }

    /** Three years with a break of four months and some sends without a grade. */
    private val longLogbook: List<AscentWithClimb> = buildList {
        val start = today.minusDays(1100)
        (0 until 110).forEach { i ->
            val day = start.plusDays(i * 10L)
            if (day.isAfter(today.minusDays(500)) && day.isBefore(today.minusDays(380))) return@forEach
            val board = if (i % 3 == 0) "moonboard" else "kilter"
            add(entry(board, "long-$i", day, 14.0 + i % 8, 1 + i % 4, isSend = true))
            if (i % 5 == 0) add(entry(board, "ungraded-$i", day, 14.0, 1, isSend = true).copy(difficultyAverage = null))
        }
    }

    /** A first week on one board: two sends and two open problems. */
    private val firstWeek: List<AscentWithClimb> = listOf(
        entry("kilter", "first-1", today.minusDays(3), 12.0, 2, isSend = true),
        entry("kilter", "first-2", today.minusDays(3), 14.0, 5, isSend = false),
        entry("kilter", "first-3", today, 13.0, 3, isSend = true),
        entry("kilter", "first-2", today, 14.0, 4, isSend = false),
    )

    /** Only attempts so far, nothing sent. */
    private val onlyAttempts: List<AscentWithClimb> = listOf(
        entry("kilter", "open-1", today.minusDays(1), 16.0, 4, isSend = false),
        entry("kilter", "open-2", today, 17.0, 6, isSend = false),
    )

    /** Climbed, but nothing in the last 30 days. */
    private val breakLogbook: List<AscentWithClimb> = problems("kilter", 20, 3, today.minusDays(45), 0.0, 14.0)

    private fun zonesOf(log: List<AscentWithClimb>) = IntensityZoneEngine.computeZones(log.mapNotNull { it.difficultyAverage })

    private fun brandsOf(log: List<AscentWithClimb>) = boards.filter { b -> log.any { it.boardBrand == b } }

    // ----------------------------------------------------------- rendering

    @Composable
    private fun Localized(v: Variant, content: @Composable () -> Unit) {
        val base = LocalContext.current
        val density = LocalDensity.current
        val config = remember(v) {
            Configuration(base.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(v.locale))
                fontScale = v.fontScale
                screenWidthDp = v.width
            }
        }
        val context = remember(v) { base.createConfigurationContext(config) }
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides config,
            LocalResources provides context.resources,
            LocalDensity provides Density(density.density, v.fontScale),
        ) { content() }
    }

    /**
     * Draws [content] in every variant into `<dir>/<name>/`; [before] runs
     * after the first frame of each variant (e.g. a tap).
     */
    private fun shoot(name: String, before: (Variant) -> Unit = {}, content: @Composable (Variant) -> Unit) {
        var variant by mutableStateOf(variants.first())
        var view: android.view.View? = null
        var bounds = Rect.Zero
        Locale.setDefault(Locale.forLanguageTag(variant.locale))
        compose.setContent {
            view = LocalView.current
            val v = variant
            key(v) {
                Localized(v) {
                    CruxCoachTheme(if (v.dark) DarkModeSetting.DARK else DarkModeSetting.LIGHT) {
                        Box(Modifier.fillMaxSize()) {
                            Surface(Modifier.width(v.width.dp).onGloballyPositioned { bounds = it.boundsInRoot() }) {
                                Box(Modifier.fillMaxWidth().padding(16.dp)) { content(v) }
                            }
                        }
                    }
                }
            }
        }
        val out = File(dir!!, name).apply { mkdirs() }
        variants.forEach { v ->
            Locale.setDefault(Locale.forLanguageTag(v.locale))
            variant = v
            compose.waitForIdle()
            before(v)
            compose.waitForIdle()
            compose.runOnIdle {
                val box = bounds
                val bitmap = Bitmap.createBitmap(
                    box.width.roundToInt().coerceAtLeast(1), box.height.roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888,
                )
                val canvas = Canvas(bitmap)
                canvas.translate(-box.left, -box.top)
                requireNotNull(view).draw(canvas)
                File(out, "${v.name}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        Locale.setDefault(Locale.ENGLISH)
        pairs.forEach { (size, members) -> pairSheets(out, size, members) }
    }

    /** [members] side by side, cut into pieces of at most [PIECE] pixels height. */
    private fun pairSheets(out: File, size: String, members: List<Variant>) {
        val shots = members.mapNotNull { v ->
            File(out, "${v.name}.png").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
        }
        if (shots.size < 2) return
        val gap = 24
        val width = shots.sumOf { it.width } + gap * (shots.size - 1)
        val height = shots.maxOf { it.height }
        val pieces = (height + PIECE - 1) / PIECE
        repeat(pieces) { piece ->
            val top = piece * PIECE
            val h = minOf(PIECE, height - top)
            val sheet = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(sheet)
            canvas.drawColor(android.graphics.Color.rgb(128, 128, 128))
            var left = 0
            shots.forEach { shot ->
                if (top < shot.height) {
                    val src = android.graphics.Rect(0, top, shot.width, minOf(shot.height, top + h))
                    val dst = android.graphics.Rect(left, 0, left + shot.width, src.height())
                    canvas.drawBitmap(shot, src, dst, null)
                }
                left += shot.width + gap
            }
            File(out, "pair-$size-${piece + 1}.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
            sheet.recycle()
        }
        shots.forEach { it.recycle() }
    }

    // --------------------------------------------------------------- screens

    @Composable
    private fun Sheet(
        log: List<AscentWithClimb>,
        interval: StatsTimeInterval = StatsTimeInterval.ALL,
        gradesView: GradesView = GradesView.PROBLEMS,
        crossView: CrossBoardView = CrossBoardView.TRIES_UNTIL_SEND,
        activityView: ActivityView = ActivityView.CALENDAR,
        boardFilter: String? = null,
        loading: Boolean = false,
    ) {
        val brands = brandsOf(log)
        val scoped = boardFilter?.let { b -> log.filter { it.boardBrand == b } } ?: log
        BoardStatsSheetContent(
            stats = BoardStatsComputer.computeStats(scoped, interval, scale, clock = clock),
            statsInterval = interval,
            gradeScale = scale,
            zones = zonesOf(log),
            boardFilter = boardFilter,
            availableBoardBrands = brands,
            boardComparison = BoardStatsComputer.computeBoardComparison(log, interval, scale, clock = clock),
            crossBoard = CrossBoardStatsComputer.compute(log, interval, scale, clock = clock),
            crossBoardView = crossView,
            progressionByBoard = if (boardFilter == null && brands.size > 1) {
                BoardStatsComputer.gradeProgressionByBoard(log, interval, clock = clock)
            } else {
                emptyMap()
            },
            activityView = activityView,
            gradesView = gradesView,
            onIntervalSelect = {},
            onCustomDateRange = { _, _ -> },
            loading = loading,
        )
    }

    @Composable
    private fun LogbookTop(log: List<AscentWithClimb>, days: Int = 2) {
        val flashes = BoardStatsComputer.trueFlashUuids(log)
        BoxWithConstraints {
            // One layout for every entry, decided over the whole logbook like the app's list.
            val layout = logbookEntryLayout(log, flashes, maxWidth)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LogbookOverviewCard(
                    StatsHighlightsComputer.atAGlance(log, scale, flashes, clock),
                    StatsHighlightsComputer.milestones(log, scale, BoardStatsComputer.weekStreakOf(log, clock)),
                    brandsOf(log),
                    onOpenStats = {},
                )
                log.sortedByDescending { it.climbedAt }.groupBy { it.climbedAt.take(10) }.entries.take(days).forEach { (day, rows) ->
                    DayHeader(day, rows.size, onRecap = {})
                    rows.take(5).forEach { row ->
                        AscentCard(
                            ascent = row, gradeScale = scale, zones = zonesOf(log), isTrueFlash = row.uuid in flashes,
                            isSelected = false, onClick = {}, onToggleSelect = {}, onEdit = {}, showDate = false,
                            layout = layout,
                        )
                    }
                }
            }
        }
    }

    /** The logbook list: the overview over the newest two days. */
    @Test
    fun logbook() = shoot("logbook") { LogbookTop(logbook) }

    /** The logbook of a first week, of only attempts, and with nothing in the last 30 days. */
    @Test
    fun logbookSmall() = shoot("logbook-small") {
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            LogbookTop(firstWeek, days = 1)
            LogbookTop(onlyAttempts, days = 1)
            LogbookTop(breakLogbook, days = 1)
        }
    }

    /** No entries yet. */
    @Test
    fun logbookEmpty() = shoot("logbook-empty") {
        Box(Modifier.fillMaxWidth().height(480.dp)) { EmptyLogbookMessage() }
    }

    @Test
    fun filters() = shoot("logbook-filters") {
        LogbookFilterContent(
            state = BoardLogbookState(
                availableBoardBrands = boards,
                availableLogbookAngles = listOf(20, 25, 30, 40, 45),
                logbookInterval = StatsTimeInterval.DAYS_90,
            ),
            onIntervalSelect = {}, onOutcomeSelect = {}, onBoardSelect = {}, onAngleSelect = {}, onDone = {},
        )
    }

    /** The whole statistics sheet with three boards, every entry. */
    @Test
    fun sheet() = shoot("sheet") { Sheet(logbook) }

    /** The sheet before its first numbers: the filters and the loading sign. */
    @Test
    fun sheetLoading() {
        // The spinner never ends, so the clock stands still and only moves on per shot.
        compose.mainClock.autoAdvance = false
        shoot("sheet-loading", before = { compose.mainClock.advanceTimeBy(400) }) { Sheet(logbook, loading = true) }
    }

    /** The other views of each card. */
    @Test
    fun sheetViews() = shoot("sheet-views") {
        Sheet(logbook, gradesView = GradesView.SENDS, crossView = CrossBoardView.TRIES_PER_GRADE, activityView = ActivityView.DAYS_PER_WEEK)
    }

    @Test
    fun sheetMonths() = shoot("sheet-months") {
        Sheet(logbook, StatsTimeInterval.DAYS_90, crossView = CrossBoardView.PER_MONTH, activityView = ActivityView.SENDS)
    }

    /** One board picked in the board chips. */
    @Test
    fun sheetBoardFilter() = shoot("sheet-board-filter") {
        Sheet(logbook, boardFilter = "moonboard", activityView = ActivityView.ENTRIES)
    }

    @Test
    fun sheetLong() = shoot("sheet-long") {
        Sheet(longLogbook, crossView = CrossBoardView.PER_MONTH, activityView = ActivityView.DAYS_PER_WEEK, gradesView = GradesView.SENDS)
    }

    @Test
    fun sheetFirstWeek() = shoot("sheet-first-week") { Sheet(firstWeek) }

    @Test
    fun sheetOnlyAttempts() = shoot("sheet-only-attempts") { Sheet(onlyAttempts) }

    /** 30 days without an entry. */
    @Test
    fun sheetEmptyPeriod() = shoot("sheet-empty-period") { Sheet(breakLogbook, StatsTimeInterval.DAYS_30) }

    /** A tapped grade and a tapped board in the tries. */
    @Test
    fun sheetTapped() {
        val stats = BoardStatsComputer.computeStats(logbook, StatsTimeInterval.ALL, scale, clock = clock)
        val cross = CrossBoardStatsComputer.compute(logbook, StatsTimeInterval.ALL, scale, clock = clock)
        shoot(
            "sheet-tapped",
            before = {
                compose.onAllNodesWithText(stats.gradeOutcomes.last().grade).onFirst().performClick()
                compose.onAllNodesWithText("MoonBoard").onFirst().performClick()
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                GradesCard(stats.gradeOutcomes, stats.gradePyramid, zonesOf(logbook), GradesView.PROBLEMS, onSelect = {})
                CrossBoardSection(cross, CrossBoardView.TRIES_UNTIL_SEND, onViewSelect = {}, allBoards = boards)
            }
        }
    }

    @Composable
    private fun Recap(recap: SessionRecap) {
        val layer = rememberGraphicsLayer()
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SessionRecapImage(recap, layer)
            ShareRecapButton(recap, layer)
        }
    }

    /** The recap of a busy day on two boards with a new grade. */
    @Test
    fun recap() = shoot("recap") { Recap(StatsHighlightsComputer.sessionRecap(logbook, today, scale)!!) }

    /** A small day, and a day of attempts only. */
    @Test
    fun recapSmall() = shoot("recap-small") {
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Recap(StatsHighlightsComputer.sessionRecap(firstWeek, today, scale)!!)
            Recap(StatsHighlightsComputer.sessionRecap(onlyAttempts, today, scale)!!)
        }
    }

    /** The summary at the end of a timed session, with its card and without the logbook history. */
    @Test
    fun sessionSummary() = shoot("session-summary") {
        val session = Board_sessions(1, "${today}T17:00:00", "${today}T18:32:00", 5520, 1260, 9, 21)
        val rows = logbook.filter { it.climbedAt.startsWith(today.toString()) }
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            SessionSummaryContent(
                session,
                EnhancedSessionSummary(
                    warmupCount = 3, optimalCount = 4, limitCount = 2, flashCount = 4, totalSends = 8, totalAttempts = 21,
                    recap = StatsHighlightsComputer.recapOf(rows, logbook, scale),
                ),
                zonesOf(logbook),
                onDone = {},
            )
            SessionSummaryContent(
                session,
                EnhancedSessionSummary(
                    warmupCount = 3, optimalCount = 4, limitCount = 2, flashCount = 4, totalSends = 8, totalAttempts = 21,
                    hardestSendGrade = "7a", hardestSendName = "Crimp Theory",
                ),
                zonesOf(logbook),
                onDone = {},
            )
        }
    }

    private companion object {
        const val PIECE = 1800
    }
}
