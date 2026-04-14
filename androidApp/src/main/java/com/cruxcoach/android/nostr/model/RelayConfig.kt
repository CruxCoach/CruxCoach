package com.cruxcoach.android.nostr.model

data class RelayConfig(
    val url: String,
    val read: Boolean = true,
    val write: Boolean = true
)
