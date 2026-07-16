package com.cruxcoach.android.nostr

import android.util.Log
import com.cruxcoach.android.nostr.model.MessageType
import com.cruxcoach.android.nostr.model.NostrRecipient
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NostrMessageSender @Inject constructor(
    private val eventBuilder: NostrEventBuilder,
    private val relayPool: NostrRelayPool,
    private val nostrSigner: NostrSigner
) : NostrMessageSending {

    override suspend fun buildMessage(
        content: String,
        type: MessageType,
        recipients: NostrRecipient,
        subject: String?,
        replyToId: String?,
        selfReplyToId: String?
    ): SendResult {
        // Verify signer is available (detects Amber uninstall)
        nostrSigner.verifySignerAvailable()

        return try {
            val build = eventBuilder.buildGiftWraps(
                content = content,
                recipients = recipients,
                type = type,
                subject = subject,
                replyToId = replyToId,
                selfReplyToId = selfReplyToId
            )
            if (build.wraps.isEmpty()) {
                Log.w(TAG, "No gift wraps generated")
                return SendResult.Failed("No gift wraps generated")
            }
            val wrapJsons = build.wraps.joinToString(separator = "\n") { it.toJson() }
            SendResult.Queued(wrapJsons, build.selfWrapId, build.recipientWrapId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build gift wraps for ${type.label}", e)
            SendResult.Failed(e.message ?: "Unknown error")
        }
    }

    override suspend fun deliverWraps(eventJsons: String): Boolean {
        return try {
            // Random delay to decouple relay arrival from the send tap.
            // Kept short: the heavy timing protection is NIP-59's ±2-day
            // created_at randomization; a long sleep here only widened the
            // window in which delivery could be interrupted.
            delay(kotlin.random.Random.nextLong(2_000, 10_000))
            sendJsonLines(eventJsons)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Deliver wraps failed", e)
            false
        }
    }

    override suspend fun retrySend(eventJsons: String): Boolean {
        return try {
            sendJsonLines(eventJsons)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Retry send failed", e)
            false
        }
    }

    private suspend fun sendJsonLines(eventJsons: String): Boolean {
        val lines = eventJsons.split("\n").filter { it.isNotBlank() }
        val senderPubkey = nostrSigner.getPublicKeyHex()
        val outcomes = mutableListOf<Pair<String?, Boolean>>()
        for (json in lines) {
            val event = Event.fromJson(json)
            outcomes += recipientPTag(json) to relayPool.sendEvent(event)
        }
        return recipientDeliverySucceeded(senderPubkey, outcomes)
    }

    companion object {
        private const val TAG = "NostrMessageSender"
    }
}

internal fun recipientDeliverySucceeded(
    senderPubkey: String,
    outcomes: List<Pair<String?, Boolean>>,
): Boolean {
    val recipientOutcomes = outcomes.filter { (recipient, _) ->
        recipient != null && !recipient.equals(senderPubkey, ignoreCase = true)
    }
    return recipientOutcomes.isNotEmpty() && recipientOutcomes.all { it.second }
}

private fun recipientPTag(eventJson: String): String? = runCatching {
    Json.parseToJsonElement(eventJson).jsonObject["tags"]?.jsonArray
        ?.firstOrNull { tag ->
            val fields = tag.jsonArray
            fields.firstOrNull()?.jsonPrimitive?.content == "p" && fields.size >= 2
        }
        ?.jsonArray
        ?.get(1)
        ?.jsonPrimitive
        ?.content
}.getOrNull()
