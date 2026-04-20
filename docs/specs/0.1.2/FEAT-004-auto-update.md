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
the Android `PackageInstaller` with a user-consented install dialog.

If CruxCoach was installed via Zapstore (or another known store),
runtime detection disables the self-updater entirely to avoid
conflicting notifications.

### Goals

- Detect new releases on Codeberg automatically, ~24 h cadence
- Download the APK on Wi-Fi (user-overridable) with resume/retry
- Verify the download's SHA-256 against the release manifest
- Verify the download's signing certificate against a locally-pinned hash (TOFU)
- Surface an in-app notification with release notes parsed from the Codeberg release body
- Hand off to `PackageInstaller` session API with system consent prompt
- Hard-disable the updater when installed via Zapstore / F-Droid / Play

### Non-Goals

- **No silent install.** `UPDATE_PACKAGES_WITHOUT_USER_ACTION` is not requested in v1. Every install goes through the system consent dialog.
- **No beta / nightly channels.** Only `latest` from Codeberg's release list.
- **No in-app rollback.** If an update breaks, users reinstall the previous APK manually from Codeberg.
- **No delta / patch updates.** Full APK each time.
- **No signed update manifest of our own.** We rely on Codeberg's HTTPS + Android's same-signature install rule + the TOFU cert pin. A Nostr-signed manifest is possible future hardening.
- **No build flavors.** One APK ships everywhere; coexistence is handled at runtime (§6.6).

---

## 2. Architecture

```
    ┌──────────────────────────────┐
    │  Codeberg Release API        │
    │  /api/v1/repos/.../releases/ │
    │  latest                      │
    └──────────────┬───────────────┘
                   │  JSON (version, assets[], body)
                   ▼
    ┌──────────────────────────────┐
    │  UpdateCheckWorker           │
    │  (PeriodicWork, 24 h)        │
    │  + manual "Check now"        │
    └──────────────┬───────────────┘
                   │  UpdateInfo
                   ▼
    ┌──────────────────────────────┐     newer?
    │  VersionChecker              │──── no ───▶ drop
    │  (strict > vs BuildConfig)   │
    └──────────────┬───────────────┘
                   │  yes
                   ▼
    ┌──────────────────────────────┐
    │  Foreground UI: Update Dialog│
    │  (release notes, confirm)    │
    └──────────────┬───────────────┘
                   │  user: "Download"
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
    │  ApkInstaller                │
    │  PackageInstaller session,   │
    │  USER_ACTION_REQUIRED        │
    └──────────────────────────────┘
```

### Core Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Update source | Codeberg Releases API (`/api/v1/repos/<org>/cruxcoach/releases/latest`) | No extra infra; API already provides version, assets, release body |
| Scheduling | WorkManager `PeriodicWorkRequest`, 24 h, `NetworkType.CONNECTED`, `requiresBatteryNotLow` | No custom alarm plumbing; respects Doze |
| Integrity anchor | SHA-256 of APK (from release description) + TOFU signing-cert pin | Defense in depth: detects server compromise even if attacker controls SHA-256 |
| Install mechanism | `PackageInstaller` session API, `USER_ACTION_REQUIRED` | No `UPDATE_PACKAGES_WITHOUT_USER_ACTION` permission in v1 |
| Store coexistence | Runtime `getInstallSourceInfo()` check; hard-disable | Single APK; no flavor duplication |
| Cert pin strategy | TOFU: read from installed app on first launch | Survives CI/release without hardcoding; manual reinstall on key rotation |
| Downgrade policy | Strict `versionCode > installed`; reject equal or lower | Defence against stale-manifest MITM |

---

## 3. Data Model

### 3.1 Codeberg Release JSON (subset we consume)

```json
{
  "tag_name": "v0.1.2",
  "name": "v0.1.2",
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

- `tag_name` → version parsed from `v<major>.<minor>.<patch>`
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
    val lastCheckResult: CheckResult,  // SUCCESS | NO_UPDATE | ERROR
    val pendingDownloadId: Long?,      // DownloadManager ID if download in progress
    val pendingUpdate: UpdateInfo?,    // null once consumed or cleared
    val userCheckNetworkOverride: Boolean, // default false = Wi-Fi-only
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
| WorkManager bootstrap | `androidApp/src/main/java/com/cruxcoach/android/CruxCoachApp.kt` | Enqueue `UpdateCheckWorker` once at startup if self-updater enabled (see §6.6) |
| DI | `di/AppModule.kt` or `di/UpdaterModule.kt` (new) | Provide `UpdateChecker`, `ApkDownloader`, `IntegrityVerifier`, `ApkInstaller`, `InstallSourceGate` singletons |
| Settings UI | `ui/settings/*` | New "App updates" section: last-check timestamp, "Check now" button, Wi-Fi-only toggle, "auto-update check" toggle, info row when store-gated |
| Manifest | `androidApp/src/main/AndroidManifest.xml` | Add `REQUEST_INSTALL_PACKAGES`, receiver for `PackageInstaller` callbacks |
| Strings | `values/strings.xml` + `values-de/strings.xml` | All update-related UI strings (both locales per CLAUDE.md) |
| Cert-pin bootstrap | App startup or first settings-open | Read `PackageInfo.GET_SIGNING_CERTIFICATES` of the installed CruxCoach package, write `pinnedCertSha256` if absent |

New package: `com.cruxcoach.android.updater` containing
`UpdateCheckWorker`, `CodebergReleaseClient`, `VersionChecker`,
`ApkDownloader`, `IntegrityVerifier`, `ApkInstaller`,
`InstallSourceGate`, `UpdaterRepository`.

---

## 5. Update Pipeline

Five discrete stages, each idempotent, each persistable for resume.

### 5.1 Check

- `UpdateCheckWorker` (PeriodicWork, 24 h, `NetworkType.CONNECTED`, `requiresBatteryNotLow`)
- Fetch `https://codeberg.org/api/v1/repos/<org>/cruxcoach/releases/latest`
- Parse JSON; if `tag_name` equal or older than installed → record `NO_UPDATE` and stop
- If newer → write `pendingUpdate`, post in-app notification (system notification only if app in background)

Manual check: Settings → "Check now" enqueues a one-shot `OneTimeWorkRequest`, bypasses throttling.

### 5.2 User Prompt

Foreground dialog (or notification → dialog) shows:
- Version tag, release date, APK size
- Release notes rendered from `releaseNotesMarkdown` (use existing
  markdown renderer if present, else plain text)
- Two buttons: "Download" / "Later"

"Later" sets a 24 h snooze; the pending update is retained.

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

### 6.1 Update Source — Codeberg Releases API

**Decision:** Scrape `/api/v1/repos/<org>/cruxcoach/releases/latest`. No self-hosted manifest.

**Why:** Codeberg already provides version, asset URLs, upload timestamp, and release body out of the box. Self-hosting a signed manifest (Nostr-event style) is better defense but much more infra. Codeberg's rate limits at 1× / 24 h cadence are irrelevant.

**How to apply:**
- HTTP GET via existing OkHttp client
- `Accept: application/json`, explicit User-Agent `CruxCoach-Updater/<versionName>`
- 10 s connect timeout, 15 s read timeout
- Treat any non-2xx as a transient failure → retry on next scheduled run

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

### 6.6 Coexistence with Zapstore / F-Droid / Play — Runtime Hard-Disable

**Decision:** At app launch, `InstallSourceGate` queries `PackageManager.getInstallSourceInfo(packageName)` (API 30+, fall back to `getInstallerPackageName`). If the installer is in `{"dev.zapstore.app", "org.fdroid.fdroid", "com.android.vending"}`, the self-updater is fully disabled:
- `UpdateCheckWorker` is not enqueued
- The Settings "App updates" section shows: *"Updates erhältst du über Zapstore/F-Droid/Play. Der App-eigene Updater ist deaktiviert."* (localized in both locales)
- No toggle, no override

**Why:** Single-APK distribution stays simple; Signal's flavor approach doubles CI effort. Detecting at runtime catches sideload-from-Zapstore cleanly. Hard-disable (not warn-and-allow) prevents double notifications and confusion about "which version should win."

**How to apply:**
- `InstallSourceGate.selfUpdateAllowed(): Boolean` — checked by `UpdateCheckWorker`, `UpdaterRepository.checkNow()`, and the Settings UI
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
- **Beta / nightly channels**
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
