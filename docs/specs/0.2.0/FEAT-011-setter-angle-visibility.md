---
status: skeleton
---
# Feature Spec: Setter Angle Visibility (v0.2.0)

> **Status:** Skeleton — UX problem and rough surfaces identified.
> §2 data-source spike resolved 2026-05-06 (see §2 for the
> live-probed Kilter API verdict and the per-source coverage
> table). Remaining gate before implementation: visual alignment
> with FEAT-009's confidence/origin treatment so the anchor row
> coexists cleanly with the rating engine's per-angle rendering.
>
> **Depends on:** None at spec level. Touches the detail screen,
> the browser card, and the per-angle picker — all in
> `androidApp/src/main/java/com/cruxcoach/android/ui/board/`.
>
> **Relates to:** FEAT-009 (Difficulty Rating Engine) — both render
> the per-angle stats row; the visual treatment must compose, not
> compete. FEAT-008 (Kilter Own-Climb Import) — imports must
> preserve the anchor angle alongside the rest of the climb row.

---

## 1. Overview

A Kilter Board climb's holds are set at a single specific wall
angle. The same hold sequence climbs very differently at 25° vs
40° — sometimes drastically. The setter's chosen angle is
high-signal information for the user picking a climb to try, or
interpreting a difficulty:

- **Setter intent**: the angle the setter sent at is the canonical
  expression of the climb. Users who want the "intended" version
  of a climb match that angle.
- **Difficulty calibration**: a V5 set at 25° and a V5 set at 40°
  are effectively different climbs. Without the anchor visible,
  users self-calibrate from the per-angle stats list, which
  conflates community votes across angles.
- **Logging accuracy**: matching the angle to the setter's anchor
  reduces user-side guesswork when self-grading or comparing sends
  with friends.

Today CruxCoach surfaces the per-angle ascensionist + difficulty
aggregates in `climb_stats` but does not visually distinguish the
setter's anchor angle from a purely community-derived row.

### Goals

- Make the setter's anchor angle visible at a glance on **detail
  screen** and **browser card**.
- Emphasise the anchor row in the per-angle stats list (badge,
  icon, or row-level highlight — exact treatment in §3).
- Default the angle picker on first open to the setter's anchor
  when the user has not yet expressed a preference.

### Non-goals

- Re-grading / multi-angle propagation (FEAT-009 owns that).
- Multi-anchor climbs. We treat each climb as having one canonical
  anchor; if a setter publishes at multiple angles intentionally,
  the earliest / lowest-angle row wins. Edge case, not common.
- Editing the anchor for community climbs. The anchor is set at
  publish time and immutable thereafter.

## 2. Data source — spike resolved 2026-05-06

**Decision: use Kilter's `climbs.angle` field directly.** The
Kilter REST API exposes the setter's anchor angle as a first-class
column on each `climbs` row, distinct from the per-(climb, angle)
`climb_stats` aggregates. Verified against the live API
(`/api/climbs/curated`, n=5,279 climbs):

| coverage | rows |
|---|---|
| non-null, non-zero `angle` | 5,121 (97.0 %) |
| `angle = 0` (sentinel for "no anchor set") | 147 (2.8 %) |
| `angle = null` | 11 (0.2 %) |

Distinct values: `5°, 10°, 15°, …, 70°` in 5° steps.

### Heuristic alternatives — all rejected

The three candidate heuristics from the original skeleton,
re-tested against the 5,121 truth rows:

| heuristic | match rate against `climbs.angle` |
|---|---|
| Highest-`ascensionist_count` angle in `climb_stats` | 68.6 % |
| `climb_stats.fa_username == climbs.username` row | 35.2 % |
| `climb_stats.is_listed = 1` row | n/a — column does not exist on the new Kilter API stats response |

Setters publish at one angle, the community then logs sends at
their preferred wall angles — the modal-send-angle is *not* the
setter's anchor 31 % of the time. Neither fallback is reliable
enough to serve as a primary or even secondary signal.

### Per-source coverage in our local DB

| climb source | `angle` available? |
|---|---|
| New CruxCoach-authored climbs (FEAT-003 editor) | Yes — wall angle is known at publish time, persist on the row |
| FEAT-008 imported NATIVE (`/api/climbs/climbdetails/user`) | Yes — endpoint returns `angle` (and `derivativeAngle`) per row |
| Live Kilter-API harvest into the daily Blossom mirror (`/api/climbs/curated`) | ~97 % — the cron just needs to pull the field |
| Aurora-era bulk catalog (~169k climbs in our snapshot) | **No** — the Aurora schema never stored a setter-anchor column. The data is not recoverable from what we have. |

For the Aurora-era bulk, there is no realistic backfill — the
value was never recorded. The UI must tolerate `angle = NULL`
(badge omitted) for that majority of historical rows.

### Catalog coverage — sparse by design

The daily blossom-sync only pulls `/api/climbs/curated`
(~5,279 rows on 2026-05-06) and `/api/climbs/delteduuids`
(deletion notifications). The local Board DB currently carries
**174,218 climbs** (snapshot 2026-05-06), of which 171,172 are
still listed. The sync *adds and updates* but never wipes;
climbs that were once in /curated stay in the local DB even
after they leave the curated set.

Result, in concrete numbers:

| segment | rows | `angle` populated post-FEAT-011? |
|---|---|---|
| Currently in `/api/climbs/curated` | ~5,279 | Yes (~97 %) |
| Once in /curated, now de-listed; or pure Aurora-era bulk | ~169,000 | **No — never** |
| FEAT-008 own-climb imports | per-user, small | Yes (100 %) |
| New CruxCoach-authored climbs | growing, small | Yes (100 %) |

⇒ at v0.2.0 ship, the badge is visible on roughly **3 % of the
catalog**. Net growth of /curated is ~30 climbs/day on recent
observation, so the coverage shrinks asymptotically toward "all
new climbs have it" without ever back-filling the historical
bulk. **This is the design-intentional outcome of the spike, not
a gap to close** — there is no upstream that has the data we
would need.

`/api/climbs/all` (and its paginated variants `/all/0`,
`/all/<iso8601>`) all return HTTP 200 + `[]` for normal user
accounts (verified 2026-05-06; FEAT-008 §3.1 candidate table).
Admin-restricted at the API layer; not a workable bulk-fetch
path. `/api/climbs/single/<uuid>` could in principle hydrate one
row at a time, but ~169k per-row API calls is neither ergonomic
nor compliant with the cron's "be a polite API citizen" stance.

### Scope decision — ship at sparse coverage (2026-05-06)

Decision: ship at the ~3 % absolute coverage, no fallback
signal. The number understates *effective* visibility — /curated
is by definition the slice the user encounters most in the
browser (curated / featured / recent); tail-content climbs from
the historical bulk are reachable only via deep search, where
the badge's absence matters least anyway.

A secondary "popular at X°" signal from
`argmax(climb_stats.ascensionist_count)` is rejected for the
same reason the same heuristic was rejected as a primary
candidate above: at 68.6 % agreement with the true setter
angle, two semantically distinct badges sharing the same UI
slot would muddy the signal users come to trust on the slice
where it does carry setter intent. A user who learns the badge
as "setter intent" would silently get a different reading on
~31 % of climbs without warning.

The badge therefore omits gracefully when `angle` is null or 0;
FEAT-009's confidence/origin treatment must compose with that
absence (no slot collisions on the no-anchor case).

### Why `/climbdetails/user.angle` does not help blossom-sync

### Why `/climbdetails/user.angle` does not help blossom-sync

The `/api/climbs/climbdetails/user` endpoint (FEAT-008 §3.1) is
per-user-authenticated and returns only the caller's authored
climbs. It is the right source for the FEAT-008 import flow.
It is **not** usable in the daily blossom-sync cron, which runs
on a single dedicated cron account per compliance
(`feedback_kilter_compliance.md`: no shared service-account
aggregation, self-account-only writes). The cron account itself
authors no climbs, so the endpoint returns `[]`. The cron must
keep using `/api/climbs/curated.angle` for broad-catalog coverage.

## 3. UX surfaces

Final visual design TBD pending FEAT-009 alignment, but the rough
treatment per surface:

### Detail screen header
Under the climb name, alongside the existing grade + ascensionist
badges:

```
"Open Project"      ★ 7c+    Ø 42 sends
                    [ Set at 35° ]
```

A subdued chip with a "wall-tilt" glyph + the anchor angle.

### Browser card
Append the anchor angle to the second metadata line:

```
"Open Project" — 7c+ · 42 sends · 35°
```

### Per-angle stats list (the angle picker / detail-sheet rows)
Highlight the anchor row visually so it stands out from the
community-vote rows. Options:
- "Setter" tag in the row's right-edge metadata column
- Emphasised border or a subtle icon prefix
- Picked treatment must coexist with FEAT-009's
  confidence-indicator badge so a row can carry both without
  visual collision.

### Climb-creator
For climbs the user is **authoring** (FEAT-003), the anchor is
whatever angle the wall is at when they publish. No new UI needed
here today — but worth surfacing the chosen-anchor display once
this spec ships, so the creator sees what they are about to lock
in. (Tracked here, implementation deferred until creator-side
priorities allow.)

## 4. Default-angle behaviour

When the detail screen opens for a climb the user has not viewed
before:
- If there is a global user preference (Settings → preferred
  default angle), use it.
- Otherwise, **select the setter's anchor**.
- Today the implicit default is the highest-attempt angle, which
  reflects community popularity rather than setter intent.

The change is one-way: once the user manually picks any angle on
that climb, the picked angle becomes sticky for subsequent opens.

## 5. Implementation sketch

- ✅ Spike (§2): resolved 2026-05-06 — use Kilter's `climbs.angle`.
- **Schema migration** (`shared/src/commonMain/sqldelight/board/<n>.sqm`):
  `ALTER TABLE climbs ADD COLUMN angle INTEGER` (nullable; treat
  `0` and `null` as "unknown" client-side).
- **Server-side blossom-sync mapping**: extend the Kilter-API-harvest
  INSERT path to read `angle` from the `/api/climbs/curated[].angle`
  field and persist it on the new column. The next regular sync
  cycle picks up the ~5,000 curated-side values; older Aurora-era
  rows stay NULL.
- **FEAT-008 importer**: pass `KilterClimbDto.angle` straight
  through into `climbs.angle` on the local insert.
- **ClimbCreator publish path** (FEAT-003 + this spec): persist
  the wall-angle the user selected at publish time onto
  `climbs.angle` for newly-authored CruxCoach climbs.
- **`shared/`**: surface `angle: Int?` on `ClimbBrowseRow` via
  the `climb_browse` view.
- **Compose**: a small reusable `SetterAngleBadge` composable so
  the detail-header and browser card stay visually consistent.
  Renders only when `angle != null && angle > 0`; otherwise the
  badge slot collapses with no fallback.
- **Default-angle wiring**: `BoardClimbDetailViewModel` reads
  `climbs.angle` when no user-preference / per-climb override is
  set.
- **Maestro**: pick a community climb known to be set at 35°,
  assert badge displays 35°, assert the per-angle picker defaults
  to 35° on first open. Plus one Aurora-era climb to assert the
  badge is hidden when `angle == 0/null`.
- **JVM unit test**: assert the badge composable hides on `null`
  and on `0`, and renders for a sane in-range value (5–70 in 5°
  steps).

## 6. Open questions

- ✅ ~~Heuristic choice (spike outcome).~~ — resolved 2026-05-06
  in §2: use `climbs.angle` direct, no heuristic.
- ✅ ~~Coverage tradeoff: accept sparse ~3 % at ship, or add a
  "popular at X°" secondary signal from `argmax(ascensionist_count)`?~~
  — resolved 2026-05-06 in §2 "Scope decision": ship at sparse
  coverage, no fallback. Rationale: /curated is the
  high-visibility slice users actually browse, and a fallback
  would mix two semantically distinct signals in the same UI slot.
- Final visual treatment of the anchor row alongside FEAT-009's
  confidence/origin indicator.
- Whether the climb-creator should surface the in-flight anchor
  during authoring, or whether that lands in a follow-up.
- Localisation: "Set at 35°" — needs a German string that's not
  longer than the English (constrained by browser-card width).
  Candidates: "Gesetzt bei 35°", "Setter: 35°".
