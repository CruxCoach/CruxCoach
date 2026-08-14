package com.cruxcoach.android.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event

class NostrPublicEventBuilder(
    private val nostrSigner: NostrSigner
) {
    suspend fun buildSignedEvent(
        kind: Int,
        content: String,
        tags: List<List<String>>,
        createdAt: Long = System.currentTimeMillis() / 1000,
    ): Event {
        val tagsArray = tags.map { it.toTypedArray() }.toTypedArray()

        return nostrSigner.signer.sign<Event>(
            createdAt = createdAt,
            kind = kind,
            tags = tagsArray,
            content = content
        )
    }

}
