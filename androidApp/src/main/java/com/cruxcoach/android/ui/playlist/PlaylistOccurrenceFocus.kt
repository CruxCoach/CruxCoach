package com.cruxcoach.android.ui.playlist

/**
 * Purely local cursor for the occurrence-aware player.
 *
 * It deliberately returns an entry id and never a playlist operation. This is
 * the boundary that makes opening, swiping and pressing previous/next local
 * navigation; only the separately wired lamp is allowed to change canonical
 * current-on-board state.
 */
internal object PlaylistOccurrenceFocus {
    fun resolve(
        entryIds: List<String>,
        requestedEntryId: String?,
        previousEntryId: String?,
        currentEntryId: String?,
    ): String? = previousEntryId?.takeIf { it in entryIds }
        ?: requestedEntryId?.takeIf { it in entryIds }
        ?: currentEntryId?.takeIf { it in entryIds }
        ?: entryIds.firstOrNull()

    fun step(entryIds: List<String>, focusedEntryId: String?, delta: Int): String? {
        if (entryIds.isEmpty()) return null
        val index = entryIds.indexOf(focusedEntryId).takeIf { it >= 0 } ?: 0
        return entryIds[(index + delta).coerceIn(0, entryIds.lastIndex)]
    }
}
