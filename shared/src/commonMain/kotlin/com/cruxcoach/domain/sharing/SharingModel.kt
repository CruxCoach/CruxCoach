package com.cruxcoach.domain.sharing

/**
 * Basic vocabulary of personal-data sharing. The audience model built on it
 * (levels, catalogue, templates, rules) is in `Audience.kt`.
 */

/** Allow or deny. There is no third state: everything unknown fails closed. */
enum class AccessEffect { ALLOW, DENY }

/**
 * A person, identified by their Nostr public key as **lower-case hex** — the
 * value every signature check compares against. The UI accepts a typed
 * `npub1…` and converts it with `SharingPeerIdParser`. Never carries an nsec.
 */
data class PeerId(val value: String) {
    init { require(value.isNotBlank()) { "PeerId must not be blank" } }
    override fun toString(): String = "PeerId($value)"
}

/** A single record (`ascent:<uuid>`, `bid:<uuid>`, `note:<climb>`, `profile:active`). */
data class ObjectId(val value: String) {
    init { require(value.isNotBlank()) { "ObjectId must not be blank" } }
}
