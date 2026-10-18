package com.cruxcoach.app.crypto

/** Physical memory of the device, to bound scrypt before allocating (see [Nip49.maxLogNFor]). */
internal expect object DeviceMemory {
    fun physicalBytes(): Long
}
