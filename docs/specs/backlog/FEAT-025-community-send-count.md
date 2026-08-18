---
status: backlog
---
# Feature Spec: Community Send Count (privacy-preserving) (backlog)

> **Status:** Backlog - captured 2026-05-17, revised 2026-07-15.
> Privacy and product constraints are locked; the public contribution
> protocol is intentionally not implementation-ready.
>
> CruxCoach community climbs (`origin='cruxcoach'`, published through
> Nostr Kind 30078) currently have no community send-count protocol.
> Their local `climb_stats.ascensionist_count` is written as `0`, while
> Kilter catalogue climbs receive an upstream aggregate.
>
> **Hard constraints:** Nostr is the backend, no CruxCoach-owned server
> or cron is authoritative, and no public event may create a stable
> per-user ascent history.
>
> **Relates to:**
> - FEAT-003 (Climb Creator and Community Climbs), which owns the climb
>   definition event.
> - FEAT-009 (Difficulty Rating Engine), which owns grade aggregation.
>   Its public vote identity/event design requires a privacy review
>   against this spec before either feature is implemented.
> - FEAT-023 (Cross-Board Lists and Send Concept), which owns list and
>   source-board read paths, not community aggregation.
> - FEAT-043 (Competitions and Leaderboards), whose public leaderboard
>   eligibility depends on this feature for community climbs.

---

## 1. Problem statement

A useful community count has to answer one question for a specific
`(board ecosystem, climb ID, angle)`: how many independent climbers
claim at least one successful ascent?

A naive Nostr event saying "pubkey P sent climb C" answers both, but
also creates a permanent, crawlable ascent log. Per-climb throwaway
keys avoid that graph but allow one person to manufacture unlimited
contributors. The feature must not pretend that one problem is solved
by ignoring the other.

### 1.1 Current implementation state

- `climb_stats.ascensionist_count` already exists.
- Community-climb upsert writes `ascensionist_count = 0`.
- Community events carry a setter grade, not independent send claims.
- The secure personal logbook can already calculate the current
  user's own repeat count without publishing anything.
- There is no Nostr protocol for distinct community send claims.

## 2. Locked product decisions

### 2.1 Count semantics

- The public number is **distinct claimed ascensionists**, not total
  ascents. Repeating a climb never increases the community count.
- The uniqueness key is `(board ecosystem, climb ID, angle)`.
- The same climb at the same angle on compatible physical board sizes
  counts once.
- The same climb at different angles counts separately because its
  difficulty and physical challenge differ.
- A claim means "self-reported send" unless a separate trusted source
  attests it. The UI must not label self-reported counts as verified.

### 2.2 Consent boundary

- The user's personal count is local and requires no public consent.
- Any contribution to a community-wide count is public-scope sharing
  and therefore **explicitly opt-in**.
- A consuming feature does not silently enable climb-level claims.
  Community-count participation is a separate, clearly described
  consent because it has its own disclosure surface.
- Opt-out stops future claims and publishes the applicable Nostr
  deletion request. The UI must state that relay/client archives may
  retain earlier public artifacts.

## 3. Goals

- Show a correct local "you: N times" count immediately from the
  encrypted logbook.
- Eventually show a reproducible, Nostr-derived distinct community
  count without a CruxCoach-owned service.
- Expose validated distinct-claim evidence that other features can
  consume without making this feature own their policy.
- Deduplicate retries, edits, relay duplication, and repeated sends.
- Keep Kilter-provided and CruxCoach-derived statistics visibly and
  structurally distinguishable.

## 4. Non-goals

- Proving that a physical ascent happened. Without a hall, competition,
  or board-provider attestation, a send remains self-reported.
- Publishing an individual's ascent history.
- Counting attempts, flashes, sessions, training time, or repeats.
- Deciding leaderboard eligibility, thresholds, or scoring; FEAT-043
  owns those policies.
- Calculating a community grade; FEAT-009 owns that algorithm.
- Treating Nostr identities as unique humans. A Nostr key alone is not
  Sybil resistance.
- Running a proprietary API, database, cron, or hidden aggregate.

## 5. Threat model and privacy requirements

The final public protocol must address:

| Threat | Required property |
|---|---|
| Cross-climb profiling | A public contribution identifier must not be linkable across different climbs/angles. |
| Main-identity disclosure | No main, social-control, feed, acquaintance, or friend pubkey appears in public claim tags/content. |
| Duplicate claims | One eligible identity/credential contributes at most once per climb and angle. |
| Sybil inflation | A proof must bind uniqueness to an eligible identity or credential; creating another ephemeral key must not be sufficient. |
| Replay and relay duplication | Deterministic claim identity/nullifier and event validation make replay idempotent. |
| Timing inference | Claims are delayed and time-bucketed; no ascent or session timestamp is published. |
| Location inference | Claims contain no gym, wall visit, or session identifier. |
| Malicious aggregate publisher | Every client can validate and recompute the count from Nostr artifacts. Cached assertions are optional only. |
| Deletion expectations | Product copy states that Nostr deletion is a request, not guaranteed erasure from archives. |

Network observers and relays can still correlate IP addresses and
publication timing. Per-climb pseudonyms only prevent application-level
key linkage; they do not claim network anonymity.

## 6. Options considered

| Option | Privacy | Integrity | Nostr-only | Verdict |
|---|---|---|---|---|
| Stable-pubkey send events | Fails: public ascent graph | One claim per key, still Sybil-prone | Yes | Reject |
| Unproven per-climb pseudonyms | Good unlinkability | Trivially inflatable | Yes | Reject for global count |
| CruxCoach cron publishes totals | Cron sees claims; users cannot independently audit omissions | Operator-controlled | No | Reject |
| Personal-only local count | Strong | Exact for that device/user | Yes | Ship first |
| Upstream Kilter aggregate for cross-published climbs | Same disclosure already made to Kilter | Inherits Kilter trust model | Read-only input | Allowed with explicit provenance |
| Per-climb pseudonym plus ZK nullifier/membership proof | Can prevent cross-climb linkage | One claim per enrolled credential | Yes | Preferred research direction |
| Hall/vendor-signed anonymous credential | Strong if issuer and presentation are unlinkable | Can add real attestation | Yes on the wire; issuer needed | Optional verified tier |

A nullifier only proves "one claim per enrolled credential". It does
not prove "one human" and does not prove a physical send. The eventual
membership/credential policy is therefore a security decision, not a
cryptographic implementation detail.

## 7. Phased recommendation

### Phase 0: honest local count

- Derive the current user's per-climb repeat count from the secure
  logbook.
- Display it separately from any upstream/community aggregate.
- No new Nostr event.

### Phase 1: provenance-safe upstream reuse

- Where a community climb has a verified mapping to a Kilter catalogue
  climb, the UI may display Kilter's count and grade with a `Kilter`
  provenance label.
- Never copy that number into a field presented as a CruxCoach count.
- Mapping failure degrades to the local count only.

### Phase 2: Nostr community aggregation

Do not start implementation until a separate protocol/security review
locks all of the following:

1. Per-climb, per-angle unlinkable contribution identifier.
2. Proof of membership in the chosen eligible identity/credential set.
3. Deterministic nullifier preventing duplicate contribution.
4. On-device proof verification with maintained Kotlin Multiplatform
   dependencies and acceptable mobile cost.
5. Nostr event shape, relay queries, replacement/revocation rules, and
   test vectors.
6. Abuse recovery when a proof system, eligibility root, or algorithm
   version is compromised.

The likely construction derives a signing key scoped to
`(climb ID, angle, protocol version)` from the existing `social_root`,
so it adds no separately backed-up secret. A zero-knowledge proof or
anonymous credential must then show that the scoped claim belongs to
one eligible member without revealing or reusing the member's public
identity. This is a research direction, not a locked wire format.

## 8. Nostr constraints for the future wire format

- Claims and any aggregate cache are Nostr events published to the
  user's selected relays.
- Indexed tags may expose the community-climb address and angle, but
  never a stable user identity, hall, session, or ascent time.
- Raw private log entries are never attached, hashed as enumerable
  identifiers, or uploaded as proof inputs.
- Clients count unique valid nullifiers and calculate the canonical
  result locally.
- A NIP-85-style third-party assertion may cache a count, but clients
  can ignore it and reproduce the result. It is never a required
  CruxCoach service.
- Event and proof versions are explicit. Consumers requiring a
  validated count fail closed on unknown versions.

## 9. Storage and provenance

Do not overload the current Kilter-derived `ascensionist_count` without
recording its source. The implementation design should expose at least:

```text
personal_repeat_count             private, secure DB
kilter_ascensionist_count         upstream aggregate, optional
community_claimed_ascensionists   Nostr-derived aggregate, optional
community_aggregation_version     protocol/ruleset identifier
community_aggregation_updated_at  coarse cache timestamp
```

The UI may choose one primary display value, but repository/domain
models must retain provenance so Kilter and CruxCoach populations are
never silently merged.

## 10. Acceptance criteria

### Phase 0

- A community climb shows the local user's correct repeat count.
- No network request or public event is created by viewing/logging it.
- Clearing/restoring the personal logbook produces the expected local
  count through existing backup semantics.

### Public aggregation gate

- Two claims from the same eligible credential for one climb/angle
  count once, including across multiple relays and devices.
- Claims for two different angles remain separate.
- Compatible board sizes do not double-count the same climb/angle.
- No observer can link valid claims across climbs from protocol fields
  alone.
- No claim discloses the main Nostr identity, social graph, hall,
  session, exact ascent time, attempts, or repeat count.
- Invalid, unsupported, replayed, or revoked proof artifacts cannot
  increase the count.
- Every supported client derives the same count from the same Nostr
  events.

## 11. Open decisions before Phase 2

- What constitutes an eligible member and how is the membership root
  created without turning CruxCoach into an identity authority?
- Is web-of-trust weighting sufficient, or is an external anonymous
  credential issuer required?
- Which maintained proof system has acceptable Android/KMP binaries,
  audit history, proof size, and verification latency?
- How are compromised or duplicate credentials revoked without making
  presentations linkable?
- Should an attested send and a self-reported send contribute to one
  count with separate provenance, or to two separately displayed
  counts?
Until those questions are resolved, shipping only the personal count
is the correct and explicit fallback. Each consuming feature decides
how an unavailable global count affects its own behavior.
