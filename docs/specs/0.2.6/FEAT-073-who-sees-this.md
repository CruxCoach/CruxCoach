---
status: design-locked
queue: 0.2.6 on feat/permission-foundations
target: v0.2.6 (engine core, person audience over the direct Olm channel); v0.2.7 (mailbox, link invitations, privacy profiles, FEAT-063, friend leaderboards, public posts, research pilot); v0.2.8 (groups, team and public leaderboards, coach template, further categories)
base: feat/permission-foundations@0ef1a7868 (sharing), feat/0.2.5-release@80b3bf7d2 (app)
depends_on: [FEAT-062, FEAT-063, FEAT-025, FEAT-045, FEAT-058, "backlog/FEAT-043-competitions-and-leaderboards.md"]
created: 2026-10-25
decided: 2026-10-25
---

# Feature Spec: Who Sees This — audience levels, groups, public sharing, leaderboards and anonymous research

> **Accepted 2026-10-25.** The owner accepted the concept behind this spec and every recommendation of the joint
> decision list (transport, phases, templates, defaults, research rules) on 2026-10-25. §13 records those
> decisions. Background documents of the same day (the permission report with its independent review and the German
> concept) live outside the repository; everything this spec relies on is stated here.
>
> Owner request (2026-10-25): bring the permission system and public Nostr social media under one roof; maximum
> privacy must be possible; many ways to exchange information — privately between two people, in groups and
> publicly; some use cases need user data (for example the correlation of max hangs and pull-ups with board
> grades), done with transparent opt-in and as little data as possible, kept long term but strictly
> person-independent.
>
> **Reference note.** `FEAT-043` is ambiguous in the registry (see INDEX "Unresolved ID collisions"). This spec
> always means `docs/specs/backlog/FEAT-043-competitions-and-leaderboards.md` and cites it by path.

---

## 0. Scope and status

This spec defines **one audience model** for every piece of user data and the product rules for each audience:
only me, one person, a group, public, research. It reuses the existing sharing core — local unsigned policy,
fail-closed resolver, preview equals send, per-direction sync — and extends it.

Person-to-person transport is decided (§13, O16): an Olm channel per relationship, delivered directly when people
meet (0.2.6) and later also through an encrypted relay mailbox (0.2.7). MLS/Marmot is removed from the app; §4.6
records what would change if it came back after an audit.

Implementation starts in 0.2.6 on `feat/permission-foundations` (§11). §10 maps the model onto current code.

## 1. Goals and non-goals

### 1.1 Goals

- G1. Every category and every record answers "who sees this?" with the same model and the same UI.
- G2. The default for everything is **only me**. Public is always explicit and says that it cannot be taken back.
- G3. One resolver decides for persons, groups, public publications and studies, with visible reasons.
- G4. Health-related data can reach a trusted single person (coach, parent) but never a group or the public.
- G5. Friends and teams can compare themselves on leaderboards that are computed locally and never published.
- G6. Public leaderboards exist, built only from score cards the user chose to publish.
- G7. Research questions can be answered from anonymous, coarse, locally computed contributions that carry no
  identity and are stored only as a tally with a minimum cell size.
- G8. Metadata exposure per audience is documented, minimised and tested.

### 1.2 Non-goals

- Re-opening the transport choice (decided 2026-10-25: Olm; MLS only after an audit, §4.6).
- Chat. Messages between people are not part of this feature.
- Multi-device identity, device handover and backup of relationships (separate feature).
- Verified ascents. Self-reported stays self-reported (FEAT-043 §11).
- Local differential privacy, secure aggregation (DAP/Prio) and Oblivious HTTP in the first versions (§7.9).
- Paid services of any kind without an explicit owner decision.

## 2. Product contract

### 2.1 Audience levels

| Level | Audience | Reversible |
|---|---|---|
| `ONLY_ME` | this installation (and the user's own encrypted backup, if enabled) | – |
| `PERSON` | each selected person individually | cooperatively: the recipient's app deletes when it receives the end/narrowing message |
| `GROUP` | members of a private group; or an open (non-E2E) community | private: cooperatively per member; open: no |
| `PUBLIC` | anyone | **no** — deletion is a request to relays |
| `RESEARCH` | nobody individually; only an anonymous tally | **no** — contributions cannot be attributed, so they cannot be deleted individually |

`ONLY_ME < PERSON < GROUP < PUBLIC` is a ladder. `RESEARCH` is a separate axis and MUST be presented separately
("additionally contribute anonymously to research"). A record may have several audiences at once; its displayed
level is the widest one, and the UI lists all of them.

### 2.2 Category catalogue and ceilings

The catalogue is closed. Each category has a fixed field whitelist, a **ceiling** (highest level it may ever reach,
enforced in code) and the default `ONLY_ME`.

| Category id | Fields | Ceiling | Notes |
|---|---|---|---|
| `NOSTR_PROFILE` | name, picture, about (kind 0) | PUBLIC | public by nature on Nostr; disclosed when created |
| `CLIMBING_PROFILE` | max boulder/sport grade, years climbing, sessions per week, equipment, goals | PUBLIC | today's `PROFILE_AND_GOALS` |
| `SENDS` | climb id, name, board, angle, grade, **day** | PUBLIC (single publication or single-record grant only, never a standing public feed) | split from `TRAINING_HISTORY` |
| `ATTEMPTS` | climb id, attempt count, day | GROUP | split from `TRAINING_HISTORY` |
| `CLIMB_NOTES` | own note per climb | GROUP | today's `PRIVATE_NOTES` |
| `LEADERBOARD_VALUES` | score per season and division, qualifying count, ruleset version | PUBLIC | new; §6 |
| `TRAINING_SESSIONS` | workouts, exercises, sets, climbing days (day only) | GROUP | new |
| `WEEKLY_VOLUME` | per ISO week: climbing days, number of sends, number of attempts | GROUP | new; feeds the *Family* template; no single records |
| `BENCHMARKS` | max hang, one-rep pull-up and similar, as **% body weight**, edge, grip, day | PUBLIC (as % body weight and as publication only) | absolute load and % body weight MUST NOT both reach GROUP or PUBLIC for the same benchmark (body weight would be derivable) |
| `BODY` | weight, measurements | PERSON | health-related |
| `WELLBEING` | readiness, sleep, pain, sick | PERSON | health data |
| `INJURY_COMEBACK` | injuries, pauses, comeback diary | PERSON, time-limited only | health data |
| `NUTRITION` | meals, hydration | PERSON | |
| `PRESENCE` | hall affiliation, "at the hall today" | GROUP; hall affiliation public only through an explicit FEAT-045 action | home-board location never |

Never transmitted at any level: time of day, GPS or home-board location, device identifiers, free text to studies,
received data of other people, logbook comments and ratings (until a category defines them).

Health flags: `BODY`, `WELLBEING`, `INJURY_COMEBACK` are treated as GDPR Art. 9 data; `NUTRITION` and `BENCHMARKS`
as health-adjacent. A `PERSON` grant of a health category requires its own explicit confirmation and an expiry.

### 2.3 Precedence

For `(audience, category, record?)` the resolver returns allow/deny and the deciding source. Highest first:

1. **Fixed rules** — ceiling, never-fields, age gate (§2.7), received data → always deny.
2. **Record rule** for this audience — deny before allow; plus a global **never share this record** that applies
   to every audience.
3. **Audience rule** for this person or group — deny before allow; optionally with `expiresAt`.
4. **Template** of the person or the group (§2.4).
5. Otherwise deny.

An expired rule resolves as if absent. Expiry MUST trigger the same narrowing path as a manual withdrawal
(withdraw pending, new generation), so the recipient loses access without a user action.

`PUBLIC` and `RESEARCH` have **no standing rules**:

- `PUBLIC` is a **publication act** with a preview of the exact event. The only standing public flow is an opted-in
  public leaderboard (§6.3).
- `RESEARCH` is a **study participation** with consent per study and a preview per round. Studies never receive
  records, only derived, banded values (§7). Records marked "never share" are excluded from those derivations;
  no other record rules apply to studies.

### 2.4 Templates instead of circles

The two circles (`ACQUAINTANCES ≤ FRIENDS`, inherited baselines) become named **templates**, each with its own
category set, period (30/90/365 days/all/"from today") and optional default expiry. Inheritance between templates
does not exist. The circles never shipped in a release, so they are replaced in the unreleased schema and nothing is
migrated. Shipped templates (owner decision 2026-10-25, replacing the circle presets of 2026-09-23):

| Template | Grants | From |
|---|---|---|
| *Training partner* | `CLIMBING_PROFILE` + `SENDS` and `ATTEMPTS` of the last 30 days + `CLIMB_NOTES` | 0.2.6 |
| *Family* | `CLIMBING_PROFILE` + `WEEKLY_VOLUME`; no single sends | 0.2.6 (last, separable step) |
| *Coach* | as *Training partner* with 365 days; later `TRAINING_SESSIONS` and `BENCHMARKS`; health categories only ticked one by one, each with an expiry | 0.2.8 |

Own templates come later. Per-person rules adjust any template for one person.

Groups have a **group template** ("the crew sees: attempts, notes, leaderboard values"). Each member accepts it
for their own outgoing direction or narrows it; nobody can widen another member's direction.

### 2.5 Preview equals send

For every audience the preview is computed by the same function that produces the payload:

- person/group: the exact record list;
- public: the exact event (content, tags, media) as it will be signed;
- research: the exact plaintext record before encryption.

The reverse query "who sees this record?" iterates persons, groups, past publications and running studies and
lists every audience that has or will receive the record.

### 2.6 Bridges between levels

Only by explicit user action, each with a preview:

- public → private: find people from one's own public follow list and invite them privately;
- group → public: only the author may publish their own record; never another member's data;
- private → public: summary posts ("my week") and single-record publications;
- own data → research: study participation; derived only from the user's own data;
- person → group: inviting a person into a group sends nothing before they join.

Received data is stored apart (as today) and MUST NOT enter any outgoing projection.

### 2.7 Age gate

Self-declared age on first use of any level above `ONLY_ME`. Under 16 (GDPR Art. 8, Germany): `ONLY_ME` only; a
later version may allow a single confirmed parent person without health categories. Exact rules: decision O12.

## 3. Identities and privacy profiles

### 3.1 Identities

| Identity | Used for | Key | Default |
|---|---|---|---|
| public | profile, posts, follows, public leaderboards (scoped derived keys with a signed link, FEAT-043 §9), open communities | Amber or local signer | yes |
| private (optional, later) | relationships to persons and private groups | second Amber account | no — same npub |
| research | study submissions | **none**; a fresh random key per submission, never derived, discarded after sending | always |

With transport C nothing is published under the npub for private relationships (no key package, no relay lists);
the npub signs one binding per installation that only travels encrypted. A separate private identity therefore
mainly separates personas towards friends and is deferred (O13).

### 3.2 Privacy profiles

A profile sets many switches at once; every switch stays individually editable ("custom"). Profiles never change a
category's audience: everything remains `ONLY_ME` until granted.

| Setting | Maximal | Normal (default, O3) | Social |
|---|---|---|---|
| incoming requests | none; invite by QR or link only | invite by QR or link only | invite by QR or link only; requests to an npub only after their own design (§3.3) |
| discoverable as a CruxCoach user | no | no | yes (public marker) |
| public key package (only if MLS ever returns; with Olm there is none) | never | only on explicit request | yes |
| mailbox relays | user-chosen only | default pool | default pool |
| background Nostr (FEAT-063) | off — sync only while the app is open | short sharing runs only (at most every 15 min), otherwise off | on |
| batching/delay of outgoing sync | daily with random offset | 2–5 min | immediate |
| suggest public posts | never | never | after personal bests, always with preview |
| public leaderboards | not offered | opt-in per board | offered on qualification |
| friend/team leaderboards | off | on where leaderboard values are shared | on |
| show "last delivered" | no | yes | yes |
| studies | listed, no reminders | listed | listed with reminders |

Switching profile MUST show the resulting changes before applying them.

### 3.3 Invitations

- QR code when meeting (two-stage, as in the Olm spike; 0.2.6). The invitation package goes only to the device that
  proves knowledge of the QR tag and is encrypted with a key derived from it; unknown devices in reach receive
  nothing, so neighbours cannot learn who invites whom.
- Invitation link (0.2.7, needs the mailbox): carries only a one-time admission token and the mailbox address, never
  secrets; expiry (default 7 days), use limit (1/10/100 for groups), revocation; the issuing device confirms each
  admission.
- Requests to an npub are **deferred** (owner decision 2026-10-25). Through the NIP-17 inbox the app would have to
  read every gift wrap of the account (inbox relays SHOULD require NIP-42 AUTH with the npub for that) and decrypt
  each one through the signer, including other apps' private chats; a "only from accounts I follow" filter would
  work only after decryption, because the sender is inside the seal. A later design needs its own event kind and its
  own review. The 10050 list is never written.
- Nothing flows before acceptance. "Also share my data" on accept defaults to **off**.

### 3.4 Namespace

Every event CruxCoach sends for sharing uses CruxCoach-specific kinds and the label `cc.share.v3`; anything else
is rejected. If MLS is kept, groups and key packages MUST require a CruxCoach component in the private-use range
and invitations MUST only target key packages that carry it.

## 4. Transport per audience

### 4.1 Overview

| Level | Recommended transport | Encryption |
|---|---|---|
| ONLY_ME | none; backup: NIP-44 to self + Blossom (existing) | yes |
| PERSON | Olm channel per relationship; direct (LAN/BLE), then encrypted relay mailbox | Olm |
| GROUP, private | v1: pairwise channels (fan-out); v2: Megolm sessions distributed over Olm | Olm / Megolm |
| GROUP, open | NIP-29 relay-based group | **none end-to-end** |
| PUBLIC | signed Nostr events under the npub (kind 1, kind 30078 score cards, kind 0) | none |
| RESEARCH | HPKE to the study round key, carried by public relays under a throwaway key | HPKE (RFC 9180) |

### 4.2 Person

Decided (option C of the permission report): Olm frames delivered directly — LAN with NSD discovery and BLE, no
relay — in 0.2.6, then also via a mailbox in 0.2.7: the same frames as Nostr events signed by throwaway keys, addressed by tags rotating from the
relationship secret, with NIP-40 expiry, no public key package, no relay lists. CruxCoach's own relay is selectable but not part of the default mailbox
pool, so CruxCoach does not become controller of mailbox metadata by default. Every GROUP feature depends on the
mailbox.

### 4.3 Private groups

A group is: a random group id; an **admin-signed roster** (admins, members, roles such as read-only coach;
version, previous-version hash); and a group template. Roster updates are signed by an admin's npub and travel
only encrypted to members. Data is sent only to members of the latest valid roster; a member removed from the
roster receives nothing from the next send on.

Rationale: formal analysis of Olm/Megolm group messaging shows confidentiality and authentication hold only with
authenticated group membership (Albrecht, Dowling, Jones, IEEE S&P 2024); membership MUST NOT be controlled by a
relay or server.

| | v1 fan-out | v2 Megolm | MLS (only after an audit) |
|---|---|---|---|
| sending | n − 1 pairwise sends | 1 send + key share to n − 1 on rotation | 1 send |
| library | vodozemac (audited 2022) | vodozemac (audit covered Olm and Megolm) | MDK or a pure-Kotlin MLS library (current code unaudited) |
| FS / PCS | yes / yes | ratchet forward; healing by rotation on every removal and at least weekly | yes / yes with self-updates |
| size limit | ~12–20 | ~50 | large |

Decided (O2): v1 fan-out (reuses Olm, sync and per-person preview), v2 Megolm behind the same group
interface. A coach with many athletes is usually many PERSON relationships, not a group.

New members see group data **from joining**; history only if a member explicitly hands over their own history.

### 4.4 Open communities

NIP-29 groups (relay-enforced membership; `private`, `restricted`, `hidden`, `closed` flags). Content is readable
by the relay operator and, depending on flags, by anyone. The UI MUST label them "open — not encrypted". The
ceilings exclude `ATTEMPTS`, `CLIMB_NOTES`, `TRAINING_SESSIONS`, health categories and `PRESENCE` from open
communities. NIP-72 is marked unrecommended upstream (NIPs @ `d47a2ec`, 2026-10-22) and MUST NOT be used. Data
model: FEAT-045.

### 4.5 Public formats

| Content | Format | Cadence | Removal |
|---|---|---|---|
| profile | kind 0 | on change | replace |
| post, summary, personal best | kind 1 with CruxCoach label, optional Blossom image | one act with preview | NIP-09 request, stated as not guaranteed |
| public leaderboard score card | kind 30078 per season/division (FEAT-043 §8.1), scoped derived key linked to the npub | ≤ 1 per 24 h, random delay 12–36 h | FEAT-043 §9.3 opt-out contract |
| fitness interop (kind 1301, NIP-101e) | deferred until the NIP is merged with a privacy section | – | – |

Public events MUST NOT carry time of day derived from training records, location other than an explicitly chosen
hall, or body weight (directly or derivable).

### 4.6 If MLS/Marmot returns after an audit

Audience model, engine, categories, leaderboards and research are unchanged. PERSON becomes a two-member MLS group,
GROUP an MLS group; privacy profiles map to key package publication (Maximal: never; Normal: on explicit request;
Social: published); the namespace rule in §3.4 becomes mandatory; the open findings of the 2026-10-25 review of
the MLS host (no forward secrecy or post-compromise security in practice, invitation blocking, unaudited engine
code, missing upstream security fixes) must be closed first.

## 5. Metadata requirements

| Level / path | Relay operator learns | Recipient learns | CruxCoach as operator learns |
|---|---|---|---|
| backup | npub, size, time, IP | – | same, only if its own relay/Blossom is used |
| PERSON direct | – (LAN neighbours: service name, size, time) | content | nothing |
| PERSON mailbox | IP, time, size bucket, rotating tag, throwaway key; **no npub** | content | same as relay if its relay is in the pool |
| GROUP private | as mailbox; group size from patterns | content + roster | as above |
| GROUP open | everything | per flags | everything if it runs the relay |
| PUBLIC | IP on publish | everything public | – |
| RESEARCH | IP, time, fixed size, throwaway key, ciphertext | – | content after round close; **no IP, no npub, no time finer than the round** |

Requirements:

- M1. No relay event and no request reveals both identities of a relationship (automated test for every format).
- M2. Mailbox and research events never carry the npub, a `p` tag of the npub, or NIP-42 AUTH with the npub.
- M3. Outgoing sync is batched per privacy profile; research submissions are delayed 0–72 h at random.
- M4. Payloads are padded to size buckets; research submissions to one fixed size.
- M5. Ephemeral content carries NIP-40 expiry.
- M6. No push server; sync in the foreground and in short background runs only (FEAT-063 governs background).
- M7. The app shows a "what servers learn" card matching this table.

## 6. Leaderboards

### 6.1 Principle

Leaderboards are **views** over data the viewer is already allowed to see, not an audience level.

| Board | Source | Computed | Consent |
|---|---|---|---|
| friends | `LEADERBOARD_VALUES` shared with me by persons | locally on my device | each person grants the category to me |
| team / group | `LEADERBOARD_VALUES` shared into the group | locally on each member's device | each member |
| public (global) | public score cards | locally on any reader | opt-in per board |
| hall | public score cards with a canonical hall id | locally | separate opt-in per hall |

Friend and team boards MUST NOT produce any published event. Different members may see slightly different friend
boards; that is correct.

### 6.2 Score model

Adopt `docs/specs/backlog/FEAT-043-competitions-and-leaderboards.md` §4–7: sum of the ten hardest distinct
successful climbs (`(ecosystem, climb id, angle)`) per season and division; seasons are calendar quarters plus
all-time; closed quarters frozen; ranking from 10 qualifying sends; community climbs count publicly only with
FEAT-025's five independent claimed ascensionists.

Optional strength boards (O11): finger strength and pull-up as % body weight, friends/teams only.

### 6.3 Public participation

Per-board opt-in with a preview of the exact score card; cadence and the full opt-out contract of FEAT-043 §8.3
and §9.3 (generation counter, tombstones, NIP-09/NIP-62, "disabled locally" vs "withdrawal delivered").

### 6.4 Plausibility and trust

- Every public board is labelled "self-reported".
- Local plausibility rules flag (not delete): grade jumps within a week, sends per day, flash ratio, unknown or
  tombstoned climbs, ruleset mismatch.
- Public boards default to the viewer's extended follow graph; "everyone" is a toggle; mute/report via NIP-51.
- A later "confirmed" class may consume competition results (FEAT-058), which are authority-signed and
  reproducible by every client.

## 7. Research

### 7.1 Study definition

A study is a signed document (owner npub via Amber; the verifying key is pinned in the app, as for the catalogue
manifest), delivered through the signed data channel. Fields:

```
id, version, title{en,de}, question{en,de}, controller, contact,
variables[ { name, derivation, bands[], generalisation_steps[] } ],
k (default 20), rounds[ { id: "2027Q1", opens, closes, hpke_public_key } ],
eligibility { min_age: 16, min_sends_90d: 10, requires: [...] },
storage { form: "tally",       // later also "prio_sums"
          tables: [ ["grade","finger"], ["grade","pull"] ],
          stages: [ { min_n: 500, split_by: "board" }, { min_n: 2000, tables: [["board","grade","finger","pull"]] } ] },
publication { epsilon, license: "CC-BY-4.0", results_url },
retention: "unlimited for the tally; submissions deleted after round processing",
assessment_url                  // anonymisation assessment for this study version
```

The app MUST NOT show or accept a study without a valid signature.

### 7.2 Pilot study (decided, O7): finger strength, pulling strength and board grade

| Variable | Local derivation | Start bands |
|---|---|---|
| board | Kilter or Moon (more frequent in the last 90 days) | 2 (pooled if needed) |
| board grade | median of the three hardest sends in the last 90 days on that board (unified scale) | 3 |
| finger strength | best max hang (20 mm, ~10 s, both hands) in the quarter, % body weight | 3 |
| pulling strength | best one-rep pull-up in the quarter, % body weight | 3 |
| round | calendar quarter | – |

Excluded on purpose: age, sex, weight, height, hall, years climbing, free text, injuries. Changes over time (for
example finger-strength gain in the quarter) are computed locally and sent as their own band; rounds are never
linked.

Stored tables (declared in the study definition as `storage.tables`): at start two 3 × 3 tables with boards pooled,
*grade × finger* and *grade × pull*; split by board from ~500 accepted submissions per round; one joint table of all
four variables from ~2,000. The full submission is never stored.

### 7.3 Client flow

1. Consent in the study centre (EN/DE text written with the pilot and reviewed with the legal check, L3); stored
   locally only.
2. At round close: derive values from the user's own data only; band them; run
   `GradeStrengthNorms.plausibility`; skip and explain if implausible or ineligible.
3. Preview the exact record; one tap "send" or "skip this round" (O8).
4. Encrypt with HPKE (mode base, DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, ChaCha20-Poly1305) to the round key;
   pad the plaintext to a fixed length.
5. Wrap in a Nostr event: kind reserved for CruxCoach study submissions (to be allocated), `pubkey` = fresh random
   key, tags `["l","cc.study.v1"]`, `["s", H(study id ‖ round id)]`, NIP-40 `expiration` = round close + 30 days,
   optional NIP-13 proof of work (~1 s on a phone); no `p` tag, no client tag.
6. Publish after a random delay of 0–72 h to 2–3 widely used third-party relays (not a CruxCoach relay); forget the
   key; remember locally "round sent" (at most one submission per study round per installation).
7. Withdrawal stops all further submissions immediately.

Plaintext (before padding):

```json
{"v":1,"study":"finger-pull-grade","studyVersion":1,"round":"2027Q1",
 "bands":{"board":"kilter","grade":1,"finger":2,"pull":0}}
```

Unknown fields are a schema error on both sides.

### 7.4 Collector

A small service next to the existing anonymous counters, deployed only on explicit owner go (O14):

- reads submissions for a round only **after** the round closes; decrypts **in memory only**;
- validates against the closed schema of the study version; drops duplicates (event id), invalid and implausible
  submissions; never sees an IP address (relays do) and never stores one;
- builds the tally and applies the storage rule (§7.5);
- persists only: the tally, the generalisation step used, totals (received, accepted, suppressed, rejected), study
  version, round id;
- destroys the round's private HPKE key after processing (this also protects ciphertexts still held by relays);
- has no per-submission table, no raw-event log and no request logging (same discipline as the existing
  `anonymous_analytics.py`).

### 7.5 Storage rule

Applied to each table declared in `storage.tables`:

1. Count combinations of the table's variables.
2. If cells below `k` exist, **globally** recode the whole table along the study's predefined generalisation steps
   (merge adjacent bands → pool boards → drop a variable) until at least 95 % of accepted submissions fall into
   cells ≥ `k`. Never recode single cells (the recoding pattern would itself leak).
3. Suppress the remaining cells < `k`; store only their total.
4. Every marginal that is published MUST also be ≥ `k`.

Simulation (concept annex, assumptions in the script headers), share of submissions landing in cells ≥ 20:

| participants per round | joint 54 cells | joint, boards pooled (27) | two 3 × 3 tables, pooled | two tables per board (2 × 9) |
|---|---|---|---|---|
| 200 | 15 % | 39 % | 79 % | 40 % |
| 500 | 54 % | 81 % | 96 % | 86 % |
| 2,000 | 92 % | 97 % | 99 % | 98 % |

Hence the pilot starts with two pooled 3 × 3 tables.

### 7.6 Publication and use

- Published tables add Laplace noise per cell (ε documented per release; ε = 1 gives ± 1.4 per cell); analysis
  code is published; licence CC BY 4.0 (O10).
- Results feed `GradeStrengthNorms` as data-derived bands per board once a round has ≥ 300 accepted submissions
  per board, delivered through the signed data channel with a visible source line.

### 7.7 Anonymity assessment

Per study version, a written assessment following EDPB Guidelines 02/2026 (draft, adopted 2026-07-07 for
consultation): relevant entities (collector, relay operators, public), the three criteria (no record isolation, no
linkage, no inference), the means reasonably likely to be used, and the residual risks. Reassessed at least yearly
and after any security incident. During a round, decrypted submissions are treated as personal data (consent,
encryption until round close, deletion right after processing); only the stored tally is claimed anonymous, and
copy MUST NOT call the submission phase "anonymous".

### 7.8 Abuse

Duplicate or fake submissions cannot be fully prevented without identity. Mitigations: one submission per round
per honest client, proof of work, plausibility on both sides, robust statistics (medians, quantiles), alerts on
implausible jumps between rounds. Anonymous tokens (Privacy Pass, RFC 9576–9578) are a later option if abuse is
observed; they reveal to the issuer *who* participates, never *what*.

### 7.9 Deferred options

| Option | Why deferred | Revisit when |
|---|---|---|
| secure aggregation (DAP/Prio3) | drafts (DAP-20 of 2026-10-21, VDAF-22 of 2026-08-14); needs two non-colluding operators; hosted service only as a paid pilot on request; sums only | health/body studies, thousands of participants, or a free independent helper |
| local differential privacy | at our scale the signal disappears (simulated r ≈ 0.8 estimated as 0.05 ± 0.92 at n = 500, ε = 1) | tens of thousands of participants |
| Oblivious HTTP | commercial relays are enterprise-only/closed beta | an independent partner operates a relay |
| Tor (Arti) | young mobile bindings, size, battery | stable mobile Arti |

The submission format keeps a `storage` field so Prio shares can be added later without breaking clients.

## 8. Legal requirements (summary, not legal advice; external review before the pilot, L3)

- L1. Person-to-person sharing is mostly household activity for users; CruxCoach is controller wherever it operates
  infrastructure (relay in the default pool, Blossom, collector).
- L2. Research consent: Art. 6(1)(a) plus explicit consent under Art. 9(2)(a) as a precaution; covers the
  anonymisation (EDPB 02/2026 §38); withdrawal information before consent; the "cannot be deleted individually"
  statement before consent; age ≥ 16; no feature is conditional on participation.
- L3. Information under Art. 13 in the study centre and on the website; record of processing (Art. 30); short DPIA;
  per-study anonymity assessment; external legal review before the pilot (O9).
- L4. The research privilege (Art. 89, Art. 9(2)(j), § 27 BDSG) is not relied on. The Digital Omnibus proposal
  (procedure 2025/0360(COD)) is not in force and is not relied on.
- L5. A professional coach receiving health data is controller for it; the coach template says so.

## 9. UX requirements

- U1. A "who sees this?" line on every category and record; tapping opens the audience sheet (levels, persons,
  groups, single publication, research note, "never share this record", "preview: what does X see?").
- U2. An overview matrix (categories × audiences) with reasons for every locked cell, a local sharing history, and
  the metadata card (M7).
- U3. Privacy profile picker with "what happens / what does not happen" per profile and a change preview.
- U4. Study centre: study card (question, controller, signature state, sent/never-sent fields, k, why not
  deletable), consent, local list of own past submissions, results page.
- U5. Leaderboards: tabs friends · teams · public; season and division; "visible to: …"; self-reported and
  plausibility badges; qualification progress.
- U6. Every irreversible action (public, research) states irreversibility on the confirming screen, not in help
  text.
- U7. All strings EN and DE.

## 10. Mapping to current code

| Area | Today | Change |
|---|---|---|
| resolver | `SharingPolicyResolver`, `SharingModel.kt` (2 circles, 3 categories, `DecisionSource`) | audience type, ceilings, never-share, expiry, templates, new categories, reverse query; new `DecisionSource` values (`CEILING`, `NEVER_FIELD`, `AGE_GATE`, `RECEIVED_DATA`, `NEVER_SHARE_RECORD`, `TEMPLATE`, `EXPIRED`) |
| preview/sync | `SharingService.preview` → `SharingStore.selected`; manifest/full/delta/ack/resync/end | reused for groups (one writer per member and group); encrypted initial snapshot needed for > 1,000 records |
| transport | MLS host wired, Olm host built; the seam is `SharingHost`/`MarmotBinding` | 0.2.6: Olm wired, MLS removed; 0.2.7: mailbox; 0.2.8: group roster |
| public | kind 0 (`NostrProfileManager`), community climbs kind 30078 | posts with preview; score cards |
| background | FEAT-063 planned, not implemented | prerequisite for privacy profiles |
| health data | AthleteDB; export/backup opt-in category for injuries/pauses/comeback | same split feeds sharing categories |
| research | none; pattern: `AnonymousUpdateMetrics.kt`, `cruxcoach-dlstats/anonymous_analytics.py` | study centre, submission, collector |
| norms | `GradeStrengthNorms` (literature line, plausibility) | plausibility gate; data-derived bands |

Domain sketch (shape only, not final code):

```kotlin
sealed interface Audience {
    data class Person(val peer: PeerId) : Audience
    data class Group(val group: GroupId) : Audience
    data object Public : Audience
    data class Study(val study: StudyId) : Audience
}

enum class AudienceLevel(val rank: Int) { ONLY_ME(0), PERSON(1), GROUP(2), PUBLIC(3) } // RESEARCH is separate

enum class ShareCategory(val ceiling: AudienceLevel, val health: HealthClass) { /* §2.2 */ }

data class AudienceRule(val effect: AccessEffect, val expiresAt: Instant? = null)

data class Template(
    val id: TemplateId,
    val name: String,
    val grants: Map<ShareCategory, Period>,
    val defaultExpiry: Duration? = null,
)
```

## 11. Phases

Decided 2026-10-25 (O17). Nothing of this feature ships in 0.2.5.

| Version | Content |
|---|---|
| 0.2.6 (test version behind the debug preview switch) | Stage 1 transport: direct Olm (QR invitations, LAN/BLE), MLS host removed · engine v3 core: audience model, category catalogue with ceilings (only existing sources connected), `SENDS`/`ATTEMPTS` split, templates per §2.4, never-share, expiry that travels with the data, local sharing history, age gate, "also share my data" off · "who sees this" line, audience sheet and overview for persons |
| 0.2.7 | mailbox (stage 2) · link invitations · privacy profiles · FEAT-063 · `LEADERBOARD_VALUES` and friend boards · public posts with preview · research pilot (study centre, submission, collector after owner go; protocol, assessment, DPIA, consent text and legal review first) |
| 0.2.8 | groups v1 (roster, template, fan-out); team boards; coach template; `TRAINING_SESSIONS`, `BENCHMARKS` and health categories (single person, explicit confirmation, mandatory expiry); public boards per FEAT-043; first data-derived norms |
| later | Megolm; NIP-29 communities (FEAT-045); separate identities (0.2.9+); multi-device and device handover; encrypted friend list in the backup; confirmed boards from competitions; seasonal aliases; re-evaluate DAP/Prio, OHTTP, Tor |

An external security review of the Olm embedding and of every remote session set-up (mailbox, link, groups)
precedes any stable release of the respective stage; commissioning it needs a separate owner go with provider and
budget.

## 12. Acceptance criteria

| # | Criterion |
|---|---|
| AC-1 | On a fresh install every category resolves to deny for every audience. |
| AC-2 | No combination of rules lets a category exceed its ceiling (property test over all audiences). |
| AC-3 | "Never share this record" denies it for every audience, including running studies' derivations where the record is an input. |
| AC-4 | Received records never appear in any outgoing projection (person, group, public, study). |
| AC-5 | For every audience type the preview and the transmitted payload come from the same function and are equal. |
| AC-6 | An expired rule resolves as absent and triggers the narrowing path at expiry, without user action. |
| AC-7 | The shipped templates grant exactly what §2.4 lists (golden test); no circle data is migrated. |
| AC-8 | Nothing is published without a confirmed preview of the exact event, except score cards of an opted-in public board. |
| AC-9 | No public event carries a time of day derived from a training record; score cards are delayed 12–36 h. |
| AC-10 | Absolute load and % body weight of the same benchmark never both reach GROUP or PUBLIC. |
| AC-11 | Health categories can never reach GROUP or PUBLIC; a PERSON grant requires explicit confirmation and an expiry. |
| AC-12 | Below the age gate only ONLY_ME is available. |
| AC-13 | No relay event reveals both identities of a relationship; mailbox and research events carry no npub, no npub `p` tag, no AUTH with the npub. |
| AC-14 | Group data reaches only members of the latest valid admin-signed roster; after removal the next send excludes the removed member (and, with Megolm, rotates the session). |
| AC-15 | Open communities are labelled "open — not encrypted" and the ceilings exclude non-public categories from them. |
| AC-16 | Friend and team leaderboards publish no event. |
| AC-17 | Public board opt-out follows FEAT-043 §9.3, including "disabled locally" vs "withdrawal delivered". |
| AC-18 | A study submission contains exactly the study version's fields, has a fixed length and fails closed on schema mismatch on both client and collector. |
| AC-19 | Each submission uses a fresh random key that is not derivable from any stored secret and is not persisted after sending. |
| AC-20 | At most one submission per study round per installation; implausible or ineligible values are not sent and the user is told why. |
| AC-21 | No submission without a valid signed study and a locally recorded consent; withdrawal stops submissions immediately. |
| AC-22 | The collector persists no per-submission data; every stored cell and every published marginal is ≥ k; the round key is destroyed after processing (fixture test). |
| AC-23 | Published tables carry Laplace noise with the ε stated in the study. |
| AC-24 | Switching privacy profile previews the changes and never changes category audiences. |
| AC-25 | Every new string exists in EN and DE with matching format arguments. |
| AC-26 | An invitation package reaches only a device that proves the QR tag; no frame of the direct channel reveals both identities of a relationship or the binding in plaintext. |

## 13. Decisions (owner, 2026-10-25)

All recommendations of the joint decision list were accepted ("everything as recommended").

| # | Decision |
|---|---|
| O1 | Five levels, closed category catalogue with ceilings (health-related data at most to one person, never to a group or the public), expiry, never-share. |
| O2 | Groups first over pairwise channels, later Megolm; MLS/Marmot only after an audit. |
| O3 | Default privacy profile *Normal*, at first without requests to an npub (QR and link only, §3.3). |
| O4 | Public formats: kind 1 posts, kind 30078 score cards, own label; kind 1301 only once its NIP is merged with a privacy section. |
| O5 | Research submissions over public relays under a throwaway key, HPKE-encrypted; CruxCoach sees no IP address. |
| O6 | Research storage: tally per generalised combination, k = 20, round = quarter, kept without limit, yearly reassessment, never single records. |
| O7 | Pilot "finger strength, pulling strength and board grade" with four variables as two 3 × 3 tables, without age, sex or weight. |
| O8 | One confirming tap per study round. |
| O9 | Short external legal review before the pilot; costs need a separate owner go. |
| O10 | Results as open data: noisy tables (ε = 1) plus analysis code, CC BY 4.0. |
| O11 | Score model of `backlog/FEAT-043-competitions-and-leaderboards.md`; strength boards (% body weight) for friends and teams only. |
| O12 | Everything above "only me" from 16 (self-declared); under 16 later only with a confirmed parent person. |
| O13 | One npub at first; separate identities from 0.2.9 on. One device per account; relationships not in the backup for now. |
| O14 | Collector on the production server next to the existing anonymous counters, deployed only after an owner go. |
| O15 | This spec lives in `docs/specs/0.2.6/` with an INDEX entry. |
| O16 | Transport: Olm channel, direct first, then an encrypted relay mailbox (global switch, on in *Normal*); CruxCoach's own relay selectable, not in the default pool; MLS/Marmot removed from the app. |
| O17 | Phases as in §11; the first test version is "only when you meet" and aimed at climbing partners. |
| O18 | Templates as in §2.4; "also share my data" on accept defaults to off. |

Still open: whether the *Family* template ships in 0.2.6 or later (it is the last, separable step of 0.2.6).

## 14. References

- Permission report and its independent review, German concept "Wer sieht das?" (all 2026-10-25, kept outside the
  repository).
- `docs/specs/0.2.3/FEAT-062-personal-information-sharing.md`, `FEAT-058-competitions.md`,
  `FEAT-063-nostr-background-privacy-control.md`; `docs/specs/backlog/FEAT-043-competitions-and-leaderboards.md`,
  `FEAT-025-community-send-count.md`, `FEAT-045-hall-directory-communities-and-handover.md`;
  `docs/architecture/marmot-permissions-v2-target.md`.
- EDPB, Guidelines 02/2026 on Anonymisation, v1.0, adopted 2026-07-07 for public consultation (until 2026-10-30).
- CJEU C-413/23 P EDPS v SRB (2025-09-04); C-582/14 Breyer; C-184/20; C-21/23; C-252/21.
- draft-ietf-ppm-dap-20 (2026-10-21); draft-irtf-cfrg-vdaf-22 (2026-08-14); RFC 9180 (HPKE); RFC 9458 (Oblivious
  HTTP); RFC 9576–9578 (Privacy Pass).
- M. Albrecht, B. Dowling, D. Jones, "Device-Oriented Group Messaging: A Formal Cryptographic Analysis of Matrix'
  Core", IEEE S&P 2024 (ePrint 2023/1300); Least Authority audit of vodozemac (Olm and Megolm), 2022.
- NIPs @ `d47a2ec` (2026-10-22): 09, 13, 17, 29, 40, 44, 51, 59, 62, 72 (unrecommended), 78.
