package com.cruxcoach.domain.competition

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * `cruxcoach-competition/1` — the wire contract, FEAT-058 §2, §3, §6.
 *
 * This is `commonMain` on purpose. The protocol, the reducer and the scoring
 * are the parts two clients must agree on byte-for-byte, so they live where
 * they can be unit-tested on the JVM against the same fixtures the website
 * runs, with no Android class anywhere near them. Signature verification stays
 * in the Android layer, where Quartz already owns the trust boundary.
 */
object CompetitionProtocol {
    const val KIND = 30078
    const val NAMESPACE = "com.cruxcoach.competition"
    const val SCHEMA = "cruxcoach-competition/1"
    const val SCHEMA_MAJOR = 1

    /** Same ceiling as `NostrEventPolicy.MAX_FUTURE_SKEW_SECONDS`. */
    const val MAX_FUTURE_SKEW_SECONDS = 3600L

    /** FEAT-058 §16.2 — half the tightest observed relay frame limit. */
    const val MAX_EVENT_BYTES = 65536

    val LIFECYCLE = listOf(
        "draft", "published", "registration_open", "registration_closed",
        "checkin_open", "running", "paused", "finished", "cancelled",
    )

    val LEGAL_TRANSITIONS: Map<String, List<String>> = mapOf(
        "draft" to listOf("published", "paused", "finished", "cancelled"),
        "published" to listOf("registration_open", "paused", "finished", "cancelled"),
        "registration_open" to listOf("registration_closed", "paused", "finished", "cancelled"),
        "registration_closed" to listOf("checkin_open", "paused", "finished", "cancelled"),
        "checkin_open" to listOf("running", "paused", "finished", "cancelled"),
        "running" to listOf("paused", "finished", "cancelled"),
        "paused" to listOf("running", "finished", "cancelled"),
        "finished" to emptyList(),
        "cancelled" to emptyList(),
    )

    val LOG_OPS = listOf(
        "lifecycle", "registration_decision", "payment_decision", "claim_decision",
        "checkin", "queue", "defer_decision", "attempt_result", "complete_turn", "correction",
        "override", "announcement", "disqualify", "prize_decision", "config_update",
    )

    val INTENT_OPS = listOf(
        "register", "withdraw", "checkin_request", "defer_request",
        "attempt_report", "climb_choice", "payment_claim", "prize_claim", "prize_receipt",
    )

    val QUEUE_ACTIONS = listOf(
        "seed", "seed_open", "open_turn", "close_turn", "advance", "reorder", "next_climb", "next_round",
    )
    val ATTEMPT_OUTCOMES = listOf("top", "zone", "fall", "pass", "timeout")
    val PAYMENT_STATES = listOf("not_required", "pending", "settled", "failed", "expired", "refunded")

    /**
     * Participant-bearing authority entries stay local unless the host crosses
     * this boundary explicitly. Missing means `local`, preserving privacy for
     * definitions created before the field existed.
     */
    val PARTICIPANT_DATA_VISIBILITIES = listOf("local", "online")

    fun participantDataVisibility(competition: Competition): String =
        competition.raw.str("participant_data_visibility") ?: "local"

    fun participantDataOnline(competition: Competition): Boolean =
        participantDataVisibility(competition) == "online"

    /** Stable default seeding shared by every authority UI. */
    fun defaultQueueOrder(compId: String, pubkeys: List<String>): List<String> =
        pubkeys.map { pubkey ->
            pubkey to ccjHash(JsonObject(mapOf("k" to JsonPrimitive(compId + pubkey))))
        }.sortedWith(compareBy<Pair<String, String>> { it.second }.thenBy { it.first })
            .map { it.first }

    /** What can happen to a prize in the public log — FEAT-058 §11.7. */
    val PRIZE_STATES = listOf("claimed", "approved", "paid", "rejected", "expired")

    /** How long a winner has to claim, when the organizer sets no deadline. */
    const val DEFAULT_PRIZE_CLAIM_DAYS = 30

    fun registrationWindowOpen(competition: Competition, status: String, at: Long): Boolean =
        status !in setOf("finished", "cancelled") &&
            at >= competition.registrationOpensAt && at <= competition.registrationClosesAt

    fun checkinWindowOpen(competition: Competition, status: String, at: Long): Boolean =
        status !in setOf("finished", "cancelled") &&
            at >= competition.checkinOpensAt && at <= competition.checkinClosesAt

    fun competitionRunning(competition: Competition, status: String, at: Long): Boolean =
        status !in setOf("paused", "finished", "cancelled") &&
            at >= competition.startsAt && at <= competition.endsAt

    /** An audit trail whose entries do not say why is a log, not an audit trail. */
    val REASON_REQUIRED_OPS = setOf("correction", "override", "disqualify", "config_update")

    private val COMP_ID = Regex("^[0-9a-f]{16}$")
    private val HEX32 = Regex("^[0-9a-f]{64}$")
    private val SEQ_SEGMENT = Regex("^\\d{6}$")
    private val HEX8 = Regex("^[0-9a-f]{8}$")

    fun isCompId(value: String?) = value != null && COMP_ID.matches(value)
    fun isHex32(value: String?) = value != null && HEX32.matches(value)

    fun compDTag(compId: String) = "cruxcoach:comp:$compId"
    fun logDTag(compId: String, seq: Int) = "cruxcoach:comp:$compId:log:${pad6(seq)}"
    fun snapDTag(compId: String, seq: Int) = "cruxcoach:comp:$compId:snap:${pad6(seq)}"
    fun resultsDTag(compId: String) = "cruxcoach:comp:$compId:results"
    fun intentDTag(compId: String, pubkey: String, nonce: String) =
        "cruxcoach:comp:$compId:intent:${pubkey.take(8)}:$nonce"

    fun competitionAddress(organizerPubkey: String, compId: String) =
        "$KIND:$organizerPubkey:${compDTag(compId)}"

    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]", "::1")

    /**
     * Which relay URLs this client will talk to: `wss://` anywhere, `ws://`
     * only for loopback.
     *
     * Cleartext WebSocket to a public host lets any network on the path rewrite
     * a competition's results in transit, so it is refused. Cleartext to
     * 127.0.0.1 has no network on the path by definition, and it is the only
     * way the development relay used by the localhost runbook can be reached at
     * all — a TLS certificate for a throwaway loopback port would be theatre.
     *
     * Must agree exactly with `competitions/app/protocol/relay-url.mjs`, or the
     * two clients disagree about which competitions are valid.
     */
    fun isLoopbackRelay(url: String): Boolean {
        if (!url.startsWith("ws://")) return false
        val rest = url.removePrefix("ws://")
        val host = rest.substringBefore('/').substringBefore('?')
        val withoutPort = if (host.startsWith("[")) {
            host.substring(0, host.indexOf(']') + 1)
        } else {
            host.substringBefore(':')
        }
        return withoutPort.lowercase() in LOOPBACK_HOSTS
    }

    fun isAllowedRelayUrl(url: String): Boolean {
        if (url.isEmpty() || url.any { it.isWhitespace() }) return false
        if (url.startsWith("wss://")) return url.length > "wss://".length
        return isLoopbackRelay(url)
    }

    /** True when a relay set contains a development relay — the UI must say so. */
    fun usesDevelopmentRelay(urls: List<String>): Boolean = urls.any { isLoopbackRelay(it) }

    private val CLIMB_UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val CLIMB_UUID_LEGACY = Regex("^[0-9a-f]{32}$")
    private val PLACEHOLDER_SEQUENCE = Regex("^0{7}[0-9a-f]0{4}40{3}80{3}0{12}$")

    fun isClimbUuid(value: String): Boolean {
        val v = value.lowercase()
        return CLIMB_UUID.matches(v) || CLIMB_UUID_LEGACY.matches(v)
    }

    /**
     * Placeholder climb ids that must never reach a published competition.
     *
     * A competition built on these cannot be climbed — the app has nothing to
     * load onto the wall — and they parse as perfectly good uuids, which is
     * exactly why they need naming. Must agree with `isPlaceholderUuid` in
     * `competitions/app/protocol/climb-ref.mjs`.
     */
    fun isPlaceholderUuid(value: String): Boolean {
        val normalized = value.lowercase().replace("-", "")
        if (!Regex("^[0-9a-f]{32}$").matches(normalized)) return false
        if (normalized.all { it == '0' }) return true
        if (normalized.all { it == normalized[0] }) return true
        if (PLACEHOLDER_SEQUENCE.matches(normalized)) return true
        // Every digit the same except the version nibble (index 12) and the
        // variant nibble (index 16) — the shape a placeholder takes when it is
        // dressed up to look like a real v4 uuid. Must agree with
        // `isPlaceholderUuid` in competitions/app/protocol/climb-ref.mjs.
        val free = normalized.substring(0, 12) + normalized.substring(13, 16) + normalized.substring(17)
        return free.all { it == free[0] }
    }

    private fun pad6(seq: Int): String {
        val text = seq.toString()
        return "0".repeat((6 - text.length).coerceAtLeast(0)) + text
    }

    /** What a competition d-tag addresses. */
    data class DTag(
        val compId: String,
        val kind: String,
        val seq: Int? = null,
        val pubkeyPrefix: String? = null,
        val nonce: String? = null,
    )

    fun parseDTag(dTag: String?): DTag? {
        if (dTag == null) return null
        val parts = dTag.split(":")
        if (parts.size < 3 || parts[0] != "cruxcoach" || parts[1] != "comp" || !isCompId(parts[2])) return null
        val compId = parts[2]
        return when {
            parts.size == 3 -> DTag(compId, "competition")
            parts.size == 5 && (parts[3] == "log" || parts[3] == "snap") -> {
                if (!SEQ_SEGMENT.matches(parts[4])) return null
                val seq = parts[4].toInt()
                if (seq < 1) return null
                DTag(compId, if (parts[3] == "log") "log" else "snapshot", seq = seq)
            }
            parts.size == 4 && parts[3] == "results" -> DTag(compId, "results")
            parts.size == 6 && parts[3] == "intent" -> {
                if (!HEX8.matches(parts[4]) || !HEX8.matches(parts[5])) return null
                DTag(compId, "intent", pubkeyPrefix = parts[4], nonce = parts[5])
            }
            else -> null
        }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    /**
     * Envelope gate (FEAT-058 §2.3).
     *
     * Signature and id verification must already have happened; this function
     * takes no "trust me" flag precisely so a caller cannot skip that step by
     * passing `true`.
     */
    fun classify(event: CompetitionEvent, nowSeconds: Long): Classified {
        if (event.kind != KIND) return Classified.Rejected("wrong kind")
        if (NAMESPACE !in event.tagValues("L")) return Classified.Rejected("not a competition namespace")
        val schema = event.tagValue("cc-schema") ?: return Classified.Rejected("missing cc-schema tag")
        val schemaParts = schema.split("/")
        val major = schemaParts.getOrNull(1)?.toIntOrNull()
        if (schemaParts.getOrNull(0) != "cruxcoach-competition" || major == null) {
            return Classified.Rejected("unreadable schema tag \"$schema\"")
        }
        if (major != SCHEMA_MAJOR) {
            return Classified.Rejected("schema version $major needs a newer CruxCoach", needsUpgrade = true)
        }
        if (event.createdAt > nowSeconds + MAX_FUTURE_SKEW_SECONDS) {
            return Classified.Rejected("created_at is too far in the future")
        }
        val dTag = parseDTag(event.tagValue("d")) ?: return Classified.Rejected("malformed d tag")
        val labelled = event.tags.firstOrNull { it.size >= 3 && it[0] == "l" && it[2] == NAMESPACE }?.getOrNull(1)
        if (labelled != dTag.kind) {
            return Classified.Rejected("label \"$labelled\" does not match d-tag shape \"${dTag.kind}\"")
        }
        if (event.content.length > MAX_EVENT_BYTES) return Classified.Rejected("content is too large")
        val payload = runCatching { json.parseToJsonElement(event.content).jsonObject }.getOrNull()
            ?: return Classified.Rejected("content is not a JSON object")
        if (payload["v"]?.jsonPrimitive?.intOrNull != SCHEMA_MAJOR) {
            return Classified.Rejected("payload version mismatch")
        }
        if (payload["type"]?.jsonPrimitive?.contentOrNullSafe() != dTag.kind) {
            return Classified.Rejected("payload type does not match d-tag")
        }
        val payloadCompId = payload["comp_id"]?.jsonPrimitive?.contentOrNullSafe()
        if (payloadCompId != null && payloadCompId != dTag.compId) {
            return Classified.Rejected("payload comp_id does not match d-tag")
        }
        return Classified.Accepted(dTag.kind, dTag, payload)
    }

    sealed interface Classified {
        data class Accepted(val type: String, val dTag: DTag, val payload: JsonObject) : Classified
        data class Rejected(val error: String, val needsUpgrade: Boolean = false) : Classified
    }

    /** Parse and validate a competition definition. */
    fun parseCompetition(event: CompetitionEvent, nowSeconds: Long): ParsedCompetition {
        val classified = classify(event, nowSeconds)
        if (classified is Classified.Rejected) {
            return ParsedCompetition.Invalid(classified.error, classified.needsUpgrade)
        }
        val accepted = classified as Classified.Accepted
        if (accepted.type != "competition") return ParsedCompetition.Invalid("not a competition definition")
        val competition = runCatching { Competition.from(accepted.payload) }.getOrElse {
            return ParsedCompetition.Invalid("invalid competition: ${it.message}")
        }
        val problems = CompetitionValidation.validate(competition)
        if (problems.isNotEmpty()) {
            return ParsedCompetition.Invalid("invalid competition: ${problems.first().field} ${problems.first().message}")
        }
        return ParsedCompetition.Valid(
            competition = competition,
            organizerPubkey = event.pubkey,
            eventId = event.id,
            address = competitionAddress(event.pubkey, competition.compId),
        )
    }

    sealed interface ParsedCompetition {
        data class Valid(
            val competition: Competition,
            val organizerPubkey: String,
            val eventId: String,
            val address: String,
        ) : ParsedCompetition

        data class Invalid(val error: String, val needsUpgrade: Boolean = false) : ParsedCompetition
    }

    /**
     * Parse a log entry and bind it to its competition. The binding is the
     * point: an entry that is well-formed but signed by someone who is not the
     * authority is not a log entry, it is someone else's event that looks like
     * one.
     */
    fun parseLogEntry(
        event: CompetitionEvent,
        competition: Competition,
        organizerPubkey: String,
        nowSeconds: Long,
    ): ParsedLogEntry {
        val classified = classify(event, nowSeconds)
        if (classified is Classified.Rejected) {
            return ParsedLogEntry.Invalid(classified.error, classified.needsUpgrade)
        }
        val accepted = classified as Classified.Accepted
        if (accepted.type != "log") return ParsedLogEntry.Invalid("not a log entry")
        if (event.pubkey != competition.authority) {
            return ParsedLogEntry.Invalid("not signed by the competition authority")
        }
        if (event.tagValue("a") != competitionAddress(organizerPubkey, competition.compId)) {
            return ParsedLogEntry.Invalid("a-tag does not reference this competition")
        }
        val payload = accepted.payload
        val seq = payload["seq"]?.jsonPrimitive?.intOrNull
            ?: return ParsedLogEntry.Invalid("seq must be a positive integer")
        if (seq != accepted.dTag.seq) return ParsedLogEntry.Invalid("seq does not match d-tag")
        if (seq < 1) return ParsedLogEntry.Invalid("seq must be a positive integer")
        val prev = payload["prev"]?.jsonPrimitive?.contentOrNullSafe()
        if (!isHex32(prev)) return ParsedLogEntry.Invalid("prev is not an event id")
        val op = payload["op"]?.jsonPrimitive?.contentOrNullSafe()
            ?: return ParsedLogEntry.Invalid("missing op")
        if (op !in LOG_OPS) return ParsedLogEntry.Invalid("unknown operation \"$op\"", needsUpgrade = true)
        val reason = payload["reason"]?.jsonPrimitive?.contentOrNullSafe()
        if (op in REASON_REQUIRED_OPS && reason.isNullOrEmpty()) {
            return ParsedLogEntry.Invalid("operation \"$op\" is missing its mandatory reason")
        }
        val data = payload["data"] as? JsonObject ?: return ParsedLogEntry.Invalid("data is missing")
        val epoch = payload["epoch"]?.jsonPrimitive?.intOrNull
        if (epoch == null || epoch < 1) return ParsedLogEntry.Invalid("epoch is missing")
        val at = payload["at"]?.jsonPrimitive?.longOrNull ?: event.createdAt
        return ParsedLogEntry.Valid(
            LogEntry(
                seq = seq,
                prev = prev!!,
                epoch = epoch,
                at = at,
                op = op,
                actor = payload["actor"]?.jsonPrimitive?.contentOrNullSafe() ?: "authority",
                reason = reason,
                data = data,
            ),
            eventId = event.id,
            createdAt = event.createdAt,
        )
    }

    sealed interface ParsedLogEntry {
        data class Valid(val entry: LogEntry, val eventId: String, val createdAt: Long) : ParsedLogEntry
        data class Invalid(val error: String, val needsUpgrade: Boolean = false) : ParsedLogEntry
    }

    fun parseIntent(
        event: CompetitionEvent,
        competition: Competition,
        organizerPubkey: String,
        nowSeconds: Long,
    ): ParsedIntent {
        val classified = classify(event, nowSeconds)
        if (classified is Classified.Rejected) return ParsedIntent.Invalid(classified.error, classified.needsUpgrade)
        val accepted = classified as Classified.Accepted
        if (accepted.type != "intent") return ParsedIntent.Invalid("not an intent")
        if (accepted.dTag.pubkeyPrefix != event.pubkey.take(8)) return ParsedIntent.Invalid("d-tag does not match the signer")
        if (event.tagValue("a") != competitionAddress(organizerPubkey, competition.compId)) {
            return ParsedIntent.Invalid("a-tag does not reference this competition")
        }
        if (event.tagValue("p") != competition.authority) return ParsedIntent.Invalid("p-tag does not reference the authority")
        val payload = accepted.payload
        val op = payload["op"]?.jsonPrimitive?.contentOrNullSafe() ?: return ParsedIntent.Invalid("missing op")
        if (op !in INTENT_OPS) return ParsedIntent.Invalid("unknown request \"$op\"", needsUpgrade = true)
        val nonce = payload["nonce"]?.jsonPrimitive?.contentOrNullSafe()
        if (nonce != accepted.dTag.nonce) return ParsedIntent.Invalid("nonce does not match d-tag")
        val data = payload["data"] as? JsonObject ?: return ParsedIntent.Invalid("data is missing")
        if (op == "climb_choice") {
            val climbId = data["climb_id"]?.jsonPrimitive?.contentOrNullSafe()
                ?: return ParsedIntent.Invalid("climb_choice is missing climb_id")
            if (competition.rules.climbSource != "participant_choice" || competition.climbPool.none { it.id == climbId }) {
                return ParsedIntent.Invalid("climb_choice does not reference the participant pool")
            }
        }
        val at = payload["at"]?.jsonPrimitive?.longOrNull ?: event.createdAt
        return ParsedIntent.Valid(op, data, event.pubkey, event.id, event.createdAt, at)
    }

    sealed interface ParsedIntent {
        data class Valid(
            val op: String, val data: JsonObject, val pubkey: String,
            val eventId: String, val createdAt: Long, val at: Long,
        ) : ParsedIntent
        data class Invalid(val error: String, val needsUpgrade: Boolean = false) : ParsedIntent
    }
}

/**
 * A Nostr event reduced to what the protocol layer needs.
 *
 * Deliberately not Quartz's `Event`: `:shared` must stay free of Android and of
 * the signing library, so the reducer can be tested on the JVM and reused by a
 * future iOS target.
 */
data class CompetitionEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
) {
    fun tagValue(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)
    fun tagValues(name: String): List<String> = tags.filter { it.size >= 2 && it[0] == name }.map { it[1] }
}

data class LogEntry(
    val seq: Int,
    val prev: String,
    val epoch: Int,
    val at: Long,
    val op: String,
    val actor: String,
    val reason: String?,
    val data: JsonObject,
)

// ── configuration model ──

data class CompetitionRules(
    val climbSource: String,
    val climbCount: Int,
    /** Best N results. Absent v1 events default to [climbCount]. */
    val countedClimbCount: Int,
    val selectionUniqueness: String,
    val progression: String,
    val attemptsPerClimb: Int,
    val turnDeadlineSec: Int,
    val attemptDeadlineSec: Int,
    val minRestSec: Int,
    val deferBudgetPerRound: Int,
    val maxConsecutiveDefers: Int,
    val deferSlots: Int,
    val scoring: String,
    val scorePoints: CompetitionScorePoints?,
    val tiebreaks: List<String>,
    val lateEntryAllowed: Boolean,
)

data class CompetitionScorePoints(val zone: Int, val top: Int, val flash: Int)

data class CompetitionClimb(
    val id: String,
    val climbUuid: String,
    val angle: Int,
    val label: String,
    val points: Int,
    /**
     * Placement id of the scoring zone hold. The organizer defines it even
     * when entrants choose from a pool: entrants choose a problem, never the
     * meaning of its result. New zone-scored competitions require it in their
     * creation UI. It stays nullable so older signed v1 competitions remain
     * readable.
     */
    val zoneHold: Int? = null,
)

data class CompetitionDivision(val id: String, val label: String)

/**
 * A prize a competition promises — FEAT-058 §11.7.
 *
 * `valueMsat` is what the organizer said they would pay, not money anybody
 * holds: CruxCoach has no pot and no escrow, and a cash prize is the
 * organizer's promise paid from their own wallet.
 */
data class CompetitionPrize(
    val id: String,
    val rank: Int,
    val kind: String,
    val label: String,
    val valueMsat: Long,
    val division: String?,
) {
    val isCash: Boolean get() = kind == "cash"
}

data class Competition(
    val compId: String,
    val authority: String,
    val authorityEpoch: Int,
    val title: String,
    val summary: String,
    val description: String,
    val visibility: String,
    val status: String,
    val timezone: String,
    val startsAt: Long,
    val endsAt: Long,
    val registrationOpensAt: Long,
    val registrationClosesAt: Long,
    val checkinOpensAt: Long,
    val checkinClosesAt: Long,
    val capacity: Int,
    val waitlistEnabled: Boolean,
    val feeMsat: Long,
    val feeLnurl: String?,
    val waiverRequired: Boolean,
    val revision: Int,
    val divisions: List<CompetitionDivision>,
    val climbs: List<CompetitionClimb>,
    /** What entrants may choose from, when the organizer let them choose. */
    val climbPool: List<CompetitionClimb>,
    val prizes: List<CompetitionPrize>,
    /** How long a winner has to claim, in days. */
    val prizeClaimDays: Int,
    val rules: CompetitionRules,
    val relays: List<String>,
    /** Everything else, for display without widening this class per field. */
    val raw: JsonObject,
) {
    /** A climb by id, from wherever this competition's climbs come from. */
    fun climb(id: String): CompetitionClimb? =
        climbs.firstOrNull { it.id == id } ?: climbPool.firstOrNull { it.id == id }

    /** Participant choice means the whole live pool; scoring keeps the best N. */
    fun climbsFor(selections: List<String>): List<CompetitionClimb> {
        @Suppress("UNUSED_VARIABLE") val ignoredLegacySelections = selections
        return if (rules.climbSource == "participant_choice") climbPool else climbs
    }

    companion object {
        fun from(payload: JsonObject): Competition {
            val rules = payload["rules"]?.jsonObject
                ?: throw IllegalArgumentException("rules is required")
            return Competition(
                compId = payload.str("comp_id") ?: throw IllegalArgumentException("comp_id is required"),
                authority = payload.str("authority") ?: throw IllegalArgumentException("authority is required"),
                authorityEpoch = payload.int("authority_epoch") ?: 1,
                title = payload.str("title").orEmpty(),
                summary = payload.str("summary").orEmpty(),
                description = payload.str("description").orEmpty(),
                visibility = payload.str("visibility").orEmpty(),
                status = payload.str("status").orEmpty(),
                timezone = payload.str("timezone").orEmpty(),
                startsAt = payload.long("starts_at") ?: 0L,
                endsAt = payload.long("ends_at") ?: 0L,
                registrationOpensAt = payload.long("registration_opens_at") ?: 0L,
                registrationClosesAt = payload.long("registration_closes_at") ?: 0L,
                checkinOpensAt = payload.long("checkin_opens_at") ?: 0L,
                checkinClosesAt = payload.long("checkin_closes_at") ?: 0L,
                capacity = payload.int("capacity") ?: 0,
                waitlistEnabled = payload.bool("waitlist_enabled") ?: false,
                feeMsat = payload.long("fee_msat") ?: 0L,
                feeLnurl = payload.str("fee_lnurl"),
                waiverRequired = payload.bool("waiver_required") ?: false,
                revision = payload.int("revision") ?: 1,
                divisions = payload["divisions"]?.jsonArray.orEmpty().map {
                    val obj = it.jsonObject
                    CompetitionDivision(obj.str("id").orEmpty(), obj.str("label").orEmpty())
                },
                climbs = payload["climbs"]?.jsonArray.orEmpty().map(::climbFrom),
                climbPool = payload["climb_pool"]?.jsonObject
                    ?.get("options")?.jsonArray.orEmpty().map(::climbFrom),
                prizes = payload["prizes"]?.jsonArray.orEmpty().mapNotNull { element ->
                    val obj = element.jsonObject
                    val id = obj.str("id") ?: return@mapNotNull null
                    CompetitionPrize(
                        id = id,
                        rank = obj.int("rank") ?: 0,
                        kind = obj.str("kind").orEmpty(),
                        label = obj.str("label").orEmpty(),
                        valueMsat = obj.long("value_msat") ?: 0L,
                        division = obj.str("division"),
                    )
                },
                prizeClaimDays = payload.int("prize_claim_days")
                    ?: CompetitionProtocol.DEFAULT_PRIZE_CLAIM_DAYS,
                rules = CompetitionRules(
                    climbSource = rules.str("climb_source").orEmpty(),
                    climbCount = rules.int("climb_count") ?: 0,
                    countedClimbCount = rules.int("counted_climb_count")
                        ?: (rules.int("climb_count") ?: 0),
                    selectionUniqueness = rules.str("selection_uniqueness").orEmpty(),
                    progression = rules.str("progression").orEmpty(),
                    attemptsPerClimb = rules.int("attempts_per_climb") ?: 0,
                    turnDeadlineSec = rules.int("turn_deadline_sec") ?: 0,
                    attemptDeadlineSec = rules.int("attempt_deadline_sec") ?: 0,
                    minRestSec = rules.int("min_rest_sec") ?: 0,
                    deferBudgetPerRound = rules.int("defer_budget_per_round") ?: 0,
                    maxConsecutiveDefers = rules.int("max_consecutive_defers") ?: 0,
                    deferSlots = rules.int("defer_slots") ?: 1,
                    scoring = rules.str("scoring").orEmpty(),
                    scorePoints = rules["score_points"]?.jsonObject?.let { points ->
                        CompetitionScorePoints(
                            zone = points.int("zone") ?: 0,
                            top = points.int("top") ?: 0,
                            flash = points.int("flash") ?: 0,
                        )
                    },
                    tiebreaks = rules["tiebreaks"]?.jsonArray.orEmpty()
                        .mapNotNull { it.jsonPrimitive.contentOrNullSafe() },
                    lateEntryAllowed = rules.bool("late_entry_allowed") ?: false,
                ),
                relays = payload["relays"]?.jsonArray.orEmpty()
                    .mapNotNull { it.jsonPrimitive.contentOrNullSafe() },
                raw = payload,
            )
        }
    }
}

private fun climbFrom(element: kotlinx.serialization.json.JsonElement): CompetitionClimb {
    val obj = element.jsonObject
    return CompetitionClimb(
        id = obj.str("id").orEmpty(),
        climbUuid = obj.str("climb_uuid").orEmpty(),
        angle = obj.int("angle") ?: 0,
        label = obj.str("label").orEmpty(),
        points = obj.int("points") ?: 0,
        zoneHold = obj.int("zone_hold"),
    )
}

// ── small JSON readers, so the model code above stays readable ──

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonPrimitive.contentOrNullSafe(): String? = if (isString) content else null

private fun kotlinx.serialization.json.JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> =
    this ?: emptyList()
