# Contributing to CruxCoach

Thank you for your interest in contributing to CruxCoach! This document explains how to get started.

---

## Reporting Bugs

1. Check [existing issues](https://codeberg.org/CruxCoach/CruxCoach/issues) to avoid duplicates.
2. Open a new issue with:
   - **Device** (model, Android version)
   - **Steps to reproduce**
   - **Expected vs. actual behavior**
   - **Logcat output** if applicable (`adb logcat -s CruxCoach`)

> **Tip:** You can also report bugs directly from within the app via **Settings > Contact Developer**. Your report is sent as an encrypted Nostr DM and automatically includes device info.

## Suggesting Features

Open an issue with the `feature` label. Describe:
- **What** you want the app to do
- **Why** it would be useful for your climbing workflow
- **How** you envision the UI/interaction

Non-trivial features are tracked as `FEAT-NNN` specifications under
[`docs/specs/`](docs/specs/). For larger proposals, open the issue
first; the maintainer will either draft the spec or invite you to
contribute one.

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
automatically. Release builds (`./gradlew :androidApp:assembleRelease`)
**silently fall back to debug signing** if `RELEASE_STORE_FILE` is empty,
which is convenient locally but means distribution builds are only trusted
when you have explicitly configured a release keystore.

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

### Project Structure

```
shared/                    # Kotlin Multiplatform module
├── domain/model/          # Data classes (pure Kotlin)
├── domain/board/          # Board protocol, frame codec, grade system
└── sqldelight/
    ├── board/             # Board DB schema: climbs, stats, layouts (unencrypted)
    └── secure/            # Personal data schema: logbook, body stats, Nostr (SQLCipher)

androidApp/                # Android app (Jetpack Compose)
├── ui/                    # Screens + ViewModels
├── data/                  # Repositories, Kilter API client
├── ble/                   # Bluetooth board communication (Nordic UART)
└── nostr/                 # Nostr relay pool, sync, announcements
```

### Releases & CI (maintainer only)

The release workflow (`.forgejo/workflows/release.yml`) runs **exclusively** on the maintainer's self-hosted Forgejo runner. Pull requests do **not** receive automated build or test feedback — the maintainer runs Gradle locally during review.

The runner expects two environment variables to be defined in its execution environment (e.g. via systemd `Environment=` directives or the runner's config file):

| Variable | Purpose |
|----------|---------|
| `CRUXCOACH_SECRETS_DIR` | Directory containing `local.properties`, `.signing/`, and `.env` for Zapstore publishing — kept outside the repo and never committed |
| `ANDROID_SDK_ROOT` | Standard Android SDK location; the workflow auto-discovers `build-tools/<version>/apksigner` |

The only repository secret used is `CODEBERG_TOKEN` (for creating Codeberg releases via the Forgejo API).

Forks running their own Forgejo runner can reproduce the workflow by providing equivalent secrets and an `ANDROID_SDK_ROOT`; the workflow itself contains no host-specific paths.

### Customizing for forks

CruxCoach is GPLv3 — fork freely. The **name and logo** are reserved
([`TRADEMARK.md`](TRADEMARK.md)); if you publish modified binaries to a
wide audience, please rename and replace the launcher icon. App-launcher
sources to replace are in [`logos/`](logos/) and the regeneration procedure
is documented there.

Maintainer-bound runtime constants are exposed as Gradle `BuildConfig`
fields with sensible defaults. Override them in your fork by adding the
following keys to `local.properties` — no source edits required:

| `local.properties` key | What it sets | Default |
|------------------------|--------------|---------|
| `MAINTAINER_PUBKEY` | Recipient hex pubkey for in-app crash reports, dev-contact DMs, and announcement subscriptions | upstream maintainer |
| `MAINTAINER_LIGHTNING_ADDRESS` | Lightning address shown for upstream-style donation flows | `cruxcoach@npub.cash` |
| `MAINTAINER_KOFI_URL` | Ko-fi donation link surfaced in the Payments UI | `https://ko-fi.com/cruxcoach` |
| `ANNOUNCE_NAMESPACE` | Nostr `L`/`l` tag namespace for announcement events | `com.cruxcoach.announce` — change this when you fork to avoid notification cross-talk with upstream users |
| `UPDATER_API_BASE` | Forgejo/Gitea API root that the in-app auto-updater polls for new releases | `https://codeberg.org/api/v1` |
| `UPDATER_REPO_OWNER` | Repository owner used by the auto-updater and the "Online" app-share QR code | `CruxCoach` |
| `UPDATER_REPO_NAME` | Repository name used by the auto-updater and the app-share QR code; also drives the expected APK filename `<repo>-<tag>.apk` | `CruxCoach` |
| `ZAPSTORE_APP_URL` | Zapstore listing URL used as the manual release handoff when signed Zapstore metadata supplied the update | `https://zapstore.dev/apps/com.cruxcoach.android` |
| `ZAPSTORE_RELAY_URL` | Relay queried for publisher-signed Zapstore release and APK metadata | `wss://relay.zapstore.dev` |
| `ZAPSTORE_CDN_BASE_URL` | Content-addressed direct-download fallback; the path is the verified APK SHA-256 | `https://cdn.zapstore.dev` |
| `USER_AGENT_PRODUCT` | Product token in outgoing HTTP `User-Agent` headers (`<product>/<version> (https://<host>)`). Lets Kilter operators tell forks apart from upstream traffic | `CruxCoach` |
| `APP_LINK_HOST` | Host for shareable climb URLs (`https://<host>/c/<naddr>`) and for the Android App Link `<intent-filter>`. Forks need to host their own `/.well-known/assetlinks.json` for verification to succeed; until then App Links fall back to opening in a browser | `cruxcoach.org` |
| `AUTO_NOTE_PTAG_MAINTAINER` | When `true`, Auto-Note Kind-1 publishes attach an unconditional `p`-tag mention of `MAINTAINER_PUBKEY` (Amethyst notification + reach amplifier for upstream). Forks usually want `false` so their users don't accidentally amplify whoever the fork's `MAINTAINER_PUBKEY` resolves to | `true` (set `AUTO_NOTE_PTAG_MAINTAINER=false` in your fork's `local.properties` to opt out) |
| `auto_note_default_template` (string resource — `values/strings.xml:33` + `values-de/strings.xml:33`) | Editable Kind-1 template a fork user sees in *Settings → Climb Creator → Auto-Note*. The default contains `{npub_cruxcoach}`, `{cruxcoach_url}`, and the `#kilterboard` hashtag — forks should reword the template (and ideally drop the upstream-flavored token names) before publishing | upstream-flavored default |

The auto-updater is disabled automatically on Zapstore installs (Zapstore
handles updates itself). For direct installs it discovers releases through
the Forgejo/Gitea-compatible `releases` API and falls back to publisher-signed
Zapstore events when Codeberg is unavailable. The APK may be downloaded from
either source, but its SHA-256 and signing certificate must match before it is
handed to Android. Forks need to upload two assets per Codeberg release:

- `<repo>-<tag>.apk` — the signed release APK (must match `UPDATER_REPO_NAME`)
- `<repo>-<tag>.apk.sha256` — a single-line `<hex>  <filename>` hash sidecar

The first install pins the signing certificate (trust-on-first-use); the
updater refuses any future release whose signing cert doesn't match.

The auto-updater also requires two runtime permissions declared in the
manifest:

- `android.permission.REQUEST_INSTALL_PACKAGES` — hands the downloaded APK to
  the system `PackageInstaller`; the user must additionally grant
  "Install unknown apps" in system settings on first use
- `android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION` — allows the
  opt-in automatic-install mode to request a no-interaction self-update on
  Android 12 and newer; Android can still require confirmation
- `android.permission.DOWNLOAD_WITHOUT_NOTIFICATION` — lets the background
  WorkManager pull the APK without surfacing a system DownloadManager
  notification (the in-app updater posts its own progress notification)

The `zapstore.yaml` signing pubkey is maintainer-specific too. Forks
publishing to Zapstore should replace it with their own zsp-managed
identity (or remove the file if not publishing through Zapstore).

The README's donation block points at the upstream maintainer — update it
to your own channels when rebranding.

---

## Coding Standards

These are non-negotiable for all contributions.

### State Management
- **Thread-safe updates only**: Use `_state.update { it.copy(...) }` (atomic). Never use `_state.value = _state.value.copy(...)`.
- State class naming: `XyzState`, `XyzViewModel`, `XyzScreen`.

### File Size
- Max ~500 lines per file. Extract composables into separate files if needed.
- One screen composable per file.
- Extracted composables use `internal` visibility.

### Code Hygiene
- No dead code. Delete unused functions, don't comment them out.
- No swallowed exceptions: always log at minimum.
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

---

## Submitting Changes

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

- [ ] Code compiles: `./gradlew :androidApp:assembleDebug`
- [ ] Shared tests pass: `./gradlew :shared:testDebugUnitTest`
- [ ] Android tests pass: `./gradlew :androidApp:testDebugUnitTest`
- [ ] `values/strings.xml` (en) and `values-de/strings.xml` both updated (if UI strings changed)
- [ ] No new warnings introduced
- [ ] No files exceed ~500 lines

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

Open an issue or reach out via Nostr.
