package com.cruxcoach.android.competition

import android.util.Log
import com.cruxcoach.android.nostr.NostrPublicEventBuilder
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.domain.competition.Competition
import com.cruxcoach.domain.competition.CompetitionProtocol
import com.vitorpamplona.quartz.nip01Core.core.Event
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A participant's requests — FEAT-058 §6.5, §8.2.
 *
 * These are *intents*, never state. The authority's decision about an intent is
 * what changes anything, and the UI says "sent" rather than "registered" until
 * that decision arrives. Code that blurs the two shows someone as entered
 * because they asked to be.
 */
@Singleton
class CompetitionIntentPublisher @Inject constructor(
    private val relayPool: NostrRelayPool,
    private val signer: NostrSigner,
) {

    /** Outcome of a publish, with enough detail for the UI to be honest. */
    sealed interface Result {
        data class Published(val event: Event, val attempted: Int, val accepted: Int) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * A nonce per (competition, operation), kept for the process lifetime.
     *
     * Reusing it means a retry REPLACES the earlier request rather than adding a
     * second one — the addressable-replacement rule doing the deduplication for
     * us, so a flaky network cannot turn one registration into three.
     */
    private val nonces = mutableMapOf<String, String>()

    private fun nonceFor(compId: String, op: String): String = synchronized(nonces) {
        nonces.getOrPut("$compId:$op") {
            (0 until 4).joinToString("") { "%02x".format(Random.nextInt(256)) }
        }
    }

    suspend fun register(
        competition: Competition,
        organizerPubkey: String,
        division: String,
        display: String,
        waiverAccepted: Boolean,
        selections: List<String> = emptyList(),
    ): Result {
        if (competition.waiverRequired && !waiverAccepted) {
            return Result.Failed("waiver")
        }
        val data = buildMap<String, kotlinx.serialization.json.JsonElement> {
            put("division", JsonPrimitive(division))
            put("display", JsonPrimitive(display))
            put("waiver_accepted", JsonPrimitive(waiverAccepted))
            if (selections.isNotEmpty()) put("selections", JsonArray(selections.map { JsonPrimitive(it) }))
        }
        return send(competition, organizerPubkey, "register", JsonObject(data))
    }

    suspend fun withdraw(competition: Competition, organizerPubkey: String): Result =
        send(competition, organizerPubkey, "withdraw", JsonObject(emptyMap()))

    suspend fun requestCheckIn(competition: Competition, organizerPubkey: String): Result =
        send(competition, organizerPubkey, "checkin_request", JsonObject(emptyMap()))

    /**
     * A deferral request carries a NIP-40 expiration: it is meaningless once the
     * turn deadline has passed, and an expired request sitting on a relay is
     * something an organizer could act on by mistake ten minutes later.
     */
    suspend fun requestDefer(
        competition: Competition,
        organizerPubkey: String,
        climbId: String,
        deadlineAt: Long,
    ): Result = send(
        competition,
        organizerPubkey,
        "defer_request",
        JsonObject(mapOf("climb_id" to JsonPrimitive(climbId))),
        expiration = deadlineAt,
    )

    suspend fun reportAttempt(
        competition: Competition,
        organizerPubkey: String,
        climbId: String,
        outcome: String,
        attemptNo: Int,
    ): Result = send(
        competition,
        organizerPubkey,
        "attempt_report",
        JsonObject(
            mapOf(
                "climb_id" to JsonPrimitive(climbId),
                "outcome" to JsonPrimitive(outcome),
                "attempt_no" to JsonPrimitive(attemptNo),
            ),
        ),
    )

    private suspend fun send(
        competition: Competition,
        organizerPubkey: String,
        op: String,
        data: JsonObject,
        expiration: Long? = null,
    ): Result {
        val pubkey = signer.getPublicKeyHex()
        val nonce = nonceFor(competition.compId, op)
        val at = System.currentTimeMillis() / 1000
        val payload = CompetitionIntentCodec.content(competition.compId, op, at, nonce, data)

        val tags = mutableListOf(
            listOf("d", CompetitionProtocol.intentDTag(competition.compId, pubkey, nonce)),
            listOf("L", CompetitionProtocol.NAMESPACE),
            listOf("l", "intent", CompetitionProtocol.NAMESPACE),
            listOf("cc-schema", CompetitionProtocol.SCHEMA),
            listOf("alt", "CruxCoach competition request: $op"),
            listOf("a", CompetitionProtocol.competitionAddress(organizerPubkey, competition.compId)),
            listOf("p", competition.authority),
            listOf("op", op),
        )
        expiration?.let { tags += listOf("expiration", it.toString()) }

        return try {
            val event = NostrPublicEventBuilder(signer)
                .buildSignedEvent(CompetitionProtocol.KIND, payload, tags)
            val (attempted, accepted) = relayPool.sendEventWithStats(event)
            if (accepted == 0) {
                // A publish no relay accepted has not happened. Saying otherwise
                // leaves someone standing at a wall believing they are entered.
                Log.w(TAG, "intent $op accepted by 0 of $attempted relays")
                Result.Failed("no_relay")
            } else {
                Result.Published(event, attempted, accepted)
            }
        } catch (e: Exception) {
            Log.w(TAG, "intent $op failed", e)
            Result.Failed(e.message ?: "unknown")
        }
    }

    companion object {
        private const val TAG = "CompetitionIntent"
    }
}

/**
 * The intent payload, serialized canonically.
 *
 * Hand-built through the shared canonical-JSON encoder rather than a serializer,
 * for the same reason the community-climb content is: the same input has to
 * produce the same bytes, or a retry of an "identical" request is a different
 * event with a different id.
 */
object CompetitionIntentCodec {
    fun content(compId: String, op: String, at: Long, nonce: String, data: JsonObject): String =
        com.cruxcoach.domain.competition.Ccj.encode(
            JsonObject(
                mapOf(
                    "v" to JsonPrimitive(CompetitionProtocol.SCHEMA_MAJOR),
                    "type" to JsonPrimitive("intent"),
                    "comp_id" to JsonPrimitive(compId),
                    "op" to JsonPrimitive(op),
                    "at" to JsonPrimitive(at),
                    "nonce" to JsonPrimitive(nonce),
                    "data" to data,
                ),
            ),
        )
}
