package com.cruxcoach.domain.sharing

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * FEAT-062 §11: the invariants the whole model is only worth having if it keeps.
 *
 * Every other test says "this input gives that answer". These say the answer
 * cannot depend on anything it must not depend on — arrival order, which seam
 * asked, how the events were batched — and that the ordering really is an
 * order, not a rule of thumb that happens to work on the examples somebody
 * thought of.
 *
 * The seam equivalence is the one that matters most. Live reduction, replay
 * after a restart and a rebuild after a restore all call
 * [DeviceAuthorityResolver], so a divergence between them would mean two of the
 * owner's devices disagreeing about who can see what, with nothing anywhere to
 * detect it.
 */
class AuthorityInvariantsTest {

    private companion object {
        val DEVICES = listOf(
            AuthorityDeviceId("aaaa") to DeviceRole.PRIMARY,
            AuthorityDeviceId("bbbb") to DeviceRole.TRUSTED,
            AuthorityDeviceId("cccc") to DeviceRole.TRUSTED,
            AuthorityDeviceId("dddd") to DeviceRole.READ_ONLY,
        )
        val SCOPES = listOf(
            AuthorityScope.peer(PeerId("npub1one")),
            AuthorityScope.peer(PeerId("npub1two")),
            AuthorityScope.estate(),
        )
    }

    private val manifest = DeviceAuthorityState(
        authorityGeneration = 2,
        head = LedgerEntryId("m-1"),
        devices = DEVICES.associate { (device, role) ->
            device to DeviceRecord(device, "pk-${device.value}", role, 1)
        },
    )

    private val verifier = AuthorityAttestationVerifier { attestation, publicKey ->
        attestation.signature == "$publicKey:${attestation.id.value}"
    }

    /**
     * A pseudo-random but *admissible* history: every act names a device the
     * manifest knows, the established generation, and a parent that is either
     * absent or already in the list.
     */
    private fun history(seed: Int, size: Int): List<AuthorityAttestation> {
        val random = Random(seed)
        val acts = mutableListOf<AuthorityAttestation>()
        repeat(size) { index ->
            val (device, role) = DEVICES[random.nextInt(DEVICES.size)]
            val scope = SCOPES[random.nextInt(SCOPES.size)]
            val inScope = acts.filter { it.scope == scope }
            val parent = if (inScope.isEmpty() || random.nextBoolean()) {
                null
            } else {
                inScope[random.nextInt(inScope.size)].id
            }
            val id = "act-${index.toString().padStart(3, '0')}"
            acts += AuthorityAttestation(
                id = LedgerEntryId(id),
                scope = scope,
                subject = LedgerEntryId("entry-$id"),
                device = device,
                parent = parent,
                manifestContext = FIXTURE_MANIFEST_CONTEXT,
                authorityGeneration = 2,
                // Within the role, or the act is one admission would have
                // refused — and a read-time check now refuses it too, which
                // would make every property below pass on an empty answer.
                capability = if (DeviceCapability.MUTATE_PERMISSIONS in role.capabilities) {
                    DeviceCapability.MUTATE_PERMISSIONS
                } else {
                    DeviceCapability.READ
                },
                effect = if (random.nextBoolean()) {
                    AuthorityEffect.RESTRICTIVE
                } else {
                    AuthorityEffect.PERMISSIVE
                },
                signature = "pk-${device.value}:$id",
            )
        }
        return acts
    }

    // ------------------------------------------------------ seam equivalence

    /**
     * Mandatory matrix: the same winner at every seam.
     *
     * Live reduction sees the acts as they arrive. Replay reads them back off
     * disk in whatever order the query returned. A restore rebuilds from a
     * payload that was assembled somewhere else entirely. All three must agree.
     */
    @Test
    fun live_replay_and_restore_pick_the_same_winner() {
        repeat(50) { seed ->
            val acts = history(seed, size = 24)

            // Live: folded one at a time, as the app receives them.
            val live = acts.indices.fold(emptyList<AuthorityAttestation>()) { seen, index ->
                seen + acts[index]
            }.let { AuthorityLedger.winners(trusted(it, manifest)) }

            // Replay: read back in a different order, as a query would.
            val replay = AuthorityLedger.winners(trusted(acts.sortedBy { it.id.value }.reversed(), manifest))

            // Restore: rebuilt from a payload, grouped and shuffled on the way.
            val restore = AuthorityLedger.winners(
                trusted(acts.groupBy { it.scope }.values.flatten().shuffled(Random(seed + 1000)), manifest),
            )

            assertEquals(live, replay, "live and replay disagree at seed $seed")
            assertEquals(live, restore, "live and restore disagree at seed $seed")
        }
    }

    @Test
    fun batching_the_same_acts_differently_changes_nothing() {
        repeat(30) { seed ->
            val acts = history(seed, size = 20)
            val whole = AuthorityLedger.winners(trusted(acts, manifest))

            // Folded in three arbitrary chunks rather than all at once.
            val chunked = acts.chunked(7).fold(emptyList<AuthorityAttestation>()) { seen, chunk ->
                seen + chunk
            }

            assertEquals(whole, AuthorityLedger.winners(trusted(chunked, manifest)))
        }
    }

    @Test
    fun duplicates_anywhere_in_the_history_change_nothing() {
        repeat(30) { seed ->
            val acts = history(seed, size = 18)
            val doubled = (acts + acts).shuffled(Random(seed))

            assertEquals(
                AuthorityLedger.winners(trusted(acts, manifest)),
                AuthorityLedger.winners(trusted(doubled, manifest)),
            )
        }
    }

    // ------------------------------------------------------ ordering is an order

    private fun eventsFrom(acts: List<AuthorityAttestation>) = acts.map { it.toEvent() }

    @Test
    fun the_comparison_is_antisymmetric() {
        val events = eventsFrom(history(seed = 7, size = 40))

        events.forEach { a ->
            events.forEach { b ->
                val ab = DeviceAuthorityResolver.compare(a, b, manifest)
                val ba = DeviceAuthorityResolver.compare(b, a, manifest)
                if (a == b) {
                    assertEquals(0, ab)
                } else {
                    assertTrue(
                        (ab < 0 && ba > 0) || (ab > 0 && ba < 0),
                        "compare must not call two distinct events equal: $a vs $b",
                    )
                }
            }
        }
    }

    @Test
    fun the_comparison_is_transitive() {
        val events = eventsFrom(history(seed = 11, size = 22))

        events.forEach { a ->
            events.forEach { b ->
                events.forEach { c ->
                    val ab = DeviceAuthorityResolver.compare(a, b, manifest)
                    val bc = DeviceAuthorityResolver.compare(b, c, manifest)
                    if (ab < 0 && bc < 0) {
                        assertTrue(
                            DeviceAuthorityResolver.compare(a, c, manifest) < 0,
                            "a<b and b<c must give a<c: $a, $b, $c",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun sorting_by_the_comparison_gives_one_answer_whatever_the_input_order() {
        val events = eventsFrom(history(seed = 13, size = 30))
        val expected = events.sortedWith { a, b -> DeviceAuthorityResolver.compare(a, b, manifest) }

        repeat(100) { round ->
            val shuffled = events.shuffled(Random(round))
            assertEquals(
                expected,
                shuffled.sortedWith { a, b -> DeviceAuthorityResolver.compare(a, b, manifest) },
            )
        }
    }

    // ------------------------------------------------- the invariants themselves

    /**
     * The property the restrictive-first rule exists for: among events nobody
     * built on, a withdrawal is never lost to a concurrent grant. Getting this
     * wrong the other way costs somebody a repeated tap; this way costs them
     * their privacy.
     */
    @Test
    fun a_concurrent_withdrawal_is_never_lost_to_a_concurrent_grant() {
        repeat(60) { seed ->
            val acts = history(seed, size = 20)
            val winners = AuthorityLedger.winners(trusted(acts, manifest))

            winners.forEach { (scope, winner) ->
                val inScope = acts.filter { it.scope == scope }
                val superseded = inScope.mapNotNull { it.parent }.toSet()
                val frontier = inScope.filter { it.id !in superseded }
                if (frontier.any { it.effect == AuthorityEffect.RESTRICTIVE }) {
                    assertEquals(
                        AuthorityEffect.RESTRICTIVE,
                        winner.effect,
                        "a restrictive act on the frontier of $scope must win at seed $seed",
                    )
                }
            }
        }
    }

    @Test
    fun the_winner_is_always_an_act_that_was_actually_in_the_history() {
        repeat(40) { seed ->
            val acts = history(seed, size = 20)

            AuthorityLedger.winners(trusted(acts, manifest)).forEach { (_, winner) ->
                assertTrue(winner in acts, "the resolver may not invent an act")
            }
        }
    }

    /**
     * Whose act may win is the manifest's business, not the act's.
     *
     * Note what this does *not* say: that only a device with the capability
     * wins. Capability is settled at admission, before anything is stored; the
     * resolver's job is to stay total and safe on whatever it is handed, and it
     * does that by ranking a lesser role last rather than by refusing to
     * answer. What it must never do is let a revoked or fenced device decide —
     * and it must not quietly carry on without it either, because a history
     * with one device's acts silently removed is a truncated history, which is
     * the same shape as a revocation that went missing.
     */
    @Test
    fun an_act_from_a_device_the_manifest_disowns_closes_the_whole_estate() {
        val stripped = manifest.copy(
            devices = manifest.devices.mapValues { (_, record) ->
                if (record.role == DeviceRole.TRUSTED) record.copy(role = DeviceRole.REVOKED) else record
            },
        )
        val disowned = stripped.devices.values
            .filter { stripped.roleOf(it.device) == null }
            .map { it.device }
            .toSet()
        assertTrue(disowned.isNotEmpty(), "the fixture has to actually disown somebody")

        repeat(40) { seed ->
            val acts = history(seed, size = 20)
            assertTrue(acts.any { it.device in disowned }, "and the history has to contain one of them")

            assertEquals(
                emptyMap(),
                AuthorityLedger.winners(trusted(acts, stripped)),
                "a disowned device does not merely lose; nothing can be read at all",
            )
            assertTrue(
                AuthorityLedger.winners(trusted(acts, manifest)).isNotEmpty(),
                "while the manifest still vouches for everybody, the same history decides plenty",
            )
        }
    }

    @Test
    fun fencing_a_device_closes_the_estate_too() {
        val fenced = manifest.copy(
            devices = manifest.devices.mapValues { (_, record) ->
                if (record.role == DeviceRole.PRIMARY) record.copy(fenced = true) else record
            },
        )

        repeat(30) { seed ->
            val acts = history(seed, size = 20)
            assertTrue(acts.any { fenced.devices.getValue(it.device).fenced })

            assertEquals(
                emptyMap(),
                AuthorityLedger.winners(trusted(acts, fenced)),
                "a fenced device must not decide anything, and nor may what is left of its history",
            )
        }
    }

    /**
     * The other side of it: fencing ends a device's say without poisoning what
     * it already signed.
     *
     * Authority rotates for reasons that fence every device present — a
     * recovery is one — and every act on disk was authored by one of them. Read
     * as "a fenced device's acts fail the estate closed", a recovery would
     * leave an install that can never be read again, including the entries the
     * returning device is writing right now. So an act stands for the
     * generation its author held, and for no later one.
     */
    @Test
    fun a_rotation_ends_a_devices_say_without_poisoning_what_it_signed() {
        val returning = AuthorityDeviceId("eeee")
        val newHead = LedgerEntryId("m-2")
        val afterRotation = manifest.devices.mapValues { (_, record) -> record.copy(fenced = true) } +
            (returning to DeviceRecord(returning, "pk-${returning.value}", DeviceRole.PRIMARY, 3))
        val rotated = manifest.copy(
            authorityGeneration = 3,
            head = newHead,
            devices = afterRotation,
            frontier = listOf(newHead),
            // Two points, as real entries: before the rotation, which the
            // superseded acts bind and where every device still stood, and
            // after it, which is the only place the returning device exists.
            history = manifest.readableAtItsOwnHead().history + listOf(
                DeviceManifestEntry(
                    id = LedgerEntryId("m-rotate"),
                    manifestSequence = 90,
                    authorityGeneration = 2,
                    parent = FIXTURE_MANIFEST_CONTEXT.frontier.single(),
                    signerNpub = "npub1owner",
                    signature = "sig",
                    body = DeviceManifestBody.AuthorityRotated(3, fenceExisting = true),
                ),
                DeviceManifestEntry(
                    id = newHead,
                    manifestSequence = 91,
                    authorityGeneration = 3,
                    parent = LedgerEntryId("m-rotate"),
                    signerNpub = "npub1owner",
                    signature = "sig",
                    body = DeviceManifestBody.DeviceEnrolled(returning, "pk-${returning.value}", DeviceRole.PRIMARY),
                ),
            ),
        )

        repeat(20) { seed ->
            val superseded = history(seed, size = 12)
            assertTrue(superseded.all { it.authorityGeneration == 2L }, "authored under the old generation")
            val fresh = AuthorityAttestation(
                id = LedgerEntryId("fresh-$seed"),
                scope = SCOPES[0],
                subject = LedgerEntryId("entry-fresh-$seed"),
                device = returning,
                parent = null,
                manifestContext = ManifestContext.of(listOf(newHead)),
                authorityGeneration = 3,
                capability = DeviceCapability.MUTATE_PERMISSIONS,
                effect = AuthorityEffect.RESTRICTIVE,
                signature = "pk-${returning.value}:fresh-$seed",
            )

            assertEquals(
                fresh,
                AuthorityLedger.winners(trusted(superseded + fresh, rotated))[SCOPES[0]],
                "the returning device decides, with the superseded history sitting beside it",
            )
            assertEquals(
                emptyMap(),
                AuthorityLedger.winners(
                    trusted(
                    superseded.map { it.copy(manifestContext = ManifestContext.of(listOf(newHead)), authorityGeneration = 3) } + fresh,
                    rotated,
                ),
                ),
                "but a fenced device claiming the generation it was fenced out of closes everything",
            )
        }
    }

    // ------------------------------------------------- the admission state machine

    /**
     * Drives admission through a long random sequence and checks the rules that
     * must hold after every single step, whatever came before.
     *
     * A rule that only holds on the paths somebody wrote a test for is not an
     * invariant, and admission is the one place where being wrong is permanent:
     * the ledger is append-only.
     */
    @Test
    fun admission_keeps_its_invariants_through_any_sequence_of_attempts() {
        repeat(25) { seed ->
            val random = Random(seed)
            val stored = mutableListOf<AuthorityAttestation>()

            repeat(60) { step ->
                val (device, role) = DEVICES[random.nextInt(DEVICES.size)]
                val id = LedgerEntryId("s$seed-$step")
                val capability = if (random.nextBoolean()) {
                    DeviceCapability.MUTATE_PERMISSIONS
                } else {
                    DeviceCapability.ADMINISTER_DEVICES
                }
                // Sometimes a parent that exists, sometimes one that does not,
                // sometimes a stale generation, sometimes a bad signature.
                val parent = when (random.nextInt(3)) {
                    0 -> null
                    1 -> stored.randomOrNull(random)?.id
                    else -> LedgerEntryId("nowhere-$step")
                }
                val generation = if (random.nextInt(4) == 0) 1L else 2L
                val signature = if (random.nextInt(5) == 0) "wrong" else "pk-${device.value}:${id.value}"

                val candidate = AuthorityAttestation(
                    id = id,
                    scope = SCOPES[random.nextInt(SCOPES.size)],
                    subject = LedgerEntryId("entry-${id.value}"),
                    device = device,
                    parent = parent,
                    manifestContext = FIXTURE_MANIFEST_CONTEXT,
                    authorityGeneration = generation,
                    capability = capability,
                    effect = if (random.nextBoolean()) {
                        AuthorityEffect.RESTRICTIVE
                    } else {
                        AuthorityEffect.PERMISSIVE
                    },
                    signature = signature,
                )

                when (AuthorityAttestationAdmission.check(stored, candidate, manifest, verifier)) {
                    is LedgerAdmission.Accept -> {
                        // Everything admission promises, checked rather than assumed.
                        assertTrue(
                            role.capabilities.contains(capability),
                            "an accepted act must be within the device's role",
                        )
                        assertEquals(
                            manifest.authorityGeneration,
                            candidate.authorityGeneration,
                            "an accepted act must be of the established generation",
                        )
                        assertTrue(
                            candidate.parent == null || stored.any { it.id == candidate.parent },
                            "an accepted act must build on something we hold",
                        )
                        assertTrue(verifier.verify(candidate, "pk-${device.value}"))
                        stored += candidate
                    }

                    is LedgerAdmission.AlreadyPresent ->
                        assertTrue(stored.any { it == candidate })

                    is LedgerAdmission.Reject -> Unit
                }

                // Nothing stored may ever be unreadable, and the resolver must
                // always return an act that is in the store or none at all.
                assertEquals(stored.size, stored.distinctBy { it.id }.size, "ids stay unique")
                AuthorityLedger.winners(trusted(stored, manifest)).forEach { (_, winner) ->
                    assertTrue(winner in stored)
                }
            }
        }
    }

    /**
     * The manifest is a line, so its state machine has a different invariant:
     * a revoked device never comes back, and the generation never goes down.
     */
    @Test
    fun the_manifest_never_un_revokes_a_device_or_rewinds_a_generation() {
        val ownerNpub = "npub1owner"
        val manifestVerifier = DeviceManifestVerifier { true }
        val reducer = DeviceManifestReducer(manifestVerifier, ownerNpub)

        repeat(25) { seed ->
            val random = Random(seed)
            val entries = mutableListOf<DeviceManifestEntry>()
            var generation = 1L
            val revokedIn = mutableMapOf<AuthorityDeviceId, Long>()
            var lastGeneration = 0L

            repeat(20) { step ->
                val (device, _) = DEVICES[random.nextInt(DEVICES.size)]
                val body: DeviceManifestBody = when (random.nextInt(4)) {
                    0 -> DeviceManifestBody.DeviceEnrolled(device, "pk-${device.value}", DeviceRole.TRUSTED)
                    1 -> DeviceManifestBody.DeviceRoleChanged(device, DeviceRole.PRIMARY)
                    2 -> DeviceManifestBody.DeviceRevoked(device)
                    else -> DeviceManifestBody.AuthorityRotated(generation + 1, fenceExisting = false)
                }
                val candidate = DeviceManifestEntry(
                    id = LedgerEntryId("m$seed-$step"),
                    manifestSequence = entries.size + 1L,
                    authorityGeneration = generation,
                    parent = entries.lastOrNull()?.id,
                    signerNpub = ownerNpub,
                    signature = "sig",
                    body = body,
                )
                val verdict = DeviceManifestAdmission.check(entries, candidate, manifestVerifier, ownerNpub)
                if (verdict is LedgerAdmission.Accept) {
                    entries += candidate
                    if (body is DeviceManifestBody.AuthorityRotated) generation = body.generation
                    if (body is DeviceManifestBody.DeviceRevoked) revokedIn[device] = generation
                    if (body is DeviceManifestBody.DeviceEnrolled) revokedIn.remove(device)
                }

                val reduced = reducer.reduce(entries)
                assertEquals(null, reduced.failClosedReason, "an admitted manifest must stay readable")
                // Within the generation it was revoked under. A sovereign
                // reset mints a new generation and the estate starts again —
                // holding stickiness across that would leave the device
                // performing the reset unable to enrol itself.
                revokedIn.forEach { (revoked, gen) ->
                    if (reduced.authorityGeneration <= gen) {
                        assertTrue(
                            reduced.roleOf(revoked) == null,
                            "a revoked device must not come back at seed $seed step $step",
                        )
                    }
                }
                assertTrue(
                    reduced.authorityGeneration >= lastGeneration,
                    "the authority generation must never go backwards",
                )
                lastGeneration = reduced.authorityGeneration
            }

            assertNotNull(reducer.reduce(entries))
        }
    }
}

private fun <T> List<T>.randomOrNull(random: Random): T? =
    if (isEmpty()) null else this[random.nextInt(size)]
