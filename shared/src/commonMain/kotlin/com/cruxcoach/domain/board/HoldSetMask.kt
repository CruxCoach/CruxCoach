package com.cruxcoach.domain.board

/**
 * Helpers for the `climbs.hsm` hold-set bitmask that backs the always-on
 * "fits my board" browse filter (hold-set availability leg).
 *
 * `hsm` encodes WHICH hold sets a climb uses: the bit index of a set is the
 * RANK of its set_id within the layout's distinct set ids sorted ascending.
 * Verified empirically against the Tension RE DB (5000/5000 climbs) and the
 * Kilter chunk — e.g. Tension TB2 sets {8,9,10,11} → set 8 = bit0, 9 = bit1,
 * 10 = bit2, 11 = bit3; a climb using sets {8,11} carries hsm 9.
 *
 * `hsm = 0` means UNKNOWN — never "no sets". On the Kilter side that is ~10%
 * of rows. It used to be every MoonBoard row as well; since FEAT-049 the
 * MoonBoard catalogue carries a real mask computed in the build pipeline, and
 * 0 is left only where the sets genuinely cannot be resolved: a locally
 * authored or peer-received climb whose cells the on-device map does not
 * carry. Such climbs must always pass the filter; the SQL predicate
 * `(hsm & excludedMask) = 0` gives that leniency for free.
 *
 * The two sides differ only in where the set universe comes from: Kilter and
 * the Aurora family read it from `board_images`/`placements`, MoonBoard from
 * [MoonBoardHoldSets]. The bit rule below is shared and unchanged.
 */
object HoldSetMask {

    /**
     * Computes the exclusion mask for the browse queries' hsm predicate:
     * the bits of the layout's hold sets that are NOT mounted on the user's
     * selected product size. A climb passes iff `(climb.hsm & mask) == 0L`.
     *
     * @param layoutSetIds the layout's full set universe (defines the bit
     *  ranks; order/duplicates don't matter, ranking sorts ascending).
     * @param sizeSetIds the sets available on the selected size.
     * @return 0 (= filter off) when the layout has no set data, when the
     *  size carries every set, or when the size has NO set data at all —
     *  an unknown board must stay lenient rather than hide the catalogue.
     */
    fun excludedMask(layoutSetIds: Collection<Long>, sizeSetIds: Collection<Long>): Long {
        if (layoutSetIds.isEmpty()) return 0L
        val onSize = sizeSetIds.toSet()
        if (onSize.isEmpty()) return 0L
        var mask = 0L
        layoutSetIds.distinct().sorted().forEachIndexed { bit, setId ->
            if (setId !in onSize) mask = mask or (1L shl bit)
        }
        return mask
    }
}
