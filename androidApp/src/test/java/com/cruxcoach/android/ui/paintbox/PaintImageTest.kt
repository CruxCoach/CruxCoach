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
    @Test fun `alpha and the background colour decide on uncomposited RGB`() {
        // One hold over a picture of one colour.
        fun lit(pixel: Int, background: Int? = null, brand: BoardBrand = BoardBrand.KILTER): Int? {
            val bitmap = Bitmap.createBitmap(IntArray(16) { pixel }, 4, 4, Bitmap.Config.ARGB_8888)
            val session = PaintImageSession(bitmap, background = background, backgroundOff = background != null)
            return PaintImageSampling.sample(session, Size(100f, 100f), mapOf(1 to Offset(50f, 50f)), brand)[1]
        }
        assertNull(lit(0x7fff0000))
        assertEquals(224, lit(0x80ff0000.toInt()))
        // Close to the background colour, as JPEG noise or a soft gradient leaves it: off. Another colour: on.
        assertNull(lit(0xfff4f8f2.toInt(), 0xffffff))
        assertNull(lit(0xffeaeaea.toInt(), 0xffffff))
        assertEquals(224, lit(0xffff0000.toInt(), 0xffffff))
        // The background colour only counts while the background is switched off.
        val white = Bitmap.createBitmap(IntArray(16) { 0xffffffff.toInt() }, 4, 4, Bitmap.Config.ARGB_8888)
        val kept = PaintImageSession(white, background = 0xffffff, backgroundOff = false)
        assertNull(kept.darkBackground)
        assertEquals(mapOf(1 to 255), PaintImageSampling.sample(kept, Size(100f, 100f), mapOf(1 to Offset(50f, 50f)), BoardBrand.KILTER))
        for (brand in listOf(BoardBrand.MOONBOARD, BoardBrand.QUANTUM)) {
            PaintPalette.forBoard(brand).forEach { assertEquals(it, lit(0xff000000.toInt() or PaintPalette.rgb(it), brand = brand)) }
        }
    }
    @Test fun `the mapping reads a bounded copy and the border sets the default`() {
        val bitmap = Bitmap.createBitmap(2048, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0x80ff0000.toInt())
        val pixels = bitmap.toPaintPixels()
        assertEquals(1024, pixels.width)
        assertEquals(4, pixels.height)
        assertEquals(0x80, pixels.argb[100] ushr 24)
        assertEquals(0xff0000, pixels.argb[100] and 0xffffff)
        val small = Bitmap.createBitmap(IntArray(64) { 0xff000000.toInt() }, 8, 8, Bitmap.Config.ARGB_8888)
        val detected = PaintImageMapping.background(small.toPaintPixels())
        assertEquals(PaintBackground(color = 0, clear = true), detected)
        // A clear border starts with the background off; a picked colour takes the place of the detected one.
        val session = PaintImageSession(small, detected = detected)
        assertTrue(session.backgroundOff)
        assertEquals(0, session.darkBackground)
        assertEquals(0x112233, session.copy(background = 0x112233).darkBackground)
        assertFalse(PaintImageSession(small).backgroundOff)
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
        assertEquals(mapOf(4 to 28), PaintImageSampling.sample(session.copy(background = 0xff0000, backgroundOff = true),
            Size(100f, 100f), points, BoardBrand.MOONBOARD))
    }
}
