package com.cruxcoach.app.zap

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** LUD-06 pay parameters (Android `LnurlPayResponse`). */
class LnurlPayInfo(
    val callback: String,
    val minSendable: Long,
    val maxSendable: Long,
    val allowsNostr: Boolean,
    val nostrPubkey: String?,
)

/**
 * LNURL-pay (LUD-06/16) and the bits of BOLT 11 a payer must check. Everything
 * the network returns is untrusted: it is parsed strictly and bounded.
 */
object Lnurl {
    /** Longest invoice accepted; real ones with routing hints stay far below. */
    const val MAX_INVOICE_CHARS = 4_096

    private val USER = Regex("^[A-Za-z0-9._+-]{1,64}$")
    private val DOMAIN = Regex("^[A-Za-z0-9-]{1,63}(\\.[A-Za-z0-9-]{1,63})+$")
    private val INVOICE = Regex("^ln[a-z0-9]+$")
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * `user@domain` → `https://domain/.well-known/lnurlp/user` (LUD-16), as
     * Android's `fetchLnurlPayInfo`; null for anything that is not one plain
     * address (a path or a second `@` would point the request elsewhere).
     */
    fun payInfoUrl(address: String): String? {
        val parts = address.trim().split("@")
        if (parts.size != 2) return null
        val (user, domain) = parts
        if (!USER.matches(user) || !DOMAIN.matches(domain)) return null
        return "https://$domain/.well-known/lnurlp/$user"
    }

    /**
     * Android reads `callback`, `minSendable`, `maxSendable` (required),
     * `allowsNostr` and `nostrPubkey` (optional). An error answer
     * (`{"status":"ERROR"}`) or a non-https callback gives null.
     */
    fun parsePayInfo(body: String): LnurlPayInfo? {
        val root = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val callback = (root["callback"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (!callback.startsWith("https://") || callback.length > 2_048) return null
        val min = (root["minSendable"] as? JsonPrimitive)?.longOrNull ?: return null
        val max = (root["maxSendable"] as? JsonPrimitive)?.longOrNull ?: return null
        if (min <= 0 || max < min) return null
        val allowsNostr = (root["allowsNostr"] as? JsonPrimitive)?.booleanOrNull ?: false
        val nostrPubkey = (root["nostrPubkey"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return LnurlPayInfo(callback, min, max, allowsNostr, nostrPubkey)
    }

    /** Android `buildPrivateCallbackUrl`: only the amount, no zap request. */
    fun privateCallbackUrl(callback: String, amountMilliSats: Long): String =
        "$callback${separator(callback)}amount=$amountMilliSats"

    /** Android `buildZapCallbackUrl`: amount plus the URL-encoded signed zap request. */
    fun zapCallbackUrl(callback: String, amountMilliSats: Long, zapRequestJson: String): String =
        "$callback${separator(callback)}amount=$amountMilliSats&nostr=${percentEncode(zapRequestJson)}"

    /**
     * Android `fetchInvoice`: the `pr` field. Additionally refused here: text
     * that is not a bech32 invoice, so nothing else reaches a wallet or a QR code.
     */
    fun parseInvoice(body: String): String? {
        val root = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val pr = (root["pr"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: return null
        if (pr.isEmpty() || pr.length > MAX_INVOICE_CHARS) return null
        val lower = pr.lowercase()
        // BOLT 11 allows all-upper or all-lower, never mixed.
        if (pr != lower && pr != pr.uppercase()) return null
        if (!INVOICE.matches(lower)) return null
        return lower
    }

    /**
     * The amount a BOLT 11 invoice asks for, in millisatoshis; null when it
     * names none or cannot be read. LUD-06 has the payer check it against the
     * amount it asked for, so a server cannot return a larger invoice.
     */
    fun invoiceAmountMilliSats(invoice: String): Long? {
        val lower = invoice.lowercase()
        val separatorIndex = lower.lastIndexOf('1')
        if (!lower.startsWith("ln") || separatorIndex <= 2) return null
        val hrp = lower.substring(2, separatorIndex)
        val currencyEnd = hrp.indexOfFirst { it.isDigit() }
        if (currencyEnd <= 0) return null
        val amount = hrp.substring(currencyEnd)
        val digits = amount.takeWhile { it.isDigit() }
        val multiplier = amount.substring(digits.length)
        if (digits.isEmpty() || digits.length > 18 || multiplier.length > 1) return null
        if (digits.length > 1 && digits[0] == '0') return null
        val value = digits.toLongOrNull() ?: return null
        // One bitcoin is 10^11 msat; m/u/n/p are 10^-3, 10^-6, 10^-9, 10^-12 of it.
        return when (multiplier) {
            "" -> multiplyOrNull(value, 100_000_000_000L)
            "m" -> multiplyOrNull(value, 100_000_000L)
            "u" -> multiplyOrNull(value, 100_000L)
            "n" -> multiplyOrNull(value, 100L)
            "p" -> if (value % 10L == 0L) value / 10L else null
            else -> null
        }
    }

    /** RFC 3986 percent-encoding of everything outside the unreserved set. */
    fun percentEncode(value: String): String {
        val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
        val hex = "0123456789ABCDEF"
        val out = StringBuilder(value.length * 3)
        for (byte in value.encodeToByteArray()) {
            val b = byte.toInt() and 0xff
            val c = b.toChar()
            if (b < 0x80 && c in unreserved) {
                out.append(c)
            } else {
                out.append('%').append(hex[b shr 4]).append(hex[b and 0x0f])
            }
        }
        return out.toString()
    }

    private fun separator(callback: String): String = if (callback.contains("?")) "&" else "?"

    private fun multiplyOrNull(a: Long, b: Long): Long? {
        if (a == 0L) return 0L
        return if (a > Long.MAX_VALUE / b) null else a * b
    }
}
