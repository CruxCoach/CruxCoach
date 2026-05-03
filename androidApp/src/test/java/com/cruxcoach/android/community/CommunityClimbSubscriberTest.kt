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
 *    author than the signed pubkey (CRITICAL: security/authorization,
 *    audit finding 002).
 *  - content.pubkey_prefix cross-check: defence-in-depth on the same
 *    property (CRITICAL: security/cryptography).
 *  - Cross-author UUID guard: drops events that would overwrite a row
 *    owned by a different author via INSERT OR REPLACE on uuid alone
 *    (CRITICAL: security/authorization, audit finding 002).
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
}
