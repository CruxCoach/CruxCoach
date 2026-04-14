package com.cruxcoach.android.nostr

sealed class SendResult {
    data object Sent : SendResult()
    /**
     * @property eventJsons newline-separated JSON of all gift wraps to deliver.
     * @property selfWrapId id of the gift wrap p-tagged for the sender. Used as
     *  the local DB primary key so the relay echo dedupes via INSERT OR IGNORE.
     * @property recipientWrapId id of the gift wrap p-tagged for the recipient.
     *  Stored as thread_anchor_id so the recipient's replies can be matched.
     */
    data class Queued(
        val eventJsons: String,
        val selfWrapId: String?,
        val recipientWrapId: String?
    ) : SendResult()
    data class Failed(val error: String) : SendResult()
}
