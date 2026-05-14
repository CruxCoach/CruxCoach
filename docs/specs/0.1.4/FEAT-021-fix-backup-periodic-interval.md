---
status: 0.1.4
---
# Feature Spec: Fix Daily/Weekly Auto-Backup Interval (0.1.4)

> **Status:** Shipped in 0.1.4 — captured + fixed 2026-05-14.
> Bug. The daily / weekly automatic backup interval that the
> user picks in Settings → Backup was not honoured across app
> restarts: the periodic `BackupSyncWorker` got re-scheduled
> on every cold start using the **board-sync** interval, not
> the backup-specific one — and any user who had board-sync
> on MANUAL silently lost their periodic backup the next time
> the app restarted.
>
> **Depends on:**
> - None. Self-contained DataStore + Worker plumbing fix.
>
> **Relates to:**
> - FEAT-002 (Encrypted Backup) — this is a follow-up bug in
>   the same feature surface.
> - FEAT-017 (Background Board Sync) — both touch
>   WorkManager + sync-interval prefs, but the cross-pollution
>   between the two interval semantics is exactly the bug
>   this spec fixes.

---

## 1. Overview

Settings → Backup lets the user pick a backup cadence:
**Daily**, **Weekly**, or **Manual**. The expectation is that
**Daily** means "back up once a day automatically", and
**Weekly** means "once a week automatically".

The expectation breaks on every cold app-start. The periodic
work either runs at the wrong cadence, stops firing entirely,
or both — depending on what the user has set for an entirely
unrelated preference (board-sync interval).

Symptom variants observed / reported:

- User picks "Daily" in Settings → Backup. First day a backup
  fires. App is killed by the OS / device reboot. Next time
  the user opens the app, the periodic backup has been silently
  replaced by whatever the board-sync interval was. If
  board-sync is MANUAL, the backup work is **cancelled**.
- User picks "Weekly" backup + has board-sync on "Daily".
  Backups actually run daily (board-sync's cadence wins). The
  user sees more backup activity than they asked for.
- User picks "Daily" backup + has board-sync on "Weekly".
  Backups happen only weekly.

### 1.1 Goals

- The backup interval the user picks in Settings → Backup is
  honoured across app restarts, app updates, and device
  reboots.
- The backup interval is independent of the board-sync
  interval. The two are wholly unrelated user-facing options
  that happen to share an enum type.
- Existing users who have a backup interval set in their
  in-memory state on the day of the upgrade migrate cleanly
  (the upgrade preserves "what they thought they had
  selected", reading from the existing partial-state surface
  if available, defaulting to DAILY otherwise — see §4.4).
- A diagnostic on app-start logs the **resolved** backup
  interval (`event=backup_scheduled interval=X reason=Y`)
  so post-fix verification doesn't depend on installing
  WorkManager test infrastructure.

### 1.2 Non-Goals

- Adding new backup cadence options (hourly, every 6 h, etc.).
  Out of scope; the existing three-state enum is enough for
  the user-facing problem.
- Fixing Amber's background-signing limitation
  (`BackupSyncWorker` already documents this in its kdoc: if
  the user uses Amber without "always approve", periodic
  backups will fail to sign and bounce on `Result.retry()`).
  Separate issue; tracked in §3.3 as a secondary observed
  failure mode but not within scope here.
- Adding OEM-killer mitigation (Xiaomi / Huawei deferring
  WorkManager periodic jobs). Real, but a general WorkManager
  problem; mitigation lives at the platform level (see
  FEAT-017's §5.2 for the same surface).
- Migrating to a Foreground-Service-backed runner. Backup
  takes a few seconds; the WorkManager fit is correct.

---

## 2. Reproducer

1. Fresh install. Walk through onboarding. Enable backup with
   interval = **Weekly**.
2. In Settings → Sync (board-sync), select **Manual** for the
   board-sync interval.
3. Force-stop the app from system settings (or wait for the
   OS to kill the process).
4. Cold-start the app.
5. Inspect WorkManager:
   ```
   adb shell dumpsys jobscheduler | grep -A 3 cruxcoach
   ```
   Or check the periodic-work registry via the in-app
   diagnostic if available.

**Observed:** The periodic work named `backup_sync_periodic`
is **cancelled** — no entry in the scheduler. The
"Letztes Backup" timestamp in Settings will never advance
past whatever ran before the restart.

**Expected:** The periodic work persists at the user's chosen
weekly cadence.

A second reproducer (cross-pollution direction):

1. Set backup = Weekly. Set board-sync = Daily.
2. Force-stop, cold-start.
3. Observe: the periodic backup work is scheduled at a
   24-hour interval, not 168.

---

## 3. Root cause

### 3.1 The backup interval is in-memory only

`BackupSettingsViewModel.setInterval(interval)`:

```kotlin
fun setInterval(interval: SyncInterval) {
    viewModelScope.launch {
        _state.update { it.copy(interval = interval) }
        if (_state.value.backupEnabled) {
            BackupSyncWorker.schedule(appContext, enabled = true, interval = interval)
        }
    }
}
```

The function updates the local `StateFlow` state and calls
`BackupSyncWorker.schedule`. It **never persists the choice
to DataStore**. `BackupPreferences` has fields for
`backupEnabled` and `backupOnboardingSeen`, but no field for
the chosen interval. The state survives only as long as the
ViewModel — which is destroyed when the user backgrounds the
app long enough for the system to reclaim it, or kills the app
from Recents, or reboots the device.

When the user returns to Settings → Backup the next time, the
ViewModel is re-instantiated with the **default**
`SyncInterval.DAILY` and the toggle UI shows "Daily" again —
even if the user originally picked Weekly. The visible state
is wrong; the underlying scheduler has no idea what cadence
was meant.

### 3.2 `CruxCoachApp.onCreate` reads the wrong interval source

On cold start `CruxCoachApp.onCreate` reconciles the periodic
worker with persisted prefs:

```kotlin
val interval = runCatching { userPreferences.syncInterval.first() }
    .getOrDefault(SyncInterval.MANUAL)
runCatching {
    BackupSyncWorker.schedule(
        this@CruxCoachApp,
        enabled = backupEnabled,
        interval = interval,
    )
}
```

`userPreferences.syncInterval` is the **board-sync** interval
preference. There is no separate "backup interval" pref to
read, so the same value is used for both workers. When the
user has board-sync on MANUAL — the most common choice for
people who don't want their phone churning on a daily download
of a database that barely changes — `BackupSyncWorker.schedule`
is called with `interval = MANUAL`. The schedule function
then short-circuits:

```kotlin
if (!enabled || interval == SyncInterval.MANUAL) {
    wm.cancelUniqueWork(WORK_NAME_PERIODIC)
    return
}
```

→ the periodic backup work is **cancelled on every cold start**
whenever board-sync is MANUAL.

### 3.3 Secondary suspects (not the primary bug, but in scope)

While investigating §3.1 and §3.2, two adjacent failure modes
show up and are worth handling in the same fix to avoid
shipping a second patch a release later:

- **Amber background-signing.** Documented in
  `BackupSyncWorker.kt`'s kdoc: periodic backups need a
  signer that doesn't require user confirmation, which
  excludes Amber unless the user has set "always approve".
  This is a real failure mode but it's user-controllable and
  the worker correctly bounces on `Result.retry()` with
  exponential backoff. The right surface is a small in-app
  diagnostic — "letztes Backup: nie erfolgreich, Grund:
  Signer benötigt Bestätigung" — not a code change. Out of
  scope here; track in a follow-up.
- **Initial-delay**. `PeriodicWorkRequestBuilder` defaults
  to running the first invocation within a flex window of
  the period after enqueue, not immediately. A user who
  enables backup on a Tuesday at 19:00 might not see the
  first periodic run until the next day. Acceptable: that's
  WorkManager's intended behaviour and the manual "Jetzt
  sichern" button handles the immediate-feedback case.

---

## 4. Solution design

### 4.1 Persist the backup interval in `BackupPreferences`

Add a new DataStore key:

```kotlin
// BackupPreferences.kt
private val BACKUP_INTERVAL = stringPreferencesKey("backup_interval")

val backupInterval: Flow<SyncInterval> = dataStore.data.map { prefs ->
    prefs[BACKUP_INTERVAL]?.let { SyncInterval.entries.firstOrNull { e -> e.name == it } }
        ?: SyncInterval.DAILY
}

suspend fun setBackupInterval(interval: SyncInterval) {
    dataStore.edit { it[BACKUP_INTERVAL] = interval.name }
}
```

Persist by-name (string), not by ordinal — adding /
re-ordering enum values later doesn't silently migrate
existing rows to a different cadence. Default `DAILY` is the
most user-friendly fallback for an unset key (matches what
the existing ViewModel defaults to today, so users who
upgrade with no key set don't see a behaviour change).

### 4.2 Update `BackupSettingsViewModel.setInterval` to persist

```kotlin
fun setInterval(interval: SyncInterval) {
    viewModelScope.launch {
        preferences.setBackupInterval(interval)
        _state.update { it.copy(interval = interval) }
        if (_state.value.backupEnabled) {
            BackupSyncWorker.schedule(
                appContext,
                enabled = true,
                interval = interval,
            )
        }
    }
}
```

And in the VM's `init` block: replace the in-memory default
with a `preferences.backupInterval` collector so the UI
shows the persisted value on every open.

### 4.3 Fix `CruxCoachApp.onCreate` to read the right pref

```kotlin
runCatching {
    val backupPrefs = backupPreferences.get()
    val backupEnabled = backupPrefs.isBackupEnabled() && backupPrefs.isBackupFeatureEnabled()
    val backupInterval = backupPrefs.backupInterval.first()      // ← was: userPreferences.syncInterval
    BackupSyncWorker.schedule(
        this@CruxCoachApp,
        enabled = backupEnabled,
        interval = backupInterval,
    )
}
```

This is the load-bearing line. Combined with §4.1+§4.2, the
periodic worker is now reconciled against the **backup**
interval the user actually picked, not the board-sync
interval.

### 4.4 Migration for existing installs

Users upgrading to the fixed build have no `backup_interval`
key in DataStore. Two situations:

- **User has backup ENABLED.** The Flow default returns
  `DAILY`. On the next app start the worker is re-scheduled
  at DAILY. If the user previously had Weekly mentally
  selected (the in-memory UI showed it at one point, but the
  user never re-opened Settings to re-pick after the prior
  app start cleared it), they upgrade to Daily — slightly
  more frequent than they wanted, but still "the worker is
  running", which is a strict improvement over the cancelled
  state they were in before this fix landed.
- **User has backup DISABLED.** No change — `enabled=false`
  short-circuits before the interval matters.

A migration helper that runs once on first launch after the
fix is **not** necessary — the DAILY default lands the right
behaviour for everyone who didn't actively pick Weekly. For
users who did want Weekly, they re-pick it in Settings and
the new persistence path holds it correctly going forward.

If we want to be extra-friendly, the migration could read
`userPreferences.syncInterval` once on the very first launch
of the fixed build, **only if** the user has backup enabled
AND has board-sync on something other than MANUAL — that
covers the niche case "user picked the same cadence for both
manually". Not load-bearing; nice-to-have.

### 4.5 Tier 2 — Diagnostics improvement (optional)

Add a small "Last backup attempt" line to the BackupSettings
section with the timestamp + outcome (`success`, `retry`,
`failure` reason). Cheap to surface; reads existing
`BackupPreferences.lastBackupAt` (which already exists) plus
a new optional `lastBackupOutcome` field. Out of scope for
the primary fix but worth filing as a companion to make
post-fix verification visible to users.

---

## 5. Edge cases

### 5.1 User changes board-sync interval after the fix

No effect on backups. The two prefs are now fully
decoupled. Verified by the new diagnostic log line:

```
event=backup_scheduled interval=WEEKLY reason=app_start
```

vs.

```
event=board_sync_scheduled interval=MANUAL reason=app_start
```

### 5.2 User toggles backup off then on quickly

`setBackupEnabled(false)` calls schedule with `enabled=false`
→ work cancelled. The next `setBackupEnabled(true)` calls
schedule with the persisted interval. Currently the toggle
calls `schedule(appContext, enabled, _state.value.interval)`
— after the fix, `_state.value.interval` is itself sourced
from the persisted pref, so this path is correct.

### 5.3 Concurrent writes to `backup_interval`

DataStore serialises writes. The viewModelScope launch in
`setInterval` is the only writer; no contention.

### 5.4 First-install with backup-onboarding-seen but no interval yet

Default falls through to DAILY. Matches what the UI was
showing in the old in-memory default. No surprise.

### 5.5 What if board-sync interval was already used as a proxy on purpose

The code comment near the `userPreferences.syncInterval` read
doesn't justify the cross-pollination — it reads as a
mistake, not a design choice. The fix is unambiguous; no
shared-meaning argument to refute.

### 5.6 Migration test: WEEKLY → DAILY on upgrade

A user who actively picked WEEKLY in the broken state, killed
the app, and never re-opened settings, will land on DAILY
after the upgrade. UX-wise this is fine — the user notices
the toggle and re-picks WEEKLY, which now persists. The fix
doesn't promise to recover information that was never
persisted in the first place.

---

## 6. Testing

### 6.1 JVM

- `BackupPreferences.backupInterval` round-trip
  (write → snapshot → read).
- `BackupSettingsViewModel.setInterval` test: verifies that
  the new helper writes the DataStore key AND emits the
  state update AND calls `BackupSyncWorker.schedule` exactly
  once.
- `CruxCoachApp.onCreate` reconciliation simulation (using
  Robolectric + a fake `BackupPreferences`): asserts the
  worker is scheduled with the *backup* interval, not the
  *board-sync* interval. The board-sync interval is
  intentionally mismatched in the test to catch any
  re-introduction of the bug.

### 6.2 Manual regression

- Reproducer from §2: fresh install, pick WEEKLY backup +
  MANUAL board-sync, force-stop, cold-start. Verify the
  `event=backup_scheduled interval=WEEKLY` log line appears.
  Verify via `adb shell dumpsys jobscheduler` (or via
  WorkManager Inspector in Android Studio if attached) that
  `backup_sync_periodic` exists with a 168-hour repeat.
- Cross-pollution direction: pick DAILY backup + WEEKLY
  board-sync, force-stop, cold-start. Verify backup is at
  24 h repeat.

### 6.3 Maestro (optional)

A flow that toggles backup interval on/off then asserts the
in-app "Letztes Backup" label updates after the next periodic
run is not realistically writable — the flow can't wait 24 h.
The diagnostic log assertion is the post-fix verification
path; Maestro coverage adds little.

---

## 7. Estimated complexity

- 3 files touched: `BackupPreferences.kt` (add key + flow +
  setter), `BackupSettingsViewModel.kt` (persist on
  setInterval, collect the persisted flow), `CruxCoachApp.kt`
  (read backup interval, not sync interval).
- ~50 lines of new code.
- ~80 lines of test glue (3 JVM tests).
- Effort: ~0.5 day. Bug surface is tiny; the spec is long
  because the cross-pollination semantics need to be unambiguous
  for whoever picks this up.
