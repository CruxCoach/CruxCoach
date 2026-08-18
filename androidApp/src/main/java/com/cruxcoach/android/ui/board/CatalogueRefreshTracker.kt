package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.BoardSyncState

/**
 * Decides when catalogue mutations require one browse refresh.
 *
 * Revisions committed during a multi-board sync are held until that run ends,
 * so several imported chunks collapse into one query. Revisions outside a run
 * (for example catalogue deletion) refresh immediately.
 */
internal class CatalogueRefreshTracker(
    initialGeneration: Int,
    initialCatalogueRevision: Int,
) {
    private var lastGeneration = initialGeneration
    private var lastCatalogueRevision = initialCatalogueRevision
    private var catalogueRevisionPending = false

    fun shouldRefresh(syncState: BoardSyncState): Boolean {
        val catalogueChanged = syncState.catalogueRevision > lastCatalogueRevision
        if (catalogueChanged) lastCatalogueRevision = syncState.catalogueRevision
        if (catalogueChanged && syncState.isSyncing) catalogueRevisionPending = true

        val syncEnded = syncState.syncGeneration > lastGeneration && !syncState.isSyncing
        if (syncEnded) lastGeneration = syncState.syncGeneration

        val shouldRefresh = !syncState.isSyncing &&
            (syncEnded || catalogueChanged || catalogueRevisionPending)
        if (shouldRefresh) catalogueRevisionPending = false
        return shouldRefresh
    }
}
