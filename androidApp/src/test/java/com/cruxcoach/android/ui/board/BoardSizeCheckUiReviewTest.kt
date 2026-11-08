package com.cruxcoach.android.ui.board

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.android.data.BoardGymShares
import com.cruxcoach.android.data.BoardSizeCheckPoint
import com.cruxcoach.android.data.BoardSizeCheckPoints
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.ui.settings.BoardSelectionDialog
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

/**
 * Design review of the board-size check: the sheet in each of its states with the holds the
 * check really picks from the bundled geometry, and the board picker with the check's link and
 * the gym shares, into `CRUXCOACH_UI_REVIEW_DIR`, one folder per language, width and font size,
 * dark and light. Skipped unless that variable is set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalMaterial3Api::class)
class BoardSizeCheckUiReviewTest {

    @get:Rule val compose = createComposeRule()

    private val dir: String? = System.getenv("CRUXCOACH_UI_REVIEW_DIR")

    @Before
    fun onlyOnRequest() = Assume.assumeTrue(dir != null)

    /** The check's view of a board, read from the geometry the APK ships. Every send works. */
    private class Bundled(private val brand: BoardBrand) : BoardSizeCheckSession.Source {
        private val db: SQLiteDatabase = SQLiteDatabase.openDatabase(
            listOf("src/main/assets/board_geometry", "androidApp/src/main/assets/board_geometry")
                .map { File(it, "${brand.wireValue}.sqlite3") }.first { it.exists() }.absolutePath,
            null, SQLiteDatabase.OPEN_READONLY,
        )

        private fun <T> query(sql: String, vararg args: Any, row: (android.database.Cursor) -> T): List<T> =
            db.rawQuery(sql, args.map(Any::toString).toTypedArray()).use { cursor ->
                buildList { while (cursor.moveToNext()) add(row(cursor)) }
            }

        override suspend fun sizes(brand: BoardBrand, sizeId: Int): List<BoardSize> {
            val product = query("SELECT product_id FROM product_sizes WHERE id = ? AND board_brand = ?", sizeId, brand.wireValue) {
                it.getLong(0)
            }.first()
            return query(
                """SELECT id, product_id, name, edge_left, edge_right, edge_bottom, edge_top FROM product_sizes
                   WHERE product_id = ? AND board_brand = ? AND id IN (SELECT product_size_id FROM leds WHERE board_brand = ?)
                   ORDER BY id""",
                product, brand.wireValue, brand.wireValue,
            ) { BoardSize(it.getLong(0), it.getLong(1), it.getString(2), it.getLong(3), it.getLong(4), it.getLong(5), it.getLong(6), null, brand) }
                .filter { brand == BoardBrand.KILTER || !BoardConstants.isExcludedAuroraSize(brand, it.id.toInt()) }
                // In the order the app lists them: Kilter by the bundled gym counts.
                .let { sizeCheckOrder(brand, it, BoardConstants.DEFAULT_SIZE_FREQUENCY) }
        }

        override suspend fun layoutFor(brand: BoardBrand, size: BoardSize, hint: Int?): Int? =
            hint ?: query("SELECT layout_id FROM board_images WHERE product_size_id = ? AND board_brand = ?", size.id, brand.wireValue) {
                it.getInt(0)
            }.firstOrNull()

        override suspend fun holds(brand: BoardBrand, size: BoardSize, layoutId: Int?): List<BoardSizeCheckPoint> {
            val placements = query(
                """SELECT placement_id, hole_id, set_id, x, y FROM placements WHERE board_brand = ? AND set_id IN
                   (SELECT set_id FROM board_images WHERE product_size_id = ? AND layout_id = ? AND board_brand = ?)""",
                brand.wireValue, size.id, layoutId ?: 0, brand.wireValue,
            ) { BoardPlacement(it.getLong(0), it.getLong(1), it.getLong(2), it.getLong(3), it.getLong(4)) }
            val leds = query(
                """SELECT p.placement_id, l.position FROM placements p JOIN leds l
                   ON p.hole_id = l.hole_id AND p.board_brand = l.board_brand
                   WHERE l.product_size_id = ? AND l.board_brand = ?""",
                size.id, brand.wireValue,
            ) { it.getInt(0) to it.getInt(1) }.toMap()
            return BoardSizeCheckPoints.candidates(placements, leds)
        }

        override fun ready(brand: BoardBrand) = brand == this.brand

        override fun mayRelight() = true

        override suspend fun light(brand: BoardBrand, leds: List<Pair<Int, Int>>) = true
        override suspend fun clear(brand: BoardBrand) = true
        override suspend fun apply(brand: BoardBrand, layoutId: Int?, sizeId: Int) = Unit
    }

    private val kilter = Bundled(BoardBrand.KILTER)

    /** A check of [sizeId], driven through [steps] the way the user would. */
    private fun check(
        brand: BoardBrand = BoardBrand.KILTER,
        sizeId: Int = 10,
        layoutId: Int? = 1,
        steps: suspend BoardSizeCheckSession.() -> Unit = {},
    ): BoardSizeCheckState = runBlocking {
        BoardSizeCheckSession(if (brand == BoardBrand.KILTER) kilter else Bundled(brand)).run {
            open(brand, sizeId, layoutId)
            steps()
            state.value
        }
    }

    /** Close to the gym list of 2026-11-02: Kilter walls per size, gyms per board and MoonBoard version. */
    private val gymShares = BoardGymShares.from(
        sizeWalls = mapOf(10 to 933L, 28 to 108L, 8 to 80L, 14 to 9L, 7 to 7L, 27 to 2L, 25 to 34L, 17 to 23L, 21 to 17L, 23 to 12L),
        locations = mapOf(
            ("moonboard" to 2) to 300L, ("moonboard" to 4) to 120L, ("moonboard" to 5) to 40L, ("moonboard" to 3) to 50L,
            ("moonboard" to 6) to 6L, ("moonboard" to 7) to 5L, ("moonboard" to 1) to 4L, ("moonboard" to null) to 970L,
            ("tension" to null) to 401L, ("grasshopper" to null) to 43L, ("decoy" to null) to 24L,
        ),
    )

    private var shown: @Composable () -> Unit by mutableStateOf({})
    private var theme: DarkModeSetting by mutableStateOf(DarkModeSetting.DARK)
    private var view: android.view.View? = null
    private var folder = ""

    /** One composition per test (the rule allows only one); [shoot] swaps what it shows and the theme. */
    private fun start(fontScale: Float) {
        RuntimeEnvironment.setFontScale(fontScale)
        compose.setContent {
            view = LocalView.current
            CruxCoachTheme(theme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
                shown()
            }
        }
        compose.waitForIdle()
    }

    /**
     * Board pictures decode on Dispatchers.IO, and the test's unconfined dispatcher writes the
     * result from that thread: the main thread sleeps meanwhile instead of composing.
     */
    private fun settle() {
        compose.waitForIdle()
        Thread.sleep(400)
        compose.waitForIdle()
    }

    private fun shoot(name: String, dark: Boolean = true, scrollTo: String? = null, content: @Composable () -> Unit) {
        theme = if (dark) DarkModeSetting.DARK else DarkModeSetting.LIGHT
        shown = content
        settle()
        val suffix = if (dark) "dark" else "light"
        capture("$name-$suffix")
        if (scrollTo != null) {
            compose.onNodeWithTag(scrollTo, useUnmergedTree = true).performScrollTo()
            settle()
            capture("$name-$suffix-end")
        }
        shown = {}
        compose.waitForIdle()
    }

    /**
     * The picker's board pictures show a spinner while they decode, which never lets the clock
     * idle under Robolectric: its shots run on a stopped clock, frame by frame, and the pictures
     * get real time to decode. A shot that still fails is skipped, not the rest of the run.
     */
    private fun shootDialog(name: String, dark: Boolean = true, content: @Composable () -> Unit) {
        theme = if (dark) DarkModeSetting.DARK else DarkModeSetting.LIGHT
        compose.mainClock.autoAdvance = false
        try {
            shown = content
            repeat(8) {
                frames(2)
                Thread.sleep(250)
            }
            draw("$name-${if (dark) "dark" else "light"}")
        } catch (e: Throwable) {
            println("UI review: $folder/$name skipped: $e")
        } finally {
            shown = {}
            frames(2)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
        }
    }

    /** Frames on the stopped clock; the looper runs too, so a dialog gets its window. */
    private fun frames(count: Int) = repeat(count) {
        compose.mainClock.advanceTimeByFrame()
        ShadowLooper.idleMainLooper()
    }

    private fun capture(name: String) = compose.runOnIdle { draw(name) }

    /** The screen, and on top of it a dialog the content opened, as the window manager stacks them. */
    private fun draw(name: String) {
        val root = requireNotNull(view).rootView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        root.draw(canvas)
        ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.let { dialog ->
            canvas.drawColor(android.graphics.Color.argb(150, 0, 0, 0))
            canvas.save()
            canvas.translate((root.width - dialog.width) / 2f, (root.height - dialog.height) / 2f)
            dialog.draw(canvas)
            canvas.restore()
        }
        val out = File(dir!!, folder).apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    /** A fully expanded modal sheet over the dimmed screen, as [BoardSizeCheckSheet] shows it. */
    @Composable
    private fun Sheet(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)).padding(top = 24.dp)) {
            Surface(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                shape = BottomSheetDefaults.ExpandedShape,
                color = BottomSheetDefaults.ContainerColor,
                tonalElevation = BottomSheetDefaults.Elevation,
            ) {
                Column {
                    BottomSheetDefaults.DragHandle(Modifier.align(Alignment.CenterHorizontally))
                    content()
                }
            }
        }
    }

    @Composable
    private fun CheckSheet(
        state: BoardSizeCheckState,
        brand: BoardBrand = BoardBrand.KILTER,
        connected: Boolean = true,
        ready: Boolean = true,
        otherBoard: Boolean = false,
        applyOnConfirm: Boolean = true,
        connecting: Boolean = false,
    ) = Sheet {
        BoardSizeCheckContent(
            state = state, brand = brand, connected = connected, ready = ready,
            otherBoardConnected = otherBoard, applyOnConfirm = applyOnConfirm, connecting = connecting,
            onConnect = {}, onLight = {}, onYes = {}, onNo = {}, onTest = {}, onClose = {},
        )
    }

    @Composable
    private fun Picker(brand: BoardBrand, kilterSizeId: Int = 10, moonBoard: MoonBoardVariant? = null, auroraSizes: List<BoardSize> = emptyList()) {
        BoardSelectionDialog(
            initialBrand = brand.wireValue,
            productSizes = BoardConstants.KILTER_KNOWN_SIZES,
            selectedKilterSizeId = kilterSizeId,
            selectedMoonBoardVariant = moonBoard,
            selectedAuroraLayoutId = if (brand == BoardBrand.TENSION) 9 else 0,
            selectedAuroraProductSizeId = auroraSizes.firstOrNull()?.id?.toInt() ?: 0,
            loadedAuroraBrands = setOf(BoardBrand.TENSION.wireValue),
            auroraBrandSizes = mapOf(BoardBrand.TENSION.wireValue to auroraSizes),
            onConfirmKilter = {},
            onConfirmMoonBoard = { _, _ -> },
            showAuroraBoards = true,
            frequency = BoardConstants.DEFAULT_SIZE_FREQUENCY,
            gymShares = gymShares,
            onFindViaGym = {},
            onFindViaBluetooth = {},
            onDismiss = {},
        )
    }

    private fun everything(language: String, width: Int, fontScale: Float) {
        folder = "$language-$width-${if (fontScale > 1f) "large" else "normal"}"
        start(fontScale)
        val large = fontScale > 1f
        val end = if (large) "board_size_check_close" else null

        val lit = check()
        val dark = lit.copy(lit = false)
        val pick = check { reject() }
        val allTried = check {
            reject()
            for (id in listOf(28, 8, 7, 27, 14)) {
                test(id)
                reject()
            }
        }
        val other = check { reject(); test(28) }
        val fits = check { confirm(applyChange = true) }
        val saved = check { reject(); test(28); confirm(applyChange = true) }
        val selected = check { reject(); test(28); confirm(applyChange = false) }
        val tension = check(BoardBrand.TENSION, 2, 9)
        val tensionNext = check(BoardBrand.TENSION, 2, 9) { reject(); test(2) }
        val tensionPick = check(BoardBrand.TENSION, 3, 9) { reject() }
        val homewall = check(sizeId = 25, layoutId = 8)

        for (isDark in listOf(true, false)) {
            shoot("sheet-01-no-board", isDark, end) { CheckSheet(dark, connected = false, ready = false) }
            shoot("sheet-02-lit", isDark, end) { CheckSheet(lit) }
            shoot("sheet-05-pick-other", isDark, end) { CheckSheet(pick) }
            shoot("sheet-11b-tension-pick-other", isDark, end) { CheckSheet(tensionPick, BoardBrand.TENSION) }
            shoot("sheet-08-saved", isDark) { CheckSheet(saved) }
            shootDialog("picker-01-kilter", isDark) { Picker(BoardBrand.KILTER) }
        }
        shoot("sheet-00-loading") { CheckSheet(BoardSizeCheckState(brand = BoardBrand.KILTER)) }
        shoot("sheet-01b-other-board", scrollTo = end) { CheckSheet(dark, connected = true, ready = false, otherBoard = true) }
        shoot("sheet-03-sending", scrollTo = end) { CheckSheet(dark.copy(busy = true)) }
        shoot("sheet-03b-replaced", scrollTo = end) { CheckSheet(dark.copy(replaced = true)) }
        shoot("sheet-03c-send-failed", scrollTo = end) { CheckSheet(dark.copy(sendFailed = true)) }
        shoot("sheet-03d-away", scrollTo = end) { CheckSheet(dark.copy(away = true), connected = false, ready = false) }
        shoot("sheet-03e-away-connecting", scrollTo = end) {
            CheckSheet(dark.copy(away = true), connected = false, ready = false, connecting = true)
        }
        shoot("sheet-04-no-data") { CheckSheet(BoardSizeCheckState(brand = BoardBrand.KILTER, step = BoardSizeCheckStep.NO_DATA)) }
        shoot("sheet-05b-pick-other-all-tried", scrollTo = end) { CheckSheet(allTried) }
        shoot("sheet-05c-pick-other-no-board", scrollTo = end) { CheckSheet(pick, connected = false, ready = false) }
        shoot("sheet-06-other-size", scrollTo = end) { CheckSheet(other) }
        shoot("sheet-07-fits") { CheckSheet(fits) }
        shoot("sheet-09-picker-selected") { CheckSheet(selected, applyOnConfirm = false) }
        shoot("sheet-10-tension-look-alike", scrollTo = end) { CheckSheet(tension, BoardBrand.TENSION) }
        shoot("sheet-11-tension-after-no", scrollTo = end) { CheckSheet(tensionNext, BoardBrand.TENSION) }
        shoot("sheet-12-homewall", scrollTo = end) { CheckSheet(homewall) }

        // Only the picker's top: scrolling inside the dialog on the stopped clock hangs Robolectric
        // (2026-11-08, every configuration), so the rare hints and the Tension sizes are checked on a phone.
    }

    @Test
    fun english() = everything("en", 411, 1f)

    @Test
    fun englishLargestFont() = everything("en", 411, 2f)

    @Test
    @Config(qualifiers = "en-w320dp-h640dp-xhdpi")
    fun englishNarrow() = everything("en", 320, 1f)

    @Test
    @Config(qualifiers = "en-w320dp-h640dp-xhdpi")
    fun englishNarrowLargestFont() = everything("en", 320, 2f)

    @Test
    @Config(qualifiers = "de-w411dp-h891dp-xhdpi")
    fun german() = everything("de", 411, 1f)

    @Test
    @Config(qualifiers = "de-w411dp-h891dp-xhdpi")
    fun germanLargestFont() = everything("de", 411, 2f)

    @Test
    @Config(qualifiers = "de-w320dp-h640dp-xhdpi")
    fun germanNarrow() = everything("de", 320, 1f)

    @Test
    @Config(qualifiers = "de-w320dp-h640dp-xhdpi")
    fun germanNarrowLargestFont() = everything("de", 320, 2f)
}
