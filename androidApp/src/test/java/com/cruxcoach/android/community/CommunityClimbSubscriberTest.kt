package com.cruxcoach.android.community

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for [CommunityClimbValidation] — the pure helpers that
 * back the security guards added to [CommunityClimbSubscriber.handleEvent]:
 *
 *  - d-tag prefix cross-check: drops events whose d-tag claims a different
 *    author than the signed pubkey.
 *  - content.pubkey_prefix cross-check: defence-in-depth on the same
 *    property.
 *  - Cross-author UUID guard: drops events that would overwrite a row
 *    owned by a different author via INSERT OR REPLACE on uuid alone.
 *
 * The Schnorr-signature and event-id-recompute guards (`Event.fromJson` +
 * `verifySignature`) are not unit-tested here because Quartz ships its
 * `Event` class compiled against Java 21 and the project's test JVM is
 * Java 17. The same applies to every other Quartz-using ingest path in
 * this codebase (NostrProfileManager, BlossomSyncManager, BackupRepository,
 * Nip65RelayListFetcher, AnnouncementsViewModel, NotificationPollWorker) —
 * none of them have unit tests for the verification call either, by the
 * same constraint. Verification is exercised end-to-end on device.
 */
class CommunityClimbSubscriberTest {

    private val authorA = "aaaaaaaa".padEnd(64, 'a')
    private val authorB = "bbbbbbbb".padEnd(64, 'b')
    private val uuidA = "abc123ef".padEnd(32, '0')

    // ----- expectedDTagPrefix -----

    @Test
    fun expectedDTagPrefix_uses_first_8_hex_chars_of_pubkey() {
        assertEquals(
            "cruxcoach:climb:aaaaaaaa:",
            CommunityClimbValidation.expectedDTagPrefix(authorA),
        )
    }

    @Test
    fun expectedDTagPrefix_matches_publisher_format() {
        // The publisher (NostrCommunityClimb.communityClimbDTag) embeds
        // exactly this prefix; if either side drifts the ingest path
        // would silently reject every legitimate event.
        val publisherDTag = "cruxcoach:climb:${authorA.take(8)}:$uuidA"
        assertTrue(publisherDTag.startsWith(CommunityClimbValidation.expectedDTagPrefix(authorA)))
    }

    @Test
    fun expectedDTagPrefix_supports_a_fork_namespace_without_cross_talk() {
        val forkDTag = "cruxfork:climb:${authorA.take(8)}:$uuidA"

        assertTrue(
            CommunityClimbValidation.dTagAuthorMatches(
                forkDTag,
                authorA,
                brandNamespace = "cruxfork",
            )
        )
        assertFalse(
            CommunityClimbValidation.dTagAuthorMatches(
                "cruxcoach:climb:${authorA.take(8)}:$uuidA",
                authorA,
                brandNamespace = "cruxfork",
            )
        )
    }

    // ----- dTagAuthorMatches -----

    @Test
    fun dTagAuthorMatches_accepts_correctly_namespaced_dtag() {
        val dTag = "cruxcoach:climb:${authorA.take(8)}:$uuidA"
        assertTrue(CommunityClimbValidation.dTagAuthorMatches(dTag, authorA))
    }

    @Test
    fun dTagAuthorMatches_rejects_dtag_pointing_at_different_author() {
        // authorA signs an event but the d-tag claims authorB's namespace —
        // attacker holds a valid keypair, tries to squat someone else's
        // d-tag space.
        val dTag = "cruxcoach:climb:${authorB.take(8)}:$uuidA"
        assertFalse(CommunityClimbValidation.dTagAuthorMatches(dTag, authorA))
    }

    @Test
    fun dTagAuthorMatches_rejects_unrelated_dtag_format() {
        assertFalse(CommunityClimbValidation.dTagAuthorMatches("totally-different-tag", authorA))
        assertFalse(CommunityClimbValidation.dTagAuthorMatches("", authorA))
    }

    // ----- contentPubkeyPrefixMatches -----

    @Test
    fun contentPubkeyPrefixMatches_accepts_null_for_legacy_events() {
        // Older events may not carry the field — tolerated for forward-compat.
        assertTrue(CommunityClimbValidation.contentPubkeyPrefixMatches(null, authorA))
    }

    @Test
    fun contentPubkeyPrefixMatches_accepts_correct_prefix() {
        assertTrue(CommunityClimbValidation.contentPubkeyPrefixMatches(authorA.take(8), authorA))
    }

    @Test
    fun contentPubkeyPrefixMatches_rejects_mismatched_prefix() {
        assertFalse(CommunityClimbValidation.contentPubkeyPrefixMatches(authorB.take(8), authorA))
    }

    @Test
    fun contentPubkeyPrefixMatches_rejects_empty_string_when_pubkey_present() {
        assertFalse(CommunityClimbValidation.contentPubkeyPrefixMatches("", authorA))
    }

    // ----- authorOwnershipMatches -----

    @Test
    fun authorOwnershipMatches_allows_first_publish_when_no_existing_row() {
        assertTrue(CommunityClimbValidation.authorOwnershipMatches(null, authorA))
    }

    @Test
    fun authorOwnershipMatches_allows_replaceable_update_from_same_author() {
        assertTrue(CommunityClimbValidation.authorOwnershipMatches(authorA, authorA))
    }

    @Test
    fun authorOwnershipMatches_rejects_cross_author_uuid_collision() {
        // authorA already owns the uuid locally; an event signed by authorB
        // tries to overwrite via INSERT OR REPLACE on uuid alone.
        assertFalse(CommunityClimbValidation.authorOwnershipMatches(authorA, authorB))
    }

    // ----- isWithinClockSkew (cursor-poisoning guard) -----

    @Test
    fun isWithinClockSkew_accepts_now_and_past_events() {
        val now = 1_700_000_000L
        assertTrue(CommunityClimbValidation.isWithinClockSkew(now, now))
        assertTrue(CommunityClimbValidation.isWithinClockSkew(now - 86_400L, now))
    }

    @Test
    fun isWithinClockSkew_accepts_small_future_drift_within_one_hour() {
        val now = 1_700_000_000L
        assertTrue(CommunityClimbValidation.isWithinClockSkew(now + 1800L, now))
        assertTrue(CommunityClimbValidation.isWithinClockSkew(now + 3600L, now))
    }

    @Test
    fun isWithinClockSkew_rejects_far_future_event() {
        // a forged far-future timestamp must not advance the `since` cursor and
        // permanently disable the subscription.
        val now = 1_700_000_000L
        assertFalse(CommunityClimbValidation.isWithinClockSkew(now + 3601L, now))
        assertFalse(CommunityClimbValidation.isWithinClockSkew(now + 86_400L * 365L, now))
    }

    // ── Skip-matrix bounds ──────────────────────────────────────────────

    @Test
    fun eventSizeAcceptable_caps_at_16kb() {
        assertTrue(CommunityClimbValidation.eventSizeAcceptable(0))
        assertTrue(CommunityClimbValidation.eventSizeAcceptable(8 * 1024))
        assertTrue(CommunityClimbValidation.eventSizeAcceptable(16 * 1024))
        assertFalse(CommunityClimbValidation.eventSizeAcceptable(16 * 1024 + 1))
        assertFalse(CommunityClimbValidation.eventSizeAcceptable(1_000_000))
    }

    @Test
    fun nameLengthAcceptable_caps_at_100_chars() {
        assertTrue(CommunityClimbValidation.nameLengthAcceptable(0))
        assertTrue(CommunityClimbValidation.nameLengthAcceptable(50))
        assertTrue(CommunityClimbValidation.nameLengthAcceptable(100))
        assertFalse(CommunityClimbValidation.nameLengthAcceptable(101))
        assertFalse(CommunityClimbValidation.nameLengthAcceptable(10_000))
    }

    @Test
    fun descriptionLengthAcceptable_caps_at_500_chars() {
        assertTrue(CommunityClimbValidation.descriptionLengthAcceptable(0))
        assertTrue(CommunityClimbValidation.descriptionLengthAcceptable(250))
        assertTrue(CommunityClimbValidation.descriptionLengthAcceptable(500))
        assertFalse(CommunityClimbValidation.descriptionLengthAcceptable(501))
    }

    @Test
    fun holdsCountAcceptable_caps_at_200_holds() {
        assertTrue(CommunityClimbValidation.holdsCountAcceptable(0))
        assertTrue(CommunityClimbValidation.holdsCountAcceptable(50))
        assertTrue(CommunityClimbValidation.holdsCountAcceptable(200))
        assertFalse(CommunityClimbValidation.holdsCountAcceptable(201))
        assertFalse(CommunityClimbValidation.holdsCountAcceptable(10_000))
    }

    @Test
    fun kindAcceptable_only_30078() {
        assertTrue(CommunityClimbValidation.kindAcceptable(30078))
        assertFalse(CommunityClimbValidation.kindAcceptable(0))
        assertFalse(CommunityClimbValidation.kindAcceptable(1))
        assertFalse(CommunityClimbValidation.kindAcceptable(30079))
    }

    @Test
    fun isOwnEvent_skips_self_echo() {
        assertTrue(CommunityClimbValidation.isOwnEvent(authorA, authorA))
        assertFalse(CommunityClimbValidation.isOwnEvent(authorA, authorB))
        assertFalse(CommunityClimbValidation.isOwnEvent(authorA, null))
    }

    // ----- lookbackAdjustedSeed (first-run cursor seed) -----

    @Test
    fun lookbackAdjustedSeed_subtracts_safety_window_from_manifest_epoch() {
        // The manifest epoch is written by the Kilter sync only; non-Kilter
        // chunk crons can lag behind it. The seed must sit one full safety
        // window earlier so the first REQ re-covers that gap.
        val manifestEpoch = 1_750_000_000L
        assertEquals(
            manifestEpoch - CommunityClimbSubscriber.SEED_SAFETY_LOOKBACK_SEC,
            CommunityClimbSubscriber.lookbackAdjustedSeed(manifestEpoch),
        )
    }

    @Test
    fun lookbackAdjustedSeed_clamps_at_zero_for_tiny_epochs() {
        // A bogus near-epoch-zero manifest timestamp must not produce a
        // negative `since` (relays treat that as malformed / undefined).
        assertEquals(0L, CommunityClimbSubscriber.lookbackAdjustedSeed(0L))
        assertEquals(0L, CommunityClimbSubscriber.lookbackAdjustedSeed(60L))
        assertEquals(
            0L,
            CommunityClimbSubscriber.lookbackAdjustedSeed(
                CommunityClimbSubscriber.SEED_SAFETY_LOOKBACK_SEC,
            ),
        )
    }
}
