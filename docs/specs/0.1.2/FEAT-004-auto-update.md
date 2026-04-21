# Feature Spec: In-App Update Notification & APK Installer (Codeberg) (v0.1.2)

> **Status:** Design complete. Reviewed 2026-04-21 — first pass folded
> in blockers B1–B4 and robustness items R1–R8; second pass folded in
> the high-ROI UX/reliability items P2 (auto-download on Wi-Fi default),
> P3 (notification-permission nudge), P7 (clock-skew-immune throttle via
> `SystemClock.elapsedRealtime`), P9 (monthly re-arm after dismissal
> cap), P10 (WorkManager OEM-killer flags + first-run expedited), and
> P12 (cert-mismatch handoff to Codeberg in browser). Onboarding is
> Settings-only — no first-run dialog. Still open before implementation:
> concrete class names & function signatures (§10),
> `docs/KEY_ROTATION.md` (release blocker, §6.9).
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
- **Auto-download on Wi-Fi by default** (§6.14) so the user sees
  "ready to install" notifications instead of "tap to download" —
  collapsing a 5-tap flow to 2 taps. Auto-install is explicitly NOT
  done (§6.8 — system consent is the trust anchor)
- Verify the download's SHA-256 (from a separate `.sha256` release asset, §6.3)
- Verify the download's signing certificate against a locally-pinned hash (TOFU)
- Surface the update as a **single persistent system notification**
  that transitions through `PENDING_DOWNLOAD → DOWNLOADING →
  READY_TO_INSTALL` states (§5.2, §6.14); re-arm the notification on
  a cadence if the user dismisses it, monthly indefinitely after 10
  dismissals (§6.10)
- Surface a **permission-nudge banner** in Settings when the user has
  disabled notifications or the updater channel (§6.13) — otherwise
  the feature is silent failure
- Hand off to `PackageInstaller` session API with the system consent prompt
- On cert-pin mismatch (legitimate key rotation OR MITM): give the user
  a one-tap handoff to the Codeberg release page in their browser (§5.4.3),
  letting Android's platform signature rule decide
- Hard-disable the updater when installed via Zapstore (§6.6)

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
    Triggers (whichever fires first wins; throttled to ≥2h, §6.12):
      - app onStart                  (CruxCoachApp observer)
      - NetworkCallback.onAvailable  (ConnectivityManager)
      - WorkManager backstop         (flex-interval 24h, setRequiresDeviceIdle=false;
                                      first-run expedited §6.12)
      - Settings → "Jetzt prüfen"    (manual, 10s UI cooldown §R2)
                   │
                   ▼
    ┌──────────────────────────────┐
    │  Codeberg Release API        │  §6.1 — list endpoint, ETag/304
    │  /api/v1/repos/.../releases  │       prerelease filter §6.11
    └──────────────┬───────────────┘
                   │  JSON (list — pick latest with prerelease=false, stable tag)
                   ▼
    ┌──────────────────────────────┐
    │  UpdateChecker               │  throttled via ElapsedRealtime (§6.12)
    │  (opportunistic, not cron)   │  any earlier trigger coalesces
    └──────────────┬───────────────┘
                   │  UpdateInfo (incl. .sha256 asset)
                   ▼
    ┌──────────────────────────────┐     not newer
    │  VersionChecker              │───  / prerelease  ──▶ drop
    │  (SemVer tuple compare §6.2, │
    │   reject -dev/-rc/-beta)     │
    └──────────────┬───────────────┘
                   │  newer stable
                   ▼
    ┌──────────────────────────────┐
    │  UpdateNotifier              │  (§5.2, §6.10, §6.14)
    │  Single notification,        │  NO in-app dialog
    │  3 states: PENDING →         │  permission-nudge banner if
    │  DOWNLOADING → READY         │  notifications blocked (§6.13)
    └──────────────┬───────────────┘
                   │  (auto on Wi-Fi if §6.14 enabled) OR user tap
                   ▼
    ┌──────────────────────────────┐
    │  ApkDownloader               │  pre-flight StatFs (§R4)
    │  DownloadManager, state      │  progress updates notification
    │  transitions on progress     │  every ≥2s
    └──────────────┬───────────────┘
                   │  apkPath
                   ▼
    ┌──────────────────────────────┐     SHA-256 fail
    │  IntegrityVerifier           │───  ────────▶ delete + ERROR
    │  1. SHA-256 vs .sha256 asset │
    │  2. Cert SHA-256 vs pinned   │     cert mismatch
    │     (HMAC-sealed, §6.5)      │───  ────────▶ §5.4.3 handoff
    └──────────────┬───────────────┘              (Codeberg in browser)
                   │  both pass
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
| Update source | Codeberg Releases API (`/api/v1/repos/CruxCoach/CruxCoach/releases`, list endpoint) | List endpoint lets us skip prereleases explicitly; `latest` alone is not sufficient because our CI also publishes `-dev` prereleases |
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
  "body": "## Highlights\n- In-app auto-update...\n",
  "html_url": "https://codeberg.org/CruxCoach/CruxCoach/releases/tag/v0.1.2",
  "published_at": "2026-05-01T18:00:00Z",
  "assets": [
    {
      "name": "cruxcoach-release.apk",
      "browser_download_url": "https://codeberg.org/.../cruxcoach-release.apk",
      "size": 12345678
    },
    {
      "name": "cruxcoach-release.apk.sha256",
      "browser_download_url": "https://codeberg.org/.../cruxcoach-release.apk.sha256",
      "size": 64
    }
  ]
}
```

- `tag_name` → version parsed from strict `v<major>.<minor>.<patch>`;
  any tag with a suffix (e.g. `v0.1.1-dev.abc1234`, `-rc.1`, `-beta.2`)
  is rejected regardless of `prerelease`
- `prerelease` / `draft` → both must be `false`
- `assets[]` → two required entries:
  - `cruxcoach-release.apk` → the APK payload
  - `cruxcoach-release.apk.sha256` → a single line `<hex>  cruxcoach-release.apk`
    (coreutils `sha256sum` format). Parsed as a structured asset, NOT
    scraped from the release `body`, so a typo in the human-written
    release notes can never corrupt the integrity anchor. If the sha256
    asset is missing, the release is rejected (logged, not surfaced)
- `body` → release notes only (markdown). No machine-parsed data.
- `html_url` → the user-facing Codeberg page for this release. Used by the
  cert-mismatch handoff in §5.4.3 to launch the user's browser when the
  TOFU pin refuses an APK.

### 3.2 Parsed Update Info

```kotlin
data class UpdateInfo(
    val tagName: String,               // "v0.1.2" — raw
    val versionName: String,           // "0.1.2" — display
    val version: SemVer,               // (major, minor, patch) — comparison
    val apkUrl: String,
    val apkSha256Url: String,
    val apkSizeBytes: Long,
    val apkSha256: String,             // hex, lowercase — from sha256 asset
    val releaseNotesMarkdown: String,
    val releasePageUrl: String,        // Codeberg `html_url` — §5.4.3 handoff target
    val publishedAt: Instant,
)

data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int =
        compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)
}
```

### 3.3 Updater State (persistent)

Two-file split: bulk state in `DataStore<Preferences>` (Proto or key-value
— whichever the codebase already uses), and the **TOFU cert pin in its
own HMAC-sealed file** (§6.5). The pin is load-bearing for the trust
path; everything else is recoverable telemetry.

**Bulk state** — `DataStore` `updater_state.preferences_pb`:

```kotlin
data class UpdaterState(
    val lastCheckAt: Instant?,                  // wall-clock, for display only
    val lastCheckBootRealtime: Long,            // §6.12 — clock-skew-immune throttle
    val lastCheckEtag: String?,                 // §6.1 — If-None-Match on next poll
    val lastCheckResult: CheckResult,           // SUCCESS | NO_UPDATE | NO_UPDATE_STABLE | ERROR | BLOCKED_CERT_MISMATCH
    val lastErrorAt: Instant?,                  // most recent ERROR timestamp (§6.4)
    val pendingDownloadId: Long?,               // DownloadManager ID if download in progress
    val pendingUpdate: UpdateInfo?,             // null once consumed or cleared
    val pipelineStage: PipelineStage,           // NONE | PENDING_DOWNLOAD | DOWNLOADING | READY_TO_INSTALL | BLOCKED_CERT_MISMATCH
    // User toggles (§6.14, §6.15)
    val autoCheckEnabled: Boolean = true,       // §6.15 — user can opt out
    val autoDownloadOnWifi: Boolean = true,     // §6.14 — default on
    val autoDownloadOnMobile: Boolean = false,  // §6.14 — explicit opt-in only
    val userDownloadNetworkOverride: Boolean = false, // one-shot per-download (legacy §6.7)
    // Notification re-arm state (§6.10)
    val lastNotifiedVersionCode: Int?, // version of the last offer surfaced
    val notifDismissedAt: Instant?,    // user swiped the notification away
    val notifReArmCount: Int,          // 0..10 then monthly (§6.10); resets on newer release or manual check
)
```

**Trust anchor** — `files/updater_pin.bin` (see §6.5 for format):

```
pin_cert_sha256_hex (64 bytes, ASCII)
pinned_at_epoch_seconds (8 bytes, big-endian)
hmac_sha256 over the above (32 bytes)
```

The HMAC key lives in the Android Keystore (strongbox-backed when
available); an attacker with filesystem read but no code exec can read
the pin but cannot silently swap it.

### 3.4 Version comparison

Compare parsed `SemVer(major, minor, patch)` directly — lexicographic
tuple comparison via `Comparable`. The installed version is read from
`BuildConfig.VERSION_NAME` and parsed with the same regex as the
tag. No dependency on any `versionCode` formula: the formula can change
in `build.gradle.kts` without breaking the updater.

If either the remote tag or `BuildConfig.VERSION_NAME` fails the strict
`v?(\d+)\.(\d+)\.(\d+)` parse, the check aborts with
`CheckResult.ERROR` (§6.4).

---

## 4. Integration Touchpoints

| Consumer | File | Change |
|----------|------|--------|
| Lifecycle trigger | `androidApp/src/main/java/com/cruxcoach/android/CruxCoachApp.kt` | Register `ProcessLifecycleOwner` observer + `ConnectivityManager` default-network callback that both feed `UpdateChecker.maybeCheck()` (§6.12) — only if self-updater enabled (§6.6) |
| WorkManager backstop | same | Enqueue the 24 h flex-interval `UpdateCheckWorker` once at startup as the fallback trigger |
| DI | `di/AppModule.kt` or `di/UpdaterModule.kt` (new) | Provide `UpdateChecker`, `ApkDownloader`, `IntegrityVerifier`, `ApkInstaller`, `InstallSourceGate`, `UpdateNotifier`, `UpdaterPinStore` singletons |
| Settings UI | `ui/settings/*` | New "App updates" section per §6.15: last-check + "Jetzt prüfen" (with 10 s soft rate-limit, §R2), `autoCheckEnabled` switch, `autoDownloadOnWifi` switch (default on, §6.14), `autoDownloadOnMobile` switch (default off), permission-nudge banner row (§6.13) when notifications blocked, badge + inline release-notes row when an update is pending (§6.10), info row when store-gated |
| Release-notes route | `ui/navigation/NavGraph.kt` + new screen | In-app screen opened by the notification tap; **not** a dialog |
| Manifest | `androidApp/src/main/AndroidManifest.xml` | Add `REQUEST_INSTALL_PACKAGES`, `POST_NOTIFICATIONS` (Android 13+), receiver for `PackageInstaller` callbacks, notification channel declaration at first launch |
| Strings | `values/strings.xml` + `values-de/strings.xml` | All update-related UI + notification strings (both locales per CLAUDE.md) |
| TOFU pin bootstrap | `UpdaterPinStore` (lazy, called on every app start) | Read `PackageInfo.GET_SIGNING_CERTIFICATES` of the installed CruxCoach package, write HMAC-sealed pin file (§6.5.1) only if pin file is absent OR MAC fails. No UI surface |
| Notification-permission nudge | `UpdateNotificationReliabilityHelper` (new) + Settings composable | Wraps `NotificationManagerCompat.areNotificationsEnabled()` + per-channel `IMPORTANCE_NONE` check; mirrors the existing `NotificationReliabilityHelper` API used by the Nostr coordinator. Recomputes on every `ON_RESUME` (§6.13) |
| Cert-mismatch handoff | new `Intent(ACTION_VIEW, ...)` launcher in `UpdaterRepository` | One-tap path to the Codeberg release page when §5.4.2 detects a pin mismatch (§5.4.3); never auto-overrides the pin |

New package: `com.cruxcoach.android.updater` containing
`UpdateChecker`, `UpdateCheckWorker`, `UpdateNotifier`,
`CodebergReleaseClient`, `VersionChecker`, `ApkDownloader`,
`IntegrityVerifier`, `ApkInstaller`, `InstallSourceGate`,
`UpdaterPinStore`, `UpdaterRepository`.

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
  constraints `NetworkType.CONNECTED` + `requiresBatteryNotLow`,
  explicitly **`setRequiresDeviceIdle(false)`** and
  **`setRequiresCharging(false)`**. We want this to actually run on
  OEM-killer devices (Xiaomi/Huawei/Oppo) where idle-constrained jobs
  are often deferred for days. Flex-interval means WorkManager picks
  its own moment inside the window — we never pin a clock time. If
  the device is offline *and* the user isn't using the app, the check
  simply never happens that cycle, and the next online/foreground
  event picks it up
- **First-run expedited**: the very first check after install uses
  `OneTimeWorkRequest` with `setExpedited(OUT_OF_QUOTA_POLICY_RUN_AS_NON_EXPEDITED_WORK_REQUEST)`
  so a fresh install knows within minutes (not 24 h) if it is already
  stale. Falls back to non-expedited if the system rejects the quota.
  Subsequent checks use the periodic worker above

Throttle: `UpdateChecker.maybeCheck()` drops any call made within
`MIN_CHECK_INTERVAL = 2 h` of the last successful network fetch. Manual
"Jetzt prüfen" bypasses the 2 h throttle but is itself soft-rate-limited
to **once per 10 seconds** at the UI layer — the button is disabled (and
shows a spinner) while a check is in flight and for 10 s after one
completes. This prevents a user tapping the button repeatedly from
becoming a small DoS against the Codeberg API.

Fetch path:
- `GET https://codeberg.org/api/v1/repos/CruxCoach/CruxCoach/releases?limit=10`
  — the org/repo pair is pinned via `BuildConfig.UPDATER_REPO_OWNER` and
  `BuildConfig.UPDATER_REPO_NAME` (§10), not user-configurable: changing
  the source repo would invalidate the TOFU cert pin
- Parse JSON; apply the stable-release filter (§6.11): skip any entry
  where `prerelease` or `draft` is `true`, or where the tag has a suffix
  after the `MAJOR.MINOR.PATCH` segment. Pick the first remaining entry
- If none remain → record `NO_UPDATE_STABLE` and stop
- If the selected `tag_name` is equal or older than installed → record
  `NO_UPDATE` and stop
- If newer → write `pendingUpdate`, trigger §5.2

Offline / transient failure: logged as `ERROR` with a timestamp; no
user surface (§6.4). The next trigger retries.

### 5.2 User Prompt — Single Notification, Three Content States

When a newer stable release is detected, the updater posts a
**persistent notification**. It does **not** open any dialog, banner,
snackbar, or modal in the app. The same notification transitions
through three content states as the pipeline progresses (see §6.14 for
the state machine), always on the same channel / same id so
`notify()` replaces in-place:

**State 1 — `PENDING_DOWNLOAD`** (auto-download disabled or metered-only
on cellular):
- Title: "Update verfügbar: v<version>"
- Body: "<APK size> — Tippen zum Herunterladen"
- Primary action: "Herunterladen" (starts §5.3)
- Secondary action: "Details" (opens the in-app release-notes screen)

**State 2 — `DOWNLOADING`** (auto-download just triggered or user
tapped "Herunterladen"):
- Title: "Update v<version> lädt…"
- Body: progress bar + "<X> %"
- Updated at most every 2 s
- Primary action: "Abbrechen"

**State 3 — `READY_TO_INSTALL`** (download verified):
- Title: "Update v<version> bereit"
- Body: "Tippen zum Installieren"
- Primary action: "Installieren" (launches §5.5 → system consent)
- Secondary action: "Details" (release-notes)

Swipe to dismiss is only meaningful in states 1 and 3 — during
`DOWNLOADING` the notification is `setOngoing(true)` so the user cannot
accidentally dismiss an active download. Dismissal in state 1 or 3
records `dismissedAt`; re-armed per §6.10.

Inside the app: the Settings "App updates" section mirrors the
notification state with a matching inline row (badge + release-notes
snippet + the same primary action as the current state). No popup ever
appears on top of any other screen.

### 5.3 Download

Download starts via one of two paths:
- **Auto-triggered** (default, §6.14): the check flow detects a newer
  release while on Wi-Fi and `autoDownloadOnWifi == true`, and enqueues
  immediately. Notification boots directly into state 2 `DOWNLOADING`
- **User-initiated**: the user taps "Herunterladen" on a state-1
  `PENDING_DOWNLOAD` notification or on the inline Settings row

`pendingDownloadId` persistence handles **crash recovery** for both
paths: if the process dies mid-download, the next app start queries
`DownloadManager` with the saved ID and picks up where we left off
rather than re-fetching.

Pre-flight (before starting the download):
- Verify `StatFs(cacheDir).availableBytes >= apkSizeBytes + 16 MiB
  headroom`. If false → surface "Kein Speicher frei" in the
  notification-replaces-notification slot, log, do not enqueue. (§R4
  — DownloadManager otherwise fails silently mid-stream when storage
  fills.)
- Verify current network matches user policy (§6.7) before enqueueing;
  on mobile-data + Wi-Fi-only toggle set, surface "Wi-Fi nötig —
  später erneut versuchen".

Enqueue:
- `DownloadManager.Request` on the APK URL
- Target: `context.cacheDir / "pending-update-<versionName>.apk"`
  (not external-files-dir — CruxCoach APKs are small, and cacheDir is not world-readable)
- `setAllowedNetworkTypes(NETWORK_WIFI)` unless user override
- `setNotificationVisibility(VISIBILITY_HIDDEN)` — we post our own progress UI
- Persist `downloadId` in `UpdaterState.pendingDownloadId`; on next app
  start, if the download is still running, reuse the ID; if it
  completed while the process was dead, run verify (§5.4) from the
  existing cache file; if `DownloadManager` no longer knows the ID,
  clear the field and require the user to re-initiate

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
  == UpdaterPinStore.get().pinCertSha256
```

The pin comes from the HMAC-sealed file (§6.5); if the file is absent
or its MAC does not verify, the store re-TOFUs against the currently
installed cert (§6.5.1) and uses that. Mismatch against a **valid**
pin → delete APK, log, surface the mismatch screen per §5.4.3. Do NOT
offer to override the pin from the UI.

### 5.4.3 Cert-Mismatch Recovery Surface

A cert-pin mismatch is effectively the worst-case the TOFU model
defends against — but it is ALSO what the user sees on a legitimate,
intentional signing-key rotation. Without a recovery path the user
would be stuck on the old version forever, because we refuse to
auto-accept a new cert.

Surface (notification + in-app):
- Title: "Update kann nicht automatisch installiert werden"
- Body: "Die Signatur der neuen Version unterscheidet sich von der
  installierten. Das kann ein legitimer Schlüsselwechsel oder ein
  Angriff sein. Bitte manuell von Codeberg neu installieren."
- Primary action button: **"Auf Codeberg öffnen"** →
  `Intent(ACTION_VIEW, apkAsset.releasePageUrl)` — opens the user's
  browser directly on the Codeberg release page for the new version.
  From there the user downloads the APK themselves and installs via
  system file manager. Android's same-signature install rule handles
  the actual accept/reject: a legitimate new signer from the same
  project will install cleanly on a fresh install; a malicious APK
  with a different signer will be rejected by the platform.
- Secondary: "Später" — dismisses the error notification; state stays
  as "blocked" and we do NOT fire the standard re-arm cadence (§6.10)
  on a blocked update. The user gets exactly one reminder per new
  mismatched version

This is **not** an override of TOFU — the updater still refuses to
install the mismatched APK itself. It is a *handoff* to the user +
the platform's own trust checks. A legitimate rotation is recoverable
in two taps (Open → system file-manager install); a MITM is caught by
Android refusing the install; a compromised signer exfiltration is
a strictly worse attack than what the TOFU pin was ever defending
against and is out of scope.

### 5.5 Install

- `PackageInstaller.createSession()` with `MODE_FULL_INSTALL`
- Write APK stream into the session
- `session.commit(statusReceiver)` with a `PendingIntent` to `ApkInstallStatusReceiver`
- Receiver handles `PackageInstaller.STATUS_*`:
  - `STATUS_PENDING_USER_ACTION` → launch the system consent dialog
  - `STATUS_SUCCESS` → notification "Update installiert"
  - `STATUS_FAILURE_INVALID` → "APK ist beschädigt oder inkompatibel —
    bitte erneut herunterladen". (Note: this code covers corrupt APK,
    unsupported ABI, and API-level mismatch. Signer mismatch is a
    separate code — `STATUS_FAILURE_CONFLICT` with
    `EXTRA_STATUS_MESSAGE` containing "INSTALL_FAILED_UPDATE_INCOMPATIBLE"
    — and in any case we already caught it in §5.4.2 before reaching
    `PackageInstaller`. So we do not promise "Signatur" in the INVALID
    copy — it would be misleading in every scenario where this code
    actually fires.)
  - `STATUS_FAILURE_STORAGE` → "Kein Speicher frei — bitte Platz schaffen und erneut versuchen"
  - `STATUS_FAILURE_CONFLICT` → "Installation kollidiert mit vorhandener App — neu installieren erforderlich"
  - `STATUS_FAILURE_ABORTED` → user cancelled the system consent dialog;
    keep `pendingUpdate`, keep cached APK, but cancel the install
    session and return to the "Installieren"-ready state. Next
    notification re-arm cadence (§6.10) applies normally. No error
    surface — cancel is a user choice, not a failure.
  - `STATUS_FAILURE_BLOCKED` → "Installation durch System blockiert
    (z. B. Play Protect)" — surface with a "Details" link into the
    in-app release-notes route
  - Other `STATUS_FAILURE*` → generic "Installation fehlgeschlagen"
    + last-check screen shows the raw code for bug reports

On terminal success: clear `pendingUpdate`, delete cached APK, reset
`pendingDownloadId`, cancel any re-arm notification for this version.

On `STATUS_FAILURE_ABORTED` (user cancelled): keep all state. The user
can re-tap the notification or "Installieren" in Settings without
re-downloading.

On any other `STATUS_FAILURE_*`: delete cached APK (it may be corrupt),
keep `pendingUpdate`, reset `pendingDownloadId`. User must re-download.

---

## 6. Design Decisions

### 6.1 Update Source — Codeberg Releases API (list, not `/latest`)

**Decision:** Query the **list endpoint**
`/api/v1/repos/CruxCoach/CruxCoach/releases?limit=10`, filter client-side
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
- **Conditional GET via `If-None-Match: <lastEtag>`** when `UpdaterState.lastCheckEtag` is set; on `304 Not Modified` the call is a no-op (bump both `lastCheckAt` and `lastCheckBootRealtime` so the throttle counts the round-trip, keep result). On `200 OK`, store the new `ETag` header in `UpdaterState.lastCheckEtag` alongside the parsed release. Codeberg (Gitea) serves proper ETags on the releases endpoint; falling back gracefully if the header is missing is fine — the 2 h throttle (§6.12) is the second line of politeness
- 10 s connect timeout, 15 s read timeout
- Treat any non-2xx (except 304) as a transient failure → retry on next trigger
- Ten entries is plenty: even if every second release were a dev build,
  ten covers five stable releases back — more than we will ever need to
  skip past

### 6.2 Version Comparison — SemVer Tuple, Strict `>`

**Decision:** Compare `SemVer(major, minor, patch)` parsed from both
`BuildConfig.VERSION_NAME` and the remote tag. Strict greater-than;
reject equal or lower.

**Why:** Using `(major, minor, patch)` tuples decouples the updater
from the `versionCode` formula in `build.gradle.kts`. That formula has
already evolved once (see `project_zapstore_release_strategy.md` in
memory) and will evolve again; every change would otherwise be a
silent hazard to released updater code. Android's platform downgrade
protection still applies at install time as a second line of defense.

**How to apply:**
- Tag parser: `v?(\d+)\.(\d+)\.(\d+)` → `SemVer(major, minor, patch)`
- `BuildConfig.VERSION_NAME` parsed with the same regex — the installed
  version is the ground truth, not `BuildConfig.VERSION_CODE`
- Parse failure on either side → `CheckResult.ERROR`, no prompt
- Pre-release / suffix rejection stays in §6.11 (tag shape check); it
  is orthogonal to version comparison

### 6.3 Release Notes — Markdown Body, SHA-256 as Separate Asset

**Decision:** Release notes come from `body` (markdown). The APK SHA-256
is a separate release asset (`cruxcoach-release.apk.sha256`), NOT parsed
from the body. A release missing the `.sha256` asset is treated as
malformed and skipped.

**Why:** Scraping a hash out of free-form prose is brittle — a
writer forgetting a backtick, swapping ` ` for `—`, or rewording the
surrounding line is enough to break verification. Machine-readable data
(the hash) and human-readable data (release notes) have different
correctness requirements; they belong in different fields.

**How to apply:**
- `CodebergReleaseClient.fetchLatestStable()` returns a parsed
  `CodebergRelease` with explicit `apkAsset` and `sha256Asset` fields;
  missing `sha256Asset` → `CheckResult.ERROR` (release malformed)
- `sha256` asset is downloaded as plain text, first 64-hex token parsed
  with case-insensitive compare; rest of the file is ignored (lets us
  keep coreutils `sha256sum > file.apk.sha256` format)
- `body` is rendered with the existing markdown renderer in the in-app
  release-notes route; nothing is stripped or post-processed
- Release process (§10): `sha256sum cruxcoach-release.apk > cruxcoach-release.apk.sha256`
  is a required CI step, uploaded alongside the APK

### 6.4 Bootstrap Failure Handling — Silent Retry, No User Error

**Decision:** Network failures, JSON parse errors, and tag parse errors are logged at debug and stored as `CheckResult.ERROR` with a timestamp. No user-facing message. The next periodic run retries.

**Exception:** signature mismatch during verify is **always** surfaced and never auto-retried — it is a trust-path violation, not a network blip.

**Why:** A release polling app that pops errors on flaky Wi-Fi is hostile. Climbers in gyms have flaky Wi-Fi. The security-critical path (signature) is the one users must see.

**How to apply:**
- Distinguish transient (network, parse) from security (signature, SHA-256) failures in `UpdaterState`
- Settings screen shows `lastCheckAt` + a subtle "last check failed" hint only if `ERROR` state has persisted >3 days

### 6.5 Cache & Persistence — DataStore + HMAC-sealed Pin File

**Decision:** Bulk updater state in `DataStore<Preferences>`. The TOFU
cert pin lives in a separate file (`files/updater_pin.bin`) sealed with
an HMAC-SHA256 over its contents, the HMAC key held in the Android
Keystore (strongbox-backed when available, software-backed otherwise).
Cached APK in `cacheDir`, deleted after install or on any verification
failure.

**Why:**
- `androidx.security:security-crypto` (which provides
  `EncryptedSharedPreferences`) has been effectively unmaintained for
  years; Google flagged a replacement as TBD and the library still
  blocks AndroidX upgrades in some configurations. Taking on that
  dependency for a single hash is net-negative.
- The TOFU pin is a **public** value (it is derived from the installed
  APK's signing cert, which every app on the device could already read
  via `PackageManager`). Confidentiality is not the threat — **integrity
  is**. Encryption would not stop an on-device attacker with code exec
  from writing a new encrypted blob. An HMAC-sealed file stops a
  filesystem-write-only attacker (e.g. ADB shell without root) from
  silently swapping the pin, because they cannot forge the MAC without
  access to the Keystore key.
- Splitting trust anchor from telemetry also means a corrupted
  DataStore (mid-write crash) cannot invalidate the pin: the worst case
  is we re-check Codeberg once.

**How to apply:**
- `UpdaterPinStore.get(): Result<Pin>` — reads the file, recomputes the
  HMAC, rejects on mismatch (treated as first-launch → TOFU re-runs;
  see §R7 / §6.5.1 below)
- `UpdaterPinStore.set(pin)` — writes atomically via `File.renameTo`
  from a tempfile, computes MAC with the Keystore key, persists
- HMAC key tag: `cruxcoach.updater.pin.hmac.v1`; generated lazily on
  first pin write; never rotated in v1 (key rotation is explicitly
  out-of-scope, §8)

#### 6.5.1 TOFU Timing — First Post-Install Launch Only

The pin is written **exactly once**, on the first launch after install
where no valid pin file exists. In practice that is the first launch of
the app after the user initially sideloads / installs from Codeberg. If
the user later clears app data the pin file is wiped; the next launch
will re-TOFU against whatever APK is currently installed — which is
acceptable because clearing app data is a deliberate local action and
does not expand the attacker surface (an attacker with the ability to
swap the installed APK between clear-data and next-launch already has
the capabilities of a platform-level compromise).

Boot path:
1. On every app start, `UpdaterPinStore.get()` is called.
2. If it returns a valid pin, done.
3. If it returns "missing" or "MAC mismatch" (indistinguishable and
   treated identically — a tampered file is semantically the same as
   a missing one: both force a re-TOFU), compute
   `sha256(currentSigningCert)` and `set()` it.
4. No user prompt; no delay; no dialog.

The pin is therefore authoritative for all subsequent verification, and
any divergence — even one caused by the user deliberately reinstalling
with a new signing cert — surfaces as the "Signatur hat sich geändert"
message in §6.9.

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

### 6.7 Network Policy — Wi-Fi Default, Two Independent Knobs

**Decision:** Two separate user toggles govern network usage, both
shown in the Settings "App updates" section (§6.15):

1. `autoDownloadOnWifi` (default **on**, §6.14) — auto-triggers a
   background download as soon as Wi-Fi is available
2. `autoDownloadOnMobile` (default **off**, §6.14) — explicit opt-in
   for users on unlimited mobile data plans

On the `DownloadManager.Request` level, this resolves to:
- Both off → `NETWORK_WIFI` only; download only starts after user tap
- Wi-Fi on, mobile off → `NETWORK_WIFI` only; auto-triggered on Wi-Fi,
  otherwise waits
- Wi-Fi on, mobile on → `NETWORK_WIFI | NETWORK_MOBILE`; auto-triggered
  on whichever transport is currently active

**Why:** Defaults respect data plans (auto-download on Wi-Fi is free
to 99 % of users; mobile-data auto-download is only the right default
for unlimited-plan users, which we cannot detect). Splitting Wi-Fi and
mobile into two toggles, rather than a single "also mobile" override,
makes the opt-in explicit and reversible: a user who travels abroad
can disable mobile auto-download without losing Wi-Fi auto-download.

### 6.8 Consent Model — System Dialog Every Install

**Decision:** No silent install in 0.1.2. Every install triggers the Android consent dialog via `PackageInstaller.STATUS_PENDING_USER_ACTION`.

**Why:** `UPDATE_PACKAGES_WITHOUT_USER_ACTION` is a Google-sensitive permission, F-Droid reviewers see it with suspicion. The dialog is one extra tap; the UX cost is small, the trust cost of skipping it is not.

### 6.9 Key Rotation — No Auto-Override, One-Tap Handoff

**Decision:** If the signing key is ever rotated, the TOFU pin
mismatches and the self-updater refuses to install. The user is given
a direct one-tap path to the Codeberg release page (§5.4.3), where
they download and install themselves. Android's platform same-signature
rule is the actual accept/reject gate — not our pin.

**Why:** Auto-overriding our own pin would make TOFU worthless — an
attacker with a stolen key would need no further effort to MITM
updates. Forcing manual `adb shell pm clear` would strand every
non-technical user on a rotation. The handoff pattern in §5.4.3 is the
middle path: we do not bless the new signer, but we do not block the
user — we let the platform decide. Legitimate rotation works with two
taps (Open → Install); a MITM is rejected by the platform.

> **Release blocker:** `docs/KEY_ROTATION.md` does not yet exist. It
> must be created before 0.1.2 ships, describing:
> 1. The §5.4.3 user-facing flow ("Auf Codeberg öffnen" button)
> 2. When the user should vs. should NOT accept the reinstall ("if
>    you didn't expect a signature change, ask first in the Dev-Chat")
> 3. The developer procedure for rotating the Android signing key
>    with coordination warning (announce before pushing the first
>    release with the new cert)
> See §10 checklist.

### 6.10 User Surface — Notification Only, No Dialog

**Decision:** The update offer is a **persistent system notification**.
No dialog, banner, snackbar, or modal ever appears inside the app to
announce an available update. Inside the app, the Settings screen shows
a small badge and a non-modal row with the release notes + a
"Herunterladen" button. The only dialog anywhere in the flow is
Android's own install-consent dialog at the very end — that one is
platform-required and cannot be suppressed.

If the user swipes the notification away, it is re-armed on a cadence:

1. First re-arm at **+24 h**
2. Then every **72 h** for up to **10** re-arms (~1 month coverage)
3. After the cap: re-arm **once every 30 days** indefinitely

Stage 3 replaces what would otherwise be permanent silence for a user
on a long-running old version. A monthly reminder is neither nagging
(≪ daily) nor dead-end (not ∞ silence) and aligns with most users'
"I'll deal with it this weekend" update rhythm.

Cadence **fully resets** (`reArmCount = 0`) and the notification
re-fires immediately under either signal: (a) a *newer* stable release
appears, or (b) the user opens Settings and manually taps "Jetzt
prüfen". Once the user taps "Herunterladen" or the update is installed,
the notification is cancelled permanently for that version.

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
- Single coalescing throttle, **clock-skew-immune**: the elapsed delta
  is measured against `SystemClock.elapsedRealtime()`, not wall-clock
  `System.currentTimeMillis()`. Wall-clock is persisted separately as
  `lastCheckAt` only for display. A device with a wrong system clock
  (NTP off, timezone stuck, battery-pulled reboot) therefore cannot
  either skip checks forever or fire on every trigger:
  ```kotlin
  val sinceMs = SystemClock.elapsedRealtime() - state.lastCheckBootRealtime
  if (sinceMs < MIN_CHECK_INTERVAL_MS && !isManual) return
  ```
- `lastCheckBootRealtime` resets to 0 across reboots (that is what we
  want — a reboot is a natural moment to re-check)
- No queue, no retry — caller just moves on
- WorkManager uses a `flex-interval`, not a fixed `initialDelay` — the
  system gets to choose the exact moment inside the window
- If the network capability check fails (offline), the trigger path
  exits early before a single HTTP call is made

### 6.13 Notification-Permission Nudge

**Decision:** The updater depends on notifications to reach the user.
If the system- or channel-level notification permission is denied, the
whole feature is silent without any feedback. Surface a permission
nudge in the Settings "App updates" section — **not a popup**, a
banner row identical in pattern to `NotificationReliabilityBanner` —
whenever either of the following is true:

1. `NotificationManagerCompat.areNotificationsEnabled() == false`
   (app-level denied or Android 13+ runtime permission not granted)
2. `notificationManager.getNotificationChannel(UPDATE_CHANNEL_ID).importance == NONE`
   (channel explicitly muted in system settings)

**Why:** A silent failure mode at the transport layer destroys the
entire reliability contract. A user who dismissed the POST_NOTIFICATIONS
prompt once (tap, tap, gone) has no other way to learn the feature
exists. A one-time banner with a deeplink into system settings is the
smallest intervention that closes this gap.

**How to apply:**
- New helper `UpdateNotificationReliabilityHelper.isBlocked(context): Boolean`
  wrapping both checks above; mirrors the existing
  `NotificationReliabilityHelper` API used by the Nostr coordinator
- In the Settings "App updates" section, if `isBlocked == true`, render
  a compact banner above the normal rows with:
  - Title: "Update-Hinweise deaktiviert"
  - Body: "Benachrichtigungen für dieses Feature sind ausgeschaltet — du verpasst neue Versionen."
  - Primary action: "Aktivieren" → opens
    `Settings.ACTION_APP_NOTIFICATION_SETTINGS` for the app (Android
    8+) or channel-specific `ACTION_CHANNEL_NOTIFICATION_SETTINGS` if
    the channel exists but is muted
- Recomputes on every `ON_RESUME` (user returns from system settings
  without needing a nav round-trip), exactly like the Nostr banner
- Dismissible per-session only — we do not persist a "hide forever"
  flag; this has to stay nudging or users never fix it

### 6.14 Auto-Download on Wi-Fi (Default On)

**Decision:** The updater downloads a newly-detected stable APK
automatically when the device is on **Wi-Fi** and the feature is
enabled (default: **on**). Completed downloads are verified and held
ready. The notification then posts as "Installieren bereit" and tapping
it goes directly to the system install-consent dialog. No
mobile-data auto-download; no auto-install (consent dialog is
non-negotiable, §6.8).

**Why:** The "discover → download → install" path without auto-download
takes 5 taps and makes the user wait minutes while the download runs.
With auto-download on Wi-Fi, that collapses to **2 taps** (notification
tap → system "Install"), and the user never waits for bytes to arrive —
they only ever see "ready to install." Restricting it to Wi-Fi
preserves the data-volume-safety default; auto-install is explicitly
excluded because (a) Android forbids it without the Google-sensitive
`UPDATE_PACKAGES_WITHOUT_USER_ACTION` permission, and (b) the system
consent dialog is the final trust anchor and intentionally cannot be
suppressed.

**How to apply:**
- New `UpdaterState.autoDownloadOnWifi: Boolean = true` (default on)
- `UpdaterState.autoDownloadOnMobile: Boolean = false` (separate
  toggle, default off; exists so a user on an unlimited data plan can
  explicitly opt in)
- After §5.1 detects a newer stable release, `UpdateChecker` inspects
  the current transport via
  `ConnectivityManager.getNetworkCapabilities(activeNetwork)`:
  - On Wi-Fi **and** `autoDownloadOnWifi` → enqueue §5.3 immediately;
    initial notification posts with state "Wird heruntergeladen…"
  - On cellular **and** `autoDownloadOnMobile` → same
  - Else → post the pre-download notification ("Update verfügbar —
    Tippen zum Herunterladen") and wait for the user tap, exactly as
    before
- Notification is a single entity that transitions through three
  content states as the pipeline progresses (not three separate
  notifications — one channel, same id, `NotificationManager.notify()`
  replaces):
  1. `PENDING_DOWNLOAD` — "Update verfügbar: vX.Y.Z — Tippen zum Herunterladen"
  2. `DOWNLOADING` — "Update vX.Y.Z lädt… (42 %)" with progress bar;
     updated at most every 2 s to avoid notification spam
  3. `READY_TO_INSTALL` — "Update vX.Y.Z bereit — Tippen zum Installieren"
- If `DOWNLOADING` fails (network loss, storage full mid-stream, user
  cancelled), fall back to `PENDING_DOWNLOAD` with a "Fehler beim
  Herunterladen — erneut versuchen" suffix. The cached partial APK is
  deleted; the `pendingDownloadId` is cleared
- Users on metered connections with `autoDownloadOnMobile = false` get
  exactly the legacy behavior — no surprise data usage

### 6.15 Settings — "App updates" Section Layout

**Decision:** All updater-related user-facing controls live in a single
"App updates" section in Settings. There is **no onboarding dialog**,
no first-run wizard, no consent prompt — the defaults are sensible
(Wi-Fi auto-download on, mobile-data off, auto-check on), and the
Settings section is the discoverable escape hatch for users who want
to change them.

**Section contents (in order):**

```
─── App updates ────────────────────────────────────────────────
   [permission nudge banner — only when §6.13 applies]
   
   Status
     Zuletzt geprüft: vor 2 Std.         [Jetzt prüfen]
     (or: "Zuletzt geprüft: noch nie" for fresh installs)
   
   Automatisch prüfen                         [ON  ●]
     Aus: der Updater prüft nie mehr selbst. Du musst
     "Jetzt prüfen" manuell drücken.
   
   Automatisch herunterladen (WLAN)           [ON  ●]
     Lädt Updates im Hintergrund, sobald WLAN verfügbar ist.
     Installation erfordert weiterhin einen Tap.
   
   Auch über mobile Daten laden               [OFF ○]
     Nur aktivieren, wenn du einen unbegrenzten Datentarif hast.
   
   [pending-update inline row, only when pendingUpdate != null]
     Version X.Y.Z verfügbar (ca. 12 MB)
     [Release-Notes]  [Herunterladen / Installieren]
─────────────────────────────────────────────────────────────────
```

**Why all toggles default to sane values:** Requiring a first-run
dialog would mean either (a) interrupting the user on first app start
(bad — we are a climbing app, not a cloud-service onboarding) or (b)
deferring configuration until they find Settings (bad — most users
never will). Defaults that work silently for 95 % of users + an
obvious section for the other 5 % is the shortest path to "everyone
gets updates."

**How to apply:**
- Implemented as a single `UpdateSettingsScreen` composable under the
  existing `ui/settings/` tree
- Uses the same `SettingRow` / `SettingSwitch` components already in
  use for other settings — no new widgets
- Pending-update row uses the same `NotificationReliabilityBanner`
  visual language so the section reads as coherent
- When `InstallSourceGate.selfUpdateAllowed() == false` (Zapstore
  install), the entire section is replaced by the single info row from
  §6.6 — no toggles, no status, no pending row

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
- **Auto-install** without the system consent dialog — explicitly out of scope (§6.8). Auto-*download* on Wi-Fi IS now in scope (§6.14, default on); the install step always requires user tap + system consent
- **Auto-download on mobile data by default** — opt-in toggle exists (§6.14) but defaults to off; we never silently consume metered bytes
- **Onboarding dialog / first-run wizard** for updater settings — defaults are sane, Settings is the discoverable escape hatch (§6.15)
- **Changelog across multiple versions** (show only the latest release's notes, not cumulative)
- **Rollback UI**

---

## 9. Dependencies

- `androidx.work:work-runtime-ktx` — WorkManager (already present)
- `androidx.datastore:datastore-preferences` — bulk updater state
  (add if not already present; it is the standard replacement for
  `SharedPreferences` in modern Android)
- Android Keystore (`KeyGenParameterSpec.Builder`, `KeyProperties.PURPOSE_SIGN`)
  — HMAC-SHA256 key for sealing the TOFU pin file (§6.5). Platform API,
  no extra dependency
- Existing OkHttp client + JSON parser (Moshi or kotlinx-serialization — whatever is in use)
- `android.app.DownloadManager` (platform)
- `android.content.pm.PackageInstaller` (platform)
- **Removed from the original skeleton:** `androidx.security:security-crypto`
  (see §6.5 — effectively unmaintained, and confidentiality is not the
  threat this feature defends against)
- No new third-party runtime dependencies expected

---

## 10. Delivery Checklist (for full spec later)

- [ ] Concrete class names, package placement, function signatures
- [ ] `androidApp/build.gradle.kts` — add three `buildConfigField`s the
      updater reads: `UPDATER_REPO_OWNER = "CruxCoach"`,
      `UPDATER_REPO_NAME = "CruxCoach"`,
      `UPDATER_API_BASE = "https://codeberg.org/api/v1"`. Hardcoded for
      release builds; debug builds may override via `local.properties`
      to test against a fork
- [ ] Error handling matrix (all `PackageInstaller.STATUS_*` codes mapped
      to user messages, localized DE/EN per §5.5)
- [ ] Test plan:
  - Unit: tag parser, `SemVer` comparison, SHA-256 helper, cert-pin compare,
    install-source gate logic, HMAC pin-file seal/verify, ETag round-trip,
    notification three-state transitions (`PENDING_DOWNLOAD` → `DOWNLOADING`
    → `READY_TO_INSTALL` and the `DOWNLOADING` → `PENDING_DOWNLOAD` failure
    fallback, §6.14), re-arm cadence including the post-cap monthly stage
    (§6.10), `ElapsedRealtime` throttle ignoring wall-clock changes (§6.12),
    auto-download decision matrix across Wi-Fi/cellular × `autoDownloadOnWifi`
    × `autoDownloadOnMobile` (§6.14), `UpdateNotificationReliabilityHelper.isBlocked`
    matrix across app-disabled / channel-`IMPORTANCE_NONE` / both-allowed (§6.13)
  - Integration: mock Codeberg JSON (incl. 304 Not Modified), DownloadManager
    stub, verify/install happy path + each failure path incl.
    `STATUS_FAILURE_ABORTED`; auto-download triggered from a fake "Wi-Fi
    became available" event with notification booting straight into
    `DOWNLOADING`; cert-mismatch path (§5.4.3) opens
    `Intent(ACTION_VIEW, releasePageUrl)` and does NOT install; permission-nudge
    banner appears/disappears across `ON_RESUME` when the simulated
    permission flips
  - Manual: install from Zapstore and verify Settings shows "deaktiviert";
    install from Codeberg APK and verify end-to-end update flow; clear
    app data and verify TOFU re-runs silently; deny POST_NOTIFICATIONS at
    runtime and verify the §6.13 banner shows up in Settings with a working
    "Aktivieren" deeplink; trigger a cert-pin mismatch on a test build and
    verify the §5.4.3 "Auf Codeberg öffnen" path lands on the right release
    page; OEM-killer device check (Xiaomi or Huawei in MIUI/EMUI battery
    profile "strict") — verify the WorkManager backstop still fires within
    the 24 h window per §5.1 / §6.12
- [ ] Migration: existing installs have no pin file → TOFU on first
      launch after 0.1.2 upgrade (§6.5.1)
- [ ] Rollout / kill-switch: remote flag to disable the updater (e.g. a
      bool in a known-relay Nostr kind or a static URL) — TBD, not
      strictly required for v1
- [ ] **Release blocker — `docs/KEY_ROTATION.md`:** must be written
      before 0.1.2 ships. Content: (1) why TOFU pin is in place, (2)
      user procedure on intentional key rotation = uninstall + reinstall
      + clear-data OR fresh install from the new APK, (3) developer
      procedure for rotating the Android signing key with coordination
      warning
- [ ] Release process (add to `docs/RELEASE.md`):
  - `sha256sum cruxcoach-release.apk > cruxcoach-release.apk.sha256`
    is a required CI step
  - Both `cruxcoach-release.apk` and `cruxcoach-release.apk.sha256`
    must be uploaded as assets on the Codeberg release
  - Release body is markdown-only — no machine-parsed data embedded
