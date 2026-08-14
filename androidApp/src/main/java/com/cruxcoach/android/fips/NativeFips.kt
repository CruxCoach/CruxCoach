package com.cruxcoach.android.fips

/** Small, deliberately explicit JNI surface backed by the pinned FIPS revision. */
internal object NativeFips {
    init { System.loadLibrary("cruxcoach_fips") }

    external fun start(secret: String, maxDirectConnections: Int): Boolean
    external fun stop()
    external fun npub(): String
    external fun send(destinationNpub: String, bytes: ByteArray): Boolean
    external fun receive(timeoutMs: Int): ByteArray
    external fun peers(): String

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
