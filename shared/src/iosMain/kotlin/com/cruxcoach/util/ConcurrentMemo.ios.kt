package com.cruxcoach.util

import platform.Foundation.NSLock

// Same contract as the JVM's ConcurrentMap.getOrPut: compute outside the lock,
// and the first value stored for a key wins.
actual class ConcurrentMemo<K : Any, V : Any> actual constructor() {
    private val lock = NSLock()
    private val map = HashMap<K, V>()

    actual fun getOrPut(key: K, compute: () -> V): V {
        lock.lock()
        val cached = try { map[key] } finally { lock.unlock() }
        if (cached != null) return cached
        val value = compute()
        lock.lock()
        try {
            return map.getOrPut(key) { value }
        } finally {
            lock.unlock()
        }
    }
}
