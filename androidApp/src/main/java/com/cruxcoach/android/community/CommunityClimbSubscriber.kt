package com.cruxcoach.android.community

import android.util.Log
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.community.ClimbBounds
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
    private val nostrSigner: NostrSigner,
    private val nostrProfileManager: com.cruxcoach.android.payment.NostrProfileManager,
) {

    /**
     * Set of pubkeys whose Kind 0 we've already resolved (or attempted)
     * during this process lifetime. Process-scoped so we don't spam
     * relays when a setter publishes 50 climbs in a burst — only the
     * first event triggers a fetch, the rest hit this in-memory guard.
     * The persistent cache lives in `nostr_profiles`; this is just a
     * de-duper in front of it.
     */
    private val pubkeysResolvedThisRun = mutableSetOf<String>()
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
            seedCursorFromManifestIfFirstRun()
            runSubscriptionLoop()
        }
    }

    /**
     * On a fresh install (or wiped prefs), seed the live-sub cursor with
     * the Blossom-manifest's `created_at`. Rationale: the cron already
     * merges all CruxCoach community climbs older than `manifest.created_at`
     * into the blob, so the live sub doesn't need to re-fetch them from
     * relays. This shrinks the cold-start delta from "all of history" to
     * "~24 h since last cron run", which is the difference between a
     * 100 MB Nostr-burst and ~50 KB.
     *
     * Skipped when:
     *  - cursor is already set (we've persisted at least one event)
     *  - no Blossom manifest fetched yet (rare — first launch before
     *    initial sync; the cron-snapshot pathway hasn't kicked in either,
     *    so a full backfill is the right thing)
     */
    private suspend fun seedCursorFromManifestIfFirstRun() {
        val cursor = userPreferences.communityClimbSince.first()
        if (cursor != null && cursor > 0) return
        val manifestEpoch = userPreferences.blossomManifestCreatedAt.first() ?: return
        if (manifestEpoch <= 0) return
        Log.i(TAG, "seeding cursor from blossom manifest: $manifestEpoch")
        userPreferences.setCommunityClimbSince(manifestEpoch)
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

        // Self-filter: skip events we authored ourselves. Relays echo
        // every event back to all subscribers including the publisher,
        // and our own climbs already live in the local DB with the
        // correct `source='local'`, `sync_status='published_nostr'`,
        // `kilter_status=…` flags set by the publish path. Letting
        // upsertCommunityClimb (INSERT OR REPLACE) write over them
        // would clobber kilter_status / kilter_synced_at and remove the
        // climb from the KilterPublishRetryWorker's filter set
        // (`sync_status='published_nostr'` flips to 'synced'). For other
        // users' events this isn't a problem — their cruxcoach metadata
        // never existed locally.
        val ownPubkey = runCatching { nostrSigner.getPublicKeyHex() }.getOrNull()
        if (ownPubkey != null && parsedClimb.pubkey == ownPubkey) {
            Log.d(TAG, "skip own event uuid=${parsedClimb.uuid}")
            return
        }

        // Skip ungraded events — per the "no synthetic stats" rule, we
        // don't want to manufacture NULL-difficulty rows that pollute
        // default browse.
        val grade = parsedClimb.setterGradeId ?: return
        val angle = parsedClimb.angle ?: return

        // Stale-event protection: replaceable Kind-30078 events can arrive
        // out of order if the user's main key republished, then a backup
        // restore replays the older copy on a different relay. Compare
        // event.created_at against whatever we already have for this uuid.
        val incomingIso = epochToIso(parsedClimb.createdAt)
        val existingIso = boardRepository.getClimbCreatedAt(parsedClimb.uuid)
        if (existingIso != null && existingIso > incomingIso) {
            Log.i(
                TAG,
                "skip stale event uuid=${parsedClimb.uuid} " +
                    "incoming=$incomingIso existing=$existingIso",
            )
            return
        }

        val moveCount = computeMoveCount(parsedClimb.framesText)

        try {
            boardRepository.upsertCommunityClimb(
                uuid = parsedClimb.uuid,
                layoutId = parsedClimb.layoutId,
                // Display stub mirrors the cron-side merge format
                // (`merge_cruxcoach_climbs` in update_board_db.py): same
                // 16-hex-char prefix means a Blossom blob refresh produces
                // no diff with what the live sub already wrote. When Plan 3
                // adds Kind-0 lookup the UI takes the real display_name,
                // this column is the persistent fallback.
                setterUsername = "npub:${parsedClimb.pubkey.take(16)}",
                name = parsedClimb.name,
                framesText = parsedClimb.framesText,
                description = parsedClimb.description,
                moveCount = moveCount.toLong(),
                nostrEventId = parsedClimb.eventId,
                nostrDTag = parsedClimb.dTag,
                createdByPubkey = parsedClimb.pubkey,
                framesHash = parsedClimb.framesHash,
                createdAt = incomingIso,
                angle = angle.toLong(),
                difficultyAverage = grade.toDouble(),
                qualityAverage = null,
                bounds = parsedClimb.bounds,
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

        // Lazy Kind-0 resolution for the setter. Cache-first via
        // NostrProfileManager: the first event from a new pubkey triggers
        // a relay fetch, subsequent events for the same pubkey hit the
        // local profileQueries cache. The in-memory `pubkeysResolvedThisRun`
        // guard keeps us from even invoking getProfile (which would hit
        // SQLDelight) on tight bursts. Fire-and-forget on the same scope
        // — failures don't affect climb display, just leave the npub stub.
        resolveSetterDisplayName(parsedClimb.pubkey)
    }

    /**
     * Async fetch of Kind 0 for [pubkey] and bulk-update of every
     * community climb's `setter_username`. No-op when the pubkey was
     * already resolved this process lifetime, when no profile is found,
     * or when the profile lacks a non-blank display_name.
     */
    private fun resolveSetterDisplayName(pubkey: String) {
        if (!pubkeysResolvedThisRun.add(pubkey)) return
        // Use the same job's coroutine scope so we don't accumulate
        // orphan coroutines if the subscriber is stopped. job is the
        // top-level subscription; we fork a child that survives the
        // current event handler frame but stops with the subscriber.
        val parent = job ?: return
        kotlinx.coroutines.CoroutineScope(parent).launch {
            val profile = runCatching { nostrProfileManager.getProfile(pubkey) }.getOrNull()
            val displayName = profile?.displayName?.takeIf { it.isNotBlank() } ?: return@launch
            runCatching {
                boardRepository.updateSetterUsernameForPubkey(
                    pubkey = pubkey,
                    displayName = displayName,
                )
            }.onFailure { Log.w(TAG, "setter rename for $pubkey failed", it) }
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
        val bounds: ClimbBounds?,
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
                var bounds: ClimbBounds? = null
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
                        // Optional Plan-2 tag: "L,R,B,T". Malformed values
                        // decode to null and we fall back to NULL edge_*
                        // (matches pre-Plan-2 events that have no tag).
                        "bounds" -> bounds = ClimbBounds.decode(tag[1])
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
                    bounds = bounds,
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
