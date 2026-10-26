package com.cruxcoach.android.ui.board

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.ui.settings.BoardImageChoiceRow
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.ShinyWallVariant
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Review shots of a Shiny Wall problem as the detail screen draws it, plus the
 * variant row of the board picker, in light and dark mode. Saved only when
 * `CRUXCOACH_UI_REVIEW_DIR` is set (`shinywall-board-light.png`, `-dark.png`).
 * No density qualifier: an xhdpi run left BoardImageChoiceTest waiting in the
 * same test JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShinyWallBoardReviewTest {
    @get:Rule val compose = createComposeRule()

    private val variant = ShinyWallVariant.ST1

    @Test fun `a Shiny Wall problem renders in light and dark mode`() {
        val (size, placements) = loadVariant(variant)
        val holds = sampleProblem(size, placements)
        val dark = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            CruxCoachTheme(if (dark.value) DarkModeSetting.DARK else DarkModeSetting.LIGHT) {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        BoardImageChoiceRow(variant.displayName, true, {}, BoardBrand.SHINYWALL,
                            variant.productSizeId, variant.layoutId)
                        KilterBoardVisualization(
                            holds = holds,
                            placements = placements,
                            boardSize = size,
                            modifier = Modifier.height(560.dp),
                        )
                    }
                }
            }
        }
        for (theme in listOf("light", "dark")) {
            dark.value = theme == "dark"
            // The photo decodes in the background; without one the dots show at once.
            runCatching {
                compose.waitUntil(IMAGE_WAIT_MS) {
                    compose.onAllNodes(hasContentDescription("Board", substring = false))
                        .fetchSemanticsNodes().isNotEmpty()
                }
            }
            compose.waitForIdle()
            capture("shinywall-board-$theme.png")
        }
        assertTrue(holds.size >= 8)
    }

    private fun capture(name: String) {
        val directory = System.getenv("CRUXCOACH_UI_REVIEW_DIR") ?: return
        compose.runOnIdle {
            val root = android.view.inspector.WindowInspector.getGlobalWindowViews().last()
            val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(bitmap))
            File(directory).mkdirs()
            File(directory, name).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    /** A plausible problem: feet on the kicker, two start holds, hands up the middle, a top hold. */
    private fun sampleProblem(size: BoardSize, placements: Map<Int, BoardPlacement>): List<BoardHold> {
        fun near(fx: Double, fy: Double): Int {
            val x = size.edgeLeft + fx * (size.edgeRight - size.edgeLeft)
            val y = size.edgeBottom + fy * (size.edgeTop - size.edgeBottom)
            return placements.values.minBy { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) }.placementId.toInt()
        }
        val targets = listOf(
            0.35 to 0.02 to 15, 0.62 to 0.02 to 15,
            0.42 to 0.09 to 12, 0.58 to 0.09 to 12,
            0.40 to 0.25 to 13, 0.63 to 0.38 to 13, 0.45 to 0.52 to 13, 0.60 to 0.66 to 13, 0.48 to 0.80 to 13,
            0.52 to 0.97 to 14,
        )
        return targets.map { (point, role) -> BoardHold(near(point.first, point.second), role) }.distinctBy { it.placementId }
    }

    private fun loadVariant(variant: ShinyWallVariant): Pair<BoardSize, Map<Int, BoardPlacement>> {
        val file = listOf(
            File("src/main/assets/board_geometry/shinywall.sqlite3"),
            File("androidApp/src/main/assets/board_geometry/shinywall.sqlite3"),
        ).first { it.exists() }
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val size = db.rawQuery(
                "SELECT id, product_id, name, edge_left, edge_right, edge_bottom, edge_top FROM product_sizes WHERE id = ?",
                arrayOf(variant.productSizeId.toString()),
            ).use { c ->
                c.moveToFirst()
                BoardSize(c.getLong(0), c.getLong(1), c.getString(2), c.getLong(3), c.getLong(4), c.getLong(5),
                    c.getLong(6), null, BoardBrand.SHINYWALL)
            }
            val placements = mutableMapOf<Int, BoardPlacement>()
            db.rawQuery(
                "SELECT placement_id, hole_id, set_id, x, y FROM placements WHERE placement_id BETWEEN ? AND ?",
                arrayOf(variant.placementId(0).toString(), variant.placementId(variant.holdCount - 1).toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    placements[c.getInt(0)] = BoardPlacement(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getLong(4))
                }
            }
            return size to placements
        }
    }

    private companion object {
        const val IMAGE_WAIT_MS = 20_000L
    }
}
