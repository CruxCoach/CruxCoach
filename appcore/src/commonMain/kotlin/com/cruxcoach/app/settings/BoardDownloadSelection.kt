package com.cruxcoach.app.settings

import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.domain.board.BoardBrand

/**
 * Which catalogues this installation downloads (Android `board_download_brands`,
 * a DataStore string set). [KeyValueStore] holds strings, so the set is stored
 * as comma-separated wire values.
 *
 * Three states have to stay distinguishable, exactly as on Android:
 *  - key absent   -> never chosen; every interactive board is eligible,
 *  - key present but empty -> the user opted every board out,
 *  - key present with values -> that selection.
 *
 * Unknown or non-interactive wire values are dropped on read: a future release
 * must not silently opt an old installation into extra downloads.
 */
object BoardDownloadSelection {
    const val KEY = "board_download_brands"

    private val allInteractive: List<BoardBrand> get() = BoardBrand.entries.filter { it.isInteractive }

    fun isConfigured(store: KeyValueStore): Boolean = read(store) != null

    /** Null when the user has never chosen; [selected] applies the Android default for that case. */
    fun selected(store: KeyValueStore): List<BoardBrand> = read(store) ?: allInteractive

    fun save(store: KeyValueStore, brands: List<BoardBrand>) {
        val wires = brands.filter { it.isInteractive }.map { it.wireValue }.distinct()
        store.safePut(KEY, wires.joinToString(SEPARATOR))
    }

    /** Deleting a catalogue also opts it out, or the next auto-sync would undo the deletion. */
    fun exclude(store: KeyValueStore, brands: List<BoardBrand>) {
        val doomed = brands.toSet()
        save(store, selected(store).filterNot { it in doomed })
    }

    private fun read(store: KeyValueStore): List<BoardBrand>? {
        val raw = store.safeGet(KEY) ?: return null
        if (raw.isEmpty()) return emptyList()
        return raw.split(SEPARATOR)
            .mapNotNull { wire -> BoardBrand.fromWireOrNull(wire.trim())?.takeIf { it.isInteractive } }
            .distinct()
    }

    private const val SEPARATOR = ","
}
