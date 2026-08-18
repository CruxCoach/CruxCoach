---
status: backlog
---
# Feature Spec: Training Plan Generation Engine (backlog — vision-tier)

> **Status:** Backlog — captured 2026-06-24. **This is the founding
> thesis of CruxCoach, written down for the first time.** The board
> client most of the app currently *is* was originally a means to an
> end: the question "how do I get clean training data to generate
> local training plans, without making users hand-log everything?"
> The answer was "meet them where their board logs already live."
> This spec captures the destination that motivated all of it.
>
> **Vision-tier:** no release target, no committed design. ID 040
> allocated above the known-reserved range (017–021, 029–037 live on
> parallel branches) to avoid collision. Renumber freely at merge.
>
> **Builds on:**
> - Auto-captured board logs (ascents, attempts, grades, angles) —
>   the data-acquisition layer that already exists.
> - SQLCipher personal DB — training data stays on-device, encrypted.
>
> **Relates to:** [[FEAT-041]] (holistic athlete data feeds the same
> engine), [[FEAT-009]] (difficulty engine — grade truth the plan
> reasons over), [[FEAT-014]] (live coordination can surface the plan).

---

## 1. Overview

CruxCoach's name is "Coach", not "Board Browser". The board client is
the **data-acquisition layer**; the product thesis is a personal
climbing coach that generates and adapts training plans from data the
user generates *anyway* by climbing on a board.

The core insight: every Kilter/MoonBoard/Aurora session already
produces a structured log (which climbs, what grade, sent vs.
projected, at what angle). Competing coaching apps (Lattice, Crimpd,
TrainingBeta) require disciplined manual logging. CruxCoach gets the
same signal for free because it's also the board client.

### 1.1 Goals

- Generate a structured training plan from the user's own board log.
- Adapt the plan as new sessions land (closed-loop, not one-shot).
- Run **on-device** wherever feasible — training data is sensitive
  and the privacy stance is load-bearing.
- Be honest about uncertainty: surface *why* a plan suggests what it
  suggests, not a black box.

### 1.2 Non-Goals (v1)

- Replacing a human coach for elite/competition athletes.
- Medical / injury-rehab prescription.
- Cloud-side model training on user data without explicit opt-in.

---

## 2. The data substrate

What the board client already captures or can capture:
- Ascent log: climb id, grade, angle, sent/flashed/projected, date.
- Attempt density / session frequency / volume per session.
- Grade distribution over time (progression curve).
- Style signal (via hold-type / move-type metadata where available).

What is missing and would sharpen plans (see [[FEAT-041]]):
- Body metrics (weight, finger strength tests, max hangs).
- Subjective load / fatigue / soreness.
- Off-board training (hangboard, gym, antagonist work).

---

## 3. Sketch of the engine

Three layers, each shippable independently:

1. **Descriptive** — turn the log into insight: progression curves,
   volume trends, style strengths/weaknesses, plateau detection. No
   prescription yet; pure mirror. Lowest risk, highest trust-building.
2. **Prescriptive** — periodised plan generation: given a goal
   (e.g. "send V8 by autumn") and the current log, produce a weekly
   structure (volume/intensity/rest, target climbs from the catalogue
   that train the weak axis). Rule-based first; the catalogue +
   difficulty engine make "suggest 5 climbs at your limit on this
   style" tractable without ML.
3. **Adaptive** — closed loop: after each session, re-fit the plan to
   actual performance vs. expectation (over/under-reaching, missed
   sessions, faster/slower progression than modelled).

### 3.1 Why on-device matters here

The plan reasons over the most personal data in the app. Doing it
on-device (vs. a cloud coach) is the privacy-honest path and a real
differentiator. Open question is how far rule-based / small-model
on-device reasoning carries before a cloud assistant ([[FEAT-041]])
is needed — and whether that crossing is ever worth the trust cost.

---

## 4. Open Questions

1. **Goal capture UX** — how does a user state a goal the engine can
   plan against without a coaching-jargon wall?
2. **Rule-based vs. learned** — how far does a transparent rule engine
   get before ML earns its complexity (and data-collection) cost?
3. **Cold start** — a fresh user with 3 logged sessions has almost no
   signal. Descriptive layer needs a graceful "not enough data yet"
   stance instead of confident-wrong plans.
4. **Cross-board normalisation** — grades and styles differ across
   Kilter/MoonBoard/Aurora. The plan must reason across them or scope
   to one board at a time.
5. **Evaluation** — how do we know a generated plan is *good*? Needs a
   feedback signal beyond "user didn't churn".

---

## 5. Caveats

- This is a vision spec, not an implementation plan. It exists to
  record the founding intent so downstream FEAT specs trace back to a
  coherent destination.
- The hardest part is not code, it's coaching correctness. A wrong
  plan that causes overtraining/injury is a real harm — ship the
  descriptive layer first and earn prescriptive trust slowly.
