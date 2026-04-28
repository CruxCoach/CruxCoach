package com.cruxcoach.android.community

import android.util.Log
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.HoldRole
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Live Nostr Channel B subscription for community-climb events.
 *
 * Subscribes to Kind-30078 events with the `com.cruxcoach.climb`
 * namespace label, parses each into a community-climb row, and upserts
 * into the local DB. Closes the gap between two daily Blossom snapshots
 * — climbs published while the user is online appear immediately, not
 * after the next blob refresh.
 *
 * Lifecycle: started once from [CruxCoachApp] on app boot. The pool
 * keeps the WebSocket open as long as the process lives; reconnects are
 * handled inside [NostrRelayPool] (we don't manage them here).
 *
 * Filter strategy:
 *  - `since` cursor: persisted in [UserPreferences] as
 *    `COMMUNITY_CLIMB_SINCE` (epoch seconds). Updated after each event
 *    that lands in the DB. On a fresh install the cursor is null →
 *    relays return all stored events they have, then live updates.
 *  - Skip events without a `setter_grade` tag — per the "no synthetic
 *    stats" rule, ungraded climbs would land as NULL difficulty rows
 *    and pollute default browse.
 */
@Singleton
class CommunityClimbSubscriber @Inject constructor(
    private val pool: NostrRelayPool,
    private val boardRepository: BoardRepository,
    private val userPreferences: UserPreferences,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var job: Job? = null

    /** Idempotent — calling twice is a no-op. */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            // Tiny startup delay so the relay pool has time to read user
            // prefs + connect; not strictly required, just avoids racing
            // the very first connection-open.
            delay(STARTUP_GRACE_MS)
            runSubscriptionLoop()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun runSubscriptionLoop() {
        while (true) {
            val since = userPreferences.communityClimbSince.first()
            val filter = buildFilter(since)
            try {
                pool.subscribe(filter, skipDedup = false, closeOnEose = false).collect { eventJson ->
                    handleEvent(eventJson)
                }
                // collect returned without throwing → relay flow ended cleanly
                // (rare). Brief backoff before re-subscribing.
                delay(BACKOFF_MS)
            } catch (e: Exception) {
                Log.w(TAG, "subscription terminated; re-subscribing", e)
                delay(BACKOFF_MS)
            }
        }
    }

    private fun buildFilter(sinceEpoch: Long?): String {
        val sinceClause = if (sinceEpoch != null && sinceEpoch > 0) ",\"since\":$sinceEpoch" else ""
        return """{"kinds":[30078],"#L":["$NAMESPACE_LABEL"]$sinceClause}"""
    }

    private suspend fun handleEvent(eventJson: String) {
        val parsed = runCatching {
            json.parseToJsonElement(eventJson).jsonObject
        }.getOrElse {
            Log.w(TAG, "failed to parse event JSON")
            return
        }
        val parsedClimb = runCatching { ParsedClimb.from(parsed) }.getOrNull() ?: return
        // Skip ungraded events — per the "no synthetic stats" rule, we
        // don't want to manufacture NULL-difficulty rows that pollute
        // default browse.
        val grade = parsedClimb.setterGradeId ?: return
        val angle = parsedClimb.angle ?: return

        val moveCount = computeMoveCount(parsedClimb.framesText)

        try {
            boardRepository.upsertCommunityClimb(
                uuid = parsedClimb.uuid,
                layoutId = parsedClimb.layoutId,
                setterUsername = null,
                name = parsedClimb.name,
                framesText = parsedClimb.framesText,
                description = parsedClimb.description,
                moveCount = moveCount.toLong(),
                nostrEventId = parsedClimb.eventId,
                nostrDTag = parsedClimb.dTag,
                createdByPubkey = parsedClimb.pubkey,
                framesHash = parsedClimb.framesHash,
                createdAt = epochToIso(parsedClimb.createdAt),
                angle = angle.toLong(),
                difficultyAverage = grade.toDouble(),
                qualityAverage = null,
            )
        } catch (e: Exception) {
            Log.w(TAG, "upsertCommunityClimb failed for uuid=${parsedClimb.uuid}", e)
            return
        }

        // Advance cursor only after a successful upsert. Use the event's
        // own created_at; relays may stream out of order so we max() rather
        // than blindly overwrite (a stale event could rewind us).
        val current = userPreferences.communityClimbSince.first() ?: 0L
        if (parsedClimb.createdAt > current) {
            userPreferences.setCommunityClimbSince(parsedClimb.createdAt)
        }
    }

    private fun computeMoveCount(framesText: String): Int {
        val holds = runCatching { BoardClimbParser.parseFrames(framesText) }.getOrNull() ?: return 0
        return holds.count { it.roleId == HoldRole.HAND || it.roleId == HoldRole.START }
    }

    private fun epochToIso(epochSeconds: Long): String =
        java.time.Instant.ofEpochSecond(epochSeconds).toString()

    private data class ParsedClimb(
        val eventId: String,
        val pubkey: String,
        val createdAt: Long,
        val dTag: String,
        val uuid: String,
        val name: String,
        val description: String,
        val framesText: String,
        val framesHash: String,
        val layoutId: Long,
        val setterGradeId: Int?,
        val angle: Int?,
    ) {
        companion object {
            fun from(event: JsonObject): ParsedClimb {
                val eventId = event["id"]?.jsonPrimitive?.contentOrNull
                    ?: error("event id missing")
                val pubkey = event["pubkey"]?.jsonPrimitive?.contentOrNull
                    ?: error("event pubkey missing")
                val createdAt = event["created_at"]?.jsonPrimitive?.longOrNull
                    ?: error("event created_at missing")
                val tagsJson = event["tags"]?.jsonArray ?: JsonArray(emptyList())

                var dTag: String? = null
                var framesText: String? = null
                var framesHashRaw: String? = null
                var layoutId: Long? = null
                var setterGradeId: Int? = null
                var angle: Int? = null
                var foundLabel = false

                for (tagElem in tagsJson) {
                    val tag = tagElem.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
                    if (tag.size < 2) continue
                    when (tag[0]) {
                        "d" -> dTag = tag[1]
                        "L" -> if (tag[1] == "com.cruxcoach.climb") foundLabel = true
                        "frames" -> framesText = tag[1]
                        "frames_hash" -> framesHashRaw = tag[1]
                        "layout_id" -> layoutId = tag[1].toLongOrNull()
                        "setter_grade" -> {
                            setterGradeId = tag[1].toIntOrNull()
                            if (tag.size >= 3) angle = tag[2].toIntOrNull()
                        }
                    }
                }
                require(foundLabel) { "not a com.cruxcoach.climb event" }
                require(dTag != null) { "d-tag missing" }
                require(framesText != null && framesText!!.isNotBlank()) { "frames tag missing" }
                require(layoutId != null) { "layout_id tag missing" }

                val contentObj = runCatching {
                    Json { ignoreUnknownKeys = true; isLenient = true }
                        .parseToJsonElement(event["content"]?.jsonPrimitive?.contentOrNull ?: "{}")
                        .jsonObject
                }.getOrNull() ?: JsonObject(emptyMap())
                val uuid = contentObj["uuid"]?.jsonPrimitive?.contentOrNull
                    ?: dTagUuid(dTag!!)
                    ?: error("uuid not derivable from event")
                val name = contentObj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val description = contentObj["description"]?.jsonPrimitive?.contentOrNull.orEmpty()

                // frames_hash arrives as "sha256:<hex>" — strip the prefix
                // before persisting (our column stores the hex only).
                val framesHash = framesHashRaw?.removePrefix("sha256:") ?: ""

                return ParsedClimb(
                    eventId = eventId,
                    pubkey = pubkey,
                    createdAt = createdAt,
                    dTag = dTag!!,
                    uuid = uuid,
                    name = name,
                    description = description,
                    framesText = framesText!!,
                    framesHash = framesHash,
                    layoutId = layoutId!!,
                    setterGradeId = setterGradeId,
                    angle = angle,
                )
            }

            /** d-tag pattern: cruxcoach:climb:<pubkey-prefix-8>:<uuid>. */
            private fun dTagUuid(dTag: String): String? {
                val parts = dTag.split(":")
                return if (parts.size >= 4 && parts[0] == "cruxcoach" && parts[1] == "climb")
                    parts.last() else null
            }
        }
    }

    private companion object {
        const val TAG = "CommunityClimbSub"
        const val NAMESPACE_LABEL = "com.cruxcoach.climb"
        const val STARTUP_GRACE_MS = 2_000L
        const val BACKOFF_MS = 5_000L
    }
}
