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

The release workflow is `.github/workflows/release.yml`, and it runs **exclusively** on the maintainer's self-hosted runner — which is the whole reason GitHub Actions is acceptable for a signed release at all: the keystore stays on our own filesystem and never enters GitHub's secret store. `.forgejo/workflows/release.yml` is the Codeberg fallback and is **manual-trigger only** (`workflow_dispatch`), so a single merge cannot start two pipelines that would publish different bytes under one version number. Full rationale and migration order: [`docs/RELEASE_GITHUB.md`](docs/RELEASE_GITHUB.md).

Pull requests do **not** receive automated build or test feedback — the maintainer runs Gradle locally during review. Keep the self-hosted runner off pull-request triggers: it executes whatever a push contains, on the host that holds the signing key.

The runner expects these environment variables in its execution environment (e.g. via systemd `Environment=` directives or the runner's config file):

| Variable | Purpose |
|----------|---------|
| `CRUXCOACH_SECRETS_DIR` | Directory containing `local.properties`, `.signing/`, and `.env` for Zapstore publishing — kept outside the repo and never committed |
| `ANDROID_SDK_ROOT` | Standard Android SDK location; the workflow auto-discovers `build-tools/<version>/apksigner` and `aapt2` |
| `CRUXCOACH_APK_LOCAL_DIR` | Optional. Download-server APK directory; defaults to `~/cruxcoach-dlstats/apk` |
| `CRUXCOACH_PAGES_DIR` | Optional. Website checkout whose `tools/publish-release.sh` refreshes the download links; defaults to `~/cruxcoach-pages` |

Files the runner reads off its own filesystem, none of which is a forge secret:

| Path | Purpose |
|------|---------|
| `$CRUXCOACH_SECRETS_DIR/local.properties` | Build config, including `RELEASE_STORE_FILE` — beware the trap documented above: an empty value makes a release build fall back to debug signing **silently** |
| `$CRUXCOACH_SECRETS_DIR/.signing/` | Release keystore |
| `$CRUXCOACH_SECRETS_DIR/.env` | Zapstore publishing. Mode 600; must define a headless `SIGN_WITH` (`nsec1…`, hex private key, or a provisioned `bunker://` NIP-46 signer) whose public key matches `zapstore.yaml`. A bare `npub1…` only creates unsigned output. The file uses raw zsp `KEY=value` syntax and is never sourced as shell. See [`.env.example`](.env.example) |
| `~/.config/cruxcoach/github-release-token` | Mode 600, `Contents: Read and write` on `CruxCoach/CruxCoach`. Authenticates both the GitHub API calls and the tag push. Override with `GITHUB_TOKEN` / `GITHUB_TOKEN_FILE`, or `GITHUB_RELEASE_TOKEN` for the dev-release cleanup step |

**No GitHub repository secret is required, and none is load-bearing** — that is deliberate, so a GitHub-side compromise cannot reach the signing key or the publisher identity. The Codeberg fallback still uses one repository secret, `CODEBERG_TOKEN`, for creating Codeberg releases via the Forgejo API.

Forks running their own runner can reproduce the workflow by providing the equivalent directories and an `ANDROID_SDK_ROOT`; the workflows themselves contain no host-specific paths.

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
| `MAINTAINER_LIGHTNING_ADDRESS` | Lightning address shown for upstream-style donation flows | `npub1uadpshqpn5ysf82lev8zngkvn07szmkq7mvf9lyc7ml7qxq6fqxsmrqt2s@npub.cash` |
| `MAINTAINER_KOFI_URL` | Ko-fi donation link surfaced in the Payments UI | `https://ko-fi.com/cruxcoach` |
| `ANNOUNCE_NAMESPACE` | Nostr `L`/`l` tag namespace for announcement events | `com.cruxcoach.announce` — change this when you fork to avoid notification cross-talk with upstream users |
| `UPDATER_API_BASE` | Forge API root for the *compiled-in default* forge source. Works for Forgejo/Gitea (`https://<host>/api/v1`) and for GitHub (`https://api.github.com`) — their release JSON is field-compatible | `https://codeberg.org/api/v1` |
| `UPDATER_REPO_OWNER` | Repository owner used by the auto-updater and the "Online" app-share QR code | `CruxCoach` |
| `UPDATER_REPO_NAME` | Repository name used by the auto-updater and the app-share QR code; also drives the expected APK filename `<repo>-<tag>.apk` | `CruxCoach` |
| `UPDATE_SOURCES_URLS` | Runtime source list (FEAT-050), comma-separated; the first host that answers wins. Fetched at most daily and cached; lets you add, reorder or retire release hosts **without shipping a new APK**. Falls back to the compiled-in defaults when unreachable or unusable | `https://cruxcoach.org/update-sources.json,https://mirror.cruxcoach.org/update-sources.json` |
| `UPDATER_MANIFEST_URLS` | Plain release pointer used as a forge-independent discovery source, comma-separated like the above | `https://cruxcoach.org/apk-target.json,https://mirror.cruxcoach.org/apk-target.json` |
| `UPDATER_BLOSSOM_SERVERS` | Comma-separated content-addressed stores (BUD-01 `GET /<sha256>`) used as download-only last resorts | public Blossom servers |
| `UPDATER_RELEASE_PAGE_URL` | Install page used by the cert-mismatch handoff *and* by the "Share app" QR. Deliberately not a forge URL: it survives a forge migration, always offers the current release rather than the sharing device's version, and routes through the site's health-checked download selector | `https://cruxcoach.org/#install` |
| `ANONYMOUS_METRICS_ENDPOINT` | Identifier-free aggregate increment after a fully downloaded update passes SHA-256 and signer verification; set only to an endpoint you operate or trust | empty; the upstream release workflow injects the CruxCoach endpoint only when `github.repository == CruxCoach/CruxCoach` |
| `ZAPSTORE_APP_URL` | Zapstore listing URL used as the manual release handoff when signed Zapstore metadata supplied the update | `https://zapstore.dev/apps/com.cruxcoach.android` |
| `ZAPSTORE_RELAY_URL` | Relay queried for publisher-signed Zapstore release and APK metadata | `wss://relay.zapstore.dev` |
| `ZAPSTORE_CDN_BASE_URL` | Content-addressed direct-download fallback; the path is the verified APK SHA-256 | `https://cdn.zapstore.dev` |
| `USER_AGENT_PRODUCT` | Product token in outgoing HTTP `User-Agent` headers (`<product>/<version> (https://<host>)`). Lets Kilter operators tell forks apart from upstream traffic | `CruxCoach` |
| `APP_LINK_HOST` | Host for shareable climb URLs (`https://<host>/c/<naddr>`) and for the Android App Link `<intent-filter>`. Forks need to host their own `/.well-known/assetlinks.json` for verification to succeed; until then App Links fall back to opening in a browser | `cruxcoach.org` |
| `AUTO_NOTE_PTAG_MAINTAINER` | When `true`, Auto-Note Kind-1 publishes attach an unconditional `p`-tag mention of `MAINTAINER_PUBKEY` (Amethyst notification + reach amplifier for upstream). Forks usually want `false` so their users don't accidentally amplify whoever the fork's `MAINTAINER_PUBKEY` resolves to | `true` (set `AUTO_NOTE_PTAG_MAINTAINER=false` in your fork's `local.properties` to opt out) |
| `auto_note_default_template` (string resource — `values/strings.xml:33` + `values-de/strings.xml:33`) | Editable Kind-1 template a fork user sees in *Settings → Climb Creator → Auto-Note*. The default contains `{npub_cruxcoach}`, `{cruxcoach_url}`, and the `#kilterboard` hashtag — forks should reword the template (and ideally drop the upstream-flavored token names) before publishing | upstream-flavored default |

The anonymous update counter's closed client behavior, opt-out, approximate
delivery semantics, backend requirements, and aggregate retention are specified
in [`docs/anonymous-update-metrics.md`](docs/anonymous-update-metrics.md). A fork
that sets `ANONYMOUS_METRICS_ENDPOINT` must keep its own disclosure and backend
contract accurate; leaving the property empty disables the feature completely.

The auto-updater is disabled automatically on Zapstore installs (Zapstore
handles updates itself). For direct installs it walks an **ordered list of
release sources** (FEAT-050), stopping at the first that answers and moving on
only when one fails — so the healthy case still costs a single request. The
list comes from `UPDATE_SOURCES_URLS` at runtime, falling back to the
compiled-in defaults: the configured forge, publisher-signed Zapstore/Nostr
events, the website's release pointer, and content-addressed Blossom stores.

Because that list is data rather than code, a release host can be added,
reordered or retired for installs **already in the field**. This matters:
compiled-in constants can never be changed retroactively, so a forge migration
without a runtime list would strand every existing direct install.

The APK may be downloaded from any source on the list, but its SHA-256 and
signing certificate must match before it is handed to Android — the transport
is untrusted by construction, which is what makes an open-ended source list
safe. Note the corollary: with several sources, the **signing-certificate pin
carries the entire security load**, because a hostile source could serve a
matching APK *and* sidecar. Do not weaken `IntegrityVerifier`.

Changing the forge does **not** invalidate the trust-on-first-use pin — that
pin is on the APK signing certificate, not on the host. Signing with a
different key does.

Forks need to upload two assets per release:

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
