---
status: backlog
---
# Feature Spec: Background-Safe Board Sync (backlog)

> **Status:** Backlog — captured 2026-05-13. No release target.
> The bug is a hard usability blocker on the very first launch
> (~28 s climbs-import phase blocks the OnboardingScreen on
> mid-range devices); a 0.1.5 or 0.2.0 candidate depending on
> user-report volume.
>
> **Depends on:**
> - None — self-contained Android-platform fix. The existing
>   `BoardSyncWorker` already speaks WorkManager + foreground
>   notifications; this spec extends that path to the manual /
>   onboarding-triggered sync too.
>
> **Relates to:**
> - FEAT-005 (Aurora JSON Import) — same OOM/Backgrounding
>   surface but a much shorter run (~5-30 s); the same lifecycle
>   guarantees would benefit both flows. Out of scope here.
> - FEAT-002 (Encrypted Backup) — uses WorkManager already, no
>   foreground requirement (typical run < 5 s). Reference for
>   "WorkManager only, no Service" pattern.

---

## 1. Overview

The first-launch board-database import is a multi-stage pipeline
that streams ~50–80 MB of chunked SQLite blobs, decompresses each,
imports ~270 k climbs + ~700 k climb-stat rows into SQLDelight,
then runs an index-rebuild + denormalisation pass. End-to-end on
a slower-eMMC mid-range Android device this takes **30–120
seconds** of continuous foreground work.

Today the entire pipeline runs inside an app-scoped
`CoroutineScope(SupervisorJob() + Dispatchers.IO)` created inside
`BoardSyncManager`. The scope is **not** backed by a foreground
service when the sync was started manually (OnboardingScreen,
BoardSyncScreen → "Synchronisieren", "Force resync"). The
`BoardSyncWorker` does wire a foreground notification, but only
the **periodic** daily/weekly tick goes through it — the manual
entry points bypass the Worker entirely and call
`BoardSyncManager.startBlossomSync` directly.

When the user backgrounds the app mid-sync (lock screen, switch
to messages, accept an incoming call), Android's process-lifecycle
rules apply with no foreground service to keep the app alive:

- On Doze + memory pressure: process killed within 5–30 s.
- On aggressive OEM stacks (Xiaomi MIUI, OnePlus, Samsung
  battery-saver): often within 2–10 s of leaving foreground.
- On clean Android (Pixel, AOSP): minutes — but no guarantee.

The killed process drops every coroutine, aborts the in-flight
SQLite transaction (rolled back at next open), and removes the
partial chunk files in `cacheDir` via the `finally` block of
`performBlossomSync`. On re-open the app starts cold, the
`BoardSyncManager` singleton is re-instantiated with
`isSyncing = false`, `alreadyImported = false`, and the user is
back on the OnboardingScreen's BOARD_SETUP step staring at
"Du musst die Kletter-Datenbank herunterladen". The earlier 30–90 s
of work was wasted; nothing is resumable.

From the user's perspective this presents as a crash even though
no exception was thrown — the in-flight sync silently vanished
and the app re-started at an earlier state.

### 1.1 Goals

- A manually-triggered board sync survives the user backgrounding
  the app for the full pipeline duration (~30–120 s).
- A user-visible progress notification while the sync runs in the
  background; tapping it returns to the BoardSyncScreen / the
  same OnboardingScreen step.
- Killing the app from Recents stops the sync cleanly (no
  zombie work, no leaked WakeLock).
- The exact same import pipeline runs from both the manual entry
  points and the periodic Worker — no code path divergence that
  drifts over time.
- Resume on cold-restart when feasible: chunks that finished
  downloading + verifying should not re-download.

### 1.2 Non-Goals

- A user-visible "background sync queue" UI. Out of scope; the
  existing BoardSyncScreen progress is sufficient for v1.
- Per-row resumable bulk-import (i.e. resuming an import that
  was killed mid-`INSERT` batch). The 28-second climbs-import
  pass either runs to completion or the transaction rolls back;
  attempting per-row checkpointing complicates the writer-lock
  hold time and the index-rebuild dance.
- Silent cold-start auto-resume without user-visible feedback.
  If the process dies, the user MUST see "sync interrupted —
  tap to resume" on next open rather than silently re-running
  it; cold-start auto-resume hides failure modes the user
  should know about (low storage, repeated OEM kills).
- Fixing other long-running flows that share the same
  backgrounding pattern (Aurora JSON Import, Kilter ascent
  upload). Same architectural shape, but each has its own UX
  + size profile; tackled separately.

---

## 2. Current behaviour & reproducer

**Reproducer:**

1. Fresh install on a mid-range Android device (e.g. Nokia 6.1,
   Android 15 LineageOS, or any device with < 4 GB RAM).
2. Walk through onboarding → BOARD_SETUP step.
3. Tap "Synchronisieren". The Blossom sync starts.
4. **Within 5 seconds**, switch to a heavy app (any browser
   loading a media page is enough).
5. Wait 15 seconds. Return to CruxCoach.

**Expected:** Sync continues in the background. Either it's
already done (progress bar gone, "synchronisiert" marker
shown) or progress is further along than when we left.

**Observed:** App cold-restarts. OnboardingScreen is back on
BOARD_SETUP step. Sync state is fresh — no progress. No error
message. The hint banner reads "Du musst die Kletter-Datenbank
herunterladen" as if the previous tap never happened.

**Logcat (when reproducible):** the `BoardSyncManager` coroutine
log lines stop abruptly mid-pipeline (e.g. between
`Chunk 7/12 verified` and `Chunk 8/12 starting`). No exception
trace; no `onDestroy`-style teardown log. The process simply
went away. ActivityManager kill reasons (`adb shell dumpsys
activity processes | grep CruxCoach`) will show
`isolated=false`, `cached=true`, `lastTrim=80` style entries
right before the process disappears.

**Frequency in production:** unknown — no telemetry. Anecdotal
reports cluster around the first-launch flow.

---

## 3. Root causes

### 3.1 No foreground service for manual syncs

`BoardSyncManager.startBlossomSync` launches the pipeline on its
own `Dispatchers.IO`-backed scope. There is no `Service` started,
no `setForeground` call, no notification. From Android's POV the
process is a regular backgrounded app and is killable.

The periodic `BoardSyncWorker` does call `setForeground` (with
`FOREGROUND_SERVICE_TYPE_DATA_SYNC` on Android 14+), but the
manual path doesn't go through the Worker.

### 3.2 Two parallel sync-trigger surfaces with drift potential

- **Periodic:** `BoardSyncWorker.doWork()` calls
  `syncManager.startBackgroundSync()` which calls
  `startBlossomSync()`. Foreground-protected by `setForeground`.
- **Manual / Onboarding:** UI calls `syncManager.startApiSync()`
  → `startBlossomSync()` directly on the scope. Not protected.

Both paths converge on `startBlossomSync`, which is good for
behaviour consistency, but they have completely different
lifecycle guarantees. A user can't tell from the UI which path
they're on. Drift risk: anyone adding a new manual entry point
will copy the unsafe pattern by default.

### 3.3 No resume on cold restart

When the process dies mid-sync, the partial chunk files in
`cacheDir/blossom-chunks/` are deleted by the `finally` block in
`performBlossomSync`. Stored chunk hashes
(`preferences.boardChunkHashes`) are advanced only after a
successful import phase, so they're consistent with what's
actually in the DB — but useless for resuming because the DB has
zero rows when the import phase aborted. The user has to redo the
full download + import.

The Aurora-equivalent (FEAT-005) has the same issue but with a
~5–30 s window so it's less painful.

---

## 4. Solution design

### 4.1 Tier 1 — Route manual syncs through an expedited WorkManager job

Wrap the manual sync trigger in a `OneTimeWorkRequest` for
`BoardSyncWorker`, with
`setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)`.
The Worker's existing `setForeground(createForegroundInfo())`
call already wires the DATA_SYNC foreground type + notification;
routing the manual path through it gets the lifecycle guarantee
for free.

#### Code shape

`BoardSyncWorker.kt`:
```kotlin
companion object {
    const val WORK_NAME_MANUAL = "board_sync_manual"

    fun enqueueManual(context: Context) {
        val request = OneTimeWorkRequestBuilder<BoardSyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME_MANUAL,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
```

`BoardSyncManager.startApiSync()`:
```kotlin
fun startApiSync() {
    if (_state.value.isSyncing) return
    checkNetwork()
    if (!_state.value.networkAvailable) { _state.update { it.copy(showNetworkDialog = true) }; return }
    if (!_state.value.wifiConnected)   { _state.update { it.copy(showWifiDialog = true)   }; return }
    BoardSyncWorker.enqueueManual(appContext)
    // Worker calls back into startBackgroundSync(); UI observes the same StateFlow.
}
```

Manager keeps owning the `StateFlow` (progress, errors,
isSyncing). The Worker is purely the lifecycle wrapper — no
business logic.

#### Why expedited

- Expedited jobs in Android 12+ run with elevated priority and
  bypass Doze, similar to a foreground service. Quota: ~30 s of
  CPU per 10 min for an app in foreground / ~10 s when
  backgrounded on default policy. Sufficient for the sync's
  CPU profile (most of the time is I/O-bound, not CPU).
- `RUN_AS_NON_EXPEDITED_WORK_REQUEST` fallback: if the quota is
  exhausted, the job downgrades to a regular WorkManager job
  instead of failing. Regular Workers still respect their
  constraints and run when the device wakes up — the sync
  becomes best-effort-background rather than
  guaranteed-foreground, but it doesn't disappear.
- Already covered by the manifest's
  `FOREGROUND_SERVICE_DATA_SYNC` permission.

### 4.2 Tier 2 — Chunk-level resume on cold restart

Persist a **chunk-import checkpoint** keyed on `chunk_sha256`:
once a chunk has been downloaded, verified, decompressed, and
its rows merged into the live SQLDelight DB, the checkpoint is
written. On cold restart the pipeline skips chunks whose
checkpoint matches the current manifest hash.

#### Schema (new migration in board DB)

```sql
CREATE TABLE board_sync_chunk_checkpoints (
    chunk_sha256 TEXT NOT NULL PRIMARY KEY,
    chunk_kind TEXT NOT NULL,           -- 'meta' / 'climbs' / 'stats'
    imported_at_ms INTEGER NOT NULL,
    manifest_created_at INTEGER NOT NULL,
    row_count_imported INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_chunk_checkpoint_manifest
    ON board_sync_chunk_checkpoints(manifest_created_at);
```

#### Pipeline change

`performBlossomSync`'s climbs / stats / meta import loops check
the checkpoint before downloading. Chunks whose `chunk_sha256`
is in the table for the current `manifest_created_at` are
skipped end-to-end (no download, no decompress, no insert).
Chunks whose hash isn't present (or whose `manifest_created_at`
is older — manifest rotated meanwhile) go through the full
pipeline; on success the checkpoint is written inside the same
transaction that wrote the rows.

A manifest rotation invalidates all old checkpoints implicitly
via the `manifest_created_at` filter — no explicit cleanup
needed. A periodic cleanup query can prune rows older than 30
days to bound the table size.

#### Edge cases for Tier 2

- **Schema-roll mid-resume**: a manifest with `schema_version =
  N+1` invalidates everything below N+1. The checkpoint table
  carries `manifest_created_at`, which advances on every
  schema-roll; the existing comparison handles it.
- **User cancels mid-import**: cancellation cleanly cancels the
  Worker's coroutine; the in-flight chunk's transaction rolls
  back, so its checkpoint never lands. Resume picks up from
  the last fully-committed chunk.
- **Partial chunk-rows-imported**: if a chunk-import transaction
  starts but the process dies before commit, SQLite rolls it
  back atomically. The checkpoint write is inside the
  transaction, so it never lands either. Resume re-imports
  the whole chunk; no orphaned partial rows.

### 4.3 Notification UX

The existing `AppNotificationService.buildSyncProgressNotification`
already covers the periodic path. For the manual path, the same
notification surfaces but with one additional behaviour:

- Tap action: `PendingIntent` to `MainActivity` with extra
  `route=board-sync` so the app navigates back to the sync screen
  (or stays on Onboarding's BOARD_SETUP step if onboarding isn't
  complete yet). Today the periodic-path notification has no tap
  action because there's no expectation to interact.
- "Cancel sync" action button (Android 7+ inline action): calls
  `WorkManager.cancelUniqueWork(WORK_NAME_MANUAL)`. The
  cooperative-cancellation semantics in the Manager's
  `performBlossomSync` already handle a cancelled coroutine
  cleanly — rolls back the in-flight transaction, restores prior
  state, deletes partial cache files.

Notification stays silent (no sound, no vibration) — the user
already knows a sync is in flight; the notification is just
status, not an alert.

### 4.4 Why not a Service directly?

A `Service` started via `startForegroundService` + `startForeground`
would also solve the lifecycle problem. We deliberately prefer
WorkManager because:

- **Constraints**: WorkManager already handles network /
  battery / charging constraints declaratively. A Service
  needs to re-implement these checks.
- **Retry policy**: WorkManager retries with exponential
  backoff on failure, persisted across reboots. A Service
  needs custom retry plumbing.
- **OEM-killer resilience**: WorkManager is the
  Google-blessed background-work API and gets explicit
  whitelisting in most OEM battery-saver stacks. A custom
  Service often gets killed more aggressively on Xiaomi /
  Huawei / OnePlus.
- **Single code path**: the periodic Worker uses WorkManager;
  using it for manual too means one lifecycle, one notification
  path, one tested behaviour.

The trade-off: WorkManager has measurable enqueue latency
(~50–500 ms) on cold-start. For a 30–120 s job this is
imperceptible. For sub-second background tasks we'd reach for
a Service or a plain coroutine — neither applies here.

---

## 5. Edge cases & open questions

### 5.1 Expedited quota exhausted

If the user triggers many sync attempts rapidly (e.g. tap-spam,
or three failed runs in a row), the expedited quota can fall to
zero. The `RUN_AS_NON_EXPEDITED_WORK_REQUEST` fallback degrades
gracefully — the next sync runs as a regular Worker. UX: the
progress notification appears but is lower-priority; sync may
defer until the device is charging or on unmetered network
(constraints permit). Acceptable degradation for an edge case
already gated by the "no isSyncing" claim.

### 5.2 OEM aggressive killers

Even WorkManager isn't immune to OEM-side "battery optimisation"
that puts the app on doze-whitelist exclusions. Mitigations:

- A first-launch dialog ("To finish your one-time setup, please
  keep CruxCoach in the foreground for ~1 minute") nudges users
  through the worst-affected vendor stack on the very first
  install where the sync is biggest. Spec'd here as
  user-research-pending; not blocking the v1 implementation.
- The Tier 2 resume path is the durable fix: a killed sync
  resumes from the last finished chunk, so even an OEM that
  kills every 30 s eventually completes the sync over multiple
  app-foreground sessions.

### 5.3 Doze + idle maintenance window

WorkManager respects Doze. An expedited job ignores Doze but is
limited to ~10 s of CPU per 24 h window when backgrounded. If
the sync runs longer (which it will — most of the time is
I/O), the job downgrades to non-expedited and waits for the
next maintenance window. This is invisible to the user except
that the notification stalls — the resume-from-checkpoint path
covers this when the device wakes up.

### 5.4 Re-triggering a sync that's already in-flight

`enqueueUniqueWork(WORK_NAME_MANUAL, ExistingWorkPolicy.KEEP, …)`
preserves the existing job. The UI's `isSyncing` guard remains
the primary protection; the WorkManager-side `KEEP` is the
backstop.

### 5.5 Process kill during checkpoint-write

The checkpoint INSERT lives inside the same SQLite transaction
as the chunk-row inserts. Either both commit or both roll back.
No partial-checkpoint state possible.

### 5.6 Manifest changed mid-sync

Long syncs (slow connection, schema-roll boundary) can span a
manifest rotation server-side. The `fetchManifest()` call at the
top of `performBlossomSync` snapshots the manifest once; the
sync runs against that snapshot. If the manifest rotates during
the sync, the next sync attempt will see the new manifest and
the unchanged-chunk checkpoints from the old manifest are
invalidated by the `manifest_created_at` filter. Acceptable:
the user's data is consistent with whatever the manifest said
at the start; the next sync converges.

### 5.7 Notification when onboarding hasn't completed

The first-launch sync runs before `onboardingCompleted = true`.
The notification's tap-target must route to the OnboardingScreen
(BOARD_SETUP step), not the BoardSyncScreen which doesn't exist
in the onboarding flow. Plumb via an `onboardingActive` extra
on the PendingIntent; MainActivity reads it and chooses the
target screen accordingly.

---

## 6. Rollout / migration path

### 6.1 Phasing

**Phase A — Tier 1 (Foreground-protected manual sync).** Self-
contained, no schema, no data migration. Routes the manual
trigger through `BoardSyncWorker.enqueueManual`. Ship as a
`fix(sync):` in a patch release. Behaviour difference visible
to users: a notification appears during sync; backgrounding no
longer drops the sync. No DB changes.

**Phase B — Tier 2 (Chunk-level resume).** Schema migration +
checkpoint-write integration. Bigger code touch, needs
checkpoint-cleanup heuristics. Independent of Phase A — Phase B
benefits from Phase A's stability but doesn't depend on it.
Could ship in a later minor release once Phase A is proven
stable in production.

### 6.2 Backwards compatibility

- Phase A is purely additive to runtime behaviour. No schema,
  no preferences, no API. Old clients (no Phase A) continue to
  work as today; the bug they have is the bug this spec
  describes.
- Phase B adds a schema migration in the board DB. Migration is
  additive — new table + new index. Old clients without the new
  table will simply re-run the full pipeline (status quo).

### 6.3 Telemetry / verification post-rollout

- New PERF marker: `BoardSyncWorker.foregroundLifecycle`
  (start, end, duration). Compared to pre-fix marker
  `BoardSyncManager.scope.start/end` durations, this should
  show fewer truncated traces.
- Maestro flow that backgrounds the app mid-sync and asserts
  the notification appears + sync completes (see Testing).

---

## 7. Testing strategy

### 7.1 JVM (Robolectric + WorkManager test API)

- New `BoardSyncWorker.enqueueManual` test: builds a request
  with the expected constraints, expedited policy, and uses
  `WorkManager.getInstance(context).enqueueUniqueWork` with
  `KEEP`. Use `WorkManagerTestInitHelper` to drive the worker
  inline + assert it calls `setForeground` exactly once.
- `BoardSyncManager.startApiSync` test: with a mock
  `WorkManager`, verifies the dispatch path delegates to the
  Worker rather than the direct `scope.launch`.

### 7.2 Maestro (real-device flow)

- New `flows/board-sync-background-survival.yaml`:
  1. Reset app data (fresh install state).
  2. Tap through onboarding to BOARD_SETUP.
  3. Tap "Synchronisieren".
  4. Press Home (background app) immediately.
  5. Wait 30 s.
  6. Re-launch the app.
  7. Assert one of: (a) sync has completed and the user is on
     the BOARD_BROWSER, OR (b) sync is still in flight with a
     non-zero progress and the foreground notification is
     visible.
  8. Fail if onboarding is still on BOARD_SETUP with zero
     progress (regression: process was killed).
- Existing PERF-marker contract holds: every step still emits
  the timing markers the wrapper asserts on.

### 7.3 Manual regression checklist (release qual)

- Cold-install on a budget device → sync once → kill from
  Recents mid-sync → re-open → sync resumes from checkpoint
  (Phase B) or restarts cleanly (Phase A).
- Cold-install on a Xiaomi / OnePlus device → sync once →
  background → verify completion. If this fails consistently,
  the OEM-killer mitigation (§5.2) becomes a release blocker.
- Verify periodic-sync path: trigger a daily-sync tick via
  WorkManager test API; the existing Worker code stays
  unchanged so no behaviour regression expected.

---

## 8. Estimated complexity

Phase A:
- ~3 files: `BoardSyncWorker.kt`, `BoardSyncManager.kt`,
  `MainActivity.kt` (route-from-notification).
- ~80 lines of new code + ~20 lines moved.
- 1 new Maestro flow + ~30 lines of JVM test glue.
- Effort: ~1 day.

Phase B:
- ~6 files: new migration `.sqm` in board DB, `Board.sq`
  appends, `BoardRepository` + Impl, `BlossomSyncManager.kt`
  pipeline chunk-skip logic, one new test.
- ~150 lines new code.
- Effort: ~1.5 days.

Combined ~2.5 dev-days for a fully-baked fix. Phase A alone is
enough to close the user-reported bug; Phase B is a quality-of-
life improvement on top.
