---
status: implemented-on-branch
queue: active
base: feat/0.2.4-training-nutrition-ux-review@df233f06b
branch: feat/0.2.4-training-nutrition-ux-review
depends_on: [FEAT-066, FEAT-070, FEAT-071]
created: 2026-10-20
---

# Feature Spec: Comeback plan after an injury (0.2.4)

> Owner request 2026-10-20: "bitte baue mal so eine art trainingsprogramm zur regeneration von verletzungen ein. mache
> aber vorab ausführliche recherche zu empfehlungen von ärzten und forschung etc … wenn fingerverletzungen angegeben
> werden, soll das auch teil der recommendations werden. am besten auch mehr zu den verletzungen fragen …" and, after
> the research: "bitte alles umsetzen, was hilfreich ist, aber immer mit dem hinweis, dass dies kein ersatz für
> arztbesuch ist und nur in absprache mit arzt genutzt werden sollte".
> Research (≈300 sources with evidence levels): `docs/research/2026-10-20-comeback-plan/` – fingers, loading
> principles, other injuries, safety and regulation.

## 0. Intended purpose

CruxCoach's comeback plan is a training aid for climbers who are getting back to training after an injury **together
with a doctor or physiotherapist**. It shows generic, published load stages and lets the athlete keep a diary. It does
not diagnose, does not treat, does not decide when an injury has healed, and does not replace a doctor or physio.
Every surface says: "Kein Ersatz für den Arztbesuch – nur in Absprache mit Ärztin, Arzt oder Physio nutzen."

Why this frame (research/safety-regulation.md §2): MDCG 2019-11 rev.1 (June 2025) lists software that takes a
person's musculoskeletal data and alleviates pain by recommending personalised rehabilitation exercises as a medical
device (≥ class IIa). Therefore:

- the athlete enters what a professional told them (finding, grade) – the app never derives a diagnosis or a grade;
- the athlete picks the stage agreed with the doctor or physio – the app never picks one from symptoms;
- the questions point one way only: warning signs → "get it checked" (now / within days / soon); nothing ticked never
  means "all good";
- the weekly look back shows the athlete's values next to the stage's rule and asks; a stage only changes on a tap;
- no "healed", "cleared", "allowed", "Reha", "Therapie" (a test checks every text).

Store, README and marketing texts must keep to this wording (HWG §3: no promise of success).

## 1. Data (local, SQLCipher, no cloud backup; schema 6, migration `5.sqm`)

- `injury.check_json` → `InjuryCheck`: onset, pop, swelling, fingers, ticked `WarningSign`s, `ProfessionalCheck`,
  `InjuryFinding`, checked on, signs acknowledged on.
- `injury.plan_json` → `ComebackPlan`: source (CruxCoach stages / "Plan meiner Physio" with an own routine), stage,
  stage history (each change with day and reason), acknowledged on, agreed with a professional, injured hand dominant,
  reference values per grip, prompt log (one per week), started/ended.
- `comeback_entry` (injury, day): morning pain, pressure pain (thumb until the nail whitens), swelling, new warning
  sign, sessions (kind, highest pain, workout id), note.
- Backup: new category `HEALTH` "Verletzungen & Comeback-Tagebuch" with injuries and diary. It is off by default in the
  file export and excluded from the automatic encrypted Nostr backup unless the athlete switches it on (Injuries
  screen). Older builds ignore the new keys; old backups restore without them.

## 2. Logic (`shared/…/athlete/logic/Comeback*.kt`)

- `ComebackContent`: stage ladders. Finger (owner's pick-up plan corrected per fingers.md §6): 0 Schützen, 1 Sanft
  belasten (open hand), 2 Halb aufgestellt dazu + easy climbing with tape, 3 Kraft aufbauen (40–60 % reference),
  4 Kraft steigern (60–85 %), 5 Zurück zum Limit (→ 90 %, board/limit gradually, full crimp optional from ~40 %).
  Sessions: tendon 5 × 30 s, ≤ 2/day ≥ 6 h apart; strength 6 × 10 s, 3/week ≥ 48 h; healthy hand 4 × 10 s.
  Generic six-stage ladder (other-injuries.md §3.3) for shoulder, elbow (lateral / medial / front), wrist, ankle,
  knee, hamstring, back, with region exercises and the moves to avoid (§10 matrix). Seven new catalogue exercises tagged
  `comeback` (never picked by the suggestion).
- `ComebackTexts`: warning signs per region with urgency and why, findings, notes per finding (tenosynovitis overuse
  rule, lumbrical grips, A4, buddy tape, own-plan-only findings), typical course per finding with sources, gelatin note
  (optional, weak evidence, allergy and kidney-stone caveats).
- `Comeback`: pain rule (green ≤ rule, amber +1, red above; morning vs the median of the previous mornings, +2 or ≥ 5
  red), weekly review (MOVE_UP after ≥ 7 days, ≥ 4 logged, pressure pain not rising; REPEAT on 2 amber or 1 red;
  STEP_BACK on 2 red or a red morning), prompts (worsening, not better after 14 and 21 days, new sign, review – each
  quiet for 7 days once shown), sessions due today, sessions as trainings (injured side on purpose, last comeback load
  → stage percentage of the reference → 5 kg start), reference from logged holds before the injury.
- Coach: `InjuryAdvisor` follows a running CruxCoach plan (injured spot outside the plan only from stage 5 for fingers,
  stage 3 elsewhere; climbing follows the stage); `SessionSuggester` adds COMEBACK_PLAN, COMEBACK_CLIMBING,
  COMEBACK_NO_BOARD and advises lighter on hard/limit days before the last stage. Comeback sessions never become the
  usual workout, keep their loads (no autoregulation, no warm-up ramp, no learned lower values).

## 3. UI (Android; iOS via the iOS parity agent)

- Injuries: per injury "Comeback-Plan starten/öffnen" with the notice; switch for the encrypted backup.
- Comeback screen: notice + warning signs, header, signs banner, under-18 hint, setup (questions → whose plan → stage
  agreed with doctor/physio → reference) with "Bevor du startest" confirmed by "Verstanden – in Absprache mit Ärztin,
  Arzt oder Physio"; running plan: prompts, not-checked / not-agreed hints, stage card, today's sessions, diary,
  14-day chart, typical course, nutrition, evidence and sources, summary for the appointment (share), end plan, delete
  all comeback data.
- Questions sheet; warning-sign alert (now: "112 anrufen" first, "Trotzdem fortfahren" as text button; within days:
  OK / "Wurde abgeklärt" + 116117 hint).
- Today: a card per running plan (morning value, sessions due, note hint, notice).
- Training finish: highest pain at the injured spot for comeback sessions.

## 4. Tests

`ComebackTest` (logic, wording), `SessionSuggesterTest.aComebackStageShapesBoardAndClimbingDays`, `ComebackDataTest`
(storage, migration 5 → 6, backup category, catalogue), `ComebackScreenTest` (flow), review shots
`TrainingUiReviewTest.comeback*`.

## 5. Open

- iOS screens (shared logic ready).
- A legal opinion or a BfArM status request (§ 6 Abs. 2 MPDG) if the owner wants certainty about the boundary.
