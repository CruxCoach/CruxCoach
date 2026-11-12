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

    /** [imagePoint] as one affine map, into the mapping's copy of the picture (which may be smaller). */
    fun pixelMap(image: Size, viewport: Size, pixels: PaintPixels): PaintAffine {
        val origin = imagePoint(Offset.Zero, image, viewport)
        val right = imagePoint(Offset(viewport.width, 0f), image, viewport)
        val down = imagePoint(Offset(0f, viewport.height), image, viewport)
        val sx = pixels.width / image.width
        val sy = pixels.height / image.height
        return PaintAffine(
            (right.x - origin.x) / viewport.width * sx, (down.x - origin.x) / viewport.height * sx, origin.x * sx,
            (right.y - origin.y) / viewport.width * sy, (down.y - origin.y) / viewport.height * sy, origin.y * sy,
        )
    }

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
    /** The copy of the picture the mapping reads; made once when the photo is loaded. */
    val pixels: PaintPixels? = null,
    /** What the border of the picture says about its background. */
    val detected: PaintBackground = PaintBackground(),
    /** The background lights no LEDs: [background] if the user picked one, else the detected colour. */
    val backgroundOff: Boolean = detected.clear,
    /** The photo has its place. A newly loaded photo is put in place once by the editor, then only by the user. */
    val placed: Boolean = true,
    /** The user asked to settle the photo on the holds where it lies. */
    val aligning: Boolean = false,
    /** The prepared picture; it is made again only when the background changes. */
    internal val pictures: PaintPictures = PaintPictures(),
) {
    /** The colour that stays dark, or null when the whole picture is lit. */
    val darkBackground: Int? get() = if (backgroundOff) background ?: detected.color else null
}

/** Keeps the prepared picture of a photo for the background it was prepared with. */
class PaintPictures {
    private var pixels: PaintPixels? = null
    private var background: Int? = null
    private var transparent = false
    private var picture: PaintPicture? = null

    @Synchronized internal fun of(pixels: PaintPixels, background: Int?, transparent: Boolean): PaintPicture {
        picture?.takeIf { this.pixels === pixels && this.background == background && this.transparent == transparent }?.let { return it }
        return PaintImageMapping.picture(pixels, background, transparent).also {
            this.pixels = pixels; this.background = background; this.transparent = transparent; picture = it
        }
    }
}

/** A copy of at most [side] pixels a side: plenty for any board, and four megabytes at most. */
internal fun Bitmap.toPaintPixels(side: Int = 1024): PaintPixels {
    val ratio = min(1f, side.toFloat() / max(width, height))
    val w = max(1, (width * ratio).roundToInt())
    val h = max(1, (height * ratio).roundToInt())
    val source = if (ratio < 1f) Bitmap.createScaledBitmap(this, w, h, true) else this
    val argb = IntArray(w * h)
    source.getPixels(argb, 0, w, 0, 0, w, h)
    if (source !== this) source.recycle()
    return PaintPixels(w, h, argb)
}

internal object PaintImageSampling {
    fun pixel(bitmap: Bitmap, point: Offset): Int? =
        if (point.x >= 0 && point.y >= 0 && point.x < bitmap.width && point.y < bitmap.height)
            bitmap.getPixel(floor(point.x).toInt(), floor(point.y).toInt()) else null

    private fun picture(session: PaintImageSession): PaintPicture =
        session.pictures.of(session.pixels ?: session.bitmap.toPaintPixels(), session.darkBackground, session.detected.transparent)

    fun sample(session: PaintImageSession, viewport: Size, points: Map<Int, Offset>, brand: BoardBrand): Map<Int, Int> {
        val picture = picture(session)
        val image = Size(session.bitmap.width.toFloat(), session.bitmap.height.toFloat())
        return PaintImageMapping.map(picture, session.transform.pixelMap(image, viewport, picture.pixels), points,
            PaintPalette.forBoard(brand))
    }

    /** Where the photo falls most cleanly on the holds: a new photo anywhere on the board, a placed one close to where it lies. */
    fun place(session: PaintImageSession, viewport: Size, points: Map<Int, Offset>): PaintImageTransform {
        val image = Size(session.bitmap.width.toFloat(), session.bitmap.height.toFloat())
        return PaintImageMapping.place(picture(session), image, viewport, points, session.transform.takeIf { session.placed })
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
