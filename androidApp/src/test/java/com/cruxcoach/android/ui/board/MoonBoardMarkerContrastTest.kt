package com.cruxcoach.android.ui.board

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MoonBoardMarkerContrastTest {
    @Test fun `blue role ring has visible light and dark edges on matching blue white and black`() {
        val blue = Color(0xFF2F6BE0)
        val backgrounds = listOf(blue, Color.White, Color.Black)
        val bitmap = Bitmap.createBitmap(480, 160, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(
            Density(1f), LayoutDirection.Ltr, Canvas(bitmap.asImageBitmap()), Size(480f, 160f),
        ) {
            backgrounds.forEachIndexed { index, background ->
                drawRect(background, topLeft = Offset(index * 160f, 0f), size = Size(160f, 160f))
                drawMoonBoardHoldMarker(Offset(index * 160f + 80f, 80f), blue, 40f)
            }
        }
        backgrounds.indices.forEach { index ->
            val x = index * 160 + 80
            assertEquals("role colour remains intact", blue.toArgb(), bitmap.getPixel(x + 40, 80))
            assertEquals("light edge separates the ring from blue/dark holds", Color.White.toArgb(), bitmap.getPixel(x + 48, 80))
            assertEquals("dark edge separates the ring from light holds", Color.Black.toArgb(), bitmap.getPixel(x + 53, 80))
            assertEquals("outside the marker is unchanged", backgrounds[index].toArgb(), bitmap.getPixel(x + 60, 80))
        }
        // A review artifact rendered by the production marker code, not a mock-up.
        val output = File("build/reports/moonboard-marker-contrast.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
