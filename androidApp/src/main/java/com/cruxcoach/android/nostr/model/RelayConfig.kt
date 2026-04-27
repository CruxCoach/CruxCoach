package com.cruxcoach.android.nostr.model

enum class RelaySource { USER_NIP65, DEFAULT }

data class RelayConfig(
    val url: String,
    val read: Boolean = true,
    val write: Boolean = true,
    val source: RelaySource = RelaySource.DEFAULT,
)
