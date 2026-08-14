package com.cruxcoach.android.competition

import com.cruxcoach.android.nostr.NostrPublicEventBuilder
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.domain.competition.Competition
import com.cruxcoach.domain.competition.CompetitionBolt11
import com.cruxcoach.domain.competition.CompetitionEvent
import com.cruxcoach.domain.competition.CompetitionLnurl
import com.cruxcoach.domain.competition.CompetitionProtocol
import com.cruxcoach.domain.competition.CompetitionZap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verifyId
import com.vitorpamplona.quartz.nip01Core.crypto.verifySignature

/**
 * Paying a competition entry fee, in the app — FEAT-058 §11.
 *
 * The website could do this and the app could not: it rendered `payment ==
 * pending` and offered nothing that could reach `settled`. A screen that
 * displays a state without the action that leaves it is the same defect as a
 * button labelled "mark paid" that checks nothing — it just fails quietly
 * instead of loudly.
 *
 * Every rule lives in `:shared` (`CompetitionLnurl`, `CompetitionBolt11`,
 * `CompetitionZap`) and is pinned by the same fixtures the website asserts on,
 * so the two clients cannot drift about what a valid endpoint, a valid invoice
 * or a valid receipt is. This class is the part that cannot be shared: HTTP,
 * signing, and publishing.
 *
 * Deliberately not the existing `ZapManager`. That one splits an address on
 * "@" without validating it, never checks `tag == "payRequest"`, never checks
 * the callback is https, never reads the invoice it is handed, and has no
 * concept of a competition to bind a zap to. Reusing it would have meant
 * shipping every one of those gaps on the paid path.
 */
@Singleton
class CompetitionPaymentFlow @Inject constructor(
    @Named("nostr") private val httpClient: OkHttpClient,
    private val signer: NostrSigner,
    private val intents: CompetitionIntentPublisher,
    private val relayPool: NostrRelayPool,
) {

    /**
     * The one thing this flow needs a signer for, expressed without naming
     * Quartz.
     *
     * Quartz ships class files for a newer JVM than the unit tests run on, so
     * anything that mentions its `Event` type cannot be tested here at all.
     * Depending on "something that can sign a zap request" rather than on the
     * library is both the testable shape and the honest description of the
     * dependency.
     */
    data class SignedZapRequest(val id: String, val json: String)

    fun interface ZapRequestSigner {
        suspend fun sign(content: String, tags: List<List<String>>): SignedZapRequest?
    }

    /**
     * Telling the organizer which receipt to look for.
     *
     * A seam for the same reason as the signer: `CompetitionIntentPublisher` is
     * a final class that reaches a relay, so a test that wants to check the
     * *sequence* would otherwise have to bring up a relay to do it.
     */
    fun interface ClaimPublisher {
        suspend fun claim(
            competition: Competition,
            organizerPubkey: String,
            zapReceiptId: String,
            bolt11: String,
        )
    }

    private val defaultClaimPublisher = ClaimPublisher { competition, organizerPubkey, receiptId, bolt11 ->
        intents.claimPayment(competition, organizerPubkey, receiptId, bolt11)
    }

    /** Signs with Quartz. Replaced in tests, never bypassed in the app. */
    private val defaultZapSigner = ZapRequestSigner { content, tags ->
        runCatching {
            val event = NostrPublicEventBuilder(signer).buildSignedEvent(
                kind = CompetitionZap.REQUEST_KIND,
                content = content,
                tags = tags,
            )
            SignedZapRequest(event.id, event.toJson())
        }.getOrNull()
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** What the screen has after a successful request for an invoice. */
    data class Invoice(
        val bolt11: String,
        val amountMsat: Long,
        val expiresAt: Long,
        /**
         * True when the endpoint named a key its receipts are signed with, so
         * the organizer's console can verify one. False means the organizer has
         * to confirm by hand, and the entrant is told so *before* paying.
         */
        val verifiable: Boolean,
        val zapReceiptId: String,
    ) {
        val walletUri: String get() = CompetitionBolt11.walletUri(bolt11)

        fun secondsLeft(nowSeconds: Long): Long = (expiresAt - nowSeconds).coerceAtLeast(0)
    }

    sealed interface Result {
        data class Ready(val invoice: Invoice) : Result

        /** [code] is one of the shared layer's stable error codes. */
        data class Failed(val code: String, val amountSats: Long = 0) : Result
    }

    sealed interface ReceiptResult {
        data class Verified(
            val receiptId: String,
            val amountMsat: Long,
            val zapperPubkey: String,
            val weaklyBound: Boolean,
        ) : ReceiptResult
        data class Failed(val code: String) : ReceiptResult
    }

    data class ReceiptHistory(val events: List<String>, val complete: Boolean)

    fun interface ReceiptFetcher {
        suspend fun fetch(filter: String): ReceiptHistory
    }

    data class DecodedReceipt(
        val event: CompetitionEvent,
        val verifiedEventIds: Set<String>,
    )

    fun interface ReceiptDecoder {
        fun decode(raw: String): DecodedReceipt?
    }

    private val defaultReceiptFetcher = ReceiptFetcher { filter ->
        val events = mutableListOf<String>()
        val complete = runCatching {
            withTimeoutOrNull(RECEIPT_TIMEOUT_MS) {
                relayPool.fetchStoredComplete(filter).collect(events::add)
                true
            } ?: false
        }.getOrDefault(false)
        ReceiptHistory(events, complete)
    }

    private val defaultReceiptDecoder = ReceiptDecoder { raw ->
        val event = runCatching { Event.fromJson(raw) }.getOrNull() ?: return@ReceiptDecoder null
        if (!runCatching { event.verifyId() && event.verifySignature() }.getOrDefault(false)) {
            return@ReceiptDecoder null
        }
        val description = event.tags.firstOrNull { it.firstOrNull() == "description" }?.getOrNull(1)
            ?: return@ReceiptDecoder null
        val request = runCatching { Event.fromJson(description) }.getOrNull() ?: return@ReceiptDecoder null
        if (!runCatching { request.verifyId() && request.verifySignature() }.getOrDefault(false)) {
            return@ReceiptDecoder null
        }
        DecodedReceipt(event.toCompetitionEvent(), setOf(event.id, request.id))
    }

    /**
     * Fetch and verify exactly the receipt an entrant named. This is invoked by
     * an explicit organizer action, uses complete all-relay history, and never
     * treats an unavailable relay or endpoint as proof of payment.
     */
    suspend fun verifyClaimedReceipt(
        competition: Competition,
        organizerPubkey: String,
        payerPubkey: String,
        receiptId: String,
        receiptFetcher: ReceiptFetcher = defaultReceiptFetcher,
        receiptDecoder: ReceiptDecoder = defaultReceiptDecoder,
    ): ReceiptResult {
        if (!CompetitionProtocol.isHex32(receiptId)) return ReceiptResult.Failed("no_receipt")
        val lnurl = competition.feeLnurl ?: return ReceiptResult.Failed("no_provider_key")
        val endpoint = when (val resolved = CompetitionLnurl.resolvePayEndpoint(lnurl)) {
            is CompetitionLnurl.Resolved.Ok -> resolved.endpoint
            is CompetitionLnurl.Resolved.Failed -> return ReceiptResult.Failed(resolved.error)
        }
        val payBody = fetch(endpoint.url) ?: return ReceiptResult.Failed("unreachable")
        val pay = when (val checked = CompetitionLnurl.validatePayResponse(payBody, competition.feeMsat)) {
            is CompetitionLnurl.PayResult.Ok -> checked.request
            is CompetitionLnurl.PayResult.Failed -> return ReceiptResult.Failed(checked.error)
        }
        val provider = pay.nostrPubkey ?: return ReceiptResult.Failed("no_provider_key")
        val history = runCatching {
            receiptFetcher.fetch(
                """{"ids":["$receiptId"],"kinds":[${CompetitionZap.RECEIPT_KIND}],"limit":4}""",
            )
        }.getOrElse { return ReceiptResult.Failed("incomplete_receipt_history") }
        if (!history.complete) return ReceiptResult.Failed("incomplete_receipt_history")
        val decoded = history.events.asSequence()
            .mapNotNull(receiptDecoder::decode)
            .firstOrNull { it.event.id == receiptId }
            ?: return ReceiptResult.Failed("receipt_not_found")
        val event = decoded.event
        return when (val verdict = CompetitionZap.verifyReceipt(
            event,
            CompetitionZap.Expected(
                providerPubkey = provider,
                payerPubkey = payerPubkey,
                recipientPubkey = competition.authority,
                address = CompetitionProtocol.competitionAddress(organizerPubkey, competition.compId),
                amountMsat = competition.feeMsat,
            ),
            verifySignature = { it.id in decoded.verifiedEventIds },
        )) {
            is CompetitionZap.Verdict.Failed -> ReceiptResult.Failed(verdict.error)
            is CompetitionZap.Verdict.Ok -> ReceiptResult.Verified(
                receiptId = event.id,
                amountMsat = verdict.amountMsat,
                zapperPubkey = event.pubkey,
                weaklyBound = verdict.weaklyBound,
            )
        }
    }

    /**
     * Resolve, ask, check, publish.
     *
     * Retrying is safe: the zap request carries the registration's own nonce,
     * and the `payment_claim` intent reuses its nonce too, so a second attempt
     * replaces the first rather than adding one.
     */
    suspend fun requestInvoice(
        competition: Competition,
        organizerPubkey: String,
        relays: List<String>,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
        zapSigner: ZapRequestSigner = defaultZapSigner,
        claimPublisher: ClaimPublisher = defaultClaimPublisher,
        registrationNonce: String = intents.registrationNonce(competition.compId),
    ): Result {
        if (competition.feeMsat <= 0) return Result.Failed("no_fee")
        val lnurl = competition.feeLnurl
        if (lnurl.isNullOrBlank()) return Result.Failed("empty")

        val endpoint = when (val resolved = CompetitionLnurl.resolvePayEndpoint(lnurl)) {
            is CompetitionLnurl.Resolved.Ok -> resolved.endpoint
            is CompetitionLnurl.Resolved.Failed -> return Result.Failed(resolved.error)
        }

        val payBody = fetch(endpoint.url) ?: return Result.Failed("unreachable")
        val pay = when (val checked = CompetitionLnurl.validatePayResponse(payBody, competition.feeMsat)) {
            is CompetitionLnurl.PayResult.Ok -> checked.request
            is CompetitionLnurl.PayResult.Failed -> return Result.Failed(
                checked.error,
                (if (checked.error == "below_minimum") checked.minMsat else checked.maxMsat) / 1000,
            )
        }

        // The zap request is what makes a later receipt attributable to this
        // person and this registration rather than to "somebody paid something".
        var zapReceiptId = ""
        var zapRequestJson: String? = null
        if (pay.allowsNostr) {
            val tags = CompetitionZap.buildZapRequestTags(
                recipientPubkey = competition.authority,
                address = CompetitionProtocol.competitionAddress(organizerPubkey, competition.compId),
                amountMsat = competition.feeMsat,
                relays = relays,
                nonce = registrationNonce,
            )
            val signed = zapSigner.sign("CruxCoach competition entry", tags)
                ?: return Result.Failed("signing_failed")
            zapReceiptId = signed.id
            zapRequestJson = signed.json
        }

        val invoiceBody = fetch(CompetitionLnurl.invoiceUrl(pay.callback, competition.feeMsat, zapRequestJson))
            ?: return Result.Failed("unreachable")
        val checked = CompetitionLnurl.validateInvoiceResponse(invoiceBody, competition.feeMsat)
        val invoice = when (checked) {
            is CompetitionLnurl.InvoiceResult.Ok -> checked
            is CompetitionLnurl.InvoiceResult.Failed ->
                return Result.Failed(checked.error, checked.invoiceMsat / 1000)
        }

        // An invoice that has already expired must not be shown as payable.
        if (invoice.invoice.isExpired(nowSeconds)) return Result.Failed("expired")

        // Told to the organizer even when the provider cannot zap: the claim is
        // also the paper trail a hand-recorded payment refers to.
        claimPublisher.claim(competition, organizerPubkey, zapReceiptId, invoice.bolt11)

        return Result.Ready(
            Invoice(
                bolt11 = invoice.bolt11,
                amountMsat = invoice.invoice.amountMsat ?: competition.feeMsat,
                expiresAt = invoice.invoice.expiresAt,
                verifiable = pay.allowsNostr,
                zapReceiptId = zapReceiptId,
            ),
        )
    }

    /** One GET with a deadline. A hung request is not an answer. */
    private suspend fun fetch(url: String): JsonObject? = withContext(Dispatchers.IO) {
        runCatching {
            httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                // A hostile endpoint should not be able to hand us an unbounded
                // body; the rest of the parse is cheap after this.
                val body = response.body?.source()?.apply { request(MAX_RESPONSE_BYTES + 1) }
                    ?.buffer?.snapshot()?.utf8() ?: return@use null
                if (body.length > MAX_RESPONSE_BYTES) return@use null
                json.parseToJsonElement(body).jsonObject
            }
        }.getOrNull()
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 65_536L
        const val RECEIPT_TIMEOUT_MS = 15_000L
    }
}
