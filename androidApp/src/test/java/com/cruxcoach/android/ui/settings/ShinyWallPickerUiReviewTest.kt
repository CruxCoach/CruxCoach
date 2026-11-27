package com.cruxcoach.android.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.ui.board.ShinyWallTagFilter
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.ShinyWallTag
import com.cruxcoach.domain.board.ShinyWallVariant
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Design review of the Shiny Wall parts of the board picker (the variant list,
 * the variant prompt after a mismatch) and the route categories in the filter.
 * Renders them dark and light, English and German, at 320 and 411 dp and font
 * scale 1.0 and 2.0 into `CRUXCOACH_UI_REVIEW_DIR`. Skipped unless that
 * variable is set. Robolectric does not apply the font scale to dialogs: check
 * those on the phone.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShinyWallPickerUiReviewTest(
    private val locale: String,
    private val dark: Boolean,
    private val width: Int,
    private val fontScale: Float,
) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}-w{2}-f{3}")
        fun matrix(): List<Array<Any>> = buildList {
            for (locale in listOf("en", "de")) for (dark in listOf(true, false))
                for (width in listOf(320, 411)) for (scale in listOf(1.0f, 2.0f)) add(arrayOf(locale, dark, width, scale))
        }
    }

    private val dir: String? = System.getenv("CRUXCOACH_UI_REVIEW_DIR")
    private val variant = "$locale-${if (dark) "dark" else "light"}-w$width-f${if (fontScale > 1f) "2" else "1"}"

    @get:Rule(order = 0)
    val device = TestRule { base: Statement, _: Description ->
        object : Statement() {
            override fun evaluate() {
                Assume.assumeTrue(dir != null)
                val height = if (width == 320) 694 else 891
                RuntimeEnvironment.setQualifiers("$locale-w${width}dp-h${height}dp-${if (dark) "night" else "notnight"}-xhdpi")
                RuntimeEnvironment.setFontScale(fontScale)
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private fun shoot(name: String, ready: () -> Unit = {}, content: @Composable () -> Unit) {
        var view: android.view.View? = null
        compose.setContent {
            view = LocalView.current
            CruxCoachTheme(if (dark) DarkModeSetting.DARK else DarkModeSetting.LIGHT) {
                Surface(Modifier.fillMaxSize()) { content() }
            }
        }
        compose.waitForIdle()
        runCatching(ready)
        // The board pictures decode in the background.
        Thread.sleep(400)
        compose.waitForIdle()
        compose.runOnIdle {
            val root = requireNotNull(view)
            val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            root.draw(canvas)
            windows().filter { it !== root.rootView && it.width > 0 }.forEach { w ->
                canvas.drawColor(android.graphics.Color.argb(0x52, 0, 0, 0))
                val at = IntArray(2).also { w.getLocationOnScreen(it) }
                canvas.save(); canvas.translate(at[0].toFloat(), at[1].toFloat()); w.draw(canvas); canvas.restore()
            }
            java.io.File(dir!!).mkdirs()
            java.io.File(dir, "$name-$variant.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun windows(): List<android.view.View> = runCatching {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        (global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<android.view.View>).toList()
    }.getOrDefault(emptyList())

    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    @Composable
    private fun Picker(mismatch: BoardConfigurationMismatch? = null) = BoardSelectionDialog(
        initialBrand = BoardBrand.SHINYWALL.wireValue,
        productSizes = emptyList(),
        selectedKilterSizeId = 0,
        selectedMoonBoardVariant = null,
        selectedAuroraLayoutId = ShinyWallVariant.ST5.layoutId.toInt(),
        onConfirmKilter = {},
        onConfirmMoonBoard = { _, _ -> },
        onConfirmShinyWall = {},
        prefill = mismatch?.prefill,
        mismatch = mismatch,
        onFindViaBluetooth = {},
        onDismiss = {},
    )

    private val variantMismatch = BoardConfigurationMismatch(
        kind = BoardMismatchKind.CONNECTED_MODEL,
        climbBrand = BoardBrand.SHINYWALL,
        expectedBrand = BoardBrand.SHINYWALL,
        activeBrand = BoardBrand.SHINYWALL,
        connectedBrand = BoardBrand.SHINYWALL,
        climbLayoutId = ShinyWallVariant.ST1.layoutId,
        activeLayoutId = ShinyWallVariant.ST5.layoutId,
        prefill = BoardPickerPrefill(
            BoardBrand.SHINYWALL, layoutId = ShinyWallVariant.ST1.layoutId, source = BoardPickerPrefillSource.CONNECTED_CONTROLLER,
        ),
    )

    @Test
    fun picker() = shoot("picker") { Picker() }

    @Test
    fun pickerVariants() = shoot("picker-variants", ready = {
        compose.onNode(hasText(text(R.string.board_selection_shinywall_variant_hint))).performScrollTo()
    }) { Picker() }

    @Test
    fun pickerMismatch() = shoot("picker-mismatch") { Picker(variantMismatch) }

    @Test
    fun filterCategories() = shoot("filter-categories") {
        Column(Modifier.padding(16.dp)) {
            ShinyWallTagFilter(ShinyWallTag.CRIMPY.bit or ShinyWallTag.ONLY_HANDS.bit) {}
        }
    }
}
