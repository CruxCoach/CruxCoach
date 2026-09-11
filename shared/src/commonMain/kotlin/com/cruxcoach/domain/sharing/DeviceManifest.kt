package com.cruxcoach.domain.sharing

/**
 * FEAT-062 §11: the owner-signed, append-only manifest of the owner's own
 * devices.
 *
 * This is the root of device authority. Nothing else in the feature may decide
 * that a device exists, what role it holds, or which authority generation is
 * current — those are read off the reduced manifest, so a manifest that
 * reduced loosely would hand out authority nobody granted.
 *
 * It is chained as well as sequenced: each entry names the entry it was
 * authored on top of, and carries that entry's sequence plus one.
 *
 * ## Why it forks
 *
 * It used to refuse a fork outright, on the grounds that the manifest has one
 * writer — the owner's root identity — so a second entry claiming the same
 * place was evidence of a problem rather than of concurrency. That was only
 * ever true of the *signature*. The root key is reachable from every device the
 * owner holds, so two of them offline both ask it to sign, both produce "next
 * sequence, parent head", and the second to arrive was refused. Whichever
 * device reached the database first decided who administers the estate — the
 * one thing [DeviceAuthorityResolver] exists to eliminate.
 *
 * So siblings on one parent are admitted, exactly as on the other two ledgers,
 * and the same resolver settles them **per scope**: each of the owner's devices
 * has its own, and only rotations and sovereign resets are about the estate as
 * a whole. Enrolling a tablet and revoking a phone are independent facts and
 * both stand; two changes to *one* device compete, and the restrictive one
 * wins.
 */

/** Checks that a manifest entry really was signed by whom it claims. */
fun interface DeviceManifestVerifier {
    fun verify(entry: DeviceManifestEntry): Boolean
}

/** What one manifest entry says. */
sealed interface DeviceManifestBody {

    /** Adds a device, or re-enrols one after a rotation, under [role]. */
    data class DeviceEnrolled(
        val device: AuthorityDeviceId,
        val publicKey: String,
        val role: DeviceRole,
    ) : DeviceManifestBody

    /** Moves an existing device between roles. */
    data class DeviceRoleChanged(
        val device: AuthorityDeviceId,
        val role: DeviceRole,
    ) : DeviceManifestBody

    /** Withdraws a device for good. Sticky. */
    data class DeviceRevoked(val device: AuthorityDeviceId) : DeviceManifestBody

    /**
     * Establishes a new authority generation — what a restore performs.
     *
     * [fenceExisting] is the restore default: a device that was authorised
     * before the backup was taken must not silently keep working afterwards,
     * because the backup cannot know what happened to it in between.
     */
    data class AuthorityRotated(
        val generation: Long,
        val fenceExisting: Boolean,
    ) : DeviceManifestBody

    /**
     * Revokes and fences the entire estate and mints a fresh authority.
     *
     * The one move available when the owner has lost track of which devices are
     * theirs. Deliberately not parameterised: a reset that spared something
     * would not be a reset.
     */
    data object SovereignReset : DeviceManifestBody

    /** A body this build cannot read. Never ignored — see the reducer. */
    data class Unknown(val kind: String) : DeviceManifestBody
}

/** One entry of the manifest. */
data class DeviceManifestEntry(
    val id: LedgerEntryId,
    /** Strictly increasing from 1, gap-free. */
    val manifestSequence: Long,
    /** The generation this entry was authored under. */
    val authorityGeneration: Long,
    /** The entry this one follows; `null` only for the genesis. */
    val parent: LedgerEntryId?,
    val signerNpub: String,
    val signature: String,
    val body: DeviceManifestBody,
)

/**
 * Folds the manifest into the state everything else reads.
 *
 * Every refusal below produces a [DeviceAuthorityState.failClosedReason], and a
 * failed manifest grants **nothing to anybody** — not even to devices whose own
 * entries were fine. That is severe on purpose: once the chain is broken there
 * is no evidence-based way to choose which half to trust, and choosing wrongly
 * means a device the owner revoked keeps its authority.
 *
 * ## Two signatures, and where each is checked
 *
 * The root signature says the owner asked for the change; it is required on
 * every entry without exception. The **device** act says which of the owner's
 * devices asked, and is what the resolver orders a fork by — so it is required
 * on every ordinary mutation and checked against the estate as it stood at that
 * entry's **parent**. Checking it against the state the entry produces would
 * let an enrolment be authorised by the device it enrols, and would judge a
 * fork against a merge that exists only because both sides were let in.
 *
 * The two moves that carry no act are the two where no device could sign one: a
 * genesis, where the estate does not exist yet, and a sovereign recovery, where
 * the root key has just revoked everything — including whatever device is
 * performing it. Both are root-signature-only by construction, not by a flag a
 * caller passes.
 */
class DeviceManifestReducer(
    private val verifier: DeviceManifestVerifier,
    private val ownerNpub: String,
    /**
     * Checks the device act on an ordinary mutation.
     *
     * Refuses everything by default, for the same reason the repository's does:
     * an install that has wired no verifier has no device authority, and that
     * has to lock the manifest rather than open it.
     */
    private val attestationVerifier: AuthorityAttestationVerifier =
        AuthorityAttestationVerifier { _, _ -> false },
) {

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
    fun reduce(
        entries: List<DeviceManifestEntry>,
        acts: List<AuthorityAttestation> = emptyList(),
    ): DeviceAuthorityState {
        // Byte-identical duplicates are noise from a replay, not a fork.
        val unique = entries.distinct()
        if (unique.isEmpty()) return DeviceAuthorityState(0, null, emptyMap())

        val byId = unique.associateBy { it.id }
        if (byId.size != unique.size) {
            return failed("two manifest entries claim one id")
        }

        // ------------------------------------------------ shape, then order
        unique.forEach { entry ->
            if (entry.signerNpub != ownerNpub) {
                return failed("manifest entry ${entry.id.value} was signed by ${entry.signerNpub}, not the owner")
            }
            if (!verifier.verify(entry)) {
                return failed("manifest entry ${entry.id.value} does not verify")
            }
            val parent = entry.parent
            if (parent == null) {
                // One estate, one beginning. A second root is not a fork — a
                // fork shares a history — it is two estates in one table, and
                // nothing in the evidence says which of them is the owner's.
                if (unique.count { it.parent == null } > 1) {
                    return failed("the manifest has more than one root; that is two histories, not a fork")
                }
                if (entry.manifestSequence != 1L) {
                    return failed(
                        "manifest entry ${entry.id.value} begins a history at sequence " +
                            "${entry.manifestSequence}, not 1",
                    )
                }
            } else {
                val above = byId[parent]
                    ?: return failed(
                        "manifest entry ${entry.id.value} follows ${parent.value}, which is not stored",
                    )
                if (entry.manifestSequence != above.manifestSequence + 1L) {
                    return failed(
                        "manifest entry ${entry.id.value} has sequence ${entry.manifestSequence}, " +
                            "which does not follow its parent's ${above.manifestSequence}",
                    )
                }
            }
        }
        unique.forEach { entry ->
            val seen = mutableSetOf(entry.id)
            var cursor = entry.parent
            while (cursor != null) {
                if (!seen.add(cursor)) return failed("the manifest chain through ${entry.id.value} contains a cycle")
                cursor = byId[cursor]?.parent
            }
        }

        // Deliberately no per-entry ancestry fold here. An entry carries one
        // parent, so its own chain is a single branch — but after a merge the
        // estate it was written against is the *union*, and that is what its
        // act's context names. Folding one branch would refuse an entry that
        // re-roles a device the other branch enrolled, which is a legitimate
        // change on a merged estate. The context below, and the authorised
        // fold at the end, are what decide.

        // ------------------------------------------------ the authorising act
        //
        // The acts about this manifest are a DAG in their own right, and the
        // same check the other two ledgers run applies here first: exactly one
        // act per subject that needs one, saying exactly what that subject's
        // signed body requires, with a parent that exists in the same scope and
        // no cycles. Resolving what is left of a malformed set would hand
        // somebody a truncated manifest, which has the shape of a revocation
        // that quietly disappeared.
        val mine = acts.distinctBy { it.id }.filter { it.subject in byId }
        AuthorityDagValidation.check(
            acts = mine,
            knownSubjects = byId.keys,
            requirements = unique.filterNot { rootOnly(it, byId) }
                .mapNotNull { e -> AuthorityPairing.requiredFor(e.body)?.let { e.id to it } }
                .toMap(),
        )?.let { return failed("the manifest's attestations do not form a readable history: $it") }

        val actBySubject = mine.associateBy { it.subject }
        val actById = mine.associateBy { it.id }
        // The role each event's author held at the head its own act names.
        // Not one synthetic manifest folded from every act: a device that
        // authored twice contributed twice, and which record survived depended
        // on the order the acts happened to be in.
        val roleAtItsOwnHead = mutableMapOf<LedgerEntryId, DeviceRole>()
        unique.forEach { entry ->
            val required = AuthorityPairing.requiredFor(entry.body)
                ?: return failed("manifest entry ${entry.id.value} has no authority requirement")
            if (rootOnly(entry, byId)) return@forEach

            val act = actBySubject[entry.id]
                ?: return failed("manifest entry ${entry.id.value} is authorised by no device attestation")
            if (act.scope != required.scope || act.effect != required.effect ||
                act.capability != required.capability
            ) {
                return failed(
                    "attestation ${act.id.value} does not say what manifest entry " +
                        "${entry.id.value}'s signed body requires",
                )
            }
            // Against the estate *before* this entry, never against what it
            // produces: a change to the manifest cannot be its own
            // justification. The context is the whole frontier its author saw,
            // which after a merge is more than one branch.
            val context = act.manifestContext
            context.frontier.forEach { id ->
                if (id !in byId) {
                    return failed(
                        "attestation ${act.id.value} binds manifest entry ${id.value}, which is not stored",
                    )
                }
            }
            val closure = context.frontier.flatMap { ancestorIdsOf(it, byId) }.toSet()
            context.frontier.forEach { id ->
                context.frontier.forEach { other ->
                    if (id != other && id in ancestorIdsOf(other, byId)) {
                        return failed(
                            "attestation ${act.id.value} binds ${id.value}, which is an ancestor of " +
                                "${other.value}; a frontier is the entries nothing else follows",
                        )
                    }
                }
            }
            if (entry.id in closure) {
                return failed(
                    "attestation ${act.id.value} binds a context containing manifest entry " +
                        "${entry.id.value}, which it is what authorises",
                )
            }
            entry.parent?.let { parent ->
                if (parent !in closure) {
                    return failed(
                        "attestation ${act.id.value} binds a context that does not reach manifest entry " +
                            "${entry.id.value}'s parent ${parent.value}",
                    )
                }
            }
            if (entry.parent == null && context.frontier.isNotEmpty()) {
                return failed("attestation ${act.id.value} binds a context, but ${entry.id.value} is a genesis")
            }
            val asOf = ManifestFold.apply(
                closure.mapNotNull { byId[it] }
                    .sortedWith(compareBy({ it.manifestSequence }, { it.id.value })),
            )
            asOf.failure?.let { return failed(it) }
            val record = asOf.devices[act.device]
                ?: return failed(
                    "manifest entry ${entry.id.value} was asked for by ${act.device.value}, " +
                        "which the manifest did not name at that point",
                )
            val role = DeviceAuthorityState(asOf.generation, entry.parent, asOf.devices).roleOf(act.device)
                ?: return failed(
                    "manifest entry ${entry.id.value} was asked for by ${act.device.value}, " +
                        "which held no authority at that point",
                )
            if (!role.capabilities.contains(required.capability)) {
                return failed(
                    "manifest entry ${entry.id.value} needs ${required.capability}, " +
                        "which ${act.device.value} did not hold as $role",
                )
            }
            if (act.authorityGeneration != asOf.generation) {
                return failed(
                    "attestation ${act.id.value} claims authority generation " +
                        "${act.authorityGeneration}, but its manifest head establishes ${asOf.generation}",
                )
            }
            if (!attestationVerifier.verify(act, record.publicKey)) {
                return failed("attestation ${act.id.value} does not verify under ${act.device.value}'s key")
            }
            // Established here, from evidence already checked, so ranking
            // cannot reach for anything the reader has not validated.
            roleAtItsOwnHead[entry.id] = role
        }

        // ------------------------------------------------ resolve, per scope
        //
        // One resolver and one total order, with the rank looked up per event
        // from the role its author held at the head its act names — a fork is
        // judged by the estate as it stood where it forked, not by whatever the
        // merge happens to say.
        val generationCeiling = DeviceAuthorityState(
            authorityGeneration = unique.maxOfOrNull { it.authorityGeneration } ?: 0L,
            head = null,
            devices = emptyMap(),
        )
        val rankOf: (AuthorityEvent) -> DeviceRole? = { roleAtItsOwnHead[it.eventId] }

        val authorised = linkedSetOf<LedgerEntryId>()
        unique.filter { rootOnly(it, byId) }.forEach { authorised += it.id }
        actBySubject.values.map { it.scope }.distinct().sortedBy { it.value }.forEach { scope ->
            val events = actBySubject.values.filter { it.scope == scope }.map { act ->
                AuthorityEvent(
                    eventId = act.subject,
                    parent = act.parent?.let { actById[it]?.subject },
                    authorityGeneration = act.authorityGeneration,
                    device = act.device,
                    effect = act.effect,
                )
            }
            var cursor = DeviceAuthorityResolver.resolve(events, generationCeiling, rankOf)
                ?.let { byId[it.eventId] }
            val seen = mutableSetOf<LedgerEntryId>()
            while (cursor != null && seen.add(cursor.id)) {
                authorised += cursor.id
                cursor = actBySubject[cursor.id]?.parent?.let { actById[it] }?.subject?.let { byId[it] }
            }
        }

        // Canonical, so every device folds the same list the same way.
        val standing = authorised.mapNotNull { byId[it] }
            .sortedWith(compareBy({ it.manifestSequence }, { it.id.value }))
        val folded = ManifestFold.apply(standing)
        folded.failure?.let { return failed(it) }
        // The frontier is every authorised entry nothing else authorised
        // follows — the whole of what stands, which is what an act has to bind.
        // `head` is kept only as the tip a new entry chains onto; it never says
        // what the estate *is*.
        val followed = standing.mapNotNull { it.parent }.toSet()
        return DeviceAuthorityState(
            authorityGeneration = folded.generation,
            head = standing.lastOrNull()?.id,
            devices = folded.devices,
            frontier = standing.map { it.id }.filterNot { it in followed }.sortedBy { it.value },
            history = standing,
        )
    }

    /**
     * The two moves no device could have signed for.
     *
     * A genesis, where there is no estate yet, and a sovereign recovery — the
     * rotation or reset itself, and the enrolment that immediately completes
     * it, by which point the root key has revoked everything including the
     * device performing it. Both are root-signature-only because of what they
     * are, not because a caller said so.
     */
    private fun rootOnly(entry: DeviceManifestEntry, byId: Map<LedgerEntryId, DeviceManifestEntry>): Boolean {
        if (entry.parent == null) return true
        if (entry.body is DeviceManifestBody.AuthorityRotated) return true
        if (entry.body is DeviceManifestBody.SovereignReset) return true
        val above = byId[entry.parent]?.body
        return above is DeviceManifestBody.AuthorityRotated || above is DeviceManifestBody.SovereignReset
    }

    /** [id] and everything it follows, by parent link. */
    private fun ancestorIdsOf(
        id: LedgerEntryId,
        byId: Map<LedgerEntryId, DeviceManifestEntry>,
    ): Set<LedgerEntryId> {
        val seen = linkedSetOf<LedgerEntryId>()
        var cursor: LedgerEntryId? = id
        while (cursor != null && seen.add(cursor)) cursor = byId[cursor]?.parent
        return seen
    }

    private fun failed(reason: String) =
        DeviceAuthorityState(0, null, emptyMap(), failClosedReason = reason)
}

/**
 * Applies a causal, canonically ordered run of manifest entries.
 *
 * One implementation, shared by the reducer and by [DeviceAuthorityState.at]:
 * replaying a bound context has to produce exactly what the reduction produced,
 * and two folds would eventually be two answers.
 */
internal object ManifestFold {

    /** What a run of entries, folded, leaves behind. */
    data class Folded(
        val generation: Long,
        val devices: Map<AuthorityDeviceId, DeviceRecord>,
        val failure: String? = null,
    )

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    fun apply(chain: List<DeviceManifestEntry>): Folded {
        var generation = 0L
        val devices = mutableMapOf<AuthorityDeviceId, DeviceRecord>()

        chain.forEachIndexed { index, entry ->
            fun fail(reason: String) = Folded(generation, devices.toMap(), reason)

            // A rotation *establishes* the new generation, so it is authored
            // under the old one; everything else must match the current.
            val rotation = entry.body as? DeviceManifestBody.AuthorityRotated
            val expectedGeneration = if (index == 0) entry.authorityGeneration else generation
            if (entry.body !is DeviceManifestBody.SovereignReset &&
                entry.authorityGeneration != expectedGeneration
            ) {
                return fail(
                    "manifest entry ${entry.id.value} was authored under generation " +
                        "${entry.authorityGeneration}, but the current authority is $expectedGeneration",
                )
            }

            when (val body = entry.body) {
                is DeviceManifestBody.DeviceEnrolled -> {
                    // Revocation is sticky within a generation: withdrawing
                    // trust from a device that may be lost or seized must not
                    // be undoable by a later entry, or revocation is only
                    // advice. Across generations it is not, because a sovereign
                    // reset revokes everything including the device performing
                    // it — and a rule that held there would leave an estate
                    // nobody could act in.
                    val existing = devices[body.device]
                    val stillRevoked = existing?.role == DeviceRole.REVOKED &&
                        (existing.revokedInGeneration ?: 0L) >= entry.authorityGeneration
                    if (!stillRevoked) {
                        devices[body.device] = DeviceRecord(
                            device = body.device,
                            publicKey = body.publicKey,
                            role = body.role,
                            enrolledInGeneration = if (index == 0) entry.authorityGeneration else generation,
                            fenced = false,
                            revokedInGeneration = null,
                        )
                    }
                }

                is DeviceManifestBody.DeviceRoleChanged -> {
                    val existing = devices[body.device]
                        ?: return fail("manifest entry ${entry.id.value} re-roles a device that was never enrolled")
                    if (existing.role != DeviceRole.REVOKED) {
                        devices[body.device] = existing.copy(role = body.role)
                    }
                }

                is DeviceManifestBody.DeviceRevoked -> {
                    val existing = devices[body.device]
                        ?: return fail("manifest entry ${entry.id.value} revokes a device that was never enrolled")
                    devices[body.device] = existing.copy(
                        role = DeviceRole.REVOKED,
                        fenced = true,
                        revokedInGeneration = generation,
                    )
                }

                is DeviceManifestBody.AuthorityRotated -> {
                    if (body.generation <= generation) {
                        return fail(
                            "authority generation ${body.generation} does not advance on $generation",
                        )
                    }
                    generation = body.generation
                    if (body.fenceExisting) {
                        devices.keys.toList().forEach { key ->
                            devices[key] = devices.getValue(key).copy(fenced = true)
                        }
                    }
                }

                DeviceManifestBody.SovereignReset -> {
                    // Revoked under the generation the reset *supersedes*, so
                    // the enrolment that follows it — under the new one — can
                    // re-establish the estate. Recording the new generation
                    // here would make the reset unrecoverable: the device
                    // performing it could never enrol itself again.
                    val supersededGeneration = generation
                    generation += 1
                    devices.keys.toList().forEach { key ->
                        devices[key] = devices.getValue(key).copy(
                            role = DeviceRole.REVOKED,
                            fenced = true,
                            revokedInGeneration = supersededGeneration,
                        )
                    }
                }

                is DeviceManifestBody.Unknown ->
                    // Skipping it would mean reducing a manifest whose meaning
                    // this build does not know — the change might be the very
                    // revocation that matters.
                    return fail("manifest entry ${entry.id.value} has an unreadable body ${body.kind}")
            }

            if (index == 0 && rotation == null) generation = entry.authorityGeneration
        }

        return Folded(generation, devices.toMap())
    }
}
