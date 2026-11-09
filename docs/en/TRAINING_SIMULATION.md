# Training simulation

Synthetic athletes run day by day through the real training logic, and automatic checks look for
suggestions that are unsafe or implausible over weeks (issue #59). The rules themselves have their own unit
tests. This harness checks what comes out when they act together for months.

Code: `androidApp/src/test/java/com/cruxcoach/android/athlete/sim/`.

## How to run

- **Fast subset** (CI): six personas for three weeks each, about 30 s. It runs with the unit tests and fails
  on any safety or exercise-choice flag.

  ```
  ./gradlew :androidApp:testDebugUnitTest --tests 'com.cruxcoach.android.athlete.sim.TrainingSimulationTest'
  ```

- **Full run**: every persona for its whole run, reports only.

  ```
  SIM_FULL=1 ./gradlew :androidApp:testDebugUnitTest --tests 'com.cruxcoach.android.athlete.sim.TrainingSimulationTest'
  SIM_FULL=1 SIM_ONLY=pulley-injury,masters-55 ./gradlew …   # some personas only
  ```

  Reports are written to `androidApp/build/training-simulation/`: one Markdown file per persona and
  `overview.md`. Every run is reproducible: each persona has a fixed seed.

## What runs

Each persona starts in a fresh database with a simulated clock. Every day:

1. The athlete answers the morning check-in (seeded: an occasional bad night, sick days during an illness).
   Once a week they weigh in.
2. Events happen on their day: illness and holiday pauses, an injury (with its end), a trip with no
   equipment ("nothing here today").
3. Climbing days are logged by hand after the session (kind, minutes, intensity).
4. In the evening the athlete opens Today. The suggestion is built exactly as Today builds it:
   `TodayCoach` gathers the block, weekly volume, return ramp, preferences and climbing, and then
   `SessionSuggester` runs. The athlete sees it every day, including days they do not train.
   In the first two weeks, on a training day, they tap "Don't know it" on every Special exercise that is new
   to them, the way the card sends it (`SessionSuggester.familiarSwap`, then the ledger entry and the swap).
   Persona `picky` also taps "Another suggestion" (a ledger entry and the next variant) and swaps one exercise
   for one of the alternatives the card offers (`SessionSuggester.alternatives`, then the ledger entry and the
   swap).
5. On a training day (unless they skip) the athlete starts the suggestion (`AthleteService.startSuggestion`).
   They do every set in the player's order. Each set goes through `completeSetDetailed`, then
   `adjustUpcomingSets`, then the reserve answer and `adjustUpcomingSets` again, as the player sends them.
   The training ends with `finishWorkout`.
6. During a comeback plan (persona `pulley-comeback` and the other `comeback-*`) the athlete also follows the plan, through the same
   calls as the comeback screen and Today's card: they start it on the day of the injury
   (`ComebackService.start`, from stage 0, with the doctor's finding and their own reference values), log the
   morning pain on most days and the pressure pain twice a week, and answer the plan's prompts with the
   button the screen offers. At 9, 13, 16 and 21 o'clock they do every session Today lists as due
   (`Comeback.due`, `ComebackService.startSession`), set by set, finish it and report its highest pain
   (`ComebackService.logSession`). They climb no harder than the stage allows and end the plan when the last
   stage's week went well.

Nothing of the coach's rules is copied into the simulation. The logic that used to live in the Today screen
now lives in shared code (`TodayCoach`), so the app and the simulation run the same lines.

Not simulated yet: the board logbook from the app's own board (board climbers log their sessions as
climbing days), the athlete's own routine as the plan, a dismissed "not better" question, and the card's other buttons ("Not today",
"Leave it out", the kind and time menus). "Too easy", "Don't know it", "Another suggestion" and swaps are
simulated.

## The simulated athlete

The athlete is deliberately not the app's own model. The app has to find the truth from what the athlete
logs. When a planned load is far beyond them (not even half the planned reps or seconds), the athlete lowers
it by hand to what they believe gives the plan with two in reserve, and the player carries the change to the
sets after it, as in the app.

- **Qualities**: one true value per quality (fingers, pulling, pushing, legs, core, wrists and shoulders,
  power, mobility), in % body weight. For fingers it is the two-hand 10-second maximum on 20 mm; otherwise
  it is the 1RM of the total load. Every exercise reads its true capacity from its quality with a fixed
  seeded factor (0.9–1.1). Smaller edges hold less (√(edge/20 mm)). One arm holds about half, one leg about
  85 %.
- **Reps to failure** follow Brzycki (reps = 37 − 36 · load / 1RM; Brzycki 1993), not the app's Epley.
- **Holds**: the time held at a load relative to the 10-second maximum is t = 10 s · rel^(−1/0.12).
- **Fatigue**: each hard set (true reserve ≤ 4) costs its quality 3 % for the rest of the session. A bad
  night costs 5 %, illness 15 %.
- **Reserve answers**: the true reserve plus normal noise (SD 1 rep or second), rounded and capped at "3+",
  as the app asks. Reps-in-reserve ratings are accurate to about one rep near failure (Zourdos et al. 2016).
- **Adaptation**: a training with at least two hard sets of a quality raises it by its rate, less as it
  nears its ceiling (logistic). The rate is 1.5 % per session for beginners, 0.8 % for intermediates and
  0.4 % for advanced athletes. Fingers adapt at half that rate, because tendons and pulleys adapt slower than
  muscle (Magnusson & Kjaer 2019). Age 40–54 adapts at 0.8 ×, 55+ at 0.6 × (Fell & Williams 2008).
  Climbing counts as a smaller stimulus for fingers, pulling and power.
- **Detraining**: after 14 days without stimulus a quality loses 0.4 % a day, down to 85 % of its peak.
  Strength is largely kept for about three weeks (Mujika & Padilla 2000).
- An injury takes a share off the injured quality at once (pulley injury: fingers −25 %).

### The injured tissue

During a comeback plan the injured tissue has a truth of its own (`SimInjury`), apart from the athlete's
strength and apart from the plan's rules:

- **Tolerance**: the share of the hand's maximum before the injury it takes without pain. 15 % on the day of
  the injury, rising evenly to 100 % over seven weeks (pulley strain: easy climbing after about four weeks,
  full climbing after six to eight; Schöffl et al. 2003). A session that loads the hand within tolerance
  speeds healing a little; a session that hurts 6 or more and climbing above tolerance set it back.
  Other findings and regions take longer or shorter (rough mid values of the usual healing times): a
  pulley rupture 10 weeks, several pulleys 12, a tenosynovitis 6, pain on the inside or outside of the
  elbow 12, the ankle, the hamstring and the back 6, the shoulder, the wrist and the knee 8.
- **Pain** in a set: none up to 70 % of the tolerance, then one point per 10 % more, plus 0.4 × the
  irritation. Longer holds ask more of the hand (the same hold curve as above).
- **Irritation** starts at the injury's pain and fades by 7 % a day; painful sessions and climbing above
  tolerance add to it. The morning pain reads it, the pressure pain adds what has not healed yet.
- **The athlete** is careful and honest. They report the true highest pain of a session. They take the
  stage's range when it has one (from below: a fifth more per session at most), otherwise the planned load
  and their sense of effort. They go one step lighter after a session above the stage's green zone, and one
  step heavier at most once a week when it stayed green. They accept every proposal.
- **Region plans** (shoulder, elbow, wrist, knee, ankle, hamstring, back) have no reference values: the
  athlete works at the effort an item asks for (holds about 4–5 of 10, 15 repetitions about 5, fewer about
  7, mobility 2) of what the region can do today, which is a little more than it takes without pain
  (tolerance + 25 %). Every item that is not for the healthy side loads the injured spot. A climbing day
  reaches the hip, the knee and the ankle with 60 % of what it asks of the hands.
- **Habits** (`ComebackHabits`, personas `comeback-*`): the same athlete, but skipping a share of the due
  sessions, climbing one level harder than the stage allows on a share of the climbing days, keeping the stage
  when the plan proposes a step back or asks about a worsening, or starting the plan later and at a later
  stage. The careful athlete above has none of them.

## Personas

| Id | Who |
|---|---|
| `beginner-board` | Under a year, board three times a week, no other training |
| `home-hangboard` | 3–5 years, hangboard, bar and dumbbells at home, gym bouldering twice |
| `advanced-taper` | Over five years, board and pick-up block, a trip date in week 9 (taper) |
| `rope-outdoor` | Rope and outdoor climbing logged by hand, bar and bands at home |
| `masters-55` | 55+, in pounds |
| `youth-15` | Under 16, gym bouldering three times |
| `pulley-injury` | A finger pulley injury in week 3, over in week 8 |
| `pulley-comeback` | A pulley strain of the right hand in week 2, then the comeback plan until the athlete ends it |
| `ill-and-away` | A week of illness, then three weeks of holiday |
| `gym-no-board` | Gym only, then two weeks on a trip without equipment |
| `skipper` | Plans four sessions a week and skips about half |
| `always-easy` | Answers "3+" in reserve every time and taps "too easy" on the card |
| `beginner-home-lb` | Beginner at home in pounds with 2.5 lb steps |
| `open-home` | 3–5 years at home with hangboard, bar, dumbbells and bands; chose "Open to unusual exercises" |
| `home-classics` | `home-hangboard` with "Mostly classics" |
| `new-no-setup` | A new user who answers none of the coach setup; a pull-up bar and a mat |
| `picky` | `home-hangboard`, but taps "Another suggestion" on about a third of the training days and swaps one exercise for one of its alternatives on about four in ten |
| `comeback-skipper` | `pulley-comeback`, but skips about half of the plan's sessions and keeps the stage when the plan proposes a step back |
| `comeback-eager` | `pulley-comeback`, but on half of the climbing days the stage allows climbs one level harder |
| `comeback-late` | `pulley-comeback`, but starts the plan two weeks after the injury, at stage 2 |
| `comeback-rupture` | `pulley-comeback`, but a pulley rupture (the plan's pain rule is 1 in its first two stages) |
| `comeback-tenosynovitis` | `pulley-comeback`, but a tenosynovitis (the plan's overuse pain rule) |
| `comeback-shoulder` | `pulley-comeback`, but a rotator cuff problem of the right shoulder and its plan |
| `comeback-elbow` | `pulley-comeback`, but pain on the inside of the right elbow and its plan |
| `comeback-wrist` | `pulley-comeback`, but pain on the little-finger side of the right wrist and its plan |
| `comeback-knee` | `pulley-comeback`, but a strain on the inside of the right knee and its plan |
| `comeback-ankle` | `pulley-comeback`, but a sprained right ankle and its plan |
| `comeback-hamstring` | `pulley-comeback`, but a hamstring strain and its plan |
| `comeback-back` | `pulley-comeback`, but low back pain and its plan |

## Checks and thresholds

The checks read only what the athlete was shown and did. None of them calls the coach's rules, so a rule
that misfires is caught instead of confirmed.

| Group | Check | Threshold and source |
|---|---|---|
| Safety | No max finger work (loaded strength hangs, campus) during an open finger injury, the return ramp, or for young or first-year climbers | Growing climbers risk finger growth-plate injuries (Morrison & Schöffl 2007; Schöffl et al. 2015) |
| Safety | No max finger work within 48 h after a hard or limit climbing day (72 h at 55+) | Hörst 2016; longer recovery with age (Fell & Williams 2008) |
| Safety | No suggested exercise that lists an open injury's region as a contraindication (injuries of pain ≥ 4), unless a comeback plan has reached the stage from which training outside the plan may load the spot again (fingers stage 5, other regions stage 3) | The comeback plan's own stages (owner 2026-10-20) |
| Safety | Climbers under 18: no campus, no one-arm or max finger work | Morrison & Schöffl 2007 |
| Safety | 55+: max finger sessions at least 72 h apart | Fell & Williams 2008 |
| Safety | Comeback plan: the planned load of the injured hand stays within the stage's range of the athlete's reference (one step for rounding); where the stage asks for an effort instead, it feels at most 1.5 × that effort | The stage's own percentages and effort |
| Safety | Comeback plan: the next stage is offered only after a week in which no session truly hurt beyond the stage's amber limit and no morning reached 5 | Pain-monitoring model (Silbernagel et al. 2007) |
| Safety | Comeback plan: no more sessions of a kind a day than the stage plans; strength sessions at least 46 h apart (the stage asks for 48 h of rest) | The stage's own plan |
| Safety | Comeback plan: no board session while the stage allows less than hard climbing | The stage's own climbing |
| Safety | Comeback plan: after two sessions within a week that truly hurt beyond the stage's amber limit, the plan proposes a step back or a repeat, or asks about the worsening, within a week | Pain-monitoring model (Silbernagel et al. 2007) |
| Load | The first planned set of an exercise rises by at most one weight step or 10 % of the total load from one session to the next, counted from the higher of last time's plan and the heaviest load done. More is allowed after a session in which every work set had 3+ in reserve, but below 10 kg never more than one step | ACSM 2009: 2–10 %; owner 2026-11-06 |
| Load | No max finger work while the finger load of the last 7 days is above 1.5 × its 28-day normal, from week 5 on (the athlete's climbing makes the load; the coach must not add to a spike) | Gabbett 2016 (acute:chronic ratio) |
| Load | After six weeks the app's value of an exercise trained in at least four sessions is within 20 % of the truth | – |
| Progress | No loaded or body-weight strength exercise is planned the same way in five sessions over four weeks or more | – |
| Progress | A comeback stage that stayed within its pain rule for its last two weeks has offered the next stage within three weeks | – |
| Progress | The next comeback stage is offered only after a week with comeback sessions on at least three days | The stage's own sessions |
| Progress | A run of six weeks or more has a lighter phase, deload trainings have at most 85 % of the work sets of build trainings, and the ten days before a target date taper | Bompa & Haff 2009 (3:1 loading); Bosquet et al. 2007 (taper of 8–14 days, volume −41–60 %) |
| Plan | In weeks trained as planned, without an injury or return ramp and in one block phase, each area (mobility aside) reaches at least half of its weekly goal and at most 1.25 × its maximum | Schoenfeld et al. 2017 (weekly sets) |
| Plan | A suggested training lasts at most 1.3 × the chosen length (+5 min) | – |
| Plan | No full training is suggested once the week's training days (climbing included) are used up, and "training days done" is not said while days are left | – |
| Plan | At least six different exercises in every eight full trainings (the block after climbing is meant to repeat) | – |
| Stacking | A suggested training has at least three work sets and 12 minutes | – |
| Choice | Beginners (personas of the beginner level), weeks 1–4: no Special exercise they do not know yet | Novices start with simple exercises and proper technique; complexity grows with training status (ACSM 2009) |
| Choice | Beginners, weeks 1–4: at least 70 % of the main exercises (warm-ups and mobility aside) are Basic or known to them; with "Mostly classics" 80 % outside the finger work, for the whole run, and no Special exercise they do not know | ACSM 2009; the shares are the values of the proposal in issue #68 |
| Choice | A training holds at most one exercise new to the athlete (two with "Open to unusual exercises"); warm-ups and mobility count only when Special. More, where nothing Basic or known of the same kind fits the equipment, is reported under Info and not counted | One new skill at a time keeps the session's attention on the work (ACSM 2009); the limits are those of issue #68 |
| Choice | An advanced athlete still gets variety: at least 30 % of the main exercises are Common or Special (and the variety check above) | Issue #68 |
| Choice | "Open to unusual exercises": a Special exercise in at least half of all two-week stretches | Issue #68 |
| Choice | "Don't know it": the replacement is Basic or known in at least 95 % of the taps, the tap changes nothing in the learned preference for the exercise and does not make it rest, and the exercise does not come back as a new pick within 14 days | Issue #68 |

## Sources

- ACSM (2009). Progression models in resistance training for healthy adults. *Medicine & Science in Sports &
  Exercise*, 41(3), 687–708.
- Bompa, T. O., & Haff, G. G. (2009). *Periodization: Theory and Methodology of Training* (5th ed.). Human
  Kinetics.
- Bosquet, L., Montpetit, J., Arvisais, D., & Mujika, I. (2007). Effects of tapering on performance: a
  meta-analysis. *Medicine & Science in Sports & Exercise*, 39(8), 1358–1365.
- Brzycki, M. (1993). Strength testing – predicting a one-rep max from reps-to-fatigue. *Journal of Physical
  Education, Recreation & Dance*, 64(1), 88–90.
- Fell, J., & Williams, D. (2008). The effect of aging on skeletal-muscle recovery from exercise. *Journal of
  Aging and Physical Activity*, 16(1), 97–115.
- Gabbett, T. J. (2016). The training–injury prevention paradox: should athletes be training smarter and
  harder? *British Journal of Sports Medicine*, 50(5), 273–280.
- Hörst, E. J. (2016). *Training for Climbing* (3rd ed.). Falcon Guides.
- Magnusson, S. P., & Kjaer, M. (2019). The impact of loading, unloading, ageing and injury on the human
  tendon. *The Journal of Physiology*, 597(5), 1283–1298.
- Morrison, A. B., & Schöffl, V. R. (2007). Physiological responses to rock climbing in young climbers.
  *British Journal of Sports Medicine*, 41(12), 852–861.
- Mujika, I., & Padilla, S. (2000). Detraining: loss of training-induced physiological and performance
  adaptations. Part I. *Sports Medicine*, 30(2), 79–87.
- Schoenfeld, B. J., Ogborn, D., & Krieger, J. W. (2017). Dose-response relationship between weekly
  resistance training volume and increases in muscle mass. *Journal of Sports Sciences*, 35(11), 1073–1082.
- Schöffl, V., Hochholzer, T., Winkelmann, H. P., & Strecker, W. (2003). Pulley injuries in rock climbers.
  *Wilderness & Environmental Medicine*, 14(2), 94–100.
- Schöffl, V., Popp, D., Küpper, T., & Schöffl, I. (2015). Injury trends in rock climbers: evaluation of a
  case series of 911 injuries between 2009 and 2012. *Wilderness & Environmental Medicine*, 26(1), 62–67.
- Silbernagel, K. G., Thomeé, R., Eriksson, B. I., & Karlsson, J. (2007). Continued sports activity, using a
  pain-monitoring model, during rehabilitation in patients with Achilles tendinopathy. *The American Journal
  of Sports Medicine*, 35(6), 897–906.
- Zourdos, M. C., et al. (2016). Novel resistance training-specific rating of perceived exertion scale
  measuring repetitions in reserve. *Journal of Strength and Conditioning Research*, 30(1), 267–275.
