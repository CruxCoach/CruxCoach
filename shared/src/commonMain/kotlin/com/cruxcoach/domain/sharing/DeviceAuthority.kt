package com.cruxcoach.domain.sharing

/**
 * FEAT-062 §11: which of the owner's own devices may change what, and whose
 * change stands when two of them disagree.
 *
 * The relationship ledger answers "may this peer see this". This file answers
 * the question underneath it: "may this *device of mine* say so at all", and
 * "if two of my devices said different things without seeing each other, which
 * is true". Both have to be answerable from signed evidence alone, identically
 * on every device, for ever — in live reduction, in replay, and after a
 * restore.
 */

/**
 * Stable identity of one of the **owner's own** devices.
 *
 * Deliberately not [DeviceId], which names a device belonging to a *peer*. The
 * two are used within a few lines of each other in the purge and grant paths
 * and mean opposite things; giving them one type is how a peer's device would
 * end up holding owner authority.
 */
data class AuthorityDeviceId(val value: String) {
    init { require(value.isNotBlank()) { "AuthorityDeviceId must not be blank" } }
}

/**
 * What one of the owner's devices is allowed to do.
 *
 * Ordered most to least authority; [rank] is that order and is derived here
 * rather than carried on an event, so no client can promote itself.
 */
enum class DeviceRole {
    /** Full authority, including enrolling devices and a sovereign reset. */
    PRIMARY,

    /** May change permissions, but not the device manifest itself. */
    TRUSTED,

    /** May read the reduced state. Changes nothing. */
    READ_ONLY,

    /** Fenced. Carries no authority at all, and never regains it. */
    REVOKED,
    ;

    val rank: Int get() = ordinal

    /**
     * Derived, never stored. A capability set written into an event would be a
     * claim the event makes about itself; read off the role it is a fact about
     * the manifest.
     */
    val capabilities: Set<DeviceCapability>
        get() = when (this) {
            PRIMARY -> setOf(
                DeviceCapability.READ,
                DeviceCapability.MUTATE_PERMISSIONS,
                DeviceCapability.ADMINISTER_DEVICES,
                DeviceCapability.SOVEREIGN_RESET,
            )
            TRUSTED -> setOf(DeviceCapability.READ, DeviceCapability.MUTATE_PERMISSIONS)
            READ_ONLY -> setOf(DeviceCapability.READ)
            REVOKED -> emptySet()
        }

    /** True while this role may still author administrative changes. */
    val carriesAuthority: Boolean get() = this != REVOKED
}

/** One thing a device may be permitted to do. */
enum class DeviceCapability {
    READ,
    MUTATE_PERMISSIONS,
    ADMINISTER_DEVICES,
    SOVEREIGN_RESET,
}

/** One device as the manifest currently describes it. */
data class DeviceRecord(
    val device: AuthorityDeviceId,
    val publicKey: String,
    val role: DeviceRole,
    /** The authority generation this device was enrolled under. Provenance. */
    val enrolledInGeneration: Long,
    /**
     * Set by a rotation or a sovereign reset that did not re-enrol this device.
     *
     * Deliberately a recorded decision rather than something inferred from
     * `enrolledInGeneration < currentGeneration`: authority rotates for reasons
     * that re-affirm every device present, and inferring it there would fence
     * the whole estate — including the device doing the rotating — the instant
     * the generation moved. A fenced device keeps its row so the screen can say
     * *why* it stopped working, and carries no authority until re-enrolled.
     */
    val fenced: Boolean = false,
    /**
     * The generation this device's revocation was recorded under, if any.
     *
     * Revocation is sticky *within* a generation — that is what stops a device
     * that may be lost or seized being quietly reinstated. It cannot be sticky
     * across one, because a sovereign reset revokes everything including the
     * device performing it, and that device would then be unable to enrol
     * itself: the reset would leave an estate nobody could act in.
     */
    val revokedInGeneration: Long? = null,
)

/**
 * The whole manifest state an act was authored against: every maximal entry of
 * the authorised union, sorted.
 *
 * ## Why a set and not a head
 *
 * The manifest forks and independent scopes merge. Enrolling a tablet and
 * revoking a phone are siblings on one parent, and the state that stands is
 * their **union** — a single head names one branch of it and silently omits the
 * other. A device that signed against "the tablet is enrolled and the phone is
 * revoked" could then only record half of what it saw, leaving the reader to
 * guess the rest from whichever head looked canonical. Guessing is exactly what
 * must not decide who can see what.
 *
 * ## Why the act signs it, and what that does not prove
 *
 * Signing the context is what stops a *stored* act being edited afterwards: a
 * frontier cannot be narrowed or widened on disk without breaking the
 * signature, so what an act was admitted with is what it still says.
 *
 * It proves nothing about the author's intent at the time. A signature attests
 * to the frontier its author **declared**, not that the author had not seen
 * more — a device revoked on an independent branch can perfectly well sign a
 * context from before that branch existed, and every structural check passes.
 * What stops that is positional, not evidential: **live admission requires the
 * frontier standing now**, so an old partial frontier is refused at the door
 * rather than argued with afterwards. See [RequiredManifestContext].
 *
 * Old contexts stay meaningful only for acts already admitted under that rule,
 * and for a restored backup whose whole file was authenticated against the
 * owner's root key first.
 */
class ManifestContext private constructor(val frontier: List<LedgerEntryId>) {

    /**
     * `ccctx.v1|<n>:<len>:<id><len>:<id>…`
     *
     * Length-prefixed for the same reason [AuthorityScope] is: entry ids are
     * opaque strings, and a separator that can appear inside one is how two
     * different frontiers come to share an encoding.
     */
    val canonical: String
        get() = buildString {
            append(VERSION)
            append('|')
            append(frontier.size)
            append(':')
            frontier.forEach { id ->
                append(id.value.encodeToByteArray().size)
                append(':')
                append(id.value)
            }
        }

    override fun equals(other: Any?): Boolean =
        this === other || (other is ManifestContext && frontier == other.frontier)

    override fun hashCode(): Int = frontier.hashCode()

    override fun toString(): String = "ManifestContext($canonical)"

    companion object {
        private const val VERSION = "ccctx.v1"
        private const val ZERO = '0'.code.toByte()
        private const val NINE = '9'.code.toByte()
        private const val COLON = ':'.code.toByte()

        /**
         * Canonical by construction: distinct, and sorted by id.
         *
         * The constructor is private so this is the only way in. A public one
         * let an unsorted or duplicated list be a perfectly ordinary context,
         * unequal to the canonical value over the same ids — two values for one
         * frontier, and two byte forms an act could be signed under.
         */
        fun of(ids: Collection<LedgerEntryId>): ManifestContext =
            ManifestContext(ids.distinct().sortedBy { it.value })

        /** Before the genesis, where the estate does not exist yet. */
        val genesis: ManifestContext = ManifestContext(emptyList())

        /**
         * Rebuilt from [canonical]; `null` for anything that is not *exactly*
         * that.
         *
         * Deliberately not lenient. Accepting an unsorted or duplicated
         * encoding, a padded length, or trailing bytes would give one frontier
         * a second byte form — and the act's signature covers the bytes, so a
         * second form is a second thing that can be signed while meaning the
         * same. The input is untrusted, so every read is bounds-checked and
         * nothing here can throw: a malformed context is refused, not fatal.
         */
        @Suppress("ReturnCount")
        fun parse(canonical: String): ManifestContext? {
            val bytes = canonical.encodeToByteArray()
            val prefix = "$VERSION|".encodeToByteArray()
            if (bytes.size < prefix.size) return null
            prefix.indices.forEach { if (bytes[it] != prefix[it]) return null }

            var at = prefix.size

            // Digits then a colon, with no padding and no room to overflow.
            fun number(): Int? {
                var value = 0
                var digits = 0
                while (at < bytes.size && bytes[at] in ZERO..NINE) {
                    if (digits == 0 && bytes[at] == ZERO && at + 1 < bytes.size && bytes[at + 1] in ZERO..NINE) {
                        return null
                    }
                    val digit = bytes[at] - ZERO
                    if (value > (MAX_FIELD - digit) / 10) return null
                    value = value * 10 + digit
                    digits += 1
                    at += 1
                }
                if (digits == 0) return null
                if (at >= bytes.size || bytes[at] != COLON) return null
                at += 1
                return value
            }

            val count = number() ?: return null
            val ids = ArrayList<LedgerEntryId>(minOf(count, 64))
            repeat(count) {
                val length = number() ?: return null
                if (length <= 0 || bytes.size - at < length) return null
                val value = bytes.copyOfRange(at, at + length).decodeToString()
                // A value that does not re-encode to the same length was not
                // whole UTF-8, so the length prefix did not mean what it says.
                if (value.encodeToByteArray().size != length || value.isBlank()) return null
                at += length
                ids += LedgerEntryId(value)
            }
            if (at != bytes.size) return null
            // Strictly increasing: sorted, and no duplicates.
            if (ids.zipWithNext().any { (a, b) -> a.value >= b.value }) return null
            return ManifestContext(ids)
        }

        /** Far above any real frontier, and far below an Int overflow. */
        private const val MAX_FIELD = 1 shl 20
    }
}

/** The reduced device manifest: who exists, in what role, under which authority. */
data class DeviceAuthorityState(
    val authorityGeneration: Long,
    val head: LedgerEntryId?,
    val devices: Map<AuthorityDeviceId, DeviceRecord>,
    /** Non-null when the manifest could not be reduced safely. Grants nothing. */
    val failClosedReason: String? = null,
    /**
     * Every maximal entry of the authorised union: the context an act written
     * now has to bind.
     *
     * A list, not a head, because independent scopes merge — see
     * [ManifestContext].
     */
    val frontier: List<LedgerEntryId> = emptyList(),
    /**
     * The authorised entries this reduced from, so a bound context can be
     * replayed exactly rather than approximated.
     *
     * Only the authorised ones: a branch that lost decides nothing, so nothing
     * may be signed against it either.
     */
    val history: List<DeviceManifestEntry> = emptyList(),
) {

    /**
     * The estate as it stood at [context] — the fold of the ancestor closure of
     * every id it names.
     *
     * Fail-closed on everything it cannot place exactly:
     *
     * - an id this install does not hold, which may be *future* and may never
     *   have existed, and the two cannot be told apart;
     * - an id no authorised branch holds, because a branch that lost decides
     *   nothing and signing against it would let a device pick the estate it
     *   preferred;
     * - a frontier that is not an antichain, where one id is an ancestor of
     *   another — redundant, and two encodings for one union.
     *
     * What it deliberately does **not** demand is that the context equal the
     * frontier standing *now*. An act signed before an independent sibling
     * arrived keeps naming what its author actually saw; exactness against the
     * present is an admission-time rule, where "now" is well defined.
     */
    @Suppress("ReturnCount")
    fun at(context: ManifestContext): DeviceAuthorityState {
        failClosedReason?.let { return this }
        // Before the genesis there is no estate, and that is a legitimate
        // place to sign against: it is where the genesis itself is authored.
        if (context.frontier.isEmpty()) return DeviceAuthorityState(0, null, emptyMap())

        fun closed(reason: String) = DeviceAuthorityState(0, null, emptyMap(), failClosedReason = reason)
        val byId = history.associateBy { it.id }
        context.frontier.forEach { id ->
            if (id !in byId) {
                return closed("manifest context names ${id.value}, which no authorised branch holds")
            }
        }

        fun ancestorsOf(id: LedgerEntryId): Set<LedgerEntryId> {
            val seen = linkedSetOf<LedgerEntryId>()
            var cursor: LedgerEntryId? = id
            while (cursor != null && seen.add(cursor)) cursor = byId[cursor]?.parent
            return seen
        }

        val closures = context.frontier.associateWith { ancestorsOf(it) }
        context.frontier.forEach { id ->
            context.frontier.forEach { other ->
                if (id != other && id in closures.getValue(other)) {
                    return closed(
                        "manifest context names ${id.value}, which is an ancestor of ${other.value}; " +
                            "a frontier is the entries nothing else follows",
                    )
                }
            }
        }

        val union = closures.values.flatten().toSet()
        val folded = ManifestFold.apply(
            union.mapNotNull { byId[it] }.sortedWith(compareBy({ it.manifestSequence }, { it.id.value })),
        )
        folded.failure?.let { return closed(it) }
        return DeviceAuthorityState(
            authorityGeneration = folded.generation,
            head = context.frontier.lastOrNull(),
            devices = folded.devices,
        )
    }
    /**
     * The role [device] actually holds right now, or `null` if it holds none.
     *
     * Fail-closed on three counts: a manifest that did not reduce grants
     * nothing, a device the manifest does not name is not ours, and a device
     * enrolled under a superseded generation is fenced until re-enrolled.
     */
    fun roleOf(device: AuthorityDeviceId): DeviceRole? {
        if (failClosedReason != null) return null
        val record = devices[device] ?: return null
        if (!record.role.carriesAuthority) return null
        if (record.fenced) return null
        return record.role
    }

    fun can(device: AuthorityDeviceId, capability: DeviceCapability): Boolean =
        roleOf(device)?.capabilities?.contains(capability) == true
}

/**
 * Whether an event takes access away or gives it.
 *
 * Derived from the body kind, which is inside the signature — not a field a
 * client fills in.
 */
enum class AuthorityEffect {
    /** Deny, revoke, expire, purge: takes access away. */
    RESTRICTIVE,

    /** Allow, grant: gives access. */
    PERMISSIVE,
}

/**
 * One administrative decision, reduced to exactly the terms that decide it.
 *
 * There is deliberately no timestamp, no sequence number a client chooses, and
 * no priority or weight. Every field here is either signed content or derived
 * from the manifest, so the winner cannot be influenced by a device that simply
 * asserts it should win.
 */
data class AuthorityEvent(
    val eventId: LedgerEntryId,
    /** The event this one was authored on top of; `null` only for a genesis. */
    val parent: LedgerEntryId?,
    val authorityGeneration: Long,
    val device: AuthorityDeviceId,
    val effect: AuthorityEffect,
)

/**
 * The single deterministic rule for "which change stands".
 *
 * Used unchanged by live reduction, by replay and by restore. One function, so
 * the three cannot drift apart — a divergence there would mean two devices
 * disagreeing about who can see what, with nothing to detect it.
 *
 * ## The order
 *
 * Causality is settled **first, by removing superseded events**, not inside the
 * comparison. An event whose descendant is also present was seen and built upon,
 * so it is not in conflict with anything — it is simply older. Doing this as a
 * filter rather than as a comparison step is what keeps [compare] a total order:
 * ancestry is partial, and a partial relation mixed into a comparator produces
 * intransitive results, which would let two devices sort the same set
 * differently.
 *
 * What remains is mutually concurrent, and is ordered by:
 *
 *  1. **higher valid authority generation** — re-established authority wins;
 *  2. **restrictive before permissive** — a concurrent withdrawal must never be
 *     silently lost to a concurrent grant. The other way round costs somebody a
 *     repeated tap; this way round costs somebody their privacy;
 *  3. **manifest-derived role rank** — PRIMARY before TRUSTED;
 *  4. **canonical device id**, then **canonical event id**.
 */
object DeviceAuthorityResolver {

    /**
     * The event that stands, or `null` when none of them carries authority.
     *
     * Anything the manifest does not vouch for is dropped before ordering: an
     * unknown device, a fenced or revoked one, or a generation the manifest
     * never established. Dropping rather than ranking matters — an unknown
     * device claiming a restrictive effect would otherwise be able to impose a
     * denial on everybody just by inventing an id.
     */
    fun resolve(
        events: List<AuthorityEvent>,
        manifest: DeviceAuthorityState,
        /**
         * The role the event's author held, as the reader has established it.
         *
         * Defaults to the role the manifest records *now*, which is right for
         * the permission ledgers: they are read against the estate as it
         * stands. The manifest's own forks are not — each of its events was
         * validated at the head its act names, and that is the role it must be
         * ranked by. Assembling those into one synthetic manifest instead made
         * a device that authored twice contribute twice, so which record
         * survived depended on the order the acts happened to be in.
         */
        roleOf: (AuthorityEvent) -> DeviceRole? = { manifest.roleOf(it.device) },
    ): AuthorityEvent? {
        val valid = events.distinctBy { it.eventId }.filter { isAuthorised(it, manifest, roleOf) }
        if (valid.isEmpty()) return null

        val superseded = supersededIds(valid)
        val frontier = valid.filterNot { it.eventId in superseded }
        // A cycle in the parent links would supersede everything; the whole set
        // is then untrustworthy and nothing wins.
        if (frontier.isEmpty()) return null

        return frontier.reduce { best, next -> if (compare(next, best, manifest, roleOf) < 0) next else best }
    }

    /**
     * Negative when [a] wins. A **total order** over concurrent events: every
     * term is total on its own, and the last is a unique id, so no two distinct
     * events ever compare equal.
     */
    fun compare(
        a: AuthorityEvent,
        b: AuthorityEvent,
        manifest: DeviceAuthorityState,
        roleOf: (AuthorityEvent) -> DeviceRole? = { manifest.roleOf(it.device) },
    ): Int {
        // 1. Higher generation first.
        if (a.authorityGeneration != b.authorityGeneration) {
            return b.authorityGeneration.compareTo(a.authorityGeneration)
        }
        // 2. Restrictive before permissive.
        if (a.effect != b.effect) {
            return if (a.effect == AuthorityEffect.RESTRICTIVE) -1 else 1
        }
        // 3. Manifest-derived rank. An unrepresented device sorts last rather
        //    than throwing, so `compare` stays total even off the happy path.
        val rankA = roleOf(a)?.rank ?: Int.MAX_VALUE
        val rankB = roleOf(b)?.rank ?: Int.MAX_VALUE
        if (rankA != rankB) return rankA.compareTo(rankB)
        // 4. Canonical ids.
        val byDevice = a.device.value.compareTo(b.device.value)
        if (byDevice != 0) return byDevice
        return a.eventId.value.compareTo(b.eventId.value)
    }

    /** True when the manifest vouches for this event's author and generation. */
    private fun isAuthorised(
        event: AuthorityEvent,
        manifest: DeviceAuthorityState,
        roleOf: (AuthorityEvent) -> DeviceRole?,
    ): Boolean {
        if (event.authorityGeneration > manifest.authorityGeneration) return false
        return roleOf(event) != null
    }

    /**
     * Ids that some other event in the set descends from.
     *
     * Walked with a visited set: a parent chain that loops is malformed, and
     * looping forever on malformed input is its own denial of service.
     */
    private fun supersededIds(events: List<AuthorityEvent>): Set<LedgerEntryId> {
        val byId = events.associateBy { it.eventId }
        val superseded = mutableSetOf<LedgerEntryId>()
        events.forEach { event ->
            val seen = mutableSetOf(event.eventId)
            var parent = event.parent
            while (parent != null && byId.containsKey(parent) && seen.add(parent)) {
                superseded += parent
                parent = byId[parent]?.parent
            }
        }
        return superseded
    }
}
