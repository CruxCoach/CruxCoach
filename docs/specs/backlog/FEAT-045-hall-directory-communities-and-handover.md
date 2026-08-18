---
status: backlog
---
# Feature Spec: Hall Directory, Communities, and Operator Handover (backlog - vision-tier)

> **Status:** Backlog - product direction captured 2026-07-15. Anyone-can-add,
> search-database seeding, and future operator handover are decided; the
> detailed domain/event protocol remains a recommendation and is not
> implementation-ready.
>
> **Hard constraints:** Nostr is the backend; no CruxCoach-owned application
> server is authoritative. Anyone may add a public climbing hall or start its
> community. A hall must not require a dedicated Nostr private key. Private
> home-board locations must never be promoted into the public directory.
>
> **Depends on:**
> - FEAT-015 (Board Locations Map) and its existing location data.
> - Quartz 1.08.0 with NIP-29 support (or a later release after the required
>   Android/Kotlin toolchain upgrade).
>
> **Relates to:**
> - FEAT-043 (Competitions and Leaderboards), which references canonical
>   `hall_id` values and owns leaderboard consent.
> - FEAT-025 (Community Send Count), which must not infer hall visits.

---

## 1. Product decision

A climbing hall is a physical entity, not a Nostr account and not a relay
group. The implementation separates:

| Entity | Purpose | Identity/authority |
|---|---|---|
| `Hall` | Stable physical venue record | Ownerless opaque `hall_id` |
| `BoardInstallation` | One provider board or wall at a hall | Provider source aliases linked to `hall_id` |
| `HallCommunityBinding` | Public social room attached to a hall | NIP-29 `(relay, group_id)` endpoint |
| `HallAuthority` | Who may publish official metadata/news | Signed, scoped roles held by normal Nostr profiles |

The creator of a hall or group is a provisional editor/community steward, not
the owner of the physical venue. The same hall ID survives metadata changes,
relay migration, community replacement, and a future operator claim.

There is deliberately no generated hall `nsec`. A later operator uses its
existing organisation or staff profile and receives roles. Handover is a
signed role transition, not private-key transfer.

## 2. Feature ownership

This spec owns:

- the canonical hall and installation data model;
- the initial directory seed;
- user-created public hall records;
- public NIP-29 community bindings;
- provisional stewardship, operator claims, and official publisher roles;
- duplicate, merge, closure, and relay-migration semantics;
- official hall news transport.

It does not own:

- private friend/acquaintance sharing;
- hall leaderboard scoring or opt-in;
- board catalogue data and BLE control;
- private teams inside a public hall community;
- proof that a user physically visited a hall.

## 3. Stable identity

### 3.1 Canonical hall ID

`hall_id` is an opaque 128-bit identifier. It is not derived from:

- name, slug, address, or coordinates;
- creator or operator pubkey;
- a NIP-29 relay or group ID;
- a Kilter/Aurora/MoonBoard provider ID.

User-created halls receive a locally generated random UUID. Seed IDs are
assigned once and persisted in a versioned source-alias registry. Rebuilding
the seed must reuse that registry; it must never regenerate IDs from mutable
fields.

### 3.2 Provider aliases

Every imported installation retains its source identity, at minimum:

```text
(source namespace, board_brand, gym_uuid)
(source namespace, wall_uuid) when available
```

Several aliases may point to one hall. An alias may never silently move to
another hall during a normal refresh. Such a correction requires an explicit
merge/correction record so old clients and leaderboard cards can resolve it.

### 3.3 Hall lifecycle

The minimum lifecycle is:

```text
provisional -> community-run -> operator-verified -> closed/merged
```

`closed` preserves history and source aliases. `merged` points to exactly one
surviving hall ID. IDs are never deleted and reused.

## 4. Initial population from hall search

The legacy `kilter_board_location` name is misleading: the current location
chunk contains installations from Kilter, MoonBoard, Tension, Grasshopper,
Decoy, So iLL, Touchstone, Aurora, and 12Climb.

A live source build on 2026-07-15 produced:

| Access classification | Installation rows | Seed policy |
|---|---:|---|
| `PUBLIC` | 1,271 | Eligible for automatic hall seed |
| `MEMBERS` | 21 | Eligible, retaining access semantics |
| `PRIVATE` | 744 | Excluded completely |
| `UNKNOWN` | 1,003 | No automatic public hall; keep only in board search |
| **Total** | **3,039** | Installation rows, not canonical hall count |

Unknown records may represent real commercial gyms, but publishing an
uncertain home-board location is the worse failure. A user may later add such
a venue explicitly after confirming that it is public or membership-based.

### 4.1 Deduplication rules

Existing coordinate grouping is suitable for map pins, not canonical
identity. The live data contains unrelated halls sharing the same coarse city
coordinates. Therefore coordinate equality alone must never merge halls.

Automatic merge is allowed only with high-confidence evidence such as:

- the same trusted website host plus compatible name/location;
- the same normalized street address plus compatible name;
- a strong normalized-name match at close distance, backed by another shared
  contact/source field.

Ambiguous records remain separate. False duplicates are less damaging than a
false merge because a later signed merge can preserve both source histories.

The seed builder must emit a review summary and regression fixtures for every
automatic merge rule. A refresh fails closed if it would reassign an existing
source alias or unexpectedly remove a large portion of the public seed.

## 5. MVP user flows

### 5.1 Existing seed hall

1. The user searches the local hall index.
2. A seed hall opens as a directory page even when no social room exists.
3. `Start community` searches Nostr for an existing binding first.
4. If none is found, the user selects a compatible NIP-29 relay and creates a
   public group.
5. The app publishes a binding referencing the stable `hall_id`.
6. The creator becomes community steward for that group only.

### 5.2 Missing hall

1. `Add hall` first runs local and Nostr duplicate search around the entered
   name/location.
2. The user confirms that the venue is public or membership-based and is not a
   private home board.
3. Required fields are name, coordinates, country, and access class. Address
   and official website are optional but strongly encouraged.
4. The app previews exactly which location data and signer identity become
   public.
5. It publishes a provisional hall descriptor with a new `hall_id`.
6. Starting a community is offered next but is not required for the directory
   record to exist.

The creator may correct the provisional descriptor. Their edits remain
labelled community-supplied until stronger provenance or an operator claim
exists.

### 5.3 Community defaults

Hall communities use the public NIP-29 profile already selected by the social
architecture:

- public and discoverable;
- readable without joining;
- join requests allowed without an invitation;
- writing restricted to members;
- forum posts/comments before real-time chat;
- follow, join, and leaderboard opt-in remain independent actions.

Nostr cannot guarantee one globally unique unverified group without a trusted
authority. If several valid bindings exist, no client may call one `official`
merely because it was first observed. The app shows community-run status and
stores the user's preferred binding locally. A verified operator may later
designate an official endpoint.

## 6. Nostr object model

The protocol needs four logically separate signed objects. Exact custom kinds,
tags, replacement rules, and authority-chain validation remain subject to a
dedicated interoperability/security review.

### 6.1 Hall descriptor

Minimum public payload:

```text
schema_version
hall_id
display name
coordinates and country
optional address, website, contact and image
access class
lifecycle state
source aliases with provenance
authority generation/reference
```

The signer is not part of `hall_id`. Clients accept an update only when the
signer has valid authority for that hall and generation.

### 6.2 Community binding

The binding contains:

```text
hall_id
NIP-29 relay URL
NIP-29 group ID
binding state (candidate, preferred, retired)
creating steward pubkey
replacement/migration reference
```

Important hall objects never exist only inside the relay-hosted group.

### 6.3 Authority grant/claim

Roles are independent and least-privilege:

| Role | Capability |
|---|---|
| `provisional_editor` | Edit a community-created provisional descriptor |
| `directory_editor` | Publish verified canonical metadata |
| `official_publisher` | Publish official news/events for the hall |
| `community_steward` | Moderate one named NIP-29 binding |

Role grants are scoped to `hall_id`, identify a generation, and are revocable.
A cooperative handover is two-step: the current authority grants and the new
profile accepts. Old grants cannot be replayed into a later generation.

### 6.4 Official announcements

Operator news is a portable structured event on normal public relays. It is
signed by a profile with a valid `official_publisher` role and references
`hall_id`. A NIP-29 post may point to the announcement, but the group is not
its sole storage or authority.

Community posts remain visibly separate from official announcements.

## 7. Future operator verification and handover

Operator management is not in the first implementation phase, but the data
model must not block it.

The preferred serverless proof is NIP-05 on the hall's already trusted
official website domain. A claimant-controlled website field added in the same
claim is not evidence. The domain must come from a seed source, a prior trusted
descriptor, or another independently established provenance path.

The operator signs the claim with its normal organisation/staff profile. On a
valid proof it may receive `directory_editor` and `official_publisher` without
taking ownership of user discussions.

Two cases must work:

- **Cooperative:** current stewards also grant the operator stewardship of the
  existing NIP-29 room.
- **Abandoned or squatted room:** the verified operator can publish official
  metadata/news and designate another official room, but cannot retroactively
  seize or rewrite the old relay's community content.

An operator may delegate roles to several staff pubkeys and revoke them. No
shared hall private key is required. DNS, QR, or manual fallback verification
is deferred until its trust and recovery model is specified.

## 8. Privacy and consent

- Directory data is public. The creation preview must say so explicitly.
- Private and unknown home-board locations are never automatically seeded.
- Following a hall is local or encrypted and does not imply public membership.
- NIP-29 joining may expose membership to the group relay and other clients.
- Creating, editing, posting, or claiming publicly links the signing profile
  to the hall; passive viewing/following does not.
- Hall leaderboard participation is a separate FEAT-043 opt-in and never
  follows from a directory record, group join, detected `gym_uuid`, or visit.
- Descriptors and announcements contain no training log, visit time,
  frequency, or private social relationship.

## 9. Abuse and conflict handling

- Creation always performs duplicate search but cannot forbid valid competing
  Nostr events globally.
- Unverified creator fields carry community provenance, never an official
  badge.
- Report, block, and local hide apply independently to a hall descriptor and a
  group binding.
- Merge records preserve redirects and source aliases; they do not erase old
  public events.
- A relay migration retires a binding, not the hall.
- An operator claim does not grant control over historical user content.
- Clients reject authority cycles, stale generations, self-claims based only
  on claimant-supplied domains, and source aliases assigned to two live halls.

## 10. Delivery stages

### H1: Directory foundation

- add `Hall`, `BoardInstallation`, and source-alias persistence;
- generate a filtered, stable-ID seed from the location database;
- union seed entries with validated Nostr hall descriptors;
- implement search, detail, duplicate detection, and public hall creation;
- exclude private and unknown locations from automatic publication;
- no operator workflow and no dedicated hall account.

### H2: Public communities

- add the narrow `PublicCommunityTransport` abstraction;
- create/discover NIP-29 bindings with Quartz;
- implement follow, join, leave, forum, mute, report, and relay migration;
- keep portable hall objects outside the group relay.

### H3: Verified operators

- lock the authority event protocol and generation rules;
- implement NIP-05 domain proof against trusted descriptor provenance;
- add scoped staff roles, revocation, official news, and endpoint designation;
- specify fallback proof and dispute handling before enabling it.

## 11. MVP acceptance criteria

- Re-running the seed with unchanged aliases preserves every `hall_id`.
- No `PRIVATE` or `UNKNOWN` location is automatically published as a hall.
- Coordinate-only collisions do not merge unrelated venues.
- One hall may contain several provider installations and board types.
- Any signed-in user can add a missing public hall after duplicate/privacy
  confirmation.
- A hall exists independently of a NIP-29 group and of its creator's account.
- Creating a hall does not create or back up a hall-specific private key.
- Closing, merging, or migrating never reuses a hall ID.
- Hall creation/join never enables a public leaderboard.
- Existing NIP-17, NIP-55, signer, backup, and community-climb paths continue
  to pass after the Quartz update.

## 12. Open protocol decisions

1. Exact event kinds, tags, replacement semantics, and authority-chain format.
2. Which relay set is queried for global hall/binding discovery.
3. Trust ordering and UX when several unverified bindings exist.
4. Evidence required to promote an `UNKNOWN` source record safely.
5. Fallback verification for halls without an NIP-05-capable website.
6. Who may authoritatively merge two unverified community-created halls.
7. Closure, rename, relocation, and chain-franchise edge cases.
8. Retention and display policy for retired community endpoints.
9. Whether a verified operator may moderate the main community only after an
   explicit steward grant or may always designate a separate official room.
