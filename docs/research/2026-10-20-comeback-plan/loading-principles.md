# Rehabilitative loading of connective tissue (tendon, ligament, pulley): principles for app logic

Research report for the CruxCoach injury-rehabilitation feature. Prepared 2026-10-20. English.
Scope: general loading principles that program logic can rely on. Finger-injury specifics (diagnosis, grading, red flags) and
regulation are covered in depth by sibling reports; here they appear only where they affect loading rules.

## How to read this report

**Evidence levels** used for every key statement:

| Level | Meaning |
|---|---|
| **L1** | Systematic review / meta-analysis of RCTs, or a clinical practice guideline / consensus built on a systematic review |
| **L2** | Randomised controlled trial (including small or crossover RCTs, noted where relevant) |
| **L3** | Prospective cohort or controlled human physiology study |
| **L4** | Cross-sectional, biomechanical modelling, animal or in-vitro (engineered tissue) work |
| **L5** | Expert opinion, narrative review, clinical commentary, clinic protocol, consensus without systematic review |

**Transfer tag** for finger flexor tendons / annular pulleys: **[direct]** = studied in fingers/climbers;
**[extrapolated]** = studied in other tendons/ligaments (Achilles, patellar, elbow, shoulder, ankle, knee) and assumed to transfer.

**Bottom line on transfer:** I found **no randomised trial of any loading protocol for pulley injuries or finger flexor
tendinopathy in climbers**. Everything finger-specific in the loading literature is clinic protocols, cohort data or
biomechanics (L3–L5). The dose–response rules below are therefore borrowed from lower-limb/elbow tendon and ankle/knee
ligament research. The app should say so to users.

---

## 1. Tissue healing phases and what loading fits each phase

### 1.1 Phase timelines

| Phase | Tendon (acute injury) | Ligament | Pulley (clinical schemes, not histology) |
|---|---|---|---|
| **Inflammatory** | ~24–72 h, up to ~1 week: haematoma, neutrophils/macrophages, start of type III collagen synthesis | ~0–7 days | Days 0–~10/14: splint or pulley-protection ring for ruptures |
| **Proliferative / reparative** | ~day 3 to ~6 weeks: peak type III collagen synthesis, high water and GAG content, weak and disorganised matrix | ~day 3 to ~6 weeks; collagen *content* back to normal by ~6 weeks | Weeks ~1–6/8: protected low-load isometrics, guided motion (see 1.4) |
| **Remodelling: consolidation** | ~6–10 weeks: fewer cells, more fibrous tissue, fibres align with load | ~6 weeks to months | Weeks ~6–16: progressive hangboard and climbing load under tape |
| **Remodelling: maturation** | ~10 weeks to ~1 year: shift to scar-like tendon, lower cell metabolism and vascularity | Months to years; scar *material* properties not normal even at 1–2 years | Full load ~4 months after conservatively treated ruptures; tape ≥1 year |

Sources:
- Tendon timings come from Sharma & Maffulli 2005 (**L5**, narrative review, the most-cited source of these numbers).
  The phases overlap and are not crisp. The same authors note that healed tendon never fully regains the biochemical or
  mechanical properties of intact tendon (Sharma & Maffulli 2006, **L5**).
- Healing ligament scar is "weaker, larger and creeps more than normal ligament", with more type III/V/VI collagen and
  fewer cross-links (Hildebrand & Frank 1998, **L4/L5**).
- In rabbit MCL work from Woo's lab, the bone–ligament–bone complex recovered about two-thirds of normal structural
  properties at 52 weeks, but the scar's material properties reached only about 25–30 % of normal (Woo lab summary 1993;
  Woo et al. 2000; **L4**, animal).
- The adult Achilles tendon *core* is essentially not renewed after adolescence (bomb-pulse ¹⁴C; Heinemeier et al. 2013,
  **L3**). Structural turnover of dense collagen is very slow, which is why rehab timelines run in months.
- Climbers' A2/A4 pulleys are 62–76 % thicker than non-climbers' after ≥15 years of climbing (Schreiber et al. 2015,
  **L4 [direct]**, cross-sectional). Pulleys adapt to load, but on a timescale of years.

**Tendinopathy is not an acute-healing problem.** Overuse tendinopathy shows disordered healing, and inflammation is
typically absent (Sharma & Maffulli 2006; Cook & Purdam 2009 continuum model, **L5**). For tendinopathy the phase table
does not apply. Programs are staged by symptoms and capacity, not by time since injury (sections 2–3). An app must branch
on **acute structural injury vs overuse/tendinopathy-like presentation**.

### 1.2 Relative rest vs protected loading (definitions the app can use)

- **Relative rest:** remove or reduce only the provoking activity (e.g., crimping on small edges, board climbing). Keep
  everything else, including training of uninjured regions and general fitness. It is not complete rest.
  - Silbernagel et al. 2007 (**L2**, n = 38, Achilles tendinopathy): continuing running and jumping under a pain-monitoring
    model was as effective as 6 weeks of "active rest" (swimming/cycling only). VISA-A improved from 57 to 85 vs 57 to 91
    at 12 months, with no negative effects.
  - The current Schöffl/Bamberg pulley scheme allows non-specific and general strength training "immediately, without
    using the injured finger" (Strohm & Schöffl 2026, **L5 [direct]**).
- **Protected loading:** load the injured tissue deliberately but under constraints, such as external protection
  (ring/tape, brace, boot), a restricted position or range (open-hand instead of crimp), or low intensity (low % MVC,
  isometric). Examples:
  - Achilles rupture treated non-operatively in a boot with early weight-bearing had re-rupture and strength outcomes
    similar to surgery (Willits et al. 2010, **L2**, n = 144).
  - For ankle sprains, tape or a brace plus exercise beats immobilisation (Vuurberg et al. 2018, **L1** guideline).
  - Pulley ring plus "light isometry" in weeks 1–2 (Strohm & Schöffl 2026, **L5 [direct]**).

Per phase, this translates into:

| Phase | Default stance for acute ligament/pulley injury | Loading intent |
|---|---|---|
| Inflammatory (0–~7 d) | Protect (ring/tape/splint as advised clinically), pain-free active motion, tendon glides, train everything else | Avoid tissue strain; prevent stiffness and detraining |
| Proliferative (~1–6 wk) | Protected, low-to-moderate, mostly isometric loading in the safest position; pain ≤2–3/10 | Give a mechanical signal to orient new collagen without disrupting weak repair tissue |
| Remodelling (>~6 wk) | Progressive heavy loading toward and beyond pre-injury capacity; reintroduce provocative positions (crimp) last | Increase stiffness and strength; restore sport-specific capacity |

### 1.3 Evidence that early controlled loading beats immobilisation

- **Mechanism:** cells sense load through mechanotransduction. Controlled load stimulates collagen synthesis and
  alignment, while unloading degrades material properties (Khan & Scott 2009, **L5**; Killian et al. 2012, **L4/L5**;
  Magnusson & Kjaer 2019, **L5**).
- **Unloading data in humans:**
  - 90 days of bed rest cut gastrocnemius tendon stiffness by 58 %, or by 37 % with resistive countermeasures (Reeves
    et al. 2005, **L3**).
  - 14 days of immobilisation reduced patellar tendon stiffness by about 20 % in young men (3622 → 2910 N/mm; Couppé
    et al. 2012, **L3**).
  - 2 weeks of immobilisation reduced calf muscle CSA by 6 % and strength by 9 %, while tendon size and collagen
    turnover did not change (Christensen et al. 2008, **L2/L3**).
  - Muscle deconditions faster than tendon, but prolonged unloading degrades tendon.
- **Ankle sprain (ligament):**
  - Functional treatment beat immobilisation. More patients returned to sport (RR 1.86), they returned 4.9 days sooner,
    went back to work 8.2 days sooner, had less persistent swelling and instability, and were more satisfied (Kerkhoffs
    et al. 2002 Cochrane, **L1**; 21 trials, n = 2184; Cochrane withdrew the review in 2013 as no longer maintained, but
    its findings agree with the 2018 guideline).
  - An accelerated exercise protocol in week 1 improved function and activity vs standard PRICE, with equal reinjury
    (4 % each) (Bleakley et al. 2010, **L2**, n = 101).
- **Acute Achilles rupture:** accelerated functional rehabilitation with early weight-bearing and early motion
  (Willits et al. 2010, **L2**).
- **After rotator cuff repair:** early passive or active motion improved range of motion with **no difference in
  re-tear** vs delayed motion (Hu et al. 2023, **L1**, 13 RCTs, n = 1082).
- **Patient advice frameworks:** "PEACE & LOVE" (Dubois & Esculier 2020, **L5**) replaced PRICE/POLICE. It recommends
  early "optimal loading" and avoiding anti-inflammatories early on. The 2018 ankle guideline also cautions that NSAIDs
  "may suppress the natural healing process" (Vuurberg 2018).

### 1.4 Where early loading does *not* clearly win

- **Severe ankle sprains:** a 10-day below-knee cast gave faster recovery than a tubular compression bandage at
  3 months, with ankle function 9 % better (Lamb et al. 2009, **L2**, n = 584). There was no difference at 9 months. A
  *short* protection period can help the most severe ligament injuries.
- **Repaired flexor tendons (hand):** early *active* motion gave better range of motion but a slightly higher rupture
  rate than early passive motion (5 % vs 4 %; Starr et al. 2013, **L1**, mixed-quality). Load dose after a repair is a
  trade-off.
- **PIP hyperextension / volar plate injuries:**
  - There is insufficient evidence on the need for, or duration of, immobilisation. Trials found no differences (Chalmer
    et al. 2013 Cochrane, **L1**, 3 old, high-risk-of-bias trials).
  - A 2026 RCT found that a *self-managed* exercise regimen was non-inferior to supervised hand therapy for stable PIP
    volar plate avulsions (PINOT, Salentijn et al. 2026, **L2 [direct, finger]**, n = 200). This supports app-delivered
    self-management for appropriately triaged finger injuries.
- **Pulley ruptures (grade III–IV):** expert schemes keep an initial protection period. The Bamberg 2026 scheme uses a
  pulley-protection ring for 6–8 weeks and forbids hanging and climbing in the ring. Grade IV ruptures need surgery
  (Schöffl et al. 2003, **L3 [direct]**, 604 injured climbers; Miro et al. 2021, **L5**; Strohm & Schöffl 2026, **L5**).
  - Conservatively treated single ruptures showed no long-term strength deficit, and climbers regained their level
    within a year (Schöffl et al. 2006, **L3 [direct]**, n = 21).

**App consequence:** early loading is the default. The *start date and intensity* of tissue-specific loading must depend
on severity. A grade I pulley strain can start protected isometrics in week 1. A suspected rupture needs clinical grading
(ultrasound) and a protection phase before any hangboard work.

---

## 2. Loading modalities and dosage

### 2.1 What tendon (and probably ligament/pulley) adaptation responds to

- **Load magnitude is the dominant variable.** In a meta-analysis of 27 studies on healthy tendons, high-intensity
  loading (>~70 % MVC or 1RM) increased stiffness (SMD 0.90, 95 % CI 0.71–1.08). Low-intensity loading did not (SMD 0.04).
  Contraction type did not matter once intensity was high: isometric SMD 0.95, concentric–eccentric 0.82, eccentric 1.04.
  Effects were somewhat larger with interventions ≥12 weeks (Bohm, Mersmann & Arampatzis 2015, **L1** [extrapolated]).
- **Strain per repetition and its duration matter.** Achilles tendon adapted only at high strain (~4.5–4.7 %), not
  low strain (~2.9 %), over 14 weeks (Arampatzis et al. 2007, **L3**). With equal volume, 3 s load / 3 s relax beat
  1 s / 1 s (Arampatzis et al. 2010, **L3**). Holds of a few seconds or longer at high intensity are the
  tendon-building stimulus.
- **Tendon adapts more slowly than muscle.** Muscle strength gains can outpace tendon/pulley capacity, which creates an
  imbalance risk (Mersmann et al. 2017, **L5**). This is relevant to climbers: finger flexor strength returns faster than
  pulley tolerance.
- **Collagen time course after a bout.** Tendon collagen synthesis rises by 6 h, peaks at ~24 h and is still elevated at
  72 h after strenuous exercise (Miller et al. 2005, **L3**). Degradation rises too and peaks earlier than synthesis
  (Magnusson, Langberg & Kjaer 2010, **L5**). Peritendinous procollagen was 3× baseline at 72 h after a 36-km run
  (Langberg et al. 1999, **L3**). This supports **≥48 h between heavy sessions** for the same tissue (2–3 heavy sessions
  a week).

### 2.2 Isometrics and the "analgesia" claim (Rio et al. and replications)

| Study | Population | Protocol | Result | Level |
|---|---|---|---|---|
| Rio et al. 2015 | Patellar tendinopathy, n = 6 volleyball players, crossover | 5 × 45 s at 70 % MVIC, 2 min rest, vs isotonic | Decline-squat pain fell from 7.0 to 0.17 (isotonic: 6.3 to 3.8), held 45 min; MVIC +18.7 %; less cortical inhibition | L2 (tiny) |
| Rio et al. 2017 | Patellar, n = 20, in-season | Isometric 80 % MVIC at 60° knee flexion vs isotonic 80 % of 8RM, 4×/wk for 4 wk | More immediate analgesia with isometrics; both improved | L2 |
| van Ark et al. 2016 | Patellar, n = 29, in-season | Isometric vs isotonic, 4×/wk for 4 wk | Both reduced pain, **no difference** | L2 |
| Holden et al. 2020 | Patellar, n = 21, crossover, pre-registered | Single high-load isometric vs dynamic session | Small pain drop (−0.9/10) immediately, gone at 45 min; **no difference** between modes | L2 |
| O'Neill et al. 2019 | Achilles tendinopathy, n = 16 | 45-s heavy isometric plantar-flexor holds | **No meaningful analgesia**; authors advise against using them for pain relief | L3 |
| Coombes et al. 2016 | Lateral elbow, n = 24 | 10 × 15 s wrist-extension isometric 20 % below vs above pain threshold | **Above** pain threshold *increased* pain for ≥30 min; below threshold neutral | L2 (crossover) |
| Parle et al. 2017 | Acute rotator cuff tendinopathy | Low-load isometric vs ice vs both | Isometrics no better than ice | L2 (letter) |
| Pearson et al. 2020 | Patellar, n = 16 | 24 × 10 s vs 6 × 40 s at 85 % MVC, equal time under tension | **Short holds as effective as long holds** for pain and function over 4 wk | L2/L3 |
| Clifford et al. 2020 | 10 RCTs, mixed tendons | — | Isometrics **not superior** to isotonic for pain or function, immediately or ≤12 wk; response variable | **L1** |
| Challoumas et al. 2021 | Patellar NMA, 37 RCTs | — | Isometric ≈ isotonic for immediate relief (MD −1.03, 95 % CI −2.6 to 0.5) | **L1** |

Interpretation for the app:
- Isometrics are a **safe, well-tolerated entry modality** that can be dosed precisely. That suits a protected-loading
  phase and a hangboard or pick-up block.
- Do **not promise pain relief**. The analgesic effect did not replicate consistently.
- Keep holds **below the provocation threshold**. Coombes 2016 is a direct warning against "pushing into pain" with
  isometrics.
- 10-s holds can substitute for 30–45-s holds when total time under tension is matched (Pearson 2020). The owner's
  6 × 10 s strength block and 5 × 30 s "tendon" block are both defensible [extrapolated].

### 2.3 Isotonic / heavy slow resistance (HSR)

- **Kongsgaard et al. 2009 (L2, patellar, n = 39):** HSR 3×/week for 12 weeks had good short- and long-term effects,
  with less tendon swelling, less neovascularisation, increased collagen turnover, and the highest satisfaction at
  6 months. Corticosteroid injection had good short-term but poor long-term effects.
- **Beyer et al. 2015 (L2, Achilles, n = 58).** Verified from the full text:
  - **HSR:** 3×/week, 3–4 sets, 2–3 min rest between sets. Loads went from 3 × 15RM (week 1) to 3 × 12RM (weeks 2–3),
    4 × 10RM (weeks 4–5), 4 × 8RM (weeks 6–8) and 4 × 6RM (weeks 9–12). Each repetition took 3 s eccentric + 3 s
    concentric.
  - **Eccentric (comparator):** 3 × 15, twice daily, every day.
  - **Pain rules:** during the exercises pain could reach **40–50 mm VAS** but had to subside by the next session. Sport
    was allowed from week 4 at ≤30 mm VAS.
  - **Outcomes:** clinical outcomes equal at 12 and 52 weeks. Compliance was 92 % with HSR vs 78 % with eccentric
    training, and satisfaction 100 % vs 80 % at 12 weeks.
- **Progressive tendon-loading exercise (PTLE; Breda et al. 2021, L2, patellar, n = 76):**
  - **Stages:** (1) isometric 5 × 45 s at 70 % MVC daily; (2) isotonic 4 × 15 progressing to 4 × 6, on alternate days
    with isometrics; (3) energy-storage/plyometrics every third day; (4) sport-specific.
  - **Rules:** train within VAS ≤3/10. Progress a stage when VAS ≤3 on a single-leg-squat provocation test **and** at
    least 1 week has been spent in the current stage. Maintain stages 1–2 twice weekly after return to sport.
  - **Outcomes:** VISA-P +28 vs +18 with eccentric training (adjusted difference 9, 95 % CI 1–16). Return to sport 43 %
    vs 27 % (not significant). Adherence was only ~40–50 %.
  - This is the best template for a **staged, criteria-gated** program.
- **Rotator cuff:** *progressive and resisted* exercise showed a 15-point pain/function benefit vs placebo or no
  treatment. Non-progressive or non-resisted exercise showed none (Naunton et al. 2020, **L1**, low certainty).
  **Progression matters.**
- **Lateral elbow:** exercise beat passive treatments and corticosteroid in the mid and long term. Pain-free grip
  strength mean differences were ~12–22 (units not stated in the abstract), but effects were small and protocols
  heterogeneous (Karanasios et al. 2021, **L1**, 30 RCTs, n = 2123).

### 2.4 Eccentric training

- Alfredson et al. 1998 (**L3**, n = 15): 3 × 15 eccentric heel drops, straight and bent knee, twice daily, every day for
  12 weeks (180 reps/day), with pain allowed. All patients returned to running.
- Later syntheses:
  - Isolated eccentric training is **not clearly superior** to other loading (Malliaras et al. 2013, **L1**).
  - Heavy eccentric calf training may beat natural history and traditional physiotherapy, but may be slightly inferior
    to other exercise programs (Murphy et al. 2019, **L1**, low confidence).
  - In Achilles tendinopathy no active treatment is clearly superior, and all beat wait-and-see. Exercise is a
    reasonable low-cost first line (van der Vlist et al. 2021, **L1**, 29 RCTs).
- Guidelines: the 2024 JOSPT Achilles CPG (Chimenti et al. 2024, **L1**) and the Dutch multidisciplinary Achilles
  guideline (de Vos et al. 2021, **L1**) both put progressive loading exercise at the core.
- **For fingers:** eccentric-only finger work has no specific evidence. Pulleys are loaded most in eccentric crimping
  (Miro et al. 2021). The app should avoid eccentric or dynamic crimp loading until late stages [extrapolated + L4
  biomechanics].

### 2.5 Pain during loading: allowed or not?

- Painful exercise gave a small short-term pain benefit over pain-free exercise in chronic musculoskeletal pain (SMD −0.27,
  moderate quality), with no difference in the medium or long term (Smith et al. 2017, **L1**).
- Acute hamstring strain: rehab allowing **≤4/10** pain did not slow return to play (17 vs 15 days median, not
  significant). Isometric knee-flexor strength recovered 15 % better, and reinjuries were equal (2 vs 2) (Hickey et al.
  2020, **L2**, n = 43). This is an acute *muscle* injury, so transfer to ligament or pulley is uncertain.

### 2.6 What transfers to finger flexor tendons and pulleys

- **Pulleys are ligament-like annular restraints, and finger posture controls their load.** In modelling, A2 pulley force
  was **36× lower** in the slope/open grip than in the crimp grip, and A4 force 4× lower (Vigouroux et al. 2006, **L4
  [direct]**). In crimp, A2 load is about 3× the fingertip force (up to 116 N measured) (Schweizer 2001, **L4 [direct]**).
  - So **grip position is the main load dial**: open/drag, then half crimp, then full crimp. This is biomechanically
    grounded, but no outcome data compare progressions.
- **Edge depth, load in kg and hold time** are the other dials. Keep one edge depth fixed so kg is comparable over time
  (owner's plan, L5).
- **Isometric hangs and pick-ups** map well onto the isometric tendon-loading literature. At adequate intensity they are
  as effective as dynamic loading for tendon stiffness (Bohm 2015) [extrapolated].
- **Tape:** H-tape reduced tendon–bone distance by 16 % and improved crimp strength by 13 % in climbers with old pulley
  ruptures (Schöffl I et al. 2007, **L3 [direct]**). Circular tape over A2 absorbs only ~11–12 % of bowstringing force
  (Schweizer 2000, **L4 [direct]**). A systematic review rated the evidence that taping reduces bowstringing 15–22 % as
  low to moderate. Pain, return-to-sport and rupture-prevention evidence is very low (Larsson et al. 2022, **L1
  [direct]**).
- **Clinical commentary:** Ehiogu, Schöffl & Jones 2023 (**L5 [direct]**) and Manjunathan & Liem 2026 (**L5 [direct]**)
  both frame pulley rehab as progressive strength-and-conditioning loading. Neither provides trial data.

### 2.7 Dosage summary (extrapolated to fingers unless marked)

| Parameter | "Tendon / protected" bout | Strength / remodelling bout | Basis |
|---|---|---|---|
| Mode | Isometric hold (pick-up / hang with feet support) | Isometric hangs or pulls, later isotonic | Bohm 2015 (L1); Breda 2021 (L2) |
| Intensity | Low–moderate; pain ≤2–3/10. Rio-type 70 % MVC only if pain-free | Start ~40–60 % of current max (RPE ~5–6), rising to ≥70–85 % (RPE 7–8) | Bohm 2015; Beyer 2015 RM progression (15RM ≈ 65 % to 6RM ≈ 85 % 1RM, approximate conversion, L5); owner's %R steps |
| Sets × hold | 4–5 × 30–45 s | 4–6 × 10 s (or 3–4 × 6–15 reps isotonic) | Rio 2015; Pearson 2020; Beyer 2015 |
| Rest | 1–2 min | 2–3 min | Rio 2015 (2 min); Beyer 2015 (2–3 min) |
| Bout length | ≤10 min | 20–30 min | Paxton 2012 / Baar 2017 (L4) |
| Frequency | 1–2×/day, ≥6 h apart | 2–3×/week, ≥48 h between heavy sessions on the same tissue | Baar 2017 (L4); Miller 2005 (L3); HSR 3×/wk (L2) |
| Program length | — | ≥8–12 weeks for tendon material adaptation; maintenance 2×/week after return to sport | Bohm 2015; Beyer 2015; Breda 2021 |

---

## 3. Pain-monitoring model and traffic-light rules

### 3.1 The original model (Thomeé 1997, modified by Silbernagel)

Silbernagel et al. 2007 (**L2**), verbatim from the full text: pain was "allowed to reach level 5 on the VAS … during the
exercise training. The pain after the exercise program was allowed to reach 5 on the VAS but should have subsided by the
following morning. Pain and stiffness in the Achilles tendon were not allowed to increase from week to week."

Silbernagel's teaching material (2009 handout, **L5**) gives the same four rules on an NPRS. It labels **0–2 "safe zone"**,
**2–5 "acceptable zone"** and **5–10 "high risk zone"**:

1. Pain during activity may reach 5/10.
2. Pain after the activity may reach 5/10.
3. Pain the morning after should not exceed 5/10.
4. Pain and stiffness must not increase from week to week.

Thomeé 1997 (**L2**, patellofemoral pain) originated the model.

Silbernagel's later clinical guidance (Silbernagel, Hanlon & Sprague 2020, **L5**) adds:
- a load classification:

  | Load | Pain during | Pain after | Recovery before next session |
  |---|---|---|---|
  | Light | 1–2 | 1–2 | none (daily OK) |
  | Medium | 2–3 | 3–4 | ~2 days |
  | High | 4–5 | 5–6 | ~3 days |

- that heavy loading needs 36–72 h recovery;
- that activities are reclassified every 3–4 weeks;
- return-to-run gates: walking pain ≤1–2/10. Athletes with pain >2/10 during 20 single-leg hops "will struggle to start
  running".

### 3.2 Thresholds used in other loading trials (useful anchors)

| Context | During loading | After / next day | Progression gate | Source |
|---|---|---|---|---|
| Achilles tendinopathy | ≤5/10 | ≤5 after, settled by next morning; no week-to-week rise | Symptom-led | Silbernagel 2007 (L2) |
| Achilles tendinopathy (HSR/ECC) | 40–50 mm VAS during exercise; ≤30 mm during sport | Subsided by next session, otherwise reduce load | Weekly RM plan | Beyer 2015 (L2) |
| Patellar tendinopathy (PTLE) | ≤3/10 | — | Provocation test ≤3/10 and ≥1 week at stage | Breda 2021 (L2) |
| Acute muscle strain | ≤4/10 (vs 0/10) | — | Per exercise | Hickey 2020 (L2) |
| Post-operative ligament (ACL) and injured-athlete weight training ("soreness rules") | Soreness in warm-up that continues → 2 days off, drop 1 level. Soreness that goes away → stay. Goes away but returns in session → 2 days off, drop 1 level | Soreness next day (not muscle soreness) → 1 day off, do not advance | No soreness → advance 1 level/week | Fees et al. 1998; Adams et al. 2012 (L5, widely used clinically) |
| Acute pulley (owner's example plan) | ≤2/10 | Next morning not worse | Whole week ≤2/10 and mornings never worse → progress; outlier → repeat week; clearly worse → 1 week back | Owner plan (L5), consistent with the conservative end of the above |

### 3.3 How thresholds should differ: tendinopathy vs acute ligament/pulley

- **Tendinopathy/overuse:** pain is a poor marker of tissue damage. Moderate pain (≤5/10) that settles by next morning
  is acceptable and lets people stay active (Silbernagel 2007, L2; Smith 2017, L1).
- **Acute structural injury (ligament sprain, pulley strain or rupture):** repair tissue is mechanically weak during the
  proliferative phase (section 1.1). Pain is more likely to signal strain on the healing structure. No RCT defines a
  pain ceiling for ligament or pulley rehab. Clinical practice uses stricter rules: soreness rules, effusion-based gates
  after ACL surgery, and ≤2/10 in the owner's pulley plan. **Default ceiling: 2/10 during, nothing worse next morning**
  in the first ~6–8 weeks after an acute pulley/ligament injury. Relax toward 3/10 in the remodelling phase. Evidence
  level L5, extrapolated.
- **Clinically important change** on a 0–10 NRS is ~2 points or ~30 % (Farrar et al. 2001, **L3**, chronic pain). A
  next-morning rise of **≥2 points** over baseline is a defensible "red" trigger. **+1** is within noise and should be
  "amber/watch".

### 3.4 Operationalising "traffic light" and progression rules

How clinicians and protocols turn the above into decisions:
- **Zones:** green 0–2 (progress), amber 3–5 (hold or repeat for tendinopathy; amber-high for acute injury), red >5,
  or next-morning or week-to-week worsening (regress). Zones are Silbernagel's; actions follow the soreness rules.
- **Stages with entry criteria, not dates:**
  - PTLE: ≥1 week at a stage plus a provocation test ≤3/10.
  - ACL criterion-based rehab: phase gates at quadriceps index ≥60 %, ≥80 % and ≥90 %, effusion "trace or less", and
    "do not advance more than 2 levels in a 7-day period" (Adams et al. 2012, L5).
- **A standardised provocation test** gives a comparable daily pain number: single-leg decline squat for patellar
  tendinopathy, hops or heel raises for Achilles. A finger analogue would be a 10-s open-hand pick-up or hang at a fixed
  submaximal load, rated 0–10, done before each session. This is an extrapolation; no finger-specific test is validated.
- **Regression:** drop one level and take 1–2 days off tissue-specific loading (soreness rules). The owner's "one week
  back, 3–4 days there" is a reasonable stricter variant.

---

## 4. Collagen synthesis, refractory period, gelatin/collagen + vitamin C

### 4.1 The "short bouts, ~6 h apart" principle (Baar)

- **Engineered human ligaments** (Paxton et al. 2012, **L4**, in vitro):
  - ERK1/2 signalling peaked after **10 min** of cyclic stretch and returned toward baseline within 60 min.
  - Cells stayed **refractory to a second stretch for up to 6 h**.
  - Over 7 days, 10 min of stretch **every 6 h** raised collagen content more than continuous stretch (5.9 % vs 4.8 %;
    control 3.1 %).
- Baar 2017 (**L5** review) generalises this: sinews "quickly become refractory", so "<10 min periods of activity with
  relatively long (6 h) periods of rest are best".
- Analogous **bone** data: recovery periods restore mechanosensitivity in loaded rat bone (Robling et al. 2001, **L4**).
- **Human evidence:** there is no human trial comparing tendon or ligament outcomes between 6-h-spaced and other bout
  schedules. Shaw et al. 2017 *used* ≥6 h spacing but did not test it. The principle is plausible and harmless, but
  **mechanistic only (L4)**. It also sits alongside the human finding that heavy sessions keep collagen synthesis elevated
  for ~72 h (Miller 2005).
- **Reconciliation:**
  - Short, low-to-moderate "tendon bouts" (≤10 min) can be done 1–2×/day, ≥6 h apart.
  - Heavy strength sessions on the same tissue go every 48–72 h (2–3×/week).
  - The owner's split (A daily or twice daily, B 3×/week) is consistent with both.

### 4.2 Gelatin / collagen + vitamin C

| Study | Design | Dose / timing | Outcome | Level |
|---|---|---|---|---|
| Shaw et al. 2017 | Crossover RCT, n = 8 healthy men | 5 g or 15 g gelatin in a blackcurrant drink with 48 mg vitamin C, 1 h before 6 min rope skipping; 3×/day ≥6 h apart for 3 days | 15 g **doubled** serum PINP; serum taken 1 h post-dose increased collagen content and mechanics of engineered ligaments | L2 (very small; surrogate) |
| Lis & Baar 2019 | Crossover RCT, n = 10 | 15 g gelatin or hydrolysed collagen + vitamin C, 1 h before 6 min jump rope | PINP ~+20 % trend, **not significant** (high variability) | L2 (surrogate) |
| Lee et al. 2024 | Crossover RCT, n = 10 trained men | 0, 15 or 30 g hydrolysed collagen + 50 mg vitamin C, 1 h before 4 × 10RM squats | 30 g > 15 g and 0 g for PINP AUC; **15 g ≈ 0 g** | L2 (surrogate) |
| Aussieker et al. 2023 | RCT, n = 45 | 30 g collagen vs whey vs placebo after resistance exercise | Muscle connective-tissue protein synthesis **not increased** (direct isotope measure) | L2 |
| Jerger et al. 2022 | RCT, n = 40 healthy men | 5 g specific collagen peptides/day + 14 wk heavy resistance training | Achilles CSA +11.0 % vs +4.7 %; **stiffness and strength no different** | L2 |
| Lee et al. 2023 | Pair-matched RCT, n = 17 female soccer players | 30 g hydrolysed collagen + 500 mg vitamin C after each session, 10 wk | Patellar tendon stiffness +18 % vs +5 %, modulus +17 % vs +5 % | L2 (small) |
| Praet et al. 2019 | Crossover pilot RCT, n = 20, Achilles tendinopathy | 2.5 g specific collagen peptides twice daily (~5 g/day), ~30 min before twice-daily calf exercise; no vitamin C | VISA-A +12.6 vs +5.3 at 3 months; not powered | L2 (pilot) |
| Bischof et al. 2024 | Meta-analysis, 19 RCTs, n = 768, healthy adults | Long-term collagen peptides + training | Tendon morphology SMD 0.67 (**very low certainty**); maximal strength SMD 0.19 (low) | **L1** |
| Hijlkema et al. 2022 | Systematic review, tendon health/tendinopathy | 9 collagen/gelatin studies (6 RCTs) | Collagen "seems to be beneficial", but GRADE **low**; optimal dose, timing and type unknown; often confounded by co-interventions | **L1** |
| Buchalski et al. 2026 | Systematic review, 8 RCTs, n = 257 | 15–30 g + ≥50 mg vitamin C with ≥70 % 1RM training | Higher doses improved tendon CSA/stiffness; authors call it "GRADE A". **That rating is hard to reconcile with the small samples**; prefer Bischof 2024's very-low-certainty rating | L1 (overstated) |
| JUMPFOOD (van Dam et al. 2023) | RCT protocol, n = 76, patellar tendinopathy, 10 g hydrolysed collagen + 40 mg vitamin C daily + PTLE for 24 wk (NCT05407194) | — | **No results found as of 2026-10-20** | — |

What this means:
- **Biomarker effect (whole-body collagen synthesis, PINP):** plausible at 15–30 g taken ~1 h before loading. Note that
  PINP is a systemic marker that is largely bone-derived. It is not tendon-specific.
- **Tendon structure in healthy trained people:** a few small RCTs show increased CSA or stiffness. Overall certainty is
  very low to low.
- **Injury rehabilitation outcomes:** one small pilot in Achilles tendinopathy. **No data for ligaments or pulleys.**
- **Effect sizes:** unknown for clinical outcomes in injury. Moderate (SMD ~0.7) for tendon morphology in healthy adults,
  at very low certainty.
- **Practical dose if offered:** 10–15 g gelatin (Shaw) or 15–30 g hydrolysed collagen (Lee 2024), with ~50 mg vitamin C
  (about one orange or a fruit juice), 30–60 min before the tendon bout. Optional. Present as "may help; evidence low".
- **Safety caveats:**
  - Generally well tolerated; no adverse events reported in the trials above. Collagen is an incomplete protein (no
    tryptophan) and does not replace total protein intake.
  - **Kidney stones:** gelatin is rich in hydroxyproline, which raises urinary oxalate (Knight et al. 2006, **L3**).
    Vitamin C supplements (~1 g/day) were associated with roughly double the kidney-stone risk in men (Thomas et al.
    2013, **L3**). Ask about stone history; keep vitamin C at the ~50 mg needed, not gram doses.
  - **Allergy:** mammalian gelatin can trigger reactions in alpha-gal syndrome (Platts-Mills et al. 2020, **L5**). Also
    consider dietary or religious restrictions (bovine/porcine), and fish-collagen allergy.
  - Supplement quality varies. Recommend food-grade gelatin or third-party-tested products.
  - Never present supplementation as a substitute for, or accelerator of, return-to-sport timelines.

---

## 5. Return-to-sport frameworks

- **Consensus (Ardern et al. 2016, L1/L5, Bern consensus):**
  - Return to sport is a **continuum** that runs parallel to rehab: return to participation, then return to sport, then
    return to performance.
  - Decisions should be shared (athlete, clinician, coach), use a biopsychosocial view, and be structured with StARRT.
  - Research evidence for specific return criteria is scarce.
- **StARRT** (Shrier 2015, **L5**; building on Creighton et al. 2010):
  1. Tissue health (healing status, symptoms, imaging, function tests).
  2. Tissue stresses (risk of the planned activity, e.g., board vs slab climbing, protection by tape).
  3. Risk tolerance (the athlete's goals, timing, pressure, conflicts of interest).
- **Control–chaos continuum** (Taberner et al. 2019, **L5**): progress from controlled, predictable tasks to chaotic,
  sport-specific ones before full return. For climbing: hangboard/pick-up, then easy jugs and big holds, then edges with
  tape, then limit routes, then board/dynamic moves on small holds.
- **Criteria-based vs time-based progression:**
  - ACL reconstruction (**L3**):
    - Delaware-Oslo cohort (Grindem et al. 2016, n = 106): reinjury fell 51 % for each month return was delayed, up to
      9 months. 38.2 % of those failing criteria (>90 % on strength, hop and self-report) were reinjured vs 5.6 % of
      those passing (HR 0.16, p = 0.075).
    - Professional athletes (Kyritsis et al. 2016, n = 158): failing any of 6 discharge criteria meant 4.1× the risk of
      graft rupture.
  - Meta-analyses are **equivocal**:
    - Passing return-to-sport batteries lowered graft rupture (RR 0.40) but **raised contralateral ACL injury** (RR 3.35).
      Only 23 % of patients passed (Webster & Hewett 2019, **L1**).
    - Passing criteria gave a non-significant 3 % risk reduction, at very low GRADE certainty (Losciale et al. 2019,
      **L1**).
  - Best practice is **criteria plus a minimum time floor**, not either alone.
- **Strength symmetry (limb symmetry index, LSI ≥90 %):**
  - The ≥90 % LSI threshold is standard in ACL protocols (Adams 2012; Grindem 2016).
  - However, LSI **overestimates function** because the uninjured side also detrains. 57 % of patients met 90 % LSI, but
    only 29 % met 90 % of *pre-injury* capacity (EPIC), and EPIC predicted second ACL injuries better (Wellsandt et al.
    2017, **L3**).
  - For CruxCoach: if the user has pre-injury hangboard or pick-up maxima in the app, compare the injured hand with
    **both** the healthy hand and the pre-injury value. Require ≥90 % of the higher reference.
- **Tendinopathy return to sport:** definitions and criteria are inconsistent and poorly operationalised. The categories
  used are pain, functional recovery, strength, range of motion, endurance, medical advice, psychosocial factors and
  tissue properties (Habets et al. 2018, **L1**). Usable anchors:
  - Silbernagel: walking pain ≤1–2/10 and hop pain ≤2/10.
  - PTLE: all stage-4 exercises at ≤3/10.
- **Ankle sprain consensus (PAASS):** Pain (during sport and over the last 24 h), Ankle impairments, Athlete perception
  (confidence, psychological readiness), Sensorimotor control, Sport/functional performance (Smith et al. 2021, **L1/L5**,
  Delphi, 155 experts). This is a good template structure for finger return decisions.
- **Psychological readiness:** fear of reinjury is a common reason for not returning. Positive psychological responses
  are associated with returning to pre-injury level (Ardern et al. 2013, **L1**). Validated scales are ACL-specific
  (ACL-RSI; Webster et al. 2008, **L3**) or generic (I-PRRS; Glazer 2009, **L3**). There is no finger-specific scale. A
  short confidence/fear check (0–10), adapted and unvalidated, is a reasonable app proxy.
- **Pulley-specific time anchors:**
  - The Bamberg 2026 scheme for conservatively treated ruptures: ring 6–8 weeks, then careful hangs with relief bands,
    then climbing built up to full load over 4–6 weeks with H-tape. **Full load and competition readiness after
    ~4 months**, with taping continued for at least a year (Strohm & Schöffl 2026, **L5 [direct]**).
  - Commonly quoted grade-based timelines attributed to Schöffl (full climbing: grade I ~6 weeks, grade II 6–8 weeks,
    grade III ~3 months, operated grade IV ~6 months) come from **secondary summaries**. Verify against the
    finger-injury report before encoding.
  - The owner's return criterion: injured hand in half crimp ≥90 % of the healthy hand's max for 6 × 10 s at ≤1/10 pain,
    pressure pain clearly falling for ≥1 week, then full crimp starting at ~40 % of the half-crimp load. This is an
    ACL-style LSI rule **[extrapolated, L5]**. It is consistent with the frameworks above if combined with a time floor
    by grade.

---

## 6. Load management concepts for an app

- **Acute:chronic workload ratio (ACWR):**
  - It was popularised with a "sweet spot" of 0.8–1.3 and a danger zone above 1.5 (Gabbett 2016, **L5**), and the
    load concept was adopted by the IOC consensus (Soligard et al. 2016, **L1/L5**).
  - Strong critiques since: mathematical coupling (Lolli et al. 2019, **L5**); conceptual flaws (Impellizzeri et al.
    2020, **L5**); with contrived or random chronic loads the ratio gives similar odds ratios and no predictive advantage
    over an intercept-only model, so the authors recommend dismissing it (Impellizzeri et al. 2021, **L3**
    re-analysis); methodological pitfalls (Wang et al. 2020, **L5**).
  - **Do not compute or display an ACWR "injury-risk" score.**
- **Avoid spikes, especially single-session spikes:**
  - In 5205 runners, a session more than 10 % longer than the longest run of the previous 30 days raised overuse-injury
    rates (HR ~1.5–2.3 by spike size). Weekly ACWR showed a *negative* dose-response, and week-to-week ratios no
    relationship (Frandsen et al. 2025, **L3**).
  - A >30 % increase in weekly distance over 2 weeks was associated with distance-related injuries (HR 1.59,
    exploratory; Nielsen et al. 2014, **L3**).
  - Yet a graded "10 % rule" program did not reduce injuries in novice runners (Buist et al. 2008, **L2**).
  - App translation [extrapolated]: cap **single-session** peaks (max hang load, crimp volume, board time) relative to
    the recent 30-day maximum, rather than relying on weekly ratios.
- **Gradual exposure:** progression in pre-planned small steps, gated by symptoms (sections 3–4). It is also the core
  mechanism in the PTLE, HSR and Silbernagel programs.
- **Monitoring signals:**
  - Pain during the session, pain that evening, and next-morning pain and stiffness (Silbernagel; Beyer diaries).
  - A standardised daily provocation test (section 3.4).
  - **Pressure (palpation) pain** at the same spot, 0–10. Self-palpation is pragmatic but **unvalidated**. Pressure-pain
    thresholds by algometry are used in research (e.g., Holden 2020), but they changed little with exercise and are not
    a validated progression marker.
  - Session RPE × duration as internal load (Foster et al. 2001, **L3**).
  - Simple function: pain-free grip or hang load.
- **Deload rules:**
  - No rehab trial supports a fixed deload cadence. Evidence-anchored deloads are **symptom-triggered** (soreness rules;
    Silbernagel week-to-week rule).
  - A planned lighter week every 3–4 weeks is S&C convention (L5). It aligns with Silbernagel's 3–4-weekly activity
    reclassification. Use it as an optional default, not as an evidence claim.

---

## 7. Digital tools: responsible presentation (short; regulation covered elsewhere)

- **Evidence-based digital MSK programs:**
  - Kaia back-pain app: pain 2.7 vs 3.4/10 at 12 weeks vs physiotherapy plus online education (Toelle et al. 2019,
    **L2**, n = 101).
  - Hinge Health: a digital knee program reduced pain (Mecklenburg et al. 2018, **L2**). A 10,000-participant cohort
    (Bailey et al. 2020, **L3**).
  - Joint Academy (digital first-line osteoarthritis education and exercise).
  - For a stable finger injury, self-managed exercise was non-inferior to supervised hand therapy (PINOT 2026, **L2**).
- **Quality across app stores:** a review of back-pain apps found developers mostly choose guideline-endorsed
  interventions, but app quality is low and effectiveness unevaluated (Machado et al. 2016, **L1**). Digital MSK
  interventions show mixed but often positive effects (Hewitt et al. 2020, **L1**).
- **What responsible programs do** (pattern across the above, L5):
  - **Triage before prescribing:** onset and mechanism, "pop", swelling, deformity, bowstringing, inability to flex the
    fingertip, numbness, adolescents (growth-plate injuries; Schöffl et al. 2022, L3 [direct]). Red flags lead to "see a
    clinician first" instead of a plan.
  - **Explicit escalation triggers:** worsening, no improvement after 2–3 weeks, repeated regressions, new symptoms.
  - **Shareable logs** for clinicians.
  - **Conservative defaults** and transparent evidence levels.
  - **Language that informs and supports rather than diagnoses.**
- **Regulatory pointers:**
  - **EU MDR** (Regulation 2017/745). Art. 2(1) lists "treatment, alleviation of, or compensation for, an injury" as a
    medical purpose, so software intended to rehabilitate injuries is likely medical-device software.
  - **Annex VIII Rule 11:** software providing information "used to take decisions with diagnosis or therapeutic
    purposes" is class IIa or higher; all other software is class I. See MDCG 2019-11 rev.1 (June 2025).
  - **US FDA:** the *General Wellness: Policy for Low Risk Devices* guidance (revised January 2026) exempts low-risk
    products with wellness-only claims. Claims to treat a specific injury point toward device status.
  - Wording ("training to rebuild finger load tolerance" vs "treatment of pulley rupture") and the decision logic matter.
    Defer to the regulation report.

---

## Rules an app can encode

Conventions:
- **Pain variables:** `P_during` (max NRS at the injured site during the session), `P_evening`, `P_morning` (next
  morning), `PP` (palpation pressure pain, same spot, 0–10), `PT` (provocation-test pain), `baseline` (median of the last
  3 mornings).
- **Profiles:** `ACUTE` = acute ligament/pulley injury ≤12 weeks; `OVERUSE` = tendinopathy-like or tenosynovitis-like
  persistent pain.
- Evidence levels as above. "Extrap." means borrowed from non-finger tissue.

### A. Gating and phases

| # | Rule | Default parameters | Basis (level) |
|---|---|---|---|
| A1 | **Triage gate**: no tissue-specific plan until the intake screen passes; red flags → advise clinician/imaging, offer only general training of uninjured areas | Red flags (finger): audible pop plus visible bowstringing, inability to actively flex DIP/PIP, gross swelling or deformity, numbness or colour change, age <18 with joint pain (growth plate), suspected multi-pulley rupture | Schöffl 2003 (L3); Miro 2021 (L5); Schöffl 2022 (L3); details in finger report |
| A2 | **Branch by presentation**: `ACUTE` uses time-phased gates plus symptom gates; `OVERUSE` uses symptom/capacity stages only | — | Sharma & Maffulli 2005/2006 (L5); Cook & Purdam 2009 (L5) |
| A3 | **Relative rest, never total rest**: from day 1 keep training non-injured regions and general fitness; remove only the provoking task | — | Silbernagel 2007 (L2); Strohm & Schöffl 2026 (L5); PEACE & LOVE (L5) |
| A4 | **Phase floors for `ACUTE`** (earliest allowed, never "due"): ≤7 d protection and pain-free motion; from ~day 7 protected isometrics in the safest grip; heavy and crimp-specific loading not before ~6 weeks | Floors scale with grade; suspected rupture → clinical grading first and protection phase per clinician (Bamberg: ring 6–8 wk, no hanging in ring) | Sharma & Maffulli 2005 (L5); Lamb 2009 (L2); Strohm & Schöffl 2026 (L5) |
| A5 | **Stage advance needs time *and* criteria**: ≥7 days at the current stage **and** all green (B1–B3) **and** `PT` ≤ stage threshold | `PT` ≤2 (`ACUTE`) / ≤3 (`OVERUSE`) | Breda 2021 PTLE (L2); soreness rules (L5) |

### B. Pain monitoring (traffic light)

| # | Rule | Default parameters | Basis (level) |
|---|---|---|---|
| B1 | **During-session ceiling** | `ACUTE`: green ≤2, amber 3, red ≥4 (stop set, reduce load 10–20 %). `OVERUSE`: green ≤2, amber 3–5, red >5 | Silbernagel 2007 (L2) zones; owner plan; Hickey 2020 ≤4 safe in muscle (L2); extrap. |
| B2 | **Settles-by-morning rule** | `P_morning` ≤ `baseline` → green; +1 → amber; ≥+2 or absolute ≥5 → red | Silbernagel 2007 (L2); Beyer 2015 (L2); Farrar 2001 MCID ~2 (L3) |
| B3 | **Week-to-week rule**: 7-day mean of `P_morning` and `PP` must not rise vs the previous 7 days | Rise ≥1.0 → treat the week as amber; rise ≥2 → red | Silbernagel 2007 (L2) |
| B4 | **Progress**: whole week green (≤1 amber day allowed, no red) → advance one step | One step = next row of the plan, or +5–10 % load (≈ +1–2.5 kg on a pick-up), or the next grip position | Soreness rules "advance 1 level/week" (L5); owner plan; Beyer RM progression (L2) |
| B5 | **Repeat**: ≥2 amber days, or one red that resolved within 24 h → repeat the current week | — | Soreness rules "stay at level" (L5); owner plan |
| B6 | **Regress**: red during warm-up that persists, red next morning, or 2 reds in 7 days → 1–2 days without tissue-specific loading, then drop one level for ≥3–4 days | — | Fees 1998 / Adams 2012 soreness rules (L5); owner plan |
| B7 | **Escalate**: no downward trend in `PP` or `PT` after 2–3 weeks, 2 consecutive regressions, or any new red flag → recommend clinician/ultrasound; pause progression | — | Owner plan; general practice (L5) |

### C. Dosing

| # | Rule | Default parameters | Basis (level) |
|---|---|---|---|
| C1 | **Tendon bout (protected isometric)** | 4–5 × 30–45 s, rest 60–120 s, ≤10 min total; load set so `P_during` ≤2 and effort ~RPE 4–6 | Rio 2015 (L2); Pearson 2020 (L2/L3); Coombes 2016 (L2, don't exceed pain threshold); extrap. |
| C2 | **Bout spacing** | Tendon bouts ≤2/day, ≥6 h apart | Paxton 2012 (L4); Baar 2017 (L5): mechanistic only |
| C3 | **Strength bout** | 4–6 × 10 s isometric (or 3–4 sets of 6–15 reps isotonic); rest 2–3 min; intensity rising weekly from ~40–60 % to ≥70–85 % of current capacity (RPE 5–6 → 7–8) | Bohm 2015 (L1: ≥70 % for adaptation); Beyer 2015 (L2); Pearson 2020 (L2/L3) |
| C4 | **Heavy-session frequency** | 2–3/week, ≥48 h apart for the same tissue | Miller 2005 (L3); HSR protocols (L2); Silbernagel 2020 36–72 h (L5) |
| C5 | **Load dial order for fingers** | Open/drag → half crimp → full crimp; fixed edge depth; full crimp starts at ~40 % of the current half-crimp load | Vigouroux 2006 (L4 [direct]); Schweizer 2001 (L4 [direct]); 40 % = owner plan (L5) |
| C6 | **Measure absolute load** (kg total incl. pin and plates); store per hand and grip; express as % of reference | Reference = max(healthy-hand max, pre-injury max) | Wellsandt 2017 EPIC (L3); owner plan |
| C7 | **Pain-threshold, not pain-free, in remodelling**: from ~week 6 (`ACUTE`) allow `P_during` up to 3 | — | Hickey 2020 (L2, muscle); Smith 2017 (L1); extrap. |
| C8 | **Program duration expectations**: show users that tissue adaptation takes ≥8–12 weeks of progressive loading; full return after pulley rupture ~4 months | — | Bohm 2015 (L1); Beyer/Kongsgaard (L2); Strohm & Schöffl 2026 (L5) |
| C9 | **Maintenance** after return to sport: keep 1–2 strength/tendon sessions per week for ≥3 months | 2×/week | Breda 2021 (L2); Silbernagel 2007 phase 4 (L2) |

### D. Load management during return

| # | Rule | Default parameters | Basis (level) |
|---|---|---|---|
| D1 | **Single-session spike cap** during rehab and return: no session's peak metric >10 % above the 30-day maximum | Metrics: max hang/pick-up load per grip, crimp-hold count, board minutes | Frandsen 2025 (L3, running); Nielsen 2014 (L3); extrap. |
| D2 | **No ACWR risk score**; show load trends descriptively only | — | Impellizzeri 2020/2021 (L3/L5); Lolli 2019 (L5) |
| D3 | **Climbing re-entry ladder** (control → chaos): jugs/open holds 3–4 grades below limit → more volume → medium edges with tape, 2–3 grades below → edge routes, not at limit → harder routes with dosed small edges → board/limit. One rung per week only if the week was green (B4) | Mirrors the owner's weeks 1–6 | Taberner 2019 (L5); Strohm & Schöffl 2026 (L5) |
| D4 | **Optional deload**: one lighter week (~60–70 % of the previous week's volume) every 3–4 weeks; mandatory deload only via B6 | — | S&C convention (L5); Silbernagel 3–4-weekly reclassification (L5) |
| D5 | **Tape prompt** during climbing re-entry for pulley injuries (H-tape) | From the climbing re-entry phase; keep ≥3–12 months per clinician | Schöffl I 2007 (L3 [direct]); Larsson 2022 (L1, low certainty); Strohm & Schöffl 2026 (L5) |

### E. Return-to-sport gate (all must be true)

| # | Criterion | Default | Basis (level) |
|---|---|---|---|
| E1 | Time floor by severity passed | Per finger report / clinician; ruptures ~4 months to full load | Strohm & Schöffl 2026 (L5); Grindem 2016 time effect (L3) |
| E2 | Strength: injured hand ≥90 % of the reference (C6) in the target grip for 6 × 10 s | 90 % | Grindem 2016; Kyritsis 2016 (L3); Wellsandt 2017 (L3); owner plan |
| E3 | Pain at that load ≤1/10, no next-morning rise | ≤1 | Owner plan; Silbernagel 2020 ≤1–2 for walking (L5) |
| E4 | `PP` trending down ≥7 days (slope <0, no value above the previous week's max) | ≥7 days | Owner plan (L5) |
| E5 | Psychological readiness: confidence ≥7/10 for the target activity; if lower, extend D3 rather than block | ≥7/10 (unvalidated proxy) | Ardern 2013 (L1); Ardern 2016 (L1/L5); ACL-RSI concept (L3) |
| E6 | Shared decision: show StARRT-style summary (tissue status, planned activity risk, user goal) and recommend clinician review for ruptures | — | Shrier 2015 (L5); Ardern 2016 |

### F. Nutrition adjunct (optional, opt-in)

| # | Rule | Default | Basis (level) |
|---|---|---|---|
| F1 | Offer gelatin/collagen + vitamin C before the first tendon bout of the day as **optional, low-certainty** | 10–15 g gelatin or 15–30 g hydrolysed collagen + ~50 mg vitamin C, 30–60 min before | Shaw 2017 (L2, n = 8); Lee 2024 (L2); Bischof 2024 (L1, very low certainty); Hijlkema 2022 (L1, low) |
| F2 | **Safety prompts** before enabling F1; show a warning rather than silently blocking | Kidney-stone history → warn (oxalate; avoid gram-dose vitamin C); alpha-gal or gelatin allergy → do not suggest gelatin; dietary restrictions → offer alternatives or skip | Knight 2006 (L3); Thomas 2013 (L3); Platts-Mills 2020 (L5); owner rule "warn, don't lock" |
| F3 | Never shorten timelines or relax gates because the user takes supplements | — | No outcome evidence in ligament/pulley injury |

### Reference pseudo-logic (daily)

```
classify day:
  red    if P_during >= ceiling_red(profile, phase) or P_morning - baseline >= 2 or P_morning >= 5
  amber  if P_during in amber band or P_morning - baseline == 1
  green  otherwise
weekly (after >= 7 days at level):
  if any red_flag: pause + escalate
  if reds >= 2 or red persisting next morning: rest 1-2 days specific loading; level -= 1 (hold >= 3-4 days)
  elif ambers >= 2 or single red resolved < 24 h: repeat week
  elif 7d_mean(P_morning, PP) not rising and PT <= stage_threshold: level += 1 (one step, spike cap D1)
  if no downward PP/PT trend after 14-21 days or 2 consecutive regressions: recommend clinician
```

---

## Where evidence is thin (explicit caveats for product copy)

1. **No RCTs exist for pulley or finger-flexor loading protocols.** All finger dosing is extrapolated from Achilles,
   patellar, elbow and shoulder tendons and ankle/knee ligaments, plus biomechanics and expert clinic schemes.
2. **The 6-hour refractory period** is from engineered-tissue and animal-bone work. It has not been tested in human
   tendons or ligaments.
3. **Pain ceilings for acute ligament/pulley injury** (≤2/10) are expert convention. Only tendinopathy (≤5/10) and acute
   muscle strain (≤4/10) have trial support.
4. **The isometric analgesia effect** did not replicate reliably. Do not claim it.
5. **Collagen/gelatin:** surrogate markers and small trials; no ligament/pulley data. Certainty is very low to low.
6. **The LSI ≥90 % threshold** comes from ACL research, where even there it predicts reinjury inconsistently.
7. **Self-palpation pressure pain** and a hang-based provocation test are pragmatic, unvalidated monitoring tools.
8. **Phase durations** are approximate and overlap. Pulley-specific healing timelines are clinical, not histological.

---

## References

### Healing, mechanobiology, unloading
- Sharma P, Maffulli N. Tendon injury and tendinopathy: healing and repair. *J Bone Joint Surg Am* 2005;87:187–202. doi:10.2106/JBJS.D.01850 (timings as tabulated in Wellings et al., *Orthop J Sports Med* 2021, doi:10.1177/23259671211062929)
- Sharma P, Maffulli N. Biology of tendon injury: healing, modeling and remodeling. *J Musculoskelet Neuronal Interact* 2006;6:181–190. PMID 16849830
- Leong NL, et al. Tendon and ligament healing and current approaches to tendon and ligament regeneration. *J Orthop Res* 2020;38:7–12. doi:10.1002/jor.24475
- Hildebrand KA, Frank CB. Scar formation and ligament healing. *Can J Surg* 1998;41:425–429. PMID 9854530
- Woo SL-Y, et al. Injury and repair of ligaments and tendons. *Annu Rev Biomed Eng* 2000;2:83–118. doi:10.1146/annurev.bioeng.2.1.83; Woo lab MCL healing summary (1993): https://sites.pitt.edu/~msrc/publications/1993/woo_7b.html
- Khan KM, Scott A. Mechanotherapy: how physical therapists' prescription of exercise promotes tissue repair. *Br J Sports Med* 2009;43:247–252. doi:10.1136/bjsm.2008.054239
- Killian ML, et al. The role of mechanobiology in tendon healing. *J Shoulder Elbow Surg* 2012;21:228–237. doi:10.1016/j.jse.2011.11.002
- Magnusson SP, Kjaer M. The impact of loading, unloading, ageing and injury on the human tendon. *J Physiol* 2019;597:1283–1298. doi:10.1113/JP275450
- Magnusson SP, Langberg H, Kjaer M. The pathogenesis of tendinopathy: balancing the response to loading. *Nat Rev Rheumatol* 2010;6:262–268. doi:10.1038/nrrheum.2010.43
- Miller BF, et al. Coordinated collagen and muscle protein synthesis in human patella tendon and quadriceps muscle after exercise. *J Physiol* 2005;567:1021–1033. doi:10.1113/jphysiol.2005.093690
- Langberg H, et al. Type I collagen synthesis and degradation in peritendinous tissue after exercise determined by microdialysis in humans. *J Physiol* 1999;521:299–306. doi:10.1111/j.1469-7793.1999.00299.x
- Heinemeier KM, et al. Lack of tissue renewal in human adult Achilles tendon is revealed by nuclear bomb ¹⁴C. *FASEB J* 2013;27:2074–2079. doi:10.1096/fj.12-225599
- Reeves ND, et al. Influence of 90-day simulated microgravity on human tendon mechanical properties. *J Appl Physiol* 2005;98:2278–2286. doi:10.1152/japplphysiol.01266.2004
- Couppé C, et al. The effects of immobilization on the mechanical properties of the patellar tendon in younger and older men. *Clin Biomech* 2012;27:949–954. doi:10.1016/j.clinbiomech.2012.06.003
- Christensen B, et al. Short-term immobilization and recovery affect skeletal muscle but not collagen tissue turnover in humans. *J Appl Physiol* 2008;105:1845–1851. doi:10.1152/japplphysiol.90445.2008
- Cook JL, Purdam CR. Is tendon pathology a continuum? *Br J Sports Med* 2009;43:409–416. doi:10.1136/bjsm.2008.051193
- Cook JL, Docking SI. Defining "tissue capacity". *Br J Sports Med* 2015;49:1484–1485. doi:10.1136/bjsports-2015-094849
- Dubois B, Esculier JF. Soft-tissue injuries simply need PEACE and LOVE. *Br J Sports Med* 2020;54:72–73. doi:10.1136/bjsports-2019-101253
- Bleakley CM, Glasgow P, MacAuley DC. PRICE needs updating, should we call the POLICE? *Br J Sports Med* 2012;46:220–221. doi:10.1136/bjsports-2011-090297
- Mersmann F, Bohm S, Arampatzis A. Imbalances in the development of muscle and tendon as risk factor for tendinopathies in youth athletes. *Front Physiol* 2017;8:987. doi:10.3389/fphys.2017.00987

### Early loading vs immobilisation
- Kerkhoffs GM, et al. Immobilisation and functional treatment for acute lateral ankle ligament injuries in adults. *Cochrane Database Syst Rev* 2002;(3):CD003762. doi:10.1002/14651858.CD003762 (withdrawn 2013)
- Bleakley CM, et al. Effect of accelerated rehabilitation on function after ankle sprain: RCT. *BMJ* 2010;340:c1964. doi:10.1136/bmj.c1964
- Lamb SE, et al. Mechanical supports for acute, severe ankle sprain: a pragmatic, multicentre RCT. *Lancet* 2009;373:575–581. doi:10.1016/S0140-6736(09)60206-3
- Vuurberg G, et al. Diagnosis, treatment and prevention of ankle sprains: update of an evidence-based clinical guideline. *Br J Sports Med* 2018;52:956. doi:10.1136/bjsports-2017-098106
- Willits K, et al. Operative versus nonoperative treatment of acute Achilles tendon ruptures (accelerated functional rehabilitation). *J Bone Joint Surg Am* 2010;92:2767–2775. doi:10.2106/JBJS.I.01401
- Starr HM, et al. Flexor tendon repair rehabilitation protocols: a systematic review. *J Hand Surg Am* 2013;38:1712–1717. doi:10.1016/j.jhsa.2013.06.025
- Hu CW, et al. Early versus delayed mobilization for arthroscopic rotator cuff repair: meta-analysis of RCTs. *BMC Musculoskelet Disord* 2023;24:938. doi:10.1186/s12891-023-07075-5
- Chalmer J, et al. Conservative interventions for hyperextension injuries of the PIP joints. *Cochrane Database Syst Rev* 2013;(2):CD009030. doi:10.1002/14651858.CD009030.pub2
- Salentijn DA, et al. Self-management exercise regimen versus supervised hand therapy … volar plate avulsion of the PIP joint: the PINOT RCT. *Hand (N Y)* 2026. doi:10.1177/15589447261469595

### Finger / pulley
- Schöffl V, Hochholzer T, Winkelmann HP, Strecker W. Pulley injuries in rock climbers. *Wilderness Environ Med* 2003;14:94–100. doi:10.1580/1080-6032(2003)014[0094:PIIRC]2.0.CO;2
- Schöffl VR, Einwag F, Strecker W, Schöffl I. Strength measurement and clinical outcome after pulley ruptures in climbers. *Med Sci Sports Exerc* 2006;38:637–643. doi:10.1249/01.mss.0000210199.87328.6a
- Schöffl I, Einwag F, Strecker W, Hennig F, Schöffl V. Impact of taping after finger flexor tendon pulley ruptures in rock climbers. *J Appl Biomech* 2007;23:52–62. doi:10.1123/jab.23.1.52
- Schweizer A. Biomechanical effectiveness of taping the A2 pulley in rock climbers. *J Hand Surg Br* 2000;25:102–107. doi:10.1054/jhsb.1999.0335
- Schweizer A. Biomechanical properties of the crimp grip position in rock climbers. *J Biomech* 2001;34:217–223. doi:10.1016/S0021-9290(00)00184-6
- Vigouroux L, et al. Estimation of finger muscle tendon tensions and pulley forces during specific sport-climbing grip techniques. *J Biomech* 2006;39:2583–2592. doi:10.1016/j.jbiomech.2005.08.027
- Schreiber T, et al. Connective tissue adaptations in the fingers of performance sport climbers. *Eur J Sport Sci* 2015;15:696–702. doi:10.1080/17461391.2015.1048747
- Miro PH, vanSonnenberg E, Sabb DM, Schöffl V. Finger flexor pulley injuries in rock climbers. *Wilderness Environ Med* 2021;32:247–258. doi:10.1016/j.wem.2021.01.011
- Larsson R, Nordeman L, Blomdahl C. To tape or not to tape: pulley injuries in rock climbers – a systematic review. *BMC Sports Sci Med Rehabil* 2022;14:148. doi:10.1186/s13102-022-00539-6
- Ehiogu UD, Schöffl V, Jones G. Rehabilitation of annular pulley injuries of the fingers in climbers: a clinical commentary. *Curr Sports Med Rep* 2023;22:345–352. doi:10.1249/JSR.0000000000001107
- Manjunathan A, Liem B. Climbing pulley injuries: evaluation, treatment and prevention. *Curr Sports Med Rep* 2026;25:196–202. doi:10.1249/JSR.0000000000001348
- Strohm P, Schöffl V. Therapeutic guidelines for conservative therapy of finger pulley injuries (Klinikum Bamberg, Stand 01/2026). https://www.sozialstiftung-bamberg.de/fileadmin/default/Klinikum/kliniken-und-experten/orthopaedie/Nachbehandlungschema_Ringband_konservativ_englisch.pdf
- Schöffl V, et al. Evaluation of a diagnostic-therapeutic algorithm for finger epiphyseal growth plate stress injuries in adolescent climbers. *Am J Sports Med* 2022;50:229–237. doi:10.1177/03635465211056956
- Secondary summary of Schöffl grade-based timelines (verify before use): The Climbing Doctor, "Pulley injuries explained – Part 2". https://theclimbingdoctor.com/pulley-injuries-explained-part-2/

### Loading modalities
- Rio E, et al. Isometric exercise induces analgesia and reduces inhibition in patellar tendinopathy. *Br J Sports Med* 2015;49:1277–1283. doi:10.1136/bjsports-2014-094386 (protocol details also in ACTRN12616000752460)
- Rio E, et al. Isometric contractions are more analgesic than isotonic contractions for patellar tendon pain: an in-season RCT. *Clin J Sport Med* 2017;27:253–259. doi:10.1097/JSM.0000000000000364
- van Ark M, et al. Do isometric and isotonic exercise programs reduce pain in athletes with patellar tendinopathy in-season? *J Sci Med Sport* 2016;19:702–706. doi:10.1016/j.jsams.2015.11.006
- Holden S, et al. Isometric exercise and pain in patellar tendinopathy: a randomized crossover trial. *J Sci Med Sport* 2020;23:208–214. doi:10.1016/j.jsams.2019.09.015
- O'Neill S, et al. Acute sensory and motor response to 45-s heavy isometric holds … Achilles tendinopathy. *Knee Surg Sports Traumatol Arthrosc* 2019;27:2765–2773. doi:10.1007/s00167-018-5050-z
- Coombes BK, et al. Isometric exercise above but not below an individual's pain threshold influences pain perception in lateral epicondylalgia. *Clin J Pain* 2016;32:1069–1075. doi:10.1097/AJP.0000000000000365
- Parle PJ, et al. Acute rotator cuff tendinopathy: does ice, low load isometric exercise, or a combination produce an analgaesic effect? *Br J Sports Med* 2017;51:208–209. doi:10.1136/bjsports-2016-096107
- Clifford C, et al. Effectiveness of isometric exercise in the management of tendinopathy: systematic review and meta-analysis. *BMJ Open Sport Exerc Med* 2020;6:e000760. doi:10.1136/bmjsem-2020-000760
- Pearson SJ, et al. Immediate and short-term effects of short- and long-duration isometric contractions in patellar tendinopathy. *Clin J Sport Med* 2020;30:335–340. doi:10.1097/JSM.0000000000000625
- Challoumas D, et al. Management of patellar tendinopathy: systematic review and network meta-analysis. *BMJ Open Sport Exerc Med* 2021;7:e001110. doi:10.1136/bmjsem-2021-001110
- Kongsgaard M, et al. Corticosteroid injections, eccentric decline squat training and heavy slow resistance training in patellar tendinopathy. *Scand J Med Sci Sports* 2009;19:790–802. doi:10.1111/j.1600-0838.2009.00949.x
- Beyer R, et al. Heavy slow resistance versus eccentric training as treatment for Achilles tendinopathy: an RCT. *Am J Sports Med* 2015;43:1704–1711. doi:10.1177/0363546515584760
- Alfredson H, et al. Heavy-load eccentric calf muscle training for chronic Achilles tendinosis. *Am J Sports Med* 1998;26:360–366. doi:10.1177/03635465980260030301
- Malliaras P, et al. Achilles and patellar tendinopathy loading programmes: a systematic review. *Sports Med* 2013;43:267–286. doi:10.1007/s40279-013-0019-z
- Murphy MC, et al. Efficacy of heavy eccentric calf training for mid-portion Achilles tendinopathy: SR/MA. *Br J Sports Med* 2019;53:1070–1077. doi:10.1136/bjsports-2018-099934
- van der Vlist AC, et al. Which treatment is most effective for Achilles tendinopathy? Living SR with NMA. *Br J Sports Med* 2021;55:249–256. doi:10.1136/bjsports-2019-101872
- Breda SJ, et al. Effectiveness of progressive tendon-loading exercise therapy in patellar tendinopathy: an RCT. *Br J Sports Med* 2021;55:501–509. doi:10.1136/bjsports-2020-103403
- Karanasios S, et al. Exercise interventions in lateral elbow tendinopathy … SR/MA of 30 trials. *Br J Sports Med* 2021;55:477–485. doi:10.1136/bjsports-2020-102525
- Naunton J, et al. Progressive and resisted vs non-progressive exercise in rotator cuff related shoulder pain: SR/MA. *Clin Rehabil* 2020;34:1198–1216. doi:10.1177/0269215520934147
- Bohm S, Mersmann F, Arampatzis A. Human tendon adaptation in response to mechanical loading: SR/MA. *Sports Med Open* 2015;1:7. doi:10.1186/s40798-015-0009-9
- Arampatzis A, Karamanidis K, Albracht K. Adaptational responses of the human Achilles tendon by modulation of the applied cyclic strain magnitude. *J Exp Biol* 2007;210:2743–2753. doi:10.1242/jeb.003814
- Arampatzis A, et al. Plasticity of human Achilles tendon mechanical and morphological properties in response to cyclic strain. *J Biomech* 2010;43:3073–3079. doi:10.1016/j.jbiomech.2010.08.014
- Malliaras P, Cook J, Purdam C, Rio E. Patellar tendinopathy: clinical diagnosis, load management, and advice for challenging case presentations. *J Orthop Sports Phys Ther* 2015;45:887–898. doi:10.2519/jospt.2015.5987
- Rio E, et al. Tendon neuroplastic training. *Br J Sports Med* 2016;50:209–215. doi:10.1136/bjsports-2015-095215
- Smith BE, et al. Should exercises be painful in the management of chronic musculoskeletal pain? SR/MA. *Br J Sports Med* 2017;51:1679–1687. doi:10.1136/bjsports-2016-097383
- Hickey JT, et al. Pain-free versus pain-threshold rehabilitation following acute hamstring strain injury: an RCT. *J Orthop Sports Phys Ther* 2020;50:91–103. doi:10.2519/jospt.2020.8895
- Chimenti RL, et al. Midportion Achilles tendinopathy CPG – revision 2024. *J Orthop Sports Phys Ther* 2024;54:CPG1–CPG32. doi:10.2519/jospt.2024.0302
- de Vos RJ, et al. Dutch multidisciplinary guideline on Achilles tendinopathy. *Br J Sports Med* 2021;55:1125–1134. doi:10.1136/bjsports-2020-103867
- Scott A, et al. ICON 2019: International Scientific Tendinopathy Symposium Consensus: clinical terminology. *Br J Sports Med* 2020;54:260–262. doi:10.1136/bjsports-2019-100885
- Vicenzino B, et al. ICON 2019: nine core health-related domains for tendinopathy. *Br J Sports Med* 2020;54:444–451. doi:10.1136/bjsports-2019-100894

### Pain monitoring
- Thomeé R. A comprehensive treatment approach for patellofemoral pain syndrome in young women. *Phys Ther* 1997;77:1690–1703. doi:10.1093/ptj/77.12.1690
- Silbernagel KG, Thomeé R, Eriksson BI, Karlsson J. Continued sports activity, using a pain-monitoring model, during rehabilitation in patients with Achilles tendinopathy: an RCT. *Am J Sports Med* 2007;35:897–906. doi:10.1177/0363546506298279
- Silbernagel KG, Crossley KM. A proposed return-to-sport program for patients with midportion Achilles tendinopathy. *J Orthop Sports Phys Ther* 2015;45:876–886. doi:10.2519/jospt.2015.5885
- Silbernagel KG, Hanlon S, Sprague A. Current clinical concepts: conservative management of Achilles tendinopathy. *J Athl Train* 2020;55:438–447. doi:10.4085/1062-6050-356-19
- Silbernagel KG. Tendinopathy and sports (course handout, 2009; pain-monitoring zones). https://www.mckenzieinstituteusa.org/forms/SYLLABUS_Tendinopathy%20and%20Sport%20KGS%20handout.pdf
- Fees M, Decker T, Snyder-Mackler L, Axe MJ. Upper extremity weight-training modifications for the injured athlete. *Am J Sports Med* 1998;26:732–742. doi:10.1177/03635465980260052301
- Adams D, et al. Current concepts for ACL reconstruction: a criterion-based rehabilitation progression. *J Orthop Sports Phys Ther* 2012;42:601–614. doi:10.2519/jospt.2012.3871
- Farrar JT, et al. Clinical importance of changes in chronic pain intensity measured on an 11-point NRS. *Pain* 2001;94:149–158. doi:10.1016/S0304-3959(01)00349-9

### Refractory period and collagen nutrition
- Paxton JZ, Hagerty P, Andrick JJ, Baar K. Optimizing an intermittent stretch paradigm using ERK1/2 phosphorylation results in increased collagen synthesis in engineered ligaments. *Tissue Eng Part A* 2012;18:277–284. doi:10.1089/ten.TEA.2011.0336
- Baar K. Minimizing injury and maximizing return to play: lessons from engineered ligaments. *Sports Med* 2017;47(Suppl 1):5–11. doi:10.1007/s40279-017-0719-x
- Robling AG, Burr DB, Turner CH. Recovery periods restore mechanosensitivity to dynamically loaded bone. *J Exp Biol* 2001;204:3389–3399. doi:10.1242/jeb.204.19.3389
- Shaw G, et al. Vitamin C–enriched gelatin supplementation before intermittent activity augments collagen synthesis. *Am J Clin Nutr* 2017;105:136–143. doi:10.3945/ajcn.116.138594
- Lis DM, Baar K. Effects of different vitamin C–enriched collagen derivatives on collagen synthesis. *Int J Sport Nutr Exerc Metab* 2019;29:526–531. doi:10.1123/ijsnem.2018-0385
- Lee J, et al. The collagen synthesis response to an acute bout of resistance exercise is greater when ingesting 30 g hydrolyzed collagen compared with 15 g and 0 g. *J Nutr* 2024;154:2076–2086. doi:10.1016/j.tjnut.2023.10.030
- Aussieker T, et al. Collagen protein ingestion during recovery from exercise does not increase muscle connective protein synthesis rates. *Med Sci Sports Exerc* 2023;55:1792–1802. doi:10.1249/MSS.0000000000003214
- Jerger S, et al. Effects of specific collagen peptide supplementation combined with resistance training on Achilles tendon properties. *Scand J Med Sci Sports* 2022;32:1131–1141. doi:10.1111/sms.14164
- Lee J, et al. Collagen supplementation augments changes in patellar tendon properties in female soccer players. *Front Physiol* 2023;14:1089971. doi:10.3389/fphys.2023.1089971
- Praet SFE, et al. Oral supplementation of specific collagen peptides combined with calf-strengthening exercises … Achilles tendinopathy. *Nutrients* 2019;11:76. doi:10.3390/nu11010076
- Bischof K, et al. Impact of collagen peptide supplementation in combination with long-term physical training …: SR/MA. *Sports Med* 2024;54:2865–2888. doi:10.1007/s40279-024-02079-0
- Hijlkema A, et al. The impact of nutrition on tendon health and tendinopathy: a systematic review. *J Int Soc Sports Nutr* 2022;19:474–504. doi:10.1080/15502783.2022.2104130
- Buchalski A, et al. Collagen supplementation on tendon-related structural and performance outcomes: a systematic review. *J Funct Morphol Kinesiol* 2026;11:130. doi:10.3390/jfmk11010130
- Holwerda AM, van Loon LJC. The impact of collagen protein ingestion on musculoskeletal connective tissue remodeling: a narrative review. *Nutr Rev* 2022;80:1497–1514. doi:10.1093/nutrit/nuab083
- van Dam L, et al. The JUMPFOOD study (protocol). *Trials* 2023;24:768. doi:10.1186/s13063-023-07783-2; NCT05407194
- Thomas LD, et al. Ascorbic acid supplements and kidney stone incidence among men. *JAMA Intern Med* 2013;173:386–388. doi:10.1001/jamainternmed.2013.2296
- Knight J, et al. Hydroxyproline ingestion and urinary oxalate and glycolate excretion. *Kidney Int* 2006;70:1929–1934. doi:10.1038/sj.ki.5001906
- Platts-Mills TAE, et al. Diagnosis and management of patients with the α-Gal syndrome. *J Allergy Clin Immunol Pract* 2020;8:15–23. doi:10.1016/j.jaip.2019.09.017

### Return to sport
- Ardern CL, et al. 2016 Consensus statement on return to sport from the First World Congress in Sports Physical Therapy, Bern. *Br J Sports Med* 2016;50:853–864. doi:10.1136/bjsports-2016-096278
- Shrier I. Strategic Assessment of Risk and Risk Tolerance (StARRT) framework for return-to-play decision-making. *Br J Sports Med* 2015;49:1311–1315. doi:10.1136/bjsports-2014-094569
- Creighton DW, et al. Return-to-play in sport: a decision-based model. *Clin J Sport Med* 2010;20:379–385. doi:10.1097/JSM.0b013e3181f3c0fe
- Taberner M, Allen T, Cohen DD. Progressing rehabilitation after injury: consider the "control-chaos continuum". *Br J Sports Med* 2019;53:1132–1136. doi:10.1136/bjsports-2018-100157
- Grindem H, et al. Simple decision rules can reduce reinjury risk by 84 % after ACL reconstruction. *Br J Sports Med* 2016;50:804–808. doi:10.1136/bjsports-2016-096031
- Kyritsis P, et al. Likelihood of ACL graft rupture: not meeting six clinical discharge criteria … *Br J Sports Med* 2016;50:946–951. doi:10.1136/bjsports-2015-095908
- Webster KE, Hewett TE. What is the evidence for and validity of return-to-sport testing after ACL reconstruction? SR/MA. *Sports Med* 2019;49:917–929. doi:10.1007/s40279-019-01093-x
- Losciale JM, et al. The association between passing return-to-sport criteria and second ACL injury risk: SR/MA. *J Orthop Sports Phys Ther* 2019;49:43–54. doi:10.2519/jospt.2019.8190
- Wellsandt E, Failla MJ, Snyder-Mackler L. Limb symmetry indexes can overestimate knee function after ACL injury. *J Orthop Sports Phys Ther* 2017;47:334–338. doi:10.2519/jospt.2017.7285
- Habets B, et al. Return to sport in athletes with midportion Achilles tendinopathy: qualitative SR of definitions and criteria. *Sports Med* 2018;48:705–723. doi:10.1007/s40279-017-0833-9
- Smith MD, et al. Return to sport decisions after an acute lateral ankle sprain injury: introducing the PAASS framework. *Br J Sports Med* 2021;55:1270–1276. doi:10.1136/bjsports-2021-104087
- Ardern CL, et al. A systematic review of the psychological factors associated with returning to sport following injury. *Br J Sports Med* 2013;47:1120–1126. doi:10.1136/bjsports-2012-091203
- Webster KE, Feller JA, Lambros C. ACL-RSI scale development. *Phys Ther Sport* 2008;9:9–15. doi:10.1016/j.ptsp.2007.09.003
- Glazer DD. Development and preliminary validation of the I-PRRS scale. *J Athl Train* 2009;44:185–189. doi:10.4085/1062-6050-44.2.185

### Load management
- Gabbett TJ. The training–injury prevention paradox. *Br J Sports Med* 2016;50:273–280. doi:10.1136/bjsports-2015-095788
- Soligard T, et al. How much is too much? (Part 1) IOC consensus statement on load in sport and risk of injury. *Br J Sports Med* 2016;50:1030–1041. doi:10.1136/bjsports-2016-096581
- Impellizzeri FM, et al. Acute:chronic workload ratio: conceptual issues and fundamental pitfalls. *Int J Sports Physiol Perform* 2020;15:907–913. doi:10.1123/ijspp.2019-0864
- Impellizzeri FM, et al. What role do chronic workloads play in the ACWR? Time to dismiss ACWR. *Sports Med* 2021;51:581–592. doi:10.1007/s40279-020-01378-6
- Lolli L, et al. Mathematical coupling causes spurious correlation within the conventional ACWR. *Br J Sports Med* 2019;53:921–922. doi:10.1136/bjsports-2017-098110
- Wang C, et al. Analyzing activity and injury: lessons learned from the ACWR. *Sports Med* 2020;50:1243–1254. doi:10.1007/s40279-020-01280-1
- Buist I, et al. No effect of a graded training program on running-related injuries in novice runners: RCT. *Am J Sports Med* 2008;36:33–39. doi:10.1177/0363546507307505
- Nielsen RØ, et al. Excessive progression in weekly running distance and risk of running-related injuries. *J Orthop Sports Phys Ther* 2014;44:739–747. doi:10.2519/jospt.2014.5164
- Frandsen JSB, et al. How much running is too much? Identifying high-risk running sessions in a 5200-person cohort study. *Br J Sports Med* 2025;59:1203–1210. doi:10.1136/bjsports-2024-109380
- Foster C, et al. A new approach to monitoring exercise training. *J Strength Cond Res* 2001;15:109–115. PMID 11708692

### Digital tools and regulation (brief)
- Toelle TR, et al. App-based multidisciplinary back pain treatment versus combined physiotherapy plus online education: RCT. *npj Digit Med* 2019;2:34. doi:10.1038/s41746-019-0109-x
- Mecklenburg G, et al. Effects of a 12-week digital care program for chronic knee pain: RCT. *J Med Internet Res* 2018;20:e156. doi:10.2196/jmir.9667
- Bailey JF, et al. Digital care for chronic musculoskeletal pain: 10,000 participant longitudinal cohort study. *J Med Internet Res* 2020;22:e18250. doi:10.2196/18250
- Hewitt S, Sephton R, Yeowell G. The effectiveness of digital health interventions in the management of musculoskeletal conditions: systematic review. *J Med Internet Res* 2020;22:e15617. doi:10.2196/15617
- Machado GC, et al. Smartphone apps for the self-management of low back pain: a systematic review. *Best Pract Res Clin Rheumatol* 2016;30:1098–1109. doi:10.1016/j.berh.2017.04.002
- Regulation (EU) 2017/745 on medical devices (MDR), Art. 2(1) and Annex VIII Rule 11. https://eur-lex.europa.eu/eli/reg/2017/745/oj
- MDCG 2019-11 rev.1 (June 2025): Qualification and classification of software. https://health.ec.europa.eu/latest-updates/update-mdcg-2019-11-rev1-qualification-and-classification-software-regulation-eu-2017745-and-2025-06-17_en
- US FDA. General Wellness: Policy for Low Risk Devices (revised January 2026). https://www.fda.gov/regulatory-information/search-fda-guidance-documents/general-wellness-policy-low-risk-devices
