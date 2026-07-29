---
status: backlog
---
# Feature Spec: Opt-In Automatic Update Install (backlog)

> **Status:** Backlog — captured 2026-05-13. No release target.
> The updater pipeline already does the hard parts (check →
> download → verify → notify). The remaining gap is the final
> "install" tap, which forces every user to interact with a
> system dialog every release cycle. Likely candidate for a
> 0.1.x patch once the user-facing toggle copy is settled and
> the silent-install permission flow is reviewed.
>
> **Depends on:**
> - The existing `UpdaterRepository` / `UpdaterCoordinator` /
>   `ApkInstaller` pipeline (already shipped). This spec adds an
>   opt-in branch at the very end of that pipeline; everything
>   upstream stays as-is.
>
> **Relates to:**
> - FEAT-002 (Encrypted Backup) — independent. A user with auto-
>   update on may experience an unexpected app-restart; the
>   backup worker's lifecycle is unaffected because backups run
>   as periodic WorkManager jobs, not in-process.
> - FEAT-017 (Background Board Sync) — both touch process
>   lifecycle, but in opposite directions. FEAT-017 keeps the
>   process alive longer; FEAT-018 may end the process sooner
>   (system installs the new APK + restarts the app). The two
>   must not deadlock: a board sync in flight blocks auto-
>   install until the sync completes or is cancelled (§5.4).

---

## 1. Overview

CruxCoach ships its own updater (no Play Store, no Aurora /
Zapstore intermediary): a `OneTimeWorkRequest` checks Codeberg
releases for a newer `versionName`, downloads the APK, verifies
the SHA-256 against the published sidecar file, pins the signing
certificate via TOFU, and posts a notification. The user taps
"Installieren" in the notification (or in Settings), the system
shows a "Update CruxCoach?" dialog, the user taps "Install" again,
and the new version replaces the running one.

The user-visible work is **two taps per release**. Aurora /
F-Droid users are conditioned to that pattern; CruxCoach users
report it as friction — every minor release breaks the workflow
of users who'd just like the new code to arrive in the
background and have no opinion about which release they're on.

This spec adds an **opt-in toggle in Settings** that wires the
"Download done + signature pinned + integrity verified" outcome
directly into `ApkInstaller.install()`, with no notification-tap
and (on Android 12+) no system confirm-dialog. Default is off —
the existing notification + tap flow stays the default behaviour
for every user who doesn't deliberately opt in.

### 1.1 Goals

- Settings toggle "Updates automatisch installieren". Default
  off. When on, a verified download triggers `ApkInstaller.install`
  immediately instead of posting a "Tap to install" notification.
- On Android 12+: the system confirm-dialog is suppressed via
  `PackageInstaller.SessionParams.setRequireUserAction
  (USER_ACTION_NOT_REQUIRED)` plus the matching
  `UPDATE_PACKAGES_WITHOUT_USER_ACTION` runtime permission. The
  install becomes truly silent (an "installing update…"
  notification surfaces during the few seconds the system takes
  to swap the APK, then the app restarts).
- On Android 11 and older: the system confirm-dialog cannot be
  suppressed at the platform level; the silent-install permission
  doesn't exist. The toggle still works, but the user sees the
  system dialog one final time per install. (Trade-off
  documented; not a v1 blocker.)
- The toggle is reachable from Settings → Updates and respects
  the install-source gate (`InstallSourceGate.selfUpdateAllowed`)
  — if self-updates are hard-disabled, the toggle is hidden.
- Auto-install never runs while a long-running in-app workflow
  is active: board sync, Aurora import, profile-image upload,
  ascent publish in flight, BLE-connected climbing session.
  The update waits for the next idle window.
- A user-visible "Updating to vX.Y.Z…" notification surfaces
  for the few seconds before the system replaces the process,
  so a user staring at the screen knows what's happening when
  the app suddenly restarts.

### 1.2 Non-Goals

- Auto-update for users on Play Store / Aurora / F-Droid. Out
  of scope — those store-distributed builds already have the
  store's auto-update path; CruxCoach's in-app updater is the
  Codeberg-distributed channel only. `InstallSourceGate`
  already excludes the others.
- Background update without any user-visible signal. A
  silent-on-Android-12+ install still posts a transient
  notification, so the user is never surprised by a sudden
  app restart with no explanation.
- Auto-update on metered networks. The download step already
  honours the network constraint; this spec doesn't change
  that. Auto-install only runs after a download completed
  under the user's existing constraints.
- Custom rollback. If an auto-installed release crashes on
  start, the user lands in the OS-level "App keeps stopping"
  loop — same as a manual install would. Rollback to the
  previous APK is a separate feature with significant
  complexity (versioned APK retention, crash-detection
  heuristic, user-confirmed rollback dialog).
- Pre-release / nightly builds. The existing channel selector
  already gates this; this spec adds no new channel logic.

---

## 2. What's already in place

The full pre-install pipeline already exists:

- **`UpdateCheckWorker` + `UpdaterCoordinator`** — opportunistic
  + 24 h periodic check, ProcessLifecycle-aware, network-callback
  driven. Runs `UpdateChecker.checkNow(trigger)` → outcomes
  surface to `UpdaterRepository`.
- **`UpdaterRepository.checkNow`** — on a positive check it
  downloads the APK via the existing `OkHttpClient` pipeline,
  shows progress notifications, verifies sha256 + signing cert
  pin (`IntegrityVerifier` + `UpdaterPinStore`), and ends in
  one of:
  - `notifier.showReadyToInstall(info)` — green-light: download
    + verify succeeded, ready for the user to tap "Installieren".
  - `notifier.showCertMismatch(info)` — signing cert doesn't
    match the pinned one (refuses the install; user must investigate).
  - `notifier.showDownloadError(info, reason)` — network or
    file-system failure (re-tries on the next trigger).
- **`UpdaterRepository.installNow()`** — wraps
  `ApkInstaller.install(file)` (`PackageInstaller` MODE_FULL_INSTALL).
  Triggered today by `UpdaterActionReceiver` from the
  notification "Installieren" action, and from
  `SettingsUpdaterScreen`'s "Jetzt installieren" button.
- **`InstallSourceGate.selfUpdateAllowed()`** — short-circuits
  the entire updater when the app wasn't installed via the
  Codeberg path (Play / Aurora / F-Droid users get nothing).
- **`UpdaterPreferences`** (DataStore-backed) — already persists
  every relevant updater state field across the existing
  pipeline; the new `autoApplyEnabled` flag fits in without
  ceremony.

The "ready to install" state already includes a fully-verified
APK file on disk. **All this spec needs to do is wire that
state directly into `installNow()` when the user has opted in.**

---

## 3. Solution design

### 3.1 Tier 1 — Opt-in auto-install (no platform-level silent install)

Adds an `autoApplyEnabled: Boolean` to `UpdaterPreferences`,
defaulting to `false`. The UI toggle lives in Settings →
Updates (already a section in `SettingsUpdaterScreen`).

In `UpdaterRepository.checkNow`, the `Pinned` branch that
currently calls `notifier.showReadyToInstall(info)` gains an
opt-in fork:

```kotlin
// After integrity verify passes:
when (verification) {
    is Pinned -> {
        if (prefs.snapshot().autoApplyEnabled && safeToInstallNow()) {
            notifier.showInstalling(info)   // transient banner
            installNow()                    // existing entry point
        } else {
            notifier.showReadyToInstall(info)
        }
    }
    is CertMismatch -> notifier.showCertMismatch(info)
}
```

`safeToInstallNow()` (new helper, see §3.3) checks for active
in-app work that an install would interrupt. On Android 11 and
older the system confirm-dialog still appears — the user is
spared the notification tap, but the system dialog is
unavoidable at the platform level.

### 3.2 Tier 2 — Silent install on Android 12+

Two changes on top of Tier 1:

**Manifest:**
```xml
<uses-permission android:name="android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION" />
```

The permission is **install-time** (auto-granted) and gates
the ability to set `USER_ACTION_NOT_REQUIRED` on a session.
It only applies when the new APK has the same signing
certificate as the installed app — exactly our case for
self-updates from Codeberg.

**`ApkInstaller.install`:**
```kotlin
val params = PackageInstaller.SessionParams(MODE_FULL_INSTALL)
params.setAppPackageName(context.packageName)
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S /* 31 */
    && autoApplyEnabled) {
    params.setRequireUserAction(SessionParams.USER_ACTION_NOT_REQUIRED)
}
```

`autoApplyEnabled` plumbs in via constructor or a setter. When
the flag is on AND the platform is API 31+, the install runs
without the system dialog — the user sees the "Updating to
vX.Y.Z…" notification, the app restarts a few seconds later,
new version is live.

### 3.3 In-flight workflow protection — `safeToInstallNow()`

A naive "install whenever ready" path would interrupt:
- An in-progress board sync (FEAT-017's pipeline — would
  abort the chunk import).
- An in-progress Aurora import (FEAT-005).
- A ProfileImage upload to Blossom.
- A BLE-active climbing session (`BoardBleConnection` writeMutex
  held).
- A foreground Compose screen actively in use.

`safeToInstallNow()` returns `false` if **any** of:

- `boardSyncManager.state.value.isSyncing == true`
- `auroraMigrationViewModel.state.value.isImporting == true`
- `nostrProfileViewModel.state.value.pictureUploadInFlight == true`
- `bleConnection.state.value.isConnected == true`
- The activity-lifecycle observer reports any Activity in
  `RESUMED` state (i.e. the app is currently in the foreground
  with a screen visible — defer until the user backgrounds the
  app voluntarily, so a sudden restart doesn't yank their
  in-progress UI).

When it returns false the install is **deferred, not abandoned**:
the verified APK stays on disk, and the next trigger that
re-enters `checkNow` retries the `safeToInstallNow` check. On
the next idle window the install runs.

A small back-off (e.g. 5 minutes) prevents tight-loop
re-evaluation — the install will retry on the next opportunistic
trigger (network-available, app-foreground), which is more than
enough natural pacing.

### 3.4 "Installing update…" UX

When `installNow()` fires, post a notification:

> **CruxCoach wird aktualisiert…**
> Version X.Y.Z wird installiert. Die App startet gleich neu.

The notification is `ONGOING`, `IMPORTANCE_LOW` (no sound),
sticky for the few seconds before Android replaces the APK.
The system kills the app's process during the swap; the
notification disappears with the process and the new APK starts
fresh. The user has a continuous visual thread from
"download done" to "new version running".

If the install fails (rare — `PackageInstaller` returned
`STATUS_FAILURE_*`), the notification updates to:

> **Update fehlgeschlagen**
> Version X.Y.Z konnte nicht installiert werden. Tippe für
> Details.

… and the next opportunistic trigger retries (Tier 1) or surfaces
the manual "Installieren" notification (Tier 2 fallback when
silent-install isn't available).

### 3.5 Settings UI

`SettingsUpdaterScreen` gains one new row near the existing
"Pre-release-Updates erhalten" toggle:

```
☐  Updates automatisch installieren
    Verifizierte Updates werden im Hintergrund eingespielt.
    Die App startet danach neu.
```

Adjacent help-text (collapsible / info-icon):

> Funktioniert nur, wenn du CruxCoach von Codeberg installiert
> hast. Updates werden weiter gegen die festgepinnte
> Signatur geprüft (TOFU) — eine veränderte Signatur stoppt
> den Auto-Install. Auf Android 11 und älter zeigt das System
> noch einen einmaligen Bestätigungs-Dialog.

The toggle is **hidden** when `InstallSourceGate.selfUpdateAllowed()`
returns false (Play/Aurora/F-Droid users).

---

## 4. Security & trust implications

The auto-install path **does not lower the trust bar**. The
checks that already happen on a manual install run on the
auto path too:

- **Codeberg release fetch** — TLS, pinned via the OkHttp
  default trust store.
- **SHA-256 verification** — `IntegrityVerifier` reads the
  sidecar `.sha256` file and compares against the downloaded
  APK bytes.
- **Signing-cert TOFU pin** — `UpdaterPinStore` stores the
  first-observed signing cert and refuses to install any APK
  whose cert doesn't match. A compromised Codeberg release
  page can't swap in a different cert without the install
  refusing.
- **`InstallSourceGate`** — only Codeberg-installed apps can
  self-update at all.

What changes:

- The user does not get a "yes / no" confirm dialog before
  the install. The pin is the last line of defence; if it
  rejects, the install never starts. If it accepts, the user
  has already (by virtue of running CruxCoach today) trusted
  the cert that's now being matched.
- Auto-install does not bypass the network policy — a metered-
  data user with their constraint still has to be on
  unmetered for the download to start.

What the user gives up by opting in:

- **The chance to read the release notes before installing.**
  Mitigation: link the release-notes URL from the
  "Installing update…" notification so a curious user can
  tap and read. Also: the changelog auto-opens on first
  launch of the new version (existing FEAT-007 "What's
  new" path), so they see the changes on restart.

---

## 5. Edge cases & open questions

### 5.1 User changes their mind mid-install

Once `installNow()` commits the session, the install is past
the point of cancellation from the app's perspective —
`PackageInstaller` owns it. The user can disable the toggle
in Settings, but the in-flight install proceeds. Next release
respects the new setting.

### 5.2 Battery-saver / Doze conflicts

Silent install is a `PackageInstaller` session; it doesn't
require any special Doze exemption. The download step is
already constraint-bounded; the install step is fast (~seconds)
and doesn't drain. No new Doze interaction.

### 5.3 Install fails on Android 12+ silent path

If the system rejects the silent install (e.g. permission
auto-grant didn't happen, signing cert changed since pin),
fall back to the regular `STATUS_PENDING_USER_ACTION` flow:
the existing receiver fires the system dialog, and the user
sees "CruxCoach updates" exactly as today. The toggle stays
on for the next release.

### 5.4 Foreground Board Sync vs Auto-Update

Both want process control. Resolution:
- `safeToInstallNow()` returns false while `isSyncing == true`.
- Auto-install defers to the next opportunistic trigger after
  the sync completes.
- No new ordering primitive needed; the StateFlow check is
  enough.

### 5.5 Auto-install on first-launch / onboarding

A user who installed an old APK from Codeberg, opens the app
for the first time, completes onboarding, hits Settings, opts
into auto-update… and the next periodic check finds a newer
version. We could auto-install immediately, but the user has
just spent 5 minutes in onboarding and isn't expecting a
restart.

Mitigation: a fresh-install grace period of 24 h after
`onboardingCompleted = true` during which auto-install is
deferred. Stored in `UpdaterPreferences.firstInstallCompletedAtMs`
(new field, written once by `OnboardingViewModel.completeOnboarding`).

### 5.6 Auto-install during an active climbing session

`bleConnection.state.value.isConnected == true` is part of
`safeToInstallNow`. The auto-install won't fire while the
user is climbing. A user climbing on a 90-minute session might
miss an auto-update window entirely; this is fine — the
update happens after they put the phone away.

### 5.7 Pre-release builds + auto-update

The user already has a "Pre-release-Updates erhalten" toggle.
Combining it with "Updates automatisch installieren" means a
user is auto-installing dev builds. We honour this — they
opted into both. The risk is theirs; the same trust chain
applies.

### 5.8 Codeberg-distributed-but-not-on-current-channel

If the install source check passes but the user is on a
release-only channel and an upgrade exists only as a pre-
release, the existing channel filter already drops the
pre-release from the candidate set. No interaction with this
spec.

---

## 6. Rollout / migration path

### 6.1 Phasing

**Phase A — Tier 1 (Opt-in, dialog stays on pre-API-31).**
Adds the toggle + the conditional `installNow()` call + the
`safeToInstallNow` helper. No new manifest permissions. Ships
as `feat(updater):` in a patch release. The Android 12+ silent
install does **not** activate yet — every install still routes
through the system dialog, but the user no longer has to tap
the notification to start it.

**Phase B — Tier 2 (Silent install on Android 12+).** Adds
`UPDATE_PACKAGES_WITHOUT_USER_ACTION` to the manifest +
`setRequireUserAction(USER_ACTION_NOT_REQUIRED)` in
`ApkInstaller`. Requires a documentation page explaining the
new permission so a curious user reading the manifest in
F-Droid / Aurora can see why we ask.

### 6.2 Backwards compatibility

- `autoApplyEnabled` defaults to false on every existing
  install. Anyone who doesn't open Settings → Updates sees
  zero behaviour change.
- Phase A → Phase B is invisible to a user who already opted
  in — the toggle stays on, the install just becomes quieter
  on capable devices.

### 6.3 Telemetry / verification

- New PERF marker: `UpdaterRepository.autoApply` (start, end,
  outcome). Compares verify→install latency for the two
  branches.
- Maestro flow that flips the toggle on, force-triggers a
  release check, asserts the "Installing update…" notification
  appears (we can't actually exercise the install in CI — the
  real install needs a signed-but-newer APK we don't have in
  test infra).
- Manual release qual: ship a beta to a small group of
  consenting testers with the toggle on, monitor crash reports
  for any "install loop" failures.

---

## 7. Testing strategy

### 7.1 JVM (Robolectric + DataStore test API)

- `UpdaterPreferences.autoApplyEnabled` round-trip test
  (write → snapshot → read).
- `UpdaterRepository.checkNow` branch test: with
  `autoApplyEnabled = true` and a fake `safeToInstallNow()`
  returning true, the `Pinned` outcome calls `installNow`
  (mocked). With `autoApplyEnabled = false` or
  `safeToInstallNow = false`, it calls
  `notifier.showReadyToInstall` instead.
- `safeToInstallNow` integration: drive the four StateFlow
  predicates through their states; assert the helper returns
  the expected boolean. Use mockk-relaxed for the upstream
  ViewModels.

### 7.2 Maestro (real-device flow)

- New `flows/auto-update-toggle.yaml`:
  1. Navigate Settings → Updates.
  2. Tap "Updates automatisch installieren".
  3. Assert the toggle moves to ON.
  4. Tap back, re-open, assert it's still ON
     (DataStore persistence smoke).
  5. Toggle OFF, assert state survives navigation.
- Live install verification requires a signed test-channel
  APK + Codeberg release; cannot be wired into CI. Manual
  test plan: a release-qual checklist item that the next
  beta install is silent on a Pixel 8 / silent on a OnePlus,
  + dialog-once on a Nokia 6.1 (API < 31).

### 7.3 Manual regression checklist

- Opt-in user with auto-update on, mid-board-sync release
  arrives → install deferred until sync completes → installs
  silently afterwards on Pixel.
- Opt-in user with auto-update on, app foregrounded on
  ClimbEditor → install deferred until backgrounded → installs
  silently afterwards.
- Opt-in user on Android 11 → install runs but the system
  dialog appears once → user taps "Install" → app restarts.
- Opt-out user (default) → notification appears as today →
  no behaviour change.
- Cert-mismatch (test: re-pin to a wrong cert) → auto-install
  refuses → cert-mismatch notification → user manually
  investigates. Auto-install does NOT bypass the pin.

---

## 8. Estimated complexity

Phase A:
- ~4 files: `UpdaterPreferences.kt`, `UpdaterRepository.kt`,
  `SettingsUpdaterScreen.kt`, new `SafeToInstallChecker.kt`
  helper.
- ~120 lines new code.
- ~50 lines JVM test glue + 1 Maestro flow.
- Effort: ~1 day.

Phase B:
- ~3 files: `AndroidManifest.xml`, `ApkInstaller.kt`,
  `UpdaterRepository.kt` (plumb autoApplyEnabled into the
  ApkInstaller call).
- ~30 lines new code.
- ~20 lines test glue.
- Effort: ~0.5 day.

Combined ~1.5 dev-days. Phase A alone closes the user-reported
friction; Phase B is the polish for Android 12+ devices.
