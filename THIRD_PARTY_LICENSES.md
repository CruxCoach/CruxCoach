# Third-Party Licenses

CruxCoach source code is licensed under the GNU General Public License v3.0
(see [LICENSE](LICENSE)). The CruxCoach **name and logo** are *not* covered
by GPLv3 — they are subject to a separate trademark policy
(see [TRADEMARK.md](TRADEMARK.md)) and the brand-asset license at
[`logos/LICENSE`](logos/LICENSE).

This document lists third-party components distributed with CruxCoach and the
licenses under which they are used.

Runtime dependencies pulled at build time (Gradle/Maven) are not listed here —
their license metadata is captured in the build output. This file documents
**vendored** sources and **bundled assets** that ship inside the repository
or APK.

---

## Vendored Source Code

### zstd (1.5.6) — Meta Platforms, Inc.

- **Location:** [`androidApp/src/main/cpp/zstd/`](androidApp/src/main/cpp/zstd/)
- **Upstream:** https://github.com/facebook/zstd
- **License:** Dual BSD-3-Clause OR GPLv2
- **CruxCoach election:** **BSD-3-Clause** (required for GPLv3 compatibility)
- **Texts:** [`LICENSE`](androidApp/src/main/cpp/zstd/LICENSE), [`COPYING`](androidApp/src/main/cpp/zstd/COPYING)
- **Details:** [`README.md`](androidApp/src/main/cpp/zstd/README.md)

Used to decompress board manifests downloaded from Blossom relays.

### MoonBoard hold-set cell map — BoardSesh

- **Location:** [`MoonBoardHoldSets.kt`](shared/src/commonMain/kotlin/com/cruxcoach/domain/board/MoonBoardHoldSets.kt)
- **Upstream:** https://github.com/boardsesh/boardsesh —
  `MOONBOARD_CELL_SETS` and `MOONBOARD_SETS` in
  `packages/shared/board-config/src`
- **License:** Apache License 2.0
- **License text:** https://www.apache.org/licenses/LICENSE-2.0
- **Distribution form:** the mapping data (hold-set ids, product names, and
  which grid cell belongs to which set, for all seven MoonBoard layouts) is
  transcribed into Kotlin literals; no BoardSesh code is copied.

Used to show which holds a MoonBoard hold set covers, and to derive the
`climbs.hsm` hold-set mask for locally authored and peer-received MoonBoard
climbs (FEAT-049).

---

## Bundled Assets

### Kilter Board layout images

- **Location:** [`androidApp/src/main/assets/board_images/`](androidApp/src/main/assets/board_images/)
- **Files:** `board_7.webp`, `board_8.webp`, `board_10.webp`, `board_14.webp`, `board_27.webp`, `board_28.webp`
- **Source:** extracted from the Kilter Board app's own data files
  (`product_sizes.image_filename`) and re-encoded as WebP.
- **Rights holder:** Kilter Grips, LLC (formerly Aurora Climbing)
- **Basis for bundling:** referential / interoperability use under
  § 23(1) No. 3 MarkenG (DE) and analogous nominative fair-use doctrines —
  see [`LEGAL.md`](LEGAL.md#bundled-kilter-layout-images).
- **Details:** [`board_images/README.md`](androidApp/src/main/assets/board_images/README.md)
- **Takedown:** Kilter Grips, LLC (or a prior rights holder) may request
  removal or replacement at any time via the contacts in
  [`SECURITY.md`](SECURITY.md). Requests will be handled promptly.

### MoonBoard 2010 and Mini MoonBoard 2025 layout images

- **Location:** [`androidApp/src/main/assets/board_images/`](androidApp/src/main/assets/board_images/)
- **Files:** `moonboard_2010_*.png`, `mini_moonboard_2025_*.png`
- **Source:** extracted unchanged from the official MoonBoard Android app
  (`1.3.56`); one board plate plus the fixed transparent hold layers for each
  complete configuration.
- **Rights holder:** Moon Climbing Ltd
- **Basis for bundling:** referential offline visualization and hardware
  interoperability; see [`LEGAL.md`](LEGAL.md#bundled-moonboard-layout-images).
- **Details:** [`board_images/README.md`](androidApp/src/main/assets/board_images/README.md)
- **Takedown:** Moon Climbing Ltd may request removal or replacement at any
  time via the contacts in [`SECURITY.md`](SECURITY.md). Requests will be
  handled promptly.

### Aurora-family board layout images

- **Location:** [`board_images/`](androidApp/src/main/assets/board_images/) —
  `tension/`, `grasshopper/`, `decoy/`, `soill/`, `touchstone/`
- **Files:** `board_<product_size_id>.webp` per board (Tension 1–9,
  Grasshopper 2–6, Decoy 1–3, So iLL 1–2, Touchstone 1)
- **Source:** extracted from each board's official Aurora Climbing app
  (`com.auroraclimbing.*`) — the `product_sizes_layouts_sets` image for the
  dominant layout's base hold set — and re-encoded as WebP.
- **Rights holders:** the respective board makers (Tension Climbing,
  Grasshopper, Decoy, So iLL, Touchstone Climbing); apps published on the
  Aurora Climbing platform.
- **Basis for bundling:** referential / interoperability use under
  § 23(1) No. 3 MarkenG (DE) and analogous nominative fair-use doctrines —
  see [`LEGAL.md`](LEGAL.md#bundled-aurora-family-layout-images).
- **Details:** [`board_images/README.md`](androidApp/src/main/assets/board_images/README.md)
- **Takedown:** any rights holder may request removal or replacement at any
  time via the contacts in [`SECURITY.md`](SECURITY.md). Requests will be
  handled promptly (per board).

These images are **not** covered by the CruxCoach GPLv3 source license.

---

## Bundled Datasets

### dontkillmyapp.com — OEM background-killer taxonomy

- **Used in:** [`NotificationReliabilityHelper.kt`](androidApp/src/main/java/com/cruxcoach/android/notification/NotificationReliabilityHelper.kt)
- **Source:** https://dontkillmyapp.com/
- **Maintainer:** Urbandroid Team
- **License:** CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/)
- **Scope of use:** Manufacturer/brand → severity classification
  (`NONE` / `MODERATE` / `SEVERE`) and the corresponding OEM autostart-settings
  Activity component names. Underlying package names and intent components are
  functional Android API references; the editorial severity classification is
  reproduced under CC BY-SA 4.0.
- **Modifications:** Adapted into Kotlin enums + Android `ComponentName` lookups;
  not redistributed verbatim.

### @hangtime/climbing-boards — Gym/wall locations GeoJSON

- **Used in:** [`BoardDatabaseImporter.importLocations()`](androidApp/src/main/java/com/cruxcoach/android/data/BoardDatabaseImporter.kt),
  consumed by [`BoardLocationRepository`](shared/src/commonMain/kotlin/com/cruxcoach/data/repository/BoardLocationRepository.kt)
  (board-locations map, FEAT-015).
- **Source:** https://www.npmjs.com/package/@hangtime/climbing-boards
- **License:** Unlicense (public-domain dedication) — no attribution or
  share-alike obligation; GPL-3.0-compatible.
- **Upstream provenance:** Daily mirror of the Kilter PowerSync `global_gyms`
  bucket, augmented with StoreRocket contact data. CruxCoach does not directly
  contact the original sources at runtime; the upstream package builds a
  static GeoJSON which CruxCoach mirrors via the Blossom locations chunk.
- **Scope of use:** Public gym entities (lat/lng, address, name, phone,
  email, website, accessibility, board hardware metadata). No personal
  user data is included.
- **Redistribution basis:** The package is published under the Unlicense
  (public-domain dedication), so it carries no attribution or copyleft
  obligation. Its records are *factual* public-business-directory data about
  commercial gyms — not climbs, user data, or copyrightable expression — for
  which copyright/database protection is thin to none (see the climb-data
  reasoning in `LEGAL.md`). CruxCoach mirrors only this factual location layer
  and never itself accesses any Kilter/Aurora API to obtain it. The upstream
  maintainer derives part of the dataset from Kilter's PowerSync bucket; if a
  rights holder objects to that upstream sourcing, the Take-down path below
  applies and the entire location layer can be dropped without affecting core
  app function.
- **Modifications:** Re-encoded into the SQLite-shaped chunk schema in
  `shared/src/commonMain/sqldelight/board/12.sqm` / `13.sqm`; wall and
  product-size joins are applied at chunk build time, not at app runtime.
- **Take-down:** A gym or rights holder may request removal at any time
  via the contacts in `SECURITY.md`. Requests are forwarded upstream
  to the dataset maintainer where applicable.

---

## Map Rendering & Tile Data

### MapLibre Native (Android)

- **Used in:** [`MapView.kt`](androidApp/src/main/java/com/cruxcoach/android/ui/map/MapView.kt),
  [`MapMarkerLayer.kt`](androidApp/src/main/java/com/cruxcoach/android/ui/map/MapMarkerLayer.kt),
  [`MapScreen.kt`](androidApp/src/main/java/com/cruxcoach/android/ui/map/MapScreen.kt) — the
  rendering engine for the board-locations map (FEAT-015).
- **Upstream:** https://github.com/maplibre/maplibre-native
- **License:** BSD 2-Clause (Simplified)
- **License text:** https://github.com/maplibre/maplibre-native/blob/main/LICENSE.md
- **Distribution form:** runtime Maven dependency (`org.maplibre.gl:android-sdk`)
  pulled at build time; not vendored into the repository tree.

### mapbox-android-gestures

- **Used by:** transitive dependency of MapLibre Native, providing
  touch-gesture interpretation for pan/zoom/rotate.
- **Upstream:** https://github.com/mapbox/mapbox-gestures-android
- **License:** Apache License 2.0
- **License text:** https://www.apache.org/licenses/LICENSE-2.0
- **NOTICE:** Apache 2.0 §4(d) requires preserving any `NOTICE` file from
  the dependency. The upstream NOTICE attribution is reproduced via
  [`NOTICE`](NOTICE) at the repository root.
- **Distribution form:** runtime Maven dependency; not vendored.

### OpenFreeMap (vector tile provider)

- **Used in:** [`MapStyleProvider.kt`](androidApp/src/main/java/com/cruxcoach/android/ui/map/MapStyleProvider.kt) — default style URL
  resolves to OpenFreeMap's hosted tiles.
- **Upstream:** https://openfreemap.org/
- **License & terms:** Free for any use, with attribution and the
  request that high-traffic users self-host. The style/schema is
  based on **OpenMapTiles** (BSD-3-Clause).
- **Attribution:** Surfaced in-app via MapLibre's default attribution
  control (`UiSettings.isAttributionEnabled = true`, locked in
  defensively in `MapView.kt`).

### OpenStreetMap (geographic base data)

- **Used as:** the underlying data source for the OpenFreeMap tile
  service above. Every map tile delivered by OpenFreeMap is derived
  from OSM.
- **Upstream:** https://www.openstreetmap.org/
- **License:** Open Database License (ODbL) v1.0
- **License text:** https://opendatacommons.org/licenses/odbl/1-0/
- **Attribution requirement (ODbL §4.3):** A "© OpenStreetMap
  contributors" notice must accompany the produced work. CruxCoach
  satisfies this in two places: (1) the MapLibre default attribution
  control rendered inside the map UI, and (2) this file plus the root
  [`NOTICE`](NOTICE) in the distribution.

---

## Build-Time Bundled Libraries

### Core-library desugaring (desugar_jdk_libs)

- **Used in:** core-library desugaring for the `androidApp` module
  ([`androidApp/build.gradle.kts`](androidApp/build.gradle.kts) —
  `isCoreLibraryDesugaringEnabled` plus the `coreLibraryDesugaring(...)`
  dependency). Backports modern `java.*` APIs (e.g. `java.time`,
  `SequencedCollection`) to the `minSdk = 26` device range so the app runs on
  older Android versions without per-call `SDK_INT` guards.
- **Upstream:** https://github.com/google/desugar_jdk_libs
- **License:** GNU General Public License, version 2, with the Classpath
  Exception (SPDX `GPL-2.0-with-classpath-exception`). The library is derived
  from OpenJDK; the Classpath Exception explicitly permits linking/bundling it
  into an independent work, so it imposes no GPL obligations on CruxCoach itself.
- **License text:** GPLv2 — https://www.gnu.org/licenses/old-licenses/gpl-2.0.html;
  the combined GPLv2 + Classpath Exception is published with the project at
  https://github.com/google/desugar_jdk_libs (see its `LICENSE`).
- **Distribution form:** not vendored into the repository tree; the backported
  implementations are compiled into the app's DEX by D8/L8 at build time (the
  `l8DexDesugarLibRelease` task) and therefore ship inside the APK.

---

### FIPS (Free Internetworking Peering System)

- **Used in:** `native/fips-bridge`, compiled into `libcruxcoach_fips.so`.
- **Upstream:** https://github.com/jmcorgan/fips
- **Vendored revision:** `6580a806f9b05ee10497786f872fd65480ca8e5c` (reviewed
  platform-integration lineage; supersedes
  `967776079ba5ddc8fe118c3f289365b51eb03737`).
- **License:** MIT. Full text: the upstream `native/fips/LICENSE`, also
  mirrored at `native/fips-bridge/LICENSE-FIPS-MIT`.
- **Distribution form:** source vendored into this repository at `native/fips`
  and an arm64 binary in the APK. It is a Cargo *path* dependency, not a Git
  revision: the reviewed commit is a branch head, and a `rev =` pin would leave
  the build dependent on upstream object retention.
- **Modifications:** exactly the patch series in `native/fips/patches`, applied
  in the order recorded by `native/fips/VENDOR.toml`. Everything else is
  byte-identical to upstream. `python3 scripts/verify_vendored_fips.py` proves
  the first claim offline (and runs in CI); adding
  `--upstream <path-to-fips-clone>` proves the second against upstream itself.

### Myco Android BLE reference

- **Used as:** build/protocol reference for Android L2CAP ownership and the
  FIPS JNI bridge; CruxCoach maintains its implementation in its own package.
- **Upstream:** https://github.com/Origami74/myco
- **Reference revision:** `85316faf80fda48bfef8977584ab4ad68203de02`
- **License:** MIT. Full text: `native/fips-bridge/LICENSE-MYCO-MIT`.
  No Myco binary or asset is bundled.

---

## Notes for Maintainers

When vendoring a new third-party source tree:

1. Place sources under a dedicated directory (e.g. `androidApp/src/main/cpp/<name>/`).
2. Copy the upstream `LICENSE` (and any other required notice files) verbatim
   into that directory.
3. Add a `README.md` documenting upstream URL, version, import date, and
   license election (if dual-licensed).
4. Add an entry to this file linking to the texts and stating the election.
5. Add an entry to [`NOTICE`](NOTICE) if attribution is required by the license.

When bundling a third-party asset (image, font, model, dataset):

1. Keep an attribution entry in this file with source, license, and any
   restrictions on redistribution.
2. If the license requires it, surface the attribution in the app's about /
   credits screen.
