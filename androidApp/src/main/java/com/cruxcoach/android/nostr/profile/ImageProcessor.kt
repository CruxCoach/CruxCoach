package com.cruxcoach.android.nostr.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
        val resolver = context.contentResolver
        // Pass 1: bounds-only decode to know the raw dimensions.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("Could not decode image at $uri")
        }
        val sampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)

        // Pass 2: real decode at the chosen sample size.
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val raw = resolver.openInputStream(uri).use { input ->
            BitmapFactory.decodeStream(input, null, opts)
        } ?: throw IOException("Could not decode image at $uri")

        // Final scale to the exact long-edge limit. inSampleSize sub-samples
        // by powers of 2 only, so the Bitmap from pass 2 may still be up
        // to ~2× too big.
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
