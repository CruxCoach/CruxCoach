package com.cruxcoach.android.nostr.model

/**
 * Result of decrypting a NIP-17 gift wrap.
 *
 * NIP-59 randomizes the OUTER (gift wrap) created_at by up to ±2 days for
 * privacy. Only the INNER rumor's created_at reflects the actual send time.
 *
 * - [timestamp]      → real send time from the rumor (kind 14). Use for UI
 *                      display, sorting, and DB storage.
 * - [wrapTimestamp]  → randomized created_at of the outer gift wrap (kind
 *                      1059). Use ONLY for advancing the relay sync cursor,
 *                      because that's the timestamp the relay's `since`
 *                      filter operates on. Both are stored as milliseconds.
 */
data class DecryptedMessage(
    val id: String,
    val content: String,
    val type: MessageType,
    val senderPubkey: String,
    val timestamp: Long,
    val wrapTimestamp: Long,
    val subject: String? = null,
    val replyToId: String? = null
)
