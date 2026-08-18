package com.cruxcoach.android.fips

/** Small, deliberately explicit JNI surface backed by the vendored FIPS revision. */
internal object NativeFips {
    init { System.loadLibrary("cruxcoach_fips") }

    /**
     * [controlDirectory] is an app-private directory the native node binds its
     * FIPS control socket in. That socket is how the bridge reads the peer and
     * session tables now that upstream made the in-process read handle
     * crate-private; it is never exposed outside the app sandbox.
     */
    external fun start(secret: String, maxDirectConnections: Int, controlDirectory: String): Boolean
    external fun stop()
    external fun isAlive(): Boolean
    external fun npub(): String
    external fun sendBatch(destinationNpub: String, packedFrames: ByteArray): Boolean
    external fun receive(timeoutMs: Int): ByteArray
    external fun peers(): String

    /**
     * Aggregate BLE transport outcome counters, one `instance\tcounter\tvalue`
     * line each. This is the FIPS-side diagnostic layer only: upstream deleted
     * the per-peer attempt ring, and per-peer attempts are traced by
     * [FipsBleRadio] instead. Never infer per-peer history from these numbers.
     */
    external fun bleTransportCounters(): String

    external fun bleBridgeNew(radio: Any): Long
    external fun bleBridgeFree(handle: Long)
    external fun bleDeliverInbound(handle: Long, address: String, sendMtu: Int, receiveMtu: Int): Long
    external fun bleDeliverConnectResult(handle: Long, connectId: Long, ok: Boolean,
        address: String, sendMtu: Int, receiveMtu: Int): Long
    external fun bleDeliverScan(handle: Long, address: String, psm: Int, rssi: Int)
    external fun bleChannelDeliverRecv(handle: Long, channelId: Long, data: ByteArray, len: Int): Boolean
    external fun bleChannelClosed(handle: Long, channelId: Long)
    external fun bleChannelNextSend(handle: Long, channelId: Long, out: ByteArray, timeoutMs: Int): Int
}
