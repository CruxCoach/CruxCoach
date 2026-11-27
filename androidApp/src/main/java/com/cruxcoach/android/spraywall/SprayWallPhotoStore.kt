package com.cruxcoach.android.spraywall

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.cruxcoach.android.nostr.profile.ImageProcessor
import com.cruxcoach.domain.spraywall.SprayCropRect
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Spray-wall photos (FEAT-078), stored in the app's private files under
 * `spray_walls/` and kept out of Android backups (backup_rules.xml).
 *
 * An imported photo is decoded with its orientation applied, shrunk to at
 * most [MAX_LONG_SIDE] pixels and written again as a plain JPEG, so no
 * camera metadata (location, device) from the original is kept.
 */
@Singleton
class SprayWallPhotoStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageProcessor: ImageProcessor,
) {
    data class StoredPhoto(val fileName: String, val width: Int, val height: Int)

    private val directory: File get() = File(context.filesDir, DIRECTORY).apply { mkdirs() }

    suspend fun import(uri: Uri): StoredPhoto = withContext(Dispatchers.IO) {
        val bytes = imageProcessor.loadAndCompress(uri, MAX_LONG_SIDE, JPEG_QUALITY)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "not a picture" }
        val name = "${UUID.randomUUID()}.jpg"
        val part = File(directory, "$name.part")
        part.writeBytes(bytes)
        check(part.renameTo(File(directory, name))) { "could not store the photo" }
        StoredPhoto(name, bounds.outWidth, bounds.outHeight)
    }

    /**
     * A new photo made from a stored one: turned [quarterTurns] times clockwise,
     * then cropped to [crop] (0..1 of the turned photo). The source file stays;
     * the caller deletes it when it is no longer needed.
     */
    suspend fun adjust(fileName: String, quarterTurns: Int, crop: SprayCropRect): StoredPhoto = withContext(Dispatchers.IO) {
        val source = file(fileName) ?: error("unknown photo")
        val original = BitmapFactory.decodeFile(source.path) ?: error("unreadable photo")
        val turns = ((quarterTurns % 4) + 4) % 4
        val turned = if (turns == 0) original else {
            val m = android.graphics.Matrix().apply { postRotate(90f * turns) }
            Bitmap.createBitmap(original, 0, 0, original.width, original.height, m, true)
        }
        val x = (crop.left * turned.width).toInt().coerceIn(0, turned.width - 1)
        val y = (crop.top * turned.height).toInt().coerceIn(0, turned.height - 1)
        val w = (crop.width * turned.width).toInt().coerceIn(1, turned.width - x)
        val h = (crop.height * turned.height).toInt().coerceIn(1, turned.height - y)
        val cropped = if (x == 0 && y == 0 && w == turned.width && h == turned.height) turned else Bitmap.createBitmap(turned, x, y, w, h)
        try {
            val name = "${UUID.randomUUID()}.jpg"
            val part = File(directory, "$name.part")
            part.outputStream().use { cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            check(part.renameTo(File(directory, name))) { "could not store the photo" }
            StoredPhoto(name, cropped.width, cropped.height)
        } finally {
            if (cropped !== turned) cropped.recycle()
            if (turned !== original) turned.recycle()
            original.recycle()
        }
    }

    /** Pixels of a stored photo for the hold detector, at most [maxLongSide] on the long side. */
    suspend fun pixels(fileName: String, maxLongSide: Int): Triple<Int, Int, IntArray>? = withContext(Dispatchers.IO) {
        val bitmap = decode(fileName, maxLongSide * 2) ?: return@withContext null
        val long = max(bitmap.width, bitmap.height)
        val scaled = if (long <= maxLongSide) bitmap else {
            val s = maxLongSide.toFloat() / long
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * s).toInt().coerceAtLeast(1), (bitmap.height * s).toInt().coerceAtLeast(1), true)
        }
        val out = IntArray(scaled.width * scaled.height)
        scaled.getPixels(out, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        val result = Triple(scaled.width, scaled.height, out)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        result
    }

    /** A stored photo with at least [minLongSide] on its long side (or its full size), for the hold model. */
    suspend fun bitmap(fileName: String, minLongSide: Int): Bitmap? = withContext(Dispatchers.IO) { decode(fileName, minLongSide) }

    /** Stores a photo downloaded from Blossom (someone else's public wall), after its hash was checked. */
    suspend fun storeDownloaded(bytes: ByteArray): StoredPhoto = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "not a picture" }
        require(bounds.outMimeType == "image/jpeg" || bounds.outMimeType == "image/webp" || bounds.outMimeType == "image/png") { "unexpected type" }
        val name = "${UUID.randomUUID()}.jpg"
        val part = File(directory, "$name.part")
        part.writeBytes(bytes)
        check(part.renameTo(File(directory, name))) { "could not store the photo" }
        StoredPhoto(name, bounds.outWidth, bounds.outHeight)
    }

    /** The bytes of a stored photo, for the Blossom upload. */
    suspend fun bytes(fileName: String): ByteArray? = withContext(Dispatchers.IO) { file(fileName)?.readBytes() }

    /** The stored file, or null for a name that is not one of ours. */
    fun file(fileName: String): File? {
        if (!SAFE_NAME.matches(fileName)) return null
        return File(directory, fileName).takeIf { it.isFile }
    }

    fun delete(fileName: String) {
        if (SAFE_NAME.matches(fileName)) File(directory, fileName).delete()
    }

    /**
     * Decodes a stored photo for the screen, at most [maxLongSide] pixels on
     * its long side. A full-size photo (3072 px) would otherwise reach the
     * screen whole and stall the first frame while it is uploaded.
     */
    suspend fun load(fileName: String, maxLongSide: Int = DISPLAY_LONG_SIDE): Bitmap? = withContext(Dispatchers.IO) {
        val decoded = decode(fileName, maxLongSide) ?: return@withContext null
        val long = max(decoded.width, decoded.height)
        val bitmap = if (long <= maxLongSide) decoded else {
            val s = maxLongSide.toFloat() / long
            Bitmap.createScaledBitmap(decoded, (decoded.width * s).toInt().coerceAtLeast(1), (decoded.height * s).toInt().coerceAtLeast(1), true)
                .also { decoded.recycle() }
        }
        // Starts the upload to the GPU now, not on the first frame that draws it.
        bitmap.prepareToDraw()
        bitmap
    }

    /** Decodes with the largest power-of-two sample that keeps at least [minLongSide] pixels on the long side. */
    private fun decode(fileName: String, minLongSide: Int): Bitmap? {
        val file = file(fileName) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val long = max(bounds.outWidth, bounds.outHeight)
        if (long <= 0) return null
        var sample = 1
        while (long / (sample * 2) >= minLongSide) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    companion object {
        const val DIRECTORY = "spray_walls"
        /** Sharp enough to tell small footholds apart at full zoom, small enough for any phone. */
        const val MAX_LONG_SIDE = 3072
        const val DISPLAY_LONG_SIDE = 2048
        const val JPEG_QUALITY = 90
        private val SAFE_NAME = Regex("[A-Za-z0-9-]{1,64}\\.jpg")
    }
}
