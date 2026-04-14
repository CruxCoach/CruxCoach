package com.cruxcoach.android.nostr.model

enum class MessageType(val label: String, val prefix: String?) {
    CRASH("crash-report", "[CRASH]"),
    BUG("bug-report", "[BUG]"),
    FEATURE("feature-request", "[FEATURE]"),
    CHAT("chat", null);

    companion object {
        fun fromLabel(label: String): MessageType? = entries.find { it.label == label }
    }
}
