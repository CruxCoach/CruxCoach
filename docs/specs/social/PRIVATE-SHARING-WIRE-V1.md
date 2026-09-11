---
status: design-review
owner: FEAT-062
protocol: com.cruxcoach.private-sharing/v1
carrier: Marmot MLS application message, inner Marmot app event kind 1220
marmot_pin: 4ad4ae21479c3f3fa9950c6fc4556a76941a62e1
mdk_pin: 101d79946cff6c82d2b849d3b70902a7af7bac08
created: 2026-08-11
revised: 2026-08-13
---

# Private Sharing Wire Contract v1

> **SUPERSEDED (2026-08-16).** This annex describes the withdrawn bilateral
> one-group-per-relationship design. The shipped model — three visibility
> circles, five categories, group baselines plus person and object exceptions,
> device-bound consent and a recovery code — is specified in
> [FEAT-062](../0.2.3/FEAT-062-personal-information-sharing.md), which wins
> wherever the two disagree. Kept as evidence of what was investigated.

Normative application contract for
[FEAT-062](../0.2.3/FEAT-062-personal-information-sharing.md). Companion
documents: [threat model](PRIVATE-SHARING-THREAT-MODEL.md) ·
[golden vectors](PRIVATE-SHARING-GOLDEN-VECTORS.md).

`MUST`, `MUST NOT`, `SHOULD` and `MAY` are normative.

> **No-Go.** This annex is `design-review`. No product implementation or
> release is allowed at the pins above. A throwaway, upstream-first adapter
> spike must pass all eight gates in FEAT-062 §5 and an independent review must
> confirm the evidence. The current pin is not claimed to expose the dedicated
> group creator, outbound, ACK inbox or invalidation mapping specified here.

## 1. Scope and ownership

Marmot/MLS through MDK is the sole runtime, transport and convergence path.
Marmot/MDK owns MLS, group membership, admin enforcement, signed group
components, relays, routing, cursor, epoch, fork selection, retained history,
publish obligations and invalidation. CruxCoach owns only:

- deterministic generation and application-slot bindings;
- kind-1220 namespace filtering;
- canonical `content` bytes;
- relationship, grant and projection semantics;
- minimised closed payloads;
- the idempotent application inbox, outbox and reducer.

CruxCoach does not store an MLS secret, derive an epoch, select a branch, open a
social relay pool or advance a transport cursor.

## 2. Carrier and trust boundary

### 2.1 Marmot app event

The plaintext inside the MLS application message is one
`MarmotAppEvent` with exactly six fields and no `sig`:

| Field | Required value |
|---|---|
| `id` | lowercase NIP-01 SHA-256 id of `[0,pubkey,created_at,kind,tags,content]` |
| `pubkey` | 64 lowercase hex; equal to the MLS-authenticated sender account |
| `created_at` | non-negative Unix seconds; equal to envelope `issued_at` |
| `kind` | exactly `1220` |
| `tags` | exactly the two-tag array below |
| `content` | UTF-8 JCS bytes of one `cc.envelope.v1`, represented as the JSON string field |

The exact tags, including order and case, are:

```text
[["L","com.cruxcoach.private-sharing"],["l","v1","com.cruxcoach.private-sharing"]]
```

Marmot and MDK own the complete outer app-event encoding. At the MDK pin,
`MarmotAppEvent::encode()` serialises the struct in declaration order:
`id,pubkey,created_at,kind,tags,content`. CruxCoach MUST NOT JCS-sort this full
event. CruxCoach JCS-normalises only `content`. The complete event bytes MUST
match the golden vector and the pinned encoder.

### 2.2 Kind registry

The Marmot registry at the pin classifies relevant kinds as follows:

| Kinds | Layer |
|---|---|
| `444`, `445`, `446` | Nostr transport |
| `447`, `448`, `449` | Marmot app payload |
| `450`, `451`, `452` | local signing templates, never relayed |
| `9`, `1009`, `1200`, `1210` | other Marmot app payload semantics |

Kinds `1201` and `1202` are reserved for experiments. Kind `1220` is neither
allocated nor reserved at Marmot SHA
`4ad4ae21479c3f3fa9950c6fc4556a76941a62e1`, and Marmot permits unknown
otherwise-valid inner kinds.

Before design-lock and before every release, a machine check MUST:

1. verify that the checked registry file comes from the declared full Marmot
   SHA;
2. parse both assigned kind rows and reserved-kind statements;
3. assert that decimal kind 1220 is absent;
4. block on collision, parse ambiguity or SHA mismatch.

A collision requires a new kind and protocol version. Two kind values MUST NOT
coexist under v1, and the check never claims 1220 is reserved for CruxCoach.

### 2.3 Namespace prefilter and validation order

Validation order is fixed:

1. MDK structurally decodes the six-field event, rejects a bad NIP-01 `id`,
   authenticates `pubkey` against the MLS sender leaf and supplies only its
   canonical-branch disposition.
2. The CruxCoach adapter checks `kind == 1220` and byte-exact tag-array equality.
   A mismatch is dropped immediately, without reading `content`, without a
   diagnostic quarantine record and without any application state change.
3. The adapter checks the 65,536-byte UTF-8 `content` limit, duplicate JSON keys,
   canonical JCS and the closed envelope/version.
4. It recomputes `generation_id` from the actually delivering MDK group id and
   endpoints; the asserted generation and endpoints must match.
5. It checks creator, topology, the exact component profile including the
   immutable bootstrap marker, signed lifecycle and local MDK lifecycle.
6. It checks author, deterministic slot id, body schema, sequence and every
   cross-stream dependency. Before the generation is `ACTIVE`, the only body
   that may be applied is `cc.generation.accept.v1`; every other body is
   rejected without occupying a slot.
7. It reduces or stores `HELD` atomically, then host-ACKs only after the
   CruxCoach transaction commits.

A failure after the exact namespace matched stores only the bounded digest
record defined in §8.6. Nothing is partially applied.

### 2.4 Hard isolation

Kind 1220 and its Marmot group MUST NOT be exposed through `send_text`, a chat
timeline, chat list, unread state, conversation preview, Markdown, mentions,
reply/reaction/edit UI or NewMessage notification. Social code MUST NOT use
`NostrRelayPool` or a second transport cursor. Application-specific sharing
notifications may be derived only after full validation and local block
filtering.

`send_text` is only the generic plaintext path. The other UniFFI sends at the
pin — reaction, unreact, reply, edit, delete, media and agent stream — are
closed, purpose-specific intents, and none of them accepts a caller-defined
kind, tag array or content.

### 2.5 Consent for public account metadata

Marmot's own Nostr transport binding defines two durable, account-authored,
public artefacts, and they carry no sharing state, no peer and no climbing data.
The first is the Marmot KeyPackage, kind 30443, published per account so another
account can add it to a group. The second is the account's DM inbox relay list,
which Marmot reads as the address where Welcomes arrive; it is the standard
NIP-17 kind-10050 list, reused unchanged, and it names relays and nothing else.
Both are MDK transport metadata: this contract defines no wire object of either
kind, MUST NOT publish either itself, and MUST NOT read their presence as a
relationship, a grant or a sharing signal.

Reusing that standard list as an address does not make it a second social
protocol or a second transport for FEAT-062 traffic. Every FEAT-062 payload
still travels only as an inner kind-1220 app event inside an MLS application
message on the single MDK path.

Because both are permanent and public, neither may be published as a silent side
effect of a sharing action:

- Publication requires an explicit, informed user action that names what becomes
  public and that it cannot be unpublished from relays that already have it.
- The owning layer is MDK for the KeyPackage and for the relay list; CruxCoach
  only asks and records the consent, and never publishes either itself.
- A partial failure — consent granted, publication failed — leaves no local
  state claiming the account is reachable, and the user sees the failure.
- Withdrawing consent stops future publication only. It cannot recall bytes.

## 3. Canonical content

### 3.1 Closed JCS

Every schema below is closed. Required members occur exactly once, optional
members at most once, and every unlisted member, unknown enum or unknown version
invalidates the whole object.

- UTF-8 only; duplicate object keys are invalid.
- `content` is RFC 8785 JCS with no trailing byte. A receiver reserialises the
  parsed object and requires byte equality with the received string.
- Integers lie in `[-9007199254740991,9007199254740991]`. JSON floating-point
  values are forbidden; fractions use scaled integers.
- Strings are NFC. Protocol identifiers are ASCII and case-sensitive.
- Optional empty strings and optional empty arrays are omitted.
- Arrays marked sorted contain no duplicates and sort lexicographically over
  their canonical UTF-8/token bytes unless another order is stated.

Scalar forms:

| Name | Form |
|---|---|
| `hex32` | exactly 64 lowercase hexadecimal characters |
| `account` | `hex32`, decoded to a valid 32-byte x-only account key |
| `event_id` | `hex32`, an inner Marmot app-event id |
| `uuid7` | lowercase canonical UUID with version nibble 7 and RFC 4122 variant |
| `uuid8` | lowercase canonical UUID with version nibble 8 and RFC 4122 variant |
| `unix_s` | non-negative integer Unix seconds |

### 3.2 Size and collection limits

The complete canonical `content` is at most **65,536 UTF-8 bytes**. There is no
compression or alternate payload carrier. A larger object emits no event. UI
reports **Selection too large** and requires narrower fields or a later explicit
detail grant.

| Collection | Limit |
|---|---|
| delta operations | 1–50 |
| complete logbook-detail sessions | 0–100 |
| distinct sends in one session when `sends` is granted | 0–100 |
| focus areas | 1–8 unique tokens |
| planned sessions in one plan | 0–14 |

The limits never authorise truncation. A snapshot is the complete authorised
state or is not sent.

Those numbers are **schema upper bounds, not reachable capacity.** The binding
constraint is the 65,536-byte content cap, and for `LOGBOOK_DETAIL` it bites
long before the collection limits do. A send row
`{"grade_milli":21000,"problem_hash":"<64 hex>"}` is 97 bytes, so a session
carrying 100 sends is roughly 9.9 kB and about **six** such sessions fit under
the cap — not 100. Since §7.1 requires sequence 1 to be a complete snapshot and
this section forbids truncation, the cap is felt on a grant's very first
projection.

The closed bounds make every summary and one bounded `PLAN_DETAIL` resource fit
under the content limit. Beyond that the sender measures the final envelope and
the actual JCS size decides; an oversize result is an encoding error, emits
nothing, and reports **Selection too large**. It never truncates and never
sends a subset.

## 4. Identifiers

### 4.1 Integer and UUID primitives

`u8(x)`, `u16be(x)` and `u32be(x)` are unsigned fixed-width big-endian bytes.
`uuid_bytes(x)` is the 16 decoded bytes of a canonical UUID. `decode_hex(x)`
decodes lowercase hex. Concatenation adds no delimiter beyond bytes shown.

```text
uuid8(digest32):
  take digest32[0..16]
  byte[6] = (byte[6] & 0x0f) | 0x80
  byte[8] = (byte[8] & 0x3f) | 0x80
  render lowercase canonical UUID
```

### 4.2 Endpoints and generation

Decode the two distinct account values and sort the 32-byte values
lexicographically. The lower value is `endpoint_low` and the higher value is
`endpoint_high`. Those two names are used throughout this contract; no other
short form exists:

```text
endpoints = [endpoint_low, endpoint_high]
generation_id = uuid8(SHA256(
  UTF8("cc-ps-generation-v1") || 0x00 ||
  u16be(len(mdk_group_id)) || mdk_group_id || endpoint_low || endpoint_high
))
```

`mdk_group_id` is the exact private byte string returned for the actual
delivering group and MUST be 1–65,535 bytes for this encoding. The group id is
stored locally only and never appears in an envelope. Every receiver recomputes
the UUIDv8; a sender cannot assert a different binding.

For local duplicate detection only:

```text
endpoint_pair_id = SHA256(
  UTF8("cc-ps-endpoints-v1") || 0x00 || endpoint_low || endpoint_high
)
```

The local bootstrap attempt identifier is independent of both:

```text
attempt_id = lowercase canonical UUID rendered from 16 CSPRNG bytes with
             byte[6] = (byte[6] & 0x0f) | 0x40 and
             byte[8] = (byte[8] & 0x3f) | 0x80
```

`attempt_id` MUST NOT be derived from a clock, a counter, an account value or
any other observable input. It exists only so a crashed create can be found
again before a second create is attempted.

### 4.3 Deterministic semantic slot ids

Scope codes are fixed:

| Scope | Code |
|---|---:|
| `LOGBOOK_SUMMARY` | 1 |
| `PLAN_SUMMARY` | 2 |
| `LOGBOOK_DETAIL` | 3 |
| `PLAN_DETAIL` | 4 |

```text
acceptance_object_id = uuid8(SHA256(
  UTF8("cc-ps-acceptance-slot-v1") || 0x00 ||
  uuid_bytes(generation_id) || endpoint_high_account_bytes
))

offer_object_id = uuid8(SHA256(
  UTF8("cc-ps-offer-slot-v1") || 0x00 ||
  uuid_bytes(generation_id) || actor_account_bytes
))

grant_object_id = uuid8(SHA256(
  UTF8("cc-ps-grant-slot-v1") || 0x00 ||
  uuid_bytes(generation_id) || publisher_account_bytes || u8(scope_code) ||
  resource_suffix
))

resource_suffix = uuid_bytes(recipient_specific_plan_uuid) for PLAN_DETAIL
resource_suffix = empty bytes for every other scope

projection_object_id = uuid8(SHA256(
  UTF8("cc-ps-projection-slot-v1") || 0x00 ||
  uuid_bytes(generation_id) || publisher_account_bytes || uuid_bytes(grant_id)
))
```

For `PLAN_DETAIL`, exactly one recipient-specific plan UUID is part of the grant
slot. No other scope has a resource suffix. A body whose `object_id` differs from
the formula is rejected and does not occupy the slot.

The projection slot preimage binds `publisher` in addition to the generation and
the grant. Without that binding a peer could deliberately publish a grant whose
`grant_id` repeats the other publisher's, and both would claim the same
projection slot. With it, the two derive different slots and no shared chain can
form. That is necessary but not sufficient, so §6.3 additionally makes a
`grant_id` unique generation-wide.

The projection slot's only permitted author is the referenced grant's
`publisher` (§7.1). "The author is fixed by the deterministic dimensions" is
operational for every slot in this contract: the acceptance slot admits only
`endpoint_high`, an offer slot admits only the actor whose account bytes are in
its preimage, and grant and projection slots admit only the publisher whose
account bytes are in theirs.

There is exactly **one** grant slot per `(generation, publisher, scope[, plan])`
and exactly **one** acceptance slot per generation, whose only permitted author
is `endpoint_high`. Parallel grant streams for one slot do not exist: a second
free identifier for the same semantic dimensions is not representable, so it can
never be silently collapsed or ranked.

### 4.4 Recipient-specific row ids

Local row ids never cross the wire:

```text
session_uuid = uuid8(SHA256(
  UTF8("cc-ps-session-v1") || 0x00 ||
  uuid_bytes(generation_id) || recipient_account_bytes ||
  uuid_bytes(local_session_uuid) || decode_hex(board_config_id)
))

plan_uuid = uuid8(SHA256(
  UTF8("cc-ps-plan-v1") || 0x00 ||
  uuid_bytes(generation_id) || recipient_account_bytes ||
  uuid_bytes(local_plan_uuid)
))
```

A missing local UUID makes the detail row ineligible. A legacy plan may receive
one local UUID only during an explicit transactional share selection as defined
by FEAT-062; a session is never backfilled.

### 4.5 Board identity

`CanonicalBoardV1` is closed:

| Field | Requirement |
|---|---|
| `type`, `v` | `cc.board`, `1` |
| `ecosystem` | one of `KILTER`, `MOONBOARD`, `TENSION`, `GRASSHOPPER`, `DECOY`, `SOILL`, `TOUCHSTONE` |
| `layout_id` | non-negative provider layout integer |
| `product_id`, `size_id` | required non-negative integers for every listed ecosystem except `MOONBOARD`; forbidden for `MOONBOARD` |
| `angle_mdeg` | integer 0–90000, degrees multiplied by 1000 |

```text
board_config_id = SHA256(JCS(CanonicalBoardV1))
```

`catalogue_revision` is **removed** from v1 and MUST NOT appear in any board,
problem, envelope or payload member. CruxCoach has no authoritative persisted
per-board catalogue revision to derive it from, and an invented one would make
two clients compute different `board_config_id` values for the same board.
Identity is stable across catalogue republication instead, because problem
identity always includes the mandatory hold fingerprint below. Reintroducing a
catalogue dimension requires a concrete, specified provenance source and a new
protocol version.

### 4.6 Mandatory hold fingerprint and problem identity

Each board importer first resolves the complete stored problem into ordered
frames. Frame order is retained. Within each frame, distinct holds are sorted by
`(position_id,role_code)` as unsigned integers:

| Ecosystem | `position_id` | canonical `role_code` |
|---|---|---|
| `KILTER` | provider placement id | Kilter 12–15; route 42–45 maps to 12–15 |
| `MOONBOARD` | provider hold id | provider-native 42–45 |
| other Aurora-protocol ecosystems | provider placement id | provider-native 1–8 |

The exact fingerprint preimage is:

```text
canonical_hold_sequence =
  UTF8("cc-holds-v1") || 0x00 || u8(ecosystem_code) ||
  u16be(frame_count) ||
  for each frame in order:
    u16be(hold_count) ||
    for each sorted hold:
      u32be(position_id) || u16be(role_code)

ecosystem_code:
  KILTER=1, MOONBOARD=2, TENSION=3, GRASSHOPPER=4,
  DECOY=5, SOILL=6, TOUCHSTONE=7

hold_fingerprint = SHA256(canonical_hold_sequence)
```

Counts and ids must fit their fixed widths. A duplicate position with
conflicting roles, unknown role, malformed frame, unresolved removal, skipped
hold or unavailable full sequence makes the ascent ineligible. No best-effort
fingerprint exists.

`CanonicalProblemV1` is closed and contains `type = "cc.problem"`, `v = 1`,
embedded `board`, `provider_problem_id`, boolean `is_mirror`, and required
`hold_fingerprint` (`hex32`) for every problem type. `provider_problem_id` is
the locally verified provider-native identifier, 1–64 lowercase ASCII
characters matching `^[0-9a-f-]+$`; it is never a display name or caller text.

```text
problem_hash = SHA256(JCS(CanonicalProblemV1))
```

### 4.7 Grade normalisation

Parse the provider's official average as base-10 decimal, round half-up to an
integer, accept only 10–34 and multiply by 1000. Unknown, non-finite or
out-of-range grades are excluded from grade-bearing fields and never clamped.

## 5. Envelope and common sequencing

`content` is one closed object:

| Field | Requirement |
|---|---|
| `type`, `v` | `cc.envelope.v1`, `1` |
| `generation_id` | derived `uuid8` from §4.2 |
| `endpoints` | exactly `[endpoint_low_hex,endpoint_high_hex]` |
| `author` | endpoint account; equals inner `pubkey` and authenticated sender |
| `object_id` | deterministic `uuid8` from §4.3 for the body |
| `seq` | integer ≥1, contiguous in the semantic slot |
| `previous_event_id` | forbidden at 1; required above 1 and equal to the prior event id in this slot |
| `issued_at` | `unix_s`; equal to inner `created_at`, never an ordering authority |
| `body` | exactly one closed body from §6 or §7 |

An object slot is `(generation_id,object_id)`. Its author is fixed by its
deterministic dimensions. Ordering comes only from `seq` and
`previous_event_id`. `issued_at`, arrival order, relay evidence, outer event id,
source epoch and MDK branch membership MUST NOT be used as application order.

Version 1 has exactly six body types and no others:

```text
cc.generation.accept.v1
cc.relationship.offer.v1
cc.grant.set.v1
cc.grant.revoke.v1
cc.projection.snapshot.v1
cc.projection.delta.v1
```

There is no multipart carrier, no `part`, `part_index` or `part_count` member,
no projection-request body and no `base_seq` member. Those shapes were
considered and removed; an event carrying any of them is invalid. Enlarging this
list requires a new protocol version.

## 6. Provisioning, relationship and grants

### 6.1 `cc.generation.accept.v1`

This is the only body that may be sent or applied before a generation is
`ACTIVE`, and the only body whose author may be `endpoint_high`'s first act in
the group. It carries no sharing data and authorises nothing.

The closed body contains `type`, `v = 1`, and `acceptance_revision`, an integer
1–1000 that names the local acceptance-UI revision the user acted on.

| Requirement | Rule |
|---|---|
| author | exactly `endpoint_high`; a `cc.generation.accept.v1` authored by `endpoint_low` is rejected |
| slot | the deterministic acceptance slot from §4.3, `seq = 1` only |
| precondition | the local user of `endpoint_high` explicitly accepted the Welcome for this exact group; an auto-accepted or merely observed Welcome MUST NOT emit it |
| binding | the envelope's `generation_id` and `endpoints` bind it byte-exactly to the recomputed generation; the body adds no second binding |
| effect | it is evidence of acceptance only; it grants no level, no scope and no field |

`seq > 1` on the acceptance slot, a second acceptance with a different body, and
an acceptance whose envelope generation does not recompute from the delivering
group are each rejected without occupying the slot. Two different valid
acceptance events at `seq = 1` terminalise the generation under the
equal-sequence rule in §8.2.

`endpoint_low` MUST NOT promote `endpoint_high` to admin before this event is
canonically applied. Relationship, grant and projection events are both
send-blocked and apply-blocked until the generation is `ACTIVE`.

### 6.2 `cc.relationship.offer.v1`

The closed body is `type`, `v = 1`, and `offer`, one of `NONE`,
`ACQUAINTANCE`, `FRIEND`. It uses the deterministic offer slot for
`envelope.author`.

Two different things must not be confused, so they have two names:

| Concept | Name | Meaning |
|---|---|---|
| computed effective level | `NOT_ESTABLISHED`, `ACQUAINTANCE`, `FRIEND` | what the applied state currently authorises |
| received body value | `offer: "NONE"` | an explicit, authored withdrawal |

- Only the latest gapless head of each endpoint counts. An endpoint that has not
  yet offered has **no head**, and a missing head makes the computed effective
  level `NOT_ESTABLISHED`. That is the ordinary starting state of every new
  generation and it is **not** terminal.
- Effective level is `min(endpoint_low_head, endpoint_high_head)` under
  `NOT_ESTABLISHED < ACQUAINTANCE < FRIEND`, where a missing head contributes
  `NOT_ESTABLISHED`.
- For display/audit only, an increase transition time is
  `max(endpoint_low_head.issued_at, endpoint_high_head.issued_at)`. It never
  supplies a detail boundary or an ordering rule.
- Only an explicitly received, structurally and semantically valid, applied
  `cc.relationship.offer.v1` whose body carries `offer: "NONE"` terminalises the
  generation. A computed `NOT_ESTABLISHED` level never terminalises anything; an
  implementation that terminalises on the computed level would close every
  generation at the moment it is created.
- `FRIEND` to `ACQUAINTANCE` permanently retires every detail grant. A later
  increase never reactivates one.

### 6.3 `cc.grant.set.v1`

The closed body contains:

| Field | Requirement |
|---|---|
| `type`, `v` | `cc.grant.set.v1`, `1` |
| `grant_id` | fresh `uuid7` for every explicit set; unique generation-wide |
| `supersedes_grant_id` | forbidden at `seq = 1`; required above 1 and equal to the last grant id in this slot, even if expired/revoked |
| `publisher` | equals envelope author |
| `recipient` | the other endpoint |
| `scope` | one scope from §4.3 |
| `offer_heads` | exactly two inner event ids in endpoint order |
| `fields` | sorted unique non-empty subset of the scope's closed token set |
| `resource_id` | exactly one recipient-specific `plan_uuid` for `PLAN_DETAIL`; forbidden otherwise |
| `issued_at` | equals envelope `issued_at` |
| `expires_at` | greater than `issued_at` and at most `issued_at + 7776000` |
| `not_before` | required for detail and exact formula below; forbidden for summary |

Scope level and field tokens:

| Scope | Minimum level | Field tokens |
|---|---|---|
| `LOGBOOK_SUMMARY` | `ACQUAINTANCE` | `all_time_send_count`, `hardest_grade_milli` |
| `PLAN_SUMMARY` | `ACQUAINTANCE` | `phase`, `sessions_per_week` |
| `LOGBOOK_DETAIL` | `FRIEND` | `board_config_id`, `duration_minutes`, `sends`, `started_at_utc` |
| `PLAN_DETAIL` | `FRIEND` | `focus_areas`, `phase`, `sessions`, `sessions_per_week` |

For detail:

```text
not_before = max(
  grant.issued_at,
  endpoint_low_referenced_offer_head.issued_at,
  endpoint_high_referenced_offer_head.issued_at
)
```

Each referenced event must exist on the correct deterministic offer slot, be in
its gapless chain and itself establish the scope's required level together with
the other referenced head. A missing head leaves the grant `HELD`; it is
re-evaluated whenever either dependency arrives. Existing heads that fail the
level test reject the whole grant and do not occupy its sequence. The current
effective head pair at evaluation must also meet the minimum level. The reducer
walks every gapless offer successor between each referenced head and the current
head; if any resulting effective pair falls below the minimum, the grant is
permanently retired even if it was still `HELD` or arrived only after a later
re-upgrade. Later higher or equal offers do not invalidate a grant. Thus stale
qualifying references cannot bypass an intervening downgrade.

Every valid set immediately retires, hides and purges materialised data for the
prior grant in that slot in the same local commit. Narrowing, widening,
extension and re-sharing have no special renewal semantics; each is a fresh
explicit set, fresh grant id and, for detail, fresh boundary. Old detail never
comes back.

A narrowing set removes the dropped fields and the dropped resource from the
local view and from storage immediately. The earlier events stay in the local
audit record, but an event that is no longer the current grant authorises no
display: historical provenance is not authorisation.

#### Grant identifier rules

`grant_id` is minted, not derived, so both sides need explicit rules:

- The **sender** MUST mint a fresh UUIDv7 for every explicit set, with its 48
  timestamp bits set to the real millisecond value at minting time. It MUST NOT
  reuse an id, and MUST NOT derive one from a counter or from another
  identifier.
- The **receiver** checks the version nibble, the RFC 4122 variant, and that the
  id has not been used before **anywhere in this generation**. It MUST NOT treat
  the timestamp bits as authority time, MUST NOT compare them with `issued_at`,
  and MUST NOT order anything by them; §8.1 already forbids time as ordering
  input.
- A `grant_id` is unique **generation-wide**, across both publishers and all
  scopes. A set whose `grant_id` is already bound to the *other* publisher in
  this generation is the terminal `GRANT_ID_COLLISION` fault of §8.4 — never a
  shared slot, never a silent second stream. A set that reuses the *same*
  publisher's own earlier id is rejected without occupying its sequence.
- `publisher` MUST equal `envelope.author`.

### 6.4 `cc.grant.revoke.v1`

The closed body contains `type`, `v = 1`, current `grant_id`, `publisher`,
`recipient`, `scope`, and `reason`, one of `REVOKED_BY_USER`, `BLOCKED`,
`GENERATION_CLOSED`.

Authorisation is explicit and identical to the set case:
`envelope.author == body.publisher == the named grant's publisher`. A revoke
authored by the recipient, or naming a grant published by the other endpoint, is
rejected and occupies no sequence. A revoke of a grant that is not the current
one in that slot is likewise rejected.

It occupies the same deterministic grant slot and linear sequence. It is
terminal for the named grant, not for the slot: a later explicit set is valid
only with a fresh grant id, required supersession link and fresh detail boundary.
The revoked materialised data is hidden immediately and purged within 24 hours.

## 7. Projection bodies and payloads

### 7.1 Common binding

There is exactly one projection slot per `grant_id`.

`cc.projection.snapshot.v1` is closed and contains `type`, `v = 1`, `grant_id`,
`grant_event_id`, `scope`, exact `fields`, optional `resource_id` under the same
rule as the grant, detail-only `prospective_from`, `as_of`, and `payload`.

`cc.projection.delta.v1` has the same binding fields and `as_of`, plus `ops`
instead of `payload`. `ops` contains 1–50 closed operations.

Rules:

- sequence 1 MUST be a snapshot;
- a later snapshot is allowed on the same gapless chain and completely replaces
  that grant's materialised state;
- a delta advances the preceding gapless state; envelope sequence and
  predecessor are the only cursor;
- `grant_event_id` is the exact inner event id of the current
  `cc.grant.set.v1` that created `grant_id`;
- `envelope.author` MUST equal the referenced grant's `publisher`, and the
  deterministic slot of §4.3 is computed from that same publisher. A projection
  authored by the other endpoint is rejected with `AUTHOR_BINDING_INVALID` and
  occupies no sequence;
- scope, fields and optional resource equal that grant byte-for-byte;
- for detail, `prospective_from = grant.not_before`; it is forbidden for
  summary;
- a missing grant or offer dependency leaves the projection `HELD` for later
  re-evaluation; an existing mismatching dependency rejects it whole.

Structural and token-driven members are distinguished. `type`, `v`, `grant_id`,
`grant_event_id`, `scope`, `fields`, `resource_id`, `prospective_from`, `as_of`,
`op` and every row's `session_uuid` are **structural**: they are required by the
schema and are never named by a grant token. Only a token-driven payload member
that the grant's `fields` does not authorise rejects the whole object. A
receiver MUST NOT reject an object merely because a structural member is not
listed in `fields`.

### 7.2 `cc.logbook_summary`

Closed payload: `type = "cc.logbook_summary"`, `v = 1`, and every field named by
the grant, with no other field.

- `all_time_send_count`: integer 0–1,000,000;
- `hardest_grade_milli`: `0` or integer 10000–34000.

These are the sole historical exception. They may aggregate eligible ascents
from before the grant because the aggregate itself is explicitly consented.
They expose no source row and cannot be used to request detail.

An eligible ascent is one whose complete `CanonicalProblemV1`, including its
hold fingerprint, can be derived. `all_time_send_count` counts every eligible
send row, including repeats of the same problem; it is not deduplicated by
`problem_hash`. `hardest_grade_milli` is the maximum eligible grade, or `0`
when the eligible set is empty. An ineligible ascent contributes to neither
field.

### 7.3 `cc.plan_summary`

Closed payload: `type = "cc.plan_summary"`, `v = 1`, and the granted fields for
the current active plan only:

- `phase`: `BASE`, `STRENGTH`, `POWER`, `PERFORMANCE`, `DELOAD`;
- `sessions_per_week`: integer 0–21.

When a current plan exists, each named grant field appears exactly once. When
none exists, the complete snapshot contains only `type` and `v`; that single
closed exception conveys absence, not hidden companion data.

### 7.4 `cc.logbook_detail`

Closed payload: `type = "cc.logbook_detail"`, `v = 1`,
`prospective_from` equal to the body and `sessions`, a complete array of 0–100
rows sorted by `session_uuid`.

A session row always contains structural `session_uuid`; every other member is
present exactly when its grant token is present:

| Grant token | Value |
|---|---|
| `started_at_utc` | `floor900(private unrounded start)`, subject to the boundary rules below |
| `duration_minutes` | nearest multiple of 15, ties upward, bounded 15–720 |
| `board_config_id` | `hex32` from §4.5 |
| `sends` | 0–100 complete rows `{grade_milli,problem_hash}`, unique and sorted by `problem_hash` |

#### The 900-second prospective boundary

`prospective_from` is `grant.not_before`, an ordinary Unix second that is not
900-aligned; `started_at_utc` always is. Both sides therefore need a rule, and
both rules are decidable from the wire value alone:

```text
floor900(t) = (t / 900) * 900        integer division
ceil900(t)  = -((-t) / 900) * 900
```

- **Producer.** A session MAY be sent only when all three hold: its private
  unrounded start is at or after `prospective_from`; the session does not span
  the boundary; and `floor900(start) >= prospective_from`. The first sendable
  instant after a boundary is therefore `ceil900(prospective_from)`.
- **Receiver.** A session row is valid only when
  `started_at_utc mod 900 == 0` **and** `started_at_utc >= prospective_from`.
  A row failing either test rejects the whole projection object with
  `BODY_SCHEMA_INVALID`; it does not occupy the sequence.

The two rules are deliberately symmetric, so the prospectivity promise is
provable from the envelope by an auditor who never sees a private start. The
price is stated rather than hidden: up to 899 seconds of otherwise legitimate
data immediately after a boundary are unsendable, because serialising them would
put a pre-boundary value on the wire. Nothing is rounded up, nothing is shifted
and nothing is emitted with a boundary the receiver would have to trust.

Golden vectors §6.5 carries the positive boundary vector and both negative rows.

If more than 100 distinct eligible sends exist while `sends` is granted, the
session and therefore the complete projection are unsendable. No subset or
companion flag is emitted. If `sends` is not granted, the other granted fields
may still flow.

### 7.5 `cc.plan_detail`

One `PLAN_DETAIL` grant names exactly one plan through the projection body's
`resource_id`. Its closed payload contains `type = "cc.plan_detail"`, `v = 1`,
`prospective_from`, and `plan`. `plan` is either `null` for a complete absent
state or a closed object containing every granted token and no other data:

| Grant token | Value |
|---|---|
| `phase` | closed phase enum from §7.3 |
| `focus_areas` | 1–8 sorted unique values from `core`, `finger_strength`, `flexibility`, `power`, `power_endurance`, `technique`, `upper_body_pull`, `upper_body_push` |
| `sessions_per_week` | integer 0–21 |
| `sessions` | 0–14 planned-session rows sorted by day then type |

A planned-session row has exactly four members:

- `day_of_week`: integer 0–6;
- `session_type`: `STRENGTH`, `POWER`, `VOLUME`, `TECHNIQUE`, `DELOAD`, `REST`;
- `target_duration_min`: integer 0–600;
- `target_rpe_deci`: integer 10–100.

No exercise row, title, name, repetition prescription, note or other free text
is representable. Eligibility is checked against the plan's private local start
boundary: it must be at or after `prospective_from`, and a plan already active
across that boundary is ineligible. That private local start value is not
transmitted. A selected plan whose complete prospective state does not fit the
limits emits nothing. The sequence-1 snapshot requires the selected plan
object; only a later full snapshot may use `plan: null` after that resource was
deleted.

### 7.6 Delta operations

Closed operations by scope:

| Scope | Allowed operation |
|---|---|
| `LOGBOOK_SUMMARY` | `SET_SUMMARY` with one complete `cc.logbook_summary` row |
| `PLAN_SUMMARY` | `SET_SUMMARY` with one complete `cc.plan_summary` row |
| `LOGBOOK_DETAIL` | `UPSERT_SESSION` with one complete session row, or `DELETE_SESSION` with only `session_uuid` |
| `PLAN_DETAIL` | `UPSERT_PLAN` with the one complete plan, or `DELETE_PLAN` with only `op`; the body `resource_id` identifies it |

Semantic row ids make repeated identical operations no-ops. An operation with
an ungranted payload member rejects the complete delta.

A delta is checked against the **resulting** state before it is committed, not
only operation by operation. If applying the complete delta would leave more
than 100 logbook sessions for that grant, or would violate any other collection
bound in §3.2, the **whole** delta is rejected with `BODY_SCHEMA_INVALID`, it
occupies no sequence, and no operation is partially applied. There is no
eviction, no truncation and no partial commit.

### 7.7 Global prohibition

No v1 envelope or payload can contain raw ascent/logbook rows; bids, bid counts,
attempts, attempt counts or timestamps; comments, private notes, free text or
`adaptationNotes`; health, injury, body, weight, sleep, pain, skin or mood data;
workout logs; payments; location, gym, wall, hall or GPS data; credentials,
keys, key references, backups or backup locations; MLS/MDK secrets; local row
or database ids; `userId`, `generatedBy` or `planVersion`; generated/adaptation
metadata; climb or setter names; descriptions; hold sequences or frames; relay
URLs; group ids; epochs; or any unknown member.

## 8. Reducer and provenance

### 8.1 Allowed ordering inputs

The reducer may use the validated envelope, its authenticated source mapping,
the local generation binding and prior CruxCoach application state. It MUST NOT
use arrival order, `issued_at`, outer ids, relay observations, source epoch or
branch membership as application ordering.

### 8.2 Linear slot rule

For every semantic slot:

- `seq = 1` has no predecessor;
- `seq > 1` applies only after the exact predecessor event is applied;
- reordered suffixes and missing cross-stream dependencies are durably `HELD`
  and re-evaluated on every relevant arrival;
- byte-identical redelivery and repeated inner id are no-ops;
- a structurally or semantically invalid event, including a wrong predecessor,
  is rejected and does not occupy `(object_id,seq)`;
- two different structurally and semantically valid events at the same
  `(object_id,seq)` terminalise the entire generation. No id, time or arrival
  comparison chooses a winner.

A fresh snapshot cannot repair an equal-sequence conflict. Re-sending at the
conflicting sequence is one more conflicting event, and re-sending above it has
no applied predecessor. There is deliberately no stream-reset or new-incarnation
primitive: the whole generation is terminal and the users create a new one. Offer
and grant slots have no snapshot body at all, so no recovery shortcut exists
there either.

#### Bounded `HELD` and accepted history

`HELD` is durable and has no expiry, because dropping a held event could drop a
restrictive prefix. It is therefore bounded by count and bytes instead, and the
bounds are hard:

| Bound | Value |
|---|---:|
| held events per slot | 64 |
| held content bytes per slot | 262,144 |
| held events per generation | 256 |
| held content bytes per generation | 1,048,576 |
| held content bytes across all generations | 4,194,304 |
| `PLAN_DETAIL` grant slots per publisher per generation | 8 |
| grant slots per publisher per generation | 11 |
| accepted logbook sessions per `LOGBOOK_DETAIL` grant | 100 |

Without the `PLAN_DETAIL` bound the slot space would be unbounded, because each
distinct plan resource derives its own grant and projection slot.

An insert that would exceed any bound does **not** happen. The offending
generation becomes terminal with `STORAGE_BOUND_EXCEEDED` **before** the insert,
and:

- nothing is evicted, and no best-effort eviction exists;
- no safety prefix, tombstone, revoke, downgrade or purge record is ever pruned
  to make room;
- every other generation stays fully available and is not touched.

A held object whose chain has been incomplete for more than 30 days is surfaced
as a stale `RECONCILING` object in the UI. That is a presentation rule only: the
held bytes are not discarded by age, because age is not a safety argument.

### 8.3 Gap recovery

Receiving a valid event above the next sequence creates an immediate gap. Only
that object enters `RECONCILING`, and only its materialised data is hidden. The
held suffix remains stored. The state exits exclusively when MDK replay supplies
every missing event and the chain becomes gapless. There is no automatic reset
or synthetic application message. A user may instead visibly terminate the
entire generation and reconnect with a new group.

A missing cross-stream dependency is `HELD`, not quarantined. It does not become
a chain gap unless the object's own sequence has a missing predecessor.

### 8.4 Invalidation and terminal safety

Every adapter invalidation must map exactly one `source_message_id` to exactly
one stored kind-1220 `inner_event_id` and a closed reason. Invalidation of any
stored event, applied or held, makes the whole generation terminal immediately,
hides all data and requires a fresh group/generation. A zero-match or multi-match
mapping has the same result.

The closed adapter reasons mirror the pin one-to-one:
`LOSING_BRANCH`, `BEYOND_ANCHOR`, `BEYOND_APP_RETENTION`, and
`UNDECRYPTABLE_IN_CANONICAL_STATE`. A zero- or multi-match is instead a local
terminal `SOURCE_MAPPING_MISSING` or `SOURCE_MAPPING_AMBIGUOUS` fault; it never
fabricates an inner id.

A reason is only usable when it is one of those four **and** survives a
restart. At the pin it is neither guaranteed to: the invalidated boolean is
persisted while the reason is emitted in memory after the canonical commit, so
a reason that cannot be reproduced after a restart is the expected observation
rather than an exotic one. FEAT-062 §5 gate 5 decides what follows — the gate
is reported **FAIL**, no exactly-once invalidation delivery is claimed, and the
durable record keeps no reason it cannot stand behind. None of that softens the
outcome: the invalidation is terminal either way, and an unusable *mapping*
dominates an otherwise perfect reason, because a reason never repairs a source
id that matches zero or several stored events.

A signal that names no well-formed `source_message_id` at all is the same
failure seen from the other side: it maps nothing, whatever it claims to have
matched, and is terminal with `SOURCE_MAPPING_MISSING`. The adapter is never
asked to supply the id from context, and CruxCoach never infers one.

**No signal at all is a FAIL, not a pass.** Where a stored kind-1220 event sits
on a branch the client has itself observed being rolled back (§8.4a) and *no*
adapter invalidation for that payload ever arrives, §5 gate 5 is reported
**FAIL**. The absence is never read as evidence that the payload is still
canonical. The generation terminalises from the commit-level rollback it did
see, with `COMMIT_ROLLBACK_AFTER_ACTIVATION`, and the durable record keeps no
adapter reason. This is the case R5 and R6 did not plan for — both only ever
planned for a reason that is not durable — and the case R8 observed directly on
the pin's own direct fork-recovery seam.

There is no partial recomputation after invalidation. This intentionally
prevents a losing branch from widening grants, undoing revokes or reviving an
earlier offer. Locally initiated revoke, downgrade, block, removal and purge
tombstones remain sticky.

MDK quarantine and `Unrecoverable` are also terminal. `RECONCILING` means only a
repairable pure application-chain gap.

#### Closed local terminal fault codes

Every terminal outcome carries exactly one of these codes. The set is closed and
no peer-supplied text ever enters it:

```text
EQUAL_SEQUENCE_CONFLICT           STORED_EVENT_INVALIDATED
SOURCE_MAPPING_MISSING            SOURCE_MAPPING_AMBIGUOUS
GRANT_ID_COLLISION                TOPOLOGY_INVALID
PROFILE_MARKER_CHANGED            LIFECYCLE_NOT_ACTIVE
DUPLICATE_GENERATION              MDK_QUARANTINE
MDK_UNRECOVERABLE                 COMMIT_ROLLBACK_AFTER_ACTIVATION
OFFER_NONE_RECEIVED               STORAGE_BOUND_EXCEEDED
WRONG_CREATOR                     PROMOTION_FAILED
ATTEMPT_ID_REUSED                 ATTEMPT_ID_NOT_CSPRNG
PROJECTION_AUTHORITY_INVALID      RETRY_NOT_TRANSPORT_IDENTICAL
BRANCH_SELECTION_DIVERGENT
```

`PROJECTION_AUTHORITY_INVALID` (§8.5a) and `RETRY_NOT_TRANSPORT_IDENTICAL`
(§8.7a) were added after the R5 adapter spike; §11.1 records the evidence.
`BRANCH_SELECTION_DIVERGENT` (§8.4a rule 6) was added after the R8 spike
reproduced two members holding different canonical branches for the same fork.

The closed hold reasons of §8.3 are `HOLD_PREDECESSOR_MISSING`,
`HOLD_GRANT_MISSING`, `HOLD_OFFER_HEAD_MISSING` and `HOLD_REPLAY_INCOMPLETE`
(§8.5a). Those four are exhaustive because a hold is only ever a *missing*
dependency. A projection whose named `grant_id` has no applied grant yet is
`HOLD_GRANT_MISSING`; a projection whose `grant_event_id` disagrees with the
applied grant of that `grant_id`, or whose grant is no longer `ACTIVE`, is not
waiting for anything and is rejected with `DEPENDENCY_INVALID` (§8.2). A
`grant_id` is unique generation-wide and binds its grant event once, so there is
no third state in which a projection's grant exists and its grant event does
not.

### 8.4a Commit rollback at an authority boundary

`AppMessageInvalidated` is not the pin's only invalidation class. MDK also
withdraws *group state* through `GroupStateInvalidated`, whose only reason is
`SupersededByBranchSelection` — "branch selection superseded a commit this client
previously applied, **including the client's own published and confirmed
commit**". The engine emits it alongside the two commit-rollback seams,
`ForkRecovered` on the direct staged-commit path and `CommitRolledBack` on the
stored-convergence path. Each names the rolled-back commit's transport
`MessageId`.

This matters because FEAT-062's authority boundaries are commits, not app
messages: the promotion of `endpoint_high` to admin (§9.4), any change to the
`0x8003` admin policy, any change to the `0x8001` bootstrap marker, and the
`0x800c` lifecycle transition.

Required behaviour on any of the three signals for the generation's group:

1. The generation immediately **holds**: no send, no apply, no purge deadline is
   advanced by the rollback itself.
2. The client performs one fresh session-consistent MDK read and decides by
   §9.5. Convergence states that are merely unresolved keep holding; they are
   not terminal by that fact alone.
3. If the rolled-back commit is a promotion, an admin-policy change, a `0x8001`
   profile change or the `0x800c` transition **and** the generation had already
   reached `ACTIVE`, it is terminal with `COMMIT_ROLLBACK_AFTER_ACTIVATION`. A
   generation cannot un-become active: the authority the peer exercised while it
   was active is not retracted by the branch that lost.
4. Before activation, the same rollback returns the generation to
   `PROVISIONING`; it is not terminal by itself, and a permanently unreconcilable
   promotion ends in the visible abort of §9.1.
5. Safety-lowering tombstones stay sticky through every one of these. A rollback
   never revives a revoked grant, an old offer head or purged data.

6. If the client can observe that a peer settled on a **different** canonical
   branch for the same fork, the generation is terminal with
   `BRANCH_SELECTION_DIVERGENT`, whatever the rolled-back commit's class and
   whether or not the generation had reached `ACTIVE`. This is decided ahead of
   rules 3 and 4: the two seams the pin resolves with do not share a rule — the
   direct fork-recovery seam orders only by (source epoch, priority, committer,
   digest) and carries no app-payload term, while witness-aware stored
   convergence stops earlier, at `app_witness_score`, and a device can only
   count app witnesses on a branch it has already applied, because a rival
   branch's payloads are wrapped under that branch's own epoch exporter secret.
   Two members can therefore hold different canonical branches indefinitely with
   no further input. A permission generation that is active on one device and
   rolled back on another is not repairable by re-reading either side, so it
   fails closed rather than holding.

A rollback of an unrelated commit holds and re-reads like any other unresolved
convergence; only the fresh read decides.

### 8.5 ACK and crash boundary

The dedicated adapter inbox persists snapshot/live/replay items independently
of MDK message retention until host ACK. It supplies group, authenticated
sender, source epoch, source message id, inner id, kind, tags and content.

CruxCoach writes accepted or held state and the source-to-inner mapping in one
SQLCipher transaction. It sends host ACK only after commit. A crash before ACK
replays; a crash after ACK sees an idempotency row. No transaction spans MDK and
CruxCoach stores. More than five epoch advances and pruning MUST NOT remove an
unacknowledged item.

### 8.5a Durable projection, retention and broadcast lag

Two facts from the R5 adapter spike (§11.1) are normative here, because they
decide what may be treated as long-lived state.

**The MDK raw app store is not a CruxCoach authority.** R5 ran a real retention
sweep on a group with active retention: the expired raw custom row was deleted
and a later survivor remained. That is correct engine behaviour and it is
precisely why the raw store cannot hold CruxCoach state. FEAT-062's exact
minimal profile forbids the `0x8005` retention component (§9.3), but absence
only keeps the effective retention **outside peer control**; it does not turn
MDK's raw storage into durable application state, and it does not promise that
any raw row still exists when CruxCoach next looks.

Therefore:

- CruxCoach MUST hold its own durable projection — the acknowledged inbox of
  §8.5 plus the reduced application state — and MUST NOT re-read the MDK raw
  app store as authority for anything. A client that does is terminal with
  `PROJECTION_AUTHORITY_INVALID`.
- An inbox item that is gone before its host ACK is terminal with
  `PROJECTION_AUTHORITY_INVALID` for that generation. Nothing is reconstructed,
  interpolated or guessed from a partial view.
- A retention-independent long-lived projection does **not** exist at the pin.
  It is gate `R6-G1-DURABLE-PROJECTION` and it is the reason the product design
  is No-Go, not a property this contract may assume.

**Live delivery is never a correctness input.** The pin's private message
broadcast really does drop items under load and report `RecvError::Lagged` to
its subscriber: R6 forced the overflow on the real 1024-slot broadcast and lost
2152 events outright (§11.1, criterion 3). It also recovered the custom event
from the store exactly once — but from a crate-internal test against a private
sender, on no surface this adapter could reach. The loss is therefore proven and
the recovery CruxCoach would depend on is not, so the rules below stand as
written rather than being relaxed on the strength of that run.

- Correctness MUST rest on the durable snapshot/replay path alone. A live item
  is an optimisation.
- A live gap that durable replay has not yet closed holds the affected objects
  with `HOLD_REPLAY_INCOMPLETE`. Holding is not terminalising: the objects wait,
  send and permissive apply stay blocked, and no completeness is claimed.
- A gap whose bytes retention already removed is terminal with
  `PROJECTION_AUTHORITY_INVALID` under the rule above.
- This is gate `R6-G3-BROADCAST-LAG-REPLAY` and stays open.

### 8.6 Bounded diagnostic quarantine

After the exact kind/tag namespace matched, a rejected object may create one
diagnostic record containing only:

- `SHA256(raw_content_bytes)`;
- closed reason code;
- source message id;
- inner event id when structurally valid;
- byte size and `first_seen_at`.

The closed reason codes are `CONTENT_UTF8_INVALID`, `CONTENT_OVERSIZE`,
`CONTENT_JSON_INVALID`, `CONTENT_JCS_INVALID`, `ENVELOPE_SCHEMA_INVALID`,
`GENERATION_BINDING_INVALID`, `TOPOLOGY_INVALID`, `AUTHOR_BINDING_INVALID`,
`SLOT_BINDING_INVALID`, `BODY_SCHEMA_INVALID`, `PREDECESSOR_INVALID`,
`DEPENDENCY_INVALID`, and `EQUAL_SEQUENCE_CONFLICT`. No peer-supplied text is
copied into a reason.

Raw content bytes are never retained. The store is capped at 100 records per
generation and 30 days. At the cap, the new diagnostic is dropped after a
bounded counter increment. Diagnostic records never occupy a semantic slot or
change reducer state. Unknown or oversize traffic therefore cannot fill local
storage and cannot deposit peer-supplied plaintext.

Every rejected input resolves to exactly one of five closed dispositions, and
the golden-vector corpus asserts which one:

| Disposition | Stored | Slot |
|---|---|---|
| `DROP_BEFORE_PARSE` | nothing at all, not even a digest | untouched |
| `BOUNDED_DIGEST_ONLY` | one §8.6 digest record | never reached |
| `REJECT_NO_SLOT` | one §8.6 digest record | stays free for a later correct event |
| `HELD` | the event itself, durably, within the §8.2 bounds | reserved for its own sequence only |
| `TERMINAL` | the generation stops; tombstones stay sticky | frozen |

### 8.7 Outbox, leases and supersession

The outbox is a durable local table. One row is one intended inner event.

| Column | Rule |
|---|---|
| `state` | exactly one of `QUEUED`, `IN_FLIGHT`, `SENT_LOCALLY`, `MDK_CANONICAL`, `FAILED`, `DELIVERY_UNCLEAR`, `SUPERSEDED` |
| `lease_owner` | opaque id of the single process/worker that took the row; `NULL` unless `IN_FLIGHT` |
| `lease_boot_id` | the boot identity that minted the lease; `NULL` unless `IN_FLIGHT` |
| `lease_until` | monotonic-clock deadline of that lease, valid only within `lease_boot_id`; `NULL` unless `IN_FLIGHT` |
| `external_effect_phase` | exactly one of `PRE_EXTERNAL_EFFECT`, `EXTERNAL_EFFECT_UNCLEAR`, `EXTERNAL_EFFECT_OBSERVED`; advanced only by a committed local transaction of its own, ahead of the step it describes (§8.7b, §8.7c) |
| `attempts` | bounded retry counter |

Rules:

- only a worker whose `lease_owner` matches may advance an `IN_FLIGHT` row;
- an `IN_FLIGHT` row whose `lease_boot_id` differs from the current boot has an
  expired lease, **without comparing `lease_until` at all**. A monotonic
  deadline minted in a previous boot has no meaning in this one, so comparing
  the two numbers is forbidden;
- within the same boot, an `IN_FLIGHT` row whose `lease_until` has passed also
  has an expired lease;
- an expired lease may be **reclaimed** by any worker. Reclaiming the lease is
  not requeueing the row: it says only that nobody holds it any more. What the
  reclaiming worker may then do with the row is decided by the external-effect
  phase in §8.7b, and an automatic return to `QUEUED` is only one of its
  outcomes. No recovery path ever mints a second inner event id;
- retries follow the schedule in FEAT-062 §8 and stop at 30 days, after which
  the row is `FAILED` and stays visible; a `FAILED` row is never pruned by age
  alone, because silent disappearance would hide an unsent revoke. What that
  schedule re-attempts is a send that was **never authorised** — the write-ahead
  commit of §8.7c was never proven, so no handover happened and each attempt is
  an ordinary first delivery. A send that *was* authorised is never re-attempted
  automatically (§8.7d), and a `DELIVERY_UNCLEAR` row (§8.7b) is on no schedule
  at all: it is not retried, so it neither consumes `attempts` nor ever becomes
  `FAILED` by time;
- a row may be `SUPERSEDED` only when the superseding row is already durably
  enqueued in the same committed transaction.

#### Typed outbound receipt

At the MDK pin the send summary is not sufficient evidence for any user-visible
claim, so the adapter persists its own typed receipt. `SendSummaryFfi` has an
untyped `message_ids: Vec<String>`; the app-message send path puts the **inner**
event id in it (`SendSummary { message_ids: vec![app_event_id] }`, where
`app_event_id` is `event.id`), other commands use the same field for transport
ids, and `published` is a count of transport reports, not of accepted relays.
The accepting endpoints exist internally on `TransportPublishReport.accepted`
but do not cross the FFI. Nothing may be inferred from that field.

The required durable receipt, restart-safe and one per intended inner event:

| Member | Rule |
|---|---|
| `intent_id` | opaque local id of the send intent, minted before any external effect |
| `inner_event_id` | typed `event_id`, present as soon as the event is minted |
| `source_message_ids` | the MDK/source ids observed for this inner event, bound as they become known; **at most one distinct value** (§8.7a); empty is a legitimate offline state, never a failure signal |
| `queue_state` | mirrors the outbox `state` column |
| `accepted_relays` | the concrete configured relay endpoints that accepted, possibly empty; never a count and never inferred |
| `canonical_at` | set only when MDK reports the event on the canonical branch |

`MDK_CANONICAL` is reached only when **both** `canonical_at` is set and
`accepted_relays` is non-empty. Canonical-branch membership without persisted
relay evidence leaves the row `SENT_LOCALLY`. This is a transport fact in either
case, never a peer receipt; v1 defines no peer ACK.

#### 8.7a One inner event, at most one source id

§8.4 requires every invalidation to map exactly one `source_message_id` to
exactly one inner event id, and makes a zero- or multi-match terminal. That is
only decidable if the outbound side never creates a one-to-many relation in the
first place, so the rule is stated here as well:

- an intended inner event MUST NOT become bound to more than one **distinct**
  `source_message_id`; observing the same transport message twice is one id, not
  two;
- a retry after an unclear or partial first delivery MUST be
  **transport-identical**: the identical transport message is re-driven. The
  inner event id, the MLS application message and the source id are all the same
  as before;
- re-encrypting or re-queueing the inner payload produces a **new** transport
  message. It is a new event, never a retry of the same inner id. Presenting it
  as one is terminal with `RETRY_NOT_TRANSPORT_IDENTICAL`;
- binding a second distinct source id to one inner event id is terminal with the
  same code, whatever produced it.

The R5 spike attempted the requeue variant, observed that it can mint a second
MLS transport id for one inner id and corrupt the single-valued
`source_message_id_hex` correlation, and removed that path again. That is why
the rule sits on the receipt rather than on the sender.

R6 then established that the pin **does** offer a transport-identical re-drive:
a durable `OutboundFanout` plus `resume_outbound_fanouts` republishes only the
still-open endpoints, under the same `message.id` and the same ciphertext bytes
(§11.1, criterion 1). The obstacle is therefore no longer a missing capability,
and this contract does not claim one.

What remains open is CruxCoach's ability to *rely* on it, which is a different
question and is gate `R6-G2-TRANSPORT-IDENTICAL-RETRY`. Two things keep it
shut: the gate itself, and R6 finding 1 — the resume path that performs the
re-drive emits no `PublishedApplicationMessage`, so a delivery accepted there
binds no source message id and §8.4's correlation can never be established for
it. §8.7b names the full set of conditions.

Until they are all met the fail-closed behaviour is exactly the one above: an
unclear or partial first delivery is re-driven identically or not at all.
Nothing else may be labelled a retry, and the durable outbox row stays visible
either way.

Retry backoff, the 30-day limit and the `FAILED` row of §8.7 are unchanged by
this; they never create a second identity for one inner event. What a scheduled
attempt may actually do is §8.7c and §8.7d: re-attempt a send that was never
authorised, as an ordinary first delivery, or nothing at all.

#### 8.7b Lease recovery before and after external effect

An expired lease is a statement about a **worker**, not about a **send**. It
says that nobody holds the row; it says nothing about how far that send got
before the worker died or the device rebooted. Requeueing every expired or
foreign-boot `IN_FLIGHT` row to `QUEUED` and driving its inner event again
therefore mixes two different situations: one where nothing has left the device,
and one where a first delivery may already have had external effect. The second
one is not a retry at all unless it is transport-identical (§8.7a), so it is
separated here.

The row persists which of these it is in `external_effect_phase`. Each value is
a statement about what **this device has committed locally**, never a claim
about the transport's own state:

| Phase | Meaning |
|---|---|
| `PRE_EXTERNAL_EFFECT` | the send has not been authorised to start: the write-ahead commit of §8.7c has not been proven, so no handover can have begun and nothing can have left the device |
| `EXTERNAL_EFFECT_UNCLEAR` | a handover was authorised, so it may have begun and may have taken effect; no durable evidence exists either way |
| `EXTERNAL_EFFECT_OBSERVED` | the transport message was emitted and that fact is durably evidenced by the typed receipt of §8.7 |

The phase is persisted, never inferred at recovery time and never derived from
`SendSummaryFfi`. It is advanced **ahead of** the step it describes, in a
committed local transaction of its own, under the write-ahead order of §8.7c.
That order — not the phase column by itself — is what makes reading
`PRE_EXTERNAL_EFFECT` after a crash a safe observation. A stored phase that
cannot be read or decoded is treated as `EXTERNAL_EFFECT_UNCLEAR`, because the
unclear case is the fail-closed one.

Once the lease is expired, the phase decides, and only these outcomes exist:

| Phase | Recovery | Row becomes |
|---|---|---|
| lease still live, any phase | `LEASE_HELD`: none — the holder keeps it | `IN_FLIGHT` |
| `PRE_EXTERNAL_EFFECT` | `RECOVER_QUEUED`: automatic; the row returns to `QUEUED` and the lease is released. Nothing is handed to the transport here; a later drain attempt does that for the same inner event, as a **first** delivery, through §8.7c (§8.7e) | `QUEUED` |
| `EXTERNAL_EFFECT_OBSERVED` with its one bound source id | `RESOLVE_FROM_RECEIPT`: automatic; the durable receipt decides the state and nothing is handed to the transport again | `SENT_LOCALLY` or `MDK_CANONICAL`, exactly as §8.7 earns it |
| `EXTERNAL_EFFECT_OBSERVED` without a durable receipt | `RECOVERY_BLOCKED`, `RECOVERY_EVIDENCE_INCOMPLETE` | `DELIVERY_UNCLEAR` |
| `EXTERNAL_EFFECT_UNCLEAR`, no replayable transport persisted | `RECOVERY_BLOCKED`, `RECOVERY_EXTERNAL_EFFECT_UNCLEAR` | `DELIVERY_UNCLEAR` |
| `EXTERNAL_EFFECT_UNCLEAR`, a transport record that is not restart-durable, or not repeatable | `RECOVERY_BLOCKED`, `RECOVERY_EXTERNAL_EFFECT_UNCLEAR`; its known source id stays visible | `DELIVERY_UNCLEAR` |
| `EXTERNAL_EFFECT_UNCLEAR`, identical transport persisted and replayable, `R6-G2-TRANSPORT-IDENTICAL-RETRY` still open | `RECOVERY_BLOCKED`, `RECOVERY_RETRY_GATE_OPEN` | `DELIVERY_UNCLEAR` |
| `EXTERNAL_EFFECT_UNCLEAR`, identical transport persisted and replayable, that gate closed, but no source-correlated receipt obtainable from the replaying path | `RECOVERY_BLOCKED`, `RECOVERY_SOURCE_CORRELATION_UNAVAILABLE` — **this is the state a closed gate alone would reach at the pin** | `DELIVERY_UNCLEAR` |
| `EXTERNAL_EFFECT_UNCLEAR`, identical transport persisted and replayable, that gate closed, **and** a source-correlated receipt obtainable | `RECOVER_TRANSPORT_IDENTICAL`: the row returns to `QUEUED` for a later replay of the identical MLS message under its one existing source id — again not sent from here (§8.7e) | `QUEUED` |

A transport record that is not restart-durable, or not repeatable, is not a
record for this purpose: the row stays blocked with
`RECOVERY_EXTERNAL_EFFECT_UNCLEAR`. Whatever source id the row already knows —
from its receipt or from that partial record — stays visible on the row in every
blocked case. It is shown, never re-driven, and never joined by a second one.

So a recovery may return a row to `QUEUED` without asking the user — putting it
back in line for a later drain attempt, never sending it here — in exactly two
shapes, and both are narrow:

1. it is **provable that no external effect has begun** — the phase is
   `PRE_EXTERNAL_EFFECT`, no source id is bound and no transport record exists.
   What makes that a proof rather than a hope is the write-ahead order of
   §8.7c: this phase can only survive where no handover was ever authorised; or
2. the **identical MLS transport message, together with its one source id, is
   persisted restart-durably and is repeatable** — the transport-identical
   branch of §8.7a, whose later replay re-drives that stored message and binds
   no second source id.

Neither shape reaches the network from the recovery itself; §8.7e says what the
drain attempt must still satisfy first. `RESOLVE_FROM_RECEIPT` needs no user
decision either, but it is not a third shape: it hands nothing to the transport
and only writes down what the durable receipt already proves.

Everything else is blocked. A blocked row moves to `DELIVERY_UNCLEAR` and stays
there:

- it is **visible**, with its own label, and is never pruned by age — the same
  rule as `FAILED`, and for the same reason: a silently vanished revoke would be
  a safety regression;
- it is **never automatically sendable**. No drain loop, no retry schedule and
  no later lease reclaim may hand it to the transport;
- it claims **neither delivery nor non-delivery**. The `attempts` counter and
  the 30-day limit do not apply to it, because it is not being retried;
- it is left only by an explicit user decision. Sending the same information
  again is a **new** intent with a new inner event id and a new row, never a
  retry of this one (§8.7a); giving up leaves the row visible as the record that
  the outcome was never established.

The second branch above is admitted only by a **conjunction**, and R6 satisfies
none of it by proving the capability. All three must hold together:

| Condition | Unmet reason | Meaning | State today |
|---|---|---|---|
| persisted identity | `REPLAY_NO_PERSISTED_IDENTITY` | this row carries a restart-durable, repeatable record of the identical transport message — a remembered id is knowledge, not a replayable message | row-dependent |
| product gate closed | `REPLAY_PRODUCT_GATE_OPEN` | `R6-G2-TRANSPORT-IDENTICAL-RETRY` (§11.1) is closed; a CruxCoach decision about relying on the capability, never a claim that the capability is missing | **open** |
| source-correlated receipt | `REPLAY_NO_SOURCE_CORRELATION` | the replaying path yields a receipt bindable to one source message id | **unavailable**: R6 finding 1 — the resume path emits no `PublishedApplicationMessage` |

Every unmet condition is reported, not just the first: this is a conjunction,
and naming one failure alone would make that one look decisive. The row the user
sees names the unmet condition nearest to the row itself, in the order above,
and each maps to exactly one blocked recovery reason:

| Unmet condition | Blocked recovery reason (§8.7b) |
|---|---|
| `REPLAY_NO_PERSISTED_IDENTITY` | `RECOVERY_EXTERNAL_EFFECT_UNCLEAR` |
| `REPLAY_PRODUCT_GATE_OPEN` | `RECOVERY_RETRY_GATE_OPEN` |
| `REPLAY_NO_SOURCE_CORRELATION` | `RECOVERY_SOURCE_CORRELATION_UNAVAILABLE` |

At the pin a row that carries a replayable record reaches the second of those;
a row that carries none never gets that far and reaches the first. The third is
the state a **closed gate alone** would reach, and it is written down precisely
so that closing `R6-G2-TRANSPORT-IDENTICAL-RETRY` cannot be mistaken for
opening the replay route: the outcome would still be a blocked, visible
`DELIVERY_UNCLEAR` row.

Closing the gate alone is therefore **not** sufficient, exactly as §8.7e already
says of the drain route. With the gate open every post-external-effect recovery
takes the blocked route with `RECOVERY_RETRY_GATE_OPEN`; were the gate closed
tomorrow it would take it with `RECOVERY_SOURCE_CORRELATION_UNAVAILABLE`, and a
row whose record is not restart-durable never reaches either question. The
harness enumerates all eight combinations so that no single condition can
quietly become decisive.

This is the same fail-closed rule §8.7a already states — an unclear or partial
first delivery is re-driven identically or not at all — applied to the one path
that could otherwise re-send silently, without a user ever asking for it.

Restrictive local effects are unaffected. A revoke, downgrade, block, removal or
disband takes local effect immediately and never waits for its outbox row
(§8.7), so a `DELIVERY_UNCLEAR` row never leaves the local side of a
safety-lowering action pending.

#### 8.7c Write-ahead before a non-atomic handover

§8.7b is only as good as the phase it reads, and the phase is only trustworthy
because of the order in which it is written. Handing a message to the transport
is not undoable and no local transaction spans that call: the database commit
and the handover are two separate steps on two sides of a boundary, and this
contract claims no atomicity across it. Writing the phase *after* the handover
would therefore leave the very window this section exists to close — a crash
after a possible external effect but before the phase commit, leaving a row that
still reads `PRE_EXTERNAL_EFFECT` and would be requeued and driven a second
time.

So the order is fixed, and it is write-ahead:

1. the row's phase is committed to `EXTERNAL_EFFECT_UNCLEAR` in a local
   transaction of its own, **before** any handover is attempted;
2. only once that commit is **proven** may the transport be called at all;
3. a commit that was never attempted, that failed, or whose result the caller
   cannot establish authorises nothing. Unknown is not committed, and no
   handover may begin;
4. `EXTERNAL_EFFECT_OBSERVED` is reached afterwards from durable typed-receipt
   evidence only (§8.7) — never from an in-memory observation, a live callback
   or a send summary.

The guarantee this buys is one implication, and the whole of §8.7b rests on it:

> if a recovering worker durably reads `PRE_EXTERNAL_EFFECT`, then the
> write-ahead commit was never proven, so the transport was never called, so
> nothing can have left the device.

The protocol is deliberately asymmetric. It over-approximates external effect
and never under-approximates it: it may leave `EXTERNAL_EFFECT_UNCLEAR` on a
send that never actually reached the transport, and it can never leave
`PRE_EXTERNAL_EFFECT` on one that may have. A false *unclear* costs one visible
row and an explicit user decision; a false *pre* would cost a silent second
delivery.

The crash windows of one send attempt, named by what durable local state still
establishes afterwards:

| Crash point | Durable phase found | Handover authorised | Recovery (§8.7b) |
|---|---|---|---|
| before the write-ahead commit | `PRE_EXTERNAL_EFFECT` | no | `RECOVER_QUEUED` — provably nothing was sent |
| inside the write-ahead commit | either `PRE_EXTERNAL_EFFECT` or `EXTERNAL_EFFECT_UNCLEAR` | no — the result was never proven, so nothing was called | `RECOVER_QUEUED` on the first, `RECOVERY_BLOCKED` on the second; both are safe |
| after the commit, before the handover | `EXTERNAL_EFFECT_UNCLEAR` | yes | `RECOVERY_BLOCKED` → `DELIVERY_UNCLEAR`, conservatively |
| during the handover | `EXTERNAL_EFFECT_UNCLEAR` | yes | `RECOVERY_BLOCKED` → `DELIVERY_UNCLEAR` |
| after the handover, before receipt persistence | `EXTERNAL_EFFECT_UNCLEAR` | yes | `RECOVERY_BLOCKED` → `DELIVERY_UNCLEAR` |
| after the typed receipt is persisted | `EXTERNAL_EFFECT_OBSERVED` | yes | `RESOLVE_FROM_RECEIPT`, handing nothing to the transport |

Durable local state cannot distinguish a crash *between* the commit and the
handover from one *inside* the handover, and it does not try to: both are
treated as *may have taken effect*. A requeue is possible only where no handover
was ever authorised, and even there it follows the phase actually found on disk
— which is why the crash inside the commit has two admissible outcomes rather
than one.

A **refusal at this gate** — step 3 above — means one thing only: no transport
call was made. It does *not* by itself say where the row now stands, because the
three refusing results differ exactly in what they establish about the commit:

| Refusal | Closed reason | What is established | Durable phase | What may follow |
|---|---|---|---|---|
| `not_attempted` | `WRITE_AHEAD_NOT_COMMITTED` | the commit was never started | provably `PRE_EXTERNAL_EFFECT` | a later attempt, as an ordinary first delivery |
| `failed`, provably | `WRITE_AHEAD_COMMIT_FAILED` | the commit ran and did not land | provably `PRE_EXTERNAL_EFFECT` | a later attempt, as an ordinary first delivery |
| `unproven` | `WRITE_AHEAD_COMMIT_UNPROVEN` | the caller never learned the outcome | **either** `PRE_EXTERNAL_EFFECT` **or** `EXTERNAL_EFFECT_UNCLEAR` | whatever the phase actually read allows — nothing is assumed |

Those three reason codes are the closed set of this gate. A refusal carries
exactly one of them and no free text, and there is no fourth: a result outside
`not_attempted`, `failed`, `unproven` and `committed` is not a value this
contract admits.

For the first two the row is still durably pre-effect, so a later attempt starts
this order again from the beginning and is an ordinary first delivery, not a
retry of anything. The write-ahead commit is idempotent — committing the same
phase for the same row again changes nothing — so repeating it is always safe.

For `unproven`, the commit may still have landed. No handover was authorised
either way, so nothing can have left the device; but the durable phase may
already read `EXTERNAL_EFFECT_UNCLEAR`, and this contract does not write it back
to pre-effect. There is no such transition, precisely because the conditions
that would make one safe are the ones §8.7d also lacks. Recovery therefore
follows the phase it actually finds:

- read as `PRE_EXTERNAL_EFFECT`: the ordinary first-delivery attempt is
  permitted, as above;
- read as `EXTERNAL_EFFECT_UNCLEAR`: the row fails closed exactly like any other
  unclear row — `RECOVERY_BLOCKED` → `DELIVERY_UNCLEAR` (§8.7b), never
  automatically requeued, even though in this particular history nothing was
  ever sent.

That asymmetry is the price of the order and it is paid deliberately: an
occasional visible unclear row for a send that never happened, rather than a
silent second delivery.

Those are the *only* refusals this section describes. A call that was authorised
and then came back refused is a different thing entirely, and §8.7d says what
happens to it.

Finally, a defensive rule for the pair a correct writer cannot produce. If a row
is durably `PRE_EXTERNAL_EFFECT` while some durable local evidence says a
handover was nevertheless authorised or attempted, the write-ahead order was
violated by whatever wrote it. Recovery then trusts the evidence rather than the
phase: the row is `RECOVERY_BLOCKED` with `RECOVERY_WRITE_AHEAD_INCONSISTENT`
and becomes `DELIVERY_UNCLEAR`. It is never requeued on the strength of a phase
that the rest of the record contradicts.

#### 8.7d After an authorised transport call

§8.7c is a gate in front of the call. `HANDOVER_AUTHORISED` means the transport
**may now be called** and nothing more: it is permission, not an outcome. What
the call then did is this section, and the two words must be kept apart, because
the same English word — *refused* — means opposite things on the two sides of
the boundary:

| Where | What it means | Phase | What may follow |
|---|---|---|---|
| refused **at the gate** (§8.7c) | no transport call was made at all | provably `PRE_EXTERNAL_EFFECT` after `not_attempted` or a proven `failed`; either phase after `unproven` | a later attempt as an ordinary first delivery from a pre-effect row; a row read as unclear stays unclear |
| refused **after authorisation** (here) | the call was made; the transport answered with a refusal | already `EXTERNAL_EFFECT_UNCLEAR` | nothing automatic |

Once the proven commit has authorised the call, `EXTERNAL_EFFECT_UNCLEAR` is
durable by construction — that commit is exactly what authorised it. So the
closed results of the call are:

| Call result | Outcome | Durable phase |
|---|---|---|
| the call returned a refusal | `POST_HANDOVER_UNCLEAR`, `CALL_REFUSED_AFTER_AUTHORISATION` | `EXTERNAL_EFFECT_UNCLEAR` |
| the call returned an error | `POST_HANDOVER_UNCLEAR`, `CALL_ERROR_AFTER_AUTHORISATION` | `EXTERNAL_EFFECT_UNCLEAR` |
| the call never answered | `POST_HANDOVER_UNCLEAR`, `CALL_WITHOUT_ANSWER` | `EXTERNAL_EFFECT_UNCLEAR` |
| the transport accepted it and produced no correlatable receipt | `POST_HANDOVER_UNCLEAR`, `ACCEPTED_WITHOUT_SOURCE_RECEIPT` | `EXTERNAL_EFFECT_UNCLEAR` |
| a durable typed receipt was persisted (§8.7) | `POST_HANDOVER_OBSERVED` | `EXTERNAL_EFFECT_OBSERVED` |

The fourth result is the one that looks like success and is not. R6 finding 1
(§11.1) established that the pin has a real path with exactly this shape: a
message whose **first** accepted delivery happens during
`resume_outbound_fanouts` is published, and no `PublishedApplicationMessage` is
ever emitted for it, because only `publish_queue`'s `ApplicationMessage` arm
emits one. The fanout outcome is reported; the typed receipt is not. Such a
delivery therefore never runs `finalize_published_app_message_source_retention`,
and no `source_message_id_hex` is ever bound to it.

§8.4 binds exactly **one** source message id to one inner event id, and every
downstream correlation in this contract depends on that binding existing. A
delivery that can never be correlated cannot satisfy it, now or later, so this
result is not a slow success — it is permanently unclear. Acceptance the adapter
cannot name is not observation, and the row is never labelled sent on the
strength of it. This matters far beyond an edge case: on a phone the process
being killed mid-send is the ordinary case, and it is precisely the case that
lands here.

None of the four unclear results transitions the row back to
`PRE_EXTERNAL_EFFECT`, and none of them starts another first delivery. A
returned refusal or error is a statement about the call, not durable evidence
that no external effect began anywhere behind it. Undoing the phase would be
safe only under two conditions together: the adapter would have to establish
**synchronously and durably** that no external effect whatsoever had begun, and
the write-back of `PRE_EXTERNAL_EFFECT` would itself have to stay fail-closed
across a crash. Neither is established at the pin, and this contract assumes
neither; nothing here may be read as a claim about what the MDK can or cannot
do.

The row therefore stays where §8.7b then finds it: `RECOVERY_BLOCKED` with
`RECOVERY_EXTERNAL_EFFECT_UNCLEAR`, shown as `DELIVERY_UNCLEAR` — visible, never
automatically sendable, claiming neither delivery nor non-delivery. Sending the
same information again remains what §8.7a says it is: a **new** intent with a
new inner event id and a new row, chosen explicitly by the user. Only durable
typed-receipt evidence moves the row on to `EXTERNAL_EFFECT_OBSERVED`, where
recovery resolves it from that receipt without handing anything over again.

#### 8.7e Recovery requeues; only a drain attempt sends

Lease recovery (§8.7b) is a **local state transition**: it writes the row's new
durable state and releases the lease. It performs no network I/O and calls no
transport, not even on the one path where it returns a row to `QUEUED`. The
`automatic` in "automatic recovery" describes that state change and the absence
of a user decision — never a send.

Reaching the network is always a separate, later **drain attempt**, and it runs
the write-ahead order of §8.7c from the beginning: commit the phase, prove the
commit, and only then call the transport. Recovery cannot shortcut that gate,
because a requeued row is in exactly the position a fresh one is in.

What a recovered row still needs is therefore named per outcome:

The step below is what *this* recovery leaves for the worker that ran it; it is
not a verdict on the row for all time.

| Recovery outcome | Next step | What the drain attempt must satisfy |
|---|---|---|
| `LEASE_HELD` | `NONE` | this worker touches nothing; the holder continues under its own §8.7c gate |
| `RESOLVE_FROM_RECEIPT`, `RECOVERY_BLOCKED` | `NONE` | nothing may reach the network automatically at all |
| `RECOVER_QUEUED` | `WRITE_AHEAD_FIRST_DELIVERY` | the §8.7c gate: a proven commit of `EXTERNAL_EFFECT_UNCLEAR` before the first call |
| `RECOVER_TRANSPORT_IDENTICAL` | `WRITE_AHEAD_TRANSPORT_IDENTICAL_REPLAY` | the §8.7c gate **and** the same persisted MLS message under its one existing source id **and** a closed `R6-G2-TRANSPORT-IDENTICAL-RETRY` **and** a source-correlated receipt obtainable from the replaying path |

The last row is defined and unreachable, now for two independent reasons.

The gate is still open (§11.1), so no drain attempt may take the replay route
and recovery may not reach that outcome. But R6 changed the character of the
second reason: the identical re-drive is no longer merely unproven — it was
demonstrated — and the pin performs it through `resume_outbound_fanouts`, which
finding 1 shows emits no `PublishedApplicationMessage`. The source-correlated
receipt this route requires is therefore not obtainable at the pin at all.

Closing `R6-G2-TRANSPORT-IDENTICAL-RETRY` on its own would consequently **not**
make the replay route usable here: a successful replay would still land on
§8.7d's `ACCEPTED_WITHOUT_SOURCE_RECEIPT` and stay unclear. Both conditions gate
the route independently, and the harness asserts that flipping either one alone
leaves it unreachable.

This is what keeps §8.7c honest. If recovery could send, a requeued row would
travel to the transport without a write-ahead commit, and the very window §8.7c
closes would reopen behind it.

#### Outbox state to user-visible label

The label vocabulary is closed and the mapping is total:

| Outbox state | User-visible label |
|---|---|
| `QUEUED` | **Queued locally** |
| `IN_FLIGHT` | **Queued locally** |
| `SENT_LOCALLY` | **Queued locally** |
| `MDK_CANONICAL` | **Published to at least one configured relay** |
| `FAILED` | **Not sent — retries exhausted** |
| `DELIVERY_UNCLEAR` | **Delivery unclear — not retried automatically** |
| `SUPERSEDED` | none; the row is audit-only and is never shown as an active send |

Every one of the four visible labels is shown together with **Not
peer-confirmed**. The published label says *at least one* configured relay
precisely because the persisted evidence is a non-empty `accepted_relays` list,
not a promise about all configured relays. A `FAILED` row stays visible until
the user acts on it; it is never pruned by age and never silently disappears.

The unclear label is the honest wording for §8.7b: it states that the outcome of
one first delivery was never established and that nothing is being re-sent for
it. It must not be softened into *queued*, because the row will never drain by
itself, and it must not be hardened into *sent* or *not sent*, because neither
is known.

Forbidden supersession pairs are explicit:

| Prior row | Superseding row | Allowed |
|---|---|---|
| restrictive (revoke, downgrade, narrowing set, `NONE`) | permissive (widening set, upgrade) | **never** |
| permissive | restrictive | yes |
| same object, lower restriction | same object, higher restriction | yes, after durable enqueue |
| same object, higher restriction | same object, lower restriction | **never** |

A restrictive local action takes local effect immediately and does not wait for
its outbox row. A permissive local state change takes effect only after the
canonical self-delivery of its own event. Enqueueing authorises nothing.

### 8.8 Trusted time across reboot

Expiry, retirement deadlines and purge deadlines are evaluated against a
persisted, reboot-aware clock, never against the raw wall clock. A high-water
mark alone is not sufficient: with `high_water = 100`, a deadline at `200` and a
wall clock parked at `0`, `max(wall, high_water)` stays at `100` forever, which
*extends* the grant and *postpones* the purge indefinitely. That is exactly the
outcome this section forbids, so the model is fail-closed rather than
best-effort.

Three values are persisted together, inside the same SQLCipher transaction as
the state they gate:

| Value | Meaning |
|---|---|
| `boot_id` | opaque identity of the current boot; changes on every restart of the device |
| `monotonic_anchor` | the monotonic reading taken when this `boot_id` was anchored |
| `wall_high_water` | the highest effective time ever observed |

Within one boot:

```text
effective_now = max(wall_now, wall_high_water + (monotonic_now - monotonic_anchor))
```

and the result is persisted as the new `wall_high_water`, with `monotonic_now`
as the new anchor. `effective_now` therefore advances with real elapsed time
even while the wall clock is wrong, and never decreases.

The clock enters **`TIME_UNTRUSTED`** on any of:

| Condition | Code |
|---|---|
| the persisted `boot_id` differs from the current one | `BOOT_CHANGED` |
| the monotonic reading decreased within one boot | `MONOTONIC_RESET` |
| `wall_now < wall_high_water` | `WALL_ROLLBACK` |
| monotonic time advanced by at least 900 s while the wall clock did not advance at all | `WALL_FROZEN` |

Those four codes are the **closed set** of this section, exactly as §8.4, §8.6
and §8.7c close theirs. A distrust carries one of them and no free text, and no
peer-supplied or relay-supplied value ever enters the set. The code is not a
diagnostic: it is what decides whether a time-bounded grant stays hidden and
whether a purge whose due-ness cannot be established is due now, so an
unrecognised fifth value would be a silent widening of exactly the window this
section exists to close.

`TIME_UNTRUSTED` is fail-closed and immediate:

- every time-bounded grant is hidden, in both directions, as if expired;
- permissive sends and permissive applies are blocked;
- every purge whose due-ness cannot be established is immediately due;
- restrictive local actions — revoke, downgrade, block, remove, disband — are
  **never** blocked by it, because blocking them would lower safety.

The only exit is an explicit **trusted-time re-initialisation**: the client
re-anchors `boot_id` and `monotonic_anchor` and sets
`wall_high_water = max(wall_high_water, trusted_wall_now)` from the device's own
trusted time source, in one committed transaction, visibly to the user. A peer's
`issued_at`, a relay timestamp and any other network-supplied time are **never**
inputs to that re-initialisation, and no automatic recovery exists.

The clock never orders application events. §8.1 still forbids `issued_at`,
arrival order and epoch as ordering inputs.

## 9. Group bootstrap and topology

### 9.1 Deterministic creator and idempotent attempt

Only `endpoint_low` creates the group.

A QR code, share link or other out-of-band action by `endpoint_high` transports
only an existing account identifier and a local UI intention. It is not a signed
CruxCoach protocol message, it is never published, and it introduces no second
social transport. There is **no** `cc.connection.intent.v1` object, no kind 1221
and no network bootstrap intent of any kind in v1. A Welcome whose actual
creator is not `endpoint_low` is never activated and its derived generation is
terminally rejected.

Creation runs through a dedicated, locally idempotent adapter path:

```text
create_private_sharing_group(attempt_id, endpoint_high) -> mdk_group_id
```

- `attempt_id` is durably persisted together with the resulting
  `mdk_group_id` **before** the first externally observable Welcome effect, in
  the adapter's own store; after a crash or retry the same `attempt_id` returns
  the same group instead of creating a second one.
- At the MDK pin, `create_group(..., members = [], ...)` produces a canonical
  self-only group and `invite_members` is a separate call, so a create can be
  committed and durably bound before any peer is invited. Whether that binding
  actually survives every crash point is a spike gate, not a property claimed
  here.
- An explicit user abort terminalises that attempt. A new user attempt gets a
  new `attempt_id`; an aborted `attempt_id` is never reused.
- `endpoint_low` then invites `endpoint_high` and promotes it, subject to §9.4.
  Permanent promotion failure offers visible abort and a fresh explicit attempt,
  never a one-admin active group.

### 9.2 Bootstrap marker profile

The `0x8001` group profile component is engine-owned and mandatory at the MDK
pin. FEAT-062 therefore does not omit it; it fixes its exact content and uses it
as the Welcome-authenticated bootstrap marker that makes the crash-safe
rediscovery in §9.1 possible.

`name` is exactly:

```text
CruxCoach private sharing
```

`description` is exactly the RFC 8785 JCS serialisation of this closed object,
with no trailing byte:

```json
{"attempt_id":"<uuid4>","endpoint_high":"<hex32>","endpoint_low":"<hex32>","protocol":"com.cruxcoach.private-sharing/bootstrap/v1"}
```

| Member | Requirement |
|---|---|
| `attempt_id` | the lowercase canonical UUIDv4 from §4.2, CSPRNG-derived |
| `endpoint_low`, `endpoint_high` | the two sorted endpoints, 64 lowercase hex each |
| `protocol` | exactly `com.cruxcoach.private-sharing/bootstrap/v1` |

The object is closed: an extra member, a missing member, a non-JCS byte order,
an uppercase hex value or any other version invalidates the marker.

The marker is not localisable, not user-editable and never rendered as a chat
profile. Both endpoints are admins and MDK permits an admin to propose a profile
update, so immutability is enforced fail-closed rather than prevented: any
observed change to `name` or `description` after activation terminalises the
generation.

Both clients recompute the marker's endpoints against the sorted endpoints they
derived themselves. A marker whose endpoints, protocol or shape do not match is
not a FEAT-062 group; it is not activated and no application event from it is
applied.

At the MDK pin the profile's `name` and `description` come from the group
creation request and cannot be supplied as a caller `app_components` override —
the engine rejects that as an attempt to override engine-owned components. The
dedicated creator path in §9.1 therefore has to set them through the creation
call itself.

### 9.3 Exact component placement

The GroupContext `app_data_dictionary` has the upstream `app_components` entry
`0x0001`. Its required Marmot component list is exactly, in ascending order:

```text
[0x8001,0x8003,0x8004,0x8009,0x800c]
```

`0x8001`, `0x8003` and `0x800c` are non-negotiable engine defaults at the MDK
pin, and `0x8009` is added for the current protocol profile; the exact minimal
profile keeps them rather than pretending they can be dropped.

The GroupContext `required_capabilities` also requires upstream extension type
`app_data_dictionary` (`0x0006`) and proposal type `app_data_update` (`0x0008`).
Every current leaf/KeyPackage advertises both capabilities. These are MLS
capability requirements, not additional required application components.

The GroupContext contains exactly these private component data entries. Ids and
placement are **invariant**; two of the values are **phase-dependent**, because
at the MDK pin the creator is the implicit sole admin of the group it creates,
promotion to admin is a separate commit, and a disband commit rewrites both:

| Id | Placement and value |
|---|---|
| `0x8001` | GroupContext dictionary; the exact immutable bootstrap marker from §9.2 |
| `0x8003` | GroupContext dictionary; admin-policy list, phase-dependent, sorted by raw key bytes |
| `0x8004` | GroupContext dictionary; signed Nostr routing state with random routing id and non-empty canonical relay list |
| `0x800c` | GroupContext dictionary; exactly one byte, phase-dependent |

| Phase | Leaves | `0x8003` | `0x800c` |
|---|---|---|---|
| self-only create, before invite | `[endpoint_low]` | `[endpoint_low]` | `0x00` |
| invited, before canonical promotion | `[endpoint_low,endpoint_high]` | `[endpoint_low]` | `0x00` |
| `ACTIVE` | `[endpoint_low,endpoint_high]` | `[endpoint_low,endpoint_high]` | `0x00` |
| disbanded | `[committer]` | `[committer]` | `0x01` |

The `0x800c` value `0x00` is Marmot's *group* lifecycle "active" and is present
from creation, before any invite, acceptance or promotion. It is one of the
eight conditions in §9.5 and never on its own evidence that a FEAT-062
generation is `ACTIVE`: a self-only group carries `0x00` while the generation is
still `PROVISIONING`, and only the applied acceptance plus the canonical
promotion plus the full gate move it. Conversely `0x01` is terminal in every
phase.

A missing `endpoint_high` admin **before** the canonical promotion is the
expected provisioning state and MUST NOT be treated as a fault. After the
promotion, an admin policy that is anything other than exactly both endpoints is
terminal, in either direction: a missing high admin and a high admin that
appeared before the acceptance was applied are both `TOPOLOGY_INVALID`. The
disbanded row is listed for completeness only; reaching it is terminal under
§9.7.

The GroupContext dictionary has no upstream `0x0002` entry; at this pin such an
entry would enable SafeAAD framing that MDK does not implement.

`0x8009` is required by GroupContext but its data is **not** valid there. Each
current LeafNode dictionary has upstream `0x0001` `app_components`, upstream
`0x0002` `safe_aad`, and exactly one private `0x8009` account-identity proof for
its own BasicCredential and MLS signature key. The `0x0001` support list
contains `0x0001` itself and all five required private ids; its other advertised
support may be broader. The pin encodes the `0x0002` support list as empty,
meaning SafeAAD framing is understood but not enabled. The proof is 104 bytes
and is invalid in GroupContext, in a KeyPackage-level dictionary or in
GroupInfo; the LeafNode embedded in a KeyPackage carries it in that LeafNode's
own dictionary. A leaf support advertisement is capability only and does not
activate group state.

No private component entry for image (`0x8002` or `0x8007`), message retention
(`0x8005`), agent stream (`0x8006`), encrypted media (`0x8008` or `0x800b`) or
multi-device join authorization (`0x800a`) is present or required. A message
retention component is forbidden **even with the value 0**: an absent `0x8005`
and a present `0x8005` carrying zero are different canonical group states, and
only absence keeps the effective retention outside peer control. Any other
private GroupContext component entry, and any unknown component id, also
violates this exact profile.

A missing, additional or wrongly placed component terminalises the generation
fail-closed. In-protocol data changes inside the required components — a routing
update, an admin-policy re-signature — stay valid only while MDK reports them
canonically and schema-valid, and the `0x8001` marker stays byte-identical. MDK
still owns and preserves the signed bytes; the CruxCoach generation simply
stops.

At the MDK pin the ordinary public group-create path additionally injects an
agent-stream policy and an encrypted-media component. That path therefore cannot
produce this profile, which is why §9.1 requires a dedicated creator and why
gate 2 of the spike exists.

### 9.4 Provisioning acceptance and promotion

Between creation and `ACTIVE` the generation is `PROVISIONING`. In that state:

1. `endpoint_high` receives the Welcome. Nothing happens automatically: an
   unaccepted foreign Welcome is not a sharing group, though it MUST be visibly
   refusable.
2. After an explicit local acceptance action by the user of `endpoint_high`, and
   only then, that client may send exactly one `cc.generation.accept.v1` (§6.1).
3. `endpoint_low` promotes `endpoint_high` to admin only after that acceptance
   event has been canonically applied.
4. Every relationship, grant and projection event stays both send-blocked and
   apply-blocked for the whole of `PROVISIONING`.

There is no candidate-set comparison, no ranking and no claim that either side
knows a complete candidate set.

### 9.5 Activation gate

Send **and** apply are blocked until one session-consistent local MDK read proves:

1. exactly two current leaves, one account proof for `endpoint_low` and one for
   `endpoint_high`;
2. exactly those two accounts as active admins;
3. the exact required list and placement in §9.3, including the byte-identical
   `0x8001` bootstrap marker of §9.2;
4. signed lifecycle `active`;
5. local MDK lifecycle `Stable`;
6. the actual creator was `endpoint_low`;
7. the canonical `cc.generation.accept.v1` from `endpoint_high` was applied;
8. no other nonterminal sharing group is known for the endpoint pair.

Before that gate passes the generation is `PROVISIONING`. A missing
`endpoint_high` admin during `PROVISIONING` is the expected state of §9.3 and is
not a fault; only conditions 1–8 above decide activation, and a promotion that
appeared before the acceptance was applied fails condition 7.

`PendingPublish`, `Merging`, `Recovering` or unresolved convergence holds new
send/apply but is not terminal by itself; so does an unresolved commit rollback
under §8.4a. A third or duplicate leaf, wrong account, wrong/missing/extra admin
for the phase, wrong component, any retention component (including a signed
zero), wrong creator, duplicate group, signed lifecycle not active, MDK
quarantine or `Unrecoverable` is terminal. Existing data is hidden and the
generation never becomes sendable again.

### 9.6 Duplicate discovery

Duplicate groups are never ranked or selected. Discovery at either client of
more than one nonterminal sharing group for the same sorted endpoints —
accepted, or locally bound to FEAT-062 — terminalises every affected generation,
even if one was already active or the duplicate appears after a long delay. All
data is hidden and a new explicit `endpoint_low`-created attempt is required.

### 9.7 Disband and terminal lifecycle

Marmot's `0x800c` lifecycle has exactly one transition, `active -> disbanded`,
and any active admin may commit it. Both endpoints of a FEAT-062 group are
admins, so either of them can disband it.

- **Normal disconnect** uses disband. For a bilateral group it is the cleaner
  terminal action than member removal because the lifecycle state is
  *absorbing*: after the disband commit is selected, clients do not process
  later group traffic, do not select a later branch and do not rejoin the same
  group id, so there is no subsequent traffic or routing observation on either
  side. The disband commit is itself a removal at the wire level — at the Marmot
  pin a valid disband commit removes **every candidate-parent leaf except the
  exact committing leaf**, including other leaves of the committer's own
  account, and replaces the admin-policy list with exactly the committer. That
  is precisely why no separate MDK removal action is needed afterwards, and it
  is the reason to prefer disband, not a claim that nobody was removed.
- **A hostile unilateral disband** is an authentic, authorised terminal action,
  not an attack to be repaired. The generation becomes terminal, all data is
  hidden and purged on the block/remove deadline, and its tombstone is sticky.
- **Member removal** stays available only as recovery or loser cleanup, with a
  stated reason; it is not the default disconnect.
- A signed lifecycle value other than `active` is terminal in both directions:
  no client may reopen a disbanded generation, and reconnecting requires a new
  explicit `endpoint_low`-created group.

## 10. Grant retirement, purge, status and restore

| Cause | Required result |
|---|---|
| revoke | hide immediately; purge that grant's data within 24 h |
| `FRIEND` to `ACQUAINTANCE` | permanently retire and hide detail; purge detail within 24 h; keep authorised summary and group |
| block or remove | terminal generation; hide all; purge within 24 h; then best-effort MDK removal |
| expiry | hide at expiry; purge no later than 90 days after expiry |
| superseding set | old grant hidden, retired and purged in the same committed local transition |
| disband | terminal generation; hide all; purge within 24 h; no MDK removal attempt is needed |

An increase after downgrade and a new grant after revoke/expiry never revive old
data. Purge deadlines are per originating grant and per field: a narrower or
newer grant never extends the purge deadline of fields already removed under an
older grant, and never cancels a running purge. A recipient may retain plaintext
already copied; neither application events nor MDK removal can erase it
retroactively.

The maximum grant duration (90 days from issue) and the post-expiry purge
deadline (90 days after expiry) are two distinct 90-day windows and MUST NOT be
conflated. Every window in this contract is evaluated against the monotonic
high-water clock of §8.8.

A grant request is not part of v1. There is no request body, and a party holding
no valid grant cannot cause one. After a revoke, an expiry or a `RECONCILING`
object, the only paths forward are a fresh explicit grant by the publisher or,
where the generation is terminal, a fresh generation. No stale request and no
stale snapshot survives that boundary.

Outbound UI status is exactly the closed vocabulary of §8.7: **Queued locally**,
**Published to at least one configured relay**, **Not sent — retries
exhausted**, **Delivery unclear — not retried automatically**, each shown
together with **Not peer-confirmed**. A `SUPERSEDED` row is audit-only and is
never presented as an active send. The published label is earned only by a
persisted typed receipt naming at least one concrete accepted relay endpoint; it
means neither *all configured relays* nor that a peer received or read the
event. v1 has no peer ACK. The unclear label is shown exactly for the blocked
recovery of §8.7b and offers no automatic re-send action.

A backup restores no MLS group and carries no MDK keychain or MLS secret. On a
new device, application data is inactive and read-only, every restored outbox
row is unsendable, and the outbox drains nothing. Blocks and terminal tombstones
remain sticky. Reconnection is a visible new `endpoint_low`-created group and a
newly derived generation id.

Restore is **preserve-or-NULL** for every recipient-specific row id. A restored
`session_uuid` or `plan_uuid` is either exactly the value that was backed up or
`NULL`; restore MUST NOT mint a new one, because a minted id would silently
address a different remote row. A `NULL` id makes its row ineligible for
sending, permanently for sessions and until an explicit transactional share
selection for plans.

The same rule governs product size: an unset or cross-brand `product_size_id`
stays `NULL`, makes the affected item ineligible and fails closed. It is never
defaulted to a plausible value.

Whether the existing FEAT-002 encrypted backup covers the Nostr account key is a
FEAT-002 question that this contract does not answer and does not assume. What
it does state is that restoring any backup grants no sharing rights: restored
state is closed, and no identifier is minted by restore.

`foreign_keys = ON` MUST be active and verified for the production SQLCipher
connection before any FEAT-062 migration or query runs, and a failed activation
or verification MUST fail application startup. Enabling it only in tests is
explicitly insufficient.

## 11. Required adapter contract

The current pins do not satisfy this table; it is the eight-gate spike target.

| Required adapter surface | Contract |
|---|---|
| group creator | dedicated `endpoint_low`-only minimal-profile creation with the exact `0x8001` marker, durable `attempt_id` binding, promotion and leaf-exact roster evidence |
| outbound | only kind 1220/exact tags/exact content; full pinned encoder bytes; the typed restart-durable receipt of §8.7 |
| inbox | dedicated snapshot/live/replay with full provenance and retention-independent pre-ACK persistence |
| ACK | callable only after durable CruxCoach commit, and revision-bound so a later invalidation reopens the row |
| invalidation | exact `{source_message_id,inner_event_id,reason}` mapping, durable across restart |
| commit rollback | `GroupStateInvalidated`/`ForkRecovered`/`CommitRolledBack` surfaced with the rolled-back commit id and its commit class, durable across restart, so §8.4a can decide |
| isolation | no chat surface, group list exposure, unread, Markdown, push, second relay pool or cursor |

At MDK pin `101d7994`, the closed `AppMessageIntent` send APIs do not accept
caller-supplied kind/tags/content. The existing FFI message record has rich
snapshot/live/replay data but omits the source message id. `SendSummaryFfi`
carries an untyped `message_ids` list which, for the app-message path, holds the
**inner** event id and no source-id relation at all, while `published` counts
transport reports rather than accepted relays and the accepting endpoints on
`TransportPublishReport.accepted` never cross the FFI. Invalidation names the
source id. Ordinary group creation adds components outside §9.3. No sentence in
this contract claims otherwise, and none of these is treated as evidence for a
user-visible publication claim.

Four properties are explicitly **not** provable at the pin without exceeding the
agreed patch budget, and each is a binary No-Go gate rather than an assumption:

1. **Crash-safe outbound correlation.** `inner_event_id` and its one resulting
   `source_message_id` must stay correlated across every crash point, including
   the point where the event was already published externally but not yet
   persisted by the host. A purely synchronous in-memory receipt association is
   a fail.
2. **Durable invalidation reason.** The pin persists the invalidated boolean but
   emits the reason only in memory after the canonical commit. The reason must
   become restart-durably correlated, or the gate fails. No exactly-once
   invalidation notification may be claimed.
3. **Offline/queued receipt.** A queueing send path must either refuse offline
   queueing outright or persist `Queued { intent_id, inner_event_id }` and later
   bind that one source id restart-durably (§8.7a). Empty, fabricated or undefined receipt
   behaviour is a fail.
4. **Cross-store crash rail.** No transaction spans the engine store, the
   adapter store and the CruxCoach database. Every crash point of the rail
   `engine canonical commit -> repeatable pending ingress -> adapter inbox
   revision -> CruxCoach transaction -> host ACK` must be proven. The existing
   protocol-level ACK MUST NOT be reinterpreted as the host ACK.

If closing any of these requires changes beyond additive work in the adapter,
storage and FFI layers — that is, changes to the engine, session or account
crates — that is a scope-expanding No-Go requiring a new independent review
decision, not a silent part of a small spike.

### 11.1 Open MDK-capability gates (R6)

**R5** was the first executed throwaway adapter spike against MDK
`101d79946cff6c82d2b849d3b70902a7af7bac08`. Its own GO/NO-GO record is an
overall **NO-GO**: the throwaway adapter spike, the move of the product design
to `design-locked`, the release and a private long-term fork are each No-Go.

**R6** is the second, executed against the same MDK pin under Marmot spec pin
`4ad4ae21479c3f3fa9950c6fc4556a76941a62e1`. The binding record is its
`GO-NO-GO_1.md` together with the patch whose SHA-256 is
`820335ab329b58d716415b203b8511257546fa31ec2dfc44634db53c8cce0e36`; the
superseded first attachments are not a source for anything here. R6 is likewise
an overall **NO-GO** on all four axes.

R6 did, however, change what is known. It produced real runtime evidence — real
MLS sessions, a real SQLCipher store, a real MockRelay — for five of the six
capabilities R5 left open, and it **refutes** R5's finding that the pin has no
transport-identical retry. This section therefore records three things per gate,
and keeps them apart:

- **capability** — what the pin was demonstrated to do;
- **evidence scope** — the surface that demonstrated it. R6 ran no
  instrumentation, so nothing here is native, on-device or release evidence, and
  those scopes are forbidden values rather than merely unused ones;
- **status** — whether that is enough to close the gate for CruxCoach.

All eight remain `OPEN`. Each is a **technical gate**, not an assumption: while
it is open the stated fail-closed behaviour applies, and no document, test or UI
string may claim the capability is discharged.

| Gate | What is open | R5 evidence | R6 evidence | Capability / scope | Blocking remainder | Fail-closed while open |
|---|---|---|---|---|---|---|
| `R6-G1-DURABLE-PROJECTION` | a retention-independent long-lived CruxCoach projection/inbox | criterion E PASS: a real retention sweep deleted the expired raw custom row and kept the later survivor | R6 decided nothing here; moving the product design to `design-locked` stays NO-GO for R5's reason | absent / none | no durable projection exists, and finding 1 means a resume-accepted row never reaches `finalize_published_app_message_source_retention` | the raw app store is never authority; an item gone before host ACK is terminal with `PROJECTION_AUTHORITY_INVALID` (§8.5a) |
| `R6-G2-TRANSPORT-IDENTICAL-RETRY` | re-driving the identical transport message after an unclear or partial first delivery | criterion C FAIL: no such capability on the publish-failure branch; the attempted requeue could mint a second MLS transport id and was removed | criterion 1 PASS, refuting R5: a process killed mid-fanout re-publishes exactly the two open endpoints after restart with identical `message.id` and identical ciphertext bytes, epoch unchanged, second resume a no-op, fanout deleted; a definitive rejection is terminal and never retried | proven at host scope / host Rust + MockRelay | finding 1: the resume path emits no `PublishedApplicationMessage`, so a resumed first delivery has no source-id correlation at all; finding 3: no on-demand resume; finding 2: a refused endpoint is never served again | retry identically or not at all; a second distinct source id for one inner id is terminal with `RETRY_NOT_TRANSPORT_IDENTICAL` (§8.7a); an expired lease over a row whose external effect may already have begun is never requeued automatically and becomes `DELIVERY_UNCLEAR` (§8.7b); an acceptance with no correlatable receipt is `ACCEPTED_WITHOUT_SOURCE_RECEIPT` (§8.7d) |
| `R6-G3-BROADCAST-LAG-REPLAY` | proving a `RecvError::Lagged` overflow is recovered from the store without loss or duplication | criterion B FAIL: no harness overflows the private broadcast at all | criterion 3 PASS, refuting R5: the real 1024-slot broadcast overflowed — 4200 published, 2048 delivered, 2152 genuinely lost — and the custom event was recovered from the store exactly once, with no second recovery in a 3s trailing window | proven at host scope / host Rust, crate-internal | the test is `#[cfg(test)]` against a private broadcast sender with synthetic filler events; no adapter-reachable surface drives that recovery | correctness rests on durable replay only; an unclosed gap holds with `HOLD_REPLAY_INCOMPLETE` (§8.5a) |
| `R6-G4-EXACTLY-ONCE-SCOPE` | exactly-once live emission and a raw row count of 1 | criterion C1 PARTIAL: the wait returns on the first match, watches no second window and counts no raw rows | criterion 2 PASS, superseding the PARTIAL: two rounds of deliberate redelivery pressure (`catch_up_accounts`, then `repair_full_history`) each with a drained 3s window, live counter exactly 1, exactly one raw row per side with transport-id correlation | proven at host scope / host Rust + MockRelay | bounded to one host process against a MockRelay: never relay, network or peer-receipt exactly-once, and silent about the resume path, which produces no receipt to count | only §11.2's local claim is permitted |
| `R6-G5-CUSTOM-REORG` | placing a custom payload deterministically on the losing fork and observing its invalidation | criterion C FAIL: no such harness exists and no stub was substituted | criterion 4 **NOT MET** — the criterion R6 itself failed. The app half is real: tombstone by transport id with reason `LosingBranch`, firehose fields unchanged, empty timeline, no effective reemit, one row surviving close and reopen. The fork is not | partial / host Rust + MockRelay | the losing-branch decision is injected into the ingest observer, not produced by a relay- or fork-driven partition; this is R6's own blocking open gate 1. Finding 4: the same event via `observe_drained_session_events` publishes without running the projection dispatch | every stored-event invalidation and every zero/multi source mapping terminalises (§8.4) |
| `R6-G6-UNIFFI-FORWARDING` | a public UniFFI call that really forwards caller kind/tags/content and refuses reserved kinds | criterion A/F NOT TESTED: the smoke test proves public callability and an invalid group hex only | criterion 5 PASS: a hostile payload — padded spaces, multi-value tag, emoji, Markdown-like content — crosses exported bindings byte-identically between two identities, `content_tokens` empty, both raw rows agreeing; all eleven reserved kinds refused fail-closed with no row | proven at host scope / host Rust + MockRelay | construction used the crate-internal `Marmot::open` behind its dev/test loopback gate, because the exported constructors reject loopback relays correctly; the fully exported constructor-to-send path is unexecuted | no public outbound FFI surface is treated as existing (§11) |
| `R6-G7-INBOUND-NATIVE-DELIVERY` | executing the inbound local/native `NewMessage` path for custom traffic | criterion D NOT TESTED: only the generic `MessageReceived` firehose was observed | criterion 6 PASS: `subscribe_notifications` — the source the UniFFI subscription and the native callback share — was subscribed before the send and stayed empty for the inner id, as did `collect_notifications_after_wake` (the iOS NSE surface); a following chat message produced `NotificationTrigger::NewMessage` as a positive control | proven at host scope / host Rust + MockRelay | the observed surface is the shared host-side stream, not the native callback on a device; until G8 runs no isolation claim rests on a native surface | isolation is asserted, never assumed; §2.4 stays a spike target |
| `R6-G8-INSTRUMENTATION-RUN` | an on-device instrumentation run on Android arm64 | criterion F NOT TESTED: `connectedDebugAndroidTest` exited 1 with `No connected devices!` | criterion 8 NOT TESTED, honestly recorded: no device, no emulator package, no AVD, no virtualisation (`/dev/kvm` absent, no `vmx` or `svm` flag). arm64-v8a bindings were built; the run was not simulated | not tested / none | unchanged, and the reason every other gate's evidence stays host-scope | FEAT-062 §5 gates 2 and 4–7 stay open; a packaged-but-unrun APK is not evidence |

Two further R6 results are recorded because a release decision depends on them,
even though neither is a gate in this table: five pre-existing `marmot-app`
relay integration tests fail deterministically — at the bare pin and at
`origin/master` alike, so provably no R5 or R6 regression, but a red gate all
the same — and the consumer APKs were not reproduced, because that project is
not part of MDK and was not supplied.

The checked-in Python harness under `docs/specs/social/scripts/` is the **S9
specification stage**. It executes these rules as a spec oracle and keeps the
documents consistent with them. It is not R6 evidence, it runs no native or
on-device code, and it can never move a gate out of `OPEN`.

### 11.2 The only permitted exactly-once claim

CruxCoach claims exactly-once **local reduction**, and nothing else:

| Scope | Claim |
|---|---|
| CruxCoach reduction keyed by typed ids | permitted |
| duplicate inner event id | permitted — a no-op |
| duplicate source message id | permitted — a no-op |
| live emission | **forbidden** |
| relay delivery | **forbidden** |
| network delivery | **forbidden** |
| raw row count | **forbidden** |
| peer receipt | **forbidden** — v1 has no peer ACK |

The permitted claim is a statement about the reducer and the idempotency row of
§8.5: the same typed identity reduces once, no matter how often it is delivered
or replayed. It says nothing about how many times an event was emitted,
transmitted, accepted by a relay or stored raw.

A claim outside the permitted three is refused with exactly one closed code,
`EXACTLY_ONCE_SCOPE_EXCEEDED`, and no free text. That set has one member because
there is one way to get this wrong — reaching past local reduction — and naming
it here is what keeps the code two-sided: the golden corpus decides it per
scope, and this section is where the specification states it. A refusal is a
statement about the **claim**, never about the pin: the five forbidden scopes
stay forbidden whatever an adapter is later shown to do.

This table is about what CruxCoach may **claim**, and it is not a report of what
the pin does. The distinction became load-bearing with R6, which observed both
exactly-once live emission and a raw row count of 1 under two rounds of
deliberate redelivery pressure (§11.1, criterion 2). Every row above is
unchanged by that, and a `forbidden` here has never meant `unproven`:

- the observation was one host process against a MockRelay, while the forbidden
  claims are about relays, networks and peers it never touched;
- `R6-G8-INSTRUMENTATION-RUN` never ran, so nothing is native or on-device;
- and a first delivery accepted during resume produces no receipt at all
  (§11.1, finding 1), so there are deliveries with nothing to be exactly-once
  about.

`R6-G4-EXACTLY-ONCE-SCOPE` governs the evidence and stays open; this section
governs the claim. Closing that gate would not move a single row, and §8.7's
user-visible labels already carry the honest wording.

## 12. Multi-device authority

The owner has more than one device. Everything above says which *peer* may see
what; this section says which of the owner's **own** devices may decide that,
and whose decision stands when two of them decided differently without seeing
each other.

Nothing here is on the wire yet — the native transport is still gated shut. It
is specified and implemented as local, signed, replayable state so that the day
it is on the wire, the answer does not have to change.

### 12.1 The manifest is a line; the acts and the entries are DAGs

Two append-only structures, with deliberately different shapes:

| | device manifest | attestations and permission entries |
| --- | --- | --- |
| what it records | which devices exist, in what role | what each device actually did, and to what |
| who signs | the owner's root identity, **always** | the authoring device |
| shape | a DAG: sequence = parent + 1, siblings share a number | a DAG: concurrent siblings on one parent |
| a fork is | normal, and resolved per scope | normal, and resolved per scope |

The manifest used to be strictly linear and refuse a fork outright, on the
grounds that it has one writer. That is true only of the *signature*: the root
key is reachable from every device the owner holds, so two of them offline both
ask it to sign and both produce "parent head, next sequence". Refusing the
second made whichever device reached the database first the one that decided who
administers the estate — the arrival-order dependence the resolver exists to
eliminate. So siblings on one parent are admitted, and the same resolver settles
them.

**Manifest scopes.** A change about one of the owner's devices belongs to that
device's own scope; only a rotation or a sovereign reset is about the estate as
a whole. Enrolling a tablet and revoking a phone are independent facts and both
stand; two changes to *one* device compete, and the restrictive one wins.
Independent scopes are unioned and folded in canonical `(sequence, id)` order.

**Two signatures, and where each is checked.** The root signature says the owner
asked; it is required on every entry without exception. The device **act** says
which of the owner's devices asked, and is what the resolver orders a fork by —
so every ordinary mutation carries exactly one, checked against the estate as it
stood at that entry's **parent**. Checked against the state the entry produces,
an enrolment would be authorised by the device it enrols, and a fork would be
judged against a merge that exists only because both sides were let in.

Two moves carry no act, because at both there is no device that could have
signed one: the **genesis**, where the estate does not exist yet, and a
**sovereign recovery** — the rotation or reset itself and the enrolment that
completes it, by which point the root key has revoked everything including the
device performing it. Both are root-signature-only by construction, not by a
flag a caller passes.

A new entry builds on the tip of the *authorised* history, and is **numbered**
from it too. An entry's snapshot is folded from its own ancestors, so continuing
from a branch that lost would be building on an estate nobody chose; and a
losing branch can be the longer one — two role changes beaten by a single
revocation — so numbering from the highest number on disk pushes the count
forward for ever while the winner stays where it is.

**The manifest's acts are a DAG in their own right**, and the same check the
other two ledgers run applies here first: exactly one act per entry that needs
one, saying exactly what that entry's signed body requires, with a parent that
exists in the same scope and no cycles, verifying under the key the manifest
binds at the head it names. Any violation fails the manifest closed — resolving
what is left would hand somebody a truncated manifest, which has the shape of a
revocation that quietly disappeared.

**One estate, one beginning.** A second root is not a fork: a fork shares a
history. Two roots are two estates in one table, and nothing in the evidence
says which is the owner's, so that fails closed as well.

**Rank is looked up per event**, from the role its author held at the head its
own act names — never assembled into one synthetic manifest folded from every
act, where a device that authored twice contributes twice and which record
survives depends on the order the acts happened to be in. One resolver, one
total order, and nothing it ranks by that the reader has not already validated.

The **permission ledgers order the same way**. The signature check moved to the
manifest point an act names, and the ordering had to move with it: looking the
author's rank up in the manifest as it stands meant an unrelated later change
rewrote a settled decision. Revoking a device dropped the denials it had made —
the withdrawal disappears and the grant underneath stands again — and demoting
one changed which of two concurrent changes won, months after both were signed.
Both are decisions the owner genuinely made while the manifest vouched for the
device that made them. So [TrustedAttestations] keeps the role it validated for
each act and the resolvers order by that; `null` there means the reader cannot
place the author, and the event is not ranked at all. Promotion is not
retroactive either: an act made while a device was `READ_ONLY` was never
authorised, and no later change to the manifest backdates it.

Administering devices is `PRIMARY`-only, so a manifest fork is always between
two primaries and is settled by effect and then canonical id. Rank between a
`PRIMARY` and a `TRUSTED` device is decided on the permission ledgers, where
both may write.

The **owner-policy ledger is a DAG as well.** Leaving it linear while the
relationship ledger was fixed put arrival order straight back in charge of
baselines and rules: two devices offline both change the same baseline, both
claim the next sequence, the unique index rejects the second, and a denial made
on a phone loses to a grant made on a laptop because of network timing.
Baselines, per-peer rules and per-object rules each have their own attestation
scope, so unrelated policy changes never compete — but two changes to the same
one do, and the resolver settles them. The reduced policy, the offer sync, a
restart and a restore all read it the same way.

The **permission ledger is a DAG too**, and this is load-bearing rather than
symmetric-for-neatness. It used to be a line with a unique `(peer, sequence)`
index, so two of the owner's devices offline could not both record a decision:
the second to arrive collided and was discarded, and whichever branch reached
the database first won. That is arrival order deciding who can see what.

`AuthorityBranchResolver` picks the branch that stands, using the same
[§12.3](#123-one-resolver-three-seams) comparison as everything else —
**per scope**. Resolving every act in one pool was the other half of the
arrival-order bug: a revocation about one of a peer's devices deleted an
unrelated grant, and a baseline change to one category deleted a change to
another, because restrictive-beats-permissive was applied across subjects that
never competed. That rule settles two changes to the *same* thing.

So each scope resolves its own winner and walks its own causal chain, and the
scopes are unioned into one history, ordered canonically by sequence then id.

Causality comes from the **act's signed parent**, never from the entry chain.
An act names the act it was built on; that act names its subject; that subject
is the event's parent. Deriving it instead by walking entries for the nearest
ancestor that happened to share a scope was a guess assembled from unsigned
structure — entries interleave scopes, so which ancestor came "nearest"
depended on what unrelated changes were written in between, and two independent
scopes merging was enough to hide a permissive successor behind the restrictive
act it was built on.

An act's parent must be in the **same scope**, and is null only for that
scope's first act. A scope's causality is its own; an act following one
elsewhere would import an unrelated subject's ordering.

### 12.3b A malformed DAG decides nothing

`AuthorityDagValidation` is the one check both the current authority and the
branch projection run before deciding anything. Validating in one and not the
other is how they would come to disagree, with the screen showing an estate the
projection does not reflect.

It requires: the current scope encoding; **exactly one act for every
administrative subject** — no more, and no fewer — saying exactly what that
subject's signed body requires; a subject that exists; an act parent that is
null only for a scope genesis and otherwise an existing act in the same scope;
no cycles among acts; entry parents that exist, are acyclic, and carry a
sequence one past their parent's. Duplicate sequences **across forks stay
legal** — a fork is the point.

"No fewer" is the sharper half. A check that only walked the acts could not see
an administrative entry that had none: a revocation whose act was dropped in
transit, or deleted from the database, simply stopped counting, and the grant it
withdrew went on standing. A history missing its last restrictive step cannot be
told apart from one that never had it. A body the **peer** authored — their
acceptance, their refusal, their own device — still needs no act; it is carried
by their signature, and demanding one from the owner would be the owner
authorising somebody else's consent.

Any violation yields an empty, fail-closed projection and no current authority
— never a partial one. Resolving whatever is left would hand somebody a
truncated history, which has exactly the shape of a revocation that was quietly
dropped. Refusing to answer is visible; answering from half a history is not.

Only the repository holds every ledger at once, so only it can tell an act that
belongs to the *other* ledger from one that belongs nowhere. The per-ledger
projections scope themselves to their own subjects, and the repository gates
both on the global check — with the requirements of **both** ledgers combined,
because a relationship revocation whose act went missing must not leave the
owner policy granting away, and a policy denial whose act went missing must not
leave the relationships open. The two ledgers are one estate. Two baselines, a
peer rule and an object rule, a grant and a device revocation, two revocations
of different devices: independent, all kept.

Because of that union, **numbering is no longer strict or gap-free**, and the
reducers no longer demand it. Entries from independent scopes legitimately
share a number — they are numbered from the same parent — and a scope whose
branch lost leaves a gap. The fold cannot tell a gap from a resolved conflict;
admission can, because it sees the parent, so that is where a genuine gap or a
second root is refused. A new entry is numbered from the **tip of the
authorised history**, not the highest number on disk: a long abandoned branch
holds the higher numbers, and following those leaves every later entry hanging
off a branch nobody chose. An
entry's ordering terms come from the one act paired with it, so the effect is
derived from the signed body and no device can win by calling its grant a
revocation. A peer's reply carries no owner act and cannot be ranked; it rides
the branch its parent is on, and is emitted straight after the entry it answers.
It never appears in the event graph at all — causality there runs act to act,
along the parents devices signed.

**No authorised branch means no released data.** An entry nothing vouches for
is a permission change with no authority behind it, and folding it anyway would
release data on the strength of nothing.

### 12.3c Every act is checked against the manifest on every read

Admission verifies an act's signature once, on the way in. That is not the same
promise as "this row was signed": the check ran in our process, against a table
anything with write access can reach — a restored backup, a rooted device, a bug
of our own, a hand-edited file. A row put in by any of those routes was read as
evidence that one of the owner's devices had authorised a permission change, for
the life of the install and across every restart. "It was checked when it was
written" is a claim about a past the file does not record.

So on **every** read, each stored act is checked against the public key the
*manifest* binds to the device that claims to have signed it — never a key
carried on the act, which would let an attacker supply both halves. An act is
refused when its device is one the manifest does not name, when it claims a
generation its author did not hold, when it needs a capability outside its
device's current role, or when the signature does not verify. A manifest that
did not itself reduce binds no keys at all and refuses everything.

Any refusal closes the **whole estate**: no current authority, no projection,
no grants, before and after a restart. Skipping the one act would leave a
truncated history, which is [§12.3b](#123b-a-malformed-dag-decides-nothing)'s
problem exactly.

The type carries the result. Everything that decides access takes checked acts
rather than a bare list, and the only way to obtain them is to hand over the
manifest and a verifier — so no call site, present or future, can reach the
resolver holding acts nobody checked. The manifest travels with them, because
acts verified against one manifest and resolved against another is a mismatch
nobody would see.

### 12.3d An act binds the whole manifest frontier it was authored against

An act carries the **full manifest context** its device signed against — every
maximal entry of the authorised union, sorted and length-prefixed
(`ccctx.v1|<n>:<len>:<id>…`) — and the authority check happens **there**: the
device's key, its role, the capabilities that role carried, its standing and the
generation are all read off the manifest as the signer actually saw it.

A single head cannot say it. The manifest forks and independent scopes merge, so
after enrolling a tablet and revoking a phone the state that stands is their
union; a head names one branch and silently omits the other, leaving the reader
to guess the rest from whichever head looked canonical. There is no
canonical-head heuristic here: the frontier is recorded in full, and replay
reconstructs exactly that union by folding the ancestor closure of the ids it
names.

Judging an act against the manifest as it stands *now* is a different question
from the one the signing device answered. A demotion, a revocation or a recovery
moves the answer, and the act stops verifying because the estate moved on rather
than because anything is wrong with it — which closes an estate whose history is
perfectly sound. With the point recorded there is nothing to infer: what a
device signed while it was PRIMARY stays authorised after it is demoted, and
what it signed before a revocation or a fence stays part of the history, while
anything claiming a head where it did not stand is refused.

Which context an act may bind depends on where it came from:

| | context it must bind |
| --- | --- |
| an ordinary act, wherever it came from | the whole frontier standing now — after a merge, every branch of it |
| an act authorising a **manifest mutation** | the frontier from before that mutation |
| a **root-authenticated** historical import — a restored backup | the frontier its author bound, replayed as written |

**A signature does not excuse a stale context.** It proves which frontier the
author *declared*, not that the author had not seen more. A device revoked on an
independent branch can sign a well-formed act naming the frontier from before
that branch, choose a parent inside it, and pass every structural check — so
admitting anything live on the strength of an old partial frontier lets a
revoked device go on writing. Nothing in the evidence separates "was offline"
from "saw the revocation and is pretending otherwise", so the rule is positional
rather than evidential: **anything written now binds the frontier standing now**,
and a device that really was offline re-signs against what it finds on
reconnect.

The one exception is a restore, where the whole file has already been
authenticated against the owner's root key before any of it is read. That is a
separately named seam, not a flag on the shared door — a boolean is how the
generic path quietly acquires the exception.

A context has exactly one byte form: `ccctx.v1|<n>:<len>:<id>…`, with the ids
distinct and sorted. The type is canonical by construction and the parser
accepts that form and nothing else — an unsorted or duplicated encoding, a
padded length, a length that runs past the end, or trailing bytes are all
refused rather than normalised, because a second byte form for one frontier is a
second thing that can be signed while meaning the same. The input is untrusted,
so every read is bounds-checked: a malformed context is refused, never fatal.

A context is refused when it names an id no authorised branch holds — which
covers one this install does not hold, one it has not caught up to, and one on a
branch that lost, none of which can be told apart from the others — and when its
ids are not an antichain, where one is an ancestor of another and the frontier
designates its union twice over. Narrowing a context to hide a branch, or
widening it to claim the signer had seen more, is caught by the signature: the
context is inside the canonical bytes.

A change to the manifest cannot be its own justification, which is why the
second row is the entry's parent rather than the head it produces. Checked
against the state it produces, an enrolment would be authorised by the device it
enrols, and a fork would be judged against a merge that exists only because both
sides of the fork were let in. An act from elsewhere was authored against
whatever head *that* install had; demanding it match ours would refuse every act
in a backup. What it must still be is a point in this manifest that can be read,
and the check happens at that point either way.

What this deliberately does **not** demand is that a stored context equal the
frontier standing now. An act signed before an independent sibling arrived keeps
naming what its author actually saw — the sibling does not retroactively change
that — and an act written after the merge binds both branches. Exactness against
the present is an admission-time rule, and only for acts written here; the
reader must not be able to fall back on "well, what does it look like now" and
get a different answer.

### 12.2 Roles and capabilities

Four roles. Capabilities are **derived** from the role and never stored on an
event: a capability set written into an event is a claim the event makes about
itself, while one read off the role is a fact about the manifest.

| role | read | change permissions | manage devices | sovereign reset |
| --- | --- | --- | --- | --- |
| `PRIMARY` | yes | yes | yes | yes |
| `TRUSTED` | yes | yes | no | no |
| `READ_ONLY` | yes | no | no | no |
| `REVOKED` | no | no | no | no |

Revocation is **sticky**, mirroring the rule for a peer's device: re-enrolling a
revoked device does nothing. A device is also *fenced* — carrying no authority
without being revoked — when a rotation or sovereign reset did not re-enrol it.
Fencing is a recorded decision, not something inferred from the generation a
device was enrolled under; inferring it would fence the whole estate, including
the device performing the rotation, the instant the generation moved.

Both bind **forwards**, from the generation they were recorded in. What a device
signed while the manifest still vouched for it stays part of the history — see
[§12.3c](#123c-every-act-is-checked-against-the-manifest-on-every-read).

### 12.3 One resolver, three seams

Live reduction, replay after a restart and a rebuild after a restore all call
one function. A divergence between them would be two of the owner's devices
disagreeing about who can see what, with nothing anywhere to detect it.

Causality is settled **first, by dropping superseded events**, not inside the
comparison. An event whose descendant is present was seen and built upon, so it
is not in conflict — it is simply older. Doing this as a filter is what keeps
the comparison a total order: ancestry is a partial relation, and a partial
relation mixed into a comparator yields intransitive results, which would let
two devices sort the same set differently.

What remains is mutually concurrent and is ordered by:

1. **higher valid authority generation** — re-established authority wins;
2. **restrictive before permissive** — a concurrent withdrawal is never lost to
   a concurrent grant. The other way round costs somebody a repeated tap; this
   way round costs them their privacy;
3. **manifest-derived role rank** — `PRIMARY` before `TRUSTED`;
4. **canonical device id**, then **canonical event id**.

Never a clock, never relay order, and never a priority a client sets. There is
nowhere in an attestation to put one.

### 12.3a Scope encoding

A scope is `ccscope.v2|<count>:<len>:<field>…` — length-prefixed, because peer
ids and object ids are chosen by other people. Under a plain join, a peer whose
npub reads `npub1alice|category|VIDEOS` would land in the scope that decides
Alice's videos, and a restrictive act there could be beaten by a permissive act
about somebody else entirely. Scopes are compared by equality, so an ambiguous
encoding is a privacy bug and not a tidiness one.

A value without the version came from the earlier encoding and is **not
repairable**: the old string does not say which subject it meant. Such a scope
never equals a current one, decides nothing, and accepts nothing new.

### 12.4 Pairing, and admission before storage

Every owner-administrative entry — relationship, policy or manifest — is paired
with **exactly one** valid device attestation. The scope, effect and required
capability are **derived from the entry's own signed body**, not read off the
act:

> An attestation carrying its own capability meant both halves of the check came
> from the device. A `READ_ONLY` device could write `capability = READ`, which
> its role genuinely allows, attach the act to a `GrantChanged`, and the ledger
> would hold a permission change whose only authority claimed to be a read.

Exactly one, enforced by a `UNIQUE(subject_entry_id)` index as well as by
admission. Admission reads and then writes, so two of them interleaving — a
sync and a tap, or the same import twice — both see nothing and both insert.
A second act is how a weaker claim rides in on a valid one, so the database
decides it where a race cannot get past. Inbound entries with no act are refused; that is what a replayed
pre-revocation message looks like.

Bodies the **peer** authored — their acceptance, their refusal, their own device
— require no owner act. Their authority is the peer's signature, and demanding
an owner act would mean the owner authorising somebody else's decision.

The one waiver is narrow: a manifest entry written when *no device in the
current manifest holds the required capability* cannot be paired, because there
is nothing to pair with. That is genesis and post-fencing recovery, both
authorised instead by a verified root signature. It is not reachable by a
device; getting there needs the root key, which sits above the device layer by
construction.

Capability, generation, signature, parent and event id are all settled *before*
anything is written, not filtered on read.

Capability, generation, signature, parent and event id are all settled *before*
anything is written, not filtered on read. The ledger is append-only: a row that
reaches the table cannot be taken back out, and "we will ignore it when we read
it" is a much weaker promise than "it never got in".

A signature is checked against the public key the **manifest** holds for that
device. Taking the key from the attestation would let an attacker supply both
halves of the check.

An act and the permission entry it authorises are written in one transaction.
An entry without its act is a change nobody signed for; an act without its entry
claims authority for something that never happened.

### 12.4a Genesis

Enrolling needs `ADMINISTER_DEVICES`, and a fresh install's manifest names no
device — so without a bootstrap every administrative path would be closed for
ever on a release build. Genesis is one root-signed entry enrolling *this*
install's own device as `PRIMARY`, atomic, and refused once a manifest exists so
it cannot mint a second primary.

### 12.5 Recovery

#### Importing a file is a recovery

There is one way in, `importRecovery`, and it trusts its caller with nothing but
the file, the code, and whether a reset was deliberately asked for:

- the **authority generation** comes from the payload's own signed manifest,
  which must equal what that manifest reduces to;
- the **device key** comes from this install's stored identity.

A caller that could supply either could call any file current, or enrol a key
this install does not hold.

**Holding a manifest is not a sync.** It says only that this device once had an
estate at that generation, not that it has seen what other devices did since —
and while the transport is gated shut, no install can show that at all. The
file's number and the install's number are the same kind of evidence, so
freshness is `UNKNOWN` regardless and a file import is a preview, even when the
numbers match. Only an explicit root-signed sovereign reset writes from a file.
`syncedGeneration()` is the single place that changes when sync arrives.

There is also **no public "restore this payload"** and no `commitRestore`:
either was a payload-plus-code shortcut past this gate. `commitRecoveryFromBackup`
takes signed plans only the gate can produce.

A preview is the right answer and a dead end on its own: permissions visible,
nothing changeable. So the screen offers the sovereign reset **there**, with
the whole price named before the button exists — every earlier device loses its
rights, every share is revoked and must be granted again, every key is renewed,
and copies other people already downloaded stay with them. Cancelling writes
nothing and leaves the preview in place; confirming runs exactly once, because
the pending attempt is taken before the work starts rather than inside it.

The file and the code are held **in process memory only** for that retry —
never a `SavedStateHandle`, never a file. A recovery code plus a backup is a
complete takeover of somebody's permissions, and anything durable is one
backup-agent or crash-report away from being somewhere else. Process death
simply loses it and the person picks the file again, which is the safe
direction to fail in.

Even a **preview** is a decision about somebody's data and it locks writes, so
it happens only after the root identity has signed the challenge. A refusal
leaves no lock and no claim.

Re-wrapping the backup's data keys mints a Keystore alias per handle, and a
Keystore entry is not part of the SQL transaction. A failed recovery therefore
**destroys the handles it created, and only those** — an alias the install
already held is somebody's live data key, and destroying it would erase content
the recovery never touched.

Every entry the restore writes goes through the ordinary admission door and a
refusal fails the whole import. The validation pass and the write pass do not
check quite the same things — a peer this install purged is invisible to the
first and refused by the second — so dropping the write result left a history
with a hole in it that nothing downstream could see.

The whole recovery is **one SQL transaction**: the payload's history, the
manifest rotation, the PRIMARY enrolment, the fencing, the epoch and revoke
entries, their acts, and the lock release. Everything is planned and signed
first, so a refusal, a timeout or a cancellation at any prompt leaves the
database byte-identical. A key store that cannot re-wrap a data key fails the
recovery rather than reporting a success with keys it never re-wrapped.

A restore needs the root signature **and** the recovery code. The signature is
over a domain-separated challenge bound to the recovery code's digest, the
returning device's key, the backup generation and whether this is a reset — so a
signature the owner produced for some other purpose cannot be replayed as
consent to a sovereign reset, and a signature obtained without the code cannot
be paired with a code obtained without the signature. A wired signer proves
nothing; only a verifying signature does. It mints the next
authority generation, enrols the returning device as `PRIMARY`, fences every
device that was there before, and advances each relationship's resource epoch
and device generation. All of it lands in one transaction: an install carrying a
new generation with no enrolled device holds no authority and cannot enrol one
either, so half a recovery is worse than none.

The device that recovers enrols **itself**. Naming a key the install does not
hold produces a manifest it cannot act under and no device can repair.

A backup that cannot be shown to be current gives a **read-only Recovery
Preview**. A backup is a snapshot; restoring an old one re-establishes
permissions that were since withdrawn, because the withdrawal is not in it.
"We cannot tell" is treated exactly as "we know it is old". The lock is stored
on disk — a lock a restart forgets is not a lock, and forgetting it is how a
stale backup ends up writing after all.

The one way past a stale backup is an explicit root-signed **sovereign reset**,
which revokes and fences every earlier device, revokes every grant and rotates
every epoch. That price is what makes it an acceptable override.

Recipient-side key rotation is **planned and not performed** while the native
gate is shut, and is recorded as such. Claiming a rotation that did not happen
would be an assertion the next reader has no way to check.

### 12.6 Limits

- The envelope is **version 2**, and version 1 is refused rather than read: it
  predates the device model, so every entry in it is unattributed. The HKDF info
  string is bound to the version, so one recovery code does not derive one key
  for both formats.
- A sovereign reset revokes every device including the one performing it.
  Revocation is therefore sticky *within* a generation — which is what stops a
  lost or seized device being reinstated — and the enrolment under the
  generation the reset mints re-establishes the estate. A rule that held across
  generations would leave an estate nobody could act in.
- A backup carries the manifest, the acts and the authority metadata, and is
  re-validated and re-paired against its own contents on import. It carries
  **public keys only**: a backup gives back the estate, never the ability to act
  as a device already in it.
- Nothing in this section is transmitted. There is no multi-device sync yet;
  the manifest and the acts are local, signed, and replayable.
- Recipient-side rotation after a restore is planned only (§12.5).
- The per-device keypair is BIP-340 and is minted on the device. The curve
  binding is unverified without a device, exactly as for the Keystore-backed
  wrapping keys; the storage, sealing and restart behaviour around it is tested.
- A device is named on screen by the leading characters of its public key. There
  is no authenticated source for a friendlier name, and an unauthenticated one
  would be a label an attacker chooses.
