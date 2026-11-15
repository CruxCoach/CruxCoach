package com.cruxcoach.android.spraywall

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The withdrawals [SprayWallPublisher] runs, per wall (a second try may start before the first ends), and which of
 * them delete the wall once it is withdrawn. Apart from the publisher, so it is testable on the JVM.
 */
class SprayRunningWithdrawals {
    private val running = HashMap<String, Int>()
    private val deleting = HashMap<String, Int>()
    private val _walls = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** The walls being withdrawn; true for one that is deleted once it is withdrawn. */
    val walls: StateFlow<Map<String, Boolean>> = _walls.asStateFlow()

    /** A withdrawal of [wallId] starts; [delete]: it deletes the wall when done. */
    fun started(wallId: String, delete: Boolean) = change(wallId, +1, delete)

    /** The withdrawal [started] with the same arguments ended, done or not. */
    fun ended(wallId: String, delete: Boolean) = change(wallId, -1, delete)

    private fun change(wallId: String, by: Int, delete: Boolean) = synchronized(running) {
        running.add(wallId, by)
        if (delete) deleting.add(wallId, by)
        _walls.value = running.keys.associateWith { it in deleting }
    }

    private fun HashMap<String, Int>.add(wallId: String, by: Int) {
        val n = (this[wallId] ?: 0) + by
        if (n > 0) this[wallId] = n else remove(wallId)
    }
}
