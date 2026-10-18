package com.cruxcoach.app.zap

import com.cruxcoach.app.ui.Subscription
import com.cruxcoach.db.secure.SecureDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Swift-facing snapshot of the gift sheet (Android `PaymentState`). */
class ZapUi(
    /** choose | amount | invoice */
    val stepCode: String,
    /** Private LNURL payment (true) or public Nostr zap (false). */
    val isPrivate: Boolean,
    val isSending: Boolean,
    /** The BOLT 11 invoice on the invoice step; "" otherwise. */
    val invoice: String,
    /**
     * none | noAddress | payInfoUnavailable | outOfRange | noNostrSupport |
     * signingFailed | invoiceUnavailable | invoiceMismatch | failed
     */
    val errorCode: String,
    /** The endpoint's limits in sats with outOfRange; 0 otherwise. */
    val minSats: Long,
    val maxSats: Long,
)

/**
 * The payment-events table Android writes when an invoice was created
 * (`PaymentRepository.insert`). It records the request, not a payment: the
 * app never learns whether the wallet paid.
 */
class PaymentLog(private val database: SecureDatabase) {
    fun recordRequest(
        id: String,
        privately: Boolean,
        senderPubkey: String,
        recipientPubkey: String,
        amountSats: Long,
        message: String?,
        createdAtMillis: Long,
    ) {
        database.paymentEventsQueries.insert(
            id = id,
            type = if (privately) "LIGHTNING_PRIVATE" else "LIGHTNING",
            direction = "sent",
            sender_pubkey = senderPubkey,
            recipient_pubkey = recipientPubkey,
            event_id = null,
            amount_sats = amountSats,
            message = message,
            created_at = createdAtMillis,
        )
    }
}

/**
 * Android's `PaymentViewModel` + `SettingsPaymentDialogs`: choose private
 * Lightning or a public zap, pick an amount, get an invoice for a wallet.
 *
 * What it never does: pay, or say that a payment happened. The invoice is
 * handed to a wallet app (or copied / scanned); whether it was paid is between
 * the wallet and the recipient.
 */
class ZapModel(
    private val client: ZapClient,
    private val recipientPubkey: String,
    private val senderPubkey: String,
    private val log: PaymentLog,
    /** Encrypted note to the maintainer for a private gift with a message. */
    private val donationMessage: suspend (content: String, subject: String) -> Unit,
    private val idFactory: () -> String,
    private val millis: () -> Long,
    private val scope: CoroutineScope,
    /** Outlives the sheet: the note must not be cut off when the sheet closes. */
    private val appScope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val state = MutableStateFlow(ZapUi(STEP_CHOOSE, true, false, "", ERROR_NONE, 0, 0))

    val currentState: ZapUi get() = state.value

    /** Android's preset chips: 1k, 5k, 10k, 21k, 50k sats. */
    val presetAmounts: List<Long> = PRESETS

    /** Preselected chip, as on Android. */
    val defaultAmount: Long = DEFAULT_AMOUNT

    fun watch(onState: (ZapUi) -> Unit): Subscription {
        val job = scope.launch { state.collect { onState(it) } }
        return Subscription { job.cancel() }
    }

    /** "Lightning (private)" or "Lightning Zap (public)" on the first step. */
    fun selectLightning(privately: Boolean) {
        if (state.value.isSending) return
        state.value = ZapUi(STEP_AMOUNT, privately, false, "", ERROR_NONE, 0, 0)
    }

    /** Back from the amount step to the choice. */
    fun backToChoice() {
        if (state.value.isSending) return
        state.value = ZapUi(STEP_CHOOSE, state.value.isPrivate, false, "", ERROR_NONE, 0, 0)
    }

    /** Asks the recipient's LNURL server for an invoice of [amountSats]. */
    fun requestInvoice(amountSats: Long, message: String) {
        val current = state.value
        if (current.isSending || current.stepCode != STEP_AMOUNT) return
        if (amountSats <= 0 || amountSats > MAX_SATS) return
        val privately = current.isPrivate
        val note = message.trim()
        state.value = ZapUi(STEP_AMOUNT, privately, true, "", ERROR_NONE, 0, 0)
        scope.launch {
            val outcome = try {
                withContext(io) { client.createPaymentRequest(recipientPubkey, amountSats * 1000, note, privately) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val invoice = outcome?.invoice
            if (outcome == null || invoice == null) {
                state.value = ZapUi(
                    STEP_AMOUNT, privately, false, "", errorCode(outcome?.failure),
                    outcome?.minSats ?: 0, outcome?.maxSats ?: 0,
                )
                return@launch
            }
            try {
                withContext(io) {
                    log.recordRequest(idFactory(), privately, senderPubkey, recipientPubkey, amountSats, note.ifBlank { null }, millis())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The local record is bookkeeping; the invoice is still valid.
            }
            if (privately && note.isNotBlank()) {
                appScope.launch {
                    try {
                        withContext(io) { donationMessage("[DONATION] $amountSats sats — $note", DONATION_SUBJECT) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Android: the note is best effort, the gift proceeds without it.
                    }
                }
            }
            state.value = ZapUi(STEP_INVOICE, privately, false, invoice, ERROR_NONE, 0, 0)
        }
    }

    fun consumeError() {
        val s = state.value
        state.value = ZapUi(s.stepCode, s.isPrivate, s.isSending, s.invoice, ERROR_NONE, 0, 0)
    }

    fun close() = scope.cancel()

    private fun errorCode(failure: ZapFailure?): String = when (failure) {
        null -> "failed"
        ZapFailure.NO_ADDRESS -> "noAddress"
        ZapFailure.PAY_INFO_UNAVAILABLE -> "payInfoUnavailable"
        ZapFailure.OUT_OF_RANGE -> "outOfRange"
        ZapFailure.NO_NOSTR_SUPPORT -> "noNostrSupport"
        ZapFailure.SIGNING_FAILED -> "signingFailed"
        ZapFailure.INVOICE_UNAVAILABLE -> "invoiceUnavailable"
        ZapFailure.INVOICE_MISMATCH -> "invoiceMismatch"
    }

    companion object {
        const val STEP_CHOOSE = "choose"
        const val STEP_AMOUNT = "amount"
        const val STEP_INVOICE = "invoice"
        const val ERROR_NONE = "none"

        /** Android sends the note with this (German) subject. */
        const val DONATION_SUBJECT = "Spende"

        private val PRESETS = listOf(1_000L, 5_000L, 10_000L, 21_000L, 50_000L)
        private const val DEFAULT_AMOUNT = 5_000L

        /** 21 million BTC in sats: anything above cannot be an amount. */
        private const val MAX_SATS = 2_100_000_000_000_000L
    }
}

/** Row ids for [PaymentLog]: random version-4 UUIDs, as Android's `UUID.randomUUID()`. */
object PaymentIds {
    fun random(hashing: com.cruxcoach.app.platform.Hashing): String {
        val b = hashing.randomBytes(16)
        b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        val hex = "0123456789abcdef"
        val out = StringBuilder(36)
        for (i in 0 until 16) {
            if (i == 4 || i == 6 || i == 8 || i == 10) out.append('-')
            val v = b[i].toInt() and 0xff
            out.append(hex[v shr 4]).append(hex[v and 0x0f])
        }
        return out.toString()
    }
}
