---
status: skeleton
---
# Feature Spec: Schema Naming Cleanup (v0.1.4)

> **Status:** Skeleton — drafted 2026-04-27. Decisions collected during the
> 0.1.3 release post-mortem (Aurora API died 2026-03-26; the `aurora_*` table
> prefix has been semantically wrong for ~5 weeks). Sections 1–7 (design)
> and 8–11 (rollout) are agreed. §4 column drop scope and §5 migration
> mechanics need a final pass against the actual SQLDelight migration
> setup before implementation.
> **Depends on:** —
> **Blocks:** FEAT-003 (Climb Creator), FEAT-005 (Aurora JSON Import). Both
> write into the renamed tables; merging this first means their code is
> born clean instead of inheriting a name we know we'll change anyway.

## 1. Overview

CruxCoach's local database carries three coexisting naming conventions:

| Layer                             | Naming                | Examples                                                                                                                                                           |
| --------------------------------- | --------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **Board DB** (`board.db`)         | `aurora_*` snake_case | `aurora_climb`, `aurora_climb_stat`, `aurora_placement`, `aurora_hole`, `aurora_led`, `aurora_product_size`, `aurora_board_image`, `aurora_beta_link`, `aurora_sync_state` |
| **Secure DB** (`secure.db`, encrypted) — board logbook | `aurora_*` snake_case | `aurora_ascent`, `aurora_bid`                                                                                                                                      |
| **Secure DB** — everything else   | mixed                 | `Announcement`, `Assessment`, `BodyStat`, `ClimbLog`, `NostrMessage`, `NostrProfile`, `PaymentEvent`, `TrainingPlan`, `TrainingSession`, `UserProfile`, `WorkoutLog` (PascalCase); `climb_list`, `climb_list_entry`, `board_session`, `board_hold_position` (plain snake_case singular) |

Three issues:

1. **`aurora_*` prefix is misleading.** Aurora Climbing took its API offline
   on 2026-03-26 (cease-and-desist over Kilter trademark/logo). All board
   data flows through the new Kilter API now (Keycloak + REST `/climbs/curated`)
   and the Blossom mirror cron — never Aurora. The prefix lies about
   provenance.
2. **Three conventions in one project add cognitive friction.** Every
   contributor has to remember "is this a `aurora_*` table, a snake_case
   plain table, or a PascalCase table?". Solo-maintainer reality: this
   tax compounds over hundreds of edits.
3. **The Blossom DB and Aurora-extracted Kilter App schemas use plural
   unprefixed names (`climbs`, `climb_stats`, `placements`).** Importer
   code currently does prefix translation (`climbs` → `aurora_climb`); a
   target schema closer to the source schema means less translation
   surface.

This spec consolidates all CruxCoach tables onto a single convention:
**plural snake_case, no provenance prefix.**

### 1.1 Goals

- Rename every `aurora_*` table and every PascalCase table to plural
  snake_case (`aurora_climb` → `climbs`, `NostrMessage` → `nostr_messages`)
- Drop the `aurora_*` and PascalCase prefix from index names + SQLDelight
  query files (filenames + content) + Kotlin string references
- Sweep Kotlin sources for `Aurora`-mentions that no longer fit
  ("Aurora API"-flavored comments, dead-URL fields in `AuroraBoard.kt`,
  Aurora-prefixed BLE classes, "Aurora difficulty" comments) — see §3.5
- Forward-only migration that preserves every row of every table —
  pure `ALTER TABLE … RENAME TO`, no rebuild dance, no column drops
- Zero change to wire formats: Cloud Backup envelope (FEAT-002), manual
  JSON export (`CruxCoachBackup`), Aurora JSON import target (FEAT-005),
  Nostr event tags/content (FEAT-003)
- All migration paths tested with synthetic + real-device smoke

### 1.2 Non-Goals

- **No column-name renames.** `frames_count` stays `frames_count` (even
  though Kilter API uses singular `frameCount`). Column-rename adds wire-
  format risk + migration complexity disproportionate to the benefit;
  defer to a future schema-evolution spec if ever wanted.
- **No new columns.** Schema-Diff doc §1.1 / §2.3 lists `setter_uuid`,
  `updated_at`, populating `official_kilter_difficulty` etc. as
  candidates. All deferred — they're additive and orthogonal to the
  rename.
- **No table consolidation.** `aurora_ascent` + `aurora_bid` → single
  `climb_log` would mirror Kilter's `POST /api/logs/bulk` shape better
  but is a domain refactor, not a rename. Out of scope for 0.1.4.
- **No Blossom DB changes.** Server-side schema (`scripts/data/kilter_board.bin`,
  the chunks on Blossom) stays identical — the Blossom DB is already
  closer to the target convention than CruxCoach is. Local-only refactor.
- **No code-level redesign.** Repository abstractions, query patterns,
  Hilt wiring all preserved. Mechanical rename only.

## 2. Naming Conventions

The four rules CruxCoach commits to after this spec:

| Rule                           | Detail                                                                                                  |
| ------------------------------ | ------------------------------------------------------------------------------------------------------- |
| **R1 — Plural snake_case**     | Every table is `body_stats`, never `BodyStat` or `body_stat`. Junction tables: `climb_list_entries`.    |
| **R2 — No provenance prefix**  | No `aurora_*`, no `kilter_*`, no `nostr_*`, no `cruxcoach_*`. Domain names speak for themselves.        |
| **R3 — Index names mirror**    | `idx_climbs_listed`, not `idx_aurora_climb_listed`.                                                      |
| **R4 — SQLDelight `.sq` files** | Filename matches table-cluster intent (`Climbs.sq`, `Nostr.sq`, `Training.sq`), not legacy entity name. |

Rule R2 explicitly excludes `board_*` style two-word descriptors —
`board_sessions`, `board_hold_positions` are domain prefixes, not
provenance prefixes, and stay.

## 3. Scope: Tables to Rename

### 3.1 Board DB (`shared/.../sqldelight/board/`)

| Today                  | Target           | File-rename                      |
| ---------------------- | ---------------- | -------------------------------- |
| `aurora_climb`         | `climbs`         | `AuroraBoard.sq` → `Board.sq`    |
| `aurora_climb_stat`    | `climb_stats`    | (same file)                      |
| `aurora_placement`     | `placements`     | (same file)                      |
| `aurora_hole`          | `holes`          | (same file)                      |
| `aurora_led`           | `leds`           | (same file)                      |
| `aurora_product_size`  | `product_sizes`  | (same file)                      |
| `aurora_board_image`   | `board_images`   | (same file)                      |
| `aurora_beta_link`     | `beta_links`     | (same file)                      |
| `aurora_sync_state`    | `sync_states`    | (same file)                      |
| `board_hold_position`  | `board_hold_positions` | (same file)                |

The `Board.sq` filename keeps the existing single-file-bundles-the-catalog
pattern; an optional split into per-cluster files (`Climbs.sq`, `Layout.sq`,
…) is documented in §6.1 — encouraged, not mandated, per Q-D.

**Out of scope in this rename — Aurora-prefixed Kotlin source files.**
`AuroraBleConnection.kt`, `AuroraBleScanner.kt`, `AuroraBleUuids.kt`,
`AuroraPacketEncoder.kt`, `AuroraBoard.kt` (the supported-board enum)
all keep their names. "Aurora" in those files refers to the Aurora-
Climbing **BLE protocol family** (Kilter / Tension / Decoy / Spire all
speak the same on-the-wire BLE GATT shape) — that protocol is alive
and well, and the BLE classes are correctly named. Only the *Aurora API*
provenance prefix on DB tables is dead and being removed.

### 3.2 Secure DB (`shared/.../sqldelight/secure/`)

Board logbook (currently in `AuroraAscent.sq`, `AuroraBid.sq`):

| Today           | Target    | File-rename             |
| --------------- | --------- | ----------------------- |
| `aurora_ascent` | `ascents` | `AuroraAscent.sq` → `Ascents.sq` |
| `aurora_bid`    | `bids`    | `AuroraBid.sq` → `Bids.sq`       |

PascalCase tables (one-table-per-file pattern stays; only the table name + filename change):

| Today              | Target              | File-rename                                |
| ------------------ | ------------------- | ------------------------------------------ |
| `Announcement`     | `announcements`     | `Announcement.sq` → `Announcements.sq`     |
| `Assessment`       | `assessments`       | `Assessment.sq` → `Assessments.sq`         |
| `BodyStat`         | `body_stats`        | `BodyStat.sq` → `BodyStats.sq`             |
| `ClimbLog`         | `climb_logs`        | `ClimbLog.sq` → `ClimbLogs.sq`             |
| `NostrMessage`     | `nostr_messages`    | `NostrMessage.sq` → `NostrMessages.sq`     |
| `NostrProfile`     | `nostr_profiles`    | `NostrProfile.sq` → `NostrProfiles.sq`     |
| `PaymentEvent`     | `payment_events`    | `PaymentEvent.sq` → `PaymentEvents.sq`     |
| `TrainingPlan`     | `training_plans`    | `TrainingPlan.sq` → `TrainingPlans.sq`     |
| `TrainingSession`  | `training_sessions` | `TrainingSession.sq` → `TrainingSessions.sq` |
| `UserProfile`      | `user_profiles`     | `UserProfile.sq` → `UserProfiles.sq`       |
| `WorkoutLog`       | `workout_logs`      | `WorkoutLog.sq` → `WorkoutLogs.sq`         |

Already snake_case singular — pluralize only:

| Today              | Target               |
| ------------------ | -------------------- |
| `board_session`    | `board_sessions`     |
| `climb_list`       | `climb_lists`        |
| `climb_list_entry` | `climb_list_entries` |

### 3.3 Total table count

22 table renames (10 board DB + 12 secure DB). All other tables in the
codebase already conform.

### 3.4 Index renames

Existing index names in `AuroraBoard.sq` (verified 2026-04-27):

```
idx_aurora_placement_set
idx_aurora_board_image_size
idx_aurora_beta_link_climb
idx_aurora_climb_stat_angle
idx_aurora_climb_listed
idx_climb_stat_browse
idx_climb_stat_by_popularity
idx_aurora_climb_frames_count
idx_climb_stat_count_cover
```

After rename:

```
idx_placements_set
idx_board_images_size
idx_beta_links_climb
idx_climb_stats_angle
idx_climbs_listed
idx_climb_stats_browse                  (was idx_climb_stat_browse — pluralize too)
idx_climb_stats_by_popularity           (was idx_climb_stat_by_popularity)
idx_climbs_frames_count
idx_climb_stats_count_cover             (was idx_climb_stat_count_cover)
```

(Note: a few indexes were ALREADY without the `aurora_` prefix; they get
pluralized to match the table-name pluralization.)

Indexes from secure DB (per existing `.sq` files, 2026-04-27):

```
idx_announcement_created          → idx_announcements_created
idx_ascent_climb / idx_ascent_date → idx_ascents_climb / idx_ascents_date
idx_bid_climb / idx_bid_date       → idx_bids_climb / idx_bids_date
idx_body_stat_date / idx_body_stat_name_date → idx_body_stats_*
idx_climb_list_entry_list / idx_climb_list_entry_climb → idx_climb_list_entries_*
idx_nostr_message_type / idx_nostr_message_created / idx_nostr_message_reply / idx_nostr_msg_type_dir → idx_nostr_messages_*
idx_payment_event_recipient / idx_payment_event_ref → idx_payment_events_*
```

## 3.5 Aurora-Mention Cleanup — Kotlin sources beyond `aurora_*` tables

Audit conducted 2026-04-27 against the full source tree. Findings split
into:

- **Aurora references that are still semantically correct** and stay
  (e.g. comments noting that the BLE GATT shape was originally Aurora's,
  it still applies)
- **Aurora references that have gone stale** (Aurora API died 2026-03-26;
  comments and class names that reference it lie about provenance)
- **One enum with mostly-dead fields** (`AuroraBoard.kt`)

### 3.5.1 BLE-class file renames

The Aurora-prefixed BLE classes are referenced by `Board*`-prefixed
domain code (`BoardBleConnection` would join `BoardConstants`,
`BoardRepository`, `BoardClimbParser`, `BoardDatabaseImporter`,
`BoardSyncManager`). Drop the legacy "Aurora" since CruxCoach is
Kilter-only at the product level, the protocol identity is preserved
in inline doc-comments.

| Today                       | Target                       |
| --------------------------- | ---------------------------- |
| `AuroraBleConnection.kt`    | `BoardBleConnection.kt`      |
| `AuroraBleScanner.kt`       | `BoardBleScanner.kt`         |
| `AuroraBleUuids.kt`         | `BoardBleUuids.kt`           |
| `AuroraPacketEncoder.kt`    | `BoardPacketEncoder.kt`      |
| `AuroraPacketEncoderTest.kt`| `BoardPacketEncoderTest.kt`  |

Class names + KDoc comments inside follow (`class AuroraBleConnection`
→ `class BoardBleConnection`, etc.). One short sentence per class
preserved in the KDoc explaining the BLE shape originated with Aurora's
ecosystem (Kilter / Tension / Decoy / Spire all speak it) — that's
useful provenance for anyone digging into the protocol later.

### 3.5.2 `AuroraBoard.kt` enum — drop dead fields, rename class

Audit (2026-04-27): of 6 fields, only 2 are actually referenced.

| Field          | References (non-self)              | Action |
| -------------- | ---------------------------------- | ------ |
| `productId`    | 1 (`BoardRepositoryImpl:216`)      | KEEP   |
| `appPackage`   | 2 (`ApkDownloader:57, :135`)       | KEEP   |
| `apiUrl`       | 0 — value `https://kilterboardapp.com` is a dead Aurora URL | DROP   |
| `imageUrl`     | 0 — value `https://api.kilterboardapp.com` was Aurora's image CDN | DROP |
| `hostBase`     | 0                                  | DROP   |
| `displayName`  | 0                                  | DROP   |

Action:
- Drop the 4 unused fields
- Rename `AuroraBoard` → `SupportedBoard` (file + class)
- Update both call-sites (`BoardRepositoryImpl`, `ApkDownloader`)

### 3.5.3 Pattern-constant renames in `BoardClimbParser` / `FramesBinaryCodec`

These two files parse **two distinct frame formats** (delta-encoded vs
range-encoded) and use the constants `AURORA_PATTERN` and
`KILTER_PATTERN` to discriminate. Both labels are misleading —
"Aurora" is dead-API-flavored, "Kilter" collides with the broader
`Kilter*` API-client code. Rename to format-semantic names:

| Today              | Target            | Format meaning                                   |
| ------------------ | ----------------- | ------------------------------------------------ |
| `AURORA_PATTERN`   | `DELTA_PATTERN`   | `p{placementId}r{roleId}` — Aurora-era / Blossom DB |
| `KILTER_PATTERN`   | `RANGE_PATTERN`   | `h{holdPlacementId}p{ptid}[s{n}][e{n}]` — Kilter REST `climbConcat` |

Rename also reaches inline comments using "Aurora frame format" /
"Kilter frame format" — replace with "delta-format frames" /
"range-format frames" respectively.

Affected files:
- `shared/.../BoardClimbParser.kt` (lines 9, 44, 61, 66, 76, 92, 131)
- `shared/.../FramesBinaryCodec.kt` (lines 4, 25–27, 65)
- `shared/.../HoldHeatmapComputer.kt` (line 50)

### 3.5.4 Comment-only rewrites (Kilter explicit where it's Kilter-specific)

Single-line touch-ups, one occurrence each:

| File                                    | Today                                                          | Target                                                      |
| --------------------------------------- | -------------------------------------------------------------- | ----------------------------------------------------------- |
| `Rgb332Palette.kt:9`                    | "color palette for Aurora Climbing board LEDs"                | "color palette for Kilter Board LEDs"                       |
| `BoardEasterAnimations.kt:11`           | "LED animation patterns for Aurora Climbing boards"            | "LED animation patterns for Kilter Boards"                  |
| `KilterGradeMapper.kt:6, 94`            | "Maps Aurora/Kilter difficulty integers" / "to Aurora difficulty value" | "Maps Kilter difficulty integers (10–34)" / "to Kilter difficulty value" |
| `Color.kt:36`                           | "Converts Aurora difficulty_average → V-Grade number"          | "Converts Kilter difficulty_average → V-Grade number"       |
| `GradeDisplayHelper.kt:55, 76`          | "Aurora difficulty_average" / "Aurora difficulty"              | "Kilter difficulty_average" / "Kilter difficulty"           |
| `BoardStatsComputer.kt:38`              | "Grade band thresholds (Aurora difficulty values)"             | "Grade band thresholds (Kilter difficulty values)"          |
| `CruxCoachBackup.kt:44`                 | "what Aurora/Kilter stores for climb_uuid"                     | "what Kilter stores for climb_uuid"                         |
| `CruxCoachBackupValidationTest.kt:84`   | "Kilter/Aurora climb_uuid format"                              | "Kilter climb_uuid format"                                  |
| `BoardConstants.kt:5`                   | "Aurora API product_id for Kilter Board"                       | "Product ID 1 = Kilter Board Original (Aurora-era numbering, preserved in Blossom)" |
| `BoardDatabaseImporter.kt:18, 383`      | "raw Aurora schema (climbs, climb_stats, …)"                   | "Kilter board schema (Aurora-style: climbs, climb_stats, placements)" |
| `ApkDownloader.kt:17, 122, 125`         | "Aurora board APKs (via APKPure)"                              | "Kilter Board legacy APKs (via APKPure)"                    |

### 3.5.5 Aurora references that **stay** (semantically load-bearing)

Not touched by this cleanup:

- KDoc inside the renamed BLE classes saying "BLE GATT shape originally
  introduced by Aurora Climbing's board ecosystem" (one sentence, useful
  provenance hint for protocol RE)
- "Aurora-style" as an adjective for the delta-format wire shape, where
  it disambiguates from "Kilter-API-style" range format (kept in
  comments and CONTRIBUTING after rename)
- `com.auroraclimbing.kilterboard` package literal in `ApkDownloader`
  (that's the actual APKPure package ID; renaming would break the
  download URL)

### 3.5.6 User-facing strings

Two strings imply Tension support that does not exist:

| File                                  | Today                                                                                | Target                                                              |
| ------------------------------------- | ------------------------------------------------------------------------------------ | ------------------------------------------------------------------- |
| `values/strings.xml:395`              | "Sync the Kilter/Tension Board database to search climbs."                          | "Sync the Kilter Board database to search climbs."                  |
| `values/strings.xml:619`              | "Sync the Kilter/Tension board database to search climbs and display them on the board." | "Sync the Kilter Board database to search climbs and display them on the board." |
| `values-de/strings.xml:395`           | "Synchronisiere die Kilter/Tension Board-Datenbank, um Climbs zu suchen."           | "Synchronisiere die Kilter Board-Datenbank, um Climbs zu suchen."   |
| `values-de/strings.xml:662`           | "Synchronisiere die Kilter/Tension Board-Datenbank, um Climbs zu suchen und auf dem Board anzuzeigen." | "Synchronisiere die Kilter Board-Datenbank, um Climbs zu suchen und auf dem Board anzuzeigen." |

`Equipment.TENSION_BOARD` enum value in `Enums.kt` stays — that's a
workout-equipment tag for training-plan UI (independent of which
climbing-board CruxCoach connects to).

## 4. Scope: Columns

### 4.1 Drops — none in 0.1.4

The only column verified as functionally dead during the 2026-04-27 audit
was `aurora_climb.is_deleted`. After weighing the SQLite 3.35 minSdk
constraint (see §5.4), the decision is: **no column drops in 0.1.4**.

| Column                    | Audit verdict                                                                | Decision                |
| ------------------------- | ---------------------------------------------------------------------------- | ----------------------- |
| `aurora_climb.is_deleted` | Functionally dead — not in Blossom schema, cron always writes 0, UI ignores | **KEEP** — §5.4 deferred to 0.2.0+ |

§5.2 migration is therefore pure RENAME, no table-rebuild dance needed.

### 4.2 Originally proposed drops, **REJECTED** after data audit

The schema-diff doc (`~/kilter-re/analysis/SCHEMA_DIFF_CRUXCOACH_VS_KILTER.md`)
flagged five columns as redundant. Four of them are NOT redundant — the
audit was based on the Kilter-API-merge output only, missing that the
ORIGINAL Aurora-extracted bundled DB still carries real values:

| Column                              | Schema-diff claim   | Verified reality (Blossom DB 2026-04-27)                              |
| ----------------------------------- | ------------------- | ---------------------------------------------------------------------- |
| `aurora_climb.is_nomatch`           | "always 0"          | 44,520 of 174,345 climbs (25%) carry value 1                           |
| `aurora_climb.hsm`                  | "always 0"          | 4 distinct values (0/1/2/3), real distribution                          |
| `climb_stat.display_difficulty`     | "redundant"         | 284,779/290,888 populated (98%), 14,184 distinct values                |
| `climb_stat.benchmark_difficulty`   | "redundant"         | 866 rows (0.3%), independent benchmark flag, real per-(climb,angle)    |

These columns hold real Aurora-era data preserved through `build_board_db.py`
into Blossom and back into CruxCoach via the cron. **Keep all four.**

The audit correction will be recorded in `~/kilter-re/analysis/SCHEMA_DIFF_CRUXCOACH_VS_KILTER.md`
(private RE workspace, the source-of-truth for that doc). It is NOT
mirrored into CruxCoach docs — see §13 Q-E.

### 4.3 Out of scope: column-name renames

`frames_count` (Aurora plural) vs Kilter API `frameCount` (singular) is
known but deferred. Cost/benefit: column rename means migration `ALTER
TABLE ... RENAME COLUMN` (only since SQLite 3.25) plus updating every
SQLDelight query plus auditing for hardcoded column-name strings, all to
fix exactly one cosmetic mismatch with the wire-format JSON. The Cron's
field-mapping line `"frame_count": climb["frameCount"]` already does the
camelCase translation. Not worth doing in 0.1.4.

### 4.4 Out of scope: column adds

Schema-Diff doc §1.1 and §2.3 list `setter_uuid` (Kilter delivers,
CruxCoach drops), `updated_at` (Kilter delivers, CruxCoach drops),
populating `official_kilter_difficulty` from API (CruxCoach has the
column NULL). Each is a separate, additive change with its own decision
matrix. Out of scope for 0.1.4 schema rename.

## 5. Migration Mechanics

### 5.1 SQLDelight setup recap

CruxCoach uses SQLDelight 2.x. Each DB module has:
- `.sq` files containing schema + queries
- `migrations/<n>.sqm` files containing forward migration SQL
- `databases/<n>.db` snapshots for `verify-migrations` Gradle task

Database version is bumped per migration. SQLDelight runs migrations in
order on first open of an older DB.

### 5.2 Migration .sqm structure

For each renamed table, in the appropriate migration file:

```sql
-- shared/src/commonMain/sqldelight/board/migrations/<N>.sqm

-- 1. Tables (rename preserves all rows + indexes track automatically)
ALTER TABLE aurora_climb RENAME TO climbs;
ALTER TABLE aurora_climb_stat RENAME TO climb_stats;
ALTER TABLE aurora_placement RENAME TO placements;
ALTER TABLE aurora_hole RENAME TO holes;
ALTER TABLE aurora_led RENAME TO leds;
ALTER TABLE aurora_product_size RENAME TO product_sizes;
ALTER TABLE aurora_board_image RENAME TO board_images;
ALTER TABLE aurora_beta_link RENAME TO beta_links;
ALTER TABLE aurora_sync_state RENAME TO sync_states;
ALTER TABLE board_hold_position RENAME TO board_hold_positions;

-- 2. Indexes — drop old, recreate with new names
DROP INDEX IF EXISTS idx_aurora_placement_set;
CREATE INDEX idx_placements_set ON placements(set_id);

DROP INDEX IF EXISTS idx_aurora_board_image_size;
CREATE INDEX idx_board_images_size ON board_images(product_size_id);

-- ... (one per index, see §3.4 for the full list)
```

For secure DB (separate migration file under `secure/migrations/`):

```sql
-- shared/src/commonMain/sqldelight/secure/migrations/<N>.sqm

ALTER TABLE aurora_ascent RENAME TO ascents;
ALTER TABLE aurora_bid RENAME TO bids;
ALTER TABLE Announcement RENAME TO announcements;
-- ... (full list per §3.2)

-- Pluralize already-snake-case-singular
ALTER TABLE board_session RENAME TO board_sessions;
ALTER TABLE climb_list RENAME TO climb_lists;
ALTER TABLE climb_list_entry RENAME TO climb_list_entries;

-- Indexes per §3.4
DROP INDEX IF EXISTS idx_announcement_created;
CREATE INDEX idx_announcements_created ON announcements(created_at);
-- ...
```

### 5.3 SQLite version constraints

Android `minSdk = 26` (Android 8.0). SQLite versions on Android:

| Android | Approx SQLite |
| ------- | ------------- |
| API 26  | 3.18          |
| API 30  | 3.31          |
| API 33  | 3.32          |
| API 34  | 3.39          |

**`ALTER TABLE ... RENAME TO`** — supported on all versions we target. Safe.

**`DROP INDEX` + `CREATE INDEX`** — supported everywhere. Safe.

**`ALTER TABLE ... RENAME COLUMN`** — needs SQLite 3.25 (API 30+). We don't
do any column rename in this spec (per §4.3), so not relevant.

**`ALTER TABLE ... DROP COLUMN`** — needs SQLite 3.35 (API 34+ only).
Most users on 0.1.4 will be on API < 34. This affects only `is_deleted`
on `climbs` (§4.1). See §5.4.

### 5.4 `is_deleted` drop strategy — DECIDED: leave in place

**Decision (2026-04-27):** keep the `is_deleted` column on `climbs`. Do
not drop it in 0.1.4.

Rationale:
- The column has zero functional impact — nothing reads it, the cron
  writes 0 unconditionally.
- Carrying it costs ~4 bytes per row × ~174k rows ≈ 680 KB per
  user-device-DB. Trivial against a 38-MiB-gzipped catalog.
- minSdk = 26 → SQLite 3.18 → no `ALTER TABLE DROP COLUMN` (which
  requires SQLite 3.35 / API 34+). The portable 12-step rebuild
  (CREATE NEW + INSERT-SELECT + DROP OLD + RENAME) would add 50+ lines
  of migration SQL with new failure modes (constraint violations,
  concurrent transaction edge cases) for one cosmetic debt column.
- A future 0.2.0 spec can pick this up cheaply IF we either bump minSdk
  to 30+ OR we're already running the rebuild dance for a column rename
  (`frames_count` → `frame_count` etc.). Until then the column stays.

§4.1 status updated: column drop **deferred to 0.2.0+**, not part of
this spec. The §5.2 migration is therefore pure RENAME — no rebuild
required.

### 5.5 SQLCipher consideration

The secure DB is opened via SQLCipher. `ALTER TABLE` works identically
on SQLCipher — the encryption is at the page level, transparent to SQL.
Only operational gotcha: the migration must run inside the same opened
DB session that has the key set, which `BoardDatabase.android.kt` /
`SecureDatabase.android.kt` already handle via SQLDelight's standard
migration callback.

### 5.6 Migration version bump

Two SQLDelight migrations are needed (one per DB module). After the bump:

- Board DB: version N → N+1, migration `<N+1>.sqm` adds rename SQL
- Secure DB: version M → M+1, migration `<M+1>.sqm` adds rename SQL

Implementation step: read current versions from the SQLDelight Gradle
config; this spec doesn't pin them because they may have shifted in
intervening 0.1.4 work (e.g. FEAT-003 may also need a migration).

## 6. Code Refactor Scope

### 6.1 SQLDelight `.sq` files

The CRUD/query files contain `FROM aurora_climb`, `INSERT INTO aurora_*`
etc. throughout. SQLDelight regenerates Kotlin bindings on every build,
so a successful build gives high confidence that all references are
covered.

Approach: full find-replace within `.sq` files, then
`./gradlew generateSqlDelightInterface` to surface any remaining
references. Compiler errors will pinpoint missed Kotlin call sites.

For `AuroraBoard.sq` (currently 130+ lines, multiple tables): split into
focused files per Table-cluster:
- `Climbs.sq` (climbs + climb_stats + indexes)
- `Layout.sq` (placements, holes, leds, board_hold_positions, product_sizes,
  board_images)
- `BetaLinks.sq` (beta_links)
- `SyncStates.sq` (sync_states)

Optional but improves discoverability. Keep `AuroraBoard.sq` as a single
file if the split adds churn we don't want; rename its content but not
the filename.

### 6.2 Kotlin code

Per `git grep -l "aurora_climb\|aurora_placement\|aurora_hole\|aurora_led\|aurora_product_size\|aurora_board_image\|aurora_beta_link\|aurora_sync_state\|aurora_ascent\|aurora_bid"` (run 2026-04-27): **9 files** in
`shared/` + `androidApp/` reference these table names directly outside
the SQLDelight-generated layer.

Audit pattern at PR time:

```bash
# 1. Hardcoded SQL strings in Kotlin (should be ZERO after refactor)
git grep -nE 'aurora_(climb|climb_stat|placement|hole|led|product_size|board_image|beta_link|sync_state|ascent|bid)' \
  -- '*.kt' '*.kts' '*.gradle' '*.pro' \
  | grep -v 'sqldelight/'   # generated files exempt

# 2. Hardcoded references to PascalCase table names in raw SQL
git grep -nE 'FROM (Announcement|Assessment|BodyStat|ClimbLog|NostrMessage|NostrProfile|PaymentEvent|TrainingPlan|TrainingSession|UserProfile|WorkoutLog)\b' \
  -- '*.kt'

# 3. Renamed BLE classes — old names should disappear from imports/usages
git grep -nE 'AuroraBle(Connection|Scanner|Uuids)|AuroraPacketEncoder' \
  -- '*.kt' '*.kts'

# 4. Renamed AuroraBoard enum + its dropped fields
git grep -nE 'AuroraBoard\b|\.apiUrl\b|\.imageUrl\b|\.hostBase\b|\.displayName\b' \
  -- '*.kt' '*.kts' \
  | grep -v 'AuroraBoard\.kt'   # remove if file already gone

# 5. Renamed pattern constants
git grep -nE 'AURORA_PATTERN|KILTER_PATTERN' -- '*.kt'
```

All five must produce zero hits before the refactor PR is merged.

### 6.2.1 Aurora-mention sweep (comments + KDoc)

Comments listed in §3.5.4 are not caught by the SQL/identifier audits
above. Manual pass + sentinel grep:

```bash
# Aurora-mention sweep — all hits get a manual review pass
git grep -nE 'Aurora\b|aurora\b' \
  -- '*.kt' '*.xml' \
  | grep -vE '(/build/|sqldelight-generated/|com\.auroraclimbing\.kilterboard|"Aurora-style"|BLE GATT shape originally)'
```

Expected non-zero hits after refactor: only the kept references in
§3.5.5 (BLE provenance KDoc, "Aurora-style" disambiguator, the legacy
APKPure package literal).

### 6.3 Test fixtures

`androidApp/src/test/java/com/cruxcoach/android/fakes/FakeBoardRepository.kt`
and similar fakes: rename method-internal SQL strings (if any). The
fakes implement the repository interface — interface signatures don't
change since column names are preserved. Mostly mechanical.

### 6.4 Backups + import paths

Per the analysis posted to FEAT-006-discussion thread 2026-04-27:

- `CruxCoachBackup.kt` — wire format on `@Serializable` data classes,
  not on SQL columns. **No change.**
- `WaistlineExchange.kt` — body-stats Waistline-app-compat format,
  pure repository-mediated. **No change.**
- FEAT-005 Aurora JSON Import — Aurora's own snake_case wire format,
  mapper layer. **No change.**
- `BoardDatabaseImporter.kt` — reads from Blossom DB chunks (table names
  fixed, server side: `climbs`, `placements`, …) and writes via
  generated SQLDelight bindings. After rename: SQLDelight bindings now
  emit SQL into `climbs` (target) instead of `aurora_climb`. Source-side
  unchanged. **Importer code: trivial diff** — only the local-side
  bindings change name.

### 6.5 Documentation files

Update:
- `CONTRIBUTING.md` — naming-convention section (add R1–R4 from §2)
- Existing 0.1.4 specs (`FEAT-003`, `FEAT-005`) — update §9.1 and §6
  references that still use `aurora_*` names
- README / wiki: low-priority, naming is internal

`~/kilter-re/analysis/SCHEMA_DIFF_CRUXCOACH_VS_KILTER.md` lives in the
private RE workspace, not in the CruxCoach repo. The §4.2 audit
correction is recorded **only** in the kilter-re doc — not surfaced
back into CruxCoach (per Q-E decision in §13).

## 7. Backup, Import, External Compatibility

### 7.1 Cloud Backup (FEAT-002)

`CruxCoachBackup.Backup` is a `@Serializable` data class with camelCase
Kotlin field names (`boardAscents`, `climbUuid`, `bidCount`). The wire
format is independent of SQL table or column names.

A 0.1.3 backup envelope (`version: Int = 2`) deserializes byte-identically
on 0.1.4. Restore calls `repository.upsertAscent(...)` — the repository
abstracts the table name, so the rename is invisible to the restore
code path.

**Verification test (required at PR time):** seed a 0.1.3 export JSON
fixture (committed in `androidApp/src/test/resources/`), invoke
`CruxCoachBackup.import()` on 0.1.4, assert all sample rows present
in the renamed tables.

### 7.2 Manual JSON Export (existing in 0.1.x)

Same code path as Cloud Backup — `CruxCoachBackup.export()` /
`CruxCoachBackup.import()`. Same verification test covers both.

### 7.3 Aurora JSON Import (FEAT-005, planned for 0.1.4)

Aurora wire format is fixed (the snake_case shape that Aurora's
data-export emails contain). FEAT-005's importer parses Aurora JSON
into Aurora-mirroring Kotlin classes, runs a domain mapper to CruxCoach
domain objects, and calls repositories. None of these layers touch SQL
table names directly. **Zero impact** from the rename.

### 7.4 External SQLite imports

`BoardDatabaseImporter` reads from Blossom DB chunks (Aurora-extracted
schema, table names: `climbs`, `placements`, `holes`, `leds`,
`product_sizes`, `climb_stats`, …) and writes into the local CruxCoach
DB.

After rename, the local DB's table names *match* the source plural form
exactly. The importer's per-row mapping logic loses one translation
step (`aurora_climb` ← `climbs` becomes a no-op). Net: simpler, not
harder.

### 7.5 Nostr events (FEAT-002 + FEAT-003)

Nostr Kind 30078 events use tags + content shapes defined per spec
(see FEAT-002 §4 and FEAT-003 §4). Events do not embed local SQL
schema. Rename irrelevant.

### 7.6 boardlib / external tools

No external tooling reads on-device CruxCoach DB files (verified with
maintainer 2026-04-27). The Blossom DB schema is unchanged, so any
boardlib-style scripts targeting the Blossom mirror are unaffected.

## 8. Test Plan

### 8.1 Migration unit test (REQUIRED)

`shared/src/commonTest/kotlin/com/cruxcoach/db/board/SchemaRenameMigrationTest.kt`:

1. Create an in-memory SQLite DB at the **pre-rename version**
2. Seed each Aurora-named table with at least 3 sample rows of varying
   content (multi-frame climb, single-frame boulder, climb_stat with
   benchmark, climb_stat without)
3. Run the SQLDelight migration to the post-rename version
4. Assert: all sample rows present in the renamed tables, with all
   column values intact (use checksums or row-count + spot-checks)
5. Assert: SELECT against the OLD names raises a SQLite error
   ("no such table: aurora_climb")

Same test pattern for the secure DB module.

### 8.2 Backup round-trip test (REQUIRED)

`androidApp/src/test/java/com/cruxcoach/android/data/BackupRenameRoundTripTest.kt`:

1. Take a real-shape 0.1.3 export JSON (committed under
   `src/test/resources/backup_v2_sample.json`, ~50 ascents + 20 bids +
   profile + body stats + climb lists)
2. Run `CruxCoachBackup.import()` on a fresh 0.1.4 schema DB
3. Assert: all repository methods (`getUserAscentsAll`, `getRawBidsForUser`,
   `getAllBoardSessions`, `getAllClimbLists`, `BodyStatRepository.getAll`)
   return the seeded data with correct row counts + spot-checked field
   values

### 8.3 Manual export round-trip test (REQUIRED)

Same as §8.2 but: import → export → re-import → diff JSON. Round-trip
must be idempotent modulo the `exportedAt` timestamp.

### 8.4 BoardDatabaseImporter end-to-end test (RECOMMENDED)

`androidApp/src/test/java/com/cruxcoach/android/data/BoardDatabaseImporterRenameTest.kt`:

1. Synthesize a tiny Blossom DB (5 climbs, 10 climb_stats, 20 placements,
   ...) with current Blossom-side schema
2. Run `BoardDatabaseImporter.importFromLocalDb()` against it
3. Assert: `BoardRepository.getClimbCount()` returns 5,
   `BoardRepository.getClimbByUuid("…")` returns the seeded row

### 8.5 Real-device smoke (REQUIRED before merge to dev)

On a v0.1.3 device with real user data (ascents, bids, body stats,
training plan):

1. Side-load the rename APK (built from feat/0.1.4-release after the
   refactor PR)
2. Open app: should not crash
3. Browse climbs: list populated, multi-frame routes still play
4. Open Logbook: ascents + bids visible with correct details
5. Open Body Stats: history present
6. Trigger Cloud Backup: should succeed
7. Trigger Manual Export: JSON should download, spot-check fields
8. (Optional) Sideload 0.1.3 again, verify Cloud Restore from the just-made
   backup works

### 8.6 Performance sanity (RECOMMENDED)

Run `androidApp/src/androidTest/.../BoardBrowseBenchmark.kt` (if exists)
or do a manual timed browse:
- "Browse all climbs" should be within ±10% of pre-rename time
- "Filter by grade" should be within ±10%
- "Sort by quality" should be within ±10%

The rename should be performance-neutral, but verifying catches index
mistakes.

## 9. Rollback / Disaster Recovery

SQLDelight migrations are forward-only. There is no automated rollback.

### 9.1 If migration fails on a user device

Failure modes:
- (a) SQL syntax error → caught by `verify-migrations` Gradle task
  before release. Should be impossible in production.
- (b) Constraint violation during data move → caught by §8.1 test +
  real-device smoke
- (c) Mid-migration crash (power loss / OOM) → SQLite transaction
  rollback. DB returns to pre-migration state. App will retry on next
  launch.

App behavior on persistent migration failure: crash with diagnostic
(do not silently fall back to old schema). User can:
- Side-load 0.1.3 APK from Codeberg releases (data file untouched if
  migration rolled back)
- Restore from Cloud Backup (FEAT-002) which is independent of local
  schema state

### 9.2 No `.bak` mechanism

A "copy DB to .bak before migrate" mechanism was considered and rejected:
- Adds storage cost (effective DB size doubles during migration)
- Adds code path that itself has failure modes
- Adds ambiguity: "which is the live DB?" if interrupt
- SQLite transaction rollback already covers the failure mode

If a future migration genuinely warrants this (e.g., a refactor that
involves re-encoding column data), spec it then.

### 9.3 Forward-only is acceptable here

Since the rename is a pure `ALTER TABLE … RENAME TO` chain (no data
transformation, no column drops, no rebuild — see §5.4 decision), the
migration is straightforward and unlikely to fail. The strict
forward-only stance is acceptable.

## 10. PR Sequence

Per memory `feedback_release_branches_long_lived` (feat/X.Y.Z-release
branches are long-lived) and `feedback_squash_merges` (always squash-merge
dev → main):

1. **Create branch** `feat/0.1.4-release` from current `dev` (post-0.1.3-merge).
2. **PR 1: Schema Rename** (this spec). Lands on `feat/0.1.4-release`,
   then merged into `dev` after CI green and the §8 tests pass locally.
3. **Soak period: ~3 days** on Test-Device. During this window, no
   other DB-touching PRs to `dev`. Only Aurora-rename work allowed.
4. **PR 2: FEAT-003 Climb Creator.** Now starts from a clean dev with
   the new schema in place. New tables (`community_grade_cache`,
   `publish_queue`) and new columns on `climbs` (`source`, `nostr_event_id`,
   `nostr_d_tag`, `created_by_pubkey`, `frames_hash`, `sync_status`)
   land in the Zielform from day one — no transitional naming.
5. **PR 3: FEAT-005 Aurora JSON Import.** Builds on FEAT-003 storage
   layer.
6. **PR 4: any other 0.1.4 polish.**
7. **Eventual squash-merge** `dev` → `main` for 0.1.4 release.

Soak rationale: the §8 tests cover known cases, but real-device usage
exercises shape variations the test seed data may not. Three days
catches the long tail.

## 11. Risk Register

| ID  | Risk                                                                                  | Severity | Likelihood | Mitigation                                                       |
| --- | ------------------------------------------------------------------------------------- | -------- | ---------- | ---------------------------------------------------------------- |
| R1  | Migration silently corrupts data on user device                                       | HIGH     | LOW        | §8.1 unit test + §8.5 real-device smoke + SQLite transaction safety |
| R2  | Hidden raw SQL string in Kotlin survives find-replace                                 | MED      | MED        | §6.2 grep audit gates the PR (zero hits required)                |
| R3  | Backup format inadvertently leaks SQL → 0.1.3 backups break on 0.1.4                  | MED      | NEAR-ZERO  | §7.1 verified — wire format is repository-mediated. §8.2 round-trip test confirms |
| R4  | minSdk 26 lacks `DROP COLUMN` → if a column drop is added later, would fail            | n/a      | n/a        | §5.4: no column drops in 0.1.4. R4 is moot for this spec, kept here as a marker for future schema-evolution specs |
| R5  | Concurrent dev merges during soak break the rename's consistency                      | LOW      | LOW        | §10 sequencing — dev locked to rename-only during soak           |
| R6  | External tool reads on-device DB                                                      | NONE     | n/a        | Verified maintainer-only access (§7.6)                           |
| R7  | Some `.sq` query gets the renamed-table name wrong post-refactor                      | LOW      | LOW        | SQLDelight build fails fast on undefined-table reference         |
| R8  | Index-rename order causes momentary "no index" (slow query) during migration          | NONE     | n/a        | Migration runs at app open; no concurrent queries possible       |
| R9  | A user on extremely old `0.0.x` skips multiple migrations and lands on 0.1.4 directly | LOW      | NEAR-ZERO  | SQLDelight runs all in-between migrations in order; if any prior migration was lossy, that's pre-existing tech debt, not a rename issue |
| R10 | A user backed up on 0.1.3, sideloaded 0.1.4, and Cloud Backup format is incompatible | MED      | NEAR-ZERO  | §8.2 explicit test                                               |

## 12. Out of Scope (deferred)

Tracked here so they're not forgotten:

- **Column-name renames** to better match Kilter API: `frames_count` →
  `frame_count`, `setter_username` → `username` (matches API), etc. → 0.2.0.
- **Adding `setter_uuid` column** to `climbs`. Kilter API delivers it,
  cron currently drops. Useful for setter-attribution + Push-to-Kilter
  later. → schema-additions spec (FEAT-XXX) when needed.
- **Adding `updated_at` column** to `climbs`. Enables incremental sync
  via `WHERE updated_at > X`. → same.
- **Populating `official_kilter_difficulty`** in the cron. Today: column
  exists, NULL for all rows. Schema-Diff §2.3 noted this — easy fix in
  `update_board_db.py`, no client-side change. → server-side cleanup.
- **Dropping `is_deleted`** properly via 12-step rebuild. → 0.2.0 if we
  bump minSdk to 30+, or never if we accept the cosmetic debt.
- **Consolidating `ascents` + `bids` → `climb_logs`** to mirror Kilter's
  `POST /api/logs/bulk` shape. → FEAT-XXX (climb-log unification), 0.3.0+
  along with Push-to-Kilter (FEAT-007 territory).
- **Adding tables for circuits, gyms, walls, hold_sets, beta_links**
  per Schema-Diff §4. → multi-feature roadmap.
- **Renaming Blossom DB schema** (server-side). Already plural
  unprefixed, no work needed; preserves backward-compat with all
  CruxCoach versions.

## 13. Decisions Recorded

All §13 questions resolved 2026-04-27. None remain open at spec-merge
time. Recorded for traceability:

- **Q-A — `BodyStat.sq` → `BodyStats.sq` filename rename:** YES
  (default accepted). One table per file; filename matches table.

- **Q-B — `sync_states.table_name` row content rewrite:** YES
  (default accepted). Migration includes
  `UPDATE sync_states SET table_name = '<new>' WHERE table_name = '<old>'`
  for each renamed table. No functional difference (next cron sync
  would overwrite anyway), but keeps semantics clean during the soak
  window.

- **Q-C — `BoardSession.sq` → `BoardSessions.sq` filename rename:** YES
  (default accepted). Same rationale as Q-A.

- **Q-D — One-table-per-file `.sq` convention:** Encouraged but NOT
  mandated. `AuroraBoard.sq` (board catalog, ~10 tables) renames to
  `Board.sq` and stays as a single bundled file in 0.1.4. Per-cluster
  splits (`Climbs.sq`, `Layout.sq`, `BetaLinks.sq`, `SyncStates.sq`)
  remain opt-in for a future cleanup.

- **Q-E — Schema-diff doc audit-correction (§4.2 finding) location:**
  ONLY in `~/kilter-re/analysis/SCHEMA_DIFF_CRUXCOACH_VS_KILTER.md`.
  No CruxCoach-repo entry. The kilter-re workspace is the
  reverse-engineering source-of-truth; the public CruxCoach repo
  doesn't surface RE artefacts.

- **§3.5 — Aurora-Mention scope:** Variant A (Board*-prefix) for BLE
  class renames, Option 1 (DELTA_PATTERN / RANGE_PATTERN) for
  format-pattern constants, "Kilter Board legacy APKs (via APKPure)"
  for ApkDownloader comments. Cat-3 references that are still
  load-bearing (BLE protocol provenance KDoc, "Aurora-style"
  disambiguator, APKPure package literal) stay — see §3.5.5.

- **§5.4 — `is_deleted` drop strategy:** Option B (leave column in
  place). No column drops in this rename. Deferred to 0.2.0+ if a
  minSdk bump or co-located column-rename ever justifies the rebuild
  dance. See §4.1 + §5.4 for rationale.

## 14. Implementation Checklist

Ordered for a clean PR:

**Phase 1 — Schema rename (§3.1–§3.4)**
- [x] Branch `feat/0.1.4-release` from `dev` (created 2026-04-27 at `ecb503d`)
- [ ] Add new SQLDelight migration files (one per DB module)
- [ ] Update `.sq` schema definitions (CREATE TABLE statements + index
  CREATEs use new names; rename files per §3.1, §3.2)
- [ ] Migration also runs `UPDATE sync_states SET table_name = '<new>'
  WHERE table_name = '<old>'` for each rename (Q-B)
- [ ] Run `./gradlew generateSqlDelightInterface` — fix all generated
  Kotlin call-site failures
- [ ] Update `.sq` query files (`SELECT FROM old`, `INSERT INTO old` →
  new names)
- [ ] Run `./gradlew generateSqlDelightInterface` again — should be
  green
- [ ] Run `./gradlew :shared:verifyMigrations` — must pass

**Phase 2 — Code-level Aurora cleanup (§3.5)**
- [ ] Rename BLE class files + classes (§3.5.1):
      `AuroraBleConnection` → `BoardBleConnection`,
      `AuroraBleScanner` → `BoardBleScanner`,
      `AuroraBleUuids` → `BoardBleUuids`,
      `AuroraPacketEncoder` → `BoardPacketEncoder` (+ test)
- [ ] Rename `AuroraBoard.kt` → `SupportedBoard.kt`; drop dead fields
      `apiUrl`, `imageUrl`, `hostBase`, `displayName` (§3.5.2)
- [ ] Update both call-sites: `BoardRepositoryImpl:216`,
      `ApkDownloader:57,135`
- [ ] Rename pattern constants in `BoardClimbParser` + `FramesBinaryCodec`:
      `AURORA_PATTERN` → `DELTA_PATTERN`, `KILTER_PATTERN` → `RANGE_PATTERN`
      (§3.5.3); also update inline format-name comments
- [ ] Apply comment-only rewrites per §3.5.4 (11 single-line touch-ups)
- [ ] Apply user-facing string fixes per §3.5.6 (4 strings, EN + DE)

**Phase 3 — Audit gates**
- [ ] Audit Kotlin code with §6.2 grep commands 1–5 — all must produce
      ZERO hits
- [ ] Aurora-mention sweep (§6.2.1) — only the 3 known load-bearing
      references (§3.5.5) should remain

**Phase 4 — Tests**
- [ ] Update test fakes (`FakeBoardRepository`, etc.)
- [ ] Add `SchemaRenameMigrationTest` (§8.1)
- [ ] Add `BackupRenameRoundTripTest` (§8.2)
- [ ] Add `ManualExportRoundTripTest` (§8.3)
- [ ] (Optional) Add `BoardDatabaseImporterRenameTest` (§8.4)
- [ ] Run full `./gradlew test` — all green

**Phase 5 — Smoke + Release**
- [ ] Build release APK (`./gradlew :androidApp:assembleRelease`)
- [ ] Side-load on Test-Device with real user data (§8.5)
- [ ] Walk through smoke checklist
- [ ] Update `CONTRIBUTING.md` naming-conventions section
- [ ] Open PR `feat/0.1.4-release` → `dev`
- [ ] Self-review the diff for any leaked `aurora_*` strings
- [ ] Merge after CI green
- [ ] 3-day soak; no other DB-touching PRs to `dev` during this window
- [ ] Communicate readiness to start FEAT-003 implementation
