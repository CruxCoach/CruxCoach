---
status: planned
queue: needs-clarification
base: 0.2.2
depends_on: []
created: 2026-07-15
---

# Feature Spec: Reach metric — wire ReachAnalyzer into the local save path (completes FEAT-043)

> **Status:** design locked by the developer (2026-07-15); reviewer sign-off
> owed on the recommendations marked **[REC]** in §3 (see §8).
> **Depends on:** none
> **Relates to:** FEAT-003 (climb creator — the save path this hooks into)

**Id note:** FEAT-043 was allocated by the core commit `bd169ac`
("ReachAnalyzer — MST-bottleneck reach metric (FEAT-043 core)"); no FEAT-043
spec file exists anywhere in the repo or its history. This spec completes the
existing id rather than allocating a new one.

## 1. Overview

`ReachAnalyzer`
(`shared/src/commonMain/kotlin/com/cruxcoach/domain/board/ReachAnalyzer.kt`)
computes the reach metric — the longest edge of the minimum spanning tree
over a climb's HAND-role holds, i.e. the widest gap that *must* be bridged
regardless of sequence. Its kdoc declares it "the save-time fallback for
locally created climbs" (the server-side catalogue cron is the source of
truth for everything else) — but it is dead code: no save path calls it, no
column stores a result, no UI shows it. This spec is the **minimal honest
integration**: compute at save time for locally created climbs, store it,
surface it where those climbs are shown.

**Goals:**
- Every save/re-save of a locally created climb computes and stores its
  reach score.
- The value is visible in the climb detail for climbs that have one.
- Parity with the server cron stays pinned (existing fixtures in
  `ReachAnalyzerTest`).

**Non-goals:**
- No browser sort/filter by reach (needs catalogue-wide coverage first).
- No on-device backfill of the existing catalogue (the ~190k-row catalogue
  gets its values from the server pipeline; ingesting them is a future sync
  schema bump, out of scope here).
- No display for climbs whose `reach_gap` is NULL (which is: everything
  except locally created climbs, until the catalogue pipeline delivers).
- No mirror-aware or angle-aware variants (frame geometry is
  angle-independent; mirroring preserves distances).

## 2. Today's behaviour

- `ReachAnalyzer.mstBottleneckGap` / `climbReach` exist and are unit-tested
  (parity fixtures), zero production callers.
- Local climb save: the creator (`ClimbEditorViewModel.kt`, saveDraft ~`:658`)
  persists via the `CommunityClimbQueries.insertLocalDraft` seam
  (`shared/src/commonMain/kotlin/com/cruxcoach/data/repository/BoardRepository.kt:961-978`,
  impl in `BoardRepositoryImpl.kt`) — frames string, layout, bounds; no
  reach anywhere.
- Schema: `climbs` / `climb_stats`
  (`shared/src/commonMain/sqldelight/board/com/cruxcoach/db/board/Board.sq:6/:66`)
  have no reach column; board DB migrations currently end at `24.sqm`.
- Geometry sources exist: `BoardClimbParser` parses frames to
  `(placementId, roleId)` holds with `roleClass` (`BoardClimbParser.kt:60`)
  distinguishing hand-class roles (start/hand/finish) from feet; the
  `placements` table (`Board.sq:100`, pre-joined with `holes` x/y) maps
  placement → grid coordinates.

## 3. Solution design

### 3.1 Storage

**[REC R7]** New nullable column on `climbs` (NOT `climb_stats`):
board DB migration `25.sqm` —
`ALTER TABLE climbs ADD COLUMN reach_gap REAL;` + the matching column in
`Board.sq`'s CREATE TABLE and the local-draft insert/upsert queries.
Rationale: the metric derives purely from frame geometry — one value per
climb, independent of angle, so the per-`(climb, angle)` stats table is the
wrong home. Unit: board-grid units, exactly what `ReachAnalyzer` emits
(NULL = not computed). Conversion to human units happens at display time.

### 3.2 Compute at save time

- In the repository save path (`BoardRepositoryImpl`'s `insertLocalDraft`),
  not the ViewModel — so every writer of a local climb (creator save,
  re-save of an edited draft) gets it for free and the computation sits next
  to the existing bounds derivation:
  1. Parse `frames` with `BoardClimbParser`.
  2. Keep holds whose `roleClass` is hand-class (start/hand/finish); drop
     feet.
  3. Resolve each placement to `(x, y)` via the `placements` rows already
     loaded for the layout (the bounds computation needs the same lookup —
     share it). Placements that don't resolve are skipped; if fewer than 2
     resolve, store NULL (mirrors `mstBottleneckGap`'s `< 2` contract).
  4. `ReachAnalyzer.climbReach(framesHandPoints)` → store in `reach_gap`.
- Re-save replaces the row in place (same uuid) — recompute unconditionally;
  a hold edit must never leave a stale score.
- Extract steps 1–3 as a pure `ReachInput` builder in
  `shared/.../domain/board/` (frames + placement map → hand points per
  frame) so it is JVM-testable without a DB.

### 3.3 Surface in UI

**[REC R8]** Climb detail only (no creator preview in v1): a stat chip next
to the existing grade/quality row, shown iff `reach_gap` is non-NULL —
which today means: locally created climbs. Display converts grid units to
centimetres via the board family's grid pitch; the pitch constants MUST be
copied verbatim from the server cron's the reach-metric module (single source of
truth — do not re-derive them), and the parity fixture set is extended with
one grid→cm case per supported family. Format: rounded to 5 cm
("~85 cm") — the metric is a geometric lower bound, not a tape measure, and
the tilde keeps it honest. If a family has no pitch constant in the cron,
show nothing for that family (never guess).

## 4. Strings (en + de)

| key | en | de |
|---|---|---|
| `climb_detail_reach` | Widest gap ~%d cm | Größter Abstand ~%d cm |
| `cd_climb_detail_reach` | Widest hand-hold gap | Größter Abstand zwischen Griffen |

## 5. Acceptance criteria

1. Saving a local draft with ≥ 2 resolvable hand holds stores a non-NULL
   `reach_gap` equal to `ReachAnalyzer.climbReach` over the same input
   (repo test, in-memory SQLDelight).
2. Feet-only or single-hand-hold climbs store NULL.
3. Foot holds do not influence the score (fixture: adding a distant foot
   hold leaves `reach_gap` unchanged).
4. Re-saving an edited draft recomputes: moving a hand hold changes the
   stored value.
5. Existing catalogue rows are untouched (`reach_gap` NULL after migration;
   migration test).
6. Climb detail shows the chip iff `reach_gap` non-NULL; grid→cm conversion
   matches the cron constants (extended parity fixtures).
7. Existing `ReachAnalyzerTest` parity fixtures still pass unchanged.

## 6. Edge cases

1. **Unresolvable placements** (catalogue row for the layout missing/partial):
   skip those holds; < 2 resolved → NULL, never a crash.
2. **Multi-frame climbs (routes)**: `climbReach` takes the max across frames
   (already implemented) — the builder must group hand points per frame, not
   pool them.
3. **Duplicate placements in a frame**: distance 0 edges are harmless to the
   MST; no dedup required (document, don't "fix").
4. **MoonBoard role codes** (1-8 incl. mirrored set): `roleClass` already
   normalizes — the builder uses `roleClass`, never raw role ids.
5. **Backup/restore**: restored local climbs re-enter via the same upsert →
   recomputed; no special path.

## 7. Testing

- JVM: `ReachInputBuilderTest` (role filtering, per-frame grouping,
  unresolvable placements), repo save test (AC 1-5), grid→cm parity fixtures
  (AC 6-7).
- On-device owed: create a climb in the editor, verify the chip appears with
  a plausible value; edit a hold, value updates.

## 8. Open questions

None blocking — recommendations awaiting reviewer sign-off:

- **[R7]** Column `reach_gap REAL` on `climbs` (board DB, migration 25),
  grid units.
- **[R8]** Detail-screen chip only in v1; cm conversion constants copied
  from the server cron, families without a cron constant show nothing.
