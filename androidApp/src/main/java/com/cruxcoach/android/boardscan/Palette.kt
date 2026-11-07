package com.cruxcoach.android.boardscan

import kotlin.math.max
import kotlin.math.min

object Roles {
    const val START = 0
    const val HAND = 1
    const val FINISH = 2
    const val FOOT = 3
}

/**
 * Hues (degrees) a phone camera records for each role's LED colour in one lighting scheme.
 * A negative hue means the scheme has no such role.
 */
class Palette(
    val name: String,
    val hues: DoubleArray,
    /** Roles whose colours record too close together to tell apart up front share a class. */
    val classes: IntArray,
    /** How far (degrees) a blob's hue may be from its role's hue. */
    val maxDist: Double = 26.0,
    /** Hue ranges no LED of this scheme has (e.g. yellow holds on a MoonBoard); blobs there are ignored. */
    val deadZones: List<ClosedFloatingPointRange<Double>> = emptyList(),
    /** LED blobs must reach this share of the frame's brightest blobs (bright LED dots vs painted holds); 0 = off. */
    val minPeakFrac: Double = 0.0,
) {
    companion object {
        // measured on photos of lit boards: green start ~152, cyan hand ~194, magenta finish ~287, orange foot ~38;
        // green and cyan record only ~40 deg apart and can merge, so they share one class
        val KILTER = Palette("kilter", doubleArrayOf(152.0, 194.0, 287.0, 38.0), intArrayOf(0, 0, 2, 3))
        val CRUXCOACH = Palette("cruxcoach", doubleArrayOf(292.0, 232.0, 152.0, 2.0), intArrayOf(0, 1, 2, 3))
        // three well separated colours; the yellow and orange of the MoonBoard's holds are never LED light
        val MOONBOARD = Palette(
            "moonboard", doubleArrayOf(150.0, 232.0, 356.0, -1.0), intArrayOf(0, 1, 2, 3),
            maxDist = 50.0, deadZones = listOf(32.0..105.0), minPeakFrac = 0.8,
        )
    }
}

/** Role per blob (-1 = none) under one palette, after the global hue shift that best explains the blobs. */
class RoleLabels(val roles: IntArray, val cost: Double)

fun labelRoles(blobs: List<Blob>, pal: Palette): RoleLabels {
    val maxDist = pal.maxDist
    val minPeak = if (pal.minPeakFrac > 0 && blobs.size >= 5) {
        val peaks = blobs.map { it.peak }.sorted()
        max(160.0, pal.minPeakFrac * peaks[(peaks.size * 95) / 100 - (if ((peaks.size * 95) % 100 == 0) 1 else 0)])
    } else {
        0.0
    }
    var bestCost = Double.MAX_VALUE
    var best = IntArray(blobs.size) { -1 }
    // the camera's white balance shifts every hue by about the same amount
    var shift = -20.0
    while (shift <= 20.0) {
        var cost = 0.0
        val roles = IntArray(blobs.size)
        for ((i, b) in blobs.withIndex()) {
            if (pal.deadZones.any { b.hue in it } || b.peak < minPeak) {
                roles[i] = -1
                cost += maxDist
                continue
            }
            var bd = Double.MAX_VALUE
            var br = -1
            for (r in 0 until 4) {
                if (pal.hues[r] < 0) continue
                val d = hueDist(b.hue, pal.hues[r] + shift)
                if (d < bd) {
                    bd = d
                    br = r
                }
            }
            cost += min(bd, maxDist)
            roles[i] = if (bd <= maxDist) br else -1
        }
        if (cost < bestCost) {
            bestCost = cost
            best = roles
        }
        shift += 4.0
    }
    return RoleLabels(best, bestCost)
}
