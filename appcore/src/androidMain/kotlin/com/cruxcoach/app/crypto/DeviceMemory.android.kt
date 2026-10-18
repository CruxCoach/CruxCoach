package com.cruxcoach.app.crypto

internal actual object DeviceMemory {
    /** The JVM (the unit-test host) limits the heap, not the machine. */
    actual fun physicalBytes(): Long = Runtime.getRuntime().maxMemory()
}
