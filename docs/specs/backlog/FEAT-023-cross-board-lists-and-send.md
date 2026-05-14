---
status: backlog
---
# Feature Spec: Cross-Board Lists + Send Concept (backlog)

> **Status:** Backlog — captured 2026-05-14. No release target.
> Custom climb lists (Favoriten + user-created lists) are
> currently **board-agnostic on the data layer** but **board-
> coupled on every read path**. A user with a mixed logbook
> (Aurora-imported + community-published + own climbs across
> Original / Homewall / different sizes) hits at least three
> distinct cross-board failures whose individual point-fixes
> would all conflict — this spec is a placeholder for the
> design pass that decides on one coherent model.
>
> **Depends on:**
> - The per-climb board picker shipped in v0.1.4 (the
>   `canRenderClimbOnSize` + `getProductSizeForClimbRender`
>   pair on `BoardRepository`). The same edge-containment
>   logic is what would let the list view group / filter /
>   route per source-board.
>
> **Relates to:**
> - FEAT-022 (Multi-Heatmap per Board) — same root cause
>   (logbook/list aggregations assume one board), different
>   surface. The conceptual model picked here will likely
>   subsume FEAT-022's per-board heatmap approach.
> - FEAT-005 (Aurora JSON Import) — the dominant source of
>   cross-board entries in user lists; the v0.1.4 release
>   ships these silently broken on every list read path.
> - FEAT-008 (Kilter Own-Climb Import + Backup Extension) —
>   own climbs from a different physical board hit the same
>   gap.

---

## 1. Problem statement

Three distinct symptoms, all caused by the same untreated
"a list contains climbs from boards the user isn't currently
configured for" gap:

### 1.1 Cross-board list entries silently disappear

`BoardListDetailViewModel.loadEntries` calls
`boardRepository.getClimbsByUuids(uuids, angle)` with the
user's currently-configured `boardAngle` (a single
`UserPreferences.boardAngle` value). The underlying
`getClimbsByUuidsSimple` SQL hits `climb_browse`, which is
defined as an **INNER JOIN** of `climbs ⨝ climb_stats` on
`(climb_uuid, angle)`. Result:

- A Homewall climb (stats at angles 15 / 30 / 45 / …) added
  to a list while the user has the Original-board angle 40°
  set in Settings is **invisible** in the list view — its
  inner-join row simply doesn't materialise.
- Symmetrically, an Original 12×12 climb is invisible in
  the list while a Homewall-typical angle (e.g. 50°) is
  active.
- The user sees the entry count drop based on which angle
  they have configured, with no UI feedback about what
  happened.

### 1.2 Cross-board send corrupts the LED pattern

`BoardSendController.sendToBoard` reads
`userPreferences.boardProductSizeId` and looks up the
placement→LED map for *that* size. Climb holds are then
matched against that map by `placement_id`.

- A user with Original 12×12 configured (size 10) tapping
  "Send" on a Homewall climb (placement IDs 4127, 4159,
  4487, …) gets a placement→LED map keyed for size 10.
- The Homewall placement IDs miss in that map ⇒ the BLE
  command goes out with the matched subset (typically
  empty or nonsense) ⇒ the board lights up nothing /
  unrelated holds.
- No error surfaces. The Detail-screen renders the climb
  correctly on its source board (FEAT-022 sister fix), so
  the user sees a coherent visual + a silently broken
  physical send.

### 1.3 Detail-screen pager shows the wrong climb

When the user navigates from `SetterDetailScreen` (and any
other future entry point that doesn't refresh
`climbNavState.climbUuids`), the detail-screen's pager
inherits the *previous* browser/logbook session's UUID
list. The initial pager index falls back to 0 when the
tapped UUID isn't in that stale list — landing on a
completely different climb. This was point-fixed in v0.1.4
with a "stale navState defense" in `BoardClimbDetailScreen`
that drops to single-page mode when the route's UUID is
missing from the cached list, but the *root* problem is
that every entry-point screen has its own contract for
populating `climbNavState`.

## 2. Why none of the obvious fixes works alone

Each symptom has a "small fix" that solves it locally but
breaks the model elsewhere:

| Symptom | Local fix | Why it breaks |
|---------|-----------|---------------|
| 1.1 invisible entries | `INNER → LEFT JOIN`, show "?" grade for off-angle climbs | leaks across the whole `climb_browse` VIEW (every browser query starts returning angle-less rows); breaks BoardBrowser filter/sort assumptions; makes "0 results" UI ambiguous |
| 1.1 alt | per-climb nearest-angle lookup at list-render time | introduces a per-climb DB roundtrip in a hot list path; still doesn't solve the BLE-send mismatch (1.2) |
| 1.1 alt | hide cross-board entries with explicit "n hidden because board" disclosure | honest but every user with Aurora-imports sees a permanent "n hidden" banner — feels like the app is broken |
| 1.2 send | block send when climb's source-size != user's size | strictly correct but means a user with two boards in their gym (or who switches Settings frequently) has to toggle Settings to use a list normally — high-friction |
| 1.2 send | warn-then-send with the LEDs that match | hostile UX — what does "the climb partially lit" mean to a user mid-session? |
| 1.3 stale nav | every screen sets `climbNavState` before nav | requires touching every navigation entry point; the v0.1.4 defense already exists but loses swipe-paging for those entries |

The shared root: **lists (and the screens reading them)
treat "board" as global state from Settings, but climbs
themselves carry a board identity (their edges).** Any fix
that doesn't reconcile those two views of "board" leaves
one of the symptoms above unsolved or just moves it.

## 3. Open design questions (to resolve before implementation)

- **Are lists scoped to one board, or do they span boards?**
  Current code allows the latter implicitly (no schema
  constraint), but every read assumes the former.
- **What is the "board" of a climb at list-display time?**
  The climb's source size (per its edges)? The user's
  current size? Some inferred "best size for this climb on
  this user's hardware"?
- **Should the BLE-send target follow the climb or the
  user's configured board?** A user with a Homewall 10×12
  in their gym + a CruxCoach build configured for that
  size shouldn't have to toggle Settings just because they
  saved a 7×10 climb to Favoriten.
- **What's the right UI signal when a list contains a
  cross-board entry?** A per-card chip ("Homewall 10×12"),
  a section header that groups by board, a separate
  "Other boards" pane, or no signal at all?
- **Does the lists feature need a "primary board" field
  per list?** Could be auto-inferred from the dominant
  source-board of the entries; user-overridable; surfaces
  the cross-board mismatch only when the user explicitly
  adds an outlier.
- **Are filtered views compatible with this concept?**
  BoardBrowser's "All / CruxCoach / Kilter" origin chip
  is one cross-cut; does adding board to the list
  abstraction conflict with the browser's existing
  filter UX?

## 4. Out of scope (explicitly)

- Multi-board hardware support. The user's *physical*
  setup is still one board at a time; this spec doesn't
  add a "switch board mid-session" feature, and
  hardware-side BLE sends remain bound to whatever the
  configured board can address.
- Re-projecting placement IDs across boards. Same reasoning
  as FEAT-022 §1.2: the placement_id space is not portable
  across layouts, and faking a re-projection would lie
  about where the user's hands need to go.
- Renumbering climb_lists schema to include a `board`
  column. May or may not be the right answer; deferred to
  the design pass.

## 5. Provisional success criteria

Whatever the final model, it should:

- Make every climb in a list **discoverable** regardless of
  which board the user has configured (no silent hiding).
- Produce a **predictable, honest** BLE send outcome — either
  light up the right LEDs on a compatible board, or refuse
  the send with a one-line explanation of why.
- Keep the BoardBrowser → Detail flow's mental model
  ("this climb on MY board") intact for the matched-board
  case.
- Survive the Aurora-import scenario without the user
  having to toggle Settings to "see their climbs again".
- Resolve §1.3 by either (a) standardising
  `climbNavState` population at every entry point, or
  (b) making the detail-screen self-source its pager
  context (e.g. by re-querying the source list from
  the route's `from=` parameter).

## 6. Notes for the design pass

- Anchor the conversation in the actual user scenarios
  observed during v0.1.4 testing: Aurora-imported
  Homewall climb on an Original 12×12 user; mixed-board
  Favoriten; Setter-page navigation surfacing wrong
  pager content.
- A whiteboard mock of the list-detail UI for the four
  candidate models (board-scoped lists, multi-board
  lists with chips, multi-board lists with section
  groups, no-cross-board allowed) is probably worth
  more than a 30-page spec at this stage.
- Consider whether the board-picker (FEAT-020, planned for
  0.1.5) makes the "user's current board" axis *cheap
  enough to vary mid-flow* that some of the above tensions
  collapse — a one-tap board switch in the top bar
  changes the trade-off between "block cross-board send"
  and "make user toggle Settings".
