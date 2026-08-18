---
status: backlog
---
# Feature Spec: CruxCoach Controller — ESP32 BLE Multiplexer (backlog)

> **Status:** Backlog — captured 2026-05-06, scope-trimmed 2026-05-06
> to hardware-only. The Nostr-based live-training social features
> originally bundled here moved to **FEAT-014** so they can ship
> phone-first without waiting on hardware. This spec is now strictly
> the BLE-multiplexer + captive-portal + OTA hardware companion.
>
> **Reference architecture:** BoardSesh's "Gizmo" (open-source ESP32
> firmware, see `embedded/projects/board-controller/` in the BoardSesh monorepo)
> implements the BLE-proxy core idea CruxCoach would build on.
>
> **Relates to:**
> - FEAT-014 (Live Training Coordination via Nostr) — phone-first
>   Nostr social layer. The controller's display can become an
>   additional surface for FEAT-014 once it exists, but FEAT-014
>   ships and works without any controller.
> - FEAT-003 (Climb Creator) — controller's "set climb on board"
>   path forwards the same `BoardPacketEncoder` Aurora frames the
>   editor produces.
>
> **Depends on (when implemented):**
> - Stable Aurora-protocol encoder in CruxCoach (`BoardPacketEncoder`)
>   — already shipped, exact same frames the controller forwards.
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
        phone C ─┼────→ Controller (BLE peripheral
                 │        + BLE central)
                 │
                 │            │
                 │            │ BLE (single Aurora connection)
                 │            ↓
                 │        Kilter Board
```

CruxCoach can ship its own equivalent that:

1. Multiplexes BLE access (the BoardSesh-Gizmo core capability).
2. Optionally serves as a **display surface** for FEAT-014's
   Nostr-based live-training features when both controller and
   FEAT-014 are deployed together — but the controller does not
   know or care about Nostr, it just renders strings the
   firmware-level handler is told to render.

Everything social/networked is FEAT-014's problem. This spec is the
hardware bridge.

### 1.2 Why CruxCoach builds it (vs. just supporting BoardSesh's)

| Question | Answer |
|---|---|
| Does CruxCoach need to make hardware to support multi-phone access? | No — a CruxCoach phone client speaking Aurora-BLE finds a Gizmo just like a real board. We get the value-prop for free if a user has a Gizmo. |
| What does our own controller buy us? | (a) Branding parity with the cruxcoach.org store. (b) Optional integration with FEAT-014 Nostr features without depending on BoardSesh's GraphQL-WS backend. (c) Distribution control (firmware via Codeberg/Zapstore). |
| Could we fork BoardSesh firmware? | Possibly. License-permitting. Most of the BLE-proxy + WiFi config layers are reusable as-is; the GraphQL-WS layer would just be removed (we have no backend service to talk to). |
| Could we ship just a firmware-fork, no hardware? | Yes — that's the lightest path. Users buy off-the-shelf ESP32 + cabling, flash our firmware. Pre-built unit via cruxcoach.org store comes later if at all. |

### 1.3 Goals — Offline-only

The controller is **fully offline-capable**. Internet access is only
required for OTA firmware updates. None of the runtime features
need a relay or backend.

- **Multi-phone access** to a single Aurora-protocol board: 4-10
  simultaneous BLE clients seamlessly multiplexed onto one board.
- **Captive-portal setup**: WiFi-AP fallback for first-run
  configuration; HTTP web UI on `192.168.4.1` to set device name,
  optional WiFi credentials (only used for OTA), brightness,
  proxy target MAC, etc.
- **Time-out / cooldown**: auto-disconnect a hogging client after
  N minutes so the board frees up for the next user.
- **Optional display** (Waveshare 1.69" round LCD or similar):
  surfaces who's currently sending, board status, BLE health.
  Display content is driven by what the firmware infers from BLE
  traffic, not from any network feed.
- **OTA firmware updates**: polled from Codeberg-Releases (only
  network feature; can be disabled in Settings).
- **Open-source firmware** distributed via Zapstore, Codeberg, or
  similar.
- **Optional pre-built hardware** via cruxcoach.org for users who
  don't want to source/flash themselves.

### 1.4 Non-Goals

- Any Nostr / relay / network-event handling on the controller. If
  social or training-coordination features are needed, they live
  in FEAT-014 on the phone client side; the controller participates
  only via the BLE pipe.
- Replicate every BoardSesh Gizmo feature one-to-one.
- Standalone climbing app on the controller (use the phone-side
  CruxCoach app for everything that's not a board-side display).
- Custom ASIC / FPGA — commodity ESP32 only.
- Multi-board switching from a single controller (1 controller per
  board; multi-board gym → multiple controllers).
- Any cloud-locked features. If it doesn't work without our infra,
  it doesn't ship.

---

## 2. Hardware

### 2.1 Reference design (initial guess)

| Component | Choice |
|---|---|
| MCU | ESP32-S3 (BLE 5.0 + WiFi + USB-OTG, ~$3-5) |
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

No Nostr layer, no WebSocket client, no relay subscription. If the
phone wants to publish a climb to Nostr or trigger trainer-mode
features, that happens on the phone side per FEAT-014 — the
controller only sees the resulting Aurora-protocol BLE frames the
phone sends.

### 3.2 Reusable from BoardSesh

The BoardSesh firmware tree at `embedded/` in the BoardSesh monorepo cleanly
separates:

| BoardSesh lib | CruxCoach reuse? |
|---|---|
| `aurora-protocol` (Kilter/Tension BLE decoder/encoder) | direct fork — Aurora frames are board-side spec |
| `moonboard-protocol` | optional, if we expand to Moonboard |
| `nordic-uart-ble` (BLE GATT server pretending to be Aurora) | direct fork — exactly what we need |
| `ble-proxy` | direct fork — multi-client → single-board forwarding |
| `config-manager` (NVS persistence) | reuse |
| `wifi-utils` + `esp-web-server` (captive portal, HTTP UI) | reuse |
| `graphql-ws-client` | **drop** — we have no backend service |
| `climb-history` | drop or simplify — local-only stats if at all |

License compatibility check before forking — BoardSesh's repo
license + their CONTRIBUTING.md need a real read-pass before any
file copy. Worst case: clean-room reimplementation guided by
their structure (the BLE-proxy logic is a few hundred lines in
total).

---

## 4. Feature Catalogue (offline-only)

### 4.1 Multi-user BLE access

- N concurrent BLE-central clients (phones, browsers, IoT)
- Single forwarder to the real board
- Display: short identifier of currently-active sender (BLE
  client address suffix or device-name) — purely from BLE-level
  data, not from any networked profile lookup

### 4.2 Time-out / cooldown

- Auto-disconnect after N minutes of inactivity (configurable)
- Frees the board for the next user — no one stuck holding the
  connection
- Visible countdown on display

### 4.3 Display modes

- "Now-sending" (default): which client currently has the floor
- "Stats" (firmware-local): today's send count, BLE uptime, WiFi
  status — no per-climb history because that's phone-side data
- "Diagnostics": BLE/WiFi/board-link status

### 4.4 Diagnostics + OTA

- BLE/WiFi/board-link status on display + web UI
- OTA firmware update via web UI (multipart upload) or auto-pull
  from Codeberg-Releases
- Firmware signed by CruxCoach maintainer pubkey for trust

### 4.5 Privacy mode

- All board-side identity (BLE name visible to other connected
  clients) can be hidden via a config toggle for shared / public
  gym setups
- Display only shows generic indicators ("climber 1 sending")

### 4.6 Setting-mode lock-out

- Setter "claims" the board via app (a specific magic frame
  sequence the controller recognises) → controller locks BLE
  input forwarding to the claiming client only
- Other connected clients still see frames coming through
  (read-only mode) but can't write
- Display: "Setter at work"
- Released when the claiming client disconnects or sends an
  unlock magic sequence

This is the most complex feature in v1 — but worth it because it
stops accidental writes during board setting.

### 4.7 Easter animations

- Specific Aurora frame patterns for benchmark-flash, milestone
  grades sent through the controller can trigger pre-canned LED
  animations inserted into the forwarding stream
- Configurable via web UI on/off
- Connects to existing CruxCoach `EasterAnimations` flag

### 4.8 BLE-multiplex fairness

- Round-robin or priority-based scheduling when multiple clients
  send simultaneously
- Display surfaces queue position
- Plain offline algorithm; no networked coordination

---

## 5. Implementation Phases

### Phase A — Hardware-feasibility MVP (1-2 months)

Goal: prove the BLE-multiplex value-prop without any display.

| Task | Artefact |
|---|---|
| Source ESP32-S3 + Aurora board for testing | hardware procurement |
| Fork BoardSesh's `aurora-protocol`, `nordic-uart-ble`, `ble-proxy` libs (license-permitting) | `boardsesh/embedded/libs/*` reused or reimplemented |
| Strip GraphQL-WS layer; keep WiFi captive portal + HTTP config | `embedded/cruxcoach-controller/` |
| BLE multiplex test: 4 phone clients, one board | manual test |
| OTA firmware update flow | Codeberg-Releases auto-poll |
| Build + flash documentation | `docs/controller/build.md`, `docs/controller/flash.md` |

### Phase B — Display + advanced features (open-ended)

Pick from §4 catalogue:

- §4.3 Display modes
- §4.6 Setting-mode lock-out
- §4.7 Easter animations
- §4.8 BLE-multiplex fairness scheduler

### Phase C — Optional FEAT-014 integration

If FEAT-014 has shipped on the phone-side and gained traction,
the controller can grow a thin Nostr-relay subscription that
mirrors a subset of FEAT-014's events on the controller's display
(e.g. "next climb in trainer queue: ..."). This is the only
reason the controller would ever speak Nostr. Scope to be
designed in a separate spec increment when FEAT-014 has real
deployment data.

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
   GraphQL-WS as one option, plain offline as another), with user
   toggle. Reduces fragmentation. Worth opening as an issue on
   their repo.
5. **Power management**: USB-C wall adapter is fine for v1.
   Battery-backed for portable training is Phase C+ if at all.
6. **ESP32 BLE multi-central limit**: can the chip handle 10
   simultaneous BLE-central connections, or do we need to reject
   beyond N? Test, document the limit, surface in UI.
7. **Backwards compatibility with the ageing "old" Kilter board
   firmware** (pre-2024): both the new and old Aurora protocols
   need to work. BoardSesh handles both — confirm.

---

## 8. Caveats

- This is **months of work** for one motivated maintainer once
  hardware lands. Backlog placement reflects that — do not pull
  forward until the on-phone backlog (FEAT-008/009/010, FEAT-011
  setter angle, FEAT-012 routes, FEAT-014 live-training) is
  delivered and stable.
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
