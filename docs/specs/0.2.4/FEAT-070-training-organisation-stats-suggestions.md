---
status: implemented-on-branch
queue: active
base: main@b5bef5163 (0.2.3)
branch: feat/0.2.4-training-nutrition (training line feat/0.2.4-training-body, merged with nutrition on 2026-10-07)
depends_on: [FEAT-066, FEAT-067]
created: 2026-10-04
---

# Feature Spec: Training organisation, stats, own workouts, daily suggestion (0.2.4)

> Owner request 2026-10-04: "Where do I see my stats? Charts per exercise and
> for body weight, better organisation, favourites that are easy to plan into
> workouts, own standard workouts, and suggestions like MCI."

## 1. Organisation

- One main-menu entry **Training** (replaces "Heute" and "Übungen").
- Inside it a bottom tab bar: **Heute · Workouts · Übungen · Statistik · Körper**.
  Tabs keep their state (`popUpTo(TODAY){saveState}`, `restoreState`); back from
  a tab leaves the training area. Detail screens (exercise, player, editor,
  week plan, settings, fueling, injuries) open above the tabs without a tab bar.

## 2. Stats (`ProgressStats`, shared)

- Hub: this week vs. goal, training days and new bests in range
  (4 wk / 12 wk / 6 mo / 1 yr), days and minutes per week split board vs.
  off-wall, 16-week calendar heatmap (intensity 0–3), load days per structure
  (finger, shoulder, elbow, skin; board days count for the climbing domains),
  body-weight trend, strength-to-weight (% BW of key strength exercises), and
  every trained exercise (warm-up-only exercises left out) with a sparkline.
- Per exercise: best, last, change over 4 weeks, sessions, % body weight;
  capacity over time (e1RM, 10-s max, max reps or max time, per hand for
  one-sided work, current performance value as reference), volume per session,
  bests, all sessions. A progress card on every exercise page links here.

## 3. Favourites and own workouts

- Favourites: star on every row and page; a "Favoriten" section tops the
  library (category chips apply, safety/equipment filters do not hide them).
- "Zu Workout": add to the running training, start a training with it, add to
  one of the own workouts, or create a workout with it.
- Workouts tab: own routines (start in guided mode, edit, duplicate, delete),
  starter routines to copy, history link.
- Editor: name, notes, exercises from a multi-select picker (favourites first,
  search, categories), per item sets, reps or time, rest, edge, side
  (both / left only / right only), warm-up sets, order.
- Week plan (`AthleteProfile.weekPlan`: ISO weekday → routine id, `board` or
  `rest`; unset = free) and `sessionMinutes` for suggestions. Stored in the
  profile JSON, no schema change.

## 4. Daily suggestion (`SessionSuggester`, shared)

Rules in order: sick/rest check-in → week plan (rest, board, or the planned
routine, injury-filtered; a planned board day becomes off-wall work while
climbing is paused) → injury with climbing paused (only OK or healthy-side-only
exercises) → finger load in the last 48 h, tired fingers or low skin → finger
strength, otherwise pull/push vs. legs/core by what was trained longer ago.
Exercises per slot are scored by favourites, available performance values,
variety, goal and level, one per progression chain, fitted to the preferred
duration (sets first, then trailing slots; warm-up kept). Every suggestion
shows its reasons. Actions: start (guided), another one (seeded variation),
adapt (opens the editor), save as workout.

## 5. Tests

`ProgressStatsTest`, `SessionSuggesterTest` (shared); Robolectric smoke tests
for the hub, exercise stats, workouts tab, editor, week plan, suggestion card,
library favourites under a one-hand finger injury. Device E2E session 3 in the
run log.
