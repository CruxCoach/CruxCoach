---
status: draft
---
# Feature Spec: Kilter Homewall Support (FEAT-016)

> **Status:** Draft — research phase. Needs schema + climb-data
> verification against the bundled board DB before promotion to "Ready".
> **Depends on:**
> - FEAT-007 (improved board selection) — Homewall sits next to Original
>   in the picker and the gym→board inference, so the new picker UX
>   ships first.
> - **0.1.4 Climb Creator must be done** before extending the creator to
>   support Homewall (per user direction). Map + Browser parts of FEAT-016
>   can land before the creator extension.
> **Blocks:** Climb Creator's Homewall mode (a follow-up to v0.1.4).
> **Target release:** v0.1.7 for read-only browsing; Climb Creator
> Homewall mode is a separate ticket once 0.1.4 ships.

## 1. Overview

Kilter sells two product families on the same hardware bus:

- **Kilter Board Original**: 12×12, 16×12, 8×12, 7×10, 12×14 commercial
  installations. CruxCoach already supports these end-to-end (browse,
  send, log, BLE).
- **Kilter Board Homewall**: smaller home-installation product line with
  ~10 distinct sub-layouts (Mainline, Auxiliary, Fullride…), a fraction
  of the commercial board's hold count, different routes, but the same
  BLE protocol and LED encoding.

Today CruxCoach hides the entire Homewall family. The board picker
hardcodes `KILTER_ORIGINAL_LAYOUT = 1` and the size picker only lists
`product_id == 1` (Original family) sizes. Homewall users (~5% of the
hangtime dataset, plus an unknown number of private installations not
in any locator) have no path through the app.

This feature adds full read-side Homewall support: browse climbs, view
holds, send via BLE, log climbs. It does **not** include Climb Creator
support — that depends on the v0.1.4 creator landing first.

### Goals

- Homewall users can finish onboarding and pick their specific Homewall
  variant (Mainline / Auxiliary / Fullride / Full-Density / etc.)
- Climb Browser shows Homewall-specific climbs for the selected
  variant — separate from Original's ~228k climbs
- Hold renderer handles Homewall layouts (different image, different
  hold positions, often fewer LED roles)
- BLE LED packets work unchanged (same hardware, same protocol)
- Climb logging records Homewall layout/size correctly so personal
  history stays consistent across board changes

### Non-Goals

- Climb Creator Homewall mode — separate spec, blocked on v0.1.4
- Custom user-built Homewall layouts (not Kilter SKUs)
- Tension Board Homewall — Kilter only
- Migrating existing Original-only users' climb history into
  Homewall (data is layout-keyed, no migration needed)
- Hardware emulation / virtual Homewall for testing

---

## 2. Reference Material

### 2.1 BoardSesh

BoardSesh ships Homewall support for browsing + sending. Their
implementation is the most authoritative public reference for which
`product_layout_uuid` values exist, what each maps to, and what the
hold layouts look like.

- Repo: https://github.com/boardsesh/boardsesh
- Path: `packages/board-data/` and `packages/web/` for the renderer
- Look for: `product_layout` enum, `Homewall` rendering branches,
  hold-position math for non-Original layouts

### 2.2 Reverse-engineering folder

The kilter-re workspace (`the internal research archive`) holds the official
Kilter app's product_layouts table. Section 5 of `FINDINGS.md` documents
the PowerSync `global_gyms[]` schema where `product_layouts.id` covers
both Original and Homewall product lines.

Known IDs from the May 2026 hangtime crawl:
- `10` = Original 12×12 (most common)
- `8` = Original 8×12
- `28` = Original 16×12
- `14` = Original 7×10
- `7` = Original 12×14
- `27` = Original variant
- `17, 18, 19, 21, 22, 23, 24, 25, 26` = Homewall variants
- `33` = UP Board (out-of-scope — different hardware)

Action: validate this table against `the internal research archiveFINDINGS.md`
section 5 and against BoardSesh's enum. Catalog the human-readable
name for each Homewall variant before locking the spec.

### 2.3 Bundled board DB

Our daily Blossom-synced `kilter_board.bin` decompresses to a SQLite
file with `product_sizes`, `layouts`, `placements`, `images` tables.
**Open question Q1**: do these tables already include Homewall rows,
or has our cron been silently filtering them out? Check by:

```bash
sqlite3 /tmp/kilter_board.sqlite3 \
  "SELECT id, name, product_id FROM product_sizes WHERE product_id != 1;"
sqlite3 /tmp/kilter_board.sqlite3 \
  "SELECT id, name FROM layouts WHERE id != 1;"
```

If yes → no schema work needed, this is purely a UI surface job.
If no → cron + Blossom chunk schema needs widening (significant work,
delays the feature).

---

## 3. Data Model

### 3.1 Schema (no change expected)

The `kilter_board_location` table FEAT-015 added uses `layout_id` and
`product_size_id` opaquely — a Homewall row is just a different
integer in those columns, no schema change. Same for the climbs table.

If section 2.3's check shows the bundled DB is missing Homewall data,
update `update_board_db.py` to include `product_id IN (1, 2, ...)` (or
the actual Homewall product_id) in its export queries, regenerate the
Blossom chunk, bump migration version.

### 3.2 New constants

`BoardConstants.kt` gets a Homewall layout id constant alongside the
existing Original one. Hardcoded UI defaults stay on Original — a
Homewall user explicitly opts in via Path A or B of FEAT-007.

### 3.3 Per-variant holds

Each Homewall variant has a different hold layout. The bundled DB's
`placements` and `holes` tables are keyed by `(product_size_id, set_id)`
which already disambiguates per variant. No new join logic required.

---

## 4. UX

### 4.1 Board picker (FEAT-007 Path A)

Layout dropdown gains a "Homewall" option below "Original". Selecting
it switches the size dropdown to enumerate Homewall variants by
human-readable name (Mainline, Auxiliary, Fullride, Full-Density, etc.).

The size dropdown filters `product_sizes` by the selected layout's
`product_id`.

### 4.2 Gym search inference (FEAT-007 Path B)

A Homewall gym in the location dataset (rare in commercial locators —
mostly Original gyms publish there) sets `(layout=8, product_size_id=N)`
on selection. Snackbar message reflects: "Board für DIY Garage Wall:
Homewall Mainline. Ändern?"

### 4.3 Climb Browser

No UI change. The existing `WHERE layout_id = ?` query naturally
filters to the active layout. Visual distinguisher: the layout name
appears in the Browser's title bar so a user doesn't accidentally
think the empty list is a sync failure.

### 4.4 Hold renderer

`BoardImageRenderer` looks up `(product_size_id, set_id)` already.
Verify against a Homewall test case that hold dot positions and LED
indices line up with the Homewall image. Likely one or two off-by-one
fixes.

### 4.5 Map markers (FEAT-015 follow-up)

The current "Show homewalls" toggle FEAT-015 ships in v0.1.5 already
exposes Homewall gyms on the map. Once FEAT-016 lands, "Browse Climbs
for this board" on a Homewall location actually opens a populated
browser instead of showing an empty list.

---

## 5. Climb Creator (Out of Phase 1)

The v0.1.4 Climb Creator (FEAT-030 in the existing backlog) supports
Original-only because its `product_layout_uuid` validator hardcodes
Original IDs. Extending it to Homewall is mechanical:

- Validator accepts any `(product_id, layout_id)` from the bundled DB
- Hold-placement palette switches per the user's active layout
- API submission body works unchanged (Kilter accepts any
  `product_layout_uuid` server-side)

This is a follow-up ticket, **scheduled after v0.1.4 ships and
stabilises**. Do not start before then per user direction.

---

## 6. Open Questions

| # | Question | Resolution path |
|---|---|---|
| Q1 | Does the bundled board DB contain Homewall rows today? | Inspect with sqlite3 query in §2.3. If no → cron rewrite scope. |
| Q2 | What's the actual Kilter product_id for Homewall? | Check `products` table in bundled DB or kilter-re FINDINGS. |
| Q3 | Are Homewall climbs published via the same `/curated` endpoint or a different one? | Probe via existing `probe_climbs_endpoints.py` — does response carry layout_id=8 entries? |
| Q4 | How many Homewall climbs exist? | Count distinct `(layout_id, climb_uuid)` for layout_id=8 after Q1/Q3 unblock. If <50, the feature is data-poor and may not be worth shipping until Kilter publishes more. |
| Q5 | Is there a single canonical "Homewall" layout name in our UI, or do we list all sub-variants (Mainline / Aux / Fullride / Full-Density)? | Decide after counting installs per variant — if 90% are one variant, simplify; otherwise show all. |
| Q6 | Hold-set differences: does Homewall use the same hold IDs (12/13/14/15 + 42-45) or a subset? | Verify against kilter-re section on placement_types. |
| Q7 | BLE: any Homewall-specific firmware quirks? | RE folder claims protocol unchanged — needs hardware confirmation from a real Homewall user. |
| Q8 | Should we add a "Homewall" layout filter to the Board Browser independent of the user's configured board? | No — single active board per FEAT-007. User switches board to switch corpus. |

---

## 6.1 Cron / Blossom-sync changes (load-bearing constraint)

If §2.3's check confirms Homewall data must be added on the cron side
(`update_board_db.py` and/or a new chunk in `blossom_upload.py`), the
work follows three **non-negotiable** rules:

### Rule 1 — Backward compatibility is mandatory

Every change must keep older clients (≤ v0.1.6) working. Concretely:

- Existing chunks (`climbs-YYYY-MM`, `stats-YYYY-MM`, `meta`,
  `locations`) must keep their current schema and content shape. Old
  clients ignore unknown chunk types in the manifest's `chunks[]` array
  by silently dropping them in `BoardSyncManager.inferType()`'s
  `else -> null` branch — **verify this hasn't regressed before
  shipping**.
- If Homewall climbs are added to existing `climbs-YYYY-MM` chunks
  (rather than a new `homewall_climbs-*` chunk), old clients will
  import them but show layout-id=8 climbs in their Browser even
  without UI affordance. That's acceptable — stale data, no crash.
- New chunk types (`homewall_climbs`, `homewall_stats`, etc.) ride the
  same backward-compat property: old clients ignore them.
- The bundled `kilter_board.bin` schema must not drop or rename any
  table/column an old client reads. Add columns at the end if needed,
  add new tables freely.
- The Nostr Kind 30078 manifest event must not change its `d` tag
  (`cruxcoach/board-db`) or top-level structure. Adding new chunk
  entries is fine; mutating existing fields is not.

### Rule 2 — Dry runs before every real publish

The pipeline already has a dry-run path: `blossom_upload.py` can build
all chunks locally without uploading. For Homewall changes, run the
full sequence and gate the real publish on inspection of:

1. Total chunk count + sizes (warn if any chunk grows >2× from previous)
2. Sample row counts per chunk type (`SELECT COUNT(*)` against the
   built SQLite for each chunk file)
3. Schema diff against the previous shipped chunk
   (`sqlite3 .schema | diff` between local and last archived copy)
4. Manifest event size (must stay under the 128 KB relay cap; hard
   threshold at 110 KB to leave headroom)
5. Backward-compat sanity: install the previous CruxCoach release
   (latest `main`) on a test device, point it at a manifest containing
   the new chunks, confirm sync completes without crashes and the
   Browser still shows Original climbs

Document the dry-run output in the PR description. The cron must not
pick up the new code until at least one full dry-run + inspection
cycle has passed.

### Rule 3 — Staged rollout

The cron's actual `cron_update_board_db.sh` switch happens in three
stages with manual review at each gate:

1. **Stage 1 (manual-only):** new build_*.py modules behave like
   `build_locations_chunk.py` did pre-shipping — invoked via CLI flags,
   not from `prepare_chunks()`. Run by hand against staging output
   directory, inspect, archive, repeat.
2. **Stage 2 (dry-run wrapper):** `prepare_chunks()` calls the new
   builders inside try/except (mirroring the `locations` chunk
   pattern). A failure leaves the manifest publishing the previous
   Homewall chunk hashes. Two consecutive successful daily runs
   required before stage 3.
3. **Stage 3 (production):** remove the try/except wrapper if and only
   if the failure mode is acceptable. Likely keep it permanently —
   Homewall data isn't load-bearing for non-Homewall users.

### Test data archive

All dry-run outputs go to `the internal research archive<YYYYMMDDTHHMMSSZ>/`
per the public-repo-hygiene rules — never into the repo tree.

---

## 7. Risk

- **Q1 = no** is the biggest risk: rewriting the cron to publish
  Homewall data widens the Blossom payload and triggers a coordinated
  client-side schema migration. That's a release of its own, pushing
  FEAT-016 to v0.1.8+. Mitigated by §6.1's staged rollout — old
  clients keep working at every stage.
- **Q4 = low number** makes the feature lukewarm — Homewall users
  finally have onboarding parity but find an empty climb library. Plan
  B: Homewall users see a banner pointing to BoardSesh's web view as a
  bridge until their dataset grows.
- **Hold renderer surprises** on non-Original layouts. Mitigation: write
  a debug screen that overlays placement_id labels on the hold image
  for visual verification before shipping.

---

## 8. Implementation Phases

**Phase 1 — Discovery (1 day)**
- Run §2.3 queries against the current bundled DB
- Cross-check IDs against kilter-re FINDINGS section 5 + BoardSesh enum
- Decide Q1 / Q4 outcome — abort or proceed

**Phase 2 — UI surface (assumes Phase 1 = proceed, ~3 days)**
- Add Homewall to layout picker (FEAT-007 dependency)
- Verify climb browser query path for layout_id=8
- Hold renderer smoke-test with a Homewall variant
- Map "Show homewalls" toggle becomes meaningful (already shipped in v0.1.5)

**Phase 3 — Logging + history (1-2 days)**
- Verify climb log persists `(layout_id, product_size_id)` so a
  user-switched board still shows the right entry retroactively
- Personal stats screen splits Original vs Homewall when both have
  history

**Phase 4 — Climb Creator Homewall mode (separate ticket, blocked on v0.1.4)**
- Hold palette per layout
- Validator accepts Homewall product_layout_uuid
- API submission unchanged

---

## 9. Out of Scope

- Tension / Moonboard / other system-board Homewalls
- User-built custom Homewall layouts
- Cross-board climb porting ("can I climb Original 12×12 routes on my
  Homewall?") — physically impossible, no UI for it
- Per-wall disambiguation in multi-wall gyms — covered by FEAT-007.1
