package com.cruxcoach.android.ui.paintbox

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/** The picture mapping on drawn test pictures: plain pixel arrays, no Android. */
class PaintImageMappingTest {
    private class Board(val name: String, val viewport: Size, val points: Map<Int, Offset>, val spacing: Float) {
        /** A square picture is fitted and centred: its position (0..1) in the viewport. */
        fun at(u: Float, v: Float): Offset {
            val side = min(viewport.width, viewport.height)
            return Offset(viewport.width / 2 + (u - .5f) * side, viewport.height / 2 + (v - .5f) * side)
        }
        val side get() = min(viewport.width, viewport.height)
        fun distance(id: Int, to: Offset) = (points.getValue(id) - to).getDistance()
        /** Holds within [reach] hold spacings of each other. */
        fun linked(a: Int, b: Int, reach: Float) = a != b && (points.getValue(a) - points.getValue(b)).getDistance() <= reach * spacing
        /** The holds of [among] that can be reached from [start] over links of [reach] spacings. */
        fun reachable(start: Int, among: Set<Int>, reach: Float): Set<Int> {
            val seen = mutableSetOf(start)
            val queue = ArrayDeque(listOf(start))
            while (queue.isNotEmpty()) {
                val next = queue.removeFirst()
                among.filter { it !in seen && linked(next, it, reach) }.forEach { seen.add(it); queue.add(it) }
            }
            return seen
        }
    }

    /** Staggered rows like a Kilter board: 490 holds, the nearest ones on the diagonals. */
    private fun kilterLike(): Board {
        val unit = 1000f / 144f
        val points = buildMap {
            var id = 1
            for (row in 0 until 28) for (col in 0 until 18) {
                val x = 8f * col + if (row % 2 == 0) 4f else 8f
                if (x <= 140f) put(id++, Offset(x * unit, (4f + 4f * row) * unit))
            }
        }
        return Board("staggered", Size(1000f, 116f * unit), points, 4f * sqrt(2f) * unit)
    }

    /** The MoonBoard grid: 11 columns, 18 rows. */
    private fun moonGrid(): Board {
        val points = buildMap {
            for (row in 0 until 18) for (col in 0 until 11) put(row * 11 + col + 1, Offset(25f + 50f * col, 25f + 50f * row))
        }
        return Board("grid", Size(550f, 900f), points, 50f)
    }

    /** A square picture; [color] gets the position 0..1. */
    private fun picture(side: Int = 400, color: (Float, Float) -> Int) =
        PaintPixels(side, side, IntArray(side * side) { color((it % side + .5f) / side, (it / side + .5f) / side) })

    private fun map(
        board: Board, pixels: PaintPixels, brand: BoardBrand = BoardBrand.KILTER, background: Int? = null,
        transparent: Boolean = false, transform: PaintImageTransform = PaintImageTransform(),
    ): Map<Int, Int> = PaintImageMapping.map(
        pixels, transform.pixelMap(Size(pixels.width.toFloat(), pixels.height.toFloat()), board.viewport, pixels),
        board.points, PaintPalette.forBoard(brand), background, transparent,
    )

    /** Maps with what the border says, as the editor does by default. */
    private fun mapDetected(board: Board, pixels: PaintPixels, brand: BoardBrand = BoardBrand.KILTER,
                            transform: PaintImageTransform = PaintImageTransform()): Map<Int, Int> {
        val detected = PaintImageMapping.background(pixels)
        return map(board, pixels, brand, detected.color.takeIf { detected.clear }, detected.transparent, transform)
    }

    private fun disc(u: Float, v: Float, radius: Float = .3f) = hypot(u - .5f, v - .5f) < radius
    /** Slight noise as a JPEG leaves it, the same on every run. */
    private fun noisy(u: Float, v: Float, base: Int): Int {
        val n = ((u * 7919f + v * 104729f) * 1000f).toInt().mod(9) - 4
        return (0xff shl 24) or ((base - 4 + n).coerceIn(0, 255) * 0x010101)
    }
    /** [rgb] with noise of up to 4 × [amount] levels on each channel, as a photo of a logo has it, the same on every run. */
    private fun withNoise(u: Float, v: Float, rgb: Int, amount: Int): Int {
        val n = (((u * 400).toInt() * 7919 + (v * 400).toInt() * 104729) % 9 - 4) * amount
        fun ch(shift: Int) = (((rgb shr shift) and 255) + n).coerceIn(0, 255)
        return (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * [pixels] as a baseline JPEG of [quality] (1..100) leaves them: YCbCr, colour at half size, each 8 × 8 block
     * of the standard tables' cosines rounded, colour back to full size as libjpeg does it. Edges ring as in a file.
     */
    private fun jpeg(pixels: PaintPixels, quality: Int): PaintPixels {
        val w = pixels.width
        val h = pixels.height
        val luma = intArrayOf(16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56,
            14, 17, 22, 29, 51, 87, 80, 62, 18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
            49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99)
        val chroma = IntArray(64) { if (it < 32) intArrayOf(17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99,
            24, 26, 56, 99, 99, 99, 99, 99, 47, 66, 99, 99, 99, 99, 99, 99)[it] else 99 }
        val scale = if (quality < 50) 5000.0 / quality else 200.0 - 2 * quality
        fun table(base: IntArray) = DoubleArray(64) { ((base[it] * scale + 50) / 100).toInt().coerceIn(1, 255).toDouble() }
        val cosine = Array(8) { u -> DoubleArray(8) { x -> (if (u == 0) sqrt(1.0 / 8) else sqrt(2.0 / 8)) * cos((2 * x + 1) * u * PI / 16) } }
        fun blocks(a: Array<DoubleArray>, q: DoubleArray): Array<DoubleArray> {
            val rows = a.size
            val cols = a[0].size
            val out = Array(rows) { DoubleArray(cols) }
            val block = Array(8) { DoubleArray(8) }
            val t = Array(8) { DoubleArray(8) }
            for (by in 0 until rows step 8) for (bx in 0 until cols step 8) {
                for (y in 0 until 8) for (x in 0 until 8) block[y][x] = a[min(rows - 1, by + y)][min(cols - 1, bx + x)] - 128
                for (u in 0 until 8) for (x in 0 until 8) t[u][x] = (0 until 8).sumOf { cosine[u][it] * block[it][x] }
                for (u in 0 until 8) for (v in 0 until 8) block[u][v] = (0 until 8).sumOf { t[u][it] * cosine[v][it] }
                for (u in 0 until 8) for (v in 0 until 8) block[u][v] = round(block[u][v] / q[u * 8 + v]) * q[u * 8 + v]
                for (y in 0 until 8) for (v in 0 until 8) t[y][v] = (0 until 8).sumOf { cosine[it][y] * block[it][v] }
                for (y in 0 until 8) for (x in 0 until 8) {
                    if (by + y < rows && bx + x < cols) out[by + y][bx + x] = (0 until 8).sumOf { t[y][it] * cosine[it][x] } + 128
                }
            }
            return out
        }
        fun channel(f: (Int, Int, Int) -> Double) = Array(h) { y -> DoubleArray(w) { x ->
            val c = pixels.argb[y * w + x]
            f((c shr 16) and 255, (c shr 8) and 255, c and 255)
        } }
        val luminance = blocks(channel { r, g, b -> .299 * r + .587 * g + .114 * b }, table(luma))
        fun half(full: Array<DoubleArray>): Array<DoubleArray> {
            val small = Array((h + 1) / 2) { y -> DoubleArray((w + 1) / 2) { x ->
                fun at(dy: Int, dx: Int) = full[min(h - 1, 2 * y + dy)][min(w - 1, 2 * x + dx)]
                (at(0, 0) + at(1, 0) + at(0, 1) + at(1, 1)) / 4
            } }
            val c = blocks(small, table(chroma))
            fun at(y: Int, x: Int) = c[y.coerceIn(0, c.size - 1)][x.coerceIn(0, c[0].size - 1)]
            // libjpeg's upsampling: each pixel 9 : 3 : 3 : 1 from the four colour samples around it
            return Array(h) { y -> DoubleArray(w) { x ->
                val sy = y / 2
                val sx = x / 2
                val ny = if (y % 2 == 0) -1 else 1
                val nx = if (x % 2 == 0) -1 else 1
                (9 * at(sy, sx) + 3 * at(sy + ny, sx) + 3 * at(sy, sx + nx) + at(sy + ny, sx + nx)) / 16
            } }
        }
        val chromaBlue = half(channel { r, g, b -> 128 - .168736 * r - .331264 * g + .5 * b })
        val chromaRed = half(channel { r, g, b -> 128 + .5 * r - .418688 * g - .081312 * b })
        return PaintPixels(w, h, IntArray(w * h) { o ->
            val y = luminance[o / w][o % w]
            val cb = chromaBlue[o / w][o % w] - 128
            val cr = chromaRed[o / w][o % w] - 128
            fun ch(v: Double) = round(v).coerceIn(0.0, 255.0).toInt()
            (0xff shl 24) or (ch(y + 1.402 * cr) shl 16) or (ch(y - .344136 * cb - .714136 * cr) shl 8) or ch(y + 1.772 * cb)
        })
    }

    private val red = 0xffff0000.toInt()
    private val green = 0xff00ff00.toInt()
    private val black = 0xff000000.toInt()
    private val white = 0xffffffff.toInt()

    @Test fun `a filled circle has neither holes nor specks`() {
        for (board in listOf(kilterLike(), moonGrid())) {
            val colors = mapDetected(board, picture { u, v -> if (disc(u, v)) red else noisy(u, v, 255) })
            val centre = board.at(.5f, .5f)
            val radius = .3f * board.side
            board.points.keys.forEach { id ->
                val d = board.distance(id, centre)
                if (d < radius - .45f * board.spacing) assertEquals("${board.name}: hole at $id", 0xe0, colors[id])
                if (d > radius + .45f * board.spacing) assertNull("${board.name}: speck at $id", colors[id])
            }
            assertEquals(board.name, setOf(0xe0), colors.values.toSet())
        }
    }

    @Test fun `a ring thinner than the hold spacing stays closed`() {
        for (board in listOf(kilterLike(), moonGrid())) {
            val thickness = .8f * board.spacing / board.side
            val colors = mapDetected(board, picture { u, v ->
                if (abs(hypot(u - .5f, v - .5f) - .3f) < thickness / 2) green else black
            })
            val lit = colors.keys
            assertTrue(board.name, lit.isNotEmpty())
            assertEquals("${board.name}: the ring is one piece", lit, board.reachable(lit.first(), lit, 1.45f))
            // From the middle, the dark holds inside never reach the outside.
            val centre = board.at(.5f, .5f)
            val dark = board.points.keys - lit
            val middle = dark.minBy { board.distance(it, centre) }
            val inside = board.reachable(middle, dark, 1.05f)
            assertTrue("${board.name}: the ring leaks", inside.all { board.distance(it, centre) < .3f * board.side })
            assertEquals(board.name, setOf(0x1c), colors.values.toSet())
        }
    }

    @Test fun `a thin line stays connected`() {
        for (board in listOf(kilterLike(), moonGrid())) {
            val from = Offset(.1f, .85f)
            val to = Offset(.9f, .2f)
            val length = (to - from).getDistance()
            fun fromLine(u: Float, v: Float): Float {
                val t = (((u - from.x) * (to.x - from.x) + (v - from.y) * (to.y - from.y)) / (length * length)).coerceIn(0f, 1f)
                return hypot(u - from.x - t * (to.x - from.x), v - from.y - t * (to.y - from.y))
            }
            val thickness = .5f * board.spacing / board.side
            val colors = mapDetected(board, picture { u, v -> if (fromLine(u, v) < thickness / 2) white else black })
            val lit = colors.keys
            assertTrue(board.name, lit.isNotEmpty())
            assertEquals("${board.name}: the line is one piece", lit, board.reachable(lit.first(), lit, 1.45f))
            listOf(from, to).forEach { end ->
                assertTrue("${board.name}: the line reaches its end", lit.any { board.distance(it, board.at(end.x, end.y)) < 1.5f * board.spacing })
            }
            // It stays a line: no hold far from it, and no more than two rows of holds along it.
            board.points.forEach { (id, point) ->
                val u = (point.x - board.viewport.width / 2) / board.side + .5f
                val v = (point.y - board.viewport.height / 2) / board.side + .5f
                if (id in lit) assertTrue("${board.name}: stray hold $id", fromLine(u, v) * board.side < board.spacing)
            }
            assertTrue("${board.name}: ${lit.size} holds", lit.size <= 2.6f * length * board.side / board.spacing)
            assertEquals(board.name, setOf(0xff), colors.values.toSet())
        }
    }

    @Test fun `the background stays dark whether black, white, uneven or transparent`() {
        val board = kilterLike()
        val centre = board.at(.5f, .5f)
        val backgrounds: Map<String, (Float, Float) -> Int> = mapOf(
            "black" to { _: Float, _: Float -> black },
            "white" to { _: Float, _: Float -> white },
            // a soft gradient with noise: light at the top, 15 levels darker at the bottom
            "uneven" to { u: Float, v: Float -> noisy(u, v, 250 - (15 * v).toInt()) },
            "transparent" to { _: Float, _: Float -> 0 },
        )
        backgrounds.forEach { (name, background) ->
            val pixels = picture { u, v -> if (disc(u, v, .25f)) green else background(u, v) }
            val detected = PaintImageMapping.background(pixels)
            assertTrue(name, detected.clear)
            assertEquals(name, name == "transparent", detected.transparent)
            val colors = mapDetected(board, pixels)
            assertTrue(name, colors.size > 50)
            assertTrue(name, colors.keys.all { board.distance(it, centre) < .25f * board.side + .5f * board.spacing })
            assertEquals(name, setOf(0x1c), colors.values.toSet())
        }
        // Switched off, a white background lights white ...
        val corner = board.points.keys.minBy { board.distance(it, board.at(.08f, .08f)) }
        val onWhite = map(board, picture { u, v -> if (disc(u, v, .25f)) green else white })
        assertEquals(0xff, onWhite[corner])
        assertEquals(0x1c, onWhite[board.points.keys.minBy { board.distance(it, centre) }])
        // ... and black stays off all the same: an LED cannot show it.
        val onBlack = map(board, picture { u, v -> if (disc(u, v, .25f)) green else black })
        assertNull(onBlack[corner])
        assertEquals(setOf(0x1c), onBlack.values.toSet())
    }

    @Test fun `a chosen background colour is the one that stays dark`() {
        val board = kilterLike()
        val blue = 0xff0000ff.toInt()
        // white ground, a blue block on the left, a red disc in the middle
        val pixels = picture { u, v -> if (disc(u, v, .2f)) red else if (u in 0.08f..0.25f && v in 0.3f..0.7f) blue else white }
        fun within(u: ClosedFloatingPointRange<Float>, v: ClosedFloatingPointRange<Float>) = board.points.filterValues {
            it.x in board.at(u.start, .5f).x..board.at(u.endInclusive, .5f).x && it.y in board.at(.5f, v.start).y..board.at(.5f, v.endInclusive).y
        }.keys
        val block = within(0.11f..0.22f, 0.33f..0.67f)
        val ground = within(0.8f..0.95f, 0.3f..0.7f)
        assertTrue(block.size >= 6 && ground.size >= 6)
        // By itself the white border decides: the ground is dark, the block is lit.
        val automatic = mapDetected(board, pixels)
        assertTrue(block.all { automatic[it] == 0x03 })
        assertTrue(ground.none { it in automatic })
        // Blue chosen: the block is dark, the ground is lit.
        val chosen = map(board, pixels, background = 0x0000ff)
        assertTrue(block.none { it in chosen })
        assertTrue(ground.all { chosen[it] == 0xff })
        assertEquals(setOf(0xe0, 0xff), chosen.values.toSet())
    }

    @Test fun `black is the picture when nothing else is`() {
        val board = kilterLike()
        val centre = board.at(.5f, .5f)
        val inside = board.points.keys.filter { board.distance(it, centre) < .3f * board.side - .5f * board.spacing }
        val outside = board.points.keys.filter { board.distance(it, centre) > .3f * board.side + .5f * board.spacing &&
            board.distance(it, centre) < .45f * board.side }
        // A black shape on a background that is left off lights white.
        val onWhite = picture { u, v -> if (disc(u, v)) black else white }
        val shape = mapDetected(board, onWhite)
        assertTrue(inside.all { shape[it] == 0xff })
        assertTrue(outside.none { it in shape })
        val onNothing = mapDetected(board, picture { u, v -> if (disc(u, v)) black else 0 })
        assertEquals(shape.keys, onNothing.keys)
        // With the white background lit, the black shape is the dark part.
        val whole = map(board, onWhite)
        assertTrue(inside.none { it in whole })
        assertTrue(outside.all { whole[it] == 0xff })
    }

    @Test fun `a small shift changes only a few holds`() {
        val board = kilterLike()
        val pixels = picture { u, v -> if (disc(u, v)) red else black }
        val placed = mapDetected(board, pixels)
        val shifted = mapDetected(board, pixels, transform = PaintImageTransform(center = Offset(.5f + .25f * board.spacing / board.viewport.width, .5f)))
        val changed = (placed.keys + shifted.keys).count { placed[it] != shifted[it] }
        assertTrue("$changed of ${placed.size} holds changed", changed <= .12f * placed.size)
    }

    @Test fun `board colours map to themselves`() {
        val board = moonGrid()
        fun flat(rgb: Int) = PaintPixels(8, 8, IntArray(64) { 0xff000000.toInt() or rgb })
        for (brand in listOf(BoardBrand.MOONBOARD, BoardBrand.QUANTUM)) PaintPalette.forBoard(brand).forEach { color ->
            assertEquals("$brand $color", setOf(color), map(board, flat(PaintPalette.rgb(color)), brand).values.toSet())
        }
        // An Aurora board shows every colour that has one channel fully on exactly ...
        val bright = PaintPalette.forBoard(BoardBrand.KILTER).filter { (it shr 5) == 7 || ((it shr 2) and 7) == 7 || (it and 3) == 3 }
        assertEquals(109, bright.size)
        bright.forEach { color ->
            assertEquals("kilter $color", setOf(color), map(board, flat(PaintPalette.rgb(color))).values.toSet())
        }
        // ... raises a dim colour to full brightness, shows grey as white and leaves black off.
        assertEquals(setOf(0x03), map(board, flat(0x000080)).values.toSet())
        assertEquals(setOf(0xff), map(board, flat(0x808080)).values.toSet())
        assertTrue(map(board, flat(0x101010)).isEmpty())
        // A few fixed colours follow the hue: light blue is blue on a MoonBoard, orange is yellow on a Quantum.
        assertEquals(setOf(0x03), map(board, flat(0x42bede), BoardBrand.MOONBOARD).values.toSet())
        assertEquals(setOf(0xfc), map(board, flat(0xff6d00), BoardBrand.QUANTUM).values.toSet())
    }

    @Test fun `two colours meet without a third`() {
        val colors = map(kilterLike(), picture { _, v -> if (v < .5f) 0xff0000ff.toInt() else 0xffffff00.toInt() })
        assertEquals(setOf(0x03, 0xfc), colors.values.toSet())
    }

    @Test fun `the border tells what the background is`() {
        val onBlack = PaintImageMapping.background(picture { u, v -> if (disc(u, v)) red else black })
        assertEquals(PaintBackground(color = 0x000000, clear = true), onBlack)
        val onNoisyWhite = PaintImageMapping.background(picture { u, v -> if (disc(u, v)) red else noisy(u, v, 255) })
        assertTrue(onNoisyWhite.clear)
        assertFalse(onNoisyWhite.transparent)
        assertTrue((0..2).all { ((onNoisyWhite.color!! shr (it * 8)) and 255) >= 245 })
        assertEquals(PaintBackground(transparent = true, clear = true), PaintImageMapping.background(picture { u, v -> if (disc(u, v)) red else 0 }))
        // A photo that fills the frame: sky above, ground below, a colour run in between.
        val photo = PaintImageMapping.background(picture { u, v ->
            (0xff shl 24) or ((40 + 180 * v).toInt() shl 16) or ((90 + 100 * u).toInt() shl 8) or (220 - 150 * v).toInt()
        })
        assertFalse(photo.clear)
        assertFalse(photo.transparent)
    }

    @Test fun `a pale shape keeps its holds beside black or a strong colour`() {
        val cyan = 0xff00dce6.toInt()
        val navy = 0xff000080.toInt()
        for (board in listOf(kilterLike(), moonGrid())) {
            // Black that stays off, somewhere else in the picture, changes nothing.
            val shape = { u: Float, v: Float -> disc(u, v, .2f) || (abs(u - v) < .02f && u > .08f && u < .3f) }
            val alone = mapDetected(board, picture { u, v -> if (shape(u, v)) cyan else white })
            val beside = mapDetected(board, picture { u, v ->
                if (shape(u, v)) cyan else if (u > .15f && u < .85f && v > .86f && v < .96f) black else white
            })
            assertTrue(board.name, alone.isNotEmpty())
            assertEquals(board.name, alone, beside)
            // A pale rim around a strong disc is lit like a disc of the pale colour alone.
            for (pale in listOf(0xffffdd00.toInt(), 0xffc8c8c8.toInt())) {
                val name = "${board.name} ${Integer.toHexString(pale)}"
                val plain = mapDetected(board, picture { u, v -> if (disc(u, v, .33f)) pale else white })
                val rimmed = mapDetected(board, picture { u, v ->
                    if (disc(u, v, .2f)) navy else if (disc(u, v, .33f)) pale else white
                })
                assertTrue(name, plain.keys.containsAll(rimmed.keys))
                assertTrue("$name: ${plain.size} alone, ${rimmed.size} around the disc", plain.size - rimmed.size <= 1)
                val rim = rimmed.filterKeys { board.distance(it, board.at(.5f, .5f)) > .26f * board.side }
                assertTrue(name, rim.isNotEmpty() && rim.values.all { it in plain.values })
            }
        }
    }

    @Test fun `a placement that is not a number lights nothing`() {
        val broken = PaintAffine(Float.NaN, 0f, 0f, 0f, Float.NaN, 0f)
        val colors = PaintImageMapping.map(picture(8) { _, _ -> red }, broken, moonGrid().points,
            PaintPalette.forBoard(BoardBrand.KILTER))
        assertTrue(colors.isEmpty())
    }

    @Test fun `a thin line becomes one chain of holds`() {
        for (board in listOf(kilterLike(), moonGrid())) {
            val from = Offset(.1f, .85f)
            val to = Offset(.9f, .2f)
            val length = (to - from).getDistance()
            fun fromLine(u: Float, v: Float): Float {
                val t = (((u - from.x) * (to.x - from.x) + (v - from.y) * (to.y - from.y)) / (length * length)).coerceIn(0f, 1f)
                return hypot(u - from.x - t * (to.x - from.x), v - from.y - t * (to.y - from.y))
            }
            val thickness = .5f * board.spacing / board.side
            val lit = mapDetected(board, picture { u, v -> if (fromLine(u, v) < thickness / 2) white else black }).keys
            // One hold after the other along the line, and hardly one beside it.
            assertTrue("${board.name}: ${lit.size} holds", lit.size <= 1.3f * length * board.side / board.spacing)
            assertEquals("${board.name}: one piece", lit, board.reachable(lit.first(), lit, 1.45f))
        }
    }

    @Test fun `lines that run close together stay apart`() {
        for (board in listOf(kilterLike(), moonGrid())) {
            val spacing = board.spacing / board.side
            fun v(id: Int) = (board.points.getValue(id).y - board.viewport.height / 2) / board.side + .5f
            fun u(id: Int) = (board.points.getValue(id).x - board.viewport.width / 2) / board.side + .5f
            for (gap in listOf(2f, 3f)) {
                val upper = .5f - gap * spacing / 2
                val lower = .5f + gap * spacing / 2
                val lit = mapDetected(board, picture { u, v ->
                    if ((abs(v - upper) < spacing / 4 || abs(v - lower) < spacing / 4) && u > .15f && u < .85f) white else black
                }).keys
                val name = "${board.name}, $gap spacings apart"
                val above = lit.filter { v(it) < .5f }.toSet()
                assertTrue("$name: ${above.size} above, ${lit.size - above.size} below", above.size >= 5 && lit.size - above.size >= 5)
                // Nothing is lit in the middle between them, and no chain of holds leads from one line to the other.
                assertTrue(name, lit.none { abs(v(it) - .5f) < (gap / 2 - .75f) * spacing && u(it) > .25f && u(it) < .75f })
                assertTrue(name, board.reachable(above.first(), lit, 1.05f).all { it in above })
            }
        }
    }

    @Test fun `a glow does not count as the shape`() {
        for (board in listOf(kilterLike(), moonGrid())) {
            // a bright ring on a dark ground, with a glow that fades out to both sides
            val pixels = picture { u, v ->
                val off = abs(hypot(u - .5f, v - .5f) - .3f)
                val glow = if (off < .012f) 1f else .5f * exp(-(off - .012f) / .035f)
                (0xff shl 24) or (15 shl 16) or ((15 + (225 * glow).toInt()) shl 8) or (15 + (240 * glow).toInt())
            }
            assertTrue(PaintImageMapping.background(pixels).clear)
            val lit = mapDetected(board, pixels).keys
            val centre = board.at(.5f, .5f)
            assertTrue("${board.name}: ${lit.size} holds", lit.size >= 12)
            assertTrue(board.name, lit.all { abs(board.distance(it, centre) - .3f * board.side) < .8f * board.spacing })
            assertEquals("${board.name}: the ring is one piece", lit, board.reachable(lit.first(), lit, 1.45f))
        }
    }

    @Test fun `a small patch of one clear colour stays and a sprinkle of another does not`() {
        val board = kilterLike()
        val yellow = 0xffffdd00.toInt()
        fun u(id: Int) = (board.points.getValue(id).x - board.viewport.width / 2) / board.side + .5f
        // Two white dots on a yellow disc, each about a hold in size: details of the picture.
        val dots = mapDetected(board, picture { u, v ->
            if (hypot(u - .42f, v - .42f) < .035f || hypot(u - .58f, v - .42f) < .035f) white
            else if (disc(u, v)) yellow else black
        }).filterValues { it == 0xff }.keys
        assertTrue("$dots", dots.size in 2..4 && dots.any { u(it) < .5f } && dots.any { u(it) > .5f })
        // White hairlines across the disc, far thinner than a hold: the disc stays one colour.
        val lines = mapDetected(board, picture { u, v ->
            if (!disc(u, v)) black else if ((u * 400).toInt() % 9 == 0) white else yellow
        })
        assertEquals(setOf(0xf8), lines.values.toSet())
    }

    @Test fun `a new photo is fitted to the holds and a picture that mirrors lights a mirrored pattern`() {
        for (board in listOf(kilterLike(), moonGrid())) {
            // a ring off the middle of a photo, with a wide white margin
            val pixels = picture { u, v -> if (abs(hypot(u - .45f, v - .55f) - .2f) < .035f) 0xff0096e6.toInt() else white }
            val detected = PaintImageMapping.background(pixels)
            val prepared = PaintImageMapping.picture(pixels, detected.color.takeIf { detected.clear }, detected.transparent)
            assertTrue(board.name, prepared.removed && prepared.mirrorX && prepared.mirrorY)
            val image = Size(pixels.width.toFloat(), pixels.height.toFloat())
            val placed = PaintImageMapping.place(prepared, image, board.viewport, board.points)
            assertTrue("${board.name}: zoom ${placed.zoom}", placed.zoom > 1.5f)
            val lit = PaintImageMapping.map(prepared, placed.pixelMap(image, board.viewport, pixels), board.points,
                PaintPalette.forBoard(BoardBrand.KILTER)).keys
            // The ring reaches across the board in one direction at least.
            val xs = board.points.values.map { it.x }
            val ys = board.points.values.map { it.y }
            val litX = lit.map { board.points.getValue(it).x }
            val litY = lit.map { board.points.getValue(it).y }
            val reach = max((litX.max() - litX.min()) / (xs.max() - xs.min()), (litY.max() - litY.min()) / (ys.max() - ys.min()))
            assertTrue("${board.name}: reaches $reach of the board", reach > .85f)
            // Mirrored about the ring's centre, left to right and top to bottom.
            val scale = placed.scale(image, board.viewport)
            val centre = Offset(placed.center.x * board.viewport.width + (.45f - .5f) * pixels.width * scale,
                placed.center.y * board.viewport.height + (.55f - .5f) * pixels.height * scale)
            fun litAt(x: Float, y: Float) = lit.any { board.distance(it, Offset(x, y)) < .12f * board.spacing }
            val alone = lit.count {
                val point = board.points.getValue(it)
                !litAt(2 * centre.x - point.x, point.y) || !litAt(point.x, 2 * centre.y - point.y)
            }
            assertTrue("${board.name}: $alone of ${lit.size} holds without their mirror image", alone <= 2)
            // As the photo comes, the ring is small and falls on the holds as it happens to.
            assertTrue(board.name, lit.size > 2 * mapDetected(board, pixels).size)
        }
    }

    @Test fun `a placed photo is settled close to where it lies and keeps its rotation`() {
        val board = moonGrid()
        val pixels = picture { u, v -> if (abs(hypot(u - .5f, v - .5f) - .3f) < .05f) 0xff0096e6.toInt() else white }
        val prepared = PaintImageMapping.picture(pixels, 0xffffff)
        val image = Size(pixels.width.toFloat(), pixels.height.toFloat())
        val byHand = PaintImageTransform(center = Offset(.52f, .47f), zoom = 1.3f, degrees = 20f)
        val settled = PaintImageMapping.place(prepared, image, board.viewport, board.points, byHand)
        assertEquals(20f, settled.degrees)
        assertTrue("zoom ${settled.zoom}", settled.zoom <= 1.3f + 1e-4f && settled.zoom >= .9f * 1.3f - 1e-4f)
        // The ring's centre is the photo's centre: it moves by one hold spacing at most, in each direction.
        assertTrue(abs(settled.center.x - byHand.center.x) * board.viewport.width <= board.spacing + 2f)
        assertTrue(abs(settled.center.y - byHand.center.y) * board.viewport.height <= board.spacing + 2f)
    }

    @Test fun `settling a photo placed by hand gives a mirroring picture its mirrored pattern`() {
        for (board in listOf(kilterLike(), moonGrid())) {
            val pixels = picture { u, v -> if (abs(hypot(u - .5f, v - .5f) - .3f) < .05f) 0xff0096e6.toInt() else white }
            val prepared = PaintImageMapping.picture(pixels, 0xffffff)
            val image = Size(pixels.width.toFloat(), pixels.height.toFloat())
            // a third of a hold spacing off the middle: the ring falls on the holds as it happens to
            val byHand = PaintImageTransform(center = Offset(.5f + .31f * board.spacing / board.viewport.width,
                .5f + .17f * board.spacing / board.viewport.height), zoom = 1.3f)
            fun alone(transform: PaintImageTransform): Pair<Int, Int> {
                val lit = PaintImageMapping.map(prepared, transform.pixelMap(image, board.viewport, pixels), board.points,
                    PaintPalette.forBoard(BoardBrand.KILTER)).keys
                val centre = Offset(transform.center.x * board.viewport.width, transform.center.y * board.viewport.height)
                fun litAt(x: Float, y: Float) = lit.any { board.distance(it, Offset(x, y)) < .12f * board.spacing }
                return lit.count {
                    val point = board.points.getValue(it)
                    !litAt(2 * centre.x - point.x, point.y) || !litAt(point.x, 2 * centre.y - point.y)
                } to lit.size
            }
            val (before, lit) = alone(byHand)
            assertTrue("${board.name}: $before of $lit holds were without their mirror image", before > lit / 2)
            val settled = PaintImageMapping.place(prepared, image, board.viewport, board.points, byHand)
            val (after, _) = alone(settled)
            assertTrue("${board.name}: $after holds are without their mirror image", after <= 2)
        }
    }

    @Test fun `a new photo asks for no more holds of a color than the board lights`() {
        val board = moonGrid()
        val pixels = picture { u, v -> if (disc(u, v, .4f)) red else white }
        val prepared = PaintImageMapping.picture(pixels, 0xffffff)
        val image = Size(pixels.width.toFloat(), pixels.height.toFloat())
        val palette = PaintPalette.forBoard(BoardBrand.QUANTUM)
        fun lit(transform: PaintImageTransform) =
            PaintImageMapping.map(prepared, transform.pixelMap(image, board.viewport, pixels), board.points, palette).size
        val free = lit(PaintImageMapping.place(prepared, image, board.viewport, board.points, palette = palette))
        assertTrue("$free holds without a limit", free > 60)
        val limited = lit(PaintImageMapping.place(prepared, image, board.viewport, board.points, palette = palette, most = 40))
        assertTrue("$limited holds with at most 40 of a color", limited in 20..40)
        // A photo that asks for no more than the board lights, fitted and settled, keeps its size.
        val xs = board.points.values.map { it.x }
        val ys = board.points.values.map { it.y }
        val base = min(board.viewport.width / image.width, board.viewport.height / image.height)
        val zoom = min((xs.max() - xs.min() + board.spacing) / (prepared.right - prepared.left),
            (ys.max() - ys.min() + board.spacing) / (prepared.bottom - prepared.top)) / base
        val fitted = PaintImageTransform(Offset(
            ((xs.min() + xs.max()) / 2 - ((prepared.left + prepared.right) / 2 - image.width / 2) * base * zoom) / board.viewport.width,
            ((ys.min() + ys.max()) / 2 - ((prepared.top + prepared.bottom) / 2 - image.height / 2) * base * zoom) / board.viewport.height,
        ), zoom, 0f)
        val settled = PaintImageMapping.place(prepared, image, board.viewport, board.points, palette = palette)
        val asks = maxOf(lit(fitted), lit(settled))
        assertEquals(settled, PaintImageMapping.place(prepared, image, board.viewport, board.points, palette = palette, most = asks))
        // A photo that was placed by hand is never made smaller for it.
        val byHand = PaintImageTransform(zoom = 1.2f)
        assertEquals(1.2f, PaintImageMapping.place(prepared, image, board.viewport, board.points, byHand, palette, 40).zoom, .13f)
    }

    @Test fun `a flat darker tone on a dark ground keeps its holds`() {
        for (board in listOf(kilterLike(), moonGrid())) {
            fun u(id: Int) = (board.points.getValue(id).x - board.viewport.width / 2) / board.side + .5f
            // a white block and a blue one, the blue close to the navy ground
            val lit = mapDetected(board, picture { u, v ->
                if (v < .2f || v > .8f) 0xff0a1a3a.toInt()
                else if (u > .1f && u < .48f) white else if (u > .52f && u < .9f) 0xff1a3a7a.toInt() else 0xff0a1a3a.toInt()
            })
            val left = lit.keys.count { u(it) < .5f }
            val right = lit.keys.count { u(it) > .5f }
            assertTrue("${board.name}: $left white, $right blue", left >= 10 && right >= left - 2)
            assertEquals(board.name, 2, lit.values.toSet().size)
        }
    }

    @Test fun `a flat tone close to the ground keeps its holds beside a logo of any color, also with noise`() {
        for (board in listOf(kilterLike(), moonGrid())) {
            fun u(id: Int) = (board.points.getValue(id).x - board.viewport.width / 2) / board.side + .5f
            // a disc as the logo, beside it a panel of a darker tone, on anthracite
            fun panel(logo: Long, tone: Long) = mapDetected(board, picture { u, v ->
                if (hypot(u - .3f, v - .5f) < .15f) logo.toInt()
                else if (u > .55f && u < .9f && v > .25f && v < .75f) tone.toInt() else 0xff202020.toInt()
            }).keys.filter { u(it) > .5f }.toSet()
            val full = panel(0xffc03030, 0xffa02020)
            assertTrue("${board.name}: ${full.size} holds", full.size >= 20)
            for ((logo, tone) in listOf(0xffc03030 to 0xff501414, 0xffc03030 to 0xff581212, 0xffc03030 to 0xff5c1111, 0xffffffff to 0xff5c1111)) {
                assertEquals("${board.name}: ${tone.toString(16)} beside ${logo.toString(16)}", full, panel(logo, tone))
            }
            // white, red and dark red blocks on anthracite, with noise as a photo of a logo has it
            fun v(id: Int) = (board.points.getValue(id).y - board.viewport.height / 2) / board.side + .5f
            fun blocks(noise: Int): List<Set<Int>> {
                val lit = mapDetected(board, picture { u, v ->
                    val k = ((u - .08f) / .28f).toInt()
                    val inside = v > .2f && v < .8f && k in 0..2 && u > .1f + k * .28f && u < .34f + k * .28f
                    withNoise(u, v, if (!inside) 0x202020 else intArrayOf(0xffffff, 0xff0000, 0x601010)[k], noise)
                }).keys
                return (0..2).map { k -> lit.filter { u(it) > .08f + k * .28f && u(it) < .36f + k * .28f }.toSet() }
            }
            val clean = blocks(0)
            val inside = (0..2).map { k -> board.points.keys.filter { u(it) > .1f + k * .28f && u(it) < .34f + k * .28f && v(it) > .2f && v(it) < .8f } }
            assertTrue("${board.name}: ${clean.map { it.size }} holds of ${inside.map { it.size }}", (0..2).all { clean[it].containsAll(inside[it]) })
            // the same holds, not only as many
            assertEquals(board.name, clean, blocks(3))
        }
    }

    @Test fun `a pale part beside a black picture lights, a light grey one does not`() {
        for (board in listOf(kilterLike(), moonGrid())) {
            fun u(id: Int) = (board.points.getValue(id).x - board.viewport.width / 2) / board.side + .5f
            fun v(id: Int) = (board.points.getValue(id).y - board.viewport.height / 2) / board.side + .5f
            fun right(pale: Int, noise: Int = 0) = mapDetected(board, picture { u, v ->
                if (u > .08f && u < .52f && v > .15f && v < .85f) black
                else if (u > .62f && u < .9f && v > .3f && v < .7f) withNoise(u, v, pale, noise) else white
            }).keys.filter { u(it) > .57f }.toSet()
            val inside = board.points.keys.filter { u(it) > .64f && u(it) < .88f && v(it) > .32f && v(it) < .68f }
            for (pale in listOf(0xffffdd00.toInt(), 0xffffb0c0.toInt(), 0xffa0a0a0.toInt())) {
                val lit = right(pale)
                assertTrue("${board.name}: ${Integer.toHexString(pale)}, ${lit.size} holds", lit.containsAll(inside) &&
                    lit.all { u(it) > .62f - .6f * board.spacing / board.side && v(it) > .3f - .6f * board.spacing / board.side && v(it) < .7f + .6f * board.spacing / board.side })
            }
            for (noise in 0..2) assertEquals("${board.name}: light grey, noise $noise", emptySet<Int>(), right(0xffe0e0e0.toInt(), noise))
            // right at the bar between the two the grey lights in full or not at all: noise does not scatter holds over it
            val atBar = right(0xffd0d0d0.toInt())
            assertTrue("${board.name}: ${atBar.size} holds", atBar.isEmpty() || atBar.containsAll(inside))
            for (noise in 1..2) assertEquals("${board.name}: grey at the bar, noise $noise", atBar, right(0xffd0d0d0.toInt(), noise))
        }
    }

    @Test fun `two faint tones at about the same distance from the ground are judged each on its own`() {
        for (board in listOf(kilterLike(), moonGrid())) {
            fun u(id: Int) = (board.points.getValue(id).x - board.viewport.width / 2) / board.side + .5f
            fun v(id: Int) = (board.points.getValue(id).y - board.viewport.height / 2) / board.side + .5f
            for (bottom in listOf(.68f, .75f, .9f)) {
                // on navy, a white disc, a faint navy panel and a wine-red element: the two are about as far from the ground
                val lit = mapDetected(board, picture { u, v ->
                    if (hypot(u - .25f, v - .3f) < .12f) white
                    else if (u > .55f && u < .9f && v > .1f && v < .45f) 0xff2b3954.toInt()
                    else if (u > .55f && u < .9f && v > .55f && v < bottom) 0xff4c0434.toInt()
                    else 0xff0a1a3a.toInt()
                }).keys
                val name = "${board.name}, element to $bottom"
                assertEquals("$name: panel", emptySet<Int>(), lit.filter { u(it) > .5f && v(it) < .5f }.toSet())
                val element = board.points.keys.filter { u(it) > .58f && u(it) < .87f && v(it) > .58f && v(it) < bottom - .03f }
                assertTrue("$name: element", element.isNotEmpty() && lit.containsAll(element))
            }
        }
    }

    @Test fun `shapes one hold spacing apart stay apart in a JPEG`() {
        val board = kilterLike()
        val spacing = board.spacing / board.side
        // four bars, red and white, one hold spacing apart on anthracite
        val bars = picture { u, v ->
            val x = u - .1f
            val k = (x / (.12f + spacing)).toInt()
            if (x >= 0f && k in 0..3 && v > .2f && v < .8f && x - k * (.12f + spacing) < .12f) {
                if (k % 2 == 0) 0xffff3030.toInt() else white
            } else 0xff202020.toInt()
        }
        val clean = mapDetected(board, bars).keys
        for (quality in listOf(95, 85, 70)) {
            // the ringing beside the edges does not fill the gaps between the bars
            assertEquals("quality $quality", clean, mapDetected(board, jpeg(bars, quality)).keys)
        }
    }

    @Test fun `a photo that fills the frame keeps its bands of color on a board with three colors`() {
        // muted bands, alike enough that a shape on a removed background would lose one of them
        val colors = map(moonGrid(), picture { _, v ->
            if (v < 1f / 3) 0xff96aabe.toInt() else if (v < 2f / 3) 0xff6e8c5a.toInt() else 0xff8c6e5a.toInt()
        }, BoardBrand.MOONBOARD)
        assertEquals(PaintPalette.forBoard(BoardBrand.MOONBOARD).toSet(), colors.values.toSet())
    }

    @Test fun `a dot lights the hold nearest to it, a short stroke the holds under it, wherever they fall`() {
        for (board in listOf(kilterLike(), moonGrid())) {
            val spacing = board.spacing / board.side
            fun nearest(u: Float, v: Float) = board.points.keys.minBy { board.distance(it, board.at(u, v)) }
            var strokes = 0
            for (step in 0 until 12) {
                val cu = .5f + (step % 4 - 1.5f) * .3f * spacing
                val cv = .5f + (step / 4 - 1f) * .4f * spacing
                val name = "${board.name} at $cu, $cv"
                // smaller than a hold: exactly the hold nearest to it, not a neighbour and not none
                val small = mapDetected(board, picture { u, v -> if (hypot(u - cu, v - cv) < .35f * spacing) white else black })
                assertEquals("$name: small dot", setOf(nearest(cu, cv)), small.keys)
                // a speck, less than half a hold spacing wide: nothing
                val speck = mapDetected(board, picture { u, v -> if (hypot(u - cu, v - cv) < .2f * spacing) white else black })
                assertEquals("$name: speck", emptySet<Int>(), speck.keys)
                // about a hold in size
                val dot = mapDetected(board, picture { u, v -> if (hypot(u - cu, v - cv) < spacing / 2) white else black })
                assertTrue("$name: dot, ${dot.keys}", dot.size in 1..2 && nearest(cu, cv) in dot)
                // a thin stroke two and a half hold spacings long is not taken for a dot, and lights only holds under it
                val stroke = mapDetected(board, picture { u, v ->
                    if (abs(v - cv) < .15f * spacing && abs(u - cu) < 1.25f * spacing) white else black
                }).keys
                assertTrue("$name: stroke, ${stroke.size} holds", stroke.size in 1..3)
                val start = board.at(cu - 1.25f * spacing, cv)
                val end = board.at(cu + 1.25f * spacing, cv)
                fun along(p: Offset) = p.x >= start.x - .3f * board.spacing && p.x <= end.x + .3f * board.spacing
                // the row of holds nearest to the stroke, not the one beside it
                val row = board.points.values.filter(::along).minOf { abs(it.y - start.y) }
                assertTrue("$name: stroke $stroke", stroke.all {
                    val p = board.points.getValue(it)
                    abs(p.y - start.y) <= row + .1f * board.spacing && along(p)
                })
                if (stroke.size >= 2) strokes++
            }
            assertTrue("${board.name}: $strokes of 12 strokes with two holds or more", strokes >= 9)
        }
    }

    @Test fun `a dot on a hold lights that hold, also beside other dots`() {
        for (board in listOf(kilterLike(), moonGrid())) {
            fun dots(vararg holds: Int, width: Float = .7f) = mapDetected(board, picture(side = 400) { u, v ->
                val at = board.at(u, v)
                if (holds.any { board.distance(it, at) < width / 2 * board.spacing }) white else black
            }).keys
            val middle = board.points.keys.minBy { board.distance(it, board.at(.5f, .5f)) }
            // exactly on a hold and a little beside it: that hold, not a neighbour
            for ((du, dv) in listOf(0f to 0f, .05f to 0f, 0f to .05f, -.05f to 0f, 0f to -.05f)) {
                val p = board.points.getValue(middle)
                val shifted = mapDetected(board, picture { u, v ->
                    val at = board.at(u, v)
                    if (hypot(at.x - p.x - du * board.spacing, at.y - p.y - dv * board.spacing) < .35f * board.spacing) white else black
                }).keys
                assertEquals("${board.name}: dot moved by $du, $dv", setOf(middle), shifted)
            }
            // two dots on neighbouring holds, also diagonal ones on a square grid
            for (next in board.points.keys.filter { board.linked(middle, it, 1.5f) }) {
                assertEquals("${board.name}: dots on $middle and $next", setOf(middle, next), dots(middle, next))
            }
        }
        // patterns of dots drawn on the holds of a MoonBoard grid
        val board = moonGrid()
        fun id(row: Int, col: Int) = row * 11 + col + 1
        val patterns = mapOf(
            "a diagonal" to (0 until 5).map { id(6 + it, 3 + it) },
            "a block of three by three" to (-1..1).flatMap { r -> (-1..1).map { c -> id(8 + r, 4 + c) } },
            "every second hold of a row" to (1 until 10 step 2).map { id(8, it) },
            "every second hold of a block" to listOf(-2, 0, 2).flatMap { r -> listOf(-2, 0, 2).map { c -> id(8 + r, 5 + c) } },
        )
        for ((name, holds) in patterns) {
            val lit = mapDetected(board, picture { u, v ->
                val at = board.at(u, v)
                if (holds.any { board.distance(it, at) < .35f * board.spacing }) white else black
            }).keys
            assertEquals(name, holds.toSet(), lit)
        }
    }

    @Test fun `a ring drawn around a hold lights that hold`() {
        for (board in listOf(kilterLike(), moonGrid())) {
            val middle = board.points.keys.minBy { board.distance(it, board.at(.5f, .5f)) }
            val p = board.points.getValue(middle)
            for (radius in listOf(.3f, .4f)) {
                val lit = mapDetected(board, picture { u, v ->
                    val at = board.at(u, v)
                    if (abs(hypot(at.x - p.x, at.y - p.y) - radius * board.spacing) < .08f * board.spacing) white else black
                }).keys
                assertEquals("${board.name}: ring of $radius spacings", setOf(middle), lit)
            }
        }
    }

    @Test fun `a line on the MoonBoard reaches the holds at its ends`() {
        val board = moonGrid()
        fun line(from: Int, to: Int): Set<Int> {
            val a = board.points.getValue(from)
            val b = board.points.getValue(to)
            return mapDetected(board, picture { u, v ->
                val p = board.at(u, v)
                val dx = b.x - a.x
                val dy = b.y - a.y
                val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
                if (hypot(p.x - a.x - t * dx, p.y - a.y - t * dy) < .15f * board.spacing) white else black
            }).keys
        }
        assertEquals((0 until 10).map { 45 + 12 * it }.toSet(), line(45, 153))
        assertEquals((101..109).toSet(), line(101, 109))
    }

    @Test fun `on black a glow is left out and a faint second tone of the ground stays dark`() {
        for (board in listOf(kilterLike(), moonGrid())) {
            val spacing = board.spacing / board.side
            fun u(id: Int) = (board.points.getValue(id).x - board.viewport.width / 2) / board.side + .5f
            fun v(id: Int) = (board.points.getValue(id).y - board.viewport.height / 2) / board.side + .5f
            // a white bar with a soft pink glow on pure black
            val bar = mapDetected(board, picture { u, v ->
                val beside = if (abs(v - .5f) > .05f) abs(v - .5f) - .05f else 0f
                val off = if (u > .2f && u < .8f) beside else hypot(minOf(abs(u - .2f), abs(u - .8f)), beside)
                if (off <= 0f) white else {
                    val glow = .6f * exp(-off / .05f)
                    (0xff shl 24) or ((255 * glow).toInt() shl 16) or ((60 * glow).toInt() shl 8) or (140 * glow).toInt()
                }
            }).keys
            assertTrue("${board.name}: ${bar.size} holds", bar.size >= 8)
            assertTrue(board.name, bar.all { abs(v(it) - .5f) < .05f + .6f * spacing && u(it) > .2f - .6f * spacing && u(it) < .8f + .6f * spacing })
            // a white disc on navy, and beside it a panel only a little lighter than the ground, also with noise
            for (noise in 0..2) {
                val panel = mapDetected(board, picture { u, v ->
                    if (hypot(u - .3f, v - .5f) < .15f) white
                    else withNoise(u, v, if (u > .55f && u < .9f && v > .25f && v < .75f) 0x2b3954 else 0x0a1a3a, noise)
                }).keys
                assertTrue("${board.name}: noise $noise, ${panel.size} holds", panel.size >= 6 && panel.none { u(it) > .5f })
            }
        }
    }

    @Test fun `a photo that fills the frame is fitted to the holds and left there`() {
        val board = kilterLike()
        val pixels = picture { u, v ->
            (0xff shl 24) or ((40 + 180 * v).toInt() shl 16) or ((90 + 100 * u).toInt() shl 8) or (220 - 150 * v).toInt()
        }
        val prepared = PaintImageMapping.picture(pixels)
        assertFalse(prepared.removed)
        val image = Size(pixels.width.toFloat(), pixels.height.toFloat())
        val placed = PaintImageMapping.place(prepared, image, board.viewport, board.points)
        val xs = board.points.values.map { it.x }
        val ys = board.points.values.map { it.y }
        assertEquals(0f, placed.degrees)
        assertEquals((xs.min() + xs.max()) / 2, placed.center.x * board.viewport.width, 1f)
        assertEquals((ys.min() + ys.max()) / 2, placed.center.y * board.viewport.height, 1f)
        // It fills the area of the holds in one direction and stays inside it in the other.
        val side = pixels.width * placed.scale(image, board.viewport)
        val width = xs.max() - xs.min() + board.spacing
        val height = ys.max() - ys.min() + board.spacing
        assertEquals(minOf(width, height), side, 2f)
    }

    @Test fun `placing a photo takes a moment at most`() {
        val board = kilterLike()
        val side = 1024
        val pixels = PaintPixels(side, side, IntArray(side * side) {
            val u = (it % side + .5f) / side
            val v = (it / side + .5f) / side
            if (disc(u, v, .18f)) red else if (abs(hypot(u - .5f, v - .5f) - .3f) < .02f) green else black
        })
        val prepared = PaintImageMapping.picture(pixels, 0x000000)
        val image = Size(side.toFloat(), side.toFloat())
        repeat(2) { PaintImageMapping.place(prepared, image, board.viewport, board.points) }
        val runs = (1..5).map {
            val start = System.nanoTime()
            PaintImageMapping.place(prepared, image, board.viewport, board.points)
            (System.nanoTime() - start) / 1_000_000.0
        }.sorted()
        val prepare = (1..5).map {
            val start = System.nanoTime()
            PaintImageMapping.picture(pixels, 0x000000)
            (System.nanoTime() - start) / 1_000_000.0
        }.sorted()
        println("PaintBox placement, ${side} px picture, ${board.points.size} holds: median ${"%.0f".format(runs[2])} ms, " +
            "slowest ${"%.0f".format(runs.last())} ms; preparing the picture: median ${"%.0f".format(prepare[2])} ms")
        // The printed times are the measurement; the bound is loose so that a busy build machine does not fail it.
        assertTrue("median ${runs[2]} ms", runs[2] < 5000.0)
    }

    @Test fun `a large picture on some 500 holds maps fast enough for the live preview`() {
        val board = kilterLike()
        val side = 2048
        val pixels = PaintPixels(side, side, IntArray(side * side) {
            val u = (it % side + .5f) / side
            val v = (it / side + .5f) / side
            if (disc(u, v)) red else if (abs(hypot(u - .5f, v - .5f) - .4f) < .02f) green else black
        })
        // As the editor does it: the picture is prepared once, then mapped for every placement.
        val detected = PaintImageMapping.background(pixels)
        val prepared = PaintImageMapping.picture(pixels, detected.color.takeIf { detected.clear }, detected.transparent)
        val toPixel = PaintImageTransform(degrees = 12f, zoom = 1.3f).pixelMap(Size(side.toFloat(), side.toFloat()), board.viewport, pixels)
        val palette = PaintPalette.forBoard(BoardBrand.KILTER)
        repeat(8) { PaintImageMapping.map(prepared, toPixel, board.points, palette) }
        val runs = (1..7).map {
            val start = System.nanoTime()
            PaintImageMapping.map(prepared, toPixel, board.points, palette)
            (System.nanoTime() - start) / 1_000_000.0
        }.sorted()
        println("PaintBox mapping, ${side} px picture, ${board.points.size} holds: median ${"%.1f".format(runs[3])} ms, " +
            "fastest ${"%.1f".format(runs.first())} ms, slowest ${"%.1f".format(runs.last())} ms per placement")
        // The printed time is the measurement. The bound is loose on purpose, so that a busy build machine
        // does not fail it; it catches a mapping whose cost grows out of proportion.
        assertTrue("median ${runs[3]} ms", runs[3] < 1000.0)
    }
}
