package com.cruxcoach.domain.relay

/**
 * Optional playlist-capture dedup for CruxRelay.
 *
 * The relay always passes climbs through to the board (last-write-wins), so
 * there is NO dark-board/retry-storm problem and NO acknowledgement flash. When
 * playlist capture is enabled, this decides whether a relayed climb should also
 * be appended to the playlist: a re-send of the same climb (same holds) from the
 * same client within a sliding window is treated as a duplicate and skipped, and
 * a global cap bounds a runaway session.
 *
 * Deterministic: the caller passes `nowMs` (no wall-clock read), so it is
 * unit-tested. Not thread-safe — call from the relay's single handler.
 */
class RelayCaptureDedup(
    private val windowMs: Long = 30_000L,
    private val globalCap: Int = 50,
) {
    private val seenByClient = HashMap<String, HashMap<Long, Long>>() // client -> (framesHash -> lastSeenMs)

    /**
     * @param currentCapturedCount how many relay-captured items are already in
     *   the playlist (for the global cap; the caller owns the playlist).
     * @return true if this climb should be appended, false if it is a re-send
     *   within the window or the cap is reached.
     */
    fun shouldCapture(
        client: String,
        framesHash: Long,
        nowMs: Long,
        currentCapturedCount: Int,
    ): Boolean {
        val seen = seenByClient.getOrPut(client) { HashMap() }
        prune(seen, nowMs)
        val last = seen[framesHash]
        // Window is measured from the last CAPTURE, not refreshed on skips: a
        // spurious re-send is ignored, but the same climb genuinely re-climbed
        // after the window still gets captured.
        if (last != null && nowMs - last < windowMs) return false
        if (currentCapturedCount >= globalCap) return false
        seen[framesHash] = nowMs
        return true
    }

    fun onClientGone(client: String) { seenByClient.remove(client) }

    fun reset() { seenByClient.clear() }

    private fun prune(seen: HashMap<Long, Long>, nowMs: Long) {
        if (seen.isEmpty()) return
        val it = seen.entries.iterator()
        while (it.hasNext()) if (nowMs - it.next().value >= windowMs) it.remove()
    }
}
