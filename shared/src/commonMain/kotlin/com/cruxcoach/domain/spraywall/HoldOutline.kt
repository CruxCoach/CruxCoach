package com.cruxcoach.domain.spraywall

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot

/**
 * A hold's shape: a closed ring `x0, y0, x1, y1, …` relative to the hold's
 * centre, in units of its radius (pixel space, so a round hold is round on a
 * photo of any aspect). Because it is relative, moving a hold moves its shape
 * and resizing scales it. A hold without a ring is drawn as a circle.
 *
 * Stored and published compactly: at most [STORED_POINTS] points, each
 * coordinate as one signed byte of 1/[STEPS] radius, base64url without padding.
 */
object HoldOutline {
    const val STORED_POINTS = 32
    const val MIN_POINTS = 3
    /** A point may sit at most this many radii from the centre. */
    const val MAX_COORDINATE = 4f
    private const val STEPS = 31f

    fun isValid(ring: List<Float>?): Boolean =
        ring != null && ring.size % 2 == 0 && ring.size / 2 in MIN_POINTS..150 &&
            ring.all { it.isFinite() && abs(it) <= MAX_COORDINATE }

    /** The ring with at most [maxPoints] points, dropping the least important ones; null when it is no shape. */
    fun fit(ring: List<Float>?, maxPoints: Int = STORED_POINTS): List<Float>? {
        if (!isValid(ring)) return null
        val points = ring!!.chunked(2) { it[0] to it[1] }
        if (points.size <= maxPoints) return ring
        var tolerance = 0.01
        var kept = points
        repeat(16) {
            if (kept.size <= maxPoints) return@repeat
            kept = simplifyClosed(points, tolerance)
            tolerance *= 1.5
        }
        if (kept.size > maxPoints || kept.size < MIN_POINTS) return null
        return kept.flatMap { listOf(it.first, it.second) }
    }

    fun encode(ring: List<Float>): String {
        val bytes = ByteArray(ring.size) { i -> floor(ring[i] * STEPS + 0.5f).toInt().coerceIn(-127, 127).toByte() }
        return Base64Url.encode(bytes)
    }

    fun decode(text: String): List<Float>? {
        val bytes = Base64Url.decode(text) ?: return null
        val ring = bytes.map { it / STEPS }
        return ring.takeIf { isValid(it) }
    }

    /** Douglas–Peucker on a closed ring, split at the point farthest from the first one. */
    private fun simplifyClosed(points: List<Pair<Float, Float>>, tolerance: Double): List<Pair<Float, Float>> {
        if (points.size <= MIN_POINTS) return points
        val far = points.indices.maxBy { hypot((points[it].first - points[0].first).toDouble(), (points[it].second - points[0].second).toDouble()) }
        val first = simplifyOpen(points.subList(0, far + 1), tolerance)
        val second = simplifyOpen(points.subList(far, points.size) + listOf(points[0]), tolerance)
        return first + second.drop(1).dropLast(1)
    }

    private fun simplifyOpen(points: List<Pair<Float, Float>>, tolerance: Double): List<Pair<Float, Float>> {
        if (points.size <= 2) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var best = -1
            var bestDistance = tolerance
            for (i in a + 1 until b) {
                val d = distance(points[i], points[a], points[b])
                if (d > bestDistance) {
                    best = i
                    bestDistance = d
                }
            }
            if (best >= 0) {
                keep[best] = true
                stack.addLast(a to best)
                stack.addLast(best to b)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    private fun distance(p: Pair<Float, Float>, a: Pair<Float, Float>, b: Pair<Float, Float>): Double {
        val dx = (b.first - a.first).toDouble()
        val dy = (b.second - a.second).toDouble()
        val l2 = dx * dx + dy * dy
        if (l2 == 0.0) return hypot((p.first - a.first).toDouble(), (p.second - a.second).toDouble())
        val t = (((p.first - a.first) * dx + (p.second - a.second) * dy) / l2).coerceIn(0.0, 1.0)
        return hypot(p.first - (a.first + t * dx), p.second - (a.second + t * dy))
    }
}

/** RFC 4648 base64url without padding; small enough to keep the shared module free of platform code. */
internal object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            out.append(ALPHABET[b0 shr 2])
            out.append(ALPHABET[((b0 and 3) shl 4) or (b1 shr 4)])
            if (i + 1 < bytes.size) out.append(ALPHABET[((b1 and 15) shl 2) or (b2 shr 6)])
            if (i + 2 < bytes.size) out.append(ALPHABET[b2 and 63])
            i += 3
        }
        return out.toString()
    }

    fun decode(text: String): ByteArray? {
        if (text.length % 4 == 1) return null
        val values = IntArray(text.length)
        for (i in text.indices) values[i] = ALPHABET.indexOf(text[i]).takeIf { it >= 0 } ?: return null
        val out = ByteArray(text.length * 3 / 4)
        var o = 0
        var i = 0
        while (i < values.size) {
            val v0 = values[i]
            val v1 = if (i + 1 < values.size) values[i + 1] else 0
            val v2 = if (i + 2 < values.size) values[i + 2] else 0
            val v3 = if (i + 3 < values.size) values[i + 3] else 0
            if (o < out.size) out[o++] = ((v0 shl 2) or (v1 shr 4)).toByte()
            if (o < out.size) out[o++] = (((v1 and 15) shl 4) or (v2 shr 2)).toByte()
            if (o < out.size) out[o++] = (((v2 and 3) shl 6) or v3).toByte()
            i += 4
        }
        return out
    }
}
