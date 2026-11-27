package com.cruxcoach.android.ui.map

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.DisplayMetrics
import com.cruxcoach.domain.board.BoardBrand
import kotlin.math.cos
import kotlin.math.sin

/**
 * Marker colour and monogram per board family. The hues follow the
 * cruxcoach.org board map (Kilter red, MoonBoard yellow, Grasshopper cyan, …)
 * so a gym looks the same on the web and in the app; a few are darkened so a
 * white ring and the letter stay readable on the map tiles. [onColor] is the
 * letter colour that keeps contrast on [color] (dark on yellow and cyan).
 */
data class BrandMarkerStyle(val color: Int, val onColor: Int, val monogram: String)

private const val WHITE = 0xFFFFFFFF.toInt()
private const val INK = 0xFF1A1A1A.toInt()

fun BoardBrand.markerStyle(): BrandMarkerStyle = when (this) {
    BoardBrand.KILTER -> BrandMarkerStyle(0xFFE5232A.toInt(), WHITE, "K")
    BoardBrand.MOONBOARD -> BrandMarkerStyle(0xFFFDB515.toInt(), INK, "M")
    BoardBrand.TENSION -> BrandMarkerStyle(0xFF566573.toInt(), WHITE, "T")
    BoardBrand.GRASSHOPPER -> BrandMarkerStyle(0xFF12B5CB.toInt(), INK, "G")
    BoardBrand.DECOY -> BrandMarkerStyle(0xFFAB47BC.toInt(), WHITE, "D")
    BoardBrand.SOILL -> BrandMarkerStyle(0xFF5E8F6E.toInt(), WHITE, "S")
    BoardBrand.TOUCHSTONE -> BrandMarkerStyle(0xFF3F7FC1.toInt(), WHITE, "Ts")
    BoardBrand.QUANTUM -> BrandMarkerStyle(0xFF00897B.toInt(), WHITE, "Q")
    BoardBrand.SHINYWALL -> BrandMarkerStyle(0xFFEF6C00.toInt(), WHITE, "Sw")
    BoardBrand.AURORA -> BrandMarkerStyle(0xFFB93655.toInt(), WHITE, "A")
    BoardBrand.TWELVECLIMB -> BrandMarkerStyle(0xFFE0185F.toInt(), WHITE, "12")
}

/**
 * Draws the venue markers as bitmaps for MapLibre's symbol layer: a round
 * badge in the brand colour with the brand's monogram, white ring and a soft
 * shadow; a venue with several families gets one pie segment per family
 * (12 o'clock first), like the website's markers. One image per family
 * combination, registered under [iconId].
 */
object MapMarkerIcons {
    /** Marker diameter at icon-size 1.0, in dp. */
    const val DIAMETER_DP = 26f
    private const val RING_DP = 2f
    private const val SHADOW_DP = 3f

    fun iconId(brands: List<BoardBrand>): String =
        "venue-" + brands.joinToString("+") { it.wireValue }

    /** [density] is the display density (1.0 = mdpi); the bitmap carries it so
     *  MapLibre draws it at [DIAMETER_DP] regardless of the screen. */
    fun render(brands: List<BoardBrand>, density: Float): Bitmap {
        require(brands.isNotEmpty())
        val pad = SHADOW_DP * density
        val radius = DIAMETER_DP / 2f * density
        val size = Math.round(2 * (radius + pad))
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.density = Math.round(density * DisplayMetrics.DENSITY_DEFAULT)
        val canvas = Canvas(bitmap)
        val c = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Soft drop shadow so the marker lifts off busy tiles.
        paint.color = 0x55000000
        paint.maskFilter = BlurMaskFilter(1.6f * density, BlurMaskFilter.Blur.NORMAL)
        canvas.drawCircle(c, c + 0.8f * density, radius, paint)
        paint.maskFilter = null

        paint.color = WHITE
        canvas.drawCircle(c, c, radius, paint)

        val inner = radius - RING_DP * density
        val styles = brands.map { it.markerStyle() }
        if (styles.size == 1) {
            paint.color = styles[0].color
            canvas.drawCircle(c, c, inner, paint)
            drawMonogram(canvas, styles[0], c, density)
        } else {
            val oval = RectF(c - inner, c - inner, c + inner, c + inner)
            val sweep = 360f / styles.size
            styles.forEachIndexed { i, style ->
                paint.color = style.color
                canvas.drawArc(oval, -90f + i * sweep, sweep, true, paint)
            }
            // Thin white seams keep neighbouring colours apart (red/pink, cyan/blue).
            paint.color = WHITE
            paint.strokeWidth = 1.2f * density
            paint.style = Paint.Style.STROKE
            styles.indices.forEach { i ->
                val angle = Math.toRadians((-90.0 + i * sweep))
                canvas.drawLine(c, c, c + inner * cos(angle).toFloat(), c + inner * sin(angle).toFloat(), paint)
            }
            paint.style = Paint.Style.FILL
        }
        return bitmap
    }

    private fun drawMonogram(canvas: Canvas, style: BrandMarkerStyle, c: Float, density: Float) {
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.onColor
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = (if (style.monogram.length > 1) 10.5f else 12.5f) * density
        }
        val metrics = text.fontMetrics
        val baseline = c - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(style.monogram, c, baseline, text)
    }
}
