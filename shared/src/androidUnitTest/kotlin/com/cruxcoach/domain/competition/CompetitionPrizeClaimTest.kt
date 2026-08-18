package com.cruxcoach.domain.competition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Who may claim a prize, decided the same way in the app as on the website.
 *
 * A claim the website accepts and the app refuses (or the reverse) would mean a
 * winner being paid or not depending on which screen the organizer happened to
 * open. These are the same cases `tools/competition-prize.test.mjs` asserts.
 *
 * Nothing here holds money: a prize is the organizer's promise, and these rules
 * only decide whether a request to be paid is a legitimate one.
 */
class CompetitionPrizeClaimTest {

    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)

    private val cash = CompetitionPrize(
        id = "place_1", rank = 1, kind = "cash", label = "First",
        valueMsat = 500_000, division = null,
    )
    private val goods = CompetitionPrize(
        id = "place_2", rank = 2, kind = "non_cash", label = "Chalk bag",
        valueMsat = 0, division = null,
    )

    private fun standing(rank: Int, pubkey: String, division: String = "open") =
        CompetitionScoring.Standing(
            rank = rank, pubkey = pubkey, display = "", division = division,
            result = "active", tops = 0, zones = 0, attempts = 0, zoneAttempts = 0,
            totalAttempts = 0, points = 0, finishedAt = 0,
        )

    private val standings = listOf(standing(1, alice), standing(2, bob))

    /** The same unpayable invoice shape the fixtures use. */
    private fun invoice(amountMsat: Long, expirySec: Int = 3600, timestamp: Long = 1_789_000_000) =
        CompetitionFixtureInvoiceBuilder.bolt11(amountMsat, timestamp, expirySec)

    @Test
    fun `the winner of a prize is the one person standing at its rank`() {
        assertEquals(alice, CompetitionPrizeClaim.eligibleWinner(standings, cash)?.pubkey)
        assertEquals(bob, CompetitionPrizeClaim.eligibleWinner(standings, goods)?.pubkey)
    }

    @Test
    fun `a tie makes nobody automatically eligible`() {
        // No protocol can decide which of two people sharing a rank the money is
        // for. The organizer has to.
        val tied = listOf(standing(1, alice), standing(1, bob))
        assertNull(CompetitionPrizeClaim.eligibleWinner(tied, cash))
    }

    @Test
    fun `a prize for a division is claimed from that division only`() {
        val mixed = listOf(standing(1, alice, "open"), standing(1, bob, "youth"))
        val youthPrize = cash.copy(division = "youth")
        assertEquals(bob, CompetitionPrizeClaim.eligibleWinner(mixed, youthPrize)?.pubkey)
    }

    @Test
    fun `a lightning address destination is held to the same rules as an entry fee`() {
        assertTrue(
            CompetitionPrizeClaim.validateClaimInput(cash, "lightning_address", "alice@example.org")
                is CompetitionPrizeClaim.Check.Ok,
        )
        for ((destination, error) in listOf(
            "http://example.org/pay" to "destination_not_https",
            "alice@abcdefgh.onion" to "destination_onion",
            "https://evil.example@bank.example/pay" to "destination_bad_url",
            "" to "no_destination",
        )) {
            val result = CompetitionPrizeClaim.validateClaimInput(cash, "lightning_address", destination)
            assertEquals(error, (result as CompetitionPrizeClaim.Check.Failed).error, destination)
        }
    }

    @Test
    fun `an invoice destination must be for the prize amount and still alive`() {
        assertTrue(
            CompetitionPrizeClaim.validateClaimInput(
                cash, "bolt11", invoice(500_000), 1_789_000_500,
            ) is CompetitionPrizeClaim.Check.Ok,
        )

        // Wrong in either direction is asking to be paid something other than
        // the prize.
        for (amount in listOf(1_000_000L, 100_000L)) {
            val result = CompetitionPrizeClaim.validateClaimInput(cash, "bolt11", invoice(amount))
            assertEquals(
                "destination_wrong_amount",
                (result as CompetitionPrizeClaim.Check.Failed).error,
                "$amount",
            )
        }

        val expired = CompetitionPrizeClaim.validateClaimInput(
            cash, "bolt11", invoice(500_000, expirySec = 60), 1_789_000_000 + 61,
        )
        assertEquals("destination_expired", (expired as CompetitionPrizeClaim.Check.Failed).error)
    }

    @Test
    fun `a non-cash prize collects contact details, not a wallet`() {
        assertTrue(
            CompetitionPrizeClaim.validateClaimInput(goods, "non_cash", "I will collect it at the desk")
                is CompetitionPrizeClaim.Check.Ok,
        )
        assertEquals(
            "not_a_cash_prize",
            (CompetitionPrizeClaim.validateClaimInput(goods, "lightning_address", "bob@example.org")
                as CompetitionPrizeClaim.Check.Failed).error,
        )
        assertEquals(
            "cash_prize_needs_a_wallet",
            (CompetitionPrizeClaim.validateClaimInput(cash, "non_cash", "call me")
                as CompetitionPrizeClaim.Check.Failed).error,
        )
    }

    @Test
    fun `the claim body carries only what it must, canonically`() {
        val body = CompetitionPrizeClaim.buildClaimBody(
            compId = "aa00bb11cc22dd33",
            prizeId = "place_1",
            resultsHash = "1234567890abcdef".repeat(4),
            payoutKind = "lightning_address",
            destination = "  alice@example.org  ",
        )
        val parsed = CompetitionPrizeClaim.parseClaimBody(body)!!
        assertEquals("alice@example.org", parsed.str("destination"), "trimmed before it is sent")
        assertEquals(
            listOf("comp_id", "destination", "payout_kind", "prize_id", "results_hash", "schema"),
            parsed.keys.sorted(),
        )
        // Canonical JSON, so the app and the website build the same bytes for
        // the same claim.
        assertTrue(body.indexOf("\"comp_id\"") < body.indexOf("\"destination\""))
    }

    @Test
    fun `a body that is not a claim is read as nothing rather than trusted`() {
        assertNull(CompetitionPrizeClaim.parseClaimBody("not json"))
        assertNull(CompetitionPrizeClaim.parseClaimBody("""{"schema":"something/else"}"""))
    }

    @Test
    fun `the claim deadline defaults to thirty days`() {
        val at = 1_789_000_000L
        assertEquals(at + 30 * 86400, CompetitionPrizeClaim.claimDeadline(at, 30))
        assertEquals(at + 30 * 86400, CompetitionPrizeClaim.claimDeadline(at, 0))
        assertEquals(at + 7 * 86400, CompetitionPrizeClaim.claimDeadline(at, 7))
    }
}

/**
 * A structurally valid, deliberately unsigned invoice.
 *
 * Nothing can settle it, which is the point: a test that needed a payable
 * invoice would need a Lightning node and real money.
 */
internal object CompetitionFixtureInvoiceBuilder {

    fun bolt11(amountMsat: Long, timestamp: Long, expirySec: Int): String {
        val words = mutableListOf<Int>()
        for (i in 6 downTo 0) words += ((timestamp shr (5 * i)) and 31).toInt()

        fun field(type: Int, value: List<Int>) {
            words += type
            words += (value.size shr 5) and 31
            words += value.size and 31
            words += value
        }

        fun hexWords(hex: String): List<Int> {
            val bits = hex.chunked(2).joinToString("") { it.toInt(16).toString(2).padStart(8, '0') }
            return bits.chunked(5).map { it.padEnd(5, '0').toInt(2) }
        }

        field(1, hexWords("a".repeat(64)))
        field(6, listOf((expirySec shr 5) and 31, expirySec and 31))
        repeat(104) { words += 0 }

        val micro = amountMsat / 100_000
        require(micro * 100_000 == amountMsat) { "amount must be a whole micro-bitcoin" }
        return encode("lnbc${micro}u", words)
    }

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    private fun polymod(values: List<Int>): Int {
        var checksum = 1
        for (value in values) {
            val top = checksum ushr 25
            checksum = ((checksum and 0x1ffffff) shl 5) xor value
            for (i in 0 until 5) if ((top shr i) and 1 == 1) checksum = checksum xor GENERATOR[i]
        }
        return checksum
    }

    private fun hrpExpand(hrp: String): List<Int> = buildList {
        for (char in hrp) add(char.code shr 5)
        add(0)
        for (char in hrp) add(char.code and 31)
    }

    private fun encode(hrp: String, words: List<Int>): String {
        val checksum = polymod(hrpExpand(hrp) + words + List(6) { 0 }) xor 1
        val combined = words + (0 until 6).map { (checksum shr (5 * (5 - it))) and 31 }
        return hrp + "1" + combined.joinToString("") { CHARSET[it].toString() }
    }
}
