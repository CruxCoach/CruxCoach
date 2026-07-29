---
status: backlog
---
# Feature Spec: Competitions & Leaderboards (backlog - vision-tier)

> **Status:** Backlog - captured 2026-06-24, leaderboard decisions
> revised 2026-07-15. No release target.
>
> **Hard constraints:** Nostr is the backend; there is no
> CruxCoach-owned ranking server. Raw ascent logs never become public
> leaderboard inputs. Visibility beyond acquaintances is opt-in.
>
> **Depends on:**
> - The encrypted acquaintance/friend social layer.
> - FEAT-009 (Difficulty Rating Engine) for canonical community grades.
> - FEAT-025 (Community Send Count) for privacy-preserving distinct
>   claimed-ascensionist counts.
> - FEAT-045 (Hall Directory and Communities) for canonical `hall_id`
>   identity and merge/closure semantics.
>
> **Relates to:**
> - FEAT-014 (Live Training Coordination via Nostr).
> - FEAT-040 (Training Plan Engine), which consumes the same private
>   performance data but does not publish it.

---

## 1. Feature ownership

This spec owns leaderboard scopes, eligibility, scoring, publication,
ranking, and privacy UX.

It does **not** own the underlying community send count. FEAT-025
produces a provenance-preserving count and validation state. This spec
decides whether that result is sufficient to admit a community climb
into a public score.

It also does not own community-grade calculation. FEAT-009 produces the
canonical grade and confidence information consumed here.

Competitions share some display and scoring primitives but are a
separate product track. Before competition implementation is scheduled,
its organiser workflow and authoritative judging protocol should be
split into a dedicated spec rather than expanding the leaderboard MVP.

## 2. Relationship scopes

CruxCoach has one mutual connection model with two explicit sharing levels:

- **Acquaintance:** both peers accepted the connection. Each sees
  aggregate/list-oriented information, including an encrypted leaderboard
  score, but no private training details.
- **Friend:** both peers also accepted the higher sharing level. Friends keep
  the aggregate feed and additionally receive detailed logs and training times
  under the separate sharing policy.

The aggregate feed may bootstrap a newly confirmed connection with a current
all-time snapshot. The friend detail feed is prospective by default: its first
shareable detail is created at or after the timestamp of mutual friend
confirmation. Upgrading must not silently backfill earlier sessions, training
times, attempts, or notes. Explicit historical detail sharing is deferred to a
separate feature and is not part of the initial upgrade flow.

Friend and acquaintance leaderboards are viewer-relative sets. They are
computed locally and never published as group events. A public event
must not disclose membership of either set.

A friend upgrade requires both peers to accept. Either peer may unilaterally
downgrade the connection to acquaintance; this rotates only the outgoing
detail-feed epoch and retains aggregate access. Either peer may remove the
connection completely; this rotates the outgoing aggregate feed and, for a
former friend, the detail feed as well. Expiring grants and periodic epoch
rotation bound the stale-access window when a peer is offline or misses the
revoke message.

## 3. Leaderboard scopes and consent

| Scope | Data path | Default |
|---|---|---|
| Friends | Encrypted Nostr social feed; rank locally | Available inside enabled social sharing |
| Acquaintances | Encrypted aggregate score card; rank locally | Available inside enabled social sharing |
| Global | Public minimal Nostr score card | Off; explicit opt-in |
| Gym | Public minimal Nostr score card for one canonical gym | Off; explicit opt-in per gym |

Keeping a private logbook never publishes a score. Global opt-in never
implies hall participation, and detecting a provider `gym_uuid` never silently
creates a FEAT-045 `hall_id`, joins a hall community, or joins a hall
leaderboard.

The MVP exposes one two-state public-participation setting:

| Mode | Public behavior |
|---|---|
| **Do not participate** | Publish no global or gym score cards. This is the default. |
| **Use Nostr profile** | Deliberately and publicly link leaderboard identities to the user's main Nostr profile. |

The selected mode applies to all public leaderboard surfaces. Gym
participation still requires a separate opt-in for every gym, because
it discloses a location association. Friend and acquaintance
leaderboards are private scopes and are not disabled by choosing `Do not
participate` here.

Seasonal aliases are deliberately deferred. The MVP has no alias UI,
alias score cards, alias-to-profile migration, or stable pseudonymous
all-time identity.

Opt-out follows the explicit withdrawal contract in Section 9.3.

## 4. Locked score model

A user has no permanent universal rating. A score is calculated for a
specific:

```text
season + board division/roll-up + leaderboard scope
```

The score is the sum of the canonical difficulty values of the user's
**ten hardest distinct successful climbs** in that selection.

- Difficulty is the only scoring input.
- No flash, attempt, repeat, quality, volume, consistency, session, or
  training-time bonus or penalty exists.
- Repeating the same climb at the same angle never adds another slot.
- The distinct key is `(board ecosystem, climb ID, angle)`.
- Compatible physical board sizes do not duplicate the same
  climb/angle.
- Different angles are distinct because difficulty and physical
  performance differ.
- A user is not ranked or publicly published until ten eligible
  distinct sends exist for that exact selection. Before then the UI
  shows local qualification progress, for example `7/10`.

The wire-format score uses the canonical continuous grade index, not a
locale-specific Font/V-scale label. Display conversion is local.

## 5. Board hierarchy and roll-ups

Divisions use stable catalogue identifiers, not display names:

```text
ecosystem -> product family -> layout/hold set -> physical size -> angle
```

For Kilter this distinguishes at least Original and Homewall, their
actual layouts/sizes, and angle. For MoonBoard the equivalent hierarchy
includes the hold-set/year and its supported physical configuration.

The UI supports both exact and aggregate views, for example:

- all Kilter boards;
- all Kilter Original boards;
- all Kilter Homewalls;
- Kilter Original, 12x12 with kickboard, 40 degrees.

An aggregate score is recomputed from the union of eligible sends and
then takes the ten hardest distinct entries. Child-division scores are
never added together, because that would reward access to more boards.

## 6. Time windows and grade changes

- Main rankings use calendar quarters: Jan-Mar, Apr-Jun, Jul-Sep, and
  Oct-Dec.
- A separate all-time leaderboard exists.
- An active quarter uses the current canonical difficulty so the same
  climb counts equally for all participants.
- At quarter close, its score inputs and standings are frozen.
- The all-time leaderboard continues to recompute using current
  canonical difficulties.

Season and ruleset identifiers are explicit in every score card. A
ruleset change creates a new version; clients never silently compare
incompatible scores.

## 7. Climb eligibility

### 7.1 Official catalogue climbs

An official catalogue climb is eligible when it has a resolvable
canonical ID, exact board/angle classification, and canonical
difficulty.

### 7.2 Community climbs

A community climb affects a **public** global or gym score only when:

- FEAT-025 reports at least **five valid independent claimed
  ascensionists** for the exact `(ecosystem, climb ID, angle)`;
- FEAT-009 provides a canonical community difficulty from eligible
  evidence;
- the public climb definition is resolvable and not tombstoned; and
- the client supports the count/proof, grade, and leaderboard ruleset
  versions.

Four claims do not qualify the climb; five do. Missing or unverifiable
evidence fails closed. Before qualification, the climb remains valid in
the private logbook, activity feed, and encrypted friend/acquaintance
comparisons but contributes no public score.

Private drafts and locally invented grades never affect public scores.

## 8. Nostr data model

### 8.1 Public score cards

Global and gym participation publishes one minimal addressable
NIP-78/Kind-30078 score card per participant, season, scope, and
division/roll-up. It contains only data needed to reproduce ordering:

```text
schema version
scope and canonical scope ID
season
ruleset hash
division/roll-up ID
exact numeric score
proof/attestation class
public display alias/profile fields allowed by the identity policy
```

It never contains climb IDs, grade lists, exact timestamps, session
counts, ascent counts, attempts, comments, training duration, or raw
logs.

All clients query current addressable events from configured relays,
validate ruleset compatibility, deduplicate events, and sort locally.
An optional NIP-85-style assertion may cache a ranking but is never a
required or authoritative CruxCoach service.

### 8.2 Private score cards

All mutual connections receive score cards through the encrypted aggregate
social feed. Friends receive a separate detail feed in addition, but ranking
does not depend on it. Neither path creates a public event or exposes the
relationship graph.

An aggregate score card may cover the full eligible logbook history. Receiving
that all-time aggregate never grants access to the underlying historical
details. This distinction must remain true after acquaintance-to-friend
upgrades and friend-to-acquaintance downgrades.

### 8.3 Publication cadence

- Local score calculation may run immediately after log changes.
- A changed public score card is published at most once per rolling 24
  hours and only after a random delay of 12-36 hours. Multiple changes
  in that window collapse into one replacement event.
- An unchanged score never creates a new event.
- A final quarter card is published after the quarter closes and its
  inputs are frozen.
- Opt-out/withdrawal events bypass the delay and enter the delivery
  queue immediately.
- Private friend/acquaintance delivery has its own encrypted-feed
  cadence and is not constrained by the public delay.

## 9. Public identity and key management

Public leaderboard keys are deterministically derived from the single
backed-up `social_root`; no additional mnemonic or per-leaderboard
secret backup is required.

### 9.1 MVP: Nostr profile mode

- Score cards continue to use scoped derived leaderboard keys rather
  than repeatedly using the main signing key.
- The main Nostr identity signs an explicit link statement for each
  active public leaderboard identity. This supports external signers
  such as Amber without requiring approval for every score update.
- Profile name, picture, and verification information are resolved from
  the linked main profile.
- Global, all-time, and opted-in gym participation are deliberately
  attributable to that profile.
- Quarterly keys are separated by season; gym keys are additionally
  separated by canonical gym ID. The all-time board uses one stable
  derived key for the current participation generation.

### 9.2 Deferred: seasonal alias mode

Seasonal aliases remain a possible later privacy mode, not an MVP
setting. A future design needs independent quarterly keys/aliases and a
separate stable pseudonymous all-time identity. It also needs an
explicit migration policy because moving from a previously linked Nostr
profile cannot erase the historical public link.

### 9.3 Opt-out contract

Selecting `Do not participate` has the following meaning:

1. Local public participation is disabled immediately. Scheduled score
   jobs are cancelled, and every publisher checks the active
   participation generation before signing so an in-flight stale job
   cannot republish a score.
2. The app replaces every known score card from the current generation
   - active quarter, closed quarters, all-time, global, and joined gyms
   - with an addressable tombstone at the same Kind-30078 address. The
   tombstone contains no score or profile fields.
3. Each scoped leaderboard key publishes NIP-09 deletion requests for
   its prior score-card addresses/event IDs to all known publication
   relays. Where supported, it also sends NIP-62 relay-specific vanish
   requests because those scoped keys have no non-leaderboard purpose.
4. Main-profile link statements are replaced with withdrawn/revoked
   state and receive their own deletion requests. CruxCoach clients
   treat that withdrawal as authoritative even if an older score event
   is later replayed from another relay.
5. If the device is offline, local opt-out still takes effect
   immediately. Tombstones and deletion requests remain in the durable
   delivery queue until relay acknowledgement; the UI distinguishes
   `disabled locally` from `withdrawal delivered`.
6. The encrypted backup retains a minimal participation manifest and
   revocation state (generation, scopes, keys/addresses, relays, and
   delivery status). Restore must never resurrect a withdrawn
   generation.
7. Rejoining increments the participation generation and derives fresh
   scoped keys. It never overwrites or silently reactivates withdrawn
   cards.

The master opt-out withdraws all public scopes but leaves encrypted
friend/acquaintance leaderboards enabled. Leaving one gym withdraws
only that gym's cards and links. In both cases, the confirmation text
must state that public Nostr events already copied by relays, clients,
or archives cannot be guaranteed erased.

Separate keys do not hide IP/socket correlation from a relay operator.
Publication delay and connection/relay policy require a dedicated
metadata review before implementation.

## 10. Privacy properties

- Exact public scores are accepted for opted-in global/gym boards.
- Raw logs and the ten contributing climbs remain private.
- Score updates are delayed and time-bucketed so they do not reveal the
  exact end of a training session.
- In the MVP, gym participation deliberately links the main Nostr
  profile to that gym. Avoiding that direct link requires the deferred
  alias mode.
- A public gym card never includes visit time or frequency.
- Small gym cohorts require a still-open publication/display policy;
  hiding them only in the UI is insufficient once cards are on public
  relays.
- Nostr deletion cannot guarantee removal from all archives.

## 11. Trust and attestation

The initial class is `community/self-reported`. A Nostr signature proves
authorship of a score card, not that its private input logs or physical
ascents are true.

A separate `attested` class may later consume signed statements from a
gym, competition, or board provider. A hash, Merkle root, local device
attestation, or zero-knowledge proof over user-authored logs does not by
itself prove a physical ascent.

Self-reported and attested results must remain visibly distinguishable;
clients must not imply verification that the source evidence cannot
support.

## 12. Current implementation gaps

- `board_sessions` has no stable UUID, gym ID, or ascent linkage.
- `ascents.gym_uuid` alone is not a canonical physical-hall identity;
  custom Kilter walls can use locally generated IDs. FEAT-045 owns the stable
  hall and installation alias model.
- Logged rows need a durable canonical board hierarchy snapshot for
  exact and aggregate divisions.
- FEAT-025 has no implemented privacy-preserving community-count
  protocol.
- FEAT-009 requires a privacy review before its public vote events can
  serve this model.

## 13. Competitions (deferred track)

Competitions may reuse division identifiers, score rendering, and
Nostr transport, but they add authoritative problem sets, participant
registration, judges, live deadlines, disputes, and organiser tooling.
Those requirements are not part of the leaderboard MVP and need a
separate implementation spec before work starts.

## 14. Open decisions

1. Minimum cohort and publication mechanism for hall leaderboards.
2. Eligibility/attestation policy for verified rankings.
3. Tie presentation and deterministic ordering of equal scores.
4. Abuse reporting, blocking, and moderation for public profiles/cards.

Canonical hall identity and future operator roles are owned by FEAT-045. An
operator claim never opts users into that hall's leaderboard and does not grant
access to their private score inputs.
