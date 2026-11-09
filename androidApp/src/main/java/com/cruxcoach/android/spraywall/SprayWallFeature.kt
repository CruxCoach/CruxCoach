package com.cruxcoach.android.spraywall

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The switch for spray walls (FEAT-078). Off, the app shows them nowhere: no spray walls on a place in the map, the
 * year in review does not mention them, and the main menu has their entry only on a phone that may still have
 * something of its own on the relays ([inMenu]). Nothing of them runs either, since only their screens start the
 * publisher and the fetcher.
 *
 * Data already on the phone stays as it is (its migrations run either way), and so does the hold model's entry under
 * Settings, so its storage can still be freed. Turned on again, everything is back.
 */
object SprayWallFeature {
    /** False hides spray walls in this build. */
    val enabled: Boolean = true

    /**
     * Whether the main menu has spray walls: always while [enabled]. Off, only while the relays may hold something of
     * mine ([SprayWallRepository.hasMineMaybeOnRelays]), so a wall made public or a climb set with an earlier build
     * can still be withdrawn or deleted; after that the entry goes. [repository] is only opened while off.
     */
    fun inMenu(enabled: Boolean, repository: () -> SprayWallRepository): Flow<Boolean> {
        if (enabled) return flowOf(true)
        val walls = repository()
        return walls.changes.map { walls.hasMineMaybeOnRelays() }.distinctUntilChanged()
    }
}
