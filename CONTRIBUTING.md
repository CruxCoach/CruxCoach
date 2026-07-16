# Contributing to CruxCoach

Thank you for your interest in contributing to CruxCoach! This document explains how to get started.

---

## Reporting Bugs

1. Check [existing issues](https://codeberg.org/CruxCoach/CruxCoach/issues) to avoid duplicates.
2. Open a new issue with:
   - **Device** (model, Android version)
   - **Steps to reproduce**
   - **Expected vs. actual behavior**
   - **Logcat output** if applicable

To capture only the running app process:

```bash
APP_ID="${APPLICATION_ID:-com.cruxcoach.android}"
PID="$(adb shell pidof -s "$APP_ID")"
test -n "$PID" || { echo "$APP_ID is not running" >&2; exit 1; }
adb logcat --pid="$PID" -v threadtime > cruxcoach-logcat.txt
```

Reproduce the problem, stop capture with **Ctrl-C**, and review the file before
sharing it. Remove access tokens, private keys, Nostr identifiers, private
climbing notes, precise private/home locations, and unrelated device or app
data. The issue tracker is public. Security vulnerabilities belong on the
encrypted route in [SECURITY.md](SECURITY.md), not in an issue.

> **Tip:** You can also report bugs directly from within the app via **Settings > Contact Developer**. Your report is sent as an encrypted Nostr DM and automatically includes device info.

## Suggesting Features

Open an issue with the `feature` label. Describe:
- **What** you want the app to do
- **Why** it would be useful for your climbing workflow
- **How** you envision the UI/interaction

Public `FEAT-NNN` identifiers and their release status are indexed in
[ROADMAP.md](ROADMAP.md). For larger proposals, open the issue first; an
identifier is assigned only when it helps implementation or release tracking.

> **Tip:** Feature requests can also be sent directly from the app via **Settings > Contact Developer**.

---

## Development Setup

### Prerequisites

- JDK 17 (OpenJDK)
- Android SDK (API 35+36, Build Tools 36.0.0, NDK 27.2.12479018, CMake 3.22.1)
- An Android device or emulator (BLE features require a physical device)

On Debian/Ubuntu (amd64 + arm64), the setup script handles everything:

```bash
bash scripts/setup_dev_env.sh
source ~/.bashrc   # or ~/.zshrc
```

The Gradle modules select a JDK 17 toolchain for compilation and tests. Gradle
itself must still be launched by a compatible JDK, so point `JAVA_HOME` at a
local JDK 17 installation before invoking the wrapper.

On macOS, Windows, non-Debian Linux, and pre-provisioned CI images, install the
same Android components through Android Studio's SDK Manager or `sdkmanager`:

```sh
sdkmanager \
  "platforms;android-36" \
  "platforms;android-35" \
  "build-tools;36.0.0" \
  "platform-tools" \
  "ndk;27.2.12479018" \
  "cmake;3.22.1"
```

The NDK can add roughly 2 GB to the first setup. The SDK directory must be
writable by the build user if Gradle needs to provision a missing component;
for a read-only SDK, pre-install every component above before running even a
shared-module task because the Android module is configured in the same build.

### Building

```bash
# Debug APK
./gradlew :androidApp:assembleDebug

# Run tests
./gradlew :shared:testDebugUnitTest
./gradlew :androidApp:testDebugUnitTest

# Install on device
adb install androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

On a fresh install, the climb catalogues are downloaded rather than bundled.
The current full board database is approximately 85 MB, so allow the onboarding
sync to finish (Wi-Fi recommended) before treating an empty browser as a bug.

### Testing

The repository's test layers, commands, device prerequisites, and manual
release checks are documented in [docs/testing.md](docs/testing.md). During
development, run the narrow test class or module that exercises your change;
before submitting app-wide changes, run both unit-test tasks and
`:androidApp:assembleDebug` shown above. Pull requests do not run on the
maintainer's secret-bearing self-hosted release runner, so include exact local
commands and outcomes in the PR description.

### Configuration templates

Two example files ship with the repo so first-time contributors can see
which keys the build expects without reading the Gradle scripts:

| Template | Copy to | Purpose |
|----------|---------|---------|
| [`local.properties.example`](local.properties.example) | `local.properties` | SDK path, release signing keys, fork overrides |
| [`.env.example`](.env.example) | `.env` | Zapstore publishing credentials (`zsp publish`) — maintainer / fork publishers only |

Both target files are gitignored — never commit populated copies.

### Release signing

Debug builds need no signing setup — AGP uses the built-in debug keystore
automatically. Release builds (`./gradlew :androidApp:assembleRelease`) fail
unless `RELEASE_STORE_FILE` and the other signing properties are complete.
This prevents a release from silently using Android's public debug key. For a
throwaway local minified build only, opt in explicitly with
`-PallowDebugSignedRelease=true`; never distribute that artifact.

1. Generate a release keystore (one-time, keep it outside the repo too):

   ```bash
   keytool -genkeypair -v \
     -keystore .signing/release.jks \
     -alias cruxcoach-release \
     -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Add the four keys to `local.properties` (see
   [`local.properties.example`](local.properties.example)):

   ```properties
   RELEASE_STORE_FILE=.signing/release.jks
   RELEASE_STORE_PASSWORD=<store-password>
   RELEASE_KEY_ALIAS=cruxcoach-release
   RELEASE_KEY_PASSWORD=<key-password>
   ```

3. Verify the APK after building:

   ```bash
   $ANDROID_SDK_ROOT/build-tools/<version>/apksigner verify --print-certs \
     androidApp/build/outputs/apk/release/androidApp-release.apk
   ```

The `.signing/` directory is gitignored; keep the keystore and passwords
off shared machines and out of version control.

The release workflow also compares the APK signer with the public SHA-256
fingerprint in [`release-cert-sha256.txt`](release-cert-sha256.txt). Forks must
replace that file with the fingerprint of their own established release key
before publishing.

### Project Structure

```
shared/src/commonMain/                         # Kotlin Multiplatform logic
├── kotlin/com/cruxcoach/
│   ├── data/repository/                       # shared repository contracts
│   ├── domain/{board,community,engine,model,usecase}/
│   └── util/
└── sqldelight/
    ├── board/com/cruxcoach/db/board/          # public catalogue schema
    └── secure/com/cruxcoach/db/secure/        # personal SQLCipher schema

androidApp/src/main/                           # Android app
├── java/com/cruxcoach/android/
│   ├── {aurora,ble,community,crash,data,di}/
│   ├── {nostr,notification,payment,updater,util}/
│   └── ui/                                    # Compose screens + ViewModels
│       ├── {board,climb,community,dashboard,map,onboarding}/
│       └── {settings,stats,workout,...}/
├── assets/                                    # legal data and runtime assets
├── cpp/                                       # native SQLCipher integration
└── res/                                       # Android resources/localizations

shared/src/{commonTest,androidUnitTest}/        # shared tests
androidApp/src/test/                            # Android/JVM unit tests
```

### Releases & CI (maintainer only)

The release workflow (`.forgejo/workflows/release.yml`) runs **exclusively** on the maintainer's self-hosted Forgejo runner. Pull requests do **not** receive automated build or test feedback — the maintainer runs Gradle locally during review.

The runner expects two environment variables to be defined in its execution environment:

| Variable | Purpose |
|----------|---------|
| `CRUXCOACH_SECRETS_DIR` | Directory containing `local.properties`, `.signing/`, and `.env` for Zapstore publishing — kept outside the repo and never committed |
| `ANDROID_SDK_ROOT` | Standard Android SDK location; the workflow auto-discovers `build-tools/<version>/apksigner` |

The only repository secret used is `CODEBERG_TOKEN` (for creating Codeberg releases via the Forgejo API).

Forks running their own Forgejo runner can reproduce the workflow by providing
equivalent secrets and an `ANDROID_SDK_ROOT`. Repository coordinates and asset
names are derived from the Forgejo job context. Forks must also replace
`release-cert-sha256.txt` with their own release certificate fingerprint.

### Customizing for forks

CruxCoach is GPLv3 — fork freely. For a public modified distribution, use a
distinct name and icon so users can tell the fork from upstream; the scope and
same-name-project disclaimer are in [`TRADEMARK.md`](TRADEMARK.md).

Maintainer- and brand-bound runtime constants are exposed as Gradle
`BuildConfig` fields. Upstream defaults keep a contributor checkout buildable.
For a downstream distribution, first replace every localized display-name
literal in one controlled step, then configure the complete identity group:

```bash
scripts/rebrand_ui.sh "Your App Name"
# Set APP_DISPLAY_NAME and every required identity key in local.properties.
```

Once any identity differs, `validateDistributionIdentity` fails the build if
the group is partial, if `APP_DISPLAY_NAME` differs from `app_name`, or if the
localized resources retain “CruxCoach”. Runtime code uses the configured
display/product fields; protocol and schema names retained for compatibility
are not user-facing fork branding.

| `local.properties` key | What it sets | Default |
|------------------------|--------------|---------|
| `APPLICATION_ID` | Permanent Android/store identity. Change it so upstream and fork can coexist and stores do not treat them as one app | `com.cruxcoach.android` |
| `APP_DISPLAY_NAME` | Runtime display name used outside localized resources (diagnostics, local APK-share page/file, public auth-event descriptions). Set it to the same value passed to `rebrand_ui.sh` | `CruxCoach` |
| `MAINTAINER_PUBKEY` | Recipient hex pubkey for in-app crash/support DMs and donations. The payment path refreshes the signed Kind-0 `lud16`; no immutable Lightning fallback or Ko-fi address is built in | upstream maintainer |
| `APP_LINK_HOST` | Host for shareable climb URLs and the verified Android App Link. Publish `/.well-known/assetlinks.json` with the fork's `APPLICATION_ID` and release-certificate fingerprint | `cruxcoach.org` |
| `APP_SCHEME` | Custom board-import URI scheme. Custom schemes cannot be verified; choose a distinct value and retain the import confirmation as the trust boundary | `cruxcoach` |
| `BRAND_NAMESPACE` | Prefix for user-owned public d-tags (`<ns>:climb:…`, `<ns>/backup/v1`, `<ns>/key/v1`). Change it so a fork does not publish upstream-branded events or overwrite an upstream backup under the same Nostr key | `cruxcoach` |
| `NOSTR_NAMESPACE_PREFIX` | Prefix for community event `L`/`l` labels | `com.cruxcoach` |
| `ANNOUNCE_NAMESPACE` | Nostr `L`/`l` namespace for announcement events | derived as `<NOSTR_NAMESPACE_PREFIX>.announce` |
| `UPDATER_API_BASE` | Forgejo/Gitea API root that the in-app auto-updater polls for new releases | `https://codeberg.org/api/v1` |
| `UPDATER_REPO_OWNER` | Repository owner used by the auto-updater and the "Online" app-share QR code | `CruxCoach` |
| `UPDATER_REPO_NAME` | Repository name used by the auto-updater and the app-share QR code | `CruxCoach` |
| `ZAPSTORE_APP_URL` | Zapstore listing URL surfaced as a QR code + shareable link in *Settings → Share via Zapstore* | `https://zapstore.dev/apps/com.cruxcoach.android` |
| `USER_AGENT_PRODUCT` | Product token in outgoing HTTP `User-Agent` headers (`<product>/<version> (https://<host>)`). Lets Kilter operators tell forks apart from upstream traffic | `CruxCoach` |
| `auto_note_default_template` (localized string resource) | Editable Kind-1 template shown in *Settings → Climb Creator → Auto-Note*. New defaults use generic `{author_npub}` and `{climb_url}` placeholders; legacy `{npub_cruxcoach}` / `{cruxcoach_url}` templates remain readable | upstream prose |

Catalogue reads are configured separately because a fork may legitimately
consume the attributed upstream dataset while using its own user-event
namespace. Keep `CATALOGUE_NAMESPACE=cruxcoach` and the default
`CATALOGUE_MANIFEST_PUBKEY` for that case. A fork operating its own catalogue
pipeline must change both values together and publish signed manifests under
`<CATALOGUE_NAMESPACE>/board-db`, `/moonboard-db`, and `/<board>-db`.

This repository contains the catalogue **consumer**, not a supported tool for
harvesting third-party services or publishing the upstream snapshots. The
runtime contract is defined by
`androidApp/src/main/java/com/cruxcoach/android/data/blossom/BlossomManifest.kt`
and `androidApp/src/main/java/com/cruxcoach/android/data/BoardDatabaseImporter.kt`.
A fork must therefore either retain the attributed upstream catalogue identity
or implement and operate its own lawful producer for that contract; changing
the namespace alone does not create a catalogue.

The auto-updater is disabled automatically on Zapstore installs (Zapstore
handles updates itself). Forks whose APKs are distributed through other
channels need to expose releases as a Forgejo/Gitea-compatible `releases`
API endpoint and upload two assets per release:

- any asset ending in `.apk` — the signed release APK
- any asset ending in `.apk.sha256` — a single-line `<hex>  <filename>` hash sidecar

The updater selects by suffix; the filename stem is not part of its protocol.

The first install pins the signing certificate (trust-on-first-use); the
updater refuses any future release whose signing cert doesn't match.

The auto-updater also requires two runtime permissions declared in the
manifest:

- `android.permission.REQUEST_INSTALL_PACKAGES` — hands the downloaded APK to
  the system `PackageInstaller`; the user must additionally grant
  "Install unknown apps" in system settings on first use
- `android.permission.DOWNLOAD_WITHOUT_NOTIFICATION` — lets the background
  WorkManager pull the APK without surfacing a system DownloadManager
  notification (the in-app updater posts its own progress notification)

The `zapstore.yaml` signing pubkey is maintainer-specific too. Forks
publishing to Zapstore should replace it with their own zsp-managed
identity (or remove the file if not publishing through Zapstore).

The README's donation block points at the upstream maintainer — update the
documentation and QR image to your own channels when rebranding. In-app
donations resolve the `lud16` in `MAINTAINER_PUBKEY`'s signed Nostr profile.

Replace the visual assets with
[`scripts/generate_brand_rasters.sh`](scripts/generate_brand_rasters.sh) and
the complete [`logos/README.md`](logos/README.md) checklist, then run
`scripts/check_rebrand_assets.sh <upstream-ref>`. Also update `zapstore.yaml`,
public documentation, release notes, signing certificate, and store metadata.

---

## Coding Standards

These guidelines apply to new and changed code. Existing code does not always
match every preference below; keep a change focused and do not mix unrelated
cleanup into a bug fix.

### State Management
- Prefer atomic `_state.update { it.copy(...) }` for concurrent state-flow
  updates. A direct assignment is appropriate only when it cannot race and the
  surrounding lifecycle makes that invariant clear.
- State class naming: `XyzState`, `XyzViewModel`, `XyzScreen`.

### File Size
- Treat roughly 500 lines as a review signal, not a mechanical limit. Extract
  cohesive components when doing so makes ownership and tests clearer.
- One screen composable per file.
- Extracted composables use `internal` visibility.

### Code Hygiene
- No dead code. Delete unused functions, don't comment them out.
- Handle expected failures deliberately. Log unexpected failures without
  credentials, personal data, or redundant stack traces.
- No duplicated constants: centralize shared values.
- DRY mappers: extract repeated DB-to-domain mapping logic.

### Database (SQLDelight)
- Check the highest existing `.sqm` migration number before creating a new one.
- Add composite indices for frequently filtered column combinations.
- Delete unused queries from `.sq` files.

### Language
- Code is written in English.
- UI strings live in `values/strings.xml` (English — the default/fallback) and `values-de/strings.xml` (German). Both must always be updated together when you add or change a UI string. The empty `values-en/strings.xml` is only a locale-detection marker — do not edit it by hand.

### Dependencies
- Constructor injection via Hilt. No global mutable singletons.
- Use the type that the storage layer expects (SQLDelight `Long` for INTEGER).

All supported Gradle build configurations use strict dependency locking and
SHA-256 dependency verification. After changing a dependency, regenerate both
controls from a trusted network. The helper exercises the real shared tests,
Android tests, debug APK and minified release APK rather than trying to resolve
AGP's synthetic configurations outside the tasks that supply their attributes:

```bash
./gradlew resolveAndLockAll -PallowDebugSignedRelease=true --write-locks \
  --write-verification-metadata sha256
```

Review every changed coordinate, resolved version, repository and checksum in
the lock/verification diff before committing it. A successful download is not
evidence that new bytes are trustworthy; compare security-sensitive artifacts
with checksums or release information published by their upstream project.
Normal builds run in strict mode and must not rewrite these files.

Third-party CI actions are pinned to a full 40-character commit SHA. To update
one, resolve the intended upstream release tag, review that revision, and
change the SHA and trailing version comment in the same commit. Never replace
the pin with a branch or major-version tag.

---

## Submitting Changes

Read the [Code of Conduct](CODE_OF_CONDUCT.md). Contributions are licensed
under GPL-3.0-only and certified under [DCO 1.1](DCO.md); sign every commit with
`git commit -s`. A sign-off is a statement about your right to contribute,
not merely a formatting convention.

### Branch Naming

- `feat/<short-description>` for new features
- `fix/<short-description>` for bug fixes
- `refactor/<short-description>` for refactoring
- `docs/<short-description>` for documentation

### Commit Messages

Use conventional commit style:

```
feat(board): add climb difficulty histogram
fix(ble): reconnect after Android 14 permission change
refactor(engine): extract periodization into standalone class
```

### Pull Request Checklist

Before submitting a PR:

- [ ] `git diff --check` passes and the diff contains no secrets or personal data
- [ ] Relevant focused regression tests pass
- [ ] Shared tests pass when shared code is affected: `./gradlew :shared:testDebugUnitTest`
- [ ] Android tests pass when Android code is affected: `./gradlew :androidApp:testDebugUnitTest`
- [ ] The debug APK builds when app/build inputs change: `./gradlew :androidApp:assembleDebug`
- [ ] `values/strings.xml` (en) and `values-de/strings.xml` both updated (if UI strings changed)
- [ ] Each commit includes the DCO `Signed-off-by` line

### PR Description

Include:
- **Summary**: What changed and why (1-3 bullet points)
- **Test plan**: How to verify the change works

---

## Architecture Decisions

### Why KMP?
Domain logic (grade calculations, frame codec, board protocol) is pure Kotlin in `shared/`. This keeps the door open for iOS and Wear OS without rewriting business logic.

### Why SQLDelight + SQLCipher?
- SQLDelight: SQL-first, type-safe, cross-platform. Not Android-only like Room.
- Two databases: board data (community climbs, unencrypted, public) and personal data (logbook, body stats, Nostr keys, encrypted with SQLCipher).

### Why Nostr?
Decentralized sync and communication without running a server. Users control their own keys. Board database distributed via Blossom (content-addressed blobs). Crash reports and dev contact via encrypted DMs (NIP-17).

### Why not Room?
Room is Android-only. SQLDelight generates code for all KMP targets from a single `.sq` schema.

---

## Questions?

Open an issue or use the verified Nostr route in
[MAINTAINERS.md](MAINTAINERS.md).
