# Training simulation

Synthetic athletes run day by day through the real training logic, and automatic checks look for
suggestions that are unsafe or implausible over weeks (issue #59). The rules themselves have their own unit
tests. This harness checks what comes out when they act together for months.

Code: `androidApp/src/test/java/com/cruxcoach/android/athlete/sim/`.

## How to run

- **Fast subset** (CI): three personas for three weeks each, about 15 s. It runs with the unit tests and fails
  on any safety flag.

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
5. On a training day (unless they skip) the athlete starts the suggestion (`AthleteService.startSuggestion`).
   They do every set in the player's order. Each set goes through `completeSetDetailed`, then
   `adjustUpcomingSets`, then the reserve answer and `adjustUpcomingSets` again, as the player sends them.
   The training ends with `finishWorkout`.

Nothing of the coach's rules is copied into the simulation. The logic that used to live in the Today screen
now lives in shared code (`TodayCoach`), so the app and the simulation run the same lines.

Not simulated yet: the board logbook from the app's own board (board climbers log their sessions as
climbing days), the comeback plan's own sessions (the injury persona checks the injury filter and the return
ramp after the injury), and swaps or "another suggestion" on the card, except "too easy".

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
| `ill-and-away` | A week of illness, then three weeks of holiday |
| `gym-no-board` | Gym only, then two weeks on a trip without equipment |
| `skipper` | Plans four sessions a week and skips about half |
| `always-easy` | Answers "3+" in reserve every time and taps "too easy" on the card |
| `beginner-home-lb` | Beginner at home in pounds with 2.5 lb steps |

## Checks and thresholds

The checks read only what the athlete was shown and did. None of them calls the coach's rules, so a rule
that misfires is caught instead of confirmed.

| Group | Check | Threshold and source |
|---|---|---|
| Safety | No max finger work (loaded strength hangs, campus) during an open finger injury, the return ramp, or for young or first-year climbers | Growing climbers risk finger growth-plate injuries (Morrison & Schöffl 2007; Schöffl et al. 2015) |
| Safety | No max finger work within 48 h after a hard or limit climbing day (72 h at 55+) | Hörst 2016; longer recovery with age (Fell & Williams 2008) |
| Safety | No suggested exercise that lists an open injury's region as a contraindication (injuries of pain ≥ 4) | – |
| Safety | Climbers under 18: no campus, no one-arm or max finger work | Morrison & Schöffl 2007 |
| Safety | 55+: max finger sessions at least 72 h apart | Fell & Williams 2008 |
| Load | The first planned set of an exercise rises by at most one weight step or 10 % of the total load from one session to the next. More is allowed after a session in which every work set had 3+ in reserve | ACSM 2009: 2–10 % |
| Load | No max finger work while the finger load of the last 7 days is above 1.5 × its 28-day normal, from week 5 on (the athlete's climbing makes the load; the coach must not add to a spike) | Gabbett 2016 (acute:chronic ratio) |
| Load | After six weeks the app's value of an exercise trained in at least four sessions is within 20 % of the truth | – |
| Progress | No loaded or body-weight strength exercise is planned the same way in five sessions over four weeks or more | – |
| Progress | A run of six weeks or more has a lighter phase, deload trainings have at most 85 % of the work sets of build trainings, and the ten days before a target date taper | Bompa & Haff 2009 (3:1 loading); Bosquet et al. 2007 (taper of 8–14 days, volume −41–60 %) |
| Plan | In weeks trained as planned and without an injury or return ramp, each area (mobility aside) reaches at least half of its weekly goal and at most 1.25 × its maximum | Schoenfeld et al. 2017 (weekly sets) |
| Plan | A suggested training lasts at most 1.3 × the chosen length (+5 min) | – |
| Plan | No full training is suggested once the week's training days (climbing included) are used up | – |
| Plan | At least six different exercises in every eight full trainings (the block after climbing is meant to repeat) | – |
| Stacking | A suggested training has at least three work sets and 12 minutes | – |

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
- Schöffl, V., Popp, D., Küpper, T., & Schöffl, I. (2015). Injury trends in rock climbers: evaluation of a
  case series of 911 injuries between 2009 and 2012. *Wilderness & Environmental Medicine*, 26(1), 62–67.
- Zourdos, M. C., et al. (2016). Novel resistance training-specific rating of perceived exertion scale
  measuring repetitions in reserve. *Journal of Strength and Conditioning Research*, 30(1), 267–275.
