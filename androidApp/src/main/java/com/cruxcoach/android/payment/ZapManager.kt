package com.cruxcoach.android.payment

import android.util.Log
import com.cruxcoach.android.nostr.NostrConfig
import com.cruxcoach.android.nostr.NostrPublicEventBuilder
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.android.payment.model.LnurlPayResponse
import com.cruxcoach.android.payment.model.ZapResult
import com.cruxcoach.android.util.ExternalInputPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class ZapManager @Inject constructor(
    private val nostrSigner: NostrSigner,
    private val eventBuilder: NostrPublicEventBuilder,
    private val relayPool: NostrRelayPool,
    @Named("nostr") private val okHttpClient: OkHttpClient,
    private val profileManager: NostrProfileManager
) {
    /**
     * Creates a Lightning payment request.
     *
     * @param private If true, sends a plain LNURL-pay request without a Nostr zap request event.
     *   No public event is published to relays — only the LNURL server sees the payment amount + IP.
     *   If false, a signed Kind-9734 zap request is attached (publicly visible on relays).
     */
    suspend fun createPaymentRequest(
        recipientPubkey: String,
        amountMilliSats: Long,
        message: String = "",
        private: Boolean = true
    ): ZapResult {
        return try {
            val lnAddress = resolveLightningAddress(recipientPubkey)
                ?: return ZapResult.Error("No lightning address found for recipient")

            val lnurlPayResponse = fetchLnurlPayInfo(lnAddress)
                ?: return ZapResult.Error("Failed to fetch LNURL pay info")

            if (amountMilliSats < lnurlPayResponse.minSendable ||
                amountMilliSats > lnurlPayResponse.maxSendable
            ) {
                return ZapResult.Error(
                    "Amount out of range: ${lnurlPayResponse.minSendable}-${lnurlPayResponse.maxSendable} msat"
                )
            }

            val callbackUrl = if (`private`) {
                buildPrivateCallbackUrl(lnurlPayResponse.callback, amountMilliSats)
            } else {
                if (!lnurlPayResponse.allowsNostr) {
                    return ZapResult.Error("LNURL endpoint does not support Nostr zaps")
                }
                val zapRequestEvent = buildZapRequestEvent(
                    recipientPubkey = recipientPubkey,
                    amountMilliSats = amountMilliSats,
                    message = message,
                    relays = NostrConfig.DEFAULT_RELAYS.map { it.url }
                )
                val encodedZapRequest = URLEncoder.encode(zapRequestEvent, "UTF-8")
                buildZapCallbackUrl(lnurlPayResponse.callback, amountMilliSats, encodedZapRequest)
            }

            val invoice = fetchInvoice(callbackUrl)
                ?: return ZapResult.Error("Failed to fetch lightning invoice")

            ZapResult.Invoice(invoice)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create payment request (${e.javaClass.simpleName})")
            ZapResult.Error("Payment request failed")
        }
    }

    private suspend fun resolveLightningAddress(pubkey: String): String? {
        val address = profileManager.getLightningAddress(pubkey)
        if (address != null) return address

        if (pubkey == NostrConfig.DEV_PUBKEY) {
            return NostrConfig.DEV_LIGHTNING_ADDRESS
        }
        return null
    }

    private suspend fun fetchLnurlPayInfo(lnAddress: String): LnurlPayResponse? {
        val parts = lnAddress.split("@")
        if (parts.size != 2) return null

        val (user, domain) = parts
        val url = "https://$domain/.well-known/lnurlp/$user"

        val responseBody = httpGet(url) ?: return null

        return try {
            val json = JSONObject(responseBody)
            LnurlPayResponse(
                callback = json.getString("callback"),
                minSendable = json.getLong("minSendable"),
                maxSendable = json.getLong("maxSendable"),
                allowsNostr = json.optBoolean("allowsNostr", false),
                nostrPubkey = json.optString("nostrPubkey", null)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse LNURL pay response", e)
            null
        }
    }

    private suspend fun buildZapRequestEvent(
        recipientPubkey: String,
        amountMilliSats: Long,
        message: String,
        relays: List<String>
    ): String {
        val tags = mutableListOf(
            listOf("p", recipientPubkey),
            listOf("amount", amountMilliSats.toString()),
            listOf("relays") + relays
        )

        val event = eventBuilder.buildSignedEvent(
            kind = KIND_ZAP_REQUEST,
            content = message,
            tags = tags
        )
        return event.toJson()
    }

    private fun buildPrivateCallbackUrl(callback: String, amountMilliSats: Long): String {
        val separator = if (callback.contains("?")) "&" else "?"
        return "${callback}${separator}amount=$amountMilliSats"
    }

    private fun buildZapCallbackUrl(
        callback: String,
        amountMilliSats: Long,
        encodedZapRequest: String
    ): String {
        val separator = if (callback.contains("?")) "&" else "?"
        return "${callback}${separator}amount=$amountMilliSats&nostr=$encodedZapRequest"
    }

    private suspend fun fetchInvoice(url: String): String? {
        val responseBody = httpGet(url) ?: return null

        return try {
            val json = JSONObject(responseBody)
            val pr = json.optString("pr", null)
            val validInvoice = pr?.let(ExternalInputPolicy::validBolt11OrNull)
            if (validInvoice == null) {
                Log.e(TAG, "No payment request in invoice response")
                null
            } else {
                validInvoice
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse invoice response (${e.javaClass.simpleName})")
            null
        }
    }

    private suspend fun httpGet(url: String): String? = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            val request = Request.Builder().url(url).build()
            val call = okHttpClient.newCall(request)

            cont.invokeOnCancellation { call.cancel() }

            try {
                val response = call.execute()
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    cont.resume(body)
                } else {
                    Log.e(TAG, "HTTP GET failed: ${response.code}")
                    cont.resume(null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "HTTP GET exception (${e.javaClass.simpleName})")
                cont.resume(null)
            }
        }
    }

    companion object {
        private const val TAG = "ZapManager"
        private const val KIND_ZAP_REQUEST = 9734
    }
}
