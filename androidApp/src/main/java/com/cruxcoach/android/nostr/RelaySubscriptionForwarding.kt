package com.cruxcoach.android.nostr

import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Marks EOSE on a subscription's frame stream. Never valid JSON, so never an event. */
private const val EOSE_PREFIX = "__EOSE__ "

internal fun eoseFrame(relayUrl: String): String = EOSE_PREFIX + relayUrl

internal fun eoseRelayOf(frame: String): String? =
    if (frame.startsWith(EOSE_PREFIX)) frame.substring(EOSE_PREFIX.length) else null

/**
 * Tracks which relays of a one-shot query are done (sent EOSE or failed).
 *
 * Each relay counts once. A relay that reconnects sends the REQ again and then a
 * second EOSE; that must not close the query while other relays still stream
 * stored events.
 */
internal class EoseTracker(relayUrls: Collection<String>) {
    private val pending: MutableSet<String> =
        ConcurrentHashMap.newKeySet<String>().apply { addAll(relayUrls) }
    private val remaining = AtomicInteger(pending.size)

    /** True exactly once: when the last pending relay is marked done. */
    fun markDone(relayUrl: String): Boolean =
        pending.remove(relayUrl) && remaining.decrementAndGet() == 0
}

/**
 * Copies accepted relay frames from [frames] into [out].
 *
 * With an [eose] tracker, closes [out] once every relay is done. Frames that
 * arrive after that (live events after the last EOSE, or events racing the
 * close from the subscribe loop) are dropped. Sending them used to throw
 * [ClosedSendChannelException] and crash the app (0.2.4 crash report).
 */
internal suspend fun forwardRelayFrames(
    frames: Flow<String>,
    out: SendChannel<String>,
    eose: EoseTracker?,
    accepts: (String) -> Boolean,
) {
    try {
        frames.collect { frame ->
            val eoseRelay = eoseRelayOf(frame)
            if (eoseRelay != null) {
                if (eose?.markDone(eoseRelay) == true) out.close()
            } else if (accepts(frame)) {
                out.send(frame)
            }
        }
    } catch (e: ClosedSendChannelException) {
        // The query is complete; a late frame has nowhere to go.
    }
}
