# Contributing to CruxCoach

Start with the [documentation index](docs/README.md) and
[core concepts/source map](docs/en/CORE_CONCEPTS.md). Read [AGENTS.md](AGENTS.md)
before working; it applies to human-assisted coding agents. On the development
host, also read the applicable parent instructions and `~/SERVER-SPLIT-STATUS.md`.
Never use a historical server path as authority to operate production.

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

The commands below describe build tasks for contributor environments. Under
[AGENTS.md](AGENTS.md), full APK builds, full test suites and Android lint run
in CI; locally run only checks focused on your change. Do not duplicate CI.

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

This subsection is background for independent fork owners on their own
machines. Contributors and remote agents do not need upstream signing keys.
Upstream stable signing/publication requires the owner’s authorization for
the exact release; see [current release status](docs/RELEASE_GITHUB.md).

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

```text
shared/src/commonMain/
├── kotlin/com/cruxcoach/domain/           # Models, board codecs, domain logic
├── kotlin/com/cruxcoach/data/repository/  # Repository interfaces and implementations
└── sqldelight/
    ├── board/                            # Catalogue and local drafts, unencrypted
    └── secure/                           # Personal records, SQLCipher on Android

androidApp/src/main/java/com/cruxcoach/android/
├── ui/          # Compose screens and ViewModels
├── data/        # Android storage, imports, sync and playback coordination
├── ble/         # Controller protocols and nearby sessions
├── community/   # Draft authoring and public climb publication
├── nostr/       # Signers, relays, backup and messaging
└── updater/     # Discovery, integrity verification and installation
```

### Releases & CI (maintainer only)

Pull requests targeting `main` or `feat/**` have hosted checks in
[pr-ci.yml](.github/workflows/pr-ci.yml): Python tooling tests, shared/Android
unit tests and debug APK compilation. Android lint is advisory. The workflow
file establishes configured behavior, not the result of any particular run.

Feature artifacts use a separate trusted-main APKTrack publisher; never give
feature code signing keys or upload tokens. Stable publication belongs to the
private operator plane. The repository's self-hosted release workflow and the
OIDC/private-signing migration must not be described as an activated production
service. See [release status and historical procedure](docs/RELEASE_GITHUB.md)
for the source files, handoff evidence and remaining activation work.

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
handles updates itself). For direct installs, [UpdateChecker](androidApp/src/main/java/com/cruxcoach/android/updater/UpdateChecker.kt)
queries the configured discovery sources and selects the highest newer
version; source order breaks ties. This is distinct from fetching the runtime
source-list manifest: [UpdateSourceRegistry](androidApp/src/main/java/com/cruxcoach/android/updater/UpdateSourceRegistry.kt)
uses the first usable manifest host, then cached/compiled defaults when
needed. Blossom stores can provide download fallbacks without independently
discovering a release.

Because that list is data rather than code, a release host can be added,
reordered or retired for installs **already in the field**. This matters:
compiled-in constants can never be changed retroactively, so a forge migration
without a runtime list would strand every existing direct install.

The APK may be downloaded from any source on the list, but its SHA-256 and
signing certificate must match before it is handed to Android — the transport
is untrusted by construction, which is what makes an open-ended source list
safe. Note the corollary: with several sources, the **signing identity is the trust anchor**, because a hostile source could
serve a matching APK *and* sidecar. Hash matching alone is insufficient. Do not weaken `IntegrityVerifier`.

Changing the forge does **not** invalidate the trust-on-first-use pin — that
pin is on the APK signing certificate, not on the host. An unrelated
signing key does; an accepted rotation lineage has a separate verification
path.

Forks need to upload two assets per release:

- `<repo>-<tag>.apk` — the signed release APK (must match `UPDATER_REPO_NAME`)
- `<repo>-<tag>.apk.sha256` — a single-line `<hex>  <filename>` hash sidecar

The first install pins the signing certificate (trust-on-first-use); later
updates require that identity or an accepted signing lineage. See
[key rotation](docs/KEY_ROTATION.md).

The auto-updater also uses three permissions declared in the
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
- `chore/<short-description>` for maintenance or refactoring
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

- [ ] Focused local checks match the change; record their result and limits
- [ ] Required CI unit tests and debug compilation pass before merge
- [ ] Lint report reviewed; its advisory job is not proof of zero findings
- [ ] Only task-owned hunks staged; unrelated changes preserved
- [ ] Trust-boundary changes flagged for project-owner review
- [ ] `values/strings.xml` (en) and `values-de/strings.xml` both updated (if UI strings changed)
- [ ] No new warnings introduced
- [ ] No files exceed ~500 lines

### PR Description

Include:
- **Summary**: What changed and why (1-3 bullet points)
- **Test plan**: How to verify the change works

---

## Architecture Decisions

For the running data flow and exact source paths, see the
[architecture guide](docs/en/CORE_CONCEPTS.md). The notes below explain design
motivation, not a promise of support on additional platforms.

### Why KMP?
Domain logic (grade calculations, frame codec, board protocol) is pure Kotlin in `shared/`. This keeps the door open for iOS and Wear OS without rewriting business logic.

### Why SQLDelight + SQLCipher?
- SQLDelight: SQL-first, type-safe, cross-platform. Not Android-only like Room.
- Two databases: BoardDB (catalogue and local drafts, unencrypted) and SecureDB (logbook and personal records, SQLCipher). Local Nostr key storage has its own protection; see [SECURITY.md](SECURITY.md).

### Why Nostr?
Decentralized sync and communication without requiring users to run their own server. Users control their own keys. Board database distributed via Blossom (content-addressed blobs). Crash reports and dev contact via encrypted DMs (NIP-17).

### Why not Room?
Room is Android-only. SQLDelight generates code for all KMP targets from a single `.sq` schema.

---

## Questions?

Open an issue or reach out via Nostr.
