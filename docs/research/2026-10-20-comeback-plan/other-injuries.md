# Non-finger climbing injuries: shoulder, elbow, wrist, lower limb, trunk
## Triage, conservative rehabilitation and return to climbing: an evidence review for CruxCoach

*Research input for the CruxCoach injury-rehabilitation feature (run `injury-rehab-20261020`). Compiled 2026-10-20.
This is a literature synthesis for product design. It is not medical advice and does not replace clinical assessment.
Finger injuries (pulleys, tenosynovitis, growth-plate injuries) are covered in a separate report. Red-flag wording, escalation
UX and the EU MDR boundary are covered in `research/safety-regulation.md`; this report adds the region-specific detail.*

---

## 0. Key findings

1. **Priority order for the app** (non-finger), based on specialist-clinic series from Bamberg (Schöffl/Lutter) and survey data:
   **shoulder** (17–20 % of all climbing injuries, the most common non-finger site), then **elbow/forearm** (8–9 %),
   then **knee and ankle/foot** (rising, mostly from bouldering falls and heel hooks), then **wrist** (5–8 %), then
   **hamstring/thigh** and **spine** (each ≤ 3–5 %) [E1, E2, E3]. In emergency-department data, lower-limb fall injuries come first [E11, E12].
2. **Climbing-specific rehabilitation evidence is thin.** Almost every dosage and return-to-climbing rule has to be
   extrapolated from general or overhead-sport guidelines (JOSPT CPGs, BJSM consensus statements). A 2026 scoping-review
   protocol confirms that rehab content and return-to-climbing criteria are "sparse" [E19]. Most climbing-specific
   recommendations are therefore **expert opinion (Level 5)**. The app must say so openly.
3. **Exercise therapy is the first-line treatment** for the most common overuse problems: rotator-cuff-related shoulder pain
   (JOSPT CPG, grade A [S1]; surgery no better than placebo surgery [S3, S4]), lateral elbow tendinopathy (grade B [L1]),
   ankle sprain (grade A [A1]) and degenerative meniscal tears (RCT [K6]). Corticosteroid injection gives short-term relief
   but worse 1-year results in tennis elbow [L2, L3].
4. **Optimal dosage is unknown.** Higher-load programmes do not reliably beat lower-load ones in rotator-cuff tendinopathy
   (very-low-certainty evidence) [S5, S6], and isometrics are not superior to isotonic exercise [S7]. The defensible defaults are
   progressive resistance exercise, 2–3 sets × 8–15 reps, 2–3×/week (or daily at low load), for 6–12 weeks, progressed by
   symptoms [S1, L1, G3]. A specific scheme from the guideline: 3 × 15 reps for 6–12 weeks for elbow tendinopathy, progressing
   isometric → isotonic → eccentric [L1].
5. **Pain rules.** Pain-monitored loading is safe and effective: up to 5/10 in Achilles tendinopathy [G1] and ≤ 4/10 in acute
   hamstring strains (no slower return, better strength) [H6]. Painful exercise gives a small short-term benefit in chronic pain [G2].
   The owner's finger rule (≤ 2/10, next morning not worse) is a conservative, defensible app default. Only nerve symptoms and
   instability need stricter "no provocation" rules.
6. **Imaging findings mislead in climbers.** 80 % of asymptomatic elite climbers have intra-articular shoulder findings on MRI
   (labral tears 56 %) [S15]. 30 years of climbing increases labral and biceps changes without loss of function [S16].
   The app must never let a scan result drive the programme on its own.
7. **Strongest triage tools:** Ottawa ankle and knee rules (sensitivity ≈ 98–100 %) [A4, A5, K9, K10], the elbow extension
   test [L6], the Canadian C-spine rule [B6], the distal-biceps hook test [L5] and the IFOMPT spinal red-flag framework [B5].
   An app can ask about the self-reportable parts (can you take 4 steps; can you fully straighten your elbow; is there bone
   tenderness here) and route to imaging when any is positive.
8. **Heel hooks** cause 40 % of acute knee injuries in climbers (ITB sprain, LCL, meniscus) [K1] and most acute hamstring
   injuries, including proximal avulsions that are often missed at first [H1, H3]. A suspected avulsion (pop, large bruise,
   sit-bone pain, trouble walking) needs specialist assessment within days, because the surgical decision is time-sensitive [H8, H9].
9. **Bouldering falls** drive ankle sprains and fractures, calcaneus/talus fractures, complex knee injuries (ACL + meniscus)
   and shoulder dislocations [E10, E11, K2, A8]. Return-to-climbing advice for any leg injury should therefore put
   **rope climbing on overhangs and down-climbing before bouldering landings** [K1].
10. **First-time traumatic shoulder dislocation** in a young climber recurs in ≈ 47 % with sling-only care versus ≈ 6 %
    after stabilisation (meta-analysis of RCTs) [S17]. Arthroscopic repair in climbers gives a high rate of return [S19].
    The app should route every first dislocation to a doctor and never run it as a self-managed programme.

---

## 1. Method and evidence grading

**Search.** WebSearch (standard and extended), WebFetch, the Europe PMC REST API (to verify authors, year, DOI and abstract
for every peer-reviewed citation) and full-text PDFs where openly available (JOSPT CPGs for rotator cuff 2025, lateral elbow
2022, ankle 2021, knee ligament 2017, meniscus 2018; Lutter 2020 MLTJ; Lutter 2020 AJSM knee; Hickey 2020).
Practitioner sources (Hooper's Beta, The Climbing Doctor) are labelled as expert opinion.

**Evidence levels (LoE) used in this report** (adapted from Oxford CEBM and the JOSPT grading):

| LoE | Meaning |
|---|---|
| **1** | Systematic review/meta-analysis of RCTs, a good RCT, a validated clinical decision rule (diagnostic SR), or a CPG recommendation graded **A** |
| **2** | Smaller or lower-quality RCT, prospective cohort, SR of cohorts; CPG grade **B** |
| **3** | Retrospective cohort, cross-sectional or case-control study; CPG grade **C** |
| **4** | Case series or case report; CPG grade **D** (conflicting evidence) |
| **5** | Expert opinion, Delphi/consensus without strong primary data, narrative review, practitioner content, biomechanical reasoning; CPG grade **E/F** |

Tags: **[CS]** = climbing-specific source. **[X]** = extrapolated from non-climbing populations.
Numbers in square brackets point to the reference list (§ 12).

**Important bias note.** The Bamberg series [E1, E2, K1, W1] come from a specialist sports-orthopaedic centre. They
under-represent acute fractures and ligament ruptures, which go to the nearest emergency department [E2]. ED series [E11, E12]
over-represent acute lower-limb trauma. Surveys [E3–E7] rely on self-report. No single source gives "true" incidence.

---

## 2. Epidemiology: which non-finger injuries matter most

### 2.1 Body-region distribution in specialist-clinic series (Bamberg, same methodology three times) [CS]

| Region | 1998–2001 (n = 604) | 2009–2012 (n = 911) | 2017–2018 (n = 633) |
|---|---|---|---|
| Finger | 41 % | 52 % | 41.2 % |
| **Shoulder** | 5 % | 17.2 % | **20.2 %** |
| Hand | 7.8 % | 13.1 % | 7.7 % |
| **Forearm/elbow** | 13.4 % | 9.1 % | 7.7 % |
| **Lower leg/foot** | 9.1 % | 3.8 % | **10.6 %** |
| **Knee** | 3.6 % | 2.1 % | **7.1 %** |
| Trunk/spine/pelvis | 7.1 % | 2.3 % | 5.4 % |

Sources: Lutter et al. 2020, Table II [E2], which re-tabulates Schöffl et al. 2015 [E1]. LoE 4.
By OSICS site (2017–18): wrist 5.1 %, ankle 6.2 %, elbow 6.2 %, lumbar spine 1.4 %, cervical 0.5 % [E2].

**Trends** (2017–18 vs 2009–12) [E2]:
- upper-limb share falling, lower-limb share rising;
- **epicondylitis** falling (8.4 % → 5.5 % → 3.3 % of all injuries);
- **knee injuries** rising (2.3 % → 7.1 %);
- **shoulder dislocations** (10.2 % → 17.7 % of shoulder injuries) and **AC-joint injuries** (1.9 % → 9.7 %) rising, attributed
  to bouldering ground falls;
- bouldering caused 60.4 % of acute injuries.

**Shoulder diagnoses (2017–18, n = 154 shoulder injuries)** [E2]:

| Diagnosis | Share of shoulder injuries |
|---|---|
| SLAP lesion | 29.8 % |
| Subacromial impingement | 27.4 % |
| Dislocation/Bankart | 17.7 % |
| Sprain | 12.9 % |
| Rotator-cuff tear | 9.7 % |
| AC joint | 9.7 % |
| Long-head biceps tendinosis | 4.8 % |
| Non-Bankart instability | 4.0 % |

### 2.2 Surveys and cohorts

- **Grønhaug 2018** (667 climbers, chronic injuries in the last 6 months) [E3], LoE 3:
  - all: fingers 41.3 %, **shoulders 19.4 %**, **elbows 17.7 %**;
  - women: fingers 29.2 %, shoulder 21.9 %, **wrist 12.5 %**, elbow 11.5 %, **foot/ankle 10.4 %**;
  - men: fingers 45.3 %, **elbow 19.7 %**, shoulder 18.7 %.
- **Grønhaug 2024** (1,513 climbers) [E4], LoE 3: 50.3 % injured in 12 months. Shoulders were the most common site in women,
  fingers in men. Shoulder, elbow, neck and foot/ankle distributions differed significantly by sex.
- **Grønhaug 2023** (114 IFSC female elite athletes) [E5], LoE 3: 53.5 % injured in 12 months; most injuries were to the
  **shoulders (37.7 %)** and fingers (34.4 %).
- **Lutter 2019** (climbers aged ≥ 35, 275 injuries) [E13], LoE 4: 68 % were overuse injuries; **subacromial impingement**
  was the leading degenerative diagnosis.
- **Backe 2009** (606 Swedish club members) [E7], LoE 3: 4.2 injuries/1000 h; 93 % overuse; finger and wrist inflammatory
  conditions most common; bouldering and overweight raised primary-injury risk.
- **Jones 2008** (201 UK climbers) [E6], LoE 3: ≈ 50 % injured in 12 months; frequent, high-grade climbers more prone
  to finger and **shoulder** overuse injuries.
- **van Middelkoop 2015** (426 indoor climbers, prospective) [E15], LoE 2: 13 upper-limb injuries/1000 h. **Previous injury**
  (OR 3.05), campus-board use (OR 2.48) and age were risk factors.
- **Woollings 2015, youth** [E9], LoE 3: 4.44 injuries/1000 h; repetitive overuse was the main mechanism.
- **Woollings 2015, SR** [E8], LoE 2–3: candidate risk factors are age, years of experience, peak grade, climbing-intensity
  score and lead climbing; prevention evidence inconclusive.
- **Quarmby 2023, SR of 34 studies** [E16], LoE 2–3: higher climbing intensity, bouldering, low grip strength, crimp grip and
  **previous injury** increase overuse risk. A strength-training intervention prevented shoulder and elbow injuries.
  Warm-up, stretching and taping were not associated with risk.

### 2.3 Bouldering and lower-limb data (falls)

- **Auer 2021**, prospective, 507 boulderers, 12 months [E10], LoE 2: 44 % injured.
  - Upper limb 63 %, **lower limb 23 %**.
  - Lower-limb injuries were more often severe (UIAA ≥ 2: OR 2.23).
  - They were associated with **falling (OR 2.92)** and **jumping off the wall (OR 4.39)**.
  - **Heavily downturned shoes** were associated with severe injury (OR 2.58).
- **Müller 2022**, ED Munich, 430 bouldering patients [E11], LoE 4:
  - ankle 36.7 %, knee 16.8 %, elbow 12.3 %, spine 7.2 %, shoulder 6.3 %;
  - 61 % lower limb; sprains 53 %, fractures 22.8 %, dislocations 11.9 %;
  - 20 % needed surgery.
- **Buzzacott 2019**, US EDs 2008–2016 [E12], LoE 3: lower extremity 47 %; the ankle was the most commonly fractured site;
  falls caused 60 % of injuries.
- **Beurienne 2025**, 245 fall-injured boulderers [E17], LoE 3: 67 % lower limb, mainly ankle sprain; 74 % feet-first landings;
  62 % rotated during the fall.
- **Schöffl & Küpper 2013**, review [A8], LoE 5: almost half of acute climbing injuries involve the legs/feet. Strains,
  contusions and **calcaneus/talus fractures** from ground falls are most frequent. Tight shoes cause forefoot overload,
  hallux valgus/rigidus and subungual haematoma.

**App implication:** the non-finger rehab library should cover, in this order:

| Priority | Injuries |
|---|---|
| 1 | Shoulder pain: rotator-cuff/subacromial, SLAP/biceps, instability |
| 2 | Elbow tendinopathies (medial, lateral, anterior) |
| 3 | Ankle sprain |
| 4 | Knee (meniscus/MCL/ITB-LCL) |
| 5 | Ulnar-sided wrist pain |
| 6 | Hamstring (heel hook) |
| 7 | Back/neck (brief) |

---

## 3. Cross-cutting principles (all regions)

### 3.1 Load management and pain monitoring

| Source | Pain rule tested | Result | LoE |
|---|---|---|---|
| Silbernagel 2007, Achilles tendinopathy RCT [G1] | Continued running/jumping allowed using a **pain-monitoring model**: pain during activity ≤ 5/10; pain after activity allowed if it settles by the next morning; no week-on-week increase | Same improvement as 6 weeks of "active rest" | 1 [X] |
| Smith 2017, SR of 7 RCTs, chronic MSK pain [G2] | Painful vs pain-free exercise | Small short-term benefit for painful exercise (SMD −0.27); no difference medium/long term | 1 [X] |
| Hickey 2020, acute hamstring strain RCT (n = 43) [H6] | Pain-threshold rehab (**≤ 4/10 NRS**) vs pain-free (0/10) | Return-to-play clearance 17 vs 15 days (n.s.); 15 % more knee-flexor strength; better fascicle length; no extra reinjuries | 1–2 [X] |
| Owner's finger plan (TASK.md) | ≤ 2/10 during, next morning not worse | (design rule) | 5 |

**Recommendation for the app** (LoE 5, a synthesis of the above):

- **Green:** ≤ 2–3/10 during the exercise, and the next-morning baseline is not worse. Progress normally. This is the
  default and is consistent with the finger plan.
- **Amber:** 4–5/10 during, or a flare that settles within 24 h. Repeat the current step and do not progress. For tendinopathy,
  tolerable pain (≤ 5/10) that settles by the next morning is acceptable in the literature [G1], but the app should not
  advertise this as the default.
- **Red (stop and regress one step):** > 5/10, pain still increased the next morning, or a progressive week-on-week rise.
- **Region exceptions:**
  - **Nerve symptoms** (tingling, numbness): no provocation that persists beyond a few minutes. Stop if numbness persists.
  - **Instability:** *apprehension*, not pain, is the stop signal.
  - **Post-operative or fracture cases:** follow the surgeon's protocol only.

### 3.2 Dosage defaults when evidence is silent

- **Strength/tendon loading** (ACSM 2009 [G3]; JOSPT CPGs [S1, L1]):
  - 2–4 sets × 6–15 reps at an effort of about 6–8/10 (≈ 2–3 reps in reserve), 2–3×/week;
  - progress the load by ≈ 5–10 % (or one band/dumbbell step) when all sets are completed with green pain responses for one week;
  - typical block length 6–12 weeks.
  - LoE 1 for "progressive resistance helps"; LoE 4–5 for the exact numbers.
- **Isometrics:** 3–5 × 30–45 s at moderate effort as an **entry level** or for flare days. Evidence shows variable analgesia
  and no superiority over isotonic loading [S7] (LoE 1, limited).
- **Motor control/scapular work:** low load, high repetition (e.g., 2–3 × 10–20), daily possible [S9] (LoE 5).
- **Range of motion/neural glides:** daily, pain-free, short bouts [L9] (LoE 5).

### 3.3 Return-to-climbing continuum

The 2016 Bern consensus defines a continuum: return to **participation** → return to **sport** → return to **performance**,
with shared decision-making and a sport-specific risk tolerance [S23] (LoE 5).

A generic climbing ladder for all regions (LoE 5, built from [K1, S10, H7] and the owner's finger plan):

| Stage | Climbing allowed | Typical gate to enter |
|---|---|---|
| 0 Protect | No climbing that loads the injured region; off-wall rehab; train unaffected regions if pain-free (legs/core for arm injuries; hangboard/pulling for leg injuries) | Red flags excluded |
| 1 Easy | Vertical/slab, big holds, top rope, 3–4 grades below limit, no aggravating move types (§ 10 matrix), no bouldering falls for leg injuries | Daily-life activities pain-free; stage-0 exercises green for 1 week |
| 2 Volume | As 1 with more volume, then moderate angle | Stage 1 green for 1–2 weeks; key strength ≥ 70–80 % of other side |
| 3 Specific | Gradual re-exposure to the aggravating hold/move type in an easy context (e.g., underclings on jugs; heel hooks on low-angle wall) | Strength ≥ 80–90 %; region-specific tests pain ≤ 2/10 |
| 4 Hard | 1–2 grades below limit, board/steep terrain dosed, bouldering with down-climbing | Strength ≥ 90 %; functional tests passed |
| 5 Full | Limit climbing, dynos, campus, falls | All criteria met; confidence/psych readiness |

### 3.4 Imaging and "diagnosis" in an app

- JOSPT RC CPG (2025): do **not** image initially (grade F). Consider ultrasound only after ≤ 12 weeks of unsuccessful care.
  Refer to a specialist if severe pain/disability persists after ≤ 12 weeks [S1].
- In climbers specifically, abnormal MRI findings are the norm in asymptomatic shoulders [S15, S16] (LoE 3).
- **Consequence:** the app works from **symptom pattern + mechanism + red flags**, and shows the user that a diagnosis comes
  from a clinician.

### 3.5 Psychological readiness and confidence

The PAASS ankle framework [A6] and the hamstring RTP Delphi [H7] both include athlete perception and psychological readiness
as RTP criteria (LoE 5). A short confidence rating (0–10: "How confident are you to do move X?") is a cheap, sensible gate
before stage 4–5.

---

## 4. Shoulder

### 4.1 Rotator-cuff-related shoulder pain / subacromial pain ("impingement", RC tendinopathy, partial tears)

**Presentation and climbing mechanism**
- Gradual onset of pain over the lateral upper arm or deltoid region.
- Painful arc (≈ 60–120° elevation); pain when reaching overhead or behind, and when lying on the shoulder [S1, P1].
- Most common in climbers ≥ 35 [E13] and ranked #2 among shoulder diagnoses [E2].
- Typical climbing loads: sustained overhead pulling on steep terrain, lock-offs, wide or high reaches, gastons (abduction with
  internal rotation, pushing outward), plus training such as pull-ups and campus.
- Practitioners also name mantling, "chicken-wing" elbow positions, thumb-down jams and long passive hangs as provocative
  [P1, P2] (LoE 5).
- Climbers show altered scapular mechanics (higher glenohumeral-to-scapulothoracic ratio) [S22] (LoE 3) and increased
  thoracic kyphosis [B1] (LoE 3). Whether these cause pain is unproven [S8].

**Red flags and when to see a doctor first** [S1] (CPG rec. 3, grade F; LoE 5):
- **Traumatic onset with marked weakness** (cannot lift the arm; the arm drops; external-rotation lag). This suggests an acute
  full-thickness cuff tear, especially if aged > 40, and needs assessment within about 2 weeks.
- Deformity; fever or chills; signs of cardiovascular or visceral involvement (left shoulder/arm pain with exertional chest
  pressure or breathlessness: **emergency**); history of cancer.
- Neurological symptoms (arm pain below the elbow with tingling suggests the neck).
- Marked loss of **passive** rotation (frozen shoulder or osteoarthritis).
- No improvement after 12 weeks of proper care (refer; CPG rec. 14).

**Evidence-based conservative rehabilitation**

| Element | Recommendation | LoE |
|---|---|---|
| Active exercise as first-line (motor control and/or resistance training, "various loads") | Grade **A** [S1] | 1 [X] |
| Education, activity modification, self-management | Grade C [S1] | 3 |
| Manual therapy as an adjunct, short-term pain | Grade B [S1] | 2 |
| Corticosteroid injection: short-term only, not first-line | B / C [S1] | 2–3 |
| Therapeutic ultrasound; shockwave without calcification | Not recommended (B/C) [S1] | 2–3 |
| Specific exercise vs. unspecific (Holmgren RCT, n = 102): eccentric cuff + concentric/eccentric scapular stabilisers + mobilisation; 12 weeks | Surgery chosen later by 20 % vs 63 % (NNT 3) [S2] | 1 [X] |
| Surgery (acromioplasty) vs placebo surgery | No clinically meaningful difference (CSAW, FIMPACT) [S3, S4] | 1 [X] |
| High vs low load | High load not superior (RoCTEx RCT; Malliaras SR, very low certainty) [S5, S6] | 1–2 |

**Practical programme template** (LoE 5 for structure and numbers; consistent with [S1, S2, S9, G3])

| Phase | Weeks (typical) | Content | Dose | Progress when |
|---|---|---|---|---|
| 1 Calm & control | 0–2 | Education; reduce provocative climbing; isometric ER/IR/abduction at mid-range; scapular setting; pain-free active ROM; thoracic extension mobility | Isometrics 3–5 × 30–45 s; ROM 2 × 10 daily | Daily-life pain ≤ 2/10, no night-pain increase |
| 2 Strength | 2–8 | Side-lying or band ER, prone horizontal abduction with ER, prone Y/T (lower/middle trapezius), rows, serratus punch/wall slide, scaption to 90° | 3 × 8–15 at RPE 6–8, 3×/week (scapular low-load daily possible) | All sets green for 1 week → +5–10 % load |
| 3 Overhead & climbing positions | 6–12 | ER at 90° abduction, overhead pressing light, scapular pull-ups, active hangs, inverted rows → pull-ups, lock-off holds (90°, 120°), push-up plus; ball catches overhead before dynos | 3 × 6–12 / holds 3–5 × 5–10 s | Strength ≥ 90 % of other side; lock-offs pain ≤ 2/10 |
| 4 Return | 8–16 | Ladder stages 3–5 (§ 3.3) | — | Criteria below |

**Timelines** (LoE 5 unless noted):
- **Easy climbing** (vertical, arms mostly below shoulder level, top rope) can often continue or resume within 1–2 weeks
  under the pain rule.
- **Full climbing** usually takes 6–12 weeks. If there is no meaningful improvement by 12 weeks, refer [S1].
- The Holmgren programme was evaluated at 3 months [S2].

**Return criteria** (LoE 5; Bern consensus [S10], JOSPT RC CPG rec. 35–36 [S1]):
- Full, pain-free active range of motion, symmetrical with the other side.
- Isometric ER and IR strength (hand-held dynamometer or the same-load test) **≥ 90 % of the other side**. Do not use the
  ER:IR ratio alone [S10].
- Pain ≤ 2/10 during, and not worse next morning, after a session including lock-offs, steep pulling and the move types that
  used to hurt.
- Scapular control maintained under fatigue (no obvious winging in a push-up plus or in hangs).

**Climbing to avoid or modify during rehab** (LoE 5):
- **Reduce:** steep overhangs/roofs, wide gastons, high side-pulls, iron-cross or wide stemming of the arms, dynos and
  deadpoints, big cross-throughs, campus, long passive straight-arm hangs if they reproduce pain, mantles.
- **Prefer:** vertical/slab, holds in front of the body, short reaches, footwork-heavy climbing.

**Strength of evidence:**
- exercise first-line: high (1) [X];
- specific dose: low;
- climbing modifications and return criteria: expert opinion (5).

### 4.2 SLAP / superior labral and long-head-biceps pain

**Presentation and mechanism:**
- Deep or anterior shoulder pain, clicking/catching, pain with hard overhead pulling, sometimes a "dead arm".
- Often combined with cuff or biceps pathology [S11].
- The leading shoulder diagnosis in Bamberg (29.8 % of shoulder injuries in 2017–18; 32.5 % in 2009–12) [E2] [CS].
- Mostly chronic overload, "especially with an inwardly rotated arm"; mechanisms proposed are internal impingement and
  biceps-anchor peel-back [S14] [CS] (LoE 4–5).
- Traumatic causes: catching a dyno or a foot slip that forces the arm overhead, or a fall on the outstretched arm (LoE 5).

**Red flags and see-a-doctor triggers:**
- mechanical locking;
- episodes of subluxation or "slipping";
- traumatic onset with weakness;
- failure of ≈ 3 months of structured rehab.

**Imaging caveat:** labral findings appear in 69 % of asymptomatic elite climbers' shoulders [S15].

**Conservative rehabilitation:**
- **NATA position statement** [S11] (SR plus panel consensus; LoE 5): non-operative care first-line (rehab, NSAIDs, injection).
  Rehab should address internal-rotation deficit, total arc and horizontal adduction, and periscapular and glenohumeral
  strength, endurance and neuromuscular control.
- **Steinmetz 2022 SR** (5 studies, 244 athletes) [S12] (LoE 3):
  - non-surgical return to play 53.7 %; **78 % in those who completed rehab**;
  - return to prior performance 42.6 % (72 % in completers);
  - return generally **< 6 months**;
  - successful patients averaged **20 physiotherapy sessions**, versus 8 in those who switched to surgery;
  - failure predictors: older age, overhead sport, traumatic onset, concomitant cuff injury.
- **Edwards 2010** [S13] (LoE 4): 51 % failed non-operative care and had surgery. Successes improved significantly, but only
  two-thirds of overhead athletes regained their prior level.
- **Climbers after surgery** (Schöffl group, primary long-biceps tenodesis for SLAP 2, n = 30 climbers) [S14] (LoE 4, abstract):
  20/30 at or above pre-injury level; 22/30 within ± 0.66 UIAA grades.

**Practical template** (LoE 5):
- Base: the RC programme (§ 4.1).
- Add posterior-shoulder mobility (cross-body/sleeper stretch) only if a clinician confirms an IR deficit.
- Add progressive **biceps loading**: isometric elbow flexion → hammer curls → supinated curls → chin-ups.
- Add overhead stability (bottoms-up kettlebell carries, overhead ER) before dynamic catches.

**Timelines** (LoE 5 [S11, S12]):
- easy climbing: 2–6 weeks, modified;
- full climbing: 3–6 months;
- surgical opinion if not improving after ≈ 3 months of adherent rehab.

**Return criteria:** as § 4.1, plus no clicking with pain on hard pulls, and pain-free dynamic catches (ball catches, then
low-intensity deadpoints) (LoE 5).

**Avoid or modify** (LoE 5):
- underclings (biceps loaded with shoulder flexion and supination), roofs and steep overhead pulling;
- dynos and catches, gastons with internal rotation, big overhead reaches;
- supinated campus or pull-up volume;
- falls into the rope with the arm overhead.

**Strength of evidence:** low (3–5); the climbing-specific data are a surgical case series.

### 4.3 Glenohumeral instability (traumatic dislocation; atraumatic/multidirectional)

**Presentation and mechanism:**
- **Traumatic anterior dislocation:** from ground falls or the arm being forced into abduction and external rotation
  (catching a dyno, a hand stuck while falling). Rising in climbing (17.7 % of shoulder injuries in 2017–18) [E2] [CS].
- **Atraumatic/MDI:** hypermobile climbers with vague pain, a feeling of looseness, and symptomatic subluxation in more than one
  direction [S21].

**Red flags:**
- shoulder still out: **emergency**;
- numbness over the deltoid or weakness (axillary nerve);
- aged > 40 with weakness after dislocation (associated cuff tear);
- any **first-time dislocation**: doctor (X-ray, nerve check, and a discussion of surgery, especially < 25–30 years) (LoE 5).

**Evidence:**
- **Belk 2023**, meta-analysis of 5 level-1 RCTs, mean age ≈ 23 [S17] (LoE 1): recurrent instability **6.3 % after
  stabilisation vs 46.6 % after sling immobilisation**.
- **Paterson 2010 SR** [S18] (LoE 1–2): sling > 1 week gives no benefit (recurrence 37 % with ≥ 3 weeks vs 41 % with ≤ 1 week
  in < 30 year-olds). Age < 30 predicts recurrence.
- **Climbers after arthroscopic repair** of a first-time traumatic dislocation [S19] (LoE 4, [CS]): 93 % climbing again;
  78 % within ± 0.33 UIAA of pre-injury level or higher; 7 % recurrence.
- **MOON poster** [S20] (LoE 2, abstract, [CS]): 58 % back to climbing at 2 years after stabilisation surgery; 2.9 % recurrence.
- **Atraumatic MDI:** a physiotherapy-led programme (Watson Instability Programme) is first-line. It improved outcomes over
  general strengthening in an RCT, as summarised by the programme's authors [S21] (LoE 2–5).

**Conservative rehabilitation template** (LoE 5):
- **0–1 week:** sling for comfort only [S18]. Gentle range of motion below 90° elevation, no combined abduction + ER.
  Isometric cuff work, scapular setting.
- **1–6 weeks:** progressive ER/IR strength at the side, then in scaption; scapular and serratus work; closed-chain weight
  shifts; avoid end-range abduction + ER until about 6 weeks.
- **6–12 weeks:** strength and endurance in overhead and abduction/ER positions, perturbation and rhythmic stabilisation,
  plyometrics (ball catches), pull-ups, lock-offs, controlled hangs.
- **MDI:** scapular control first, then cuff/deltoid in progressively provocative positions, usually 3–6+ months [S21].

**Timelines:**
- non-operative: easy climbing about 6 weeks after a first dislocation (vertical, no dynos, no falling); full climbing about
  3 months (LoE 5);
- post-surgery: surgeon protocol, typically 4–6 months (LoE 4–5).

**Return criteria** (LoE 5, [S10]):
- full range of motion;
- **no apprehension** in abduction/ER;
- ER/IR strength ≥ 90 % of the other side;
- closed-chain stability test or push-up plus pain-free and symmetrical;
- controlled catch drills pain- and apprehension-free.

**Avoid or modify:** dynos, deadpoints to wide holds, iron-cross/wide stems, roof climbing with the arm abducted and externally
rotated, campus, uncontrolled falls (top rope preferred), backstep reaches behind the head (LoE 5).

**Strength of evidence:** high for "surgery reduces recurrence in young first-time dislocators" (1); low for non-operative
return-to-climbing timelines (5).

### 4.4 Scapular dyskinesis

- **Kibler 2013 consensus** [S8] (LoE 5):
  - dyskinesis is present in a high percentage of shoulder injuries, **but also in asymptomatic people**;
  - its role is undefined, and it is best viewed as an **impairment, not a diagnosis**;
  - a reliable yes/no observational test exists;
  - scapular rehab works **within a comprehensive shoulder programme**.
- **Cools 2014 algorithm** [S9] (LoE 5): flexibility (pectoralis minor, posterior shoulder) → conscious scapular control →
  strength (lower/middle trapezius, serratus; exercises with favourable trapezius ratios such as side-lying ER, prone horizontal
  abduction with ER, side-lying forward flexion, prone extension) → integration into sport-specific tasks.

**App implication:** do not offer "scapular dyskinesis" as a standalone injury. Embed scapular modules in the shoulder and
elbow programmes, and do not alarm users about "winging" without symptoms.

### 4.5 Other shoulder conditions (brief)

- **AC-joint injuries:** falls onto the shoulder; rising in bouldering (9.7 % of shoulder injuries) [E2]. A doctor is needed
  if there is a step deformity.
- **Long-head biceps rupture:** "Popeye" bulge after a pop [E2].
- **Coracoid impingement:** described in intensive climbing training (Schöffl group), rare.

---

## 5. Elbow

**Epidemiology.** Elbow/forearm was 7.7–9.1 % of injuries in Bamberg [E2] and 17.7 % of chronic injuries in Grønhaug's
survey (19.7 % in men) [E3]. Epicondylitis has fallen from 8.4 % to 3.3 % of all injuries over 20 years [E2].
A climbing-medicine chapter ranks the elbow fourth among sites (after fingers, shoulder, hand) and lists epicondylitis plus
brachialis, biceps and triceps tendinopathies as the common problems. Distal-biceps rupture, compartment syndromes and
vascular/muscle trauma are rarer and easily missed [E22] [CS] (LoE 5).

### 5.1 Medial elbow pain ("golfer's elbow", medial epicondylalgia/tendinopathy)

**Presentation and mechanism:**
- Pain at the medial epicondyle with gripping, crimping, pinching, pull-ups, and resisted wrist flexion or pronation.
- Classic in climbing literature [E21] (LoE 5).
- Practitioner-described aggravators: over-gripping, a flexed-wrist grip, hips far from the wall, high-volume steep juggy
  endurance, pull-up and campus volume [P2, L9] (LoE 5).

**Important differential: ulnar-nerve irritation** (cubital tunnel or more proximal), commonly misdiagnosed as medial
tendinopathy in climbers:
- tingling or numbness in the ring/little finger, worse with sustained elbow flexion;
- in a case series of 3 climbers, nerve-mobility work (tensioning/flossing) gave a full return [L7] (LoE 4, [CS], co-authored by
  J. Hooper).
- Other differentials: UCL sprain (valgus trauma), medial apophysitis in adolescents, fracture.

**Red flags** (LoE 5 unless noted):
- trauma with swelling and **inability to fully straighten the elbow**: X-ray (elbow extension test negative predictive value
  98 % in adults [L6], LoE 1–2);
- numbness or weakness of the hand intrinsics;
- locking or catching;
- adolescent with medial pain after throwing/dynamic loading;
- hot swollen joint.

**Evidence for rehabilitation:**
- Only small trials exist. A 2026 SR (5 studies, n = 143) found **eccentric exercise** associated with improved pain and
  function, but with low certainty [L4] (LoE 2).
- Most programmes therefore extrapolate from **lateral elbow** guidance [L1] (3 × 15, 6–12 weeks, isometric → isotonic →
  eccentric, without symptom exacerbation) [X].

**Practical template** (LoE 5; draws on [L1, L9]):

| Phase | Content | Dose |
|---|---|---|
| 1 Calm (0–2 wk) | Reduce aggravating volume (don't stop everything); isometric wrist flexion/pronation mid-range; flexor stretch and nerve glide if neural signs | Isometrics 3–5 × 30–45 s daily; glides 2–3 × 5, pain-free |
| 2 Load (2–8 wk) | Wrist flexion (palm up, forearm supported), pronation with a hammer/lever, finger curls, hammer and supinated curls; slow eccentric emphasis | 3 × 10–15, ≈ 2 reps in reserve, 3×/week (Hooper: 2–3 rounds, 2–3×/week, pain ≤ 2/10 during and 24 h [L9]) |
| 3 Specific (6–12 wk) | Loaded grip at climbing angles (block pulls, hangboard at low % bodyweight), pull-up progression, lock-offs | 3–5 × 10 s hangs; 3 × 5–8 pulls |
| 4 Return | Ladder § 3.3 | — |

**Timelines** (LoE 5):
- Easy climbing is usually kept up with reduced volume and intensity under the pain rule.
- Tendinopathy improvement is expected over **2–4 months** [L9].
- Nerve-dominant cases often improve within days to weeks [L9].
- Re-assess if no change after 6 weeks.

**Return criteria** (LoE 5, extrapolated from [L1]):
- **pain-free grip strength** ≥ 90 % of the other side (the CPG recommends measuring pain-free grip, grade B);
- resisted wrist flexion/pronation ≤ 1–2/10;
- hangboard and pull-up test sessions green;
- PRTEE- or VISA-C-type score improving [L1, W11].

**Avoid or modify:** high-volume steep/juggy endurance and "pump" sessions, campus, pull-up volume, hard crimps and pinches;
**prefer** open-hand grips, vertical terrain, footwork; shorter sessions (LoE 5).

### 5.2 Lateral elbow pain ("tennis elbow", lateral epicondylalgia/tendinopathy)

**Presentation:** lateral epicondyle pain with gripping, wrist or middle-finger extension, and lifting with the palm down.
In climbers: pinches, slopers, mantling/pressing, wrist-extended positions, antagonist push training (LoE 5).
Differentials: radial tunnel, cervical radiculopathy (the CPG and Hooper both flag the neck [L1, L10]), posterolateral
instability.

**Evidence:**

| Element | Finding | LoE |
|---|---|---|
| Resisted wrist-extensor exercise (isometric, concentric, eccentric) | **CPG grade B**; suggested **3 × 15 reps for 6–12 weeks**; loads should not exacerbate symptoms; progress isometric → isotonic → eccentric [L1] | 2 [X] |
| Phased return for high-demand athletes | Grade F [L1] | 5 |
| Elbow mobilisation/manipulation | Grade B (short term) [L1] | 2 |
| Rigid taping | Grade B, immediate/short term [L1] | 2 |
| Counterforce brace or wrist orthosis during activity | Grade F (immediate relief); long-term grade D [L1] | 4–5 |
| Corticosteroid injection | Better at 6 weeks, **worse at 52 weeks**, high recurrence (47/65 regressed) [L2]; worse 1-year outcomes and more recurrence vs placebo [L3] | 1 |
| Wait-and-see vs physiotherapy | Physiotherapy better at 6 weeks; no difference at 52 weeks (most recover) [L2] | 1 |
| Recurrence | 20–38 % incomplete resolution or recurrence at 6–12 months; up to 54 % at 2 years [L1] | 3 |

**Template:** as § 5.1, but extensor-focused: wrist extension with the elbow bent, then straight; supination/pronation;
radial deviation (hammer); finger-extensor band work; grip [L1, L10] (LoE 2–5).

**Timelines:**
- easy climbing usually continues, modified;
- typical improvement 6–12 weeks (CPG dosing window) [L1];
- natural history is often good by 1 year [L2].

**Return criteria:** as § 5.1 (pain-free grip ≥ 90 %, resisted wrist/middle-finger extension ≤ 1–2/10).

**Avoid or modify:** wide pinches, slopers and mantles with the wrist extended, push-ups and dips if provocative; keep
antagonist work but load-managed (LoE 5).

### 5.3 Anterior elbow: brachialis / distal biceps tendinopathy ("climber's elbow") and distal-biceps rupture

**Presentation and mechanism:**
- Pain in the elbow crease with lock-offs, pull-ups, undercling and steep pulling.
- The brachialis is loaded in every forearm position; the biceps more in supination.
- Described in climbing reviews [E21, E22] (LoE 5).
- The term "climber's elbow" is used inconsistently: in some sources it means medial epicondylitis, and Lutter 2020 uses it
  for epicondylitis [E2].

**Red flag: distal biceps rupture.**
- Signs: sudden "pop" in the elbow crease during a hard pull or lock-off; bruising; changed contour; weak supination.
- The **hook test** was abnormal in 33/33 complete avulsions and normal in intact or partial tears [L5] (LoE 2, diagnostic).
- Action: see a doctor **within days**. Clinical sources commonly cite a best repair window of ≈ 2–3 weeks to limit
  retraction (LoE 5).

**Template** (LoE 5):
- **Relative rest** from lock-offs, underclings and steep pulling.
- Isometric elbow flexion at 90° in neutral, pronated and supinated grips (3–5 × 30–45 s).
- Then slow heavy curls: hammer → reverse (pronated) → supinated, 3 × 8–12, 3×/week, with slow eccentrics.
- Then rows → scapular pull-ups → assisted pull-ups → pull-ups → lock-off holds → climbing.
- **Triceps tendinopathy** (posterior pain with push-ups, gastons, mantles): one climbing case report used scapular/cuff work
  plus triceps eccentrics (3 × 8, 3×/week) and returned to pain-free climbing at prior level in 10 weeks [L8] (LoE 4).

**Timelines:** easy climbing 1–3 weeks (on jugs, vertical, no lock-offs); full climbing 4–12 weeks (LoE 5).

**Return criteria:** pain-free full pull-up set and 3 × 10 s lock-offs at 90° with ≤ 1–2/10; elbow-flexion strength
≥ 90 % of the other side (e.g., a same-load curl test) (LoE 5).

**Avoid or modify:** lock-offs, underclings, steep campus or board climbing, pull-up volume, supinated grips (LoE 5).

---

## 6. Wrist

**Epidemiology and the climbing case series** [CS]:
- **Schöffl 2023, AJSM** (78 wrist injuries in 69 climbers, 2017–2020, ≥ 2-year follow-up) [W1] (LoE 4):
  - 69 % chronic;
  - most frequent: **ulnocarpal recess synovitis, ulnar impaction, lunate bone-marrow oedema, wrist sprain** (painful capsule,
    normal MRI) and **ganglion**; ulnar-sided injuries predominated and many had more than one diagnosis;
  - 61/78 treated non-operatively;
  - outcomes favourable overall (VAS 4.8 → 0.7; PRWE 53.6 → 10.3), but **≈ one third had persistent discomfort**;
  - more days with pain correlated with worse outcomes.
- Other data: wrist strain is among the 10 most frequent diagnoses (3.5 %) [E2]; wrist is 12.5 % of chronic injuries in women
  [E3]; finger and wrist inflammatory problems are the most common overuse injuries in Swedish club members [E7].
- **den Hengst 2026** (745 recreational climbers) [E20] (LoE 3): many self-manage; only 39 % of hand providers felt they had
  adequate climbing-specific knowledge. This supports a carefully built app.

### 6.1 Ulnar-sided wrist pain / TFCC (incl. ulnocarpal synovitis, ulnar impaction, DRUJ problems)

**Presentation:**
- Pinky-side wrist pain, worse with forearm rotation plus ulnar deviation, push-up/press-up, opening jars; clicking; weaker grip.
- **Ulnar fovea sign:** tenderness between the ulnar styloid and FCU. It is sensitive for foveal and ulnotriquetral lesions
  [W4] (LoE 3), but in a 2025 series only 21/73 positive patients had a foveal tear [W5] (LoE 4). It is a clinician test,
  not proof.

**Climbing triggers** (LoE 5 [W9, P2]): falls onto the hand (bouldering), mantles and press-outs, underclings, slopers, wide
or overhead gastons, cross-throughs, wrist-bent pinches, jamming.

**Red flags and doctor-first triggers** (LoE 5 unless noted):
- FOOSH with **anatomical-snuffbox** or radial-side tenderness: suspected scaphoid fracture. Immobilise and get imaging;
  NICE NG38 advises considering MRI as first-line imaging after clinical examination [W10] (guideline).
- **DRUJ instability** (clunk, prominent ulnar head, painful loss of rotation): hand surgeon. DRUJ subluxation predicts a poor
  non-operative outcome [W2].
- **Hypothenar/palm-base pain after hard pulling, with grip weakness**, sometimes with ulnar-nerve symptoms: suspected
  **hook-of-hamate stress fracture**. This is overrepresented in climbers [W6] (LoE 4) and is biomechanically plausible from
  flexor-tendon load [W7] (LoE 5). It needs CT/MRI.
- Persistent pain > 4–6 weeks despite unloading and bracing (bone-marrow oedema of the lunate and ulnar impaction need imaging) [W1].
- Numbness in the fingers; swelling with deformity; hot joint.

**Evidence for conservative care:**
- **Choi 2024 SR** (8 studies, 425 patients) [W2] (LoE 3): non-operative management of traumatic TFCC tears gave outcomes
  **non-inferior/comparable to surgery**. For Palmer 1B tears, short **above-elbow** immobilisation was effective. DRUJ
  subluxation, short-arm immobilisation and full foveal tears predicted failure.
- **Tse 2023** (5-phase orthosis + progressive strengthening + proprioception, n = 32) [W3] (LoE 3): NPRS 5.3 → 0.5;
  PRWE 49.5 → 14.6; grip +59.5 %; DRUJ stability improved.
- **Hooper's Beta** [W9] (LoE 5, practitioner, J. Hooper PT, DPT):
  - soft ulnar brace for most waking hours early; a rigid brace 1–2 weeks after significant sprains; taper as pain eases;
  - ROM 1–2 × 4–8 reps several times daily;
  - joint distraction 15–20 s;
  - three strength circuits (wrist flexion/extension in small ranges, pronation/supination with a lever, hammer curls, triceps
    push-downs in ulnar deviation, reverse curls, knuckle-plank shoulder taps, pinch-block lifts), 2–3 rounds × 8–10, up to
    3×/week, pain ≤ 2/10 during and 24 h;
  - typical recovery 2–4 months; central disc tears ≈ 6 months.

**Template for the app** (LoE 5, consistent with [W2, W3, W9]):
1. Unload and protect, 1–4 weeks: brace for provocative activities; pain-free ROM; isometric wrist and forearm in neutral.
2. Progressive loading: pronation/supination with a lever, wrist flexion/extension and radial/ulnar deviation in small, then
   full ranges, grip; 3 × 8–15, 3×/week.
3. Proprioception and closed-chain work (dart-thrower motion, wall/knuckle weight-bearing, then plank), pinch and sloper
   simulation.
4. Climbing ladder: re-introduce slopers, underclings and mantles last.

**Timelines:**
- easy climbing (jugs, vertical, no underclings/slopers/mantles, often taped or braced): 2–4 weeks;
- full climbing: 2–4 months; 6 months for some central-disc cases (LoE 5 [W9]).
- Schöffl 2023 found that longer time off climbing correlated with better PRWE change [W1]. This argues against rushing back.

**Return criteria** (LoE 5): no pain at rest or in daily activities; pain-free full forearm rotation under light load; grip
and pinch strength ≥ 90 % of the other side; press-up/mantle test pain ≤ 1–2/10; no painful clunk.

**Avoid or modify:** underclings, slopers, mantles/press-outs, wide/overhead gastons, wrist-bent pinches, jamming, bouldering
falls onto the hands (top rope, down-climb) (LoE 5 [W9]).

### 6.2 Extensor-side problems: ECU tendinopathy/subluxation, de Quervain, intersection syndrome

- **ECU:** dorsal-ulnar pain and snapping with supination and ulnar deviation. It is often confused with TFCC (forum and
  practitioner reports [W9]).
  - Acute painful snapping (subsheath tear): hand specialist; early immobilisation in pronation is often used (LoE 5).
  - Tendinopathy: relative rest, then progressive supination and ulnar-deviation loading (LoE 5).
- **de Quervain** (radial wrist, thumb movements, positive Finkelstein) and **intersection syndrome** (dorsal-radial forearm
  crepitus; described in ice-axe climbers [W8], LoE 4): relative rest or splint, then graded loading (LoE 5).
- **Evidence strength:** low (4–5) and not climbing-specific except case reports.

### 6.3 Bony wrist problems

Scaphoid fracture, hook-of-hamate fracture [W6] and lunate bone-marrow oedema [W1] are **not self-management conditions**.
The app's role is to detect the pattern and send the user for imaging.

---

## 7. Lower limb

### 7.1 Ankle sprains and fractures (mostly bouldering falls)

**Presentation and mechanism:** inversion on landing, often with rotation during the fall [E17]; landing on the edge of a
mat or between mats; heavily downturned shoes [E10]. Fracture risk is substantial in ED series (the ankle is the most commonly
fractured site) [E11, E12].

**Triage, the Ottawa ankle rules** [A5] (LoE 1–2), pooled sensitivity ≈ 100 % and negative LR 0.08 [A4] (LoE 1):
an X-ray is needed if there is pain in the malleolar or midfoot zone **and** any of:
- bone tenderness at the posterior edge or tip of either malleolus (distal 6 cm);
- tenderness at the base of the 5th metatarsal or the navicular;
- **inability to bear weight for 4 steps** both immediately and at assessment.

The JOSPT CPG says to apply the Ottawa or Bernese rules before starting rehab [A1].

**Bouldering-specific addition** (LoE 5): **heel or hindfoot pain after a fall from height or a heel landing** suggests a
calcaneus/talus fracture [A8]. It needs an X-ray even if the ankle rules seem negative.

**Other red flags:**
- deformity; numb, cold foot;
- suspected **Achilles rupture** (sudden "kick" in the calf, cannot rise on tiptoe);
- calf swelling and pain after immobilisation (DVT).
- **"High ankle" sprain** (pain above the ankle, pain with squeezing the calf or external rotation): longer recovery, needs
  clinician assessment.

**Evidence-based conservative rehabilitation:**

| Element | Recommendation | LoE |
|---|---|---|
| External support (brace/tape) + progressive weight-bearing | **Grade A** [A1]; functional treatment better than immobilisation for return to sport [A1, A2] | 1 |
| Severe sprains: semi-rigid brace to below-knee cast for up to 10 days | Grade A [A1] | 1 |
| Structured exercise (protected ROM, neuromuscular, balance), clinic + home | **Grade A** [A1]; moderate evidence [A3] | 1 |
| Manual therapy (lymph drainage, talar AP mobilisation) with exercise | Grade A [A1] | 1 |
| NSAIDs | Short-term pain/swelling (strong evidence [A3]); use with care, may impair healing [A2] | 1–2 |
| Therapeutic ultrasound | **Do not use** (grade A) [A1] | 1 |
| Recurrence prevention: bracing + proprioceptive/balance training | Grade A [A1]; 8-week unsupervised home proprioceptive programme reduced recurrence by 35 % (22 % vs 33 %, NNT 9) [A7] | 1 |
| Delayed examination 4–5 days post-trauma best grades ligament damage | [A2] | 2 |
| Surgery only after failed thorough exercise-based treatment | [A2] | 1–2 |

**Return-to-sport framework (PAASS)** [A6] (LoE 5, Delphi of 155 experts):
- **P**ain during sport and over the last 24 h;
- **A**nkle impairments (range of motion; strength, endurance, power);
- **A**thlete perception (confidence, stability, psychological readiness);
- **S**ensorimotor control (proprioception, balance);
- **S**port/functional performance (hopping, agility, sport-specific drills, a full training session).

**Practical template** (LoE 5 for climbing specifics; based on [A1, A2, A7]):
1. **0–7 days:** protection with brace or tape, weight-bearing as tolerated, elevation, ankle pumps, pain-free active ROM,
   isometric eversion.
2. **1–4 weeks:** band strength (eversion/inversion/plantar flexion), calf raises (2 legs → 1 leg, 3 × 10–15), single-leg
   balance (eyes open → closed → unstable surface), weight-bearing dorsiflexion mobility (lunge test).
3. **3–8 weeks:** hops, landing drills from low heights onto mats, single-leg landing control, lateral hops; continue the
   8-week balance programme [A7].
4. **Climbing:**
   - top-rope/lead on overhangs with **no jumping and no bouldering falls**;
   - comfortable, non-downturned shoes, because tight shoes compress a swollen ankle (LoE 5);
   - avoid heel and toe hooks and rotational smearing on the injured side early;
   - bouldering only after landing drills pass;
   - **down-climb** instead of jumping [K1].

**Timelines** (LoE 5; ranges consistent with guideline time frames [A1]):
- **Grade I–II:** easy rope climbing possible from about days 3–14 once walking normally; full bouldering at 3–6 weeks when
  hop tests pass.
- **Grade III:** 6–12 weeks.
- **Fractures:** surgeon protocol, typically ≥ 6–12 weeks.
- Keep the brace for bouldering and high-risk landings for several months (recurrence prevention, grade A [A1]).

**Return criteria** (LoE 5, from [A1, A6]):
- pain ≤ 2/10 during and over 24 h;
- weight-bearing dorsiflexion within ≈ 2 cm (or ≈ 5°) of the other side;
- 25 single-leg calf raises or ≥ 90 % of the other side;
- single-leg balance 30 s eyes closed;
- single-leg hop distance/side-hop ≥ 90 % of the other side;
- confident on 1–1.5 m drop-landings onto a mat.

### 7.2 Heel hook hamstring injuries (and other posterior-thigh injuries)

**Presentation and mechanism** [CS]:
- **Schöffl 2016** (17 patients injured in a heel hook) [H1] (LoE 4): sudden posterior pain in the knee, thigh or pelvis
  during a heel hook. According to a secondary summary, nearly two-thirds came for a second opinion after an initial
  misdiagnosis; 8 strains and 9 tears (6 at the pelvis, 5 involving the biceps femoris); 2 needed surgery.
- **Ehiogu 2020 review** [H2] (LoE 5): the heel hook is unique to climbing and loads the hamstrings concentrically and
  isometrically with the hip flexed. Eccentric-only programmes from sprint sports may be insufficient for climbers.
- **Vantorre 2022** (two elite competitors, **high-elevation heel hooks**) [H3] (LoE 4): proximal strain/avulsion even
  without a big fall.
  - The non-operative case had persistent weakness and sitting pain and retired.
  - The surgical case (anchors at day 16) returned to competition at 9 months, but not to the former level.

**Red flags: suspected proximal hamstring avulsion.**
- Signs: pop, large posterior-thigh bruising within days, pain at the sit bone, difficulty sitting or walking normally,
  palpable gap, sciatic symptoms.
- Action: **MRI and specialist assessment within days.**
- **Harris 2011 SR** [H8] (LoE 3–4): acute repair (< 4 weeks) gave better satisfaction and return to sport than chronic repair.
- **van der Made 2022** (prospective, shared decision-making) [H9] (LoE 2): operative and non-operative groups had **comparable
  1-year outcomes**.
- **Lauf 2025** [H10] (LoE 4): some elite athletes returned after non-operative care.
- Take-home: early specialist input is needed for a shared decision, but it is not automatically surgery.

**Evidence for rehabilitation** (from sprint and football, [X]):
- **Askling 2013/2014 RCTs:** a lengthening-type programme (L-protocol: "extender" 3 × 12 twice daily, "diver" 3 × 6 every
  other day, "glider" every third day) shortened return to play (28 vs 51 days in footballers; 49 vs 86 days in sprinters/
  jumpers) [H4, H5] (LoE 1).
- **Hickey 2020:** pain-threshold (≤ 4/10) rehab was safe and gave better strength [H6] (LoE 1–2).
- **Return-to-play criteria (Delphi)** [H7] (LoE 5): no pain on palpation; no pain during strength and flexibility tests;
  similar flexibility to the other side; pain-free functional and field testing; psychological readiness.
- **Hickey's RTP clearance** [H6]: no palpation pain; pain-free active knee extension / straight-leg raise with ROM ≥ 90 % of
  the other leg; pain-free maximal isometric knee flexion at 0°/0° and 90°/90° hip/knee; no pain or apprehension at full sprint.

**Climbing-adapted template** (LoE 5; built on [H2, H4, H6]):

| Phase | Content | Dose / gate |
|---|---|---|
| 1 (days 0–7+) | Walking as tolerated; isometric knee flexion (prone, then 90/90) sub-maximal; glute bridges; pain-free ROM | 5 × 10–30 s; pain ≤ 2–3/10 (≤ 4/10 tolerable per [H6]) |
| 2 | Long-lever bridges, single-leg bridges, slider leg curls, RDL/hip hinge, extender/diver (L-protocol) | 3 × 8–12; progress when green |
| 3 | Eccentric Nordics (assisted), **hip-flexed knee flexion** (seated or supine curls with the hip at 90°+), isometric "heel-hook holds" on a low box/edge with hands assisting, progressing load | 3 × 5–8 Nordics; holds 5 × 5–10 s |
| 4 | Heel hooks on the wall: low-angle, foot below hip → higher feet → steep; high-elevation heel hooks last | Pain ≤ 1–2/10 and symmetric hip-flexed isometric strength ≥ 90 % |

**Timelines** (LoE 5 unless noted):
- **mild strain:** climbing without heel hooks on the injured side within days to 2 weeks; heel hooks reintroduced from about
  2–6 weeks;
- **partial tear:** 6–12 weeks to full heel hooking;
- **surgically repaired avulsion:** about 6–9 months [H3] (LoE 4).

**Return criteria:** Hickey/Delphi criteria translated to climbing:
- no palpation pain;
- straight-leg raise ROM ≥ 90 % of the other side;
- pain-free maximal isometric knee flexion at 90/90 **and in a hip-flexed "high heel hook" position**, ≥ 90 % of the other side;
- pain- and apprehension-free hard heel hooks and toe hooks.

**Avoid or modify:** heel hooks (especially high-elevation and hip-flexed), toe hooks/bicycles with the injured leg, deep
drop knees, wide stems; use the other leg (LoE 5).

### 7.3 Knee: meniscus, MCL, LCL/ITB, ACL

**Climbing-specific mechanisms** (Lutter 2020, AJSM, 77 acute knee injuries in 71 climbers) [K1] (LoE 4, [CS]):

| Mechanism | Share | Typical structure |
|---|---|---|
| **Heel hook** | 40.3 % | **ITB sprain** (48 % of heel-hook injuries; exclusively heel hook), LCL tear (16 %), medial meniscus (23 %) |
| Ground fall | 22.1 % | **ACL tear** (41 % of falls), patellar dislocation, fracture |
| **High step** | 20.8 % | **Medial meniscus** (50 %) |
| **Drop knee** | 16.9 % | Medial meniscus (38.5 %), **MCL** (15 %), lateral meniscus |

- Medial meniscus tears were the commonest diagnosis overall (28.6 %).
- Indoor bouldering was the setting in 46.8 %.
- All athletes had returned to sport within 12 months.
- The authors recommend, during return to sport, **rope climbing on overhangs** rather than bouldering and **down-climbing or
  walking off rather than jumping**, plus strength training of the knee stabilisers [K1].
- **Cruz 2025** [K2] (LoE 4, [CS]): bouldering ground falls produced complex knee injuries (79 % combined ACL + meniscus;
  21 % multiligament; 21 % meniscal root tears; 95 % surgical).
- **Vagy et al. 2025** [K3] (LoE 5, [CS]): a 6-phase ACL-reconstruction protocol for climbers, emphasising eccentric landing
  mechanics and controlled penduluming into the wall.

**Triage, the Ottawa knee rule** [K9] (LoE 1–2), pooled sensitivity 98.5 % [K10] (LoE 1): an X-ray is needed if any of:
- age ≥ 55;
- isolated patellar tenderness;
- fibular-head tenderness;
- inability to flex to 90°;
- inability to take 4 steps immediately and at assessment.

**Other doctor-first triggers** (LoE 5, partly [K5]):
- a "pop" with **swelling within hours** (haemarthrosis: ACL, fracture, patellar dislocation);
- **true locking** (cannot fully straighten);
- giving way;
- patellar dislocation;
- marked varus/valgus laxity;
- posterolateral pain with instability after a heel hook (LCL/posterolateral corner).

**Evidence for conservative care:**
- **Meniscus (JOSPT 2018 CPG)** [K4]: supervised, progressive ROM, knee and hip strength, and neuromuscular training for
  meniscal tears and after surgery. Use hop tests to detect asymmetry and judge readiness to return (graded recommendations in
  the CPG summary).
- **Degenerative medial meniscal tear** (middle-aged), Kise 2016 RCT (n = 140) [K6] (LoE 1): 12-week supervised exercise
  equal to arthroscopic partial meniscectomy at 2 years, with better strength at 3 months.
- **Young traumatic tears with locking:** surgical opinion (LoE 5).
- **MCL** (Svantesson 2024 SR, 26 reports, 1,912 patients) [K7] (LoE 3, very low certainty): non-operative care with
  **immediate weight-bearing**; no outcome differences between grade I and II; bracing inconsistently used.
  Older football data put return at ≈ 10.6 days for grade I and ≈ 19.5 days for grade II [K8] (LoE 4, cited via secondary
  source). The JOSPT ligament CPG allows bracing for severe MCL injury (grade F) and recommends hop tests for readiness
  (grade B) [K5].
- **ITB sprain / LCL:** no climbing-specific treatment data; conservative care is usual for isolated, stable sprains [K1]
  (LoE 4–5).

**Practical template** (LoE 5):
1. **Calm:** walking, quadriceps isometrics, ROM within pain, bike.
2. **Strength:** squats to a pain-free depth → split squats → step-downs; hamstring and hip abductor/ER work; 3 × 8–12,
   3×/week. For MCL, add a brace if it is unstable.
3. **Climbing patterns** off the wall: deep-flexion loading (high-step simulation, box step-ups to a high box), rotational
   control (drop-knee position holds), hip-flexed heel-hook pulls; then landing drills.
4. **Climbing:** rope on overhangs; no drop knees, high steps, heel hooks or frog/egyptian positions with the injured knee
   at first; no bouldering falls until landing tests pass.

**Timelines** (LoE 4–5):

| Injury | Easy climbing | Full climbing / heel hooks |
|---|---|---|
| MCL grade I | ≈ 1–2 weeks | ≈ 2–4 weeks |
| MCL grade II | ≈ 2–4 weeks | ≈ 4–8 weeks |
| Meniscus, non-operative | 2–6 weeks (avoid deep loaded flexion) | 6–12 weeks |
| ITB/LCL sprain | 1–3 weeks (no heel hooks) | 3–8 weeks |
| Meniscal repair, ACL reconstruction | Surgeon protocol | Surgeon protocol (ACL typically ≥ 6–9 months for bouldering falls) [K3] |

**Return criteria** (LoE 2–5, [K4, K5]):
- no effusion; full range of motion;
- quadriceps and hamstring strength ≥ 90 % of the other side;
- good single-leg squat and step-down quality;
- **hop tests ≥ 90 % of the other side** before bouldering landings;
- pain-free deep squat/high step, drop knee and heel hook under load.

---

## 8. Back and neck (brief)

- **"Climber's back":**
  - elite male climbers show increased thoracic kyphosis and lumbar lordosis, correlated with climbing level and probably
    shortened pectorals [B1] (LoE 3, [CS]);
  - **Carraro 2023** (180 adolescent competitors) [B2] (LoE 3): 74 % had low-back complaints in 12 months, mostly
    low-intensity/low-disability; older juniors had more.
- **Acute spine trauma:** spine was 7.2 % of bouldering ED injuries [E11] and 2.8 % of specialist-clinic injuries [E2].
  Falls from height and landing on the back or buttocks can cause vertebral fractures.
- **Neck pain from belaying:** habitual belaying for 10 min reduced neck mobility and caused pain. Belay glasses or a
  coordinated head posture prevented this (small randomised study, n = 30) [B3] (LoE 2, [CS]).
- **Management of non-specific LBP/neck pain:** guidelines recommend education, staying active and exercise; prudent use of
  medication, imaging and surgery [B4] (LoE 1).
- **Red flags:** use the IFOMPT framework [B5] (LoE 5).
  - Cauda-equina signs (saddle numbness, new bladder/bowel dysfunction, progressive leg weakness): emergency.
  - Suspected fracture (trauma, especially age > 50 or with osteoporosis/steroid use).
  - Malignancy (history of cancer, weight loss, constant night pain), infection (fever, immunosuppression, IV drug use).
  - After falls with neck pain, apply the **Canadian C-spine rule** logic [B6] (LoE 1–2): age ≥ 65, dangerous mechanism (falls
    from ≥ 3 ft/5 stairs, axial load) or limb paraesthesia means imaging. The app should simply say: neck pain after a fall →
    medical assessment before any training.
- **Adolescent with extension-related LBP** (pain arching back, e.g., on steep terrain): suspected spondylolysis; see a doctor
  (LoE 5).
- **App content:** a mobility + trunk/hip strength programme for mechanical LBP and belay-neck management (belay glasses,
  head posture, short sessions) is reasonable. Do not build a diagnostic spine module.

---

## 9. Triage question bank (per region)

**Routing categories** (align with `safety-regulation.md` § 1):
- 🔴 **Emergency now**: call emergency services or go to A&E.
- 🟠 **See a doctor within 24–72 h** before any loading programme (imaging or surgical window).
- 🟡 **Get a physio or sports-doctor assessment** within about 1–2 weeks. Meanwhile the app offers only phase-0 "protect & calm"
  advice.
- 🟢 **Self-guided programme OK** with monitoring and re-triage triggers.

The most severe answer wins. Every red or orange answer should show *why* (e.g., "could be a fracture: the Ottawa rules say
an X-ray is needed").

### 9.1 Global questions (asked for every region)

| # | Question | Options | Implication |
|---|---|---|---|
| G1 | How did it start? | a) Sudden, during a move, with a pop/snap · b) Fall/landing/impact · c) Gradually over days–weeks · d) Not sure | a/b → region trauma questions; c → overuse pathway |
| G2 | When did it start? | < 72 h · 3 d–6 wk · 6–12 wk · > 12 wk | Acute window → protect phase; > 12 wk without improvement despite proper care → 🟡 (refer per [S1]) |
| G3 | Visible deformity, bone poking out, open wound, or a joint that looks/feels "out of place"? | Yes / No | Yes → 🔴 |
| G4 | Numbness, pins and needles, weakness, or a pale/cold/blue hand or foot? | None · Brief tingling that goes away · Persistent numbness/tingling · Weakness · Cold/pale limb | Cold/pale → 🔴; weakness or persistent numbness → 🟠; brief tingling → 🟡 (nerve rules) |
| G5 | Fever, chills, feeling unwell, or a hot, red, swollen joint without an injury? | Yes / No | Yes → 🔴 (infection) |
| G6 | Pain at rest or at night? | None · Only when lying on it · Wakes me most nights regardless of position · Constant, unrelenting | Positional → 🟢 (typical cuff pain); wakes most nights → 🟡; constant unrelenting (esp. with G7) → 🟠 |
| G7 | History of cancer, unexplained weight loss, steroid use, osteoporosis or immunosuppression? | Yes / No | Yes + spine/shoulder pain → 🟠 |
| G8 | Worst pain now (0–10), at rest and in daily activities | Slider | Rest ≥ 7/10 → 🟠; daily activities > 5/10 → 🟡 |
| G9 | Can you use the limb for daily tasks / take 4 steps? | Yes · With pain · No | No → region-specific 🟠 |
| G10 | Trend over the last 7 days | Better · Same · Worse | Worse despite rest → 🟡 |
| G11 | Age | < 18 · 18–39 · 40–54 · ≥ 55 | < 18 → lower threshold (growth plates, apophyses, spondylolysis) → 🟡 for any trauma or persistent pain; ≥ 55 + knee trauma → Ottawa X-ray criterion |
| G12 | Has a clinician diagnosed it or operated on it? | No · Diagnosed (name) · Operated (date) | Operated → follow the surgeon's protocol; app = logging only unless cleared |
| G13 | Have you had this before? | No · Once · Several times | Recurrent → 🟡 (recurrence risk; previous injury is the strongest risk factor [E15, E16]) |

### 9.2 Shoulder

| # | Question | Options | Implication |
|---|---|---|---|
| S1 | Did the shoulder come out or feel like it slipped out? | No · Feels unstable but never came out · Came out and went back by itself · Came out and had to be put back · It is still out | Still out → 🔴; came out (first time) → 🟠 (X-ray, nerve check, surgical discussion if young [S17]); feels unstable → 🟡 |
| S2 | Can you lift the arm above your head on your own? | Yes · Yes but painful arc · No, but someone else can lift it · No | "No" after trauma → 🟠 (possible cuff tear); painful arc → 🟢 (cuff pathway) |
| S3 | Where is the pain? | Top/outer upper arm · Front of shoulder · Deep inside/back · Top point (AC joint) · Neck into the arm below the elbow | Outer arm → RC pathway; front/deep → biceps/SLAP pathway (🟡 recommended); AC with step → 🟠; neck/below elbow → neck pathway (🟡) |
| S4 | Clicking, catching, locking or "dead arm" episodes? | No · Painless clicks · Painful catching · Dead-arm episodes | Painful catching/dead arm → 🟡 (labrum/instability) |
| S5 | Chest pain, breathlessness or sweating, or pain with exertion that is not linked to arm movement? | Yes / No | Yes → 🔴 (cardiac) |
| S6 | Has turning the arm outwards (elbow at side) become very stiff, even when someone helps? | No · Somewhat · Markedly | Markedly → 🟡 (frozen shoulder or OA; different programme) |
| S7 | Which moves hurt? | Gastons · Underclings · Lock-offs/steep · Dynos/catches · Mantles · Hangs · Pressing | Programme module selection and climbing restrictions (§ 10) |

### 9.3 Elbow

| # | Question | Options | Implication |
|---|---|---|---|
| E1 | Where exactly? | Inner bony point · Outer bony point · Front crease · Back · Diffuse/forearm | Pathway selection (§ 5.1–5.3) |
| E2 | Did you feel a pop in the front of the elbow, followed by bruising, a changed biceps shape or weak forearm turning (screwdriver motion)? | Yes / No | Yes → 🟠 within days (distal biceps rupture; time-sensitive) [L5] |
| E3 | After a fall or impact: can you fully straighten the elbow? | Yes · No | No → 🟠 (X-ray; elbow extension test [L6]) |
| E4 | Tingling or numbness in the ring/little finger, worse when the elbow is bent? | No · Sometimes · Often/persistent · Weak hand | Sometimes → nerve-glide module + 🟡; persistent/weak → 🟠 [L7] |
| E5 | Locking, catching or swelling of the elbow joint? | Yes / No | Yes → 🟡 (intra-articular; OCD in adolescents) |
| E6 | Pain with gripping/hanging? With bending the wrist down (palm up) or up (palm down)? | Pattern | Medial vs lateral tendinopathy pathway (🟢) |

### 9.4 Wrist

| # | Question | Options | Implication |
|---|---|---|---|
| W1 | Fall onto the hand? Pain or tenderness at the thumb-side base ("snuffbox")? | Yes / No | Yes → 🟠 (scaphoid; immobilise + imaging [W10]) |
| W2 | Where? | Pinky side · Thumb side · Back of wrist · Palm base on pinky side (hypothenar) | Pinky side → TFCC/ECU pathway; hypothenar + grip weakness → 🟠 (hamate) [W6] |
| W3 | Clunking, a "loose" feeling or a prominent ulnar head; pain or block when turning the palm up/down? | No · Painless click · Painful clunk/instability · Rotation blocked | Painful clunk/instability or block → 🟠/🟡 (DRUJ) [W2] |
| W4 | Can you push up from a chair or table without sharp pain? Lift a full mug? | Yes · Painful · No | "No" or sharp pain with a mug → 🟡; app says no climbing yet [W9] |
| W5 | Numbness in the fingers? | No · Thumb–ring · Ring–little | Yes → 🟡/🟠 (carpal tunnel or Guyon canal) |
| W6 | Swelling with deformity, or bruising after a fall? | Yes / No | Deformity → 🔴; swelling/bruising after a fall → 🟠 |
| W7 | Pain > 4–6 weeks despite brace/unloading? | Yes / No | Yes → 🟡 (imaging: lunate oedema, ulnar impaction) [W1] |

### 9.5 Ankle / foot

| # | Question | Options | Implication |
|---|---|---|---|
| A1 | Could you take 4 steps right after the injury, and can you now? | Yes/Yes · No/Yes · No/No | Any "No" → 🟠 (Ottawa → X-ray) [A5] |
| A2 | Is there tenderness when pressing the back edge or tip of either ankle bone, the outer midfoot bone (base of the 5th metatarsal) or the inner midfoot bone (navicular)? (guided images) | Yes / No / Unsure | Yes → 🟠 (X-ray); unsure → 🟡 |
| A3 | Did you land on your heels from height? Heel pain? | Yes / No | Yes → 🟠 (calcaneus/talus) [A8] |
| A4 | Pain higher up, above the ankle, or pain when squeezing the lower leg? | Yes / No | Yes → 🟡 (syndesmosis) |
| A5 | Sudden kick in the calf or Achilles; can't rise on tiptoe? | Yes / No | Yes → 🟠 urgent (Achilles rupture) |
| A6 | Calf swollen, warm, painful (especially after a cast or brace) or breathlessness? | Yes / No | Yes → 🔴 (DVT/PE) |
| A7 | Has the ankle given way repeatedly in the past? | No · 1–2× · Often | Often → 🟡 (chronic instability; brace + programme [A1]) |

### 9.6 Knee

| # | Question | Options | Implication |
|---|---|---|---|
| K1 | What were you doing? | Heel hook · Drop knee · High step/rock-over · Fall/landing · Twist · Gradual | Mechanism → likely structure (§ 7.3) [K1] |
| K2 | Pop, and the knee swelled up within a few hours? | Yes / No | Yes → 🟠 (ACL, fracture, patellar dislocation) |
| K3 | Ottawa knee items: aged ≥ 55? Tender only at the kneecap? Tender at the outer bony bump below the knee (fibular head)? Can't bend to 90°? Couldn't take 4 steps? | Yes / No per item | Any yes → 🟠 (X-ray) [K9] |
| K4 | Does the knee lock (get stuck and cannot straighten)? | No · Catches but frees · Locked now | Locked now → 🟠 within 24–48 h; catching → 🟡 |
| K5 | Does it give way? | No · Once · Repeatedly | Yes → 🟡/🟠 (ligament) |
| K6 | Did the kneecap move out of place? | Yes / No | Yes → 🟠 |
| K7 | Where is the pain? | Inner joint line · Inner side above/below the joint line · Outer side · Back of the knee · Front | Meniscus / MCL / ITB-LCL / popliteus-hamstring / patellofemoral pathway |

### 9.7 Hamstring / posterior thigh

| # | Question | Options | Implication |
|---|---|---|---|
| H1 | Did it happen in a heel hook? A high heel hook? | Yes / No | Pathway; high heel hook raises suspicion of a proximal injury [H3] |
| H2 | Pain right at the sit bone, a large bruise on the back of the thigh, a felt gap, or difficulty sitting/walking normally? | Yes / No | Yes → 🟠 within days (avulsion; shared surgical decision is time-sensitive) [H8, H9] |
| H3 | Tingling or numbness down the leg? | Yes / No | Yes → 🟠 (sciatic involvement) |
| H4 | Can you walk normally? Do a gentle two-leg bridge? | Both · Walk only · Neither | Neither → 🟡/🟠; both → 🟢 programme |
| H5 | Previous hamstring injury on this side? | Yes / No | Yes → slower progression (recurrence) |

### 9.8 Back / neck

| # | Question | Options | Implication |
|---|---|---|---|
| B1 | Did it start after a fall or landing on your back, buttocks or head? | Yes / No | Yes + midline pain → 🔴/🟠 (fracture; C-spine rule logic [B6]) |
| B2 | Numbness around the genitals/buttocks, new bladder or bowel problems, or progressive leg weakness? | Yes / No | Yes → 🔴 (cauda equina) [B5] |
| B3 | Pain shooting below the knee or below the elbow, with numbness or weakness? | No · Pain only · With numbness · With weakness | Weakness → 🟠; numbness → 🟡 |
| B4 | Constant pain unchanged by position, night pain, fever, weight loss, cancer history, age > 50 with new pain, steroid use/osteoporosis? | Yes / No | Yes → 🟠 [B5] |
| B5 | Under 18 and pain mainly when arching backwards? | Yes / No | Yes → 🟡 (spondylolysis) |
| B6 | Neck stiffness or pain after belaying, without arm symptoms? | Yes / No | 🟢 (belay-neck module [B3]) |

### 9.9 Re-triage during the programme (all regions)

| Trigger | Action | Source |
|---|---|---|
| Any new red-flag answer | Re-run § 9.1–9.8 | — |
| Worse after 1 week of correct dosing | Regress; if still worse after another week → 🟡 | LoE 5 |
| No improvement after **2–3 weeks** in acute injuries or **6 weeks** in overuse injuries | 🟡 | LoE 5; consistent with the owner's finger rule ("after 2–3 weeks not better → ultrasound") |
| No meaningful improvement after **12 weeks** (any tendinopathy) | Refer to a physician specialist | [S1] (grade F) |

---

## 10. Climbing modification matrix (what to avoid or limit early)

LoE 5 for the whole table. It is an expert synthesis of mechanisms from [K1, H1, H3, E2, S14, W9, L9, P2, A8].
⛔ = avoid in stages 0–2 · ⚠ = limit or reintroduce in stage 3 · ✓ = usually fine if pain-green.

| Move / exposure | RC / subacromial | SLAP / biceps | Instability | Medial elbow | Lateral elbow | Anterior elbow | TFCC / ulnar wrist | Ankle | Hamstring | Meniscus / MCL | ITB / LCL |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Gastons (wide/overhead) | ⛔ | ⛔ | ⛔ | ⚠ | ⚠ | ⚠ | ⛔ | ✓ | ✓ | ✓ | ✓ |
| Underclings | ⚠ | ⛔ | ⚠ | ⚠ | ⚠ | ⛔ | ⛔ | ✓ | ✓ | ✓ | ✓ |
| Compression / slopers | ⚠ | ⚠ | ⚠ | ⚠ | ⛔ | ⚠ | ⛔ | ✓ | ✓ | ✓ | ✓ |
| Crimps / pinches (hard) | ✓ | ✓ | ✓ | ⛔ | ⛔ | ⚠ | ⚠ | ✓ | ✓ | ✓ | ✓ |
| Lock-offs / steep pulling | ⛔ | ⛔ | ⚠ | ⚠ | ⚠ | ⛔ | ⚠ | ✓ | ✓ | ✓ | ✓ |
| Dynos / deadpoints / campus | ⛔ | ⛔ | ⛔ | ⛔ | ⛔ | ⛔ | ⛔ | ⛔ (landing) | ⚠ | ⛔ (landing) | ⚠ |
| Mantles / press-outs | ⛔ | ⚠ | ⚠ | ✓ | ⚠ | ✓ | ⛔ | ✓ | ✓ | ⚠ (deep knee) | ✓ |
| Long passive hangs | ⚠ | ⚠ | ⚠ | ⚠ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Heel hooks (esp. high) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ⚠ | ⛔ | ⚠ | ⛔ |
| Toe hooks / bicycles | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ⛔ | ⛔ | ⚠ | ⚠ |
| Drop knee / frog / egyptian | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ⚠ | ⚠ | ⛔ | ⚠ |
| High step / rock-over (deep flexion) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ⚠ | ⚠ | ⛔ | ⚠ |
| Bouldering falls / jumping off | ⚠ | ⚠ | ⛔ | ✓ | ✓ | ✓ | ⛔ | ⛔ | ⚠ | ⛔ | ⚠ |
| Jamming (cracks) | ⚠ | ⚠ | ⚠ | ⚠ | ⚠ | ✓ | ⛔ | ⚠ (foot jams) | ✓ | ⚠ | ✓ |

**Preferred substitutes:** top rope or lead on overhangs for leg injuries (falls are caught by the rope; no wall swing)
[K1]; vertical or slab terrain with arms below shoulder level for shoulder injuries; open-hand jugs and footwork drills for
elbow and wrist injuries; down-climbing instead of jumping.

---

## 11. Conclusions for the app

### 11.1 What an app can responsibly recommend per region

**All regions**
- Structured, progressive exercise programmes with transparent dosing, the green/amber/red pain rule (§ 3.1) and next-morning
  checks, plus a daily log of pain at rest, pain in a standard provocation test and morning stiffness. This mirrors the
  finger plan.
- Climbing-modification advice from the § 10 matrix, labelled as expert opinion.
- Stage gates based on symmetry (≥ 90 % of the other side), pain thresholds and confidence ratings. Re-triage triggers at
  2–3 / 6 / 12 weeks.
- Clear evidence labels ("strong evidence", "expert opinion") on every recommendation.

**Shoulder**
- Exercise-first programme for gradual-onset rotator-cuff-related pain (grade A evidence [S1]), including scapular and
  thoracic modules.
- Advice that MRI findings are common without symptoms [S15]. Advice to see a clinician if there is no improvement by 12 weeks.
- For SLAP-like symptoms, the same base programme plus biceps and overhead progressions, but **recommend clinician
  assessment** (🟡).
- For instability: only post-assessment, clinician-cleared programmes; return criteria centred on no apprehension.

**Elbow**
- Medial and lateral tendinopathy programmes: isometric → isotonic → eccentric, 3 × 15, 6–12 weeks [L1].
- Pain-free grip-strength tracking. Information that steroid injections relapse more often [L2, L3].
- Ulnar-nerve screen questions with a nerve-glide module.
- Anterior elbow (brachialis/biceps) loading programme with a strict "pop + bruise → doctor" exit.

**Wrist**
- Ulnar-sided pain programme (brace/unload → rotation/deviation loading → proprioception → climbing re-exposure), consistent
  with non-operative TFCC evidence [W2, W3].
- Always screen for scaphoid, hamate, DRUJ instability and nerve symptoms first.

**Ankle**
- Functional rehab (brace + progressive weight-bearing + balance), the 8-week balance programme for recurrence prevention
  [A1, A7], PAASS-style return checks [A6], and bouldering-landing progressions.
- The Ottawa questions must run before any programme.

**Knee**
- Programmes for stable sprains and non-locking meniscal symptoms after the Ottawa-rule screen.
- Rope-before-bouldering and down-climbing advice [K1]. Hop-test and strength-symmetry gates [K4, K5].

**Hamstring**
- Graded programme from isometrics to hip-flexed heel-hook-specific loading, using the Askling and Hickey principles [H4–H6].
  Return-to-play criteria translated to heel hooks [H6, H7].

**Back/neck**
- General active management for mechanical back/neck pain [B4], belay-neck tips [B3], and trunk/hip strength.

### 11.2 What the app must not do

- **Must not diagnose.** It should say "your answers fit a pattern often seen with …", recommend confirmation, and must not
  label an injury "TFCC tear" or "SLAP lesion" as fact.
- **Must not run a loading programme when any 🔴/🟠 answer is present.** This applies to suspected fracture (Ottawa, elbow
  extension, snuffbox, heel landing), dislocation, tendon rupture or avulsion (distal biceps, proximal hamstring, Achilles),
  locked knee, haemarthrosis, neurological deficit, infection, cardiac or cauda-equina signs. Whether this is a hard block or
  "warn + acknowledge" is a design decision; see `safety-regulation.md` § 3.3 ("warn, don't lock" done safely).
- **Must not override surgeon or physiotherapist protocols** after surgery or a fracture. In those cases the app logs and
  reminds only.
- **Must not imply surgery is never needed.** It must not delay time-sensitive referrals: first-time dislocation in young
  climbers [S17], distal-biceps and proximal-hamstring ruptures [L5, H8, H9], locked knee.
- **Must not give medication or injection advice** beyond "discuss with your doctor". It must not encourage training on
  painkillers to mask pain.
- **Must not present expert-opinion timelines as guarantees.** Show ranges and the evidence level, and re-triage when someone
  falls behind.
- **Must not interpret imaging reports on its own.** It must not encourage scans as a first step (JOSPT: no initial imaging
  for cuff tendinopathy [S1]).
- **Must not use one fixed protocol for under-18s.** Lower the referral threshold (growth plates, apophyses, spondylolysis).
- **Must not ignore nerve symptoms or apprehension** by treating them like ordinary pain.
- **Must not hide the evidence gap.** Climbing-specific rehab data are sparse [E19]. Recommendations are mostly extrapolated
  and should be labelled so.
- Must respect the medical-device boundary and privacy obligations described in `safety-regulation.md` (EU MDR / MDCG 2019-11;
  GDPR Art. 9 health data).

---

## 12. References

LoE = level of evidence as defined in § 1. [CS] = climbing-specific.

### Epidemiology and climbing medicine
- **[E1]** Schöffl V, Popp D, Küpper T, Schöffl I. Injury trends in rock climbers: evaluation of a case series of 911 injuries between 2009 and 2012. *Wilderness Environ Med* 2015;26(1):62–67. doi:10.1016/j.wem.2014.08.013. LoE 4 [CS]
- **[E2]** Lutter C, Tischer T, Hotfiel T, Frank L, Enz A, Simon M, Schöffl V. Current trends in sport climbing injuries after the inclusion into the Olympic program. Analysis of 633 injuries within the years 2017/18. *Muscles Ligaments Tendons J* 2020;10(2):201–210. doi:10.32098/mltj.02.2020.06. Full text: https://www.ejcrim.com/index.php/mltj/article/view/7060. LoE 4 [CS]
- **[E3]** Grønhaug G. Self-reported chronic injuries in climbing: who gets injured when? *BMJ Open Sport Exerc Med* 2018;4:e000406. doi:10.1136/bmjsem-2018-000406. LoE 3 [CS]
- **[E4]** Grønhaug G, Saeterbakken A, Casucci T. Painfully ignorant? Impact of gender and aim of training on injuries in climbing. *BMJ Open Sport Exerc Med* 2024;10:e001972. doi:10.1136/bmjsem-2024-001972. LoE 3 [CS]
- **[E5]** Grønhaug G, Joubert LM, Saeterbakken AH, Drum SN, Nelson MC. Top of the podium, at what cost? Injuries in female international elite climbers. *Front Sports Act Living* 2023;5:1121831. doi:10.3389/fspor.2023.1121831. LoE 3 [CS]
- **[E6]** Jones G, Asghar A, Llewellyn DJ. The epidemiology of rock-climbing injuries. *Br J Sports Med* 2008;42:773–778. doi:10.1136/bjsm.2007.037978. LoE 3 [CS]
- **[E7]** Backe S, Ericson L, Janson S, Timpka T. Rock climbing injury rates and associated risk factors in a general climbing population. *Scand J Med Sci Sports* 2009;19(6):850–856. doi:10.1111/j.1600-0838.2008.00851.x. LoE 3 [CS]
- **[E8]** Woollings KY, McKay CD, Emery CA. Risk factors for injury in sport climbing and bouldering: a systematic review of the literature. *Br J Sports Med* 2015;49(17):1094–1099. doi:10.1136/bjsports-2014-094372. LoE 2–3 [CS]
- **[E9]** Woollings KY, McKay CD, Kang J, Meeuwisse WH, Emery CA. Incidence, mechanism and risk factors for injury in youth rock climbers. *Br J Sports Med* 2015;49(1):44–50. doi:10.1136/bjsports-2014-094067. LoE 3 [CS]
- **[E10]** Auer J, Schöffl VR, Achenbach L, Meffert RH, Fehske K. Indoor bouldering—a prospective injury evaluation. *Wilderness Environ Med* 2021;32(2):160–167. doi:10.1016/j.wem.2021.02.002. LoE 2 [CS]
- **[E11]** Müller M, Heck J, Pflüger P, Greve F, Biberthaler P, Crönlein M. Characteristics of bouldering injuries based on 430 patients presented to an urban emergency department. *Injury* 2022;53(4):1394–1400. doi:10.1016/j.injury.2022.02.003. LoE 4 [CS]
- **[E12]** Buzzacott P, Schöffl I, Chimiak J, Schöffl V. Rock climbing injuries treated in US emergency departments, 2008–2016. *Wilderness Environ Med* 2019;30(2):121–128. doi:10.1016/j.wem.2018.11.009. LoE 3 [CS]
- **[E13]** Lutter C, Hotfiel T, Tischer T, Lenz R, Schöffl V. Evaluation of rock climbing related injuries in older athletes. *Wilderness Environ Med* 2019;30(4):362–368. doi:10.1016/j.wem.2019.06.008. LoE 4 [CS]
- **[E14]** Schöffl VR, Hoffmann G, Küpper T. Acute injury risk and severity in indoor climbing—a prospective analysis of 515,337 indoor climbing wall visits in 5 years. *Wilderness Environ Med* 2013;24(3):187–194. doi:10.1016/j.wem.2013.03.020. LoE 2 [CS] (acute injury rate 0.02/1000 h)
- **[E15]** van Middelkoop M, Bruens ML, Coert JH, et al. Incidence and risk factors for upper extremity climbing injuries in indoor climbers. *Int J Sports Med* 2015;36(10):837–842. doi:10.1055/s-0035-1547224. LoE 2 [CS]
- **[E16]** Quarmby A, Zhang M, Geisler M, et al. Risk factors and injury prevention strategies for overuse injuries in adult climbers: a systematic review. *Front Sports Act Living* 2023;5:1269870. doi:10.3389/fspor.2023.1269870. LoE 2–3 [CS]
- **[E17]** Beurienne E, Bailly N, Luiggi M, et al. The art of falling: identifying the falls scenarios associated with bouldering injuries. *Front Sports Act Living* 2025;7:1609133. doi:10.3389/fspor.2025.1609133. LoE 3 [CS]
- **[E18]** Lutter C, Tischer T, Schöffl VR. Olympic competition climbing: the beginning of a new era—a narrative review. *Br J Sports Med* 2021;55(15):857–864. doi:10.1136/bjsports-2020-102035. LoE 5 [CS]
- **[E19]** Ehiogu U, Wells G, Jones G, Buckthorpe M, Patterson S. Return to climbing after musculoskeletal injury: a scoping review protocol of rehabilitation content, outcome measures and return to sport criteria in climbers. *BMJ Open Sport Exerc Med* 2026;12(1):e003156. doi:10.1136/bmjsem-2025-003156. Protocol (no data) [CS]
- **[E20]** den Hengst S, Powis E, Cooper C, Diamond S, Tuaño KR. Finger, hand and wrist injuries in climbers: insights from climber and provider surveys. *BMJ Open Sport Exerc Med* 2026;12(2):e003239. doi:10.1136/bmjsem-2026-003239. LoE 3 [CS]
- **[E21]** Holtzhausen LM, Noakes TD. Elbow, forearm, wrist, and hand injuries among sport rock climbers. *Clin J Sport Med* 1996;6(3):196–203. doi:10.1097/00042752-199607000-00010. LoE 5 [CS]
- **[E22]** Schöffl V, Schöffl I, Lutter C, Hochholzer T. *Climbing Medicine: A Practical Guide.* Springer, 2022. doi:10.1007/978-3-030-72184-8. Including Simon M, Lutter C, Schöffl V. "Elbow and Forearm", doi:10.1007/978-3-030-72184-8_8. LoE 5 [CS]
- Also screened, not used for numbers: Zieliński J et al. Epidemiology of musculoskeletal injuries among climbers—a systematic review. *J Funct Morphol Kinesiol* 2025;11(1):19. doi:10.3390/jfmk11010019 (7 studies, no pooling). Kubiak EN, Klugman JA, Bosco JA. Hand injuries in rock climbers. *Bull NYU Hosp Jt Dis* 2006;64:172–177 (hand/finger focus, out of scope).

### Cross-cutting
- **[G1]** Silbernagel KG, Thomeé R, Eriksson BI, Karlsson J. Continued sports activity, using a pain-monitoring model, during rehabilitation in patients with Achilles tendinopathy: a randomized controlled study. *Am J Sports Med* 2007;35(6):897–906. doi:10.1177/0363546506298279. LoE 1
- **[G2]** Smith BE, Hendrick P, Smith TO, et al. Should exercises be painful in the management of chronic musculoskeletal pain? A systematic review and meta-analysis. *Br J Sports Med* 2017;51(23):1679–1687. doi:10.1136/bjsports-2016-097383. LoE 1
- **[G3]** American College of Sports Medicine. Position stand: progression models in resistance training for healthy adults. *Med Sci Sports Exerc* 2009;41(3):687–708. doi:10.1249/MSS.0b013e3181915670. LoE 1/5

### Shoulder
- **[S1]** Desmeules F, Roy JS, Lafrance S, Charron M, et al. Rotator cuff tendinopathy diagnosis, nonsurgical medical care, and rehabilitation: a clinical practice guideline. *J Orthop Sports Phys Ther* 2025;55(4):235–274. doi:10.2519/jospt.2025.13182. CPG (grades as cited)
- **[S2]** Holmgren T, Björnsson Hallgren H, Öberg B, Adolfsson L, Johansson K. Effect of specific exercise strategy on need for surgery in patients with subacromial impingement syndrome: randomised controlled study. *BMJ* 2012;344:e787. doi:10.1136/bmj.e787. LoE 1
- **[S3]** Beard DJ, Rees JL, Cook JA, et al. Arthroscopic subacromial decompression for subacromial shoulder pain (CSAW): a multicentre, pragmatic, parallel group, placebo-controlled, three-group, randomised surgical trial. *Lancet* 2018;391(10118):329–338. doi:10.1016/S0140-6736(17)32457-1. LoE 1
- **[S4]** Paavola M, Kanto K, Ranstam J, et al. Subacromial decompression versus diagnostic arthroscopy for shoulder impingement: a 5-year follow-up of a randomised, placebo surgery controlled clinical trial (FIMPACT). *Br J Sports Med* 2021;55(2):99–107. doi:10.1136/bjsports-2020-102216. Original: *BMJ* 2018;362:k2860. LoE 1
- **[S5]** Malliaras P, Johnston R, Street G, et al. The efficacy of higher versus lower dose exercise in rotator cuff tendinopathy: a systematic review of randomized controlled trials. *Arch Phys Med Rehabil* 2020;101:1822–1834. doi:10.1016/j.apmr.2020.06.013. LoE 1 (low/very-low certainty)
- **[S6]** Ingwersen KG, Jensen SL, Sørensen L, et al. Three months of progressive high-load versus traditional low-load strength training among patients with rotator cuff tendinopathy: primary results from the double-blind randomized controlled RoCTEx trial. *Orthop J Sports Med* 2017;5(8):2325967117723292. doi:10.1177/2325967117723292. LoE 1
- **[S7]** Clifford C, Challoumas D, Paul L, Syme G, Millar NL. Effectiveness of isometric exercise in the management of tendinopathy: a systematic review and meta-analysis of randomised trials. *BMJ Open Sport Exerc Med* 2020;6:e000760. doi:10.1136/bmjsem-2020-000760. LoE 1 (limited)
- **[S8]** Kibler WB, Ludewig PM, McClure PW, Michener LA, Bak K, Sciascia AD. Clinical implications of scapular dyskinesis in shoulder injury: the 2013 consensus statement from the 'Scapular Summit'. *Br J Sports Med* 2013;47(14):877–885. doi:10.1136/bjsports-2013-092425. LoE 5
- **[S9]** Cools AM, Struyf F, De Mey K, et al. Rehabilitation of scapular dyskinesis: from the office worker to the elite overhead athlete. *Br J Sports Med* 2014;48(8):692–697. doi:10.1136/bjsports-2013-092148. LoE 5
- **[S10]** Schwank A, Blazey P, Asker M, et al. 2022 Bern consensus statement on shoulder injury prevention, rehabilitation, and return to sport for athletes at all participation levels. *J Orthop Sports Phys Ther* 2022;52(1):11–28. doi:10.2519/jospt.2022.10952. LoE 5
- **[S11]** Michener LA, Abrams JS, Bliven KCH, et al. National Athletic Trainers' Association position statement: evaluation, management, and outcomes of and return-to-play criteria for overhead athletes with superior labral anterior-posterior injuries. *J Athl Train* 2018;53(3):209–229. doi:10.4085/1062-6050-59-16. LoE 5 (SR-based)
- **[S12]** Steinmetz RG, Guth JJ, Matava MJ, Brophy RH, Smith MV. Return to play following nonsurgical management of superior labrum anterior-posterior tears: a systematic review. *J Shoulder Elbow Surg* 2022;31(6):1323–1333. doi:10.1016/j.jse.2021.12.022. LoE 3
- **[S13]** Edwards SL, Lee JA, Bell JE, et al. Nonoperative treatment of superior labrum anterior posterior tears: improvements in pain, function, and quality of life. *Am J Sports Med* 2010;38(7):1456–1461. doi:10.1177/0363546510370937. LoE 4
- **[S14]** Schöffl V, Popp D, Lutter C, Küpper T, Ott B. SLAP lesions in high level sport climbers. Outcome after primary surgical long biceps tenodesis. DKOU 2017 meeting abstract. doi:10.3205/17dkou088. https://www.egms.de/static/en/meetings/dkou2017/17dkou088.shtml. LoE 4 (abstract) [CS]
- **[S15]** Cooper JD, Seiter MN, Ruzbarsky JJ, et al. Shoulder pathology on magnetic resonance imaging in asymptomatic elite-level rock climbers. *Orthop J Sports Med* 2022;10(2):23259671211073137. doi:10.1177/23259671211073137. LoE 3 [CS]
- **[S16]** Beeler S, Pastor T, Fritz B, Filli L, Schweizer A, Wieser K. Impact of 30 years' high-level rock climbing on the shoulder: an MRI study of 31 climbers. *J Shoulder Elbow Surg* 2021;30(9):2022–2031. doi:10.1016/j.jse.2020.12.017. LoE 3 [CS]
- **[S17]** Belk JW, Wharton BR, Houck DA, et al. Shoulder stabilization versus immobilization for first-time anterior shoulder dislocation: a systematic review and meta-analysis of level 1 randomized controlled trials. *Am J Sports Med* 2023;51(6):1634–1643. doi:10.1177/03635465211065403. LoE 1
- **[S18]** Paterson WH, Throckmorton TW, Koester M, Azar FM, Kuhn JE. Position and duration of immobilization after primary anterior shoulder dislocation: a systematic review and meta-analysis of the literature. *J Bone Joint Surg Am* 2010;92(18):2924–2933. doi:10.2106/JBJS.J.00631. LoE 1–2
- **[S19]** Simon M, Geffel L, Lutter C, Schöffl V. Functional and sport-specific outcome following traumatic first-time shoulder dislocation and arthroscopic surgical repair in rock climbers. *Wilderness Environ Med* 2023;34(3):303–310. doi:10.1016/j.wem.2023.05.001. LoE 4 [CS]
- **[S20]** Ahmed A, Batiste A, Seidl A, McCarty E, Frank R, Bravman J. Poster 120: Return to rock climbing after shoulder stabilization surgery: a MOON instability multicenter study. *Orthop J Sports Med* 2024;12(7 suppl2). doi:10.1177/2325967124S00089 (PMC11328282). LoE 2 (abstract) [CS]
- **[S21]** Watson L, Pizzari T, Balster S, Lenssen R, Warby SA. Advances in the non-operative management of multidirectional instability of the glenohumeral joint. *J Clin Med* 2022;11(17):5140. doi:10.3390/jcm11175140. LoE 5 (summarises their RCT)
- **[S22]** Roseborrough A, Lebec M. Differences in static scapular position between rock climbers and a non-rock climber population. *N Am J Sports Phys Ther* 2007;2(1):44–50. PMC2953287. LoE 3 [CS]
- **[S23]** Ardern CL, Glasgow P, Schneiders A, et al. 2016 consensus statement on return to sport from the First World Congress in Sports Physical Therapy, Bern. *Br J Sports Med* 2016;50:853–864. doi:10.1136/bjsports-2016-096278. LoE 5

### Elbow
- **[L1]** Lucado AM, Day JM, Vincent JI, MacDermid JC, Fedorczyk J, Grewal R, Martin RL. Lateral elbow pain and muscle function impairments: clinical practice guidelines. *J Orthop Sports Phys Ther* 2022;52(12):CPG1–CPG111. doi:10.2519/jospt.2022.0302. CPG
- **[L2]** Bisset L, Beller E, Jull G, Brooks P, Darnell R, Vicenzino B. Mobilisation with movement and exercise, corticosteroid injection, or wait and see for tennis elbow: randomised trial. *BMJ* 2006;333(7575):939. doi:10.1136/bmj.38961.584653.AE. LoE 1
- **[L3]** Coombes BK, Bisset L, Brooks P, Khan A, Vicenzino B. Effect of corticosteroid injection, physiotherapy, or both on clinical outcomes in patients with unilateral lateral epicondylalgia: a randomized controlled trial. *JAMA* 2013;309(5):461–469. doi:10.1001/jama.2013.129. LoE 1
- **[L4]** See ZH, Loo CE, Jaafar Z. Eccentric exercise therapy for medial epicondylitis: a systematic review of clinical outcomes. *Complement Ther Med* 2026;98:103364. doi:10.1016/j.ctim.2026.103364. LoE 2 (low certainty)
- **[L5]** O'Driscoll SW, Goncalves LB, Dietz P. The hook test for distal biceps tendon avulsion. *Am J Sports Med* 2007;35(11):1865–1869. doi:10.1177/0363546507305016. LoE 2 (diagnostic)
- **[L6]** Appelboam A, Reuben AD, Benger JR, et al. Elbow extension test to rule out elbow fracture: multicentre, prospective validation and observational study of diagnostic accuracy in adults and children. *BMJ* 2008;337:a2428. doi:10.1136/bmj.a2428. LoE 1–2 (diagnostic)
- **[L7]** Meller LLT, Allen MR, Sigua LH, Hooper J, Vitale K. More than just another elbow tendinopathy: a case series of misdiagnosed ulnar nerve compression and review of common upper extremity misdiagnoses in climbing. *Curr Sports Med Rep* 2024;23(10):340–347. doi:10.1249/JSR.0000000000001199. LoE 4 [CS]
- **[L8]** Vagy J. Case report: using telehealth to treat triceps tendinopathy in a rock climber. *Front Sports Act Living* 2022;4:829480. doi:10.3389/fspor.2022.829480. LoE 4 [CS]
- **[L9]** Hooper J (PT, DPT, OCS, CAFS). How I fix stubborn medial elbow pain / "tendinitis" (PT's full comprehensive protocol). Hooper's Beta (Ep. 162). https://www.hoopersbeta.com/library/fix-stubborn-medial-elbow-pain. LoE 5 (practitioner) [CS]
- **[L10]** Hooper J. How to fix tennis elbow for climbers (outer elbow pain, lateral epicondylitis). Hooper's Beta. https://www.hoopersbeta.com/library/how-to-fix-pain-on-the-outside-of-your-elbow-lateral-epicondylalgia. LoE 5 (practitioner) [CS]

### Wrist
- **[W1]** Schöffl V, von Schroeder H, Lisse J, El-Sheikh Y, Küpper T, Klinder A, Lutter C. Wrist injuries in climbers. *Am J Sports Med* 2023;51(13):3416–3425. doi:10.1177/03635465231199671. LoE 4 [CS]
- **[W2]** Choi SI, Malik S, MacLean S. The natural history of non-operatively treated traumatic triangular fibrocartilage complex tears: a systematic review. *J Wrist Surg* 2024;13(6):550–558. doi:10.1055/s-0044-1786164. LoE 3
- **[W3]** Tse YL, Chau WW, Wong CW. A structured non-operative treatment program for traumatic triangular fibrocartilage complex tear: a quasi-experimental study. *Hand Surg Rehabil* 2023;42(6):492–498. doi:10.1016/j.hansur.2023.07.009. LoE 3
- **[W4]** Tay SC, Tomita K, Berger RA. The "ulnar fovea sign" for defining ulnar wrist pain: an analysis of sensitivity and specificity. *J Hand Surg Am* 2007;32(4):438–444. doi:10.1016/j.jhsa.2007.01.022. LoE 3 (diagnostic)
- **[W5]** Choudhury MM, Ling AAA, Yap RTJ. Is the ulnar fovea sign positive only in foveal tears of the triangular fibrocartilage complex? *J Hand Surg Eur* 2025;50(5):661–662. doi:10.1177/17531934241279921. LoE 4
- **[W6]** Lutter C, Schweizer A, Hochholzer T, Bayer T, Schöffl V. Pulling harder than the hamate tolerates: evaluation of hamate injuries in rock climbing and bouldering. *Wilderness Environ Med* 2016;27(4):492–499. doi:10.1016/j.wem.2016.09.003. LoE 4 [CS]
- **[W7]** Jasina A, Gräber S, Pourostad P, et al. Biomechanical analysis of sport induced hamate stress fractures. *Clin Biomech* 2025;126:106567. doi:10.1016/j.clinbiomech.2025.106567. LoE 5 (cadaver) [CS]
- **[W8]** Tobin AL. "Ice axe wrist": a case report of intersection syndrome in 2 climbers. *Wilderness Environ Med* 2017;28(3):230–233. doi:10.1016/j.wem.2017.03.016. LoE 4 [CS]
- **[W9]** Hooper J. How to fix ulnar wrist pain (TFCC injury recovery guide). Hooper's Beta (Ep. 157). https://www.hoopersbeta.com/library/how-to-fix-ulnar-wrist-pain-tfcc-injury-recovery-guide. LoE 5 (practitioner) [CS]
- **[W10]** NICE. Fractures (non-complex): assessment and management. NG38, 2016. https://www.nice.org.uk/guidance/ng38. National guideline; the "consider MRI first-line for suspected scaphoid fracture" wording was read via secondary sources (BSSH scaphoid standards, Dean et al. *Bone Joint Open* 2021) and should be checked against the NICE page before quoting.
- **[W11]** Gilmore NK, Klimek P, Abrahamsson E, Baar K. The VISA-C questionnaire: a self-administered assessment to measure finger/hand/wrist pain in climbers. *Sports Med Open* 2025;11:105. doi:10.1186/s40798-025-00912-y. LoE 3 [CS] (candidate outcome measure for the app)

### Ankle / foot
- **[A1]** Martin RL, Davenport TE, Fraser JJ, et al. Ankle stability and movement coordination impairments: lateral ankle ligament sprains revision 2021. *J Orthop Sports Phys Ther* 2021;51(4):CPG1–CPG80. doi:10.2519/jospt.2021.0302. CPG
- **[A2]** Vuurberg G, Hoorntje A, Wink LM, et al. Diagnosis, treatment and prevention of ankle sprains: update of an evidence-based clinical guideline. *Br J Sports Med* 2018;52(15):956. doi:10.1136/bjsports-2017-098106. CPG
- **[A3]** Doherty C, Bleakley C, Delahunt E, Holden S. Treatment and prevention of acute and recurrent ankle sprain: an overview of systematic reviews with meta-analysis. *Br J Sports Med* 2017;51(2):113–125. doi:10.1136/bjsports-2016-096178. LoE 1
- **[A4]** Bachmann LM, Kolb E, Koller MT, Steurer J, ter Riet G. Accuracy of Ottawa ankle rules to exclude fractures of the ankle and mid-foot: systematic review. *BMJ* 2003;326(7386):417. doi:10.1136/bmj.326.7386.417. LoE 1 (diagnostic)
- **[A5]** Stiell IG, Greenberg GH, McKnight RD, et al. Decision rules for the use of radiography in acute ankle injuries: refinement and prospective validation. *JAMA* 1993;269(9):1127–1132. doi:10.1001/jama.269.9.1127. LoE 1–2 (diagnostic)
- **[A6]** Smith MD, Vicenzino B, Bahr R, et al. Return to sport decisions after an acute lateral ankle sprain injury: introducing the PAASS framework—an international multidisciplinary consensus. *Br J Sports Med* 2021;55(22):1270–1276. doi:10.1136/bjsports-2021-104087. LoE 5
- **[A7]** Hupperets MDW, Verhagen EALM, van Mechelen W. Effect of unsupervised home based proprioceptive training on recurrences of ankle sprain: randomised controlled trial. *BMJ* 2009;339:b2684. doi:10.1136/bmj.b2684. LoE 1
- **[A8]** Schöffl V, Küpper T. Feet injuries in rock climbers. *World J Orthop* 2013;4(4):218–228. doi:10.5312/wjo.v4.i4.218. LoE 5 [CS]

### Hamstring
- **[H1]** Schöffl V, Lutter C, Popp D. The "heel hook"—a climbing-specific technique to injure the leg. *Wilderness Environ Med* 2016;27(2):294–301. doi:10.1016/j.wem.2015.12.007. LoE 4 [CS]. Detailed counts taken from a secondary summary (LITFL); the abstract available to me was truncated.
- **[H2]** Ehiogu UD, Stephens G, Jones G, Schöffl V. Acute hamstring muscle tears in climbers—current rehabilitation concepts. *Wilderness Environ Med* 2020;31(4):441–453. doi:10.1016/j.wem.2020.07.002. LoE 5 [CS]
- **[H3]** Vantorre A, Schellhammer S. Severe injuries of proximal hamstrings in high-performance sport climbers. *Dtsch Z Sportmed* 2022;73:77–80. doi:10.5960/dzsm.2021.521. LoE 4 [CS]
- **[H4]** Askling CM, Tengvar M, Thorstensson A. Acute hamstring injuries in Swedish elite football: a prospective randomised controlled clinical trial comparing two rehabilitation protocols. *Br J Sports Med* 2013;47(15):953–959. doi:10.1136/bjsports-2013-092165. LoE 1
- **[H5]** Askling CM, Tengvar M, Tarassova O, Thorstensson A. Acute hamstring injuries in Swedish elite sprinters and jumpers: a prospective randomised controlled clinical trial comparing two rehabilitation protocols. *Br J Sports Med* 2014;48(7):532–539. doi:10.1136/bjsports-2013-093214. LoE 1
- **[H6]** Hickey JT, Timmins RG, Maniar N, et al. Pain-free versus pain-threshold rehabilitation following acute hamstring strain injury: a randomized controlled trial. *J Orthop Sports Phys Ther* 2020;50(2):91–103. doi:10.2519/jospt.2020.8895. LoE 1–2
- **[H7]** van der Horst N, Backx F, Goedhart EA, Huisstede BM; HIPS-Delphi Group. Return to play after hamstring injuries in football (soccer): a worldwide Delphi procedure regarding definition, medical criteria and decision-making. *Br J Sports Med* 2017;51(22):1583–1591. doi:10.1136/bjsports-2016-097206. LoE 5
- **[H8]** Harris JD, Griesser MJ, Best TM, Ellis TJ. Treatment of proximal hamstring ruptures—a systematic review. *Int J Sports Med* 2011;32(7):490–495. doi:10.1055/s-0031-1273753. LoE 3–4
- **[H9]** van der Made AD, Peters RW, Verheul C, et al. Proximal hamstring tendon avulsions: comparable clinical outcomes of operative and non-operative treatment at 1-year follow-up using a shared decision-making model. *Br J Sports Med* 2022;56(6):340–348. doi:10.1136/bjsports-2021-104588. LoE 2
- **[H10]** Lauf K, van der Made AD, Jaspers R, et al. Successful rapid return to performance following non-operative treatment of proximal hamstring tendon avulsion in elite athletes. *BMJ Open Sport Exerc Med* 2025;11(2):e002468. doi:10.1136/bmjsem-2025-002468. LoE 4

### Knee
- **[K1]** Lutter C, Tischer T, Cooper C, Frank L, Hotfiel T, Lenz R, Schöffl V. Mechanisms of acute knee injuries in bouldering and rock climbing athletes. *Am J Sports Med* 2020;48(3):730–738. doi:10.1177/0363546519899931. LoE 4 [CS]
- **[K2]** Cruz JK, Chew KY, Ren MY, Dave Lee YH. Complex knee injuries from bouldering: a case series. *J ISAKOS* 2025;14:100995. doi:10.1016/j.jisako.2025.100995. LoE 4 [CS]
- **[K3]** Vagy J, Schöffl V, Ohde J, Petersen W, Lutter C. Anterior cruciate ligament rehabilitation and return to sport in rock climbing athletes: a practical concept paper. *Front Sports Act Living* 2025;7:1580509. doi:10.3389/fspor.2025.1580509. LoE 5 [CS]
- **[K4]** Logerstedt DS, Scalzitti DA, Bennell KL, et al. Knee pain and mobility impairments: meniscal and articular cartilage lesions revision 2018. *J Orthop Sports Phys Ther* 2018;48(2):A1–A50. doi:10.2519/jospt.2018.0301. CPG (summary read via the APTA-distributed translated summary)
- **[K5]** Logerstedt DS, Scalzitti D, Risberg MA, et al. Knee stability and movement coordination impairments: knee ligament sprain revision 2017. *J Orthop Sports Phys Ther* 2017;47(11):A1–A47. doi:10.2519/jospt.2017.0303. CPG
- **[K6]** Kise NJ, Risberg MA, Stensrud S, Ranstam J, Engebretsen L, Roos EM. Exercise therapy versus arthroscopic partial meniscectomy for degenerative meniscal tear in middle aged patients: randomised controlled trial with two year follow-up. *BMJ* 2016;354:i3740. doi:10.1136/bmj.i3740. LoE 1
- **[K7]** Svantesson J, Piussi R, Weissglas E, et al. Shedding light on the non-operative treatment of the forgotten side of the knee: rehabilitation of medial collateral ligament injuries—a systematic review. *BMJ Open Sport Exerc Med* 2024;10(2):e001750. doi:10.1136/bmjsem-2023-001750. LoE 3 (very low certainty)
- **[K8]** Derscheid GL, Garrick JG. Medial collateral ligament injuries in football: nonoperative management of grade I and grade II sprains. *Am J Sports Med* 1981;9(6):365–368. LoE 4 (figures via secondary source)
- **[K9]** Stiell IG, Greenberg GH, Wells GA, et al. Prospective validation of a decision rule for the use of radiography in acute knee injuries. *JAMA* 1996;275(8):611–615. doi:10.1001/jama.1996.03530320035031. LoE 1–2 (diagnostic)
- **[K10]** Bachmann LM, Haberzeth S, Steurer J, ter Riet G. The accuracy of the Ottawa knee rule to rule out knee fractures: a systematic review. *Ann Intern Med* 2004;140(2):121–124. doi:10.7326/0003-4819-140-5-200403020-00013. LoE 1 (diagnostic)

### Back / neck
- **[B1]** Förster R, Penka G, Bösl T, Schöffl VR. Climber's back—form and mobility of the thoracolumbar spine leading to postural adaptations in male high ability rock climbers. *Int J Sports Med* 2009;30(1):53–59. doi:10.1055/s-2008-1038762. LoE 3 [CS]
- **[B2]** Carraro A, Gilic B, Bertolo R, et al. Lower back pain in young climbers: a retrospective cross-sectional study. *Front Sports Act Living* 2023;5:1328811. doi:10.3389/fspor.2023.1328811. LoE 3 [CS]
- **[B3]** Schmid A, Eberhardt U, Oberhoffer R, Kern C. Zervikale Belastung während des Sicherns (Cervical strains while belaying). *Sports Orthop Traumatol* 2013;29(3):229–236. doi:10.1016/j.orthtr.2013.05.002. LoE 2 (small randomised) [CS]
- **[B4]** Foster NE, Anema JR, Cherkin D, et al. Prevention and treatment of low back pain: evidence, challenges, and promising directions. *Lancet* 2018;391(10137):2368–2383. doi:10.1016/S0140-6736(18)30489-6. LoE 1 (guideline synthesis)
- **[B5]** Finucane LM, Downie A, Mercer C, et al. International framework for red flags for potential serious spinal pathologies. *J Orthop Sports Phys Ther* 2020;50(7):350–372. doi:10.2519/jospt.2020.9971. LoE 5
- **[B6]** Stiell IG, Wells GA, Vandemheen KL, et al. The Canadian C-spine rule for radiography in alert and stable trauma patients. *JAMA* 2001;286(15):1841–1848. doi:10.1001/jama.286.15.1841. LoE 1–2 (diagnostic)

### Practitioner sources (expert opinion, LoE 5)
- **[P1]** Hooper's Beta library, Jason Hooper PT, DPT, OCS, CAFS. E.g., "Shoulder impingement syndrome in rock climbers" https://www.hoopersbeta.com/library/shoulder-impingement-syndrome-in-rock-climbers; "Proximal biceps pain" https://www.hoopersbeta.com/library/rock-climbers-shoulder-pain-cause-amp-treatment-biceps-tendinopathy-pt-2. Educational content; parts are commercial (paid "Recovery Blueprint").
- **[P2]** The Climbing Doctor, Jared Vagy DPT. E.g., medial epicondylosis https://theclimbingdoctor.com/portfolio-items/medial-epicondylosis-golfers-elbow/; TFCC https://theclimbingdoctor.com/tfcc-injury-a-common-source-of-wrist-pain-in-climbers/; shoulder impingement programme https://theclimbingdoctor.com/portfolio-items/shoulder-impingement-rehabilitation-program. Only search-result excerpts were reviewed (the full pages were not retrievable); the full protocols are paid products.

---

## 13. Open points for DESIGN.md

1. **Pain rule per condition.** Keep a single default (≤ 2–3/10, next morning not worse) and add the nerve and instability
   exceptions. Optionally allow "tolerable ≤ 5/10" only for clinician-confirmed tendinopathy.
2. **Strength-symmetry testing at home.** Decide which tests the app can guide reliably:
   - same-load reps-to-fatigue (e.g., ER band reps, single-leg calf raises, single-leg bridge);
   - a luggage-scale or pickup-pin isometric (as in the finger plan) for ER/IR, elbow flexion and grip;
   - hop distance with a tape measure.
3. **Outcome measures to log.** Candidates: PRTEE-like elbow items, VISA-C (climbing-specific finger/hand/wrist [W11]), a
   PSFS-style "my 3 hardest climbing moves" score (recommended for high-demand users in the LET CPG [L1]), and a confidence
   rating.
4. **Hard block vs warn.** Align 🔴/🟠 handling with `safety-regulation.md` § 3.3 and the owner's "warn, don't lock" philosophy.
5. **Localisation.** All exercise names and triage questions need DE/EN wording and pictures for palpation sites
   (Ottawa points, snuffbox, fovea, joint lines).
