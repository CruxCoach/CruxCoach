package com.cruxcoach.util

import java.util.concurrent.ConcurrentHashMap

actual class ConcurrentMemo<K : Any, V : Any> actual constructor() {
    private val map = ConcurrentHashMap<K, V>()

    actual fun getOrPut(key: K, compute: () -> V): V = map.getOrPut(key, compute)
}
