package com.cruxcoach.communityclient

/**
 * Platform seams of the community client. The protocol core only talks to these;
 * the Android app implements them with OkHttp and its Nostr signer (local key or Amber).
 */

/** Signs events with the user's key. */
interface CommunitySigner {
    /** Hex public key of the signing identity. */
    val pubkey: String

    /** Signs [event]; throws if the user or the external signer refuses. */
    suspend fun sign(event: UnsignedEvent): CommunityEvent
}

/** Checks an event's id and signature. */
fun interface EventVerifier {
    fun verify(event: CommunityEvent): Boolean
}

/** Callbacks of one WebSocket connection. Called from any thread. */
interface CommunitySocketListener {
    fun onOpen()
    fun onMessage(text: String)

    /** The connection is gone, by error or by close. Called once. */
    fun onClosed(error: Throwable?)
}

/** One WebSocket connection. */
interface CommunitySocket {
    /** Queues [text]; false if the socket is not open. */
    fun send(text: String): Boolean
    fun close()
}

/** Opens WebSocket connections. */
fun interface CommunitySocketFactory {
    fun open(url: String, listener: CommunitySocketListener): CommunitySocket
}

/** A plain HTTP response. */
data class HttpResult(val status: Int, val body: String)

/** Minimal HTTP for the community's REST endpoints. Throws on transport errors. */
interface CommunityHttp {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResult
    suspend fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): HttpResult
}

/** Wall clock in unix seconds; injectable for tests. */
fun interface UnixClock {
    fun nowSeconds(): Long
}

/** Hex SHA-256 of [bytes]. */
expect fun sha256Hex(bytes: ByteArray): String
