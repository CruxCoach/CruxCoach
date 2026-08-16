# Offline BoardCell Mesh over FIPS

Status: **Variant A is the selected MVP architecture.** Variant B is a possible
future alternative that has not been approved, evaluated, or implemented.

This document explains the model shared by Nearby Climb, playlists/sessions,
CruxRelay, and local competitions. The concise normative specification is
[`FEAT-059-offline-board-cell-fips-mesh.md`](FEAT-059-offline-board-cell-fips-mesh.md).
The hardware acceptance procedure is
[`docs/FIPS_DEVICE_TEST_PROTOCOL.md`](../../FIPS_DEVICE_TEST_PROTOCOL.md).
The separate iOS architecture note is
[`docs/IOS_BOARD_ACCESS.md`](../../IOS_BOARD_ACCESS.md).

## 1. Objective

All CruxCoach devices in front of **the same physical board** should know the
same complete state without internet access. After a temporary disconnect they
must converge on that state again, without publishing anything to Nostr and
without mixing in state from an adjacent board.

Every joined participant must be able to determine:

- which climb was last confirmed as sent to the physical board;
- the complete playlist and its current item;
- which device is allowed to control the board;
- which participants belong to the local session;
- the current state of a local competition.

“Nearby” therefore means more than receiving a short BLE advertisement. Once a
device has joined, the complete, validated BoardCell state is authoritative.

## 2. The four identities

Four identifiers deliberately serve different purposes:

| Identifier | Meaning | Lifetime |
| --- | --- | --- |
| `PhysicalBoardId` | Stable identity of one physical board | Long-lived |
| `BoardCellId` | State scope for exactly that board | Deterministically derived from `PhysicalBoardId` |
| `realmId` | Transport and connectivity boundary of the local FIPS mesh | Active BoardCell or competition |
| FIPS `npub` | Cryptographic transport identity of one CruxCoach device | Stable inside the active realm; rotated when the realm ends or changes |

The normal CruxCoach/Nostr account `npub` is **not** used as the FIPS identity.
A local radio observer therefore cannot directly associate the transport node
with the user's public CruxCoach account. Participants in a realm do not share
one FIPS identity either: every device has its own FIPS key and `npub`.

The FIPS secret is held in encrypted app storage. It does not rotate on every
BLE reconnect, because the device would otherwise return as a new member after
a brief radio interruption. It remains stable across reconnects, Bluetooth
restarts, and process restarts while the same realm is active. Ending or
switching the realm discards it and generates a new identity. A competition
also has a separate opaque local participant credential to preserve the
participant stream within that competition.

## 3. What a realm is

A realm is the boundary inside which CruxCoach may establish FIPS links and
forward packets. It is not a server, a global registry, or the board state
itself.

For an ordinary board session:

```text
PhysicalBoardId -> BoardCellId -> realmId
                                  (realmId == BoardCellId)
```

Devices therefore use the same realm only after selecting the same BoardCell.
An adjacent board has a different `PhysicalBoardId`, which produces a different
`BoardCellId` and a different realm.

For a local competition, `realmId == competitionId`; the current `boardCellId`
remains part of the transport context and every competition message. In the
current MVP, both the realm tag and the cell tag must match. The transport is
therefore still limited to the BoardCell that the participant entered. The wire
format can identify distinct physical boards, but intentional cross-board
peering is not enabled and requires a separate product and security decision.

## 4. Variant A: isolated FIPS islands

Variant A uses FIPS for authentication, encryption, and multi-hop routing, but
does **not** join a shared global FIPS network. Every active BoardCell is its own
local FIPS island:

```text
Board A / Realm A                 Board B / Realm B

 Phone 1 ------ Phone 2           Phone 4 ------ Phone 5
      \          /                     \          /
        Phone 3                         Phone 6

                 no FIPS edge between A and B
```

The phones are part of FIPS in the sense that they run the protocol and
runtime. They are not automatically members of a globally connected FIPS
component. Global reachability is not an inherent property of a FIPS `npub`; it
only arises from configured discovery and transport paths.

The MVP starts only the embedded BLE transport. OS-facing TUN, DNS, and control
socket services are disabled. CruxCoach uses only FIPS's internal app-owned TUN
and identity seams to inject and receive bounded IPv6/UDP datagrams. Nostr
discovery, public relays, STUN, LAN/internet peering, and an Android VPN are not
used. No separate FIPS daemon or second app is required: the Rust runtime is
embedded in CruxCoach through JNI.

## 5. Selecting the correct cell

### 5.1 Identify the physical board

CruxCoach resolves `PhysicalBoardId` in this order:

1. a stable board serial number, if exposed by the protocol;
2. an explicitly persisted QR/manual binding;
3. the observed BLE address as a fallback.

Name, model, and RSSI are never identifiers. RSSI is only momentary signal
strength, and two boards may have the same name. A board that rotates its BLE
address and exposes no serial therefore requires an explicit durable binding.

### 5.2 Derive the Cell ID

`BoardCellId` is deterministically derived from `PhysicalBoardId`. Two devices
that identify the same board in the same way consequently derive the same Cell
ID without a server.

During initial bootstrap, possible controllers publish claims only to already
authenticated **direct** BLE FIPS peers. A two-second settling window and a
deterministic rank select the controller and epoch. Claims are not forwarded
over multiple hops as proof of physical proximity.

The rank resolves concurrent starts; it cannot repair conflicting physical
board identities. If two phones identify one board differently because of a
rotating BLE address, they derive separate cells. Durable board binding is
therefore part of the hardware and production requirements.

Each settlement also creates a random durable lineage ID. Two radio-isolated
groups can still settle the same deterministic Cell independently. When they
later meet, unequal lineages or conflicting hashes are a fork: both sides enter
`FROZEN_FORK`; confirmed histories are never silently merged.

## 6. Nearby awareness and admission

The BLE advertisement contains only compact, non-authoritative hints:

- protocol version;
- dynamic L2CAP PSM;
- four-byte realm tag;
- four-byte cell tag;
- four-byte tag of a random, short-lived join nonce.

The scanner passes only candidates with matching realm **and** cell tags to
FIPS. The short tags fit the constrained BLE advertisement; they are prefilters,
not security proofs. After FIPS establishes an authenticated link, the
CruxCoach `CCJ1` control frame supplies the full IDs and full nonce.

A peer is admitted only when all of the following hold:

1. FIPS authenticated its cryptographic identity;
2. the complete realm ID and BoardCell ID match;
3. the CCJ1 hello is fresh (a locally observed nonce strengthens it when
   Android scanning is symmetric, but is not required on the listener);
4. the sender is a direct BLE peer at that time. The joining side must have
   discovered the member it dialed; the listener need not also discover the
   initiator before accepting its authenticated inbound L2CAP channel.

An admitted member may later send application data over multiple FIPS hops. It
may also sponsor a directly FIPS-authenticated, full-scope-validated neighbor to the
canonical controller, so joining does not require a direct controller edge.
The normal client emits sponsorship only for its current validated direct BLE
peer. A random collision of the short
advertisement tags is insufficient: full validation rejects the link before it
becomes a durable realm edge.

This is the boundary that prevents receiving events from “somewhere else.”
Physical proximity is required for initial direct admission. After admission,
that member may use multi-hop only within the same isolated cell.

## 7. How every participant reaches the same state

BoardCell deliberately uses neither majority consensus nor a freely mergeable
CRDT. One physical board has one canonical controller that serializes writes.
This matches the physical system: the board ultimately observes one concrete
ordering of LED commands.

The complete snapshot contains at least:

- `PhysicalBoardId`, `BoardCellId`, and epoch;
- monotonic sequence number;
- controller, persisted controller term and ordered heartbeat counter;
- members;
- last confirmed board climb and angle;
- whether the current physical board content is known;
- complete playlist/session and current index;
- a playlist-only revision that is unaffected by controller heartbeats;
- availability state;
- hash of the complete state.

Every event names its cell, board, and epoch, advances the sequence by exactly
one, and binds both the previous and resulting SHA-256 hashes. Every replica
applies the same deterministic reducer.

A climb becomes canonical only after the physical BLE write succeeds and the
controller then orders `PROJECT_COMMITTED`. A failed board write creates no
commit. If the board accepts bytes from an external app but CruxCoach cannot map
them to a catalogue climb, the controller orders `PROJECT_UNKNOWN` instead.
CruxCoach therefore never falsely presents the previously known climb as the
latest confirmed board state.

Every physical write has a synchronous durable state machine:

```text
WAL PREPARED -> physical write -> WAL PHYSICAL_WRITE_SUCCEEDED
             -> durable snapshot + COMMITTED ack -> broadcast event
```

A crash before the physical write discards the intent. A crash after durable
commit is repaired by anti-entropy. A crash between physical success and commit
cannot be resolved from current board protocols because they expose no semantic
readback. Startup therefore orders `PROJECTION_RECOVERY_REQUIRED`, reports no
known climb and stays frozen until an operator deliberately reprojects.

Controller liveness never uses a serialized wall-clock deadline. The history
contains a generation/term and heartbeat number. Each phone records receipt
against `elapsedRealtime()` and applies only its local timeout. Clock skew
cannot transfer authority. After three missed heartbeat windows, eligible
members start a short staggered recovery race. A claimant advances the term
only after acquiring the physical board connection, which acts as the fencing
token; peers validate the exact previous controller, term, sequence and hash.
The recovery base remains in the hashed snapshot so a peer that missed the
recovery event can still converge after reconnect.

Controller handover is durable and explicit:

```text
source PREPARED(target, nextTerm)
target acquires HOST + board keep-alive + connected-board gate
target READY -> source TARGET_READY -> source COMMITTED(nextTerm)
target renews heartbeat/write authority -> target COMPLETED
source may now tear down its old session and board ownership
```

The source may abort only before commit. A restarted source or target resumes
the persisted phase. An unavailable committed target causes waiting/freeze,
not rollback or election.

When a replica observes a sequence gap, hash mismatch, or newer epoch, it never
skips ahead. It freezes and requests a complete snapshot. If the controller
then disappears, that snapshot-wait state also advances into fenced controller
recovery instead of remaining stuck. Devices additionally compare their
`(cell, epoch, sequence, hash)` positions periodically. These digests are
best-effort and coalescing by nature, so offline peers cannot crowd canonical
events or snapshots out of the durable outbox. Persisted snapshots support
recovery after process and radio interruptions.

“Eventual consistency” therefore means:

- reachable members receive the same ordered history;
- a temporarily unreachable member may wait and catch up from a snapshot;
- a missing controller is replaced quickly, but only by a member that wins the
  physical-board connection fence and publishes the exact next term;
- a partition cannot create an accepted logical writer without that same
  physical fence, and merge-time anti-entropy repairs missed recovery events.

Safety and an unambiguous physical board state take priority over availability.

## 8. Nearby Climb, playlist, and session

Existing Nearby advertisements remain fast, backwards-compatible hints. Their
legacy 24-byte format has no board scope, so it is authoritative only while at
most one board is known. Once multiple boards have been observed, an unscoped
legacy hint cannot overwrite the selected BoardCell.

After admission, the BoardCell snapshot is always the source of truth:

- `projection` answers which climb was last confirmed as sent;
- `playlist` contains the complete list, session ID, and current position;
- each command carries a playlist revision plus semantic references to the
  affected climb occurrence, current climb and/or move destination anchors;
- validation/rebase, queue mutation and playlist snapshot commit execute in one
  cell-critical section, so a rejected command has no local side effect;
- stale commands are not rejected merely for being stale. Adds, removes,
  selections and moves are rebased when their actual preconditions remain
  unchanged. `Next`/`Prev`, changed destination anchors and ambiguous duplicate
  climbs conflict rather than guessing what the user meant;
- every command has a durable ID and correlated `ACCEPTED`, `COMMITTED` or
  terminal failure result. The UI reports a real conflict after applying the
  latest canonical snapshot instead of silently ignoring the tap;
- missing terminal acknowledgements are retried with the same command ID and
  original semantic preconditions using bounded exponential backoff. A slim
  progress indicator remains visible while commands are pending;
- the latest 256 committed command IDs travel in the hashed snapshot, so a
  controller receiving a full snapshot retains idempotency across handover;
  the matching durable ACK store is pruned to the same bound;
- authenticated FIPS and scoped GATT ingress enter the same controller
  serializer. FIPS uses bounded suspendable backpressure; GATT rejects an ATT
  write instead of acknowledging and dropping it when its bounded ingress is
  full. Scoped GATT sends a targeted command-result event back to its caller;
- reconnecting and newly admitted participants receive a full snapshot rather
  than only future deltas.

GATT session info has a backwards-compatible optional BoardCell extension.
GATT remains an admission/compatibility path and the Android 9/API 28 fallback.
On API 29+, admitted participants prefer the authenticated FIPS data plane.
The API 28 wire format still carries legacy indices, so the controller captures
their semantic meaning at ordered receipt time; it cannot recover an intention
that was already stale on the Android 9 screen.

## 9. Multi-connect boards and adjacent boards

A board may technically accept several BLE connections. That must not create
several independent writers. Within CruxCoach, direct board sends, queue sends,
and identifiable CruxRelay writes pass through the same controller term,
local-monotonic liveness gate, WAL and mutex. The ordering of physical writes matches the
ordering of `PROJECT_COMMITTED` events.

Participants do not agree on one shared FIPS `npub`. They agree on
`PhysicalBoardId`, `BoardCellId`, epoch, and the canonical controller. Every
participant retains its own transport identity.

Adjacent boards are separated at several layers:

1. stable physical board identity;
2. derived Cell ID;
3. distinct realm for ordinary board sessions;
4. realm/cell prefilter before FIPS auto-connect;
5. complete realm/cell/nonce validation after FIPS authentication;
6. scope validation on every snapshot, event, and queue command.

This boundary covers paths controlled by CruxCoach. A third-party app that
connects directly to the board and bypasses CruxRelay cannot be reliably
serialized by CruxCoach. Such a successful write is observable and ordered only
when it passes through the CruxRelay ingress.

## 10. Mesh size and traffic in Variant A

A phone does not maintain 39 direct links to all other participants. The native
BLE link cap is seven; support for 20–40 members comes from a bounded-degree
graph and multi-hop routing. In practice, two or three stable direct edges per
phone are preferable for battery use and OEM compatibility.

Variant A prevents unrelated global transit but does not eliminate internal
FIPS control traffic. The current bridge still inherits the FIPS defaults,
including link MMP in `full` mode. Depending on RTT and direct-link count, that
can amount to roughly several MB per phone per hour even when the application
is idle. BoardCell events themselves are small, but large snapshots and the
current per-member unicast fan-out must still be measured at 40 members on real
devices.

Before production approval, OEM tests must determine whether link and session
MMP should use `minimal` or `lightweight`, and whether CruxCoach should manage a
preferred direct degree below the hard cap. This tuning does not alter realm
isolation or state consistency.

## 11. Local competitions

Competition definitions, intents, and authority-chain events retain their
existing Nostr-compatible signatures and IDs. “Nostr-compatible” describes the
data format and cryptographic validation only. In local mode the events travel
through FIPS and are not published to a Nostr relay.

The existing signature/ID checks and deterministic competition reducer remain
authoritative. Per-participant epoch and sequence prevent silent event gaps; a
gap causes a local history request. If the competition authority disappears,
no competing authority chain is created. New intents wait until the canonical
history is reachable again.

The current MVP supports a **local competition within one BoardCell**. A
competition realm spanning multiple boards would be an explicit extension. It
must define how participants directly enter additional cells, which boards are
part of the competition, and how the competition avoids becoming an accidental
gym-wide bridge.

## 12. Relationship to CruxRelay

FIPS does not completely replace CruxRelay. Their responsibilities are now
separate:

- on API 29+, FIPS is the authenticated, encrypted post-admission data plane
  between CruxCoach devices and provides multi-hop;
- CruxRelay remains the controlled GATT entry point for supported official
  third-party board apps;
- API 28 retains the GATT compatibility path without FIPS multi-hop.

An external write through CruxRelay crosses the same controller boundary as a
native CruxCoach send. Identifiable writes become `PROJECT_COMMITTED`;
successful but unidentified writes become `PROJECT_UNKNOWN`. The write is
refused when the controller is missing, locally timed out, handing over, forked,
or recovering an uncertain projection.

## 13. Activation and app lifecycle

FIPS is built as an Android `arm64-v8a` native library:

```sh
./gradlew :androidApp:buildFipsNative :androidApp:assembleDebug
```

At runtime CruxCoach starts FIPS only when:

1. the device runs at least Android 10/API 29;
2. a concrete BoardCell or local competition is active;
3. the realm key has been loaded or generated;
4. the Android L2CAP transport is available.

An active cell/session/competition holds a `connectedDevice` foreground
service. Ordinary foreground discovery remains process-scoped. Android 9/API
28 does not start FIPS and continues to use GATT, but uses a persistent local
BoardCell controller ID and the same write-ahead/commit state machine.

## 14. Implemented guarantees and physical limits

Implemented in software: exact board/cell/realm/epoch/term validation, replay
and size bounds, one ordered controller history, local-monotonic liveness,
multi-phase explicit handover, durable command deduplication, uncertain-write
freeze, fork detection, snapshot gap recovery, and coverage for native sends,
playlists, CruxRelay and API 28.

Not physically provable with current hardware: two already isolated controllers
may both write before radio heal because the board accepts no fencing token; a
crash-straddling write cannot be identified without semantic readback; a
rotating BLE address is not stable without serial or operator binding. Software
freezes when evidence appears and exposes explicit recover/reproject operations.
It does not claim retroactive prevention.

Open hardware gates are OEM BLE/L2CAP and Doze behavior, multi-connect
semantics, firmware serial/readback/fencing support, address rotation, and
20–40-device RF/load measurements.

## 15. Variant B: shared FIPS underlay — future evaluation

Variant B would connect CruxCoach devices to a general FIPS component that may
also be used by other applications. Realm and BoardCell would then be
application-layer filters. This is closer to Myco's model: an application may
exchange only its own local objects while its underlying FIPS node still
participates in a wider network and may forward transit traffic.

Potential benefits include:

- interoperability with a general FIPS network;
- reuse of existing FIPS paths;
- potentially wider reach and shared infrastructure;
- future local services beyond one BoardCell.

The principal unresolved risks are:

- a phone could forward unrelated FIPS traffic even though CruxCoach discards
  it at the application layer;
- a Wi-Fi, UDP, or internet bridge could connect remote network regions to the
  local BLE graph;
- control traffic, foreign payload, battery use, and memory use would no longer
  be bounded by the local competition size;
- filtering by realm after routing does not prevent transit load;
- generic `leaf_only` blocks foreign transit but also blocks the local
  multi-hop behavior CruxCoach needs;
- global or longer-lived identities create additional correlation and privacy
  questions;
- proximity to the physical board still requires a non-relayable direct
  admission proof.

Variant B requires at least the following before reconsideration:

1. realm- or application-selective forwarding below the global route;
2. hard byte, packet, peer, and battery budgets;
3. priority for BoardCell state over unrelated transit;
4. an explicit identity and rotation policy;
5. protection against accidental gym and internet bridges;
6. long-running measurements across several Android/BLE chipsets;
7. an explicit product model for cross-board and cross-realm reachability.

Until those questions are answered and measured, Variant B remains an
architecture option. It must not appear accidentally by enabling additional
FIPS transports.

## 16. iOS and direct phone-to-board BLE

Direct board communication remains a product requirement for normal personal
use on iOS. A remote Android controller is not an adequate substitute. Stock
Safari does not expose Web Bluetooth, however, and an nsite cannot grant a web
page CoreBluetooth access. Some native BLE host must therefore be installed on
the iPhone.

The lowest-cost feasibility route is a CruxCoach web client inside an existing
Web Bluetooth host such as Bluefy. That keeps CruxCoach itself out of the App
Store and still gives the phone a direct GATT path to the board, but it depends
on a third-party App Store app and does not expose the BLE/L2CAP primitives
needed for the current FIPS phone-to-phone mesh. Full BoardCell mesh parity
requires a thin native iOS host, including CoreBluetooth and an iOS FIPS
transport, distributed through an explicitly supported signing or alternative
distribution channel.

A future Myco iOS runtime could host a CruxCoach nsite and expose a narrow board
capability. It would not eliminate native installation: Myco itself would need
to be installed first, and the current Myco runtime has neither an iOS client
nor the required privileged nsite capability API.

Regardless of platform, direct BLE capability does not grant write authority
inside a competition. Only the canonical BoardCell controller may cross the
write safety boundary. The platform analysis, distribution choices, security
constraints, and required board-hardware spike are documented in
[`docs/IOS_BOARD_ACCESS.md`](../../IOS_BOARD_ACCESS.md).

## 17. Binding MVP decision

The MVP uses Variant A:

- the CruxCoach app embeds FIPS;
- FIPS identity and account identity are separate;
- realm/cell tags filter BLE candidates before FIPS peering;
- only directly observed devices can enter a cell;
- admitted members may use multi-hop inside that cell;
- complete snapshots plus a sequence/hash chain provide convergence;
- one controller orders every physical board write;
- adjacent boards and unrelated FIPS applications create no transit edges;
- local competition data is not published to Nostr;
- hardware testing remains the production gate for OEM BLE behavior, Doze,
  traffic, load, and rotating board addresses.

The resulting mental model is intentionally simple: a device that directly
enters the same BoardCell becomes part of its local mesh. A device at another
board or outside the cell remains outside at the FIPS layer as well.
