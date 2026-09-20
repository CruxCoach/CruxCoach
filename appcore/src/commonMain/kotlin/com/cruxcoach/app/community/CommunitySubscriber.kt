package com.cruxcoach.app.community

import com.cruxcoach.app.nostr.NostrEvent
import com.cruxcoach.app.nostr.NostrEvents
import com.cruxcoach.app.nostr.NostrRelays
import com.cruxcoach.app.platform.Hashing
import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.app.platform.WallClock
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.community.ClimbBounds
import com.cruxcoach.domain.community.ClimbValidation
import com.cruxcoach.domain.community.CommunityClimbTags
import com.cruxcoach.domain.community.FramesHash
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Why one event did not become a catalogue row. Counted, never silently dropped. */
enum class IngestSkip {
    NONE,
    OVERSIZE,
    UNVERIFIED,
    UNSUPPORTED_KIND,
    FUTURE_DATED,
    MALFORMED,
    OWN_EVENT,
    DTAG_AUTHOR_MISMATCH,
    CONTENT_PREFIX_MISMATCH,
    CROSS_AUTHOR,
    NON_COMMUNITY_CLIMB,
    TOMBSTONED,
    NAME_TOO_LONG,
    DESCRIPTION_TOO_LONG,
    TOO_MANY_HOLDS,
    FRAMES_HASH_MISMATCH,
    NO_SETTER_GRADE,
    NO_ANGLE,
    STALE,
    WRITE_FAILED,
}

/** What one [CommunitySubscriber.fetch] pass did. */
class CommunityFetchResult(
    val received: Int,
    val imported: Int,
    val tombstoned: Int,
    val skipped: Int,
    /** The persisted `since` cursor after the pass. 0 = no cursor yet. */
    val cursor: Long,
)

/**
 * Ingests CruxCoach community climbs from the relays.
 *
 * Port of Android's `CommunityClimbSubscriber` with one structural difference:
 * Android holds a long-lived REQ through its relay pool and streams events as
 * they arrive, while [com.cruxcoach.app.nostr.RelayClient] only does one-shot
 * REQ/EOSE queries — so this class is polled ([fetch]) and leans on the
 * persisted `since` cursor to pick up where it left off. Every ingest rule is
 * the Android one; only the arrival schedule differs.
 *
 * Not ported: the Kind-0 setter-name resolution (iOS has no profile cache yet,
 * so rows keep the `npub:` stub Android also persists as its fallback), the
 * reconnect/backoff ladder and the Blossom-manifest cursor seed (there is no
 * manifest epoch on this side to seed from).
 */
class CommunitySubscriber(
    private val boardRepository: BoardRepository,
    private val relay: CommunityRelay,
    private val store: KeyValueStore,
    private val hashing: Hashing,
    private val clock: WallClock,
    private val pubkeyProvider: () -> String?,
) {
    /**
     * One pass: ask every relay for events newer than the cursor, ingest what
     * survives the guards, and persist the new cursor.
     */
    suspend fun fetch(timeoutMs: Long = NostrRelays.RELAY_TIMEOUT_MS): CommunityFetchResult {
        val events = relay.query(listOf(buildFilter(since())), timeoutMs)
        var imported = 0
        var tombstoned = 0
        var skipped = 0
        for (event in events) {
            when (handleEvent(event)) {
                Ingested.CLIMB -> imported++
                Ingested.TOMBSTONE -> tombstoned++
                Ingested.SKIPPED -> skipped++
            }
        }
        return CommunityFetchResult(events.size, imported, tombstoned, skipped, since() ?: 0L)
    }

    /**
     * Replays the dead-letter queue: events whose write failed keep their signed
     * bytes, so a later pass can re-verify and re-apply them. Without the queue
     * the cursor would advance past a failed event and NIP-01 would never
     * re-deliver it.
     */
    fun retryDeadLetters(): Int {
        val rows = try {
            boardRepository.getRetriableCommunityClimbDeadLetters(MAX_DEAD_LETTER_RETRIES, MAX_DEAD_LETTER_BATCH)
        } catch (e: Exception) {
            return 0
        }
        var recovered = 0
        for (row in rows) {
            val event = parseEvent(row.rawEventJson)
            // Bytes that no longer verify, or that were never a climb event, are
            // abandoned rather than retried forever.
            if (event == null || event.kind != KIND_COMMUNITY_CLIMB || !NostrEvents.verify(hashing, event)) {
                runCatching { boardRepository.deleteCommunityClimbDeadLetter(row.uuid) }
                continue
            }
            if (handleEvent(event) == Ingested.CLIMB) recovered++
        }
        return recovered
    }

    /** Persisted cursor, or null on a fresh install (relays then send their history). */
    fun since(): Long? {
        val stored = store.getString(KEY_SINCE)?.toLongOrNull() ?: return null
        // A cursor from the future would filter out every real event. Drop it.
        val ceiling = clock.epochSeconds() + CommunityClimbValidation.MAX_FUTURE_SKEW_SECONDS
        if (stored > ceiling) {
            store.putString(KEY_SINCE, null)
            return null
        }
        return stored.takeIf { it > 0 }
    }

    internal enum class Ingested { CLIMB, TOMBSTONE, SKIPPED }

    /**
     * Dual namespace: the legacy Kilter label plus the v2 label every non-Kilter
     * board uses, in one REQ. Kind-5 deletions carry the same label, so the one
     * filter catches both kinds — foreign Kind-5s without our label are filtered
     * relay-side and never arrive.
     */
    internal fun buildFilter(sinceEpoch: Long?): String {
        val since = if (sinceEpoch != null && sinceEpoch > 0) ",\"since\":$sinceEpoch" else ""
        return """{"kinds":[$KIND_COMMUNITY_CLIMB,$KIND_DELETION],"#L":["${CommunityClimbTags.NS_CLIMB}","${CommunityClimbTags.NS_CLIMB_V2}"]$since}"""
    }

    /** Last skip reason, for tests and diagnostics. Never user-visible text. */
    internal var lastSkip: IngestSkip = IngestSkip.NONE
        private set

    internal fun handleEvent(event: NostrEvent): Ingested {
        val raw = serialize(event)
        // Bound the payload before any of it is trusted.
        if (!CommunityClimbValidation.eventSizeAcceptable(raw.length)) return skip(IngestSkip.OVERSIZE)
        // The relay is untrusted: the id must bind this exact body and the
        // signature must verify. A relay can otherwise serve a validly-signed
        // event whose tags it edited.
        if (!NostrEvents.verify(hashing, event)) return skip(IngestSkip.UNVERIFIED)
        val now = clock.epochSeconds()
        if (!CommunityClimbValidation.isWithinClockSkew(event.createdAt, now)) return skip(IngestSkip.FUTURE_DATED)
        return when (event.kind) {
            KIND_COMMUNITY_CLIMB -> handleClimbEvent(event, raw)
            KIND_DELETION -> handleDeletionEvent(event)
            else -> skip(IngestSkip.UNSUPPORTED_KIND)
        }
    }

    private fun handleClimbEvent(event: NostrEvent, raw: String): Ingested {
        val climb = ParsedClimb.from(event) ?: return skip(IngestSkip.MALFORMED)

        // Self-filter: relays echo our own publishes back. Our rows already carry
        // the correct source/sync_status, and letting the echo through
        // INSERT OR REPLACE would clobber them.
        val own = pubkeyProvider()
        if (own != null && climb.pubkey == own) return skip(IngestSkip.OWN_EVENT)
        if (own == null) {
            // Backstop for a signer outage only: with a resolvable key the check
            // above is authoritative, and blocking on `source='local'` there would
            // hide foreign climbs after an identity switch.
            val locallyAuthored = runCatching { boardRepository.isLocallyAuthored(climb.uuid) }.getOrDefault(false)
            if (locallyAuthored) return skip(IngestSkip.OWN_EVENT)
        }

        // The wire format encodes the author in the d-tag; re-check it so a
        // legitimate signer cannot claim someone else's d-tag namespace.
        if (!CommunityClimbValidation.dTagAuthorMatches(climb.dTag, climb.pubkey)) {
            return skip(IngestSkip.DTAG_AUTHOR_MISMATCH)
        }
        if (!CommunityClimbValidation.contentPubkeyPrefixMatches(climb.contentPubkeyPrefix, climb.pubkey)) {
            return skip(IngestSkip.CONTENT_PREFIX_MISMATCH)
        }

        var existingAuthor = runCatching { boardRepository.getClimbAuthorPubkey(climb.uuid) }.getOrNull()
        if (!CommunityClimbValidation.authorOwnershipMatches(existingAuthor, climb.pubkey)) {
            // A synthetic tombstone shell planted by a foreign deletion must not
            // permanently block the genuine author's uuid.
            val removed = runCatching {
                boardRepository.removeForeignTombstoneShell(climb.uuid, climb.pubkey)
            }.getOrDefault(false)
            if (removed) existingAuthor = null
        }
        if (!CommunityClimbValidation.authorOwnershipMatches(existingAuthor, climb.pubkey)) {
            return skip(IngestSkip.CROSS_AUTHOR)
        }

        // A NULL author means both "no row" and "catalogue row with no author",
        // so the ownership check alone would let a community event hijack
        // official content.
        if (runCatching { boardRepository.isNonCommunityClimb(climb.uuid) }.getOrDefault(false)) {
            return skip(IngestSkip.NON_COMMUNITY_CLIMB)
        }

        if (climb.deleted) {
            absorbTombstone(climb.uuid, climb.pubkey, climb.dTag, CommunityEventTime.isoFromEpochSeconds(climb.createdAt))
            advanceCursorIfNewer(climb.createdAt)
            return Ingested.TOMBSTONE
        }

        // Absorption: once tombstoned, a re-broadcast from a non-deleting relay
        // must not resurrect the climb through INSERT OR REPLACE's column default.
        if (runCatching { boardRepository.isClimbTombstoned(climb.uuid) }.getOrDefault(false)) {
            return skip(IngestSkip.TOMBSTONED)
        }

        // The same caps the editor enforces locally, re-applied here so a
        // hand-signed event cannot bypass them.
        if (climb.name.length > ClimbValidation.NAME_MAX_LENGTH) return skip(IngestSkip.NAME_TOO_LONG)
        if (climb.description.length > ClimbValidation.DESCRIPTION_MAX_LENGTH) {
            return skip(IngestSkip.DESCRIPTION_TOO_LONG)
        }
        val holds = runCatching { BoardClimbParser.parseFrames(climb.framesText) }.getOrDefault(emptyList())
        if (holds.size > ClimbValidation.MAX_HOLDS_TOTAL) return skip(IngestSkip.TOO_MANY_HOLDS)

        // Recompute the canonical hash: a signed event with a wrong one is either
        // tampered or buggy, and either way must not pollute duplicate detection.
        // An empty hash is tolerated for forward compatibility with older events.
        if (climb.framesHash.isNotEmpty()) {
            if (FramesHash.of(climb.framesText, climb.layoutId) != climb.framesHash) {
                return skip(IngestSkip.FRAMES_HASH_MISMATCH)
            }
        }

        // No synthetic stats: an ungraded or un-angled event would land as a NULL
        // difficulty row and pollute default browse.
        val grade = climb.setterGradeId ?: return skip(IngestSkip.NO_SETTER_GRADE)
        val angle = climb.angle ?: return skip(IngestSkip.NO_ANGLE)

        // Replaceable events can arrive out of order across relays.
        val existingIso = runCatching { boardRepository.getClimbCreatedAt(climb.uuid) }.getOrNull()
        if (existingIso != null && isExistingNewer(existingIso, climb.createdAt)) return skip(IngestSkip.STALE)

        try {
            boardRepository.upsertCommunityClimb(
                uuid = climb.uuid,
                layoutId = climb.layoutId,
                // Same 16-hex stub the server-side merge writes, so a catalogue
                // refresh produces no diff against what this pass stored.
                setterUsername = "npub:${climb.pubkey.take(16)}",
                name = climb.name,
                framesText = climb.framesText,
                description = climb.description,
                moveCount = BoardClimbParser.estimateMoveCount(holds).toLong(),
                nostrEventId = climb.eventId,
                nostrDTag = climb.dTag,
                createdByPubkey = climb.pubkey,
                framesHash = climb.framesHash,
                createdAt = CommunityEventTime.isoFromEpochSeconds(climb.createdAt),
                angle = angle.toLong(),
                difficultyAverage = grade.toDouble(),
                // Community vote aggregation does not exist yet; inventing a
                // quality here would be a fabricated number.
                qualityAverage = null,
                bounds = climb.bounds,
                boardBrand = climb.boardBrand,
            )
            runCatching { boardRepository.deleteCommunityClimbDeadLetter(climb.uuid) }
        } catch (e: Exception) {
            // Keep the signed bytes so a later pass can retry; the cursor is NOT
            // advanced, because a relay will not re-deliver this event.
            runCatching {
                boardRepository.recordCommunityClimbDeadLetter(
                    uuid = climb.uuid,
                    eventId = climb.eventId,
                    eventCreatedAt = climb.createdAt,
                    rawEventJson = raw,
                    nowMs = clock.epochMillis(),
                    errorExcerpt = (e.message ?: "write failed").take(MAX_DLQ_ERROR_EXCERPT),
                )
            }
            return skip(IngestSkip.WRITE_FAILED)
        }
        advanceCursorIfNewer(climb.createdAt)
        lastSkip = IngestSkip.NONE
        return Ingested.CLIMB
    }

    /**
     * NIP-09 deletion. Valid only when the signer is the referenced event's
     * author: a cross-author Kind-5 is either misuse or a denial-of-service
     * against someone else's climbs.
     */
    private fun handleDeletionEvent(event: NostrEvent): Ingested {
        val reference = event.firstTagValue("a") ?: return skip(IngestSkip.MALFORMED)
        val parts = reference.split(":")
        if (parts.size < 3) return skip(IngestSkip.MALFORMED)
        if (parts[0].toIntOrNull() != KIND_COMMUNITY_CLIMB) return skip(IngestSkip.UNSUPPORTED_KIND)
        if (parts[1] != event.pubkey) return skip(IngestSkip.CROSS_AUTHOR)
        // The d-tag itself contains colons, so everything past the second one belongs to it.
        val dTag = parts.drop(2).joinToString(":")
        if (!CommunityClimbValidation.dTagAuthorMatches(dTag, event.pubkey)) {
            return skip(IngestSkip.DTAG_AUTHOR_MISMATCH)
        }
        val uuid = CommunityClimbValidation.dTagUuid(dTag)?.lowercase() ?: return skip(IngestSkip.MALFORMED)
        if (!CommunityClimbValidation.uuidRouteSafe(uuid)) return skip(IngestSkip.MALFORMED)
        absorbTombstone(uuid, event.pubkey, dTag, CommunityEventTime.isoFromEpochSeconds(event.createdAt))
        advanceCursorIfNewer(event.createdAt)
        lastSkip = IngestSkip.NONE
        return Ingested.TOMBSTONE
    }

    /**
     * Two writes so cross-device deletion works without a race: the shell plants
     * an `is_deleted=1` memorial when no local row exists yet (so a later
     * original event is absorbed), then the owner-locked flip handles a real row.
     */
    private fun absorbTombstone(uuid: String, pubkey: String, dTag: String, tombstoneIso: String) {
        runCatching {
            boardRepository.insertTombstoneShell(uuid, pubkey, dTag, tombstoneIso)
            boardRepository.markCommunityClimbDeleted(uuid, pubkey, tombstoneIso)
        }
    }

    private fun advanceCursorIfNewer(eventCreatedAt: Long) {
        // Relays stream out of order, so take the maximum rather than the latest
        // arrival: a stale event must not rewind us past unread ones.
        val current = since() ?: 0L
        if (eventCreatedAt > current) store.putString(KEY_SINCE, eventCreatedAt.toString())
    }

    /**
     * True when what we already hold is strictly newer than the incoming event.
     *
     * Compared as instants, not as strings: `"…00:00:00.000Z"` sorts BELOW
     * `"…00:00:00Z"` lexicographically, so a millisecond-precision row would
     * mis-compare against the second-precision stamps written here and fresh
     * edits would be rejected as stale. An unparseable value means "no stale
     * signal" and never blocks an update.
     */
    internal fun isExistingNewer(existingIso: String, incomingEpochSeconds: Long): Boolean {
        val existing = CommunityEventTime.epochSecondsFromIso(existingIso) ?: return false
        return existing > incomingEpochSeconds
    }

    private fun skip(reason: IngestSkip): Ingested {
        lastSkip = reason
        return Ingested.SKIPPED
    }

    private fun serialize(event: NostrEvent): String =
        JSON.encodeToString(JsonObject.serializer(), event.toJson())

    private fun parseEvent(raw: String): NostrEvent? = try {
        NostrEvent.fromJson(JSON.parseToJsonElement(raw))
    } catch (e: Exception) {
        null
    }

    /**
     * Everything the ingest path reads off one event. Building it can fail, and
     * a failure is a dropped event — never a partially-trusted one.
     */
    private class ParsedClimb(
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
        val contentPubkeyPrefix: String?,
        val boardBrand: String,
        val deleted: Boolean,
    ) {
        companion object {
            fun from(event: NostrEvent): ParsedClimb? {
                var dTag: String? = null
                var framesText: String? = null
                var framesHashRaw: String? = null
                var layoutId: Long? = null
                var setterGradeId: Int? = null
                var angle: Int? = null
                var bounds: ClimbBounds? = null
                var foundV1 = false
                var foundV2 = false
                var boardBrandTag: String? = null
                var deletedTag = false

                for (tag in event.tags) {
                    if (tag.size < 2) continue
                    when (tag[0]) {
                        "d" -> dTag = tag[1]
                        "L" -> when (tag[1]) {
                            CommunityClimbTags.NS_CLIMB -> foundV1 = true
                            CommunityClimbTags.NS_CLIMB_V2 -> foundV2 = true
                        }
                        // Untrusted; validated below, never used raw.
                        "board_brand" -> boardBrandTag = tag[1]
                        "frames" -> framesText = tag[1]
                        "frames_hash" -> framesHashRaw = tag[1]
                        "layout_id" -> layoutId = tag[1].toLongOrNull()
                        "bounds" -> bounds = ClimbBounds.decode(tag[1])
                        "setter_grade" -> {
                            setterGradeId = tag[1].toIntOrNull()
                            if (tag.size >= 3) angle = tag[2].toIntOrNull()
                        }
                        "deleted" -> if (tag[1].equals("true", ignoreCase = true)) deletedTag = true
                    }
                }
                if (!foundV1 && !foundV2) return null
                val resolvedDTag = dTag ?: return null

                val content = runCatching {
                    LENIENT_JSON.parseToJsonElement(event.content.ifBlank { "{}" }).jsonObject
                }.getOrNull() ?: JsonObject(emptyMap())

                // Also honour a content-side marker, for a relay or fork that
                // strips unknown tags but keeps the JSON intact.
                val deletedContent = runCatching {
                    content["deleted"]?.jsonPrimitive?.content?.equals("true", ignoreCase = true) == true
                }.getOrNull() == true
                val deleted = deletedTag || deletedContent

                if (!deleted) {
                    if (framesText.isNullOrBlank()) return null
                    if (layoutId == null) return null
                }
                val brand = CommunityClimbValidation
                    .resolveIngestBoardBrand(boardBrandTag, foundV1, foundV2, deleted)
                    ?: return null

                // The board DB's uuid column is binary-collated lowercase, while
                // Aurora-derived uuids arrive upper-case half the time.
                val uuid = (content["uuid"]?.jsonPrimitive?.contentOrNull
                    ?: CommunityClimbValidation.dTagUuid(resolvedDTag)
                    ?: return null).lowercase()
                if (!CommunityClimbValidation.uuidRouteSafe(uuid)) return null

                return ParsedClimb(
                    eventId = event.id,
                    pubkey = event.pubkey,
                    createdAt = event.createdAt,
                    dTag = resolvedDTag,
                    uuid = uuid,
                    name = content["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    description = content["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    framesText = framesText.orEmpty(),
                    // The column stores the hex only; the tag carries the algorithm prefix.
                    framesHash = framesHashRaw?.removePrefix("sha256:") ?: "",
                    layoutId = layoutId ?: 0L,
                    setterGradeId = setterGradeId,
                    angle = angle,
                    bounds = bounds,
                    contentPubkeyPrefix = content["pubkey_prefix"]?.jsonPrimitive?.contentOrNull,
                    boardBrand = brand.wireValue,
                    deleted = deleted,
                )
            }
        }
    }

    internal companion object {
        /** Same key name as Android's DataStore preference. */
        const val KEY_SINCE = "community_climb_since"

        /** Past this a dead letter is structurally broken; retrying cannot help. */
        const val MAX_DEAD_LETTER_RETRIES = 5L
        const val MAX_DEAD_LETTER_BATCH = 25L
        const val MAX_DLQ_ERROR_EXCERPT = 200

        private val JSON = Json
        private val LENIENT_JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
