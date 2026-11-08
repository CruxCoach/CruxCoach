package com.cruxcoach.android.ui.paintbox

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.ui.settings.LedColorPalette
import com.cruxcoach.android.ui.settings.SettingsLayout
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Design review of PaintBox: every screen and state, rendered into
 * `CRUXCOACH_UI_REVIEW_DIR` (skipped unless that variable is set).
 *
 * Each screen is drawn dark and light, in English and German, at font scale
 * 1.0 and 2.0, 320 dp and 411 dp wide (`<screen>/<variant>.png`); `pair-*.png`
 * puts two variants of one size side by side. `CRUXCOACH_UI_REVIEW_ONLY`
 * limits the run to variants whose name contains it. The Kilter screens use
 * the real hold positions when `CRUXCOACH_UI_REVIEW_PLACEMENTS` names a CSV
 * of `placementId,holeId,setId,x,y` for the 12 x 12 board (not part of the
 * repository); otherwise a regular grid stands in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h2000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaintBoxUiReviewTest {

    @get:Rule val compose = createComposeRule()

    private val dir: String? = System.getenv("CRUXCOACH_UI_REVIEW_DIR")
    private val only: String? = System.getenv("CRUXCOACH_UI_REVIEW_ONLY")

    @Before
    fun onlyOnRequest() = Assume.assumeTrue(dir != null)

    private data class Variant(val dark: Boolean, val locale: String, val fontScale: Float, val width: Int) {
        val name = "${if (dark) "dark" else "light"}-$locale-fs$fontScale-w$width"
        /** A phone screen of this width: about 2.1 times as high as wide. */
        val height: Int get() = (width * 2.1f).roundToInt()
    }

    private val variants = buildList {
        for (fontScale in listOf(1f, 2f)) for (width in listOf(411, 320)) {
            for (dark in listOf(true, false)) for (locale in listOf("de", "en")) add(Variant(dark, locale, fontScale, width))
        }
    }.filter { only == null || only in it.name }

    private val pairs = listOf(1f, 2f).flatMap { fontScale ->
        listOf(411, 320).map { width ->
            val swap = (fontScale == 2f) != (width == 320)
            "fs$fontScale-w$width" to listOf(
                Variant(dark = true, locale = if (swap) "en" else "de", fontScale, width),
                Variant(dark = false, locale = if (swap) "de" else "en", fontScale, width),
            )
        }
    }

    // ------------------------------------------------------------------ data

    private val kilterSize = BoardSize(10, 1, "12 x 12 with kickboard", 0, 144, 0, 156, null)

    private val kilterPlacements: Map<Int, BoardPlacement> = run {
        val csv = System.getenv("CRUXCOACH_UI_REVIEW_PLACEMENTS")?.let(::File)?.takeIf { it.exists() }
        val rows = csv?.readLines()?.filter { it.isNotBlank() }?.map { line ->
            val (id, hole, set, x, y) = line.split(',').map(String::toLong)
            BoardPlacement(id, hole, set, x, y)
        } ?: (0 until 17).flatMap { col ->
            (0 until 19).map { row -> BoardPlacement((row * 17 + col + 1).toLong(), 0, 1, 8L + col * 8, 8L + row * 8) }
        }
        rows.associateBy { it.placementId.toInt() }
    }

    /** A yellow smiley with dark eyes and a red mouth, in board coordinates. */
    private val smiley: Map<Int, Int> = buildMap {
        kilterPlacements.values.forEach { p ->
            val dx = p.x - 72.0
            val dy = p.y - 84.0
            val r = hypot(dx, dy)
            val color = when {
                hypot(p.x - 50.0, p.y - 100.0) < 9 || hypot(p.x - 94.0, p.y - 100.0) < 9 -> 0x03
                r in 30.0..40.0 && dy < -8 -> 0xe0
                r < 58 -> 0xfc
                else -> null
            }
            color?.let { put(p.placementId.toInt(), it) }
        }
    }

    private fun kilterDocument(colors: Map<Int, Int> = smiley, name: String = "Smiley") =
        PaintDocument(name = name, board = PaintBoard("kilter", 1, 10, label = "Kilter Board Original · 12 x 12 with kickboard"), colors = colors)

    private fun kilterState(colors: Map<Int, Int> = smiley) = PaintBoxState(
        document = kilterDocument(colors), boardSize = kilterSize, placements = kilterPlacements, loading = false,
        canUndo = colors.isNotEmpty(),
    )

    private fun smileyBitmap(background: Int = 0): Bitmap {
        val size = 200
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) for (y in 0 until size) {
            val r = hypot(x - 100.0, y - 100.0)
            val color = when {
                hypot(x - 70.0, y - 75.0) < 13 || hypot(x - 130.0, y - 75.0) < 13 -> 0xff141414.toInt()
                r in 45.0..55.0 && y > 110 -> 0xffc81e1e.toInt()
                r < 85 -> 0xffffd200.toInt()
                else -> background
            }
            bitmap.setPixel(x, y, color)
        }
        return bitmap
    }

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
                screenHeightDp = v.height
            }
        }
        val context = remember(v) { base.createConfigurationContext(config) }
        // The screen's photo picker and back handling look these up from the activity.
        val activity = base as androidx.activity.ComponentActivity
        CompositionLocalProvider(
            androidx.activity.compose.LocalActivityResultRegistryOwner provides activity,
            androidx.activity.compose.LocalOnBackPressedDispatcherOwner provides activity,
            LocalContext provides context,
            LocalConfiguration provides config,
            LocalResources provides context.resources,
            LocalDensity provides Density(density.density, v.fontScale),
        ) { content() }
    }

    /** Draws [content] in every variant into `<dir>/<name>/`, as a full phone screen. */
    private fun shoot(
        name: String,
        fullScreen: Boolean = true,
        before: () -> Unit = {},
        content: @Composable (Variant) -> Unit,
    ) {
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
                            val frame = Modifier.width(v.width.dp).let { if (fullScreen) it.height(v.height.dp) else it }
                            Surface(frame.onGloballyPositioned { bounds = it.boundsInRoot() }) { content(v) }
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
            before()
            // Board photos decode off the main thread.
            Thread.sleep(400)
            compose.mainClock.advanceTimeBy(500)
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

    private fun screen(state: PaintBoxState, connection: ConnectionState = ConnectionState.CONNECTED): PaintBoxViewModel {
        val vm = mockk<PaintBoxViewModel>(relaxed = true)
        every { vm.state } returns MutableStateFlow(state)
        every { vm.connection } returns MutableStateFlow(connection)
        return vm
    }

    @Composable
    private fun PaintBox(vm: PaintBoxViewModel) = PaintBoxScreen(onBack = {}, onDownloadBoards = {}, viewModel = vm)

    // --------------------------------------------------------------- screens

    @Test
    fun picture() {
        val vm = screen(kilterState())
        shoot("picture") { PaintBox(vm) }
    }

    @Test
    fun live() {
        val vm = screen(kilterState().copy(live = true, eraser = true, canRedo = true))
        shoot("live") { PaintBox(vm) }
    }

    @Test
    fun sending() {
        val vm = screen(kilterState().copy(sending = true))
        shoot("sending") { PaintBox(vm) }
    }

    @Test
    fun emptyNotConnected() {
        val vm = screen(kilterState(emptyMap()), ConnectionState.DISCONNECTED)
        shoot("empty-not-connected") { PaintBox(vm) }
    }

    @Test
    fun loading() {
        val vm = screen(PaintBoxState())
        shoot("loading") { PaintBox(vm) }
    }

    @Test
    fun missingBoard() {
        val vm = screen(PaintBoxState(document = kilterDocument(emptyMap()), loading = false, missingBoard = true))
        shoot("missing-board") { PaintBox(vm) }
    }

    @Test
    fun unsupportedBoard() {
        val vm = screen(PaintBoxState(
            document = PaintDocument(board = PaintBoard("shinywall", 1, 1, label = "Shiny Wall · Standard")),
            loading = false, unsupportedBoard = true,
        ))
        shoot("unsupported-board") { PaintBox(vm) }
    }

    @Test
    fun quantum() {
        val vm = screen(kilterState().let { s ->
            s.copy(document = s.document!!.copy(board = PaintBoard("quantum", 1, 1, label = "Quantum Board"), colors = emptyMap()),
                canUndo = false)
        })
        shoot("quantum") { PaintBox(vm) }
    }

    @Test
    fun moonBoard() {
        val variant = MoonBoardVariant.MOONBOARD_2016
        val board = PaintBoard("moonboard", variant.layoutId.toInt(), 0, label = variant.displayName)
        val holds = board.moonHoldIds().sorted()
        val colors = holds.mapIndexed { i, id -> id to PaintPalette.moon[i % 3] }.filterIndexed { i, _ -> i % 4 == 0 }.toMap()
        val vm = screen(PaintBoxState(document = PaintDocument(board = board, colors = colors), loading = false, canUndo = true))
        shoot("moonboard") { PaintBox(vm) }
    }

    @Test
    fun photo() {
        val session = PaintImageSession(smileyBitmap(), colors = smiley, ready = true)
        val vm = screen(kilterState(emptyMap()).copy(imageImport = session))
        shoot("photo") { PaintBox(vm) }
    }

    @Test
    fun photoBackground() {
        val session = PaintImageSession(smileyBitmap(background = 0xffffffff.toInt()), pickingBackground = true, ready = true)
        val vm = screen(kilterState(emptyMap()).copy(imageImport = session))
        shoot("photo-background") { PaintBox(vm) }
    }

    private fun library(pictures: List<PaintDocument>, unreadable: Int = 0) {
        val vm = screen(kilterState())
        io.mockk.coEvery { vm.preview(any()) } returns kilterState()
        shoot(if (pictures.isEmpty()) "library-empty" else "library", fullScreen = false) {
            androidx.compose.foundation.layout.Column {
                PaintLibraryContent(PaintLibrary(pictures, unreadable), vm, onLoad = {}, onDelete = {}, onSaveCurrent = {})
            }
        }
    }

    @Test
    fun library() = library(listOf(
        kilterDocument(name = "Smiley"),
        kilterDocument(smiley.filterValues { it == 0xfc }, name = "Sun for the opening night").copy(id = java.util.UUID.randomUUID().toString()),
        PaintBoard("moonboard", MoonBoardVariant.MOONBOARD_2016.layoutId.toInt(), 0,
            label = MoonBoardVariant.MOONBOARD_2016.displayName).let { board ->
            val holds = board.moonHoldIds().sorted()
            PaintDocument(name = "Three colours", board = board,
                colors = holds.filterIndexed { i, _ -> i % 3 == 0 }.mapIndexed { i, id -> id to PaintPalette.moon[i % 3] }.toMap())
        },
    ), unreadable = 1)

    @Test
    fun libraryEmpty() = library(emptyList())

    @Test
    fun colorsAurora() = shoot("colors-aurora", fullScreen = false) {
        LedColorPalette(androidx.compose.ui.res.stringResource(com.cruxcoach.android.R.string.paintbox_brush), 0xfc, {}, PaintPalette.forBoard(BoardBrand.KILTER).toSet())
    }

    @Test
    fun colorsMoonBoard() = shoot("colors-moonboard", fullScreen = false) {
        LedColorPalette(androidx.compose.ui.res.stringResource(com.cruxcoach.android.R.string.paintbox_brush), 0x1c, {}, PaintPalette.moon.toSet())
    }

    @Test
    fun settingsExtras() = shoot("settings-extras", before = {
        compose.onNodeWithTag("settings_open_paintbox").performScrollTo()
    }) {
        SettingsLayout(isLoading = false, openUpdates = false, onNavigateBack = {}, onNavigateToAppShare = {}) { page ->
            Text(page.name)
        }
    }

    private companion object {
        const val PIECE = 1800
    }
}
