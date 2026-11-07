package com.cruxcoach.android.ui.yearreview

import android.app.Activity
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.board.BoardSeriesColors
import com.cruxcoach.android.ui.common.InfoButton
import com.cruxcoach.android.ui.theme.DarkBackground
import com.cruxcoach.android.ui.theme.DarkSurface
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.domain.yearreview.YearReview
import com.cruxcoach.domain.yearreview.YearReviewComputer
import kotlinx.coroutines.launch

private val PageText = Color.White
private val PageMuted = Color.White.copy(alpha = 0.7f)
private val PageFaint = Color.White.copy(alpha = 0.5f)

/** The pages' side margin: the app's 16 dp, in line with the icons of the top bar. */
private val PageMargin = 16.dp

/**
 * The year in review (#64): full-screen pages you tap through, left for back and
 * right for on, with no automatic advance and no sound. Holding a page hides the
 * controls for a clean view. With a screen reader on, or by choice, the pages
 * show as one list.
 */
@Composable
fun YearReviewScreen(onClose: () -> Unit, viewModel: YearReviewViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val review = state.review
    LightSystemBarIcons()
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(DarkBackground, DarkSurface))),
    ) {
        when {
            state.loading -> Loading(state.year, onClose)
            state.failed -> LoadFailed(onRetry = viewModel::retry, onClose = onClose)
            review == null || !review.hasEnoughData -> SoFar(state.year, review, onClose)
            else -> {
                val pages = reviewPages(review, state.gradeScale, state.training)
                val colors = reviewBoardColors(review)
                var listView by rememberSaveable { mutableStateOf(screenReaderOn(context)) }
                var sharing by remember { mutableStateOf<ReviewPageKind?>(null) }
                // The page stays when switching between the pages and the list.
                var index by rememberSaveable { mutableIntStateOf(0) }
                if (listView) {
                    ReviewList(pages, reviewTitle(review), onClose, onShare = { sharing = it }, onPages = { listView = false }, startAt = index, boardColors = colors)
                } else {
                    ReviewPager(pages, index, onIndex = { index = it }, onClose, onShare = { sharing = it }, onList = { listView = true }, boardColors = colors)
                }
                sharing?.let { kind -> ShareSheet(review, state, kind, onDismiss = { sharing = null }) }
            }
        }
    }
}

/**
 * Each board's colour as in the statistics, always in its dark step: the review
 * is dark in both themes, and its share images are too.
 */
internal fun reviewBoardColors(review: YearReview): Map<String, Color> =
    BoardSeriesColors.slots(review.boards.map { it.board }).mapValues { (_, slot) -> BoardSeriesColors.color(slot, dark = true, fallback = PageMuted) }

/** The review is dark in both themes, so the status and navigation bar icons turn light while it shows. */
@Composable
private fun LightSystemBarIcons() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        val status = bars?.isAppearanceLightStatusBars
        val navigation = bars?.isAppearanceLightNavigationBars
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        onDispose {
            status?.let { bars?.isAppearanceLightStatusBars = it }
            navigation?.let { bars?.isAppearanceLightNavigationBars = it }
        }
    }
}

internal fun screenReaderOn(context: Context): Boolean =
    (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)?.isTouchExplorationEnabled == true

private fun animationsOn(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

/** Close on the left, an optional title, and the screen's actions on the right: the same bar on every screen of the review. */
@Composable
private fun ReviewTopBar(onClose: () -> Unit, title: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.yr_close), tint = PageText) }
        if (title != null) {
            Text(
                title,
                color = PageText,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp).semantics { heading() },
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        actions()
    }
}

// ── Pages ────────────────────────────────────────────────────────────

@Composable
internal fun ReviewPager(
    pages: List<ReviewPage>,
    index: Int,
    onIndex: (Int) -> Unit,
    onClose: () -> Unit,
    onShare: (ReviewPageKind) -> Unit,
    onList: () -> Unit,
    boardColors: Map<String, Color> = emptyMap(),
) {
    val current by androidx.compose.runtime.rememberUpdatedState(index)
    var held by remember { mutableStateOf(false) }
    val page = pages[index.coerceIn(0, pages.lastIndex)]
    val previous = stringResource(R.string.yr_previous)
    val next = stringResource(R.string.yr_next)
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Holding a page hides the controls without moving the page.
        Column(Modifier.alpha(if (held) 0f else 1f)) {
            ProgressSegments(pages.size, index)
            ReviewTopBar(onClose) {
                // How the page counts, on demand, beside the page's other actions.
                page.howCalculated?.let { InfoButton(page.title, it, tint = PageText) }
                IconButton(onClick = onList) { Icon(Icons.AutoMirrored.Filled.ViewList, stringResource(R.string.yr_list_view), tint = PageText) }
                IconButton(onClick = { onShare(page.kind) }) { Icon(Icons.Filled.Share, stringResource(R.string.yr_share), tint = PageText) }
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(pages.size) {
                    detectTapGestures(
                        onPress = {
                            held = true
                            tryAwaitRelease()
                            held = false
                        },
                        // A long hold only pauses; letting go does not turn the page.
                        onLongPress = {},
                        onTap = { offset ->
                            onIndex(if (offset.x < size.width / 3f) (current - 1).coerceAtLeast(0) else (current + 1).coerceAtMost(pages.lastIndex))
                        },
                    )
                }
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(previous) { onIndex((current - 1).coerceAtLeast(0)); true },
                        CustomAccessibilityAction(next) { onIndex((current + 1).coerceAtMost(pages.lastIndex)); true },
                    )
                },
        ) {
            PageContent(page, index, pages.size, boardColors)
        }
    }
}

@Composable
private fun ProgressSegments(count: Int, current: Int) {
    val description = stringResource(R.string.yr_page_of, current + 1, count)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(count) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i <= current) OrangeAccent else Color.White.copy(alpha = 0.25f)),
            )
        }
    }
}

@Composable
internal fun PageContent(page: ReviewPage, index: Int, count: Int, boardColors: Map<String, Color> = emptyMap()) {
    val context = LocalContext.current
    val animate = remember { animationsOn(context) }
    val appear = remember(index) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(index) { if (animate) appear.animateTo(1f, tween(450)) }
    // Each page starts at its top, also after a long page was scrolled down.
    val scroll = remember(index) { ScrollState(0) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxHeight
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(scroll)
                // A short page sits a little above the middle, as on its share image; a long one scrolls.
                .heightIn(min = viewport)
                .padding(horizontal = PageMargin, vertical = 8.dp)
                .alpha(appear.value),
            verticalArrangement = Arrangement.spacedBy(12.dp, BiasAlignment.Vertical(-0.4f)),
        ) {
            // The page's line above the title is for the shared image; here the progress bar and the title say where you are.
            Text(page.title, color = PageText, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            page.hero?.let {
                Text(it, color = OrangeAccent, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Black)
            }
            page.heroLabel?.let { Text(it, color = PageMuted, style = MaterialTheme.typography.titleMedium) }
            page.lead?.let { Text(it, color = PageText, style = MaterialTheme.typography.bodyLarge) }
            page.months?.let { MonthBars(it, page.busiestMonth) }
            if (page.rows.isNotEmpty()) Spacer(Modifier.height(4.dp))
            // The summary is the year's recap card, a grid like its share image, not one more table.
            if (page.kind == ReviewPageKind.SUMMARY) SummaryGrid(page.rows, boardColors) else RowsList(page.rows, boardColors)
            Spacer(Modifier.height(32.dp))
        }
        // A long page fades out at the bottom, so it shows that it scrolls on.
        if (scroll.canScrollForward) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, DarkSurface))),
            )
        }
    }
}

/** The summary's numbers two by two: the value large, its label below. */
@Composable
private fun SummaryGrid(rows: List<ReviewRow>, colors: Map<String, Color>) {
    rows.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            pair.forEach { row ->
                Column(Modifier.weight(1f)) {
                    Text(row.value, color = PageText, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    BoardLabel(row, colors, PageMuted, MaterialTheme.typography.bodyMedium)
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * Label and value per row, the detail below. A row keeps label and value side by
 * side when the label fits on one line beside the value; otherwise label, value
 * and detail stack at the left edge (large fonts, long German words, narrow
 * phones). A board's dot sits in front of its label, so every row starts at the
 * same edge.
 */
@Composable
private fun RowsList(rows: List<ReviewRow>, colors: Map<String, Color>, textColor: Color = PageText, labelColor: Color = PageMuted, detailColor: Color = PageFaint) {
    if (rows.isEmpty()) return
    val labelStyle = MaterialTheme.typography.bodyLarge
    val valueStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = with(density) { 12.dp.roundToPx() }
        val dot = with(density) { DotSlot.roundToPx() }
        val width = constraints.maxWidth
        Column {
            rows.forEachIndexed { i, row ->
                if (i > 0) HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
                val side = row.value.isEmpty() ||
                    measurer.measure(row.label, labelStyle).size.width + (if (row.board != null) dot else 0) + gap +
                    measurer.measure(row.value.unbroken(), valueStyle).size.width <= width
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    if (side) {
                        Row(verticalAlignment = Alignment.Top) {
                            BoardLabel(row, colors, labelColor, labelStyle, Modifier.weight(1f).padding(end = 12.dp))
                            if (row.value.isNotEmpty()) Text(row.value.unbroken(), color = textColor, style = valueStyle)
                        }
                    } else {
                        BoardLabel(row, colors, labelColor, labelStyle)
                        Text(row.value.unbroken(), color = textColor, style = valueStyle)
                    }
                    row.detail?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = detailColor, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** A row's label, with its board's dot in front when it belongs to a board. */
@Composable
private fun BoardLabel(row: ReviewRow, colors: Map<String, Color>, color: Color, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        row.board?.let { board ->
            Box(
                Modifier
                    .padding(end = 8.dp)
                    .size(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(colors[board] ?: color),
            )
        }
        Text(row.label, color = color, style = style)
    }
}

private val DotSlot = 18.dp

/** A value stays on one line: "324 entries", never "324" over "entries". */
private fun String.unbroken(): String = replace(' ', '\u00A0')

@Composable
private fun MonthBars(perMonth: List<Int>, busiest: Int?) {
    val most = (perMonth.maxOrNull() ?: 0).coerceAtLeast(1)
    val locale = LocalConfiguration.current.locales[0]
    Row(
        Modifier
            .fillMaxWidth()
            .height(120.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        perMonth.forEachIndexed { i, days ->
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                // The bars share the height the month letter leaves, so a large font never pushes a letter down.
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight((days.toFloat() / most).coerceAtLeast(if (days > 0) 0.05f else 0.02f) * 0.9f)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (i + 1 == busiest) OrangeAccent else PageMuted),
                    )
                }
                Text(
                    java.time.Month.of(i + 1).getDisplayName(java.time.format.TextStyle.NARROW_STANDALONE, locale),
                    color = PageFaint,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
    }
}

// ── List view for screen readers ─────────────────────────────────────

@Composable
internal fun ReviewList(
    pages: List<ReviewPage>,
    title: String,
    onClose: () -> Unit,
    onShare: (ReviewPageKind) -> Unit,
    onPages: () -> Unit,
    startAt: Int = 0,
    boardColors: Map<String, Color> = emptyMap(),
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = startAt.coerceIn(0, pages.lastIndex.coerceAtLeast(0)))
    val locale = LocalConfiguration.current.locales[0]
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        ReviewTopBar(onClose, title) {
            IconButton(onClick = onPages) { Icon(Icons.Filled.ViewCarousel, stringResource(R.string.yr_page_view), tint = PageText) }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = PageMargin), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(pages, key = { it.kind }) { page ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            page.title,
                            color = PageText,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f).padding(top = 6.dp).semantics { heading() },
                        )
                        page.howCalculated?.let { InfoButton(page.title, it, tint = PageMuted) }
                    }
                    page.hero?.let { Text(it, color = OrangeAccent, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                    page.heroLabel?.let { Text(it, color = PageMuted, style = MaterialTheme.typography.bodyLarge) }
                    page.lead?.let { Text(it, color = PageText, style = MaterialTheme.typography.bodyLarge) }
                    // The month chart as rows of text.
                    page.months?.let { months ->
                        RowsList(
                            months.mapIndexed { i, days ->
                                ReviewRow(
                                    java.time.Month.of(i + 1).getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, locale),
                                    pluralStringResource(R.plurals.yr_days, days, days.toString()),
                                )
                            },
                            emptyMap(),
                        )
                    }
                    RowsList(page.rows, boardColors)
                    // A screen reader hears which page the button shares, not just "Share" twelve times.
                    val shareLabel = stringResource(R.string.yr_share_page, page.title)
                    TextButton(onClick = { onShare(page.kind) }, modifier = Modifier.semantics { contentDescription = shareLabel }) {
                        Icon(Icons.Filled.Share, contentDescription = null, tint = PageMuted, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.yr_share), color = PageMuted)
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.25f))
                }
            }
        }
    }
}

// ── Loading, below the minimum, failed ───────────────────────────────

@Composable
internal fun Loading(year: Int, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        ReviewTopBar(onClose)
        Text(
            stringResource(R.string.yr_little_title, year),
            color = PageText,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = PageMargin, vertical = 8.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = OrangeAccent)
        }
    }
}

/** A year below the minimum: how far it got, and on demand what opens the full review. */
@Composable
internal fun SoFar(year: Int, review: YearReview?, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        ReviewTopBar(onClose)
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PageMargin, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(littleTitle(year, review), color = PageText, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            if (review == null || review.isEmpty()) {
                Text(emptyText(year, review), color = PageMuted, style = MaterialTheme.typography.bodyLarge)
            } else {
                // What is missing stays in view: what opens the full review, or that the year is over without it.
                Text(minimumLine(review), color = PageMuted, style = MaterialTheme.typography.bodyLarge)
                RowsList(littleRows(review), emptyMap())
            }
        }
    }
}

/** The logbook could not be read: say so, rather than show an empty year. */
@Composable
internal fun LoadFailed(onRetry: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        ReviewTopBar(onClose)
        Column(Modifier.padding(horizontal = PageMargin, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.yr_failed), color = PageText, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent, contentColor = DarkBackground),
                shape = RoundedCornerShape(12.dp),
            ) { Text(stringResource(R.string.action_retry), fontWeight = FontWeight.Bold) }
        }
    }
}

/** "Your 2026 so far" while the year runs, "Your 2025" once it is over. */
@Composable
fun littleTitle(year: Int, review: YearReview?): String =
    stringResource(if (review?.isFinal == true) R.string.yr_little_title else R.string.yr_so_far_title, year)

internal fun YearReview.isEmpty(): Boolean = volume.climbingDays == 0 && volume.tries == 0

/** "Nothing logged in 2026 yet", or for a year that is over "Nothing logged in 2025". */
@Composable
internal fun emptyText(year: Int, review: YearReview?): String =
    stringResource(if (review?.isFinal == true) R.string.yr_empty else R.string.yr_empty_so_far, year)

/** The review's name: "Your 2026 on the boards", or "Your 2026 so far" while the year runs. */
@Composable
internal fun reviewTitle(review: YearReview): String =
    stringResource(if (review.isFinal) R.string.yr_card_title else R.string.yr_card_title_so_far, review.year)

/**
 * Climbing days and tries: against what opens the full review while the year runs
 * ("2 of 8"), as plain numbers once it is over.
 */
@Composable
internal fun littleRows(review: YearReview): List<ReviewRow> {
    val days = review.volume.climbingDays.toString()
    val tries = review.volume.tries.toString()
    return listOf(
        ReviewRow(
            stringResource(R.string.yr_volume_days_row),
            if (review.isFinal) days else stringResource(R.string.yr_of, days, YearReviewComputer.MIN_CLIMBING_DAYS.toString()),
        ),
        ReviewRow(
            stringResource(R.string.yr_volume_tries),
            if (review.isFinal) tries else stringResource(R.string.yr_of, tries, YearReviewComputer.MIN_TRIES.toString()),
        ),
    )
}

/** What opens the full review, or for a year that is over, that it had too little. */
@Composable
internal fun minimumLine(review: YearReview): String = if (review.isFinal) {
    stringResource(R.string.yr_minimum_final, review.year)
} else {
    stringResource(
        R.string.yr_minimum_so_far,
        pluralStringResource(R.plurals.yr_climbing_days, YearReviewComputer.MIN_CLIMBING_DAYS, YearReviewComputer.MIN_CLIMBING_DAYS.toString()),
        pluralStringResource(R.plurals.yr_tries, YearReviewComputer.MIN_TRIES, YearReviewComputer.MIN_TRIES.toString()),
    )
}

// ── Sharing ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareSheet(review: YearReview, state: YearReviewViewModel.State, kind: ReviewPageKind, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showName by rememberSaveable { mutableStateOf(false) }
    var showProblems by rememberSaveable { mutableStateOf(false) }
    var showDates by rememberSaveable { mutableStateOf(false) }
    val privacy = SharePrivacy(name = state.displayName.takeIf { showName && it.isNotEmpty() }, problemNames = showProblems, exactDates = showDates)
    val page = reviewPages(review, state.gradeScale, state.training, privacy).firstOrNull { it.kind == kind } ?: return
    // Only the switches that change this page's image.
    val open = reviewPages(review, state.gradeScale, state.training, SharePrivacy()).firstOrNull { it.kind == kind }
    val namesMatter = open != reviewPages(review, state.gradeScale, state.training, SharePrivacy(problemNames = false)).firstOrNull { it.kind == kind }
    val datesMatter = open != reviewPages(review, state.gradeScale, state.training, SharePrivacy(exactDates = false)).firstOrNull { it.kind == kind }
    val header = privacy.name?.let { stringResource(R.string.yr_share_name_header, it.take(MAX_NAME), review.year) }
    val wordmark = stringResource(R.string.yr_share_wordmark)
    val colors = reviewBoardColors(review).mapValues { it.value.toArgb() }
    val bitmap = remember(page, header, colors) { YearShareImage.render(page, header, wordmark, colors) }
    val chooser = stringResource(R.string.yr_share_chooser)
    val failed = stringResource(R.string.yr_share_failed)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        ShareSheetContent(
            bitmap = bitmap,
            description = page.title,
            nameAvailable = state.displayName.isNotEmpty(),
            showName = showName,
            showProblems = showProblems,
            showDates = showDates,
            problemsToggle = namesMatter,
            datesToggle = datesMatter,
            onShowName = { showName = it },
            onShowProblems = { showProblems = it },
            onShowDates = { showDates = it },
            onSend = {
                scope.launch {
                    val ok = runCatching { YearShareImage.share(context, bitmap, "cruxcoach-${review.year}-${kind.name.lowercase()}", chooser) }.isSuccess
                    if (!ok) Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}

/**
 * The share sheet's body: the image as it will be sent and what it shows, with the
 * send button pinned below, so it stays in reach at any font size.
 */
@Composable
internal fun ShareSheetContent(
    bitmap: android.graphics.Bitmap,
    description: String,
    nameAvailable: Boolean,
    showName: Boolean,
    showProblems: Boolean,
    showDates: Boolean,
    problemsToggle: Boolean = true,
    datesToggle: Boolean = true,
    onShowName: (Boolean) -> Unit,
    onShowProblems: (Boolean) -> Unit,
    onShowDates: (Boolean) -> Unit,
    onSend: () -> Unit,
) {
    // The preview takes about a third of the screen, the switches the rest.
    val previewHeight = (LocalConfiguration.current.screenHeightDp * 0.34f).dp
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val title = stringResource(R.string.yr_share_title)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).semantics { heading() })
                InfoButton(title, stringResource(R.string.yr_share_hint))
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Image(
                    bitmap.asImageBitmap(),
                    contentDescription = description,
                    modifier = Modifier
                        .heightIn(max = previewHeight)
                        .aspectRatio(bitmap.width.toFloat() / bitmap.height)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }
            // Without a display name there is nothing to show, so no switch.
            if (nameAvailable) Toggle(stringResource(R.string.yr_share_show_name), showName, onChange = onShowName)
            if (problemsToggle) Toggle(stringResource(R.string.yr_share_show_problems), showProblems, onChange = onShowProblems)
            if (datesToggle) Toggle(stringResource(R.string.yr_share_show_dates), showDates, onChange = onShowDates)
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onSend,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent, contentColor = DarkBackground),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.yr_share_send), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyLarge)
        // The row toggles; the switch only shows the state.
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Long display names are cut on the image. */
private const val MAX_NAME = 24
