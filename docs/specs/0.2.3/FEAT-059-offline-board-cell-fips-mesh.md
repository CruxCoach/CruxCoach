---
status: implementation
queue: n/a
---
# FEAT-059 — Offline BoardCell over FIPS

## Outcome

One physical board has one application scope (`BoardCell`) independent of BLE,
GATT or FIPS links. The complete state is a deterministic snapshot containing
physical-board id, cell id, epoch, sequence, controller lease, members,
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
  in one active realm, and rotates on realm end or switch. Competition participant
  continuity has another opaque encrypted local credential.
- A normal realm has `realm_id == board_cell_id`. A multi-board competition has
  `realm_id == competition_id`; every wire member retains its separate Cell and
  physical-board IDs. Switching away from a board realm freezes board writes.

## Consistency and failure behavior

- The controller serializes physical writes. A successful wall write becomes
  canonical only with the following ordered `PROJECT_COMMITTED` event. FEAT-044
  external-app GATT ingress uses the same mutex/lease: an identified write emits
  `PROJECT_COMMITTED`, an unidentifiable successful write emits `PROJECT_UNKNOWN`,
  and a failed physical write emits neither.
- A sequence gap, hash mismatch or newer epoch freezes the replica and requests
  a full snapshot. It never skips a delta.
- Only the reachable old controller can order transfer. Lease expiry or a
  partition freezes writes; join-order timers cannot elect a scoped-session
  host. Safety deliberately wins over availability.
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
  physical-board/cell/epoch/sequence scope; the canonical playlist snapshot
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
join nonce. Full realm ID, Cell ID and nonce are checked in a CCJ1 control frame;
the sender must simultaneously be a native direct BLE peer and its nonce must
have been scanned locally. CCJ1 is consumed below the application transport and
cannot be relayed as discovery. Foreign realm advertisements are never handed to
FIPS auto-connect. API 28 retains GATT fallback.
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
