package com.cruxcoach.domain.sharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * FEAT-062 §12.1: an act names the **whole** manifest state it was authored
 * against, not one entry of it.
 *
 * ## Why a single head cannot say it
 *
 * The manifest forks, and independent scopes merge: enrolling a tablet and
 * revoking a phone are siblings on one parent, and the state that stands is
 * their *union*. A single head names one branch of that union and silently
 * omits the other — so a device that signed against "the tablet is enrolled and
 * the phone is revoked" could only record half of what it saw, and the reader
 * had to guess the rest. Guessing is the canonical-head heuristic this replaces.
 *
 * So the act carries the full frontier: every maximal entry of the authorised
 * union, sorted, signed. Replay reconstructs exactly that union by folding the
 * ancestor closure of those ids, and refuses anything it cannot place.
 *
 * ## What a context is not allowed to be
 *
 * - an id this install does not hold — it may be *future*, it may never have
 *   existed, and neither can be told apart from the other;
 * - an id that is not part of the authorised history — a branch that lost
 *   decides nothing, and signing against it would let a device pick the estate
 *   it preferred;
 * - a frontier that is not an antichain — an id that is an ancestor of another
 *   in the same context is redundant, and admitting it would mean two different
 *   contexts designating one union.
 */
class ManifestContextTest {

    private companion object {
        const val OWNER = "npub1owner"
        val LAPTOP = AuthorityDeviceId("aaaa-laptop")
        val PHONE = AuthorityDeviceId("bbbb-phone")
        val TABLET = AuthorityDeviceId("cccc-tablet")
    }

    private val verifier = DeviceManifestVerifier { it.signature == "${it.signerNpub}:${it.id.value}" }

    private val reducer = DeviceManifestReducer(verifier, OWNER, FIXTURE_ATTESTATION_VERIFIER)

    private fun entry(id: String, seq: Long, body: DeviceManifestBody, parent: String?) =
        DeviceManifestEntry(
            id = LedgerEntryId(id),
            manifestSequence = seq,
            authorityGeneration = 1,
            parent = parent?.let { LedgerEntryId(it) },
            signerNpub = OWNER,
            signature = "$OWNER:$id",
            body = body,
        )

    private fun ask(
        entry: DeviceManifestEntry,
        device: AuthorityDeviceId,
        context: ManifestContext,
        follows: String? = null,
    ) = AuthorityPairing.requiredFor(entry.body)!!.let { required ->
        AuthorityAttestation(
            id = LedgerEntryId("act-${entry.id.value}"),
            scope = required.scope,
            subject = entry.id,
            device = device,
            parent = follows?.let { LedgerEntryId(it) },
            manifestContext = context,
            authorityGeneration = 1,
            capability = required.capability,
            effect = required.effect,
            signature = "pk-${device.value}:act-${entry.id.value}",
        )
    }

    /**
     * The genesis, then two independent siblings: the tablet is enrolled and
     * the phone is revoked, neither seeing the other. The state that stands is
     * both, and the frontier that names it is both ids.
     */
    private fun mergedEstate(): Pair<List<DeviceManifestEntry>, List<AuthorityAttestation>> {
        val genesis = entry("m-1", 1, DeviceManifestBody.DeviceEnrolled(LAPTOP, "pk-${LAPTOP.value}", DeviceRole.PRIMARY), null)
        val enrolPhone = entry("m-2", 2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk-${PHONE.value}", DeviceRole.TRUSTED), "m-1")
        val enrolTablet = entry(
            "m-3a", 3, DeviceManifestBody.DeviceEnrolled(TABLET, "pk-${TABLET.value}", DeviceRole.TRUSTED), "m-2",
        )
        val revokePhone = entry("m-3b", 3, DeviceManifestBody.DeviceRevoked(PHONE), "m-2")
        val one = ManifestContext.of(listOf(LedgerEntryId("m-1")))
        val two = ManifestContext.of(listOf(LedgerEntryId("m-2")))
        return listOf(genesis, enrolPhone, enrolTablet, revokePhone) to listOf(
            ask(enrolPhone, LAPTOP, one),
            ask(enrolTablet, LAPTOP, two),
            ask(revokePhone, LAPTOP, two, follows = "act-m-2"),
        )
    }

    // ------------------------------------------------------- the union itself

    @Test
    fun independent_siblings_reduce_to_their_union_in_either_arrival_order() {
        val (entries, acts) = mergedEstate()

        val forward = reducer.reduce(entries, acts)
        val reverse = reducer.reduce(entries.reversed(), acts.reversed())

        assertNull(forward.failClosedReason, "the estate is well formed")
        assertEquals(forward, reverse, "the same evidence reduces the same way")
        assertEquals(DeviceRole.TRUSTED, forward.roleOf(TABLET), "the enrolment stands")
        assertNull(forward.roleOf(PHONE), "and so does the revocation")
    }

    /** The frontier the reader publishes is both maximal entries, sorted. */
    @Test
    fun the_authorised_frontier_names_every_maximal_entry() {
        val (entries, acts) = mergedEstate()

        assertEquals(
            listOf(LedgerEntryId("m-3a"), LedgerEntryId("m-3b")),
            reducer.reduce(entries, acts).frontier,
            "a single head could only ever have named one of these",
        )
    }

    /** And reading at that context reconstructs exactly the merged union. */
    @Test
    fun reading_at_the_full_frontier_reconstructs_the_union() {
        val (entries, acts) = mergedEstate()
        val reduced = reducer.reduce(entries, acts)

        val asOf = reduced.at(ManifestContext.of(reduced.frontier))

        assertNull(asOf.failClosedReason)
        assertEquals(DeviceRole.TRUSTED, asOf.roleOf(TABLET))
        assertNull(asOf.roleOf(PHONE))
        assertEquals(DeviceRole.PRIMARY, asOf.roleOf(LAPTOP))
    }

    /**
     * Half of it reconstructs only that half — which is the point. An act
     * signed before the sibling arrived keeps the estate it actually saw.
     */
    @Test
    fun reading_at_one_branch_reconstructs_only_that_branch() {
        val (entries, acts) = mergedEstate()
        val reduced = reducer.reduce(entries, acts)

        val asOf = reduced.at(ManifestContext.of(listOf(LedgerEntryId("m-3a"))))

        assertNull(asOf.failClosedReason)
        assertEquals(DeviceRole.TRUSTED, asOf.roleOf(TABLET))
        assertEquals(DeviceRole.TRUSTED, asOf.roleOf(PHONE), "the revocation is not in this closure")
    }

    // --------------------------------------------- what a context may not be

    @Test
    fun a_context_naming_an_entry_this_install_does_not_hold_fails_closed() {
        val (entries, acts) = mergedEstate()
        val reduced = reducer.reduce(entries, acts)

        val reason = assertNotNull(
            reduced.at(ManifestContext.of(listOf(LedgerEntryId("m-99")))).failClosedReason,
        )
        assertEquals(true, reason.contains("m-99"), reason)
    }

    @Test
    fun a_context_naming_an_entry_no_authorised_branch_holds_fails_closed() {
        val genesis = entry("m-1", 1, DeviceManifestBody.DeviceEnrolled(LAPTOP, "pk-${LAPTOP.value}", DeviceRole.PRIMARY), null)
        val enrol = entry("m-2", 2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk-${PHONE.value}", DeviceRole.READ_ONLY), "m-1")
        val one = ManifestContext.of(listOf(LedgerEntryId("m-1")))
        val two = ManifestContext.of(listOf(LedgerEntryId("m-2")))
        // Same scope, so one of these two loses outright.
        val promote = entry("m-3a", 3, DeviceManifestBody.DeviceRoleChanged(PHONE, DeviceRole.PRIMARY), "m-2")
        val revoke = entry("m-3b", 3, DeviceManifestBody.DeviceRevoked(PHONE), "m-2")
        val reduced = reducer.reduce(
            listOf(genesis, enrol, promote, revoke),
            listOf(
                ask(enrol, LAPTOP, one),
                ask(promote, LAPTOP, two, follows = "act-m-2"),
                ask(revoke, LAPTOP, two, follows = "act-m-2"),
            ),
        )
        assertNull(reduced.failClosedReason)
        assertEquals(listOf(LedgerEntryId("m-3b")), reduced.frontier, "the revocation is what stands")

        assertNotNull(
            reduced.at(ManifestContext.of(listOf(LedgerEntryId("m-3a")))).failClosedReason,
            "a branch that lost decides nothing, so nothing may be signed against it",
        )
    }

    @Test
    fun a_context_whose_ids_are_not_an_antichain_fails_closed() {
        val (entries, acts) = mergedEstate()
        val reduced = reducer.reduce(entries, acts)

        // m-2 is an ancestor of m-3a, so naming both is a frontier that
        // designates the same union twice over.
        val reason = assertNotNull(
            reduced.at(
                ManifestContext.of(listOf(LedgerEntryId("m-2"), LedgerEntryId("m-3a"))),
            ).failClosedReason,
        )
        assertEquals(true, reason.contains("ancestor"), reason)
    }

    @Test
    fun the_empty_context_is_the_estate_before_the_genesis() {
        val (entries, acts) = mergedEstate()

        val asOf = reducer.reduce(entries, acts).at(ManifestContext.genesis)

        assertNull(asOf.failClosedReason)
        assertEquals(emptyMap(), asOf.devices)
    }

    // ------------------------------------------------------- canonical form

    @Test
    fun a_context_is_canonical_whatever_order_it_was_built_in() {
        val a = LedgerEntryId("m-3a")
        val b = LedgerEntryId("m-3b")

        assertEquals(ManifestContext.of(listOf(a, b)), ManifestContext.of(listOf(b, a)))
        assertEquals(ManifestContext.of(listOf(a, b)), ManifestContext.of(listOf(a, b, a)))
    }

    /** Length-prefixed, so no id's content can be mistaken for structure. */
    @Test
    fun the_canonical_form_cannot_be_confused_by_an_id_containing_a_separator() {
        val sneaky = ManifestContext.of(listOf(LedgerEntryId("a,b")))
        val two = ManifestContext.of(listOf(LedgerEntryId("a"), LedgerEntryId("b")))

        assertEquals(false, sneaky.canonical == two.canonical)
    }

    // ------------------------------- exactly one byte form per frontier

    /**
     * A frontier has one encoding and one value, or the signature over it
     * proves less than it looks like it does.
     *
     * The type used to be a data class with a public constructor, so an
     * unsorted or duplicated list was a perfectly ordinary `ManifestContext`
     * that compared unequal to the canonical one over the same ids — two values
     * for one frontier, and two byte forms an act could be signed under.
     */
    @Test
    fun a_frontier_has_exactly_one_value_however_it_was_built() {
        val a = LedgerEntryId("m-3a")
        val b = LedgerEntryId("m-3b")
        val canonical = ManifestContext.of(listOf(a, b))

        assertEquals(canonical, ManifestContext.of(listOf(b, a)))
        assertEquals(canonical, ManifestContext.of(listOf(a, b, a)))
        assertEquals(canonical.canonical, ManifestContext.of(listOf(b, a, b)).canonical)
    }

    @Test
    fun the_canonical_form_round_trips() {
        val context = ManifestContext.of(listOf(LedgerEntryId("m-3b"), LedgerEntryId("m-3a")))

        assertEquals(context, ManifestContext.parse(context.canonical))
        assertEquals(ManifestContext.genesis, ManifestContext.parse(ManifestContext.genesis.canonical))
    }

    /** Multi-byte ids are counted in bytes, and still round-trip. */
    @Test
    fun an_id_with_multi_byte_characters_round_trips() {
        val context = ManifestContext.of(listOf(LedgerEntryId("m-ü-3"), LedgerEntryId("m-日-4")))

        assertEquals(context, ManifestContext.parse(context.canonical))
    }

    /**
     * The parser accepts the canonical form and nothing else. Every string
     * below decodes to a frontier some lenient reader would accept, and each is
     * a second byte form for something that already has one.
     */
    @Test
    fun the_parser_refuses_every_non_canonical_encoding() {
        val cases = mapOf(
            "out of order" to "ccctx.v1|2:4:m-3b4:m-3a",
            "a duplicate" to "ccctx.v1|2:4:m-3a4:m-3a",
            "a negative length" to "ccctx.v1|1:-4:m-3a",
            "a length past the end" to "ccctx.v1|1:99:m-3a",
            "a zero length" to "ccctx.v1|1:0:",
            "a padded length" to "ccctx.v1|1:04:m-3a",
            "trailing bytes" to "ccctx.v1|1:4:m-3axx",
            "a count that overruns" to "ccctx.v1|2:4:m-3a",
            "a count that undercounts" to "ccctx.v1|1:4:m-3a4:m-3b",
            "no version" to "1:4:m-3a",
            "another version" to "ccctx.v2|1:4:m-3a",
            "nothing at all" to "",
        )

        cases.forEach { (what, encoded) ->
            assertNull(ManifestContext.parse(encoded), "$what must not parse: $encoded")
        }
    }

    /** A length that would overflow must not throw on the way to refusing. */
    @Test
    fun an_absurd_length_is_refused_rather_than_thrown() {
        assertNull(ManifestContext.parse("ccctx.v1|1:99999999999999999999:m-3a"))
        assertNull(ManifestContext.parse("ccctx.v1|99999999999999999999:4:m-3a"))
    }
}
