package com.cruxcoach.android.foodvision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A photo scaled for the model, as raw RGB in the cache for the ":vision" process. */
data class PreparedImage(val rgbFile: File, val width: Int, val height: Int, val preview: Bitmap)

/** Prompt files shipped in assets/foodvision; tools/food-vision evaluates the same files. */
data class VisionPrompt(val system: String, val user: String, val grammar: String) {
    companion object {
        private const val VERSION = "v1"

        fun load(context: Context): VisionPrompt {
            fun read(name: String) = context.assets.open("foodvision/$name").bufferedReader().use { it.readText() }
            return VisionPrompt(
                system = read("system-$VERSION.txt").trim(),
                user = read("user-$VERSION.txt").trim(),
                grammar = read("grammar-$VERSION.gbnf"),
            )
        }
    }
}

object PhotoInput {
    /**
     * Longest side handed to the model. 640 px gives Qwen3.5 roughly 300
     * image tokens for a 4:3 photo — enough to tell rice from couscous, and
     * the image encoder's time grows with the square of this value.
     */
    const val MAX_SIDE = 640

    /** Decodes [uri] (EXIF orientation applied by ImageDecoder) and scales it down. */
    fun prepare(context: Context, uri: Uri): PreparedImage {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = min(1.0, MAX_SIDE.toDouble() / max(w, h))
            decoder.setTargetSize(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return fromBitmap(context, bitmap)
    }

    fun fromBitmap(context: Context, source: Bitmap): PreparedImage {
        val bitmap = if (source.config == Bitmap.Config.ARGB_8888) source else source.copy(Bitmap.Config.ARGB_8888, false)
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val rgb = ByteArray(w * h * 3)
        pixels.forEachIndexed { i, p ->
            rgb[i * 3] = (p shr 16 and 0xff).toByte()
            rgb[i * 3 + 1] = (p shr 8 and 0xff).toByte()
            rgb[i * 3 + 2] = (p and 0xff).toByte()
        }
        val file = File(File(context.cacheDir, "foodvision").apply { mkdirs() }, "input.rgb")
        file.writeBytes(rgb)
        return PreparedImage(file, w, h, bitmap)
    }

    /**
     * A drawn plate for the probe run after the download: same size and
     * token count as a real photo, no photo of anyone's meal involved.
     */
    fun probeImage(context: Context): PreparedImage {
        val bitmap = Bitmap.createBitmap(MAX_SIDE, MAX_SIDE * 3 / 4, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(196, 160, 120))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        canvas.drawCircle(320f, 240f, 210f, paint)
        paint.color = Color.rgb(245, 240, 225)
        canvas.drawCircle(250f, 210f, 90f, paint)
        paint.color = Color.rgb(60, 140, 60)
        canvas.drawCircle(400f, 220f, 70f, paint)
        paint.color = Color.rgb(200, 120, 60)
        canvas.drawCircle(330f, 340f, 60f, paint)
        return fromBitmap(context, bitmap)
    }
}
