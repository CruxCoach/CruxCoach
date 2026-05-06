---
status: backlog
---
# Feature Spec: CruxCoach Controller — ESP32 Hardware Companion (backlog)

> **Status:** Backlog — captured 2026-05-06. No release target. This
> spec documents direction + feature catalogue so future versions
> can pull pieces forward when team capacity allows. Hardware specs
> need a build-and-validate pass before any of this is shippable.
>
> **Reference architecture:** BoardSesh's "Gizmo" (open-source ESP32
> firmware, see `~/boardsesh/embedded/projects/board-controller/`)
> implements the BLE-proxy core idea CruxCoach would build on.
> CruxCoach's variant differs in that the backend is **Nostr** (not
> a centralised GraphQL-WS server), so the controller talks to
> relays directly and has no per-deployment account-server
> dependency.
>
> **Relates to:**
> - FEAT-009 (Difficulty Rating Engine) — controller can act as a
>   live aggregator + display surface for Kind-30079 votes.
> - FEAT-008 (Kilter Own-Climb Import) — controller surfaces
>   imported climbs alongside CruxCoach-native ones.
> - FEAT-003 (Climb Creator) — controller's "set climb on board"
>   path is the same encode pipeline.
>
> **Depends on (when implemented):**
> - Stable Aurora-protocol encoder in CruxCoach (`BoardPacketEncoder`)
>   — already shipped, exact same frames the controller forwards.
> - Nostr relay client implementation lightweight enough for ESP32
>   (no Quartz on Android; need a C++ NIP-01 minimal client).
> - Public hardware reference design (PCB or off-the-shelf
>   dev-board recipe) + 3D-printable enclosure.

---

## 1. Overview

### 1.1 Problem

A Kilter Board's BLE GATT server admits **one central connection at
a time**. In a gym setting where multiple climbers want to send
their own projects to the same board, only one phone can be
connected — everyone else waits, or shares a single phone, or
gives up. The official Kilter app doesn't solve this; BoardSesh's
Gizmo does, by interposing an ESP32 between phones and the board:

```
        phone A ─┐
        phone B ─┤  BLE (Aurora-protocol)
        phone C ─┼────→ Gizmo (BLE peripheral
                 │        + BLE central)
        web UI ──┘            │
                              │ BLE (single Aurora connection)
                              ↓
                          Kilter Board
```

CruxCoach can ship its own equivalent that:

1. Multiplexes BLE access (the BoardSesh-Gizmo core).
2. Subscribes to Nostr Kind-30078 climb events instead of a
   centralised backend, keeping the controller in line with
   CruxCoach's "no account, no server" philosophy.
3. Adds CruxCoach-specific surfaces (live difficulty voting on
   the board's display, trainer/coach session orchestration via
   Nostr, Lightning tips to setters, etc.).

### 1.2 Why CruxCoach builds it (vs. just supporting BoardSesh's)

| Question | Answer |
|---|---|
| Does CruxCoach need to make hardware to gain Gizmo benefits? | No — a CruxCoach phone client speaking Aurora-BLE finds a Gizmo just like a real board. |
| What does our own controller buy us? | Backend independence: Gizmo's GraphQL-WS expects BoardSesh accounts. Our controller speaks Nostr — works for any pubkey, no gatekeeper. |
| Could we fork BoardSesh firmware? | Possibly. License-permitting. The BLE-proxy + WiFi config + LED layers are reusable; the backend integration is what we'd swap. |
| Could we ship just a firmware-fork, no hardware? | Yes — that's the lightest path. Users buy off-the-shelf ESP32 + LED setup, flash our firmware. Or a pre-built unit via cruxcoach.org store. |

### 1.3 Goals

The controller has TWO clearly-separated tiers of functionality:

**Tier 1 — Offline core (works without any internet, mirrors the
"offline-first" stance of the CruxCoach app itself):**

- **Multi-phone access** to a single Aurora-protocol board: 4-10
  simultaneous BLE clients seamlessly multiplexed onto one board.
  Pure local-network operation, no relay or backend involved.
- **Configurable via captive portal** on the controller's WiFi-AP
  fallback (no upstream internet required for setup).
- **Time-out / cooldown** to free the board for the next user.

If you turn off your router and unplug the gym's internet, Tier 1
still works — same as how the CruxCoach app itself keeps working
on a phone in airplane mode.

**Tier 2 — Online additive (only active when the controller has
WiFi+internet, never required for Tier 1):**

- **Nostr-relay subscription** to Kind-30078 / Kind-30079 / Kind-30080
  events on the user's configured relays — no CruxCoach-operated
  server in the path. When relays are reachable, the controller
  picks up community climb-shares, grade votes, and trainer-mode
  queues. When they aren't, those features simply don't update;
  the BLE multiplexing keeps working.
- **OTA firmware updates** (polled from Codeberg-Releases).
- **Lightning zap visuals** via NIP-57 when relays + LN endpoints
  are reachable.

The reason the controller speaks Nostr at all (rather than being
purely offline) is to enable decentralised features without a
CruxCoach-operated backend — same architectural stance the phone
app takes. Internet-dependent features are explicitly opt-in and
clearly labelled.

**Other goals:**

- **Open-source firmware** distributed via Zapstore, Codeberg, or
  similar.
- **Optional pre-built hardware** via cruxcoach.org for users who
  don't want to source/flash themselves.

### 1.4 Non-Goals

- Replicate every BoardSesh Gizmo feature one-to-one.
- Standalone climbing app on the controller (use the phone-side
  CruxCoach app for everything that's not a board-side display).
- Custom ASIC / FPGA — commodity ESP32 only.
- Multi-board switching from a single controller (1 controller per
  board; multi-board gym → multiple controllers).
- Cloud-locked features. If it doesn't work without our infra, it
  doesn't ship.
- Proprietary protocols. Aurora frames in, Aurora frames out;
  Nostr events sideways.

---

## 2. Hardware

### 2.1 Reference design (initial guess)

| Component | Choice |
|---|---|
| MCU | ESP32-S3 (BLE 5.0 + WiFi + USB-OTG, ~$3-5) |
| LED chain pass-through | None for v1 — controller doesn't drive its own LEDs, just forwards BLE |
| Display (optional) | Waveshare 1.69" round LCD (240×280, capacitive touch, ~$15) |
| Input (optional) | Rotary encoder for queue navigation |
| Power | USB-C, 5 V from board's existing wiring or wall adapter |
| Enclosure | 3D-print, ~$3 of PETG |
| Estimated BOM (display variant) | $25 |
| Estimated BOM (headless) | $10 |

ESP32-C6 (BLE 5.4, WiFi 6, ~$5) is a candidate for v2 if BLE
multi-central performance turns out to need newer silicon.

### 2.2 Distribution

- **DIY**: open hardware + open firmware, parts list + flash
  instructions in `docs/controller/build.md`. Self-source from
  AliExpress / Mouser / local maker shops.
- **Pre-built (post-launch)**: optionally sold via cruxcoach.org
  store or fulfilled through a regional partner. Funds the
  project. Pricing target: $40 headless, $80 with display.

---

## 3. Software Architecture

### 3.1 Layer diagram

```
┌────────────────────────────────────────────────────────────┐
│  Controller firmware (ESP32, Arduino + PlatformIO + C++)   │
├────────────────────────────────────────────────────────────┤
│  HTTP web config server (settings UI on captive portal)    │
├────────────────────────────────────────────────────────────┤
│  Nostr client (NIP-01, NIP-10, NIP-78) — subscribes to     │
│  Kind-30078 climb defs, Kind-30079 grade votes, optional   │
│  Kind-1 session-broadcast events                           │
├────────────────────────────────────────────────────────────┤
│  BLE peripheral (Aurora-protocol-compatible GATT server)   │
│  ↑ multiple clients (phones, browsers, IoT)                │
├────────────────────────────────────────────────────────────┤
│  BLE central (one connection to the real board)            │
│  ↓ Aurora frames                                           │
├────────────────────────────────────────────────────────────┤
│  Display driver (optional Waveshare)                       │
│  Rotary encoder + button input (optional)                  │
└────────────────────────────────────────────────────────────┘
```

### 3.2 Reusable from BoardSesh

The BoardSesh firmware tree at `~/boardsesh/embedded/` cleanly
separates:

| BoardSesh lib | CruxCoach reuse? |
|---|---|
| `aurora-protocol` (Kilter/Tension BLE decoder/encoder) | direct fork — Aurora frames are board-side spec |
| `moonboard-protocol` | optional, if we expand to Moonboard |
| `led-controller` (FastLED abstraction) | optional, only if controller drives its own LEDs |
| `nordic-uart-ble` (BLE GATT server pretending to be Aurora) | direct fork — exactly what we need |
| `ble-proxy` | direct fork — multi-client → single-board forwarding |
| `config-manager` (NVS persistence) | reuse |
| `wifi-utils` + `esp-web-server` (captive portal, HTTP UI) | reuse |
| `graphql-ws-client` | **drop** — we use Nostr, not GraphQL-WS |
| `climb-history` | replaced by Nostr Kind-1 / Kind-30079 |

License compatibility check before forking — BoardSesh's repo
license + their CONTRIBUTING.md need a real read-pass before any
file copy. Worst case: clean-room reimplementation guided by
their structure (the BLE-proxy logic is a few hundred lines in
total).

### 3.3 New CruxCoach layers

| Layer | Purpose |
|---|---|
| `nostr_client.cpp` | NIP-01 minimal WebSocket client over IP. Subscribe by filter, react to incoming events. Quartz on Android isn't portable — implement directly. |
| `kind30078_handler` | Parse Kind-30078 climb events, decode frames, surface to BLE-multiplexer + display. |
| `kind30079_aggregator` | Per (climb, angle) running sufficient statistics for grade votes (matches the on-phone aggregator from FEAT-009 §3.6). |
| `session_broadcaster` | Optional Kind-1 broadcasts: "user X started session at this board", "user Y sent V8 climb Z". Public diary mode. |
| `lightning_handler` | Optional NIP-57 zap-receipt subscription for tip-the-setter visualisation. |

---

## 4. Feature Catalogue

This is a **brainstorm** of capabilities the controller could host.
Not all ship in v1; bucketing into Phase A/B/C below.

### 4.1 Multi-user BLE access (Phase A — core)

- N concurrent BLE-central clients (phones, browsers, IoT)
- Single forwarder to the real board
- Display: pubkey-short / Kind-0 display_name of currently-active
  sender ("Climb on board: Alice's V5")

### 4.2 Climb queue from Nostr (Phase B)

- Trainer/coach publishes a Kind-30079 (or new Kind, TBD) with a
  list of climb-uuids in order
- Controller subscribes, climbers in the gym see queue on display
- Physical button or app-side tap → next climb auto-loads

### 4.3 Real-time grade voting (Phase B)

- Climbers cast Kind-30079 votes from their CruxCoach app
- Controller is on the same Nostr relays, picks them up
- Display shows live aggregated grade for the climb currently on
  the board ("V5+ from 12 votes")
- Bridges FEAT-009's on-phone aggregator into a wall-side surface

### 4.4 Trainer/coach mode (Phase C)

- Coach (remote or in-gym) publishes a session-plan Kind event
- Controller queues, climbers see "next: Floats Your Boat (V5)"
- Optional: timer between climbs; physical button to skip

### 4.5 Live setter mode (Phase C)

- Setter "claims" the board via app → controller locks BLE input
  to setter's pubkey only
- Display: "Setter at work — Alice"
- Other climbers can't accidentally send during setting
- Setter publishes the new climb via CruxCoach editor — controller
  picks up the Kind-30078 immediately and displays preview

### 4.6 Easter animations (Phase C, optional)

- Send + flash → green sweep animation on the LED chain (forwarded
  to board, not controller-internal)
- Specific patterns for benchmark-flash, milestone grades
- Configured via CruxCoach app; aligns with existing
  `EasterAnimations.kt` flag

### 4.7 Lightning zap visual (Phase C, optional)

- Setter publishes a climb with `lud16` in their Kind-0
- Climber zaps via NIP-57 from their CruxCoach app
- Controller subscribes to zap-receipts (Kind-9735) targeting
  that setter's pubkey
- Visual: zapped climb flashes gold on the board for ~3 seconds
  (LED pattern), display shows "🟡 X sats sent to Alice"

### 4.8 Climb of the day (Phase B-C)

- Configurable Nostr feed (e.g. a curated list, a user-chosen
  setter, a community pubkey)
- Controller cycles through the day's picks when no climber is
  actively connected
- "Try this one" CTA on display

### 4.9 Session statistics (Phase B)

- Local SQLite of climbs sent during session
- End-of-session summary published as Kind-1 to user's relays:
  "Trained 17 climbs at @gym, 90 min, top send V8"
- Persists to climber's CruxCoach logbook via FEAT-008-style
  Nostr backup

### 4.10 Time-out / cooldown (Phase A-B)

- Auto-disconnect after N minutes of inactivity (configurable)
- Frees the board for the next user — no one stuck holding the
  connection
- Visible countdown on display

### 4.11 Sequence rehearsal (Phase C)

- Tap "rehearse last 5" on display → controller cycles climbs
  back through, each lit for 30 s
- Useful for video sessions, repeating projects

### 4.12 Group / pair sessions (Phase C)

- Two-pubkey-pair mode: alternates the active climber per send
- Display shows whose turn it is
- Friendly competition; built-in turn-taking

### 4.13 Display modes (Phase C)

- "Now-climbing" (default): grade, name, setter, holds preview
- "Queue list": top 5 upcoming climbs
- "Stats": today's sends, grade histogram
- "Heatmap": community-popular holds overlay
- Cycle via rotary encoder or web config

### 4.14 Diagnostics + OTA (Phase A)

- BLE/WiFi/board-link status on display + web UI
- OTA firmware update via web UI (multipart upload) or auto-pull
  from Codeberg-Releases / Zapstore
- Firmware signed by CruxCoach maintainer pubkey for trust

### 4.15 Setter-tip Lightning address QR (Phase C)

- Display a QR code of the current climb's setter Lightning
  address
- Climbers can scan from their phone, tip directly
- Plus: zap-receipt visualisation per §4.7

### 4.16 Privacy mode (Phase A)

- All board-side identity (display_name, npub-short) can be
  hidden via a config toggle for shared / public gym setups
- Display only shows generic indicators ("climber 1 sending")

---

## 5. Implementation Phases

### Phase A — Hardware-feasibility MVP (1-2 months)

Goal: prove the BLE-multiplex value-prop without any Nostr.

| Task | Artefact |
|---|---|
| Source ESP32-S3 + Aurora board for testing | hardware procurement |
| Fork BoardSesh's `aurora-protocol`, `nordic-uart-ble`, `ble-proxy` libs (license-permitting) | `boardsesh/embedded/libs/*` reused or reimplemented |
| Strip GraphQL-WS layer; keep WiFi captive portal + HTTP config | `embedded/cruxcoach-controller/` |
| BLE multiplex test: 4 phone clients, one board | manual test |
| OTA firmware update flow | Codeberg-Releases auto-poll |
| Build + flash documentation | `docs/controller/build.md`, `docs/controller/flash.md` |

### Phase B — Nostr + grade-voting integration (1-2 months)

| Task | Artefact |
|---|---|
| Minimal C++ Nostr client (NIP-01, REQ filters, EVENT parse) | `embedded/cruxcoach-controller/lib/nostr_client/` |
| Kind-30078 subscribe + decode → display | board-side preview of incoming community climbs |
| Kind-30079 aggregator (matching on-phone implementation from FEAT-009 §3.6) | live grade display |
| Session-broadcast Kind-1 publish path | "started training at @gym" public note |
| Time-out / cooldown logic | improves multi-user fairness |

### Phase C — Display + advanced features (open-ended)

Pick from §4 catalogue based on user demand:

- Display modes (§4.13) — first concrete user-facing display
- Trainer/coach mode (§4.4)
- Sequence rehearsal (§4.11)
- Lightning zap visual (§4.7 + §4.15)
- Group sessions (§4.12)
- Live setter mode (§4.5)

---

## 6. Distribution

### 6.1 Firmware

- License: BSD-2-Clause or MIT (CruxCoach app is GPL — firmware in
  a separate repo so it's licensable independently)
- Source repo: `controller.cruxcoach.org` or a sub-namespace under
  the existing Codeberg org
- Releases: tagged binaries via Codeberg-Releases (mirroring the
  app pipeline)
- Auto-update: web config polls every N hours, prompts user or
  auto-flashes (configurable)
- Optional Zapstore distribution if Zapstore supports firmware
  artefacts (TBD)

### 6.2 Hardware

- DIY guide: BOM, schematic, 3D-print files in `docs/controller/`
- Pre-built (optional, post-launch): partner manufacturer or
  small-batch runs sold through cruxcoach.org store. Revenue
  stream funds the project.

---

## 7. Open Questions

1. **License compatibility BoardSesh ↔ CruxCoach controller**:
   read their LICENSE before forking. If incompatible: clean-room
   reimplement.
2. **Aurora protocol stability**: Aurora's SDK is not officially
   open. Reverse-engineered libs (BoardSesh's `aurora-protocol`,
   our own `BoardPacketEncoder`) might break on Kilter firmware
   updates. Mitigation: keep encoder in CruxCoach app authoritative,
   firmware syncs from there (board-side encoder is just a forwarder
   of pre-encoded frames).
3. **Pre-built hardware revenue stream**: tax/legal implications
   for a FOSS project selling units. Probably routed through a
   donation-model partner rather than direct.
4. **Cooperate with BoardSesh**: a friendly conversation upstream
   could result in a shared multi-backend firmware (BoardSesh-
   GraphQL-WS as one option, Nostr as another), with user toggle.
   Reduces fragmentation. Worth opening as an issue on their repo.
5. **NIP coverage**: NIP-01 + NIP-10 minimum. NIP-13 PoW gate for
   spam? NIP-65 relay-discovery? NIP-78 application data? Decide
   per Phase B implementation.
6. **Power management**: USB-C wall adapter is fine for v1.
   Battery-backed for portable training is Phase C+ if at all.
7. **ESP32 BLE multi-central limit**: can the chip handle 10
   simultaneous BLE-central connections, or do we need to reject
   beyond N? Test, document the limit, surface in UI.
8. **Backwards compatibility with the ageing "old" Kilter board
   firmware** (pre-2024): both the new and old Aurora protocols
   need to work. BoardSesh handles both — confirm.

---

## 8. Caveats

- This is **months of work** for one motivated maintainer once
  hardware lands. Backlog placement reflects that — do not pull
  forward until the on-phone backlog (FEAT-008/009/010, FEAT-011
  setter angle, FEAT-012 routes) is delivered and stable.
- Hardware support is a **support burden** that scales with users.
  Returns/RMAs/firmware-bricks/WiFi-config-pain. Plan a community
  Discord or Codeberg-Issues channel before shipping pre-built
  units.
- Aurora protocol changes by Kilter are an external risk. The
  controller depends on the same reverse-engineered protocol our
  app does — a Kilter-side BLE refactor breaks both at once. Have
  a recovery plan (firmware OTA update path is part of it).
- Real-world interference with the existing Kilter app: if a user
  in the gym opens the official Kilter app, it'll try to connect
  directly to the board (bypassing the controller). The controller
  can hold the board-side BLE connection but can't prevent the
  Kilter app from contesting it. Good UX would notify everyone via
  display when this happens.
