package com.cruxcoach.app.crypto

import platform.Foundation.NSProcessInfo

internal actual object DeviceMemory {
    actual fun physicalBytes(): Long = NSProcessInfo.processInfo.physicalMemory.toLong()
}
