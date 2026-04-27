package com.cruxcoach.android.community

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.cruxcoach.android.data.dataStore
import com.cruxcoach.domain.community.ClimbEditorState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Best-effort autosave for the climb editor — writes the current draft
 * to DataStore on every change so a navigation-away or process-kill
 * can recover via `load()` on the next editor open.
 *
 * One slot only: re-opening the editor on a different climb overwrites
 * the previous autosave. Cleared on explicit save / publish / discard.
 *
 * Holds map is JSON-serialised as `pidA:roleA;pidB:roleB;…` — minimal,
 * readable in logs, no external library needed. SQL-injection / quoting
 * is moot because the value never leaves DataStore.
 */
@Singleton
class EditorAutosave @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun save(state: ClimbEditorState) {
        if (state.selectedHolds.isEmpty() && state.name.isBlank() && state.description.isBlank()) {
            // Nothing meaningful to restore — skip the write.
            return
        }
        val holds = encodeHolds(state.selectedHolds)
        context.dataStore.edit { prefs ->
            prefs[KEY_HOLDS] = holds
            prefs[KEY_NAME] = state.name
            prefs[KEY_DESCRIPTION] = state.description
            state.setterGradeId?.let { prefs[KEY_GRADE] = it } ?: prefs.remove(KEY_GRADE)
            state.angle?.let { prefs[KEY_ANGLE] = it } ?: prefs.remove(KEY_ANGLE)
            prefs[KEY_SAVED_AT] = System.currentTimeMillis()
        }
    }

    suspend fun load(): AutosaveSnapshot? {
        val prefs = context.dataStore.data.firstOrNull() ?: return null
        val holds = prefs[KEY_HOLDS] ?: return null
        val savedAt = prefs[KEY_SAVED_AT] ?: return null
        return AutosaveSnapshot(
            state = ClimbEditorState(
                selectedHolds = decodeHolds(holds),
                name = prefs[KEY_NAME].orEmpty(),
                description = prefs[KEY_DESCRIPTION].orEmpty(),
                setterGradeId = prefs[KEY_GRADE],
                angle = prefs[KEY_ANGLE],
                activeBrush = null,
            ),
            savedAtEpochMs = savedAt,
        )
    }

    suspend fun clear() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_HOLDS)
            prefs.remove(KEY_NAME)
            prefs.remove(KEY_DESCRIPTION)
            prefs.remove(KEY_GRADE)
            prefs.remove(KEY_ANGLE)
            prefs.remove(KEY_SAVED_AT)
        }
    }

    /**
     * Did the last call to [save] persist a non-trivial draft? UI uses
     * this to decide whether to show the "Restore previous session?"
     * prompt on open.
     */
    suspend fun hasAutosave(): Boolean {
        val prefs = context.dataStore.data.first()
        return prefs[KEY_HOLDS] != null
    }

    data class AutosaveSnapshot(
        val state: ClimbEditorState,
        val savedAtEpochMs: Long,
    )

    companion object {
        // Co-located in cruxcoach_prefs to avoid spawning a second DataStore
        // file just for one editor feature.
        private val KEY_HOLDS = stringPreferencesKey("editor_autosave_holds")
        private val KEY_NAME = stringPreferencesKey("editor_autosave_name")
        private val KEY_DESCRIPTION = stringPreferencesKey("editor_autosave_description")
        private val KEY_GRADE = intPreferencesKey("editor_autosave_grade")
        private val KEY_ANGLE = intPreferencesKey("editor_autosave_angle")
        private val KEY_SAVED_AT = longPreferencesKey("editor_autosave_saved_at")

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
