package com.cruxcoach.domain.sharing

/**
 * Vocabulary for personal-data sharing (v2).
 *
 * Pure Kotlin with no platform, storage or transport dependency, so every rule
 * of the policy can be tested without a device, a database or a relay. The
 * policy is local and unsigned: it decides what this installation sends; the
 * transport (MLS/Marmot) decides who can read it.
 */

/**
 * The two presets, widest first. A person is filed under exactly one. Friends
 * see at least what acquaintances see: a category granted to the wider preset
 * is inherited by the narrower one ([CircleBaselines.effectiveFor]).
 */
enum class SharingCircle(val rank: Int) {
    ACQUAINTANCES(0),
    FRIENDS(1),
    ;

    companion object {
        /** Widest to narrowest. */
        val ordered: List<SharingCircle> get() = entries.sortedBy { it.rank }
    }
}

/** The three shareable categories. Health data and videos are out of v1. */
enum class SharingCategory {
    PROFILE_AND_GOALS,
    TRAINING_HISTORY,
    PRIVATE_NOTES,
}

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

/** A single record (`ascent:<uuid>`, `bid:<uuid>`, `note:<climb>`). */
data class ObjectId(val value: String) {
    init { require(value.isNotBlank()) { "ObjectId must not be blank" } }
}

/** Why the resolver decided the way it did; the UI renders it. */
enum class DecisionSource {
    OBJECT_DENY,
    OBJECT_ALLOW,
    PERSON_DENY,
    PERSON_ALLOW,
    /** The preset the person is filed under grants the category. */
    CIRCLE_BASELINE,
    /** A wider preset grants it and the person's preset inherits it. */
    INHERITED_CIRCLE_BASELINE,
    NO_BASELINE_DEFAULT_DENY,
    /** The person is not in the policy — fail closed rather than guess. */
    PEER_UNKNOWN_FAIL_CLOSED,
}

/** Per-preset category grants; inheritance is derived, never stored. */
data class CircleBaselines(
    val byCircle: Map<SharingCircle, Set<SharingCategory>> = emptyMap(),
) {
    fun effectiveFor(circle: SharingCircle): Set<SharingCategory> =
        SharingCircle.entries
            .filter { it.rank <= circle.rank }
            .flatMapTo(mutableSetOf()) { byCircle[it].orEmpty() }

    /** The widest preset whose grant reaches [circle], or `null`. */
    fun grantingCircle(circle: SharingCircle, category: SharingCategory): SharingCircle? =
        SharingCircle.ordered
            .firstOrNull { it.rank <= circle.rank && category in byCircle[it].orEmpty() }

    fun explicitFor(circle: SharingCircle): Set<SharingCategory> = byCircle[circle].orEmpty()

    fun withCategory(circle: SharingCircle, category: SharingCategory, granted: Boolean): CircleBaselines {
        val current = byCircle[circle].orEmpty()
        val next = if (granted) current + category else current - category
        return copy(byCircle = byCircle + (circle to next))
    }
}

/** Category and record together identify an object exception. */
data class ObjectRuleKey(val objectId: ObjectId, val category: SharingCategory)

/** What the owner decided about one person. */
data class PeerPolicy(
    val circle: SharingCircle,
    val categoryRules: Map<SharingCategory, AccessEffect> = emptyMap(),
    val objectRules: Map<ObjectRuleKey, AccessEffect> = emptyMap(),
)

/** The owner's complete local policy. */
data class SharingPolicy(
    val baselines: CircleBaselines = CircleBaselines(),
    val peers: Map<PeerId, PeerPolicy> = emptyMap(),
)

data class AccessDecision(
    val effect: AccessEffect,
    val source: DecisionSource,
    /** Set only when [source] is [DecisionSource.INHERITED_CIRCLE_BASELINE]. */
    val inheritedFrom: SharingCircle? = null,
) {
    val isAllowed: Boolean get() = effect == AccessEffect.ALLOW
}
