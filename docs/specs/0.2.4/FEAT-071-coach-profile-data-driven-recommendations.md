---
status: implemented-on-branch
queue: active
base: main@b5bef5163 (0.2.3)
branch: feat/0.2.4-training-nutrition (training line feat/0.2.4-training-body, merged with nutrition on 2026-10-07)
depends_on: [FEAT-066, FEAT-067, FEAT-070]
created: 2026-10-05
---

# Feature Spec: Coach profile, start values and data-driven recommendations (0.2.4)

> Owner request 2026-10-05: ask training preferences pleasantly and optionally,
> first values too; make every recommendation use all historical climbing and
> training data; at least MCI quality, optimised for climbing. "All questions
> are okay as long as they are stored locally only"; grade ↔ finger-strength
> relations must be visible and well visualised.
> Concept: `research/cruxcoach-0.2.4-training-body-nutrition-2026-10-03/D-coach-setup-and-recommendations.md`.

## 1. Data (all local, encrypted, in the backup)

- `AthleteProfile.coach` (`CoachProfile`, profile JSON, no migration): goal,
  target grade / project / date, climbing weekdays, training days per week,
  add-on after climbing, climbing contexts, experience band, age band,
  current/flash/rope grade, focus areas, finger preference, intensity and
  variety style, setup state, block start, Health Connect and force-gauge
  switches. `AthleteProfile.excludedExercises` ("never suggest").
- Athlete DB schema 3 (migration `2.sqm`): `climbing_day` (climbing outside
  the board app, manual or Health Connect, deduplicated by external id) and
  `suggestion_event` (recommendation ledger: shown, started, edited, saved,
  another one + reason, completed share, excluded, swapped, skipped set).
- `BenchmarkSource.ESTIMATE` for quick self-estimates.

## 2. Asking

- Coach setup on first visit to Training: four optional cards (goal, week,
  climbing & experience, what suits you), each skippable, prefilled from the
  board logbook (`LogbookSummaries`: working/flash grade, rhythm, usual
  climbing days) and the settings profile; answers apply at once.
- Start values: learn during training (default, "being learned 1/2"), a
  quick estimate (pull-ups, 20 mm hang, one-arm pick-up) with a plausibility
  hint against the logbook grade, or the guided test session.
- Just-in-time questions: reason chips on "another suggestion", "never
  suggest" per exercise, week-plan proposal from the real rhythm.

## 3. Using all data

- Climbing load from the logbook: each climbing day classified relative to
  the athlete's own level (light / volume / hard / limit), load units per
  structure (finger, skin, shoulder) from climbing, manual climbing days and
  off-wall sets; acute (7 d) vs chronic (28 d) trend; recovery hours by
  intensity and age band.
- Daily suggestion uses intensity-based recovery, load trend, coach answers
  (focus, finger preference, style, goal, add-on after climbing), guardrails
  (youth, little experience, masters), learned affinities, exclusions and the
  training block (intro / build / deload, taper before a target date); every
  suggestion lists what it was based on and how confident it is.
- Set prescriptions follow today's readiness and same-day climbing.
- Progression over several sessions: stall → variation, decline → deload,
  too easy / too hard twice → swap to the chain partner.
- Board days: "create board session" presets the playlist generator from
  block phase and focus.
- Health Connect (optional): reads last night's sleep as a check-in hint and
  climbing sessions as climbing days. The same layer and settings card also
  carry nutrition's optional export of meals and water (FEAT-069); each
  direction has its own switch and permissions. A climbing day's rated
  intensity also sets that day's carbohydrate target (FEAT-068).
- Force gauge (experimental): Tindeq Progressor live force and max-pull test
  as a test value.

## 3b. MCI-parity round (2026-10-06)

- Weekly volume plan (`WeeklyVolume`): targets per area (finger, pull, push,
  legs, core, antagonist, mobility) in hard sets per ISO week, scaled by goal,
  focus, experience, age, block phase and training days; board days credit
  finger and pull work; the suggestion fills the largest remaining deficit.
- In-session autoregulation (`SetAutoregulation`): the remaining sets of a
  block follow the last set (missed reps, RIR 0, big reserve, a hang let go
  early via the "let go" button).
- Pain stop in the player: body-map region, side, pain 0–9, swap / lighter /
  end exercise, optional injury; medical-advice line.
- Re-entry (`ReturnToTraining`): ≥ 10 days off → one week at 70 % sets, no
  max finger; ≥ 21 days or illness ≥ 7 days → two weeks (60 %, 80 %); healed
  finger injury → four weeks of light finger work; applied in every
  suggestion path, board sessions become volume.
- Training reminders (`TrainingReminders`) on planned days; week view in the
  Workouts tab (planned / done / missed / paused).
- Own body map (front/back) with primary/secondary areas for every catalogue
  exercise, grip pictograms with edge depth, thumbnails; reward header after
  training; haptics.
- Today: one hero card, compact check-in, fixed quick-action row.

## 4. Visualisation

Climber profile (Stats): grade ↔ finger strength and grade ↔ pull strength
position charts with an orientation band (`GradeStrengthNorms`, own smooth
line through published ranges, large spread, never a goal), the athlete's
own path over time, working grade and finger strength on one time axis with
Pearson r once enough weeks pair up, flash ↔ max gap, attempts per send,
acute vs chronic load per structure, bottleneck bars with reasons.

## 5. Tests

Shared: ClimbingLoad, PerformanceProfile, CoachLogic, TrainingBlocks,
PreferenceLearning, ReadinessModifiers, progression trend, SessionSuggester
rules. Android: Progressor frame parser, Health Connect mapping, smoke tests
for the new screens, athlete DB migration 2 → 3 on device.
