# Legal & Intellectual Property Policy

## What This Project Is

CruxCoach is a free, open-source climbing app for use with the Kilter Board and MoonBoard climbing training systems. It provides climb browsing, BLE board control, session tracking, logbook management, progress analytics, Kilter account sync, and CruxCoach-community climb authoring. It is non-commercial and maintained by volunteers.

The legal reasoning below is written primarily around the Kilter Board (the project's original target). The same principles — factual/functional climb data, community-created content, referential trademark use, and interoperability via BLE — apply equally to the MoonBoard; MoonBoard-specific provenance details are still being documented.

---

## Our Position on Climb Data

### Climbs are factual data

A climb on a standardised board is a list of hold positions on a fixed, manufactured layout (e.g. "hold 1461 as start, hold 1575 as hand, hold 1636 as finish"), combined with a grade and wall angle. This is factual, functional information — comparable to a chess position, a set of GPS coordinates, or a recipe — constrained by the physical board layout and biomechanical feasibility.

Under EU copyright law, a work must constitute the "author's own intellectual creation" (*Infopaq International A/S v. Danske Dagblades Forening*, C-5/08, 2009). Expression "dictated by technical considerations, rules or constraints which leave no room for creative freedom" receives no protection (*Brompton Bicycle Ltd v. Chedech/Get2Get*, C-833/18, 2020). A standardised board with a finite number of holds (typically 200-500) severely constrains the possible expressions. The data representation of a climb — a list of hold IDs — captures functional instructions, not creative expression.

The same principle is reflected in U.S. copyright law, where facts are not copyrightable (*Feist Publications, Inc. v. Rural Telephone Service Co.*, 499 U.S. 340, 1991).

### The database is community-created

The climb databases for these boards are almost entirely **user-generated content**. Individual climbers create and submit climbs. The board manufacturers did not author this content — they provide a platform for submission and display.

**Aurora Climbing** (former Kilter Board app operator): Aurora's Terms of Use granted them a *"perpetual, unrestricted, unlimited, non-exclusive, irrevocable license"* over user-submitted content ("Contributed Content"). Crucially, this license was **non-exclusive** — under **§ 31(2) UrhG**, a non-exclusive right (*einfaches Nutzungsrecht*) does not exclude use by others. Users retain full rights to share their own created routes through any channel.

**Kilter Grips** (current Kilter Board app operator, since March 2026): Kilter's Terms of Use (Section 5) distinguish between "Your Content" (user-generated) and Kilter's services. Unlike Aurora, Kilter does **not** claim a perpetual irrevocable license over user-created climbs. User-created climbs remain the property of their creators.

### Sui generis database right

The EU Database Directive (96/9/EC, Article 7) grants protection to databases where there has been "substantial investment in obtaining, verifying, or presenting" their contents. However, in *The British Horseracing Board Ltd v. William Hill* (C-203/02, 2004), the CJEU drew a critical distinction: only investment in **obtaining** pre-existing data qualifies — investment in **creating** new data does not.

User-generated climbing routes are *created* by community members and submitted to the platform, not *obtained* from pre-existing independent sources. This directly parallels the football fixture lists in the *Fixtures Marketing* cases (C-46/02, C-338/02, C-444/02), where the CJEU denied sui generis protection because the data originated from the organiser's own activity rather than independent collection.

### Comprehensive databases have weak compilation protection

Even where a database arrangement might qualify for thin copyright protection as a compilation, protection extends only to creative selection or arrangement — not to the underlying facts (*Football Dataco Ltd v. Yahoo! UK Ltd*, C-604/10, 2012). The CJEU held that "significant labour and skill" in creating a database does not justify copyright — only "free and creative choices" showing a "personal touch" suffice. A database that comprehensively collects all user submissions without editorial curation provides no basis for compilation copyright.

---

## Data Distribution via Nostr Blossom

CruxCoach distributes board reference data and community-created climb data via the **Nostr Blossom protocol** (BUD-02), a decentralised content-addressed storage system built on the Nostr network.

### What we distribute

| Data | Type | Source | Rationale |
|------|------|--------|-----------|
| Board layouts, hold positions, mounting holes, LED mappings | Hardware reference data | Derived from product specifications | Functional facts about physical hardware |
| Climbs (hold sequences + grades) | Community-created factual data | User-generated content | Factual data, created by climbers |
| Climb statistics (difficulty averages, ascent counts) | Aggregated community data | Community activity metrics | Statistical facts |
| Gym & wall locations (FEAT-015) | Public-business directory data | [`@hangtime/climbing-boards`](https://www.npmjs.com/package/@hangtime/climbing-boards) (Kilter PowerSync `global_gyms` mirror + StoreRocket contact records) | Factual / functional information about commercial gym entities, used to render the in-app board-locations map. No user personal data. |

### What we do NOT distribute

- Kilter's or Moon Climbing's proprietary software, source code, or firmware
- Kilter's or MoonBoard's wordmark, logo, or marketing/branding artwork
- User personal data (email, profile photos, account details)
- Kilter's or the MoonBoard app's binary or any portion thereof

### Bundled Kilter layout images

A small set of Kilter Board layout photographs lives inside the APK at
[`androidApp/src/main/assets/board_images/`](androidApp/src/main/assets/board_images/)
so the app can render a recognisable offline view of the board behind its
hold overlay. These images remain the property of **Kilter Grips, LLC**
(formerly Aurora Climbing) and are included strictly for interoperability
and referential purposes — permitted under **§ 23(1) No. 3 MarkenG** in
Germany and under analogous referential/nominative fair-use doctrines in
other jurisdictions. CruxCoach claims no ownership, affiliation, or
endorsement. See the in-directory
[`README.md`](androidApp/src/main/assets/board_images/README.md) for origin,
scope, and removal-request contacts, and
[`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md) for the maintained
attribution inventory.

### Bundled Aurora-family layout images

CruxCoach renders five further Aurora-protocol boards — **Tension**,
**Grasshopper**, **Decoy**, **So iLL**, and **Touchstone** (FEAT-031). A small
per-board set of their layout images is bundled under
[`androidApp/src/main/assets/board_images/`](androidApp/src/main/assets/board_images/)
(in `tension/`, `grasshopper/`, `decoy/`, `soill/`, and `touchstone/`
subfolders) for the same offline referential visualization as the Kilter
images above. Each was extracted from the corresponding board's official
Aurora Climbing app and remains the property of its respective maker; the
board apps are published on the **Aurora Climbing** platform. Inclusion is
strictly for interoperability and referential purposes — permitted under
**§ 23(1) No. 3 MarkenG** in Germany and analogous referential/nominative
fair-use doctrines elsewhere. CruxCoach claims no ownership, affiliation, or
endorsement. See the in-directory
[`README.md`](androidApp/src/main/assets/board_images/README.md) for origin,
per-board rights holders, and removal-request contacts.

### Map rendering & tile data

The FEAT-015 board-locations map is rendered by **MapLibre Native (Android)**
(BSD-2-Clause), using vector tiles served by **OpenFreeMap**
(https://openfreemap.org/), whose style and schema derive from
**OpenMapTiles** (BSD-3-Clause). The underlying geographic data is
**© OpenStreetMap contributors**, licensed under the
[Open Database License (ODbL) v1.0](https://opendatacommons.org/licenses/odbl/1-0/).

ODbL §4.3 ("Notice for using or Redistributing the Database") requires a
visible attribution. CruxCoach satisfies this requirement in two places:

1. **In-app:** MapLibre's default attribution control is enabled
   (`androidApp/src/main/java/com/cruxcoach/android/ui/map/MapView.kt`
   defensively sets `uiSettings.isAttributionEnabled = true` and
   `uiSettings.isLogoEnabled = true`).
2. **In distribution:** the project root [`NOTICE`](NOTICE) and
   [`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md) carry the
   attribution for OpenStreetMap, OpenFreeMap, OpenMapTiles, MapLibre,
   and the Apache 2.0 NOTICE for the transitive
   `mapbox-android-gestures` dependency.

CruxCoach does not vendor MapLibre, mapbox-android-gestures, OpenFreeMap
style files, or OpenStreetMap data into the repository tree; these are
pulled at build time (Maven) or fetched at runtime (tile HTTP requests).

### Gym & wall locations dataset

The board-locations map (FEAT-015) sources gym entities — names,
coordinates, addresses, contact details (phone / email / website /
Instagram), accessibility classification, board-hardware metadata — from
the [`@hangtime/climbing-boards`](https://www.npmjs.com/package/@hangtime/climbing-boards)
npm package. That dataset is itself a daily mirror of the Kilter
PowerSync `global_gyms` bucket, augmented with StoreRocket contact
records. The data covers commercial gym entities only; no end-user
personal data is included. CruxCoach distributes this data via the
daily Blossom locations chunk; the upstream maintainers handle update
correctness and removal at the dataset level. Gym operators or rights
holders may request removal at any time via the contacts in `SECURITY.md`,
which are forwarded upstream where applicable.

### Integrity & verifiability

All data chunks are content-addressed by their SHA-256 hash and served via the Blossom protocol. A Nostr manifest event (Kind 30078, NIP-78) published to public relays contains the hash, size, and download URLs for each chunk, enabling any client to verify data integrity independently.

---

## Attribution & Respect for Creators

We respect the climbing community and the people who create climbs.

- Where climb creator information is available, we attribute the creator by their username.
- We do not claim authorship of climbs we did not create.
- If you are the creator of a climb and would like it removed from this project, please open an issue or contact us. We will remove it from all data sources under our control. Note that due to the decentralised nature of the Nostr/Blossom protocol, we cannot guarantee removal from third-party mirrors or relays.

---

## Interoperability & Hardware Compatibility

### BLE communication

CruxCoach communicates with Kilter Board hardware over standard Bluetooth Low Energy (BLE) using the Nordic UART Service (NUS), a widely documented BLE GATT service. The board hardware openly advertises this service through standard GATT service discovery. Sending BLE packets to a documented, publicly discoverable service on purchased hardware does not reproduce any copyrighted code and does not constitute a restricted act under copyright law.

No technological protection measures are circumvented. NUS is a communication protocol, not an access control mechanism.

EU and German law independently establish the right to analyse and interoperate with lawfully acquired products:
- **§ 69e UrhG** (implementing EU Software Directive 2009/24/EC, Article 6): statutory right to decompile for interoperability, non-waivable per § 69g(2) UrhG
- **§ 3(1) No. 2 GeschGehG** (implementing EU Trade Secrets Directive 2016/943): reverse engineering of a lawfully acquired product is a lawful means of acquiring information
- The CJEU confirmed in *SAS Institute Inc. v. World Programming Ltd* (C-406/10, 2012) that functionality, programming languages, and data file formats are not copyrightable

### Trademark

"Kilter Board" and "Kilter" are trademarks of Kilter Grips, LLC. "MoonBoard" and "Moon" are trademarks of Moon Climbing Ltd. CruxCoach uses these marks solely to indicate compatibility with the respective board hardware, as permitted under **§ 23(1) No. 3 MarkenG** (referential use to indicate intended purpose) and **Art. 14(1)(c) EUTMR (2017/1001)**.

The board-locations map (FEAT-015) additionally displays the names of other board brands (e.g. Tension, Grasshopper, Decoy, So iLL, Touchstone, Aurora, 12climb) and the **egym Wellpass** membership brand, purely to label third-party gym/board locations. These names are trademarks of their respective owners and are used referentially under the same provisions; CruxCoach claims no affiliation with, endorsement by, or sponsorship from any of them.

CruxCoach is an independent, community-developed open-source project. It is not affiliated with, endorsed by, sponsored by, or in any way officially connected with Kilter Grips, LLC, Aurora Climbing, Moon Climbing Ltd, egym, or any board manufacturer. All product and company names are trademarks of their respective holders.

---

## Copyright Concerns

If you believe any material in this project infringes your copyright, please contact us:

- **Nostr DM** (NIP-17 encrypted): developer pubkey listed in [SECURITY.md](SECURITY.md)
- **Codeberg**: open an issue on the [repository](https://codeberg.org/CruxCoach/CruxCoach)

We will review all concerns in good faith.

---

*This document is provided for informational purposes and does not constitute legal advice. Last updated: 2026-04-14.*
