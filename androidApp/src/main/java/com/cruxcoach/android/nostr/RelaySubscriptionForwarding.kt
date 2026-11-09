package com.cruxcoach.android.nostr

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The frames of one subscription from all its relays, events and EOSE alike, in the order each relay sent them.
 *
 * Frames wait here until the subscription takes them, also those that come before it starts. When [maxChars] are
 * waiting (each frame counts its length plus [FRAME_OVERHEAD], so a flood of tiny frames is bounded too), a relay's
 * reader thread waits in [offer] for room. Its socket is not read meanwhile: a burst from several relays is slowed
 * down, not cut, and memory stays bounded.
 *
 * Relays wait at most [maxWaitMs], counted from when the queue filled up until it is half empty again. A subscription
 * that does not keep up for that long (its collector waits for something else, such as a board sync) would otherwise
 * hold up the relay's other subscriptions, its OKs and its pings. Its frames are then dropped at once, with a log line,
 * until it has taken half of the queue. Each relay's first EOSE always gets through and takes no room, so a query
 * ends with its relays rather than at its timeout. After [close], everything is dropped.
 */
internal class RelayFrameQueue(
    private val maxWaitMs: Long,
    private val maxChars: Int = MAX_QUEUED_CHARS,
) {
    private val channel = Channel<String>(Channel.UNLIMITED)
    // Fair, so that a large frame waiting for room is not overtaken by smaller ones from other relays.
    private val room = Semaphore(maxChars, true)
    private val eoseFrom: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** When relays began to wait for room (System.nanoTime); 0 while the queue is less than half full. */
    private val fullSince = AtomicLong(0)
    @Volatile
    private var dropping = false
    private val dropped = AtomicInteger()

    private fun weight(frame: String) =
        if (eoseRelayOf(frame) != null) 0 else (frame.length + FRAME_OVERHEAD).coerceAtMost(maxChars)

    /**
     * Called on a relay's reader thread; waits while the queue is full. False when the frame was dropped: the
     * subscription is closed or does not keep up, or the frame is a relay's second EOSE.
     */
    fun offer(frame: String): Boolean {
        val eoseRelay = eoseRelayOf(frame)
        if (eoseRelay != null) return eoseFrom.add(eoseRelay) && channel.trySend(frame).isSuccess
        if (dropping) return drop()
        val weight = weight(frame)
        if (!takeRoom(weight)) return drop()
        if (channel.trySend(frame).isSuccess) return true
        room.release(weight)
        return false
    }

    private fun takeRoom(weight: Int): Boolean = try {
        // The timed form keeps the order of waiting relays; the untimed one would jump the queue.
        room.tryAcquire(weight, 0, TimeUnit.NANOSECONDS) || run {
            val now = System.nanoTime()
            val since = fullSince.updateAndGet { if (it == 0L) now else it }
            val left = since + TimeUnit.MILLISECONDS.toNanos(maxWaitMs) - now
            left > 0 && room.tryAcquire(weight, left, TimeUnit.NANOSECONDS)
        }
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    private fun drop(): Boolean {
        if (!dropping) {
            dropping = true
            Log.w(TAG, "event=relay_frames_dropped reason=subscription-not-keeping-up waitedMs=$maxWaitMs")
        }
        dropped.incrementAndGet()
        return false
    }

    /** For the subscription's one collector; each frame taken makes room for the next. */
    val frames: Flow<String> = channel.receiveAsFlow().onEach { frame ->
        room.release(weight(frame))
        if (room.availablePermits() >= maxChars / 2 && (fullSince.get() != 0L || dropping)) {
            fullSince.set(0)
            if (dropping) {
                dropping = false
                Log.w(TAG, "event=relay_frames_resumed dropped=${dropped.getAndSet(0)}")
            }
        }
    }

    /** Drops what is left and frees a relay waiting in [offer]. */
    fun close() {
        channel.cancel()
        room.release(maxChars)
    }

    companion object {
        /** About 8 MB of JSON; a single frame is at most [RelayInputGuard.MAX_BYTES]. */
        const val MAX_QUEUED_CHARS = 8 * 1024 * 1024
        /** What a frame costs beyond its characters (the string and its place in the queue). */
        const val FRAME_OVERHEAD = 256
        /** A live subscription's collector may stop for minutes (the community sync waits out a board sync). */
        const val LIVE_WAIT_MS = 5_000L
        /** A one-shot query's collector only gathers events: it falls behind only while it checks a large burst. */
        const val QUERY_WAIT_MS = 20_000L
        private const val TAG = "RelayFrameQueue"
    }
}

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
