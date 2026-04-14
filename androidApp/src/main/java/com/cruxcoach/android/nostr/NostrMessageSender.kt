package com.cruxcoach.android.nostr

import android.util.Log
import com.cruxcoach.android.nostr.model.MessageType
import com.cruxcoach.android.nostr.model.NostrRecipient
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NostrMessageSender @Inject constructor(
    private val eventBuilder: NostrEventBuilder,
    private val relayPool: NostrRelayPool,
    private val nostrSigner: NostrSigner
) {
    /**
     * Builds gift wraps locally (fast, no network).
     * Returns [SendResult.Queued] with the serialized wraps so the caller
     * can persist them and return to the UI immediately.
     */
    suspend fun buildMessage(
        content: String,
        type: MessageType,
        recipients: NostrRecipient = NostrRecipient.Single(NostrConfig.DEV_PUBKEY),
        subject: String? = null,
        replyToId: String? = null
    ): SendResult {
        // Verify signer is available (detects Amber uninstall)
        nostrSigner.verifySignerAvailable()

        return try {
            val build = eventBuilder.buildGiftWraps(
                content = content,
                recipients = recipients,
                type = type,
                subject = subject,
                replyToId = replyToId
            )
            if (build.wraps.isEmpty()) {
                Log.w(TAG, "No gift wraps generated")
                return SendResult.Failed("No gift wraps generated")
            }
            val wrapJsons = build.wraps.joinToString(separator = "\n") { it.toJson() }
            SendResult.Queued(wrapJsons, build.selfWrapId, build.recipientWrapId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build gift wraps for ${type.label}", e)
            SendResult.Failed(e.message ?: "Unknown error")
        }
    }

    /**
     * Delivers pre-built wrap JSONs to relays.
     * Includes a random delay (2-60s) for timing correlation resistance.
     * Returns true if at least one relay accepted.
     */
    suspend fun deliverWraps(eventJsons: String): Boolean {
        return try {
            // Random delay to obscure timing correlation
            delay(kotlin.random.Random.nextLong(2_000, 60_000))
            sendJsonLines(eventJsons)
        } catch (e: Exception) {
            Log.e(TAG, "Deliver wraps failed", e)
            false
        }
    }

    /**
     * Retries sending queued events without extra delay.
     */
    suspend fun retrySend(eventJsons: String): Boolean {
        return try {
            sendJsonLines(eventJsons)
        } catch (e: Exception) {
            Log.e(TAG, "Retry send failed", e)
            false
        }
    }

    private suspend fun sendJsonLines(eventJsons: String): Boolean {
        val lines = eventJsons.split("\n").filter { it.isNotBlank() }
        var anySuccess = false
        for (json in lines) {
            val event = Event.fromJson(json)
            if (relayPool.sendEvent(event)) {
                anySuccess = true
            }
        }
        return anySuccess
    }

    companion object {
        private const val TAG = "NostrMessageSender"
    }
}
