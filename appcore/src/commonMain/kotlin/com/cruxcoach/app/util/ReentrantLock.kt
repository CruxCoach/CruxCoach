package com.cruxcoach.app.util

/**
 * A lock the same thread may take again, like the JVM monitor behind
 * `synchronized` that the Android code these ports come from relies on
 * (a locked method calling another locked method).
 */
expect class ReentrantLock() {
    fun lock()
    fun unlock()
}

inline fun <T> withReentrantLock(lock: ReentrantLock, block: () -> T): T {
    lock.lock()
    try {
        return block()
    } finally {
        lock.unlock()
    }
}
