---
status: implementation
queue: n/a
---
# FEAT-059 — Offline BoardCell over FIPS

The explanatory architecture overview, including the selected isolated-realm
model (variant A) and the unevaluated shared-underlay alternative (variant B),
is in
[`OFFLINE-BOARDCELL-FIPS-ARCHITECTURE.md`](OFFLINE-BOARDCELL-FIPS-ARCHITECTURE.md).

## Outcome

One physical board has one application scope (`BoardCell`) independent of BLE,
GATT or FIPS links. The complete state is a deterministic snapshot containing
physical-board id, cell id, lineage, epoch, controller term, ordered heartbeat,
sequence, live members and membership revision,
projection and playlist. Every delta names the same board/cell/epoch, advances
exactly one sequence and binds previous/resulting SHA-256 hashes.

FIPS commit `967776079ba5ddc8fe118c3f289365b51eb03737` is pinned in Cargo.lock.
CruxCoach synthesizes bounded IPv6/UDP application datagrams through FIPS'
app-owned-TUN seam; it does not create an Android VPN and does not use Nostr
discovery or relays for BoardCell state.

## Identity and bootstrap

- Aurora serial is preferred. BLE address is the observed fallback for boards
  (including MoonBoard) that expose no serial. A randomized address requires a
  persisted explicit CruxCoach binding. Name, model and RSSI are never keys.
- A new wall first emits a claim only to authenticated direct BLE FIPS peers.
  Claims are not flooded. A 2 s settling window and deterministic claim rank
  resolve concurrent claims before the first physical write. New Cell IDs are
  deterministically derived from the durable physical identity, so simultaneous
  bootstrap cannot create two radio-isolated candidate realms.
- Joined snapshot/delta traffic may route multi-hop. Authenticated source,
  membership, physical board, cell, epoch, sequence and hashes are checked.
- The FIPS secret lives in a separate encrypted realm store and is never the
  account/Nostr key. It survives reconnect, process death and Bluetooth restart
  per realm across reconnect, process death and Bluetooth restart. Competition participant
  continuity has another opaque encrypted local credential.
- A normal realm has `realm_id == board_cell_id`. A local competition has
  `realm_id == competition_id`; every wire member retains its Cell and
  physical-board IDs. The current BLE admission path still requires both the
  realm and the selected Cell tag to match, so transport remains Cell-local.
  Deliberate cross-board competition peering is a future design decision, not
  an MVP capability. Switching away from a board realm freezes board writes.

## Consistency and failure behavior

- The controller serializes physical writes. A successful wall write becomes
  canonical only with the following ordered `PROJECT_COMMITTED` event. Before
  touching the board it synchronously persists a write-ahead intent; after the
  write it persists physical-success and then the resulting snapshot/command
  acknowledgement before broadcasting. FEAT-044 external-app GATT ingress uses
  the same mutex/term boundary: an identified write emits
  `PROJECT_COMMITTED`, an unidentifiable successful write emits `PROJECT_UNKNOWN`,
  and a failed physical write emits neither.
- A sequence gap, hash mismatch or newer epoch freezes the replica and requests
  a full snapshot. It never skips a delta.
- Absolute timestamps are never compared between phones. The snapshot carries
  a persisted controller term and ordered heartbeat counter; each receiver
  derives expiry only from its own monotonic clock. After three missed
  heartbeat windows, members immediately race a staggered, fenced recovery.
  Only a claimant that acquired the exclusive physical board connection may
  advance the controller term, minimizing controllerless time without allowing
  an unfenced logical writer.
- Membership is live, not an indefinite durable entitlement. Every non-controller
  sends a best-effort authenticated heartbeat to the controller every 2 s; FIPS
  preserves the end-source identity across multi-hop routing. Any valid scoped
  source traffic renews that lease. After three missed windows the controller
  sequences `MEMBER_LEFT`; one dropped packet never evicts a member. Voluntary
  leave uses the same canonical event. A recovered controller removes the failed
  controller in its fenced recovery event. A stale excluded replica accepts the
  newer controller snapshot as a tombstone and must use ordinary permissionless
  sponsorship to join again.
- Controller transfer is `PREPARED -> TARGET_READY -> COMMITTED -> COMPLETED`.
  The source explicitly selects a member. The target may emit readiness only
  after acquiring the session HOST role, board keep-alive and a connected-board
  gate. Timeout/abort is allowed only before commit. The old host tears down
  only after `COMPLETED`; either side resumes its persisted phase after restart.
- Every command has a durable `commandId` result (`ACCEPTED`, `COMMITTED`,
  `SUPERSEDED`, `REJECTED_STALE`, `REJECTED_CONFLICT`, `NOT_CONTROLLER` or
  `BOARD_WRITE_FAILED`). Playlist commands use a separate playlist revision,
  so heartbeat traffic does not invalidate them. Semantic item/current/anchor
  preconditions let the controller rebase independent concurrent changes;
  conflicting navigation or reorder intent is rejected visibly. Validation,
  optional rebase, local queue mutation and the complete playlist commit share
  the cell serializer. Missing terminal ACKs retry idempotently with bounded
  backoff. Bounded ingress applies backpressure (or rejects the GATT write)
  rather than silently dropping an accepted burst, and the queue UI exposes
  pending work plus targeted conflict/failure feedback.
- A crash after physical success but before canonical commit cannot be read
  back semantically on current board protocols. Recovery marks projection
  unknown and freezes until an explicit operator reproject succeeds.
- Independently settled lineages meeting later are a fork, not mergeable state.
  Both histories freeze and require operator selection plus reproject.
- Snapshots persist in app-private storage and anti-entropy runs every five
  seconds. A bounded outbox applies backpressure; its eviction is repaired by
  the mandatory gap/snapshot path.

## Active paths

- Direct Aurora/MoonBoard sends and queue sends pass through the controller.
  Board state, playlist/session scope and last projection are per physical wall.
- Legacy Nearby advertisements remain one-hop hints. Their 24-byte format has
  no scope, so they are authoritative only while at most one board is known.
- GATT session info has a backwards-compatible optional BoardCell extension.
  Once admitted, participant queue commands prefer authenticated FIPS and carry
  physical-board/cell/epoch/term/playlist-revision scope, semantic preconditions
  and a command ID; the canonical playlist snapshot
  drives reconnect and participant catch-up. GATT remains admission/API-28
  fallback rather than the post-join data plane.
- Competition definitions, intents and authority-chain events retain existing
  Nostr-compatible signatures but use local FIPS when a BoardCell is joined.
  Signature/id checks and the deterministic reducer remain unchanged; history
  request/replay supplies reconnect catch-up. BoardCell data is never Nostr data.

## Android, capacity and lifecycle

API 29+ uses Android L2CAP CoC beneath FIPS' authenticated/encrypted sessions.
The compact CruxCoach-specific advertisement contains only version, dynamic PSM,
four-byte realm/cell prefilter tags and a four-byte tag of a short-lived random
join nonce. Full realm and Cell IDs are checked in a fresh CCJ1 control frame;
the nonce is positive hardening, not a bidirectional-scan admission gate. A
joining phone needs to discover only one current member. CCJ1 is consumed below
the application transport and cannot be relayed as discovery. Foreign realm advertisements are never handed to
FIPS auto-connect. API 28 retains GATT transport fallback, but native, playlist
and CruxRelay board writes still cross the same BoardCell serializer and WAL.
An active cell/session/competition holds a `connectedDevice` foreground service;
ordinary foreground discovery is process-scoped. Frames use 900-byte chunks,
messages are capped at 1 MiB, assembly is bounded, and mixed/corrupt fragments
are rejected. The native direct-link cap is configurable at startup and clamped
to seven (CruxCoach uses seven). Logical fan-out is tested with a connected
40-member bounded-degree graph; scale comes from FIPS routing, never a 40-link
host star. RelayGattServer's four-client limit applies only to optional external
official-app guests, not to the FIPS graph.

The supported APK ABI is `arm64-v8a` and FIPS is enabled on API 29+. Build with
`./gradlew :androidApp:buildFipsNative :androidApp:assembleDebug`; the task uses
Rust 1.94.1, Cargo's locked git revision and the configured Android NDK. API 28
keeps the established GATT session lane because Android L2CAP CoC starts at 29.
The reproducible multi-device procedure and acceptance matrix are in
[`docs/FIPS_DEVICE_TEST_PROTOCOL.md`](../../FIPS_DEVICE_TEST_PROTOCOL.md).

## Production follow-ups

- Test multiple real OEM phones through Doze, process death, radio toggles and
  long-running L2CAP traffic; JVM/simulator tests cannot certify OEM BLE stacks.
- Add an explicit durable-binding UI for controllers that rotate BLE addresses.
- Add dedicated UI for frozen controller/snapshot recovery and operator-directed
  transfer. API 28 remains a GATT compatibility lane, not a FIPS multi-hop node.
- Validate firmware serial, semantic projection readback and a board-enforced
  fencing token. Without fencing, software cannot prevent two controllers that
  were already radio-isolated from both writing before fork discovery; it does
  stop all further writes once the conflict is observed.
