package com.cruxcoach.app.util

import platform.Foundation.NSRecursiveLock

actual class ReentrantLock actual constructor() {
    private val lock = NSRecursiveLock()
    actual fun lock() = lock.lock()
    actual fun unlock() = lock.unlock()
}
