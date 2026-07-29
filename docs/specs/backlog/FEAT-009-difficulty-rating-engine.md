---
status: backlog
---
# Feature Spec: Difficulty Rating Engine (backlog)

> **Status:** Backlog — moved out of v0.2.0 on 2026-05-20 in favour
> of FEAT-027 (MoonBoard). Material below remains valid; re-enters
> the release-train when a slot opens.
>
> **Prior status:** Skeleton — algorithm and Nostr event shapes locked from
> 2026-05-05 deep-research synthesis. Retargeted 0.2.0 → 0.1.4 on
> 2026-05-05, then back to 0.2.0 on 2026-05-06 — Stage 1 MVP scope was
> too large to bundle with FEAT-003 + FEAT-005 + FEAT-006 + FEAT-010
> within the 0.1.4 release window. Implementation phasing (Stage 1
> MVP → Stage 3 hold-feature-ML) and the Kilter / CruxCoach blend
> behaviour are design-locked. Remaining open questions: empirical
> tuning of `KILTER_TRUST_FACTOR` once we have ≥100-active-user
> telemetry, and Nostr-side relay cost validation under sustained
> Kind-30079 traffic.
>
> **Depends on:**
> - FEAT-003 (Climb Creator) — the Kind-30078 climb event already
>   exists; this spec extends its tag schema with `valid_angles`,
>   `broken_at`, and refines the `setter_grade`-per-angle convention.
> - Existing `BoardClimbParser`, `KilterGradeMapper`, and the
>   `climb_browse` VIEW.
>
> **Blocks:** none. Stage-1 MVP ships independently of FEAT-008.
> FEAT-008 imports become more interesting once FEAT-009 is live (Kilter
> aggregate becomes Bayesian prior; community votes accumulate
> client-side), but the import flow itself works without it.

---

## 1. Overview

The official Kilter Board app has a well-known set of grade-quality
problems: the `difficulty_average` aggregate is dominated by a
"Quick-Log" auto-confirm spike at the setter grade; setters tend to
soft-grade their own climbs; strong climbers and weak climbers vote with
equal weight; community-set climbs (especially low-traffic ones) take
months to converge or never do. Multi-angle propagation is non-existent
in the UI even though the schema supports it.

CruxCoach is in a position to do better:
- Decentralised data lets us define our own event schema (Nostr Kind
  30079 vote events, Kind 30080 climber-profile events).
- Smaller, more engaged user base → strength-weighted votes carry signal
  earlier than they would on Kilter's mass-market user pool.
- Bayesian conjugate updates run cheaply on-device — no server.
- Per-(climb, angle) Aurora schema already exists.

This spec defines the Difficulty Rating Engine: a per-(climb, angle)
hierarchical Bayesian posterior over a continuous Kilter-scale grade
(1–40), seeded from the setter anchor and a global angle-delta prior,
updated by community votes weighted by per-voter strength, send-vs-
attempts evidence, recency, and (where available) Kilter aggregate as a
trust-discounted prior.

### Goals

- Single trustworthy grade displayed per (climb, angle) for the user,
  with a confidence indicator.
- Multi-angle propagation: setter sets one anchor at one angle, all
  other angles get a credible projected estimate that converges to the
  community consensus over time.
- Robust to setter sandbagging, soft-grading, Quick-Log spikes, and
  small-sample Sybil manipulation.
- Cheap on-device aggregation: no MCMC, no server, no GPU.
- Graceful degradation: a fresh climb with zero votes still shows a
  sensible projected grade with a clear "low-confidence" badge.
- For climbs that exist on both Kilter and CruxCoach (round-tripped or
  imported), the Kilter aggregate informs the CruxCoach posterior
  without baking in its known pathologies.

### Non-Goals

- Replacing or competing with Kilter's official grades. CruxCoach grades
  are independently computed and labelled "CruxCoach" in disagreement
  surfaces.
- Server-side aggregation, ML pipelines, or a centralised
  authoritative scoreboard.
- Cross-board generalisation (Tension, Moonboard) — Kilter only for
  v0.2.0.
- Whole-History Rating (Coulom 2008 / Scarff 2020). Considered, deferred
  to a 0.3.0+ optional "trusted aggregator" variant per §11.
- Hold-feature CNN-based grade prediction. Stage 3 ships a simple
  feature regression at most.

---

## 2. Background

### 2.1 What today's ecosystem actually does

| Platform | Aggregation | Per-angle | Strength-weighting | Sybil resistance | Setter bias correction |
|---|---|---|---|---|---|
| Kilter app | Arithmetic mean of all votes incl. Quick-Log spike | Stored per (climb, angle); UI hides the breakdown | None | None | None |
| Tension Board (Aurora) | Same as Kilter | Same | None | None | None |
| Decoy (Aurora) | Same as Kilter, but explicit `setter_angle` displayed | Same; setter-angle highlighted | None | None | None |
| Moonboard | Setter grade + curated "Benchmark" by moderators | Single fixed angle per setup | None (mods curate) | Centralised | Manual moderation |
| 8a.nu / Mountain Project | Opaque consensus algorithm; histograms exposed | N/A (outdoor) | Not disclosed | Standard auth gate | Not disclosed |
| theCrag.com | **Whole-History Rating (Scarff grAId)** | N/A | Yes — climber rating × climb difficulty | Standard auth gate | Implicit via WHR |
| Climbdex | Re-exposes Aurora numbers with two decimals + variance filter | Per (climb, angle) | None | N/A (passthrough) | None — exposes the problem |
| `bjude/kilterbench` | Skewed-normal fit per (climb, angle) with Quick-Log truncation | Per (climb, angle) | None | N/A | **Setter-bin truncation** |

The most relevant prior art:
- **Aurora schema and reverse engineering**: `lemeryfertitta/BoardLib`,
  `Climbdex`, `kilterboard.app`. Establishes the canonical
  `(climb_uuid, angle)` data model and the `display_difficulty` /
  `difficulty_average` / `benchmark_difficulty` field semantics.
- **Bjude's `kilterbench`**: demonstrates that Quick-Log truncation
  (capping the setter-grade bin at 50 % of total ascents) recovers the
  true grade distribution.
- **Drummond & Popinga 2021 (arXiv:2111.08140)**: estimates the
  Bradley-Terry-style scale parameter at ~3.17× failures per V-grade
  increment on Kilter data; provides a quantitative anchor for
  "1 vote of average precision = ±1 V-grade".
- **Scarff 2020 (arXiv:2001.05388 / theCrag.com grAId)**: only
  production-deployed peer-reviewed grade-estimation algorithm. Not
  feasible on-phone for full corpus, but a useful upper-bound benchmark.
- **Lattice / Ben Moon analysis 2018**: per-climber grade-perception
  bias is real and learnable; relevant for §6.3 setter-bias.
- **MoonBoard CNN literature** (Dobles 2017, Duh & Chang 2020,
  Petashvili & Rodda 2023): hold-features predict grade with
  ~0.87–1.12 RMSE on V-scale. Stage-3-only.

### 2.2 Continuous Kilter scale (1–40)

We compute on the same 1–40 continuous scale Kilter uses internally.
`KilterGradeMapper` already exposes the integer breakpoints; the
posterior carries a `Double` mean, rounded to V/Font only at display
time.

Why continuous: Bayesian updates compose naturally on a metric scale.
Round-trips through V-scale lose information.

### 2.3 Schema relation to existing `climb_stats`

The Aurora `climb_stats` table is the storage substrate. FEAT-009 adds:

- A new `cruxcoach_grade_posteriors` table holding sufficient
  statistics per (climb_uuid, angle) for the local CruxCoach posterior.
  Decoupled from `climb_stats` so a Blossom blob refresh doesn't
  overwrite computed posterior state.
- Vote-event sufficient-statistics caching: `(count, weighted_sum,
  weighted_sumsq, last_event_id, last_aggregated_at)`.
- A separate `cruxcoach_climber_strength` table for the local user's
  Glicko-2 estimate (Stage 2+).

The display value the UI shows is the FEAT-009 posterior mean, not
`climb_stats.difficulty_average`. The Kilter aggregate flows in as a
prior (§5).

---

## 3. Algorithm Design

### 3.1 Core posterior — Bayesian conjugate Normal-Normal

For each `(climb, angle)` we maintain a Gaussian posterior over the
true grade `μ` on the 1–40 continuous scale:

```
posterior_mean     = μ
posterior_variance = σ²
posterior_n        = effective vote count (information content)
```

Each new vote with grade `g_i` and precision `τ_i` (= 1/variance
contribution) updates the posterior in closed form:

```
new_precision = old_precision + τ_i
new_mean      = (old_precision × old_mean + τ_i × g_i) / new_precision
```

This is the textbook Normal-Normal conjugate update. ~5 floating-point
ops per vote. No matrix inversion, no convergence check.

### 3.2 Anchor and prior construction

Three information sources combine to form the prior at any
`(climb, angle)`:

1. **Setter anchor** at the setter's chosen `(setter_grade, setter_angle)`.
   Default variance `σ_setter² = 4.0` (≈±2 Kilter points = ≈±1 V-grade).
2. **Global angle-delta curve** projecting the setter's grade across
   angles. Variance grows linearly with angle distance:
   `σ_proj² = 4 + 0.5 × |angleSteps|`.
3. **Optional biomechanical adjustment** (Stage 3+) refining the
   projection per-climb based on hold-feature regression.
4. **Optional Kilter aggregate prior** (§5) when the climb has ≥5
   Kilter-side ascents. Discounted by `KILTER_TRUST_FACTOR` to avoid
   baking in Quick-Log pathologies.

### 3.3 Vote precision factorization

```
τ_i = (1 / VOTE_BASE_VARIANCE)
    × w_strength_i
    × w_evidence_i
    × w_recency_i
    × w_specialism_i
```

with `VOTE_BASE_VARIANCE = 4.0` (one vote at neutral weight = ±1
V-grade variance contribution).

| Factor | Range | Source |
|---|---|---|
| `w_strength` | 0.1–1.0 | `exp(-((μ_voter - g_climb)/3)²)`, clamped at 0.1. Lowest at strength gap >5 grades. |
| `w_evidence` | 0.6–1.5 | flash=1.0, sent in 2-10 = 1.5, sent in 11-50 = 1.2, sent in >50 = 0.9, attempted-not-sent = 0.6 |
| `w_recency` | 0.0–1.0 | `exp(-Δt_days / 365)`. ~37 % weight at 1 year, ~14 % at 2 years. |
| `w_specialism` | personalised | Per-user style profile. Default 1.0. Stage 2+. |

### 3.4 Setter-bin truncation (Quick-Log defence)

Before running the conjugate update on community votes, apply Bjude's
Quick-Log defence: cap the count of votes at exactly the setter grade
(±0.5 Kilter points) to no more than the count of all *other* votes.
Excess setter-grade-bin votes are dropped (oldest first).

This neutralises the Kilter-style auto-confirm pathology if and when
CruxCoach ever introduces a similar one-tap "log ascent at setter
grade" affordance. Even without that affordance, the truncation is
cheap insurance against organic clustering near the anchor.

### 3.5 Setter-vote exclusion threshold

Until `n_community_voters_at_angle ≥ 5`, the setter's anchor acts as
the primary prior. From `n ≥ 5`, the setter's *own* personal vote is
removed from the community pool — not down-weighted, removed.

This is the Mountain Project pattern (forum-confirmed behaviour, never
documented officially) and the Lattice/Ben-Moon-2018 recommendation:
once enough community evidence exists, the setter's grade is just one
data point in a known-biased direction (always softer than reality).
Excluding it cleanly avoids double-counting the anchor.

### 3.6 Algorithm pseudocode

```kotlin
// One-shot posterior compute for a given (climb, angle).
// Cheap enough to run inline on browse-list scroll for thousands of
// climbs (each call is ~50 floating-point ops + the per-vote loop).

fun computePosterior(
    climb: ClimbDef,
    votes: List<Vote>,
    targetAngle: Int,
    nowSec: Long,
    kilterAggregate: KilterAggregate? = null,
): GradePosterior {

    // ---- 1. Build prior (§3.2 + §5) ---------------------------------
    val prior = buildPrior(climb, targetAngle, kilterAggregate)

    // ---- 2. Restrict to votes for this exact angle ------------------
    val angleVotes = votes.filter { it.angle == targetAngle }

    // ---- 3. "Broken at this angle" early-exit ----------------------
    if (isBrokenAtAngle(angleVotes)) {
        return GradePosterior(
            mean = prior.mean, variance = prior.variance,
            nVotes = angleVotes.size,
            confidence = Confidence.BROKEN,
            provenance = "doesn't go at $targetAngle°",
        )
    }

    // ---- 4. Setter-bin truncation (§3.4) ---------------------------
    val truncated = truncateSetterBin(angleVotes, climb.setterGrade)

    // ---- 5. Setter-vote exclusion past threshold (§3.5) ------------
    val communityVotes = if (countCommunityVoters(truncated, climb.setterPubkey) >= 5)
        truncated.filter { it.voterPubkey != climb.setterPubkey }
    else
        truncated

    // ---- 6. Conjugate update over surviving votes -------------------
    var precision = 1.0 / prior.variance
    var mean      = prior.mean
    var nUsed     = 0
    for (v in communityVotes) {
        val tau = voteTau(v, climb, nowSec)
        if (tau < 1e-6) continue                  // negligible weight, skip
        val newPrec = precision + tau
        mean = (precision * mean + tau * v.grade) / newPrec
        precision = newPrec
        nUsed++
    }
    val variance = 1.0 / precision

    val confidence = classifyConfidence(nUsed, prior.sourceTag)
    val provenance = buildProvenance(prior, nUsed, climb)
    return GradePosterior(mean, variance, nUsed, confidence, provenance)
}
```

### 3.7 Confidence classification

```
n=0  & angleSteps=0          → BOOTSTRAP   (setter anchor, no projection)
n=0  & angleSteps≠0          → LOW          (projected from setter anchor)
n<3                           → LOW
n in 3..9                     → MEDIUM
n>=10                         → HIGH
isBrokenAtAngle() → BROKEN
```

When the prior was sourced from Kilter aggregate (`kilter_aggregate`
non-null), the bootstrap label upgrades to MEDIUM at n=0 (Kilter
already provides ≥5 ascents of evidence; we just discount our trust
in it via `KILTER_TRUST_FACTOR`).

---

## 4. Multi-Angle Propagation

### 4.1 Setter mental model

The setter sets the climb at *their* angle (their main / chosen angle).
We never ask them to grade angles they haven't tested. The editor:

1. Captures the anchor: one `(grade, angle)` pair.
2. Optionally lets the setter tick which other angles they think the
   climb works at (`valid_angles` in the Kind-30078 tags). No grade
   required; just a binary "yes this still goes at 50°" signal that
   marginally raises the prior precision at those angles.
3. Optionally lets the setter mark angles as "doesn't go" (`broken_at`
   tag list). Hard-flags the angle as BROKEN regardless of community
   votes.

### 4.2 Global angle-delta curve

Trained offline once by the maintainer against a Kilter-blob snapshot,
restricted to climbs with `ascensionist_count ≥ 100` at multiple
angles. For each climb, compute the per-pair grade delta `Δ_ij =
g_at_angle_j - g_at_angle_i`; aggregate across the corpus into a
piecewise-linear curve `f(angle)` with one data point per 5° step
from 0° to 70°.

Empirically (per Bjude plots and Lattice 2018): the slope is roughly
+0.4 Kilter points per +5° in the 20°–55° range, flattening at
extremes. Exact values fitted from data, shipped as a JSON resource
inside the app, refreshed every 6 months alongside other tunables.

The curve provides a per-angle delta `Δ(targetAngle, setterAngle) =
f(targetAngle) - f(setterAngle)`. The projected anchor at any target
angle is `setterGrade + Δ(targetAngle, setterAngle)`.

### 4.3 Per-climb feature adjustment (Stage 3)

Stage-3 ships a small linear regression mapping hold-feature vectors
to per-degree grade adjustments. Inputs:
- Hold count, hold-class distribution (crimps / slopers / jugs / feet).
- Vertical span, horizontal span.
- Mean horizontal hold density.
- Move count from `BoardClimbParser`.

Coefficients fitted offline (same training data as the global curve)
and shipped as a Float[N] resource. ~50 floats. The feature delta
adds to the curve delta:

```
projected_mean = setterGrade
              + curveDelta(setterAngle → targetAngle)
              + featureDelta(climb.features, |targetAngle - setterAngle|)
```

Stage 1 ships only the curve. Stage 3 adds the feature delta when
empirical RMSE on held-out climbs justifies it (target: <0.3 V-grade
RMSE on cross-angle prediction).

### 4.4 Broken-at-angle detection

Three triggers, any one suffices:

1. **Send rate <5% on ≥10 attempts** at the angle: hard signal of
   physical infeasibility.
2. **Bimodal grade distribution** with cluster separation > 1.5
   V-grades and ratio of within-cluster to between-cluster variance
   < 0.3. Indicates "two different sequences exist depending on body
   position", which usually means at least one of them is broken.
3. **Setter explicitly marks `broken_at` in Kind-30078**.

UI: greyed-out grade with "doesn't go at this angle" or "controversial
— V5 to V9 reported" badge. No conjugate update produces a usable mean
in BROKEN state; the prior is shown as the fallback.

---

## 5. Kilter / CruxCoach Rating Blend (§B-merge)

This is the most architecture-critical section. The key insight: a
single climb may have ratings from up to **three** independent sources
(Kilter setter, Kilter community aggregate, CruxCoach setter, CruxCoach
community votes), and we need a single number for the user without
silently averaging incompatible distributions.

### 5.1 Per-(climb, angle) source matrix

| Source | Field | Available when |
|---|---|---|
| **A** — Kilter `display_difficulty` | Setter-set on Kilter | `origin='kilter'` OR `kilter_status='synced'` |
| **B** — Kilter `difficulty_average` | Aggregate of Kilter community votes (with all known pathologies) | Same as A, with `ascensionist_count` ≥ 1 |
| **C** — CruxCoach setter anchor | `setter_grade` from Kind-30078 | `origin='cruxcoach'` |
| **D** — CruxCoach community votes | Posterior over Kind-30079 events | Stage 1+ |
| **E** — Kilter `benchmark_difficulty` | Kilter-internal computed benchmark | `is_benchmark=1` |

### 5.2 Strategy: Kilter aggregate as Bayesian prior, CruxCoach votes as updates

Rejected alternatives:
- **Show two values side-by-side** — verbose, casual users find it
  confusing.
- **Treat Kilter's per-vote distribution as additional CruxCoach votes**
  — bakes in Quick-Log spike, no strength weighting.
- **Hard segregation by origin** — imported climbs lose their Kilter
  vote evidence permanently. Wastes data.

Adopted: **Kilter aggregate becomes the prior; CruxCoach Kind-30079
votes update it.** Kilter's effective sample size is discounted by
`KILTER_TRUST_FACTOR` so a CruxCoach community of moderate size can
shift the posterior away from the Kilter consensus when warranted.

### 5.3 Prior construction with Kilter present

```kotlin
fun buildPrior(
    climb: ClimbDef,
    targetAngle: Int,
    kilterAggregate: KilterAggregate?,
): GaussianPrior {

    // CASE 1: pure CruxCoach, no Kilter data → setter anchor only.
    if (kilterAggregate == null || kilterAggregate.ascensionistCount < 5) {
        return setterAnchorPrior(climb, targetAngle)
    }

    // CASE 2: Kilter has data → use as prior, discounted.
    val kilterMean = kilterAggregate.benchmarkDifficulty
        ?.takeIf { kilterAggregate.isBenchmark }
        ?: kilterAggregate.difficultyAverage
        ?: kilterAggregate.displayDifficulty   // fallback (no community votes)

    val rawN          = kilterAggregate.ascensionistCount.toDouble()
    val effectiveN    = (rawN / KILTER_TRUST_FACTOR).coerceAtMost(KILTER_MAX_PRIOR_PRECISION)
    val benchmarkBoost = if (kilterAggregate.isBenchmark) 1.5 else 1.0
    val priorPrecision = effectiveN * benchmarkBoost / VOTE_BASE_VARIANCE

    return GaussianPrior(
        mean = kilterMean,
        precision = priorPrecision,
        variance = 1.0 / priorPrecision,
        sourceTag = if (kilterAggregate.isBenchmark) "kilter_benchmark" else "kilter_avg",
        ascensionistCount = kilterAggregate.ascensionistCount.toLong(),
    )
}
```

Constants (initial calibration; tune empirically per §11):

```kotlin
const val VOTE_BASE_VARIANCE          = 4.0   // ±1 V-grade per neutral vote
const val KILTER_TRUST_FACTOR         = 3.0   // 100 Kilter ascents → 33 effective pseudo-votes
const val KILTER_MAX_PRIOR_PRECISION  = 20.0  // hard cap: even 1000-ascent climbs are movable
const val SETTER_ANCHOR_VARIANCE      = 4.0   // setter ≈ ±1 V-grade prior
```

Reasoning:
- **`KILTER_TRUST_FACTOR = 3`**: empirical penalty for Kilter's
  Quick-Log spike (Bjude shows ~⅓ of Kilter votes are auto-confirms),
  no strength weighting (factor of ~1.5×), no Sybil resistance (factor
  of ~1.2× safety margin). Multiply: 1.7 × 1.5 × 1.2 ≈ 3.0.
- **`KILTER_MAX_PRIOR_PRECISION = 20`**: even a 1000-ascent climb gets
  a prior that ~20 high-quality CruxCoach votes can shift by half a
  grade. Without this cap, hyper-popular Kilter climbs would be
  un-budgeable.

### 5.4 CruxCoach setter anchor when Kilter is also present

For round-tripped or imported climbs (origin='cruxcoach',
kilter_status='synced'), the CruxCoach setter anchor (Kind-30078) and
the Kilter aggregate may disagree. Three cases:

| Disagreement | Behaviour |
|---|---|
| `|setter_grade - kilter_mean| < 1.0` Kilter point | Setter folded into prior as one extra pseudo-vote with `τ = 1/SETTER_ANCHOR_VARIANCE = 0.25`. Tightens precision modestly. |
| ≥ 1.0 point | Setter kept *separate*; UI surfaces "Setter sagt X, Kilter-Community sagt Y" in the disagreement-detail box (§9.3). Bayesian update uses the Kilter aggregate as the prior; setter contributes one additional pseudo-vote with the precision scheme above (so it does influence the posterior but doesn't double-count). |
| Setter is the local user's own pubkey AND `n_cruxcoach_votes ≥ 5` | Drop setter from the pool entirely (§3.5 standard rule). |

### 5.5 Stage rollout

| Stage | Kilter blend behaviour |
|---|---|
| **Stage 1 MVP** | `KILTER_TRUST_FACTOR = 3.0` constant. No empirical tuning. |
| **Stage 2** | Compare CruxCoach posterior to actual send-rates on local users' ascent logs. If CruxCoach posterior is consistently >0.5 V-grade more predictive than Kilter aggregate, lower the trust factor. If less predictive, raise it. |
| **Stage 3** | Per-setter and per-region trust factors (some setters' anchors are reliably accurate, others always sandbag — learn). |

---

## 6. Climber Strength + Vote Weighting

### 6.1 Strength estimator

**Stage 1 fallback** (no separate climber-profile event yet):

```
strength_estimate = max(
    median_grade_of_last_N_sends_at_relevant_angle_band,
    highest_flashed_grade
)
```

with `N = 30`. The relevant angle band is `setterAngle ± 10°`; widen
to ±15° if fewer than `N` sends exist in the narrow band.

**Stage 2** ships Glicko-2 over the user's full ascent history. Each
ascent is treated as a "game" against an opponent rated at the climb's
current Bayesian-posterior mean:

| Ascent type | Glicko-2 score |
|---|---|
| Flash | win (1.0) |
| Sent in 2–10 attempts | draw-leaning-win (0.6) |
| Sent in 11–50 attempts | draw (0.5) |
| Sent in >50 attempts | draw-leaning-loss (0.4) |
| Logged attempt without send | loss (0.0) |

Rating period = 1 week. Stored as `(μ, φ, σ)` in
`cruxcoach_climber_strength`.

### 6.2 Style profile (Stage 2)

Computed from the user's send log:

| Component | Definition |
|---|---|
| `power_score` | (sends_above_40°) / (sends_above_40° + sends_at_30°_or_less) |
| `crimp_score` | (sends with top-5 hardest holds in size class crimp) / (total sends) |
| `endurance_score` | (sends with >12 moves) / (total sends) |

Vector `[power, crimp, endurance]` ∈ [0,1]³. Updated incrementally on
each send-log mutation.

### 6.3 Setter bias correction

For each setter pubkey with ≥10 climbs that have ≥5 community votes
each:

```
setter_bias = mean over their climbs of
              (community_posterior_mean − setter_anchor_grade)
```

This is the per-setter correction offset. Applied as a prior-mean shift
on new climbs by the same setter:

```
priorMean = setterGrade + setter_bias_offset(climb.setterPubkey)
```

If a setter consistently sandbags by 1.5 grade points, new climbs by
them get a +1.5-point upward pre-correction on the prior. Cached in
`cruxcoach_setter_bias`. Decays toward 0 over 6 months without new
data points.

### 6.4 Style bias surfacing

When (climb, angle) has enough votes (`n ≥ 10`), compute two parallel
posteriors:

- `posterior_powerful` = restricted to top-quartile `power_score` voters
- `posterior_technique` = restricted to bottom-quartile `power_score` voters

If `|posterior_powerful.mean - posterior_technique.mean| ≥ 1.5` Kilter
points, surface in UI: "Powerful climbers say V7; technique climbers
say V6 — your style suggests V6.5". For users without enough style
profile data (<30 sends), suppress and show only the population
posterior.

This is opt-in by default — config toggle in Settings, off until the
user has 30 sends and we have a style vector with confidence.

---

## 7. Nostr Event Shapes

### 7.1 Kind-30078 — Climb definition (extended)

Replaceable per (setter_pubkey, d-tag). Existing kind from FEAT-003;
this section adds three tag conventions.

```json
{
  "kind": 30078,
  "pubkey": "<setter_pubkey>",
  "tags": [
    ["d", "cruxcoach/climb/<frame_hash>"],
    ["board", "kilter-original-12x12"],
    ["frame_hash", "<sha256>"],
    ["frames", "p1083r15p1117r15..."],
    ["name", "Project Beta"],
    ["setter_grade", "21.0", "40"],          // grade@angle: continuous Kilter scale, integer angle
    ["setter_angle", "40"],
    ["valid_angles", "20", "25", "30", "35", "40", "45", "50"],
    ["broken_at", "10", "15"],               // optional: angles where setter says it doesn't go
    ["t", "crimpy"], ["t", "powerful"]       // NIP-32 style/quality tags (informational)
  ],
  "content": "<setter notes, optional>"
}
```

Backwards-compatible with FEAT-003: existing `setter_grade` consumers
already accept the `(grade, angle)` pair; `valid_angles` and `broken_at`
default to "all reasonable angles" / "none" when absent.

### 7.2 Kind-30079 — Grade vote (new)

Parameterized replaceable per `(voter_pubkey, climb_hash, angle)`.
Latest vote from a given voter for a given climb-angle wins; relays
auto-discard older.

```json
{
  "kind": 30079,
  "pubkey": "<voter_pubkey>",
  "tags": [
    ["d", "cruxcoach/vote/<frame_hash>/<angle>"],
    ["a", "30078:<setter_pubkey>:cruxcoach/climb/<frame_hash>"],
    ["frame_hash", "<sha256>"],
    ["angle", "40"],
    ["grade", "21.0"],                    // continuous Kilter scale 1..40
    ["sent", "1"],                        // 1=sent, 0=attempted only
    ["attempts", "3"],
    ["client_strength", "22.5"],          // self-reported Glicko-2 mu
    ["client_strength_rd", "0.42"],       // Glicko-2 phi
    ["style", "0.7", "0.4", "0.2"]        // [power, crimp, endurance], optional
  ],
  "content": ""
}
```

### 7.3 Kind-30080 — Climber profile (new, Stage 2+)

Parameterized replaceable per pubkey. Self-published Glicko-2 state +
style vector. Used by other clients to weight this climber's votes.

```json
{
  "kind": 30080,
  "pubkey": "<pubkey>",
  "tags": [
    ["d", "cruxcoach/climber/main"],
    ["board", "kilter-original-12x12"],
    ["strength_mu", "22.5"],
    ["strength_phi", "0.42"],
    ["strength_sigma", "0.06"],
    ["style", "0.7", "0.4", "0.2"],
    ["sends_total", "247"],
    ["sends_window_days", "365"]
  ],
  "content": ""
}
```

### 7.4 Why new kinds rather than NIP-32 labels

NIP-32 (Kind 1985) constrains label values to short strings; we need
numeric grade + numeric metadata + replaceability per `(voter, target,
angle)`. Kind 30079 in the parameterized-replaceable range (30000–
39999) gives us replaceability without relay-side hassle. NIP-32
remains useful additionally for *style/quality tags* on climbs (e.g.
labels tagging climb-d-tag with `["L", "cruxcoach.climb"], ["l",
"crimpy", "cruxcoach.climb"]`) — pure-text taxonomy is its sweet spot.

### 7.5 NIP registration plan

Open a draft NIP PR documenting Kinds 30079 and 30080 with the
`cruxcoach/` d-tag namespace. Version the namespace
(`cruxcoach/vote/v1/<hash>/<angle>`) so a v2 schema iteration can ship
without breaking v1 readers.

---

## 8. Aggregation Cost & Storage

### 8.1 Sufficient-statistics caching

Per (climb_uuid, angle) we store 5 floats + 1 long:

```sql
CREATE TABLE cruxcoach_grade_posteriors (
    climb_uuid TEXT NOT NULL,
    angle INTEGER NOT NULL,
    posterior_mean REAL NOT NULL,
    posterior_variance REAL NOT NULL,
    n_votes_used INTEGER NOT NULL,
    last_event_id TEXT,                  -- highest-timestamp Kind-30079 we've folded in
    last_aggregated_at_sec INTEGER NOT NULL,
    confidence_label TEXT NOT NULL,      -- 'BOOTSTRAP'|'LOW'|'MEDIUM'|'HIGH'|'BROKEN'
    source_tag TEXT NOT NULL,            -- 'setter_anchor'|'kilter_avg'|'kilter_benchmark'
    PRIMARY KEY (climb_uuid, angle)
);
CREATE INDEX idx_posteriors_climb ON cruxcoach_grade_posteriors(climb_uuid);
```

~80 bytes × 15 angles × 85k climbs ≈ **102 MB** in steady state. Phone-
feasible.

### 8.2 Vote-event ingestion

On every relay-side delta of Kind-30079 events:

1. Parse vote into a `Vote` struct.
2. Upsert into a `cruxcoach_climb_votes` table keyed on
   `(voter_pubkey, climb_uuid, angle)` — replaceable-by-event pattern.
3. Mark the affected `(climb_uuid, angle)` posterior as dirty.

A background job recomputes dirty posteriors throttled to ~100/sec
(plenty headroom for sustained ingestion).

### 8.3 Cold-sync cost

Initial sync via NIP-77 negentropy: pull all Kind-30079 events for the
user's local board. Worst case ~50 votes × 15 angles × 85k climbs × 250
bytes = 16 GB upper bound, **but the parameterized-replaceable
property caps it at distinct voters per (climb, angle)**. Real-world
v0.2.0 estimate (≤1000 active climbers, sparse cross-coverage):
50–200 MB, multi-hour first-sync over LTE, a few minutes on Wi-Fi.

### 8.4 Optional: trusted aggregator events (Kind 30081)

For users who don't want to do client-side aggregation, a maintainer-
operated key can publish replaceable `Kind 30081` "consensus events"
per (climb, angle): the maintainer's own posterior, signed. Other
clients can opt to trust this signature in lieu of computing locally.
Mirrors theCrag's grAId publication. Scope-deferred — not in MVP.

---

## 9. UX

### 9.1 Default display: single grade + steepness slider

Primary surface (climb detail screen, browser cards):
- Single rounded grade in V-scale or Font (per user's setting).
- Confidence dot: green (HIGH), yellow (MEDIUM), grey (LOW), red
  (BROKEN), blue (BOOTSTRAP).
- Below the grade, a thin horizontal line shows ±1σ confidence
  interval, visually mapped to V-grade ticks.

### 9.2 Per-angle steepness slider

A compact slider 0°–70° in 5° steps appears on the detail screen. The
grade animates as the user drags. Default position = the user's current
browse-angle (saved from FEAT-006 prefs).

Tap the slider thumb to expand a per-angle table:
- One row per 5° step (15 rows).
- Columns: grade · n votes · confidence dot · histogram thumbnail.
- Greyed-out rows for angles with no votes (showing the projected
  estimate).
- Setter angle tagged with a small wrench icon.

### 9.3 Disagreement detail box (Kilter ⊕ CruxCoach)

When `|setter_grade - kilter_mean| ≥ 1` Kilter point AND `n_cruxcoach
≥ 3`, an info chip appears below the primary grade. Tapping expands:

```
┌──────────────────────────────────────────────┐
│ V5 · 6c           ◯ medium confidence (n=12) │
├──────────────────────────────────────────────┤
│  Setter anchor:        V4 (Kilter)           │
│  Kilter community:     V5 (n=247)            │
│  CruxCoach community:  V6 (n=12)             │
└──────────────────────────────────────────────┘
```

This is opt-in expansion; the casual user sees one number with a
confidence dot.

### 9.4 Setter-side editor (FEAT-003 extension)

The publish flow gains a "Multi-angle" sub-section after the grade
slider:

```
Grade (an Ihrem Winkel):     V5 (id=21)
Winkel:                       40°

Multi-Angle (optional):
  ☑ Funktioniert auch bei 35°
  ☑ Funktioniert auch bei 45°
  ☐ Funktioniert auch bei 30°
  ☐ Funktioniert auch bei 50°
  …
  ☑ DOESN'T GO at 10°  ← marks broken_at
```

Default: only the setter angle is checked. Setter ticks confirmed
angles for a small prior-precision boost at those angles.

### 9.5 Voting UI (climb detail screen)

A "Grade abgeben"-CTA appears for any climb the user has logged at
least one ascent on. Tapping opens a sheet:

```
Wie schätzt du diese Climb ein?
┌──────────────────────────────────────────────┐
│ V0    V2    V4    V6    V8    V10            │
│ ●─────●─────●──[●]─●─────●─────●             │  ← slider (continuous)
│                                              │
│  Versuche: ( - )  3  ( + )                   │
│  [✓] Send             [ ] Nur Versuch        │
│                                              │
│              [ Veröffentlichen ]             │
└──────────────────────────────────────────────┘
```

Submits Kind-30079. Replaces any previous vote from this user at this
(climb, angle). Visible in their personal log.

---

## 10. Sybil Resistance

Layered defence; each layer raises the cost of manipulation.

### 10.1 Web-of-Trust hop count

Every user has a Kind-3 follow list (NIP-02). For each vote-event,
compute the social distance from the local user to the voter:

| Hops | Weight multiplier |
|---|---|
| 0 (self) | 1.0 |
| 1 | 1.0 |
| 2 | 0.5 |
| 3+ | 0.1 |
| no path | 0.0 (vote ignored) |

Computed lazily per (voter_pubkey, local_user) and cached for ~1 day.

### 10.2 Implicit reputation via Kind-30080

Pubkeys with a credible Kind-30080 climber profile (consistent ascent
log going back ≥6 months on the same board) weight more. New pubkeys
with no profile history → effectively zero weight until they accumulate
≥30 logged ascents.

### 10.3 Optional NIP-13 PoW gate

Configurable in Settings: "Reject votes from pubkeys without
work-of-proof and without web-of-trust ties". Default: enabled at the
strict tier (PoW required if hops > 1).

### 10.4 NIP-65 + NIP-05 verification

Voters with NIP-05 verification at a recognised climbing-gym domain
(`name@gymname.com` for partner gyms) get a small precision bonus.
Optional partner-gym list shipped as a JSON resource.

### 10.5 Acceptance

Combining the above, a coordinated attack would need to:
- Acquire reputable pubkeys (each ≥6 months of credible ascent logging),
  OR
- Invest months of fake ascent history per puppet.

Both are economically uninteresting at the scale of a climbing app.
The system cannot prevent Sybil entirely — it makes manipulation cost
more than any plausible benefit.

---

## 11. Implementation Plan

### 11.1 Stages

**Stage 1 — MVP (ships with v0.2.0)**

Goal: single trustworthy grade per (climb, angle), strictly better
than Kilter's `difficulty_average` even with sparse community.

| Component | Status |
|---|---|
| Kind-30078 setter_grade tag refinement (§7.1) | already mostly done; add `valid_angles`, `broken_at`, format check `setter_grade` as `(grade, angle)` pair |
| Kind-30079 vote event (§7.2) | new |
| Bayesian Normal-Normal aggregator (§3) | new — `DifficultyRatingEngine` class |
| Setter-bin truncation (§3.4) | new |
| Setter-vote exclusion at n≥5 (§3.5) | new |
| Global angle-delta curve (§4.2) | new — JSON resource |
| Kilter aggregate as Bayesian prior (§5) | new |
| `cruxcoach_grade_posteriors` table | new SQL migration |
| UX: single grade + confidence dot + steepness slider (§9.1, §9.2) | new |
| Voting UI sheet (§9.5) | new |
| Default WoT weight (§10.1) | new — uses existing Kind-3 plumbing |
| Stage-1 climber-strength heuristic (§6.1 fallback) | new — purely local, no Nostr publish yet |

**Stage 2 — After 100 active users (~v0.2.x)**

| Component |
|---|
| Glicko-2 strength tracker |
| Kind-30080 climber profile event |
| Per-setter bias correction (§6.3) |
| Style-personalised grade variants (§6.4, §9.3-extension) |
| Empirical tuning of `KILTER_TRUST_FACTOR` from telemetry |

**Stage 3 — After 500 active users (~v0.3.0)**

| Component |
|---|
| Hold-feature regression for cross-angle delta (§4.3) |
| Broken-at-angle automatic detection (§4.4 triggers 1+2) |
| Optional trusted-aggregator Kind-30081 (§8.4) |
| NIP-13 PoW gate enforcement (§10.3 strict tier) |

### 11.2 Disagreement detection

Once Stage 1 is live, log telemetry:
- For each climb where Kilter aggregate AND CruxCoach posterior both
  exist with `n ≥ 3`, log `(climb_uuid, kilter_mean, cruxcoach_mean,
  cruxcoach_n)`.
- If disagreement >0.5 V-grade is correlated with CruxCoach having
  better predictive accuracy on the local user's send-vs-attempts
  log, lower `KILTER_TRUST_FACTOR`. If worse, raise it.

### 11.3 Constants exposed in Settings

For power users:
- `KILTER_TRUST_FACTOR` (default 3.0; allow 1.5–5.0 range).
- WoT hop cutoff (default 3; allow 1–5).
- Setter-vote exclusion threshold (default 5; allow 0–20).

Hidden behind a Developer-Mode gate; production users see the defaults.

---

## 12. Schema Additions

### 12.1 `cruxcoach_grade_posteriors`

See §8.1. New table, no Aurora-side dependency.

### 12.2 `cruxcoach_climb_votes`

```sql
CREATE TABLE cruxcoach_climb_votes (
    voter_pubkey TEXT NOT NULL,
    climb_uuid TEXT NOT NULL,
    angle INTEGER NOT NULL,
    grade REAL NOT NULL,                  -- 1..40 continuous
    sent INTEGER NOT NULL,                -- 0/1
    attempts INTEGER NOT NULL,
    voter_strength_mu REAL,
    voter_strength_phi REAL,
    style_power REAL, style_crimp REAL, style_endurance REAL,
    event_id TEXT NOT NULL,               -- Nostr Kind-30079 event id
    event_created_at INTEGER NOT NULL,    -- epoch sec
    PRIMARY KEY (voter_pubkey, climb_uuid, angle)
);
CREATE INDEX idx_votes_climb ON cruxcoach_climb_votes(climb_uuid, angle);
CREATE INDEX idx_votes_voter ON cruxcoach_climb_votes(voter_pubkey);
```

INSERT OR REPLACE on event ingestion (parameterized-replaceable
pattern).

### 12.3 `cruxcoach_climber_strength`

```sql
CREATE TABLE cruxcoach_climber_strength (
    pubkey TEXT NOT NULL PRIMARY KEY,
    strength_mu REAL NOT NULL,
    strength_phi REAL NOT NULL,
    strength_sigma REAL NOT NULL,
    style_power REAL, style_crimp REAL, style_endurance REAL,
    sends_total INTEGER NOT NULL,
    sends_window_days INTEGER NOT NULL,
    last_recomputed_at_sec INTEGER NOT NULL
);
```

### 12.4 `cruxcoach_setter_bias`

Stage 2+:

```sql
CREATE TABLE cruxcoach_setter_bias (
    setter_pubkey TEXT NOT NULL PRIMARY KEY,
    bias_offset REAL NOT NULL,
    n_climbs_used INTEGER NOT NULL,
    last_recomputed_at_sec INTEGER NOT NULL
);
```

---

## 13. Testing Strategy

### 13.1 Unit

- Posterior update math against published Bayesian-conjugate examples.
- Setter-bin truncation correctness (synthetic Quick-Log spike).
- Kilter prior construction with `KILTER_TRUST_FACTOR` round-trip.
- BROKEN classification with synthetic bimodal vote distributions.

### 13.2 Integration

- Mock Kind-30079 ingestion → posterior recomputation → UI render.
- Vote-replacement (a voter updating their grade) → old vote eviction.
- Multi-angle propagation: setter at 40° → projected at 25° matches
  global curve.

### 13.3 Empirical / regression

- Run posterior over a 1000-climb subset of well-voted Kilter blob.
  Compare against `difficulty_average`. Flag climbs where CruxCoach
  posterior diverges by >1 grade and verify by manual review.
- Synthetic Sybil attack: 5 sock-puppet pubkeys all voting V8 on a
  legitimate-V5 climb. Verify WoT weighting + new-pubkey-no-history
  gate suppress them.

### 13.4 UX

- Disagreement-detail box on climbs with Kilter `display_difficulty`
  and CruxCoach votes diverging.
- Steepness slider performance: <60 fps on a mid-range Android phone
  while dragging across all 15 angles.

---

## 14. Caveats and Known Limitations

### 14.1 Kilter aggregate trust

The default `KILTER_TRUST_FACTOR = 3.0` is calibrated from
Bjude/Lattice analyses and conservative engineering judgment. It is
not empirically validated. **Stage 2 must tune it** against local
predictive accuracy before we confidently claim "better than Kilter".

### 14.2 Bayesian conjugate is an approximation

The Normal-Normal model assumes Gaussian-distributed grades. Real
grade-vote distributions are slightly heavy-tailed (occasional
"benchmark sandbag" outliers). Setter-bin truncation handles the
common pathology; tail-robustness would require heavier statistics
(e.g. Hodges-Lehmann), which we accept as a limitation in Stage 1.

### 14.3 No MCMC, no full WHR

theCrag.com's grAId is provably better in some regimes. We accept up
to ±0.5 V-grade approximation error on heavily-voted climbs. The
trusted-aggregator pattern (§8.4) preserves the option to ship grAId-
quality numbers later from a maintainer-run pipeline.

### 14.4 Kind-30079 is a novel registration

No prior Nostr application uses replaceable numeric ratings at this
scale. Expect schema iteration. The `cruxcoach/vote/v1/<hash>/<angle>`
namespace versioning lets v2 ship without breaking v1 readers.

### 14.5 Style bias is socially fraught

Surfacing "powerful climbers say X, technique climbers say Y" can be
read as gatekeeping. Default behaviour: opt-in only after the user has
≥30 sends and an explicit toggle is enabled.

### 14.6 Setter pseudonymity

Per-setter bias correction (§6.3) ties to a stable pubkey. Setters
rotating pubkeys defeat the offset learning. NIP-05 verification or a
stable Kind-30080 history is the mitigation; a determined evader can
always create fresh pubkeys.

### 14.7 Anchoring bias on the voting UI

Showing the current consensus grade adjacent to the voting slider
biases the vote toward consensus. **Mitigation**: hide the consensus
grade until the user submits their own, by analogy with Stack
Overflow's vote-then-reveal flow. Stage 1 can ship the simple version
(consensus visible during voting) and tighten in Stage 2 if the data
shows clustering.

### 14.8 Aurora schema drift

The post-2024 Kilter/Aurora split (per Vertige Media reporting) and
the new `kilterboard.io` Keycloak/PowerSync stack mean the schema may
shift. The `(climb_uuid, angle)` keying is invariant across all
known Aurora variants; we monitor for new fields via the Blossom-
sync diff log.

---

## 15. Open Questions

1. **Empirical `KILTER_TRUST_FACTOR` tuning** — block on Stage 2
   telemetry. Current default 3.0 is conservative-enough to ship.
2. **Voting UI anchoring-bias mitigation** — hide consensus during
   vote? Stage 1 ships simple version; revisit empirically.
3. **Trusted-aggregator pubkey** — if/when we ship Kind-30081, who
   signs? CruxCoach maintainers' rotated key? Per-relay aggregator
   competition? Out of MVP scope.
