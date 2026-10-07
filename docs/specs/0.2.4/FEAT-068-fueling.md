---
status: implemented-on-branch
queue: active
base: main@b5bef5163 (0.2.3)
branch: feat/0.2.4-training-nutrition (training line feat/0.2.4-training-body, merged with nutrition on 2026-10-07)
depends_on: [FEAT-066, FEAT-067]
created: 2026-10-04
---

# Feature Spec: Fueling, not dieting (0.2.4)

Always on with its own main-menu entry "Nutrition" / "Ernährung" next to
"Training" (owner decision 2026-10-05; it was an opt-in module before). The
guard rails are explained once in a dismissible card on the first visit.
Calories are shown by default (owner 2026-10-06) and can be hidden in the
settings; entries without kcal show the energy of their macros (Atwater
4/4/9) marked "≈".

- Protein: 1.6 g/kg trend weight by default (adjustable 1.4–2.0).
- Carbohydrate: 3–7 g/kg by the day's training load (rest, light, training,
  hard, very hard) computed from climbing time – board sessions and climbing
  days logged by hand or from Health Connect – at the RPE of the day's rated
  intensity (light 4, volume 6, hard 8, limit 9; 7 when unrated, FEAT-071)
  plus logged trainings (session RPE × minutes). Board sessions count
  automatically.
- Water: 35 ml/kg + 500 ml per training hour.
- Logging: quick entries (every nutrient optional), own foods (per 100 g or per
  portion), favourites, "same as yesterday", hydration taps; logged entries
  can be edited. Calories shown unless hidden; no budget, no red "over"
  states, no good/bad food labels.
- RED-S guard (no diagnosis): BMI below the IFSC 2024 screening thresholds
  (18.5 / 17.5 female), trend loss > 1 %/week or > 3 % in 4 weeks, or ≥ 3
  logged training days with < 3 g/kg carbohydrate in 7 days → supportive card,
  information and referral; any weight-loss goal is paused. One card with
  the same title and reasons in Today, Nutrition, Body and the weekly review.
- Weekly review: days with logged food, protein and carbohydrate against the
  targets of those days' training load, the micronutrient week (FEAT-069) and,
  for the current week, the energy card.
- Food search and barcode moved into 0.2.4 with FEAT-069 and are fully
  offline: BLS 4.0 (CC BY 4.0), an Open Food Facts extract for German- and
  English-speaking countries (ODbL), CameraX + ZXing; no lookup leaves the
  phone. Health Connect export, recipes and micronutrient watch items are
  specified in FEAT-069 as well.
