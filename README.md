# CruxCoach

Open-source multi-board climbing and training app for Android.

Browse climbs, control supported LED boards over Bluetooth, play training playlists, import your Kilter or MoonBoard logbook and track your progress — with no mandatory account, no ads and full control over your data.

## 📲 Get the app

<p align="center">
  <a href="https://zapstore.dev/apps/com.cruxcoach.android">
    <img src="https://img.shields.io/badge/Zapstore-recommended-7c3aed?style=for-the-badge&logo=android&logoColor=white" alt="Install via Zapstore">
  </a>
  &nbsp;
  <a href="https://codeberg.org/CruxCoach/CruxCoach/releases/latest">
    <img src="https://img.shields.io/badge/Codeberg-latest_APK-2185d0?style=for-the-badge&logo=codeberg&logoColor=white" alt="Download latest APK from Codeberg">
  </a>
  &nbsp;
  <a href="https://github.com/CruxCoach/CruxCoach/releases/latest">
    <img src="https://img.shields.io/badge/GitHub-latest_APK-24292f?style=for-the-badge&logo=github&logoColor=white" alt="Download latest APK from GitHub">
  </a>
  &nbsp;
  <a href="#building-from-source">
    <img src="https://img.shields.io/badge/Source-build_yourself-374151?style=for-the-badge&logo=gnu&logoColor=white" alt="Build from source">
  </a>
  &nbsp;
  <a href="https://cruxcoach.org/">
    <img src="https://img.shields.io/badge/Website-cruxcoach.org-e07a4f?style=for-the-badge&logo=firefoxbrowser&logoColor=white" alt="cruxcoach.org">
  </a>
</p>

<p align="center">
  <em>
    <a href="https://zapstore.dev/apps/com.cruxcoach.android">Zapstore</a> auto-updates, Nostr-native, verifiable builds &middot;
    <a href="https://codeberg.org/CruxCoach/CruxCoach/releases/latest">Codeberg</a> and
    <a href="https://github.com/CruxCoach/CruxCoach/releases/latest">GitHub</a> carry the identical APK and <code>.apk.sha256</code> sidecar &middot;
    <a href="#building-from-source">Source build</a> reproducible from <code>main</code><br>
    Project site: <a href="https://cruxcoach.org/">cruxcoach.org</a> &middot;
    <a href="https://cruxcoach.org/boards/">board locations map</a>
  </em>
</p>

<p align="center">
  <img src="docs/screenshots/board-browser.png" alt="Board Browser" width="220">&nbsp;&nbsp;
  <img src="docs/screenshots/climb-detail.png" alt="Climb Detail" width="220">&nbsp;&nbsp;
  <img src="docs/screenshots/hold-search.png" alt="Hold Search" width="220">&nbsp;&nbsp;
  <img src="docs/screenshots/heatmap.png" alt="Heatmap" width="220">
</p>

> **First-time sideload from Codeberg or GitHub?** Android Settings → Apps → *Special app access* → *Install unknown apps* → enable for your browser or file manager. The signing certificate is stable across releases, so future updates install on top without re-enabling.

---

## What makes it different

**Custom LED Colors** — Choose your own hold colors on the Kilter Board. Different colors for start, hand, foot, and top holds. Visible to everyone around you.

**Nearby Sharing** — Share the current climb or active playlist state with nearby CruxCoach users over Bluetooth. No internet is required.

**Your Data, Your Device** — All personal data encrypted locally. Decentralized identity via [Nostr](https://nostr.com) — no email, no password, no central account server. No user tracking, no ads. Official builds can send an identifier-free aggregate update count only after an APK has been fully downloaded and cryptographically verified; it is disclosed and can be disabled under *Settings → Updates*.

---

## All Features

- **680,000+ catalogue climbs** across Kilter, MoonBoard, Tension, Grasshopper, Decoy, So iLL and Touchstone, with filters for grade, angle, quality, moves and setter — plus a combinable status filter (*Neu / Versucht / Geschafft*), an *ungraded-only (projects)* mode, ignored climbs and board-fit filtering
- **Variable climb angle** *(0.2.0+)* — pick the angle to browse, send, or log a climb at across every angle the board physically supports, not only the angles that already have community stats; the original setter's angle is shown as info
- **MoonBoard support** *(0.2.0+)* — browse and light MoonBoard 2010, 2016, Masters 2017, Masters 2019, Mini 2020, 2024 and Mini 2025; choose mounted hold sets and LED position, and import the official Moon logbook directly from the installed app or from CSV
- **Quantum Board support** *(0.2.2+)* — browse and control Quantum walls, inspect controller-reported live layers and filter for climbs with zero or at most one overlap with occupied layers
- **More boards** *(0.2.0+)* — Tension, Grasshopper, Decoy, So iLL and Touchstone work like Kilter: browse the catalogue, render climbs, read the hold heatmap, send to the board over Bluetooth, and set your own climbs. Pick one in Settings
- **Board-Locations Map** *(0.2.0+)* — interactive world map of every known Kilter **and MoonBoard** installation, with filters for board brand, layout, country, public-vs-private, adjustability and size; tap a marker for contact details and a direct link into the climbs that fit that exact board
- **Find-your-gym board picker** *(0.2.0+)* — set the right board configuration in Settings by searching for your gym, no product-size codes to memorise; always-on board-fit filter then narrows the catalog to climbs your board can actually do
- **BLE board control** — light supported Kilter/Aurora, MoonBoard and Quantum controllers, with manual or automatic send modes and direct reconnect to the last board
- **Playable playlists** *(0.2.2+)* — turn favourites or custom lists into guided sessions with ordering, repeats, rests, one-tap logging and a final summary, or generate a session from your logbook
- **CruxRelay** *(0.2.2+)* — keep CruxCoach connected to the physical board while compatible board apps send climbs through it
- **Log ascents** with grade opinions, attempts, and notes
- **History (*Verlauf*)** *(0.2.0+)* — the climbs you sent to the board, recorded automatically when you push one over Bluetooth; each entry shows which board it was sent on, mirrored sends are flagged, and you can multi-select and delete entries
- **Hold Search** — tap holds on the Kilter Board to find climbs that use them
- **Heatmap** — visualize hold popularity by type, sends, or all climbs, with a per-board selector to read the hold heatmap for each board you have logged on
- **Kilter and MoonBoard logbook import** — Kilter through your account; MoonBoard from the installed app or an official CSV export
- **Statistics** — grade progression, difficulty trends, favorite angles
- **Data exchange** — JSON and CSV ZIP export/import for logbook data, private climb notes, lists/playlists and own climbs; readable Excel export for spreadsheets
- **Encrypted cloud backup** *(0.1.3+, opt-in)* — your climbing data encrypted on-device, mirrored across the open Nostr network and Blossom storage servers. The maintainer cannot decrypt it; only your CruxCoach Account key can. Survives app uninstall + device transfer.
- **App-share QR code** — share CruxCoach with nearby climbers by QR
- **Reliable notifications** — guided setup for Android battery and autostart restrictions so dev-DMs and sync updates always arrive
- **In-app auto-updater** — verifiable APK updates with TOFU certificate pinning (auto-disabled on Zapstore installs)
- **In-app developer contact** via encrypted Nostr DMs

---

## Download

| Channel | When to pick it | Trade-off |
|---|---|---|
| **[Zapstore](https://zapstore.dev/apps/com.cruxcoach.android)** | You already use Zapstore, want hands-off auto-updates and Nostr-native verifiable builds | Requires the Zapstore client app installed |
| **[Codeberg release APK](https://codeberg.org/CruxCoach/CruxCoach/releases/latest)** | You want a direct sideload, no app-store dependency, full SHA-256 transparency | Manual install + updates (or opt into the in-app updater under *Settings → Updates*) |
| **[GitHub release APK](https://github.com/CruxCoach/CruxCoach/releases/latest)** | Codeberg is unavailable or GitHub is your preferred forge | Identical signed APK and SHA-256 sidecar |
| **[Source build](#building-from-source)** | You want to read / patch the code first | Requires Android SDK + NDK and a few minutes |

### Verifying the APK

Each Codeberg and GitHub release ships an `*.apk.sha256` sidecar next to the identical APK asset. After downloading both into the same folder:

```bash
sha256sum -c CruxCoach-v*.apk.sha256
```

Expected output: `CruxCoach-v0.2.2.apk: OK`. The signing certificate is the same across releases — Android refuses installs from a different cert, which is your second integrity check on top of the SHA-256.

### Updating

- **Zapstore**: handled by the Zapstore client.
- **Codeberg/GitHub APK + in-app updater**: open *Settings → Updates → Check for updates*. CruxCoach checks the configured release sources and verified mirrors, then installs only an APK whose SHA-256 and signing certificate match.
- **Source build**: `git pull && ./gradlew :androidApp:assembleRelease`.

---

## Building from Source

```bash
git clone https://codeberg.org/CruxCoach/CruxCoach.git
cd CruxCoach
bash scripts/setup_dev_env.sh   # installs JDK 17, Android SDK, NDK, CMake (Debian/Ubuntu)
source ~/.bashrc                # or ~/.zshrc
./gradlew :androidApp:assembleDebug
```

The APK is at `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

For signing, testing, and full setup details see [CONTRIBUTING.md](CONTRIBUTING.md#development-setup).

---

## Architecture

| Layer | Technology |
|-------|-----------|
| Language | Kotlin (shared + Android) |
| UI | Jetpack Compose + Material 3 |
| Database | SQLDelight (board data) + SQLCipher (personal data) |
| DI | Hilt |
| Async | kotlinx-coroutines |
| Serialization | kotlinx-serialization |
| Navigation | Jetpack Navigation Compose |
| BLE | Android BLE API (Nordic UART Service) |
| Nostr | Quartz (Vitor Pamplona) |
| Background | WorkManager |

Domain logic lives in a shared Kotlin Multiplatform module (~60–70% of code). See [CONTRIBUTING.md](CONTRIBUTING.md#project-structure) for the full project structure.

---

## Board Compatibility

Currently supported:

- **Kilter Board** — all sizes and angles (catalogue, BLE send, logbook, climb authoring).
- **MoonBoard** — 2010, 2016, Masters 2017, Masters 2019, Mini 2020, 2024 and Mini 2025 (catalogue, BLE send, logbook import and CruxCoach-community climb authoring). The 2024 set carries BoardSesh-community problems only for now — no official catalogue yet.
- **Tension, Grasshopper, Decoy, So iLL, Touchstone** — catalogue browse, render, hold heatmap, BLE send, and CruxCoach-community climb authoring (these share the Aurora protocol with Kilter).
- **Quantum Board** — catalogue browse, BLE send, authoritative live-layer readback and overlap-aware filtering. Controller capabilities and the selected model are checked before sending.

> Primarily tested on the Kilter 12x12 Original layout and MoonBoard 2016. Other sizes/variants should work but are less tested — feedback welcome!

See [LEGAL.md](LEGAL.md) for our position on interoperability and data usage.

---

## Data & Privacy

**Board database** — community-created content distributed via Nostr Blossom. Content-addressed and verifiable by any client. See [LEGAL.md](LEGAL.md).

**Personal data** — stored locally in an encrypted SQLCipher database. Encryption keys live in the Android Keystore and never leave the device.

**Optional cloud backup** *(off by default)* — when you turn it on, your data is encrypted on the device with a key derived from your Nostr identity, then mirrored across the open Nostr network and Blossom storage servers. No single provider holds a usable copy. Saving your CruxCoach Account key once is what makes the backup recoverable on any other device — see [SECURITY.md](SECURITY.md#encrypted-cloud-backup-feat-002-013) for the full threat model.

**Your identity** — a Nostr key pair. No central server can lock you out. The same key pair that is your CruxCoach Account encrypts your cloud backup, so saving it once protects both.

**Anonymous update counter** — in official endpoint-enabled builds, this setting is on by default and can be disabled persistently under *Settings → Updates*. After the in-app updater fully downloads an APK and verifies both its SHA-256 and signing certificate, the app makes at most one best-effort dispatch attempt per target version containing only that version and the configured source that served it. It creates no device, installation, account, Nostr, advertising, session, or event identifier; ordinary/fork builds have an empty endpoint unless their builder explicitly configures one. There is no retry, and failure can never affect update checking, verification, download readiness, or installation. The first-party backend immediately folds accepted requests into UTC-day aggregates, retains no raw event, request header, IP address, User-Agent, referrer, or exact timestamp, and retains the identifier-free daily aggregates and repository history without a fixed deletion period. This counts verified updater APKs, not people or successful PackageInstaller completions; Zapstore-managed store updates are excluded. See the [complete client, backend, and retention contract](docs/anonymous-update-metrics.md) and the public [privacy notice](https://cruxcoach.org/privacy.html).

---

## Contributing

We welcome contributions! See [CONTRIBUTING.md](CONTRIBUTING.md) for bug reporting, dev setup, coding standards, and PR guidelines.

For the active feature roadmap and per-release specifications, see [`docs/specs/`](docs/specs/).

## Security

Found a vulnerability? See [SECURITY.md](SECURITY.md) for responsible disclosure.

## Legal

CruxCoach is not affiliated with Kilter, LLC or Aurora Climbing. See [LEGAL.md](LEGAL.md).

---

## Support

- **Bug Reports**: [Codeberg Issues](https://codeberg.org/CruxCoach/CruxCoach/issues) or in-app via Settings
- **Donate** (upstream maintainer): Lightning `npub1uadpshqpn5ysf82lev8zngkvn07szmkq7mvf9lyc7ml7qxq6fqxsmrqt2s@npub.cash`

  <img src="docs/lightning-qr.png" alt="Lightning: npub1uadpshqpn5ysf82lev8zngkvn07szmkq7mvf9lyc7ml7qxq6fqxsmrqt2s@npub.cash" width="220">

  > Forks: this address routes to the upstream maintainer. Replace it via
  > `local.properties` before publishing your build — see
  > [CONTRIBUTING.md → Customizing for forks](CONTRIBUTING.md#customizing-for-forks).

---

## Project site

[**cruxcoach.org**](https://cruxcoach.org/) — screenshots, the privacy architecture in
full, and the FAQ people actually ask before installing.

- [Board locations map](https://cruxcoach.org/boards/) — 2,800+ Kilter, MoonBoard,
  Tension and other venues worldwide, searchable by gym, city or country
  ([plain text directory](https://cruxcoach.org/boards/list.html) for non-JS clients)
- [Coming from the Kilter app?](https://cruxcoach.org/kilter-board-app-alternative.html) —
  migration and alternative guide
- [MoonBoard app](https://cruxcoach.org/moonboard-app.html) ·
  [Tension Board app](https://cruxcoach.org/tension-board-app.html)
- [Privacy notice](https://cruxcoach.org/privacy.html) — what the site and the app do
  and do not send

---

## License & Trademark

- **Code**: [GNU General Public License v3.0](LICENSE) — Copyright (C) 2025-2026 CruxCoach Contributors.
- **Name + logo**: Reserved — see [TRADEMARK.md](TRADEMARK.md). Forks distributing modified binaries to a wide audience must rename and replace the launcher icon.
- **Bundled & vendored third-party components**: see [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) and [NOTICE](NOTICE).
