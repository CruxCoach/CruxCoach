---
status: skeleton
---
# Feature Spec: Route Creation & Playback (v0.2.0+)

> **Status:** Skeleton — scope agreed, design open. Likely too
> large to ship inside the 0.2.0 window alongside FEAT-008/009/011;
> may split into 0.2.0 (playback / sending of imported routes) and
> 0.3.0 (authoring in the climb-creator). Decision deferred until
> the 0.2.0 contents are locked.
>
> **Depends on:**
> - FEAT-003 (Climb Creator) — extends the boulder-only authoring
>   path to multi-frame.
> - FEAT-005 (Aurora JSON Import) — incoming Aurora routes must be
>   preserved across import even before the creator supports
>   authoring them; today FEAT-005 either drops them or stores
>   them in a half-rendered state. Spec needs an import-side
>   verification once the data model is final.
>
> **Relates to:**
> - FEAT-009 (Difficulty Rating Engine) — per-(route, angle) grade
>   semantics carry over; no new engine work, but the engine must
>   not assume single-frame.
> - FEAT-011 (Setter Angle Visibility) — anchor angle is per-route,
>   not per-frame; same surfacing applies.

---

## 1. Overview

A Kilter Board route is a multi-frame climb: the LED pattern
changes as the climber progresses up the wall, presenting one
section of holds at a time instead of the entire boulder at once.
This enables routes that are physically longer than a single visible
hold-set (typically 5-10 holds per frame, 3-8 frames per route) and
introduces a different practice mode where the wall walks with you.

Today CruxCoach is boulder-only on three axes:
1. **Authoring**: the climb-creator (FEAT-003) accepts a single
   frame; multi-frame UI does not exist.
2. **Playback / sending**: the detail-screen send pipeline writes
   one `frames` string and disconnects (boulders) or keeps the
   connection alive (routes) — but for routes there is no UI to
   advance through frames, the connection just stays open.
3. **Data model & schema**: the `frames` field is a single string,
   not a list. Route-specific roles (42-45) are recognised by the
   parser but no surface uses them.

This spec covers all three to bring CruxCoach to feature parity
with hardware-supported route climbing.

### Goals

- **Author** multi-frame routes in the climb-creator with a
  visual frame-strip and per-frame editing.
- **Play back** routes on the connected board — one frame on the
  LEDs at a time, advance to the next on user trigger.
- **Persist** routes through the existing local DB, Nostr publish
  pipeline, and Aurora import path without lossy round-trips.
- **Validate** routes (minimum 2 frames, role-count rules per
  frame, hold-set continuity between adjacent frames).

### Non-goals

- Hold-tap auto-advance via board sensors (the LED-only Kilter
  Board has no hold-touch input — out of scope by hardware
  constraint).
- Choreography / time-based auto-advance. Frame transitions are
  user-triggered for v0.2.0+; auto-advance can be a follow-up if
  there's user demand.
- Re-importing routes that were dropped by an earlier version of
  FEAT-005. The migration is one-way: routes published / imported
  after this ships are full-fidelity, older drops stay dropped.

## 2. Data model

### 2.1 Multi-frame `frames` representation
The Aurora wire format already supports multi-frame as a delimited
string (frame separator: `~` based on Kilter parser conventions —
**verify via parser spike**). Each frame is itself a sequence of
`pXXXrYY` tokens.

Example route with 3 frames:
```
p1164r42p1185r43p1233r44~p1185r43p1233r44p1282r43p1392r44~p1282r43p1392r44p1450r45
```
Frame 1: route-start + early holds. Frame 2: middle. Frame 3:
route-finish.

### 2.2 Schema impact
- `BoardClimbParser`: extend `parseFrames` to return
  `List<List<BoardHold>>` instead of `List<BoardHold>`. The
  single-frame caller path stays via a `flatten()` helper or a
  variant (`parseSingleFrame`).
- `frames_hash`: hashed over the canonical concat of all frames
  with the separator preserved, so route identity differs from
  boulder identity even with overlapping holds.
- `is_route` derived flag on the climb row (`true` iff frame
  count ≥ 2 OR any role ∈ 42-45).

### 2.3 Route-specific roles (42-45)
| Role | Name           | Allowed in |
|------|----------------|------------|
| 42   | Route start    | routes only |
| 43   | Route hand     | routes only |
| 44   | Route finish   | routes only |
| 45   | Route foot     | routes only |

Validation: a frame containing role 42-45 must be part of a route
(`is_route = true`); a boulder must not contain any 42-45.

## 3. Climb Creator (authoring)

### 3.1 Frame strip
A horizontal strip below the board visualisation:

```
[Frame 1] [Frame 2] [+ Frame 3]   [Settings]
```

Tapping a frame chip selects it for editing. The board view
mirrors the selected frame. A `+ Frame N` chip appends a new empty
frame after the rightmost.

### 3.2 Per-frame editing
Same hold-tap-cycles-role interaction as the boulder editor, but
the role palette switches to route-roles (42-45) automatically
when the user has chosen "route" in the climb-type toggle (new UI
control — small segmented control near the climb-name field).

### 3.3 Reorder + delete
Long-press a frame chip → drag to reorder; swipe or
delete-icon to remove. Min 1 frame; deleting the last
remaining frame deletes the climb's route status (toggle back to
boulder).

### 3.4 BLE preview during authoring
The "live mirror" wiring from FEAT-003 (every applyEditor →
syncLeds) must respect the currently-selected frame: only the
selected frame's holds go to the board. Switching the selected
frame triggers a re-send.

### 3.5 Validation
- Min 2 frames.
- Each frame: ≥1 START role (12 or 42).
- Each frame: ≥1 FINISH role (15 or 44).
- Cross-frame continuity warning (not blocker): adjacent frames
  should share ≥1 hold so the climber has a transition path. UX
  shows a yellow hint, does not prevent publish.

## 4. Playback / detail-screen sending

### 4.1 Frame navigator
Below the route visualisation, a frame navigator strip identical
in shape to the editor's strip but read-only:

```
[1 / 3]  ← Prev   [Frame ●●○]   Next →   ⟲ Restart
```

Tapping `Next` advances to the next frame and writes it to the
board (same `sendClimb` path as boulder, just a different frames
list). `Prev` goes back. `Restart` returns to frame 1.

### 4.2 Auto-send on connect
On CONNECTED, frame 1 is sent automatically (matches boulder
behaviour). User then advances manually.

### 4.3 Connection lifecycle
Quick-Send macro must keep the connection alive for routes:
already implemented per the `isRoute` exemption in
`BleConnectionViewModel.startQuickSend`. New work: ensure the
auto-disconnect timer (`bleAutoDisconnectSeconds`) resets on each
frame send, not only the first.

### 4.4 Send-history per session
A session-scoped log of which frames were sent + when, so a user
sending the same route twice in 5 minutes doesn't lose context.
Out-of-band of this spec; tracked here for awareness.

## 5. Nostr publish + import

### 5.1 Kind-30078 climb event
Existing schema (FEAT-003) carries `["frames", "..."]` as a single
tag value. Routes use the same tag with the multi-frame string —
no schema bump. Add `["climb_type", "route"]` (or "boulder") for
explicit relay-side filtering.

### 5.2 Aurora import
FEAT-005 currently passes Aurora routes through the
single-frame parser, which either drops the route entirely or
keeps frame 1 only. Once multi-frame parsing lands (§2.2), the
import path stores the full multi-frame string and the climb is
correctly displayed.

Verification: import a known Aurora route, assert
`is_route=true` and frame count matches the source.

## 6. Open questions

- **Frame separator**: confirm `~` via parser spike, or whether
  Aurora uses a different delimiter. Single-source-of-truth doc
  in `shared/`.
- **`is_route` source**: derived from frame count? Or stored
  flag on the climb row? Storing avoids re-computation but adds a
  migration; deriving is cheaper.
- **Authoring split**: ship route playback in 0.2.0 (smaller
  scope, unblocks Aurora-imported routes), defer authoring to
  0.3.0? Decision deferred to release-planning.
- **Climb-type toggle UI**: where exactly in the creator (top
  bar? metadata section? automatic on first 42-45 role tap)?
- **Frame-strip horizontal scrolling**: small phones with 6+
  frames need horizontal scroll vs. vertical reflow.

## 7. Implementation sketch (post-spike)

- `shared/` parser: `parseFrames` → `List<List<BoardHold>>`.
- `frames_hash`: hash of canonical multi-frame concat.
- `androidApp/`:
  - Editor: `FrameStrip` composable, `selectedFrameIndex` state,
    role-palette switching on climb-type.
  - Detail: `FrameNavigator` composable, `currentFrame` state,
    `sendClimb` wired per frame.
  - BLE: idle-timer reset on every frame send (small change in
    `BoardBleConnection`).
- Tests:
  - JVM: `parseFrames` round-trip on multi-frame strings; routes
    fixture set covering 2-, 3-, 8-frame examples.
  - Maestro: author a 2-frame route, publish, re-open, advance
    Next, assert second frame's holds illuminate (logcat-PERF
    marker).
- i18n: `values/strings.xml` + `values-de/strings.xml` for
  "Frame", "Next", "Previous", "Add frame", "Route" /
  "Frame", "Weiter", "Zurück", "Frame hinzufügen", "Route".
