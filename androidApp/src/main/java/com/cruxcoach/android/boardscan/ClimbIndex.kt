package com.cruxcoach.android.boardscan

/**
 * The problems of one board configuration, most climbed first, in the form the recogniser searches.
 *
 * Hole coordinates are in board units with y pointing up. Problem `c` owns the slice `offsets[c] until
 * offsets[c + 1]` of [holes] (hole indices) and [roles] ([Roles]); the slices are reordered in place so that the
 * non-foot holds come first.
 */
class ClimbIndex(
    val holeX: FloatArray,
    val holeY: FloatArray,
    val offsets: IntArray,
    val holes: ShortArray,
    val roles: ByteArray,
    /** Logged ascents per problem, used to break ties. */
    val ascents: IntArray,
    /** The board's hole spacing in board units. */
    val spacing: Double,
    /** The smallest distance between two holes, in board units. */
    val minHoleDist: Double,
    /** The board's outline in board units: xmin, xmax, ymin, ymax. */
    val extent: DoubleArray,
    val metresPerUnit: Double,
) {
    val size: Int get() = offsets.size - 1
    /** Hold-set signature (holes + roles) for duplicate grouping. */
    val sig: LongArray = LongArray(size)
    /** Per climb and role: the 2 highest holds of that role (-1 = none). */
    val topSlots: IntArray = IntArray(size * 8) { -1 }
    /** Per climb and role: the 3 lowest holds of that role (-1 = none). */
    val bottomSlots: IntArray = IntArray(size * 12) { -1 }

    /** Board coordinates per hold slot (aligned with [holes]). */
    val holdX: FloatArray = FloatArray(holes.size)
    val holdY: FloatArray = FloatArray(holes.size)
    /** Per climb: number of non-foot holds; they come first in the climb's slice. */
    val nonFoot: IntArray = IntArray(size)

    init {
        for (c in 0 until size) {
            val a = offsets[c]
            val m = offsets[c + 1] - a
            // non-foot holds first: the pose search relies on them, feet are often dim
            val ord = (0 until m).sortedBy { if (roles[a + it].toInt() == Roles.FOOT) 1 else 0 }
            val hs = ShortArray(m) { holes[a + ord[it]] }
            val rs = ByteArray(m) { roles[a + ord[it]] }
            for (k in 0 until m) {
                holes[a + k] = hs[k]
                roles[a + k] = rs[k]
            }
            nonFoot[c] = rs.count { it.toInt() != Roles.FOOT }
            for (k in 0 until m) {
                holdX[a + k] = holeX[hs[k].toInt()]
                holdY[a + k] = holeY[hs[k].toInt()]
            }
            val idx = (0 until m).sortedByDescending { holeY[holes[a + it].toInt()] }
            val keys = LongArray(m) { (holes[a + it].toLong() shl 3) or roles[a + it].toLong() }
            keys.sort()
            var h = 1125899906842597L
            for (k in keys) h = 31 * h + k
            sig[c] = h
            for (k in 0 until m) {
                val slot = idx[k]
                val r = roles[a + slot].toInt()
                val o = c * 8 + r * 2
                if (topSlots[o] < 0) topSlots[o] = slot else if (topSlots[o + 1] < 0) topSlots[o + 1] = slot
            }
            for (k in 0 until m) {
                val slot = idx[m - 1 - k]
                val r = roles[a + slot].toInt()
                val o = c * 12 + r * 3
                if (bottomSlots[o] < 0) bottomSlots[o] = slot
                else if (bottomSlots[o + 1] < 0) bottomSlots[o + 1] = slot
                else if (bottomSlots[o + 2] < 0) bottomSlots[o + 2] = slot
            }
        }
    }
}

/** A colourful feature of the unlit board (e.g. a yellow hold), in board units, found in the board's own image. */
class StaticMark(val x: Double, val y: Double, val hue: Double)
