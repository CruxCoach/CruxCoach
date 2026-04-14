package com.cruxcoach.android.nostr.model

sealed class NostrRecipient {
    data class Single(val pubkey: String) : NostrRecipient()
    data class Group(val pubkeys: List<String>) : NostrRecipient()

    fun asList(): List<String> = when (this) {
        is Single -> listOf(pubkey)
        is Group -> pubkeys
    }
}
