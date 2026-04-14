# Legal & Intellectual Property Policy

## What This Project Is

CruxCoach is a free, open-source climbing app for use with the Kilter Board climbing training system. It provides climb browsing, BLE board control, session tracking, logbook management, progress analytics, and Kilter account sync. It is non-commercial and maintained by volunteers.

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

### What we do NOT distribute

- Kilter's proprietary software, source code, or firmware
- Kilter's trademarks, logos, or branding assets
- User personal data (email, profile photos, account details)
- Kilter's app binary or any portion thereof

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

"Kilter Board" and "Kilter" are trademarks of Kilter Grips, LLC. CruxCoach uses these marks solely to indicate compatibility with the Kilter Board hardware, as permitted under **§ 23(1) No. 3 MarkenG** (referential use to indicate intended purpose) and **Art. 14(1)(c) EUTMR (2017/1001)**.

CruxCoach is an independent, community-developed open-source project. It is not affiliated with, endorsed by, sponsored by, or in any way officially connected with Kilter Grips, LLC, Aurora Climbing, or any board manufacturer. All product and company names are trademarks of their respective holders.

---

## Copyright Concerns

If you believe any material in this project infringes your copyright, please contact us:

- **Nostr DM** (NIP-17 encrypted): developer pubkey listed in [SECURITY.md](SECURITY.md)
- **Codeberg**: open an issue on the [repository](https://codeberg.org/CruxCoach/CruxCoach)

We will review all concerns in good faith.

---

*This document is provided for informational purposes and does not constitute legal advice. Last updated: 2026-04-14.*
