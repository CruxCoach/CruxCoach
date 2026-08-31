# UI slice: board logbook

Status: portable contract established; first production card accessibility slice awaiting candidate device render

Decision date: 2026-08-30

## Journey and user goal

1. A durable send or attempt is confirmed on climb detail.
2. The climber opens the board logbook from the browser or the confirmation action.
3. The newest entry is recognizable by climb, outcome, grade, angle, board and time.
4. Opening an entry returns to the exact climb variant through the existing logbook navigation source.
5. Secondary tasks—edit, selection/delete, statistics and heatmap—remain available through progressive disclosure.

The primary goal is to verify and revisit a durable training record. Statistics are valuable but must not compete with the newest entries for initial attention.

## Functional and state parity

| Existing behavior | Required after this slice | Evidence/gate |
|---|---|---|
| Paginated sends and attempts grouped by day | Preserve ordering, grouping and 50-row paging | repository/ViewModel tests and Maestro logbook flows |
| Open exact climb UUID, angle and logbook navigation source | Preserve | `flows/logbook-create-send.yaml` |
| Honest flash calculation and summary statistics | Preserve | `flows/logbook-honest-flash-and-stats.yaml` |
| Edit attempt count, quality and comment | Preserve through shared attempt form | focused logging tests |
| Single/multi-select and destructive confirmation | Preserve; never delete from a row tap alone | existing test tags and Maestro cleanup flows |
| Stats interval, charts, board split and heatmap | Preserve behind secondary disclosure | existing stats/heatmap tests |
| Publish eligible own Kilter climbs | Preserve identity gate and feedback | existing publisher tests |
| Imported legacy rows and missing catalogue metadata | Preserve readable fallback and exact UUID | backup/import Maestro flows |
| Loading, empty and failure | Distinguish all three; failure must offer retry | portable state contract and future scenario tests |

No database, backup, playlist, share/import, BLE or migration format changes belong to this slice.

## Information hierarchy

1. Top bar identifies Logbook and owns back navigation.
2. A quiet compact summary establishes total volume; it must not push the newest entry below the first compact viewport.
3. Newest entries are the primary content and expose outcome, climb identity, board/angle and time without opening stats.
4. Interval and detailed statistics move behind an explicit stats action.
5. Selection changes the top bar into a clear contextual mode; destructive action is disabled until selection exists.
6. Edit, publish and other row actions are secondary disclosure and must not make the entire row ambiguous.

## Semantic token and component specification

- Use CruxCoach semantic color, spacing, shape and typography tokens; no new raw colors.
- Send and attempt use text/icon/state in addition to color. Positive color is reserved for confirmed sends, not generic cards.
- Row and toolbar actions have 48 dp minimum targets and deterministic labels/roles.
- Day headings are semantic headings. Entry traversal follows visual newest-first order.
- Selection exposes selected state and count; destructive confirmation names the affected count.
- Empty and error use different title/body/action semantics. A storage failure is never rendered as “no entries”.
- Dates, grades and board labels are platform-formatted inputs to the portable state, not embedded Android resources in the common contract.

## Deterministic scenarios

The later fixture harness must add:

| Scenario | Fixed state | Purpose |
|---|---|---|
| `logbook/content` | two days; send, attempt and legacy metadata fallback | hierarchy and row parity |
| `logbook/empty` | successful zero-row load | first-use guidance |
| `logbook/error` | initial storage failure | retry and no false empty state |
| `logbook/selection` | two selected rows | contextual actions and destructive guard |
| `logbook/loading-more` | content retained with page progress | paging continuity |

Render English/German, light/dark, compact/expanded and font scales 1.0/1.5. Fixture time, locale, rows and grade labels are fixed.

## Design hypothesis and comparison region

Hypothesis: prioritizing newest durable entries, collapsing detailed analytics behind one explicit action and separating error from empty will make post-log verification faster without removing expert statistics.

The first comparison region is the content header plus first day group only. Selection, stats sheet and edit dialog are separate later regions. At most three autonomous correction rounds are allowed per region; Golden changes require explicit review.

## Accessibility and performance budgets

- Minimum target: 48 dp; normal text contrast 4.5:1, large text and meaningful non-text contrast 3:1.
- No outcome, selection, publish or error state is encoded by color alone.
- Large text must keep the first entry and all contextual actions reachable without horizontal clipping.
- Initial load exposes one Loading node, successful zero data exposes Empty, and failure exposes Error plus Retry.
- Paging must retain existing rows and scroll position; it must not replace content with a full-screen loader.
- Capture startup-to-first-browser-content separately; for Logbook capture open-to-first-entry and a controlled 100-row scroll before broad production wiring. Investigate median regressions above 5% or frame-time regressions above 10%.

## Verification record

- Existing Maestro coverage: `logbook-create-send`, `logbook-honest-flash-and-stats`, legacy backup import and backup round-trip.
- Portable contract tests are Linux-runnable and do not touch persistence formats.
- The Nokia 6.1 product baseline on 2026-08-31 renders the German dark/1.5 empty state without inset overlap or clipping. It contains no entry row, so it cannot validate row actions.
- The first bounded card hypothesis is that explicit per-climb selection/edit labels and 48-dp targets remove ambiguous undersized controls while preserving row-open, selection and edit as distinct actions. `logbook/content` now provides the deterministic selected-row fixture needed for candidate pixels and semantics.
- The focused Robolectric semantics contract preserves all three callbacks, checked state and 48-dp selection/edit targets. Candidate device rendering remains required before declaring this region complete.
- The next independent state slice now routes the production host through the portable Loading/Empty/Error/Content distinction. Initial failure exposes one localized 48-dp retry; successful zero rows remain Empty, and the established content/paging branch is unchanged. `logbook/error` is the deterministic supplemental fixture; candidate device rendering remains required.
- The broader content hierarchy remains a separate region; it is not implied by these card and initial-state changes.
- Mac/iPhone shell mapping remains behind the KMP export and Apple gates in `docs/refactor/ios-readiness.md`.
