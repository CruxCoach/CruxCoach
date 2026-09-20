package com.cruxcoach.app.ui

import com.cruxcoach.app.platform.KeyValueStore

/**
 * "What's new" on upgrade, port of Android `WhatsNewViewModel`.
 *
 * The rules that matter are the ones that keep it from becoming noise: a
 * fresh install sees nothing (onboarding already introduces the app), the
 * watermark only ever moves forward, and it is written once the whole queue
 * has been read — so a dialog closed by accident comes back rather than being
 * lost.
 *
 * The item ids are Android's, so both platforms show the same release notes
 * under the same string keys.
 */
class WhatsNewScreenModel(
    private val store: KeyValueStore,
    private val currentVersionCode: Int = CURRENT_VERSION_CODE,
) {
    private var queue: List<String> = emptyList()

    /** Release-note id to show now, or "" when there is nothing. */
    var currentId: String = ""
        private set

    /**
     * Computes the queue. [onboardingCompleted] false with no watermark means
     * a fresh install: nothing to announce.
     */
    fun start(onboardingCompleted: Boolean) {
        val lastSeen = store.getString(KEY_LAST_SEEN)?.toIntOrNull()
        if (lastSeen == null && !onboardingCompleted) {
            markSeen(lastSeen)
            return
        }
        val effective = lastSeen ?: 0
        queue = REGISTRY
            .filter { it.sinceVersionCode in (effective + 1)..currentVersionCode }
            .sortedBy { it.sinceVersionCode }
            .map { it.id }
        if (queue.isEmpty()) {
            markSeen(lastSeen)
            currentId = ""
        } else {
            currentId = queue.first()
        }
    }

    /** Advances to the next note; writes the watermark after the last one. */
    fun dismiss() {
        queue = queue.drop(1)
        currentId = queue.firstOrNull() ?: ""
        if (queue.isEmpty()) markSeen(store.getString(KEY_LAST_SEEN)?.toIntOrNull())
    }

    private fun markSeen(lastSeen: Int?) {
        // Never lower the watermark: a downgrade or a hot fix with a smaller
        // version would otherwise replay notes the user already dismissed.
        if (lastSeen == null || lastSeen < currentVersionCode) {
            store.putString(KEY_LAST_SEEN, currentVersionCode.toString())
        }
    }

    private class Item(val id: String, val sinceVersionCode: Int)

    companion object {
        /** Android's DataStore key, so a restored backup agrees with it. */
        const val KEY_LAST_SEEN = "last_seen_app_version_code"

        /** versionCode of the 0.2.3 release line this app tracks. */
        const val CURRENT_VERSION_CODE = 9

        private val REGISTRY = listOf(
            Item("nostr-backup", 4),
            Item("aurora-json-import", 5),
            Item("release-0.2.1", 7),
            Item("release-0.2.2", 8),
            Item("release-0.2.3", 9),
        )
    }
}
