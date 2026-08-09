package com.cruxcoach.domain.competition

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Deterministic state reduction — FEAT-058 §7.
 *
 * The port of `competitions/app/protocol/reduce.mjs`. It must agree with that
 * file on every fixture stream, including on which entries it *refuses*: the
 * rejection list is part of the hashed state, so a disagreement about what is
 * legal fails a test instead of producing two different leaderboards.
 *
 * Pure and synchronous: no clock, no crypto, no I/O, no randomness. Everything
 * time-shaped comes from the entries themselves.
 */
object CompetitionReducer {

    /**
     * The closed set of rejection codes. Codes rather than sentences, because
     * the sentence has to be translated into German and the hash must not be.
     */
    val REJECTION_CODES = listOf(
        "already_topped", "attempt_out_of_order", "capacity_full", "climb_already_claimed",
        "correction_missing_replacement", "defer_budget_exhausted", "defer_consecutive_limit",
        "duplicate_in_order", "empty_announcement", "epoch_mismatch", "illegal_transition",
        "incomplete_seed_order", "index_out_of_range", "ineligible_in_order", "no_attempts_left",
        "no_fee", "no_order", "no_such_participant", "not_accepted_registration", "not_eligible",
        "not_in_order", "participant_inactive", "unknown_checkin_state", "unknown_climb",
        "unknown_decision", "unknown_division", "unknown_op", "unknown_outcome",
        "unknown_payment_state", "unknown_queue_action", "uniqueness_not_enforced", "wrong_status",
    )

    private val REGISTRATION_STATES = setOf("registration_open")
    private val CHECKIN_STATES = setOf("checkin_open", "running")
    private val QUEUE_STATES = setOf("checkin_open", "running")
    private val ATTEMPT_STATES = setOf("running")

    data class Chained(val entry: LogEntry, val eventId: String, val createdAt: Long)

    data class Reduction(val state: CompetitionState, val chainBreakAt: Int?)

    fun initialState(competition: Competition, competitionEventId: String) = CompetitionState(
        compId = competition.compId,
        authority = competition.authority,
        epoch = competition.authorityEpoch,
        head = competitionEventId,
        status = competition.status,
        configRevision = competition.revision,
    )

    /**
     * Walk the `seq`/`prev` chain and reduce.
     *
     * @param entries already parsed and author-checked entries, in any order.
     */
    fun reduce(
        competition: Competition,
        competitionEventId: String,
        entries: List<Chained>,
        snapshot: CompetitionState? = null,
        snapshotHead: String? = null,
    ): Reduction {
        var state = snapshot?.copy(fromSnapshot = true)
            ?: initialState(competition, competitionEventId)

        val bySeq = mutableMapOf<Int, MutableList<Chained>>()
        for (item in entries) {
            val bucket = bySeq.getOrPut(item.entry.seq) { mutableListOf() }
            // Duplicate deliveries of the same event collapse; two relays racing
            // the same event must not look like a fork.
            if (bucket.none { it.eventId == item.eventId }) bucket.add(item)
        }

        var expectedPrev = snapshotHead ?: competitionEventId
        var seq = if (snapshot != null) snapshot.seq + 1 else 1
        var chainBreakAt: Int? = null

        while (true) {
            val bucket = bySeq[seq]
            if (bucket.isNullOrEmpty()) break
            val linked = bucket.filter { it.entry.prev == expectedPrev }
            if (linked.isEmpty()) {
                chainBreakAt = seq
                break
            }
            val chosen = if (linked.size == 1) {
                linked.first()
            } else {
                // Which branch is "right" is unknowable; that every client picks
                // the same one is not. Lower created_at wins, ties by lower id.
                state = state.copy(forkDetected = true)
                linked.sortedWith(compareBy({ it.createdAt }, { it.eventId })).first()
            }
            state = applyEntry(state, chosen.entry, competition)
            state = state.copy(seq = chosen.entry.seq, head = chosen.eventId)
            expectedPrev = chosen.eventId
            seq += 1
        }

        // "We have reached the end" and "there is a hole and more entries behind
        // it" look identical at the stopping point and must not be conflated:
        // the missing entry may be the disqualification that changes everything
        // after it.
        if (chainBreakAt == null && bySeq.keys.any { it > state.seq }) {
            chainBreakAt = seq
        }
        return Reduction(state.copy(chainComplete = chainBreakAt == null), chainBreakAt)
    }

    fun applyEntry(state: CompetitionState, entry: LogEntry, competition: Competition): CompetitionState {
        if (entry.epoch != state.epoch) return reject(state, entry, "epoch_mismatch")

        if (entry.op == "override") {
            val audited = state.copy(
                audit = state.audit + AuditEntry(entry.seq, "override", entry.reason, entry.at),
            )
            val wrappedOp = entry.data.str("op") ?: return reject(audited, entry, "unknown_op")
            val wrappedData = entry.data["data"] as? JsonObject ?: return reject(audited, entry, "unknown_op")
            if (wrappedOp !in HANDLED_OPS) return reject(audited, entry, "unknown_op")
            return dispatch(audited, entry.copy(op = wrappedOp, data = wrappedData), competition)
        }

        if (entry.op == "correction") {
            val audited = state.copy(
                audit = state.audit + AuditEntry(
                    entry.seq, "correction", entry.reason, entry.at,
                    supersedesSeq = entry.data.int("supersedes_seq"),
                ),
            )
            val replacement = entry.data["replacement"] as? JsonObject
                ?: return reject(audited, entry, "correction_missing_replacement")
            val wrappedOp = replacement.str("op") ?: return reject(audited, entry, "unknown_op")
            val wrappedData = replacement["data"] as? JsonObject ?: return reject(audited, entry, "unknown_op")
            if (wrappedOp !in HANDLED_OPS) return reject(audited, entry, "unknown_op")
            return dispatch(audited, entry.copy(op = wrappedOp, data = wrappedData), competition)
        }

        if (entry.op !in HANDLED_OPS) return reject(state, entry, "unknown_op")
        return dispatch(state, entry, competition)
    }

    private val HANDLED_OPS = setOf(
        "lifecycle", "registration_decision", "payment_decision", "claim_decision",
        "checkin", "queue", "defer_decision", "attempt_result", "disqualify", "announcement",
    )

    private fun dispatch(state: CompetitionState, entry: LogEntry, competition: Competition) = when (entry.op) {
        "lifecycle" -> applyLifecycle(state, entry, competition)
        "registration_decision" -> applyRegistrationDecision(state, entry, competition)
        "payment_decision" -> applyPaymentDecision(state, entry, competition)
        "claim_decision" -> applyClaimDecision(state, entry, competition)
        "checkin" -> applyCheckin(state, entry)
        "queue" -> applyQueue(state, entry, competition)
        "defer_decision" -> applyDeferDecision(state, entry, competition)
        "attempt_result" -> applyAttemptResult(state, entry, competition)
        "disqualify" -> applyDisqualify(state, entry)
        "announcement" -> applyAnnouncement(state, entry)
        else -> reject(state, entry, "unknown_op")
    }

    private fun reject(state: CompetitionState, entry: LogEntry, code: String) =
        state.copy(rejected = state.rejected + Rejection(entry.seq, entry.op, code))

    // ── operations ──

    private fun applyLifecycle(state: CompetitionState, entry: LogEntry, competition: Competition): CompetitionState {
        val next = entry.data.str("status") ?: return reject(state, entry, "illegal_transition")
        val legal = CompetitionProtocol.LEGAL_TRANSITIONS[state.status].orEmpty()
        if (next !in legal) return reject(state, entry, "illegal_transition")
        var updated = state.copy(status = next, paused = next == "paused")
        if (next == "running" && state.round == 0) {
            updated = updated.copy(
                round = 1,
                currentClimbId = if (competition.rules.climbSource == "organizer_set" && competition.climbs.isNotEmpty()) {
                    competition.climbs.first().id
                } else {
                    updated.currentClimbId
                },
            )
        }
        if (next == "cancelled" || next == "finished") {
            updated = updated.copy(cursor = -1, turnDeadlineAt = 0)
        }
        return updated
    }

    private fun applyRegistrationDecision(
        state: CompetitionState,
        entry: LogEntry,
        competition: Competition,
    ): CompetitionState {
        if (state.status !in REGISTRATION_STATES) return reject(state, entry, "wrong_status")
        val pubkey = entry.data.str("pubkey") ?: return reject(state, entry, "no_such_participant")
        val decision = entry.data.str("decision")
        if (decision !in listOf("accepted", "waitlisted", "rejected")) {
            return reject(state, entry, "unknown_decision")
        }
        if (decision == "accepted" && competition.capacity > 0) {
            val alreadyAccepted = state.participants.count { it.registration == "accepted" && it.pubkey != pubkey }
            // The reducer refuses rather than trusting the authority to have
            // counted. A capacity only the organizer enforces is not a capacity.
            if (alreadyAccepted >= competition.capacity) return reject(state, entry, "capacity_full")
        }
        val division = entry.data.str("division")
        if (division != null && competition.divisions.none { it.id == division }) {
            return reject(state, entry, "unknown_division")
        }
        var participant = state.upsertParticipant(pubkey).copy(registration = decision!!)
        if (division != null) participant = participant.copy(division = division)
        entry.data.str("display")?.let { participant = participant.copy(display = it) }
        participant = participant.copy(
            waitlistPosition = if (decision == "waitlisted") entry.data.int("waitlist_position") ?: 0 else 0,
        )
        if (decision == "accepted" && competition.feeMsat > 0 && participant.payment == "not_required") {
            participant = participant.copy(payment = "pending")
        }
        return state.withParticipant(participant)
    }

    private fun applyPaymentDecision(
        state: CompetitionState,
        entry: LogEntry,
        competition: Competition,
    ): CompetitionState {
        val paymentState = entry.data.str("state")
        if (paymentState == null || paymentState !in CompetitionProtocol.PAYMENT_STATES || paymentState == "not_required") {
            return reject(state, entry, "unknown_payment_state")
        }
        if (competition.feeMsat == 0L) return reject(state, entry, "no_fee")
        val pubkey = entry.data.str("pubkey")
        val participant = pubkey?.let { state.participant(it) } ?: return reject(state, entry, "no_such_participant")
        return state.withParticipant(participant.copy(payment = paymentState))
    }

    private fun applyClaimDecision(
        state: CompetitionState,
        entry: LogEntry,
        competition: Competition,
    ): CompetitionState {
        if (competition.rules.selectionUniqueness != "unique_per_competition") {
            return reject(state, entry, "uniqueness_not_enforced")
        }
        val decision = entry.data.str("decision")
        if (decision !in listOf("granted", "denied")) return reject(state, entry, "unknown_decision")
        val pubkey = entry.data.str("pubkey")
        val participant = pubkey?.let { state.participant(it) } ?: return reject(state, entry, "no_such_participant")
        if (decision == "denied") return state
        val climbId = entry.data.str("climb_id") ?: return reject(state, entry, "unknown_climb")
        val holder = state.claims[climbId]
        // Enforced here, not merely by the authority behaving well. A double
        // grant is visible to every client the same way, which is what makes it
        // correctable.
        if (holder != null && holder != pubkey) return reject(state, entry, "climb_already_claimed")
        val selections = if (climbId in participant.selections) {
            participant.selections
        } else {
            (participant.selections + climbId).sorted()
        }
        return state
            .copy(claims = state.claims + (climbId to pubkey))
            .withParticipant(participant.copy(selections = selections))
    }

    private fun applyCheckin(state: CompetitionState, entry: LogEntry): CompetitionState {
        if (state.status !in CHECKIN_STATES) return reject(state, entry, "wrong_status")
        val checkinState = entry.data.str("state")
        if (checkinState !in listOf("checked_in", "no_show")) return reject(state, entry, "unknown_checkin_state")
        val pubkey = entry.data.str("pubkey")
        val participant = pubkey?.let { state.participant(it) } ?: return reject(state, entry, "no_such_participant")
        if (participant.registration != "accepted") return reject(state, entry, "not_accepted_registration")
        val updated = participant.copy(
            checkin = checkinState!!,
            result = if (checkinState == "no_show") "dns" else participant.result,
        )
        return state.withParticipant(updated)
    }

    private fun isEligible(
        state: CompetitionState,
        competition: Competition,
        pubkey: String,
        atSeconds: Long,
    ): Boolean {
        val participant = state.participant(pubkey) ?: return false
        if (participant.registration != "accepted") return false
        if (participant.checkin != "checked_in") return false
        if (participant.result != "active") return false
        if (competition.feeMsat > 0 && participant.payment != "settled") return false
        val rest = competition.rules.minRestSec
        if (rest > 0 && participant.lastAttemptAt > 0 && atSeconds - participant.lastAttemptAt < rest) return false
        return true
    }

    private fun nextEligibleIndex(
        state: CompetitionState,
        competition: Competition,
        from: Int,
        atSeconds: Long,
    ): Int {
        for (index in (from + 1) until state.order.size) {
            if (isEligible(state, competition, state.order[index], atSeconds)) return index
        }
        return -1
    }

    private fun applyQueue(state: CompetitionState, entry: LogEntry, competition: Competition): CompetitionState {
        if (state.status !in QUEUE_STATES) return reject(state, entry, "wrong_status")
        val action = entry.data.str("action")
        if (action == null || action !in CompetitionProtocol.QUEUE_ACTIONS) {
            return reject(state, entry, "unknown_queue_action")
        }

        if (action == "seed" || action == "reorder") {
            val orderArray = entry.data["order"] as? JsonArray ?: return reject(state, entry, "no_order")
            val order = orderArray.mapNotNull { (it as? JsonPrimitive)?.contentOrNullSafe() }
            if (order.size != orderArray.size) return reject(state, entry, "no_order")
            val eligible = state.participants
                .filter { it.registration == "accepted" && it.checkin == "checked_in" && it.result == "active" }
                .map { it.pubkey }
            if (order.toSet().size != order.size) return reject(state, entry, "duplicate_in_order")
            if (order.any { it !in eligible }) return reject(state, entry, "ineligible_in_order")
            if (action == "seed" && order.size != eligible.size) return reject(state, entry, "incomplete_seed_order")
            return state.copy(order = order, cursor = -1)
        }

        if (action == "open_turn") {
            val index = entry.data.int("index") ?: return reject(state, entry, "index_out_of_range")
            if (index < 0 || index >= state.order.size) return reject(state, entry, "index_out_of_range")
            if (!isEligible(state, competition, state.order[index], entry.at)) {
                return reject(state, entry, "not_eligible")
            }
            return state.copy(
                cursor = index,
                turnOpenedAt = entry.at,
                turnDeadlineAt = entry.at + competition.rules.turnDeadlineSec,
            )
        }

        if (action == "close_turn") return state.copy(cursor = -1, turnDeadlineAt = 0)

        if (action == "advance") {
            val next = nextEligibleIndex(state, competition, state.cursor, entry.at)
            if (next == -1) return state.copy(cursor = -1, turnDeadlineAt = 0)
            return state.copy(
                cursor = next,
                turnOpenedAt = entry.at,
                turnDeadlineAt = entry.at + competition.rules.turnDeadlineSec,
            )
        }

        if (action == "next_climb") {
            val climbId = entry.data.str("climb_id") ?: return reject(state, entry, "unknown_climb")
            if (competition.rules.climbSource == "organizer_set" && competition.climb(climbId) == null) {
                return reject(state, entry, "unknown_climb")
            }
            return state.copy(currentClimbId = climbId, cursor = -1, turnDeadlineAt = 0)
        }

        // next_round
        return state.copy(
            round = state.round + 1,
            cursor = -1,
            turnDeadlineAt = 0,
            participants = state.participants.map { it.copy(defersUsedThisRound = 0, consecutiveDefers = 0) },
        )
    }

    private fun applyDeferDecision(
        state: CompetitionState,
        entry: LogEntry,
        competition: Competition,
    ): CompetitionState {
        val decision = entry.data.str("decision")
        if (decision !in listOf("granted", "denied")) return reject(state, entry, "unknown_decision")
        val pubkey = entry.data.str("pubkey")
        val participant = pubkey?.let { state.participant(it) } ?: return reject(state, entry, "no_such_participant")
        if (decision == "denied") return state

        val rules = competition.rules
        if (participant.defersUsedThisRound >= rules.deferBudgetPerRound) {
            return reject(state, entry, "defer_budget_exhausted")
        }
        if (participant.consecutiveDefers >= rules.maxConsecutiveDefers) {
            return reject(state, entry, "defer_consecutive_limit")
        }
        val current = state.order.indexOf(pubkey)
        if (current == -1) return reject(state, entry, "not_in_order")

        // Move back by exactly defer_slots, never to the end of the round.
        val target = minOf(current + rules.deferSlots, state.order.size - 1)
        val order = state.order.toMutableList()
        order.removeAt(current)
        order.add(target, pubkey)

        return state
            .copy(order = order, cursor = -1, turnDeadlineAt = 0)
            .withParticipant(
                participant.copy(
                    defersUsedThisRound = participant.defersUsedThisRound + 1,
                    consecutiveDefers = participant.consecutiveDefers + 1,
                ),
            )
    }

    private fun applyAttemptResult(
        state: CompetitionState,
        entry: LogEntry,
        competition: Competition,
    ): CompetitionState {
        if (state.status !in ATTEMPT_STATES) return reject(state, entry, "wrong_status")
        val outcome = entry.data.str("outcome")
        if (outcome == null || outcome !in CompetitionProtocol.ATTEMPT_OUTCOMES) {
            return reject(state, entry, "unknown_outcome")
        }
        val pubkey = entry.data.str("pubkey")
        val participant = pubkey?.let { state.participant(it) } ?: return reject(state, entry, "no_such_participant")
        if (participant.result != "active") return reject(state, entry, "participant_inactive")
        val climbId = entry.data.str("climb_id") ?: return reject(state, entry, "unknown_climb")

        val existing = participant.climb(climbId) ?: ClimbProgress(climbId = climbId)
        if (existing.outcome == "top") return reject(state, entry, "already_topped")
        val attemptNo = entry.data.int("attempt_no")
        if (attemptNo == null || attemptNo != existing.attemptsUsed + 1) {
            return reject(state, entry, "attempt_out_of_order")
        }
        if (existing.attemptsUsed >= competition.rules.attemptsPerClimb) {
            return reject(state, entry, "no_attempts_left")
        }

        val attemptsUsed = existing.attemptsUsed + 1
        // A pass or a timeout consumes an attempt but is not a zone.
        var nextOutcome = when {
            outcome == "top" -> "top"
            outcome == "zone" && existing.outcome != "top" -> "zone"
            existing.outcome == "none" -> "attempted"
            else -> existing.outcome
        }
        if (nextOutcome != "top" && attemptsUsed >= competition.rules.attemptsPerClimb) {
            nextOutcome = if (nextOutcome == "zone") "zone" else "dnf"
        }

        val updated = participant
            .withClimb(ClimbProgress(climbId, attemptsUsed, nextOutcome, entry.at))
            .copy(lastAttemptAt = entry.at, consecutiveDefers = 0)
        return state.withParticipant(updated)
    }

    private fun applyDisqualify(state: CompetitionState, entry: LogEntry): CompetitionState {
        val pubkey = entry.data.str("pubkey")
        val participant = pubkey?.let { state.participant(it) } ?: return reject(state, entry, "no_such_participant")
        val order = state.order.filterNot { it == pubkey }
        var updated = state.withParticipant(participant.copy(result = "disqualified")).copy(order = order)
        if (updated.cursor >= order.size) updated = updated.copy(cursor = -1, turnDeadlineAt = 0)
        return updated
    }

    private fun applyAnnouncement(state: CompetitionState, entry: LogEntry): CompetitionState {
        val text = entry.data.str("text")
        if (text.isNullOrEmpty()) return reject(state, entry, "empty_announcement")
        return state.copy(announcements = state.announcements + Announcement(entry.seq, text, entry.at))
    }
}
