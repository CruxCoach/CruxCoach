package com.cruxcoach.android.nostr.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Bitmap → JPEG-bytes pipeline for FEAT-010 profile-image uploads.
 *
 * The pipeline mirrors what Amethyst's `MediaCompressor` does, minus
 * the configurable quality tiers (we only need one — MEDIUM ≈ q=85,
 * a deliberate compromise between file size and visual quality on
 * faces / banner photos).
 *
 * Two-pass decode keeps memory bounded:
 *   1. `inJustDecodeBounds = true` — read width/height, no pixels
 *   2. Pick `inSampleSize` so the decoded Bitmap is at most ~2× the
 *      target dimension; Android can only sub-sample by powers of 2
 *      so we then [createScaledBitmap] to the exact target.
 *
 * Caller decides [maxDimension] (1024 for profile picture, 1920 for
 * banner per FEAT-010 §3.3) and [jpegQuality] (default 85).
 */
@Singleton
class ImageProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun loadAndCompress(
        uri: Uri,
        maxDimension: Int,
        jpegQuality: Int = DEFAULT_JPEG_QUALITY,
    ): ByteArray = withContext(Dispatchers.IO) {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeWithImageDecoder(uri, maxDimension)
        } else {
            decodeWithBitmapFactory(uri, maxDimension)
        }
        // Final scale to the exact long-edge limit. ImageDecoder's
        // setTargetSize is exact, so this is a no-op there; the
        // BitmapFactory path can come out up to 2× too big because
        // inSampleSize is power-of-2 only.
        val scaled = scaleToLongEdge(raw, maxDimension)
        try {
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
                out.toByteArray()
            }
        } finally {
            if (scaled !== raw) scaled.recycle()
            raw.recycle()
        }
    }

    /**
     * Modern decode path (API 28+) — handles HEIF/AVIF/WebP/JPEG/PNG
     * uniformly via the platform's [ImageDecoder]. Critically, it
     * handles the photo-picker URIs (`content://media/picker/...`)
     * that the legacy [BitmapFactory.decodeStream] mis-parses as
     * "corrupt PNG" when the underlying file is HEIF (modern phone-
     * camera default since 2019).
     *
     * Bytes are spooled to a temp file first instead of going through
     * `ImageDecoder.createSource(resolver, uri)` or `createSource(byteBuffer)`:
     *  - `createSource(resolver, uri)` uses `openTypedAssetFileDescriptor`,
     *    which Glide and ImageDecoder both fail on Photo-Picker URIs
     *    with "Input was incomplete" on some OEM forks.
     *  - `createSource(byteBuffer)` is unreliable for HEIF — libheif
     *    needs a seekable, length-known source, and a memory-backed
     *    ByteBuffer trips the "incomplete" path on some encodings.
     * The temp-file route is what Coil's `BitmapFactoryDecoder` and
     * Glide's `LocalUriFetcher` fall back to and is the most robust.
     *
     * `setTargetSize` is exact — we don't need a follow-up scale for
     * power-of-2 boundary correction here.
     *
     * On any decoder failure (e.g. legacy WMF / unsupported format),
     * we fall back to [decodeWithBitmapFactory] which still handles
     * JPEG/PNG/WebP. Final-line failure throws and the caller toasts.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(uri: Uri, maxDimension: Int): Bitmap {
        val resolver = context.contentResolver
        val tempFile = java.io.File.createTempFile("imgproc_", ".bin", context.cacheDir)
        var bytesCopied = 0L
        try {
            bytesCopied = resolver.openInputStream(uri).use { input ->
                if (input == null) throw IOException("Could not open stream for $uri")
                java.io.FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (bytesCopied == 0L) {
                throw IOException("Empty image stream at $uri")
            }
            val source = ImageDecoder.createSource(tempFile)
            return try {
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    // Mutable so the JPEG-compress step can run on the
                    // result; ImageDecoder defaults to immutable Bitmaps.
                    decoder.isMutableRequired = true
                    val (w, h) = info.size.width to info.size.height
                    if (w > 0 && h > 0) {
                        val longest = max(w, h)
                        if (longest > maxDimension) {
                            val ratio = maxDimension.toFloat() / longest
                            val targetW = (w * ratio).toInt().coerceAtLeast(1)
                            val targetH = (h * ratio).toInt().coerceAtLeast(1)
                            decoder.setTargetSize(targetW, targetH)
                        }
                    }
                }
            } catch (e: Exception) {
                // Last-resort: BitmapFactory on the same temp file.
                // Handles formats ImageDecoder rejects on this OEM.
                decodeFileWithBitmapFactory(tempFile, maxDimension)
                    ?: throw IOException(
                        "Could not decode image at $uri (size=${bytesCopied}B)", e,
                    )
            }
        } finally {
            tempFile.delete()
        }
    }

    private fun decodeFileWithBitmapFactory(file: java.io.File, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    /** Legacy decode path for API < 28 (no [ImageDecoder]). Handles
     *  JPEG/PNG/WebP but stumbles on HEIF/AVIF — those formats land
     *  on phones running Android 9+ (API 28+) anyway, so the legacy
     *  path is fine for the API-26/27 minority. */
    private fun decodeWithBitmapFactory(uri: Uri, maxDimension: Int): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("Could not decode image at $uri")
        }
        val sampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return resolver.openInputStream(uri).use { input ->
            BitmapFactory.decodeStream(input, null, opts)
        } ?: throw IOException("Could not decode image at $uri")
    }

    companion object {
        const val MAX_DIMENSION_PICTURE = 1024
        const val MAX_DIMENSION_BANNER = 1920
        const val DEFAULT_JPEG_QUALITY = 85

        /** Picks the smallest power-of-2 sample size such that decoding
         *  the image stays within ~2× [maxDimension] on the long edge. */
        internal fun computeSampleSize(width: Int, height: Int, maxDimension: Int): Int {
            if (width <= 0 || height <= 0 || maxDimension <= 0) return 1
            val longest = max(width, height)
            var sample = 1
            while (longest / sample > maxDimension * 2) sample *= 2
            return sample
        }

        private fun scaleToLongEdge(bitmap: Bitmap, maxDimension: Int): Bitmap {
            val longest = max(bitmap.width, bitmap.height)
            if (longest <= maxDimension) return bitmap
            val ratio = maxDimension.toFloat() / longest
            val targetW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
            val targetH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(bitmap, targetW, targetH, /*filter=*/true)
        }
    }
}
