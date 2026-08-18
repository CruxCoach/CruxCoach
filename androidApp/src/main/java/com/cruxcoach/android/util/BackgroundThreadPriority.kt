package com.cruxcoach.android.util

import android.os.Process

/**
 * Run blocking catalogue/database work below interactive UI priority, restoring
 * the pooled coroutine thread before returning it to Dispatchers.IO.
 */
inline fun <T> withBackgroundThreadPriority(block: () -> T): T {
    val tid = Process.myTid()
    val previous = runCatching { Process.getThreadPriority(tid) }.getOrNull()
    runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
    return try {
        block()
    } finally {
        if (previous != null) runCatching { Process.setThreadPriority(tid, previous) }
    }
}
