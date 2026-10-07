---
status: implemented-on-branch
queue: active
base: main@b5bef5163 (0.2.3)
branch: feat/0.2.4-training-nutrition (training line feat/0.2.4-training-body, merged with nutrition on 2026-10-07)
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
- Measurement round: one date, all metrics optional, last value as hint.
- Reminders (optional, off by default): weigh-in on chosen weekdays/time and a
  monthly measurement round; WorkManager one-shot jobs, no notification when
  today's value already exists; tap opens the body screen.
- Waistline import: JSON database backup (`diary[].stats`, units from
  `settings.bodyStats.units`) and CSV diary export (localized headers, dates
  and decimals, display units); preview, keep/overwrite, day/month switch.
