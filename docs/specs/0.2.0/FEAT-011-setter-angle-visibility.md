---
status: skeleton
---
# Feature Spec: Setter Angle Visibility (v0.2.0)

> **Status:** Skeleton — UX problem and rough surfaces identified.
> Final design pending a small data-source spike (§2) and visual
> alignment with FEAT-009's confidence/origin treatment so the
> anchor row coexists cleanly with the rating engine's per-angle
> rendering.
>
> **Depends on:** None at spec level. Touches the detail screen,
> the browser card, and the per-angle picker — all in
> `androidApp/src/main/java/com/cruxcoach/android/ui/board/`.
>
> **Relates to:** FEAT-009 (Difficulty Rating Engine) — both render
> the per-angle stats row; the visual treatment must compose, not
> compete. FEAT-008 (Kilter Own-Climb Import) — imports must
> preserve the anchor angle alongside the rest of the climb row.

---

## 1. Overview

A Kilter Board climb's holds are set at a single specific wall
angle. The same hold sequence climbs very differently at 25° vs
40° — sometimes drastically. The setter's chosen angle is
high-signal information for the user picking a climb to try, or
interpreting a difficulty:

- **Setter intent**: the angle the setter sent at is the canonical
  expression of the climb. Users who want the "intended" version
  of a climb match that angle.
- **Difficulty calibration**: a V5 set at 25° and a V5 set at 40°
  are effectively different climbs. Without the anchor visible,
  users self-calibrate from the per-angle stats list, which
  conflates community votes across angles.
- **Logging accuracy**: matching the angle to the setter's anchor
  reduces user-side guesswork when self-grading or comparing sends
  with friends.

Today CruxCoach surfaces the per-angle ascensionist + difficulty
aggregates in `climb_stats` but does not visually distinguish the
setter's anchor angle from a purely community-derived row.

### Goals

- Make the setter's anchor angle visible at a glance on **detail
  screen** and **browser card**.
- Emphasise the anchor row in the per-angle stats list (badge,
  icon, or row-level highlight — exact treatment in §3).
- Default the angle picker on first open to the setter's anchor
  when the user has not yet expressed a preference.

### Non-goals

- Re-grading / multi-angle propagation (FEAT-009 owns that).
- Multi-anchor climbs. We treat each climb as having one canonical
  anchor; if a setter publishes at multiple angles intentionally,
  the earliest / lowest-angle row wins. Edge case, not common.
- Editing the anchor for community climbs. The anchor is set at
  publish time and immutable thereafter.

## 2. Data source — open question (spike, ~1-2 h)

The Aurora-shaped `climb_stats` table holds per-(climb, angle)
aggregates but does not flag the setter's anchor explicitly.
Plausible candidates to be validated against ~50 known
ground-truth climbs (climbs where the setter's intended angle is
known from community context):

- **`climb_stats.is_listed = 1`** — Kilter uses this flag to mark
  angles the setter assigned a grade to. If exactly one row per
  climb carries it, that's the anchor.
- **First-vote / created-at ordering** — the chronologically
  earliest row in `climb_stats` is typically the setter's own
  first send.
- **`climbs.angle` field** — some Aurora rows carry an `angle`
  column on `climbs` itself; if populated, it is the anchor.

Output of the spike: one heuristic chosen, plus a short
`SetterAngleResolver` document under `shared/` describing it.

## 3. UX surfaces

Final visual design TBD pending FEAT-009 alignment, but the rough
treatment per surface:

### Detail screen header
Under the climb name, alongside the existing grade + ascensionist
badges:

```
"Open Project"      ★ 7c+    Ø 42 sends
                    [ Set at 35° ]
```

A subdued chip with a "wall-tilt" glyph + the anchor angle.

### Browser card
Append the anchor angle to the second metadata line:

```
"Open Project" — 7c+ · 42 sends · 35°
```

### Per-angle stats list (the angle picker / detail-sheet rows)
Highlight the anchor row visually so it stands out from the
community-vote rows. Options:
- "Setter" tag in the row's right-edge metadata column
- Emphasised border or a subtle icon prefix
- Picked treatment must coexist with FEAT-009's
  confidence-indicator badge so a row can carry both without
  visual collision.

### Climb-creator
For climbs the user is **authoring** (FEAT-003), the anchor is
whatever angle the wall is at when they publish. No new UI needed
here today — but worth surfacing the chosen-anchor display once
this spec ships, so the creator sees what they are about to lock
in. (Tracked here, implementation deferred until creator-side
priorities allow.)

## 4. Default-angle behaviour

When the detail screen opens for a climb the user has not viewed
before:
- If there is a global user preference (Settings → preferred
  default angle), use it.
- Otherwise, **select the setter's anchor**.
- Today the implicit default is the highest-attempt angle, which
  reflects community popularity rather than setter intent.

The change is one-way: once the user manually picks any angle on
that climb, the picked angle becomes sticky for subsequent opens.

## 5. Implementation sketch

- Spike (§2): pick the anchor heuristic.
- `shared/`: derive `setter_angle: Int?` on the
  `climb_browse` view (or whichever shared row representation
  feeds the browser).
- `ClimbBrowseRow` + detail-screen view-models read it.
- Compose: a small reusable `SetterAngleBadge` composable so the
  detail-header and browser card stay visually consistent.
- Default-angle wiring: `BoardClimbDetailViewModel` reads
  `setter_angle` when no user-preference / per-climb override is
  set.
- Maestro: pick a community climb known to be set at 35°, assert
  badge displays 35°, assert the per-angle picker defaults to
  35° on first open.
- JVM unit test for the `SetterAngleResolver` against a fixture of
  rows representing each candidate heuristic.

## 6. Open questions

- Heuristic choice (spike outcome).
- Final visual treatment of the anchor row alongside FEAT-009's
  confidence/origin indicator.
- Whether the climb-creator should surface the in-flight anchor
  during authoring, or whether that lands in a follow-up.
- Localisation: "Set at 35°" — needs a German string that's not
  longer than the English (constrained by browser-card width).
  Candidates: "Gesetzt bei 35°", "Setter: 35°".
