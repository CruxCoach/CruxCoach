package com.cruxcoach.domain.competition

/**
 * Just enough BOLT11 to be honest about an invoice — FEAT-058 §11.3.
 *
 * The Kotlin half of `competitions/app/protocol/bolt11.mjs`, held to the same
 * fixtures. A competition client does not pay invoices and does not verify
 * their signatures; a wallet does both. What it needs is to be able to say four
 * true things about the invoice it is about to show somebody:
 *
 *   - how much it is for, so the number on screen is not one we invented
 *   - when it expires, so nobody is left staring at a dead invoice
 *   - its payment hash, so a receipt can be matched to *this* invoice
 *   - its description hash, which under NIP-57 commits the invoice to the zap
 *     request — the binding that makes a receipt mean "this person paid this
 *     competition" rather than "somebody paid something"
 *
 * Anything it cannot read, it says it cannot read. A parser that guessed here
 * would let a mismatched invoice through as a match.
 */
object CompetitionBolt11 {

    /** The default when an invoice carries no `x` field (BOLT11 tagged fields). */
    const val DEFAULT_EXPIRY_SEC = 3600

    /**
     * Multipliers on the human-readable amount, in millisatoshi per unit.
     *
     * `p` is absent on purpose: pico-bitcoin is a *tenth* of a millisatoshi, so
     * it cannot be a whole-number multiplier and is handled separately.
     */
    private val MULTIPLIERS = mapOf('m' to 100_000_000L, 'u' to 100_000L, 'n' to 100L)
    private val UNITS = setOf('m', 'u', 'n', 'p')
    private val NETWORKS = listOf("bcrt", "tbs", "tb", "bcs", "bs", "bc")

    data class Hrp(val network: String, val amountMsat: Long?)

    data class Invoice(
        val network: String,
        val amountMsat: Long?,
        val timestamp: Long,
        val expirySec: Int,
        val paymentHash: String?,
        val descriptionHash: String?,
        val description: String?,
        val payee: String?,
    ) {
        val expiresAt: Long get() = timestamp + expirySec

        fun isExpired(nowSeconds: Long): Boolean = nowSeconds >= expiresAt

        fun secondsLeft(nowSeconds: Long): Long = (expiresAt - nowSeconds).coerceAtLeast(0)
    }

    sealed interface Result {
        data class Ok(val invoice: Invoice) : Result
        data class Failed(val error: String) : Result
    }

    /** Split `lnbc20u` into its network and its amount in msat. */
    fun parseHrp(hrp: String): Hrp? {
        if (!hrp.startsWith("ln")) return null
        val rest = hrp.substring(2)
        val network = NETWORKS.firstOrNull { rest.startsWith(it) } ?: return null

        val amount = rest.substring(network.length)
        if (amount.isEmpty()) return Hrp(network, null)

        val last = amount.last()
        val hasUnit = last in UNITS
        val digits = if (hasUnit) amount.dropLast(1) else amount
        if (digits.isEmpty() || !digits.all { it.isDigit() }) return null
        val value = digits.toLongOrNull() ?: return null

        if (!hasUnit) return Hrp(network, value * 100_000_000_000L)
        if (last == 'p') {
            // Pico-bitcoin is a tenth of a millisatoshi; an amount that is not a
            // multiple of ten cannot be paid, and BOLT11 calls it invalid.
            if (value % 10L != 0L) return null
            return Hrp(network, value / 10L)
        }
        return Hrp(network, value * MULTIPLIERS.getValue(last))
    }

    private fun wordsToInt(words: List<Int>): Long =
        words.fold(0L) { total, word -> total * 32 + word }

    fun decode(invoice: String): Result {
        val text = invoice.trim()
        if (text.length < 20) return Result.Failed("empty")
        val decoded = Nip19.decodeWords(text) ?: return Result.Failed("not_bech32")
        val hrp = parseHrp(decoded.first) ?: return Result.Failed("not_an_invoice")

        val words = decoded.second
        // 7 words of timestamp, then tagged fields, then 104 words of signature.
        if (words.size < 7 + 104) return Result.Failed("too_short")
        val timestamp = wordsToInt(words.subList(0, 7))
        val fields = words.subList(7, words.size - 104)

        var expirySec: Int? = null
        var paymentHash: String? = null
        var descriptionHash: String? = null
        var description: String? = null
        var payee: String? = null

        var i = 0
        while (i + 3 <= fields.size) {
            val type = fields[i]
            val length = fields[i + 1] * 32 + fields[i + 2]
            val start = i + 3
            val end = start + length
            if (end > fields.size) return Result.Failed("truncated_field")
            val value = fields.subList(start, end)
            i = end

            when (type) {
                1 -> if (length == 52 && paymentHash == null) paymentHash = hex(value)
                23 -> if (length == 52 && descriptionHash == null) descriptionHash = hex(value)
                13 -> if (description == null) description = text(value)
                19 -> if (length == 53 && payee == null) payee = hex(value)
                6 -> if (expirySec == null) expirySec = wordsToInt(value).toInt()
                // Unknown fields are skipped by their length, which is exactly
                // why the length is part of the format.
                else -> Unit
            }
        }

        return Result.Ok(
            Invoice(
                network = hrp.network,
                amountMsat = hrp.amountMsat,
                timestamp = timestamp,
                expirySec = expirySec ?: DEFAULT_EXPIRY_SEC,
                paymentHash = paymentHash,
                descriptionHash = descriptionHash,
                description = description,
                payee = payee,
            ),
        )
    }

    private fun hex(words: List<Int>): String? =
        Nip19.wordsToBytes(words)?.joinToString("") { byte ->
            val value = byte.toInt() and 0xff
            val digits = "0123456789abcdef"
            "${digits[value shr 4]}${digits[value and 0x0f]}"
        }

    private fun text(words: List<Int>): String? =
        Nip19.wordsToBytes(words)?.decodeToString()

    /** The URI a wallet on the same device opens. */
    fun walletUri(invoice: String): String = "lightning:${invoice.trim().lowercase()}"
}
