# Feature Spec: In-App Update Notification & APK Installer (Codeberg) — Skeleton (v0.1.2)

> **Status:** Skeleton — scope + design decisions agreed (§6), implementation details TBD.
> **Motivation:** Users who received the APK via local share or direct
> download from Codeberg currently have no way to learn about new
> releases. Zapstore users are already covered by Zapstore's own updater.

## 1. Overview

CruxCoach is distributed via three paths: (a) Zapstore, (b) direct APK
download from Codeberg Releases, (c) sideloading / local share from a
user who installed via (a) or (b). Paths (b) and (c) — together the
"apk-direct" cohort — have no update channel today.

This feature adds an in-app update checker that polls the Codeberg
Releases API, downloads the newer APK when one exists, verifies its
signing certificate against a trust-on-first-use pin, and hands off to
the Android `PackageInstaller` with the system install-consent dialog.

The update *offer* itself is never a dialog — it surfaces as a
persistent system notification only (see §6.10). Inside the app there
is no interrupting popup; the Settings screen shows a subtle badge.
CruxCoach also ships `-dev.<sha>` prereleases from the `dev` branch to
Codeberg; the updater must never pick one of those up (see §6.11).

If CruxCoach was installed via Zapstore, runtime detection disables
the self-updater entirely to avoid conflicting notifications. Zapstore
is currently the only store-based distribution channel; F-Droid / Play
are not supported, so their installer IDs are not recognized.

### Goals

- Detect new **stable** releases on Codeberg opportunistically — no fixed
  polling time; check on app start, on network regain, and as a WorkManager
  backstop. Skip (not fail) when offline or the user is absent; retry on
  the next online/user-present signal (§6.12)
- **Reject prereleases.** `prerelease=true` in the Codeberg JSON, or a
  `-dev.*` / `-rc.*` / `-beta.*` tag suffix, disqualifies a release even
  if its version is higher than the installed one (§6.11)
- Download the APK on Wi-Fi (user-overridable) with resume/retry
- Verify the download's SHA-256 against the release manifest
- Verify the download's signing certificate against a locally-pinned hash (TOFU)
- Surface the update as a **persistent system notification** with release
  notes parsed from the Codeberg release body; re-arm the notification on
  a cadence if the user dismisses it (§6.10)
- Hand off to `PackageInstaller` session API with the system consent prompt
- Hard-disable the updater when installed via Zapstore

### Non-Goals

- **No silent install.** `UPDATE_PACKAGES_WITHOUT_USER_ACTION` is not requested in v1. Every install goes through the system consent dialog.
- **No beta / nightly channels.** Only stable releases (`prerelease=false`, no `-dev.*` / `-rc.*` / `-beta.*` tag suffix). Even a `latest` endpoint response that points to a prerelease is rejected (§6.11).
- **No in-app update dialog.** The offer is a notification; the Settings screen shows a badge. The only dialog the user ever sees is Android's own install-consent dialog at the end of the pipeline.
- **No in-app rollback.** If an update breaks, users reinstall the previous APK manually from Codeberg.
- **No delta / patch updates.** Full APK each time.
- **No signed update manifest of our own.** We rely on Codeberg's HTTPS + Android's same-signature install rule + the TOFU cert pin. A Nostr-signed manifest is possible future hardening.
- **No build flavors.** One APK ships everywhere; coexistence is handled at runtime (§6.6).

---

## 2. Architecture

```
    Triggers (whichever fires first wins):
      - app onStart                  (CruxCoachApp observer)
      - NetworkCallback.onAvailable  (ConnectivityManager)
      - WorkManager backstop         (flex-interval 24 h, only fires
                                      when CONNECTED + battery-not-low)
      - Settings → "Jetzt prüfen"    (manual)
                   │
                   ▼
    ┌──────────────────────────────┐
    │  Codeberg Release API        │  (§6.1, prerelease filter §6.11)
    │  /api/v1/repos/.../releases  │
    └──────────────┬───────────────┘
                   │  JSON (list — pick latest with prerelease=false)
                   ▼
    ┌──────────────────────────────┐
    │  UpdateChecker               │  throttled: 1 real fetch / ≥2 h
    │  (opportunistic, not cron)   │  any earlier trigger coalesces
    └──────────────┬───────────────┘
                   │  UpdateInfo
                   ▼
    ┌──────────────────────────────┐     not newer
    │  VersionChecker              │───  / prerelease  ──▶ drop
    │  (strict > vs BuildConfig,   │
    │   reject -dev/-rc/-beta)     │
    └──────────────┬───────────────┘
                   │  newer stable
                   ▼
    ┌──────────────────────────────┐
    │  UpdateNotifier              │  (§6.10)
    │  Persistent notification     │
    │  (re-arms after dismiss)     │  ← NO in-app dialog
    │  Settings screen badge       │
    └──────────────┬───────────────┘
                   │  user taps notification → "Download"
                   ▼
    ┌──────────────────────────────┐
    │  ApkDownloader               │
    │  (DownloadManager, Wi-Fi     │
    │   default, cacheDir target)  │
    └──────────────┬───────────────┘
                   │  apkPath
                   ▼
    ┌──────────────────────────────┐
    │  IntegrityVerifier           │
    │  1. SHA-256 vs manifest      │
    │  2. Signing cert SHA-256 vs  │
    │     pinned (TOFU)            │
    └──────────────┬───────────────┘
                   │  verified
                   ▼
    ┌──────────────────────────────┐
    │  ApkInstaller                │  ← Android's install-consent
    │  PackageInstaller session,   │    dialog is the *only* dialog
    │  USER_ACTION_REQUIRED        │    in the whole flow
    └──────────────────────────────┘
```

### Core Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Update source | Codeberg Releases API (`/api/v1/repos/<org>/cruxcoach/releases`, list endpoint) | List endpoint lets us skip prereleases explicitly; `latest` alone is not sufficient because our CI also publishes `-dev` prereleases |
| Release channel | Stable only — reject `prerelease=true` and any tag matching `-dev.*` / `-rc.*` / `-beta.*` (§6.11) | CI publishes dev builds to Codeberg from the `dev` branch; users must never be handed a dev APK |
| Scheduling | **Opportunistic**, not cron: app `onStart` + `NetworkCallback.onAvailable` + manual "Check now", with WorkManager as a 24 h backstop only (§6.12) | Fixed cron wakes the device to fail if offline; opportunistic checks only fire when the user is already using the device and online |
| User surface | **Persistent system notification** for the offer; Settings badge for in-app hint; **no dialog** before the install-consent step (§6.10) | Dialogs interrupt; a notification sits silently and re-arms if dismissed. The only unavoidable dialog is Android's own install consent at the very end |
| Integrity anchor | SHA-256 of APK (from release description) + TOFU signing-cert pin | Defense in depth: detects server compromise even if attacker controls SHA-256 |
| Install mechanism | `PackageInstaller` session API, `USER_ACTION_REQUIRED` | No `UPDATE_PACKAGES_WITHOUT_USER_ACTION` permission in v1 |
| Store coexistence | Runtime `getInstallSourceInfo()` check; hard-disable | Single APK; no flavor duplication |
| Cert pin strategy | TOFU: read from installed app on first launch | Survives CI/release without hardcoding; manual reinstall on key rotation |
| Downgrade policy | Strict `versionCode > installed`; reject equal or lower | Defence against stale-manifest MITM |

---

## 3. Data Model

### 3.1 Codeberg Release JSON (subset we consume)

We query the **list** endpoint (`.../releases?limit=10`) rather than
`.../releases/latest`, so we can filter prereleases client-side and fall
back if the most recent entry is a dev build (see §6.11).

```json
{
  "tag_name": "v0.1.2",
  "name": "v0.1.2",
  "prerelease": false,
  "draft": false,
  "body": "## Highlights\n- In-app auto-update...\n\n### SHA-256\n- cruxcoach-release.apk: `abc123...`",
  "published_at": "2026-05-01T18:00:00Z",
  "assets": [
    {
      "name": "cruxcoach-release.apk",
      "browser_download_url": "https://codeberg.org/.../cruxcoach-release.apk",
      "size": 12345678
    }
  ]
}
```

- `tag_name` → version parsed from strict `v<major>.<minor>.<patch>`;
  any tag with a suffix (e.g. `v0.1.1-dev.abc1234`, `-rc.1`, `-beta.2`)
  is rejected regardless of `prerelease`
- `prerelease` / `draft` → both must be `false`
- `assets[].browser_download_url` → APK URL (pick `cruxcoach-release.apk`)
- `body` → both release notes AND source of SHA-256 (parsed from a known-format code block)

### 3.2 Parsed Update Info

```kotlin
data class UpdateInfo(
    val tagName: String,
    val versionName: String,           // "0.1.2"
    val versionCode: Int,              // derived from tagName (see §6.2)
    val apkUrl: String,
    val apkSizeBytes: Long,
    val apkSha256: String,             // hex, lowercase
    val releaseNotesMarkdown: String,
    val publishedAt: Instant,
)
```

### 3.3 Updater State (persistent)

Stored in `EncryptedSharedPreferences` under `updater_state`:

```kotlin
data class UpdaterState(
    val pinnedCertSha256: String?,     // hex, set on first launch (TOFU)
    val pinnedAt: Instant?,
    val lastCheckAt: Instant?,
    val lastCheckResult: CheckResult,  // SUCCESS | NO_UPDATE | NO_UPDATE_STABLE | ERROR
    val pendingDownloadId: Long?,      // DownloadManager ID if download in progress
    val pendingUpdate: UpdateInfo?,    // null once consumed or cleared
    val userCheckNetworkOverride: Boolean, // default false = Wi-Fi-only
    // Notification re-arm state (§6.10)
    val lastNotifiedVersionCode: Int?, // version of the last offer surfaced
    val notifDismissedAt: Instant?,    // user swiped the notification away
    val notifReArmCount: Int,          // capped at 5
)
```

### 3.4 Version comparison

`versionCode` is the monotonic integer from `build.gradle.kts`, not the
tag. The tag is `v<semver>`, and we parse it with a small helper to
derive a comparable integer using the project's existing
`versionCode` formula (see `build.gradle.kts` and
`project_zapstore_release_strategy.md` memory). If parse fails → abort
update (§6.4 Error Handling).

---

## 4. Integration Touchpoints

| Consumer | File | Change |
|----------|------|--------|
| Lifecycle trigger | `androidApp/src/main/java/com/cruxcoach/android/CruxCoachApp.kt` | Register `ProcessLifecycleOwner` observer + `ConnectivityManager` default-network callback that both feed `UpdateChecker.maybeCheck()` (§6.12) — only if self-updater enabled (§6.6) |
| WorkManager backstop | same | Enqueue the 24 h flex-interval `UpdateCheckWorker` once at startup as the fallback trigger |
| DI | `di/AppModule.kt` or `di/UpdaterModule.kt` (new) | Provide `UpdateChecker`, `ApkDownloader`, `IntegrityVerifier`, `ApkInstaller`, `InstallSourceGate`, `UpdateNotifier` singletons |
| Settings UI | `ui/settings/*` | New "App updates" section: last-check timestamp, "Jetzt prüfen" button, Wi-Fi-only toggle, "auto-update check" toggle, badge + inline release-notes row when an update is pending (§6.10), info row when store-gated |
| Release-notes route | `ui/navigation/NavGraph.kt` + new screen | In-app screen opened by the notification tap; **not** a dialog |
| Manifest | `androidApp/src/main/AndroidManifest.xml` | Add `REQUEST_INSTALL_PACKAGES`, `POST_NOTIFICATIONS` (Android 13+), receiver for `PackageInstaller` callbacks, notification channel declaration at first launch |
| Strings | `values/strings.xml` + `values-de/strings.xml` | All update-related UI + notification strings (both locales per CLAUDE.md) |
| Cert-pin bootstrap | App startup or first settings-open | Read `PackageInfo.GET_SIGNING_CERTIFICATES` of the installed CruxCoach package, write `pinnedCertSha256` if absent |

New package: `com.cruxcoach.android.updater` containing
`UpdateChecker`, `UpdateCheckWorker`, `UpdateNotifier`,
`CodebergReleaseClient`, `VersionChecker`, `ApkDownloader`,
`IntegrityVerifier`, `ApkInstaller`, `InstallSourceGate`,
`UpdaterRepository`.

---

## 5. Update Pipeline

Five discrete stages, each idempotent, each persistable for resume.

### 5.1 Check

Trigger sources (any of them may fire a check, all funnel through the
same throttle in `UpdateChecker.maybeCheck()`):

- **App foreground**: `ProcessLifecycleOwner` `ON_START` — catches the
  common case ("user opened the app"); only fires if device is currently
  online
- **Network regain**: `ConnectivityManager.registerDefaultNetworkCallback`
  `onAvailable` — catches "came back from offline while app is open"
- **Manual**: Settings → "Jetzt prüfen" — bypasses throttle
- **Backstop**: WorkManager `PeriodicWorkRequest`, flex-interval 24 h,
  constraints `NetworkType.CONNECTED` + `requiresBatteryNotLow`.
  Flex-interval means WorkManager picks its own moment inside the window
  — we never pin a clock time. If the device is offline *and* the user
  isn't using the app, the check simply never happens that cycle, and
  the next online/foreground event picks it up

Throttle: `UpdateChecker.maybeCheck()` drops any call made within
`MIN_CHECK_INTERVAL = 2 h` of the last successful network fetch (manual
check ignores the throttle).

Fetch path:
- `GET https://codeberg.org/api/v1/repos/<org>/cruxcoach/releases?limit=10`
- Parse JSON; apply the stable-release filter (§6.11): skip any entry
  where `prerelease` or `draft` is `true`, or where the tag has a suffix
  after the `MAJOR.MINOR.PATCH` segment. Pick the first remaining entry
- If none remain → record `NO_UPDATE_STABLE` and stop
- If the selected `tag_name` is equal or older than installed → record
  `NO_UPDATE` and stop
- If newer → write `pendingUpdate`, trigger §5.2

Offline / transient failure: logged as `ERROR` with a timestamp; no
user surface (§6.4). The next trigger retries.

### 5.2 User Prompt — Notification Only

When a newer stable release is detected, the updater posts a
**persistent notification** (ongoing=false, but set to auto-re-arm per
§6.10). It does **not** open any dialog, banner, snackbar, or modal in
the app.

Notification content:
- Title: "Update verfügbar: v<version>"
- Short body: "<APK size> — Tippen für Details"
- Primary action: "Herunterladen"
- Secondary action: "Details" (opens an in-app release-notes screen —
  not a dialog; a dedicated route under Settings)
- Swipe to dismiss: recorded as `dismissedAt`; re-armed per §6.10

Inside the app: the Settings screen shows a badge next to the "App
updates" row and a non-modal, non-interrupting row with release notes
+ a "Herunterladen" button. No popup ever appears on top of any other
screen.

### 5.3 Download

- `DownloadManager.Request` on the APK URL
- Target: `context.cacheDir / "pending-update-<versionCode>.apk"`
  (not external-files-dir — CruxCoach APKs are small, and cacheDir is not world-readable)
- `setAllowedNetworkTypes(NETWORK_WIFI)` unless user override
- `setNotificationVisibility(VISIBILITY_HIDDEN)` — we post our own progress UI
- Persist `downloadId` in `UpdaterState.pendingDownloadId`; on next worker run, query status and resume/restart as needed

### 5.4 Verify (critical)

Two checks, both must pass before the install step is reachable:

**5.4.1 Payload integrity:**
```
sha256(downloaded bytes) == UpdateInfo.apkSha256   (MessageDigest.isEqual, constant-time)
```

**5.4.2 Signing certificate (TOFU):**
```
getPackageArchiveInfo(apkPath, GET_SIGNING_CERTIFICATES)
  .signingInfo.apkContentsSigners[0].toByteArray()
  .sha256()
  == pinnedCertSha256
```

Mismatch → delete APK, log, surface "Signatur hat sich geändert — bitte manuell prüfen", abort. Do NOT offer to override from the UI.

### 5.5 Install

- `PackageInstaller.createSession()` with `MODE_FULL_INSTALL`
- Write APK stream into the session
- `session.commit(statusReceiver)` with a `PendingIntent` to `ApkInstallStatusReceiver`
- Receiver handles `PackageInstaller.STATUS_*`:
  - `STATUS_PENDING_USER_ACTION` → launch the system consent dialog
  - `STATUS_SUCCESS` → notification "Update installed"
  - `STATUS_FAILURE_INVALID` → "Signatur stimmt nicht — bitte manuell neu installieren" (Androids same-signature rejection)
  - `STATUS_FAILURE_STORAGE` → specific message
  - `STATUS_FAILURE_CONFLICT` → specific message
  - Others → generic "Install fehlgeschlagen"

On success: clear `pendingUpdate`, delete cached APK, reset `pendingDownloadId`.

---

## 6. Design Decisions

### 6.1 Update Source — Codeberg Releases API (list, not `/latest`)

**Decision:** Query the **list endpoint**
`/api/v1/repos/<org>/cruxcoach/releases?limit=10`, filter client-side
per §6.11, pick the highest-version stable release. No self-hosted
manifest.

**Why:** Codeberg already provides version, asset URLs, upload timestamp,
and release body out of the box. The `/latest` endpoint cannot be used
alone: CruxCoach CI publishes `-dev.<sha>` prereleases from the `dev`
branch to the same repo, and Codeberg's `/latest` may point at one of
them. The list endpoint lets us walk down to the first true stable
entry. Self-hosting a signed manifest is better defense but much more
infra.

**How to apply:**
- HTTP GET via existing OkHttp client
- `Accept: application/json`, explicit User-Agent `CruxCoach-Updater/<versionName>`
- 10 s connect timeout, 15 s read timeout
- Treat any non-2xx as a transient failure → retry on next trigger
- Ten entries is plenty: even if every second release were a dev build,
  ten covers five stable releases back — more than we will ever need to
  skip past

### 6.2 Version Comparison — versionCode, strict `>`

**Decision:** Compare `BuildConfig.VERSION_CODE` against `versionCode` derived from the tag. Strict greater-than; reject equal or lower.

**Why:** Android platform also blocks downgrades for non-debuggable APKs, but the explicit check means we never even prompt the user about a stale release. `versionCode` is the canonical monotonic integer CruxCoach already ships.

**How to apply:**
- Tag parser: `v?(\d+)\.(\d+)\.(\d+)` → `(major, minor, patch)` → apply existing `versionCode` formula from `build.gradle.kts`
- Parse failure → abort with `CheckResult.ERROR`, do not offer update

### 6.3 Release Notes — Parsed from Markdown Body

**Decision:** Render the release `body` markdown in the update dialog. Not auto-expanded; user can scroll.

**Why:** Zero extra infra; markdown is already the source of truth on Codeberg. Writers just describe changes in the normal release description.

**How to apply:**
- `body` field, rendered with existing markdown renderer
- SHA-256 is extracted from a predictable code block; stripped from the version shown to the user

### 6.4 Bootstrap Failure Handling — Silent Retry, No User Error

**Decision:** Network failures, JSON parse errors, and tag parse errors are logged at debug and stored as `CheckResult.ERROR` with a timestamp. No user-facing message. The next periodic run retries.

**Exception:** signature mismatch during verify is **always** surfaced and never auto-retried — it is a trust-path violation, not a network blip.

**Why:** A release polling app that pops errors on flaky Wi-Fi is hostile. Climbers in gyms have flaky Wi-Fi. The security-critical path (signature) is the one users must see.

**How to apply:**
- Distinguish transient (network, parse) from security (signature, SHA-256) failures in `UpdaterState`
- Settings screen shows `lastCheckAt` + a subtle "last check failed" hint only if `ERROR` state has persisted >3 days

### 6.5 Cache & Persistence — EncryptedSharedPreferences

**Decision:** All updater state lives in an encrypted preference file. The cached APK lives in `cacheDir` and is deleted after install or on any verification failure.

**Why:** `pinnedCertSha256` should not be trivially tamperable; on-device attacker with filesystem read is already a catastrophic threat model, but encryption adds friction. `cacheDir` is not world-readable (unlike external-files-dir) and auto-cleaned on low storage.

### 6.6 Coexistence with Zapstore — Runtime Hard-Disable

**Decision:** At app launch, `InstallSourceGate` queries `PackageManager.getInstallSourceInfo(packageName)` (API 30+, fall back to `getInstallerPackageName`). Zapstore is the only store-based distribution channel CruxCoach uses today, so the gate matches a single installer ID — `"dev.zapstore.app"`. If that matches, the self-updater is fully disabled:
- `UpdateCheckWorker` is not enqueued
- The Settings "App updates" section shows: *"Updates erhältst du über Zapstore. Der App-eigene Updater ist deaktiviert."* (localized in both locales)
- No toggle, no override

If a new store-based channel is added later (e.g. F-Droid), its installer ID is appended to the recognized set — it is not silently allowed through the self-updater.

**Why:** Single-APK distribution stays simple; Signal's flavor approach doubles CI effort. Detecting at runtime catches sideload-from-Zapstore cleanly. Hard-disable (not warn-and-allow) prevents double notifications and confusion about "which version should win." Limiting the match to installers CruxCoach actually ships through avoids silently trusting identifiers we have not verified in practice.

**How to apply:**
- `InstallSourceGate.selfUpdateAllowed(): Boolean` — checked by `UpdateCheckWorker`, `UpdaterRepository.checkNow()`, and the Settings UI
- The set of recognized store installer IDs lives in a single constant (`InstallSourceGate.STORE_INSTALLER_IDS`) so adding F-Droid / Play later is one edit
- Detection runs on every check (not cached) — users can uninstall Zapstore and reinstall CruxCoach from Codeberg later; the gate reflects the current install source immediately

### 6.7 Network Policy — Wi-Fi Default, User-Overridable

**Decision:** `setAllowedNetworkTypes(NETWORK_WIFI)` by default. Settings toggle "Updates über mobile Daten herunterladen" (off by default) switches to `NETWORK_WIFI | NETWORK_MOBILE`.

**Why:** Default respects data plans. Override is available for users who want it.

### 6.8 Consent Model — System Dialog Every Install

**Decision:** No silent install in 0.1.2. Every install triggers the Android consent dialog via `PackageInstaller.STATUS_PENDING_USER_ACTION`.

**Why:** `UPDATE_PACKAGES_WITHOUT_USER_ACTION` is a Google-sensitive permission, F-Droid reviewers see it with suspicion. The dialog is one extra tap; the UX cost is small, the trust cost of skipping it is not.

### 6.9 Key Rotation — No Auto-Override

**Decision:** If the signing key is ever rotated (new CruxCoach release signed with a different cert), the TOFU pin will mismatch and updates halt with a user-visible "Signatur hat sich geändert" notice. The user must manually download and reinstall.

**Why:** Auto-override would make TOFU worthless — an attacker with a stolen key would need no further effort to MITM updates. Manual reinstall after intentional rotation is rare, documented in `docs/KEY_ROTATION.md`, and survives audit.

### 6.10 User Surface — Notification Only, No Dialog

**Decision:** The update offer is a **persistent system notification**.
No dialog, banner, snackbar, or modal ever appears inside the app to
announce an available update. Inside the app, the Settings screen shows
a small badge and a non-modal row with the release notes + a
"Herunterladen" button. The only dialog anywhere in the flow is
Android's own install-consent dialog at the very end — that one is
platform-required and cannot be suppressed.

If the user swipes the notification away, it is re-armed on a cadence
(first re-arm at **+24 h**, then every **72 h** up to a cap of **5**
re-arms). The cadence resets — and the notification is re-posted
immediately — if a *newer* stable release appears. Once the user taps
"Herunterladen" or the update is installed, the notification is
cancelled permanently for that version.

**Why:** A popup on app open is hostile. Climbers often open the app at
the board to queue a climb — they should not have to dismiss an update
modal first. A notification sits silently out of the way, can be seen
at a glance in the system tray, and is the Android-native way to offer
something non-urgent. Re-arming on a cadence (rather than once-and-done)
means a user who dismissed the notification by accident still finds out
about the update later, but never more than every few days so it never
turns into nagging.

**How to apply:**
- Notification channel: low-importance (`IMPORTANCE_LOW`) — no sound,
  no vibration, no heads-up; silent by design
- `Notification.Builder.setOngoing(false)` — user *can* swipe; we just
  re-arm later per the cadence above
- State: `UpdaterState.lastNotifiedVersion`, `dismissedAt`, `reArmCount`
- Re-arm scheduled with a dedicated `OneTimeWorkRequest` (initial delay),
  replacing the previous schedule on every dismiss so the cadence stays
  consistent
- Tap on notification → opens the in-app "Update available" route under
  Settings (release notes + Download/Later). No overlay, no dialog
- Settings screen: badge on the "App updates" row. A `ListItem` with
  a short summary and an inline "Herunterladen" button. Tapping
  elsewhere in Settings does not spring an update dialog

### 6.11 Release Channel — Stable Only, Reject `-dev` / Prereleases

**Decision:** The updater must never pick up a `-dev.<sha>` /
`-rc.<n>` / `-beta.<n>` release. Two independent filters must both
agree before a release is considered:

1. `prerelease == false` **and** `draft == false` on the Codeberg
   release JSON.
2. Tag matches strict `^v(\d+)\.(\d+)\.(\d+)$` — no suffix after the
   patch segment.

A release that fails either filter is skipped, even if its version is
higher than the installed one. The list endpoint is walked in order
until the first stable entry is found; if the ten entries we fetched
contain no stable release, the check ends with `NO_UPDATE_STABLE`
(neither an error nor an update prompt).

**Why:** CruxCoach CI pushes a release to Codeberg on every `dev`
branch push with tag `v<ver>-dev.<shortsha>`, `prerelease=true`. These
are throwaway artifacts for testing, not something any user should run.
Relying on `prerelease=true` alone would still be correct today, but
the dual filter (flag **and** tag shape) guards against two real
failure modes: (a) a human forgetting to tick the prerelease flag on a
manual `-rc` release, and (b) a future CI bug where `prerelease` is not
set. Strict tag shape also insulates us from Codeberg's `/latest`
endpoint ever changing its rules about which release counts as "latest".

**How to apply:**
- `VersionChecker.isStableRelease(release: CodebergRelease): Boolean` —
  single predicate, both filters in one place
- Unit tests cover the matrix: `v0.1.2` ✓, `v0.1.2-dev.abc1234` ✗,
  `v0.1.2-rc.1` ✗, `0.1.2` ✗ (missing `v`), `v0.1` ✗ (incomplete),
  `v0.1.2` with `prerelease=true` ✗
- Log the skip reason at debug — makes "why didn't my device update?"
  investigations trivial

### 6.12 Scheduling Model — Opportunistic, No Clock-Based Cron

**Decision:** Checks fire on real user signals, not on a clock. Three
triggers feed into a single `UpdateChecker.maybeCheck()` that applies a
2 h throttle:

1. `ProcessLifecycleOwner` `ON_START` — "user opened the app and we're
   online right now"
2. `ConnectivityManager.registerDefaultNetworkCallback` `onAvailable` —
   "network just came back while the app is running"
3. WorkManager `PeriodicWorkRequest` with `flex-interval 24 h`,
   `NetworkType.CONNECTED`, `requiresBatteryNotLow` — **backstop only**,
   ensures that a user who leaves the app in the background for days
   still eventually sees an update

Manual "Jetzt prüfen" in Settings bypasses the throttle.

When offline or the device is idle, the check **does not fire and does
not retry**. There is no exponential backoff, no retry queue, no
scheduled "try again in 5 minutes" alarm. The next valid trigger (app
foreground / network regain / periodic window) picks it up naturally.

**Why:** A clock-based daily check at e.g. 02:00 wakes the device only
to discover there is no network or the user is asleep — at best that
wastes battery, at worst it accumulates failures and fires "check
failed" logs that are pure noise. Opportunistic triggering is the model
Android itself uses for most background work and is what `WorkManager`
is designed around. Skipping instead of retrying also means we never
hammer Codeberg when the device is on a flaky connection — a user
walking into a gym with patchy Wi-Fi does not trigger twenty retry
bursts, just one check when the connection stabilizes.

**How to apply:**
- `UpdateChecker` injects `ProcessLifecycleOwner.get().lifecycle` and
  observes `ON_START`
- `ConnectivityManager` callback registered in the updater's own scope,
  filtered to `NET_CAPABILITY_INTERNET && NET_CAPABILITY_VALIDATED` so
  we don't fire on captive-portal Wi-Fi
- Single coalescing throttle: `val since = now() - state.lastCheckAt;
  if (since < MIN_CHECK_INTERVAL && !isManual) return` — no queue, no
  retry, caller just moves on
- WorkManager uses a `flex-interval`, not a fixed `initialDelay` — the
  system gets to choose the exact moment inside the window
- If the network capability check fails (offline), the trigger path
  exits early before a single HTTP call is made

---

## 7. Security & Privacy

**Threat model (what we defend against):**

| Attack | Defense |
|---|---|
| Tampered APK served by Codeberg (compromised CDN) | SHA-256 match + TOFU cert pin |
| Stale-manifest MITM (replays old vulnerable release) | Strict `versionCode > installed` |
| Different-signer APK pushed to updater | TOFU cert pin + Android same-signature rule in `PackageInstaller` |
| Silent downgrade via rollback | Strict `>`; Android platform also refuses |
| Install-source spoofing (app pretends to be Zapstore-installed) | Not defended — `getInstallSourceInfo` is platform-authoritative; forging it requires root |

**Threats out of scope (acceptable residual risk):**

- Full compromise of Codeberg's TLS (HSTS not pinned — cert pinning on a third-party domain is too brittle)
- Signing-key exfiltration (mitigated by protecting `.signing/` per CLAUDE.md, not by this feature)
- Attacker with on-device code execution (unbounded capability; TOFU pin is one more hurdle but not a fence)

**Privacy:**

- Update check sends a single HTTPS request per 24 h — leaks "this device polled Codeberg at this time" to Codeberg and any network intermediary. Identical to every other app that uses Codeberg Releases; no additional disclosure.
- No analytics, no telemetry. `lastCheckAt` is strictly local.

---

## 8. Out of Scope — Explicitly Deferred

- **Silent install** (`UPDATE_PACKAGES_WITHOUT_USER_ACTION`) — possible 0.2.0+ opt-in
- **Beta / nightly channels** — CI keeps publishing `-dev.<sha>` to Codeberg for testing, but they are not discoverable via the updater (§6.11). No opt-in "I want dev builds" toggle in 0.1.2
- **Delta / patch updates**
- **Self-hosted signed manifest** (Nostr-event delivery) — considered, rejected for v1 due to infra cost
- **Background auto-download** (today: check → notify → user decides; no pre-fetch)
- **Changelog across multiple versions** (show only the latest release's notes, not cumulative)
- **Rollback UI**

---

## 9. Dependencies

- `androidx.work:work-runtime-ktx` — WorkManager (likely already present)
- `androidx.security:security-crypto` — `EncryptedSharedPreferences` (check if present; likely for Nostr key store)
- Existing OkHttp client + JSON parser (Moshi or kotlinx-serialization — whatever is in use)
- `android.app.DownloadManager` (platform)
- `android.content.pm.PackageInstaller` (platform)
- No new third-party runtime dependencies expected

---

## 10. Delivery Checklist (for full spec later)

- [ ] Concrete class names, package placement, function signatures
- [ ] Error handling matrix (all `PackageInstaller.STATUS_*` codes mapped to user messages, localized DE/EN)
- [ ] Test plan:
  - Unit: tag parser, versionCode comparison, SHA-256 helper, cert-pin compare, install-source gate logic
  - Integration: mock Codeberg JSON, DownloadManager stub, verify/install happy path + each failure path
  - Manual: install from Zapstore and verify Settings shows "deaktiviert"; install from Codeberg APK and verify end-to-end update flow
- [ ] Migration: existing installs have no `pinnedCertSha256` → TOFU on first launch after 0.1.2 upgrade
- [ ] Rollout / kill-switch: remote flag to disable the updater (e.g. a bool in a known-relay Nostr kind or a static URL) — TBD, not strictly required for v1
- [ ] `docs/KEY_ROTATION.md` entry for the manual-reinstall procedure
- [ ] Release process: SHA-256 of the APK must be embedded in the Codeberg release body in a predictable format; document in `docs/RELEASE.md`
