package com.cruxcoach.android.community

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.cruxcoach.domain.community.ClimbEditorState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Best-effort autosave for the climb editor — writes the current draft
 * to DataStore on every change so a navigation-away or process-kill
 * can recover via `load()` on the next editor open.
 *
 * One slot PER EXACT BOARD (brand + layout + size — the same granularity the
 * board picker differentiates): keys are namespaced by a `boardKey`, so a draft
 * in progress on one board is preserved independently and never bleeds into a
 * different board's editor. Cleared on explicit save / publish / discard.
 *
 * Per-identity scope: writes go to the `keyScoped` DataStore (one file
 * per Nostr-pubkey-prefix — see [AppModule.provideKeyScopedDataStore]),
 * so an identity switch on the same device doesn't surface the previous
 * identity's in-flight draft. Mirrors the pattern used by every other
 * identity-bound preference (KILTER_SYNC_ENABLED, COMMUNITY_CLIMB_SINCE,
 * PROFILE_HINT_DISMISSED, etc.).
 *
 * Holds map is JSON-serialised as `pidA:roleA;pidB:roleB;…` — minimal,
 * readable in logs, no external library needed.
 */
@Singleton
class EditorAutosave @Inject constructor(
    @Named("keyScoped") private val store: DataStore<Preferences>,
) {
    /** [boardKey] = exact-board identity (e.g. "tension_10_6"). */
    suspend fun save(boardKey: String, state: ClimbEditorState) {
        if (state.selectedHolds.isEmpty() && state.name.isBlank() && state.description.isBlank()) {
            // Nothing meaningful to restore — skip the write.
            return
        }
        val holds = encodeHolds(state.selectedHolds)
        store.edit { prefs ->
            prefs[holdsKey(boardKey)] = holds
            prefs[nameKey(boardKey)] = state.name
            prefs[descKey(boardKey)] = state.description
            state.setterGradeId?.let { prefs[gradeKey(boardKey)] = it } ?: prefs.remove(gradeKey(boardKey))
            state.angle?.let { prefs[angleKey(boardKey)] = it } ?: prefs.remove(angleKey(boardKey))
            prefs[savedAtKey(boardKey)] = System.currentTimeMillis()
        }
    }

    suspend fun load(boardKey: String): AutosaveSnapshot? {
        val prefs = store.data.firstOrNull() ?: return null
        val holds = prefs[holdsKey(boardKey)] ?: return null
        val savedAt = prefs[savedAtKey(boardKey)] ?: return null
        return AutosaveSnapshot(
            state = ClimbEditorState(
                selectedHolds = decodeHolds(holds),
                name = prefs[nameKey(boardKey)].orEmpty(),
                description = prefs[descKey(boardKey)].orEmpty(),
                setterGradeId = prefs[gradeKey(boardKey)],
                angle = prefs[angleKey(boardKey)],
            ),
            savedAtEpochMs = savedAt,
        )
    }

    suspend fun clear(boardKey: String) {
        store.edit { prefs ->
            prefs.remove(holdsKey(boardKey))
            prefs.remove(nameKey(boardKey))
            prefs.remove(descKey(boardKey))
            prefs.remove(gradeKey(boardKey))
            prefs.remove(angleKey(boardKey))
            prefs.remove(savedAtKey(boardKey))
        }
    }

    /**
     * Did the last call to [save] persist a non-trivial draft for [boardKey]?
     * UI uses this to decide whether to show the "Restore previous session?"
     * prompt on open.
     */
    suspend fun hasAutosave(boardKey: String): Boolean {
        val prefs = store.data.first()
        return prefs[holdsKey(boardKey)] != null
    }

    data class AutosaveSnapshot(
        val state: ClimbEditorState,
        val savedAtEpochMs: Long,
    )

    companion object {
        // Stored in the per-identity (keyScoped) DataStore — see class kdoc.
        // Keys are namespaced per EXACT board ([boardKey] = brand_layout_size)
        // so every board keeps its own in-flight draft without cross-contamination.
        private fun holdsKey(b: String) = stringPreferencesKey("editor_autosave_${b}_holds")
        private fun nameKey(b: String) = stringPreferencesKey("editor_autosave_${b}_name")
        private fun descKey(b: String) = stringPreferencesKey("editor_autosave_${b}_description")
        private fun gradeKey(b: String) = intPreferencesKey("editor_autosave_${b}_grade")
        private fun angleKey(b: String) = intPreferencesKey("editor_autosave_${b}_angle")
        private fun savedAtKey(b: String) = longPreferencesKey("editor_autosave_${b}_saved_at")

        internal fun encodeHolds(holds: Map<Int, Int>): String =
            holds.entries.joinToString(";") { (pid, role) -> "$pid:$role" }

        internal fun decodeHolds(encoded: String): Map<Int, Int> {
            if (encoded.isBlank()) return emptyMap()
            val out = HashMap<Int, Int>(8)
            for (pair in encoded.split(';')) {
                val parts = pair.split(':')
                if (parts.size != 2) continue
                val pid = parts[0].toIntOrNull() ?: continue
                val role = parts[1].toIntOrNull() ?: continue
                out[pid] = role
            }
            return out
        }
    }
}
