package com.cruxcoach.android.ui.board

import com.cruxcoach.domain.board.HoldSetMask
import com.cruxcoach.domain.board.MoonBoardHoldSets
import com.cruxcoach.domain.board.MoonBoardVariant

/**
 * Memoises the browse filter's MoonBoard hold-set mask (FEAT-049 §3.6).
 *
 * It exists because MoonBoard has no product size: `boardSize` stays null, so
 * `needsBoardReload` is permanently true for a MoonBoard and the block that
 * computes the mask runs on every browser refresh, not only on a board change.
 * The presence probe behind it walks the brand's rows while its answer is
 * false — which is exactly the state before the catalogue pipeline half ships
 * — so running it per refresh would be a recurring cost for no new answer.
 *
 * Split out of the ViewModel so the invalidation rule is testable on its own:
 * getting it wrong would let a 2019 mask survive a switch to a 2017.
 */
internal class MoonBoardMaskCache {

    private var key: String? = null
    private var mask: Long = 0L

    /**
     * The exclusion mask for [variant] given the user's [ownedSetIds], or 0
     * when the filter cannot apply.
     *
     * [syncGeneration] is part of the key so a completed catalogue sync
     * re-asks the gate — that is the one event that can flip its answer.
     *
     * @param hasCatalogueMask probes whether any MoonBoard row carries a real
     *  `hsm` yet. Called ONLY when the mask would otherwise be non-zero: with
     *  the complete setup selected the mask is 0 either way, so the probe
     *  could not change the outcome and everyone who never opens the picker
     *  avoids it entirely.
     */
    suspend fun maskFor(
        variant: MoonBoardVariant?,
        ownedSetIds: List<Long>,
        syncGeneration: Int,
        hasCatalogueMask: suspend () -> Boolean,
    ): Long {
        if (variant == null) {
            // Not a MoonBoard (or an unresolvable layout). Drop the memo so a
            // switch back cannot serve the previous board's answer.
            key = null
            mask = 0L
            return 0L
        }
        val next = "${variant.name}|${ownedSetIds.joinToString(",")}|$syncGeneration"
        if (next == key) return mask
        val computed = HoldSetMask.excludedMask(
            layoutSetIds = MoonBoardHoldSets.setIdsFor(variant),
            sizeSetIds = ownedSetIds,
        )
        mask = if (computed == 0L || hasCatalogueMask()) computed else 0L
        key = next
        return mask
    }
}
