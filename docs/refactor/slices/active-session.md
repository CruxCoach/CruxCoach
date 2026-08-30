# UI slice: active board session surface

Status: deterministic Android candidate implemented; pixel review pending

Decision date: 2026-08-30

This slice defines the compact session surface shown from the board browser and
the focused active-attempt state reached from a climb. It does not redesign the
playlist player, mutate session persistence, change BLE ownership, or merge the
hidden general training planner with board sessions.

## Journey and user goal

1. A climber starts or joins a board session through an existing explicit action.
2. The browser remains available and never redirects into the session.
3. A single `Continue session` surface identifies the phase, current climb,
   elapsed/rest time, sends/attempts and connection state.
4. Tapping it returns to the appropriate active climb/player context.
5. During planned rest, the same surface becomes rest-first and shows the fixed
   countdown. When rest expires, the session resumes as active.
6. Ending remains a separate confirmed action and produces the existing session
   summary; browsing alone never ends or replaces the session.

The primary goal is to understand live state at a glance and return to the real
activity with one deliberate action.

## Functional and state parity

| Existing behavior | Requirement | Evidence/gate |
| --- | --- | --- |
| Board session elapsed/active/pause seconds and send/attempt counts | Preserve canonical values | mapper and manager tests |
| Manual pause vs planned rest | Preserve distinct phases; only planned rest exposes countdown | `ActiveSessionStateMapperTest` |
| Planned-rest expiry resumes the session | Preserve | `BoardSessionManagerRestTest` |
| Playlist mini-player current item, participants, resend, random/add and next | Preserve in playlist player/status area; do not silently fold into generic session model | existing playlist tests |
| Active board connection and current projected climb | Preserve | portable state plus BLE/queue journeys |
| Browser stays visible | Preserve; explicit continue only, no auto-navigation | navigation journey |
| End confirmation and summary | Preserve | current browser/player paths |
| Rest notification/banner | Preserve; avoid two competing primary countdowns on the browser | state/render test |
| General training planner | Keep hidden and separate | availability test and parity matrix |

No board-session table, playlist/share format, BLE frame, backup or notification
payload changes are part of this slice.

## Information hierarchy

1. Phase label: `Session`, `Paused` or `Rest`, expressed by text and icon.
2. Current climb name and angle when known; this is the active visual subject.
3. Rest countdown when resting, otherwise elapsed active context.
4. Compact sends/attempts and connection status.
5. One primary `Continue session` action. End, next, resend and queue management
   remain inside their established focused surfaces.

The compact browser surface must not become a dashboard card grid. Missing
current-climb data degrades to session totals without inventing a climb.

## State, tokens and semantics

- Consume `ActiveSessionState` directly. Do not recreate time or infer phase in
  Compose/SwiftUI.
- `ACTIVE`, `PAUSED` and `RESTING` are text-plus-icon states; color is secondary.
  Active uses semantic positive, paused/resting use semantic caution, and a
  disconnected board is explicitly labeled.
- Use CruxCoach spacing/shapes/type/color tokens only. Every action is at least
  48 dp; no permanent animation or per-frame work.
- The complete surface is one named button with a concise state description.
  Descendant metrics remain visible text but do not cause repetitive traversal.
- Fixed seconds are formatted by the platform locale without reading the clock.
  A future Live Activity consumes the same snapshot and owns its own timeline.
- At 1.5 font scale and compact width, current climb and metrics may wrap; the
  continue action must remain reachable and no text may clip.

## Deterministic scenarios

| Scenario | Fixed state |
| --- | --- |
| `session/active` | 30:00 elapsed, 28:00 active, 3 sends, 7 attempts, current `Quiet Riot` at 40°, connected |
| `session/resting` | same identity, planned rest with 75 seconds remaining |
| `session/paused` | manual pause, no rest countdown, disconnected |
| `session/active-no-climb` | active totals with no current climb |

The core matrix remains EN/DE, light/dark, compact/expanded and font scale
1.0/1.5 with fixed UTC inputs and disabled/nonessential animation.

## Design hypothesis and comparison region

Hypothesis: one phase-led, fully clickable continue surface will communicate
the live state more clearly than separate global rest, timer and playlist bands,
while preserving their focused controls behind the destination.

The first region is only that compact surface in isolation. Existing browser
banners and `BleStatusArea` remain unchanged until a rendered comparison proves
which information is duplicate. At most three pixel-based correction rounds;
Golden changes are never accepted automatically.

## Accessibility and performance budgets

- Minimum touch target: 48 dp; full surface has a button role and localized
  action/state label.
- WCAG 2.2 AA contrast, no color-only phase/connection state.
- Traversal reaches the surface once, then proceeds to browser search/results.
- German + 1.5 scale and current-climb absence must reflow without overlap.
- No clocks, flows, repositories or BLE objects in the renderer; inputs are
  immutable snapshots and callbacks.
- Formatting is O(1), allocation-bounded and adds no startup or list-row work.
- Browser/detail Macrobenchmark gates remain required before replacing existing
  live surfaces broadly.

## Current evidence and blockers

- `ActiveSessionState` is portable and contains phase, canonical seconds,
  counts, current climb and connection without Android/SwiftUI types.
- Android mapping distinguishes active, manual pause and planned rest; the rest
  expiry regression is covered.
- Addressable active, resting, paused and no-current-climb fixtures now render
  the same tokenized component. Focused tests cover phase/copy, fixed duration
  formatting, missing climb, explicit connection text and the 48 dp continue
  action.
- Existing playlist mini-player and global rest banner contain controls below
  48 dp. They are retained for parity and must be corrected or replaced only in
  focused, tested changesets.
- Pixel/semantics capture on a real renderer remains blocked: no ADB device or
  local AVD exists. A fixture component may proceed, but visual quality and
  duplicate-banner removal cannot be claimed before the documented ADB gate.
