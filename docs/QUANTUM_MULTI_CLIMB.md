# Board layers and Quantum multi-climb

## Product contract

CruxCoach models simultaneous board projections as **board layers**, not as a
Quantum-only UI trick. `BoardBrand.maxSimultaneousClimbs` and
`supportsIndependentClimbLayers` are the capability boundary. Quantum is the
first adapter with four independent layers; every existing board remains at
one layer and keeps its existing wire format and send semantics.

An explicit user action adds the current climb to the next free layer, or
replaces the selected installation-owned layer. Automatic page changes and
route playback always reuse primary layer 1, so browsing cannot silently fill
the controller. Removing a layer sends `TURN_OFF_USER` for that layer's UUID;
normal switching never sends `TURN_OFF_ALL` and therefore cannot erase another
climber's projection.

## Identity and colour

The app creates one random installation UUID and derives four stable UUIDv4-shaped
slot identities from it with SHA-256. The seed is stored in app-private SharedPreferences.
It is deliberately unrelated to a CruxCoach/Nostr/vendor account and is never
sent anywhere except the locally connected board. Stable identities let a
restarted app recognise and remove its own retained controller entries.

Each layer has an RGB colour from a high-contrast palette. Colours already
reported by the controller—including other apps' users—are unavailable. The
UI can replace the colour on its selected slot. Protocol RGB is always the low
24 bits; Compose keeps the ordinary ARGB representation.

## Controller truth and failure handling

`fff1` notifications are reassembled and decoded with strict device address,
length, maximum-player and CRC checks. Responses to `ACTIVATE_WALL`,
`BOARD_SWIPE` and `REQUEST_USER_ROUTE_LIST` carry zero to four 37-byte player
records (route UUID, user UUID, remaining seconds, RGB). Controller exception
codes 5–11 and 254 map to distinct UI errors.

Projection is a conservative transaction:

1. `TURN_OFF_USER(ownSlotUuid)`
2. `ACTIVATE_WALL(route, ownSlotUuid, colour, diodes)` in 92-diode logical
   chunks and 15-byte Android writes
3. `REQUEST_USER_ROUTE_LIST`
4. success only after an authoritative snapshot contains the exact
   route/user/colour tuple

The intermediate off snapshot preserves the local `SENDING` placeholder. A
timeout or exception becomes `FAILED`; it is never presented as controller-
confirmed. A successful `TURN_OFF_ALL` from the separate clear-board action
also updates local truth, even if firmware omits the notification.

## Conflict and overlay UX

The detail screen shows a four-position rack with route name, state, colour,
replace/remove actions, controller occupancy and foreign-player count. Known
hold overlap is rejected before BLE because Quantum cannot assign two user
colours to one diode. Unknown external layers remain controller-authoritative
and may still produce the specific firmware spot-conflict response.

On the board image, a hold belonging to multiple local preview layers is drawn
as adjacent ring segments rather than a blended colour. This preserves every
layer's identity and makes the conflict legible. Layer numbers in the rack are
the redundant cue for users who cannot distinguish a colour pair.

## Extension path for other boards

A future adapter opts into more than one layer through `BoardBrand`, then
implements independent identity/removal/confirmation in its transport. The
UI and layer state do not depend on Quantum opcodes. Boards without these
capabilities do not render the rack and continue through the legacy single-
projection path byte-for-byte.

BoardCell/mesh still carries one canonical `BoardProjection`; multi-layer
ownership is intentionally local to a direct physical controller in this
version. Extending the signed BoardCell wire model needs an explicit protocol
version and mixed-client rollout rather than silently changing its existing
serialization.

## Verification status

The packet layouts and parser match the clean-room eWalls 2.0.14 analysis and
BoardSimulator. Real Quantum hardware behaviour remains `hardware_verified:
false` until the same four-user, conflict, reconnect and multi-chunk suite is
captured against each controller generation. In particular, `BOARD_SWIPE`
remains unused until its atomic semantics are hardware-verified.
