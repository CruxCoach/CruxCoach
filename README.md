# CruxCoach

Open-source Kilter Board climbing app for Android.

Browse climbs, control your Kilter Board via Bluetooth, log ascents, import your Kilter logbook, and track your progress — no third-party cloud services, full control over your data.

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
  <a href="#building-from-source">
    <img src="https://img.shields.io/badge/Source-build_yourself-374151?style=for-the-badge&logo=gnu&logoColor=white" alt="Build from source">
  </a>
</p>

<p align="center">
  <em>
    <a href="https://zapstore.dev/apps/com.cruxcoach.android">Zapstore</a> auto-updates, Nostr-native, verifiable builds &middot;
    <a href="https://codeberg.org/CruxCoach/CruxCoach/releases/latest">APK release</a> ships with a <code>.apk.sha256</code> sidecar &middot;
    <a href="#building-from-source">Source build</a> reproducible from <code>main</code>
  </em>
</p>

<p align="center">
  <img src="docs/screenshots/board-browser.png" alt="Board Browser" width="220">&nbsp;&nbsp;
  <img src="docs/screenshots/climb-detail.png" alt="Climb Detail" width="220">&nbsp;&nbsp;
  <img src="docs/screenshots/hold-search.png" alt="Hold Search" width="220">&nbsp;&nbsp;
  <img src="docs/screenshots/heatmap.png" alt="Heatmap" width="220">
</p>

> **First-time sideload from Codeberg?** Android Settings → Apps → *Special app access* → *Install unknown apps* → enable for your browser or file manager. The signing certificate is stable across releases, so future updates install on top without re-enabling.

---

## What makes it different

**Custom LED Colors** — Choose your own hold colors on the Kilter Board. Different colors for start, hand, foot, and top holds. Visible to everyone around you.

**Nearby Sharing** — Share your current climb with nearby CruxCoach users over Bluetooth. No internet needed, works instantly at the Kilter Board.

**Your Data, Your Device** — All personal data encrypted locally. Decentralized identity via [Nostr](https://nostr.com) — no email, no password, no central server. No cloud accounts, no telemetry, no ads.

---

## All Features

- **85,000+ climbs** with filters for grade, angle, quality, moves, and setter
- **Board-Locations Map** *(0.1.5+)* — interactive world map of every known Kilter Board installation, with filters for layout, country, public-vs-private, adjustability and size; tap a marker for contact details and a direct link into the climbs that fit that exact board
- **Find-your-gym board picker** *(0.1.5+)* — set the right board configuration in Settings by searching for your gym, no product-size codes to memorise; always-on board-fit filter then narrows the catalog to climbs your board can actually do
- **BLE board control** — light up holds on your Kilter Board
- **Climb lists** — favorites, projects, custom lists
- **Log ascents** with grade opinions, attempts, and notes
- **Hold Search** — tap holds on the Kilter Board to find climbs that use them
- **Heatmap** — visualize hold popularity by type, sends, or all climbs
- **Kilter logbook import** from your Kilter account
- **Statistics** — grade progression, difficulty trends, favorite angles
- **Data export/import** as JSON backup
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
| **[Source build](#building-from-source)** | You want to read / patch the code first | Requires Android SDK + NDK and a few minutes |

### Verifying the APK

Each Codeberg release ships an `*.apk.sha256` sidecar next to the APK asset. After downloading both into the same folder:

```bash
sha256sum -c CruxCoach-v*.apk.sha256
```

Expected output: `CruxCoach-v0.1.x.apk: OK`. The signing certificate is the same across every release — Android refuses installs from a different cert, which is your second integrity check on top of the SHA-256.

### Updating

- **Zapstore**: handled by the Zapstore client.
- **Codeberg APK + in-app updater**: open *Settings → Updates → Check for updates*. The updater pulls the next release's APK + SHA-256 from Codeberg and installs over the current build (signature must match — same certificate as the original install).
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

Currently supported: **Kilter Board** (all sizes and angles).

> Primarily tested on the 12x12 Original layout. Other sizes should work but are less tested — feedback welcome!

See [LEGAL.md](LEGAL.md) for our position on interoperability and data usage.

---

## Data & Privacy

**Board database** — community-created content distributed via Nostr Blossom. Content-addressed and verifiable by any client. See [LEGAL.md](LEGAL.md).

**Personal data** — stored locally in an encrypted SQLCipher database. Encryption keys live in the Android Keystore and never leave the device.

**Optional cloud backup** *(off by default)* — when you turn it on, your data is encrypted on the device with a key derived from your Nostr identity, then mirrored across the open Nostr network and Blossom storage servers. No single provider holds a usable copy. Saving your CruxCoach Account key once is what makes the backup recoverable on any other device — see [SECURITY.md](SECURITY.md#encrypted-cloud-backup-feat-002-013) for the full threat model.

**Your identity** — a Nostr key pair. No central server can lock you out. The same key pair that is your CruxCoach Account encrypts your cloud backup, so saving it once protects both.

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
- **Donate** (upstream maintainer): Lightning `cruxcoach@npub.cash`

  <img src="docs/lightning-qr.png" alt="Lightning: cruxcoach@npub.cash" width="180">

  > Forks: this address routes to the upstream maintainer. Replace it via
  > `local.properties` before publishing your build — see
  > [CONTRIBUTING.md → Customizing for forks](CONTRIBUTING.md#customizing-for-forks).

---

## License & Trademark

- **Code**: [GNU General Public License v3.0](LICENSE) — Copyright (C) 2025-2026 CruxCoach Contributors.
- **Name + logo**: Reserved — see [TRADEMARK.md](TRADEMARK.md). Forks distributing modified binaries to a wide audience must rename and replace the launcher icon.
- **Bundled & vendored third-party components**: see [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) and [NOTICE](NOTICE).
