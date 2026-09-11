package com.cruxcoach.domain.sharing

/**
 * FEAT-062 core vocabulary for personal-information sharing.
 *
 * The types here are pure Kotlin with no platform, storage or transport
 * dependency, so every invariant in the product contract can be tested
 * without a device, a database or a relay.
 */

/**
 * The three visibility circles, widest first.
 *
 * Circles are *exclusive*: a peer sits in exactly one. They are ordered
 * `ALL_OTHER_USERS <= ACQUAINTANCES <= FRIENDS`, and baselines inherit
 * monotonically along that order — see [CircleBaselines.effectiveFor].
 */
enum class SharingCircle(val rank: Int) {
    ALL_OTHER_USERS(0),
    ACQUAINTANCES(1),
    FRIENDS(2),
    ;

    companion object {
        /** Widest to narrowest. */
        val ordered: List<SharingCircle> get() = entries.sortedBy { it.rank }
    }
}

/** The five shareable data categories. This set is closed in v1. */
enum class SharingCategory {
    PROFILE_AND_GOALS,
    TRAINING_HISTORY,
    VIDEOS,
    HEALTH_INFORMATION,
    PRIVATE_NOTES,
}

/** Allow or deny. There is no third state: everything unknown fails closed. */
enum class AccessEffect { ALLOW, DENY }

/**
 * A recipient, identified by their Nostr public key as **lower-case hex**.
 *
 * Hex, not bech32: this is the value every signature check compares against, so
 * it is the only form that can be an identity here. The UI still accepts a
 * typed `npub1…` — `SharingPeerIdParser` converts it — but nothing downstream
 * ever sees the bech32 form, and an npub and its own hex are therefore one
 * relationship rather than two.
 *
 * Never carries an nsec.
 */
data class PeerId(val value: String) {
    init { require(value.isNotBlank()) { "PeerId must not be blank" } }
    override fun toString(): String = "PeerId($value)"
}

/** A single shareable item (one video, one note) inside a category. */
data class ObjectId(val value: String) {
    init { require(value.isNotBlank()) { "ObjectId must not be blank" } }
}

/**
 * Why the resolver decided the way it did. The UI renders this verbatim so a
 * user can always see *which* rule produced the outcome they are looking at.
 */
enum class DecisionSource {
    /** An explicit deny on this one object. Beats everything below. */
    OBJECT_DENY,

    /** An explicit allow on this one object. */
    OBJECT_ALLOW,

    /** An explicit per-person deny for the whole category. */
    PERSON_DENY,

    /** An explicit per-person allow for the whole category. */
    PERSON_ALLOW,

    /** The baseline of the circle the peer is actually in. */
    CIRCLE_BASELINE,

    /** A baseline of a *wider* circle, inherited monotonically. */
    INHERITED_CIRCLE_BASELINE,

    /** Nothing granted this category at all. */
    NO_BASELINE_DEFAULT_DENY,

    /** The peer is not in the policy — fail closed rather than guess. */
    PEER_UNKNOWN_FAIL_CLOSED,

    // --- relationship gates, applied on top of an allowing policy -----------

    /** The projection knows no such relationship. */
    RELATIONSHIP_UNKNOWN_FAIL_CLOSED,

    /** The ledger could not be reduced safely. */
    LEDGER_FAIL_CLOSED,

    /** Offered, but the recipient has not accepted yet. */
    RELATIONSHIP_PENDING_CONSENT,

    /** The recipient refused. */
    RELATIONSHIP_DECLINED,

    /** The owner withdrew the relationship. */
    RELATIONSHIP_REVOKED,

    /** Local cleanup is running or done. */
    RELATIONSHIP_PURGE_PENDING,

    /** A grant was widened but its key delivery is not confirmed. */
    DELIVERY_UNCLEAR,

    /** This device was never authorised, or was revoked. */
    DEVICE_NOT_AUTHORISED,

    /** Local data belongs to an older resource epoch than the grant. */
    RESOURCE_EPOCH_STALE,

    /** Category is offered but not yet covered by a fresh consent. */
    CONSENT_EXPANSION_PENDING,

    /** Restored install: closed until revocations have been re-synced. */
    AWAITING_REVOKE_SYNC,
}

/**
 * Per-circle baseline grants.
 *
 * A category granted to a wider circle is implicitly granted to every narrower
 * one. Storing only the explicit per-circle sets and deriving the rest in
 * [effectiveFor] keeps the monotonicity an invariant rather than a convention
 * somebody has to remember to maintain.
 */
data class CircleBaselines(
    val byCircle: Map<SharingCircle, Set<SharingCategory>> = emptyMap(),
) {
    /** Everything [circle] sees: its own grants plus every wider circle's. */
    fun effectiveFor(circle: SharingCircle): Set<SharingCategory> =
        SharingCircle.entries
            .filter { it.rank <= circle.rank }
            .flatMapTo(mutableSetOf()) { byCircle[it].orEmpty() }

    /**
     * The widest circle whose explicit baseline grants [category] to [circle],
     * or `null` if no baseline does. The widest one is the honest answer: it is
     * the rule a user would have to change to take the grant away.
     */
    fun grantingCircle(circle: SharingCircle, category: SharingCategory): SharingCircle? =
        SharingCircle.ordered
            .firstOrNull { it.rank <= circle.rank && category in byCircle[it].orEmpty() }

    /** Baseline set for exactly this circle, ignoring inheritance. */
    fun explicitFor(circle: SharingCircle): Set<SharingCategory> = byCircle[circle].orEmpty()

    fun withCategory(circle: SharingCircle, category: SharingCategory, granted: Boolean): CircleBaselines {
        val current = byCircle[circle].orEmpty()
        val next = if (granted) current + category else current - category
        return copy(byCircle = byCircle + (circle to next))
    }
}

/** What the owner decided about one specific peer. */
data class PeerPolicy(
    val circle: SharingCircle,
    val categoryRules: Map<SharingCategory, AccessEffect> = emptyMap(),
    val objectRules: Map<ObjectId, AccessEffect> = emptyMap(),
)

/** The complete owner-side sharing policy. */
data class SharingPolicy(
    val baselines: CircleBaselines = CircleBaselines(),
    val peers: Map<PeerId, PeerPolicy> = emptyMap(),
)

/** A resolved access answer together with the rule that produced it. */
data class AccessDecision(
    val effect: AccessEffect,
    val source: DecisionSource,
    /** Set only when [source] is [DecisionSource.INHERITED_CIRCLE_BASELINE]. */
    val inheritedFrom: SharingCircle? = null,
) {
    val isAllowed: Boolean get() = effect == AccessEffect.ALLOW
}
