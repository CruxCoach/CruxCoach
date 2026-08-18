---
status: backlog
---
# Feature Spec: Holistic Athlete Data + Personal Assistant (backlog — vision-tier)

> **Status:** Backlog — captured 2026-06-24. Vision-tier. The
> long-horizon extension of [[FEAT-040]]: once board logs feed a
> coach, the natural next step is to bring *all* training-relevant
> data into one place so the coach can reason holistically and
> eventually become a conversational personal assistant.
>
> **Builds on:** [[FEAT-040]] (training-plan engine — this is its
> richer data substrate), SQLCipher personal DB.
>
> **Relates to:** [[reference_privacy_stats_research]] — the
> clean-vs-operator-trust pivot is exactly the decision this spec
> forces.

---

## 1. Overview

A training plan is only as good as the data it sees. Board logs cover
*climbing performance* but miss the rest of what determines progress:
body composition, finger/max-strength tests, sleep, fatigue, nutrition,
off-board training. This spec brings those into CruxCoach so it holds
**all training-relevant data in one place** — and then layers a
**personal assistant** on top that can answer "what should I do today?"
in natural language.

### 1.1 Goals

- One home for the climber's full training picture, not just sends.
- Feed the [[FEAT-040]] engine richer signal (load, recovery, strength).
- A conversational assistant surface over the user's own data.

### 1.2 Non-Goals

- Becoming a general fitness/diet app. Scope stays climbing-centric.
- Medical advice. Hard line.

---

## 2. Data domains to bring in

- **Body metrics** — weight, finger-strength (max hang / repeaters),
  pull/lock-off benchmarks, anthropometrics.
- **Load & recovery** — session RPE, soreness, sleep, resting HR
  (manual or wearable import).
- **Nutrition** — lightweight, climbing-relevant (protein, weight-mgmt
  goals), not full macro-tracking unless demanded.
- **Off-board training** — hangboard, gym, antagonist, mobility.

---

## 3. The personal assistant

The end-state: a conversational coach over the user's own data —
"Am I recovered enough to project today?", "What's been my weakest
style this month?", "Plan my next 3 weeks toward V9." Built on the
latest Claude models, with the data either kept on-device or sent to
the model under explicit, revocable consent.

### 3.1 The central tension (must be decided, not deferred)

This strand pulls **toward centralising maximally-sensitive personal
data**; the project's privacy-first / SQLCipher / decentralised
identity stance pulls **the other way**. The two are not irreconcilable
but the resolution is a load-bearing product decision:

- **On-device assistant** — data never leaves the phone. Maximally
  private, bounded by what on-device inference can do.
- **Cloud assistant** — far more capable, but every byte of training,
  body, and nutrition data crossing to a server is a trust event the
  user must opt into knowingly.

This is the same clean-vs-operator-trust axis as the privacy-stats
research. Decide it deliberately; do not let an implementation default
quietly pick "cloud" because it's easier.

---

## 4. Open Questions

1. Which data domains earn their input friction? Each new tracked
   field is a logging burden — the board-log thesis was *avoid* manual
   logging, so manual body/nutrition entry is in tension with that.
2. Wearable import (Health Connect / Google Fit / Apple Health-equiv)
   to reduce manual entry — feasible on the KMP/Android stack?
3. On-device vs. cloud assistant boundary (see §3.1).
4. Consent + revocation UX for any cloud path — granular, legible,
   reversible.

---

## 5. Caveats

- Furthest-horizon spec in the set. Captured to anchor the "personal
  assistant" north star, not to schedule it.
- The privacy tension in §3.1 is the real work here. Get it wrong and
  CruxCoach stops being the privacy-respecting option it positions as.
