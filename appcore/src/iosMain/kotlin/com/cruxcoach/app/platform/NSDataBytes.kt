package com.cruxcoach.app.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/** Copies the bytes. Empty-safe: `addressOf(0)` on an empty pinned array would crash. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = size.toULong()) }
}

/** Copies the bytes. Callers must bound the length first; an NSData above Int.MAX_VALUE is rejected. */
@OptIn(ExperimentalForeignApi::class)
fun NSData.toByteArray(): ByteArray {
    val total = length
    if (total == 0uL) return ByteArray(0)
    require(total <= Int.MAX_VALUE.toULong()) { "NSData too large for a ByteArray" }
    val source = bytes ?: return ByteArray(0)
    val out = ByteArray(total.toInt())
    out.usePinned { pinned -> memcpy(pinned.addressOf(0), source, total) }
    return out
}
