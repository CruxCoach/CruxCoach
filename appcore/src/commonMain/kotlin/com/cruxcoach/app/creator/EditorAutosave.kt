package com.cruxcoach.app.creator

import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.domain.community.ClimbEditorState

/**
 * Android `EditorAutosave`: the climb editor's in-flight draft, one slot per exact board
 * (`brand_layout_size`), on Android's key names. [scopedKey] puts the slot under the
 * active identity, as Android's key-scoped DataStore does, so another key on this phone
 * never sees the draft. Cleared on save, publish and "Clear editor".
 */
class EditorAutosave(
    private val store: KeyValueStore,
    private val scopedKey: (String) -> String,
    private val nowMillis: () -> Long,
) {
    fun save(boardKey: String, state: ClimbEditorState) {
        // Nothing worth restoring: an empty slot stays empty.
        if (state.selectedHolds.isEmpty() && state.name.isBlank() && state.description.isBlank()) return
        store.putString(key(boardKey, HOLDS), encodeHolds(state.selectedHolds))
        store.putString(key(boardKey, NAME), state.name)
        store.putString(key(boardKey, DESCRIPTION), state.description)
        store.putString(key(boardKey, GRADE), state.setterGradeId?.toString())
        store.putString(key(boardKey, ANGLE), state.angle?.toString())
        store.putString(key(boardKey, SAVED_AT), nowMillis().toString())
    }

    /** Null when the slot is empty; only the draft content comes back, never a brand. */
    fun load(boardKey: String): ClimbEditorState? {
        val holds = store.getString(key(boardKey, HOLDS)) ?: return null
        store.getString(key(boardKey, SAVED_AT)) ?: return null
        return ClimbEditorState(
            selectedHolds = decodeHolds(holds),
            name = store.getString(key(boardKey, NAME)).orEmpty(),
            description = store.getString(key(boardKey, DESCRIPTION)).orEmpty(),
            setterGradeId = store.getString(key(boardKey, GRADE))?.toIntOrNull(),
            angle = store.getString(key(boardKey, ANGLE))?.toIntOrNull(),
        )
    }

    fun clear(boardKey: String) {
        for (field in FIELDS) store.putString(key(boardKey, field), null)
    }

    private fun key(boardKey: String, field: String) = scopedKey("editor_autosave_${boardKey}_$field")

    internal companion object {
        const val HOLDS = "holds"
        const val NAME = "name"
        const val DESCRIPTION = "description"
        const val GRADE = "grade"
        const val ANGLE = "angle"
        const val SAVED_AT = "saved_at"
        val FIELDS = listOf(HOLDS, NAME, DESCRIPTION, GRADE, ANGLE, SAVED_AT)

        /** Android's `pid:role;pid:role` form. */
        fun encodeHolds(holds: Map<Int, Int>): String =
            holds.entries.joinToString(";") { (pid, role) -> "$pid:$role" }

        /** Malformed pairs are skipped, as on Android: a damaged slot restores what it can. */
        fun decodeHolds(encoded: String): Map<Int, Int> {
            if (encoded.isBlank()) return emptyMap()
            val out = LinkedHashMap<Int, Int>()
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

/**
 * Android's profile hint before a publish (`shouldShowProfileHint`): shown while the
 * identity has no display name and the hint was never answered for it. The name comes
 * from Android's `NostrProfileManager.getProfile` path: the local cache, and the relays'
 * kind-0 when the cache has none or a stale one. A failed lookup reads as no profile,
 * as on Android.
 */
class CreatorProfileHint(
    private val dismissed: () -> Boolean,
    private val setDismissed: () -> Unit,
    /** The identity's display name, cache first, then relays; null when there is no profile. */
    private val ownDisplayName: suspend () -> String?,
    private val hasIdentity: () -> Boolean,
) {
    suspend fun shouldShow(): Boolean {
        if (dismissed()) return false
        if (!hasIdentity()) return false
        val name = try {
            ownDisplayName()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return name.isNullOrBlank()
    }

    fun dismiss() = setDismissed()
}
