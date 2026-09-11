package com.cruxcoach.android.sharing

import com.cruxcoach.db.secure.SecureDatabase
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max

/** Local time is not a trusted network oracle. Persisted high water prevents
 * rollback across restarts; monotonic elapsed time detects wall-clock changes
 * while running. A suspicious clock stops sharing until explicit recovery
 * first withdraws every local grant and invalidates received snapshots. */
class SharingPermissionClock(
    private val database: SecureDatabase,
    private val account: String,
    private val wall: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Anchor(val wall: Long, val elapsed: Long)
    private val anchor = AtomicReference<Anchor?>()
    private val q get() = database.snapshotQueries
    fun now(): Long {
        val wallNow = wall(); val elapsedNow = elapsed()
        val saved = q.selectClock(account).executeAsOneOrNull()
        val initial = Anchor(max(wallNow, saved?.high_water ?: 0), elapsedNow)
        anchor.compareAndSet(null, initial)
        val base = anchor.get()!!
        val delta = (elapsedNow - base.elapsed).coerceAtLeast(0)
        val expected = base.wall.coerceAtMost(Long.MAX_VALUE - delta) + delta
        val safe = max(expected, wallNow).coerceAtLeast(0)
        val suspicious = wallNow < 0 || elapsedNow < base.elapsed ||
            abs(wallNow.toDouble() - expected.toDouble()) > TOLERANCE_MILLIS ||
            saved?.high_water?.let { wallNow < it - TOLERANCE_MILLIS } == true
        q.observeClock(account, max(safe, saved?.high_water ?: 0), if (suspicious) 1 else 0)
        return q.selectClock(account).executeAsOne().high_water
    }
    fun healthy(): Boolean { now(); return q.selectClock(account).executeAsOne().locked == 0L }

    /** Only the repository's revoke-before-reset transaction may call this. */
    internal fun resetAfterWithdrawal() {
        val value = wall()
        require(value >= 0)
        q.resetClock(value, account)
        anchor.set(Anchor(value, elapsed()))
    }
    companion object { const val TOLERANCE_MILLIS = 300_000L }
}
