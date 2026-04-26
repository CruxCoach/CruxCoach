# Feature Spec: Kilter-Native Board Database Schema (v0.1.4)

> **Status:** Draft — open design decisions in §10.
> **Depends on:**
> - FEAT-003 (Climb Creator) — reuses `placement_mapping.json` for `climbConcat` ↔ Aurora-frames conversion
> - kilter-re analysis (`~/kilter-re/analysis/FINDINGS.md`) — primary reference for the new API surface
> **Coordinates with:** `cruxcoach-blossom-sync` — server-side cron must emit DBs in the new schema in lockstep with this release.

## 1. Overview

CruxCoach's local board database currently mirrors the **old Aurora API schema** —
integer IDs everywhere, separate `aurora_climb_stat` table per (climb, angle)
pair, no gym/wall/grade-system metadata. Since Aurora's Kilter backend went down
on 2026-03-26, the daily Blossom-cron `update_board_db.py` pulls from the
**new Kilter API** (Keycloak + REST + PowerSync) and *converts* the response
back into the Aurora schema using a hardcoded 692-entry placement-ID mapping.

This bridge is functional but unsustainable:

- The 692-row `placement_mapping.json` is a manual mapping — every new placement
  Kilter adds breaks it until someone re-derives the file.
- Kilter-native fields (`gym_uuid`, `wall_uuid`, `product_layout_uuid`,
  `official_kilter_difficulty`, `circuit_uuid`, `hold_set_name`, `grade_system`,
  `is_draft`, `updated_at`) are silently dropped during conversion. The Blossom-
  distributed DB therefore loses information that the API actually delivers.
- Offline-first PowerSync semantics (per-user buckets, CRUD queue, `ps_*`
  metadata) are unreachable when forced through Aurora-style tables.
- Eventually Kilter will retire the old placement-ID space entirely; the
  conversion will fail wholesale at that point.

This spec migrates CruxCoach's **board** database to a Kilter-native schema,
aligned with what the new API actually delivers, and coordinates the
server-side `cruxcoach-blossom-sync` cron so the Blossom-distributed DB ships
in the new schema.

### Goals

- Adopt UUID-based identity for layouts, walls, gyms, holds, climbs.
- Flatten `aurora_climb_stat` into the climb row (per the new API shape).
- Add the tables Kilter actually populates: `gyms`, `walls`, `hold_sets`,
  `holds`, `grade_systems`, `difficulty_grades`, `hold_placements`,
  `mounting_holes`, `product_layouts`, `circuits`, `circuit_climbs`,
  `climb_beta_links`, `videos`.
- Store frames natively in `climbConcat` format (`h{holdPlacementId}p{typeRef}`),
  with a one-shot conversion of historic Aurora-format frames during migration.
- Replace hardcoded `KilterGradeMapper` with a DB-backed grade lookup.
- Provide a one-shot in-app migration path for existing 0.1.3 users that
  preserves all logbook references (no broken `climb_uuid` foreign keys).
- Coordinate with `cruxcoach-blossom-sync` so Phase 1 of this release ships
  a dual-schema DB (Aurora tables + Kilter-native tables) for back-compat,
  Phase 2 drops Aurora tables.

### Non-Goals

- **Personal-data DB changes** — `aurora_ascent` / `aurora_bid` keep their
  current schema for this release. Their `climb_uuid` references survive the
  migration unchanged. `frames` strings in those tables are upgraded in §6.4.
- **Kilter API authentication / token refresh** — covered by FEAT-030.
- **Climb publishing to Kilter API** — covered by FEAT-003.
- **Aurora JSON file import** — covered by FEAT-005.
- **User-data sync between apps (Boardsesh interop)** — separate discussion;
  this spec is purely a local-DB realignment.
- **PowerSync client integration** — out of scope. CruxCoach reads the
  Blossom-distributed snapshot, not the live PowerSync stream. The schema is
  PowerSync-shaped to make a future PowerSync client trivial, but no
  PowerSync runtime is added here.
- **Multi-board support** — CruxCoach is Kilter-only. The new schema has
  `product_name` columns but only Kilter products are populated.

---

## 2. Schema Comparison Summary

### 2.1 Tables (high-level)

| Aurora-style (current) | Kilter-native (target) | Migration |
|---|---|---|
| `aurora_climb` | `climbs` | Rename + denormalize stats; one row per (climb, angle) |
| `aurora_climb_stat` | (folded into `climbs`) | Drop after migration |
| `aurora_placement` (INT id) | `hold_placements` (INT id, UUID parent) | 692-row remap via FEAT-003 mapping |
| `aurora_hole` | `mounting_holes` | Add UUID; preserve `legacy_aurora_id` for transition |
| `aurora_led` | (folded into `hold_placements.led_position`) | Drop after migration |
| `aurora_product_size` | `products` + `product_layouts` | Split into 2 tables; UUIDs added |
| `aurora_board_image` | (folded into `product_layouts.image_path`) | Drop after migration |
| `aurora_beta_link` | `climb_beta_links` | Rename; UUID identity for video link |
| `aurora_sync_state` | (kept; schema-version bumped) | Keep |
| `board_hold_position` (view) | (kept; rebuilt over new tables) | Keep |

### 2.2 New Tables (no Aurora equivalent)

| Table | Source | Purpose |
|---|---|---|
| `gyms` | PowerSync `global_gyms[]` | Gym directory (location, branding, contact) |
| `walls` | PowerSync `global_gyms[].walls[]` | Wall instances within gyms |
| `hold_sets` | PowerSync `global[]` | Hold-set names (Full, Micro, Slopers …) |
| `holds` | PowerSync `global[]` | Individual holds, set membership |
| `grade_systems` | PowerSync `global[]` | V-Scale, Font, French, YDS |
| `difficulty_grades` | PowerSync `global[]` | Full grade-mapping rows; replaces hardcoded `KilterGradeMapper` |
| `circuits` | REST `/api/circuits` | Curated climb sequences |
| `circuit_climbs` | REST `/api/circuits` | Many-to-many circuit ↔ climb |
| `videos` | PowerSync `global[]` | Beta-video metadata |

### 2.3 Dropped Concepts

| Aurora concept | Why dropped |
|---|---|
| `aurora_climb_stat.benchmark_difficulty` | Kilter has `official_kilter_difficulty` instead (setter-assigned, not consensus) |
| `aurora_climb_stat.fa_username` / `fa_at` | Not exposed by new API; can be re-derived from logs if needed |
| `aurora_climb.is_nomatch` | Aurora-only flag, no equivalent |
| `aurora_climb.move_count` | Aurora-only; superseded by `frames_count` |
| `aurora_ascent.is_mirror` (logbook side, *not in this spec*) | Aurora-only; new API has `flashed`/`topped` instead. **Personal-data side, deferred to a later spec.** |
| `aurora_ascent.is_benchmark` (logbook side, *not in this spec*) | Aurora-only. **Deferred.** |

---

## 3. Target Schema (full DDL)

All new tables live in the same SQLDelight module as the current `AuroraBoard.sq`
(unencrypted board DB). `.sq` filename: `KilterBoard.sq`.

### 3.1 `climbs` (one row per climb-at-angle)

```sql
CREATE TABLE climbs (
    climb_uuid TEXT NOT NULL,
    angle INTEGER NOT NULL,
    product_name TEXT NOT NULL,
    product_layout_uuid TEXT NOT NULL,
    setter_uuid TEXT,
    setter_username TEXT,
    name TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    frames TEXT NOT NULL,                  -- climbConcat format: h{holdPlacementId}p{typeRef}
    frames_count INTEGER NOT NULL DEFAULT 1,
    frames_pace INTEGER NOT NULL DEFAULT 0,
    hsm INTEGER NOT NULL DEFAULT 0,
    edge_left INTEGER, edge_right INTEGER,
    edge_bottom INTEGER, edge_top INTEGER,
    is_listed INTEGER NOT NULL DEFAULT 1,
    is_draft INTEGER NOT NULL DEFAULT 0,
    is_deleted INTEGER NOT NULL DEFAULT 0,
    quality_average REAL,
    difficulty_average REAL,
    ascensionist_count INTEGER,
    official_kilter_difficulty INTEGER,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (climb_uuid, angle),
    FOREIGN KEY (product_layout_uuid) REFERENCES product_layouts(product_layout_uuid)
);

CREATE INDEX climb_layout_angle ON climbs(product_layout_uuid, angle);
CREATE INDEX climb_setter ON climbs(setter_uuid);
CREATE INDEX climb_name_search ON climbs(name);
```

**Note on PK shape.** The new API returns one record per (climb, angle), and the
same `climb_uuid` legitimately appears at multiple angles with different
difficulty/quality. Composite primary key is the natural fit. For lookup by
`climb_uuid` alone (e.g., logbook joins) an index on `climb_uuid` is implicit
in the composite PK. See OPEN DECISION §10.1.

### 3.2 `hold_placements`

```sql
CREATE TABLE hold_placements (
    hold_placement_id INTEGER PRIMARY KEY,
    product_layout_uuid TEXT NOT NULL,
    mounting_hole_uuid TEXT NOT NULL,
    hold_id INTEGER NOT NULL,
    hold_set_name TEXT NOT NULL,
    default_placement_type INTEGER,
    hold_rotation INTEGER NOT NULL DEFAULT 0,
    led_position INTEGER,
    FOREIGN KEY (product_layout_uuid) REFERENCES product_layouts(product_layout_uuid),
    FOREIGN KEY (mounting_hole_uuid) REFERENCES mounting_holes(mounting_hole_uuid)
);

CREATE INDEX hp_layout ON hold_placements(product_layout_uuid);
CREATE INDEX hp_mounting_hole ON hold_placements(mounting_hole_uuid);
```

`led_position` lives on `hold_placements` directly — the old `aurora_led` table
is gone. Migration translates `aurora_led.hole_id → mounting_holes.mounting_hole_uuid → led_position`.

### 3.3 `mounting_holes`

```sql
CREATE TABLE mounting_holes (
    mounting_hole_uuid TEXT PRIMARY KEY,
    legacy_aurora_id INTEGER,             -- preserves aurora_hole.id for back-compat lookups
    product_name TEXT NOT NULL,
    x INTEGER NOT NULL,
    y INTEGER NOT NULL,
    mirrored_hole_uuid TEXT
);

CREATE UNIQUE INDEX mh_legacy ON mounting_holes(legacy_aurora_id) WHERE legacy_aurora_id IS NOT NULL;
```

`legacy_aurora_id` is **transitional**. It carries the old `aurora_hole.id`
forward so we can resolve old `aurora_ascent.frames` strings during the
one-shot Phase 2 frame-conversion. Drop in 0.1.6 (see §10.5).

### 3.4 `products` + `product_layouts`

```sql
CREATE TABLE products (
    product_name TEXT PRIMARY KEY,
    label TEXT,
    instagram_caption TEXT
);

CREATE TABLE product_layouts (
    product_layout_uuid TEXT PRIMARY KEY,
    product_name TEXT NOT NULL,
    edge_left INTEGER, edge_right INTEGER,
    edge_bottom INTEGER, edge_top INTEGER,
    height INTEGER, width INTEGER,
    image_height INTEGER, image_width INTEGER,
    image_path TEXT,
    accumulated_hold_set_value INTEGER,
    is_listed INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    FOREIGN KEY (product_name) REFERENCES products(product_name)
);

CREATE INDEX pl_product ON product_layouts(product_name);
```

### 3.5 `gyms` + `walls`

```sql
CREATE TABLE gyms (
    gym_uuid TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    company_vat_number TEXT,
    latitude REAL, longitude REAL,
    is_listed INTEGER NOT NULL DEFAULT 1,
    gym_logo TEXT,
    banner_image TEXT,
    city TEXT, country TEXT, address TEXT, postal_code TEXT,
    phone_number TEXT, email TEXT,
    homepage_url TEXT,
    instagram_username TEXT
);

CREATE TABLE walls (
    wall_uuid TEXT PRIMARY KEY,
    gym_uuid TEXT NOT NULL,
    product_name TEXT NOT NULL,
    product_layout_uuid TEXT NOT NULL,
    name TEXT NOT NULL,
    is_adjustable INTEGER NOT NULL DEFAULT 0,
    min_angle INTEGER, max_angle INTEGER,
    angle_increments INTEGER,
    angle INTEGER,
    serial_number TEXT,
    accumulated_hold_set_value INTEGER,
    is_listed INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    FOREIGN KEY (gym_uuid) REFERENCES gyms(gym_uuid),
    FOREIGN KEY (product_layout_uuid) REFERENCES product_layouts(product_layout_uuid)
);

CREATE INDEX wall_gym ON walls(gym_uuid);
CREATE INDEX wall_layout ON walls(product_layout_uuid);
CREATE INDEX gym_geo ON gyms(latitude, longitude) WHERE is_listed = 1;
```

### 3.6 `hold_sets` + `holds`

```sql
CREATE TABLE hold_sets (
    hold_set_name TEXT PRIMARY KEY,
    hold_set_value INTEGER
);

CREATE TABLE holds (
    hold_id INTEGER PRIMARY KEY,
    hold_set_name TEXT NOT NULL,
    FOREIGN KEY (hold_set_name) REFERENCES hold_sets(hold_set_name)
);
```

### 3.7 `grade_systems` + `difficulty_grades`

```sql
CREATE TABLE grade_systems (
    grade_system TEXT PRIMARY KEY
);

CREATE TABLE difficulty_grades (
    difficulty_grade_id INTEGER PRIMARY KEY,
    boulder_difficulty INTEGER,
    route_difficulty INTEGER,
    is_listed INTEGER NOT NULL DEFAULT 1,
    font_scale TEXT,
    v_scale TEXT,
    french_scale TEXT,
    yds_scale TEXT
);

CREATE INDEX dg_boulder ON difficulty_grades(boulder_difficulty);
CREATE INDEX dg_route ON difficulty_grades(route_difficulty);
```

The hardcoded `KilterGradeMapper` is replaced by lookups against this table.
A startup-time cache (HashMap) is loaded once into memory to keep the per-call
cost zero. Cache invalidates on board-DB sync.

### 3.8 `circuits` + `circuit_climbs` + `climb_beta_links` + `videos`

```sql
CREATE TABLE circuits (
    circuit_uuid TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT,
    setter_uuid TEXT,
    is_listed INTEGER NOT NULL DEFAULT 1,
    is_pinned INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE circuit_climbs (
    circuit_uuid TEXT NOT NULL,
    climb_uuid TEXT NOT NULL,
    position INTEGER NOT NULL,
    PRIMARY KEY (circuit_uuid, climb_uuid),
    FOREIGN KEY (circuit_uuid) REFERENCES circuits(circuit_uuid)
);

CREATE TABLE climb_beta_links (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    climb_uuid TEXT NOT NULL,
    angle INTEGER,
    link TEXT NOT NULL,
    foreign_username TEXT,
    thumbnail TEXT,
    created_at TEXT NOT NULL
);

CREATE INDEX cbl_climb ON climb_beta_links(climb_uuid, angle);

CREATE TABLE videos (
    video_uuid TEXT PRIMARY KEY,
    link TEXT NOT NULL,
    title TEXT,
    thumbnail_path TEXT
);
```

### 3.9 Sync-state Bookkeeping

`aurora_sync_state` is preserved verbatim, but `schema_version` is bumped from
the current value to **`4`** to gate the migration in §6.

---

## 4. Source Mapping (Kilter API → CruxCoach Tables)

This section anchors the schema in observable wire-format fields, so the
implementer can verify each column against `update_board_db.py` output and the
`kilter-re/analysis/FINDINGS.md` reference.

| CruxCoach table | Kilter source | Notes |
|---|---|---|
| `climbs` | REST `GET /api/climbs/curated` (per-angle row) + PowerSync `global_climbs[]` | Stats fields are inline on the climb object |
| `hold_placements` | PowerSync `global[].hold_placements` | `default_placement_type` defines start / hand / foot / top |
| `mounting_holes` | PowerSync `global[].mounting_holes` | `legacy_aurora_id` populated only during migration |
| `products` | PowerSync `global[].products` | |
| `product_layouts` | PowerSync `global[].product_layouts` | `image_path` is a relative or absolute URL — fetched on demand, not bundled |
| `gyms` | PowerSync `global_gyms[]` | |
| `walls` | PowerSync `global_gyms[].walls` | Per-gym; flattened into a single table |
| `hold_sets` | PowerSync `global[].hold_sets` | |
| `holds` | PowerSync `global[].holds` | |
| `grade_systems` | PowerSync `global[].grade_systems` | |
| `difficulty_grades` | PowerSync `global[].difficulty_grades` | |
| `circuits` | REST `GET /api/circuits` | |
| `circuit_climbs` | REST `GET /api/circuits/{uuid}/climbs` | |
| `climb_beta_links` | PowerSync `global_climbs[].beta_links` | |
| `videos` | PowerSync `global[].videos` | |

---

## 5. Code Impact

### 5.1 SQLDelight files

- **New:** `shared/.../sqldelight/board/com/cruxcoach/db/board/KilterBoard.sq`
- **Replaced:** `shared/.../sqldelight/board/com/cruxcoach/db/board/AuroraBoard.sq`
- **Migration assets:** `shared/.../resources/migration/0_1_4/placement_mapping.json`
  bundled as a Kotlin resource (already present in FEAT-003 — re-use the same file).

### 5.2 Repositories

| Old | New |
|---|---|
| `BoardRepository.getClimb(uuid)` | `getClimbAtAngle(uuid, angle)` |
| `BoardRepository.getClimbStats(uuid, angle)` | (removed; data lives on the climb row) |
| `BoardRepository.getClimbsByGrade(grade)` | (unchanged signature; query rewritten over new tables) |
| `BoardRepository.getPlacementsForLayout(layoutId)` | `getPlacementsForLayout(layoutUuid)` |
| `KilterGradeMapper.toDisplayGrade(difficulty)` | `BoardRepository.lookupGrade(difficulty)` (cached lookup) |
| — | `BoardRepository.getGymsNear(lat, lng, radiusKm)` (new) |
| — | `BoardRepository.getCircuitsContaining(climbUuid)` (new) |

### 5.3 Domain models

- New: `Climb(climbUuid, angle, productName, layoutUuid, setterUuid, …)` —
  flattened, replaces `Climb` + `ClimbStats` pair.
- New: `Gym`, `Wall`, `Circuit`, `CircuitClimb`, `BetaLink`, `Video`,
  `DifficultyGrade`.
- Kept (unchanged signature): logbook-side `Ascent`, `Bid`, `BoardSession`.

### 5.4 cruxcoach-blossom-sync (separate repo)

| Script | Change |
|---|---|
| `update_board_db.py` | Stop converting Kilter API → Aurora schema. Emit Kilter-native tables directly. Phase 1: emit BOTH Aurora and Kilter-native tables in the same SQLite file (dual-schema). Phase 2: emit Kilter-native only. |
| `build_board_db.py` | Drop Aurora APK extraction path entirely. Build is REST + PowerSync only. |
| `pull_board_db.py` | Unchanged. Still reassembles monthly chunks from Blossom. |
| `placement_mapping.json` | Move to a separate Blossom blob, ship inside the board DB or as a sidecar — clients need it for one-shot frame migration. |

The blossom-sync flip is not a same-day deploy: clients on 0.1.3 must keep
working off the Aurora-style DB until 0.1.4 has rolled out widely. See §6.5.

---

## 6. Migration Strategy

### 6.1 Two-Phase Approach

| Phase | Released in | Local DB | Blossom DB | App reads from |
|---|---|---|---|---|
| 0 (today) | 0.1.3 | Aurora only | Aurora-converted | Aurora tables |
| 1 | 0.1.4 | Aurora + Kilter-native (dual) | Dual (Aurora + Kilter-native) | Kilter-native |
| 2 | 0.1.6 | Kilter-native only | Kilter-native only | Kilter-native |

**Rationale.** A single-shot break in 0.1.4 risks stranding any user who skips
upgrades or whose Zapstore client is delayed. Two phases give a soak window
where both schemas coexist on disk; old logbook code paths can still resolve
climb metadata even if the new query layer has a bug. Phase 2 cleanup is
~50 lines of `DROP TABLE` plus the Aurora SQLDelight file removal.

See OPEN DECISION §10.2 if a single-shot is preferred.

### 6.2 In-App Migration Trigger

`BoardSchemaMigration_0_1_4` runs once on first launch of 0.1.4, gated by
`aurora_sync_state.schema_version < 4`. It:

1. Creates new tables (Kilter-native).
2. If a freshly-downloaded Blossom DB is present and already contains
   Kilter-native tables, skips local conversion: just bumps `schema_version`
   to 4. Common case for users who sync the board DB on first launch of 0.1.4.
3. Otherwise (Blossom DB is still Aurora-only, e.g., user is offline), runs
   the in-app conversion described in §6.3.
4. Sets `schema_version = 4`. Aurora tables remain queryable for Phase 1.

The migration must be **idempotent** — if it crashes mid-way and is retried,
it must produce the same end state without errors. SQLDelight's `INSERT OR
REPLACE` plus a clean transaction boundary handles this.

### 6.3 In-App Aurora → Kilter-Native Conversion (offline path)

```
For each aurora_climb row:
    For each angle in aurora_climb_stat WHERE climb_uuid = climb_uuid:
        Build a `climbs` row by joining (climb, climb_stat-at-angle, product_size, layout)
        Convert frames string p{placement_id}r{role} → climbConcat using placement_mapping.json
        Insert into climbs

For each aurora_placement row:
    Look up new hold_placement_id via placement_mapping
    Insert into hold_placements (with led_position joined from aurora_led)

For each aurora_hole row:
    Generate a deterministic UUID5(namespace=board_DB_uuid, name="hole:" + id)
    Insert into mounting_holes with legacy_aurora_id = aurora_hole.id

For each aurora_product_size row:
    Split into products(product_name) + product_layouts(*) rows
    Generate deterministic UUID5 for product_layout_uuid

(gyms, walls, circuits, hold_sets, holds, grade_systems, difficulty_grades,
 climb_beta_links, videos: not present in Aurora — populated empty until next
 Blossom sync.)
```

**UUID generation must be deterministic** so that a fresh device reinstall
that re-syncs the same Aurora DB produces the same UUIDs. Use UUID5 with the
board-DB SHA-256 as the namespace.

See OPEN DECISION §10.3 (UUID determinism scheme).

### 6.4 Frame String Migration in Logbook (`aurora_ascent.frames`)

The board-DB migration above is one piece. The logbook side stores climb
frames in `aurora_ascent.frames` and `aurora_bid.frames` in the old Aurora
format (`p{placement_id}r{role}`). For Phase 1 these stay in the old format —
the rendering layer detects the format and converts on read using
`placement_mapping.json`. For Phase 2 they are upgraded in-place, in a
dedicated migration job, to `climbConcat`.

This keeps Phase 1 small (board-DB only) and isolates the larger walk over
personal-data tables to a focused 0.1.6 spec.

See OPEN DECISION §10.4 (eager vs. lazy frame-string migration).

### 6.5 Blossom-Sync Server-Side Coordination

| Day | blossom-sync emits | CruxCoach versions reading it |
|---|---|---|
| 0 (0.1.4 release) | Aurora + Kilter-native (dual) | 0.1.3 reads Aurora tables; 0.1.4 reads Kilter-native |
| 0–60 | Dual | Same |
| 60 (planned) | Kilter-native only | 0.1.3 stops working — show prominent upgrade banner via Announcement Kind 30078 starting day 30 |
| 90 | Kilter-native only | 0.1.3 declared unsupported |

Day-60 cutover is monitored against Zapstore download stats; if 0.1.3 still
holds >5% of active installs at day 60, postpone by 30 days. See OPEN
DECISION §10.6.

### 6.6 Migration Test Matrix

| Scenario | Expected outcome |
|---|---|
| Fresh install of 0.1.4 + sync from Kilter-native Blossom DB | Skip in-app conversion; `schema_version = 4` |
| Upgrade 0.1.3 → 0.1.4, no internet | In-app conversion runs against existing Aurora DB; logbook references survive |
| Upgrade 0.1.3 → 0.1.4, with Blossom-native DB downloadable | In-app conversion is *not* required — fresh DB replaces local; existing logbook entries' `climb_uuid` references survive because UUIDs are stable |
| Mid-conversion crash | On retry, migration completes cleanly (idempotent) |
| Logbook frame rendering during Phase 1 | Old-format frames detected, converted on render |
| User reverts to 0.1.3 from 0.1.4 (downgrade) | Aurora tables still present, queryable; Kilter-native tables ignored |

---

## 7. Performance Notes

| Operation | Estimate |
|---|---|
| In-app Aurora → Kilter-native conversion | ~3 s for 85 000-climb DB on a mid-range device (single transaction, no I/O between rows) |
| Difficulty-grade lookup | O(1) HashMap hit after startup-time load (~100 rows) |
| `climbs` query at angle | Same as old Aurora `climb + climb_stat` join, but one table; faster |
| Schema size on disk | Estimated +5–10% over Aurora due to extra tables (gyms, walls, circuits) — but users that don't yet have gym/wall data populated stay at ~Aurora size |

---

## 8. Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Kilter API adds new placement IDs not in `placement_mapping.json` | Medium | High | Re-derive mapping from latest API on each blossom-sync run; bundle latest mapping in Blossom DB itself |
| `legacy_aurora_id` collision after Phase 2 cleanup | Low | High | Phase 2 is gated by all logbook frames being upgraded first |
| Migration crash leaves DB in inconsistent state | Low | High | Wrap migration in single SQLite transaction; idempotency on retry |
| `is_listed = 0` climbs lost during conversion | Low | Medium | Migration preserves all rows regardless of `is_listed`; UI filters at query time |
| Old 0.1.3 client breaks on day-60 cutover | Medium | Medium | Announcement-banner from day 30; postpone cutover if active 0.1.3 share > 5% |
| FEAT-003 schema additions conflict with new schema | Medium | Medium | Coordinate landing order; FEAT-003 migrates against new schema directly (its `community_grade_cache` table sits cleanly alongside) |

---

## 9. Dependencies

- **kilter-re** (`~/kilter-re/analysis/FINDINGS.md` and `placement_mapping.json`)
  — the schema in this spec is derived directly from FINDINGS.md sections 1–17.
- **FEAT-003** (Climb Creator) — owns the canonical `placement_mapping.json`
  and the `climbConcat` ↔ Aurora-frames conversion code. Both are reused here.
- **cruxcoach-blossom-sync** — the server-side cron that delivers the board
  DB. Schema flip on the server must coordinate with this release.
- **FEAT-030** (Kilter API auth, separate spec) — out of scope here, but the
  daily blossom-sync run depends on it being live.

No new app dependencies.

---

## 10. Open Decisions

### 10.1 Composite primary key vs. single PK on `climbs`

- **Composite `(climb_uuid, angle)`** — reflects what the API returns; same
  `climb_uuid` legitimately appears at multiple angles with different stats.
- **Single PK `climb_uuid`** — preserves the current CruxCoach mental model;
  requires picking one angle as canonical.

**Recommendation:** composite PK. The API shape is the source of truth; query
patterns adapt easily. Logbook joins continue to work because the index on
the leading column of the composite PK functions identically to a single-column
index for `climb_uuid`-only lookups.

### 10.2 One-shot vs. two-phase migration

- **One-shot in 0.1.4** — drop Aurora tables immediately. Smaller code, single
  release. Stranded if user hasn't synced a Kilter-native DB.
- **Two-phase, Aurora dropped in 0.1.6** — soak window, smoother UX, more
  code to maintain through one release.

**Recommendation:** two-phase. Phase 1 cost is ~200 lines of code; risk of a
botched single-shot migration is much higher.

### 10.3 UUID generation for migrated Aurora rows

Aurora tables have integer IDs; the new schema needs UUIDs. Options:

- **UUID5 with board-DB SHA-256 as namespace** — deterministic, reproducible
  on reinstall. **Recommended.**
- **Random UUID4** — non-reproducible; logbook references break across
  reinstalls if the user re-runs migration on a fresh device.
- **UUIDs from `placement_mapping.json`** — only covers placements; not
  applicable to gyms/walls (which Aurora doesn't have anyway).

**Recommendation:** UUID5 with the local Aurora DB's SHA-256 as the namespace
seed, plus a per-row name like `"hole:<aurora_id>"`. Reinstall on the same
DB produces the same UUIDs.

### 10.4 Frame-string migration: eager (one transaction) vs. lazy (on read)

- **Eager** — walk all `aurora_ascent.frames` and `aurora_bid.frames` at
  upgrade time, rewrite to `climbConcat`. Single big transaction, ongoing
  reads are simple. Cost: ~10 s for a power user with 5 000+ ascents.
- **Lazy** — keep Aurora-format frames in DB, convert on read. No upgrade
  cost. Ongoing CPU cost forever, plus every feature touching frames must do
  conversion.

**Recommendation:** lazy in Phase 1 (this spec); eager migration deferred to
the 0.1.6 cleanup spec. Rationale: Phase 1 is already large; isolate the
logbook walk to its own focused release.

### 10.5 `legacy_aurora_id` lifetime

Kept on `mounting_holes` through Phase 1 to support frame-string conversion.
Drop in Phase 2 (0.1.6) once `aurora_ascent.frames` is migrated to
`climbConcat`.

**Recommendation:** keep through Phase 1, drop in Phase 2. Code change is
trivial (one ALTER TABLE).

### 10.6 Blossom-sync schema cutover timing

Conservative: 90 days. Aggressive: 30. **Recommendation:** 60 days, monitored
against active 0.1.3 download share via Zapstore stats. Cutover slips by 30
days if active 0.1.3 share is still >5%.

### 10.7 Grade-system lookup: dynamic table vs. hardcoded `KilterGradeMapper`

- **Dynamic** — query `difficulty_grades` (cached at startup). Future-proof
  if Kilter changes scales.
- **Hardcoded** — keep `KilterGradeMapper` as a fallback for when the
  difficulty_grades table is empty (fresh install before first sync).

**Recommendation:** dynamic with a hardcoded fallback that ships alongside
the migration assets. The fallback is removed in Phase 2.

### 10.8 Gym/Wall integration in personal-data DB

CruxCoach personal-data tables (`aurora_ascent`, `aurora_bid`,
`board_session`) currently have no `gym_uuid` / `wall_uuid` columns. Adding
them is a personal-data-DB change — out of scope for this spec — but if we
don't add them now, push-to-Kilter (FEAT-003 dual-publish) can't populate the
mandatory `gymUuid` / `wallUuid` fields on log creation.

**Recommendation:** add nullable `gym_uuid` and `wall_uuid` columns on
`aurora_ascent` and `aurora_bid` in this release — minimal personal-data
change, isolated to two ALTER TABLEs. Defer the broader logbook
restructuring to a separate spec.

### 10.9 Image asset strategy

`product_layouts.image_path` is a URL (per Kilter). Three options:

- **Lazy fetch + cache** — request on first display, cache in app's HTTP cache.
- **Eager bundle in Blossom DB** — increases DB size by ~5–10 MB.
- **Sidecar Blossom blob per layout** — one blob per layout, fetched on demand.

**Recommendation:** lazy fetch with on-disk cache, identical to how
`aurora_board_image` is handled today. No spec change beyond a `image_path`
column rename.

---

## 11. Implementation Checklist

- [ ] Create `KilterBoard.sq` with full DDL from §3
- [ ] Generate SQLDelight Kotlin types
- [ ] Write `BoardSchemaMigration_0_1_4.kt` per §6.2–6.3
- [ ] Update `BoardRepository*.kt` — new query methods per §5.2
- [ ] Replace `KilterGradeMapper` callers with `BoardRepository.lookupGrade`
- [ ] Add startup-time grade-cache load
- [ ] Add lazy frame-format detection + conversion in rendering layer
- [ ] Add nullable `gym_uuid` / `wall_uuid` columns on `aurora_ascent` and
      `aurora_bid` (per §10.8)
- [ ] Update `cruxcoach-blossom-sync.update_board_db.py` to emit dual-schema
      DB (per §5.4 + §6.5)
- [ ] Add migration test matrix (per §6.6)
- [ ] Add Announcement Kind 30078 banner for day-30 0.1.3 deprecation warning
- [ ] Document the schema cutover plan in `cruxcoach-blossom-sync/README.md`
- [ ] Plan Phase 2 (0.1.6) follow-up spec for: drop Aurora tables, eager
      `aurora_ascent.frames` rewrite, drop `legacy_aurora_id`, drop hardcoded
      grade fallback
