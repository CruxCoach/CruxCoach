---
status: implemented-on-branch
queue: active
base: main@b5bef5163 (0.2.3)
branch: feat/0.2.4-training-body
depends_on: [FEAT-066]
created: 2026-10-04
---

# Feature Spec: Body tracking v2 (0.2.4)

- `body_measurement` in the AthleteDatabase: primary key (day, metric), so one
  value per metric and day; canonical metric units, display in kg/lb, cm/in.
- Back-dating and editing; chart x-axis by date.
- Trend weight (Hacker's Diet smoothing 0.9, gaps compounded per day) is the
  number everything uses: % body weight on the hangboard, fueling targets.
- Circumferences, height, arm span, ape index (span − height).
- Strength-to-weight view from max hang / one-arm pick-up / weighted pull-up.
- "Hide numbers" mode shows only the trend direction.
- 0.1–0.2.3 `body_stats` rows are copied once (never overwriting) on first use.
- No BMI categories, no target weight, no body-fat goals. A weight-loss goal is
  blocked by the RED-S guard (FEAT-068) when signals are present.
