# Feature Spec: Nostr Relay Discovery (NIP-65) — Skeleton (v0.2.0)

> **Status:** Skeleton — scope + design decisions agreed (§6), implementation details TBD.
> **Prerequisite for:** FEAT-002 (Nostr Backup & Sync) relies on Kind 10002 discovery.

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
- Cache the resolved list and expose it as the single source of truth for `NostrRelayPool`
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
                   │   Bootstrap Discovery Relays │
                   │   purplepag.es              │
                   │   relay.nostr.band          │
                   └──────────────┬──────────────┘
                                  │  REQ Kind 10002
                                  ▼
                   ┌─────────────────────────────┐
                   │  Nip65RelayListFetcher      │
                   │  (parse read/write tags)    │
                   └──────────────┬──────────────┘
                                  │
                   ┌──────────────▼──────────────┐
                   │  RelayListResolver          │
                   │  merge(userRelays, defaults)│
                   │  cache (TTL, see §5)        │
                   └──────────────┬──────────────┘
                                  │
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
| Bootstrap relays | `purplepag.es`, `relay.nostr.band` | Both specialize in indexing replaceable kinds (10002, 0). Hardcoded — not user-configurable in v1 |
| Merge strategy | Additive (union) | `resolved = userRelays ∪ defaults`. More relays = more safety nets, not more dependencies |
| Pool model | Single shared `NostrRelayPool` | All features use the same WebSocket pool. Avoids N×M connection explosion |
| Publishing of Kind 10002 | Explicitly out of scope | User manages via Amber/third-party clients. CruxCoach is read-only |
| Cache layer | Local (SharedPreferences or DataStore), 24 h TTL | Avoid hammering bootstrap relays on every app launch. TTL + invalidation policy: §6.1 and §6.6 |
| Fallback | Defaults on any failure | Bootstrap down, user has no Kind 10002, parse error → use `NostrConfig.DEFAULT_RELAYS` |

---

## 3. Data Model

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

Rather than introduce a parallel type, extend the existing
`com.cruxcoach.android.nostr.model.RelayConfig` with an optional `source`
field. `NostrRelayPool` already consumes `RelayConfig` throughout — adding
to that type avoids a translation layer at the pool boundary.

```kotlin
enum class RelaySource { USER_NIP65, DEFAULT }

data class RelayConfig(
    val url: String,
    val read: Boolean = true,
    val write: Boolean = true,
    val source: RelaySource = RelaySource.DEFAULT
)

data class ResolvedRelayList(
    val relays: List<RelayConfig>,
    val resolvedAt: Instant,
    val hasUserList: Boolean  // false → only defaults
)
```

The `source` field is metadata-only — pool operations do not branch on
it. It exists for telemetry (§10) and potential future diagnostics.

---

## 4. Integration Touchpoints

All existing consumers must be refactored to use the resolved pool instead of
`NostrConfig.DEFAULT_RELAYS` directly. Known callsites:

| Consumer | File | Current behavior | After FEAT-001 |
|----------|------|------------------|----------------|
| Relay pool init | `di/AppModule.kt` | Constructs pool from `DEFAULT_RELAYS` | Constructs from `RelayListResolver.resolve()` |
| DM sender | `nostr/NostrMessageSender.kt` | Broadcasts to pool | Unchanged — pool is updated, sender is not |
| DM subscription | `nostr/NostrRelaySubscription.kt` | Subscribes on pool | Unchanged |
| Profile fetch | `payment/NostrProfileManager.kt` | Queries pool for Kind 0 | Unchanged |
| Zap request | `payment/ZapManager.kt` | Publishes Kind 9734 to pool | Unchanged in v1 (see §6.3 — Outbox routing deferred) |
| Crash reports | (see NostrRelayPool.kt usage) | Publishes via pool | Unchanged |
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
notification for dropped relays:

- Pool takes `RelayListResolver` as a constructor dependency
- On every operation the pool queries `resolver.current()` — no in-pool
  caching of the list itself
- When the resolver updates the list (logout, key change, TTL refresh),
  it calls `pool.onRelaysChanged(newList)`:
  - **Dropped relays** (URLs gone) → connections hard-closed, any
    in-flight subscriptions on them are lost
  - **New relays** → connections established lazily on the next
    `sendEvent` / `subscribe` call
  - **Stable relays** (still present) → untouched, existing connections
    and subscription IDs keep flowing
- No StateFlow / subscriber pattern — the pool pulls from the resolver
  per-op, and the drop hook exists only to release WebSocket handles
  for relays we no longer need

---

## 5. Cache & Refresh

Discovery is expensive (two WebSocket connections, a REQ, a wait for EOSE), so
the resolved list is cached locally (SharedPreferences or DataStore).
Two forces govern the cache:

- **TTL-based freshness** (§6.1) — opportunistic refresh on app start when the
  cached list is older than the TTL
- **Invalidation triggers** (§6.6) — identity changes (logout, key switch)
  clear the cache; the TTL handles all other refresh needs

See §6 for the full decision record.

---

## 6. Design Decisions

These replace the skeleton's open-question list. Rationale is kept short —
the full implementation spec (when FEAT-001 expands from skeleton) documents
edge cases and error handling.

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

Deliberately excluded: app-update (orthogonal to relay list), individual
relay connection failures (transient network issues must not invalidate
the list), long idle without foreground use (covered by TTL on next
launch), manual-refresh button (no settings UI in v1 per §1 Non-Goals —
users who need an immediate refresh can log out and back in, which clears
the cache via trigger #1).

**How to apply:** Invalidation logic lives in `RelayListResolver`.
Consumers never invalidate directly — they observe pool changes through
the shared `NostrRelayPool`.

---

## 7. Security & Privacy

- Kind 10002 events are **public** — fetching them leaks no additional info beyond "someone queried this pubkey's relay list". Bootstrap relays already see that query from every client.
- No credentials involved; discovery is unauthenticated read.
- Malicious Kind 10002 (e.g. attacker-signed event for someone else's pubkey) → impossible: relays verify the signature matches the author's pubkey.
- **Threat model to revisit:** what happens if an attacker controls a relay in the user's list and it returns malicious events? This is a pre-existing concern and not introduced by FEAT-001 — but the increased relay surface is worth noting during review.

---

## 8. Out of Scope — Explicitly Deferred

These were considered and explicitly pushed out of v1:

- **Publishing Kind 10002** (user-initiated relay list management inside CruxCoach)
- **Add/remove relay UI** beyond the read-only display
- **Outbox-model routing** (writing to recipients' read relays per NIP-65 intent)
- **Per-feature pool segmentation** (separate pools for DMs, zaps, etc.)
- **Blossom server discovery** (Kind 10063 — FEAT-002 territory)
- **Relay health scoring / auto-pruning** of slow or misbehaving relays

---

## 9. Dependencies

- Quartz 1.05.1 — already in use; provides Kind 10002 event parsing primitives
- `NostrRelayPool` — existing singleton, to be enhanced (not replaced)
- `NostrConfig.DEFAULT_RELAYS` — stays as fallback constant
- `Nip65RelayListFetcher` (new) — pubkey-agnostic helper, `fetch(pubkey) → Kind10002Event?`. Used by `RelayListResolver` for the own user in v1; future Outbox specs (§6.3) will reuse it for arbitrary pubkeys
- No new third-party dependencies expected

---

## 10. Delivery Checklist (for full spec later)

When expanding from skeleton to full spec, add:

- [ ] Concrete class names, package placement, function signatures
- [ ] Error handling matrix (bootstrap timeout, invalid event, signature mismatch, relay list empty)
- [ ] Test plan (unit: parser, merge logic, TTL expiry; integration: mock bootstrap relays, stale-cache fallback)
- [ ] Migration plan (first launch after upgrade — no cache yet, synchronous bootstrap or defaults)
- [ ] Rollout / kill-switch strategy (feature flag to disable discovery and revert to static defaults)
- [ ] Telemetry: success/failure rates for discovery, cache hit rate, stale-cache-used counter
