package com.cruxcoach.android.competition

import com.cruxcoach.domain.competition.Ccj
import com.cruxcoach.domain.competition.CompetitionProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Minimal host-to-participant confirmation carried as NIP-44 ciphertext.
 *
 * This is deliberately not a second competition state. It only answers the
 * remote UX question "did the host accept my request?". The signed local
 * authority chain remains canonical and is replayed when the participant
 * reaches the on-site competition mesh.
 */
data class CompetitionPrivateReceipt(
    val op: String,
    val state: String,
    val sourceEventId: String,
    val at: Long,
)

object CompetitionPrivateReceiptCodec {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }
    private val eventId = Regex("^[0-9a-f]{64}$")

    fun dTag(compId: String, recipient: String, seq: Int): String =
        "cruxcoach:comp:$compId:private:${recipient.take(8)}:${seq.toString().padStart(6, '0')}"

    fun content(
        compId: String,
        recipient: String,
        op: String,
        state: String,
        sourceEventId: String,
        at: Long,
    ): String = Ccj.encode(
        JsonObject(
            linkedMapOf(
                "v" to JsonPrimitive(CompetitionProtocol.SCHEMA_MAJOR),
                "type" to JsonPrimitive("private_receipt"),
                "comp_id" to JsonPrimitive(compId),
                "recipient" to JsonPrimitive(recipient),
                "op" to JsonPrimitive(op),
                "state" to JsonPrimitive(state),
                "source_event_id" to JsonPrimitive(sourceEventId),
                "at" to JsonPrimitive(at),
            ),
        ),
    )

    fun parse(
        plaintext: String,
        expectedCompId: String,
        expectedRecipient: String,
    ): CompetitionPrivateReceipt? {
        val obj = runCatching { json.parseToJsonElement(plaintext) as? JsonObject }.getOrNull() ?: return null
        fun string(name: String) = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if ((obj["v"] as? JsonPrimitive)?.content?.toIntOrNull() != CompetitionProtocol.SCHEMA_MAJOR) return null
        if (string("type") != "private_receipt" || string("comp_id") != expectedCompId ||
            string("recipient") != expectedRecipient
        ) return null
        val op = string("op")?.takeIf { it in setOf("registration_decision", "checkin") } ?: return null
        val state = string("state") ?: return null
        val allowed = if (op == "registration_decision") {
            setOf("accepted", "waitlisted", "rejected", "withdrawn")
        } else setOf("checked_in", "no_show")
        if (state !in allowed) return null
        val source = string("source_event_id")?.takeIf(eventId::matches) ?: return null
        val at = (obj["at"] as? JsonPrimitive)?.longOrNull ?: return null
        return CompetitionPrivateReceipt(op, state, source, at)
    }
}
