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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val _connectedRelayCount = MutableStateFlow(0)
    /** Transport health only; event truth still comes from the signed events. */
    val connectedRelayCount: StateFlow<Int> = _connectedRelayCount.asStateFlow()

    private fun updateConnectedRelayCount() {
        _connectedRelayCount.value = connections.values.count { it.isConnected }
    }
    private val seenEventIds: MutableMap<String, Boolean> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Boolean>(100, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>) = size > MAX_SEEN_IDS
        }
    )

    /**
     * The resolved relay list, pushed here by
     * [com.cruxcoach.android.nostr.relaydiscovery.RelayListResolver]. Starts as
     * `NostrConfig.DEFAULT_RELAYS` so the pool is usable before discovery runs
     * (or at all, if the feature flag is off). Never null, never empty.
     *
     * Reads and writes are volatile only — updates are single-writer (the
     * resolver) and consumers read a snapshot each op, so no lock is needed.
     */
    @Volatile
    private var resolvedRelays: List<RelayConfig> = NostrConfig.DEFAULT_RELAYS

    private inner class RelayConnection(val url: String) {
        // @Volatile: ws is written from the OkHttp WebSocketListener dispatcher
        // (onFailure / onClosed) and read from sender coroutines — visibility
        // must be guaranteed to avoid leaked/stale WebSocket references.
        @Volatile
        private var ws: WebSocket? = null
        private val pendingOks = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
        private val subscriptionFlows = ConcurrentHashMap<String, MutableSharedFlow<String>>()
        private val activeFilters = ConcurrentHashMap<String, String>()
        private val connectLock = Mutex()

        @Volatile
        private var connected = false
        val isConnected: Boolean get() = connected && ws != null

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
                    updateConnectedRelayCount()
                    deferred.complete(Unit)
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    connected = false
                    this@RelayConnection.ws = null
                    updateConnectedRelayCount()
                    deferred.completeExceptionally(t)
                    failAllPending(t)
                    scheduleReconnect()
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    connected = false
                    this@RelayConnection.ws = null
                    updateConnectedRelayCount()
                    // Fail pending OKs immediately instead of waiting for each
                    // publisher's RELAY_TIMEOUT_MS — otherwise every in-flight
                    // sendEvent hangs and the pendingOks map piles up while
                    // reconnect is pending.
                    failAllPending(Exception("Relay $url closed: $code $reason"))
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
                        // NIP-01 OK frame format: ["OK", event_id, accepted,
                        // <reason>]. Capture the optional reason so we can
                        // distinguish "rejected with reason" from "silently
                        // dropped". Some relays additionally return OK true
                        // with an informational reason ("duplicate:",
                        // "ephemeral:") — keep that visible at debug level
                        // so a 1-of-3-accepted result has a per-relay
                        // explanation in logcat instead of looking like an
                        // app-side bug.
                        val reason = arr.getOrNull(3)?.let { el ->
                            runCatching { el.jsonPrimitive.content }.getOrNull()
                        }.orEmpty()
                        if (!accepted) {
                            Log.w(
                                TAG,
                                "event=relay_ok_false url=$url eventIdPrefix=${eventId.take(8)} " +
                                    "reason=${if (reason.isEmpty()) "<none>" else reason}",
                            )
                        } else if (reason.isNotEmpty()) {
                            Log.d(
                                TAG,
                                "event=relay_ok_true_with_reason url=$url " +
                                    "eventIdPrefix=${eventId.take(8)} reason=$reason",
                            )
                        }
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
            if (ws?.send(msg) != true) {
                // ws was null or send queued failed — distinguishable from
                // a relay-side OK false in logcat (no `relay_ok_false` will
                // follow this line).
                Log.w(
                    TAG,
                    "event=relay_send_failed url=$url eventIdPrefix=${eventId.take(8)} reason=ws-not-ready",
                )
                pendingOks.remove(eventId)
                return false
            }
            return try {
                withTimeout(NostrConfig.RELAY_TIMEOUT_MS) { deferred.await() }
            } catch (e: Exception) {
                pendingOks.remove(eventId)
                Log.w(
                    TAG,
                    "event=relay_send_timeout url=$url eventIdPrefix=${eventId.take(8)} " +
                        "reason=${e.javaClass.simpleName}",
                )
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
                    Log.w(TAG, "Gave up reconnecting to $url after $attempts attempts; scheduling slow-retry every ${NostrConfig.RECONNECT_SLOW_RETRY_MS}ms")
                    // Slow-retry backstop: without this, a stable
                    // network that just happens to be racing the
                    // initial reconnect window leaves the pool dead
                    // forever (no further OS network-event would fire,
                    // no in-app trigger ever calls reconnectAll for
                    // this relay specifically). Loop here at a long
                    // interval so the connection eventually heals
                    // even with no external prompt — bounded battery
                    // cost, very high reliability win for the
                    // community-climb live-sub.
                    while (isActive && !connected) {
                        delay(NostrConfig.RECONNECT_SLOW_RETRY_MS)
                        try {
                            openWebSocket()
                            activeFilters.forEach { (subId, filter) ->
                                ws?.send("[\"REQ\",\"$subId\",$filter]")
                            }
                            reconnectExhausted = false
                            Log.i(TAG, "Slow-retry reconnect to $url succeeded")
                            return@launch
                        } catch (e: Exception) {
                            Log.d(TAG, "Slow-retry to $url failed; will try again in ${NostrConfig.RECONNECT_SLOW_RETRY_MS}ms")
                        }
                    }
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
            updateConnectedRelayCount()
            ws?.close(1000, "shutdown")
            ws = null
            failAllPending(Exception("Connection closed"))
            subscriptionFlows.clear()
            activeFilters.clear()
        }
    }

    private fun getOrCreateConnection(url: String): RelayConnection {
        // computeIfAbsent is atomic on ConcurrentHashMap; getOrPut is not
        // (it is get() ?: put(), which races and leaks duplicate connections).
        return connections.computeIfAbsent(url) { RelayConnection(it) }
    }

    /**
     * Relays marked write-enabled in the current resolved list. Safe to call
     * on any thread — reads a `@Volatile` snapshot.
     */
    fun writeRelays(): List<RelayConfig> = resolvedRelays.filter { it.write }

    /** Relays marked read-enabled in the current resolved list. */
    fun readRelays(): List<RelayConfig> = resolvedRelays.filter { it.read }

    /**
     * Called by the relay-list resolver when the resolved set changes.
     *
     * Dropped URLs have their WebSocket connections hard-closed (any in-flight
     * subscriptions on them are dropped). New URLs are connected lazily on the
     * next [sendEvent]/[subscribe]. Stable URLs keep their existing connection
     * and subscription ids.
     *
     * Idempotent: calling with the same content as the current resolved list
     * is a no-op (the resolver also skip-checks, but the pool defends in
     * depth in case other callers reach this method).
     */
    fun onRelaysChanged(resolved: List<RelayConfig>) {
        if (resolved.isEmpty()) {
            Log.w(TAG, "onRelaysChanged called with empty list — ignored")
            return
        }
        val previousUrls = resolvedRelays.map { it.url }.toSet()
        val newUrls = resolved.map { it.url }.toSet()
        val dropped = previousUrls - newUrls
        resolvedRelays = resolved
        if (dropped.isNotEmpty()) {
            dropped.forEach { url ->
                connections.remove(url)?.close()
            }
        }
    }

    suspend fun sendEvent(event: Event): Boolean {
        val (_, accepted) = sendEventWithStats(event)
        return accepted > 0
    }

    /**
     * Publish [event] to every configured write relay and report
     * (attempted, accepted). Used by delete flows that need to tell
     * the user how many relays actually acknowledged — a single
     * Boolean "did any accept" is fine for fire-and-forget writes but
     * hides a 1-of-5 outcome from the user when they explicitly asked
     * for full removal.
     */
    suspend fun sendEventWithStats(event: Event): Pair<Int, Int> {
        val eventJson = event.toJson()
        val eventId = extractEventId(eventJson) ?: return 0 to 0
        val relays = writeRelays()
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
        return relays.size to results.count { it }
    }

    /**
     * Fetch stored events even when another screen already observed their ids.
     *
     * The process-wide dedup cache protects long-lived live subscriptions from
     * duplicate delivery across relays. It must not turn an explicit historical
     * lookup into an empty result merely because discovery saw the event first.
     */
    fun fetchStored(filter: String): Flow<String> = subscribe(
        filter = filter,
        skipDedup = true,
        closeOnEose = true,
    )

    internal fun shouldDeliverEvent(eventId: String?, skipDedup: Boolean): Boolean =
        skipDedup || (eventId != null && seenEventIds.putIfAbsent(eventId, true) == null)

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
        val readRelays = readRelays()
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
                if (shouldDeliverEvent(extractEventId(eventJson), skipDedup)) trySend(eventJson)
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
        val readRelays = readRelays()
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
