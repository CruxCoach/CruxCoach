package com.cruxcoach.domain.relay

/**
 * What CruxRelay should do with a completed climb an official-app user sent in
 * PLAYLIST mode.
 */
enum class RelayAdmission {
    /** New climb — add it to the playlist AND flash the board as an "accepted"
     *  acknowledgement (the only success signal the write-only board gives the
     *  official-app user). */
    ACCEPT,

    /** A retry of a climb we already queued in this window — do NOT add again,
     *  but re-flash the board (throttled) so the user finally sees it worked. */
    DUPLICATE_RE_ACK,

    /** A retry, but a re-ACK flashed too recently — do nothing (avoid strobing
     *  the board on rapid re-taps). */
    DUPLICATE_SUPPRESSED,

    /** Distinct climbs arriving too fast from this client — dropped (still
     *  ATT-acked at the transport). Bounds flooding even with varied climbs. */
    RATE_LIMITED,

    /** The relay-added queue is at its global cap — dropped. */
    QUEUE_FULL,
}

/**
 * Anti-flood admission control for CruxRelay PLAYLIST mode.
 *
 * Problem: in PLAYLIST mode the diverted send leaves the board dark, and the
 * official app gives the user no application-level success (the board is
 * write-only, so the ATT ack only means "bytes reached the relay"). The user
 * perceives failure and re-taps Send, flooding the queue. Three layers, all
 * decided here so they stay unit-testable:
 *
 *  1. Idempotent add — key `(client, framesHash)`; a matching climb within
 *     [dedupWindowMs] is a retry, not a new entry.
 *  2. Board-side ACK — the caller flashes the board on [ACCEPT] and
 *     [DUPLICATE_RE_ACK]; re-ACK is throttled by [reAckThrottleMs].
 *  3. Per-client token bucket — only accepted adds consume a token; an empty
 *     bucket rate-limits even distinct climbs. Plus a global queue cap.
 *
 * Deterministic: the caller passes `nowMs` (no wall-clock read here), so tests
 * drive time explicitly. Not thread-safe — call from the relay's single
 * handler coroutine/thread.
 */
class CruxRelayAntiFlood(
    private val dedupWindowMs: Long = 10_000L,
    private val bucketCapacity: Int = 3,
    private val refillIntervalMs: Long = 2_000L,
    private val reAckThrottleMs: Long = 2_000L,
    private val globalQueueCap: Int = 50,
) {
    private class ClientState(now: Long, capacity: Int) {
        val seen = HashMap<Long, Long>()          // framesHash -> lastSeenMs
        var tokens: Double = capacity.toDouble()
        var lastRefillMs: Long = now
        var lastReAckMs: Long = Long.MIN_VALUE
    }

    private val clients = HashMap<String, ClientState>()

    /**
     * @param currentRelayQueueSize how many relay-added items are already in the
     *   queue (for the global cap); the caller owns the queue.
     */
    fun onClimb(
        client: String,
        framesHash: Long,
        nowMs: Long,
        currentRelayQueueSize: Int,
    ): RelayAdmission {
        val st = clients.getOrPut(client) { ClientState(nowMs, bucketCapacity) }
        refill(st, nowMs)
        pruneExpired(st, nowMs)

        val lastSeen = st.seen[framesHash]
        if (lastSeen != null && nowMs - lastSeen < dedupWindowMs) {
            st.seen[framesHash] = nowMs // sliding: each retry refreshes the window
            return if (nowMs - st.lastReAckMs >= reAckThrottleMs) {
                st.lastReAckMs = nowMs
                RelayAdmission.DUPLICATE_RE_ACK
            } else {
                RelayAdmission.DUPLICATE_SUPPRESSED
            }
        }

        if (currentRelayQueueSize >= globalQueueCap) return RelayAdmission.QUEUE_FULL
        if (st.tokens < 1.0) return RelayAdmission.RATE_LIMITED

        st.tokens -= 1.0
        st.seen[framesHash] = nowMs
        st.lastReAckMs = nowMs // an accept already flashes the board
        return RelayAdmission.ACCEPT
    }

    /** Forget a client that disconnected. */
    fun onClientGone(client: String) { clients.remove(client) }

    fun reset() { clients.clear() }

    private fun refill(st: ClientState, nowMs: Long) {
        val elapsed = nowMs - st.lastRefillMs
        if (elapsed <= 0) return
        st.tokens = minOf(
            bucketCapacity.toDouble(),
            st.tokens + elapsed.toDouble() / refillIntervalMs,
        )
        st.lastRefillMs = nowMs
    }

    private fun pruneExpired(st: ClientState, nowMs: Long) {
        if (st.seen.isEmpty()) return
        val it = st.seen.entries.iterator()
        while (it.hasNext()) {
            if (nowMs - it.next().value >= dedupWindowMs) it.remove()
        }
    }
}
