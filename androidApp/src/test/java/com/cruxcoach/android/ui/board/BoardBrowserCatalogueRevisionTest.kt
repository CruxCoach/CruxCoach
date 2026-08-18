package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.BoardSyncState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for catalogue refresh ordering during a board sync. */
class BoardBrowserCatalogueRevisionTest {

    @Test
    fun `a chunk committed mid-sync requests one refresh when the run ends`() {
        val tracker = CatalogueRefreshTracker(
            initialGeneration = 7,
            initialCatalogueRevision = 0,
        )

        assertFalse(tracker.shouldRefresh(state(syncing = true, revision = 0)))
        assertFalse(tracker.shouldRefresh(state(syncing = true, revision = 1)))

        // The generation is deliberately unchanged: the browser opened after
        // the run claimed its slot, so only the pending revision can redeem it.
        assertTrue(tracker.shouldRefresh(state(syncing = false, revision = 1)))
        assertFalse(tracker.shouldRefresh(state(syncing = false, revision = 1)))
    }

    @Test
    fun `a catalogue mutation outside a sync requests an immediate refresh`() {
        val tracker = CatalogueRefreshTracker(
            initialGeneration = 7,
            initialCatalogueRevision = 1,
        )

        assertTrue(tracker.shouldRefresh(state(syncing = false, revision = 2)))
        assertFalse(tracker.shouldRefresh(state(syncing = false, revision = 2)))
    }

    @Test
    fun `a completed newer generation requests one refresh without a revision`() {
        val tracker = CatalogueRefreshTracker(
            initialGeneration = 7,
            initialCatalogueRevision = 1,
        )

        assertTrue(
            tracker.shouldRefresh(
                state(syncing = false, revision = 1, generation = 8),
            ),
        )
        assertFalse(
            tracker.shouldRefresh(
                state(syncing = false, revision = 1, generation = 8),
            ),
        )
    }

    private fun state(
        syncing: Boolean,
        revision: Int,
        generation: Int = 7,
    ) = BoardSyncState(
        isSyncing = syncing,
        syncGeneration = generation,
        catalogueRevision = revision,
    )
}
