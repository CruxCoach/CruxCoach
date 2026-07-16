package com.cruxcoach.android.nostr

import android.util.Log
import com.cruxcoach.android.data.NostrMessageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OfflineQueueManager @Inject constructor(
    private val messageRepository: NostrMessageRepository,
    private val messageSender: NostrMessageSending
) {
    private val drainMutex = Mutex()
    private val _queuedCount = MutableStateFlow(0)
    val queuedCount: Flow<Int> = _queuedCount.asStateFlow()

    suspend fun refreshCount() {
        withContext(Dispatchers.IO) {
            _queuedCount.value = messageRepository.getQueuedCount().toInt()
        }
    }

    suspend fun drainQueue() {
        if (!drainMutex.tryLock()) return
        try {
            cleanupExpired()

            val queued = withContext(Dispatchers.IO) { messageRepository.getQueued() }
            if (queued.isEmpty()) return

            val batch = queued.take(MAX_DRAIN_BATCH)
            for (msg in batch) {
                val eventJson = msg.event_json
                if (eventJson == null) {
                    defer(msg.id)
                    Log.w(TAG, "Queue drain: missing payload; deferred row")
                    continue
                }
                // Cap per-message send so a stalled relay cannot hold drainMutex
                // across the whole batch and silently starve other drain triggers.
                val success = withTimeoutOrNull(RELAY_SEND_TIMEOUT_MS) {
                    messageSender.retrySend(eventJson)
                }
                when (success) {
                    true -> {
                        withContext(Dispatchers.IO) { messageRepository.clearQueued(msg.id) }
                        Log.d(TAG, "Queue drain: sent ${msg.id}")
                    }
                    false -> {
                        // A Boolean result cannot distinguish a permanent
                        // relay rejection from a transient outage. Preserve
                        // the user's message, but rotate it behind later rows
                        // so one poison payload cannot starve the queue.
                        defer(msg.id)
                        Log.w(TAG, "Queue drain: send rejected; deferred row")
                    }
                    null -> {
                        defer(msg.id)
                        Log.w(TAG, "Queue drain: send timed out; deferred row")
                        break
                    }
                }
            }

            refreshCount()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Queue drain failed", e)
        } finally {
            drainMutex.unlock()
        }
    }

    private suspend fun defer(id: String) {
        withContext(Dispatchers.IO) {
            messageRepository.deferQueued(id, System.currentTimeMillis())
        }
    }

    suspend fun cleanupExpired() {
        withContext(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - EXPIRY_MS
            messageRepository.deleteExpiredQueued(cutoff)
        }
    }

    companion object {
        private const val TAG = "OfflineQueueManager"
        private const val MAX_DRAIN_BATCH = 10
        private const val EXPIRY_MS = 30L * 24 * 60 * 60 * 1000 // 30 days
        private const val RELAY_SEND_TIMEOUT_MS = 10_000L
    }
}
