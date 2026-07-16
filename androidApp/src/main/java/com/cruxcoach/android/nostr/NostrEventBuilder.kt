package com.cruxcoach.android.nostr

import android.util.Log
import com.cruxcoach.android.nostr.model.MessageType
import com.cruxcoach.android.nostr.model.NostrRecipient
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip17Dm.NIP17Factory
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent

/**
 * Result of building gift wraps for a message.
 *
 * @property wraps All gift wraps to publish (one per recipient + one for self).
 * @property selfWrapId Event id of the gift wrap p-tagged for the sender.
 *  This is the canonical id under which the message must be stored locally,
 *  so that when the same wrap echoes back through the relay subscription
 *  it deduplicates via INSERT OR IGNORE.
 * @property recipientWrapId Event id of the gift wrap p-tagged for the recipient.
 *  The recipient (e.g. dashboard) sees this id as the message's identity, so
 *  any reply they send will reference it in their ["e", ...] tag. Stored as
 *  thread_anchor_id so thread queries can match replies from either side.
 */
data class BuildResult(
    val wraps: List<Event>,
    val selfWrapId: String?,
    val recipientWrapId: String?
)

class NostrEventBuilder(
    private val nostrSigner: NostrSigner
) {
    /**
     * @param replyToId WIRE id for the ["e", …, "reply"] tag. For replies to
     *  an own root this must be the root's recipient-wrap id
     *  (thread_anchor_id) — the id the other side stored the root under —
     *  NOT the local self-wrap row id, which the recipient has never seen.
     * @param selfReplyToId LOCAL (self-wrap) id of the thread root, emitted
     *  as a [NostrConfig.RUMOR_TAG_SELF_ROOT] hint tag. Required so that a
     *  wipe-and-refetch, which re-ingests this reply from its self-wrap
     *  echo, can map the foreign wire id in the e-tag back to the local
     *  root row and re-learn the root's wiped thread anchor. NOT a second
     *  e-tag on purpose — the dashboard threads on e-tags and must never
     *  see the self-wrap id.
     */
    suspend fun buildGiftWraps(
        content: String,
        recipients: NostrRecipient,
        type: MessageType,
        subject: String? = null,
        replyToId: String? = null,
        selfReplyToId: String? = null
    ): BuildResult {
        val formattedContent = formatContent(content, type)
        val ownPubkey = nostrSigner.getPublicKeyHex()
        val pubkeys = recipients.asList()
        val allWraps = mutableListOf<Event>()
        var selfWrapId: String? = null
        var recipientWrapId: String? = null

        for (pubkey in pubkeys) {
            try {
                val tagFn = { builder: TagArrayBuilder<ChatMessageEvent> ->
                    builder.add(arrayOf("L", "com.cruxcoach.type"))
                    builder.add(arrayOf("l", type.label, "com.cruxcoach.type"))
                    if (subject != null) {
                        builder.add(arrayOf("subject", subject))
                    }
                    if (replyToId != null) {
                        builder.add(arrayOf("e", replyToId, "", "reply"))
                    }
                    if (selfReplyToId != null) {
                        builder.add(
                            arrayOf(NostrConfig.RUMOR_TAG_SELF_ROOT, selfReplyToId)
                        )
                    }
                    Unit
                }
                // Rumor timestamp = real time. NIP-59 already randomizes the
                // outer gift wrap ±2 days, so fuzzing the rumor (which is
                // inside the encrypted payload) only distorts the UI display.
                val timestamp = System.currentTimeMillis() / 1000
                val template = ChatMessageEvent.build(
                    formattedContent,
                    listOf(PTag(pubkey)),
                    timestamp,
                    tagFn
                )

                val factory = NIP17Factory()
                val result = factory.createMessageNIP17(template, nostrSigner.signer)
                allWraps.addAll(result.wraps)

                // NIP-17: createMessageNIP17 produces TWO wraps per call — one
                // p-tagged for the recipient and one p-tagged for the sender
                // (for multi-device sync).
                if (selfWrapId == null) {
                    for (wrap in result.wraps) {
                        val pTag = wrap.tags.firstOrNull { it.size >= 2 && it[0] == "p" }
                            ?: continue
                        if (pTag[1] == ownPubkey) {
                            selfWrapId = wrap.id
                        } else {
                            recipientWrapId = wrap.id
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to build gift wraps", e)
            }
        }

        return BuildResult(allWraps, selfWrapId, recipientWrapId)
    }

    private fun formatContent(content: String, type: MessageType): String {
        // NIP-44 already pads ciphertext to power-of-two buckets, so
        // application-level padding is redundant and leaks into other
        // NIP-17 clients (e.g. Amethyst) that don't strip it.
        val prefix = type.prefix ?: return content
        return "$prefix $content"
    }

    companion object {
        private const val TAG = "NostrEventBuilder"
    }
}
