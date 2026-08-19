package com.cruxcoach.android.fips

/**
 * Outbound L2CAP dial admission.
 *
 * The rule this exists to enforce: **a dial this app declined to start is not
 * a radio failure, and must never be reported as one.** The FIPS revision this
 * app now runs feeds every `bleDeliverConnectResult(.., false, ..)` into a
 * per-address exponential backoff (`PendingProbes`), doubling up to one
 * attempt every sixteen minutes. Answering "we were busy" with "the radio
 * could not reach that peer" therefore does not cost one dial — it silences a
 * reachable BoardCell member for a quarter of an hour.
 *
 * So contention defers and then dials for real. Only the radio decides
 * success or failure. When even deferral cannot happen the request is
 * abandoned with no answer at all, and FIPS' own `connect_timeout_ms` governs;
 * this is rare by construction, and traced when it happens.
 *
 * Concurrency is bounded because two Android L2CAP dials contend on one
 * controller and, on several OEM stacks, on each other. One is the default:
 * FIPS' scan/probe loop is itself sequential, so the only overlap comes from
 * the node layer dialling a specific address while a probe is in flight, and
 * nothing measured here argues for two.
 */
internal class FipsDialScheduler(
    private val maxConcurrentDials: Int = MAX_CONCURRENT_DIALS,
    private val maxDeferredDials: Int = MAX_DEFERRED_DIALS,
    private val deferBudgetMs: Long = DEFER_BUDGET_MS,
) {
    enum class Admission {
        /** Start the platform dial now, and report whatever the radio says. */
        DIAL,

        /** Wait and ask again; nothing is reported to FIPS meanwhile. */
        DEFER,

        /** Give up without answering. Never report this as a dial failure. */
        ABANDON,
    }

    private val active = mutableSetOf<Long>()
    private val deferredSince = mutableMapOf<Long, Long>()

    @Synchronized
    fun admit(connectId: Long, nowMs: Long): Admission {
        if (connectId in active) return Admission.DIAL
        if (active.size < maxConcurrentDials) {
            deferredSince.remove(connectId)
            active.add(connectId)
            return Admission.DIAL
        }
        val since = deferredSince[connectId]
        if (since != null) {
            // The budget is deliberately shorter than the native connect
            // timeout, so a deferral that never wins the slot still leaves
            // FIPS time to conclude the attempt on its own terms.
            if (nowMs - since >= deferBudgetMs) {
                deferredSince.remove(connectId)
                return Admission.ABANDON
            }
            return Admission.DEFER
        }
        if (deferredSince.size >= maxDeferredDials) return Admission.ABANDON
        deferredSince[connectId] = nowMs
        return Admission.DEFER
    }

    /** Release the slot once the platform dial has concluded, either way. */
    @Synchronized
    fun release(connectId: Long) {
        active.remove(connectId)
        deferredSince.remove(connectId)
    }

    @Synchronized
    fun activeDials(): Int = active.size

    @Synchronized
    fun deferredDials(): Int = deferredSince.size

    companion object {
        const val MAX_CONCURRENT_DIALS = 1
        const val MAX_DEFERRED_DIALS = 2
        const val DEFER_BUDGET_MS = 4_000L
        /** How long a deferred request sleeps between admission attempts. */
        const val DEFER_POLL_MS = 100L
    }
}

/**
 * Collapses one member's rotating BLE addresses into a single candidate
 * *before* the advertisement reaches FIPS.
 *
 * Android rotates its resolvable private address roughly every fifteen
 * minutes, and the two addresses of one rotation look like two peers to a
 * scanner. FIPS then dials both; the second is redundant, and every redundant
 * dial is another chance to earn a backoff on an address that was never a
 * distinct peer.
 *
 * The heuristic is the advertisement's own four-byte join-nonce tag, alongside
 * the realm and cell tags. The nonce rotates on its own thirty-second schedule
 * independently of the address, so two *different* addresses carrying the
 * *same* realm, cell and nonce tag within one window are, in practice, one
 * phone mid-rotation.
 *
 * That tag is a coalescing hint and nothing else. It is not authenticated, not
 * an identity, and grants nothing: admission remains FIPS peer authentication
 * plus the full CCJ1 realm and cell proof, exactly as before. A four-byte
 * collision between two real members costs at most one supersede interval of
 * alternation, because a retained candidate that stops advertising is replaced
 * — which is also what makes a rotation converge on the live address.
 */
internal class FipsScanCoalescer(
    private val supersedeAfterMs: Long = SUPERSEDE_AFTER_MS,
    private val rssiMarginDb: Int = RSSI_MARGIN_DB,
    private val groupTtlMs: Long = GROUP_TTL_MS,
    private val maxGroups: Int = MAX_GROUPS,
) {
    sealed interface Decision {
        /** Hand this advertisement to FIPS. */
        data class Deliver(val reason: String) : Decision

        /** A redundant address of an already-offered member. */
        data class Suppress(val reason: String, val retained: String) : Decision
    }

    private class Retained(var address: String, var rssi: Int, var lastSeenMs: Long)

    // Insertion-ordered so eviction can drop the least recently refreshed.
    private val groups = LinkedHashMap<String, Retained>()

    @Synchronized
    fun offer(
        realmTag: String,
        cellTag: String,
        nonceTag: String,
        address: String,
        rssi: Int,
        nowMs: Long,
    ): Decision {
        expire(nowMs)
        val key = "$realmTag:$cellTag:$nonceTag"
        val retained = groups[key]
        if (retained == null) {
            groups[key] = Retained(address, rssi, nowMs)
            evictBeyondBound()
            return Decision.Deliver("first candidate for this member")
        }
        // Refresh recency so this group is the last one evicted.
        groups.remove(key)
        groups[key] = retained

        if (retained.address == address) {
            retained.rssi = rssi
            retained.lastSeenMs = nowMs
            return Decision.Deliver("retained candidate")
        }
        val quietFor = nowMs - retained.lastSeenMs
        if (quietFor >= supersedeAfterMs) {
            val previous = retained.address
            retained.address = address
            retained.rssi = rssi
            retained.lastSeenMs = nowMs
            return Decision.Deliver("supersedes $previous, quiet for ${quietFor}ms")
        }
        if (rssi >= retained.rssi + rssiMarginDb) {
            val previous = retained.address
            val gain = rssi - retained.rssi
            retained.address = address
            retained.rssi = rssi
            retained.lastSeenMs = nowMs
            return Decision.Deliver("supersedes $previous, ${gain}dB stronger")
        }
        return Decision.Suppress("rotated address of an already-offered member", retained.address)
    }

    /**
     * Stop retaining [address].
     *
     * Called when a dial to it concluded badly. Without this a candidate that
     * cannot be reached would keep masking the alternative addresses of the
     * same member for as long as it kept advertising.
     */
    @Synchronized
    fun forget(address: String) {
        groups.entries.removeAll { it.value.address == address }
    }

    @Synchronized
    fun clear() = groups.clear()

    @Synchronized
    fun trackedMembers(): Int = groups.size

    private fun expire(nowMs: Long) {
        groups.entries.removeAll { nowMs - it.value.lastSeenMs > groupTtlMs }
    }

    /** Applied after an insert, so the map is bounded at every observable
     * point rather than one entry past the bound. Insertion order is refresh
     * order, so the head is the least recently seen member. */
    private fun evictBeyondBound() {
        while (groups.size > maxGroups) {
            val oldest = groups.keys.firstOrNull() ?: break
            groups.remove(oldest)
        }
    }

    companion object {
        /** A rotated-away address stops advertising, so a short quiet period
         * is the rotation signal. Long enough to ride out a missed scan
         * window, short enough that a real rotation converges quickly. */
        const val SUPERSEDE_AFTER_MS = 3_000L
        /** Only a decisive RSSI difference overrides a live retained
         * candidate; small fluctuations must not flap the choice. */
        const val RSSI_MARGIN_DB = 12
        /** No longer than the join nonce is considered fresh anywhere else. */
        const val GROUP_TTL_MS = DirectJoinProof.MAX_AGE_MS
        /** A BoardCell is a handful of phones; the bound keeps a noisy or
         * hostile environment from growing this map. */
        const val MAX_GROUPS = 16
    }
}
