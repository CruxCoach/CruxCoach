package com.cruxcoach.domain.competition

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.Json

/**
 * LNURL-pay, for a competition entry fee — FEAT-058 §11.1.
 *
 * The Kotlin half of `competitions/app/protocol/lnurl.mjs`, held to the same
 * rules and the same fixtures. Deliberately NOT the app's existing
 * `ZapManager`: that one takes a lightning address apart with `split("@")`,
 * never checks `tag == "payRequest"`, never checks that the callback is https,
 * never validates the metadata, and never looks at the invoice it gets back.
 * Every one of those is the difference between charging somebody the entry fee
 * and charging them whatever a hostile endpoint felt like.
 *
 * Rules this file exists to enforce:
 *   - https only, never a redirect to http, never `.onion`
 *   - the amount asked for is the amount the competition charges, inside the
 *     provider's own limits
 *   - the invoice that comes back is checked before it is shown; one for a
 *     different amount is refused, not displayed with a warning
 */
object CompetitionLnurl {

    private val json = Json { ignoreUnknownKeys = true }

    private val LOCAL_PART = Regex("^[a-zA-Z0-9._-]+$")
    private val DOMAIN = Regex("^[a-z0-9.-]+\\.[a-z]{2,}$")

    data class Endpoint(val url: String, val kind: String, val display: String)

    sealed interface Resolved {
        data class Ok(val endpoint: Endpoint) : Resolved
        data class Failed(val error: String) : Resolved
    }

    data class PayRequest(
        val callback: String,
        val metadata: String,
        val allowsNostr: Boolean,
        val nostrPubkey: String?,
        val minSendable: Long,
        val maxSendable: Long,
        val commentAllowed: Int,
    )

    sealed interface PayResult {
        data class Ok(val request: PayRequest) : PayResult
        data class Failed(val error: String, val minMsat: Long = 0, val maxMsat: Long = 0) : PayResult
    }

    sealed interface InvoiceResult {
        data class Ok(val bolt11: String, val invoice: CompetitionBolt11.Invoice) : InvoiceResult
        data class Failed(val error: String, val invoiceMsat: Long = 0) : InvoiceResult
    }

    /** Turn what the organizer published into a URL to fetch. */
    fun resolvePayEndpoint(value: String?): Resolved {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return Resolved.Failed("empty")

        // A URL is a URL first. Checked before the address branch because
        // `https://evil.example@bank.example/pay` contains an '@' and would
        // otherwise be taken apart as a lightning address — refused either way,
        // but under a name that describes the wrong thing.
        if (text.startsWith("https://", ignoreCase = true)) return checkedUrl(text, text)
        if (text.startsWith("http://", ignoreCase = true)) return Resolved.Failed("not_https")

        val at = text.indexOf('@')
        if (at > 0 && !text.lowercase().startsWith("lnurl")) {
            val name = text.substring(0, at)
            val domain = text.substring(at + 1).lowercase()
            if (!LOCAL_PART.matches(name)) return Resolved.Failed("bad_address")
            if (!DOMAIN.matches(domain)) return Resolved.Failed("bad_domain")
            if (domain.endsWith(".onion")) return Resolved.Failed("onion")
            return Resolved.Ok(
                Endpoint(
                    url = "https://$domain/.well-known/lnurlp/$name",
                    kind = "address",
                    display = "$name@$domain",
                ),
            )
        }

        if (text.lowercase().startsWith("lnurl1")) {
            val decoded = Nip19.decode(text.lowercase()) ?: return Resolved.Failed("bad_lnurl")
            if (decoded.hrp != "lnurl") return Resolved.Failed("bad_lnurl")
            return checkedUrl(decoded.bytes.decodeToString(), text)
        }

        return Resolved.Failed("unrecognised")
    }

    /**
     * Accept only an https URL to a real host.
     *
     * Parsed by hand rather than with a URL type: `:shared` has no Android or
     * Java URL available on every target, and the rule is narrow enough that
     * hand-parsing is clearer than pulling in a dependency to be strict about.
     */
    private fun checkedUrl(url: String, display: String): Resolved {
        val text = url.trim()
        if (!text.startsWith("https://", ignoreCase = true)) return Resolved.Failed("not_https")
        val rest = text.substring("https://".length)
        if (rest.isEmpty()) return Resolved.Failed("bad_url")
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty()) return Resolved.Failed("bad_url")
        // No credentials in the authority: `https://evil.example@bank.example`
        // reads as the bank to a person and resolves to the attacker.
        if (authority.contains('@')) return Resolved.Failed("bad_url")
        val host = authority.substringBefore(':').lowercase()
        if (host.isEmpty() || host.any { it.isWhitespace() }) return Resolved.Failed("bad_url")
        if (host.endsWith(".onion")) return Resolved.Failed("onion")
        return Resolved.Ok(Endpoint(url = text, kind = "lnurl", display = display))
    }

    /** Check the provider's pay-request response against what this comp charges. */
    fun validatePayResponse(response: JsonObject?, amountMsat: Long): PayResult {
        if (response == null) return PayResult.Failed("not_json")
        if (response.str("status") == "ERROR") return PayResult.Failed("provider_error")
        if (response.str("tag") != "payRequest") return PayResult.Failed("not_a_pay_request")

        val callback = when (val checked = checkedUrl(response.str("callback").orEmpty(), "")) {
            is Resolved.Ok -> checked.endpoint.url
            is Resolved.Failed -> return PayResult.Failed("bad_callback")
        }

        val min = response.long("minSendable") ?: return PayResult.Failed("bad_limits")
        val max = response.long("maxSendable") ?: return PayResult.Failed("bad_limits")
        if (min <= 0 || max < min) return PayResult.Failed("bad_limits")
        if (amountMsat < min) return PayResult.Failed("below_minimum", min, max)
        if (amountMsat > max) return PayResult.Failed("above_maximum", min, max)

        // The metadata string is hashed into the invoice's description hash for
        // a non-zap payment, so it has to be the exact string.
        val metadata = response.str("metadata")
        if (metadata.isNullOrEmpty()) return PayResult.Failed("no_metadata")
        val parsed = runCatching { json.parseToJsonElement(metadata).jsonArray }.getOrNull()
            ?: return PayResult.Failed("bad_metadata")
        if (parsed.isEmpty()) return PayResult.Failed("bad_metadata")

        // NIP-57: a provider that zaps names the key its receipts are signed
        // with. Without it there is nothing to verify a receipt against, and
        // the entrant is told the organizer will confirm by hand.
        val claimsNostr = (response["allowsNostr"] as? JsonPrimitive)?.booleanOrNull == true
        val nostrPubkey = response.str("nostrPubkey")?.lowercase()
            ?.takeIf { CompetitionProtocol.isHex32(it) }

        return PayResult.Ok(
            PayRequest(
                callback = callback,
                metadata = metadata,
                allowsNostr = claimsNostr && nostrPubkey != null,
                nostrPubkey = if (claimsNostr) nostrPubkey else null,
                minSendable = min,
                maxSendable = max,
                commentAllowed = response.int("commentAllowed") ?: 0,
            ),
        )
    }

    /**
     * Build the callback URL that asks for the invoice.
     *
     * The zap request rides along as a query parameter (NIP-57), which is what
     * makes the resulting receipt attributable to the person who paid.
     */
    fun invoiceUrl(callback: String, amountMsat: Long, zapRequestJson: String? = null): String {
        val separator = if (callback.contains('?')) "&" else "?"
        val builder = StringBuilder(callback).append(separator).append("amount=").append(amountMsat)
        if (zapRequestJson != null) {
            builder.append("&nostr=").append(percentEncode(zapRequestJson))
        }
        return builder.toString()
    }

    /** Percent-encoding for a query value, unreserved set per RFC 3986. */
    fun percentEncode(value: String): String {
        val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
        val digits = "0123456789ABCDEF"
        val out = StringBuilder()
        for (byte in value.encodeToByteArray()) {
            val code = byte.toInt() and 0xff
            val char = code.toChar()
            if (char in unreserved) {
                out.append(char)
            } else {
                out.append('%').append(digits[code shr 4]).append(digits[code and 0x0f])
            }
        }
        return out.toString()
    }

    /** Check the invoice the provider returned before showing it to anybody. */
    fun validateInvoiceResponse(response: JsonObject?, amountMsat: Long): InvoiceResult {
        if (response == null) return InvoiceResult.Failed("not_json")
        if (response.str("status") == "ERROR") return InvoiceResult.Failed("provider_error")
        val bolt11 = response.str("pr")?.trim()
        if (bolt11.isNullOrEmpty()) return InvoiceResult.Failed("no_invoice")

        val decoded = when (val result = CompetitionBolt11.decode(bolt11)) {
            is CompetitionBolt11.Result.Ok -> result.invoice
            is CompetitionBolt11.Result.Failed -> return InvoiceResult.Failed("unreadable_invoice")
        }
        val invoiceMsat = decoded.amountMsat ?: return InvoiceResult.Failed("no_amount")
        if (invoiceMsat != amountMsat) {
            // Refused, not shown with a warning: the number on screen and the
            // number the wallet would pay have to be the same number.
            return InvoiceResult.Failed("wrong_amount", invoiceMsat)
        }
        if (decoded.paymentHash == null) return InvoiceResult.Failed("no_payment_hash")
        return InvoiceResult.Ok(bolt11, decoded)
    }
}
