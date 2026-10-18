package com.cruxcoach.app.zap

import com.cruxcoach.app.backup.EventSigner
import com.cruxcoach.app.nostr.NostrRelays
import com.cruxcoach.app.platform.HttpResult
import com.cruxcoach.app.platform.HttpTransport
import com.cruxcoach.app.platform.WallClock
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Why no invoice came back. */
enum class ZapFailure {
    /** The recipient's profile names no lightning address. */
    NO_ADDRESS,

    /** The LNURL-pay endpoint did not answer usably. */
    PAY_INFO_UNAVAILABLE,

    /** The amount is outside the endpoint's min/max. */
    OUT_OF_RANGE,

    /** A public zap was asked for, but the endpoint does not take Nostr zaps. */
    NO_NOSTR_SUPPORT,

    /** The zap request could not be signed (no key in the Keychain). */
    SIGNING_FAILED,

    /** The callback returned no invoice. */
    INVOICE_UNAVAILABLE,

    /** The invoice is for a different amount than the one asked for. */
    INVOICE_MISMATCH,
}

/** Android `ZapResult`: an invoice, or why there is none. */
class ZapOutcome private constructor(
    val invoice: String?,
    val failure: ZapFailure?,
    /** Endpoint limits in sats, set with [ZapFailure.OUT_OF_RANGE]. */
    val minSats: Long,
    val maxSats: Long,
) {
    companion object {
        fun invoice(bolt11: String) = ZapOutcome(bolt11, null, 0, 0)
        fun failed(reason: ZapFailure, minSats: Long = 0, maxSats: Long = 0) = ZapOutcome(null, reason, minSats, maxSats)
    }
}

/**
 * Android's `ZapManager`: turns "send N sats to this account" into a BOLT 11
 * invoice for the user's wallet. It never pays anything itself.
 *
 * - private: plain LNURL-pay; only the LNURL server sees the amount, nothing
 *   is published;
 * - public: a kind-9734 zap request signed with the user's key is attached, so
 *   the recipient's server can publish the zap receipt.
 */
class ZapClient(
    private val http: HttpTransport,
    private val signer: EventSigner,
    private val clock: WallClock,
    private val lightningAddressOf: suspend (String) -> String?,
    private val relays: List<String> = NostrRelays.DEFAULT_RELAYS,
) {
    suspend fun createPaymentRequest(
        recipientPubkey: String,
        amountMilliSats: Long,
        message: String,
        privately: Boolean,
    ): ZapOutcome {
        val address = lightningAddressOf(recipientPubkey)?.takeIf { it.isNotBlank() }
            ?: return ZapOutcome.failed(ZapFailure.NO_ADDRESS)
        val payInfoUrl = Lnurl.payInfoUrl(address) ?: return ZapOutcome.failed(ZapFailure.NO_ADDRESS)
        val info = get(payInfoUrl)?.let { Lnurl.parsePayInfo(it) }
            ?: return ZapOutcome.failed(ZapFailure.PAY_INFO_UNAVAILABLE)

        if (amountMilliSats < info.minSendable || amountMilliSats > info.maxSendable) {
            return ZapOutcome.failed(
                ZapFailure.OUT_OF_RANGE,
                minSats = (info.minSendable + 999) / 1000,
                maxSats = info.maxSendable / 1000,
            )
        }

        val callbackUrl = if (privately) {
            Lnurl.privateCallbackUrl(info.callback, amountMilliSats)
        } else {
            if (!info.allowsNostr) return ZapOutcome.failed(ZapFailure.NO_NOSTR_SUPPORT)
            val request = zapRequest(recipientPubkey, amountMilliSats, message)
                ?: return ZapOutcome.failed(ZapFailure.SIGNING_FAILED)
            Lnurl.zapCallbackUrl(info.callback, amountMilliSats, request)
        }

        val invoice = get(callbackUrl)?.let { Lnurl.parseInvoice(it) }
            ?: return ZapOutcome.failed(ZapFailure.INVOICE_UNAVAILABLE)
        if (Lnurl.invoiceAmountMilliSats(invoice) != amountMilliSats) {
            return ZapOutcome.failed(ZapFailure.INVOICE_MISMATCH)
        }
        return ZapOutcome.invoice(invoice)
    }

    /** NIP-57 kind 9734, tags as Android builds them: p, amount, relays. */
    internal fun zapRequest(recipientPubkey: String, amountMilliSats: Long, message: String): String? {
        val tags = listOf(
            listOf("p", recipientPubkey),
            listOf("amount", amountMilliSats.toString()),
            listOf("relays") + relays,
        )
        val event = signer.sign(clock.epochSeconds(), KIND_ZAP_REQUEST, tags, message) ?: return null
        return Json.encodeToString(JsonObject.serializer(), event.toJson())
    }

    private suspend fun get(url: String): String? {
        val result = try {
            http.request("GET", url, mapOf("Accept" to "application/json"), null, MAX_RESPONSE_BYTES, TIMEOUT_SECONDS)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        val ok = result as? HttpResult.Ok ?: return null
        if (ok.response.status !in 200..299) return null
        return ok.response.body.decodeToString()
    }

    companion object {
        const val KIND_ZAP_REQUEST = 9734

        /** LNURL answers are a few hundred bytes. */
        private const val MAX_RESPONSE_BYTES = 64L * 1024
        private const val TIMEOUT_SECONDS = 20
    }
}

/**
 * Android `NostrProfileManager.getLightningAddress` plus `ZapManager`'s
 * fallback: the profile's `lud16` first, the maintainer's published address
 * when the maintainer's profile cannot be read.
 */
class LightningAddressResolver(
    private val profileAddress: suspend (String) -> String?,
    private val maintainerPubkey: String,
    private val maintainerAddress: String,
) {
    suspend fun resolve(pubkey: String): String? {
        val fromProfile = try {
            profileAddress(pubkey)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (fromProfile != null) return fromProfile
        return if (pubkey == maintainerPubkey) maintainerAddress else null
    }
}
