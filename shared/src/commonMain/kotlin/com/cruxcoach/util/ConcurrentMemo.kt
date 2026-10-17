package com.cruxcoach.util

/**
 * A thread-safe memo for a pure function: the first result for a key is kept.
 *
 * `java.util.concurrent` is JVM-only, so common code cannot name it. The
 * Android actual is the `ConcurrentHashMap.getOrPut` these sites used before
 * iOS became a target, so Android behaviour is unchanged.
 */
expect class ConcurrentMemo<K : Any, V : Any>() {
    fun getOrPut(key: K, compute: () -> V): V
}
