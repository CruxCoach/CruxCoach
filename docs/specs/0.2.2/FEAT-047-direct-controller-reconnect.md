---
status: implementation
queue: manual
base: 0.2.2
depends_on: []
created: 2026-07-19
---

# Feature Spec: Direct controller reconnect

## 1. Overview

After one successful physical-board connection, CruxCoach offers two explicit
choices for that active board family:

1. **Reconnect** reuses the known controller descriptor and opens GATT directly.
2. **Search for boards again** starts fresh BLE discovery and shows its results.

These are different operations with different permission requirements. A direct
reconnect must never start discovery or fall back to discovery implicitly.

## 1a. Revision 2026-07-25 — scan cost decides the flow

The split above was applied on every Android version. It should not have been:
`BLUETOOTH_SCAN` is declared `neverForLocation`, so from Android 12 discovery
costs the user nothing that a reconnect does not also cost. Withholding it there
only hid boards that were in range.

- **Android 12+**: discovery is the flow. The sheet always scans and lists every
  board in range. A single candidate connects itself; with several, the
  remembered controller wins if exactly one of them is it, otherwise the user
  picks. The remembered controller is still recorded and still badged.
- **Android ≤ 11**: unchanged in spirit, automatic in practice. The remembered
  controller is tried directly (one attempt, ~10 s) before anything is asked of
  the user; only when that fails does the location-permission branch open.

`BoardConnectFlowPolicy` holds this decision; the sheet reads it.

## 1b. Revision 2026-07-25 — connection capacity is no longer probed for

Capacity used to be derived from a post-connect advertising probe, with
"not observed" meaning *exclusive* and "not yet probed" meaning *unknown*. Both
were wrong in practice: Android withholds advertisements from a peer it is
already connected to, so a negative said more about the phone than the board,
and the transient UNKNOWN made send mode, auto-disconnect and the relay offer
behave differently for the first seconds of every connection.

Real controllers are exclusive — Kilter's own app cannot tell an occupied
controller from an absent one either ("Both signals are busy or out of range")
and ships an inactivity auto-disconnect for exactly that reason. So:

- every physical controller starts as `SINGLE`;
- the probe may only ever upgrade it to `MULTIPLE`, and only a positive
  observation is stored;
- the probe never justifies requesting a permission — it runs when scan rights
  are already held, and is skipped otherwise;
- `CAPACITY_UNKNOWN` is gone from the relay availability model.

## 2. Product invariants

- Remember the last successfully connected **physical controller per interactive
  `BoardBrand`**. A CruxRelay endpoint is transient and must never replace it.
- Show the remembered controller before requesting discovery permissions.
- A failed reconnect returns to the same two choices with an error. It does not
  open a permission dialog or start a scan.
- Selecting fresh search stops any existing board scan before starting a new one,
  so stale results cannot satisfy the request.
- Dismissing the picker stops picker-owned discovery while disconnected. It must
  not interrupt an in-flight GATT connect or the post-connect capacity probe.
- A controller address may become stale. The recovery path is an explicit fresh
  search; the next successful physical connection atomically replaces the saved
  descriptor.

## 3. Saved descriptor

DataStore keeps these fields per board family:

- advertised display name;
- advertised serial, including an empty MoonBoard serial;
- Aurora API level (`0` for MoonBoard);
- BLE address;
- `BoardBrand` from the per-brand key namespace.

RSSI, `isCruxRelay`, and `advertisesWhileConnected` are runtime observations and
must not be persisted. An older address-only preference is still valid for the
"last used" scan badge, but is deliberately insufficient for direct reconnect.

## 4. Permission matrix

| Operation | Android 8–9 | Android 10–11 | Android 12+ |
|---|---|---|---|
| Direct reconnect | no runtime permission | no runtime permission | `BLUETOOTH_CONNECT` |
| Fresh BLE search | `ACCESS_COARSE_LOCATION` + location services | `ACCESS_FINE_LOCATION` + location services | `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` |

`BLUETOOTH_SCAN` remains declared `neverForLocation` on Android 12+. The legacy
location prompt exists only inside the fresh-search branch. Reading whether the
system location toggle is enabled is not a location-data access.

## 5. Official-app comparison (verified 2026-07-19)

Static analysis used the then-current Android packages: Kilter 2.4.0 and Moon
Climbing 1.3.56.

- Kilter's own Auto Connect help text instructs the user to tap the lightbulb to
  scan for nearby boards; its app path contains `_initAndScan`, `_startScan`, and
  `_connectToBoardByName`. It automates selection after discovery rather than
  providing a no-scan reconnect path.
- Moon calls `scanForDevices` and tells the user that Bluetooth and location
  access are required to connect. Its manifest also retains coarse/fine location.

CruxCoach intentionally improves on both behaviours by distinguishing discovery
from communication with an already-known controller.

## 6. Acceptance criteria

- With a remembered board on Android 10/11 and location permission denied or
  location services off, tapping **Reconnect** reaches `connectGatt` without a
  runtime permission or location-settings prompt.
- Tapping **Search for boards again** on the same device enters the transparent
  legacy location-permission/location-services flow.
- Android 12+ direct reconnect requests only `BLUETOOTH_CONNECT` when needed.
- Successful relay connections do not alter the remembered physical controller.
- Persistence remains independent per interactive board family.
- Preference and permission decision tables are covered by JVM tests; Android
  compilation and the complete unit suite remain green.
