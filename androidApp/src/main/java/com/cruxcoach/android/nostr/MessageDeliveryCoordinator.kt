package com.cruxcoach.android.nostr

import android.util.Log
import com.cruxcoach.android.data.NostrMessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-scoped delivery of already-built gift wraps. Delivery must not run
 * in a ViewModel/Activity scope: [NostrMessageSender.deliverWraps] sleeps
 * a random timing-correlation delay before publishing, and a screen exit
 * inside that window used to cancel the coroutine and strand the message
 * in the offline queue until the next drain trigger.
 */
@Singleton
class MessageDeliveryCoordinator @Inject constructor(
    private val messageSender: NostrMessageSending,
    private val messageRepository: NostrMessageRepository,
    private val queueManager: OfflineQueueManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _deliveredEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** Emits the local event id after the relay accepted a tracked message. */
    val deliveredEvents: SharedFlow<String> = _deliveredEvents.asSharedFlow()

    /**
     * Delivers [eventJsons] in the background. When [eventId] is non-null the
     * message is expected to be queued in the repository; on relay accept it
     * is marked delivered and de-queued, and [deliveredEvents] emits. On
     * failure it simply stays queued for [OfflineQueueManager] to retry.
     */
    fun deliver(eventId: String?, eventJsons: String) {
        scope.launch {
            val success = try {
                messageSender.deliverWraps(eventJsons)
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Background delivery failed (tracked=${eventId != null}, type=${e.javaClass.simpleName})",
                )
                false
            }
            if (!success) {
                Log.w(
                    TAG,
                    "Message accepted by zero relays; " +
                        if (eventId == null) "untracked delivery dropped" else "tracked delivery deferred",
                )
            }
            if (success && eventId != null) {
                messageRepository.updateRelayAccepted(eventId)
                messageRepository.clearQueued(eventId)
                queueManager.refreshCount()
                _deliveredEvents.emit(eventId)
                Log.i(TAG, "Tracked message delivered to relay")
            }
        }
    }

    companion object {
        private const val TAG = "MessageDeliveryCoord"
    }
}
