package com.cruxcoach.android.data

import kotlinx.coroutines.flow.Flow

/**
 * The observable catalogue revision: a counter that advances whenever the
 * board catalogue's *contents* changed (FEAT-049 §3.7).
 *
 * It exists because [BoardSyncState.syncGeneration] answers a different
 * question. That one advances when a sync run is *claimed* — before a single
 * chunk is imported — and never again for the rest of the run, and a deletion
 * does not move it at all. Anything caching a fact ABOUT the data (the hold-set
 * presence gate and the mask derived from it) therefore cannot key on it: a
 * browser opened in the moment between claim and import caches its "no
 * hold-set data" answer under a generation that never changes again, and goes
 * on serving it until the process restarts.
 *
 * This revision advances after a catalogue commit and after a catalogue
 * deletion — the two events that can flip such an answer. Keep both meanings
 * separate: "a run started" and "the data changed" are not the same moment.
 *
 * A narrow interface rather than the whole [BoardSyncManager] so a ViewModel
 * that needs nothing else from it can be built in a JVM test — the picker's
 * live reaction to a sync landing mid-screen is exactly the behaviour that
 * needs testing, and it is untestable against a manager that wants a Context,
 * an OkHttp client and an importer.
 */
interface CatalogueRevisionSource {
    /** Monotonically increasing; emits the current value on collection. */
    val catalogueRevision: Flow<Int>
}
