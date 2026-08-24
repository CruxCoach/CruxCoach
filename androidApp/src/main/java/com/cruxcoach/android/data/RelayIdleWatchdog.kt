package com.cruxcoach.android.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Monotonic, polling backstop for a relay with no official-app clients.
 * A positive client count suspends expiry. The first observed transition back
 * to zero starts a complete new window, so a long-lived client is never
 * followed by an immediate timeout even if its disconnect event was dropped. */
internal class RelayIdleWatchdog(
    private val scope: CoroutineScope,
    private val timeoutMs: Long,
    private val pollMs: Long = minOf(5_000L, timeoutMs.coerceAtLeast(1L)),
    private val nowMs: () -> Long,
    private val clientCount: () -> Int,
    private val onTimeout: suspend () -> Unit,
) {
    private var job: Job? = null
    @Volatile private var lastActivityMs = 0L
    private var hadClients = false

    fun start() {
        stop()
        lastActivityMs = nowMs()
        hadClients = clientCount() > 0
        job = scope.launch {
            while (isActive) {
                delay(pollMs)
                val now = nowMs()
                val clients = clientCount()
                if (clients > 0) {
                    hadClients = true
                    lastActivityMs = now
                    continue
                }
                if (hadClients) {
                    hadClients = false
                    lastActivityMs = now
                    continue
                }
                if (now - lastActivityMs >= timeoutMs) {
                    onTimeout()
                    return@launch
                }
            }
        }
    }

    fun activity() {
        lastActivityMs = nowMs()
    }

    fun stop() {
        job?.cancel()
        job = null
        hadClients = false
    }
}
