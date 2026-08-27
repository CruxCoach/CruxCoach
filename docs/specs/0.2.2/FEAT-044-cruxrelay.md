---
status: in-progress
queue: manual
base: 0.2.2
depends_on: [FEAT-023-session-queue, playlist]
created: 2026-07-07
---

# Feature Spec: CruxRelay — transparent board relay for official-app users

> **Status:** core logic implemented + unit-tested; BLE glue + on-device
> validation owed (see §9, §10).
> **Relates to:** the session-queue / playlist subsystem (SessionQueueManager,
> SessionGattBridge, ClimbBleAdvertiser) — CruxRelay is a *third* participant
> transport alongside the existing CruxCoach-GATT session transport.

## 1. Overview

CruxCoach holds the ONE BLE connection a Kilter/Aurora board allows and
additionally presents itself as a **transparent relay** so that people running
the **official Kilter app** (who will never install CruxCoach) can send climbs
through it. This gives the CruxCoach user awareness of what is on the board and
removes the "only one device may connect" contention.

**Transparency is a hard requirement, not a nice-to-have.** The relay is
advertised under a clearly CruxCoach-branded name (like BoardSesh's "party
mode"), never impersonating the board or the Kilter brand. Faithful
pass-through is mandatory: the relay NEVER alters the holds it forwards.

**One relay behaviour — faithful pass-through (turn-taking).** A completed climb
from an official-app user is forwarded byte-for-byte to the real board. The
board is one shared LED surface, so "last write wins" is the natural model: the
official-app user's climb lights up (their app's mental model — send → light →
climb — always holds), CruxCoach's own playlist playback also writes to the same
board, and whoever writes last owns it. This is why there is NO diverting
"playlist mode" and NO board-feedback/anti-flood machinery — nothing is ever
withheld from the board, so a user never sees a dark board and never retries.

**Optional playlist capture (a toggle, not a second send behaviour).** When
enabled, a relayed climb is ALSO appended (silently, host-local) to the
CruxCoach playlist so the host can collect what people climb — WITHOUT diverting
it (the board already showed it). Deduped by frames hash so re-sends don't pile
up duplicates.

**Goals:**
- Official-app users drive the shared board through CruxCoach, transparently,
  with the single-connection contention gone.
- Faithful, unaltered LED pass-through (safety + trust).
- Optional, non-disruptive collection of relayed climbs into the playlist.
- Graceful hand-back of the real board when the CruxCoach host leaves.

**Non-goals (v1):**
- No LED→climb reverse decoding: relayed raw frames have no UUID/name/grade;
  captured items get a generic label and the host's current angle.
- Captured raw items are host-local (not re-shared to CruxCoach GATT
  participants), like `restAfterSeconds`.
- Relay + a hosted CruxCoach session COEXIST (they serve the two different
  populations of the same board — see §11), they are not mutually exclusive.

## 2. Research basis (2026-07-07, CONFIRMED)

Interoperability testing plus adversarial verification established, at high
confidence (device test still owed):

- **Discovery keys on the advertising service UUID
  `4488B571-7806-4DF6-BCFF-A2897E4953FF`, not on the device name.** The name is
  therefore free, and a clearly branded "CruxCoach" name works — which
  dissolves the transparency-vs-discovery tension in our favour. (This UUID is
  visible to any BLE scanner and already appears in our own source; it is
  recorded here because our advertiser must set it, not as a finding about
  anyone else's software.)
- The board GATT is **write-only** NUS: service `6E400001`, write char
  `6E400002` (WRITE | WRITE_NO_RESPONSE); there is **no** `6E400003` notify/read.
- One Android app can concurrently run a NUS GATT server + advertise + hold the
  board central link (verified against CruxCoach's own SessionGattServer +
  ClimbBleAdvertiser + BoardBleConnection running together).
- **Trademark:** "KILTER" is a registered mark (USPTO 4231332); Kilter enforced
  it against Aurora (C&D, Mar-2026). => lead with the CruxCoach app brand + an
  explicit non-affiliation disclaimer; never advertise a bare product name.

## 3. Advertised name

Listing needs only the 4488B571 service UUID, so:

- **Primary (transparency-first):** `CruxCoach·<Product>@<apiLevel>`, e.g.
  `CruxCoach·Kilter Board@3`, derived from the real connected board's product
  words + its real apiLevel (`@3` preserved so the app applies V2 LED-kit
  behaviour, no spurious V1 auto-disconnect). Serial dropped.
- **Byte budget (binding):** a connectable legacy ADV_IND is 31 B; Flags (3 B)
  + the 128-bit UUID (18 B) leave no room for the name. => **UUID in ADV_IND,
  name in SCAN_RESPONSE**, name ≤ ~29 chars.
- **Fallback (compatibility-first):** marker in the free-form serial, product +
  `@apiLevel` pristine: `<Product>#CR-<serial>@<apiLevel>` (e.g. `Kilter Board#CR1@3`).
  Use only if the device test shows the transparent name isn't listed.
- **Mechanism:** there is no per-advertiser local-name API →
  `BluetoothAdapter.setName(desired)` (GLOBAL, persistent, racy) +
  `setIncludeDeviceName(true)` in the SCAN_RESPONSE. Snapshot the original name
  and synchronously persist a `relay_name_dirty` flag before changing it. A
  missing/blank original name aborts relay startup. Restore clears the record
  only after the adapter reports the original name; adapter-off, permission,
  service, or `setName` failures retain it and retry on `STATE_ON`/next launch.
  `cruxrelay.xml` is excluded from cloud backup and device transfer so one
  phone's recovery record can never rename another phone.

## 4. Relay GATT server + reassembly

- Expose service `6E400001`, single characteristic `6E400002` =
  `PROPERTY_WRITE | PROPERTY_WRITE_NO_RESPONSE`, `PERMISSION_WRITE`; no
  notify/read/CCCD (mirrors the write-only board). `sendResponse(GATT_SUCCESS)`
  when `responseNeeded`.
- Inbound writes are NOT packet-aligned. A **per-client** `RelayFrameReassembler`
  (keyed by device address) accumulates bytes, splits on the Aurora framing
  `0x01 <dataLen> <checksum> 0x02 <type> …holdData… 0x03`, groups packets into a
  COMPLETE climb (`ONLY`, or `FIRST (MIDDLE)* LAST`). The relay acts only on a
  complete climb, so two clients' interleaved writes can never corrupt each
  other's climb.
- **Faithful-pass-through invariant:** holdData is never modified; the relay
  only re-frames BLE chunks (re-chunk the reassembled stream at
  `BoardPacketEncoder.BLE_MTU`=20 for the board). Forwarded bytes are
  byte-identical to received bytes.

## 5. Behaviour: pass-through + optional capture

- **Pass-through (always):** a completed climb → `BoardBleConnection.sendRawChunks`
  (proven verbatim pass-through) under an owner-lease FIFO so whole climbs never
  interleave (the single `writeMutex` already serialises writes; the lease keeps
  a climb's chunks contiguous). Last write wins — official-app sends, the host's
  own sends, and playlist playback all just write; the board shows the latest.
- **STATUS (0.2.2): capture is DEFERRED** — the checkbox was removed for the
  release; only the dormant manager internals ship (raw items carry no uuid, so
  playlist collection needs real design work first).
- **Capture (toggle, off by default):** if `captureToPlaylist` is on, the same
  completed climb is also appended to the playlist as a host-local raw item,
  deduped by `(clientAddress, framesHash)` within a short window so a re-send of
  the same climb doesn't add a duplicate. No board diversion, no acknowledgement
  flash — the board already lit the climb.

## 6. Capture dedup

`RelayCaptureDedup` (pure): key `(clientAddress, framesHash)`; a matching climb
within `CAPTURE_DEDUP_WINDOW` (~30 s, sliding) is a re-send → not appended again.
`framesHash` is the reassembler's 64-bit hash over ordered holdData
(re-chunk insensitive, hold-change sensitive). A global relay-added cap (~50)
bounds a runaway session. That is the whole anti-duplication story — the old
diverting-mode's ACK flash + token bucket are gone (there is no dark board to
explain, so no retry storm to suppress).

## 7. Host leaves the board

Order of operations (release the real board FIRST, tear down the relay SECOND):

1. **Drain:** stop acting on new inbound writes, clear per-client reassembly +
   capture-dedup state.
2. **Release the real board:** `BoardBleConnection.disconnect()` and wait
   (bounded, ≤ the existing 5 s force-close) for `DISCONNECTED`, so the physical
   board re-enters advertising. Clear `suppressAutoDisconnect`.
3. **Tear down the relay:** stop the GATT server + advertising → drops the
   official-app clients. The real board is already advertising, so their app's
   own reconnect/scan re-lists and connects to it. (Mirrors
   `SessionGattBridge.stopSharing`: board-disconnect `:261-263` before
   `gattServer.stop :270`.)
4. **Restore** `BluetoothAdapter.setName(original)`, clear `relay_name_dirty`.

**NUS clients do NOT migrate** — the transparent name deliberately differs from
the real board, so the user re-selects the (now-free) real board. CruxCoach
participants retain the existing staggered host migration, with its explicit
membership boundary: an unsigned nearby successor is offered by name but is
never joined until the user approves that exact still-live advertisement.

## 8. Liveness / fail-safe

- `WAIT_BEFORE_ADVERTISE`: advertise only once the board central link is
  CONNECTED; drop the relay if that link falls (never let a client attach to a
  dead relay).
- The relay holds its own board keep-alive owner for its lifetime. A monotonic
  watchdog auto-disables it after exactly 90 s with no client and no activity.
  A positive client count suspends expiry; the last client's departure starts
  a complete new window. Stop/abort cancels the watchdog and a timeout leaves a
  final user-visible notification before transport teardown.
- Run the relay under a `dataSync` foreground service (Android 12+ throttles
  background advertising).
- Crash-safe adapter-name restore on next launch and Bluetooth `STATE_ON` via
  `relay_name_dirty`; failed/unverified restores retain the record.

## 9. Integration seams (0.2.2)

- Pure logic (implemented + tested): `RelayFrameReassembler` (framing → complete
  climb + re-chunk + framesHash), `RelayBoardName` (transparent name from the
  real board name + byte budget), `RelayCaptureDedup` (framesHash window + cap).
- NEW `RelayGattServer` ← mirror `SessionGattServer.kt` but service `6E400001`,
  one write-only char; per-client `RelayFrameReassembler`; emits complete climbs.
- NEW `CruxRelayManager` ← mirror `SessionGattBridge.kt`: advertising (name
  mirror + `setName` snapshot/restore) + board-write pass-through + optional
  capture + host-leave ordering + watchdog + `WAIT_BEFORE_ADVERTISE`.
- Advertiser: `ClimbBleAdvertiser` relay path (`addServiceUuid(4488B571)`, name
  in scan-response).
- Board pass-through: `BoardBleConnection.sendRawChunks` (`:651`);
  `connectedBoardName` (`:81`) + `suppressAutoDisconnect` (`:117`).
- Capture (DEFERRED past 0.2.2 — see §5; no UI ships): `QueueItem`
  (`SessionQueueProtocol.kt:318`) gains an optional raw
  payload; `SessionQueueManager.addRawClimb` + a raw branch in
  `sendCurrentClimbToBoard` modelled on the MoonBoard branch; raw items are
  host-local (not wire-encoded).
- Prefs: `UserPreferences` `relayEnabled` + `relayCaptureToPlaylist`.
- DI: register in `AppModule.kt` beside `provideSessionGattServer`/`Bridge`.
- Manifest already has `foregroundServiceType=dataSync` + BLUETOOTH perms.

## 10. Acceptance / validation (all ON-DEVICE — owed)

Unit-testable core (this phase): reassembly of ONLY / FIRST-MIDDLE-LAST streams,
partial-write accumulation, cross-client non-interleaving, framesHash stability
under re-chunking + sensitivity to hold change, transparent-name byte budget,
capture dedup window + cap.

Device tests (owed, hardware = official app + real board):
1. **Listing gate (gates the name UX, ~5 min, do FIRST):** advertise 4488B571
   with a non-Kilter name behind a bare 6E400001/writable-6E400002 server → does
   the official app list + connect?
2. Transparent name displays acceptably; ≤29-char name doesn't trip
   `ADVERTISE_FAILED_DATA_TOO_LARGE` (Samsung + Pixel).
3. Pass-through byte-fidelity incl. >84-hold FIRST/MIDDLE/LAST split; turn-taking
   between two official-app phones + playlist playback overwriting naturally.
4. Official app write type (with/without response) accepted + forwarded; MTU
   negotiation vs the 20 B re-chunk.
5. Capture: re-sends of the same climb don't duplicate the playlist entry;
   distinct climbs each land once; cap holds.
6. V1 vs V2 via `@apiLevel` mirroring.
7. Host-leave handoff + crash-safe adapter-name restore (incl. forced kill).
8. `WAIT_BEFORE_ADVERTISE`; concurrency soak Android 9–11; FGS keeps advertising
   alive on 12+.
9. Trademark/legal review of the final advertised name + in-app non-affiliation
   disclaimer before release.

## 11. Two populations & coexistence (CruxCoach vs official-app users)

One CruxCoach user who holds the board is the sole board owner and serves BOTH
populations at once — they are complementary, not exclusive:

- **Official-app users** → connect via CruxRelay (board emulation, NUS). They
  see "a board", send climbs, get LED feedback; anonymous (BLE address only),
  unaware they are relayed.
- **CruxCoach users** → JOIN the host's session/playlist (the existing Nearby +
  SessionGattBridge path). Rich: identities, shared queue, host migration.
- **One board, last-write-wins.** Every writer — session playback, host sends,
  relay forwards — goes through `BoardBleConnection`'s `writeMutex`, which
  already serialises them; whole climbs stay atomic.

Coexistence rules:
- **Relay-on implies a joinable session.** Because the host monopolises the
  single board connection, a nearby CruxCoach user cannot reach the real board
  directly — their only way in is to join. So enabling the relay must also
  expose the host's session as joinable, otherwise CruxCoach users are locked
  out of a board the host is holding.
- **CruxCoach shows a relay host as a JOIN entry, not a board — not hidden.**
  CruxCoach recognises its own relays by the transparent-name marker
  (`RelayBoardName.isRelayName`, "CruxCoach…" prefix; legacy "CruxRelay…"
  remains accepted) and correlates them with
  the host's "CRUX" session advertisement. In the connection picker it renders
  ONE entry — "Playlist von \<host\> — beitreten" — and a tap routes to the
  session/playlist join flow, never a board connect. The relay's presence IS
  the discovery beacon, repurposed for CruxCoach users as a join.
- **Board-owner coordination.** Session and relay must not fight over the board
  lifecycle: `suppressAutoDisconnect` becomes a refcount (either feature keeps
  the board up while it wants it), and only the host leaving tears the board
  down (§7 ordering). `writeMutex` already arbitrates the writes.

Owed (design captured, not yet built): the relay-on→joinable-session coupling,
and the picker's merged join entry + tap routing. `RelayBoardName.isRelayName` +
`DiscoveredBoard.isCruxRelay` tagging (so a relay is never offered as a
connectable board) and the board-owner keep-alive refcount landed.

## 12. UI/UX requirements

**Hosting follows the board link.** *(revised 2026-08-23; see below for what
this replaced and why.)*
- Sharing is on by default and starts when a board connects, stops when it
  disconnects. It is still not scoped to the Nostr identity and still not a
  per-session toggle the user has to find: `CruxRelayManager` keeps an
  in-memory enabled flag, `WAIT_BEFORE_ADVERTISE` stays, and the standing
  choice lives in the app-scoped `relay_manual_start_v022` preference.
- Guest connections do not own the relay lifecycle. Sharing remains available
  with zero guests for as long as the host keeps the physical board connected;
  guests may join, leave and rejoin without restarting the relay.
- Settings offers an explicit **manual start** opt-in. It is off by default;
  enabling it restores the old behaviour where the connection sheet's action
  must be used for each board connection.
- The active surfaces name the physical board (including its serial where the
  controller advertises one), so sharing can never look destination-less.

*Superseded:* this section originally read "Hosting is a momentary action, NOT
a persisted setting… nothing re-activates sharing on a later board connection
without a fresh user action", on the reasoning that fronting a board is
safety-relevant and should always be a deliberate act. In a gym that produced a
control nobody found: other apps in the room could not send, and the person
holding the board had no idea why. Tying sharing to the connection keeps the
safety property that mattered — the phone is never a board while nobody is
connected to one — without requiring the tap.
- The action lives on the board/connection screen, visible only while connected
  to a real board; labelled around "sharing this board", never "relay".

**Disclosure (mandatory) — the phone's GLOBAL Bluetooth name changes.**
- Before the first share, a one-time explainer dialog: while sharing, the phone
  appears to other Bluetooth devices as "CruxCoach…", restored on stop. Persist
  "explainer seen" (app-scoped, not identity-scoped).
- Include a short non-affiliation disclaimer (not affiliated with Kilter/Aurora;
  board names are compatibility references only).
- The manager owns this gate. Automatic connection-following, the manual
  button, and advertising-permission retry all call the same request API; no
  caller can enable the transport directly. The app-global dialog is available
  even when the connection sheet is closed, and its answer is bound to the
  exact connected board address so consent for one wall cannot enable a
  replacement connection.

**Persistent host status + one-tap stop + foreground service.**
- While sharing: an in-app status surface (banner/chip) AND a `dataSync`
  foreground-service notification — "Board wird geteilt · N verbunden" — with a
  one-tap stop that runs the §7 host-leave ordering. The FGS keeps advertising
  alive on Android 12+.

**CruxCoach users see a relay host as a JOIN entry (not a board, not hidden).**
- In the connection picker, a `DiscoveredBoard.isCruxRelay` entry renders as
  "Session/Playlist von \<host\> — beitreten", visually distinct from real
  boards; the tap routes to the existing session/playlist join, NEVER
  `BoardBleConnection.connect`.
- Enabling sharing must also expose the host's session as joinable (relay-on ⟹
  joinable session) so the join entry never dead-ends.

**Capture is an inline option, not a second buried pref** — a checkbox on the
sharing surface ("Climbs anderer in Playlist sammeln"), off by default.

**Permissions + errors** — request `BLUETOOTH_ADVERTISE`/`CONNECT` on first
share; surface advertise/`setName` failures + board loss with a clear message,
never silence. Strings in EN + DE together.
