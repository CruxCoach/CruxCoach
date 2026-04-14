package com.cruxcoach.android.nostr

import android.util.Log
import com.cruxcoach.android.nostr.model.RelayConfig
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class NostrRelayPool @Inject constructor(
    @Named("nostr") private val okHttpClient: OkHttpClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = ConcurrentHashMap<String, RelayConnection>()
    private val seenEventIds: MutableMap<String, Boolean> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Boolean>(100, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>) = size > MAX_SEEN_IDS
        }
    )

    private inner class RelayConnection(val url: String) {
        private var ws: WebSocket? = null
        private val pendingOks = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
        private val subscriptionFlows = ConcurrentHashMap<String, MutableSharedFlow<String>>()
        private val activeFilters = ConcurrentHashMap<String, String>()
        private val connectLock = Mutex()

        @Volatile
        private var connected = false

        /** Guards against multiple concurrent reconnect loops for this relay. */
        private var reconnectJob: Job? = null

        /** Tracks whether reconnection attempts are exhausted. */
        @Volatile
        private var reconnectExhausted = false

        suspend fun ensureConnected() {
            if (connected && ws != null) return
            connectLock.withLock {
                if (connected && ws != null) return
                openWebSocket()
            }
        }

        private suspend fun openWebSocket() {
            val deferred = CompletableDeferred<Unit>()
            val request = Request.Builder().url(url).build()
            ws = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    connected = true
                    deferred.complete(Unit)
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    connected = false
                    this@RelayConnection.ws = null
                    deferred.completeExceptionally(t)
                    failAllPending(t)
                    scheduleReconnect()
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    connected = false
                    this@RelayConnection.ws = null
                    if (activeFilters.isNotEmpty()) scheduleReconnect()
                }
            })
            withTimeout(NostrConfig.RELAY_TIMEOUT_MS) { deferred.await() }
        }

        private fun handleMessage(text: String) {
            try {
                val arr = Json.parseToJsonElement(text).jsonArray
                when (arr[0].jsonPrimitive.content) {
                    "OK" -> {
                        val eventId = arr[1].jsonPrimitive.content
                        val accepted = arr[2].jsonPrimitive.boolean
                        pendingOks.remove(eventId)?.complete(accepted)
                    }
                    "EVENT" -> {
                        val subId = arr[1].jsonPrimitive.content
                        val eventJson = arr[2].toString()
                        val emitted = subscriptionFlows[subId]?.tryEmit(eventJson) ?: false
                        if (!emitted) Log.w(TAG, "EVENT not emitted from $url subId=$subId (flow missing or full)")
                    }
                    "EOSE" -> {
                        val subId = arr[1].jsonPrimitive.content
                        subscriptionFlows[subId]?.tryEmit(EOSE_SENTINEL)
                    }
                    "NOTICE" -> Log.w(TAG, "Relay $url: ${arr[1].jsonPrimitive.content}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse relay message from $url", e)
            }
        }

        suspend fun sendEvent(eventJson: String, eventId: String): Boolean {
            ensureConnected()
            val deferred = CompletableDeferred<Boolean>()
            pendingOks[eventId] = deferred
            val msg = "[\"EVENT\",$eventJson]"
            ws?.send(msg) ?: return false
            return try {
                withTimeout(NostrConfig.RELAY_TIMEOUT_MS) { deferred.await() }
            } catch (e: Exception) {
                pendingOks.remove(eventId)
                false
            }
        }

        fun addSubscription(subId: String, filter: String, flow: MutableSharedFlow<String>) {
            subscriptionFlows[subId] = flow
            activeFilters[subId] = filter
            val msg = "[\"REQ\",\"$subId\",$filter]"
            ws?.send(msg)
        }

        fun removeSubscription(subId: String) {
            subscriptionFlows.remove(subId)
            activeFilters.remove(subId)
            ws?.send("[\"CLOSE\",\"$subId\"]")
        }

        private fun failAllPending(t: Throwable) {
            pendingOks.values.forEach { it.completeExceptionally(t) }
            pendingOks.clear()
        }

        private fun scheduleReconnect() {
            // Guard: skip if a reconnect loop is already running or attempts are exhausted
            if (reconnectJob?.isActive == true) return
            if (reconnectExhausted) {
                Log.w(TAG, "Reconnect attempts exhausted for $url — call reconnectAll() to retry")
                return
            }

            reconnectJob = scope.launch {
                var currentDelay = NostrConfig.RECONNECT_DELAY_MS
                var attempts = 0

                while (isActive && !connected && attempts < NostrConfig.MAX_RECONNECT_ATTEMPTS) {
                    delay(currentDelay)
                    attempts++
                    try {
                        openWebSocket()
                        // Reconnected — re-subscribe all active filters
                        activeFilters.forEach { (subId, filter) ->
                            val msg = "[\"REQ\",\"$subId\",$filter]"
                            ws?.send(msg)
                        }
                        Log.i(TAG, "Reconnected to $url after $attempts attempt(s)")
                        return@launch
                    } catch (e: Exception) {
                        Log.w(TAG, "Reconnect to $url failed (attempt $attempts/${NostrConfig.MAX_RECONNECT_ATTEMPTS})", e)
                        currentDelay = (currentDelay * 2)
                            .coerceAtMost(NostrConfig.RECONNECT_MAX_DELAY_MS)
                    }
                }

                if (!connected) {
                    reconnectExhausted = true
                    Log.w(TAG, "Gave up reconnecting to $url after $attempts attempts")
                }
            }
        }

        /** Resets the exhaustion flag so [scheduleReconnect] can try again. */
        fun resetReconnect() {
            reconnectExhausted = false
        }

        fun close() {
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectExhausted = false
            connected = false
            ws?.close(1000, "shutdown")
            ws = null
            failAllPending(Exception("Connection closed"))
            subscriptionFlows.clear()
            activeFilters.clear()
        }
    }

    private fun getOrCreateConnection(url: String): RelayConnection {
        return connections.getOrPut(url) { RelayConnection(url) }
    }

    suspend fun sendEvent(event: Event): Boolean {
        val eventJson = event.toJson()
        val eventId = extractEventId(eventJson) ?: return false
        val relays = NostrConfig.DEFAULT_RELAYS.filter { it.write }
        val results = coroutineScope {
            relays.map { relay ->
                async {
                    try {
                        getOrCreateConnection(relay.url).sendEvent(eventJson, eventId)
                    } catch (e: Exception) {
                        Log.w(TAG, "Send to ${relay.url} failed", e)
                        false
                    }
                }
            }.map { it.await() }
        }
        return results.any { it }
    }

    /**
     * Subscribe to events matching [filter].
     *
     * @param skipDedup If true, bypass the [seenEventIds] cache. Use this for
     *   background poll workers that need to re-fetch events the foreground
     *   subscription may have already seen.
     * @param closeOnEose If true, the flow completes after all relays have sent
     *   EOSE (End of Stored Events), instead of staying open for real-time events.
     *   Use this for one-shot historical queries (e.g., poll workers).
     */
    fun subscribe(
        filter: String,
        skipDedup: Boolean = false,
        closeOnEose: Boolean = false
    ): Flow<String> = callbackFlow {
        val subId = java.util.UUID.randomUUID().toString()
        val readRelays = NostrConfig.DEFAULT_RELAYS.filter { it.read }
        val flow = MutableSharedFlow<String>(extraBufferCapacity = 256)
        val eoseCount = java.util.concurrent.atomic.AtomicInteger(0)
        val relayCount = readRelays.size

        val job = scope.launch {
            flow.collect { eventJson ->
                if (eventJson == EOSE_SENTINEL) {
                    if (closeOnEose && eoseCount.incrementAndGet() >= relayCount) {
                        channel.close()
                    }
                    return@collect
                }
                if (skipDedup) {
                    trySend(eventJson)
                } else {
                    val eventId = extractEventId(eventJson)
                    if (eventId != null && seenEventIds.putIfAbsent(eventId, true) == null) {
                        trySend(eventJson)
                    }
                }
            }
        }

        readRelays.forEach { relay ->
            try {
                val conn = getOrCreateConnection(relay.url)
                conn.ensureConnected()
                conn.addSubscription(subId, filter, flow)
            } catch (e: Exception) {
                Log.w(TAG, "Subscribe to ${relay.url} failed", e)
                // Count failed relays as "EOSE received" so we don't hang forever
                if (closeOnEose && eoseCount.incrementAndGet() >= relayCount) {
                    channel.close()
                }
            }
        }

        awaitClose {
            job.cancel()
            readRelays.forEach { relay ->
                connections[relay.url]?.removeSubscription(subId)
            }
        }
    }

    fun closeAll() {
        connections.values.forEach { it.close() }
        connections.clear()
    }

    /**
     * Resets all exhausted reconnection counters and triggers a fresh reconnect cycle
     * for any relay that still has active subscriptions. Call this when the user
     * explicitly wants to retry (e.g., after regaining network connectivity).
     */
    fun reconnectAll() {
        connections.values.forEach { conn ->
            conn.resetReconnect()
        }
        val readRelays = NostrConfig.DEFAULT_RELAYS.filter { it.read }
        readRelays.forEach { relay ->
            val conn = connections[relay.url] ?: return@forEach
            scope.launch {
                try {
                    conn.ensureConnected()
                } catch (e: Exception) {
                    Log.w(TAG, "Manual reconnect to ${relay.url} failed", e)
                }
            }
        }
    }

    private fun extractEventId(json: String): String? {
        return try {
            val obj = Json.parseToJsonElement(json).jsonObject
            obj["id"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "NostrRelayPool"
        private const val MAX_SEEN_IDS = 10_000
        /** Sentinel value emitted by EOSE handler — never valid JSON. */
        internal const val EOSE_SENTINEL = "__EOSE__"
    }
}
