package com.cruxcoach.android.nostr

import android.util.Log
import com.cruxcoach.android.nostr.model.DecryptedMessage
import com.cruxcoach.android.nostr.model.MessageType
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verifyId
import com.vitorpamplona.quartz.nip01Core.crypto.verifySignature
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealedRumorEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent

class NostrEventDecryptor(
    private val nostrSigner: NostrSigner
) {
    suspend fun decrypt(giftWrapEventJson: String): DecryptedMessage? {
        return try {
            val event = Event.fromJson(giftWrapEventJson)
            if (event !is GiftWrapEvent) {
                val giftWrap = GiftWrapEvent(
                    event.id, event.pubKey, event.createdAt,
                    event.tags, event.content, event.sig
                )
                decryptGiftWrap(giftWrap)
            } else {
                decryptGiftWrap(event)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt gift wrap event", e)
            null
        }
    }

    private suspend fun decryptGiftWrap(giftWrap: GiftWrapEvent): DecryptedMessage? {
        // NIP-59 has TWO layers:
        //   1) GiftWrap (kind 1059) → unwrap to Seal (kind 13)
        //   2) Seal     (kind 13)   → unseal to Rumor (kind 14, the real payload)
        // GiftWrapEvent.unwrapOrNull ONLY peels the outer layer and returns the
        // Seal. If we use seal.content as the message, we end up storing the
        // still-NIP-44-encrypted rumor JSON (starts with "Ag…" because NIP-44
        // v2 base64 payloads begin with version byte 0x02).
        val wrapSignatureValid = giftWrap.verifySignature()
        val wrapIdValid = wrapSignatureValid && giftWrap.verifyId()
        if (!NostrEventPolicy.hasValidBodyBinding(wrapSignatureValid, wrapIdValid)) {
            Log.w(TAG, "Rejecting gift wrap with invalid signature/id")
            return null
        }
        val unwrapped = giftWrap.unwrapOrNull(nostrSigner.signer) ?: return null
        // NIP-17 always uses gift-wrap -> kind-13 seal -> unsigned rumor.
        // Accepting a single-layer fallback makes the self-declared rumor
        // pubkey forgeable and lets a remote sender impersonate the developer.
        val seal = unwrapped as? SealedRumorEvent ?: run {
            Log.w(TAG, "Rejecting NIP-17 gift wrap without a kind-13 seal")
            return null
        }
        val sealSignatureValid = seal.verifySignature()
        val sealIdValid = sealSignatureValid && seal.verifyId()
        if (!NostrEventPolicy.hasValidBodyBinding(sealSignatureValid, sealIdValid)) {
            Log.w(TAG, "Rejecting NIP-17 seal with invalid signature/id")
            return null
        }
        val rumor = seal.unsealOrNull(nostrSigner.signer) ?: run {
            Log.w(TAG, "Failed to unseal rumor from seal ${seal.id}")
            return null
        }
        if (!NostrEventPolicy.hasBoundDmSender(seal.pubKey, rumor.pubKey)) {
            Log.w(TAG, "Rejecting NIP-17 rumor whose author differs from the seal")
            return null
        }

        val type = extractMessageType(rumor.tags)
        val subject = extractTagValue(rumor.tags, "subject")
        val replyToId = extractTagValue(rumor.tags, "e")
        // Self-root hint on own outgoing replies: the LOCAL (self-wrap) id of
        // the thread root, carried alongside the foreign wire id in the e-tag
        // so wipe-and-refetch can re-thread echoes. Absent on dev messages.
        val selfReplyToId = extractTagValue(rumor.tags, NostrConfig.RUMOR_TAG_SELF_ROOT)

        val unpadded = rumor.content

        return DecryptedMessage(
            id = giftWrap.id,
            content = unpadded.trim(),
            type = type,
            senderPubkey = rumor.pubKey,
            // The rumor's created_at is the REAL send time. The gift wrap's
            // created_at is randomized ±2 days for timing-correlation
            // resistance and would otherwise show wrong dates in the UI.
            timestamp = rumor.createdAt * 1000, // convert to millis
            wrapTimestamp = giftWrap.createdAt * 1000,
            subject = subject,
            replyToId = replyToId,
            selfReplyToId = selfReplyToId
        )
    }

    private fun extractMessageType(tags: Array<Array<String>>): MessageType {
        for (tag in tags) {
            if (tag.size >= 3 && tag[0] == "l" && tag[2] == "com.cruxcoach.type") {
                return MessageType.fromLabel(tag[1]) ?: MessageType.CHAT
            }
        }
        return MessageType.CHAT
    }

    private fun extractTagValue(tags: Array<Array<String>>, tagName: String): String? {
        for (tag in tags) {
            if (tag.size >= 2 && tag[0] == tagName) {
                return tag[1]
            }
        }
        return null
    }

    companion object {
        private const val TAG = "NostrEventDecryptor"
    }
}
