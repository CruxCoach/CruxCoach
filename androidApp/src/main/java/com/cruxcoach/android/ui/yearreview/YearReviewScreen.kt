package com.cruxcoach.android.ui.yearreview

import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.board.boardSeriesColors
import com.cruxcoach.android.ui.theme.DarkBackground
import com.cruxcoach.android.ui.theme.DarkSurface
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.domain.yearreview.YearReview
import kotlinx.coroutines.launch

private val PageText = Color.White
private val PageMuted = Color.White.copy(alpha = 0.7f)
private val PageFaint = Color.White.copy(alpha = 0.5f)

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
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(DarkBackground, DarkSurface))),
    ) {
        when {
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = OrangeAccent)
            review == null || !review.hasEnoughData -> SoFar(state.year, review, onClose)
            else -> {
                val pages = reviewPages(review, state.gradeScale, state.training)
                var listView by rememberSaveable { mutableStateOf(screenReaderOn(context)) }
                var sharing by remember { mutableStateOf<ReviewPageKind?>(null) }
                // The page stays when switching between the pages and the list.
                var index by rememberSaveable { mutableIntStateOf(0) }
                if (listView) {
                    ReviewList(pages, onClose, onShare = { sharing = it }, onPages = { listView = false }, startAt = index)
                } else {
                    ReviewPager(pages, index, onIndex = { index = it }, onClose, onShare = { sharing = it }, onList = { listView = true })
                }
                sharing?.let { kind -> ShareSheet(review, state, kind, onDismiss = { sharing = null }) }
            }
        }
    }
}

private fun screenReaderOn(context: Context): Boolean =
    (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)?.isTouchExplorationEnabled == true

private fun animationsOn(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

// ── Pages ────────────────────────────────────────────────────────────

@Composable
private fun ReviewPager(
    pages: List<ReviewPage>,
    index: Int,
    onIndex: (Int) -> Unit,
    onClose: () -> Unit,
    onShare: (ReviewPageKind) -> Unit,
    onList: () -> Unit,
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
            Column {
                ProgressSegments(pages.size, index)
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.yr_close), tint = PageText) }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onList) { Icon(Icons.AutoMirrored.Filled.ViewList, stringResource(R.string.yr_list_view), tint = PageText) }
                    IconButton(onClick = { onShare(page.kind) }) { Icon(Icons.Filled.Share, stringResource(R.string.yr_share), tint = PageText) }
                }
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
            PageContent(page, index, pages.size)
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
internal fun PageContent(page: ReviewPage, index: Int, count: Int) {
    val context = LocalContext.current
    val animate = remember { animationsOn(context) }
    val appear = remember(index) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(index) { if (animate) appear.animateTo(1f, tween(450)) }
    var explaining by remember(index) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 16.dp)
            .alpha(appear.value),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(page.eyebrow, color = OrangeAccent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text(page.title, color = PageText, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        page.hero?.let {
            Text(it, color = OrangeAccent, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Black)
        }
        page.heroLabel?.let { Text(it, color = PageMuted, style = MaterialTheme.typography.titleMedium) }
        page.lead?.let { Text(it, color = PageText, style = MaterialTheme.typography.bodyLarge) }
        page.months?.let { MonthBars(it) }
        if (page.rows.isNotEmpty()) Spacer(Modifier.height(4.dp))
        RowsList(page.rows)
        page.howCalculated?.let {
            TextButton(onClick = { explaining = true }) {
                Text(stringResource(R.string.yr_how_calculated), color = PageMuted)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (explaining) {
        AlertDialog(
            onDismissRequest = { explaining = false },
            confirmButton = { TextButton(onClick = { explaining = false }) { Text(stringResource(android.R.string.ok)) } },
            title = { Text(stringResource(R.string.yr_how_calculated)) },
            text = { Text(page.howCalculated.orEmpty()) },
        )
    }
}

@Composable
private fun RowsList(rows: List<ReviewRow>) {
    val colors = boardSeriesColors(rows.mapNotNull { it.board })
    rows.forEachIndexed { i, row ->
        if (i > 0) HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
            row.board?.let { board ->
                Box(
                    Modifier
                        .padding(top = 6.dp, end = 10.dp)
                        .size(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(colors[board] ?: PageMuted),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(row.label, color = PageMuted, style = MaterialTheme.typography.bodyLarge)
                row.detail?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = PageFaint, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (row.value.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                Text(
                    row.value,
                    color = PageText,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(max = 200.dp),
                )
            }
        }
    }
}

@Composable
private fun MonthBars(perMonth: List<Int>) {
    val most = (perMonth.maxOrNull() ?: 0).coerceAtLeast(1)
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    Row(
        Modifier
            .fillMaxWidth()
            .height(120.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        perMonth.forEachIndexed { i, days ->
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight((days.toFloat() / most).coerceAtLeast(if (days > 0) 0.05f else 0.02f) * 0.8f)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (days == most) OrangeAccent else PageMuted),
                )
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
    onClose: () -> Unit,
    onShare: (ReviewPageKind) -> Unit,
    onPages: () -> Unit,
    startAt: Int = 0,
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = startAt.coerceIn(0, pages.lastIndex.coerceAtLeast(0)))
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.yr_close), tint = PageText) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onPages) { Icon(Icons.Filled.ViewCarousel, stringResource(R.string.yr_page_view), tint = PageText) }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(pages, key = { it.kind }) { page ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(page.title, color = PageText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    val hero = listOfNotNull(page.hero, page.heroLabel).joinToString(" ")
                    if (hero.isNotBlank()) Text(hero, color = OrangeAccent, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    page.lead?.let { Text(it, color = PageText, style = MaterialTheme.typography.bodyLarge) }
                    page.months?.let { months ->
                        val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
                        Text(
                            months.mapIndexed { i, days ->
                                java.time.Month.of(i + 1).getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, locale) + " " + days
                            }.joinToString(", "),
                            color = PageMuted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    RowsList(page.rows)
                    page.howCalculated?.let { Text(it, color = PageFaint, style = MaterialTheme.typography.bodyMedium) }
                    TextButton(onClick = { onShare(page.kind) }) { Text(stringResource(R.string.yr_share), color = OrangeAccent) }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.25f))
                }
            }
        }
    }
}

// ── Below the minimum ────────────────────────────────────────────────

@Composable
private fun SoFar(year: Int, review: YearReview?, onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.yr_close), tint = PageText) }
        Text(stringResource(R.string.yr_so_far_title, year), color = PageText, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(soFarText(review), color = PageMuted, style = MaterialTheme.typography.bodyLarge)
    }
}

/** "3 climbing days and 12 sends so far", without judgement, and what opens the full review. */
@Composable
fun soFarText(review: YearReview?): String {
    val days = review?.volume?.climbingDays ?: 0
    val sends = review?.volume?.sends ?: 0
    return stringResource(
        R.string.yr_so_far_text,
        pluralStringResource(R.plurals.yr_climbing_days, days, days.toString()),
        pluralStringResource(R.plurals.yr_sends, sends, sends.toString()),
        pluralStringResource(R.plurals.yr_climbing_days, com.cruxcoach.domain.yearreview.YearReviewComputer.MIN_CLIMBING_DAYS, com.cruxcoach.domain.yearreview.YearReviewComputer.MIN_CLIMBING_DAYS.toString()),
        pluralStringResource(R.plurals.yr_tries, com.cruxcoach.domain.yearreview.YearReviewComputer.MIN_TRIES, com.cruxcoach.domain.yearreview.YearReviewComputer.MIN_TRIES.toString()),
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
    val header = privacy.name?.let { stringResource(R.string.yr_share_name_header, it.take(MAX_NAME), review.year) }
    val wordmark = stringResource(R.string.yr_share_wordmark)
    val colors = boardSeriesColors(review.boards.map { it.board }).mapValues { it.value.toArgb() }
    val bitmap = remember(page, header, colors) { YearShareImage.render(page, header, wordmark, colors) }
    val chooser = stringResource(R.string.yr_share_chooser)
    val failed = stringResource(R.string.yr_share_failed)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.yr_share_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Image(
                    bitmap.asImageBitmap(),
                    contentDescription = page.title,
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .aspectRatio(bitmap.width.toFloat() / bitmap.height)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }
            Toggle(stringResource(R.string.yr_share_show_name), showName, enabled = state.displayName.isNotEmpty()) { showName = it }
            if (state.displayName.isEmpty()) {
                Text(stringResource(R.string.yr_share_name_missing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Toggle(stringResource(R.string.yr_share_show_problems), showProblems) { showProblems = it }
            Toggle(stringResource(R.string.yr_share_show_dates), showDates) { showDates = it }
            Text(stringResource(R.string.yr_share_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = {
                    scope.launch {
                        val ok = runCatching { YearShareImage.share(context, bitmap, "cruxcoach-${review.year}-${kind.name.lowercase()}", chooser) }.isSuccess
                        if (!ok) Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
                    }
                },
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
}

@Composable
private fun Toggle(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Long display names are cut on the image. */
private const val MAX_NAME = 24
