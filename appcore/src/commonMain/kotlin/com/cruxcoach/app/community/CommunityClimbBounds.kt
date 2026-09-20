package com.cruxcoach.app.community

import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.community.ClimbBounds
import com.cruxcoach.domain.community.ClimbEditorState

/**
 * Bounding box of a draft's selected holds, in the board's own placement
 * coordinates. One implementation for the draft writer and the publisher, so the
 * `edge_*` columns and the event's `bounds` tag can never disagree.
 */
internal object CommunityClimbBounds {
    fun of(boardRepository: BoardRepository, state: ClimbEditorState): ClimbBounds? {
        // MoonBoard hold ids are not Aurora placement ids and the low ones would
        // collide with real Kilter placements, so any box derived here would be
        // physically wrong. A null bounds is handled everywhere it is consumed.
        if (BoardBrand.fromWire(state.boardBrand) == BoardBrand.MOONBOARD) return null
        val ids = state.selectedHolds.keys
        if (ids.isEmpty()) return null
        // Brand-scoped: placement ids overlap across boards (layout_id 1 alone is
        // five brands), so the default Kilter scope would box an Aurora draft
        // against Kilter coordinates.
        val all = boardRepository.getAllPlacements(state.boardBrand)
        if (all.isEmpty()) return null
        return ClimbBounds.fromCoords(
            all.asSequence()
                .filter { it.placementId.toInt() in ids }
                .map { it.x.toInt() to it.y.toInt() }
                .toList()
        )
    }
}
