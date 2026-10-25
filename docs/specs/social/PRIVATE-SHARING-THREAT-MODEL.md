---
status: design-review
owner: FEAT-062
protocol: com.cruxcoach.private-sharing/v1
marmot_pin: 4ad4ae21479c3f3fa9950c6fc4556a76941a62e1
mdk_pin: 101d79946cff6c82d2b849d3b70902a7af7bac08
created: 2026-08-11
revised: 2026-08-13
---

# Private Sharing Threat Model

> **SUPERSEDED (2026-08-16).** This annex describes the withdrawn bilateral
> one-group-per-relationship design. The shipped model — three visibility
> circles, five categories, group baselines plus person and object exceptions,
> device-bound consent and a recovery code — is specified in
> [FEAT-062](../0.2.3/FEAT-062-personal-information-sharing.md), which wins
> wherever the two disagree. Kept as evidence of what was investigated.

Adversary analysis for [FEAT-062](../0.2.3/FEAT-062-personal-information-sharing.md),
the [wire contract](PRIVATE-SHARING-WIRE-V1.md) and
[golden vectors](PRIVATE-SHARING-GOLDEN-VECTORS.md).

> **No-Go.** This annex is `design-review`. No product implementation or
> release is allowed until the isolated upstream-first adapter spike passes all
> eight gates in FEAT-062 §5 and an independent review confirms the evidence.
> The eight gates are all required: reproducible native build, dedicated exact
> group creation, exact outbound plus durable receipt, ACKed retention-independent
> inbox, unambiguous invalidation, hard chat/transport isolation, Android
> two-client recovery, and signer atomicity. No adapter and no product
> implementation of this feature exists in this repository. The checked-in
> specification harness under `docs/specs/social/scripts/` is a Python spec
> oracle over these documents; it runs no product, native or on-device code and
> discharges none of the eight gates.

## 1. Security claims and limits

Marmot/MLS via MDK provides authenticated group key agreement, sender-to-account
binding, admin-gated membership changes, branch convergence and explicit
invalidation. Forward secrecy and post-compromise security are **conditional**:

- they depend on erasure of obsolete epoch secrets and endpoint protection;
- retained-history material deliberately kept by MDK extends what a later
  compromise may expose within that retained window;
- application plaintext retained by either endpoint is outside MLS key erasure;
- compromise of a current endpoint or account signer defeats confidentiality or
  consent while that compromise remains effective;
- PCS begins only after an uncompromised update creates fresh secret material;
  it does not repair plaintext already copied.

No per-epoch guarantee is claimed in the absolute. Every statement about forward
secrecy or post-compromise security in this bundle is qualified by key erasure,
the retained-history window and endpoint-compromise assumptions.

CruxCoach provides relationship/grant semantics, canonical slot ids, field
minimisation, prospective detail boundaries, a durable ACK boundary and a
fail-closed reducer. Neither layer provides anonymity, metadata hiding,
retroactive deletion, recipient copy prevention, Sybil resistance or proof that
a training claim is true.

The central rule is: **Marmot membership grants nothing.** A valid bilateral
group may share no application data at all, and being in it is not consent.

## 2. Assets and trust boundaries

**Confidential assets:** raw logbook/ascent data; bids and attempts; notes;
health/body/sleep/pain/mood data; full training plans; local safety state; Nostr
account keys; MDK MLS/keychain stores; CruxCoach SQLCipher data; backup material.

**Integrity assets:** the `endpoint_low`-only creator rule; the durable
`attempt_id` to group binding; the immutable `0x8001` bootstrap marker; the
derived generation binding; the exact bilateral roster/admin/component profile;
the acceptance event that gates activation; relationship heads; grants and their
supersession; prospective boundaries; source-to-inner provenance; outbox leases;
the monotonic clock high-water mark; local safety tombstones; purge deadlines.

**Availability is not promised.** A peer, relay or fork can stall progress. The
safe response is to hold, hide or terminalise as specified, never to guess.

Trust boundaries:

1. relay bytes enter MDK;
2. MDK validates MLS, branch, inner structure/id and authenticated sender;
3. the CruxCoach adapter performs the exact kind/tag prefilter before parsing
   content;
4. the ACK inbox retains validated namespace data, independently of MDK message
   retention and pruning, until the CruxCoach database transaction commits;
5. closed application reducers materialise only explicitly authorised fields.

## 3. Actors

| Actor | Relevant capability |
|---|---|
| relay or traffic observer | drop, delay, replay, reorder and retain ciphertext; correlate IP, timing, size, routing handle, KeyPackages and inbox relay metadata |
| malicious peer/admin | fabricate self-authored values; withhold data or commits; attempt retention/component/profile/topology changes; disband the group; remove the other member |
| former peer | retain all prior plaintext and routing knowledge; stay offline during revoke |
| spam/Sybil account | create identities, imitate display names and trigger out-of-band requests |
| compromised endpoint | read local plaintext and both stores while accessible; act as current member |
| compromised account signer | author valid consent and membership actions indistinguishable from the owner |
| buggy adapter | lose source mapping or ACK state; lose the `attempt_id` binding and create a duplicate group; expose data to chat; mis-create the group profile; drop replay |
| buggy application | accept unknown fields, derive two slots, use arrival time, truncate data, keep a stale outbox lease, trust a rolled-back clock or revive retired grants |

Amber/NIP-55 is trusted only for the exact approval the user sees and for key
protection. NIP-46 is unsupported. There is no trusted CruxCoach server or
support-side reset.

## 4. Threats, controls and residual risk

| ID | Threat | Required controls | Residual risk / UI truth |
|---|---|---|---|
| PT-01 | prohibited personal data enters a payload | closed schemas, exact grant-token match, real-serializer privacy tests, no plan free text and no exercise rows at all, mandatory board/problem eligibility | serializer bugs remain possible; minimisation is the only application barrier inside the bilateral group |
| PT-02 | relay reads content | MLS application encryption owned by MDK | metadata remains visible; never promise metadata privacy |
| PT-03 | traffic links the pair | random signed routing id, ephemeral outer event keys, MDK-owned relays | timing, size, IP and long-lived routing association remain observable |
| PT-04 | public account metadata surprises the user | explicit informed consent before any account-authored KeyPackage (kind 30443) or kind-10050 inbox relay list publication; MDK owns both; partial failure claims no reachability | publication can mark the account as a Marmot user, is permanent, and cannot be recalled from relays that already hold it |
| PT-05 | wrong creator or forged first contact | only decoded-byte `endpoint_low` may create; the out-of-band action carries only an account identifier and local intent, never a signed message; wrong-creator Welcome never activates; key shown, not display name alone | first contact remains trust-on-first-use; accepting a key does not prove human identity |
| PT-06 | extra leaf/admin/component widens trust | send/apply blocked until a session-consistent exact roster, admin list and component profile including the byte-identical `0x8001` marker; any deviation terminal | protection depends on leaf-level adapter evidence, which is a spike gate |
| PT-07 | duplicate group splits state | discovering more than one nonterminal group for one endpoint pair terminalises all; no winner and no candidate ranking | malicious duplicate creation causes denial of service and requires a visible fresh attempt |
| PT-08 | crashed create silently makes a second group | dedicated `create_private_sharing_group(attempt_id, endpoint_high)` persists `attempt_id` to group id before the first external Welcome effect and returns the same group after a crash; the `0x8001` marker carries that `attempt_id` so the group is refindable from group state alone | the binding's crash-safety is a spike gate, not a property of the current pin; an abort is explicit and its `attempt_id` is never reused |
| PT-09 | forged or mutated bootstrap marker | marker is Welcome-authenticated GroupContext state with a fixed name and a closed JCS description; endpoints are recomputed locally; a mismatching marker is not a FEAT-062 group and any later change terminalises | both endpoints are admins and MDK lets an admin propose a profile update, so immutability is enforced fail-closed after the fact, not prevented |
| PT-10 | data flows before both sides consented | `PROVISIONING` blocks send and apply; only `cc.generation.accept.v1` from `endpoint_high` after an explicit local Welcome acceptance may flow; promotion follows the canonical acceptance; `ACTIVE` additionally needs exact profile, roster, admins and `Stable` | permanent failure requires user abort; availability is sacrificed deliberately |
| PT-11 | peer enables disappearance/pruning | exact profile forbids the retention component even with the value 0; any presence or change terminalises; unacknowledged inbox items are durable independently of MDK retention and pruning | previously ACKed CruxCoach state still depends on local database durability and backup policy |
| PT-12 | crash loses event between databases | durable adapter inbox, host ACK only after CruxCoach commit, idempotency by source and inner ids; the whole `engine commit -> pending ingress -> inbox revision -> CruxCoach transaction -> host ACK` rail is tested over more than five epochs | there is no cross-store transaction; correctness depends on the ACK protocol passing the spike |
| PT-13 | reorder changes authorisation | deterministic slot ids, gapless sequence/predecessor, held cross-stream dependencies, exact offer-head references | a peer can withhold a predecessor and stall only that object |
| PT-14 | equal-sequence equivocation | two different valid events in one slot/sequence terminalise the entire generation; no winner and no fresh snapshot can heal the conflicting prefix | a malicious peer can force reconnect, but cannot make receivers choose different grants |
| PT-15 | invalidation widens prior state | any invalidation of a stored kind-1220 event terminalises and hides the whole generation; there is no partial recomputation; local safety reductions remain sticky | users can see data disappear and must reconnect; no partial recovery is claimed |
| PT-16 | malformed content fills storage | wrong kind/tags drop before content parse and store nothing; matching-namespace failures store only SHA-256 digest metadata, capped at 100 records/generation and 30 days, dropping at the cap | bounded counters can still reveal that malformed traffic occurred |
| PT-17 | backdating crosses FRIEND boundary | detail grant binds both exact offer heads; boundary is max of grant issue and both head issue times; intervening downgrades retire the grant permanently | sender times are self-attested for audit, but cannot lower this computed boundary |
| PT-18 | old detail revives after upgrade | downgrade permanently retires detail; every later set uses a fresh grant id/boundary; narrowing purges dropped fields immediately and history is audit only, never authorisation | recipient may retain an earlier plaintext copy outside protocol control |
| PT-19 | rolled-back, frozen or rebooted clock keeps access alive | a persisted `boot_id`, monotonic anchor and wall high-water mark; a boot change, monotonic reset, wall rollback or frozen wall clock enters fail-closed `TIME_UNTRUSTED`, which hides every time-bounded grant, blocks permissive send/apply and makes unprovable purges immediately due; the only exit is an explicit trusted re-initialisation | a high-water mark alone was insufficient: a clock parked before a deadline would have extended the grant forever. `TIME_UNTRUSTED` costs availability on purpose, and restrictive actions are never blocked by it |
| PT-20 | stale outbox lease double-sends or drops a revoke | `lease_owner`/`lease_boot_id`/`lease_until`; a lease from another boot is expired without comparing its monotonic deadline; the phase is committed **write-ahead** of the non-atomic transport call, so a proven commit of `EXTERNAL_EFFECT_UNCLEAR` is the only thing that authorises a handover and a durably observed `PRE_EXTERNAL_EFFECT` proves the send was never authorised to start; authorisation is permission to call and never a call result, so a refusal after authorisation keeps the row unclear rather than reverting it to pre-effect or retrying it, and no synchronous durable no-external-effect proof is assumed; reclaiming an expired lease never requeues by itself — the persisted `external_effect_phase` decides, so only a provably pre-external-effect row returns to `QUEUED` — recovery itself sends nothing, and a later drain attempt re-drives that same inner event id through the write-ahead gate — a durably evidenced row is resolved from its typed receipt, and anything unclear becomes a visible `DELIVERY_UNCLEAR` row that is never automatically sendable and never pruned by age; exhausted rows stay visibly `FAILED` under a **Not sent — retries exhausted** label and are never pruned by age, permissive rows never supersede restrictive ones | a stalled worker delays a queued revoke and an unclear first delivery needs an explicit user decision rather than a silent re-send; the restrictive local effect already applied without waiting for the network |
| PT-21 | oversize object leaks a selected subset | hard 65,536-byte content cap, complete snapshots only, 100-session/send limits, explicit failure | availability suffers for large selections; UI requires narrower consent |
| PT-22 | plan becomes covert text channel | only four plan grant tokens; planned sessions have four closed scalar fields; no exercise rows, names, notes or other free text are representable | closed enum combinations still reveal training intent, which is explicitly consented |
| PT-23 | ambiguous identity mapping fabricates data | closed board/problem identity with mandatory hold fingerprint; no `catalogue_revision` dimension exists; unset or cross-brand product size stays `NULL` and makes the item ineligible | ineligible ascents are silently absent from aggregates, which the UI must state |
| PT-24 | self-attested values look verified | label all values as shared by the peer | sender authentication proves account authorship, not performance truth |
| PT-25 | chat/notification leaks data | dedicated kind and group isolation; negative tests for chat list/timeline/unread/Markdown/NewMessage on real on-device surfaces; sensitive previews off | OS history and screenshots may retain an allowed application-specific preview |
| PT-26 | misleading publication status | a total, closed map from the seven outbox states to Queued locally, Published to at least one configured relay, Not sent — retries exhausted, Delivery unclear — not retried automatically and no-label-for-`SUPERSEDED`, all visible labels also Not peer-confirmed; the published label requires a persisted typed receipt naming a concrete accepted relay endpoint, never a report count; the unclear label claims neither delivery nor non-delivery and offers no automatic re-send; offline recipients explained in words | no peer receipt or read truth exists in v1, and MDK's own send summary is not evidence for any of these claims |
| PT-27 | hostile or accidental disband | disband is an authenticated admin action either endpoint may take; the commit itself removes every candidate-parent leaf but the committer's and reduces the admin policy to the committer, which is why no separate MDK removal is needed; the lifecycle state is absorbing, so there is no later traffic to observe; terminal, hides and purges within 24 hours, tombstones sticky | a peer can end the relationship unilaterally at any time; this is authentic authorisation, not a repairable fault |
| PT-28 | restore silently resumes access | backup contains no MLS state; restored app data read-only/inactive; outbox sends nothing; row ids are preserve-or-`NULL` and never minted; fresh explicit group/generation required | every relationship must be rebuilt on a new device |
| PT-29 | database constraints silently absent | `foreign_keys = ON` verified on the production connection before any migration or query, with startup failure otherwise | a test-only activation would leave production unprotected, which is why it is explicitly insufficient |
| PT-30 | signer denial leaves half state | local and Amber approve/deny/cancel/timeout atomicity gate | compromised signer can author valid actions; replace signer and rebuild generations |
| PT-31 | second relay/cursor diverges | MDK owns all social relays/cursors; spy proves zero `NostrRelayPool` social calls and no second cursor | future refactors must keep the standing negative test |
| PT-32 | collision reinterprets kind 1220 | machine-parse the pinned registry before design-lock, every repin and every release; a collision blocks and requires a new kind and protocol version | 1220 is free at the pin, not globally reserved |
| PT-33 | branch-selection rollback of a membership or profile commit | `GroupStateInvalidated` plus `ForkRecovered`/`CommitRolledBack` hold the generation and force one fresh session-consistent read; a rolled-back promotion, admin-policy, `0x8001` or `0x800c` commit after activation terminalises; tombstones stay sticky | the peer can force a reconnect by losing a fork; availability is sacrificed rather than authority. Whether the signals can be surfaced restart-durably at all is a spike gate |
| PT-34 | grant-id collision claims another publisher's projection slot | the projection slot preimage binds the publisher, so the slots differ; `grant_id` is additionally unique generation-wide and a reused id from the other publisher terminalises with `GRANT_ID_COLLISION`; `projection.author == grant.publisher` and `revoke.author == body.publisher == the named grant's publisher` | a malicious peer can force a terminal generation, which is denial of service, not a widened grant |
| PT-35 | unbounded held or accepted state fills storage | per-slot, per-generation and global count/byte bounds on `HELD`, a bound on `PLAN_DETAIL` slots per publisher, and a resulting-state check before any delta commits; overflow terminalises the offending generation before the insert | no eviction and no age-based pruning exists, so a peer can end one generation by flooding it but can never silently drop a revoke, a tombstone or another generation's data |
| PT-36 | prospective boundary is not provable from the wire | the producer sends a session only when `floor900(start) >= prospective_from`; the receiver requires `started_at_utc mod 900 == 0` and `>= prospective_from` | up to 899 s of legitimate data immediately after a boundary are deliberately unsendable; the alternative would put a pre-boundary value on the wire |
| PT-37 | retention silently removes state the app treats as durable | the MDK raw app store is never an authority; CruxCoach keeps its own durable projection and acknowledged inbox; an item lost before host ACK terminalises with `PROJECTION_AUTHORITY_INVALID` instead of being reconstructed | R5 proved a real retention sweep deletes expired raw rows, and gate `R6-G1-DURABLE-PROJECTION` shows the durable projection does not exist at the pin. Availability is sacrificed rather than correctness |
| PT-38 | a "retry" mints a second transport identity for one inner event | a receipt binds at most one distinct source message id; a retry is the identical transport message; a re-encrypted or re-queued send is a new event and is terminal with `RETRY_NOT_TRANSPORT_IDENTICAL` if presented as a retry | R6 proved the pin *can* re-drive the identical transport message, so the risk is no longer that retry is impossible. It is that the resume path which performs it emits no `PublishedApplicationMessage` (R6 finding 1), leaving the delivery uncorrelatable — `ACCEPTED_WITHOUT_SOURCE_RECEIPT`, permanently unclear. Gate `R6-G2-TRANSPORT-IDENTICAL-RETRY` stays open, and a safety-relevant send may still stay visibly unsent |
| PT-39 | a lagged live subscription is mistaken for a complete stream | correctness rests on the durable snapshot/replay path; a live gap holds with `HOLD_REPLAY_INCOMPLETE`; a gap retention already pruned is terminal | the broadcast really does drop under load — R6 forced a real overflow and lost 2152 events — and recovered the custom event from the store exactly once, but only through a crate-internal test against a private sender (gate `R6-G3-BROADCAST-LAG-REPLAY`). No adapter-reachable surface drives that recovery, so a peer or a burst can still stall a generation |
| PT-40 | an exactly-once claim is stretched from local reduction to the network | the only permitted claim is idempotent local reduction keyed by typed ids; live emission, relay delivery, network delivery, raw row counts and peer receipts are explicitly forbidden claims | R6 held live emission at exactly one and counted exactly one raw row per side under deliberate redelivery pressure — inside one host process against a MockRelay (gate `R6-G4-EXACTLY-ONCE-SCOPE`). That bounds nothing about relays or the network. Duplicate *transport* remains possible and is absorbed by the reducer, never denied |

## 5. Leakage matrix

`Meta` means transport/shape metadata, not plaintext content protection against
an endpoint compromise.

| Data | Relay/crawler | Acquaintance | Friend | Public |
|---|---|---|---|---|
| raw rows, attempts, notes, health/body data | No | No | No | No |
| credentials, keys, backups, MLS secrets | No | No | No | No |
| location, gym, wall, GPS | No | No | No | No |
| climb/setter names and hold sequence | No | No | No | No |
| local follow, mute and block | No | No | No | No |
| relationship/application traffic | Meta | own pair | own pair | No |
| bootstrap marker: fixed name, `attempt_id`, both endpoints | No | own pair | own pair | No |
| explicitly granted all-time summary, including pre-connection history | Meta | Yes | Yes | No |
| prospective logbook detail | Meta | No | only if explicitly granted | No |
| one selected prospective plan | Meta | No | only if explicitly granted | No |
| routing handle, timing, size and IP | Yes, metadata | not an application field | not an application field | No |
| account-authored KeyPackage | Yes | observable | observable | Yes |
| published kind-10050 inbox relay list | Yes | observable | observable | Yes |
| existence/grants of another peer | No | No | No | No |

The all-time summary is the sole historical exception: the explicitly consented
aggregates may include eligible ascents from before the connection existed, and
consent copy must say so. They never expose a contributing row.

The bootstrap marker is GroupContext state inside the encrypted group. It is not
public, but anyone who receives the Welcome — including someone invited by
mistake — learns the fixed product name, the `attempt_id` and both endpoints
before accepting or refusing.

## 6. Verification obligations

The eight spike gates are non-waivable and must be independently reviewed. Four
carry a deep sub-condition that the pin does not satisfy today and that may not
be assumed away:

1. crash-safe correlation of `inner_event_id` to every `source_message_id`,
   including a crash after external publication but before host persistence;
2. a restart-durable invalidation reason, or an explicit FAIL for that gate;
3. defined offline/queued receipt behaviour — refuse queueing, or persist
   `Queued { intent_id, inner_event_id }` and bind its one source id later;
4. a proven cross-store crash rail, with the protocol-level ACK never
   reinterpreted as the host ACK.

If any of them can only be closed by changing the engine, session or account
crates instead of by additive adapter/storage/FFI work, that is a
scope-expanding No-Go requiring a fresh independent review decision.

The executed R5 and R6 adapter spikes additionally left eight named
MDK-capability gates open. They are normative in
[wire §11.1](PRIVATE-SHARING-WIRE-V1.md#111-open-mdk-capability-gates-r6) and
each carries a fail-closed rule that applies while it stays open. R6 discharged
none of them, but it did answer several of the underlying questions, so the
obligation that remains is stated as what is *still* outstanding:

| Gate | Outstanding verification obligation after R6 |
|---|---|
| `R6-G1-DURABLE-PROJECTION` | unchanged: show a retention-independent durable projection and inbox surviving a real retention sweep, a restart and a pre-ACK crash. R6 decided nothing here |
| `R6-G2-TRANSPORT-IDENTICAL-RETRY` | the identical re-drive is shown; what remains is the correlation — show a resumed first delivery that still binds one inner id to one source id, i.e. close R6 finding 1's missing `PublishedApplicationMessage`, and show it on a native surface |
| `R6-G3-BROADCAST-LAG-REPLAY` | the overflow and recovery are shown crate-internally; what remains is an adapter-reachable surface that drives the same recovery without a private test hook |
| `R6-G4-EXACTLY-ONCE-SCOPE` | live emission and raw row count are shown under redelivery pressure in one host process; what remains is the same on a native surface, and a resumed first delivery that produces a receipt to count at all — wire §11.2 forbids relay and network exactly-once regardless, so no obligation here can ever widen the claim |
| `R6-G5-CUSTOM-REORG` | the app half is shown; what remains is the trigger — deterministically partition two members into competing commits of one epoch, place the custom payload on the losing branch, and observe the invalidation without injecting a `GroupEvent` |
| `R6-G6-UNIFFI-FORWARDING` | forwarding and reserved-kind refusal are shown; what remains is the same over an exported constructor rather than the crate-internal loopback-gated one |
| `R6-G7-INBOUND-NATIVE-DELIVERY` | the negative proof is shown on the shared host stream; what remains is the native callback itself on a device |
| `R6-G8-INSTRUMENTATION-RUN` | unchanged: run the instrumentation suite on a real Android arm64 device, not merely package it |

R6 produced real runtime evidence, and all of it is host-scope: it ran no
instrumentation, so no row above is discharged by native, on-device or release
evidence. The checked-in Python harness is the S9 specification stage and is not
a partial substitute for any row either.

Product conformance, if ever authorised, additionally covers:

- deterministic `endpoint_low` creator, idempotent `attempt_id` create, exact
  bootstrap marker, generation derivation and all semantic slot UUIDs;
- wrong creator, duplicate group, duplicate leaf, third leaf, wrong admin,
  every forbidden component and any retention component including a signed zero;
- `PROVISIONING` with zero send/apply apart from one valid acceptance event,
  promotion only after canonical acceptance, and temporary MDK convergence holds
  that are not mislabelled terminal;
- reorder, duplicate, wrong predecessor, gap then missing-chain recovery and
  cross-stream held dependencies;
- equal-sequence conflict, every stored-event invalidation, MDK quarantine,
  `Unrecoverable` and disband as terminal generation outcomes;
- downgrade retirement, revoke, expiry, supersession, per-grant/per-field purge
  deadlines and the monotonic clock high-water mark;
- outbox lease ownership, every external-effect phase of an expired-lease
  recovery — the permitted pre-external-effect requeue, the receipt-resolved
  row and the blocked, visible `DELIVERY_UNCLEAR` row — without a second event
  id or a second source id anywhere, visible exhausted rows and forbidden
  supersession pairs;
- the write-ahead order in front of the non-atomic transport handover: an
  unattempted, failed or unproven phase commit authorises no send, and every
  crash window of one send attempt — including the one between the commit and
  the handover — is asserted to leave a row that recovery treats fail-closed;
- the separation of authorisation from call result: a refusal at the gate is no
  send at all, and what may follow depends on the phase actually on disk — a
  never-attempted or provably failed commit leaves a pre-effect row that may be
  attempted again from the beginning, while an unproven commit may already have
  left an unclear one, which fails closed like any other; a refusal, error or
  missing answer *after* authorisation leaves the row unclear and visible with
  no automatic retry, no requeue and no revert to pre-effect;
- summary historical exception, the 900-second prospective-detail boundary on
  both the producer and the receiver side, oversize failure and the complete
  no-free-text negative corpus. That corpus is checked in under
  `docs/specs/social/fixtures/` with concrete bytes or concrete observed state
  per case and one of five closed dispositions each, and the checked-in spec
  oracle executes it. Product conformance means the **real** serializers,
  reducer and adapter reproduce those same fixture files; the oracle's own green
  run is not evidence for that and never substitutes for it;
- restore with inactive/read-only data, preserve-or-`NULL` row ids and zero
  outbox send;
- `foreign_keys = ON` verified at startup on the production connection;
- explicit consent before any kind-30443 or kind-10050 publication;
- zero chat exposure, zero `NostrRelayPool` social use and zero second cursor,
  proven on real native on-device surfaces rather than fakes or stubs.

Synthetic data only is used in red-team fixtures.

## 7. Abuse response and safety ordering

Local safety actions never wait for the network. Restrictive effects apply
locally at once; permissive effects wait for canonical self-delivery.

- **Block:** hide everything, suppress notifications, terminalise the
  generation and make all grants unusable; then attempt best-effort MDK removal.
  Unblock restores nothing.
- **Disband:** the preferred terminal action for a normal disconnect of a
  bilateral group, because the `0x800c` lifecycle state is absorbing: after the
  disband commit is selected there is no later group traffic, no later branch
  selection and no rejoin, so nothing remains to observe. The disband commit is
  itself a removal — it removes every candidate-parent leaf except the
  committer's and reduces the admin policy to exactly the committer — which is
  why no separate MDK removal follows it. It is authenticated and terminal for
  both sides.
- **Remove:** generation-level local terminal action with later best-effort MDK
  removal; kept for recovery and loser cleanup with a stated reason, not as the
  default disconnect.
- **Downgrade:** publish a lower offer, permanently retire detail and purge it
  within 24 hours; keep the group and any still-authorised summary. It does not
  attempt member removal and does not terminalise.
- **Mute:** local presentation suppression only; no group or grant mutation.
- **Revoke:** hide the named grant immediately, queue its same-slot revoke and
  purge within 24 hours.

Suspected endpoint compromise terminalises every affected generation locally.
Suspected signer compromise requires securing/replacing the signer first and
then explicit fresh generations. Neither response claims to erase a peer copy.

## 8. Claims that are forbidden

1. Marmot, MLS or MDK hides metadata or makes sharing anonymous.
2. Revoke, downgrade, removal, disband or block deletes plaintext already held
   by a peer.
3. A signature or MLS sender proves a climb, grade or plan is true.
4. A KeyPackage or inbox relay list is private, or can be unpublished.
5. Group membership, an accepted Welcome or `FRIEND` alone grants access.
6. Queued or relay-published means peer-delivered or read.
7. A backup restores an MLS group or reconnects a relationship.
8. Forward secrecy or post-compromise security survives retained keys, retained
   plaintext or a currently compromised endpoint without qualification.
9. Any adapter surface, harness or product code required by this design already
   exists at the pins.
10. Exactly-once holds anywhere beyond CruxCoach's own idempotent reduction —
    for live emission, relay delivery, network delivery, raw row counts or a
    peer receipt.
11. The MDK raw application store is durable CruxCoach state, or a live
    subscription is a complete stream.
12. A re-encrypted or re-queued send is a retry of the same inner event.
13. The S9 Python harness is R6, native or on-device evidence, or that a green
    harness run closes any gate.
14. A transport acceptance is a delivery even when nothing correlates it to a
    source message id, or that R6's host-scope runtime evidence is native,
    on-device or release evidence.
