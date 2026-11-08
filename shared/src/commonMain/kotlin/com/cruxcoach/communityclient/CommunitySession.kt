package com.cruxcoach.communityclient

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/** Connection state of the community relay session. */
sealed interface SessionState {
    data object Idle : SessionState
    data object Connecting : SessionState
    data object Authenticating : SessionState

    /** Authenticated; subscriptions and publishing work. */
    data object Ready : SessionState

    /** The relay knows the key but it is not a member of the community. No retry. */
    data class NotMember(val message: String) : SessionState

    /** Lost or failed; [retryInSeconds] until the next attempt, null if stopped. */
    data class Offline(val reason: String, val retryInSeconds: Long?) : SessionState
}

/** What a subscription delivers. */
sealed interface SubscriptionItem {
    data class Event(val event: CommunityEvent) : SubscriptionItem

    /** End of stored events: everything after this is live. */
    data object Eose : SubscriptionItem

    /** The relay ended the subscription. */
    data class Closed(val reason: String) : SubscriptionItem
}

/** Outcome of publishing one event. */
data class PublishResult(val event: CommunityEvent, val accepted: Boolean, val message: String)

class CommunityException(message: String) : Exception(message)

/**
 * One authenticated WebSocket session with the community relay.
 *
 * - Answers the relay's NIP-42 challenge with a signed kind:22242 before anything else.
 * - Keeps subscriptions across reconnects (the REQ is sent again after re-auth).
 * - Verifies every event's signature, and that relay-only kinds come from the relay key.
 *
 * All state lives in one actor coroutine; socket callbacks only post messages to it.
 */
class CommunitySession(
    private val endpoint: CommunityEndpoint,
    private val socketFactory: CommunitySocketFactory,
    private val signer: CommunitySigner,
    private val verifier: EventVerifier,
    private val clock: UnixClock,
    private val scope: CoroutineScope,
    private val retryDelaysSeconds: List<Long> = listOf(1, 2, 5, 10, 30, 60),
    private val authChallengeTimeoutMs: Long = 10_000,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /**
     * The relay's own key (NIP-11 `self`). Relay-signed kinds count only when this key
     * signed them; while it is unknown they are dropped.
     */
    @kotlin.concurrent.Volatile
    var relayPubkey: String? = null

    private sealed interface Msg {
        data object Start : Msg
        data object Stop : Msg
        data class Opened(val gen: Int) : Msg
        data class Text(val gen: Int, val text: String) : Msg
        data class Lost(val gen: Int, val reason: String) : Msg
        data class AuthSigned(val gen: Int, val event: CommunityEvent) : Msg
        data class AuthSignFailed(val gen: Int, val reason: String) : Msg
        data class AuthTimeout(val gen: Int) : Msg
        data class Retry(val gen: Int) : Msg
        data class Subscribe(val id: String, val filters: List<RelayFilter>, val out: (SubscriptionItem) -> Unit) : Msg
        data class Unsubscribe(val id: String) : Msg
        data class Publish(val event: CommunityEvent, val result: CompletableDeferred<PublishResult>) : Msg
    }

    private class Sub(val filters: List<RelayFilter>, val out: (SubscriptionItem) -> Unit)

    private val inbox = Channel<Msg>(Channel.UNLIMITED)
    private var actor: Job? = null

    // Actor-owned state.
    private var socket: CommunitySocket? = null
    private var generation = 0
    private var running = false
    private var attempt = 0
    private var authEventId: String? = null
    private val subs = LinkedHashMap<String, Sub>()
    private val pendingOk = HashMap<String, CompletableDeferred<PublishResult>>()
    private val pendingEvents = HashMap<String, CommunityEvent>()
    private var timerJob: Job? = null

    /** Connects and keeps the session up until [stop]. */
    fun start() {
        ensureActor()
        inbox.trySend(Msg.Start)
    }

    /** Disconnects; [start] connects again and resends the open subscriptions. */
    fun stop() {
        inbox.trySend(Msg.Stop)
    }

    /** Disconnects for good and ends the session's coroutine. The session cannot be started again. */
    fun close() {
        inbox.trySend(Msg.Stop)
        inbox.close()
    }

    /**
     * Subscribes with [filters]. Stored events come first, then [SubscriptionItem.Eose],
     * then live events until the collector stops or the relay closes the subscription.
     */
    fun subscribe(filters: List<RelayFilter>): Flow<SubscriptionItem> = callbackFlow {
        ensureActor()
        val id = newSubscriptionId()
        inbox.trySend(Msg.Subscribe(id, filters) { trySend(it) })
        awaitClose { inbox.trySend(Msg.Unsubscribe(id)) }
    }.buffer(Channel.UNLIMITED)

    /**
     * Stored events matching [filters], up to EOSE. Throws [CommunityException] if the relay
     * closes the request or does not finish it in time (a plain exception, not a cancellation,
     * so callers running queries side by side see it as a failure).
     */
    suspend fun query(filters: List<RelayFilter>, timeoutMs: Long = 20_000): List<CommunityEvent> {
        val events = ArrayList<CommunityEvent>()
        withTimeoutOrNull(timeoutMs) {
            subscribe(filters).first { item ->
                when (item) {
                    is SubscriptionItem.Event -> { events += item.event; false }
                    SubscriptionItem.Eose -> true
                    is SubscriptionItem.Closed -> throw CommunityException("closed: ${item.reason}")
                }
            }
        } ?: throw CommunityException("timeout: no answer from the relay")
        return events
    }

    /** Signs and sends [event]; waits for the relay's OK. */
    suspend fun publish(event: UnsignedEvent, timeoutMs: Long = 15_000): PublishResult {
        ensureActor()
        val signed = signer.sign(event)
        val result = CompletableDeferred<PublishResult>()
        inbox.trySend(Msg.Publish(signed, result))
        return withTimeoutOrNull(timeoutMs) { result.await() }
            ?: PublishResult(signed, false, "timeout: no answer from the relay")
    }

    private fun ensureActor() {
        if (actor != null) return
        actor = scope.launch {
            for (msg in inbox) handle(msg)
        }
    }

    private fun handle(msg: Msg) {
        when (msg) {
            Msg.Start -> if (!running) {
                running = true
                attempt = 0
                connect()
            }
            Msg.Stop -> {
                running = false
                disconnect("stopped")
                _state.value = SessionState.Idle
            }
            is Msg.Opened -> if (msg.gen == generation) {
                _state.value = SessionState.Authenticating
                val gen = generation
                timerJob?.cancel()
                timerJob = scope.launch {
                    delay(authChallengeTimeoutMs)
                    inbox.trySend(Msg.AuthTimeout(gen))
                }
            }
            is Msg.Text -> if (msg.gen == generation) onFrame(msg.text)
            is Msg.Lost -> if (msg.gen == generation) onLost(msg.reason)
            is Msg.AuthSigned -> if (msg.gen == generation && _state.value == SessionState.Authenticating) {
                authEventId = msg.event.id
                socket?.send(ClientFrames.auth(msg.event))
            }
            is Msg.AuthSignFailed -> if (msg.gen == generation && _state.value == SessionState.Authenticating) {
                // Close this socket for good, so its later close is not counted as a second loss.
                disconnect(msg.reason)
                scheduleRetry(msg.reason)
            }
            is Msg.AuthTimeout -> if (msg.gen == generation && _state.value == SessionState.Authenticating) {
                disconnect("no authentication from the relay")
                scheduleRetry("no authentication from the relay")
            }
            is Msg.Retry -> if (msg.gen == generation && running) connect()
            is Msg.Subscribe -> {
                subs[msg.id] = Sub(msg.filters, msg.out)
                if (_state.value == SessionState.Ready) socket?.send(ClientFrames.req(msg.id, msg.filters))
            }
            is Msg.Unsubscribe -> if (subs.remove(msg.id) != null && _state.value == SessionState.Ready) {
                socket?.send(ClientFrames.close(msg.id))
            }
            is Msg.Publish -> {
                if (_state.value != SessionState.Ready || socket?.send(ClientFrames.event(msg.event)) != true) {
                    msg.result.complete(PublishResult(msg.event, false, "offline: not connected to the community"))
                } else {
                    pendingOk[msg.event.id] = msg.result
                    pendingEvents[msg.event.id] = msg.event
                }
            }
        }
    }

    private fun connect() {
        generation++
        val gen = generation
        authEventId = null
        _state.value = SessionState.Connecting
        socket = try {
            socketFactory.open(endpoint.relayUrl, object : CommunitySocketListener {
                override fun onOpen() { inbox.trySend(Msg.Opened(gen)) }
                override fun onMessage(text: String) { inbox.trySend(Msg.Text(gen, text)) }
                override fun onClosed(error: Throwable?) {
                    inbox.trySend(Msg.Lost(gen, error?.message ?: "connection closed"))
                }
            })
        } catch (e: Exception) {
            inbox.trySend(Msg.Lost(gen, e.message ?: "cannot connect"))
            null
        }
    }

    private fun disconnect(reason: String) {
        generation++
        timerJob?.cancel()
        socket?.close()
        socket = null
        // Pending events failed with the connection, whatever ended it.
        failPending("offline: $reason")
    }

    private fun onLost(reason: String) {
        socket = null
        timerJob?.cancel()
        failPending("offline: $reason")
        if (_state.value is SessionState.NotMember) return
        if (running) scheduleRetry(reason) else _state.value = SessionState.Idle
    }

    private fun scheduleRetry(reason: String) {
        val delaySeconds = retryDelaysSeconds[minOf(attempt, retryDelaysSeconds.lastIndex)]
        attempt++
        _state.value = SessionState.Offline(reason, delaySeconds)
        val gen = generation
        timerJob?.cancel()
        timerJob = scope.launch {
            delay(delaySeconds * 1000)
            inbox.trySend(Msg.Retry(gen))
        }
    }

    private fun failPending(reason: String) {
        pendingOk.forEach { (id, deferred) ->
            pendingEvents[id]?.let { deferred.complete(PublishResult(it, false, reason)) }
        }
        pendingOk.clear()
        pendingEvents.clear()
    }

    private fun onFrame(text: String) {
        when (val frame = RelayFrame.parse(text) ?: return) {
            is RelayFrame.Auth -> {
                if (_state.value != SessionState.Authenticating && _state.value != SessionState.Ready) return
                val gen = generation
                val template = CommunityEventTemplates.auth(endpoint.relayUrl, frame.challenge, clock.nowSeconds())
                scope.launch {
                    val signed = runCatching { signer.sign(template) }.getOrNull()
                    if (signed != null) inbox.trySend(Msg.AuthSigned(gen, signed))
                    else inbox.trySend(Msg.AuthSignFailed(gen, "signing the sign-in was refused"))
                }
            }
            is RelayFrame.Ok -> {
                if (frame.eventId == authEventId) {
                    authEventId = null
                    timerJob?.cancel()
                    when {
                        frame.accepted -> onAuthenticated()
                        relayReasonPrefix(frame.message) == "restricted" -> {
                            running = false
                            _state.value = SessionState.NotMember(frame.message)
                            disconnect(frame.message)
                        }
                        else -> {
                            disconnect(frame.message)
                            scheduleRetry(frame.message.ifBlank { "sign-in refused" })
                        }
                    }
                    return
                }
                val deferred = pendingOk.remove(frame.eventId) ?: return
                val event = pendingEvents.remove(frame.eventId) ?: return
                deferred.complete(PublishResult(event, frame.accepted, frame.message))
            }
            is RelayFrame.Event -> {
                val sub = subs[frame.subscriptionId] ?: return
                if (!accepts(frame.event)) return
                sub.out(SubscriptionItem.Event(frame.event))
            }
            is RelayFrame.Eose -> subs[frame.subscriptionId]?.out?.invoke(SubscriptionItem.Eose)
            is RelayFrame.Closed -> subs.remove(frame.subscriptionId)?.out?.invoke(SubscriptionItem.Closed(frame.reason))
            is RelayFrame.Notice -> Unit
        }
    }

    private fun onAuthenticated() {
        attempt = 0
        _state.value = SessionState.Ready
        subs.forEach { (id, sub) -> socket?.send(ClientFrames.req(id, sub.filters)) }
    }

    private fun accepts(event: CommunityEvent): Boolean {
        if (event.kind in CommunityKinds.RELAY_SIGNED && event.pubkey != relayPubkey) return false
        return runCatching { verifier.verify(event) }.getOrDefault(false)
    }

    private fun newSubscriptionId(): String =
        "c" + Random.nextLong().toULong().toString(16) + Random.nextInt().toUInt().toString(16)
}
