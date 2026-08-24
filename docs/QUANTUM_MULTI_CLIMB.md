# Board layers and Quantum multi-climb

## Product contract

CruxCoach models simultaneous board projections as **board layers**, not as a
Quantum-only UI trick. `BoardBrand.maxSimultaneousClimbs` and
`supportsIndependentClimbLayers` are the capability boundary. Quantum is the
first adapter with four independent layers; every existing board remains at
one layer and keeps its existing wire format and send semantics.

The rack has four local staging slots. Assigning the current climb only changes
the preview; it performs no BLE write. Each slot has its own lamp button and a
separate **send all** lamp transmits every staged slot sequentially. Opening,
swiping or reconnecting on a detail page never sends. The legacy automatic mode
is limited to explicit playlist/playback progression outside detail browsing.

Removing an unsent preview is local-only. Removing a physically active layer
sends `TURN_OFF_USER` for that installation-owned UUID. Normal switching never
sends `TURN_OFF_ALL` and therefore cannot erase another climber's projection.
Before the scoped removal, CruxCoach refreshes controller truth; it writes
nothing when that UUID is already absent, and confirms removal only from a new
complete snapshot. `TURN_OFF_ALL` remains confined to the separate, explicitly
named clear-entire-wall API, whose global effect is intentionally not a rack
management operation. Generic clear and Kilter animation cleanup refuse to run
on Quantum; every animation frame and its final clear are also fenced to the
board brand on which the animation began.

## Identity and colour

The app creates one random installation UUID and derives four stable UUIDv4-shaped
slot identities from it with SHA-256. The seed is stored in app-private SharedPreferences.
It is deliberately unrelated to a CruxCoach/Nostr/vendor account and is never
sent anywhere except the locally connected board. Stable identities let a
restarted app recognise and remove its own retained controller entries.
The protocol's all-zero anonymous user sentinel is never accepted as a layer
identity or removal target because it is not installation-owned and may be
shared by another client.

The selectable palette is exactly the four unique controller colours produced
by eWalls 2.0.14 after its UI colours are normalized for BLE: green `#00ff00`,
cyan `#00ffff`, magenta `#ff00ff` and yellow `#ffff00`. (Its six visual swatches
collapse to these four protocol colours.) Colours already reported by the
controller—including other apps' users—are unavailable. Protocol RGB is always
the low 24 bits; Compose keeps the ordinary ARGB representation.

## Controller truth and failure handling

Quantum setup is ordered as one GATT transaction: service discovery and MTU
negotiation finish first, local `fff1` notification routing must enable, and the
`fff1` CCCD write must complete successfully before the connection becomes
writable. CruxCoach then reads eWalls' current-state characteristic at `fff4`.
A structurally valid complete route list (or board-cleared record) is useful as
observed state for the rack; a delta, informational record, malformed value,
unavailable characteristic or failed read falls back to
`REQUEST_USER_ROUTE_LIST`. While the direct Quantum link remains connected this
observational refresh repeats every ten seconds. Because hardware evidence has
not established whether `fff4` is fresh or a cached last event, every mutation
independently resets notification recovery and requires an explicit
`REQUEST_USER_ROUTE_LIST` round trip under the BLE write lock.

`fff1` notifications are reassembled and decoded with strict device address,
exact payload length and maximum-player checks. Unlike fff2 commands, the
eWalls 2.0.14 fff1/fff4 broadcast contract carries no CRC. Responses to
`ACTIVATE_WALL`, `BOARD_SWIPE` and `REQUEST_USER_ROUTE_LIST` carry zero to four
37-byte player records (route UUID, user UUID, remaining seconds, RGB).
Controller exception codes 5–11 and 254 map to distinct UI errors. Delta
acknowledgements advance the event revision but never satisfy a fresh,
authoritative-read precondition. The fixed reserved header byte is validated.
A frame recovered across notification callbacks is trusted only inside the
freshly reset explicit-request generation; a timed-out read/request retires the
uncorrelatable GATT so its late callback cannot confirm a later operation.

Projection is a conservative transaction:

1. `TURN_OFF_USER(ownSlotUuid)`
2. `ACTIVATE_WALL(route, ownSlotUuid, colour, diodes)` for at most 92 diodes,
   fragmented into 15-byte Android writes
3. `REQUEST_USER_ROUTE_LIST`
4. success only after an authoritative snapshot contains the exact
   route/user/colour tuple

Every climb hold must resolve to a diode before the first Quantum command.
Unlike route/user/colour, diode membership is absent from controller readback;
silently dropping one unmapped hold could otherwise produce a partial wall
while the app reports the route as confirmed. Other board families retain their
existing partial-map warning behavior.

The diode plan also carries the physical controller identity and Quantum model
that produced its LED map. Both detail and playlist capture that binding before
loading geometry, revalidate after disk/controller refresh, and the transport
checks the physical identity again under its write mutex. A disconnect/reconnect
to another Quantum controller therefore cannot receive the previous board's
plan even when both controller snapshots happen to be empty.

Although the recovered encoder can split a route across multiple 92-diode
activation commands, route-list readback proves only route/user/colour, not that
every diode chunk appended atomically. Routes above that bound therefore fail
closed until the multi-frame behavior is captured on hardware.

The intermediate off snapshot preserves the local `SENDING` placeholder. A
timeout or exception becomes `FAILED`; it is never presented as controller-
confirmed. The separate clear-entire-wall action likewise requires controller
readback (an authoritative empty snapshot); successful BLE transport alone no
longer clears local truth optimistically.

## Catalogue identity and offline migration

Quantum's app climb UUID is not the controller route UUID. The local catalogue
therefore stores an authoritative `(app UUID, route UUID, model)` bridge plus
route metadata. Reverse lookup hydrates a foreign controller route to its known
climb name and hold set; known holds participate in overlap checks. Missing,
blank, malformed, wrong-model, or otherwise unresolved geometry remains
unknown and blocks another projection conservatively.

Local share keeps two permanent compatibility views. A v1 receiver receives
the same Quantum-free legacy logical artifact at `/board.db.gz`; it never sees
Quantum geometry, bridge rows, or route metadata. A v2 receiver prefers the
full `/v2/board.db.gz` artifact and falls back to v1 only when the v2 endpoint
is genuinely absent, never after a corrupt or mismatched v2 response. Generic
catalogue rows, geometry that exists in the peer schema, route references, and
metadata validate before writing and commit in one SQLite transaction. A
mapping conflict or malformed official Quantum row leaves no partial generic
rows behind.

Pre-0.2.2 modern databases are also accepted. Additive geometry tables may be
missing, and geometry without a `board_brand` column is interpreted as Kilter;
present tables still have their required columns probed before any write.
Backup format 3 already carries the additive brand/layout fields used by
Quantum ascents, bids, own climbs, and own-climb stats, so 0.2.1 restores and
current round trips require no format bump.

## Conflict and overlay UX

The detail screen shows a four-position rack with route name, state, colour,
replace/remove actions, a lamp per slot, a send-all lamp, physical controller
occupancy and foreign-player count. Foreign users are displayed separately in
their controller-reported colours and are read-only. Local staging remains
available even when foreign users fill the controller; the send preflight then
refuses to exceed four physical players instead of evicting one.

Known hold overlap is rejected before BLE because Quantum cannot assign two
user colours to one diode. Send-all also validates capacity and overlap before
the first write, avoiding a half-applied rack. A controller route whose holds
cannot be resolved against the local model catalogue is treated as unknown and
blocks another projection conservatively; it is never treated as an empty,
conflict-free climb. A matching route UUID whose frames are blank or malformed
also remains unknown: catalogue identity alone is not proof of usable geometry.

On the board image, a hold belonging to multiple local preview layers is drawn
as adjacent ring segments rather than a blended colour. This preserves every
layer's identity and makes the conflict legible. Layer numbers in the rack are
the redundant cue for users who cannot distinguish a colour pair.

The saved/running playlist remains local and non-joinable. It may use these
same rack controls only while no shared session owns the wall; it does not make
multi-layer state discoverable to another phone. A staged replacement can be
discarded independently, preserving the previously confirmed live route, while
physical removal remains an explicit `TURN_OFF_USER` action.

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

CruxRelay is likewise unavailable for Quantum in this version. Its current
transparent transport forwards Aurora/Kilter packet streams and cannot carry a
scoped Quantum route/user transaction or authoritative player-list readback.
Both the relay capability gate and the final raw-write call are brand-fenced so
an external app cannot bypass foreign-user coexistence through `fff2`.

The climb editor's generic live LED mirror and the global role-colour resend
do not write to Quantum. Those legacy paths have no explicit rack slot and no
owned route/user tuple, so using the protocol's anonymous sentinel could
replace another client's projection. A saved Quantum climb is staged and sent
through the scoped layer controls on detail instead.

## Verification status

The packet layouts, ordered `fff1` subscription, `fff4` read path and parser
match the clean-room eWalls 2.0.14 analysis and BoardSimulator. Real Quantum
hardware behaviour remains `hardware_verified: false` until the same four-user,
conflict, reconnect, CCCD/readback and multi-chunk suite is captured against
each controller generation. The implementation therefore accepts only strict
known broadcast shapes, falls back to a route-list command when `fff4` does not
prove a complete snapshot, and fails closed when notification setup cannot be
confirmed. In particular, `BOARD_SWIPE` remains unused until its atomic
semantics are hardware-verified.

The remaining hardware matrix also includes whether `fff4` is a fresh snapshot
or a cached last event, notify-versus-indicate behavior across controller
generations, `TURN_OFF_USER` to activation timing, atomic append/failure
semantics for routes above 92 diodes, no-response write callback behavior,
read-after-write visibility, and multi-client interleaving/polling impact.
