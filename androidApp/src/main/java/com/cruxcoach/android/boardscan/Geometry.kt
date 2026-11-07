package com.cruxcoach.android.boardscan

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** 3x3 homography, row-major. */
class Hom(val m: DoubleArray) {
    fun apply(x: Double, y: Double, out: DoubleArray) {
        val w = m[6] * x + m[7] * y + m[8]
        out[0] = (m[0] * x + m[1] * y + m[2]) / w
        out[1] = (m[3] * x + m[4] * y + m[5]) / w
    }

    /** Whether (x, y) maps in front of the camera (positive projective weight). */
    fun inFront(x: Double, y: Double): Boolean = m[6] * x + m[7] * y + m[8] > 0

    operator fun times(o: Hom): Hom {
        val r = DoubleArray(9)
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                var s = 0.0
                for (k in 0 until 3) s += m[i * 3 + k] * o.m[k * 3 + j]
                r[i * 3 + j] = s
            }
        }
        return Hom(r)
    }

    fun inverse(): Hom {
        val a = m
        val det = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6])
        val r = doubleArrayOf(
            a[4] * a[8] - a[5] * a[7], a[2] * a[7] - a[1] * a[8], a[1] * a[5] - a[2] * a[4],
            a[5] * a[6] - a[3] * a[8], a[0] * a[8] - a[2] * a[6], a[2] * a[3] - a[0] * a[5],
            a[3] * a[7] - a[4] * a[6], a[1] * a[6] - a[0] * a[7], a[0] * a[4] - a[1] * a[3],
        )
        for (i in 0 until 9) r[i] /= det
        return Hom(r)
    }

    companion object {
        fun diag(a: Double, b: Double, c: Double) = Hom(doubleArrayOf(a, 0.0, 0.0, 0.0, b, 0.0, 0.0, 0.0, c))

        /** Least-squares homography (h33 = 1) from >= 4 point pairs, with Hartley normalisation; null if degenerate. */
        fun fit(src: DoubleArray, dst: DoubleArray, n: Int): Hom? {
            val ns = normaliser(src, n)
            val nd = normaliser(dst, n)
            val a = DoubleArray(64)
            val bv = DoubleArray(8)
            val row = DoubleArray(8)
            for (i in 0 until n) {
                val x = (src[2 * i] - ns[0]) * ns[2]
                val y = (src[2 * i + 1] - ns[1]) * ns[2]
                val u = (dst[2 * i] - nd[0]) * nd[2]
                val v = (dst[2 * i + 1] - nd[1]) * nd[2]
                // u = (h0 x + h1 y + h2) / (h6 x + h7 y + 1)
                row[0] = x; row[1] = y; row[2] = 1.0; row[3] = 0.0; row[4] = 0.0; row[5] = 0.0; row[6] = -u * x; row[7] = -u * y
                accumulate(a, bv, row, u)
                row[0] = 0.0; row[1] = 0.0; row[2] = 0.0; row[3] = x; row[4] = y; row[5] = 1.0; row[6] = -v * x; row[7] = -v * y
                accumulate(a, bv, row, v)
            }
            val h = solve(a, bv, 8) ?: return null
            val hn = Hom(doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0))
            val ts = Hom(doubleArrayOf(ns[2], 0.0, -ns[2] * ns[0], 0.0, ns[2], -ns[2] * ns[1], 0.0, 0.0, 1.0))
            val td = Hom(doubleArrayOf(nd[2], 0.0, -nd[2] * nd[0], 0.0, nd[2], -nd[2] * nd[1], 0.0, 0.0, 1.0))
            val r = td.inverse() * hn * ts
            if (r.m.any { it.isNaN() || it.isInfinite() }) return null
            return r
        }

        /** Least-squares affine map from >= 3 point pairs; null if degenerate. */
        fun fitAffine(src: DoubleArray, dst: DoubleArray, n: Int): Hom? {
            val a = DoubleArray(9)
            val bu = DoubleArray(3)
            val bv = DoubleArray(3)
            val v = DoubleArray(3)
            for (i in 0 until n) {
                v[0] = src[2 * i]; v[1] = src[2 * i + 1]; v[2] = 1.0
                for (r in 0 until 3) {
                    for (c in 0 until 3) a[r * 3 + c] += v[r] * v[c]
                    bu[r] += v[r] * dst[2 * i]
                    bv[r] += v[r] * dst[2 * i + 1]
                }
            }
            val p = solve(a.copyOf(), bu, 3) ?: return null
            val q = solve(a.copyOf(), bv, 3) ?: return null
            return Hom(doubleArrayOf(p[0], p[1], p[2], q[0], q[1], q[2], 0.0, 0.0, 1.0))
        }

        private fun normaliser(p: DoubleArray, n: Int): DoubleArray {
            var cx = 0.0
            var cy = 0.0
            for (i in 0 until n) {
                cx += p[2 * i]
                cy += p[2 * i + 1]
            }
            cx /= n
            cy /= n
            var d = 0.0
            for (i in 0 until n) d += hypot(p[2 * i] - cx, p[2 * i + 1] - cy)
            d /= n
            return doubleArrayOf(cx, cy, if (d > 1e-9) sqrt(2.0) / d else 1.0)
        }

        private fun accumulate(a: DoubleArray, b: DoubleArray, row: DoubleArray, t: Double) {
            for (i in 0 until 8) {
                val ri = row[i]
                if (ri == 0.0) continue
                for (j in 0 until 8) a[i * 8 + j] += ri * row[j]
                b[i] += ri * t
            }
        }

        /** Gauss-Jordan with partial pivoting; [a] is n x n row-major and is destroyed. */
        fun solve(a: DoubleArray, b0: DoubleArray, n: Int): DoubleArray? {
            val b = b0.copyOf()
            for (col in 0 until n) {
                var piv = col
                for (r in col + 1 until n) if (abs(a[r * n + col]) > abs(a[piv * n + col])) piv = r
                if (abs(a[piv * n + col]) < 1e-12) return null
                if (piv != col) {
                    for (c in 0 until n) {
                        val t = a[col * n + c]
                        a[col * n + c] = a[piv * n + c]
                        a[piv * n + c] = t
                    }
                    val t = b[col]
                    b[col] = b[piv]
                    b[piv] = t
                }
                val d = a[col * n + col]
                for (r in 0 until n) {
                    if (r == col) continue
                    val f = a[r * n + col] / d
                    if (f == 0.0) continue
                    for (c in col until n) a[r * n + c] -= f * a[col * n + c]
                    b[r] -= f * b[col]
                }
            }
            return DoubleArray(n) { b[it] / a[it * n + it] }
        }
    }
}

/** An image -> rectified map with the y axis pointing up ([fwd]) and its inverse. */
class RectPair(val fwd: Hom, val back: Hom)

/**
 * Image -> rectified homographies: the rectified view looks straight at the board, so board coordinates map to
 * it by a similarity. Built from the gravity direction in camera coordinates (x right, y down, z forward), the
 * board's overhang and a guess of the camera's heading relative to the board.
 */
object Rectifier {
    /** Without a gravity reading: assume an upright camera and sweep its tilt against the board. */
    fun sweep(f: Double, cx: Double, cy: Double, tilts: DoubleArray, headings: DoubleArray): List<Hom> {
        val out = ArrayList<Hom>()
        // a camera tilted up by t relative to the board normal is a board with overhang t seen by a level camera
        for (t in tilts) out.addAll(rects(f, cx, cy, doubleArrayOf(0.0, -1.0, 0.0), t, headings))
        return out
    }

    fun rects(f: Double, cx: Double, cy: Double, upCam: DoubleArray, overhangDeg: Double, headings: DoubleArray): List<Hom> {
        val k = Hom(doubleArrayOf(f, 0.0, cx, 0.0, f, cy, 0.0, 0.0, 1.0))
        val kInv = k.inverse()
        val a = overhangDeg * PI / 180
        // world -> virtual camera looking at the board (world: x right, y up, z from the board to the climber)
        val rv = doubleArrayOf(1.0, 0.0, 0.0, 0.0, -cos(a), -sin(a), 0.0, sin(a), -cos(a))
        val norm = sqrt(upCam[0] * upCam[0] + upCam[1] * upCam[1] + upCam[2] * upCam[2])
        val yw = doubleArrayOf(upCam[0] / norm, upCam[1] / norm, upCam[2] / norm)
        // the horizontal part of the optical axis points at the board: z_world = -normalise(fwd - (fwd.y) y)
        val dot = yw[2]
        val hz = doubleArrayOf(-dot * yw[0], -dot * yw[1], 1.0 - dot * yw[2])
        val hn = sqrt(hz[0] * hz[0] + hz[1] * hz[1] + hz[2] * hz[2])
        val zw = doubleArrayOf(-hz[0] / hn, -hz[1] / hn, -hz[2] / hn)
        val xw = doubleArrayOf(yw[1] * zw[2] - yw[2] * zw[1], yw[2] * zw[0] - yw[0] * zw[2], yw[0] * zw[1] - yw[1] * zw[0])
        val out = ArrayList<Hom>()
        for (hd in headings) {
            val t = hd * PI / 180
            // R_cam = [xw yw zw] (columns) * Ry(t)
            val ry = doubleArrayOf(cos(t), 0.0, sin(t), 0.0, 1.0, 0.0, -sin(t), 0.0, cos(t))
            val rc = DoubleArray(9)
            for (i in 0 until 3) {
                for (j in 0 until 3) {
                    var s = 0.0
                    for (q in 0 until 3) {
                        val col = when (q) {
                            0 -> xw
                            1 -> yw
                            else -> zw
                        }
                        s += col[i] * ry[q * 3 + j]
                    }
                    rc[i * 3 + j] = s
                }
            }
            // H = K (Rv R_cam^T) K^-1
            val m = DoubleArray(9)
            for (i in 0 until 3) {
                for (j in 0 until 3) {
                    var s = 0.0
                    for (q in 0 until 3) s += rv[i * 3 + q] * rc[j * 3 + q]
                    m[i * 3 + j] = s
                }
            }
            out.add(k * Hom(m) * kInv)
        }
        return out
    }
}
