package com.cruxcoach.android.boardscan

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GeometryTest {

    private val known = Hom(doubleArrayOf(1.3, 0.2, 40.0, -0.1, 0.9, 25.0, 0.0004, -0.0002, 1.0))

    private fun points(): DoubleArray = doubleArrayOf(0.0, 0.0, 100.0, 0.0, 100.0, 150.0, 0.0, 150.0, 50.0, 70.0, 20.0, 120.0)

    @Test
    fun fitRecoversAHomography() {
        val src = points()
        val n = src.size / 2
        val dst = DoubleArray(src.size)
        val t = DoubleArray(2)
        for (i in 0 until n) {
            known.apply(src[2 * i], src[2 * i + 1], t)
            dst[2 * i] = t[0]
            dst[2 * i + 1] = t[1]
        }
        val fit = assertNotNull(Hom.fit(src, dst, n))
        val u = DoubleArray(2)
        for (p in listOf(10.0 to 10.0, 80.0 to 140.0, 60.0 to 30.0)) {
            known.apply(p.first, p.second, t)
            fit.apply(p.first, p.second, u)
            assertTrue(abs(t[0] - u[0]) < 1e-6 && abs(t[1] - u[1]) < 1e-6)
        }
    }

    @Test
    fun inverseUndoesTheMap() {
        val t = DoubleArray(2)
        val u = DoubleArray(2)
        known.apply(33.0, 77.0, t)
        known.inverse().apply(t[0], t[1], u)
        assertTrue(abs(u[0] - 33.0) < 1e-9 && abs(u[1] - 77.0) < 1e-9)
    }

    @Test
    fun aLevelCameraFacingAVerticalBoardNeedsNoRectification() {
        val r = Rectifier.rects(1000.0, 360.0, 640.0, doubleArrayOf(0.0, -1.0, 0.0), 0.0, doubleArrayOf(0.0)).single()
        val t = DoubleArray(2)
        r.apply(100.0, 200.0, t)
        assertTrue(abs(t[0] - 100.0) < 1e-6 && abs(t[1] - 200.0) < 1e-6)
    }

    @Test
    fun theCameraDistanceComesBackFromAPose() {
        // a camera 4.5 m in front of the board centre, looking 20 degrees to the side; board units of 1 cm
        val f = 1000.0; val cx = 360.0; val cy = 640.0; val mpu = 0.01; val d = 4.5
        val a = Math.toRadians(20.0)
        val r = doubleArrayOf(cos(a), 0.0, sin(a), 0.0, 1.0, 0.0, -sin(a), 0.0, cos(a))
        // board point (u, v) units -> camera: R (mpu (u - 100), mpu (v - 150), 0) + (0, 0, d)
        val c0 = doubleArrayOf(r[0] * mpu, r[3] * mpu, r[6] * mpu)
        val c1 = doubleArrayOf(r[1] * mpu, r[4] * mpu, r[7] * mpu)
        val c2 = DoubleArray(3) { -100.0 * c0[it] - 150.0 * c1[it] + if (it == 2) d else 0.0 }
        val h = Hom(doubleArrayOf(
            f * c0[0] + cx * c0[2], f * c1[0] + cx * c1[2], f * c2[0] + cx * c2[2],
            f * c0[1] + cy * c0[2], f * c1[1] + cy * c1[2], f * c2[1] + cy * c2[2],
            c0[2], c1[2], c2[2],
        ))
        // any overall scale of the homography gives the same distance
        val scaled = Hom(DoubleArray(9) { -3.0 * h.m[it] })
        for (pose in listOf(h, scaled)) {
            val dist = assertNotNull(cameraDistance(pose, f, cx, cy, mpu, 100.0, 150.0))
            assertTrue(abs(dist - d) < 1e-6, "distance $dist")
        }
    }
}
