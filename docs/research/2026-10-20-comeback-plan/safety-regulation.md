# Safety, red flags, escalation and the medical-device boundary

**Topic:** CruxCoach injury-comeback feature (triage questions, phase-based plans, daily pain log, criteria-based progression)
**Date:** 2026-10-20 · **Status:** research report for DESIGN.md · **Not legal advice.**
**Scope:** (1) red flags and urgency levels, (2) EU MDR / Germany / FDA boundary, (3) UX patterns that reduce liability, plus privacy, (4) recommended wording (EN/DE), (5) requirements checklist.

> **Method and limits.** Primary texts were read where they could be reached: MDCG 2019-11 rev.1 (June 2025), MDCG 2021-24 rev.1 (April 2026), the Borderline Manual v5 (April 2026) and MDCG 2025-4, all as full PDFs from health.ec.europa.eu, plus BfArM FAQ, § 6 MPDG, § 309 BGB, the FDA example lists, the Apple and Google Play policies and the NHS condition pages. EUR-Lex and NICE CKS block automated access. MDR article text is therefore quoted as MDCG reproduces it, and NICE CKS content comes from secondary summaries (marked as such). Clinical red flags are built from NHS/NICE guidance, validated decision rules (Ottawa) and the climbing-medicine literature. For finger-specific items the evidence is mostly case series and expert opinion.

---

## 0. Key conclusions (TL;DR)

1. **The MDR boundary is close, and the feature as described sits on the wrong side of it.** Under MDR Art. 2(1), "treatment, alleviation of, or monitoring of an **injury**" is an explicit medical purpose. MDCG 2019-11 rev.1 (June 2025) adds a new example that is very close to this feature: *"MDSW that uses the data of a patient with a specific musculoskeletal pathology (e.g. X-rays, range of motion, weight, age, etc.) and is intended to alleviate pain associated with the musculoskeletal pathology by recommending personalised rehabilitation exercises"*. That is a medical device, and Rule 11(a) makes it **at least class IIa**, which means a notified body, a QMS, clinical evaluation and vigilance. A volunteer FOSS project cannot do that.
2. **What keeps it a training aid** is the intended purpose plus function design. The app may: show *generic* stage templates (a library, like a book), let the **user choose** the stage, compute loads from the user's own strength test (normal fitness maths), keep a **diary**, and give **one-way safety prompts** that only ever say "get it checked". The app must not diagnose, grade, select treatment from symptoms, auto-progress from pain data, or "clear" anyone to return.
3. **A grey zone remains.** These choices reduce the regulatory risk but do not remove it. If the owner wants certainty, the options are a regulatory-law opinion or a formal **§ 6 Abs. 2 MPDG** status decision. The manufacturer may apply, it costs a fee, and the decision should come within about 3 months.
4. **Marketing defines the intended purpose too** (Art. 2(12) MDR: label, IFU, *promotional or sales materials*). BfArM does not let a "kein Medizinprodukt" note override medical claims made elsewhere. Avoid "Reha-App", "heilt", "Therapie", "Diagnose" and "personalisierte Reha" in the store text, the README, Nostr posts and the UI. German advertising law (HWG § 3) also forbids implying certain success.
5. **Red flags must lead to escalation and never to reassurance.** The app should never say "no fracture" or "no X-ray needed", even if the Ottawa-rule items come out negative. Use three urgency levels: **Notfall / emergency (112)**, **zeitnah / within days**, and **routine / if not better in about 2 weeks or getting worse**.
6. **Climbing-specific triggers to include:** a pop with swelling or bruising (suspected pulley rupture, which needs ultrasound; early treatment within about 10 days matters for multi-pulley injuries); visible **bowstringing**, i.e. the tendon lifting away from the bone (grade IV, surgical); inability to bend the fingertip on its own (FDP avulsion, surgery within about 7–10 days); Kanavel signs of infection (emergency); heel-hook pop with bruising (proximal hamstring avulsion); elbow pop (distal biceps rupture).
7. **Under 18:** finger pain at the dorsal PIP joint can be an epiphyseal (growth-plate) stress fracture. X-ray can miss it, and MRI may be needed. Always recommend a medical assessment, and default to no max tests and no campus/limit board. The user can still change these settings.
8. **"Warn, don't lock" works with this.** Make the warnings clear but never block. Never make "continue anyway" the primary button. Never auto-progress. Default to the more conservative stage. Store the user's choices locally.
9. **Disclaimers do not protect you in Germany.** § 309 Nr. 7a BGB voids AGB clauses that exclude liability for injury to life, body or health. What actually reduces liability is careful design plus clear instructions and warnings (*Instruktionspflicht*).
10. **Health data is Art. 9 GDPR data.** Keep it on the device and out of Nostr/Blossom and community sync. Exclude it from Android Auto Backup and iCloud, or ask first. Collect no telemetry on it and send nothing to cloud LLMs.

---

## 1. Red flags and urgency levels

### 1.1 Principles for using red flags in an app

- **Single red flags have poor diagnostic accuracy.** The IFOMPT international framework (Finucane et al., JOSPT 2020) says so explicitly and recommends an "index of suspicion" built from clusters and progression. For an app this means **a low threshold for escalation and no attempt to compute a diagnosis**.
- **Escalation only goes one way.** A ticked red flag leads to "get it checked". An unticked list does **not** lead to "you're fine". Wording: *"None ticked – that doesn't rule anything out."*
- **Clinical decision rules are for examiners.** The Ottawa ankle and knee rules were derived and validated with clinicians palpating defined bony points (Stiell 1993; 1996). Sensitivity is about 98–100% and specificity is modest (Bachmann 2003, 2004; Beckenkamp 2017). The app may use the lay-observable parts ("can't take four steps", "bony spot hurts to press") **only as prompts to get checked**, never to rule a fracture out.
- **Use the urgency vocabulary of the public health services.** The NHS condition pages use three tiers: 999/A&E, urgent (111/urgent GP), and see a GP. Copying that structure keeps the app in the role of "generic safety information".

**Urgency levels (proposed):**

| Level | DE label | EN label | Action text |
|---|---|---|---|
| E | **Sofort / Notfall** | **Now / emergency** | 112 or emergency department (*Notaufnahme*); don't drive yourself |
| D | **Zeitnah (heute bis in wenigen Tagen)** | **Soon (today to a few days)** | GP/urgent appointment, out of hours **116117** (DE), sports or hand specialist; ultrasound or X-ray as needed |
| R | **Bei Bedarf Termin** | **Book a check** | If not better after **~2 weeks** (stronger prompt at 3), getting worse, keeps coming back, or stops everyday activities |

### 1.2 Universal red flags (any region)

| Sign (lay wording) | Level | Rationale / source |
|---|---|---|
| Body part **changed shape / points at an odd angle**; suspected dislocation | E | NHS sprains & strains: 999/A&E; NICE CKS sprains (secondary summaries): fracture/dislocation → ED |
| **Heard a crack** at the time of injury | E/D | NHS sprains: 999/A&E. NHS finger pain lists "snap, grinding or popping" as urgent (111) |
| **Numb, tingling, pins and needles** in the injured part, or skin **blue/grey/pale/cold** | E | NHS sprains: 999/A&E (nerve or blood-supply damage); NHS elbow/arm: tingling/numb → A&E |
| **Can't move it at all / can't hold things / can't bear weight or take more than a few steps** | E/D | NHS sprains (111/urgent); NHS finger (urgent); Ottawa ankle/knee: inability to bear weight for 4 steps → X-ray |
| **Open wound** over the injury or a joint | E | NICE CKS sprains (secondary): wound penetrating a joint → ED |
| **Very painful, or pain getting worse fast**; feeling faint/sick from pain | D | NHS sprains/finger: urgent |
| **Large swelling or bruising, or getting worse**; marked swelling while on **blood thinners** | D | NHS sprains: urgent; NICE CKS lists anticoagulants/bleeding disorders as complicating factors |
| **Red, hot, swollen** plus **fever, chills, feeling unwell** | E/D | NHS sprains/finger/knee: possible infection; NICE CKS shoulder (secondary): suspected joint infection = same-day emergency |
| **Pain at night or at rest that keeps increasing**, unexplained **weight loss**, history of **cancer**, feeling generally unwell | D | Red flags for serious pathology (tumour/infection), e.g. NVL Kreuzschmerz ("starker nächtlicher Schmerz"); IFOMPT framework 2020; NHS shoulder: "feel feverish or unwell" → urgent |
| **Arm/shoulder pain with chest pressure, tightness or breathlessness** | E | NHS elbow/arm: 999 (possible heart attack). Not a training injury, but climbers do attribute it to one |
| **Calf pain and swelling after immobilisation/surgery**; plus breathlessness/chest pain | D / E | NHS DVT: suspected DVT → urgent; with chest pain/breathlessness → 999 |
| **After a fall: hit your head** and lost consciousness, confused/drowsy, seizure, new weakness or numbness | E | NICE NG232 (2023): unconsciousness, focal neurological deficit, seizure → 999/ED |
| **Neck or back pain after a fall with numbness/weakness**; **numbness around genitals/buttocks, new bladder/bowel problems**, both legs affected | E | NICE CKS sciatica / cauda equina red flags (2018 revision; secondary summaries); NVL Kreuzschmerz |
| **Not better after ~2 weeks** of sensible self-care, or **getting worse / keeps coming back**, or stops normal activities | R | NHS finger pain ("not improved after treating it at home for 2 weeks"); NHS shoulder ("does not improve after 2 weeks"); NHS sprains ("After 2 weeks, most sprains and strains will feel better"); NHS elbow/knee: "a few weeks" |
| Gradual **tingling/numbness** in the hand without trauma; **diabetes** with hand problems | R | NHS finger pain: see a GP |

### 1.3 Region-specific red flags relevant to climbers

**Fingers and hand** (*Finger/Hand*)

| Sign | Level | Why / source |
|---|---|---|
| **Pop + pain/swelling/bruising at the base or middle of the finger** (suspected A2/A3/A4 pulley rupture) | D | Schöffl et al. 2003 (n=604; 20% had pulley injuries): ultrasound for all finger injuries, grades I–III conservative, **grade IV surgical**. EJR 2026 imaging review: multi-pulley ruptures *without* major bowstringing (IVa) can often be managed conservatively **if treatment starts early (within 10 days)**. That is the reason for "within days" |
| **Visible bowstringing** (tendon lifts away from the bone when gripping) | D (soon; hand surgeon) | Grade IV / IVb, "typically require immediate surgical management" (EJR 2026 review); Schöffl 2003 |
| **Can't bend the fingertip (DIP) on its own** after a forceful pull ("jersey finger", FDP avulsion) | D (same/next day) | Leddy–Packer type I: repair **within ~7–10 days** before tendon retraction/necrosis (Radiopaedia; Leddy & Packer 1977). Often missed at first presentation |
| **Finger held slightly bent, spindle-shaped swelling, tender along the flexor sheath, very painful to straighten**, often after a cut/split tip | E | **Kanavel signs**: pyogenic flexor tenosynovitis is a surgical emergency (Kanavel 1912; Clin Orthop Relat Res "In brief" 2015, PMC4686527) |
| **Joint wobbles sideways, feels loose, won't fully straighten, or is deformed** (collateral ligament, volar plate, central slip, avulsion fracture) | D | Needs X-ray; NHS finger: "changed shape" → urgent |
| **Under 18: gradual pain on the back (dorsal side) of the middle finger joint (PIP)**, often middle/ring finger, swelling, worse with crimping/campus | D (book within days) | Epiphyseal stress fracture (Salter-Harris III most common). **X-ray may miss it, so MRI** if suspicion remains. Stop climbing 6–8 weeks after diagnosis; no campus board until skeletal maturity (Jones, Johnson, Schöffl et al., BJSM blog 2023; Curr Sports Med Rep 2022). Untreated, it can disturb growth (BMC fact sheet). Hochholzer & Schöffl 2005: all fatigue fractures, return only after 4–6 months consolidation |
| Fall on outstretched hand: **pain/tenderness at the base of the thumb** (anatomical snuffbox) | D (same/next day) | Suspected scaphoid fracture: immobilise and image even if the first X-ray is normal (NICE NG38 summaries; UK audit, Bone Jt Open 2021) |

**Elbow / upper arm**

| Sign | Level | Why / source |
|---|---|---|
| **Pop at the front of the elbow, bruising in the crease, bulge in the upper arm ("Popeye"), weak when turning the palm up** | D (within days) | Distal biceps rupture. Acute repair is usually aimed for within **2–3 weeks** (BestBETs; timing evidence is small cohorts) |
| **Snapping noise or changed shape**, severe pain, hard to move | E | NHS elbow/arm: A&E |

**Shoulder**

| Sign | Level | Why / source |
|---|---|---|
| Suspected **dislocation** (shape changed, arm held, can't move) | E | NICE CKS shoulder (secondary): unreduced dislocation = same-day emergency |
| **Since the injury you can't lift the arm sideways**, and it is not only the pain that stops you | D | Acute rotator-cuff tear: urgent referral, ideally next available clinic (NICE CKS shoulder, secondary) |
| **Pain in both shoulders**, fever, unwell; shoulder hot/cold, pins and needles that persist, no feeling | D | NHS shoulder pain: urgent list |

**Knee, hamstring, ankle, foot** (bouldering falls, heel and toe hooks)

| Sign | Level | Why / source |
|---|---|---|
| **Can't take four steps** (right after the injury and now), or pressing the **bony bumps at the ankle/midfoot** or the **kneecap / outside top of the shin (fibular head)** hurts, or you can't bend the knee to 90° | D (same day; X-ray) | Ottawa ankle (Stiell 1993) and knee rules (Stiell 1996). Used here only as a "get checked" trigger |
| **Knee locks, gives way, or swells up within hours**; painful clicking | D | NHS knee pain: urgent (111) |
| **Heel hook: pop with bruising at the back of the thigh**, hard to walk or sit | D (within days) | Proximal hamstring avulsion. Climbing case report 2022 (high heel hooks); earlier repair (≲ 4–5 weeks) associated with better outcomes (ISAKOS 2025 abstract; level IV series) |
| **Pop at the heel/calf, "kicked" feeling, can't stand on tiptoe** | D (same day) | Achilles rupture: up to 25% missed at first presentation (GPnotebook); calf-squeeze test |
| **Heel or back pain after a fall from height onto the feet** | D/E | Calcaneus or spine fracture pattern; treat like fracture (generic fracture red flags) |

### 1.4 Under-18s (*Jugendliche, Wachstumsfugen*)

- Growth plates (physes) are weaker than tendons and ligaments in adolescents. Repetitive crimping or campus loading causes **primary periphyseal stress injuries** of the fingers, most often the middle finger PIP (91–95% in case series) at ages 13–15 (Jones et al. 2022; Hochholzer & Schöffl 2005; Caine et al. 2022 SR).
- **Recommendation:** for any finger injury in a user under 18, the start screen recommends a medical assessment (level D) **before** loading. Plans default to **no maximal tests, no campus board, no limit-board crimping**. Show the note "Plans are built for adults; growing fingers need a different approach" and let the user adjust (owner rule).
- The example plan's % of healthy-hand max and the max test in week 5 are adult concepts. Do not suggest max hangs for under-18s.
- Under GDPR Art. 8, Germany sets the digital consent age at **16**. If consent is ever the legal basis (e.g., optional sharing), under-16s need parental consent. Local-only storage avoids this question (see § 3.6).

### 1.5 "Not better" and "getting worse" rules

| Trigger | Suggested in-app rule | Basis |
|---|---|---|
| **No improvement after 2 weeks** | Day 14 of a plan: the user's own pain entries (pressure pain / morning pain) are not lower than in days 1–3 → soft prompt "worth getting it checked" | NHS finger/shoulder "2 weeks"; NHS sprains "after 2 weeks most... feel better" |
| **No improvement after 3 weeks** | Day 21, same comparison → firmer prompt "please get it checked; an ultrasound can show what's going on" | Owner's example plan ("nach 2–3 Wochen nicht besser → Ultraschall"); Schöffl 2003 (ultrasound for all finger injuries) |
| **Getting worse** | Morning pain or pressure pain rises ≥ 2 points over the user's 3-day baseline on 2–3 consecutive days, or any session > the user's chosen limit with a worse next morning → suggest a step back, plus "if it keeps rising, get it checked" | Pain-monitoring model logic (Silbernagel et al. 2007; next-morning response guides load) and NHS "getting worse" |
| **New red-flag symptom logged** | Any "new pop / new swelling / numbness / can't bend fingertip" checkbox in the daily log → show the matching red-flag alert | §§ 1.2–1.3 |
| **Before returning to full load** (board, limit crimping, max test) | Always a short "consider a check-up before going all in" line | Return-to-sport decisions are ideally shared between athlete and clinician (Ardern et al., BJSM 2016 Bern consensus) |

These are **descriptive comparisons of the user's own entries**. They contain no clinical thresholds, and the only output is "consider getting checked" or "consider a step back". That keeps them in the territory of "generic advice on when to consult a professional". The FDA lists symptom checklists that give "advice on when to consult a health care professional" as low-risk (enforcement discretion).

---

## 2. Regulatory boundary: when does this become a medical device?

### 2.1 EU MDR (Regulation (EU) 2017/745): the legal hooks

- **Art. 2(1), medical device definition.** It includes software intended for "diagnosis, prevention, monitoring, prediction, prognosis, treatment or alleviation of disease" **and "diagnosis, monitoring, treatment, alleviation of, or compensation for, an injury or disability"**. Injuries are expressly covered. A rehab feature for injuries therefore has a medical purpose unless the intended purpose and functions say otherwise.
- **Art. 2(12), intended purpose.** "the use for which a device is intended according to the data supplied by the manufacturer on the label, in the instructions for use **or in promotional or sales materials or statements**". This covers store text, the README, website, Nostr marketing posts and in-app copy.
- **Recital 19.** Software "specifically intended by the manufacturer to be used for one or more of the medical purposes … qualifies as a medical device, while software for general purposes, even when used in a healthcare setting, or **software intended for life-style and well-being purposes is not a medical device**."
- **CJEU C-329/16 *Snitem & Philips France* (7 Dec 2017).** Software that applies functions to **patient-specific data** for a medical purpose is a medical device even though it does not act in or on the body.
- **Art. 7, claims.** Misleading text, names or pictures about the device's intended purpose or capabilities are prohibited.
- **Annex VIII Rule 11.** "Software intended to provide information which is used to take decisions with diagnosis or therapeutic purposes is classified as **class IIa**", higher if the impact can be serious; "All other software is classified as class I." MDCG notes that sub-rule 11(a) "is generally applicable to all MDSW", so **almost any medical-purpose software is ≥ IIa**.
- **Consequences if it qualified (class IIa):** QMS (ISO 13485-type), risk management, clinical evaluation, technical documentation, notified-body conformity assessment, person responsible for regulatory compliance (Art. 15), UDI/EUDAMED registration, post-market surveillance and vigilance, and financial coverage for liability. **This is not feasible for CruxCoach.** The design must stay outside.

### 2.2 MDCG 2019-11 rev.1 (June 2025): the operative test

**Decision steps (Figure 1).** (1) Is it software? (2) Is it an accessory or does it drive a device? (3) Does it act on data beyond **storage, archival, communication, simple search**, lossless compression? (4) Is the action **for the benefit of individual patients**? (5) Is it for a medical purpose under Art. 2(1)?

Key passages:
- Not MDSW: "**wellness or fitness apps**", "**Simple search**… retrieval of information… (e.g. **library functions**)". The caveat is that it is no longer "simple search" "if it contributes to achieving a medical purpose".
- Not for individual patients (step 4): software that only provides "**generic** diagnostic or **treatment pathways** (not directed to individual patients), scientific literature, medical atlases, models and templates".
- **New in rev.1, MDSW examples that match this feature closely:**
  - "MDSW that uses the data of a patient with a specific **musculoskeletal pathology** (e.g. X-rays, range of motion, weight, age, etc.) and is intended to **alleviate pain** associated with the musculoskeletal pathology by **recommending personalised rehabilitation exercises** to be performed."
  - "MDSW intended to assess, monitor, and manage depression. The MDSW provides patients with **questionnaires** to track their mood, symptoms… It also offers **exercises and videos, which are individually chosen based on the patient's input**."
  - MDSW "may be intended to be used by healthcare professionals **or laypersons**".
  - "The risk of harm… is **not a criterion** on whether the software qualifies." A gentle or safe plan does not take it out of scope.
- **Annex IV, classification examples:** "Cognitive therapy MDSW that includes a diagnostic function which is intended to **feed back to the software to determine follow-up therapy**… should be in **class III per Rule 22**. When a specialist determines the necessary… therapy based on the outcome provided by the MDSW, the MDSW would be classified as class IIa." In medical framing, a pain-log → automatic stage-change loop is the riskiest pattern.
- **Section 8, changes:** adding functionality "may lead it to be qualified as MDSW". Each new module needs its own check.

**Related EU guidance**
- **MDCG 2021-24 rev.1 (April 2026):** "software analogous to **diaries** for recording insulin doses would not be considered devices, **unless an analysis is performed on the data or the device in some way alters the patient's treatment, prescription, doses** etc." A pain diary is fine. Using the diary to change the "dose" (load) automatically is the trigger.
- **Borderline & Classification Manual v5 (April 2026), "Medical calculators":** even simple published formulas applied to an individual patient's data for diagnosis or treatment decisions are an "action on data beyond… simple search" and **≥ class IIa**. Implication: never run a scoring rule (e.g., Ottawa, a pulley-grade score) that outputs a diagnostic conclusion.
- **Comparable certified products show where the line is:** *Ada* (symptom assessment + care navigation) is CE class IIa under the MDR. *Vivira* is a DiGA, i.e. a CE-marked medical device: users enter their pain area (back/knee/hip) and get daily exercises "to support pain reduction". **That is exactly the claim and mechanism a non-device app must avoid.**
- **MDCG 2025-4 (June 2025):** app platforms should separate "Medical Device" from "Health, Lifestyle" categories. CruxCoach belongs under **Sports / Health & Fitness**, never "Medical".
- **AI Act (Reg. 2024/1689) / MDCG 2025-6:** AI is high-risk where it is a medical device that needs notified-body assessment. Do not use the on-device LLM to interpret injury descriptions or photos. That would add a "medical device AI" (MDAI) dimension.

### 2.3 Feature-by-feature risk assessment for CruxCoach

| Feature / wording | Risk | Why | Safer alternative |
|---|---|---|---|
| "The app **diagnoses** your injury", "you probably have an **A2 grade III rupture**", injury-grade scoring from answers | **High** | Diagnosis of injury (Art. 2(1)); calculator logic (Borderline Manual) → ≥ IIa | User enters **what a professional told them** (e.g., "A2 pulley, grade II, ultrasound on …") or "**not checked yet**". The app stores it as a label and never infers it |
| Triage questionnaire → app **picks the plan and stage** "for your injury" | **High** | Same pattern as the MDCG MSK-rehab and depression examples | Questions serve (a) a **one-way safety screen** and (b) letting the user **choose** a template from a visible library ("templates commonly used by climbing physios for pulley injuries"). The user picks the stage from **stage descriptions** |
| **Automatic** progression/regression from pain entries | **High** | "Alters treatment/doses" (MDCG 2021-24); closed-loop analogy (Rule 22 example) | App **shows** the user's numbers next to the user's chosen rule and **asks**: "Move up / stay / step back?" Nothing changes without a tap |
| "You are **healed**", "**cleared** for the board", "return allowed" | **High** | Therapeutic decision; misleading claim (Art. 7 MDR, § 3 HWG) | "Your numbers meet the return criteria you set. That doesn't show the injury has healed. The decision is yours, ideally with your clinician." |
| "No fracture likely", "**no X-ray needed**" (Ottawa negative) | **High + unsafe** | Diagnostic exclusion | Only escalate: "These signs usually mean an X-ray is needed. Get it checked today." Never the inverse |
| "**Personalised rehab plan**", "**Therapie**", "Reha-Programm", "Physio in your pocket" | **High** | Promotional statements set the intended purpose | "**Comeback plan**" / "**Belastungsaufbau nach Verletzung**" / "Return-to-climbing training". Loads "based on your own test" |
| Static list of **warning signs with urgency levels**, the same for everyone | Low | Generic information (step 4); comparable to NHS pages and first-aid encyclopedias (FDA "not a device") | Keep it static and source-cited |
| **PAR-Q-style pre-start check** ("If any apply, get checked before you start") | Low–medium | Standard in the fitness industry; output is only "get checked" | Never output a diagnosis or a "you're fine" |
| **Pain diary** with plain trend chart | Low | Diary / storage (MDCG 2021-24) | No interpretive labels ("healing well", "abnormal"), no red/green medical semantics, no clinical thresholds except the user's own rule |
| **Loads as % of own test** (e.g., 40–50% of healthy-hand max) | Low | Normal training maths, like %1RM | Frame as training load; the user can override any number |
| **Time-based "check with a professional" prompts** (start, day 14, day 21, worsening, before full load) | Low | Generic safety net; FDA: "advice on when to consult" = low-risk | Wording in § 4 |
| **Export summary for your physio/doctor** (PDF/CSV/share) | Low | Communication | Recommended: it also brings a professional into the loop |
| **"Plan from my physio"**: the user enters a clinician's loads/stages | Low | The app is then a log and timer for someone else's plan | Make this a first-class path |
| Supplements ("15 g gelatine + vitamin C **heals tendons**") | Medium (claims) | Health-claim and HWG logic. Gelatine has no authorised EU health claim | "Optional. Small studies suggest it may support collagen synthesis; evidence is limited." |
| On-device **LLM/vision** reading injury text or photos | **High** | MDAI + AI Act | Don't |

**Honest bottom line.** A module whose stated aim is getting injured climbers back to climbing is a **grey zone**, even with all the mitigations above, because "injury" is in the definition. The mitigations do three things: they keep the **intended purpose** in training and load management, they keep content **generic** (a library), and they keep **decisions with the user**. That is the strongest available position for a non-device app. Residual risk is a judgement call for the owner. Options: (a) legal opinion; (b) **§ 6 Abs. 2 MPDG** status decision by BfArM (the manufacturer may apply; fee; decision "soll" within 3 months); (c) scope the module down to a **"Plan from my physio" + diary + generic library**, which is the lowest-risk form.

**Free / open-source / non-commercial.** MDR "making available on the market" means supply "**in the course of a commercial activity**, whether in return for payment or free of charge" (Art. 2(27), quoted in MDCG 2025-4). A volunteer, no-charge FOSS app *may* fall outside "commercial activity". Unlike the new Product Liability Directive and the CRA, the MDR has no explicit FOSS carve-out, the Blue Guide assesses this case by case (regularity of supply, intentions), and voluntary gifts/zaps complicate the picture. **Do not rely on this. Design as if the MDR applied.**

### 2.4 Recommended intended-purpose statement

Use the same text in the README, LEGAL.md, the store/Zapstore listing and the in-app "About this plan" screen.

> **EN:** "CruxCoach's comeback plans are training aids for climbers. They help you plan and log a gradual return to climbing load after a break or an injury, using general load stages published in climbing-medicine literature. They do not diagnose, assess, treat or monitor injuries, do not decide when an injury has healed, and are not a medical device. If you have warning signs, or are unsure what the injury is, see a doctor or physiotherapist. If a professional has given you a plan, follow theirs."

> **DE:** "Die Comeback-Pläne in CruxCoach sind Trainingshilfen für Kletternde. Sie helfen dir, die Rückkehr zur Kletterbelastung nach einer Pause oder Verletzung schrittweise zu planen und zu protokollieren – mit allgemeinen Belastungsstufen aus der kletter­medizinischen Literatur. Sie stellen keine Diagnose, beurteilen oder behandeln keine Verletzungen, entscheiden nicht, wann etwas verheilt ist, und sind kein Medizinprodukt. Bei Warnzeichen oder wenn du nicht weißt, was du hast: ärztlich oder physiotherapeutisch abklären lassen. Hast du einen Plan von einer Fachperson, gilt der."

The Google Play-required sentence (store description) is additionally: *"not a medical device and does not diagnose, treat, cure, or prevent any medical condition"* plus a reminder to consult a healthcare professional.

### 2.5 Germany

- **BfArM:** "Der Verantwortliche für die Einstufung eines Medizinprodukts ist der Hersteller", based on the intended purpose the manufacturer sets. BfArM uses MDCG 2019-11 as orientation. According to BfArM-based accounts, a "kein Medizinprodukt" note in an app store is **not** taken into account if labelling or advertising conveys a medical purpose. A formal status determination is possible under **§ 6 Abs. 2 MPDG** (application by the manufacturer, fee-based).
- **DiGA (§ 139e SGB V, DiGAV):** prescription and reimbursement status only for **CE-marked medical devices**. The classes were I/IIa, and since the DigiG (in force 26 March 2024) also IIb, which needs stricter evidence. DiGA is **not applicable**. CruxCoach must never use "DiGA", "App auf Rezept", "verordnungsfähig" or similar.
- **Heilmittelwerbegesetz:** § 1 Abs. 1 Nr. 2 extends the HWG to "andere Mittel, Verfahren, Behandlungen …, soweit sich die Werbeaussage auf die Erkennung, Beseitigung oder Linderung von … **Körperschäden** … bezieht". § 3 prohibits misleading advertising, including the impression that "ein Erfolg mit Sicherheit erwartet werden kann". Courts apply this to the overall impression even without an explicit guarantee (OLG Düsseldorf 2025, per commentary). So: no "heilt", "garantiert zurück ans Limit", "in 6 Wochen fit".
- **Liability (BGB/ProdHaftG):** § 309 Nr. 7a BGB voids standard-term exclusions of liability "für Schäden aus der Verletzung des Lebens, des Körpers oder der Gesundheit" caused negligently. Prevailing (untested) opinion treats free FOSS as a **gift** (§ 521 BGB: liability only for intent/gross negligence). Tort duties (§ 823 BGB: *Verkehrspflichten*, warning duties) remain the practical yardstick. The **new Product Liability Directive (EU) 2024/2853** treats software as a product from **9 Dec 2026**. It excludes FOSS "developed or supplied outside the course of a commercial activity" (Art. 2(2), Recital 14). **Practical consequence:** US-style "use at your own risk, no liability" text (e.g., Crimpd's terms) does little in Germany. What counts is that warnings are present, understandable and placed where the risk arises.

### 2.6 USA (FDA), briefly

- **General Wellness: Policy for Low Risk Devices**, revised final guidance **6 Jan 2026**, superseding 2019. Category 1: intended *solely* for maintaining or encouraging a general state of health or a healthy activity, with no reference to diseases or conditions (software of this type is excluded from the device definition). Category 2: lifestyle choices to reduce the risk or impact of certain chronic diseases (enforcement discretion). Outside wellness: referencing specific conditions or diagnostic thresholds, guiding specific clinical action, treatment guidance that informs medical management. A product **may** carry a notice that evaluation by a healthcare professional may help, without naming diseases or thresholds (Covington summary, Jan 2026).
- **Device Software Functions & MMA policy (2022), examples:** "**not devices**" includes apps that "track general daily activities or make **exercise** or posture suggestions", "actively monitor and trend exercise activity", first-aid encyclopedias and libraries of clinical descriptions. Under "**enforcement discretion**" (devices the FDA chooses not to regulate) it lists "software functions that use video and video games to **motivate patients to do their physical therapy exercises** at home" and symptom checklists giving "advice on when to consult a health care professional".
- **Takeaway:** in the US, a climbing-load plan with general safety prompts is low risk. The same wording discipline (no diagnosis, no disease or injury treatment claims) applies.

### 2.7 App platforms

- **Apple App Review 1.4.1:** "Apps should **remind users to check with a doctor** in addition to using the app and before making medical decisions." **5.1.3:** health and fitness data may not be used for advertising or data mining. Apps "may not store personal health information in **iCloud**". This is relevant for iOS parity: no CloudKit/iCloud Drive sync of rehab logs, and consider excluding the rehab DB from iCloud backup.
- **Google Play, Health Content and Services:** non-device health apps "must include a clear disclaimer in their app description" saying the app is "**not a medical device and does not diagnose, treat, cure, or prevent any medical condition**" and must "remind users to consult a healthcare professional". The Health apps declaration form is required. Apps declared as medical devices get a "Medical Device" label.
- **Zapstore/APK distribution:** no equivalent policy, but use the same text for consistency.

---

## 3. Liability-reducing UX patterns (and privacy)

### 3.1 Where and how often to show disclaimers

| Place | What | Frequency |
|---|---|---|
| Store/Zapstore listing, README, LEGAL.md | Intended-purpose statement (§ 2.4) + Google sentence | Always |
| **Module entry** ("Before you start" sheet) | 3–4 short bullets + **acknowledge** button (§ 4.1) | Once per plan start; again only when plan logic changes materially (versioned) |
| **Pre-start safety check** | Static warning-sign checklist with urgency levels (§ 4.2) | At every new plan, and reachable from every plan screen ("Warning signs →") |
| Plan screens | One-line footer: "Training aid, not a diagnosis · Warning signs →" | Persistent, unobtrusive |
| **Contextual prompts** | Day 14 / day 21 / worsening / new symptom / before full load / under 18 (§ 4.3–4.7) | Event-driven; rate-limit (e.g., the same prompt at most once per 7 days unless the trigger worsens) |

Keep each message to **1–3 sentences**, in plain language, and specific to what triggered it. Long legal walls cause fatigue and get skipped. Every alert names **what triggered it** (the user's own numbers) and **one concrete action**.

### 3.2 Defined "check with a professional" points

1. **Start:** if "not checked yet" is selected, recommend a check (stronger after a pop/swelling/bruising). Always recommend one for under-18s.
2. **Any red flag ticked** (start screen or daily log) → the matching alert (E or D).
3. **Day 14, no improvement** → soft prompt. **Day 21** → firmer prompt with the ultrasound hint.
4. **Worsening** (see § 1.5) → suggest a step back plus "if it keeps rising, get it checked".
5. **Re-injury / new pop** → treat as a new injury, starting again at the safety check.
6. **Before full load** (board, limit crimping, full crimp, max test) → "consider a check-up".

### 3.3 "Warn, don't lock" done safely

- **Never block** the plan, the stage choice or the loads (owner rule). For **E-level** flags, show a full-screen sheet whose primary action is **"Call 112"**. Secondary actions are "It has been checked" and "Continue anyway". "Continue anyway" is a text button, never pre-selected.
- On "continue anyway", **do not hide the warning permanently**. Show a small persistent banner on that plan ("You reported a warning sign on 12 Oct · Details").
- **Defaults are conservative:** lowest stage, no max tests, no auto-advance. Everything stays editable.
- **The user can always adjust** stage, loads and rules, and the app records their choice locally ("You set stage 3 yourself on …"). This documents informed self-selection.
- No gamification that pushes through pain: no streaks tied to load increases, no "beat your max" for the injured side, and no social sharing of injured-side loads by default.

### 3.4 Informed self-selection of a stage

- Show **all stages** as a ladder with **plain entry descriptions** in functional terms, e.g. "Everyday use pain-free; light grip ≤ 2/10", "6 × 10 s at 40–50% of your test ≤ 2/10, next morning not worse". These are *typical* criteria used by climbing physios. The **user picks** "where I am".
- If the user's last entries don't match the chosen stage, **say so neutrally** ("Your last entries were above this stage's usual limit") and offer to step back. Do not change the stage.
- Offer **"Use my physio's plan"** (custom loads and stages) as an equal first choice.
- Label sources inline ("Stages adapted from published climbing-medicine protocols, e.g., Schöffl et al.") without claiming validation. Few rehab protocols have been tested in trials, and the BMC 2022 review rated most pulley-rehab outcome evidence "very low to low".

### 3.5 Logging that shows trends

- Log daily: **pressure pain** at the same spot (0–10), **morning value**, **each session's load + pain**, plus optional symptom checkboxes (new pop, swelling, numbness, can't bend fingertip).
- Show **raw values and a simple line chart** (7- and 14-day). Text should be descriptive ("lower than a week ago", "about the same", "higher"), with no medical traffic-light colours and no "healing on track".
- Show the **user's chosen rule** next to the data ("Your rule: ≤ 2/10 during, next morning not worse").
- **Export** for appointments: a one-page summary (dates, entries, chart, stage history, warnings reported). This helps the clinician and brings a professional into the loop.

### 3.6 Privacy (GDPR Art. 9)

- Pain logs, injury labels and red-flag answers are **"data concerning health"** (Art. 4(15) GDPR), a **special category** (Art. 9(1)). Processing by a controller generally needs explicit consent (Art. 9(2)(a)).
- **Best design: purely local processing under the user's control.** The EDPB (connected-vehicles guidelines 01/2020) recommends local processing because it gives the user "sole and full control". Purely personal use may fall under the household exemption (Art. 2(2)(c)). If the developer never receives the data, there is no controller-side processing to justify.
- **Concrete requirements for CruxCoach:**
  - Store rehab data in the local DB only. **Never** publish it to Nostr relays or Blossom, and exclude it from any community, profile or board sync. Even NIP-44-encrypted events leak metadata (timing, kind, author).
  - **Android Auto Backup / device-to-device:** exclude the rehab tables/files via `dataExtractionRules`/`fullBackupContent`, or tell the user clearly that the Google backup includes them. **iOS:** no CloudKit/iCloud sync (Apple 5.1.3). Consider `isExcludedFromBackup` for the rehab store.
  - **CruxCoach JSON backup:** make rehab data a **separate, opt-in category**, labelled "contains health entries". Consider encrypting it.
  - No analytics or telemetry, no crash-report payloads containing rehab content, no cloud LLM calls.
  - **Data minimisation:** don't ask for anything the plan doesn't use (no names of doctors, no photos by default).
  - Give the user a **"Delete all comeback data"** action.
  - Mention the rehab data handling in the privacy policy/README (Google Play's Health apps declaration also requires a privacy policy).

---

## 4. Recommended wording (EN / DE)

Style matches existing CruxCoach strings: "du" form, short, plain, no alarmism. Bracketed parts are variables.

### 4.1 Initial disclaimer ("Before you start" / "Bevor du startest")

**EN**
> **Before you start**
> This plan helps you build load back up step by step. It isn't a diagnosis or treatment, and it can't tell whether your injury has healed.
> • Not checked yet? We recommend a doctor or physio – especially after a pop, swelling or bruising.
> • You choose the stage and can change it anytime. If a professional gave you a plan, follow theirs.
> • Your entries stay on this phone.
> [Got it] [Warning signs]

**DE**
> **Bevor du startest**
> Dieser Plan hilft dir, Belastung Schritt für Schritt wieder aufzubauen. Er ist keine Diagnose und keine Behandlung und kann nicht sagen, ob deine Verletzung verheilt ist.
> • Noch nicht abklären lassen? Wir empfehlen Ärztin, Arzt oder Physiotherapie – besonders nach einem Knall, Schwellung oder Bluterguss.
> • Du wählst die Stufe selbst und kannst sie jederzeit ändern. Hast du einen Plan von einer Fachperson, gilt der.
> • Deine Einträge bleiben auf diesem Handy.
> [Verstanden] [Warnzeichen]

### 4.2 Warning-sign check (start screen) and alerts

**Intro.** EN: "Does any of this apply? Tick what fits." · DE: "Trifft etwas davon zu? Kreuz an, was passt."

**Group E items (EN / DE):**
- The finger or joint looks deformed or points at an odd angle / Finger oder Gelenk sieht verformt aus oder steht schief
- Numb, tingling, cold, pale or bluish / Taub, kribbelt, kalt, blass oder bläulich
- You can't move it at all / Du kannst es gar nicht bewegen
- Open wound over the injury or a joint / Offene Wunde über der Verletzung oder einem Gelenk
- Red, hot and swollen with fever or feeling ill, or a swollen finger held bent that hurts a lot to straighten / Rot, heiß, geschwollen mit Fieber oder Krankheitsgefühl – oder ein geschwollener, gebeugt gehaltener Finger, der beim Strecken stark schmerzt
- After a fall: hit your head and blacked out, are confused, or have new weakness or numbness / Nach einem Sturz: Kopf angeschlagen und kurz weg, verwirrt, oder neue Schwäche oder Taubheit
- Numbness around the genitals or buttocks, or new trouble with bladder or bowel / Taubheit im Genital- oder Gesäßbereich oder neue Probleme mit Blase oder Darm
- Arm pain together with pressure or tightness in the chest / Armschmerz zusammen mit Druck oder Enge in der Brust

**Group D items (EN / DE):**
- A pop with swelling or bruising / Ein Knall mit Schwellung oder Bluterguss
- The tendon lifts away from the bone when you grip / Die Sehne hebt sich beim Zugreifen sichtbar vom Knochen ab
- You can't bend the fingertip on its own / Du kannst das Fingerendglied nicht allein beugen
- The joint wobbles sideways, feels loose or won't straighten / Das Gelenk wackelt seitlich, fühlt sich locker an oder lässt sich nicht strecken
- Fell on your hand and it hurts at the base of the thumb / Auf die Hand gefallen und es schmerzt am Daumenansatz
- Elbow: pop, bruise in the crease, weak turning your palm up / Ellenbogen: Knall, Bluterguss in der Beuge, schwach beim Aufdrehen der Hand
- Shoulder: since the injury you can't lift your arm to the side / Schulter: Seit der Verletzung kannst du den Arm nicht seitlich anheben
- Knee or ankle: can't take four steps; the knee locks, gives way or swelled up within hours / Knie oder Sprunggelenk: keine vier Schritte möglich; das Knie blockiert, knickt weg oder ist innerhalb von Stunden dick geworden
- Heel or calf: pop and you can't stand on tiptoe / Ferse oder Wade: Knall und du kommst nicht auf die Zehenspitzen
- Heel hook: pop with bruising at the back of the thigh / Heel Hook: Knall mit Bluterguss hinten am Oberschenkel
- Pain at night or at rest that keeps getting worse, fever, weight loss or feeling ill / Nacht- oder Ruheschmerz, der weiter zunimmt, Fieber, Gewichtsverlust oder Krankheitsgefühl
- You take blood thinners and it is clearly swollen or bruised / Du nimmst Blutverdünner und es ist deutlich geschwollen oder blau
- You're under 18 and have finger pain / Du bist unter 18 und hast Fingerschmerzen

**None ticked.** EN: "Nothing ticked – good. That doesn't rule anything out; if something changes, check this list again." · DE: "Nichts angekreuzt – gut. Ausschließen lässt sich damit nichts; wenn sich etwas ändert, schau die Liste wieder an."

**Alert, level E**
> **EN – Please get this checked now**
> What you ticked can mean an injury that needs quick treatment, like a fracture, a dislocation or a nerve injury. Call 112 or go to an emergency department, and let someone else drive. Don't train this area until it's been checked.
> [Call 112] · [It has been checked] · *Continue anyway*

> **DE – Bitte jetzt abklären lassen**
> Was du angekreuzt hast, kann auf eine Verletzung hindeuten, die schnell behandelt werden sollte – etwa einen Bruch, eine Ausrenkung oder einen Nervenschaden. Ruf 112 an oder fahr in eine Notaufnahme, am besten nicht selbst. Trainiere diese Stelle erst, wenn sie angeschaut wurde.
> [112 anrufen] · [Wurde abgeklärt] · *Trotzdem fortfahren*

**Alert, level D (generic)**
> **EN – Best get this checked in the next few days**
> After a pop with swelling or bruising, a doctor can see with an ultrasound how much is injured. Some injuries, like a torn tendon or several pulleys, heal better when treated early. Until then, keep it easy and pain-free.
> [OK] · [It has been checked]

> **DE – Am besten in den nächsten Tagen abklären lassen**
> Nach einem Knall mit Schwellung oder Bluterguss kann eine Ärztin oder ein Arzt per Ultraschall sehen, wie viel verletzt ist. Manche Verletzungen – etwa eine abgerissene Sehne oder mehrere Ringbänder – heilen besser, wenn sie früh behandelt werden. Bis dahin: locker und schmerzfrei bleiben.
> [OK] · [Wurde abgeklärt]

Specific D variants (short):
- Fingertip can't bend. EN: "This can be a torn tendon at the fingertip. It's best repaired within about a week, so please get it checked today or tomorrow." · DE: "Das kann eine abgerissene Sehne am Fingerendglied sein. Sie wird am besten innerhalb etwa einer Woche versorgt – bitte heute oder morgen abklären lassen."
- Bowstringing. EN: "A tendon that lifts away from the bone usually means more than one pulley is torn. A hand specialist should look at it soon." · DE: "Wenn sich die Sehne vom Knochen abhebt, sind meist mehrere Ringbänder gerissen. Das sollte sich bald jemand aus der Handchirurgie ansehen."
- Under 18. EN: "**Under 18? Get finger pain checked.** While you're growing, pain on the back of the middle finger joint can come from the growth plate. An X-ray or MRI shows it, and it needs a different plan. Please see a doctor before you start, and leave out max hangs and campus boarding." · DE: "**Unter 18? Fingerschmerzen abklären lassen.** Solange du wächst, können Schmerzen auf der Rückseite des Fingermittelgelenks von der Wachstumsfuge kommen. Das zeigt ein Röntgen- oder MRT-Bild, und dann braucht es einen anderen Plan. Geh bitte vor dem Start zu einer Ärztin oder einem Arzt und lass Maximaltests und Campusboard weg."

### 4.3 "Not better in 2–3 weeks"

**Day 14**
> **EN – Two weeks in, your pain hasn't gone down**
> Your entries for [ring finger] are about where they were at the start. Most strains feel better after about two weeks, so this is a good time to have it looked at – a doctor or physio can check whether something else is going on. Meanwhile you can stay at your stage or step back one.
> [OK] · [Step back one stage] · [Remind me in a week]

> **DE – Zwei Wochen – der Schmerz ist nicht weniger geworden**
> Deine Einträge für [Ringfinger] liegen etwa da, wo sie am Anfang waren. Die meisten Zerrungen fühlen sich nach rund zwei Wochen besser an – ein guter Zeitpunkt, es anschauen zu lassen. Ärztin, Arzt oder Physiotherapie können prüfen, ob etwas anderes dahintersteckt. Bis dahin kannst du auf deiner Stufe bleiben oder eine zurückgehen.
> [OK] · [Eine Stufe zurück] · [In einer Woche erinnern]

**Day 21**
> **EN – Three weeks without improvement: please get it checked**
> Your pain has stayed about the same for three weeks. An ultrasound can show what's going on, and the plan may need to change.
> [OK] · [Export summary for my appointment]

> **DE – Drei Wochen ohne Besserung: bitte abklären lassen**
> Dein Schmerz ist seit drei Wochen etwa gleich. Ein Ultraschall kann zeigen, was los ist – vielleicht muss der Plan angepasst werden.
> [OK] · [Zusammenfassung für den Termin exportieren]

### 4.4 Worsening

> **EN – Your pain has gone up**
> Over the last [3] days your morning value went from [2] to [4]. That usually means the load is too much right now. Suggestion: go back one stage for 3–4 days. If it keeps rising, or you notice swelling, numbness or a new pop, get it checked.
> [Step back one stage] · [Keep my stage]

> **DE – Dein Schmerz ist gestiegen**
> In den letzten [3] Tagen ist dein Morgenwert von [2] auf [4] gestiegen. Meist heißt das: Die Last ist gerade zu viel. Vorschlag: eine Stufe zurück für 3–4 Tage. Steigt er weiter oder kommen Schwellung, Taubheit oder ein neuer Knall dazu, lass es abklären.
> [Eine Stufe zurück] · [Stufe behalten]

### 4.5 Stage-progression prompt

**Rule met**
> **EN – Ready for the next stage?**
> This week all [6] sessions stayed at or below 2/10, and the mornings after were never worse – that's the rule you chose for moving up. Next: [Stage 3 – medium edges with H-tape]. Move up, or stay another week? Both are fine.
> [Move up] · [Stay this week]

> **DE – Bereit für die nächste Stufe?**
> Diese Woche lagen alle [6] Einheiten bei höchstens 2/10, und am Morgen danach war es nie schlechter – das ist die Regel, die du fürs Steigern gewählt hast. Nächste Stufe: [Stufe 3 – mittlere Leisten mit H-Tape]. Steigern oder noch eine Woche bleiben? Beides ist in Ordnung.
> [Steigern] · [Diese Woche bleiben]

**Rule not met**
> **EN – Stay at this stage for now**
> On [Tuesday] you logged [4/10], above your limit of 2/10. The usual approach is to repeat the week. You can still change the stage yourself.
> [Repeat week] · [Change stage]

> **DE – Erst mal auf dieser Stufe bleiben**
> Am [Dienstag] hast du [4/10] eingetragen – über deiner Grenze von 2/10. Üblich ist, die Woche zu wiederholen. Du kannst die Stufe trotzdem selbst ändern.
> [Woche wiederholen] · [Stufe ändern]

### 4.6 Return-to-full-load prompt

> **EN – Your numbers meet the return criteria you set**
> Half crimp 6 × 10 s at [92 %] of your other hand, pain at most 1/10, and pressure pain falling for over a week. Climbing physios often use these criteria before going back to board and limit climbing. They don't show that the injury has healed – if you're unsure, a check-up first is worth it. Start full crimp at about 40 %.
> [Got it]

> **DE – Deine Werte erfüllen deine Rückkehr-Kriterien**
> Halbaufgestellt 6 × 10 s mit [92 %] deiner anderen Hand, Schmerz höchstens 1/10, Druckschmerz seit über einer Woche fallend. Das nutzen Kletter-Physios häufig als Schwelle für Board und Limit. Dass die Verletzung verheilt ist, zeigt das nicht – wenn du unsicher bist, lohnt sich vorher ein Check. Voll aufgestellt mit etwa 40 % beginnen.
> [Verstanden]

### 4.7 Persistent footer and glossary

- Footer. EN: "Training aid, not a diagnosis · Warning signs →" · DE: "Trainingshilfe, keine Diagnose · Warnzeichen →"

| Avoid (DE/EN) | Use instead (DE/EN) |
|---|---|
| Diagnose, Befund, "du hast …" / diagnosis, "you have …" | "was du eingetragen hast", "was dir gesagt wurde" / "what you entered", "what you were told" |
| Therapie, Behandlung, Reha-Programm / therapy, treatment, rehab program | Training, Comeback-Plan, Belastungsaufbau / training, comeback plan, load build-up |
| heilen, geheilt, verheilt (as an app output) / heal, healed | "belastbarer werden", "Kriterien erreicht" / "build tolerance", "criteria met" |
| erlaubt, freigegeben / allowed, cleared | "typischer nächster Schritt" / "usual next step" |
| sicher, garantiert, in X Wochen fit / safe, guaranteed | "üblich", "häufig genutzt" / "usual", "commonly used" |
| Patient / patient | du / you |
| personalisierte Reha / personalised rehab | "auf Basis deines Tests" / "based on your own test" |
| "Schmerz ist normal" / "pain is normal" | "leichter Schmerz bis [2]/10 gehört zu deiner gewählten Regel" / "mild pain up to [2]/10 is within the rule you chose" |

---

## 5. Requirements checklist for the CruxCoach comeback plan

### MUST
- [ ] **Intended purpose** (§ 2.4) in the README, LEGAL.md, store/Zapstore text and the in-app "About". Add the Google Play sentence to the store description.
- [ ] **No diagnosis or grading**: the injury label is only user-entered ("what a professional told you" / "not checked yet").
- [ ] **User chooses the stage** from visible generic stage descriptions. The app never assigns a stage from symptoms.
- [ ] **No automatic progression or regression.** The app shows data against the user's chosen rule and asks. Nothing changes without a user action.
- [ ] **No "healed/cleared" output.** Return criteria are shown as "criteria you set are met", plus "doesn't show healing".
- [ ] **Static warning-sign checklist** with three urgency levels (E/D/R), shown at every plan start and reachable from every plan screen.
- [ ] **Escalation is one-way only.** Never output "no fracture", "no X-ray needed", "nothing serious".
- [ ] **E-level alert** with "Call 112" as the primary action (localise the emergency number; 116117 hint in DE for out-of-hours).
- [ ] **Time-based prompts**: day 14 (soft), day 21 (firm), worsening (§ 1.5), new symptom checkbox, before full load.
- [ ] **Under-18 path**: always recommend assessment for finger pain; defaults without max tests or campus/limit board; growth-plate note.
- [ ] **Warn, don't lock**: never block; "continue anyway" only as a secondary text button; the warning persists as a banner; conservative defaults.
- [ ] **Health data stays local**: not in Nostr/Blossom/community sync, excluded from or disclosed for Android Auto Backup and iCloud, separate opt-in category in the JSON backup, "delete all comeback data", no telemetry, no cloud LLM.
- [ ] **No medical claims in marketing** (Nostr posts, release notes, video series): no "Reha", "heilt", "Therapie", "Diagnose", "DiGA", "in X Wochen fit".
- [ ] **Sources visible** in the app for stages, rules and red flags (title, year).

### SHOULD
- [ ] **"Use my physio's plan"** as an equal first option (custom stages/loads; the app becomes a diary and timer).
- [ ] **Export summary** (PDF/CSV/share) for appointments.
- [ ] Descriptive trend chart (7/14 days) without medical colour semantics. Show the user's rule next to the data.
- [ ] Specific D-level texts for fingertip flexion loss, bowstringing, snuffbox, elbow pop, heel-hook pop, Achilles.
- [ ] Rate-limit prompts (same prompt at most once per 7 days unless the trigger worsens). Version the disclaimer and re-show it only on material logic changes.
- [ ] Record the user's acknowledgements and overrides locally (date, choice). This documents informed self-selection.
- [ ] Present supplements neutrally ("optional; evidence limited").
- [ ] Keep the module separate (own screens/module) so its intended purpose can be described and reviewed on its own (MDCG 2019-11 § 7 modules).
- [ ] Re-check the boundary whenever a function is added (MDCG 2019-11 § 8). If in doubt, a § 6 Abs. 2 MPDG status request or a legal opinion.

### AVOID
- [ ] Symptom → diagnosis/grade → plan algorithms, scoring rules (Ottawa, pulley-grade scores) with diagnostic output.
- [ ] Auto-adjusting loads or stages from pain data (closed loop).
- [ ] "Personalised rehab/therapy", "the app knows when you're ready", "medically validated", "clinically proven".
- [ ] Reassuring outputs ("you're fine", "nothing serious", "no need to see a doctor").
- [ ] Hard locks or forced waiting periods; making "continue anyway" the default.
- [ ] Maximal testing of the injured side; max tests and campus for under-18s by default.
- [ ] Streaks or gamification tied to load increases on the injured side; social sharing of injury data.
- [ ] Sending rehab data or injury free-text/photos to any server, relay or cloud model; on-device LLM interpretation of injuries.
- [ ] Listing the app in a "Medical" store category; using the words DiGA, "App auf Rezept", "Medizinprodukt" (except "kein Medizinprodukt").
- [ ] Relying on long legal disclaimers or "no liability" clauses as protection.

---

## 6. Sources

Evidence/authority level in brackets: **[Law]** binding legal text · **[Guidance]** official non-binding regulator guidance · **[Policy]** platform policy · **[CG]** clinical guideline / public-health service guidance · **[CDR]** validated clinical decision rule · **[SR]** systematic review/meta-analysis · **[CS]** case series/cohort (level IV) · **[EO]** expert opinion/review · **[2°]** secondary commentary (used where primary access was blocked).

**EU / regulatory**
1. Regulation (EU) 2017/745 (MDR), Art. 2(1), 2(12), 2(27), Art. 7, Recital 19, Annex VIII Rule 11 (2017). https://eur-lex.europa.eu/eli/reg/2017/745/oj [Law]. EUR-Lex blocked automated access; article text as reproduced in MDCG 2019-11 rev.1 / MDCG 2025-4.
2. MDCG 2019-11 rev.1, *Qualification and classification of software – MDR and IVDR* (Oct 2019, rev.1 June 2025). https://health.ec.europa.eu/document/download/b45335c5-1679-4c71-a91c-fc7a4d37f12b_en?filename=mdcg_2019_11_en.pdf [Guidance]
3. MDCG 2021-24 rev.1, *Guidance on classification of medical devices* (April 2026). https://health.ec.europa.eu/document/download/cbb19821-a517-4e13-bf87-fdc6ddd1782e_en?filename=mdcg_2021-24_en.pdf [Guidance]
4. *Manual on borderline and classification under Regulations (EU) 2017/745 and 2017/746*, v5 (April 2026), § 1.1.9.2 Medical calculators. https://health.ec.europa.eu/document/download/71a87df8-5ca1-4555-b453-b65bdf8de909_en?filename=md_borderline_manual_en.pdf [Guidance]
5. MDCG 2025-4, *Safe making available of MDSW apps on online platforms* (June 2025). https://health.ec.europa.eu/document/download/ec9b0f40-7f82-43a7-b833-ebd45b772eae_en?filename=mdcg_2025-4_en.pdf [Guidance]
6. MDCG 2025-6, *FAQ on interplay between MDR/IVDR and the AI Act* (June 2025). https://health.ec.europa.eu/document/download/b78a17d7-e3cd-4943-851d-e02a2f22bbb4_en?filename=mdcg_2025-6_en.pdf [Guidance]
7. Commission list of MDCG endorsed documents. https://health.ec.europa.eu/medical-devices-sector/new-regulations/guidance-mdcg-endorsed-documents-and-other-guidance_en [Guidance]
8. CJEU, C-329/16 *Snitem and Philips France*, judgment of 7 Dec 2017. https://curia.europa.eu/juris/liste.jsf?num=C-329/16 [Law]. Summary: Bristows, https://www.bristows.com/news/digital-health-the-cjeu-s-first-decision-on-standalone-software-as-a-medical-device [2°]
9. Regulation (EU) 2016/679 (GDPR), Art. 2(2)(c), 4(15), 8, 9. https://eur-lex.europa.eu/eli/reg/2016/679/oj; Art. 9 text: https://gdpr-info.eu/art-9-gdpr/ [Law]
10. EDPB Guidelines 01/2020 on processing personal data in the context of connected vehicles and mobility related applications (v2.0, adopted 9 Mar 2021): local processing, household exemption. https://www.edpb.europa.eu/our-work-tools/documents/public-consultations/2020/guidelines-12020-processing-personal-data_en (DE v2.0: https://www.edpb.europa.eu/system/files/documents/2021-08/edpb_guidelines_202001_connected_vehicles_v2.0_adopted_de.pdf) [Guidance]; summary McCann FitzGerald [2°]
11. Directive (EU) 2024/2853 (Product Liability), Art. 2(2), Recital 14; transposition and application 9 Dec 2026. https://eur-lex.europa.eu/eli/dir/2024/2853/oj [Law]; Buse summary https://buse.de/en/blog-en/commercial-en/what-will-change-with-the-new-eu-product-liability-directive-eu-2024-2853/ [2°]
12. Regulation (EU) 2024/1689 (AI Act). https://eur-lex.europa.eu/eli/reg/2024/1689/oj [Law]
13. Commission Notice, *Blue Guide on the implementation of EU product rules 2022*, § 2.2 (commercial activity). https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:52022XC0629(04) [Guidance]. Paraphrased; not re-verified in this session.

**Germany**
14. BfArM FAQ *Rechtlicher Status, Klassifizierung, Einstufung* (accessed 2026-10-20). https://www.bfarm.de/DE/Medizinprodukte/_FAQ/Klassifizierung-Abgrenzung/faq-liste.html [Guidance]
15. § 6 MPDG (Medizinprodukterecht-Durchführungsgesetz). https://www.gesetze-im-internet.de/mpdg/__6.html [Law]
16. BfArM DiGA pages. https://www.bfarm.de/DE/Medizinprodukte/Aufgaben/DiGA-und-DiPA/DiGA/_node.html; § 139e SGB V https://www.gesetze-im-internet.de/sgb_5/__139e.html [Law]; DigiG (26 Mar 2024) extension to class IIb: Reusch Law, https://www.reuschlaw.de/en/news/digital-act-new-regulations-on-digital-health-applications/ [2°]
17. Heilmittelwerbegesetz §§ 1, 3. https://www.gesetze-im-internet.de/heilmwerbg/__1.html, https://www.gesetze-im-internet.de/heilmwerbg/__3.html [Law]; case commentary omsels.info [2°]
18. § 309 Nr. 7a BGB. https://www.gesetze-im-internet.de/bgb/__309.html; § 521 BGB https://www.gesetze-im-internet.de/bgb/__521.html [Law]; FOSS-as-gift opinion: Digitalzentrum Chemnitz *Praxistipp Open Source Software*; OSI license-review list post (Jan 2025, notes it is untested) [2°]
19. Vivira DiGA press material (2020): indication back/knee/hip pain, daily exercises. https://www.vivira.com/fileadmin/user_upload/pd-vivira_basis-fachpresse_20201102.pdf [2°]
20. Ada Health, *Is Ada a medical device?* and MDR class IIa certification press release (Dec 2022). https://www.ada.com/help/is-ada-a-medical-device/ [2°]

**USA / platforms**
21. FDA, *General Wellness: Policy for Low Risk Devices*, revised final guidance (6 Jan 2026). https://www.fda.gov/media/90652/download [Guidance]; Covington alert (8 Jan 2026) https://www.cov.com/en/news-and-insights/insights/2026/01/fda-issues-revised-guidance-on-general-wellness-products [2°]; FDA town hall 11 Feb 2026 https://www.fda.gov/medical-devices/medical-devices-news-and-events/town-hall-general-wellness-policy-low-risk-devices-final-guidance-02112026
22. FDA, *Examples of software functions that are NOT medical devices* (content current 29 Sep 2022). https://www.fda.gov/medical-devices/device-software-functions-including-mobile-medical-applications/examples-software-functions-are-not-medical-devices [Guidance]
23. FDA, *Examples of software functions for which the FDA will exercise enforcement discretion* (29 Sep 2022). https://www.fda.gov/medical-devices/device-software-functions-including-mobile-medical-applications/examples-software-functions-which-fda-will-exercise-enforcement-discretion [Guidance]
24. Apple, *App Review Guidelines* 1.4.1, 5.1.3 (accessed 2026-10-20). https://developer.apple.com/app-store/review/guidelines/ [Policy]
25. Google Play, *Health Content and Services* / Health apps declaration (accessed 2026-10-20). https://support.google.com/googleplay/android-developer/answer/13996823, https://support.google.com/googleplay/android-developer/answer/16679511 [Policy]
26. Crimpd, *Terms & Conditions* §3 Health and Fitness (accessed 2026-10-20). https://www.crimpd.com/terms-conditions/ [comparable app]
27. The Climbing Doctor, *Rock Rehab Protocol: Pulley Sprain* (product page disclaimer). https://theclimbingdoctor.com/product/rock-rehab-protocol-pulley-sprain-3/ [comparable product]

**Clinical red flags**
28. NHS, *Sprains and strains* (reviewed 23 Apr 2024). https://www.nhs.uk/conditions/sprains-and-strains/ [CG]
29. NHS, *Finger pain* (reviewed 5 Nov 2025). https://www.nhs.uk/conditions/hand-pain/finger-pain/ [CG]
30. NHS, *Shoulder pain* (reviewed 22 May 2023). https://www.nhs.uk/conditions/shoulder-pain/ [CG]
31. NHS, *Elbow and arm pain* (reviewed 26 Jan 2024). https://www.nhs.uk/conditions/elbow-and-arm-pain/ [CG]
32. NHS, *Knee pain* (reviewed 21 Dec 2023). https://www.nhs.uk/conditions/knee-pain/ [CG]
33. NHS, *Deep vein thrombosis* (reviewed 30 Apr 2026). https://www.nhs.uk/conditions/deep-vein-thrombosis-dvt/ [CG]
34. NICE CKS, *Sprains and strains* (rev. 2025 per secondary source). https://cks.nice.org.uk/topics/sprains-strains/ [CG; content via 2° summaries: GP Online CKS summary, Guidelinegenius]
35. NICE CKS, *Shoulder pain*. https://cks.nice.org.uk/topics/shoulder-pain/ [CG; via 2° summaries, e.g. North East London ICB shoulder pathway May 2025]
36. NICE CKS, *Sciatica (lumbar radiculopathy)*, cauda equina red flags (2018 revision). https://cks.nice.org.uk/topics/sciatica-lumbar-radiculopathy/ [CG; via 2°: MPS, GP Online]
37. NICE NG232, *Head injury: assessment and early management* (18 May 2023). https://www.nice.org.uk/guidance/ng232/chapter/recommendations [CG]
38. Stiell IG et al. *Decision rules for the use of radiography in acute ankle injuries: refinement and prospective validation.* JAMA 1993;269(9):1127–32. doi:10.1001/jama.269.9.1127 [CDR]
39. Stiell IG et al. *Prospective validation of a decision rule for the use of radiography in acute knee injuries.* JAMA 1996;275(8):611–5 [CDR]
40. Bachmann LM et al. *Accuracy of Ottawa ankle rules to exclude fractures of the ankle and mid-foot: systematic review.* BMJ 2003;326:417. https://pubmed.ncbi.nlm.nih.gov/12595378/ [SR]
41. Bachmann LM et al. *The accuracy of the Ottawa knee rule to rule out knee fractures: a systematic review.* Ann Intern Med 2004;140(2):121–4. https://pubmed.ncbi.nlm.nih.gov/14734335/ [SR]
42. Beckenkamp PR et al. *Diagnostic accuracy of the Ottawa Ankle and Midfoot Rules: a systematic review with meta-analysis.* Br J Sports Med 2017;51(6):504–10. https://bjsm.bmj.com/content/51/6/504 [SR]
43. Finucane LM et al. *International Framework for Red Flags for Potential Serious Spinal Pathologies.* JOSPT 2020;50(7):350–72. doi:10.2519/jospt.2020.9971 [EO/consensus]
44. Leitlinienprogramm NVL, *Nationale VersorgungsLeitlinie Nicht-spezifischer Kreuzschmerz*, 2nd ed. (2017). https://www.leitlinien.de/themen/kreuzschmerz [CG]
45. Schöffl V, Hochholzer T, Winkelmann HP, Strecker W. *Pulley injuries in rock climbers.* Wilderness Environ Med 2003;14(2):94–100. https://journals.sagepub.com/doi/full/10.1580/1080-6032(2003)014[0094:PIIRC]2.0.CO;2 [CS]
46. *The climber's finger: imaging of finger flexor tendon pulley injuries.* Eur J Radiol 2026 (grade IVa/IVb, early treatment within 10 days). https://www.ejradiology.com/article/S0720-048X(26)00105-1/fulltext [EO/review; abstract-level access]
47. Larsson R, Nordeman L, Blomdahl C. *To tape or not to tape: annular ligament (pulley) injuries in rock climbers – a systematic review.* BMC Sports Sci Med Rehabil 2022;14:148. doi:10.1186/s13102-022-00539-6 [SR, low certainty]
48. Jones G, Johnson R, Schöffl V, Schöffl I, Lutter C, Johnson MI, Halsey T. *When to be concerned about finger pain in an adolescent climber.* BJSM blog, 8 Sep 2023. https://blogs.bmj.com/bjsm/2023/09/08/when-to-be-concerned-about-finger-pain-in-an-adolescent-climber/ [EO]
49. Jones G et al. *Primary periphyseal stress injuries of the fingers in adolescent climbers: a critical review.* Curr Sports Med Rep 2022;21(12):436–42 [EO/review]
50. Hochholzer T, Schöffl VR. *Epiphyseal fractures of the finger middle joints in young sport climbers.* Wilderness Environ Med 2005;16(3):139–42. https://doi.org/10.1580/pr15-04.1 [CS]
51. Caine D, Meyers R, Nguyen J, Schöffl V, Maffulli N. *Primary periphyseal stress injuries in young athletes: a systematic review.* Sports Med 2022;52(4):741–72 [SR]
52. British Mountaineering Council, *Growth plate stress fractures in teenage climbers* (fact sheet). https://services.thebmc.co.uk/media/files/Comps/Development%20Squad/Growth%20Plate%20Stress%20Fractures%20in%20Teenage%20Climbers.pdf [EO]
53. Leddy JP, Packer JW. *Avulsion of the profundus tendon insertion in athletes.* J Hand Surg Am 1977;2:66–9; Radiopaedia, *Leddy and Packer classification*. https://radiopaedia.org/articles/leddy-and-packer-classification-of-jersey-finger-injuries [EO]
54. *In brief: Kanavel's signs and pyogenic flexor tenosynovitis.* Clin Orthop Relat Res 2015. https://www.ncbi.nlm.nih.gov/pmc/articles/PMC4686527/ [EO]
55. BestBETs, distal biceps rupture timing / hook test. https://bestbets.org/bets/bet.php?id=3163 [EO/BET]
56. Bone & Joint Open 2021, national audit of suspected scaphoid fracture management (NICE NG38 MRI recommendation). https://boneandjoint.org.uk/Article/10.1302/2633-1462.211.BJO-2021-0146 [CS/audit]
57. *Severe injuries of proximal hamstrings in high-performance sport climbers.* Dtsch Z Sportmed 2022 (case report). https://www.germanjournalsportsmedicine.com/archive/archive-2022/issue-2/severe-injuries-of-proximal-hamstrings-in-high-performance-sport-climbers/ [CS]; ISAKOS 2025 abstract on repair timing < 32 days https://isakos.com/2025/Abstract/18934 [CS, abstract]
58. GPnotebook, *Complete rupture of the Achilles tendon – diagnostic process*. https://gpnotebook.com/pages/musculoskeletal-medicine/complete-rupture-of-the-achilles-tendon/diagnostic-process [EO]
59. Ardern CL et al. *2016 Consensus statement on return to sport from the First World Congress in Sports Physical Therapy, Bern.* Br J Sports Med 2016;50:853–64 [EO/consensus]
60. Silbernagel KG et al. *Continued sports activity, using a pain-monitoring model, during rehabilitation in patients with Achilles tendinopathy: a randomized controlled study.* Am J Sports Med 2007;35(6):897–906 [RCT; basis for "next-morning" pain rule]
