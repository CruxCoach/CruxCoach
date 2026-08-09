package com.cruxcoach.android.competition

import android.util.Log
import com.cruxcoach.android.nostr.NostrEventPolicy
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.domain.competition.Competition
import com.cruxcoach.domain.competition.CompetitionProtocol
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verifyId
import com.vitorpamplona.quartz.nip01Core.crypto.verifySignature
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finding public competitions.
 *
 * Only `public` ones are discoverable, and that is enforced at publish time
 * rather than here: an `unlisted` competition carries no `t` tag, so this query
 * cannot return it. Filtering unlisted entries out in the client would be a
 * fiction — the events would still be sitting on the relay under a searchable
 * hashtag.
 */
@Singleton
class CompetitionDiscovery @Inject constructor(
    private val relayPool: NostrRelayPool,
) {

    data class Listing(
        val competition: Competition,
        val organizerPubkey: String,
        val eventId: String,
        val createdAt: Long,
    )

    /**
     * @param sinceSeconds only competitions published after this. Defaults to
     *   90 days: a relay asked for everything answers with years of them, and
     *   most relays silently clamp the limit anyway.
     */
    suspend fun search(
        nowSeconds: Long,
        sinceSeconds: Long = nowSeconds - 90L * 24 * 3600,
        limit: Int = 200,
    ): List<Listing> {
        val filter = """{"kinds":[${CompetitionProtocol.KIND}],""" +
            """"#t":["cruxcoach-competition"],"since":$sinceSeconds,"limit":$limit}"""

        // Newest per address: a competition is edited in place, and an older
        // revision from a lagging relay must not win.
        val newest = linkedMapOf<String, Pair<Event, Competition>>()
        runCatching {
            relayPool.subscribe(filter, closeOnEose = true).collect { json ->
                val event = runCatching { Event.fromJson(json) }.getOrNull() ?: return@collect
                val signatureValid = runCatching { event.verifySignature() }.getOrDefault(false)
                val idValid = signatureValid && runCatching { event.verifyId() }.getOrDefault(false)
                if (!NostrEventPolicy.hasValidBodyBinding(signatureValid, idValid)) return@collect
                if (!NostrEventPolicy.isCreatedAtAcceptable(event.createdAt, nowSeconds)) return@collect

                val parsed = CompetitionProtocol.parseCompetition(event.toCompetitionEvent(), nowSeconds)
                if (parsed !is CompetitionProtocol.ParsedCompetition.Valid) return@collect
                // Defence in depth: the relay was asked for public competitions,
                // and the relay is not the trust boundary.
                if (parsed.competition.visibility != "public") return@collect

                val key = parsed.address
                val existing = newest[key]
                if (existing == null || event.createdAt > existing.first.createdAt) {
                    newest[key] = event to parsed.competition
                }
            }
        }.onFailure { Log.w(TAG, "competition discovery failed", it) }

        return newest.values
            .map { (event, competition) ->
                Listing(competition, event.pubKey, event.id, event.createdAt)
            }
            // Soonest first — a list of competitions is a list of things that
            // are about to happen, not a list of things recently typed.
            .sortedWith(compareBy({ it.competition.startsAt }, { it.competition.title }))
    }

    /**
     * Local, case-insensitive filtering over an already-fetched list.
     *
     * Local because relay-side search is a single NIP-50 implementation on one
     * relay we do not ship: filtering here works everywhere, and the list is
     * already bounded by the query above.
     */
    fun filter(listings: List<Listing>, query: String): List<Listing> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return listings
        return listings.filter { listing -> haystack(listing.competition).contains(needle) }
    }

    private fun haystack(competition: Competition): String = buildString {
        append(competition.title.lowercase()).append(' ')
        append(competition.summary.lowercase()).append(' ')
        val raw = competition.raw
        (raw["venue"] as? kotlinx.serialization.json.JsonObject)?.let { venue ->
            append(text(venue, "name")).append(' ').append(text(venue, "address")).append(' ')
        }
        (raw["board"] as? kotlinx.serialization.json.JsonObject)?.let { board ->
            append(text(board, "brand")).append(' ').append(text(board, "model")).append(' ')
            append(text(board, "size")).append(' ')
        }
        (raw["organizer"] as? kotlinx.serialization.json.JsonObject)?.let { organizer ->
            append(text(organizer, "name")).append(' ')
        }
    }

    private fun text(obj: kotlinx.serialization.json.JsonObject, key: String): String =
        ((obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: "").lowercase()

    companion object {
        private const val TAG = "CompetitionDiscovery"
    }
}
