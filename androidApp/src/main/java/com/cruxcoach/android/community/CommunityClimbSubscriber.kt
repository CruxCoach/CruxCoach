package com.cruxcoach.android.community

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.community.ClimbBounds
import com.cruxcoach.domain.community.CommunityClimbTags
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verifyId
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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

/**
 * Resolve + VALIDATE the board brand of an incoming community climb event (C1).
 * Extracted as a pure function so the brand-forgery guard is unit-testable.
 *
 * [boardBrandTag] is the raw, self-signed `board_brand` tag (null if absent);
 * [foundV1]/[foundV2] record which namespace label(s) the event carried.
 *
 * For a real climb (`deleted == false`): a PRESENT tag must name a known
 * INTERACTIVE board — unknown / map-only / null-when-required is rejected (so a
 * forged value can never silently map to KILTER via [BoardBrand.fromWire]) — and
 * the brand must match the namespace the publisher always pairs it with (Kilter
 * on the legacy v1 label, every other board on v2). A missing tag is the
 * pre-FEAT-031 Kilter-only era. Throws [IllegalArgumentException] on any
 * violation (the caller drops / dead-letters the event).
 *
 * Tombstones (`deleted == true`) carry no `board_brand` and key off the uuid, so
 * they skip the strict checks and resolve leniently — identical to the behaviour
 * before C1, so legitimate deletions are never rejected.
 */
internal fun resolveIngestBoardBrand(
    boardBrandTag: String?,
    foundV1: Boolean,
    foundV2: Boolean,
    deleted: Boolean,
): BoardBrand {
    if (deleted) return BoardBrand.fromWire(boardBrandTag)
    val brand = if (boardBrandTag == null) {
        BoardBrand.KILTER
    } else {
        BoardBrand.fromWireOrNull(boardBrandTag)
            ?: throw IllegalArgumentException("unknown board_brand tag: $boardBrandTag")
    }
    require(brand.isInteractive) { "non-interactive board_brand: ${brand.wireValue}" }
    require(if (brand == BoardBrand.KILTER) foundV1 else foundV2) {
        "board_brand ${brand.wireValue} inconsistent with namespace (v1=$foundV1 v2=$foundV2)"
    }
    return brand
}

@Singleton
class CommunityClimbSubscriber @Inject constructor(
    private val pool: NostrRelayPool,
    private val boardRepository: BoardRepository,
    private val userPreferences: UserPreferences,
    private val nostrSigner: NostrSigner,
    private val nostrProfileManager: com.cruxcoach.android.payment.NostrProfileManager,
    /** Read-only state to gate event ingest while a board-data sync is
     *  in flight. Each upsertCommunityClimb is its own SQLite write
     *  transaction (commit + fsync) — interleaving them with the bulk
     *  importer's batch transactions blows up writer-lock contention
     *  even under WAL, taking the import from ~100s to 10+ minutes
     *  on slower-eMMC devices when a relay backlog drips dozens of
     *  events into the queue mid-import. handleEvent SUSPENDS (not
     *  drops) on isSyncing so the Flow collector back-pressures the
     *  websocket reader; events resume in order once the importer
     *  releases its writer. */
    private val boardSyncManager: com.cruxcoach.android.data.BoardSyncManager,
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

    /** Negative-cache TTL marker: pubkey → wall-clock millis of the last
     *  *attempt* (success or failure). On a failure-only resolve, the
     *  pubkey lives here instead of [pubkeysResolvedThisRun], so a
     *  follow-up event for the same pubkey retries after
     *  [RESOLVE_RETRY_TTL_MS]. ConcurrentHashMap is sufficient — the
     *  put-on-attempt + remove-on-success isn't strictly atomic, but
     *  losing a remove just means one extra relay fetch on the next
     *  event, never a stranded pubkey. */
    private val resolveAttemptedAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

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
            // Drain the DLQ once before the live sub takes over. Any
            // events that failed in a previous session sit in
            // community_climb_dead_letters with their signed payload
            // intact; on a fresh start the SQLite issue (disk full,
            // lock contention, transient OOM) is usually gone, so a
            // single retry pass picks them up. Best-effort — a DLQ
            // failure here doesn't block the live subscription.
            runCatching { retryDeadLetters() }
                .onFailure { Log.w(TAG, "DLQ initial retry failed; deferring to next start", it) }
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
     * On a true cold start the in-process race used to bite: the
     * subscriber's `start()` is fired in parallel with the auto board-
     * sync. The seed used to read `blossomManifestCreatedAt` ONCE and
     * bail if null — typically winning the race against the manifest-
     * fetch by ~1 s and emitting a `since=null` REQ. The relay then
     * streamed all-of-history (MB-GB) only for those events to be
     * superseded by the bundle import seconds later, blowing up the
     * WebSocket buffer + writer-lock contention.
     *
     * Now: wait up to [SEED_MANIFEST_TIMEOUT_MS] for the first non-null
     * manifest epoch, then seed. The board-sync is on the same app
     * process and writes the manifest within the first network roundtrip
     * (~100 ms LAN, ~1-2 s mobile). On timeout (no network / blossom
     * fetch fails) we fall through to the unseeded path — a full
     * backfill is correct in that case anyway, since there's no bundle
     * to defer to.
     *
     * Skipped when:
     *  - cursor is already set (we've persisted at least one event)
     *  - no Blossom manifest fetched within the timeout (= board-sync
     *    failed; we accept the cold-start cost as the lesser evil)
     */
    private suspend fun seedCursorFromManifestIfFirstRun() {
        val cursor = userPreferences.communityClimbSince.first()
        if (cursor != null && cursor > 0) return
        val manifestEpoch = withTimeoutOrNull(SEED_MANIFEST_TIMEOUT_MS) {
            userPreferences.blossomManifestCreatedAt
                .filterNotNull()
                .filter { it > 0 }
                .first()
        }
        if (manifestEpoch == null) {
            Log.w(TAG, "no blossom manifest within ${SEED_MANIFEST_TIMEOUT_MS}ms — proceeding without seed (cold-start cost accepted)")
            return
        }
        Log.i(TAG, "seeding cursor from blossom manifest: $manifestEpoch")
        userPreferences.setCommunityClimbSince(manifestEpoch)
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Replay the dead-letter queue: for every persisted Kind-30078
     * event whose previous upsert failed, re-parse the signed JSON,
     * re-validate the Schnorr signature (cheap; the bytes are exactly
     * what the relay sent), and route it through [handleClimbEvent] —
     * the same code path live events take, so the stale-event guard,
     * cross-author check, and absorption rules apply on retry too.
     *
     * Rows whose upsert succeeds are deleted from the DLQ via the
     * happy path inside [handleClimbEvent]. Rows that fail again get
     * their retry_count bumped by [recordCommunityClimbDeadLetter];
     * once a row's retry_count reaches [MAX_DEAD_LETTER_RETRIES] the
     * SQL `getRetriableDeadLetters` filter excludes it from future
     * retries (the row is still readable for a future diagnostics UI).
     *
     * Public so a Settings/health-card "retry now" button can call it
     * without restarting the subscriber.
     */
    suspend fun retryDeadLetters() {
        val rows = runCatching {
            boardRepository.getRetriableCommunityClimbDeadLetters(
                maxRetries = MAX_DEAD_LETTER_RETRIES,
                limit = MAX_DEAD_LETTER_BATCH,
            )
        }.getOrElse {
            Log.w(TAG, "DLQ retry: list query failed", it)
            return
        }
        if (rows.isEmpty()) return
        Log.i(TAG, "DLQ retry: ${rows.size} candidate(s)")
        for (row in rows) {
            // Treat each row's parse as best-effort: a corrupted JSON
            // (storage bit-flip, hostile DB rewrite) shouldn't poison
            // the rest of the batch. The signature re-verify catches
            // anything that doesn't match the original signed bytes.
            try {
                val event = Event.fromJson(row.rawEventJson)
                if (!event.verifySignature() || !event.verifyId()) {
                    Log.w(TAG, "DLQ retry: signature/id invalid for uuid=${row.uuid} — abandoning")
                    runCatching { boardRepository.deleteCommunityClimbDeadLetter(row.uuid) }
                    continue
                }
                if (event.kind != KIND_30078) {
                    Log.w(TAG, "DLQ retry: wrong kind=${event.kind} for uuid=${row.uuid} — abandoning")
                    runCatching { boardRepository.deleteCommunityClimbDeadLetter(row.uuid) }
                    continue
                }
                handleClimbEvent(event, row.rawEventJson)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "DLQ retry: throw uuid=${row.uuid}; staying in queue", e)
                // recordCommunityClimbDeadLetter inside handleClimbEvent
                // already bumped retry_count if upsert threw. A throw
                // OUT of the parse/verify branch is rarer; bump
                // explicitly so a poisoned row eventually hits the
                // MAX_DEAD_LETTER_RETRIES cap and stops looping.
                runCatching {
                    boardRepository.recordCommunityClimbDeadLetter(
                        uuid = row.uuid,
                        eventId = row.eventId,
                        eventCreatedAt = row.eventCreatedAt,
                        rawEventJson = row.rawEventJson,
                        nowMs = System.currentTimeMillis(),
                        errorExcerpt = (e.message ?: e::class.simpleName)
                            .orEmpty().take(MAX_DLQ_ERROR_EXCERPT),
                    )
                }
            }
        }
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
        // Subscribe to Kind-30078 (climb events) AND Kind-5 (NIP-09
        // deletions). The deleter publishes both kinds with a matching
        // ["L", <ns>] tag, so the same #L filter catches both — no second
        // REQ subscription needed. Foreign Kind-5 events without our label
        // tag are filtered server-side and never reach handleEvent.
        //
        // Dual-namespace (FEAT-031 back-compat gate): fetch BOTH the legacy
        // Kilter namespace and the v2 (new-board) namespace in one REQ.
        // Kilter climbs/deletions stay on NAMESPACE_LABEL; every non-Kilter
        // board (the five Aurora boards + MoonBoard) is namespaced under
        // NAMESPACE_LABEL_V2 so pre-0.2.0 apps (which only subscribe on the
        // legacy label) never see them as broken Kilter climbs. We're a
        // ≥0.2.0 subscriber, so we ask for both and ingest by the
        // `board_brand` machine tag, not by layout_id.
        return """{"kinds":[$KIND_30078,$KIND_DELETION],"#L":["$NAMESPACE_LABEL","$NAMESPACE_LABEL_V2"]$sinceClause}"""
    }

    @VisibleForTesting
    internal suspend fun handleEvent(eventJson: String) {
        // Defer-during-board-sync gate. The bulk board importer is a
        // single-writer hammering ~190k INSERT-OR-IGNORE rows in 97 chunk
        // transactions; a Live-Sub upsertCommunityClimb in the middle of
        // that is its own tiny writer transaction (commit + fsync) that
        // serializes against the importer even under WAL. A handful of
        // interleaved upserts has been observed to take a 100s import to
        // 10+ minutes on slower-eMMC devices.
        //
        // Suspend (not drop) until isSyncing flips false. The Flow
        // collector is back-pressured to the websocket reader; events
        // queue up in the upstream until we resume. This is the
        // crucial difference from "drop + advance-cursor-later" — a
        // dropped event is gone for that subscription session because
        // relays don't re-push already-delivered events; only a fresh
        // REQ with the cursor would backfill them. By suspending we
        // keep every event without depending on relay backfill behaviour.
        if (boardSyncManager.state.value.isSyncing) {
            Log.d(TAG, "suspend event handling during board-sync (resume after import)")
            boardSyncManager.state.first { !it.isSyncing }
            Log.d(TAG, "board-sync ended, resuming event handling")
        }
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
        if (!event.verifySignature() || !event.verifyId()) {
            // verifyId fails when tags/content were altered after signing: a
            // relay can serve a validly-signed event whose body it tampered with
            // unless the id (= hash of the serialized content) is re-checked.
            Log.w(TAG, "skip event with invalid signature/id id=${event.id}")
            return
        }
        // Clock-skew bound: a far-future event must never be ingested as the
        // newest climb nor advance the `since` cursor — a forged far-future
        // timestamp would push the cursor past every real event and silently
        // disable the live subscription. Applied before kind dispatch so both
        // the climb and deletion paths (which call advanceCursorIfNewer) are covered.
        val nowSec = System.currentTimeMillis() / 1000L
        if (!CommunityClimbValidation.isWithinClockSkew(event.createdAt, nowSec)) {
            Log.w(TAG, "skip future-dated event createdAt=${event.createdAt} now=$nowSec id=${event.id}")
            return
        }
        when (event.kind) {
            KIND_30078 -> handleClimbEvent(event, eventJson)
            KIND_DELETION -> handleDeletionEvent(event)
            else -> {
                Log.w(TAG, "skip event of unsupported kind=${event.kind}")
                return
            }
        }
    }

    /**
     * Process a Kind-30078 community-climb event. Two sub-paths:
     *  * `parsedClimb.deleted == true` → tombstone-replacement: route to
     *    [absorbTombstone] (owner-locked + cross-author guarded).
     *  * Otherwise → normal upsert path with the existing validation
     *    pipeline plus the L3 absorption check that refuses re-importing
     *    a climb whose local row already carries `is_deleted=1`.
     */
    private suspend fun handleClimbEvent(event: Event, rawEventJson: String) {
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
        // Backstop self-filter: only when the primary check above could
        // not run (signer outage → ownPubkey is null). With a resolvable
        // ownPubkey the primary is authoritative — an event whose
        // signer != ownPubkey is by definition a foreign event, even if
        // a local `source='local'` row exists for the same uuid (the
        // identity-switch and key-rotation cases). Blocking the upsert
        // there would silently hide foreign-authored climbs from the
        // browser when the user switches identity, which is exactly
        // what the live sub is supposed to ingest.
        if (ownPubkey == null) {
            val locallyAuthored = runCatching {
                boardRepository.isLocallyAuthored(parsedClimb.uuid)
            }.getOrDefault(false)
            if (locallyAuthored) {
                Log.d(TAG, "skip event for locally-authored row uuid=${parsedClimb.uuid} (signer-outage backstop)")
                return
            }
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

        // Catalogue guard: a community Kind-30078 must never re-key or overwrite
        // catalogue content (kilter/boardsesh) or any NULL-author row. The
        // cross-author guard above treats a NULL existing author as claimable,
        // and getClimbAuthorPubkey returns NULL for BOTH "no row" and a
        // catalogue row with no author — so without this an official climb is
        // hijackable. A genuine community row (origin='cruxcoach' + non-NULL
        // author) is excluded by isNonCommunityClimb, so legitimate same-author
        // updates and brand-new community climbs are unaffected.
        if (runCatching { boardRepository.isNonCommunityClimb(parsedClimb.uuid) }
                .getOrDefault(false)
        ) {
            Log.w(
                TAG,
                "skip community event targeting non-community climb " +
                    "uuid=${parsedClimb.uuid} incoming=${parsedClimb.pubkey.take(8)}",
            )
            return
        }

        // L4: tombstone-replacement Kind-30078. Author + d-tag + cross-
        // author guards already ran; route the deletion intent through
        // absorbTombstone, which is owner-locked at the SQL layer.
        if (parsedClimb.deleted) {
            Log.i(TAG, "tombstone-replacement received uuid=${parsedClimb.uuid}")
            absorbTombstone(
                uuid = parsedClimb.uuid,
                pubkey = parsedClimb.pubkey,
                dTag = parsedClimb.dTag,
                tombstoneIso = epochToIso(parsedClimb.createdAt),
            )
            advanceCursorIfNewer(parsedClimb.createdAt)
            return
        }

        // L3: absorption. If the local row is already tombstoned, drop
        // every incoming Original-Event for this uuid — a Live-Sub
        // re-broadcast from a non-deleting relay would otherwise reset
        // is_deleted=1 to the column DEFAULT 0 via upsertCommunityClimb's
        // INSERT OR REPLACE. The check sits after cross-author so a
        // legitimate re-author (different pubkey, currently disallowed
        // by the cross-author guard, but defensive) cannot reanimate
        // someone else's tombstone.
        if (boardRepository.isClimbTombstoned(parsedClimb.uuid)) {
            Log.d(TAG, "skip event for tombstoned climb uuid=${parsedClimb.uuid}")
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

        // Skip ungraded / un-angled events — per the "no synthetic
        // stats" rule we don't manufacture NULL-difficulty rows. Pre-
        // fix this was a silent `?: return` with no log line at all,
        // which made debugging the 0.1.4 publisher gap (`setter_grade`
        // tag missing on default-grade publishes) basically impossible
        // — every relay accepted the event, no subscriber complained
        // visibly, the climb just never appeared on any other device.
        // Logging at WARN keeps the symptom visible in logcat without
        // the publisher itself getting a crash on a stricter
        // require() (already added in NostrCommunityClimb).
        val grade = parsedClimb.setterGradeId
        if (grade == null) {
            Log.w(TAG, "skip event without setter_grade tag uuid=${parsedClimb.uuid} pubkey=${parsedClimb.pubkey.take(8)}")
            return
        }
        val angle = parsedClimb.angle
        if (angle == null) {
            Log.w(TAG, "skip event without angle uuid=${parsedClimb.uuid} pubkey=${parsedClimb.pubkey.take(8)}")
            return
        }

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
                boardBrand = parsedClimb.boardBrand,
            )
            // Successful upsert wins. Drop a previous DLQ entry (if any
            // — the row may have failed earlier in this session before
            // the next retry path retried it). No-op when no DLQ entry
            // exists for this uuid.
            runCatching { boardRepository.deleteCommunityClimbDeadLetter(parsedClimb.uuid) }
                .onFailure { Log.w(TAG, "DLQ cleanup after success failed uuid=${parsedClimb.uuid}", it) }
        } catch (e: CancellationException) {
            // Coroutine cancellation must propagate so the live-sub
            // shuts down cleanly when the subscriber's job is cancelled
            // (app stop, signer outage). NOT a DLQ-able failure.
            throw e
        } catch (e: Exception) {
            // Persist the raw signed event in the DLQ so the next
            // start-up (or an explicit retryDeadLetters call) can
            // re-run the upsert. Without the DLQ the cursor would
            // advance past this event on the very next successful
            // upsert and NIP-01 Live-REQ would never re-deliver it,
            // causing a permanent silent data-loss for this uuid.
            Log.w(TAG, "upsertCommunityClimb failed uuid=${parsedClimb.uuid} — enqueuing for retry", e)
            runCatching {
                boardRepository.recordCommunityClimbDeadLetter(
                    uuid = parsedClimb.uuid,
                    eventId = parsedClimb.eventId,
                    eventCreatedAt = parsedClimb.createdAt,
                    rawEventJson = rawEventJson,
                    nowMs = System.currentTimeMillis(),
                    errorExcerpt = (e.message ?: e::class.simpleName).orEmpty().take(MAX_DLQ_ERROR_EXCERPT),
                )
            }.onFailure { Log.w(TAG, "DLQ enqueue failed uuid=${parsedClimb.uuid}", it) }
            return
        }

        // Advance cursor only after a successful upsert. Use the event's
        // own created_at; relays may stream out of order so we max()
        // rather than blindly overwrite (a stale event could rewind us).
        advanceCursorIfNewer(parsedClimb.createdAt)

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
     * already successfully resolved this process lifetime, when no
     * profile is found, or when the profile lacks a non-blank display_name.
     *
     * Negative-cache window via [resolveAttemptedAtMs]: a failed fetch
     * marks "tried at T" rather than "resolved" so subsequent events for
     * the same pubkey re-attempt after [RESOLVE_RETRY_TTL_MS], without
     * spamming the relay if Kind 0 is genuinely absent. Pre-fix the
     * `add(pubkey)` ran before the relay fetch, so a transient timeout
     * permanently stranded the npub-stub for that pubkey until process
     * restart.
     */
    private fun resolveSetterDisplayName(pubkey: String) {
        if (pubkey in pubkeysResolvedThisRun) return
        val now = System.currentTimeMillis()
        val lastAttempt = resolveAttemptedAtMs[pubkey]
        if (lastAttempt != null && now - lastAttempt < RESOLVE_RETRY_TTL_MS) return
        // Cap memory + relay-fetch fan-out: when the de-duper hits the
        // cap a hostile relay flooding distinct pubkeys can't keep
        // growing the set. We fall through to "always re-resolve past
        // this point" — getProfile's own profileQueries cache still
        // de-duplicates persistent storage, so the worst case is a
        // single redundant relay fetch, not unbounded memory.
        if (pubkeysResolvedThisRun.size > MAX_RESOLVED_PUBKEYS) {
            pubkeysResolvedThisRun.clear()
            resolveAttemptedAtMs.clear()
            Log.w(TAG, "pubkeysResolvedThisRun capacity exceeded; cleared (cap=$MAX_RESOLVED_PUBKEYS)")
        }
        resolveAttemptedAtMs[pubkey] = now
        // Use the same job's coroutine scope so we don't accumulate
        // orphan coroutines if the subscriber is stopped. job is the
        // top-level subscription; we fork a child that survives the
        // current event handler frame but stops with the subscriber.
        val parent = job ?: return
        kotlinx.coroutines.CoroutineScope(parent).launch {
            val profile = runCatching { nostrProfileManager.getProfile(pubkey) }.getOrNull()
            if (profile == null) {
                // Failed fetch — leave only the negative-cache TTL marker
                // so the next event for this pubkey retries after the TTL.
                return@launch
            }
            // Successful fetch — promote to the durable resolved set so
            // subsequent events skip the cache check entirely (and we drop
            // the TTL marker since it's superseded).
            pubkeysResolvedThisRun.add(pubkey)
            resolveAttemptedAtMs.remove(pubkey)
            val displayName = profile.displayName?.takeIf { it.isNotBlank() } ?: return@launch
            runCatching {
                boardRepository.updateSetterUsernameForPubkey(
                    pubkey = pubkey,
                    displayName = displayName,
                )
            }.onFailure { Log.w(TAG, "setter rename for $pubkey failed", it) }
        }
    }

    /**
     * Process a NIP-09 Kind-5 deletion event.
     *
     * Spec compliance: a deletion is only valid when `event.pubkey ==
     * referencedEvent.pubkey` (NIP-09 §1). Practically: an `a`-tag of
     * shape `30078:<pubkey>:<d-tag>` is honoured iff `<pubkey>` matches
     * the signer. Cross-author Kind-5s are dropped — they would be
     * either misuse or an attempted denial-of-service against another
     * user's climbs.
     *
     * The d-tag must follow the `cruxcoach:climb:<prefix>:<uuid>`
     * convention so we can derive the canonical lowercase uuid; foreign
     * d-tags accidentally wrapped in our `["L","com.cruxcoach.climb"]`
     * label are silently ignored.
     */
    private suspend fun handleDeletionEvent(event: Event) {
        var aTagRef: String? = null
        for (tag in event.tags) {
            if (tag.size >= 2 && tag[0] == "a") {
                aTagRef = tag[1]
                break
            }
        }
        if (aTagRef == null) {
            Log.w(TAG, "skip kind-5 without a-tag id=${event.id}")
            return
        }
        val parts = aTagRef.split(":")
        if (parts.size < 3) {
            Log.w(TAG, "skip kind-5 with malformed a-tag ref=$aTagRef")
            return
        }
        val refKind = parts[0].toIntOrNull()
        val refPubkey = parts[1]
        // The d-tag may itself contain colons (cruxcoach:climb:…:uuid),
        // so rejoin everything after the second colon.
        val refDTag = parts.drop(2).joinToString(":")
        if (refKind != KIND_30078) {
            Log.w(TAG, "skip kind-5 targeting non-30078 kind=$refKind")
            return
        }
        // NIP-09 ownership: signer must be the original event's author.
        if (refPubkey != event.pubKey) {
            Log.w(
                TAG,
                "skip cross-author kind-5 signer=${event.pubKey.take(8)} ref=${refPubkey.take(8)}",
            )
            return
        }
        // Defence in depth: the d-tag must encode the same author
        // (cruxcoach:climb:<pubkey-prefix-8>:…). A relay-forged d-tag
        // for someone else's climb is otherwise indistinguishable from
        // a legitimate self-delete here.
        if (!CommunityClimbValidation.dTagAuthorMatches(refDTag, event.pubKey)) {
            Log.w(TAG, "skip kind-5 with d-tag/pubkey mismatch d=$refDTag")
            return
        }
        val uuid = ParsedClimb.dTagUuid(refDTag)?.lowercase()
        if (uuid == null) {
            Log.w(TAG, "skip kind-5 with non-cruxcoach d-tag d=$refDTag")
            return
        }
        Log.i(TAG, "kind-5 deletion received uuid=$uuid")
        absorbTombstone(
            uuid = uuid,
            pubkey = event.pubKey,
            dTag = refDTag,
            tombstoneIso = epochToIso(event.createdAt),
        )
        advanceCursorIfNewer(event.createdAt)
    }

    /**
     * Apply a tombstone intent (from a Kind-5 deletion or a Kind-30078
     * tombstone-replacement) to local state.
     *
     * Two-step write so cross-device defence works without cross-write
     * races:
     *  * `insertTombstoneShell` first plants a memorial row when no
     *    local row exists yet. The shell is `is_deleted=1` so any
     *    later Original-Event arriving via Live-Sub is absorbed by L3.
     *  * `markCommunityClimbDeleted` then flips an existing real row
     *    (or no-ops on the just-inserted shell since it's already
     *    tombstoned). Owner-locked at SQL: `created_by_pubkey = pubkey
     *    AND origin = 'cruxcoach'` — so a hostile Kind-5 with a
     *    valid signature but pointing at a Kilter-origin row or another
     *    user's row never flips anything.
     */
    private fun absorbTombstone(uuid: String, pubkey: String, dTag: String, tombstoneIso: String) {
        runCatching {
            boardRepository.insertTombstoneShell(uuid, pubkey, dTag, tombstoneIso)
            boardRepository.markCommunityClimbDeleted(uuid, pubkey, tombstoneIso)
        }.onFailure { Log.w(TAG, "absorbTombstone failed for uuid=$uuid", it) }
    }

    /**
     * Advance the persisted `since` cursor to [eventCreatedAt] if it's
     * newer than the current value. Mutex-serialised so concurrent
     * relay-collect coroutines (a future fan-out) cannot rewind the
     * cursor by writing in the wrong order.
     */
    private suspend fun advanceCursorIfNewer(eventCreatedAt: Long) {
        cursorMutex.withLock {
            val current = userPreferences.communityClimbSince.first() ?: 0L
            if (eventCreatedAt > current) {
                userPreferences.setCommunityClimbSince(eventCreatedAt)
            }
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
        /** Board family from the event's ["board_brand", x] machine tag
         *  (FEAT-031). Defaults to "kilter" when the tag is absent —
         *  legacy Kilter events predate the tag. The subscriber ingests
         *  by this brand, not by the (potentially colliding) layout_id. */
        val boardBrand: String,
        /** True when the event is a tombstone-replacement carrying
         *  `["deleted","true"]` tag or `{"deleted":true}` in content.
         *  Tombstone-replacement events bypass the strict frames /
         *  setter-grade requires below — those tags are no longer
         *  semantically meaningful for a deleted climb. */
        val deleted: Boolean,
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
                // Track WHICH namespace matched (v1 legacy-Kilter vs v2
                // new-board) and the RAW board_brand tag separately, so the
                // brand can be validated against the namespace below (C1).
                var foundV1 = false
                var foundV2 = false
                var boardBrandTag: String? = null
                var deletedTag = false

                for (tag in event.tags) {
                    if (tag.size < 2) continue
                    when (tag[0]) {
                        "d" -> dTag = tag[1]
                        // Accept BOTH the legacy Kilter namespace and the v2
                        // (new-board) namespace (FEAT-031 dual-namespace gate).
                        "L" -> when (tag[1]) {
                            NAMESPACE_LABEL -> foundV1 = true
                            NAMESPACE_LABEL_V2 -> foundV2 = true
                        }
                        // Explicit brand machine tag — the authoritative
                        // ingest key (layout_id collides across boards).
                        // UNTRUSTED (self-signed): validated below, never
                        // trusted raw.
                        "board_brand" -> boardBrandTag = tag[1]
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
                        // L4: tombstone-replacement marker. The deleter
                        // publishes a Kind-30078 with the same d-tag as
                        // the original (replaceable-event semantics: the
                        // newer event replaces the older in relay
                        // indices) plus this marker so we can detect the
                        // deletion intent without parsing content.
                        "deleted" -> if (tag[1].equals("true", ignoreCase = true)) deletedTag = true
                    }
                }
                require(foundV1 || foundV2) { "not a com.cruxcoach.climb[.v2] event" }
                require(dTag != null) { "d-tag missing" }

                val contentObj = runCatching {
                    Json { ignoreUnknownKeys = true; isLenient = true }
                        .parseToJsonElement(event.content.ifBlank { "{}" })
                        .jsonObject
                }.getOrNull() ?: JsonObject(emptyMap())

                // L4 fallback: also recognise content-side `{"deleted":true}`
                // — covers a relay or fork that strips unknown tags but
                // keeps the JSON content intact.
                val deletedContent = runCatching {
                    contentObj["deleted"]?.jsonPrimitive?.content?.equals("true", ignoreCase = true) == true
                }.getOrNull() == true
                val deleted = deletedTag || deletedContent

                if (!deleted) {
                    require(framesText != null && framesText!!.isNotBlank()) { "frames tag missing" }
                    require(layoutId != null) { "layout_id tag missing" }
                }

                // C1 — VALIDATE the untrusted board_brand before it becomes the
                // ingest key. board_brand is a self-signed, attacker-controlled
                // tag; without this a forged value would (a) silently map to
                // KILTER (fromWire's lenient fallback) and inject a foreign-hold
                // climb into every Kilter browse, or (b) ride the wrong board.
                // Rules: a PRESENT tag must name a known INTERACTIVE board
                // (reject unknown / map-only); a MISSING tag is the pre-FEAT-031
                // Kilter-only era; and the brand must match the namespace the
                // publisher pairs it with (Kilter→v1, every other board→v2), so
                // a mismatched pair is a forged/garbled event. Tombstones carry
                // no board_brand and key off the uuid, so they skip the strict
                // check (the deletion still rides the matching namespace).
                val resolvedBrand: BoardBrand =
                    resolveIngestBoardBrand(boardBrandTag, foundV1, foundV2, deleted)

                // Canonical lowercase: the board DB's uuid is BINARY-
                // collated lowercase (see 7.sqm); event content / d-tag
                // may carry mixed casing (Aurora-derived UUIDs are upper
                // half the time). Lowercase here so existsClimb /
                // getClimbAuthorPubkey / upsertCommunityClimb all match.
                val uuid = (contentObj["uuid"]?.jsonPrimitive?.contentOrNull
                    ?: dTagUuid(dTag!!)
                    ?: error("uuid not derivable from event")).lowercase()
                // A community event is self-signed by ANY keypair, so this uuid is
                // attacker-controlled — yet it becomes the climbs PK AND is
                // interpolated UNENCODED into a Compose nav route (tap-to-open).
                // Restrict to the route-safe hex/dash charset (canonical UUID
                // 8-4-4-4-12 or a 32-hex catalogue id) so a crafted uuid can't
                // break/inject the route; reject (→ drop/DLQ the event) otherwise.
                require(uuid.matches(UUID_ROUTE_SAFE)) { "uuid has unsafe chars: ${uuid.take(16)}" }
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
                    framesText = framesText.orEmpty(),
                    framesHash = framesHash,
                    layoutId = layoutId ?: 0L,
                    setterGradeId = setterGradeId,
                    angle = angle,
                    bounds = bounds,
                    contentPubkeyPrefix = contentPubkeyPrefix,
                    boardBrand = resolvedBrand.wireValue,
                    deleted = deleted,
                )
            }

            /** d-tag pattern: cruxcoach:climb:<pubkey-prefix-8>:<uuid>. */
            internal fun dTagUuid(dTag: String): String? {
                val parts = dTag.split(":")
                return if (parts.size >= 4 && parts[0] == "cruxcoach" && parts[1] == "climb")
                    parts.last() else null
            }

            /** Route-safe uuid charset: lowercase hex + dashes — covers both a
             *  canonical UUID (8-4-4-4-12) and a 32-hex catalogue id, and excludes
             *  '/', '?', '#', whitespace etc. that could break/inject a Compose
             *  nav route. The length bound is a sanity cap. */
            private val UUID_ROUTE_SAFE = Regex("^[0-9a-f-]{16,64}$")
        }
    }

    private companion object {
        const val TAG = "CommunityClimbSub"
        // Derived from the canonical publish-side constants (a const val may
        // reference another const val) so subscribe and publish can't drift:
        // a rename of CommunityClimbTags.NS_CLIMB* updates both sides at once.
        // NS_CLIMB = Kilter/legacy; NS_CLIMB_V2 = the non-Kilter back-compat
        // namespace (FEAT-031) that pre-0.2.0 apps' single-#L filter never matches.
        const val NAMESPACE_LABEL = CommunityClimbTags.NS_CLIMB
        const val NAMESPACE_LABEL_V2 = CommunityClimbTags.NS_CLIMB_V2
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
        const val KIND_DELETION = 5
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
        // Negative-cache TTL on a *failed* setter resolve attempt. After
        // this window the next event from the same pubkey retries the
        // Kind-0 relay fetch. 30 min strikes the right balance between
        // recovering quickly from a transient relay timeout and not
        // hammering relays for a setter who genuinely never published a
        // Kind 0 (in which case the npub stub is the correct steady state).
        const val RESOLVE_RETRY_TTL_MS = 30L * 60L * 1000L

        /** Cap on retries per dead-letter row. Past this the row is
         *  considered abandoned — it stays in the DLQ for diagnostics
         *  but the retry pass skips it. 5 covers 5 app-launches /
         *  manual-retry-button presses; if every one fails the issue
         *  is structural (corrupt event, schema-mismatch row) and
         *  hammering on it indefinitely doesn't help. */
        const val MAX_DEAD_LETTER_RETRIES = 5L

        /** Per-call DLQ batch size. Bounds the cost of a single retry
         *  pass so a giant accumulated backlog doesn't lock the writer
         *  for seconds. The retry method runs to completion within a
         *  single batch; subsequent batches come from the next start
         *  or manual trigger. */
        const val MAX_DEAD_LETTER_BATCH = 25L

        /** Truncation for the `last_error_excerpt` column. Keeps
         *  pathological exception messages (e.g. SQLiteException with
         *  the full failing SQL inlined) out of the DB. */
        const val MAX_DLQ_ERROR_EXCERPT = 200

        /** Upper bound on how long [seedCursorFromManifestIfFirstRun]
         *  waits for the parallel board-sync to write its first manifest
         *  before falling through to an unseeded REQ. 30 s is generous:
         *  the manifest fetch is a single Nostr REQ to three relays in
         *  parallel and completes in ~100 ms LAN, ~1-2 s mobile. The
         *  ceiling exists so a permanently-broken network doesn't
         *  starve the live-sub forever — past the timeout, accepting
         *  the cold-start relay flood is the lesser evil. */
        const val SEED_MANIFEST_TIMEOUT_MS = 30_000L

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

    /** Reject events claiming creation more than this far in the future.
     *  Events are stamped at publish time, so meaningful positive skew is a
     *  forged timestamp; 1 h tolerates client clock drift. */
    const val MAX_FUTURE_SKEW_SECONDS = 60L * 60L

    /** True iff [createdAtSec] is not implausibly far in the future relative to
     *  [nowSec]. A far-future event must be dropped before it is ingested as the
     *  newest climb or allowed to advance the `since` cursor — otherwise a forged
     *  far-future timestamp pushes the cursor past every real event and silently
     *  disables the live subscription. */
    fun isWithinClockSkew(
        createdAtSec: Long,
        nowSec: Long,
        maxFutureSkewSec: Long = MAX_FUTURE_SKEW_SECONDS,
    ): Boolean = createdAtSec <= nowSec + maxFutureSkewSec

    // ── Skip-matrix helpers (extracted for unit testability) ─────────

    /** Hard cap on raw event JSON size. Mirrors [CommunityClimbSubscriber.MAX_EVENT_BYTES]. */
    const val MAX_EVENT_BYTES = 16 * 1024

    /** Hard cap on `name` field length. Matches FEAT-003 §4.4 ingest rule. */
    const val MAX_NAME_LENGTH = 100

    /** Hard cap on `description` field length. Matches FEAT-003 §4.4 ingest rule. */
    const val MAX_DESCRIPTION_LENGTH = 500

    /** Hard cap on number of selected holds. Mirrors the publisher's
     *  MAX_HOLDS_TOTAL guard so a malicious event can't push a 10k-placement
     *  payload through. */
    const val MAX_HOLDS = 200

    /** True when the raw event JSON length is acceptable. */
    fun eventSizeAcceptable(byteLength: Int): Boolean = byteLength in 0..MAX_EVENT_BYTES

    /** True when the climb name length is acceptable. */
    fun nameLengthAcceptable(length: Int): Boolean = length in 0..MAX_NAME_LENGTH

    /** True when the climb description length is acceptable. */
    fun descriptionLengthAcceptable(length: Int): Boolean = length in 0..MAX_DESCRIPTION_LENGTH

    /** True when the parsed-holds count is acceptable. */
    fun holdsCountAcceptable(count: Int): Boolean = count in 0..MAX_HOLDS

    /** True when the event kind matches the expected Kind-30078 (parameterized replaceable). */
    fun kindAcceptable(kind: Int): Boolean = kind == 30078

    /** True when the incoming event is from the local user — used for the
     *  self-echo skip (relay echoes our own publishes back to us). */
    fun isOwnEvent(eventPubkey: String, localPubkey: String?): Boolean =
        localPubkey != null && eventPubkey == localPubkey
}
