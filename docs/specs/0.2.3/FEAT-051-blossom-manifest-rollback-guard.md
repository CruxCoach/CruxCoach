---
status: implemented
queue: done
base: 0.2.2
depends_on: []
created: 2026-08-09
implemented: 2026-08-25
---

# Feature Spec: Blossom Manifest Rollback Guard

> **Status:** implemented for the 0.2.2 release-logbook branch after the
> frozen RepoLens security gate reproduced the rollback gap. Pure and
> Robolectric persistence coverage is automated; an APK is intentionally not
> installed on an ADB device by the release agent, so criterion 10 remains a
> manual hardware smoke check rather than a publication prerequisite.
> **Depends on:** none
> **Relates to:** none

## 1. Overview

The board-DB sync accepts an **older, validly signed manifest** and imports it.
Signature and hash verification do not catch this, because on a replayed
manifest both are correct — the event really was signed by the publisher, just
at an earlier time.

The gap was found while auditing the sync path for a possible multi-publisher
future, but it is independent of that: it affects the shipped single-publisher
client today.

**How it is reachable.** `fetchManifest` queries several relays and keeps the
newest answer via `selectPreferredManifest` (`BlossomSyncManager.kt:82-88`,
`:533-545`). If *every* relay serves a stale copy — or an attacker replays a
genuine older event to a relay the client reads — the client imports the older
catalogue. Nostr `created_at` is self-asserted, so it is not by itself a
trustworthy ordering signal against a malicious relay; what makes the guard
work is comparing against a value the *client itself* previously accepted.

**Why nothing currently stops it.** `getChangedChunks`
(`BlossomSyncManager.kt:188-193`) filters on `storedHash != chunk.sha256`, which
is direction-blind: a hash that changes *backwards* looks exactly like one that
changes forwards. And the only thing persisted is `chunk_sha256_<name>`
(`:190`, `:353-355`) — no manifest timestamp, so there is nothing to compare
against.

**Goals:**
- Persist the timestamp of the last successfully imported manifest, per sync
  track, and reject any manifest older than it.
- Keep the rejection **fail-safe**: already-imported, verified data stays in
  use; no wipe, no crash, no blocked future sync.
- Reuse the timestamp semantics the code already applies in
  `selectPreferredManifest`, so the two cannot disagree.
- Reject both envelope and manifest-content timestamps more than one hour in
  the future before either can advance a persistent monotonic watermark or
  seed the community-climb subscription cursor.
- On upgrade, remove an already-persisted future watermark without deleting
  chunk hashes, and remove an already-persisted future community cursor so the
  next safe manifest can reseed it (or the subscriber can backfill unseeded).

**Non-goals:**
- Multi-publisher support / configurable trust anchor.
- Manifest staleness-in-the-past / `expires` enforcement. The future-skew
  ceiling above is an ordering safety bound, not an expiry policy.
- Declared row counts and shrink limits.
  (These three are "Stufe 1" in the same analysis and are separate work.)
- Any UI. A rejected stale manifest is a silent no-op plus a log line.
- Any change to the publishing pipeline. See §6.6 for a consequence the
  pipeline must respect, but it is not implemented here.

## 2. Today's behaviour

1. `fetchManifest` collects manifest events from `MANIFEST_RELAYS`, re-verifies
   pubkey, Schnorr signature, event-id binding and d-tag client-side
   (`BlossomSyncManager.kt:106-122`), then picks the preferred answer with
   `selectPreferredManifest`.
2. `validateManifest` rejects path-traversal chunk names and cleartext URLs
   (`:548-561`).
3. `getChangedChunks` returns every chunk whose stored hash differs from the
   manifest hash — **in either direction** (`:188-193`).
4. Each chunk is downloaded, SHA-256-verified (`:245`, `:328`), decompressed and
   imported; `saveChunkHash` then stores the new hash (`:353-355`).

There is no step that asks "is this manifest newer than the one I already
have?".

## 3. Solution design

### 3.1 Shared effective-timestamp helper

`selectPreferredManifest` already derives an effective timestamp as
`eventCreatedAt.takeIf { it > 0 } ?: createdAt` (`:538-539`) — the Nostr
envelope timestamp, falling back to the signed content field for manifests
constructed without an envelope (tests, older publishes).

Extract that expression into one `internal` helper in the companion and call it
from **both** `selectPreferredManifest` and the new guard:

```kotlin
internal fun effectiveTimestamp(manifest: BlossomManifest): Long =
    manifest.eventCreatedAt.takeIf { it > 0 } ?: manifest.createdAt
```

Both fields are covered by the publisher's signature (the envelope `created_at`
is part of the serialization hashed into the event id, and `verifyId` binds the
content to that id), so neither can be altered by a relay without breaking
verification that already runs.

### 3.2 The guard

A pure, `internal` decision function in the companion, in the style of the
existing `validateManifest` / `extractDTag` seams:

```kotlin
/**
 * A manifest may be imported only if it is not older than the last one this
 * client successfully imported. `null` = nothing imported yet (first run).
 *
 * Equal timestamps are ACCEPTED on purpose — see §6.1: a partially completed
 * import must be resumable from the same manifest.
 */
internal fun isManifestAcceptable(
    manifest: BlossomManifest,
    lastAcceptedCreatedAt: Long?,
    nowSeconds: Long,
): Boolean = hasAcceptableTimestamps(manifest, nowSeconds) &&
    (lastAcceptedCreatedAt == null ||
        effectiveTimestamp(manifest) >= lastAcceptedCreatedAt)
```

Note the deliberate difference from the two existing counter-patterns, which
both reject on equality:

- `NostrProfileManager.kt:325-328` — `if (existing != null && eventCreatedAt <= existing) return@transaction`
- `CommunityClimbSubscriber.kt:971-975` — `advanceCursorIfNewer`, advances only on `>`

Those guard *idempotent upserts and a cursor*, where re-applying an equal value
is pointless. Here an equal timestamp must stay importable, because an import
that failed halfway must be completable on the next run from the very same
manifest.

### 3.3 Wiring

- **Read** the watermark from the instance's `prefs` under a new key
  `last_manifest_created_at` (`Long`, absent = first run). It lives in the same
  `SharedPreferences` the chunk hashes use, so the Kilter track
  (`DEFAULT_PREFS_NAME = "blossom_sync"`) and the MoonBoard track
  (`MOONBOARD_PREFS_NAME = "blossom_sync_moonboard"`, `:517-525`) get
  independent watermarks for free — do not introduce a shared/global key.
- **Check** immediately after `validateManifest` and before
  `getChangedChunks`. On rejection: log at warn with both timestamps and the
  d-tag, and return the "no update available" outcome the caller already has
  for "nothing changed". Do **not** throw — a rejected manifest is a normal,
  non-exceptional outcome.
- **Advance** the watermark only after **every** changed chunk has been
  imported and its hash saved. Never on fetch, never on partial success.
- `clearStoredHashes()` (`:357-359`) calls `prefs.edit().clear()`, which already
  removes the new key too — a deliberate reset therefore also resets the
  watermark, which is correct. Add a comment saying so, so the coupling is not
  broken by accident later.

## 4. Strings (en + de)

n/a (no UI strings). The guard is silent; rejection is logged only.

## 5. Acceptance criteria

Each maps to a JVM unit test on the pure seams (`effectiveTimestamp`,
`isManifestAcceptable`) unless marked otherwise.

1. A manifest whose effective timestamp is **older** than the stored watermark
   is rejected.
2. A manifest whose effective timestamp **equals** the stored watermark is
   accepted.
3. A manifest whose effective timestamp is **newer** is accepted.
4. With no stored watermark (first run), any manifest is accepted.
5. `effectiveTimestamp` returns `eventCreatedAt` when it is `> 0`, and falls
   back to the content `createdAt` when it is `0` — identical to the value
   `selectPreferredManifest` orders by, verified by asserting both call sites
   agree on the same fixture.
6. The watermark is written only after all changed chunks import successfully;
   an import that throws partway leaves the previously stored value unchanged.
7. Rejection is non-destructive: stored `chunk_sha256_*` values are unchanged,
   no exception reaches the caller, and the sync reports "no update" rather
   than a failure.
8. The Kilter and MoonBoard tracks maintain independent watermarks — advancing
   one does not affect the other.
9. After `clearStoredHashes()`, the next sync accepts any manifest (criterion 4
   applies again).
10. On-device check: on a device that already has a synced catalogue, upgrading
    to this build and running a sync still imports the current manifest and
    does not re-download unchanged chunks.

## 6. Edge cases

1. **Partial import, then retry.** Chunks A and B changed; A imports, B fails.
   The watermark must not advance, and the next run must accept the same
   manifest and finish B. This is why equality is accepted (§3.2).
2. **Import fails before any chunk is written.** Watermark unchanged; no state
   change at all.
3. **First install / cleared data.** No watermark → accept, exactly as today.
4. **Two manifests with the same effective timestamp but different chunk
   hashes.** The guard accepts both; ordering between them is already
   `selectPreferredManifest`'s job (NIP-01 tie-break on lower event id). The
   guard must not attempt its own tie-break.
5. **Existing installs upgrading to this build.** They have chunk hashes but no
   watermark. Treated as first run: the next manifest is accepted and seeds the
   watermark. No forced re-download, because `getChangedChunks` still skips
   unchanged hashes.
6. **Publisher clock going backwards.** Once shipped, a legitimate publish
   carrying an effective timestamp older than the previous one will be rejected
   by every client that saw the newer one — permanently, until a newer
   timestamp is published. The pipeline must therefore keep manifest timestamps
   monotonically increasing per d-tag. Out of scope to implement here, but it
   must be recorded in the pipeline's release notes; flag it in the PR
   description.
7. **All relays stale.** `selectPreferredManifest` picks the newest of the
   stale set; the guard then rejects it and the client keeps its current data.
   The user sees no update, which is the intended fail-safe outcome, not an
   error.
8. **Publisher clock far ahead.** A valid signature does not make
   `created_at` a safe persistent clock. An envelope or content timestamp at
   `now + 3600 s` is accepted for ordinary skew; `now + 3601 s` is rejected at
   relay ingestion, at the apply decision, and again before the atomic
   chunk-hash/watermark write. The community cursor independently fails open to
   an unseeded first request rather than persisting a future `since` value.
9. **Previously poisoned install.** Reading a stored watermark/cursor above the
   same ceiling repairs only that ordering value. Existing chunk hashes and
   catalogue data remain intact; a current signed manifest can immediately
   establish the replacement watermark and safe subscription seed.

## 7. Testing

- Add `androidApp/src/test/java/com/cruxcoach/android/data/blossom/BlossomManifestRollbackTest.kt`,
  in the style of the existing `BlossomManifestValidationTest.kt` — plain JUnit,
  no Android dependencies, fixtures built with the same local `manifestWith(...)`
  / `chunk(...)` helper shape.
- Cover acceptance criteria 1–5 directly against `isManifestAcceptable` and
  `effectiveTimestamp`, plus the exact future-skew boundary for both envelope
  and content timestamps and the fail-open community cursor seed.
- Criteria 6–9 concern persistence ordering. Cover whatever is reachable
  without an Android runtime by extracting the advance decision into a pure
  function if needed; anything that genuinely requires `SharedPreferences`
  should be stated as owed on-device verification rather than faked into a
  passing unit test.
- Criterion 10 is on-device and owed before the spec is marked done.
- Do not weaken or delete existing tests in
  `BlossomManifestValidationTest.kt` / `BlossomManifestRetryTest.kt`; the
  `selectPreferredManifest` refactor in §3.1 must leave their behaviour
  unchanged.

## 8. Open questions

None.
