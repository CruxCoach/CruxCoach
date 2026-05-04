package com.cruxcoach.android.community

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.community.ClimbBounds
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verifySignature
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
     *
     * Bounded at MAX_RESOLVED_PUBKEYS to defend against a hostile relay
     * that fans a stream of distinct-pubkey events at the subscriber —
     * without the cap the set would grow unboundedly across the process
     * lifetime. ConcurrentHashMap.newKeySet() is JVM-atomic for
     * add/contains, so concurrent setter-resolution coroutines fired
     * from a relay backlog burst can't race the de-duper.
     */
    private val pubkeysResolvedThisRun: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Serializes the read-modify-write on the COMMUNITY_CLIMB_SINCE
     *  cursor. handleEvent is called from a single relay-collect coroutine
     *  in production, but a future fan-out / parallel collect would race
     *  on the cursor's "max(current, incoming)" semantics. */
    private val cursorMutex = Mutex()
    private var job: Job? = null

    /**
     * Live observable of the subscriber's health. Pre-fix the only
     * "is it alive" signal was log lines — a stuck loop, a tight
     * failure burst, or a never-started subscription all looked
     * identical from outside. Now the runSubscriptionLoop publishes
     * its state here so a future Settings/diagnostics surface (and
     * tests) can read it directly.
     */
    private val _health = MutableStateFlow(SubscriberHealth())
    val health: StateFlow<SubscriberHealth> = _health.asStateFlow()

    data class SubscriberHealth(
        /** True after [start]; false after [stop] (or before start). */
        val running: Boolean = false,
        /** Wall-clock ms when the most recent Kind-30078 event was
         *  delivered to handleEvent. Null = never received. */
        val lastEventAtMs: Long? = null,
        /** Number of times the relay-collect threw consecutively without
         *  a successful event in between. Resets to 0 on event delivery. */
        val failureStreak: Int = 0,
        /** Cause-class name of the last collect throw (no message — that
         *  could carry user data). Null when the streak is 0. */
        val lastErrorClass: String? = null,
    )

    /** Idempotent — calling twice is a no-op. */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        _health.update { it.copy(running = true) }
        job = scope.launch {
            // Tiny startup delay so the relay pool has time to read user
            // prefs + connect; not strictly required, just avoids racing
            // the very first connection-open.
            delay(STARTUP_GRACE_MS)
            // seedCursor is best-effort: if the prefs/manifest read throws
            // (DataStore I/O, corrupted prefs) we'd lose the live sub for
            // the rest of the session. Catch + log + fall through to
            // runSubscriptionLoop so the worst case is "we re-sync 24h of
            // events" instead of "subscription never starts".
            runCatching { seedCursorFromManifestIfFirstRun() }
                .onFailure { Log.w(TAG, "seedCursorFromManifestIfFirstRun failed; continuing without seed", it) }
            try {
                runSubscriptionLoop()
            } finally {
                // Either stop() cancelled us, or the loop genuinely
                // exited (shouldn't — it's a `while(true)`).
                _health.update { it.copy(running = false) }
            }
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
        // Backoff index — escalates on each consecutive failure, resets
        // to 0 after a successful collect (i.e. relay flow stayed alive
        // long enough to emit at least one event or close cleanly).
        // Pre-fix the loop slept a flat 5 s after every failure — a
        // permanently-broken upstream (parser exception, NIP version
        // mismatch, hostile relay) caused 12 wakeups/minute indefinitely,
        // burning battery + relay budget.
        var failureStreak = 0
        while (true) {
            val since = userPreferences.communityClimbSince.first()
            val filter = buildFilter(since)
            try {
                pool.subscribe(filter, skipDedup = false, closeOnEose = false).collect { eventJson ->
                    handleEvent(eventJson)
                    // Reset the backoff streak on each successful event
                    // delivery — relay is healthy. Also stamp lastEventAtMs
                    // for the liveness observable.
                    failureStreak = 0
                    _health.update {
                        it.copy(
                            lastEventAtMs = System.currentTimeMillis(),
                            failureStreak = 0,
                            lastErrorClass = null,
                        )
                    }
                }
                // collect returned without throwing → relay flow ended cleanly
                // (rare). Brief backoff before re-subscribing.
                failureStreak = 0
                _health.update { it.copy(failureStreak = 0, lastErrorClass = null) }
                delay(BACKOFF_MS)
            } catch (e: CancellationException) {
                // stop() / parent-scope cancellation. Don't log "re-subscribing"
                // — the loop is going away. Re-throw so the outer launch
                // exits cleanly instead of queueing another delay() that
                // would only re-raise the cancellation.
                throw e
            } catch (e: Exception) {
                val waitMs = BACKOFF_LADDER_MS[
                    failureStreak.coerceAtMost(BACKOFF_LADDER_MS.lastIndex)
                ]
                Log.w(
                    TAG,
                    "subscription terminated streak=$failureStreak waitMs=$waitMs; re-subscribing",
                    e,
                )
                failureStreak++
                _health.update {
                    it.copy(
                        failureStreak = failureStreak,
                        lastErrorClass = e::class.simpleName,
                    )
                }
                delay(waitMs)
            }
        }
    }

    private fun buildFilter(sinceEpoch: Long?): String {
        val sinceClause = if (sinceEpoch != null && sinceEpoch > 0) ",\"since\":$sinceEpoch" else ""
        return """{"kinds":[30078],"#L":["$NAMESPACE_LABEL"]$sinceClause}"""
    }

    @VisibleForTesting
    internal suspend fun handleEvent(eventJson: String) {
        // Hard size cap on the raw event payload before we even parse it.
        // The largest legitimate climb event is ~6 KB (≈84 holds × ~12
        // chars per p/r token + name + description + tag overhead);
        // anything beyond MAX_EVENT_BYTES is either misuse or a relay-side
        // amplification attempt.
        if (eventJson.length > MAX_EVENT_BYTES) {
            Log.w(TAG, "skip oversize event bytes=${eventJson.length}")
            return
        }
        // Relay is untrusted (NIP-01): parse via Quartz which recomputes
        // the canonical event id, kind-check, and Schnorr-verify before
        // trusting any field. Mirrors NostrProfileManager.parseAndCacheProfile
        // and BlossomSyncManager. Without these checks any relay (or MITM
        // on a non-TLS connection) can hand us forged events with
        // arbitrary `pubkey` and content.
        val event = runCatching { Event.fromJson(eventJson) }.getOrElse {
            Log.w(TAG, "failed to parse event JSON")
            return
        }
        if (event.kind != KIND_30078) {
            Log.w(TAG, "skip non-30078 event kind=${event.kind}")
            return
        }
        if (!event.verifySignature()) {
            Log.w(TAG, "skip event with invalid signature id=${event.id}")
            return
        }

        val parsedClimb = runCatching { ParsedClimb.from(event) }.getOrNull() ?: return

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
        val ownPubkey = runCatching { nostrSigner.getPublicKeyHex() }
            .onFailure { Log.w(TAG, "own pubkey resolve failed — self-filter disabled this event", it) }
            .getOrNull()
        if (ownPubkey != null && parsedClimb.pubkey == ownPubkey) {
            Log.d(TAG, "skip own event uuid=${parsedClimb.uuid}")
            return
        }

        // D-tag prefix must encode the same author as the signed pubkey
        // (FEAT-003 §4.2: d-tag = "cruxcoach:climb:<pubkey-prefix-8>:<uuid>").
        // The wire format already enforces author-isolation, but the
        // ingest path has to re-check it so an attacker who legitimately
        // signs with their own keypair cannot claim someone else's d-tag
        // namespace.
        if (!CommunityClimbValidation.dTagAuthorMatches(parsedClimb.dTag, parsedClimb.pubkey)) {
            Log.w(
                TAG,
                "skip d-tag/pubkey mismatch uuid=${parsedClimb.uuid} " +
                    "dtag=${parsedClimb.dTag} pubkey=${parsedClimb.pubkey.take(8)}",
            )
            return
        }

        // Defense-in-depth: content.pubkey_prefix (when present, per spec
        // §4.2) must match the signed pubkey. The publisher always sets
        // this field; absence is tolerated for forward-compat with older
        // events but a present-and-mismatched value is a tampering signal.
        if (!CommunityClimbValidation.contentPubkeyPrefixMatches(
                parsedClimb.contentPubkeyPrefix, parsedClimb.pubkey,
            )
        ) {
            Log.w(
                TAG,
                "skip content.pubkey_prefix mismatch uuid=${parsedClimb.uuid} " +
                    "content_prefix=${parsedClimb.contentPubkeyPrefix} pubkey=${parsedClimb.pubkey.take(8)}",
            )
            return
        }

        // One author per uuid: even with valid signatures, a second author
        // with a colliding uuid would silently overwrite the first via
        // INSERT OR REPLACE (uuid is the sole primary key on `climbs`).
        // First-author wins; subsequent events from a different pubkey
        // are dropped at the door so the local DB stays consistent with
        // the relay-side (pubkey,kind,d-tag) namespacing.
        val existingAuthor = runCatching {
            boardRepository.getClimbAuthorPubkey(parsedClimb.uuid)
        }.getOrNull()
        if (!CommunityClimbValidation.authorOwnershipMatches(existingAuthor, parsedClimb.pubkey)) {
            Log.w(
                TAG,
                "skip cross-author event uuid=${parsedClimb.uuid} " +
                    "existing=${existingAuthor!!.take(8)} incoming=${parsedClimb.pubkey.take(8)}",
            )
            return
        }

        // Size caps mirror the locally-enforced ClimbValidation limits so
        // a misbehaving relay or malicious publisher can't bypass them
        // by signing events directly. Without these the local DB row size
        // is bounded only by the relay's MAX_MESSAGE_SIZE.
        if (parsedClimb.name.length > com.cruxcoach.domain.community.ClimbValidation.NAME_MAX_LENGTH) {
            Log.w(TAG, "skip event name too long uuid=${parsedClimb.uuid} len=${parsedClimb.name.length}")
            return
        }
        if (parsedClimb.description.length > com.cruxcoach.domain.community.ClimbValidation.DESCRIPTION_MAX_LENGTH) {
            Log.w(TAG, "skip event description too long uuid=${parsedClimb.uuid} len=${parsedClimb.description.length}")
            return
        }
        // Holds-count cap. parseFrames is run again later via
        // computeMoveCount; this early gate prevents a multi-thousand-hold
        // payload from even reaching that path.
        val parsedHolds = runCatching {
            BoardClimbParser.parseFrames(parsedClimb.framesText)
        }.getOrElse { emptyList() }
        if (parsedHolds.size > com.cruxcoach.domain.community.ClimbValidation.MAX_HOLDS_TOTAL) {
            Log.w(TAG, "skip event with too many holds uuid=${parsedClimb.uuid} count=${parsedHolds.size}")
            return
        }
        // Frames-hash verification: recompute the canonical hash and
        // compare against what the publisher claimed. A signed event
        // with an invalid hash is either tampered-on-the-wire or a buggy
        // publisher; either way we don't want it polluting the local
        // duplicate-detection index. Tolerate empty hash (older events
        // pre-FEAT-003 §4.3) — the publisher always sets it for new
        // events so absence is forward-compat only.
        if (parsedClimb.framesHash.isNotEmpty()) {
            val expected = com.cruxcoach.domain.community.FramesHash.of(
                parsedClimb.framesText, parsedClimb.layoutId,
            )
            if (expected != parsedClimb.framesHash) {
                Log.w(
                    TAG,
                    "skip event with frames_hash mismatch uuid=${parsedClimb.uuid} " +
                        "expected=${expected.take(12)} got=${parsedClimb.framesHash.take(12)}",
                )
                return
            }
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
        if (existingIso != null && isExistingNewer(existingIso, parsedClimb.createdAt)) {
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
        // own created_at; relays may stream out of order so we max()
        // rather than blindly overwrite (a stale event could rewind us).
        // The Mutex serializes the read-modify-write so two events that
        // race for the cursor can't both observe the old value and both
        // write — the second one would have rolled the cursor backwards.
        cursorMutex.withLock {
            val current = userPreferences.communityClimbSince.first() ?: 0L
            if (parsedClimb.createdAt > current) {
                userPreferences.setCommunityClimbSince(parsedClimb.createdAt)
            }
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
        // Cap memory + relay-fetch fan-out: when the de-duper hits the
        // cap a hostile relay flooding distinct pubkeys can't keep
        // growing the set. We fall through to "always re-resolve past
        // this point" — getProfile's own profileQueries cache still
        // de-duplicates persistent storage, so the worst case is a
        // single redundant relay fetch, not unbounded memory.
        if (pubkeysResolvedThisRun.size > MAX_RESOLVED_PUBKEYS) {
            pubkeysResolvedThisRun.clear()
            Log.w(TAG, "pubkeysResolvedThisRun capacity exceeded; cleared (cap=$MAX_RESOLVED_PUBKEYS)")
        }
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
        // Delegate to the shared estimator so the publisher's
        // (handHolds - startCount) formula and the subscriber's match.
        // Pre-fix the subscriber counted START + HAND, the publisher
        // counted HAND + FINISH, so the same climb got different
        // move_count values depending on which path persisted it.
        // Result: browse sort-by-moves and "X moves" badges were
        // inconsistent across rows. The publisher's formula is the
        // climbing-semantics-correct one — starts are already on the
        // wall so a "move" is each subsequent hand-hold.
        val holds = runCatching { BoardClimbParser.parseFrames(framesText) }.getOrNull() ?: return 0
        return BoardClimbParser.estimateMoveCount(holds)
    }

    private fun epochToIso(epochSeconds: Long): String =
        java.time.Instant.ofEpochSecond(epochSeconds).toString()

    /**
     * True when the locally-stored ISO timestamp is strictly newer than
     * the incoming Nostr event's `created_at` (epoch seconds).
     *
     * Pre-fix this used `existingIso > incomingIso` (String lex compare),
     * which is wrong when the two strings differ in fractional-second
     * precision: `"…00:00:00.000Z" < "…00:00:00Z"` lex-compares because
     * `.` (0x2E) is below `Z` (0x5A), so a milli-precision timestamp
     * stored from a Kilter-import path would mis-compare against a
     * second-precision Instant.toString() the subscriber generates —
     * stale-event protection then rejects fresh edits as if they were
     * older. Parsing both sides to Instant fixes the precision mismatch
     * and gracefully tolerates non-ISO existing values (treat as "no
     * stale signal" → don't reject).
     */
    @VisibleForTesting
    internal fun isExistingNewer(existingIso: String, incomingEpochSeconds: Long): Boolean {
        val existingInstant = runCatching { java.time.Instant.parse(existingIso) }.getOrNull()
            ?: return false
        return existingInstant.epochSecond > incomingEpochSeconds
    }

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
        val contentPubkeyPrefix: String?,
    ) {
        companion object {
            fun from(event: Event): ParsedClimb {
                var dTag: String? = null
                var framesText: String? = null
                var framesHashRaw: String? = null
                var layoutId: Long? = null
                var setterGradeId: Int? = null
                var angle: Int? = null
                var bounds: ClimbBounds? = null
                var foundLabel = false

                for (tag in event.tags) {
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
                        .parseToJsonElement(event.content.ifBlank { "{}" })
                        .jsonObject
                }.getOrNull() ?: JsonObject(emptyMap())
                val uuid = contentObj["uuid"]?.jsonPrimitive?.contentOrNull
                    ?: dTagUuid(dTag!!)
                    ?: error("uuid not derivable from event")
                val name = contentObj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val description = contentObj["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val contentPubkeyPrefix = contentObj["pubkey_prefix"]?.jsonPrimitive?.contentOrNull

                // frames_hash arrives as "sha256:<hex>" — strip the prefix
                // before persisting (our column stores the hex only).
                val framesHash = framesHashRaw?.removePrefix("sha256:") ?: ""

                return ParsedClimb(
                    eventId = event.id,
                    pubkey = event.pubKey,
                    createdAt = event.createdAt,
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
                    contentPubkeyPrefix = contentPubkeyPrefix,
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
        // Exponential backoff ladder for the runSubscriptionLoop's
        // re-subscribe path. Index = failureStreak (clamped to last).
        // 1s → 2s → 5s → 15s → 60s → cap. A truly broken upstream now
        // sleeps minutes between attempts instead of hammering the
        // relay every 5s.
        val BACKOFF_LADDER_MS = longArrayOf(
            1_000L, 2_000L, 5_000L, 15_000L, 60_000L, 60_000L,
        )
        const val KIND_30078 = 30078
        // Hard cap on raw event JSON size. ~16 KB covers the largest
        // legitimate climb (~84 holds + 100-char name + 500-char
        // description + tag overhead ≈ 6 KB) with comfortable headroom
        // for future schema additions, while bounding the cost of
        // parsing a hostile payload.
        const val MAX_EVENT_BYTES = 16 * 1024
        // Cap the in-memory pubkey-resolved de-duper. Real users see
        // ~200 unique authors over months; 4096 is plenty of headroom
        // and keeps a hostile relay from growing the set unboundedly.
        const val MAX_RESOLVED_PUBKEYS = 4096

        /** d-tag prefix that the publisher embeds for [pubkey] (FEAT-003 §4.2). */
        fun communityClimbDTagPrefix(pubkey: String): String =
            CommunityClimbValidation.expectedDTagPrefix(pubkey)
    }
}

/**
 * Pure validation helpers for Kind-30078 community-climb events. Extracted
 * out of [CommunityClimbSubscriber] so JVM-only unit tests can exercise the
 * d-tag and content-prefix guards without loading Quartz's `Event` class
 * (which is compiled against Java 21 and unavailable on the project's
 * Java-17 test runtime).
 */
internal object CommunityClimbValidation {
    /** d-tag pattern: `cruxcoach:climb:<pubkey-prefix-8>:<uuid>` (FEAT-003 §4.2). */
    fun expectedDTagPrefix(pubkey: String): String =
        "cruxcoach:climb:${pubkey.take(8)}:"

    /**
     * True when the d-tag's embedded author matches the signed pubkey.
     * Catches d-tag tampering even when the Schnorr signature is valid
     * (e.g. an attacker who legitimately holds some keypair tries to
     * claim a victim's d-tag namespace).
     */
    fun dTagAuthorMatches(dTag: String, signedPubkey: String): Boolean =
        dTag.startsWith(expectedDTagPrefix(signedPubkey))

    /**
     * True when [contentPrefix] is absent (older events) or matches the
     * signed pubkey. Defence-in-depth on the same property the d-tag
     * encodes; a present-and-mismatched value is a tampering signal.
     */
    fun contentPubkeyPrefixMatches(contentPrefix: String?, signedPubkey: String): Boolean =
        contentPrefix == null || contentPrefix == signedPubkey.take(8)

    /**
     * True when no row exists yet for this uuid, or the existing owner
     * matches the incoming signed pubkey. False means a different author
     * already owns this uuid and the incoming event must be dropped to
     * prevent INSERT-OR-REPLACE-on-uuid-alone clobbering.
     */
    fun authorOwnershipMatches(existingPubkey: String?, signedPubkey: String): Boolean =
        existingPubkey == null || existingPubkey == signedPubkey
}
