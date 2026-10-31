package com.cruxcoach.android.spraywall

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.cruxcoach.android.nostr.profile.ImageProcessor
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

    /** The stored file, or null for a name that is not one of ours. */
    fun file(fileName: String): File? {
        if (!SAFE_NAME.matches(fileName)) return null
        return File(directory, fileName).takeIf { it.isFile }
    }

    fun delete(fileName: String) {
        if (SAFE_NAME.matches(fileName)) File(directory, fileName).delete()
    }

    /** Decodes a stored photo for the screen, at most [maxLongSide] pixels on its long side. */
    suspend fun load(fileName: String, maxLongSide: Int = DISPLAY_LONG_SIDE): Bitmap? = withContext(Dispatchers.IO) {
        val file = file(fileName) ?: return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val long = max(bounds.outWidth, bounds.outHeight)
        if (long <= 0) return@withContext null
        var sample = 1
        while (long / (sample * 2) >= maxLongSide) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
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
