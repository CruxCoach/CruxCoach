package com.cruxcoach.domain.competition

/**
 * Configuration validation — FEAT-058 §16.1.
 *
 * The Android client only ever consumes competitions, so this exists to refuse
 * a malformed one at the door rather than to help someone author one. It must
 * accept and reject exactly what `validateCompetitionConfig` in
 * `competitions/app/protocol/competition.mjs` does, or the two clients will
 * disagree about which competitions exist — which is worse than either being
 * strict or lenient on its own.
 */
object CompetitionValidation {

    data class Problem(val field: String, val message: String)

    private val TEXT_LIMITS = mapOf(
        "title" to (1 to 120),
        "summary" to (0 to 140),
        "description" to (0 to 4000),
        "eligibility" to (0 to 2000),
        "waiver" to (0 to 2000),
        "participant_instructions" to (0 to 2000),
        "spectator_info" to (0 to 2000),
        "refund_policy" to (0 to 2000),
    )

    private val CLIMB_SOURCES = listOf("organizer_set", "participant_choice")
    private val UNIQUENESS = listOf("none", "unique_per_competition")
    private val PROGRESSIONS = listOf("synchronous_rounds", "asynchronous_turns")
    private val SCORINGS = listOf("tops_then_attempts", "points_sum", "hardest_n")
    private val TIEBREAKS = listOf("fewest_attempts", "most_zones", "fewest_zone_attempts", "earliest_finish", "seed_order")
    private val VISIBILITIES = listOf("public", "unlisted")

    private val ID_PATTERN = Regex("^[a-z0-9_]{1,24}$")
    private val CONTROL_CHARS = Regex("[\\u0000-\\u0008\\u000b\\u000c\\u000e-\\u001f\\u007f]")

    fun validate(competition: Competition): List<Problem> {
        val problems = mutableListOf<Problem>()
        fun fail(field: String, message: String) = problems.add(Problem(field, message))

        if (!CompetitionProtocol.isCompId(competition.compId)) {
            fail("comp_id", "must be 16 lowercase hex characters")
        }
        if (!CompetitionProtocol.isHex32(competition.authority)) {
            fail("authority", "must be a 32-byte hex public key")
        }
        if (competition.authorityEpoch < 1) fail("authority_epoch", "must be a whole number of at least 1")

        for ((field, bounds) in TEXT_LIMITS) {
            val value = competition.raw.str(field)
            val (min, max) = bounds
            if (value.isNullOrEmpty()) {
                if (min > 0) fail(field, "is required")
                continue
            }
            if (value.length < min) fail(field, "must be at least $min characters")
            if (value.length > max) fail(field, "must be at most $max characters")
            if (CONTROL_CHARS.containsMatchIn(value)) fail(field, "must not contain control characters")
        }

        if (competition.visibility !in VISIBILITIES) fail("visibility", "must be one of: ${VISIBILITIES.joinToString(", ")}")
        if (competition.status !in CompetitionProtocol.LIFECYCLE) fail("status", "must be a known lifecycle state")
        if (competition.capacity !in 0..500) fail("capacity", "must be between 0 and 500")
        if (competition.timezone.isEmpty()) fail("timezone", "is required")

        if (competition.waiverRequired && competition.raw.str("waiver").isNullOrEmpty()) {
            fail("waiver", "is required when a waiver must be accepted")
        }

        val ordered = listOf(
            "registration_opens_at" to competition.registrationOpensAt,
            "registration_closes_at" to competition.registrationClosesAt,
            "checkin_opens_at" to competition.checkinOpensAt,
            "checkin_closes_at" to competition.checkinClosesAt,
            "starts_at" to competition.startsAt,
            "ends_at" to competition.endsAt,
        )
        for (index in 1 until ordered.size) {
            val (earlierName, earlier) = ordered[index - 1]
            val (laterName, later) = ordered[index]
            if (earlier > 0 && later > 0 && earlier > later) {
                fail(laterName, "must not be before ${earlierName.replace('_', ' ')}")
            }
        }

        val venue = competition.raw["venue"] as? kotlinx.serialization.json.JsonObject
        if (venue == null) {
            fail("venue", "is required")
        } else {
            val kind = venue.str("kind")
            if (kind !in listOf("physical", "online")) fail("venue.kind", "must be physical or online")
            if (kind == "physical" && venue.str("name").isNullOrEmpty()) {
                fail("venue.name", "is required for a physical venue")
            }
        }

        val board = competition.raw["board"] as? kotlinx.serialization.json.JsonObject
        if (board == null) {
            fail("board", "is required")
        } else {
            for (field in listOf("brand", "model", "size")) {
                if (board.str(field).isNullOrEmpty()) fail("board.$field", "is required")
            }
            if (board.int("angle") == null) fail("board.angle", "is required")
            if (board.int("layout_id") == null) fail("board.layout_id", "is required")
        }

        if (competition.divisions.isEmpty() || competition.divisions.size > 8) {
            fail("divisions", "must have between 1 and 8 entries")
        } else {
            val seen = mutableSetOf<String>()
            for (division in competition.divisions) {
                if (!ID_PATTERN.matches(division.id)) {
                    fail("divisions", "each division needs an id of [a-z0-9_], max 24 characters")
                } else if (!seen.add(division.id)) {
                    fail("divisions", "duplicate division id \"${division.id}\"")
                }
                if (division.label.isEmpty()) fail("divisions", "each division needs a label")
            }
        }

        val rules = competition.rules
        if (rules.climbSource !in CLIMB_SOURCES) fail("rules.climb_source", "must be one of: ${CLIMB_SOURCES.joinToString(", ")}")
        if (rules.selectionUniqueness !in UNIQUENESS) fail("rules.selection_uniqueness", "must be one of: ${UNIQUENESS.joinToString(", ")}")
        if (rules.progression !in PROGRESSIONS) fail("rules.progression", "must be one of: ${PROGRESSIONS.joinToString(", ")}")
        if (rules.scoring !in SCORINGS) fail("rules.scoring", "must be one of: ${SCORINGS.joinToString(", ")}")
        if (rules.climbCount !in 1..40) fail("rules.climb_count", "must be between 1 and 40")
        if (rules.attemptsPerClimb !in 1..20) fail("rules.attempts_per_climb", "must be between 1 and 20")
        if (rules.turnDeadlineSec !in 30..1800) fail("rules.turn_deadline_sec", "must be between 30 and 1800")
        if (rules.attemptDeadlineSec !in 0..1800) fail("rules.attempt_deadline_sec", "must be between 0 and 1800")
        if (rules.minRestSec !in 0..3600) fail("rules.min_rest_sec", "must be between 0 and 3600")
        if (rules.deferBudgetPerRound !in 0..5) fail("rules.defer_budget_per_round", "must be between 0 and 5")
        if (rules.maxConsecutiveDefers !in 0..5) fail("rules.max_consecutive_defers", "must be between 0 and 5")
        if (rules.deferSlots !in 1..10) fail("rules.defer_slots", "must be between 1 and 10")
        if (rules.tiebreaks.isEmpty()) {
            fail("rules.tiebreaks", "needs at least one tiebreak")
        } else if (rules.tiebreaks.any { it !in TIEBREAKS }) {
            fail("rules.tiebreaks", "must be from: ${TIEBREAKS.joinToString(", ")}")
        }
        if (rules.maxConsecutiveDefers > rules.deferBudgetPerRound) {
            fail("rules.max_consecutive_defers", "cannot exceed the per-round budget")
        }
        if (rules.selectionUniqueness == "unique_per_competition" && rules.climbSource != "participant_choice") {
            fail("rules.selection_uniqueness", "only applies when participants choose their climbs")
        }
        // A points format needs a points table, and the table lives on the
        // organizer's climb list. With participant-chosen climbs there is
        // nothing to look a value up in, so every score would silently be zero.
        if (rules.scoring in listOf("points_sum", "hardest_n") && rules.climbSource != "organizer_set") {
            fail("rules.scoring", "point scoring needs an organizer-set climb list with point values")
        }

        if (rules.climbSource == "organizer_set") {
            if (competition.climbs.isEmpty() || competition.climbs.size > 40) {
                fail("climbs", "must list between 1 and 40 climbs")
            } else {
                val seen = mutableSetOf<String>()
                for (climb in competition.climbs) {
                    if (!ID_PATTERN.matches(climb.id)) {
                        fail("climbs", "each climb needs an id of [a-z0-9_], max 24 characters")
                    } else if (!seen.add(climb.id)) {
                        fail("climbs", "duplicate climb id \"${climb.id}\"")
                    }
                    if (climb.climbUuid.isEmpty()) fail("climbs", "each climb needs a catalogue uuid")
                }
                if (competition.climbs.size < rules.climbCount) {
                    fail("climbs", "needs at least ${rules.climbCount} climbs for this format")
                }
            }
        } else if (competition.raw.containsKey("climbs")) {
            fail("climbs", "must be absent when participants choose their own climbs")
        }

        if (competition.feeMsat < 0) {
            fail("fee_msat", "must be 0 or a whole number of millisatoshis")
        } else if (competition.feeMsat > 0 && competition.feeLnurl.isNullOrEmpty()) {
            fail("fee_lnurl", "is required when there is an entry fee")
        } else if (competition.feeMsat == 0L && !competition.feeLnurl.isNullOrEmpty()) {
            fail("fee_lnurl", "must be absent for a free competition")
        }

        if (competition.relays.isEmpty() || competition.relays.size > 8) {
            fail("relays", "must list between 1 and 8 relays")
        } else if (competition.relays.any { !CompetitionProtocol.isAllowedRelayUrl(it) }) {
            // See CompetitionProtocol.isAllowedRelayUrl: wss:// anywhere,
            // ws:// only for loopback.
            fail("relays", "must all be wss:// URLs (ws:// only for localhost)")
        }

        return problems
    }
}
