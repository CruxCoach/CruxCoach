---
status: implemented-on-branch
queue: active
base: main@b5bef5163 (0.2.3)
branch: feat/0.2.4-training-body
depends_on: [FEAT-066, FEAT-067]
created: 2026-10-04
---

# Feature Spec: Fueling, not dieting (0.2.4)

Opt-in module (off by default, intro screen before first use).

- Protein: 1.6 g/kg trend weight by default (adjustable 1.4–2.0).
- Carbohydrate: 3–7 g/kg by the day's training load (rest, light, training,
  hard, very hard) computed from board time (RPE 7 assumed) plus logged
  trainings (session RPE × minutes). Board sessions count automatically.
- Water: 35 ml/kg + 500 ml per training hour.
- Logging: quick entries (every nutrient optional), own foods (per 100 g or per
  portion), favourites, "same as yesterday", hydration taps. Calories only
  when switched on; no budget, no red "over" states, no good/bad food labels.
- RED-S guard (no diagnosis): BMI below the IFSC 2024 screening thresholds
  (18.5 / 17.5 female), trend loss > 1 %/week or > 3 % in 4 weeks, or ≥ 3
  logged training days with < 3 g/kg carbohydrate in 7 days → supportive card,
  information and referral; any weight-loss goal is paused.
- Later (0.2.5+): offline German food search (BLS 4.0, CC BY 4.0), barcode via
  CameraX + zxing-cpp + Open Food Facts lookup (only the EAN leaves the
  device), Health Connect export, recipes, micronutrient watch items.
