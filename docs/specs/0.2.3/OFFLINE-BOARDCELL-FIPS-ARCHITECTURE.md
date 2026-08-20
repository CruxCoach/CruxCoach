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
- the complete shared playlist and the occurrence it currently points at;
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

The explicit joiner opens the outbound L2CAP channel. A settled member keeps
advertising, accepting inbound channels and scanning foreign cells for the
Nearby UI, but does not simultaneously dial advertisements for its own cell.
This avoids the Android cross-connect race without introducing a distinguished
admission server. Nearby entries are live observations, not saved membership:
an advertisement that is not refreshed expires from the UI within eight
seconds, including after the last controller ends a cell.

## 7. How every participant reaches the same state

BoardCell deliberately uses neither majority consensus nor a freely mergeable
CRDT. One physical board has one canonical controller that serializes writes.
This matches the physical system: the board ultimately observes one concrete
ordering of LED commands.

The complete snapshot contains at least:

- `PhysicalBoardId`, `BoardCellId`, and epoch;
- monotonic sequence number;
- controller, persisted controller term and ordered heartbeat counter;
- the live member set and monotonically increasing membership revision;
- members;
- last confirmed board climb and angle;
- whether the current physical board content is known;
- the complete shared playlist: its derived session id, its entries with their
  stable occurrence ids and per-entry rest plan, the current *entry*, any
  running rest, the pending-send report and the clear generation;
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
recovery event can still converge after reconnect. A different recovered
controller removes the failed controller from live membership in that same
canonical transition.

Member liveness uses the same local-monotonic rule independently of controller
liveness. Every non-controller emits an authenticated best-effort heartbeat at
2 s intervals. The FIPS routed message retains its end-source npub across
multiple hops, and any other valid, correctly scoped source frame renews the
same observation. The controller emits `MEMBER_LEFT` only after 6 s without
source traffic. Voluntary leave requests produce the same ordered event. A
phone that disables Bluetooth clears its local realm, selection, keep-alive
and durable membership replica immediately; the controller converges after the
three missed windows. When an excluded stale partition reconnects, its digest
receives an authoritative exclusion snapshot. It cannot resume the old lease
and must be sponsored through the ordinary permissionless join path.

Controller handover is durable and explicit:

```text
source PREPARED(target, nextTerm)
target acquires HOST + board keep-alive + connected-board gate
target READY -> source TARGET_READY -> source COMMITTED(nextTerm)
target renews heartbeat/write authority -> target COMPLETED
source may now tear down its old session and board ownership; if leaving, the
new controller sequences MEMBER_LEFT(source)
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

## 8. Nearby Climb, the shared playlist, and session

Existing Nearby advertisements remain fast, backwards-compatible hints. Their
legacy 24-byte format has no board scope, so it is authoritative only while at
most one board is known. Once multiple boards have been observed, an unscoped
legacy hint cannot overwrite the selected BoardCell.

### One playlist per BoardCell, and no lifecycle of its own

A BoardCell has exactly one playlist. It is created with the cell — its session
id is derived from the cell id and epoch, so every replica computes the same
value and there is no start command to lose — and it lives exactly as long as
the cell. There is deliberately no playlist host, no separate playlist join or
leave, no approval and no independent end. Being an admitted member of the
BoardCell *is* taking part, and every member may edit the playlist arbitrarily.

The technical controller serializes those edits and remains the single writer
to the physical board. It has no product-level authority over the playlist, and
nothing in the UI presents it as having any. A controller handover or a fenced
recovery therefore moves no product role at all: the playlist is untouched by
both.

Outside a BoardCell a device keeps a private local playlist, which is never
published and is unaffected by any of this.

### Occurrences, not indices

The same climb may occur any number of times — 4x4s and limit-attempt blocks
are written out exactly that way — and every occurrence carries its own stable
`entryId`, minted by the device that adds it. That is what makes concurrent
duplicate-climb edits unambiguous: two people removing "the second Zombie
Hands" name the same occurrence and the second removal is a no-op, rather than
deleting two different entries or the same one twice. It is also what makes a
retry idempotent — the same add applied twice is one entry, on any controller,
in any order.

The current entry, the entry a running rest is waiting on and the pending-send
state all name an occurrence rather than an index, so a concurrent add or move
cannot silently make any of them mean a different climb.

### Bounded typed operation deltas

A normal edit travels as a bounded, typed operation batch — add, remove, move,
set-current, set-rest, start/end-rest, clear, and the controller-only
pending-send report — never as a whole-playlist broadcast. One command is one
atomic batch, which is how "advance and arm the planned rest" stays a single
canonical step that a reconnect or a handover cannot split. Committed deltas
are replayed by every replica through the same pure reducer and verified
against the envelope's resulting hash, so a divergence is detected rather than
absorbed.

Full canonical snapshots remain the repair path, unchanged: join, restart, a
detected gap, anti-entropy, controller recovery and handover all carry complete
state.

Conflict handling is deterministic and needs no tie-breaking heuristic:

- an add whose anchor has disappeared lands at the end; a move whose anchor has
  disappeared leaves the entry exactly where it is;
- removing, pointing at or re-timing something that is already gone is accepted
  and changes nothing;
- removing the current entry moves the group to whatever now occupies that
  position; a move keeps the current entry and any running rest on their own
  occurrence;
- every command carries the clear generation it was composed against, so an
  edit that was in flight while somebody else emptied the playlist is dropped
  rather than resurrecting one entry of a list that no longer exists — and a
  retried clear names a generation already reached and does nothing.

### Commits, acknowledgements and repair

- the canonical commit and its replication are complete before the physical
  board is touched at all. Projecting the current entry onto the wall is a
  separate, later step, so a board that is slow, busy or missing can never hold
  up the group's playlist;
- a failed or impossible projection is recorded canonically with an honest
  reason and a retry any member may press; it never becomes an error state for
  the playlist itself;
- every command has a durable ID and correlated `ACCEPTED` and terminal result.
  `ACCEPTED` is sent before the command is even applied, so a sender stops
  resending within one round trip;
- unacknowledged commands are resent with the same ID on a sub-second schedule
  of their own — first retry at 250 ms, widening to 4 s — rather than on the
  2 s maintenance tick;
- `NOT_CONTROLLER` and `REJECTED_STALE` are statements about the answering
  device at that moment, not decisions about the command, and are deliberately
  never cached. Only a real decision is replayed to a retry;
- the latest 256 committed command IDs travel in the hashed snapshot, so a
  controller receiving a full snapshot retains idempotency across handover;
  the matching durable ACK store is pruned to the same bound;
- a delta that reveals a gap makes the replica request canonical state
  immediately. A separate 250 ms repair loop re-asks while the replica is still
  missing state, so a lost repair request does not wait for anti-entropy;
- a replica never presents itself as synchronised while the cell is frozen or
  the controller has been silent for three heartbeat windows. During a
  partition the UI says the list may be out of date rather than passing it off
  as the group's.

### Wire

`BoardCellWireCodec.VERSION` is 12 and the state hash schema is
`board-cell-v8`. Older peers fail closed at the exact-version gate, because a
V11 reader would decode a populated shared playlist as an empty one. Legacy
hash schemas stay valid only while the playlist is genuinely empty, so no
pre-V8 shape is ever reinterpreted under the new one; a durable snapshot in the
old shape fails to decode (`ignoreUnknownKeys = false`) and the cell is rebuilt
from the mesh, while write intents and command acks — which the change does not
touch — survive the upgrade.

The controller's stamps are the controller's: a command must leave the rest
window and the clear generation at zero and may not carry the pending-send
report at all, and a committed delta must carry them. Both directions are
enforced at the wire, so no peer can dictate a canonical deadline.

### GATT and Android 9

GATT session info has a backwards-compatible optional BoardCell extension.
GATT remains an admission/compatibility path and the Android 9/API 28 fallback.
On API 29+, admitted participants prefer the authenticated FIPS data plane.

An API-28 device has no FIPS identity and takes part as a GATT leaf of a
gateway. The gateway needs no special authority for this: what the leaf asks
for is something every cell member may do anyway, so the edit travels the
ordinary path under the gateway's own identity, and the leaf's result byte is
the controller's real answer. The API 28 wire format still carries legacy
indices, so the gateway resolves them into occurrence ids against its own
replica at ordered receipt time; it cannot recover an intention that was
already stale on the Android 9 screen.

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

## 14a. FIPS platform restack (August 2026)

### Which FIPS, and why it is in this repository

| | commit | role |
| --- | --- | --- |
| superseded | `967776079ba5ddc8fe118c3f289365b51eb03737` | the original pin (7 Aug 2026) |
| current | `6580a806f9b05ee10497786f872fd65480ca8e5c` | reviewed platform-integration lineage (18 Aug 2026) |

The current revision is **vendored into this repository** at `native/fips`, and
`native/fips-bridge/Cargo.toml` depends on it by path.

It was a Cargo `git`/`rev` pin before. That is reproducible only for as long as
the upstream object survives, and `6580a80` is the head of a branch
(`integration/platform`), not a tag or a commit on `master` — precisely the
kind of object that moves or is pruned. A signed development APK we cannot
rebuild from this repository alone is not something we can support. Vendoring
removes the FIPS repository from the build path. The exact crates.io sources
resolved by the bridge lockfile are mirrored under `native/cargo-vendor`, so
the native build itself runs with Cargo `--frozen` and needs no network.

The alternative — a CruxCoach fork of `jmcorgan/fips` carrying an immutable
integration branch — is the better long-term shape, but no such fork exists and
creating an external repository was outside this change's authority. Migrating
later is mechanical: move `native/fips/patches` onto a branch based on
`6580a80` and point Cargo at that revision.

Provenance is recorded in `native/fips/VENDOR.toml`: upstream URL, exact
commit, copied paths, the patch series, and digests of both the pristine and
patched trees.
`scripts/verify_vendored_fips.py` checks it two ways —

* offline (check the patched digest, reverse the exact patch series, check the
  pristine digest, then reapply the series), which runs on every CI run through
  `scripts/verify_vendored_fips_test.py` and requires no upstream object;
* `--upstream <path-to-a-fips-clone>`, which proves that upstream `6580a80`
  plus exactly the recorded patches reproduces the vendored tree byte for byte.

Only crate inputs are vendored. Upstream's `docs/`, `testing/`, `packaging/`,
`examples/` and nix flake have no bearing on the Android library build.
The registry mirror is refreshed only together with
`native/fips-bridge/Cargo.lock` by running the pinned `cargo vendor
--versioned-dirs --frozen` command documented in `.cargo/config.toml`.

### The patch series we carry

`0001-node-restore-the-app-owned-identity-seam.patch` adds
`Node::enable_app_owned_identities()`.

`6580a80` removed `Node::enable_app_owned_dns()`, which CruxCoach depended on.
Outbound packets pushed through the app-owned TUN are routed by looking the
destination's truncated address hash up in the node's identity cache. A
`FipsAddress` does not carry the public key, so an address the cache has never
seen is answered with ICMPv6 "destination unreachable" — every *first* send to
a new BoardCell member would be lost. Upstream's remaining filler for that
cache is the `.fips` DNS responder, which an application that already holds the
peer's npub has nothing to ask.

The patch is deliberately narrow and upstreamable: one call before `start()`,
returning the same `DnsIdentityTx` the responder produces, drained by the same
`run_rx_loop` arm. Arming twice returns the same sender, and a DNS responder
starting afterwards feeds this channel instead of replacing its receiver.
Registering an identity asserts only that a key hashes to an address; it grants
no session, peering or authorization.

`0002-ble-share-advertised-psms-with-direct-reconnects.patch` makes direct
reconnects reuse only fresh, observed Android advertisements and their dynamic
L2CAP PSM. It prevents a node-layer retry from dialing a rotated address or the
static fallback PSM while the scan loop already knows the current endpoint.

`native-api-v1` was **not** adopted. It is experimental and gated to
Linux/FreeBSD, not Android. It informed the shape of the seam and nothing more.

### API migration

Compilation, not a prior report, produced this list. It confirmed all six
expected breaks and found no others; `AndroidRadio`, `AndroidBleBridge` and
`BleAddr` are structurally unchanged.

| upstream change | CruxCoach response |
| --- | --- |
| `android_io::set_android_ble_bridge` removed | node-owned `BleRadioSlot` via `Node::enable_app_owned_ble_radio()`, reconciled by `native/fips-bridge/src/radio_install.rs` |
| `transport::ble::attempts` deleted | two honest diagnostic layers (below) |
| `ControlReadHandle` / `Node::control_read_handle()` now `pub(crate)` | the supported control socket, read by a background peer directory |
| `ControlReadHandle::peer_views()` gone | `show_peers` + `show_sessions` over that socket |
| `Node::enable_app_owned_dns()` removed | the vendored patch above |
| `deliver_scan` rssi `i32` → `Option<i16>` | Android's 127 "unavailable" sentinel maps to `None` |

### Radio lifecycle ownership

A node-owned slot is the right shape but does not by itself settle ownership,
because CruxCoach has two independent lifetimes: the radio is built by
`bleBridgeNew` *before* a node exists and freed *after* one is gone, while the
node is rebuilt on every realm switch, permission restart, Bluetooth off/on and
idle-transport recycle.

`RadioInstall` is the single reconciliation point, and every withdrawal is
guarded by an ownership token — the bridge's JNI handle, or slot pointer
identity:

* a stop that lost the race to a start presents a stale handle and is refused,
  so it cannot disarm the radio its successor just installed;
* publishing a new node's slot clears the previous one, so a node whose stop
  timed out and was detached cannot keep driving the phone's radio underneath
  its replacement, and that detached node's own late stop owns nothing;
* a failed or timed-out `Node::start` retracts the slot but keeps the radio,
  which Kotlin still owns and reuses on the retry;
* a Bluetooth cycle clears and reinstalls under a node that never stopped — an
  empty slot parks the backend rather than failing it.

The logic is generic over the slot so it compiles and is tested on the host,
where `fips` is not a dependency at all.

### Inbound attribution: control socket, directory, and the hold

An inbound datagram carries the sender's `FipsAddress`, which is a hash — the
npub has to be looked up. With `peer_views()` gone, the supported source is the
control socket, bound in app-private storage
(`<no_backup>/fips/ctl-<generation>.sock`, `0700` directory).

It is **not** queried per packet. A background refresher (1 s, plus an
out-of-turn nudge on a miss) keeps a bounded snapshot, and `receive()` reads
that. `show_sessions` is merged with `show_peers` because `show_peers` names
only direct links, and a multi-hop BoardCell member that cannot be named is a
dropped delta. Rows are keyed by the address derived locally from the npub,
with the daemon's `ipv6_addr` used only as a cross-check, and `stale` counts as
send-capable exactly as `ConnectivityState::can_send` does — a member that
missed one heartbeat window still carries traffic and still needs its
direct-join hello.

A datagram whose sender is not in the directory yet is **held, not dropped**:
on a fresh join the first frame regularly beats the peer table that describes
it. The hold is bounded in count and in time, and every eviction and expiry is
counted, so a lost BoardCell delta is visible rather than silent.

The socket path carries a generation, so a stop that timed out cannot block the
next start on a path its detached node still owns; stale files are removed only
after proving nobody answers; and the path is length-checked against `sun_path`
rather than failing obscurely at bind time.

### Diagnostic semantics

Upstream deleted the per-peer BLE attempt ring, and it cannot be reconstructed
from what replaced it. CruxCoach does not substitute an empty placeholder, and
does not fabricate per-peer attempts from aggregates. Two layers report what
each actually knows:

1. **Platform / per-peer** — the Kotlin radio owns every dial, accept, channel
   open and close, and traces each with its address, direction, outcome and
   lifetime. This is where per-peer attempt history genuinely lives.
2. **FIPS / aggregate** — connect, timeout, pubkey-exchange, tie-breaker,
   duplicate-decline, eviction, scan and advertisement counters, projected from
   `show_transports`, reported as `instance/counter/value` and diffed between
   polls. The bridge's own inbound-hold and directory-refresh-failure counters
   are reported alongside.

A counter the daemon does not report is omitted rather than rendered as a zero
it never claimed; nothing identifying (adapter address, npub) is emitted; and a
counter that resets because the node was rebuilt is not reported as activity.

### BLE dial scheduling

See `FipsDialScheduler` and `FipsScanCoalescer`. The short version: a locally
suppressed dial must never be reported to FIPS as
`bleDeliverConnectResult(.., false, ..)`, because `6580a80` feeds that into a
per-address exponential backoff that reaches one attempt per 16 minutes.
Redundant candidates are removed *before* the scan reaches FIPS instead, and
the concurrency bound defers rather than lying.

### What was tested — and what was not

Verified without hardware: the vendored-tree provenance (offline and against
upstream), the Rust bridge unit tests including the radio-ownership race
matrix, the `show_peers`/`show_sessions`/`show_transports` parsers against the
current upstream schema, the inbound hold's bounds and counters, the Kotlin
counter model, the dial-scheduling and scan-coalescing policies, the full
`:shared` and `:androidApp` JVM unit suites, an `aarch64-linux-android` release
cross-build, and `assembleDebug`.

**Not** verified, and the mandatory hardware gate before this ships to anyone:
two- and three-phone join, multiple OEMs, Bluetooth toggle recovery, RPA
rotation, simultaneous cross-probe, time-to-first-snapshot, and 30-minute
energy behaviour. JVM tests cannot certify an OEM BLE stack.

### Rollback

Revert the restack commits, or build the predecessor branch
(`feat/board-cell-mesh-reliability`), which still pins `9677760`. There is
deliberately **no** dual-FIPS runtime: two protocol revisions in one process
would double the surface this migration exists to keep small, and the rollback
target is a branch that already builds.

The superseded `9677760` source object is additionally mirrored outside
upstream retention on the operator/build host, under the refs named in
`native/fips/VENDOR.toml`, so the rollback build does not depend on GitHub
keeping an object nothing references. Placing that mirror somewhere durable
off that host would mean creating an external repository, which is the project
owner's call rather than this change's.

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
