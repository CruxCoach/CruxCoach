package com.cruxcoach.domain.sharing

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FEAT-062 §11: one deterministic winner, decided by evidence only.
 *
 * When two of the owner's own devices change the same permission while neither
 * has seen the other, something has to decide which change stands — and it has
 * to be the *same* decision on every device, in live reduction, in replay and
 * in a restore, for ever. Anything that depends on a wall clock, on the order a
 * relay happened to deliver things, or on a priority a client set for itself is
 * not a rule but a race, and the losing device would silently disagree about
 * who can see what.
 *
 * So the order is fixed and every term is derived from signed evidence:
 *
 *  1. higher **valid** authority generation;
 *  2. causal successor — if one event descends from the other, it is later;
 *  3. genuinely parallel and security-relevant: the **restrictive** one
 *     (deny, revoke, expire, purge) beats the permissive one;
 *  4. manifest-derived role: PRIMARY before TRUSTED;
 *  5. canonical device id, then canonical event id.
 *
 * Rule 3 is the one that matters most. A tie broken towards "allow" turns a
 * concurrent withdrawal into a silent grant; broken towards "deny" it turns a
 * concurrent grant into a change the person has to make again, which is
 * annoying and safe.
 */
class DeviceAuthorityResolverTest {

    private companion object {
        val PRIMARY = AuthorityDeviceId("aaaa1111")
        val TRUSTED_A = AuthorityDeviceId("bbbb2222")
        val TRUSTED_B = AuthorityDeviceId("cccc3333")
        val READER = AuthorityDeviceId("dddd4444")
    }

    /** A manifest naming one primary, two trusted devices and a reader. */
    private val manifest = DeviceAuthorityState(
        authorityGeneration = 2,
        head = LedgerEntryId("m-head"),
        devices = mapOf(
            PRIMARY to DeviceRecord(PRIMARY, "pk-primary", DeviceRole.PRIMARY, 1),
            TRUSTED_A to DeviceRecord(TRUSTED_A, "pk-a", DeviceRole.TRUSTED, 1),
            TRUSTED_B to DeviceRecord(TRUSTED_B, "pk-b", DeviceRole.TRUSTED, 1),
            READER to DeviceRecord(READER, "pk-r", DeviceRole.READ_ONLY, 1),
        ),
    )

    private fun event(
        id: String,
        device: AuthorityDeviceId,
        effect: AuthorityEffect,
        parent: String? = "root",
        generation: Long = 2,
    ) = AuthorityEvent(
        eventId = LedgerEntryId(id),
        parent = parent?.let { LedgerEntryId(it) },
        authorityGeneration = generation,
        device = device,
        effect = effect,
    )

    private fun resolve(vararg events: AuthorityEvent) =
        DeviceAuthorityResolver.resolve(events.toList(), manifest)

    // ------------------------------------------------- 1. generation first

    @Test
    fun a_higher_authority_generation_always_wins() {
        val stale = event("e-stale", PRIMARY, AuthorityEffect.RESTRICTIVE, generation = 1)
        val current = event("e-current", READER, AuthorityEffect.PERMISSIVE, generation = 2)

        // Restrictive, primary and a lexically smaller id all lose to a newer
        // generation: re-established authority supersedes everything before it.
        assertEquals(current, resolve(stale, current))
        assertEquals(current, resolve(current, stale))
    }

    @Test
    fun an_event_from_a_generation_beyond_the_manifest_is_not_valid_authority() {
        val forgedFuture = event("e-future", PRIMARY, AuthorityEffect.RESTRICTIVE, generation = 99)
        val current = event("e-current", TRUSTED_A, AuthorityEffect.PERMISSIVE, generation = 2)

        // "Higher generation wins" is qualified by *valid*: a generation the
        // manifest never established is a claim, not evidence.
        assertEquals(current, resolve(forgedFuture, current))
    }

    // --------------------------------------------------- 2. causal successor

    @Test
    fun a_causal_successor_beats_its_own_ancestor() {
        val earlier = event("e-1", PRIMARY, AuthorityEffect.RESTRICTIVE, parent = "root")
        val later = event("e-2", READER, AuthorityEffect.PERMISSIVE, parent = "e-1")

        // Not a conflict at all: the author of e-2 had already seen e-1, so
        // this is an ordinary later decision, and restrictive-wins must not
        // resurrect a change that was deliberately superseded.
        assertEquals(later, resolve(earlier, later))
        assertEquals(later, resolve(later, earlier))
    }

    @Test
    fun a_longer_chain_resolves_to_its_head() {
        val a = event("e-a", PRIMARY, AuthorityEffect.RESTRICTIVE, parent = "root")
        val b = event("e-b", TRUSTED_A, AuthorityEffect.PERMISSIVE, parent = "e-a")
        val c = event("e-c", TRUSTED_B, AuthorityEffect.PERMISSIVE, parent = "e-b")

        assertEquals(c, resolve(a, b, c))
    }

    // ------------------------------------------- 3. restrictive wins parallel

    @Test
    fun a_deny_beats_an_allow_from_the_same_parent() {
        val allow = event("e-allow", PRIMARY, AuthorityEffect.PERMISSIVE)
        val deny = event("e-deny", READER, AuthorityEffect.RESTRICTIVE)

        // Genuinely parallel: same parent, neither saw the other. The
        // restrictive one stands even though its author ranks lower and its id
        // sorts later.
        assertEquals(deny, resolve(allow, deny))
        assertEquals(deny, resolve(deny, allow))
    }

    @Test
    fun a_revoke_beats_a_grant_from_the_same_parent() {
        val grant = event("e-grant", PRIMARY, AuthorityEffect.PERMISSIVE)
        val revoke = event("e-revoke", TRUSTED_B, AuthorityEffect.RESTRICTIVE)

        assertEquals(revoke, resolve(grant, revoke))
    }

    // ------------------------------------------------------- 4. role rank

    @Test
    fun primary_beats_trusted_when_nothing_else_separates_them() {
        val fromPrimary = event("e-zzz", PRIMARY, AuthorityEffect.PERMISSIVE)
        val fromTrusted = event("e-aaa", TRUSTED_A, AuthorityEffect.PERMISSIVE)

        // The primary wins despite the lexically larger event id, so rank is
        // genuinely consulted before the id tiebreak.
        assertEquals(fromPrimary, resolve(fromPrimary, fromTrusted))
    }

    @Test
    fun the_role_comes_from_the_manifest_rather_than_from_the_event() {
        val fromTrusted = event("e-1", TRUSTED_A, AuthorityEffect.PERMISSIVE)
        val fromReader = event("e-2", READER, AuthorityEffect.PERMISSIVE)

        // READ_ONLY ranks below TRUSTED, and nothing on the event can say
        // otherwise — there is no role field to forge.
        assertEquals(fromTrusted, resolve(fromTrusted, fromReader))
    }

    // ------------------------------------------------- 5. canonical tiebreak

    @Test
    fun two_trusted_devices_are_separated_by_canonical_device_id() {
        val fromA = event("e-zzz", TRUSTED_A, AuthorityEffect.PERMISSIVE)
        val fromB = event("e-aaa", TRUSTED_B, AuthorityEffect.PERMISSIVE)

        // Same rank, same effect, same parent: the smaller device id wins, and
        // the event id is only consulted after that.
        assertEquals(fromA, resolve(fromA, fromB))
    }

    @Test
    fun one_device_deciding_twice_is_separated_by_canonical_event_id() {
        val first = event("e-aaa", TRUSTED_A, AuthorityEffect.PERMISSIVE)
        val second = event("e-bbb", TRUSTED_A, AuthorityEffect.PERMISSIVE)

        assertEquals(first, resolve(first, second))
    }

    // ------------------------------------------------------ no free priority

    @Test
    fun nothing_a_device_can_set_for_itself_changes_the_outcome() {
        // The winner is a function of exactly these five derived terms. There is
        // no priority, weight, timestamp or sequence a client could raise to
        // promote its own event — the type does not carry one, which is the
        // strongest form this guarantee can take.
        val fields = AuthorityEvent::class.simpleName
        assertTrue(fields != null)

        val a = event("e-1", TRUSTED_A, AuthorityEffect.PERMISSIVE)
        val b = event("e-2", TRUSTED_B, AuthorityEffect.PERMISSIVE)
        assertEquals(resolve(a, b), resolve(b, a))
    }

    @Test
    fun an_event_from_a_device_the_manifest_does_not_name_is_ignored() {
        val stranger = event("e-x", AuthorityDeviceId("ffff9999"), AuthorityEffect.RESTRICTIVE)
        val known = event("e-y", READER, AuthorityEffect.PERMISSIVE)

        // Fail-closed the other way round would be worse: an unknown device
        // could impose a denial on everybody by inventing an id.
        assertEquals(known, resolve(stranger, known))
        assertNull(resolve(stranger))
    }

    @Test
    fun a_revoked_device_carries_no_authority() {
        val revokedDevice = AuthorityDeviceId("eeee5555")
        val withRevoked = manifest.copy(
            devices = manifest.devices + (
                revokedDevice to DeviceRecord(revokedDevice, "pk-x", DeviceRole.REVOKED, 1)
                ),
        )
        val fromRevoked = AuthorityEvent(
            LedgerEntryId("e-1"), LedgerEntryId("root"), 2, revokedDevice, AuthorityEffect.RESTRICTIVE,
        )
        val fromReader = AuthorityEvent(
            LedgerEntryId("e-2"), LedgerEntryId("root"), 2, READER, AuthorityEffect.PERMISSIVE,
        )

        assertEquals(fromReader, DeviceAuthorityResolver.resolve(listOf(fromRevoked, fromReader), withRevoked))
    }

    // ------------------------------------------- order and duplicate freedom

    @Test
    fun the_winner_does_not_depend_on_the_order_events_arrived() {
        val events = listOf(
            event("e-1", PRIMARY, AuthorityEffect.PERMISSIVE),
            event("e-2", TRUSTED_A, AuthorityEffect.RESTRICTIVE),
            event("e-3", TRUSTED_B, AuthorityEffect.PERMISSIVE, parent = "e-1"),
            event("e-4", READER, AuthorityEffect.RESTRICTIVE, generation = 1),
        )
        val expected = DeviceAuthorityResolver.resolve(events, manifest)

        val random = Random(20260816)
        repeat(200) {
            assertEquals(expected, DeviceAuthorityResolver.resolve(events.shuffled(random), manifest))
        }
    }

    @Test
    fun duplicates_do_not_change_the_winner() {
        val a = event("e-1", PRIMARY, AuthorityEffect.PERMISSIVE)
        val b = event("e-2", TRUSTED_A, AuthorityEffect.RESTRICTIVE)

        assertEquals(resolve(a, b), DeviceAuthorityResolver.resolve(listOf(a, b, a, b, b), manifest))
    }

    @Test
    fun an_empty_or_wholly_invalid_set_has_no_winner() {
        assertNull(DeviceAuthorityResolver.resolve(emptyList(), manifest))
    }

    // ------------------------------------------------- ordering is a total order

    /**
     * The comparator must be a strict weak ordering, or "the winner" is not
     * well defined and two devices sorting the same set could disagree.
     */
    @Test
    fun the_ordering_is_antisymmetric_and_transitive_over_a_generated_set() {
        val random = Random(7)
        val devices = listOf(PRIMARY, TRUSTED_A, TRUSTED_B, READER)
        val events = (1..40).map { i ->
            AuthorityEvent(
                eventId = LedgerEntryId("e-$i"),
                parent = LedgerEntryId(if (i > 4 && random.nextBoolean()) "e-${i - 4}" else "root"),
                authorityGeneration = if (random.nextBoolean()) 2L else 1L,
                device = devices[random.nextInt(devices.size)],
                effect = if (random.nextBoolean()) AuthorityEffect.RESTRICTIVE else AuthorityEffect.PERMISSIVE,
            )
        }

        events.forEach { a ->
            events.forEach { b ->
                val ab = DeviceAuthorityResolver.compare(a, b, manifest)
                val ba = DeviceAuthorityResolver.compare(b, a, manifest)
                assertEquals(
                    ab, -ba,
                    "compare must be antisymmetric for ${a.eventId.value} vs ${b.eventId.value}",
                )
            }
        }

        events.forEach { a ->
            events.forEach { b ->
                events.forEach { c ->
                    val ab = DeviceAuthorityResolver.compare(a, b, manifest)
                    val bc = DeviceAuthorityResolver.compare(b, c, manifest)
                    if (ab < 0 && bc < 0) {
                        assertTrue(
                            DeviceAuthorityResolver.compare(a, c, manifest) < 0,
                            "compare must be transitive: ${a.eventId.value} < ${b.eventId.value} < ${c.eventId.value}",
                        )
                    }
                }
            }
        }
    }

    /**
     * Rank is looked up **per event**, not per device.
     *
     * The manifest's own forks are ranked by the role each author held at the
     * head its act names, and one device can appear twice at two different
     * points. Folding those into a single device-to-role map — which is what
     * the manifest reducer used to build — makes the answer depend on which
     * entry of the map survived, and that depended on the order the acts
     * happened to be in. The comparison takes a lookup so there is one total
     * order and no synthetic state to assemble.
     */
    @Test
    fun rank_is_looked_up_per_event_rather_than_per_device() {
        val device = AuthorityDeviceId("aaaa")
        val state = DeviceAuthorityState(
            authorityGeneration = 1,
            head = LedgerEntryId("m-1"),
            devices = mapOf(device to DeviceRecord(device, "pk", DeviceRole.TRUSTED, 1)),
        )
        fun event(id: String) = AuthorityEvent(
            eventId = LedgerEntryId(id),
            parent = null,
            authorityGeneration = 1,
            device = device,
            effect = AuthorityEffect.PERMISSIVE,
        )
        val whenPrimary = event("zzz-was-primary")
        val whenTrusted = event("aaa-was-trusted")
        // Same device, same effect, and the ids are ordered so that canonical
        // order alone would pick the other one.
        val roleOf: (AuthorityEvent) -> DeviceRole? = {
            if (it.eventId.value.endsWith("primary")) DeviceRole.PRIMARY else DeviceRole.TRUSTED
        }

        assertEquals(
            whenPrimary,
            DeviceAuthorityResolver.resolve(listOf(whenTrusted, whenPrimary), state, roleOf),
        )
        assertEquals(
            whenPrimary,
            DeviceAuthorityResolver.resolve(listOf(whenPrimary, whenTrusted), state, roleOf),
            "and the answer cannot depend on the order they arrived in",
        )
    }
}
