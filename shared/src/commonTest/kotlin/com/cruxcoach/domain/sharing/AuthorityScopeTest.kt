package com.cruxcoach.domain.sharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * FEAT-062 §11: a scope names what two acts are allowed to conflict over.
 *
 * Which makes an ambiguous encoding a privacy bug rather than a tidiness one.
 * Scopes are compared by equality: two different subjects that encode to one
 * string are put in one competition, so a restrictive act about *your* videos
 * can be beaten by a permissive act about somebody else's, or — worse — a peer
 * who chooses their own npub can aim one scope at another.
 *
 * Peer ids and object ids are attacker-influenced. Joining them with a
 * separator that may appear inside them is how that happens.
 */
class AuthorityScopeTest {

    private companion object {
        val ALICE = PeerId("npub1alice")
        val BOB = PeerId("npub1bob")
    }

    // ------------------------------------------------------- no collisions

    /**
     * The concrete attack. With `"peer|" + npub`, a peer whose npub happens to
     * read `alice|category|VIDEOS` lands in the scope that decides Alice's
     * videos.
     */
    @Test
    fun a_peer_id_containing_the_separator_cannot_reach_another_scope() {
        val crafted = PeerId("npub1alice|category|VIDEOS")

        assertNotEquals(
            AuthorityScope.category(ALICE, SharingCategory.VIDEOS),
            AuthorityScope.peer(crafted),
        )
    }

    @Test
    fun an_object_id_containing_the_separator_cannot_reach_another_scope() {
        assertNotEquals(
            AuthorityScope.peerDevice(ALICE, DeviceId("dev-1")),
            AuthorityScope.objectRule(ALICE, ObjectId("dev-1")),
        )
        assertNotEquals(
            AuthorityScope.objectRule(ALICE, ObjectId("x|device|y")),
            AuthorityScope.objectRule(ALICE, ObjectId("x")),
        )
    }

    @Test
    fun a_peer_id_cannot_impersonate_the_estate_scope() {
        assertNotEquals(AuthorityScope.estate(), AuthorityScope.peer(PeerId("estate")))
    }

    @Test
    fun no_two_different_subjects_share_one_encoding() {
        val scopes = listOf(
            AuthorityScope.peer(ALICE),
            AuthorityScope.peer(BOB),
            AuthorityScope.peer(PeerId("npub1alice|category|VIDEOS")),
            AuthorityScope.category(ALICE, SharingCategory.VIDEOS),
            AuthorityScope.category(ALICE, SharingCategory.PRIVATE_NOTES),
            AuthorityScope.category(BOB, SharingCategory.VIDEOS),
            AuthorityScope.peerDevice(ALICE, DeviceId("d1")),
            AuthorityScope.peerDevice(ALICE, DeviceId("d2")),
            AuthorityScope.objectRule(ALICE, ObjectId("d1")),
            AuthorityScope.objectRule(ALICE, ObjectId("o|1")),
            AuthorityScope.baseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS),
            AuthorityScope.baseline(SharingCircle.ACQUAINTANCES, SharingCategory.VIDEOS),
            AuthorityScope.estate(),
        )

        assertEquals(scopes.size, scopes.distinct().size, "two subjects share one scope: $scopes")
    }

    /** Same subject, same string, every time — or the DAG splits in two. */
    @Test
    fun the_same_subject_always_encodes_the_same_way() {
        assertEquals(AuthorityScope.peer(ALICE), AuthorityScope.peer(PeerId("npub1alice")))
        assertEquals(
            AuthorityScope.category(ALICE, SharingCategory.VIDEOS),
            AuthorityScope.category(PeerId("npub1alice"), SharingCategory.VIDEOS),
        )
    }

    // --------------------------------------------------------- versioning

    @Test
    fun every_scope_this_build_makes_is_versioned() {
        listOf(
            AuthorityScope.peer(ALICE),
            AuthorityScope.category(ALICE, SharingCategory.VIDEOS),
            AuthorityScope.peerDevice(ALICE, DeviceId("d")),
            AuthorityScope.objectRule(ALICE, ObjectId("o")),
            AuthorityScope.baseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS),
            AuthorityScope.estate(),
        ).forEach { assertTrue(it.isCurrentVersion, "not versioned: ${it.value}") }
    }

    /**
     * A scope from the pre-versioned encoding is recognised and refused.
     *
     * It cannot be repaired: the whole point is that the old string is
     * ambiguous, so there is no way to tell which subject it meant. Fail closed
     * — it decides nothing and nothing new may be written into it.
     */
    @Test
    fun a_scope_from_the_ambiguous_encoding_is_not_current() {
        assertFalse(AuthorityScope("peer|npub1alice").isCurrentVersion)
        assertFalse(AuthorityScope("estate").isCurrentVersion)
        assertFalse(AuthorityScope("anything at all").isCurrentVersion)
    }

    @Test
    fun a_legacy_scope_never_equals_a_current_one() {
        assertNotEquals(AuthorityScope("peer|npub1alice"), AuthorityScope.peer(ALICE))
        assertNotEquals(AuthorityScope("estate"), AuthorityScope.estate())
    }

    // ------------------------------------------------------ the encoding

    @Test
    fun the_encoding_is_length_prefixed() {
        val value = AuthorityScope.peer(ALICE).value

        // "<version>|2:4:peer10:npub1alice" — the field length is what makes
        // the separator harmless inside a field.
        assertTrue(value.contains("10:npub1alice"), value)
        assertTrue(value.contains("4:peer"), value)
    }

    @Test
    fun a_blank_scope_is_refused_outright() {
        var threw = false
        try {
            AuthorityScope("")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}
