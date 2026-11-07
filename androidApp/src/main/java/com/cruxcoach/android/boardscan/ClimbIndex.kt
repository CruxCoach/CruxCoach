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
    /** Per climb and role: the 2 highest holds of that role (slot in the climb's slice, -1 = none). */
    val topSlots: ByteArray = ByteArray(size * 8) { -1 }
    /** Per climb and role: the 3 lowest holds of that role (slot in the climb's slice, -1 = none). */
    val bottomSlots: ByteArray = ByteArray(size * 12) { -1 }

    /** Board coordinates per hold slot (aligned with [holes]). */
    val holdX: FloatArray = FloatArray(holes.size)
    val holdY: FloatArray = FloatArray(holes.size)
    /** Per climb: number of non-foot holds; they come first in the climb's slice. */
    val nonFoot: ByteArray = ByteArray(size)

    init {
        // primitive scratch arrays: a whole Kilter catalogue is over 200,000 problems, built on the phone
        var maxM = 0
        for (c in 0 until size) maxM = maxOf(maxM, offsets[c + 1] - offsets[c])
        require(maxM <= Byte.MAX_VALUE) { "a problem with $maxM holds" }
        val hs = ShortArray(maxM)
        val rs = ByteArray(maxM)
        val idx = IntArray(maxM)
        val keys = LongArray(maxM)
        for (c in 0 until size) {
            val a = offsets[c]
            val m = offsets[c + 1] - a
            // non-foot holds first, each group in its order: the pose search relies on them, feet are often dim
            var k = 0
            for (i in 0 until m) if (roles[a + i].toInt() != Roles.FOOT) { hs[k] = holes[a + i]; rs[k] = roles[a + i]; k++ }
            nonFoot[c] = k.toByte()
            for (i in 0 until m) if (roles[a + i].toInt() == Roles.FOOT) { hs[k] = holes[a + i]; rs[k] = roles[a + i]; k++ }
            for (i in 0 until m) {
                holes[a + i] = hs[i]
                roles[a + i] = rs[i]
                holdX[a + i] = holeX[hs[i].toInt()]
                holdY[a + i] = holeY[hs[i].toInt()]
            }
            // slots from the highest hold down; equal heights keep their order
            for (i in 0 until m) {
                val y = holdY[a + i]
                var j = i
                while (j > 0 && holdY[a + idx[j - 1]] < y) { idx[j] = idx[j - 1]; j-- }
                idx[j] = i
            }
            for (i in 0 until m) keys[i] = (holes[a + i].toLong() shl 3) or roles[a + i].toLong()
            keys.sort(0, m)
            var h = 1125899906842597L
            for (i in 0 until m) h = 31 * h + keys[i]
            sig[c] = h
            for (i in 0 until m) {
                val slot = idx[i]
                val o = c * 8 + roles[a + slot] * 2
                if (topSlots[o] < 0) topSlots[o] = slot.toByte() else if (topSlots[o + 1] < 0) topSlots[o + 1] = slot.toByte()
            }
            for (i in 0 until m) {
                val slot = idx[m - 1 - i]
                val o = c * 12 + roles[a + slot] * 3
                if (bottomSlots[o] < 0) bottomSlots[o] = slot.toByte()
                else if (bottomSlots[o + 1] < 0) bottomSlots[o + 1] = slot.toByte()
                else if (bottomSlots[o + 2] < 0) bottomSlots[o + 2] = slot.toByte()
            }
        }
    }
}

/** A colourful feature of the unlit board (e.g. a yellow hold), in board units, found in the board's own image. */
class StaticMark(val x: Double, val y: Double, val hue: Double)
