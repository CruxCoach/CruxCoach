package com.cruxcoach.android.payment

import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.db.secure.PaymentEvent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PaymentRepository @Inject constructor(
    private val database: SecureDatabase
) {
    private val queries get() = database.paymentEventQueries

    fun insert(
        id: String,
        type: String,
        direction: String,
        senderPubkey: String,
        recipientPubkey: String,
        eventId: String?,
        amountSats: Long,
        message: String?,
        createdAt: Long
    ) {
        queries.insert(
            id = id,
            type = type,
            direction = direction,
            sender_pubkey = senderPubkey,
            recipient_pubkey = recipientPubkey,
            event_id = eventId,
            amount_sats = amountSats,
            message = message,
            created_at = createdAt
        )
    }

    fun getAll(): List<PaymentEvent> = queries.getAll().executeAsList()

    fun getRecent(limit: Long): List<PaymentEvent> =
        queries.getRecent(limit).executeAsList()
}
