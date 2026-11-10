package com.cruxcoach.android.ui.paintbox

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.app.Application

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaintImageTest {
    @Test fun `image loader bounds size and preserves transparent PNG pixels`() = kotlinx.coroutines.runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        val file = java.io.File.createTempFile("paint-test", ".png", context.cacheDir)
        try {
            val original = Bitmap.createBitmap(4096, 2, Bitmap.Config.ARGB_8888)
            original.eraseColor(0x40ff0000)
            file.outputStream().use { original.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // Robolectric's Windows native runtime cannot decode file descriptors.
            // Feed identical bytes to the real decoder; keep the production file-spooling,
            // decoder configuration, resizing and alpha handling under test.
            val decoded = loadPaintImage(context, android.net.Uri.fromFile(file)) {
                android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(it.readBytes()))
            }
            assertEquals(2048, decoded.width)
            assertEquals(1, decoded.height)
            assertEquals(64, decoded.getPixel(100, 0) ushr 24)
        } finally { file.delete() }
    }
    @Test fun `alpha and background thresholds use uncomposited RGB`() {
        val palette = PaintPalette.forBoard(BoardBrand.KILTER)
        assertNull(PaintImageSampling.color(0x7fff0000, null, palette))
        assertEquals(224, PaintImageSampling.color(0x80ff0000.toInt(), null, palette))
        assertNull(PaintImageSampling.color(0xff181000.toInt(), 0x001000, palette))
        assertNotNull(PaintImageSampling.color(0xff191000.toInt(), 0x001000, palette))
        for (brand in listOf(BoardBrand.KILTER, BoardBrand.MOONBOARD, BoardBrand.QUANTUM)) {
            val allowed = PaintPalette.forBoard(brand)
            allowed.forEach { assertEquals(it, PaintImageSampling.color(0xff000000.toInt() or PaintPalette.rgb(it), null, allowed)) }
        }
    }
    @Test fun `inverse transformation follows rotation zoom and pan about gesture centroid`() {
        val viewport = Size(200f, 200f)
        val image = Size(100f, 100f)
        val original = PaintImageTransform()
        assertEquals(Offset(25f, 50f), original.imagePoint(Offset(50f, 100f), image, viewport))
        val changed = original.gesture(Offset(100f, 100f), Offset(10f, 20f), 2f, 90f, viewport)
        val pixel = changed.imagePoint(Offset(110f, 20f), image, viewport)
        assertEquals(25f, pixel.x, .001f)
        assertEquals(50f, pixel.y, .001f)
        val resized = changed.imagePoint(Offset(220f, 40f), image, Size(400f, 400f))
        assertEquals(pixel.x, resized.x, .001f)
        assertEquals(pixel.y, resized.y, .001f)
    }
    @Test fun `sampling turns off out of image positions and transparent pixels`() {
        val bitmap = Bitmap.createBitmap(intArrayOf(0xffff0000.toInt(), 0, 0xff00ff00.toInt(), 0xff0000ff.toInt()), 2, 2, Bitmap.Config.ARGB_8888)
        val session = PaintImageSession(bitmap)
        val points = mapOf(1 to Offset(25f, 25f), 2 to Offset(75f, 25f), 3 to Offset(100f, 0f), 4 to Offset(25f, 75f))
        assertEquals(mapOf(1 to 224, 4 to 28), PaintImageSampling.sample(session, Size(100f, 100f), points, BoardBrand.MOONBOARD))
        assertEquals(mapOf(4 to 28), PaintImageSampling.sample(session.copy(background = 0xff0000), Size(100f, 100f), points, BoardBrand.MOONBOARD))
    }
}
