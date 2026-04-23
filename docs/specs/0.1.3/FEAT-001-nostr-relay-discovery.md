# Feature Spec: Nostr Relay Discovery (NIP-65) (v0.1.3)

> **Status:** Ready for implementation — design decisions resolved (§6), concrete
> API surface defined (§7–§8), error handling / tests / rollout specified (§9–§13).
> **Prerequisite for:** FEAT-002 (Nostr Backup & Sync) — consumes the pool
> contract defined in §8.

## 1. Overview

Today every Nostr feature in CruxCoach (DMs, profile fetch, zaps, crash reports,
announcements) routes through `NostrRelayPool`, which statically uses the three
hardcoded relays in `NostrConfig.DEFAULT_RELAYS` (`relay.damus.io`, `nos.lol`,
`relay.primal.net`). Users who publish their preferred relays via NIP-65
(Kind 10002) are ignored — we may miss their DMs, send zap requests to relays
they never read, and our events may not land on the relays where their
followers expect them.

This feature introduces project-wide NIP-65 relay discovery. The hardcoded
defaults become a *bootstrap fallback* rather than the authoritative list.
All existing consumers are refactored to use the discovered pool.

### Goals

- Fetch the user's Kind 10002 relay list from well-known bootstrap relays
- Parse read/write markers and merge with hardcoded defaults (additive, never strictly replacing)
- Cache the resolved list locally and expose it as the single source of truth for `NostrRelayPool`
- Refactor all existing Nostr consumers to share the one discovered pool
- Run fully invisibly — no settings screen, no user interaction; identity events (logout, key change) and the TTL handle cache lifecycle

### Non-Goals

- **No self-publishing of Kind 10002.** Users manage their own relay list via Amber or third-party clients (e.g. Coracle, Amethyst). CruxCoach only reads it.
- **No relay-management UI of any kind.** Discovery is entirely invisible to the user — no read-only list, no manual-refresh button, no add/remove. Showing relays the user cannot act on adds surface without changing behavior; users who want to inspect or edit their list use the tool that publishes it (Amber, Coracle, Amethyst).
- **No per-feature relay pools** (e.g. separate pool for zaps vs. DMs). One shared pool keeps connection count low and debugging simple.
- **No Blossom server discovery** (Kind 10063). Separate concern, covered by FEAT-002.
- **No Outbox/Gossip-model routing** (write events to each recipient's read relays). Current write model stays fire-and-forget to the user's own write relays.

---

## 2. Architecture

```
                   ┌─────────────────────────────┐
                   │  Bootstrap Discovery Relays │
                   │   purplepag.es              │
                   │   relay.nostr.band          │
                   └──────────────┬──────────────┘
                                  │  REQ Kind 10002
                                  ▼
                   ┌─────────────────────────────┐
                   │  Nip65RelayListFetcher      │
                   │  (parse r-tags)             │
                   └──────────────┬──────────────┘
                                  │
                   ┌──────────────▼──────────────┐
                   │  RelayListResolver          │
                   │  merge(userRelays, defaults)│
                   │  cache (DataStore, 24 h TTL)│
                   └──────────────┬──────────────┘
                                  │  pool.onRelaysChanged(...)
                   ┌──────────────▼──────────────┐
                   │      NostrRelayPool         │
                   │  (single shared singleton)  │
                   └──────────────┬──────────────┘
                                  │
        ┌──────────┬──────────────┼──────────────┬─────────────┐
        ▼          ▼              ▼              ▼             ▼
     DMs (NIP-17) Profiles (K0) Zaps (K9734) CrashReports  Announcements
```

### Core Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Discovery source | Kind 10002 (NIP-65) | De facto standard. Supported by Amber, Amethyst, Coracle, Primal, Damus |
| Bootstrap relays | `wss://purplepag.es`, `wss://relay.nostr.band` | Both specialize in indexing replaceable kinds (10002, 0). Hardcoded — not user-configurable in v1 |
| Merge strategy | Additive (union) | `resolved = userRelays ∪ defaults`. More relays = more safety nets, not more dependencies |
| Pool model | Single shared `NostrRelayPool` | All features use the same WebSocket pool. Avoids N×M connection explosion |
| Publishing of Kind 10002 | Explicitly out of scope | User manages via Amber/third-party clients. CruxCoach is read-only |
| Cache layer | DataStore-Preferences, 24 h TTL | Matches existing `UserPreferences` storage. JSON-serialized `ResolvedRelayList` under a single key. TTL + invalidation policy: §6.1 and §6.6 |
| Fallback | Defaults on any failure | Bootstrap down, user has no Kind 10002, parse error → use `NostrConfig.DEFAULT_RELAYS` |

---

## 3. Data Model

Rather than introduce a parallel type, extend the existing
`com.cruxcoach.android.nostr.model.RelayConfig` with an optional `source`
field. `NostrRelayPool` already consumes `RelayConfig` throughout — adding
to that type avoids a translation layer at the pool boundary.

### 3.1 Kind 10002 Event (NIP-65)

```json
{
  "kind": 10002,
  "pubkey": "<user-pubkey>",
  "tags": [
    ["r", "wss://relay.example.com"],              // read + write
    ["r", "wss://nos.lol", "read"],                // read-only
    ["r", "wss://relay.primal.net", "write"]       // write-only
  ],
  "content": ""
}
```

Missing marker → treat as both read and write (NIP-65 default).

### 3.2 Resolved Relay List

```kotlin
package com.cruxcoach.android.nostr.model

enum class RelaySource { USER_NIP65, DEFAULT }

data class RelayConfig(
    val url: String,
    val read: Boolean = true,
    val write: Boolean = true,
    val source: RelaySource = RelaySource.DEFAULT,
)

data class ResolvedRelayList(
    val relays: List<RelayConfig>,
    val resolvedAt: Instant,
    val hasUserList: Boolean,   // false → only defaults
)
```

The `source` field is metadata-only — pool operations do not branch on
it. It exists for telemetry (§13) and potential future diagnostics.

### 3.3 Persisted Cache Shape

Serialized via `kotlinx.serialization` as a single JSON string under
`PreferenceKeys.NIP65_RESOLVED_RELAYS` in the existing DataStore-Preferences
instance. One key keeps migrations trivial (clear-on-version-bump is a single
`remove`). See §11 for migration details.

```kotlin
@Serializable
private data class PersistedRelayList(
    val schemaVersion: Int = 1,
    val relays: List<PersistedRelay>,
    val resolvedAtEpochMs: Long,
    val hasUserList: Boolean,
)

@Serializable
private data class PersistedRelay(
    val url: String,
    val read: Boolean,
    val write: Boolean,
    val source: String,   // RelaySource.name
)
```

---

## 4. Integration Touchpoints

All existing consumers must be refactored to use the resolved pool instead of
`NostrConfig.DEFAULT_RELAYS` directly. Known callsites:

| Consumer | File | Current behavior | After FEAT-001 |
|----------|------|------------------|----------------|
| Relay pool init | `di/AppModule.kt` | Constructs pool from `DEFAULT_RELAYS` | Constructs pool with injected `RelayListResolver`; pool pulls from resolver |
| DM sender | `nostr/NostrMessageSender.kt` | Broadcasts to pool | Unchanged — pool is updated, sender is not |
| DM subscription | `nostr/NostrRelaySubscription.kt` | Subscribes on pool | Unchanged |
| Profile fetch | `payment/NostrProfileManager.kt` | Queries pool for Kind 0 | Unchanged |
| Zap request | `payment/ZapManager.kt` | Publishes Kind 9734 to pool | Unchanged in v1 (see §6.3 — Outbox routing deferred) |
| Crash reports | (see `NostrRelayPool.kt` usage) | Publishes via pool | Unchanged |
| Announcements | `ui/devcontact/AnnouncementsViewModel.kt` | Queries pool | Unchanged |
| Notification poll | `notification/NotificationPollWorker.kt` | Queries pool | Unchanged |
| Blossom sync | `data/blossom/BlossomSyncManager.kt` | Uses pool for manifest events | Unchanged |

Refactor strategy: invert the dependency. Consumers already take
`NostrRelayPool`; we only change how the pool itself is constructed and when
it refreshes its relay list. Consumer code should need zero or minimal edits.

### 4.1 Pool Observability — Pull + Explicit Drop Hook

`NostrRelayPool` today reads `NostrConfig.DEFAULT_RELAYS` directly on
every `sendEvent` / `subscribe` / `reconnectAll` call. The refactor
replaces those reads with a pull from the resolver, plus a one-way
notification for dropped relays. Exact contract is in §8.

---

## 5. Cache & Refresh

Discovery is expensive (two WebSocket connections, a REQ, a wait for EOSE), so
the resolved list is cached locally in DataStore-Preferences (same instance
as `UserPreferences`). Two forces govern the cache:

- **TTL-based freshness** (§6.1) — opportunistic refresh on app start when the
  cached list is older than the TTL
- **Invalidation triggers** (§6.6) — identity changes (logout, key switch)
  clear the cache; the TTL handles all other refresh needs

See §6 for the full decision record.

---

## 6. Design Decisions

These replace the skeleton's open-question list. Rationale is kept short.

### 6.1 Refresh Strategy — TTL + Opportunistic on App Start

**Decision:** Cache with a 24 h TTL; check freshness on every app start.
If the cached list is stale, serve it immediately and fire a background
refresh so the next launch sees fresh data. No WorkManager job runs when
the app isn't in use.

**Why:** Kind 10002 events change rarely, so 24 h is "fresh enough" in
practice. Background scheduling would burn battery on users who don't open
the app. Running discovery only on actual launches means the bootstrap
relays are hit at most once per 24 h window of real usage, and the UI is
never blocked waiting for discovery.

**How to apply:**
- Cold start within TTL → instant cache hit, no network traffic
- Cold start past TTL → cache served to UI, background refresh runs in parallel
- Fresh install (no cache) → synchronous bootstrap fetch, defaults on failure
  (see §6.4 for the failure path)

### 6.2 Read/Write Marker Handling — Operation-Based in Pool, Never Per-Consumer

**Decision:** The pool filters by marker based on *operation type*:
- `sendEvent` → `write`-marked relays
- `subscribe` / `reconnectAll` → `read`-marked relays

This matches the filters already present in `NostrRelayPool.kt` today
(no-op today because defaults are all bidirectional, but kicks in
naturally once NIP-65 relays with markers arrive).

What this decision explicitly does **not** do: per-consumer routing.
Every consumer still calls the same `sendEvent` / `subscribe` on the
shared pool; the pool handles relay selection based on the operation.
There is no "zaps publish to recipient's read relays" or "DMs only to a
specific subset" logic — that would be Outbox-model territory (§6.3).

**Why:** Operation-based filtering respects user intent at essentially
zero cost — the filters already exist. Publishing to a relay the user
marked read-only wastes bandwidth and usually gets rejected; subscribing
to a write-only relay yields nothing. Leaving the existing filters in
place is strictly correct. Per-consumer routing is the expensive
complexity and stays out of scope.

**How to apply:**
- `RelayConfig.read` / `RelayConfig.write` populated per NIP-65 parse
  (missing marker → both true)
- Pool's existing `.filter { it.write }` (sendEvent) and
  `.filter { it.read }` (subscribe, reconnectAll) stay as-is
- No consumer-side changes

### 6.3 Zap Request Targeting — Own Pool in v1, Generic Fetcher Underneath

**Decision:** Zaps keep using the own relay pool in v1. The underlying
`Nip65RelayListFetcher` is implemented as a pubkey-agnostic helper
(`fetch(pubkey: String)`), so a later mini-spec can route zaps to the
recipient's inbox relays without refactoring.

**Why:** NIP-57 strictly implies Outbox-model targeting (publish zap request
to the recipient's read relays). That broadens scope into general Outbox
routing, which touches DMs and more. Shipping the current pool keeps v1
tight; the API shape of the fetcher is already "correct" for the later
extension.

**How to apply:**
- `ZapManager` stays unchanged — publishes to `NostrRelayPool`
- `Nip65RelayListFetcher` signature: `suspend fun fetch(pubkey: String): Kind10002Event?`
- Follow-up ticket: "Outbox routing for zaps and DMs" (depends on FEAT-001)

### 6.4 Bootstrap Failure Handling — Three-Stage Graceful Degradation

**Decision:** Discovery never hard-fails. Fallback order:
1. Fresh cache (within TTL) → use it
2. Stale cache (past TTL, bootstrap unreachable) → use it, retry in background
3. No cache at all → run synchronous bootstrap with a **3 s timeout**; on
   timeout or failure, fall back to `NostrConfig.DEFAULT_RELAYS` and let
   the background retry populate the cache for the next launch

No user-visible error for discovery failures. Logging at debug level only.

**Why:** The hardcoded defaults already power every existing Nostr feature
today, so falling back to them is strictly safe. A stale Kind 10002 is
strictly better than defaults because it reflects the user's real relays,
even if slightly out of date. The 3 s fresh-install timeout is the one
place discovery *could* block the UI — 3 s is long enough for a healthy
network on a fresh relay connection, short enough that users on a bad
connection don't notice a freeze.

**How to apply:**
- Never block the UI on discovery beyond the 3 s fresh-install cap
- Never surface discovery errors to the user
- First launch: synchronous fetch capped at 3 s, defaults take over on
  timeout; a background task retries so the next cold start hits the cache
- Retry cadence: next app start (the 24 h TTL already handles eventual refresh)

### 6.5 Amber Availability — Not Applicable

**Decision:** Removed as an open question. Discovery is unauthenticated
reads of public Kind 10002 events — no signing involved. The user's own
pubkey is cached locally after first login, so Amber is never on the
discovery path.

### 6.6 Cache Invalidation Triggers — Exactly Three

**Decision:** The cache is cleared or bypassed only by:
1. **Logout** — clear cache entirely
2. **Key change / Nostr identity switch** — clear cache entirely
3. **TTL expiry** (§6.1) — opportunistic refresh on next launch

Deliberately excluded: app-update (orthogonal to relay list — but see §11
for the one-time migration), individual relay connection failures (transient
network issues must not invalidate the list), long idle without foreground
use (covered by TTL on next launch), manual-refresh button (no settings UI
in v1 per §1 Non-Goals — users who need an immediate refresh can log out
and back in, which clears the cache via trigger #1).

**How to apply:** Invalidation logic lives in `RelayListResolver`.
Consumers never invalidate directly — they observe pool changes through
the shared `NostrRelayPool`.

---

## 7. Classes & Packages

Three new Kotlin files under
`androidApp/src/main/java/com/cruxcoach/android/nostr/relaydiscovery/`:

### 7.1 `Nip65RelayListFetcher.kt`

```kotlin
package com.cruxcoach.android.nostr.relaydiscovery

@Singleton
class Nip65RelayListFetcher @Inject constructor(
    @Named("nostr") private val okHttpClient: OkHttpClient,
) {
    /**
     * Opens short-lived WebSocket connections to bootstrap relays, sends a
     * REQ for Kind 10002 events authored by [pubkey], waits for EOSE or
     * [timeoutMs], and returns the highest-`created_at` event or null.
     *
     * Never throws. Network/parse failures → null.
     */
    suspend fun fetch(
        pubkey: String,
        bootstrapRelays: List<String> = DEFAULT_BOOTSTRAP,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Kind10002Event?

    companion object {
        val DEFAULT_BOOTSTRAP = listOf(
            "wss://purplepag.es",
            "wss://relay.nostr.band",
        )
        const val DEFAULT_TIMEOUT_MS = 3_000L   // §6.4 first-install cap
    }
}

data class Kind10002Event(
    val pubkey: String,
    val createdAt: Long,
    val relays: List<RelayMarker>,
) {
    data class RelayMarker(val url: String, val read: Boolean, val write: Boolean)
}
```

**Parser rules** (internal helper, unit-tested separately — see §10.1):
- Iterate tags; keep only `["r", url, marker?]` tuples with a `wss://` URL
- Missing or unknown marker → both `read=true` and `write=true`
- Empty URL, duplicate URL, or malformed scheme → skip the tag, do not fail the event

### 7.2 `RelayListCache.kt`

```kotlin
package com.cruxcoach.android.nostr.relaydiscovery

@Singleton
class RelayListCache @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    suspend fun read(): ResolvedRelayList?
    suspend fun write(list: ResolvedRelayList)
    suspend fun clear()
    suspend fun isStale(ttl: Duration = TTL_DEFAULT): Boolean

    companion object { val TTL_DEFAULT = 24.hours }
}
```

Single DataStore key: `PreferenceKeys.NIP65_RESOLVED_RELAYS` holding the
JSON-serialized `PersistedRelayList` (see §3.3). All reads are suspend
(not Flow) — consumers need a snapshot, not a subscription. Writes are
atomic per DataStore semantics.

### 7.3 `RelayListResolver.kt`

```kotlin
package com.cruxcoach.android.nostr.relaydiscovery

@Singleton
class RelayListResolver @Inject constructor(
    private val fetcher: Nip65RelayListFetcher,
    private val cache: RelayListCache,
    private val pool: NostrRelayPool,
    private val keyStore: NostrKeyStore,
    private val clock: Clock = Clock.System,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    /**
     * Snapshot of the current resolved pool. Returns immediately from cache
     * (or defaults on first call). Triggers a background refresh if the
     * cache is past TTL. Never blocks beyond the 3 s fresh-install timeout
     * defined in §6.4.
     */
    suspend fun current(): ResolvedRelayList

    /**
     * Called by NostrKeyStore on logout / key switch (§6.6 triggers 1–2).
     */
    suspend fun invalidate()

    /**
     * Manually kick off a refresh. Used only by the first-install bootstrap
     * path; consumers should prefer [current].
     */
    fun refreshAsync(): Job
}
```

The resolver is the single seam between fetcher + cache + pool. It computes
the additive-union merge (§2 Core Decisions), tags each entry with its
`RelaySource`, invokes `pool.onRelaysChanged(...)` when the resolved set
actually changes, and writes the new cache entry.

### 7.4 DI Wiring (`AppModule.kt`)

`RelayListResolver` is constructed by Hilt (constructor-injected deps).
`NostrRelayPool` gains a constructor param for `RelayListResolver`. The
resolver is injected with `@ApplicationScope CoroutineScope` so background
refresh jobs outlive any specific ViewModel.

First-launch bootstrap hook: `CruxCoachApp.onCreate()` calls
`resolver.refreshAsync()` once to populate the cache without blocking app
startup. `Application.onCreate` is the single trigger point.

---

## 8. Pool Contract (consumed by FEAT-002)

`NostrRelayPool` gains three public methods. These are the stable contract
FEAT-002 and any later Nostr feature depend on.

```kotlin
class NostrRelayPool @Inject constructor(
    @Named("nostr") private val okHttpClient: OkHttpClient,
    private val resolver: RelayListResolver,
) {
    /** Relays marked write-enabled after NIP-65 merge. Snapshot, not Flow. */
    suspend fun writeRelays(): List<RelayConfig>

    /** Relays marked read-enabled after NIP-65 merge. Snapshot, not Flow. */
    suspend fun readRelays(): List<RelayConfig>

    /**
     * Called by [RelayListResolver] when the resolved list changes. Dropped
     * URLs get their WebSocket connections closed; new URLs are lazily
     * connected on the next sendEvent/subscribe; stable URLs keep running.
     */
    fun onRelaysChanged(resolved: List<RelayConfig>)

    // Existing API unchanged:
    suspend fun sendEvent(event: Event): Boolean
    fun subscribe(filter: String, skipDedup: Boolean = false, closeOnEose: Boolean = false): Flow<String>
    fun closeAll()
    fun reconnectAll()
}
```

- `writeRelays()` / `readRelays()` delegate to `resolver.current()` and
  apply the operation-type filter (§6.2). They never perform I/O beyond
  the resolver's cache read.
- `onRelaysChanged` is idempotent: calling with the same list is a no-op.
- FEAT-002 consumes `writeRelays()` for fire-and-forget broadcast and
  `readRelays()` for the restore query-all path.

---

## 9. Error Handling Matrix

| Failure | Detection | Resolver response | Log level |
|---------|-----------|-------------------|-----------|
| Both bootstrap relays TCP/TLS refuse | WebSocket `onFailure` before `onOpen` | Fresh install: defaults + background retry on next launch. Existing cache: serve cache | `Log.d(TAG, ...)` |
| One bootstrap relay unreachable | Normal — the other answers | Union whatever arrived before EOSE / timeout; continue | `Log.d` |
| Bootstrap timeout (3 s), no cache | Fetcher returns `null` | Defaults; schedule background retry | `Log.d` |
| Bootstrap timeout, have cache | Fetcher returns `null` | Keep existing cache; background retry | `Log.d` |
| Kind 10002 event with zero valid `r` tags | Parser returns empty list | Treat as "no user list" → defaults only, `hasUserList = false` | `Log.d` |
| Signature mismatch on relay-side | Relay rejects the event, never delivered | Unreachable — bootstrap relays verify before delivering | n/a |
| Signature valid but pubkey mismatch | Fetcher compares `event.pubkey` against requested `pubkey` | Drop event, treat as no result | `Log.w` |
| Malformed JSON from relay | `kotlinx.serialization` throws | Catch, drop event, continue with other relay's reply | `Log.w` |
| DataStore write fails (disk full, I/O error) | `DataStore` throws | Serve in-memory resolved list for the rest of this process; next app start retries | `Log.w` |
| DataStore read returns corrupt JSON | Deserialization throws | Treat as cache-miss; proceed with bootstrap fetch; overwrite the corrupt entry on next successful fetch | `Log.w` |
| `onRelaysChanged` called with an identical list | Resolver deep-equality check | No-op — pool is not notified | — |
| User pubkey unknown (fresh install, pre-NostrKeyStore) | Resolver gates on `NostrKeyStore.currentPubkey` | Skip discovery, use defaults; retry after key generation completes | `Log.d` |

Every error path lands on the defaults fallback. Discovery never throws
out of `resolver.current()`.

---

## 10. Test Plan

All tests live under `androidApp/src/test/java/com/cruxcoach/android/nostr/relaydiscovery/`.
JUnit4 + `kotlinx-coroutines-test` + MockK, matching existing test style
(e.g. `UserPreferencesAnnouncementCategoryTest.kt`).

### 10.1 `Nip65RelayListParserTest.kt` — pure-function unit tests

- Multi-relay event with mixed markers → correct `RelayMarker` list
- Missing marker → both `read` and `write` true
- Unknown marker string (`"readwrite"`) → both true (NIP-65 permissive default)
- `http://` or `ftp://` URL → tag skipped, no failure
- Empty `r` tag `["r", ""]` → skipped
- Duplicate URLs → deduplicated by URL, first marker wins
- Event with `kind != 10002` → fetcher-level type guard rejects, parser never called

### 10.2 `RelayListMergeTest.kt` — union + tagging

- User `[A, B]` + defaults `[B, C]` → resolved `[A (USER_NIP65), B (USER_NIP65), C (DEFAULT)]` — user-source wins on overlap
- Empty user list + defaults → resolved = defaults with `hasUserList = false`
- User list only (no defaults configured) → defaults are still appended (hardcoded constant is always present)
- Marker preservation across merge: user says `A` is write-only, default says `A` is bidirectional → resolved `A` is write-only (user intent wins)

### 10.3 `RelayListResolverTest.kt` — behavioral

- Fresh install, no cache, fetcher succeeds → `current()` returns merged list, cache written, pool notified
- Fresh install, no cache, fetcher times out → `current()` returns defaults, pool notified with defaults, background retry job scheduled
- Cached + within TTL → `current()` returns cache, no fetch, no pool notification
- Cached + past TTL → `current()` returns cache immediately, background refresh launches
- `invalidate()` → cache cleared, next `current()` triggers fetch
- Two concurrent `current()` calls during a fetch → exactly one fetch runs (coroutine deduplication)
- `onRelaysChanged` with identical content → `pool.onRelaysChanged` not called
- Corrupt cache entry → treated as cache-miss, fetch runs, cache overwritten

### 10.4 `RelayListCacheTest.kt` — DataStore round-trip

- Write → read returns the same `ResolvedRelayList`
- Write twice → second write overrides first
- Read on empty DataStore → null
- `isStale(ttl)` when `resolvedAt` is within TTL → false; past TTL → true
- `clear()` after write → subsequent read returns null

### 10.5 Integration-style — mock relays

A mock WebSocket server (OkHttp's `MockWebServer`) verifies the
fetcher's protocol handshake:

- REQ message has correct subscription id + Kind 10002 filter + pubkey
- Respond with EVENT + EOSE → fetcher returns parsed event
- Respond with EOSE only → fetcher returns null
- Server never responds → 3 s timeout fires, fetcher returns null
- Server sends malformed JSON → dropped, fetcher returns null

### 10.6 Out of scope for unit tests

- Real-network against `purplepag.es` / `relay.nostr.band` — covered only
  by manual smoke test during release QA
- WorkManager scheduling — no WorkManager is used (§6.1)

---

## 11. Migration

### 11.1 First launch after upgrade from 0.1.2 → 0.1.3

- No existing NIP-65 cache key in DataStore → treated as fresh install
- Existing `NostrRelayPool` is reconstructed with the new resolver dependency
  (Hilt singleton is rebuilt on process restart after install)
- All existing Nostr consumers work through `DEFAULT_RELAYS` for up to 3 s,
  then transparently start using the resolved pool
- No schema migration needed — the cache is additive to DataStore

### 11.2 `PersistedRelayList.schemaVersion`

Bumping `schemaVersion` in a future release is a forced cache-clear:
`RelayListCache.read()` compares the deserialized `schemaVersion` to the
current constant; mismatch → return null, trigger cache-clear + refetch.
v1 is the only schema version at 0.1.3 ship.

### 11.3 Rollback from 0.1.3 → 0.1.2

Downgrade is not supported by this feature — the DataStore entry persists
but is simply ignored by 0.1.2 code (0.1.2 doesn't read `NIP65_RESOLVED_RELAYS`).
No corruption risk; an eventual re-upgrade reuses the stale cache until
the TTL fires.

---

## 12. Rollout & Kill-Switch

### 12.1 Feature flag

```kotlin
// In PreferenceKeys:
val NIP65_DISCOVERY_ENABLED = booleanPreferencesKey("nip65_discovery_enabled")
```

Default: `true`. Exposed via `UserPreferences.nip65DiscoveryEnabled: Flow<Boolean>`
with a one-shot setter `setNip65DiscoveryEnabled(enabled: Boolean)`.
Not surfaced in the settings UI in v1 (per §1 Non-Goals) — the flag exists
so we can remotely or via support guidance flip it off if a bootstrap relay
starts returning poisoned events or if the resolver introduces a regression.

When the flag is `false`:
- `resolver.current()` short-circuits to
  `ResolvedRelayList(relays = DEFAULT_RELAYS, resolvedAt = now, hasUserList = false)`
- No bootstrap fetch runs; no cache is written
- Existing cache is left untouched (so re-enabling restores the previous
  state without a fresh fetch)

### 12.2 Rollout sequence

- **0.1.3-dev.\*** (CI prereleases): flag default `true`, internal testing
- **0.1.3 stable**: flag default `true`, monitored via telemetry (§13)
- If the stale-cache-used rate climbs above a threshold (empirical TBD after
  first week of real data), investigate before continuing feature expansion

There is no staged percentage rollout — Zapstore has no notion of rings,
and the overall install base is small enough that full rollout is the
correct default.

### 12.3 Disable path

User-visible cause to disable: a bootstrap relay starts delivering forged
events that somehow pass signature verification, or the fetcher introduces
a crash loop. Mitigations:

- Developer DM the user `nip65_discovery_enabled = false` guidance, or
- Ship 0.1.3.x with the default flipped, or
- Ship 0.1.3.y with the feature excised entirely (clean revert — the pool
  still works on defaults)

The kill-switch is a safety net, not a rollout mechanism.

---

## 13. Telemetry

No analytics library is present in CruxCoach. "Telemetry" for FEAT-001 is
structured `Log` entries with a stable tag + key/value payload, mined from
`adb logcat` during internal testing and user-submitted crash reports.

Single tag: `Log.TAG = "Nip65Discovery"`. All log lines follow the shape:

```
Nip65Discovery: event=<event-name> key1=<val1> key2=<val2>
```

### 13.1 Events

| Event name | Level | Fields | Emitted by |
|------------|-------|--------|------------|
| `resolver_current` | `Log.d` | `source={cache|fresh-fetch|defaults}`, `hasUserList`, `relayCount` | `RelayListResolver.current()` on each call |
| `fetch_start` | `Log.d` | `bootstrapCount`, `timeoutMs` | `Nip65RelayListFetcher.fetch()` |
| `fetch_success` | `Log.d` | `relayCount`, `durationMs` | `Nip65RelayListFetcher.fetch()` |
| `fetch_timeout` | `Log.d` | `durationMs` | `Nip65RelayListFetcher.fetch()` |
| `fetch_all_failed` | `Log.w` | `durationMs`, `errorSummary` | `Nip65RelayListFetcher.fetch()` |
| `cache_hit` | `Log.d` | `ageMs` | `RelayListCache.read()` |
| `cache_miss` | `Log.d` | `reason={empty|corrupt|schema-mismatch}` | `RelayListCache.read()` |
| `cache_stale_served` | `Log.d` | `ageMs` | `RelayListResolver.current()` |
| `pool_relays_changed` | `Log.d` | `added`, `dropped`, `stable` (counts) | `NostrRelayPool.onRelaysChanged()` |
| `invalidated` | `Log.d` | `trigger={logout|key-switch}` | `RelayListResolver.invalidate()` |
| `killswitch_off` | `Log.i` | — | `RelayListResolver.current()` — logged once per process |

### 13.2 Derived measurements

From logs alone, post-release analysis can answer:
- Cache hit rate: `cache_hit / (cache_hit + cache_miss)`
- Stale-cache rate: `cache_stale_served / resolver_current`
- Bootstrap success rate: `fetch_success / (fetch_success + fetch_timeout + fetch_all_failed)`
- Median fetch duration (from `durationMs` fields)

No PII is logged — relay URLs are not emitted in event payloads. Counts
and booleans only.

---

## 14. Security & Privacy

- Kind 10002 events are **public** — fetching them leaks no additional info
  beyond "someone queried this pubkey's relay list". Bootstrap relays
  already see that query from every client.
- No credentials involved; discovery is unauthenticated read.
- Malicious Kind 10002 (e.g. attacker-signed event for someone else's
  pubkey) → impossible: relays verify the signature matches the author's
  pubkey. The fetcher additionally compares `event.pubkey` to the queried
  `pubkey` as defense in depth (§9).
- **Expanded relay surface.** The resolved pool can be larger than the
  three defaults. Every additional relay sees publish traffic from this
  client (DMs via NIP-17 wraps, Kind 30078 pointer events via FEAT-002,
  etc.). This is an intended consequence of honoring NIP-65 — the user
  explicitly published those relays as their own. No new threat vs. the
  user using any other NIP-65-aware Nostr client.
- **Bootstrap relay trust.** `purplepag.es` and `relay.nostr.band` are
  load-bearing — if both are compromised and deliver a forged Kind 10002
  with the attacker's relays, the user's traffic is routed through those
  relays until the next refresh. Mitigation: the event's Schnorr signature
  is verified before parse (Quartz handles this); a forged event cannot
  appear to come from the user's pubkey without their key.

---

## 15. Out of Scope — Explicitly Deferred

These were considered and explicitly pushed out of v1:

- **Publishing Kind 10002** (user-initiated relay list management inside CruxCoach)
- **Add/remove relay UI** beyond the read-only display
- **Outbox-model routing** (writing to recipients' read relays per NIP-65 intent)
- **Per-feature pool segmentation** (separate pools for DMs, zaps, etc.)
- **Blossom server discovery** (Kind 10063 — FEAT-002 territory)
- **Relay health scoring / auto-pruning** of slow or misbehaving relays
- **Settings UI for the kill-switch flag** — flag exists but is dev-only in v1

---

## 16. Dependencies

- Quartz 1.05.1 — already in use; provides Kind 10002 event parsing primitives
- `NostrRelayPool` — existing singleton, to be enhanced (not replaced)
- `NostrConfig.DEFAULT_RELAYS` — stays as fallback constant
- `androidx.datastore:datastore-preferences` — already in use by
  `UserPreferences`; reused for cache storage
- `kotlinx.serialization` — already in use; new `@Serializable` types for
  `PersistedRelayList`
- OkHttp WebSocket — already available via the `@Named("nostr")` client
- No new third-party dependencies
