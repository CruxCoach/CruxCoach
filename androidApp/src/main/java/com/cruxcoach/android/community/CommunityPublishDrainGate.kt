package com.cruxcoach.android.community

import kotlinx.coroutines.sync.Mutex

/** Process-wide exclusion for periodic and one-shot Nostr retry drains. */
internal object CommunityPublishDrainGate {
    private val mutex = Mutex()

    suspend fun <T> tryRun(block: suspend () -> T): T? {
        if (!mutex.tryLock()) return null
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}
