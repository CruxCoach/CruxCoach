package com.cruxcoach.android.ui.board

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.ble.BoardClimbLayer
import com.cruxcoach.android.ble.BoardLayerManager
import com.cruxcoach.android.ble.BoardLayerState
import com.cruxcoach.android.ble.BoardLayerStatus
import com.cruxcoach.android.ble.ExternalBoardLayer
import com.cruxcoach.android.ble.QuantumControllerSyncStatus
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.ui.settings.BoardSelectionDialog
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import java.io.File
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
 * Design review of the Quantum marks: the layer strip with a route the board took
 * but does not list, next to the other states, and the board picker with Quantum
 * chosen, into `CRUXCOACH_UI_REVIEW_DIR`, one folder per language, width and font
 * size, dark and light. Skipped unless that variable is set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuantumExperimentalUiReviewTest {

    @get:Rule val compose = createComposeRule()

    private val dir: String? = System.getenv("CRUXCOACH_UI_REVIEW_DIR")

    @Before
    fun onlyOnRequest() = Assume.assumeTrue(dir != null)

    private fun layer(slot: Int, name: String, status: BoardLayerStatus, unlistedUntil: Long? = null, confirmed: Boolean = true) =
        BoardClimbLayer(
            slot = slot,
            climbUuid = "climb-$slot",
            routeUuid = "route-$slot",
            climbName = name,
            angle = 40,
            userUuid = "user-$slot",
            color = BoardLayerManager.LAYER_COLORS[slot],
            holds = listOf(BoardHold(10 + slot, 12)),
            status = status,
            confirmedRouteUuid = "route-$slot".takeIf { confirmed },
            confirmedColor = BoardLayerManager.LAYER_COLORS[slot].takeIf { confirmed },
            confirmedClimbName = name.takeIf { confirmed },
            confirmedHolds = listOf(BoardHold(10 + slot, 12)).takeIf { confirmed },
            unlistedUntilElapsedMs = unlistedUntil,
        )

    private val rack = BoardLayerState(
        brand = BoardBrand.QUANTUM,
        layers = listOf(
            layer(0, "Vector 1", BoardLayerStatus.CONFIRMED),
            layer(1, "First Easy", BoardLayerStatus.CONFIRMED, unlistedUntil = 1_000_000),
            layer(2, "Overhang Dyno", BoardLayerStatus.PREVIEW, confirmed = false),
        ),
        externalLayers = listOf(
            ExternalBoardLayer("foreign-route", "foreign-user", BoardLayerManager.LAYER_COLORS[3], 600),
        ),
        quantumSyncStatus = QuantumControllerSyncStatus.LIVE,
    )

    private val allSent = rack.copy(
        layers = (0..2).map { layer(it, "Climb ${it + 1}", BoardLayerStatus.CONFIRMED, unlistedUntil = 1_000_000) },
        externalLayers = emptyList(),
    )

    private var shown: @Composable () -> Unit by mutableStateOf({})
    private var theme: DarkModeSetting by mutableStateOf(DarkModeSetting.DARK)
    private var view: android.view.View? = null
    private var folder = ""

    private fun start(fontScale: Float) {
        RuntimeEnvironment.setFontScale(fontScale)
        compose.setContent {
            view = LocalView.current
            CruxCoachTheme(theme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { shown() }
            }
        }
        compose.waitForIdle()
    }

    private fun shoot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        theme = if (dark) DarkModeSetting.DARK else DarkModeSetting.LIGHT
        shown = content
        compose.waitForIdle()
        compose.runOnIdle { draw("$name-${if (dark) "dark" else "light"}") }
        shown = {}
        compose.waitForIdle()
    }

    /** The picker's board pictures never let the clock idle: shoot it on a stopped clock. */
    private fun shootDialog(name: String, dark: Boolean, content: @Composable () -> Unit) {
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

    private fun frames(count: Int) = repeat(count) {
        compose.mainClock.advanceTimeByFrame()
        ShadowLooper.idleMainLooper()
    }

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

    @Composable
    private fun Strips() {
        Column(Modifier.padding(16.dp)) {
            QuantumLayerStatusStrip(state = rack, currentClimbUuid = "climb-1", onOpen = {})
            QuantumLayerStatusStrip(state = allSent, currentClimbUuid = "climb-0", onOpen = {}, modifier = Modifier.padding(top = 24.dp))
        }
    }

    @Composable
    private fun QuantumPicker() {
        BoardSelectionDialog(
            initialBrand = BoardBrand.QUANTUM.wireValue,
            productSizes = BoardConstants.KILTER_KNOWN_SIZES,
            selectedKilterSizeId = 10,
            selectedMoonBoardVariant = null,
            onConfirmKilter = {},
            onConfirmMoonBoard = { _, _ -> },
            showAuroraBoards = true,
            frequency = BoardConstants.DEFAULT_SIZE_FREQUENCY,
            onFindViaGym = {},
            onFindViaBluetooth = {},
            onDismiss = {},
        )
    }

    private fun everything(language: String, width: Int, fontScale: Float) {
        folder = "$language-$width-${if (fontScale > 1f) "large" else "normal"}"
        start(fontScale)
        for (dark in listOf(true, false)) {
            shoot("strip-01-sent", dark) { Strips() }
            shootDialog("picker-01-quantum", dark) { QuantumPicker() }
        }
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
