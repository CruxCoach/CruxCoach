package com.cruxcoach.domain.competition

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Zap requests and receipts, for a competition entry fee — FEAT-058 §11.2.
 *
 * The Kotlin half of `competitions/app/protocol/zap.mjs`. The point of both is
 * one sentence from the spec: **a zap receipt is the provider's attestation,
 * not proof of payment.** So the only question a client can actually answer is
 * narrow, and this answers only that one:
 *
 *   Is this receipt signed by the key this competition's own payment endpoint
 *   named, over the zap request this entrant signed, for this competition, for
 *   the right amount, referencing an invoice bound to that request?
 *
 * Every one of those has to hold. Dropping any produces a different attack: any
 * relay user could mint a receipt (signer), somebody else's payment could
 * settle your entry (payer), a receipt for another competition could be
 * replayed (address), or a one-sat payment could settle a two-thousand-sat fee
 * (amount).
 *
 * **What this file does not do:** verify a Schnorr signature. `:shared` has no
 * secp256k1 and must not gain one — the app's Quartz does it, and passes the
 * answer in. What is checked here without any crypto library is that each
 * event's **id binds its own body** (`sha256` of the NIP-01 serialisation),
 * which is what catches a receipt whose tags were edited after signing.
 */
object CompetitionZap {

    const val REQUEST_KIND = 9734
    const val RECEIPT_KIND = 9735

    /** The tag that ties a zap to the registration it is paying for. */
    const val INTENT_TAG = "cc-intent"

    private val json = Json { ignoreUnknownKeys = true }

    /** Everything the receipt has to agree with, supplied by the caller. */
    data class Expected(
        /** `nostrPubkey` from THIS competition's endpoint — never from the receipt. */
        val providerPubkey: String?,
        val payerPubkey: String,
        val recipientPubkey: String,
        val address: String,
        val amountMsat: Long,
        val nonce: String? = null,
        val notBefore: Long? = null,
        val notAfter: Long? = null,
    )

    sealed interface Verdict {
        /** [weaklyBound] when the invoice carried no description hash to check. */
        data class Ok(val amountMsat: Long, val bolt11: String, val weaklyBound: Boolean) : Verdict
        data class Failed(val error: String) : Verdict
    }

    /** Build the kind-9734 a participant signs before asking for an invoice. */
    fun buildZapRequestTags(
        recipientPubkey: String,
        address: String,
        amountMsat: Long,
        relays: List<String>,
        nonce: String,
    ): List<List<String>> = listOf(
        listOf("p", recipientPubkey),
        listOf("a", address),
        listOf("amount", amountMsat.toString()),
        listOf("relays") + relays,
        listOf(INTENT_TAG, nonce),
    )

    private fun tag(event: CompetitionEvent, name: String): String? = event.tagValue(name)

    /**
     * Verify a receipt against everything it has to agree with.
     *
     * @param verifySignature the app's Schnorr check. Returning `true`
     *   unconditionally would make this function a decoration, so a caller that
     *   cannot verify signatures must say so by failing rather than by lying.
     */
    fun verifyReceipt(
        receipt: CompetitionEvent,
        expected: Expected,
        verifySignature: (CompetitionEvent) -> Boolean,
    ): Verdict {
        if (receipt.kind != RECEIPT_KIND) return Verdict.Failed("not_a_receipt")
        if (expected.providerPubkey.isNullOrEmpty()) {
            // Not "unverified" — unverifiABLE. The endpoint never named a key,
            // so there is nothing a signature could be checked against.
            return Verdict.Failed("no_provider_key")
        }
        if (receipt.pubkey != expected.providerPubkey) return Verdict.Failed("wrong_signer")
        if (!idBinds(receipt)) return Verdict.Failed("bad_signature")
        if (!verifySignature(receipt)) return Verdict.Failed("bad_signature")

        if (tag(receipt, "p") != expected.recipientPubkey) return Verdict.Failed("wrong_recipient")
        if (tag(receipt, "a") != expected.address) return Verdict.Failed("wrong_competition")

        val bolt11 = tag(receipt, "bolt11") ?: return Verdict.Failed("no_invoice")
        val description = tag(receipt, "description") ?: return Verdict.Failed("no_description")

        val request = runCatching { json.parseToJsonElement(description).jsonObject }
            .getOrNull()
            ?.let { eventFrom(it) }
            ?: return Verdict.Failed("bad_description")
        if (request.kind != REQUEST_KIND) return Verdict.Failed("bad_description")

        // The description is the zap request verbatim, so its own binding has
        // to hold. Without this anybody could paste a request naming somebody
        // else and the receipt would still look correct.
        if (!idBinds(request)) return Verdict.Failed("bad_request_signature")
        if (!verifySignature(request)) return Verdict.Failed("bad_request_signature")

        if (request.pubkey != expected.payerPubkey) return Verdict.Failed("wrong_payer")
        if (tag(request, "p") != expected.recipientPubkey) return Verdict.Failed("request_wrong_recipient")
        if (tag(request, "a") != expected.address) return Verdict.Failed("request_wrong_competition")

        val requested = tag(request, "amount")?.toLongOrNull() ?: return Verdict.Failed("no_amount")
        if (requested != expected.amountMsat) return Verdict.Failed("wrong_amount")

        if (expected.nonce != null && tag(request, INTENT_TAG) != expected.nonce) {
            return Verdict.Failed("wrong_registration")
        }

        // `P` is optional in NIP-57, but when a provider includes one it must
        // agree with the request it is attesting to.
        val payerTag = tag(receipt, "P")
        if (payerTag != null && payerTag != expected.payerPubkey) return Verdict.Failed("wrong_payer")

        // Last, because the checks above give the more useful answer when both
        // fail: the invoice itself.
        val invoice = when (val decoded = CompetitionBolt11.decode(bolt11)) {
            is CompetitionBolt11.Result.Ok -> decoded.invoice
            is CompetitionBolt11.Result.Failed -> return Verdict.Failed("unreadable_invoice")
        }
        val invoiceMsat = invoice.amountMsat
        if (invoiceMsat != null && invoiceMsat < expected.amountMsat) {
            return Verdict.Failed("invoice_too_small")
        }

        var weaklyBound = false
        val descriptionHash = invoice.descriptionHash
        if (descriptionHash != null) {
            if (descriptionHash != CompetitionDigest.sha256Hex(description)) {
                return Verdict.Failed("invoice_not_bound")
            }
        } else {
            // NIP-57 makes the description hash a SHOULD. A receipt without one
            // still counts, but the audit trail says it was bound weakly rather
            // than pretending the strongest check happened.
            weaklyBound = true
        }

        expected.notBefore?.let { if (receipt.createdAt < it) return Verdict.Failed("receipt_too_early") }
        expected.notAfter?.let { if (receipt.createdAt > it) return Verdict.Failed("receipt_too_late") }

        return Verdict.Ok(requested, bolt11, weaklyBound)
    }

    /**
     * Does this event's id actually cover this event's body?
     *
     * NIP-01's id is `sha256` over a canonical array. An event whose tags were
     * edited after signing keeps its old id, so this catches the edit without
     * needing the signature at all.
     */
    fun idBinds(event: CompetitionEvent): Boolean {
        val serialized = Ccj.encode(
            JsonArray(
                listOf(
                    JsonPrimitive(0),
                    JsonPrimitive(event.pubkey),
                    JsonPrimitive(event.createdAt),
                    JsonPrimitive(event.kind),
                    JsonArray(event.tags.map { tag -> JsonArray(tag.map { JsonPrimitive(it) }) }),
                    JsonPrimitive(event.content),
                ),
            ),
        )
        return CompetitionDigest.sha256Hex(serialized) == event.id.lowercase()
    }

    /** Read a raw JSON event into the shape this layer works with. */
    fun eventFrom(payload: JsonObject): CompetitionEvent? {
        val id = payload.str("id") ?: return null
        val pubkey = payload.str("pubkey") ?: return null
        val kind = payload.int("kind") ?: return null
        val createdAt = payload.long("created_at") ?: return null
        val tags = payload["tags"]?.jsonArray.orEmpty().map { row ->
            row.jsonArray.mapNotNull { (it as? JsonPrimitive)?.content }
        }
        return CompetitionEvent(
            id = id,
            pubkey = pubkey,
            createdAt = createdAt,
            kind = kind,
            tags = tags,
            content = payload.str("content").orEmpty(),
        )
    }
}
