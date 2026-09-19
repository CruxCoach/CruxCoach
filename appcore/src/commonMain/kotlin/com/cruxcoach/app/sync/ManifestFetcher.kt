package com.cruxcoach.app.sync

import com.cruxcoach.app.platform.Hashing
import com.cruxcoach.app.platform.WallClock
import com.cruxcoach.app.platform.WebSocketConnector
import com.cruxcoach.app.platform.WebSocketHandle
import com.cruxcoach.app.platform.WebSocketListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.concurrent.Volatile
import kotlin.random.Random

/** Pre-parser bounds on a relay frame; same limits as Android's `RelayInputGuard`. */
internal object RelayInputGuard {
    const val MAX_BYTES = 1024 * 1024
    private const val MAX_DEPTH = 32

    fun accepts(text: String): Boolean {
        if (text.length > MAX_BYTES) return false
        var bytes = 0
        var depth = 0
        var quoted = false
        var escaped = false
        for (c in text) {
            bytes += when { c.code < 128 -> 1; c.code < 2048 -> 2; else -> 3 }
            if (bytes > MAX_BYTES) return false
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '[', '{' -> if (++depth > MAX_DEPTH) return false
                ']', '}' -> if (--depth < 0) return false
            }
        }
        return !quoted && depth == 0
    }
}

sealed class ManifestFetchResult {
    class Found(val manifest: CatalogueManifest) : ManifestFetchResult()

    /** No relay produced an acceptable manifest. [relayErrors] is false when every relay simply had none. */
    class NotFound(val relayErrors: Boolean) : ManifestFetchResult()
}

/**
 * Fetches a manifest over short-lived sockets, independent of the app's relay
 * pool. All relays are queried in parallel and the NIP-01-preferred answer
 * wins: taking the first success would pin the app to whichever relay missed
 * the last publish.
 */
class ManifestFetcher(
    private val webSockets: WebSocketConnector,
    private val hashing: Hashing,
    private val clock: WallClock,
    private val relayUrls: List<String> = CatalogueTrust.MANIFEST_RELAYS,
    private val passes: Int = MANIFEST_FETCH_PASSES,
) {
    private sealed class RelayOutcome {
        class Hit(val manifest: CatalogueManifest) : RelayOutcome()
        object Miss : RelayOutcome()
        object Error : RelayOutcome()
    }

    suspend fun fetch(dTag: String): ManifestFetchResult {
        var sawError = false
        for (pass in 0 until passes) {
            val outcomes = coroutineScope {
                relayUrls.map { url -> async { fetchFromRelay(url, dTag) } }.awaitAll()
            }
            if (outcomes.any { it is RelayOutcome.Error }) sawError = true
            val newest = CatalogueTrust.selectPreferred(outcomes.mapNotNull { (it as? RelayOutcome.Hit)?.manifest })
            if (newest != null) return ManifestFetchResult.Found(newest)
            if (pass < passes - 1) {
                // Jitter keeps back-to-back catalogue lanes from retrying into the same rate-limit window.
                delay(MANIFEST_BACKOFF_BASE_MS * (pass + 1) + Random.nextLong(MANIFEST_BACKOFF_JITTER_MS))
            }
        }
        return ManifestFetchResult.NotFound(relayErrors = sawError)
    }

    private suspend fun fetchFromRelay(relayUrl: String, dTag: String): RelayOutcome {
        if (!relayUrl.startsWith("wss://")) return RelayOutcome.Error
        val done = CompletableDeferred<RelayOutcome>()
        val socket = SocketRef()
        val opened = CompletableDeferred<Unit>()
        val requestClaim = CompletableDeferred<Unit>()
        val filter = """{"kinds":[${CatalogueTrust.MANIFEST_KIND}],"authors":["${CatalogueTrust.MANIFEST_PUBKEY}"],"#d":["$dTag"],"limit":1}"""
        val request = """["REQ","$SUBSCRIPTION_ID",$filter]"""
        // onOpen may fire inside connect(), before the handle exists, or later on another
        // thread. Whichever side sees both "open" and "handle" first claims the single send.
        fun sendRequestOnce() {
            val handle = socket.handle ?: return
            if (opened.isCompleted && requestClaim.complete(Unit)) handle.send(request)
        }
        val listener = object : WebSocketListener {
            private var accepted: CatalogueManifest? = null
            private fun finish(outcome: RelayOutcome) { done.complete(outcome) }

            override fun onOpen() {
                opened.complete(Unit)
                sendRequestOnce()
            }

            override fun onText(text: String) {
                if (done.isCompleted) return
                if (!RelayInputGuard.accepts(text)) return finish(RelayOutcome.Error)
                try {
                    val message = json.parseToJsonElement(text) as? JsonArray ?: return finish(RelayOutcome.Error)
                    when ((message.getOrNull(0) as? JsonPrimitive)?.takeIf { it.isString }?.content) {
                        "EVENT" -> {
                            val event = message.getOrNull(2) ?: return
                            val verdict = CatalogueTrust.acceptEvent(hashing, event, dTag, clock.epochSeconds())
                            if (verdict is ManifestVerdict.Accepted) {
                                accepted = CatalogueTrust.selectPreferred(listOfNotNull(accepted, verdict.manifest))
                            }
                        }
                        "EOSE" -> finish(accepted?.let { RelayOutcome.Hit(it) } ?: RelayOutcome.Miss)
                    }
                } catch (e: Exception) {
                    finish(RelayOutcome.Error)
                }
            }

            override fun onClosed(reason: String) {
                // A close before EOSE with nothing accepted is an availability problem, not a replication gap.
                finish(accepted?.let { RelayOutcome.Hit(it) } ?: RelayOutcome.Error)
            }
        }
        return try {
            socket.handle = webSockets.connect(relayUrl, RelayInputGuard.MAX_BYTES, listener) ?: return RelayOutcome.Error
            sendRequestOnce()
            withTimeoutOrNull(RELAY_TIMEOUT_MS) { done.await() } ?: RelayOutcome.Error
        } finally {
            socket.handle?.close()
        }
    }

    private class SocketRef {
        @Volatile var handle: WebSocketHandle? = null
    }

    companion object {
        const val MANIFEST_FETCH_PASSES = 3
        const val RELAY_TIMEOUT_MS = 15_000L
        private const val MANIFEST_BACKOFF_BASE_MS = 500L
        private const val MANIFEST_BACKOFF_JITTER_MS = 500L
        private const val SUBSCRIPTION_ID = "blossom-manifest"
        private val json = Json
    }
}
