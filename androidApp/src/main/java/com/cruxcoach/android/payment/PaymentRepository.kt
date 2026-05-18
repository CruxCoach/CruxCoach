package com.cruxcoach.android.payment

import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.db.secure.Payment_events
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PaymentRepository @Inject constructor(
    private val database: SecureDatabase
) {
    private val queries get() = database.paymentEventsQueries

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

    fun getAll(): List<Payment_events> = queries.getAll().executeAsList()

    fun getRecent(limit: Long): List<Payment_events> =
        queries.getRecent(limit).executeAsList()
}
