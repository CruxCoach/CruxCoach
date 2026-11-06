package com.cruxcoach.android.ui.paintbox

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.ColorSpace
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.cruxcoach.domain.board.BoardBrand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.*

/** Position is expressed relative to the viewport, so resizing keeps the composition. */
data class PaintImageTransform(val center: Offset = Offset(.5f, .5f), val zoom: Float = 1f, val degrees: Float = 0f) {
    fun scale(image: Size, viewport: Size) = min(viewport.width / image.width, viewport.height / image.height) * zoom
    fun imagePoint(point: Offset, image: Size, viewport: Size): Offset =
        ((point - Offset(center.x * viewport.width, center.y * viewport.height)).rotate(-degrees) /
            scale(image, viewport)) + Offset(image.width / 2, image.height / 2)

    fun gesture(centroid: Offset, pan: Offset, factor: Float, rotation: Float, viewport: Size): PaintImageTransform {
        val nextZoom = (zoom * factor).coerceIn(.1f, 20f)
        val oldCenter = Offset(center.x * viewport.width, center.y * viewport.height)
        val moved = centroid + (oldCenter - centroid).rotate(rotation) * (nextZoom / zoom) + pan
        return copy(center = Offset(moved.x / viewport.width, moved.y / viewport.height),
            zoom = nextZoom, degrees = (degrees + rotation) % 360f)
    }
}

private fun Offset.rotate(degrees: Float): Offset {
    val radians = degrees * PI.toFloat() / 180f
    return Offset(x * cos(radians) - y * sin(radians), x * sin(radians) + y * cos(radians))
}

data class PaintImageSession(
    val bitmap: Bitmap,
    val transform: PaintImageTransform = PaintImageTransform(),
    val background: Int? = null,
    val pickingBackground: Boolean = false,
    val colors: Map<Int, Int> = emptyMap(),
    val ready: Boolean = false,
)

internal object PaintImageSampling {
    fun distance(a: Int, b: Int): Int = (0..2).sumOf { channel ->
        val delta = ((a shr (channel * 8)) and 255) - ((b shr (channel * 8)) and 255)
        delta * delta
    }
    fun color(pixel: Int, background: Int?, palette: List<Int>): Int? {
        if ((pixel ushr 24) < 128 || (background != null && distance(pixel, background) <= 24 * 24)) return null
        return palette.minByOrNull { distance(pixel, PaintPalette.rgb(it)) }
    }
    fun pixel(bitmap: Bitmap, point: Offset): Int? =
        if (point.x >= 0 && point.y >= 0 && point.x < bitmap.width && point.y < bitmap.height)
            bitmap.getPixel(floor(point.x).toInt(), floor(point.y).toInt()) else null

    fun sample(session: PaintImageSession, viewport: Size, points: Map<Int, Offset>, brand: BoardBrand): Map<Int, Int> {
        val image = Size(session.bitmap.width.toFloat(), session.bitmap.height.toFloat())
        val palette = PaintPalette.forBoard(brand)
        return buildMap {
            points.forEach { (id, point) ->
                pixel(session.bitmap, session.transform.imagePoint(point, image, viewport))
                    ?.let { color(it, session.background, palette) }?.let { put(id, it) }
            }
        }
    }
}

/** Alpha-preserving, bounded decode; a seekable file also supports Photo Picker HEIF URIs. */
internal suspend fun loadPaintImage(
    context: Context,
    uri: Uri,
    sourceFactory: (File) -> ImageDecoder.Source = { ImageDecoder.createSource(it) },
): Bitmap = withContext(Dispatchers.IO) {
    val file = File.createTempFile("paint-import-", ".bin", context.cacheDir)
    try {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input)
            file.outputStream().use { output -> input.copyTo(output) }
        }
        ImageDecoder.decodeBitmap(sourceFactory(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val ratio = min(1f, 2048f / max(info.size.width, info.size.height))
            decoder.setTargetSize(max(1, (info.size.width * ratio).toInt()), max(1, (info.size.height * ratio).toInt()))
        }
    } finally { file.delete() }
}
