---
status: design-locked
queue: n/a
---
# FEAT-058 — CruxCoach Competition Protocol (`cruxcoach-competition/1`)

> **Companion documents**
> - User-level feature spec: [`FEAT-058-competitions.md`](FEAT-058-competitions.md)
> - Cross-client conformance matrix: [`FEAT-058-conformance.md`](FEAT-058-conformance.md)
> - Canonical fixtures / test vectors: `shared/src/commonTest/resources/competition/`
> - Website implementation + localhost runbook: `cruxcoach-pages` branch
>   `feat/competitions`, `tools/dev/RUNBOOK-competitions.md`
>
> This document is the **wire contract**. Two independent clients (the Android
> app and cruxcoach.org) must reduce the same event stream to a byte-identical
> state hash. Where this document and an implementation disagree, this document
> is wrong until it is fixed — but the fixtures decide.

---

## 0. Source register

Every protocol decision below cites the primary specification it rests on.
Access date is the date the linked text was read for this design.

| Ref | Document | URL | Accessed |
|---|---|---|---|
| NIP-01 | Basic protocol flow, event format, kind ranges, filters | https://github.com/nostr-protocol/nips/blob/master/01.md | 2026-08-09 |
| NIP-09 | Event deletion request | https://github.com/nostr-protocol/nips/blob/master/09.md | 2026-08-09 |
| NIP-07 | `window.nostr` browser signer capability | https://github.com/nostr-protocol/nips/blob/master/07.md | 2026-08-09 |
| NIP-11 | Relay information document | https://github.com/nostr-protocol/nips/blob/master/11.md | 2026-08-09 |
| NIP-19 | bech32-encoded entities | https://github.com/nostr-protocol/nips/blob/master/19.md | 2026-08-09 |
| NIP-29 | Relay-based groups (relay as authority) | https://github.com/nostr-protocol/nips/blob/master/29.md | 2026-08-09 |
| NIP-33 | **Tombstone stub** — renamed and merged into NIP-01 | https://github.com/nostr-protocol/nips/blob/master/33.md | 2026-08-09 |
| NIP-45 | `COUNT` | https://github.com/nostr-protocol/nips/blob/master/45.md | 2026-08-09 |
| NIP-70 | Protected events (`["-"]`) | https://github.com/nostr-protocol/nips/blob/master/70.md | 2026-08-09 |
| Registry | Official machine-readable kind registry (`schema.yaml`) | https://github.com/nostr-protocol/registry-of-kinds | 2026-08-09 |
| NIP-31 | `alt` tag for unknown event kinds | https://github.com/nostr-protocol/nips/blob/master/31.md | 2026-08-09 |
| NIP-32 | Labeling (`L` / `l` namespace tags) | https://github.com/nostr-protocol/nips/blob/master/32.md | 2026-08-09 |
| NIP-40 | Expiration timestamp | https://github.com/nostr-protocol/nips/blob/master/40.md | 2026-08-09 |
| NIP-42 | Relay authentication | https://github.com/nostr-protocol/nips/blob/master/42.md | 2026-08-09 |
| NIP-44 | Versioned encryption (v2) | https://github.com/nostr-protocol/nips/blob/master/44.md | 2026-08-09 |
| NIP-46 | Nostr Connect remote signing | https://github.com/nostr-protocol/nips/blob/master/46.md | 2026-08-09 |
| NIP-52 | Calendar events and RSVPs | https://github.com/nostr-protocol/nips/blob/master/52.md | 2026-08-09 |
| NIP-57 | Lightning zaps | https://github.com/nostr-protocol/nips/blob/master/57.md | 2026-08-09 |
| NIP-65 | Relay list metadata | https://github.com/nostr-protocol/nips/blob/master/65.md | 2026-08-09 |
| NIP-78 | Arbitrary application-specific data (kind 30078) | https://github.com/nostr-protocol/nips/blob/master/78.md | 2026-08-09 |
| NIP-98 | HTTP Auth (kind 27235) | https://github.com/nostr-protocol/nips/blob/master/98.md | 2026-08-09 |
| BIP-340 | Schnorr signatures for secp256k1 (test vectors) | https://github.com/bitcoin/bips/blob/master/bip-0340/test-vectors.csv | 2026-08-09 |

### 0.1 NIP evaluation — what we adopted, and what we refused

The goal for this protocol was to reuse standards wherever they are
semantically right and to say plainly where they are not. Forcing a NIP that
does not fit is worse than a documented app-specific event: it makes other
clients render nonsense and it makes our own validation lie.

> **Terminology.** NIP-33 no longer exists as a specification. Its file is a
> one-line tombstone — *"Renamed to 'Addressable events' and moved to NIP-01."* —
> it is absent from the NIPs README index, and the string "parameterized
> replaceable" appears nowhere in NIP-01. This document therefore says
> **addressable event** throughout and cites NIP-01. Kotlin and JavaScript
> identifiers follow the same rule. (The existing `docs/nostr-architecture.md`
> §1 table still uses the old wording; that is a pre-existing doc, out of scope
> for this branch, and noted in [the decision register](FEAT-058-decisions.md) §12.)

| NIP | Verdict | Reasoning |
|---|---|---|
| **NIP-01** | **Adopted, load-bearing** | Event format, id binding, addressable-kind replacement rules and filter semantics are the entire substrate. NIP-01 is itself still marked `draft`; there is no `final` NIP this protocol could have rested on instead. |
| **NIP-33** | **Not applicable — merged** | See the terminology note above. |
| **NIP-78 / kind 30078** | **Adopted for every competition document** | 30078 is the registered kind for arbitrary application-specific data. It is universally accepted by relays, it is what CruxCoach already publishes for climbs, manifests and backups, and it carries zero risk of colliding with a future registry allocation. See §2.1 for why a dedicated kind was rejected. |
| **NIP-32 (`L`/`l`)** | **Adopted** | The `L` namespace is what makes a 30078 subscription selective. Without it a subscriber receives every other NIP-78 app's settings — a mistake this codebase already made once (`docs/nostr-architecture.md` §18). |
| **NIP-19** | **Adopted** | `naddr` is the canonical competition identifier: it encodes `(kind, pubkey, d-tag)` and therefore survives every edit of the competition document. |
| **NIP-31 (`alt`)** | **Adopted** | Every competition event carries a human-readable `alt` so a generic Nostr client shows a sentence instead of a blank. |
| **NIP-09** | **Adopted, with a tombstone alongside** | Deletion requests are honoured by some relays and ignored by others. We publish both a NIP-09 kind-5 *and* an addressable tombstone at the same address, exactly as the community-climb deleter already does. |
| **NIP-40 (`expiration`)** | **Adopted, narrowly** | Only on NIP-46 transport events and on participant *intents* that are inherently short-lived (defer requests). Never on competition records: a relay that honours `expiration` would silently delete the audit trail. |
| **NIP-42 (relay AUTH)** | **Evaluated, not required in v1** | v1 competitions are public or unlisted; nothing in the wire format needs relay-enforced read control. It could not be required even if we wanted it: a live NIP-11 probe on 2026-08-09 found that `relay.damus.io`, `nos.lol` and `relay.primal.net` — three of the five relays this app ships with — do **not** list NIP-42 in `supported_nips`, while `nostr.wine` and `purplepag.es` do. AUTH-gated relays are not a portable assumption. Recorded as the mechanism a *private* competition would use alongside NIP-44 (§13.2). Clients must tolerate an `AUTH` challenge without breaking. |
| **NIP-29 (relay-based groups)** | **Evaluated, rejected** | NIP-29 is the one deployed pattern that gives a real serialization point — the relay itself becomes the authority and *"MUST reject the request if the user has not been added"*. It was rejected because it moves the authority into a relay operator, which is a heavier and less portable dependency than the organizer's own key, and because it would make a competition unrunnable on the general-purpose relays this project already uses. §14 records the coordinator seam that gets the same property without the relay lock-in. |
| **NIP-45 (`COUNT`)** | **Rejected** | Its own text disclaims trustworthiness and deployment is thin. Capacity and enrollment counts are reduced from the log, never asked of a relay. |
| **NIP-70 (protected events)** | **Evaluated, not used** | `["-"]` asks relays to reject an event unless the NIP-42-authenticated pubkey is the author. It prevents third-party rebroadcast; it is not a lock and does not serialize anything. Given the NIP-42 portability finding above it would mostly cause publish failures. |
| **NIP-44 v2** | **Adopted for NIP-46 transport only in v1** | The competition payloads are public in v1. NIP-44 is nonetheless implemented and shipped because NIP-46 requires it, which means the private-competition envelope in §13.2 needs no new cryptography later. |
| **NIP-46** | **Adopted** | Remote signing is the safest identity path on the web and the one Amber implements. |
| **NIP-52** | **Evaluated, deliberately NOT the carrier** | See §2.2. NIP-52 models a calendar entry and a personal RSVP. It has no notion of an accepting authority, capacity, waitlist, payment state, check-in, turn order, attempts, or results. Bolting those onto 31923/31925 would produce events that other calendar clients render as a broken calendar entry and that we would have to re-validate from scratch anyway. We *do* publish an optional companion NIP-52 event so calendar clients can show the event (§12). |
| **NIP-57** | **Adopted for the fee path** | Zaps are the only widely deployed standard that produces a *third-party-signed* receipt tying a Lightning payment to a Nostr identity and an arbitrary reference. §11 documents precisely what it proves and what it does not. |
| **NIP-65** | **Adopted** | Relay discovery for participants reuses the app's existing `RelayListResolver` behaviour. |
| **NIP-98** | **Evaluated, reserved** | There is no HTTP coordinator in v1, so there is nothing to authenticate. §14 records NIP-98 as the authentication mechanism for the future coordinator boundary, so that boundary does not need a new scheme invented for it. |
| **NIP-85 / trusted assertions** | **Rejected for v1** | Would add a third-party attestation layer whose trust semantics we cannot yet justify. |

---

## 1. Design rules

These are the constraints every later section answers to.

1. **Nostr gives no compare-and-swap.** There is no transaction, no conditional
   write, no total order across relays. Any rule that needs "exactly one winner"
   (a unique climb claim, whose turn it is) is therefore **serialized by a named
   authority key**, never by relay behaviour. §5.
2. **Authority decisions are the only state.** Participants publish *intents*.
   Intents are inputs to a human/organizer decision; they never mutate reduced
   state. A client that shows an intent as if it were accepted is wrong.
3. **Reduction is deterministic.** Given the same set of events, every conformant
   client produces the same state and the same `state_hash`. Anything
   non-deterministic (wall clock, map iteration order, float arithmetic) is
   banned from the reducer.
4. **Fail closed and say so.** Missing, unverifiable, or ambiguous evidence never
   silently becomes a favourable outcome. Every such case has a named UI state.
5. **Extensible without a reset.** Unknown fields, unknown log types and unknown
   modes must be survivable by an older client (§15).

---

## 2. Wire carrier

### 2.1 Why kind 30078 and not a dedicated kind

A dedicated addressable kind would be more self-describing, and it is what a
greenfield protocol would pick. The official machine-readable registry
(`nostr-protocol/registry-of-kinds`, `schema.yaml`, accessed 2026-08-09) was
checked: **no kind anywhere is claimed for a competition, tournament, contest,
leaderboard, scoring or paid-registration concept**, and a title search of the
NIPs repository for `tournament` / `competition` / `leaderboard` returns
nothing. There is genuinely no prior art to conform to, and three clean
addressable blocks are available if we ever want one — `32268–33400`
(1 133 slots), `35130–36786` (1 657), and `31926–31988` (63 slots, sitting
directly between the NIP-52 calendar family and NIP-89's 31989).

It was still rejected for v1:

- The registry is not frozen, and it already disagrees with the NIPs README in
  at least one place (the geocaching kinds). Squatting an unallocated kind risks
  a future collision that would force a wire break precisely when there are live
  events in the wild.
- Relay behaviour on unknown kinds is uneven; 30078 is accepted everywhere and
  is already proven at scale on the relays this project ships with.
- CruxCoach already carries five distinct document families on 30078,
  separated by `L` namespace and `d`-tag. A sixth costs nothing and keeps the
  trust-boundary code paths identical.

The cost is that `kind` alone does not identify a competition event. That cost
is paid by the mandatory `L` namespace plus a mandatory `cc-schema` tag, both of
which are checked before any field is read. Migrating to a dedicated kind later
is a *carrier* change, not a semantic one: §15.3 specifies the dual-publish
window that makes it possible without a protocol reset.

### 2.2 Why not NIP-52

NIP-52 (kinds 31922/31923 for date/time-based calendar events, 31924 for a
calendar, 31925 for an RSVP) is the closest existing standard, and it was
seriously evaluated. It does not fit as the carrier, for reasons that are
structural rather than cosmetic:

- **RSVP is self-asserted.** A 31925 RSVP is signed by the attendee and says
  `accepted` / `declined` / `tentative`. A competition registration is
  *decided by the organizer*: it can be waitlisted for capacity, gated on a
  settled payment, or rejected on eligibility. NIP-52 has no event whose author
  is the organizer and whose subject is one attendee's admission.
- **No capacity, no waitlist, no fee, no check-in.** All four are load-bearing
  for the first release and none has a NIP-52 representation.
- **No ordering primitive.** Turn order, attempts, results and corrections need
  a monotonic, forkable-but-detectable log. A calendar event is a single
  addressable document.
- **RSVPs do not even deduplicate.** A 31925 RSVP's `d` is a random uuid, so one
  pubkey can publish unlimited non-replacing RSVPs for the same event; "one RSVP
  per person" is a convention the spec never states. An entry list built on that
  would need exactly the same organizer-side reconciliation we are writing
  anyway.
- **The interesting fields are not queryable.** `status` and `fb` are
  multi-letter tags, and NIP-01 only indexes single-letter tags — so a relay
  cannot answer "all accepted RSVPs". Every client would have to fetch all RSVPs
  for the coordinate and filter locally, against a `limit` most relays silently
  clamp to 500.
- **The spec declines the hard part on purpose.** NIP-52 deliberately does not
  define who may attend or what happens when the event changes.
- **Rendering harm.** A generic calendar client reading a competition encoded as
  31923 would show an event whose description is a JSON blob, and would offer
  its user an RSVP button that does nothing our authority will ever see.

So NIP-52 is used for what it *is* good at: §12 publishes an **optional
companion** 31923 calendar event that points at the competition, so a calendar
client shows a correct, useful entry and a link. It is advisory. No CruxCoach
state is ever reduced from it.

### 2.3 Envelope

Every competition event is:

```jsonc
{
  "kind": 30078,
  "pubkey": "<signer>",
  "created_at": <monotonic per (signer, d-tag), see §4.4>,
  "tags": [
    ["d",         "<d-tag, §3>"],
    ["L",         "com.cruxcoach.competition"],
    ["l",         "<document type>", "com.cruxcoach.competition"],
    ["cc-schema", "cruxcoach-competition/1"],
    ["alt",       "<one human sentence, NIP-31>"],
    // …type-specific tags
  ],
  "content": "<CCJ of the payload object, §4.1>"
}
```

A consumer **must** reject the event before reading any field unless all of:

1. `verifySignature()` **and** `verifyId()` pass (NIP-01 trust boundary — a
   relay can return a validly signed envelope whose body it swapped);
2. `kind == 30078`;
3. an `L` tag equal to `com.cruxcoach.competition` is present;
4. a `cc-schema` tag is present and its major version is understood (§15.1);
5. `d` matches the expected shape for the claimed type (§3);
6. `created_at <= now + 3600` (clock-skew ceiling, same constant as
   `NostrEventPolicy.MAX_FUTURE_SKEW_SECONDS`);
7. the serialized event is at most **64 KiB** (§16.2).

Rejections are counted and surfaced, never silent.

---

## 3. Addresses and identifiers

### 3.1 `compId`

16 lowercase hex characters (64 bits) drawn from a CSPRNG by the organizer's
client. It is namespaced under the organizer's pubkey by the d-tag, so 64 bits
is ample: a collision would have to be a self-collision by one organizer.

```
compId ::= [0-9a-f]{16}
```

### 3.2 d-tags

| Document type (`l` value) | d-tag | Signer |
|---|---|---|
| `competition` | `cruxcoach:comp:<compId>` | organizer |
| `log` | `cruxcoach:comp:<compId>:log:<seq:06d>` | **authority** |
| `snapshot` | `cruxcoach:comp:<compId>:snap:<seq:06d>` | **authority** |
| `results` | `cruxcoach:comp:<compId>:results` | **authority** |
| `intent` | `cruxcoach:comp:<compId>:intent:<pubkey[0:8]>:<nonce>` | participant |

`<seq:06d>` is the zero-padded decimal sequence number, so lexicographic d-tag
order equals numeric order for the first million entries — which lets a relay
range-scan and a human read a subscription log without arithmetic. Sequence
numbers above 999999 are a protocol error in v1 (§16.2 caps a competition far
below that).

`<nonce>` is 8 lowercase hex characters from a CSPRNG, so a participant can hold
several distinct intents (a registration and a later defer request) without one
replacing the other, while a *retry of the same intent* deliberately reuses the
nonce and is therefore idempotent under the addressable-replacement rule.

### 3.3 Canonical references

- **Nostr:** `naddr` (NIP-19) over `(30078, organizerPubkey, cruxcoach:comp:<compId>)`.
  This is *the* identifier. It survives every edit of the competition document
  because it addresses the slot, not the event.
- **HTTPS:** `https://<APP_LINK_HOST>/comp/<naddr>`.
  - On Android with the app installed, an App Link intent filter on
    `pathPrefix="/comp/"` opens the app.
  - Otherwise `404.html` rewrites it to `/competitions/join.html#<naddr>`.
- **Typed/pasted:** the join screens accept a bare `naddr1…`, a
  `nostr:naddr1…` URI, or the full HTTPS link. There is deliberately **no short
  numeric join code**: a short code requires a registry that maps it to an
  address, and a registry is a central service. §14 records this as the first
  thing a coordinator would offer.

Every `a`-tag reference uses the NIP-01 form `30078:<organizerPubkey>:cruxcoach:comp:<compId>`.

---

## 4. Canonical serialization

### 4.1 CruxCoach Canonical JSON (CCJ)

`content` and every hashed structure use CCJ so that two implementations in two
languages produce byte-identical output.

1. Object keys are sorted ascending by **UTF-16 code unit** (JavaScript's
   default string comparison; Kotlin's `String.compareTo`). Both languages agree
   on this ordering for the ASCII key set this protocol uses, and §16.1 forbids
   non-ASCII keys outright so the agreement is total.
2. No insignificant whitespace.
3. **Numbers are integers only.** No floating point appears anywhere in the
   protocol — money is integer millisats, time is integer epoch seconds, scores
   are integer points. Rendered as the shortest decimal form, `-` prefix for
   negatives, no `+`, no exponent, no `-0`.
4. Strings use JSON escaping identical to `JSON.stringify` (`"`, `\`, `\b`,
   `\f`, `\n`, `\r`, `\t` as two-character escapes; all other control characters
   and lone surrogates as `\uXXXX` lowercase-hex).
5. `null` is never emitted. An absent value is an absent key.
6. Arrays preserve order.
7. Booleans are `true` / `false`.

Test vectors: `competition/vectors/ccj.json`.

### 4.2 Payload envelope

```jsonc
{
  "v": 1,                    // protocol major version, always present, always first alphabetically? no — CCJ sorts
  "type": "<document type>",
  // …type-specific fields
}
```

`v` is the **major** version. Minor/patch evolution happens by adding optional
fields, which older clients ignore (§15.1).

### 4.3 State hash

```
state_hash = sha256_hex( CCJ( reducedState ) )
```

`reducedState` is the exact structure in §7.1. The hash is what makes
cross-client conformance checkable rather than aspirational: the Android test
suite and the website test suite both replay the same fixture stream and assert
the same 64-character hex string.

### 4.4 Monotonic `created_at`

Every event published to a d-tag the signer has used before must satisfy

```
created_at = max(nowSeconds, lastCreatedAtForThisDTag + 1)
```

This is the same clamp as `CommunityEventTime.monotonicCreatedAtSeconds`, for
the same reason: two publishes in one second, or an NTP step backwards, would
otherwise make "newest wins" resolve differently on two clients and diverge them
permanently. Because competition log entries each get their **own** d-tag, the
clamp matters mainly for the competition document and for intent retries — but
it is required uniformly so no path has to remember which case it is in.

---

## 5. Authority

### 5.1 The authority key

The competition document names `authority` — a 32-byte hex pubkey.

In v1 the organizer publishes the competition and names **their own pubkey** as
the authority. The field exists separately anyway, and that separation is the
whole forward-compatibility story:

- A reducer **never** treats "signed by the organizer" as authoritative. It
  treats "signed by `authority`" as authoritative. The two happen to be equal
  in v1.
- A future redundant coordinator service is introduced by publishing a
  competition whose `authority` is the coordinator's pubkey. No client change,
  no schema change, no re-registration. §14.

### 5.2 What the authority alone may do

| Transition | Authority | Organizer (if ≠ authority) | Participant |
|---|---|---|---|
| Create / edit competition document | — | ✔ | — |
| Lifecycle change (open, close, start, pause, finish, cancel) | ✔ | — | — |
| Accept / waitlist / reject a registration | ✔ | — | — |
| Confirm payment settled / failed / refunded | ✔ | — | — |
| Grant / deny a unique climb claim | ✔ | — | — |
| Check a participant in / mark no-show | ✔ | — | — |
| Open / close a turn, advance the queue | ✔ | — | — |
| Grant / deny a defer | ✔ | — | — |
| Record or confirm an attempt result | ✔ | — | — |
| Correct or override an earlier entry | ✔ | — | — |
| Publish a snapshot or the final results | ✔ | — | — |
| Register, withdraw, request check-in, request defer, self-report an attempt | — | — | ✔ (as *intent*) |

The competition document is signed by the **organizer** and is the only
document that is not authority-signed, because it is what *names* the authority.
A reducer therefore binds the two: the log entries it accepts must be signed by
the pubkey the document names, and the document must live at the address the
log entries' `a` tag points to.

### 5.3 Authority rotation (defined, not exercised in v1)

An organizer may publish a new revision of the competition document with a
different `authority` and a mandatory `authority_epoch` incremented by exactly
one. Log entries carry `authority_epoch`; a reducer accepts an entry only if its
epoch matches the epoch in force at that point in the log, and the first entry
of a new epoch must carry `prev` = the id of the last entry of the previous
epoch. This makes a handover auditable and prevents a stale authority from
forking the log after being replaced. v1 clients implement the check; v1
organizer UI never triggers a rotation.

---

## 6. Documents

Field types: `str`, `int` (integer), `bool`, `[T]` (array), `{…}` (object),
`?` suffix = optional.

### 6.1 `competition` — the definition

d-tag `cruxcoach:comp:<compId>`, signed by the organizer.

**Tags** (in addition to the envelope, §2.3):

```
["t", "cruxcoach-competition"]         discovery hashtag
["t", "climbing"]
["status", "<lifecycle status>"]       mirrored from content for relay-side filtering
["visibility", "public"|"unlisted"]
["board_brand", "<wire value>"]        e.g. kilter
["starts", "<epoch seconds>"]
["ends",   "<epoch seconds>"]
["authority", "<pubkey hex>"]
["p", "<authority pubkey>"]            so the authority's client can find its own competitions
```

`unlisted` competitions omit the two `t` tags — a relay-searchable hashtag is
exactly what "unlisted" must not have. Unlisted is *not* private: the event is
still readable by anyone who has the address. The UI says so in those words.

**Content:**

```jsonc
{
  "v": 1,
  "type": "competition",
  "comp_id": "9f2c41ab77e05d13",
  "authority": "<pubkey hex>",
  "authority_epoch": 1,
  "title": "Kellerwand Winter Session",
  "summary": "One-line teaser, max 140 chars",
  "description": "Full description, max 4000 chars",
  "organizer": { "name": "Kellerwand Bouldern", "contact": "kellerwand@example.org" },
  "visibility": "public",                       // public | unlisted   (private: §13.2)
  "status": "registration_open",
  "timezone": "Europe/Berlin",                  // IANA name; used for display only
  "starts_at": 1789000000,
  "ends_at": 1789014400,
  "registration_opens_at": 1788400000,
  "registration_closes_at": 1788986400,
  "checkin_opens_at": 1788996400,
  "checkin_closes_at": 1788999600,
  "capacity": 24,                               // 0 = unlimited
  "waitlist_enabled": true,
  "venue": { "kind": "physical", "name": "Kellerwand Bouldern", "address": "Beispielweg 3, Berlin" },
                                                // kind: physical | online
  "board": {
    "brand": "kilter",
    "model": "kilterboard-og",
    "layout_id": 1,
    "size": "12x12",
    "angle": 40
  },
  "divisions": [
    { "id": "open",   "label": "Open" },
    { "id": "youth",  "label": "Youth (U18)" }
  ],
  "eligibility": "Open to all. Under-18 entrants need a guardian signature at check-in.",
  "waiver": "I climb at my own risk …",
  "waiver_required": true,
  "participant_instructions": "Arrive 20 minutes early …",
  "spectator_info": "Free entry, projector shows the live queue.",
  "fee_msat": 0,                                // 0 = free; see §11
  "fee_lnurl": "kellerwand@getalby.example",    // present only when fee_msat > 0
  "refund_policy": "Full refund until 24 h before the start.",
  "prizes": [
    { "rank": 1, "kind": "cash",     "value_msat": 5000000, "label": "50 000 sat" },
    { "rank": 2, "kind": "non_cash", "label": "Chalk bag" }
  ],
  "rules": {
    "climb_source": "organizer_set",            // organizer_set | participant_choice
    "climb_count": 5,
    "selection_uniqueness": "none",             // none | unique_per_competition
    "progression": "synchronous_rounds",        // synchronous_rounds | asynchronous_turns
    "attempts_per_climb": 3,
    "turn_deadline_sec": 120,
    "attempt_deadline_sec": 0,                  // 0 = no per-attempt clock
    "min_rest_sec": 180,
    "defer_budget_per_round": 1,
    "max_consecutive_defers": 1,
    "defer_slots": 2,
    "scoring": "tops_then_attempts",            // tops_then_attempts | points_sum | hardest_n
    "tiebreaks": ["fewest_attempts", "fewest_zones", "earliest_finish"],
    "late_entry_allowed": false
  },
  "climbs": [                                   // present iff climb_source == organizer_set
    { "id": "c1", "climb_uuid": "…", "angle": 40, "label": "Qualifier 1", "points": 100 }
  ],
  "climb_pool": {                               // present iff climb_source == participant_choice
    "source": "board_catalogue",
    "filter": { "min_grade": 17, "max_grade": 24, "angle": 40 }
  },
  "relays": ["wss://relay.damus.io", "wss://nos.lol"],
  "moderation": { "report_to": "<pubkey hex>" },
  "created_at": 1788300000,
  "revision": 3
}
```

`revision` increments on every edit and is a plain integer, so a UI can say
"updated" without diffing. It is **not** a security mechanism: `created_at`
monotonicity plus the relay's newest-wins rule is what actually selects a
revision.

### 6.2 `log` — the authority's append-only entries

d-tag `cruxcoach:comp:<compId>:log:<seq:06d>`, signed by the authority.

**Tags:**

```
["a",   "30078:<organizerPubkey>:cruxcoach:comp:<compId>"]
["seq", "<n>"]
["prev","<event id of entry n-1, or of the competition document for n=1>"]
["op",  "<operation>"]
["p",   "<subject pubkey>"]              zero or more; lets a participant filter their own entries
["epoch","<authority_epoch>"]
```

**Content:**

```jsonc
{
  "v": 1,
  "type": "log",
  "comp_id": "9f2c41ab77e05d13",
  "seq": 42,
  "prev": "<event id>",
  "epoch": 1,
  "at": 1789002345,                     // authority's clock, informational
  "op": "attempt_result",
  "actor": "authority",                 // authority | organizer_override
  "reason": "…",                        // required for correction / override / reject / deny
  "data": { /* per-op, §8 */ }
}
```

`prev` forms a hash chain. It is the fork detector: two entries claiming the
same `seq` with different ids means the authority signed twice, which is either
a bug, two devices, or a compromised key. §7.4 says exactly what a reducer does
about it, and it is never "pick one quietly".

### 6.3 `snapshot`

d-tag `cruxcoach:comp:<compId>:snap:<seq:06d>`, signed by the authority.
Carries the complete reduced state as of `seq`, plus its `state_hash`.

```jsonc
{
  "v": 1, "type": "snapshot", "comp_id": "…",
  "seq": 40, "epoch": 1,
  "head": "<event id of log entry 40>",
  "state_hash": "<64 hex>",
  "state": { /* §7.1 */ }
}
```

A snapshot is an **optimization, never an authority**. A client that has the
full log MUST verify the snapshot by replaying and comparing `state_hash`, and
MUST prefer its own replay if they disagree (surfacing the mismatch). A client
that starts cold MAY trust a snapshot to skip fetching entries `<= seq`, but
must then mark its state `from_snapshot: true` so the UI can say the audit trail
is not locally verified. The organizer console always replays in full.

Cadence: after every 25 log entries, and unconditionally on lifecycle changes.

### 6.4 `results` — final, immutable

d-tag `cruxcoach:comp:<compId>:results`, signed by the authority.
Published once, after `status == finished`.

```jsonc
{
  "v": 1, "type": "results", "comp_id": "…",
  "final_seq": 118,
  "head": "<event id of log entry 118>",
  "state_hash": "<64 hex>",
  "ruleset_hash": "<sha256 of CCJ(competition.rules) — pins the rules the standings were computed under>",
  "standings": [
    { "rank": 1, "pubkey": "…", "display": "…", "division": "open",
      "tops": 5, "attempts": 8, "zones": 5, "points": 500, "finished_at": 1789013000 }
  ],
  "published_at": 1789013500
}
```

Immutable means: no second results event is ever accepted at this address by a
conformant reducer once one has been seen with a valid chain. A genuine
correction after publication is a **new competition-scoped erratum** — a log
entry with `op: "correction"` and `data.supersedes_results: true` — which
clients render as "results amended", never as a silent replacement.

### 6.5 `intent` — participant-signed requests

d-tag `cruxcoach:comp:<compId>:intent:<pubkey[0:8]>:<nonce>`, signed by the
participant.

**Tags:**

```
["a", "30078:<organizerPubkey>:cruxcoach:comp:<compId>"]
["p", "<authority pubkey>"]        so the authority can subscribe to `#p` = itself
["op","<intent operation>"]
["expiration", "<epoch seconds>"]  NIP-40, only on inherently short-lived intents
```

**Content:**

```jsonc
{
  "v": 1, "type": "intent", "comp_id": "…",
  "op": "register",
  "at": 1788500000,
  "nonce": "3f9a2c17",
  "data": { /* per-op, §8.2 */ }
}
```

An intent is never state. The authority's decision about it is.

---

## 7. Reduction

### 7.1 Reduced state

```jsonc
{
  "comp_id": "…",
  "schema": "cruxcoach-competition/1",
  "authority": "<pubkey>",
  "epoch": 1,
  "seq": 42,                        // highest applied log seq
  "head": "<event id of that entry>",
  "status": "running",
  "paused": false,
  "config_revision": 3,
  "ruleset_hash": "<64 hex>",
  "participants": [                 // sorted by pubkey ascending — never by arrival
    {
      "pubkey": "…",
      "display": "…",
      "division": "open",
      "registration": "accepted",   // pending | accepted | waitlisted | rejected | withdrawn
      "waitlist_position": 0,       // 0 when not waitlisted
      "payment": "not_required",    // not_required | pending | settled | failed | expired | refunded
      "checkin": "checked_in",      // none | checked_in | no_show
      "selections": ["c1","c4"],    // granted climb ids (uniqueness-enforced)
      "defers_used_this_round": 0,
      "consecutive_defers": 0,
      "result": "active",           // active | finished | dnf | dns | withdrawn | disqualified
      "climbs": [
        { "climb_id": "c1", "attempts_used": 2, "outcome": "top", "at": 1789002345 }
      ]
    }
  ],
  "round": 1,
  "current_climb_id": "c1",
  "order": ["<pubkey>", "…"],       // turn order for the current round
  "cursor": 3,                      // index into order whose turn is open, -1 = no turn open
  "turn_opened_at": 1789002200,
  "turn_deadline_at": 1789002320,
  "announcements": [ { "seq": 30, "text": "…", "at": 1789002000 } ],
  "claims": { "c1": "<pubkey>" },   // granted unique-claim map, present when uniqueness enforced
  "audit": [ { "seq": 41, "op": "override", "reason": "…", "at": 1789002300 } ],
  "rejected": [ { "seq": 12, "op": "checkin", "code": "wrong_status" } ],
  "fork_detected": false,
  "chain_complete": true,
  "from_snapshot": false
}
```

`rejected` records every entry the reducer refused to apply, and it **is part of
the hashed state**. That is deliberate: if two clients disagree about whether an
entry was legal, they must fail the conformance test loudly rather than diverge
quietly at some later event that depended on it.

Its `code` comes from a closed set (`REJECTION_CODES` in both implementations),
never a sentence. Sentences have to be translated into German; a hash must not
be. The UI maps code → localized explanation.

`from_snapshot` is excluded from the hash — it records how *this* client got
here, not what the competition is — so a client that started from a snapshot and
one that replayed the whole chain still agree.

Every array in the reduced state has a **specified sort order**, because
"whatever order the events arrived in" is not deterministic across clients:

- `participants` — ascending by `pubkey` hex.
- `climbs` inside a participant — ascending by `climb_id`.
- `order` — as materialized by the seeding rule (§9.1), then mutated only by
  explicit `queue` entries.
- `announcements`, `audit` — ascending by `seq`.

### 7.2 The algorithm

```
reduce(competitionEvent, logEvents, now):
  1. validate competitionEvent (§2.3, §6.1); on failure → InvalidCompetition
  2. state ← initialState(competitionEvent)
  3. accepted ← logEvents filtered by:
       - envelope valid (§2.3)
       - pubkey == competition.authority
       - a-tag == competition address
       - d-tag == cruxcoach:comp:<compId>:log:<seq:06d> with seq matching content.seq
       - epoch valid for its position (§5.3)
  4. index ← accepted grouped by seq
  5. chain ← walk from seq 1:
       expected prev = competitionEvent.id
       for n = 1, 2, 3, …:
         candidates ← index[n]
         if candidates is empty → stop (chain ends here)
         valid ← candidates where prev == expected
         if valid is empty  → stop; record chain_break at n
         if valid has > 1   → fork_detected = true; pick deterministically (§7.4)
         apply(state, chosen); expected = chosen.id
  6. state.seq, state.head ← last applied
  7. return state
```

Entries after a chain break are **not applied**. A gap is not a licence to skip
ahead: an unfetched entry 41 may be the disqualification that changes everything
in 42. The UI shows "waiting for entry 41" and keeps retrying the relays.

### 7.3 Idempotency

Applying the same log entry twice must be a no-op. This is guaranteed
structurally rather than by defensive checks: the chain walk consumes each `seq`
exactly once, and duplicate deliveries of the same event id collapse in the
`index` map. Every `apply` handler is additionally written to be idempotent so a
snapshot-then-replay path cannot double-count.

### 7.4 Forks

Two valid entries at the same `seq` with the same `prev` is a fork — the
authority signed twice. It is not recoverable by voting; there is no quorum.
Conformant behaviour:

1. Set `fork_detected = true` permanently for this reduction.
2. Choose deterministically: **lower `created_at` wins; ties broken by
   lexicographically lower event id.** Every client therefore shows the same
   branch, which is worth more than picking the "right" one.
3. Surface it. The organizer console shows a blocking banner naming both event
   ids; the participant view and projector show "results under review".
4. Never publish final results while `fork_detected` is true. The organizer must
   resolve it with an explicit `op: "correction"` entry that names the discarded
   branch.

---

## 8. Operations

### 8.1 Authority log operations

| `op` | `data` | Effect |
|---|---|---|
| `lifecycle` | `{ "status": "<new status>", "at": int }` | Sets `status`. Legal transitions in §10.1; an illegal one is rejected and recorded in `audit`. |
| `registration_decision` | `{ "pubkey", "decision": "accepted"\|"waitlisted"\|"rejected", "division"?, "waitlist_position"?, "display"? }` | Sets the participant's `registration`. `accepted` beyond `capacity` is rejected by the reducer unless `capacity == 0`. |
| `payment_decision` | `{ "pubkey", "state": "settled"\|"failed"\|"expired"\|"refunded", "zap_receipt_id"?, "amount_msat"? }` | Sets `payment`. §11. |
| `claim_decision` | `{ "pubkey", "climb_id", "decision": "granted"\|"denied", "reason"? }` | First `granted` for a `climb_id` wins and writes `claims[climb_id]`. A later `granted` for an already-claimed climb is **rejected by the reducer**, not by the authority's good behaviour — so a buggy or malicious authority cannot double-grant without every client seeing it. |
| `checkin` | `{ "pubkey", "state": "checked_in"\|"no_show" }` | Sets `checkin`. |
| `queue` | `{ "action": "seed"\|"open_turn"\|"close_turn"\|"advance"\|"reorder"\|"next_climb"\|"next_round", …}` | §9. |
| `defer_decision` | `{ "pubkey", "decision": "granted"\|"denied", "reason"?, "new_index"? }` | §9.3. |
| `attempt_result` | `{ "pubkey", "climb_id", "outcome": "top"\|"zone"\|"fall"\|"pass"\|"timeout", "attempt_no": int }` | Appends to the participant's climb record and increments `attempts_used`. |
| `correction` | `{ "supersedes_seq": int, "replacement": { …a full op body… } }` + `reason` | Re-applies the named entry's effect with the replacement body. The original stays in the log forever; only the reduced state changes. |
| `override` | `{ …any op body… }` with `actor: "organizer_override"` + `reason` | Same effect as the wrapped op, but always appended to `audit`. |
| `announcement` | `{ "text": "…" }` | Appends to `announcements`. |
| `disqualify` | `{ "pubkey" }` + `reason` | Sets `result: "disqualified"`; the participant is removed from `order` at the next queue action. |

`reason` is **mandatory** on `correction`, `override`, `disqualify`, and on any
`decision: "rejected" | "denied"`. A reducer rejects the entry without one. An
audit trail whose entries do not say why is a log, not an audit trail.

### 8.2 Participant intent operations

| `op` | `data` | Expires |
|---|---|---|
| `register` | `{ "division", "display", "waiver_accepted": bool, "selections": ["c1", …]? }` | at `registration_closes_at` |
| `withdraw` | `{}` | — |
| `checkin_request` | `{}` | at `checkin_closes_at` |
| `defer_request` | `{ "climb_id" }` | `turn_deadline_at` (NIP-40 `expiration`) |
| `attempt_report` | `{ "climb_id", "outcome", "attempt_no" }` | at `ends_at` |
| `payment_claim` | `{ "zap_receipt_id", "bolt11"? }` | at `ends_at` |

`attempt_report` exists for asynchronous turns, where the organizer is not
standing at the wall for every attempt. It is still only an intent: the
projector and the leaderboard show a self-report as **`unconfirmed`** until the
authority mirrors it with an `attempt_result`. Both states are visually
distinct, and a self-report never contributes to a published standing.

---

## 9. Queue, turns and the defer rule

### 9.1 Seeding

`queue/seed` carries the running order **explicitly, as data**:
`data.order` is the full list of pubkeys.

The order is *data in the log*, not a computation the reducer performs. That
choice matters twice over. It keeps the reducer synchronous and hash-free (a
reducer that has to hash is a reducer that has to be async, in two languages,
inside a render loop). And it makes the order auditable: it is signed, it is
permanent, and it cannot silently differ between two clients that disagree about
a hash function's byte order.

What the reducer *does* enforce is that the order is honest: it must be a
permutation with no duplicates, every entry must be an accepted and checked-in
participant, and a `seed` must contain **every** eligible participant — not a
convenient subset. Those three checks are what a published order can be held to;
"the organizer must have shuffled fairly" is not.

The organizer console computes the default order by sorting **ascending by
`sha256(compId || pubkey)`**: reproducible, unpredictable before the competition
id exists, and not gameable by choosing a vanity pubkey after the fact (the
organizer draws `compId` at publish time). Any client may recompute it and warn
on a mismatch; that check is advisory and lives outside the reducer.

Divisions run as independent orders when `divisions.length > 1`; the reduced
state keeps one `order` per division under the same rules. v1 UI runs one
division at a time and says which.

### 9.2 Turn lifecycle

```
open_turn(index)  → cursor = index
                    turn_opened_at = at
                    turn_deadline_at = at + rules.turn_deadline_sec
close_turn        → cursor = -1
advance           → cursor = next eligible index, wrapping into next_climb / next_round
```

Eligible means: `registration == accepted`, `checkin == checked_in`,
`result == active`, and `min_rest_sec` has elapsed since this participant's last
`attempt_result`. A participant who is not yet rested is **skipped forward**,
not stalled behind — the queue never blocks on one person's rest timer.

### 9.3 The defer rule

The requirement is a defer that is fair, bounded, understandable at a glance,
and incapable of either stalling the event or quietly buying someone extra
attempts. The rule:

**Budget.** Each participant gets `rules.defer_budget_per_round` deferrals per
round (default 1) and at most `rules.max_consecutive_defers` in immediate
succession (default 1).

**Effect.** A granted defer moves the participant back by exactly
`rules.defer_slots` positions (default 2) **within the current round**:

```
newIndex = min(currentIndex + defer_slots, lastEligibleIndexInRound)
```

It never moves them to the end of the round, and it never carries a backlog into
the next round. `defers_used_this_round` and `consecutive_defers` increment;
`consecutive_defers` resets to 0 on any completed attempt.

**No attempt inflation.** Deferring does not change `attempts_per_climb` and
does not consume an attempt. The participant arrives at their new slot with
exactly the attempts they had.

**Deadline and consequence.** `turn_deadline_at` is set when the turn opens. If
it passes with no `attempt_result` and no granted defer, the authority records
`attempt_result{outcome: "timeout"}`, which **consumes one attempt**. This is
the explicit consequence that makes stalling unprofitable. When attempts are
exhausted that way, the climb's outcome becomes `dnf` and the queue advances.

**Refusal is legal and named.** A defer request with no budget left, or a second
consecutive one, gets `defer_decision{decision: "denied", reason: "budget"}`.
The participant screen shows "No deferrals left" *before* the button is
offered — the control is absent, not disabled-and-lying.

**Round boundary.** A participant who deferred into the last slot of a round and
still did not climb takes the timeout consequence there. Nothing is pushed past
the round boundary, so a late defer cannot disadvantage the people who took
their turn on time.

**Visibility.** The projector and the participant view both show, per person:
deferrals remaining, the position change when one is granted, and the live turn
countdown. A rule nobody can see is a rule nobody accepts.

### 9.4 Reconnect, late join, no-show

- **Reconnect** is a pure re-read. State lives in the log; a client that was
  offline replays from its cursor. There is no session to restore.
- **Late arrival** during `checkin_open` is a normal `checkin`; the participant
  is appended to `order` at the next `seed`/`reorder` if
  `rules.late_entry_allowed`, else recorded `dns`.
- **No-show** is an explicit authority decision, never a timeout inference, and
  it sets `result: "dns"`.

---

## 10. Lifecycle

### 10.1 Legal transitions

```
draft ──▶ published ──▶ registration_open ──▶ registration_closed ──▶ checkin_open
                                                                          │
                                                                          ▼
                                    finished ◀── running ⇄ paused ◀───────┘
                                        │
                                        ▼
                                 (results document)

cancelled: reachable from draft, published, registration_open,
           registration_closed, checkin_open, running, paused.
```

`finished` is terminal apart from publishing `results` and appending
`correction` entries. `cancelled` is terminal.

### 10.2 What each state permits

| Status | Registration intents | Check-in | Queue ops | Attempt results |
|---|---|---|---|---|
| `draft` | ignored | ignored | ignored | ignored |
| `published` | ignored | ignored | ignored | ignored |
| `registration_open` | accepted | ignored | ignored | ignored |
| `registration_closed` | ignored (withdraw still honoured) | ignored | ignored | ignored |
| `checkin_open` | ignored (withdraw honoured) | accepted | `seed` only | ignored |
| `running` | ignored (withdraw honoured) | late check-in if allowed | all | accepted |
| `paused` | ignored | ignored | none | ignored |
| `finished` | ignored | ignored | none | corrections only |
| `cancelled` | ignored | ignored | none | ignored |

"Ignored" means the reducer does not apply it and records the rejection in
`audit` — it does not mean the client hides it.

---

## 11. Money: fees, privacy, and prizes

### 11.0 What CruxCoach is, and is not

**CruxCoach never holds competition money.** It does not pool, escrow, custody,
split, reserve or intermediate a single satoshi. There is no prize pot, no
platform balance, and no platform fee. Every payment in this feature is
**wallet to wallet**: a participant's own wallet pays a Lightning destination
the organizer controls, and an organizer's own wallet pays a winner.

What the software does is narrower and worth stating precisely:

- it helps produce and check an invoice handoff
- it records competition state — `pending`, `settled`, `refunded`, `claimed`,
  `paid` — which are *statements about the competition*, not balances
- it never possesses funds, and therefore cannot refund, reverse, guarantee or
  release them

**A configured cash prize is an organizer's promise, not a funded pot.** Entry
fees are not linked to prizes by this protocol: fees go to the organizer's
destination and stay there, and a prize is paid from the organizer's own wallet
whether or not anybody paid a fee. Nothing here escrows an entry fee against a
future prize, and no screen may imply otherwise. Both clients say this before a
competition with a fee or a prize is created, and again before an entrant pays
or a winner claims.

### 11.1 The zero-fee path

`fee_msat: 0`. Every participant is `not_required` and nothing below applies.
This is the default and the one that involves no money at all.

### 11.2 What the old design leaked, and why it changed

The first implementation used a plain NIP-57 zap: the participant signed a
kind-9734 with their **long-term identity key**, carrying the competition's `a`
coordinate, the amount and the registration nonce; the provider then published a
kind-9735 whose `description` tag repeats that request **verbatim** on public
relays, with the payer's key again in `P`.

Anybody scraping relays could therefore read: *this person attends this
competition, paid this much, at this time* — permanently, and correlated with
everything else that key has ever done. That was described as the paid path
without ever being examined as a privacy question. It is not privacy-preserving
and this document no longer implies that it is.

NIP-57 acknowledges the gap in its own text — "zaps can be extended to be more
private by encrypting zap request notes to the target user, but for simplicity
it has been left out of this initial draft" — so **private zaps are unstandardised
future work.** Nothing here depends on them, and no claim is made that they are
portable today.

### 11.3 The privacy-first default: a direct invoice and an encrypted preimage

The default path publishes **nothing at all** about the payment.

1. The participant's client resolves the organizer's LNURL-pay endpoint (§11.5)
   and asks for an invoice **without** the `nostr` parameter. No zap request is
   created, so no kind-9734 exists to be republished.
2. The client checks the invoice before showing it: amount equal to the fee,
   readable, not already expired (§11.5).
3. The participant pays from their own wallet. Their wallet returns a
   **payment preimage**.
4. The client sends the organizer a `payment_claim` intent whose content is
   **NIP-44 encrypted to the organizer's key**, carrying the preimage, the
   invoice, and the registration nonce.
5. The organizer's console decrypts it and verifies, with no network and no
   third party: `sha256(preimage) == payment_hash` from the invoice, and the
   invoice amount equals the fee.

**Why this is stronger than a zap receipt.** BOLT11 defines the payment hash as
"256-bit SHA256 payment_hash. Preimage of this provides proof of payment", and
the payer learns the preimage only when the payment settles. That is
cryptography. A zap receipt, by the spec's own admission, "is not a proof of
payment, all it proves is that some nostr user fetched an invoice". The private
path is therefore the *more* trustworthy one as well as the more private one —
an unusual and welcome combination, and the reason it is the default rather than
a hardened option.

**What is public afterwards:** `payment_decision{state: "settled"}` naming the
participant and nothing else. No amount, no invoice, no preimage, no endpoint.

**The honest limit:** not every wallet surfaces a preimage, and a person can
paste one they were given rather than one they obtained by paying. The first is
handled by the fallback below. The second is not a real attack — the only way to
obtain a valid preimage is for the invoice to have been settled, which is what
the organizer is being asked to believe.

### 11.4 The fallback: an ephemeral zap key and a one-time token

For a wallet that cannot produce a preimage, an automatic path still exists, and
it still does not publish the participant's identity.

- The client generates a **throwaway keypair** for this payment alone. The zap
  request is signed by that key, never by the participant's identity key.
- The request carries `p` (the organizer), `amount`, `relays`, and a **random
  one-time token**. It deliberately **omits the `a` coordinate** — NIP-57 marks
  `a` optional, so a conformant provider is unaffected, and the public receipt
  therefore does not name the competition.
- The participant sends the organizer a NIP-44 encrypted `payment_claim`
  binding *their real pubkey ↔ the token ↔ the ephemeral pubkey*.
- The organizer's console finds the 9735, checks it against the key their own
  endpoint named, matches the token and the ephemeral key from the decrypted
  mapping, and checks the amount.

**What still leaks:** an observer sees that the organizer's destination received
a payment of some amount at some time from a key that has never appeared before
and never will again. They cannot tell who paid or which competition it was for.
That residue is unavoidable for any automatic path built on public receipts, and
it is stated rather than glossed.

**Fee destination.** The organizer's Lightning address is in the competition
document, which is public — a poster and a website have to show people where to
pay. Hiding it would need a per-participant encrypted handoff, which needs the
organizer's client online at registration time; v0.2.3 has no coordinator and no
persistent organizer process, so **this is not attempted**. The limitation is
recorded here rather than papered over with a privacy claim the code does not
earn.

### 11.5 Invoice and endpoint rules, in both clients

Identical in `competitions/app/protocol/{lnurl,bolt11}.mjs` and
`domain/competition/Competition{Lnurl,Bolt11}.kt`, pinned by shared fixtures:

- **https only**, never downgraded, never `.onion`, and no credentials in the
  authority — `https://evil.example@bank.example` reads as the bank and resolves
  to the attacker.
- `tag == "payRequest"`, callback checked as strictly as the endpoint, metadata
  present and a JSON array, amount inside `minSendable`/`maxSendable`.
- The invoice is **decoded and its amount compared to the fee before it is
  shown**. A mismatch is refused, not displayed with a warning: the number on
  the screen and the number the wallet would send must be the same number.
- Expiry is read and stated; an already-expired invoice is never offered.

### 11.6 The manual path stays, and stays honest

Gyms take cash. An organizer may always record a payment by hand — as an
`override` carrying a **mandatory reason**, which the reducer writes into
`state.audit` where every client can read it. It is deliberately a different
control from the verified one, because "settled because the maths says so" and
"settled because the organizer says so" are different claims and a record that
conflates them is worth less than one that does not.

### 11.7 Prize claims

`prizes` was metadata: a competition could promise money with no way to ask for
it. A winner can now claim, from either client, after results are final.

**The rules that make a claim safe:**

- Every prize has a **stable id** and unambiguous eligibility: a `rank`, and a
  `division` where the competition has more than one. Validation refuses two
  prizes claiming the same (division, rank), because two people would then be
  entitled to one payment.
- A claim is a **NIP-44 encrypted intent**. The payout destination — a Lightning
  address or an exact-amount BOLT11 — never touches the public log, nor does any
  contact detail for a non-cash prize.
- The claim is **bound to one result**: it names the competition, the prize id
  and the `results_hash` of the final standings it was made against. A claim
  cannot be replayed into another competition, and a corrected result invalidates
  claims made against the old one rather than silently paying out on it.
- The authority verifies **before** showing an organizer anything: the claimant
  signed the intent, the claimant is the entrant standing at that rank in that
  division in the final results, the prize is unclaimed, and the destination
  parses and — for a BOLT11 — is for the right amount and not expired.
- The public log records only `prize_decision{prize_id, pubkey, state}` where
  state is `claimed`, `approved`, `paid` or `rejected`. The reducer refuses a
  second `approved` or `paid` for a prize already held by somebody else, so a
  double payout is a protocol error rather than an organizer's mistake.
- **`paid` is the organizer's assertion**, and the spec says so. The optional
  winner-signed acknowledgement (`prize_receipt`) is the only evidence that
  comes from the other side, and its absence is shown rather than assumed.
- A **claim deadline** (default 30 days after results) bounds the organizer's
  exposure. After it, unclaimed prizes are `expired` — a state, not a transfer.

**What CruxCoach still does not do:** hold the prize, guarantee it, or verify
that it arrived. A cash prize is one person promising to pay another, recorded
where both can see it.

## 12. Optional NIP-52 companion

When `visibility == "public"`, the organizer's client MAY additionally publish a
NIP-52 kind **31923** (time-based calendar event) at
d-tag `cruxcoach:comp:<compId>:cal`, with `title`, `start`, `end`, `location`,
and an `r` tag pointing at the HTTPS join link.

It is advisory: no CruxCoach client reduces state from it, it carries no
registration or result data, and deleting it changes nothing. It exists so a
calendar client shows a correct entry instead of the competition being invisible
outside CruxCoach.

---

## 13. Privacy

### 13.1 What v1 publishes, and the minimization rule

Public per participant: **pubkey**, chosen **display name**, division,
registration state, check-in state, payment state (as a word, never an amount
paid), attempts, outcomes, standing.

Deliberately never published: real name, contact details, age, the waiver text a
participant signed, IP or device data, anything about a participant who only
*intended* to register and was rejected (a rejection decision names the pubkey,
which is unavoidable if the participant is to learn of it, but carries no
reason string to the public log — the reason goes to the participant by DM).

`display` is participant-chosen and defaults to the kind-0 `name`; the join UI
says in one sentence that it will appear on a public screen and on public
relays, and offers a pseudonym field in the same breath.

### 13.2 Private competitions (designed, not shipped)

The forward-compatible shape, so v1 does not have to be unpicked:

- `visibility: "private"` on the competition document.
- The *envelope* stays kind 30078 with the same `d`-tag, but `content` becomes a
  NIP-44 v2 ciphertext instead of CCJ, and the event carries
  `["cc-enc", "nip44:v2"]`.
- The competition key is a symmetric key wrapped once per participant into a
  per-participant addressable event, so adding a participant is one event and
  never a re-encryption of history.
- Key rotation on removal: the authority publishes a new epoch key and
  re-wraps for the remaining members; past events stay readable by the removed
  member (they were), which the UI must state plainly rather than imply erasure.
- Relay-side read control (NIP-42 AUTH) is complementary, not a substitute:
  it limits *distribution*, not *decryptability*.

Nothing about the v1 reducer changes: it decrypts and then runs the identical
algorithm. That is the point of specifying it now.

### 13.3 Metadata that separate keys do not hide

A relay operator sees which IP subscribed to which competition address and when.
Publishing under a fresh key does not change that. Tor/VPN is the only mitigation
and is out of scope; the privacy page says so rather than implying otherwise.

---

## 14. The coordinator boundary

v1 has no server. The protocol nonetheless has a named seam, so introducing one
later is an implementation change rather than a protocol reset:

- **`authority` is already a separate pubkey** (§5.1). A coordinator is
  introduced by naming it there.
- **`authority_epoch` already exists** (§5.3), so a handover from an organizer
  key to a coordinator key is auditable and cannot be forged retroactively.
- **A coordinator authenticates over HTTP with NIP-98** (kind 27235), which is
  why NIP-98 is in the source register despite being unused in v1.
- **What a coordinator would add:** redundancy (it is online when the
  organizer's laptop is not), sub-second claim serialization, short join codes
  (§3.3), and relay fan-out.
- **What it would not change:** the event shapes, the reducer, the fixtures, or
  any client's trust rules. A client that never learns a coordinator exists
  continues to work.

---

## 15. Versioning and compatibility

### 15.1 Rules

- `cc-schema` tag = `cruxcoach-competition/<major>`. A client rejects an event
  whose major it does not implement, and says so in the UI ("this competition
  needs a newer CruxCoach") rather than reducing a partial state.
- Within a major version, **new optional fields are additive** and older clients
  ignore them. A new field may never change the meaning of an existing one.
- **New `op` values** are additive. An unknown `op` is *not* ignored: it breaks
  the chain, because an entry a client cannot interpret may be exactly the
  disqualification that changes the standings. The client stops, shows "this
  competition uses a newer rule this app doesn't know", and refuses to display a
  leaderboard it cannot vouch for.
- **New `rules` values** (a new `progression`, `scoring`, …) are handled the same
  way: unknown enum value → refuse, name the value, do not guess.

### 15.2 Migration from v1 to v2

A v2 competition is a *new* competition document with `v: 2`. Live v1
competitions are never rewritten. Both may exist on the same relays.

### 15.3 Changing the carrier kind

If a dedicated kind is later allocated, the transition is: (a) authority
dual-publishes each event under both kinds with identical content for one
release cycle; (b) clients prefer the new kind and fall back; (c) the old kind
is dropped a release later. The `d`-tag, the payloads and the reducer are
untouched, which is why §2.1's choice is reversible.

---

## 16. Limits and validation

### 16.1 Field limits

| Field | Limit |
|---|---|
| `title` | 1–120 chars |
| `summary` | 0–140 chars |
| `description` | 0–4000 chars |
| `eligibility`, `waiver`, `participant_instructions`, `spectator_info`, `refund_policy` | 0–2000 chars each |
| `display` | 1–48 chars, no control characters |
| `divisions` | 1–8 |
| `climbs` | 1–40 |
| `prizes` | 0–10 |
| `relays` | 1–8; `wss://` anywhere, `ws://` **only** for loopback (§16.6) |
| `capacity` | 0–500 |
| `attempts_per_climb` | 1–20 |
| `turn_deadline_sec` | 30–1800 |
| `min_rest_sec` | 0–3600 |
| `defer_budget_per_round` | 0–5 |
| `defer_slots` | 1–10 |
| any JSON object key | ASCII `[a-z0-9_]` only |

### 16.2 Event and competition size, and the real relay limits

Measured from the NIP-11 documents of the relays this app ships with,
2026-08-09:

| Relay | `max_message_length` | `max_limit` | `max_subscriptions` |
|---|---|---|---|
| `nos.lol` | 131 072 (whole frame) | 500 | 20 |
| `relay.primal.net` | — | 500 | 20 |
| `nostr.wine` | 524 288 | 1000 | 50 |
| `purplepag.es` | not advertised (`max_content_length` 131 072) | 500 | 50 |

Consequences this protocol is built around:

- **A single event's serialized JSON must be `<= 64 KiB`.** That is half the
  tightest observed frame limit, deliberately — the frame carries the whole
  `["EVENT", …]` array, not just the event, and this project has already been
  bitten by a 108 KiB manifest that exactly one relay of nine accepted
  (`docs/nostr-architecture.md` §3). The competition document is bounded by
  §16.1 to roughly 12 KiB at maximum configuration.
- **`max_limit` is clamped silently.** A client must never infer "that is all of
  them" from a short result set. Log fetches page by `seq` ranges using `since`/
  `until` and stop only when the chain is contiguous to the head, which is the
  same property §7.2 already needs for correctness.
- **20 concurrent subscriptions** is the tightest budget. A client uses at most
  three per competition (document, log, intents) so several competitions can be
  open at once without hitting it.
- `nostr.wine` advertises `created_at_upper_limit: 300`, i.e. it rejects events
  stamped more than five minutes ahead. Publishers therefore never stamp further
  ahead than the monotonic clamp strictly requires (§4.4), which for log entries
  — each on its own fresh d-tag — is never.
- A competition is capped at **999 999 log entries** by the d-tag shape, and in
  practice at `capacity × climbs × attempts × ~2`, i.e. under 100 000 for the
  largest configuration §16.1 permits.

### 16.3 Deletion, and why the log is never deleted

NIP-09 interacts badly with an audit trail, and the interaction is specified
rather than discovered later:

- An `a`-tag deletion removes addressable versions only **up to the kind-5's
  `created_at`**. Republishing the same coordinate with a later timestamp
  resurrects it. There is no permanent-tombstone primitive.
- Kind 5 is a *separate regular event*, so a read path that queries only
  competition events never sees a deletion. Any path that must honour deletion
  needs a second query.
- A client MUST verify that the deletion's author equals the author of the
  target; relays cannot be trusted to have checked this.

Therefore:

1. **Log entries are never deleted, and a kind-5 against a log entry has no
   protocol effect.** A conformant reducer ignores deletion requests for log
   entries entirely. Rewriting history is done with `op: "correction"`, which is
   additive and visible. If deletion could remove a log entry, an organizer
   could quietly erase a disqualification — the exact thing the audit trail
   exists to prevent.
2. **Withdrawal is not deletion.** A participant cannot be removed by the
   organizer deleting their intent (NIP-09 forbids it — only the author may
   delete their own event, and the organizer is not the author). Withdrawal is
   an `intent{op: "withdraw"}` followed by an authority decision, which is why
   §8.2 has it.
3. **Cancelling a competition** publishes `lifecycle{status: "cancelled"}` *and*
   an addressable tombstone plus a NIP-09 kind-5 on the competition document —
   both, because relays honour different halves. The log stays, so the record of
   what happened stays.
4. Because relays *may* honour a kind-5 the protocol ignores, a late reader can
   find a chain break. That is the same visible-stall failure mode as a relay
   outage (T6), and the mitigation is the same: snapshots plus the organizer's
   local copy plus republish.

### 16.4 Validation is client-side and repeated

Relays are not trusted to have applied the filter they were given. Every
consumer re-checks kind, author, `L` namespace, `d`-tag shape, `a`-tag target
and clock skew locally — the same defence the manifest path already needed when
a relay could answer a Kilter query with the MoonBoard manifest.

---

### 16.6 Which relay URLs a client will talk to

`wss://` anywhere; `ws://` **only** when the host is `localhost`, `127.0.0.1` or
`[::1]`.

Cleartext WebSocket to a public host would let any network on the path rewrite a
competition's results in transit, so it is refused outright — a transport that
can be downgraded is one that can be edited. Cleartext to loopback has no
network on the path by definition, and it is the only way the development relay
in the localhost runbook can be reached at all; a TLS certificate for a
throwaway loopback port would be theatre.

A host that merely *starts* with a loopback literal — `ws://127.0.0.1.evil.example`
— is not loopback and is refused. The check parses the authority and compares
the host exactly, case-insensitively.

This is a cross-client rule: if one client accepts a competition the other
rejects, the two disagree about which competitions exist. It is therefore pinned
by shared vectors (`vectors/protocol.json` → `relay_urls`), implemented once per
language (`competitions/app/protocol/relay-url.mjs`,
`CompetitionProtocol.isAllowedRelayUrl`), and asserted by both suites.

A client whose resolved relay set contains a loopback relay MUST say so in the
UI. A competition running against a development relay is not a real competition,
and the screen has to be honest about that rather than looking identical to one.

---

## 17. Threat model

| # | Threat | Mitigation | Residual risk |
|---|---|---|---|
| T1 | **Forged result** — someone publishes an attempt result for another climber | Only `authority`-signed log entries are reduced. Anyone else's event fails the author check before any field is read. | An authority whose key is stolen (T7). |
| T2 | **Replay** — an old log entry is re-served by a lagging relay | The `seq`+`prev` chain: a replayed entry either is already consumed at its seq or fails the `prev` link. Idempotent apply (§7.3). | None material. |
| T3 | **Clock manipulation** — an event dated far in the future | `created_at <= now + 1 h` rejection at the envelope, and reduction order comes from `seq`, not from time. Time is display-only. | An authority can misdate its own entries; the ordering is unaffected. |
| T4 | **Malicious organizer** — rigs the standings | Not preventable, and the protocol says so. What it *does* give: every decision is signed, ordered, chained, permanently public, and carries a mandatory reason for every rejection and override. Rigging is visible, attributable and non-repudiable. | The organizer runs their event; entrants choose whether to trust them. |
| T5 | **Double-granted unique claim** | The reducer rejects a second `granted` for a claimed `climb_id` (§8.1) rather than trusting the authority to be correct. Every client sees the same rejection. | A participant who claimed second sees a denial they must resolve with the organizer. |
| T6 | **Relay outage / censorship** — a relay withholds entries | Multi-relay publish and read (`competition.relays` merged additively with NIP-65, never narrowed). A chain break stops reduction rather than skipping (§7.2), so withholding produces a visible stall, not a wrong leaderboard. | A total outage stops the live view; the organizer console keeps its local copy and republishes. |
| T7 | **Compromised signer** | Fork detection (§7.4) makes a second signing device or a thief visible immediately. `authority_epoch` (§5.3) is the recovery path. | An undetected compromise before any fork is indistinguishable from the real organizer. |
| T8 | **Payment spoofing** | §11.3 — receipt must be signed by the zapper key the organizer fetched themselves, and must contain a valid participant-signed 9734 bound to this competition and intent. | A dishonest LNURL provider can lie to its own customer. |
| T9 | **QR / deep-link injection** | The join path parses `naddr` strictly (bech32 checksum, TLV, `kind == 30078`, d-tag prefix `cruxcoach:comp:`), and only then fetches. No URL from a QR is ever navigated to directly, and no HTML from any event is ever inserted as markup. | None known. |
| T10 | **XSS via competition text** | Every event-sourced string reaches the DOM through `textContent` / Compose `Text`, never `innerHTML`. The website ships a strict CSP with no `unsafe-inline` for scripts. | None known. |
| T11 | **Local key theft (web)** | The local key is stored only as AES-GCM ciphertext under a PBKDF2-SHA-256 key derived from a user passphrase (§18). Plaintext exists only in a JS variable for the lifetime of a signing call and is zeroed after. External signers (NIP-07/NIP-46) avoid the problem entirely and are the recommended path. | A compromised browser can read the passphrase as it is typed. Stated plainly in the UI. |
| T12 | **Spam / abuse** — flooded intents | Intents are not state; the authority's console rate-limits and groups by pubkey, and unknown pubkeys' intents are collapsed. Size caps at the envelope. | Relay-level flooding is the relay's problem. |
| T13 | **Metadata leak** | §13.3 — acknowledged, not solved. | Real. Documented rather than hidden. |
| T14 | **Participant deanonymization via display name** | Pseudonym offered at registration with a plain-language warning. | A participant who chooses their real name has chosen it. |
| T15 | **History erasure via NIP-09** — the authority publishes a kind-5 against an inconvenient log entry | The reducer ignores deletion requests for log entries entirely (§16.3); corrections are additive and named. | A relay that honours the kind-5 makes the entry unfetchable for late readers, which appears as a chain break (T6), not as an altered result. |
| T16 | **Coordinate resurrection** — a deleted competition document is republished with a later `created_at` | Inherent to NIP-09 (deletion covers only versions up to the request's timestamp). Cancellation is therefore a `lifecycle` log entry first and a deletion second, so the cancelled state survives resurrection of the document. | An organizer can un-cancel; the log shows both, in order. |

---

## 18. Web key handling (threat model and rules)

The Android app already has `NostrKeyStore` and Amber. The website needs its own
answer, and it is the highest-risk surface in this feature.

**Order of preference, enforced by the UI:**

1. **NIP-07 browser extension** — key never enters our page.
2. **NIP-46 remote signer / bunker** (Amber-compatible) — key never enters our
   page; approval happens on the user's own device.
3. **Locally generated key** — offered last, with the trade-off stated before
   generation, not after.

**NIP-07 facts this implementation is built on** (spec accessed 2026-08-09):
the current surface is exactly `getPublicKey()`, `signEvent()`, optional
`nip04.*` (marked deprecated in the spec text itself) and optional `nip44.*`.
**`getRelays()` was removed** from NIP-07 (and from NIP-46, where only
`switch_relays` survives), so nothing here may depend on asking an extension for
relays; relays come from the competition document merged with the user's NIP-65
list. Extensions inject `window.nostr` asynchronously, so detection polls for up
to 1 s rather than testing once on load — a single synchronous check is a real
race, and it is the bug the dashboard's sign-in currently has.

**NIP-46 facts this implementation is built on** (spec accessed 2026-08-09):
transport is kind **24133** (ephemeral), content is NIP-44-encrypted, addressed
with `["p", <remote-signer-pubkey>]` and signed by a throwaway client key. The
spec's own `## Changes` block flags the two things implementations get wrong:
**`remote-signer-pubkey` is not the same as `user-pubkey`**, so the client
**must** call `get_public_key` after `connect` rather than assuming the pubkey in
the `bunker://` URI is the user's; and NIP-05 login was removed. Both
`bunker://` and `nostrconnect://` are supported here, `nostrconnect://` also as
a QR so Amber can scan it. Every request carries a timeout and surfaces a named
failure — an un-settling promise is the dashboard's current behaviour and it
presents to the user as a permanently spinning button.

**Rules for the local key:**

- Generated with `crypto.getRandomValues`. No custom PRNG, no `Math.random`.
- **Never persisted in plaintext.** Storage is AES-GCM ciphertext with a random
  96-bit IV, keyed by PBKDF2-SHA-256 (600 000 iterations, random 128-bit salt)
  over a user passphrase. The plaintext key is held in a `Uint8Array` owned by
  the session and overwritten with zeros **by a scheduled timer** at the
  absolute limit, at the idle limit, and five minutes after the page is hidden.
  The distinction matters: an implementation that only checks the deadline
  before the next signing call leaves the key in memory for as long as nobody
  signs anything, which is most of a competition.
- **Backup confirmation is real.** The user must re-enter three challenged
  **characters**, at positions derived from the key itself, of the displayed
  `nsec`'s checksum-verified encoding before the flow completes. "I have written
  it down" alone does not advance. (Three *words* would be a seed-phrase
  challenge; an `nsec` is bech32 and has no words.)
- **Clipboard:** copying the nsec is a distinct, explicitly-labelled action that
  warns first and schedules a best-effort clipboard clear after 60 s. It is never
  copied implicitly.
- **Screen reader:** the nsec field is a `readonly` input with an
  `aria-describedby` warning that is announced *before* the value.
- **Shared device:** a "this is a shared device" choice keeps the key in memory
  only for the session and never writes storage at all.
- **Session expiry:** 12 h absolute, 60 min idle, 5 min hidden — each armed as
  a timer when the key is adopted and re-armed on use, so the zeroing happens
  whether or not anything asks the session a question. Locking, forgetting and
  disposing all cancel every timer; the page-visibility listener is attached
  once per session and removed by `dispose()`, so a page that replaces its
  session does not accumulate one per sign-in.
- **Sign out and forget are different actions, deliberately.** *Sign out* zeroes
  the plaintext and **keeps** the encrypted vault, because somebody signing out
  on their own phone expects to return with their passphrase, and a session
  button that destroyed the only copy of a key would be a data-loss button
  wearing the wrong label. *Forget this key* removes the ciphertext and asks for
  confirmation first. The UI shows the second only when this device is actually
  holding a key.
- **NIP-46 teardown is local, and says so.** Closing a bunker session rejects
  every pending request, closes the subscription and drops the pool. It does
  **not** revoke anything remotely: NIP-46 has no revoke a client can rely on,
  so the approval lives in the user's own signer app and is withdrawn there.
  Claiming otherwise would tell someone their access had been cut off when it
  had not.
- **No secret is ever sent anywhere.** There is no endpoint that could receive
  one; the site is static.

---

## 19. Conformance

A client is conformant if, for every fixture stream in
`shared/src/commonTest/resources/competition/streams/`, it produces the
recorded `state_hash`, and for every vector in `.../vectors/` it produces the
recorded output. The matrix of which client covers which requirement is
[`FEAT-058-conformance.md`](FEAT-058-conformance.md).
