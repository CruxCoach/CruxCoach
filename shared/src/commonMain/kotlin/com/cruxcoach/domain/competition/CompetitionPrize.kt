package com.cruxcoach.domain.competition

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Claiming a prize — FEAT-058 §11.7.
 *
 * The Kotlin half of `competitions/app/protocol/prize.mjs`, held to the same
 * rules so a claim the website would accept is one the app would accept.
 *
 * A cash prize is one person's promise to pay another. CruxCoach holds nothing,
 * escrows nothing and guarantees nothing, so this is not a payment system: it is
 * the part that makes a promise *checkable* — who is entitled, to which prize,
 * against which results — while keeping the payout details out of public view.
 *
 * Nothing in a claim body reaches the log. It is NIP-44 encrypted to the
 * organizer; the log carries a status and nothing else.
 */
object CompetitionPrizeClaim {

    const val CLAIM_SCHEMA = "cruxcoach-prize-claim/1"

    val PAYOUT_KINDS = listOf("lightning_address", "bolt11", "non_cash")

    private val json = Json { ignoreUnknownKeys = true }

    sealed interface Check {
        data object Ok : Check
        data class Failed(val error: String) : Check
    }

    sealed interface Verification {
        data class Valid(val claim: JsonObject, val prize: CompetitionPrize) : Verification
        data class Failed(val error: String) : Verification
    }

    /** Build the plaintext a winner encrypts to the organizer. */
    fun buildClaimBody(
        compId: String,
        prizeId: String,
        resultsHash: String,
        payoutKind: String,
        destination: String,
        note: String? = null,
    ): String {
        val fields = buildMap {
            put("schema", JsonPrimitive(CLAIM_SCHEMA))
            put("comp_id", JsonPrimitive(compId))
            put("prize_id", JsonPrimitive(prizeId))
            put("results_hash", JsonPrimitive(resultsHash))
            put("payout_kind", JsonPrimitive(payoutKind))
            put("destination", JsonPrimitive(destination.trim()))
            if (!note.isNullOrBlank()) put("note", JsonPrimitive(note.take(280)))
        }
        // Canonical, so the two clients produce byte-identical bodies for the
        // same claim and a fixture can pin them together.
        return Ccj.encode(JsonObject(fields))
    }

    /**
     * Check a winner's own claim before it is sent.
     *
     * Refusing here is kinder than the organizer refusing later: the winner is
     * standing there and can fix it, and a malformed destination that reached
     * the organizer would be a payout that silently never arrives.
     */
    fun validateClaimInput(
        prize: CompetitionPrize,
        payoutKind: String,
        destination: String,
        nowSeconds: Long? = null,
    ): Check {
        if (payoutKind !in PAYOUT_KINDS) return Check.Failed("unknown_payout_kind")
        val text = destination.trim()
        if (text.isEmpty()) return Check.Failed("no_destination")

        if (!prize.isCash) {
            if (payoutKind != "non_cash") return Check.Failed("not_a_cash_prize")
            if (text.length > 500) return Check.Failed("too_long")
            return Check.Ok
        }
        if (payoutKind == "non_cash") return Check.Failed("cash_prize_needs_a_wallet")

        if (payoutKind == "lightning_address") {
            // The same rules as an entry fee: https only, never .onion, no
            // credentials in the authority. A payout deserves them at least as
            // much as a payment does.
            return when (val resolved = CompetitionLnurl.resolvePayEndpoint(text)) {
                is CompetitionLnurl.Resolved.Ok -> Check.Ok
                is CompetitionLnurl.Resolved.Failed -> Check.Failed("destination_${resolved.error}")
            }
        }

        val decoded = when (val result = CompetitionBolt11.decode(text)) {
            is CompetitionBolt11.Result.Ok -> result.invoice
            is CompetitionBolt11.Result.Failed -> return Check.Failed("destination_unreadable_invoice")
        }
        val amount = decoded.amountMsat ?: return Check.Failed("destination_no_amount")
        // Wrong in either direction is the winner asking to be paid something
        // other than the prize.
        if (amount != prize.valueMsat) return Check.Failed("destination_wrong_amount")
        if (nowSeconds != null && decoded.expiresAt <= nowSeconds) {
            return Check.Failed("destination_expired")
        }
        return Check.Ok
    }

    /**
     * Who is entitled to a prize.
     *
     * A tie means two people share a rank and no protocol can decide which of
     * them the money is for, so neither is automatically eligible.
     */
    fun eligibleWinner(
        standings: List<CompetitionScoring.Standing>,
        prize: CompetitionPrize,
    ): CompetitionScoring.Standing? {
        val rows = standings.filter { prize.division == null || it.division == prize.division }
        val atRank = rows.filter { it.rank == prize.rank }
        return if (atRank.size == 1) atRank.single() else null
    }

    /**
     * Verify a decrypted organizer-side claim against the exact final state it
     * names. Keep this in shared code: showing a private payout destination is
     * already a trust decision, so Android must apply every Web check first.
     */
    fun verifyClaim(
        plaintext: String,
        compId: String,
        claimantPubkey: String,
        resultsHash: String,
        standings: List<CompetitionScoring.Standing>,
        prizes: List<CompetitionPrize>,
        prizeStates: Map<String, PrizeStatus>,
        nowSeconds: Long,
        deadline: Long,
    ): Verification {
        val claim = runCatching { json.parseToJsonElement(plaintext).jsonObject }.getOrNull()
            ?: return Verification.Failed("unreadable")
        if (claim.str("schema") != CLAIM_SCHEMA) return Verification.Failed("unknown_schema")
        if (claim.str("comp_id") != compId) return Verification.Failed("wrong_competition")
        val prizeId = claim.str("prize_id") ?: return Verification.Failed("unknown_prize")
        val prize = prizes.firstOrNull { it.id == prizeId }
            ?: return Verification.Failed("unknown_prize")
        val claimedHash = claim.str("results_hash")
        if (claimedHash == null || !HEX_32.matches(claimedHash) || claimedHash != resultsHash) {
            return Verification.Failed("stale_results")
        }
        val held = prizeStates[prize.id]
        if (held != null && held.state in listOf("approved", "paid") && held.pubkey != claimantPubkey) {
            return Verification.Failed("already_awarded")
        }
        val winner = eligibleWinner(standings, prize)
            ?: return Verification.Failed("nobody_is_eligible")
        if (winner.pubkey != claimantPubkey) return Verification.Failed("not_the_winner")
        if (nowSeconds > deadline) return Verification.Failed("deadline_passed")
        val payoutKind = claim.str("payout_kind") ?: return Verification.Failed("unknown_payout_kind")
        val destination = claim.str("destination") ?: return Verification.Failed("no_destination")
        return when (val input = validateClaimInput(prize, payoutKind, destination, nowSeconds)) {
            is Check.Failed -> Verification.Failed(input.error)
            Check.Ok -> Verification.Valid(claim, prize)
        }
    }

    /** When claims close, in epoch seconds. */
    fun claimDeadline(resultsAt: Long, claimDays: Int): Long {
        val days = if (claimDays > 0) claimDays else CompetitionProtocol.DEFAULT_PRIZE_CLAIM_DAYS
        return resultsAt + days.toLong() * 24 * 60 * 60
    }

    /** Read a decrypted claim body, for a client that receives one. */
    fun parseClaimBody(plaintext: String): JsonObject? = runCatching {
        val obj = json.parseToJsonElement(plaintext).jsonObject
        if (obj.str("schema") != CLAIM_SCHEMA) null else obj
    }.getOrNull()

    private val HEX_32 = Regex("^[0-9a-f]{64}$")
}
