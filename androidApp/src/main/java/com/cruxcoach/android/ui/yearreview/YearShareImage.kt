package com.cruxcoach.android.ui.yearreview

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Draws a year-in-review page as an image on the phone: 1080 × 1920 for a page,
 * 1080 × 1080 for the summary. The layout has fixed room for every piece of
 * text; what does not fit is made smaller and then cut with an ellipsis, so a
 * long name or a long German word never runs off the image. Nothing is loaded
 * from the network.
 */
object YearShareImage {

    const val WIDTH = 1080
    const val STORY_HEIGHT = 1920
    const val SQUARE_HEIGHT = 1080

    private const val MARGIN = 80f
    /** A story's content block sits between these. */
    private const val STORY_TOP = 150f
    private const val STORY_FOOTER = 200f
    private val BACKGROUND_TOP = 0xFF121212.toInt()
    private val BACKGROUND_BOTTOM = 0xFF1A1A2E.toInt()
    private val ACCENT = 0xFFFF6D00.toInt()
    private val TEXT = 0xFFFFFFFF.toInt()
    private val MUTED = 0xB3FFFFFF.toInt()
    private val FAINT = 0x80FFFFFF.toInt()
    private val DIVIDER = 0x26FFFFFF.toInt()

    /**
     * [header] replaces the page's eyebrow, e.g. with the climber's name; [wordmark]
     * is the app's name; [boardColors] colours rows of a board (ARGB).
     */
    fun render(page: ReviewPage, header: String?, wordmark: String, boardColors: Map<String, Int> = emptyMap()): Bitmap {
        val square = page.kind == ReviewPageKind.SUMMARY
        val height = if (square) SQUARE_HEIGHT else STORY_HEIGHT
        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), BACKGROUND_TOP, BACKGROUND_BOTTOM, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), height.toFloat(), paint)
        paint.shader = null
        paint.color = ACCENT
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), 14f, paint)

        val width = WIDTH - 2 * MARGIN
        if (square) {
            var y = 90f
            (header ?: page.eyebrow).takeIf { it.isNotEmpty() }?.let { y = text(canvas, it, MARGIN, y, width, 40f, ACCENT, bold = true, maxLines = 1) + 18f }
            y = text(canvas, page.title, MARGIN, y, width, 64f, TEXT, bold = true, maxLines = 2) + 40f
            grid(canvas, page.rows, y, width, boardColors)
        } else {
            // Drawn into its own layer first, so the block can sit in the middle of the image.
            val room = STORY_HEIGHT - STORY_TOP - STORY_FOOTER
            val layer = Bitmap.createBitmap(WIDTH, room.toInt(), Bitmap.Config.ARGB_8888)
            val content = Canvas(layer)
            var y = 0f
            (header ?: page.eyebrow).takeIf { it.isNotEmpty() }?.let { y = text(content, it, MARGIN, y, width, 40f, ACCENT, bold = true, maxLines = 1) + 18f }
            y = text(content, page.title, MARGIN, y, width, 76f, TEXT, bold = true, maxLines = 2) + 40f
            page.hero?.let { y = fitted(content, it, MARGIN, y, width, 210f, ACCENT) + 8f }
            page.heroLabel?.let { y = text(content, it, MARGIN, y, width, 46f, MUTED, maxLines = 2) + 40f }
            page.lead?.let { y = text(content, it, MARGIN, y, width, 46f, TEXT, maxLines = 5) + 48f }
            page.months?.let { y = months(content, it, page.busiestMonth, MARGIN, y, width) + 48f }
            y = rows(content, page.rows, y, width, footer = room, boardColors)
            val top = STORY_TOP + ((room - y) / 2).coerceAtLeast(0f) * 0.7f
            canvas.drawBitmap(layer, 0f, top, null)
            layer.recycle()
        }

        // The wordmark, small and without a link.
        text(canvas, wordmark, MARGIN, height - (if (square) 100f else 130f), width, 38f, FAINT, bold = true, maxLines = 1)
        return bitmap
    }

    /** Writes [bitmap] to the app's cache and opens the share sheet for it. */
    suspend fun share(context: Context, bitmap: Bitmap, name: String, chooser: String) {
        val file = withContext(Dispatchers.IO) {
            File(context.cacheDir, "recap").apply { mkdirs() }
                .resolve("$name.png")
                .also { out -> out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            // Lets the share sheet show the image as its preview.
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, chooser))
    }

    /** Draws [rows] from [top] while they fit above [footer]; returns the bottom of the last one. */
    private fun rows(canvas: Canvas, rows: List<ReviewRow>, top: Float, width: Float, footer: Float, colors: Map<String, Int>): Float {
        var y = top
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // When any row has a board's dot, every row keeps its slot, so the labels line up.
        val indent = if (rows.any { row -> row.board?.let { colors[it] } != null }) 32f else 0f
        for (row in rows) {
            val valueWidth = width * 0.42f
            val labelWidth = width - valueWidth - 24f - indent
            val labelLayout = layout(row.label, labelWidth, 40f, MUTED, bold = false, maxLines = 2)
            val valueLayout = layout(row.value, valueWidth, 46f, TEXT, bold = true, maxLines = 2, align = Layout.Alignment.ALIGN_OPPOSITE)
            val detailLayout = row.detail?.takeIf { it.isNotBlank() }?.let { layout(it, width - indent, 34f, FAINT, bold = false, maxLines = 2) }
            val height = maxOf(labelLayout.height, valueLayout.height) + (detailLayout?.height?.plus(6) ?: 0)
            if (y + height > footer) break
            val x = MARGIN + indent
            row.board?.let { colors[it] }?.let { color ->
                paint.color = color
                canvas.drawCircle(MARGIN + 10f, y + 26f, 10f, paint)
            }
            draw(canvas, labelLayout, x, y)
            draw(canvas, valueLayout, MARGIN + width - valueWidth, y)
            var bottom = y + maxOf(labelLayout.height, valueLayout.height)
            detailLayout?.let {
                draw(canvas, it, x, bottom + 6f)
                bottom += it.height + 6f
            }
            y = bottom + 26f
            paint.color = DIVIDER
            canvas.drawRect(MARGIN, y - 13f, MARGIN + width, y - 11f, paint)
        }
        return y
    }

    /** The summary's numbers in two columns; a board's colour only as a dot before its label. */
    private fun grid(canvas: Canvas, rows: List<ReviewRow>, top: Float, width: Float, colors: Map<String, Int>) {
        val column = (width - 40f) / 2
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        rows.take(6).forEachIndexed { i, row ->
            val x = MARGIN + (i % 2) * (column + 40f)
            val y = top + (i / 2) * 230f
            val valueBottom = fitted(canvas, row.value, x, y, column, 96f, TEXT)
            val color = row.board?.let { colors[it] }
            if (color != null) {
                paint.color = color
                canvas.drawCircle(x + 9f, valueBottom + 26f, 9f, paint)
            }
            val labelX = if (color != null) x + 28f else x
            text(canvas, row.label, labelX, valueBottom + 4f, column - (labelX - x), 36f, MUTED, maxLines = 2)
        }
    }

    private fun months(canvas: Canvas, perMonth: List<Int>, busiest: Int?, x: Float, top: Float, width: Float): Float {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val most = perMonth.maxOrNull()?.coerceAtLeast(1) ?: 1
        val gap = 14f
        val bar = (width - gap * 11) / 12
        val chart = 260f
        perMonth.forEachIndexed { i, days ->
            val h = (chart * days / most).coerceAtLeast(if (days > 0) 8f else 3f)
            paint.color = if (i + 1 == busiest) ACCENT else MUTED
            val left = x + i * (bar + gap)
            canvas.drawRoundRect(RectF(left, top + chart - h, left + bar, top + chart), 8f, 8f, paint)
        }
        return top + chart
    }

    /** One line that shrinks until it fits [width]: the hero number or name. Returns its bottom. */
    private fun fitted(canvas: Canvas, value: String, x: Float, top: Float, width: Float, size: Float, color: Int): Float {
        val paint = textPaint(size, color, bold = true)
        while (paint.measureText(value) > width && paint.textSize > 36f) paint.textSize -= 6f
        val shown = TextUtils.ellipsize(value, paint, width, TextUtils.TruncateAt.END).toString()
        val baseline = top - paint.fontMetrics.ascent
        canvas.drawText(shown, x, baseline, paint)
        return baseline + paint.fontMetrics.descent
    }

    /** Text in [width], at most [maxLines] lines, shrunk once if needed and then cut. Returns its bottom. */
    private fun text(
        canvas: Canvas, value: String, x: Float, top: Float, width: Float, size: Float, color: Int,
        bold: Boolean = false, maxLines: Int,
    ): Float {
        val layout = layout(value, width, size, color, bold, maxLines)
        draw(canvas, layout, x, top)
        return top + layout.height
    }

    private fun layout(
        value: String, width: Float, size: Float, color: Int, bold: Boolean, maxLines: Int,
        align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
    ): StaticLayout {
        fun build(paint: TextPaint) = StaticLayout.Builder.obtain(value, 0, value.length, paint, width.toInt())
            .setAlignment(align)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(0f, 1.05f)
            .build()
        val paint = textPaint(size, color, bold)
        val first = build(paint)
        if (!first.isEllipsized()) return first
        paint.textSize = size * 0.85f
        return build(paint)
    }

    private fun StaticLayout.isEllipsized(): Boolean = (0 until lineCount).any { getEllipsisCount(it) > 0 }

    private fun draw(canvas: Canvas, layout: StaticLayout, x: Float, y: Float) {
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun textPaint(size: Float, color: Int, bold: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
    }
}
