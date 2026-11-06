# What weight and count fields mean in Training

One rule per case, the same on every screen (issue #58). The code is
`LoadRules` in `shared/src/commonMain/kotlin/com/cruxcoach/athlete/logic/LoadRules.kt`;
the labels and value texts are `WeightText.kt` in the app.

## Weight

| Case | What you enter | Label (EN / DE) | Shown as |
|---|---|---|---|
| Two dumbbells (bench press, overhead press, Romanian deadlift, wrist curls …) | the weight of **one** dumbbell, the number printed on it | Weight per dumbbell / Gewicht pro Hantel | 2 × 16 kg |
| Two kettlebells (own exercises) | the weight of one kettlebell | Weight per kettlebell / Gewicht pro Kettlebell | 2 × 16 kg |
| One dumbbell (one-arm work, goblet squat) | the dumbbell | Dumbbell weight / Gewicht der Hantel | 16 kg |
| One kettlebell | the kettlebell | Kettlebell weight / Gewicht der Kettlebell | 16 kg |
| Barbell | the total **including the bar** | Total incl. bar / Gesamt inkl. Stange | 60 kg |
| One-arm pick-up and block lifts | everything lifted: block, pin or carabiner and plates | Total incl. block / Gesamt inkl. Block | 32 kg |
| Two-arm pick-up | both hands together, on one block or two, blocks included | Total, both hands / Gesamt, beide Hände | 50 kg, hint "With two blocks: 25 kg each" |
| Cable and machine | the weight on the stack in kg or lb, not the plate number | Weight on the stack / Gewicht am Kabelzug | 40 kg |
| Force gauge (fixed-edge pull) | the gauge reading | Gauge reading / Wert am Kraftmesser | 30 kg |
| Body-weight exercises (pull-ups, hangs, dips, lunges) | only what you **add**, everything together: belt, vest, both dumbbells | Added weight / Zusatzgewicht | +12 kg |
| Assistance (pulley, band, feet) | a negative number: the kilos taken off (a band is estimated) | Added weight / Zusatzgewicht | 15 kg assisted / 15 kg entlastet |
| Body weight alone | 0 | | Bodyweight / Körpergewicht |

Weights are stored in kg. With lb selected, the app shows and reads lb and converts.

### Totals

Volume, % body weight, the estimated one-rep max, records, the strength chart and
the finger and shoulder load count the weight actually moved: **both dumbbells** of a
pair, body weight plus the added weight on body-weight exercises. Planning goes the
other way and suggests the weight of one dumbbell again. Estimated maxima and
records are shown the way you enter them ("est. 1RM 2 × 23 kg"); for body-weight
work they include body weight ("100 kg total").

## Counts

- **One-sided exercises** (one-arm pick-up, one-arm row, step-up) log each side as its
  own set. Reps, time and weight are that side's. In the workout editor the sets field
  reads "Sets per side".
- **Alternating exercises** within one set (walking lunge, dead bug, bird dog, hanging
  windshield wipers): every side counts as one rep, as the exercise steps say.
- **Typed workouts:** "2x16 kg" on a dumbbell exercise is 16 kg per dumbbell. On a
  body-weight exercise it is +32 kg added, on a two-arm pick-up 32 kg in total. A
  new exercise typed with dumbbells and "2x" becomes a two-dumbbell exercise.

## Logged sets are never rewritten

The rules only change how a number is read, never the stored number. Since the app
already planned dumbbell work per dumbbell and the text input stored "2x16 kg" as
16, older dumbbell sets are read the same way. Their volume, % body weight and
estimated maximum now count both dumbbells, like new sets, so charts stay
continuous.
