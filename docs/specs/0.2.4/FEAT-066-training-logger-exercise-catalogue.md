---
status: implemented-on-branch
queue: active
base: main@b5bef5163 (0.2.3)
branch: feat/0.2.4-training-nutrition (training line feat/0.2.4-training-body, merged with nutrition on 2026-10-07)
depends_on: []
created: 2026-10-04
---

# Feature Spec: Off-board training, exercise catalogue v2, injury mode (0.2.4)

> Owner request 2026-10-03/04: "many basic exercises" for 0.2.4, everything
> from MCI that is cool for CruxCoach — redesigned for climbers — and the case
> of an injured climber who only does one-arm pick-ups and pull-ups, no
> climbing. Research: `~/projects/research/cruxcoach-0.2.4-training-body-nutrition-2026-10-03/`.

## 1. Starting point (0.2.3)

The 0.1.x training surface existed but was unreachable (empty bottom bar), the
60-exercise library was never seeded, finished workouts discarded their
exercises, and there was no per-set model. 0.2.4 keeps that code hidden and
builds a new, smaller and complete path next to it.

## 2. Data

- **AthleteDatabase** — a third SQLDelight database, encrypted with SQLCipher,
  one file per Nostr identity (`cruxcoach_athlete_<pubkey16>.db`, key: HKDF
  label `cruxcoach-athlete-db`). Own schema line, so it never competes with
  SecureDatabase migration numbers (the Marmot line holds 16–33), and 0.2.3
  ignores the file (no downgrade crash). Opened lazily on first use.
- `workout`, `exercise_set` (target_* = prescription, plain columns = what was
  done, `completed_at` turns plan into result; planned rows survive process
  death), `routine` (items as JSON), `custom_exercise`, `exercise_favorite`.
- Secure DB gets read-only day aggregates (`TrainingDays.sq`, queries only) so
  board sessions/sends/attempts count as climbing days automatically.

## 3. Catalogue v2

`androidApp/src/main/assets/training/exercise_catalog.json`, 199 exercises in
11 categories, original German/English text (why, steps, cues, mistakes),
stable slugs, kind (REPS, LOAD_REPS, TIME, HANG, INTERVAL, CLIMB), load mode
(NONE, BODYWEIGHT, BODYWEIGHT_PLUS incl. negative = assisted, EXTERNAL for
pick-ups and free weights), unilateral flag, equipment (with substitutes:
kettlebell loads a block like plates), load domains (finger, wrist, elbow,
shoulder, skin, legs, back, systemic), contraindications, progression chains.
No third-party text or media; wger was used only as a reference for coverage.

## 4. Behaviour (MCI ideas, climber version)

| MCI idea | CruxCoach |
|---|---|
| target vs result sets, RIR | target_* columns, RIR chips, ghost values = last session |
| progression chains, too easy/hard | chain stepper; double progression → "+step" or harder/easier variant |
| equipment profiles, real increments | "What do you have?" presets, smallest weight step |
| warm-up sets | % ramp of the effective load; assisted hangs when needed |
| rest timer, side switch | shared board rest timer (AlarmManager); hang/repeater timer |
| strength test, e1RM | baseline routine (max hang, pull-ups, hollow), Epley e1RM, % body weight |
| pain options | injury mode: hide/mark by domain + contraindication, healthy side kept for unilateral work, wall paused |
| wellness check-in | sleep, energy, skin, fingers, ill → GO/ADAPT/REST + deciding factor |
| sick days | pauses (illness, injury, holiday) freeze the streak |
| streak + jokers | weekly goal of training days, board days count, 1 joker per 4 good weeks (max 2) |
| weekly report | weekly review: bests, load per structure, trends |

Not taken: avatars/"trafo" forecast, social comparison, prize challenges,
MCI texts/data/media.

## 5. Safety

Injury mode is a filter, never a treatment plan; disclaimer and red flags in
the UI. Load-jump information per structure is shown as information, not a ban.

## 6. Tests

`AthleteLogicTest` (shared), `ExerciseCatalogAssetTest`, `AthleteRepositoryTest`,
`BoardSessionRestTimerTest` (M-097), backup preview/validation tests.

## 7. Performance values and guided mode (owner request 2026-10-04)

- `exercise_benchmark` (athlete migration 1→2): per exercise, side, edge and
  grip; sources MANUAL, TEST, AUTO. Capacity = e1RM of the total load (loaded
  reps), 10-s maximum (hangs/pick-ups; other hold times converted with a
  monotone hold curve), max reps, max seconds.
- Prescription: loaded reps at RIR 2 from the e1RM, max hangs 90 % (density
  ≥ 20 s: 85 %) of what can be held for the planned time, repeaters 65 % of the
  10-s maximum (borrowed from the two-arm max hang on the same edge when they
  have no value), bodyweight reps and holds 70 % of the maximum; snapped to the
  smallest weight step. The prescription replaces last time's load.
- Learning: the first completed work set sets a value; a set implying > 2 %
  more raises it; a test may set it lower (a fresh test is the truth).
- Guided player (default for routines): set view with inline timers, own rest
  screen (ring, ±15 s, side switch, next set, RIR question), ordered writes.

## 8. Open

Plan engine (FEAT-040) on top of target/result; Tindeq/WH-C06 live force and
a critical-force test (0.3); routines over Nostr; iOS UI.
