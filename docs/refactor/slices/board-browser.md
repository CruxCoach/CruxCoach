# UI slice: board browser start surface

Status: compact candidate reviewed; board context and continue-session hosts wired

Decision date: 2026-08-30

This specification is the precondition for changing `BoardBrowserScreen`. The
first implementation region is the board-context/search header only. Climb-card
visuals, filter-screen controls, BLE sheets, queue internals, database queries,
navigation routes and persistence are outside that first region.

## Journey and user goal

1. On a normal launch, a climber lands in the browser and immediately sees the
   selected board, angle and connection state.
2. They can search by climb/setter or open relevant filters without revealing
   an unrelated action stack.
3. Recent or frequently used climbs may be ranked quietly, but normal catalogue
   browsing remains available and no session starts automatically.
4. An active session gets one explicit `Continue session` surface. It never
   redirects the user away from the browser.
5. Selecting a climb opens its detail while retaining the browser query, list
   depth and angle. A supported deep link still opens its destination directly.
6. First run remains onboarding-led: select/configure a board, load its
   catalogue and make board connection available before entering the ordinary
   content state.

The primary goal is to choose the next climb with immediate confidence about
which physical board and angle the result belongs to.

## Functional and state parity

| Existing behavior | Requirement | Evidence/gate |
| --- | --- | --- |
| Browser is the post-onboarding start destination | Preserve | route inventory and navigation journey |
| Onboarding precedes browser when incomplete | Preserve board selection, sync and connection affordance | onboarding journey |
| Direct/random/list/map/deep-link detail navigation | Preserve target and angle | route tests / existing journeys |
| Search name or setter | Keep directly visible in content state | semantics and interaction tests |
| Filter screen and board picker | Preserve every board/angle/grade/status/type/source/rule option | filter tests and parity matrix |
| BLE connection sheet and connected/sending state | Preserve; label and icon, never color alone | scenario semantics |
| Running queue/session, rest banner and summary | Preserve; add explicit continue surface without redirect | session scenarios |
| Random climb and add-random-to-queue | Preserve | picker tests |
| Hold/zone search where board supports it | Preserve via progressive disclosure | capability tests |
| Map, logbook, lists, settings and climb creator | Preserve reachability | journey assertions |
| Long-press add to list/running playlist | Preserve | interaction journey |
| Pagination and restored list position | Preserve | browse refill and return journey |
| Preparing DB, first catalogue sync, importing and no-results states | Preserve as distinct typed states | deterministic scenarios |
| View-model `error` | Currently stored but not rendered; surface a retryable error state | new state mapping/test |
| Historical filter tokens (`ALL`, `UNSENT`) | Keep decoder; write current multi-select format | existing filter tests |

No database, backup, playlist, local-share, deep-link or BLE serialization
format changes in this slice.

## State contract

The portable presentation contract must distinguish these states without
exposing SQLDelight, GATT, Compose or SwiftUI types:

```text
Loading(kind = preparingDatabase | catalogue | results)
FirstRun(boardSelectionAvailable, connectionAvailable)
Empty(kind = catalogueMissing | noResults)
Error(messageKey, retryAction)
Content(boardContext, connection, query, filters, climbs, activeSession?)
```

`Content` carries stable climb identity and display-ready domain values, not
Android resources. Platform renderers localize labels. `ActiveSessionState` is
referenced rather than duplicated so a future Live Activity and both browser
shells share the same canonical seconds/counts/phase.

Actions are explicit intents: change query, open filters, select board, connect,
continue session, choose climb, load catalogue, retry, clear filters, random,
create, open map/logbook/lists/settings and load more. Navigation and repository
effects remain platform orchestration.

## Information hierarchy

1. **Board context:** selected board/model, angle and text-plus-icon connection
   state. This is the task's physical context, not decorative chrome.
2. **Active work:** when present, a concise continue-session surface with phase,
   current climb and elapsed/rest status.
3. **Find:** always-reachable search and filter affordance; active filters are
   summarized, not expanded inline.
4. **Choose:** a calm climb list. Recent/frequent ordering may influence rank
   but is not rendered as a competing card carousel in the first slice.
5. **Secondary tools:** random, create, map, lists, logbook and settings remain
   reachable through progressive disclosure and native navigation patterns.

The board/current climb is the visual hero on detail/session surfaces. The
browser itself prioritizes board context and scan-efficient climb selection; it
must not manufacture a decorative hero that pushes results below the fold.

## Semantic token and component specification

- Use CruxCoach semantic colors, typography, spacing, shapes and motion. No new
  raw colors or arbitrary radii.
- Brand orange marks the primary/active affordance; connected, offline, error
  and session phases also use iconography and text.
- Header, search, filter, continue-session and row actions meet 48 dp minimum
  targets. The logo is not announced as an action unless it becomes one.
- Search exposes a text-field role, localized hint and named clear action.
- Board context is one semantic group announced before active session, search,
  filter summary and results. Result count changes use an appropriate live
  announcement without reading the entire list.
- Content reflows at font scale 1.5; German labels may wrap, never clip. Compact
  and expanded widths use Window Size Classes, not device-name checks.
- Edge-to-edge content consumes each inset exactly once. No zero-inset escape
  is introduced without a host-level proof.
- Motion is limited to explaining search expansion, state replacement and
  spatial navigation; reduced-motion mode removes nonessential transitions.

## Deterministic scenarios

Fixtures use the fixed clock/timezone from `ui-scenario-matrix.json`, stable IDs,
no network, no database and no BLE hardware:

| Scenario | Required state |
| --- | --- |
| `browser/content` | Kilter Original, 40°, disconnected, three stable climbs, no session |
| `browser/content-connected` | same results, connected board name visible |
| `browser/session-active` | active session with current climb and explicit continue action |
| `browser/session-resting` | same session in rest phase with fixed remaining seconds |
| `browser/loading-database` | preparing database explanation |
| `browser/loading-catalogue` | active-board import in progress |
| `browser/empty` | valid catalogue, filters match nothing, clear-filter recovery |
| `browser/catalogue-missing` | selected board has no catalogue, load recovery |
| `browser/error` | stable localized issue key and retry action |
| `browser/first-run` | board selection and connection affordances, no fake content |

Each state covers English/German, light/dark, compact/expanded and font scales
1.0/1.5. An Android Preview or DesignLab fixture is not accepted visual evidence
until rendered pixels and merged/unmerged semantics are inspected.

## First design hypothesis and comparison region

Hypothesis: replacing the logo-plus-five-icon top bar and hidden search FAB with
one board-context header plus directly visible search/filter controls will make
the current physical context and primary browse task understandable without
removing any destination.

The first comparison region is only the app bar through search/filter controls.
The list, cards, banners, FABs and navigation behavior remain unchanged during
that iteration. Baseline and candidate use `browser/content` with identical
locale, theme, width, font scale and fixture data. At most three pixel-based
correction rounds are allowed; Golden changes are never accepted automatically.

## Accessibility and performance budgets

- Minimum interactive target: 48 dp.
- WCAG 2.2 AA: 4.5:1 normal text; 3:1 large text and meaningful non-text UI.
- Color is never the only connection, session, filter, error or selection cue.
- Deterministic traversal: board context, active session if present, search,
  filters, result summary, results, then secondary actions.
- Loading, empty, error, offline, connected and active/resting session states
  must each have a text description and a reachable recovery/action.
- No clipping/overlap at German + 1.5 font scale; keyboard focus and back clear
  or exit search predictably.
- No repository creation, BLE work, polling, clock reads or random ordering in
  a renderer/Preview. Stable keys preserve list state.
- Before broad browser wiring, capture startup-to-first-content and
  browser-to-detail Macrobenchmark baselines in the reviewed benchmark spike.
  The header-only slice must add no startup I/O and no frame-by-frame work.

## Current evidence and blockers

- The route and implementation inventory confirms the browser start destination,
  onboarding gate, all top-level actions, empty/import states, pagination and
  session surfaces.
- The former raw `BoardBrowserState.error` gap is closed: query and pagination
  failures now use `BrowserIssue`, render distinct recovery surfaces, and keep
  already-loaded results usable.
- Existing unit tests cover filtering, board switches, pagination, random
  choice and catalogue revisions. The portable projection, addressable
  `browser/content`, `browser/empty` and `browser/error` fixtures, direct-search
  behavior, text-plus-icon connection state, initial/pagination retry behavior
  and 48 dp actions now have focused tests.
- The compact `browser/content`, `browser/empty`, and `browser/error` candidate
  matrix was rendered on the API-35 Nokia across EN/DE, light/dark, and
  font-scale 1.0/1.5. The current f2fe screenshots were opened and effective
  semantics parsed; critical content now starts below the status bar, all
  actions meet 48 dp, and EN/DE plus large text remain usable. Expanded width
  remains the only rendering gate for this matrix.
- Production now uses only the candidate's isolated board-context region and
  its active-session continue surface. A narrow production header host owns
  the Android-state projection, first-run absence and established BLE/filter
  callbacks without moving them into the renderer. Existing search/FAB
  behavior, hold search, map, filters, BLE/playlist status, management
  destinations and their established test tags remain in place. This
  deliberately stops short of the broader header replacement until that larger
  parity surface and Expanded pixels can be reviewed.
- The f2fe production host was inspected disconnected and connected to the
  Kilter simulator. It retained board/angle selection, search, filters,
  logbook, lists, settings, random, create and climb navigation, plus explicit
  connection and current-climb text. No active queue existed, so live continue
  pixels would require creating user state; its four current deterministic
  states are fully reviewed instead.
- The focused production sources compile. The browser context, portable mapper,
  production header host, active-session host and continue-card
  Robolectric/semantics tests pass using the writable SDK.
- Read-only device profiling found the default popularity query entering through
  `climbs(board_brand)` and taking 19.3 seconds on the 694k-climb catalogue.
  The source query now enters through the existing
  `idx_climb_stats_by_popularity` index while retaining every fit, grade, HSM,
  listing and pagination predicate. Query-only device probes were 0.50–0.54
  seconds; installed-artifact startup/scroll Macrobenchmark evidence remains
  required before treating that as an end-to-end result.
