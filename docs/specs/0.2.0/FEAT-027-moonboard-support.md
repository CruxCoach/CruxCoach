---
status: design-locked
---
# Feature Spec: MoonBoard Support — Catalogue + BLE Send (v0.2.0)

> **Status:** Design-locked 2026-05-20. Two parallel passes that
> day landed this spec:
>
> 1. **An interoperability analysis** of the official MoonBoard
>    Android app, kept in the internal research archive. Resolved
>    §2 (data source), §7 (auth posture), confirmed §5 (hold-role
>    semantics), partially populated §4 (BLE UUIDs).
> 2. **BoardSesh cross-reference** against their open-source
>    monorepo, notes in the same archive.
>    Closed the remaining BLE wire-format gap, validated §6
>    (schema), validated §2 (community-dump choice — they use
>    the same spookykat dump), surfaced an OCR-screenshot
>    bridge worth tracking for v0.2.x. The BoardSesh client at
>    `packages/web/app/components/board-bluetooth-control/bluetooth-moonboard.ts`
>    is a near-verbatim template for the Kotlin
>    `MoonBoardBleClient` CruxCoach will build.
>
> All major design decisions are now locked. Remaining open
> items (§3 variant subset, §6 schema details, §8 logbook
> integration) are implementation-phase decisions that don't
> need additional spec rework before code starts.
>
> CruxCoach extends beyond the Aurora board family for the
> first time, with read-only catalogue browsing and
> BLE-driven LED send of MoonBoard problems. Headline feature
> for v0.2.0. The previous v0.2.0 slate (FEAT-008 / FEAT-009 /
> FEAT-011 / FEAT-012) moved to backlog; reasoning: opening a
> non-Aurora board ecosystem is a strategic pivot that
> unblocks future board coverage, where the four backlog'd
> specs are all Kilter-internal polish on top of an already-
> rich feature set.
>
> **In scope for v0.2.0:** catalogue browse, per-(climb, angle)
> stats display, BLE-send to a paired MoonBoard.
>
> **Out of scope for v0.2.0:** MoonBoard climb-creator (FEAT-003
> equivalent), Nostr community climbs on MoonBoard (Kind-30078
> mirror), MoonBoard difficulty-engine votes (FEAT-009 equivalent),
> MoonBoard route-mode authoring, MoonBoard logbook backup to
> Nostr. Logbook-side MoonBoard ascents — see §8.
>
> **Depends on:**
> - FEAT-007 (Board Selection in Onboarding, v0.1.6) — MoonBoard
>   variants must coexist with the Kilter variants in the picker.
>   If FEAT-007 ships first (likely), this spec extends its
>   variant list. If parallel, coordinate the variant enum.
>
> **Relates to:**
> - FEAT-015 (Kilter Board Locations Map, v0.1.5) — the hangtime
>   feed already ships `moonboard.geojson` (1514 features in the
>   2026-05-19 snapshot, 770 commercial). Rendering MoonBoard
>   pins is a natural extension of FEAT-015; out of v0.2.0 scope
>   here unless FEAT-015 picks it up explicitly.
> - FEAT-022 (Multi-Heatmap per Board, backlog) — once MoonBoard
>   climbs are in the DB, the per-board heatmap must key on
>   `board_brand` rather than `layout_id` alone.
> - FEAT-006 schema rename (shipped in 0.1.4) — renamed the
>   board DB from `aurora_*` to `board_*` / plain plural names
>   specifically to make this kind of feature additive. §6
>   below is the first real test of that.

---

## 1. Overview

CruxCoach today is Aurora-only — Kilter Board, with the Homewall
variant added in 0.1.4, and the rest of the Aurora family
(Tension, Grasshopper, Decoy, So-iLL, Touchstone) reachable in
principle but not exercised end-to-end.

MoonBoard is the largest non-Aurora training board ecosystem:
~1500 boards installed worldwide per the hangtime feed snapshot
(2026-05-19), of which ~770 are commercial gym installations.
The MoonBoard community has been active since 2016, predates
Kilter, and maintains its own problem catalogue on moonboard.com.

Adding MoonBoard support to CruxCoach widens the app's reach to
a distinct climbing community without dragging the Aurora UX
along — and serves as the prototype for any future non-Aurora
board (Tension's LED future, hypothetical 12climb, etc.).

### Goals (v0.2.0)

- A user with a MoonBoard can pick it in onboarding alongside
  Kilter variants.
- A user can browse the MoonBoard problem catalogue with the
  same browser surface used for Kilter — filters for grade,
  setter, ascensionists, angle apply.
- A user can open a MoonBoard problem detail screen and send
  the climb to their physical MoonBoard via BLE (LEDs light
  up).
- The browser knows which board(s) the user has configured;
  the existing always-on "passt auf mein Board" filter
  (`feedback_board_fit_filter_always_on.md`) extends naturally
  to brand-aware fit.

### Non-goals (v0.2.0)

- Authoring MoonBoard problems in the climb-creator.
- Mirroring MoonBoard problems to Nostr as Kind-30078 events.
- A MoonBoard equivalent of FEAT-009's difficulty rating
  engine. Community votes stay Kilter-side until a reason to
  extend emerges.
- Importing the user's existing MoonBoard logbook (the
  FEAT-005 / FEAT-008 equivalent for MoonBoard accounts).
- Cross-board send concept (FEAT-023, backlog).

## 2. Data source — spike resolved 2026-05-20

**Decision: community-dataset path + server-side daily snapshot.
The official MoonBoard API is not viable for CruxCoach.**

### What the spike found

An interoperability spike looked at whether the official
MoonBoard app's backend could serve as a data source. It cannot.
(The endpoint-level detail is deliberately not reproduced here —
see the internal research archive. What matters for this spec is
the conclusion, not the map.)

The backend requires **Firebase App Check with Play Integrity
attestation on every request**, on top of ordinary Firebase user
auth.

### Why CruxCoach cannot hit the official API

That attestation is a **hard stop**. It binds API access to a
genuine, Play-installed copy of the official app running on a
Google-certified device — and it attests the *installed package*.

CruxCoach cannot satisfy it, and would not try:
- Our package identity is our own. Passing the check would mean
  impersonating the official app — a non-starter ethically and
  legally, and the end of the conversation regardless of
  feasibility.
- Even with a user's own valid credentials, App Check rejects
  requests whose attestation doesn't match the expected app
  identity. Signing in changes nothing, so anonymous browse is
  no more viable than authenticated access.

Scraping `moonboard.com` (path 2.2 in the prior skeleton) faces
both TOS exposure and brittleness; not worth the risk when path
2.3 (community dataset) covers the in-scope features without
hitting any MoonBoard infrastructure from the CruxCoach client.

### Chosen path

**One-shot import from the spookykat community catalogue dump
(2023-01-30) + server-side daily snapshot** — analogous to
`project_blossom_sync.md` for the Kilter side, run as a separate
parallel cron.

The choice was independently validated by BoardSesh — they use
the same dump in `packages/db/scripts/import-moonboard-problems.ts`:

```
GitHub issue:    https://github.com/spookykat/MoonBoard/issues/6#issuecomment-1783515787
Direct download: https://github.com/spookykat/MoonBoard/files/13193317/problems_2023_01_30.zip
Date:            2023-01-30
```

Six JSON files in the dump, one per board+angle combination:

| filename | board variant | angle |
|---|---|---|
| `problems MoonBoard 2016 .json` | 2016 | 40° |
| `problems MoonBoard Masters 2017 25.json` | Masters 2017 | 25° |
| `problems MoonBoard Masters 2017 40.json` | Masters 2017 | 40° |
| `problems MoonBoard Masters 2019 25.json` | Masters 2019 | 25° |
| `problems MoonBoard Masters 2019 40.json` | Masters 2019 | 40° |
| `problems Mini MoonBoard 2020 40.json` | Mini 2020 | 40° |

**Not in the dump (because it predates them):**
- MoonBoard 2010 (no LEDs; out of BLE-send scope anyway)
- MoonBoard Masters 2024 (released after 2023-01-30)
- Mini MoonBoard 2025 (bundled in v1.2.45 of the official app
  but absent from the 2023 dump)

CruxCoach's coverage at v0.2.0 launch is therefore bounded by
the dump's contents: 2016, Masters 2017, Masters 2019, Mini
2020 — see §3 for variant-subset planning.

### Implementation shape

- **Server-side snapshot job:** runs daily, mirrors the dump
  (with any later refresh) into a Blossom-stored snapshot
  keyed by board variant + hold set. Client pulls the snapshot
  the same way it pulls the Kilter snapshot today
  (`project_blossom_sync.md`). Refresh cadence on the upstream
  dump is sporadic — the snapshot job re-runs from the same
  source until a fresher dump appears.
- **Schema landing:** unified `boardClimbs/Stats/Holds` tables
  with `board_type = 'moonboard'` discriminator. See §6.
- **No on-device API hits.** The CruxCoach Android client never
  talks to MoonBoard’s backend directly. This sidesteps the
  App Check question entirely and keeps CruxCoach on the right
  side of MoonBoard's implicit "don't impersonate our app"
  expectation.

### Freshness trade-off + the OCR escape hatch

The dataset is **stale by definition** — the gap between when a
problem is published on MoonBoard and when it lands in CruxCoach
is at minimum the snapshot interval (daily), and at maximum
"however often the upstream dump is re-run" plus our snapshot
interval. The 2023-01-30 dump means **anything published after
that date is missing**, including the entire Masters 2024 and
Mini 2025 catalogues.

BoardSesh ships a pragmatic workaround: `packages/moonboard-ocr/`
— a Tesseract+Sharp screenshot parser that extracts climb data
from official-MoonBoard-app problem-detail screenshots. Detects
holds via HSV colour ranges on the LED-overlay circles drawn by
the app; OCRs the header text for name / setter / angle / grade
/ benchmark flag. Works in Node and browser. Returns a
structured `MoonBoardClimb` ready to drop into the catalogue.

This is **an App-Check-free user-driven import path** — the user
takes a screenshot, the parser runs client-side, no MoonBoard
infrastructure is touched. Worth tracking for v0.2.x as the
post-2023 catalogue bridge (see §10 open questions).

### Why no live polite-API path

If user demand warrants faster freshness later, two paths exist:
1. Tighten the snapshot cron interval — only helps if upstream
   dumps actually refresh.
2. Add a server-side polite-API path behind a CruxCoach-
   controlled identity (NOT the official app's signature). This
   still requires figuring out an authentication story since the
   official API is App-Check-gated; might be feasible if Moon
   Climbing eventually exposes a publishable public API, but no
   such API is known today.

### Implications for FEAT-027 scope

- **No login screen, no MoonBoard account integration** —
  resolves §7.
- **No "publish to MoonBoard" path** — already out of scope per
  the v0.2.0 non-goals.
- **No live community-climb visibility on MoonBoard** — the
  Kilter-side parallel (FEAT-003 + live Nostr subscription) does
  not extend here. MoonBoard catalogue is read-only-from-mirror
  in v0.2.0.

## 3. Supported MoonBoard variants

The MoonBoard family is not "different angles of one board" the
way Kilter does it — it's actually different boards with different
hold sets and (in the case of Mini) different grids.

Two source-of-truth tables matter here: what the **official app**
bundles as renderable assets (v1.2.45), and what the **catalogue
data source** (the spookykat 2023-01-30 dump, see §2) actually
contains. They diverge — the app bundle is newer than the dump.

### What the app bundle ships (v1.2.45 asset folders)

Per the internal research archive:

| asset folder | hold sets bundled | wall angle |
|---|---|---|
| `moonboard2010` | `originalschoolholds` | original — pre-LED |
| `moonboard2016` | `holdseta`, `holdsetb`, `originalschoolholds` | 40° |
| `moonboard2024` | `holdsetd`, `holdsete`, `holdsetf`, `woodenholds`, `woodenholdsb`, `woodenholdsc` | 40° (Masters) |
| `minimoonboard2020` | `originalschoolholds`, `woodenholds`, `woodenholdsb`, `woodenholdsc` | 45° |
| `minimoonboard2025` | `holdsetf`, `originalschoolholds`, `woodenholdsb`, `woodenholdsc` | 45° (new in v1.2.45) |

### What BoardSesh's firmware tracks as distinct layouts

BoardSesh's ESP32 firmware (`embedded/projects/moonboard-dev-server/src/main.cpp`)
enumerates layouts **separately from the asset bundle**. They keep
Masters 2017 and Masters 2019 as distinct catalogue-scope
layouts, even though the v1.2.45 bundle has no `moonboard2017` /
`moonboard2019` asset folders:

| BoardSesh layout id | Name | Hold sets (firmware UI) |
|---|---|---|
| 1 | MoonBoard 2010 | Original School Holds |
| 2 | MoonBoard 2016 | Hold Set A, Hold Set B, Original School Holds |
| 3 | MoonBoard 2024 | Hold Set D, E, F, Wooden Holds, Wooden Holds B, Wooden Holds C |
| 4 | MoonBoard Masters 2017 | Hold Set A, B, C, Original School Holds, Screw-on Feet, Wooden Holds |
| 5 | MoonBoard Masters 2019 | Hold Set A, B, Original School Holds, Screw-on Feet, Wooden Holds, Wooden Holds B, Wooden Holds C |

**Pulls together:** there are **7 catalogue-scope variants** to
plan for (2010, 2016, Masters 2017, Masters 2019, 2024, Mini
2020, Mini 2025), with hold-set sub-scoping per variant. Mini
variants and 2010 are not in BoardSesh's firmware layout list
(Mini has a smaller grid; 2010 has no LEDs).

### What the catalogue dump covers (§2)

The spookykat 2023-01-30 dump covers only 4 variants — see §2
for the file list. Specifically **not** covered: Masters 2024
and Mini 2025 catalogues. Until a newer dump or alternative
source emerges, those two variants are catalogue-empty for
CruxCoach even if we wire up the rendering side.

### Hold-set granularity is the picker tier

The **hold set, not the board generation, is the granularity that
matters for rendering and catalogue scoping**. Problems published
against `holdseta` cannot be climbed on a board fitted with
`holdsetb` — the holds are physically in different positions.

BoardSesh's firmware UI confirms this: the user picks layout +
hold-set CSV. CruxCoach's board-picker (touches FEAT-007) must
therefore be **two-tier (variant × hold set)**, not just
generation.

### Variant subset for v0.2.0 — revised

**MVP (catalogue-data-driven):**
- **MoonBoard 2016 (40°)** — ~600 problems in the dump.
- **MoonBoard Masters 2017 (25° + 40°)** — separate climbs per
  angle.
- **MoonBoard Masters 2019 (25° + 40°)** — separate climbs per
  angle.

All three share the same 11×18 grid + NUS BLE protocol → minimal
per-variant logic, single encoder/renderer covers everything.

**Defer to 0.2.x (no catalogue data yet, would need OCR import
or alternative source):**
- **MoonBoard Masters 2024** — adopters of the flagship would
  benefit most from CruxCoach support, but with no climbs in the
  dump there's nothing to send. Track the OCR-import bridge
  (§2's escape hatch) as the realistic path.
- **Mini MoonBoard 2020** — covered by dump (~30 problems), but
  smaller grid means variant-specific encoder + renderer + asset
  work. Skip until 2024 is also reachable.
- **Mini MoonBoard 2025** — no catalogue data + smaller grid.

**Out of v0.2.0 scope entirely:**
- **MoonBoard 2010** — no LEDs, no BLE-send. Browse-only support
  is technically feasible (the dump implicitly covers 2010-era
  problems on the original school holds) but the spec scopes
  catalogue-browse-WITHOUT-send out.

Decision gate: confirm the variant subset at the start of
implementation, once the dump's per-variant population is
verified.

## 4. BLE protocol

The static RE on v1.2.45 (the internal research archive)
confirmed the BLE stack:

- **BLE library:** `flutter_reactive_ble` — the same modern
  reactive Flutter BLE plugin we can use in CruxCoach. Plugin
  identifiers visible in `libapp.so` strings
  (`flutter_reactive_ble_method`, `flutter_reactive_ble_scan`,
  `flutter_reactive_ble_char_update`,
  `flutter_reactive_ble_connected_device`).
- **Write primitive:** `writeCharacteristicWithoutResponse`
  (confirmed string in `libapp.so`).

### GATT services — TWO UUIDs to scan for

The app scans for **both** UART variants — the installed
MoonBoard fleet spans two generations of BLE hardware:

| service | UUID | hardware generation |
|---|---|---|
| **Nordic UART Service (NUS)** | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | newer (2017+) |
| NUS RX characteristic (write to board) | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` | newer |
| **Red Bear Lab BLE UART** | `713d0000-503e-4c75-ba94-3148f18d941e` | pre-2017 RedBear-module boards |
| Red Bear UART characteristic | `713d0003-503e-4c75-ba94-3148f18d941e` | pre-2017 |
| Vendor (unknown) | `2c9285bc-dfd0-4fd8-a13d-393660f8a060` | likely Masters 2024 — confirm at dynamic-capture |
| Vendor (unknown) | `5b2bf25f-9a69-4a5a-8788-6f3ddcb97fc4` | likely Masters 2024 — confirm at dynamic-capture |

The two unknown vendor UUIDs are present in the v1.2.45 strings
but their role is not statically inferable. Treat them as
"scan-and-fall-through" UUIDs for the implementation skeleton;
resolve via dynamic capture once we have a paired Masters 2024.

### Wire format — concrete, fully resolved from BoardSesh

Static RE alone could not extract the wire format (role-prefix
characters were too short to distinguish from binary). The
BoardSesh codebase fills this in directly. Two complementary
sources:

1. **Encoder reference** — `packages/web/app/components/board-bluetooth-control/bluetooth-moonboard.ts`
   in BoardSesh. The TypeScript client that emits the frames
   actually written to factory MoonBoards via Web Bluetooth /
   Capacitor BLE.
2. **Decoder reference** — `embedded/libs/moonboard-protocol/src/moonboard_protocol.{h,cpp}`
   in BoardSesh. The C++ parser running on their ESP32 dev-server
   that accepts writes from either the official MoonBoard app or
   their own client. Wider grammar (parses more than the encoder
   emits).

The encoder is the directly portable reference for CruxCoach.

**Frame grammar (client-emitted):**

```
l#<role-token><serial-pos>,<role-token><serial-pos>,...#
```

- `l#` prefix — "lights only" mode. The `~D...` config preamble
  ("lights above holds" mode with aux LEDs) is supported by the
  decoder but **not used by the encoder**. CruxCoach can ship
  with `l#` only.
- Holds comma-separated.
- Frame terminated by `#`.

**Role tokens (encoder side, 3 tokens):**

| token | role | numeric (Aurora-aligned) | LED colour (board side) |
|---|---|---|---|
| `S` | start | 42 | green |
| `P` | hand (middle) | 43 | blue |
| `E` | end / finish | 44 | red |

(The decoder accepts a wider set — `S`/`R`/`P`/`L`/`M`/`F`/`E`
mapping to roles 42-48 — but the catalogue dump and the encoder
only need 3 tokens. CruxCoach can ship the same 3-token encoder.)

**Serial position — serpentine 11×18 grid:**

The `<serial-pos>` is a 0-indexed LED-strip position, not a grid
coordinate. The LED strip snakes column-by-column: even-indexed
columns (0, 2, 4, …) run bottom-to-top, odd-indexed columns (1, 3,
5, …) run top-to-bottom. Total 198 cells + 2 buffer = 200
positions.

Encoder math (1-based grid hold ID → 0-based serial position),
ported from `bluetooth-moonboard.ts`:

```
fun moonBoardSerialPosition(holdId: Int): Int {
    val zero = holdId - 1
    val col = zero % 11
    val row = zero / 11
    return if (col % 2 == 0) col * 18 + row
           else                col * 18 + (17 - row)
}
```

**Grid coordinate convention:**

Hold IDs are 1-based, computed as `(row - 1) * 11 + colIndex + 1`
where `colIndex` is 0-based (A=0, K=10) and `row` is 1-18 (row 1
at the bottom). The catalogue uses string coordinates like
`"J3"` which decompose to `colIndex=9, row=3` → `holdId = 25`.

### Implementation references in BoardSesh

The directly portable files:

- `packages/web/app/components/board-bluetooth-control/bluetooth-moonboard.ts`
  — encoder (`getMoonboardBluetoothPacket`,
  `getMoonboardSerialPosition`), scan filter
  (`MOONBOARD_REQUEST_DEVICE_OPTIONS`), name-prefix matcher
  (`isMoonboardDeviceName`), role map (S/P/E → 42/43/44).
- `packages/web/app/components/board-bluetooth-control/bluetooth-shared.ts`
  — NUS UUIDs (`UART_SERVICE_UUID`, `UART_WRITE_CHARACTERISTIC_UUID`),
  `MAX_BLUETOOTH_MESSAGE_SIZE = 20`, `splitMessages`,
  `writeCharacteristicSeries` with `INTER_CHUNK_DELAY_MS = 5`.
- `packages/web/app/lib/ble/web-adapter.ts` + `capacitor-adapter.ts`
  — adapter pattern that picks the right transport (Web
  Bluetooth in browser, Capacitor `BluetoothLe` plugin in
  mobile).

These files are a near-verbatim template for the Kotlin
`MoonBoardBleClient` CruxCoach will build — see the
implementation sketch below.

### BLE chunking + pacing

From `bluetooth-shared.ts`:

- **MTU:** 20 bytes per chunk (Web Bluetooth conservative default;
  Capacitor adapter calls `requestMtu()` after connect to
  negotiate higher if available).
- **Chunk strategy:** split the full `l#…#` frame into 20-byte
  chunks with `splitMessages()`.
- **Pacing:** 5 ms `INTER_CHUNK_DELAY_MS` between
  `writeWithoutResponse` calls — keeps Web Bluetooth and
  iOS CoreBluetooth happy on small chunks.
- **Per-hold error handling:** invalid hold IDs are silently
  skipped with a console warning, not fatal-erroring the write.
  Useful for catalogue-vs-installed-set mismatches.

### Scan filter

Combining service UUID + device-name prefix (from
`MOONBOARD_REQUEST_DEVICE_OPTIONS`):

```kotlin
val moonBoardScanFilter = ScanFilter.Builder()
    .setServiceUuid(ParcelUuid.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"))
    .build()
val nameFilters = listOf("MoonBoard", "Moonboard")  // case-sensitive prefix
```

— factory MoonBoards advertise both the NUS service UUID and a
device name starting with `MoonBoard` or `Moonboard` (the older
firmware capitalised the second word, newer firmware doesn't).
CruxCoach should match on the service UUID first and use name
prefix as a confirmation / display label.

### Implementation sketch

- New `MoonBoardBleClient` under
  `androidApp/src/main/java/com/cruxcoach/android/ble/moonboard/`.
  Near-verbatim port of BoardSesh's
  `bluetooth-moonboard.ts` + `bluetooth-shared.ts` to Kotlin.
- Reuse the brand-agnostic connection state machine — the
  BLE class rename pass in 0.1.4 already de-Aurora'd the
  shared layer.
- **Scan for NUS** (`6e400001-...`) primarily; **also include
  Red Bear UART** (`713d0000-...`) as a secondary filter so
  pre-2017 hardware can still pair. BoardSesh does not cover
  Red Bear — that's a CruxCoach-original extension if we choose
  to ship it.
- Single shared encoder in `shared/` (`MoonBoardFrameEncoder`),
  not per-variant — the encoder only emits `l#<S|P|E><pos>,…#`
  which is identical across NUS-based MoonBoard generations.
  The serial-position arithmetic is a pure function:

  ```kotlin
  fun encodeFrame(holds: List<Pair<Int, Role>>): ByteArray {
      val tokens = holds.mapNotNull { (holdId, role) ->
          val pos = moonBoardSerialPosition(holdId) ?: return@mapNotNull null
          "${role.token}$pos"
      }
      return "l#${tokens.joinToString(",")}#".toByteArray(Charsets.US_ASCII)
  }
  ```

  Testable on JVM without a device.
- **Chunking + pacing:** split the encoded frame into 20-byte
  chunks, write each via `BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE`,
  sleep 5 ms between chunks.
- Maestro flow: connect → send a known canary problem → assert
  PERF logcat markers for `MOONBOARD_SEND_OK`.
- **No** `use_dual_lights` UI in v0.2.0 — the encoder doesn't
  emit `~D` mode, so the aux-LED behaviour is moot. Re-evaluate
  when Masters-2024-specific features are designed (separate
  spec).
- **Red Bear UART encoder:** if/when we add pre-2017 support,
  the wire format may differ — verify against
  `c0d3z3r0/moonboard-bluetooth-led` or similar community
  references before adopting any specific encoding.

## 5. Hold-role semantics — simpler than first thought

Early drafts of this spec framed MoonBoard's role model as
"fundamentally different from Kilter's." The BoardSesh
cross-reference (`06-boardsesh-comparison.md`) showed this
overstated the difference. The catalogue layer is actually
simpler than Kilter's, not different in kind.

### What the catalogue carries

The spookykat 2023-01-30 dump records each move as:

```ts
{ description: "J3", isStart: boolean, isEnd: boolean }
```

— a grid coordinate plus two booleans. That's it. **Three
roles** in the catalogue model:

- `isStart = true` → start hold (role code 42)
- `isEnd = true` → finish hold (role code 44)
- neither → hand hold (role code 43, default)

No foot / match / left-hand distinctions in the catalogue.

### What the wire format CAN carry

The decoder side accepts a richer 7-token vocabulary
(`S/R/P/L/M/F/E` mapping to roles 42-48 — start / hand-r /
hand-p / left / match / foot / end). But the encoder side of
BoardSesh's client only emits 3 tokens (`S/P/E`), because the
catalogue only has 3. CruxCoach mirrors this: 3-token encoder.

### The "physical hold colour" axis is a wall-side convention

MoonBoard physical hold sets do use colour stickers (e.g.
yellow stickers on holds the climber is permitted to use as
footholds). But this is **a climber-facing wall-side
convention**, not catalogue data — the climb's defined start /
hand / finish set is what the LEDs show, and the climber
applies the foot-eligibility rule by reading the colour of
*any* hold not in the LED-lit set.

For CruxCoach's renderer, this means:

- **Render the 3 catalogue roles** (start / hand / finish) with
  three visually distinct treatments. BoardSesh's overlay
  palette is red/blue/green (start/hand/finish — see
  `moonboard-ocr/src/types.ts` `HOLD_COLORS`); the physical-LED
  palette is green/blue/red. Pick one canonical palette for
  detail-screen rendering; the obvious choice is the LED
  palette (green=start / blue=hand / red=finish) because that's
  what the climber sees on the wall.
- **Don't try to encode foot-eligibility** in the catalogue
  data. It's not there.

### Implications for a future climb-creator

A MoonBoard climb-creator (out of v0.2.0 scope) would:
- Let the user pick start / hand / finish per hold — same
  model as the Kilter creator.
- Not surface foot / match / left-hand markers (catalogue
  doesn't carry them; the BoardSesh creator doesn't emit
  them either).

So the "different mental model" framing in earlier drafts
(role-constrained-by-physical-hold-colour) was wrong; the
authoring model is the same as Kilter's.

## 6. Schema decision — Option A + cross-product (validated)

The BoardSesh cross-reference confirms this is the right
direction. BoardSesh's MoonBoard rows live in the same unified
`board_climbs / board_climb_stats / board_climb_holds` tables as
their Aurora rows, with a `board_type` text discriminator
(`UnifiedBoardName = BoardName | 'moonboard'` in their type
system). One climb row per (problem, angle) — same `layoutId`
for Masters 2017 25° and 40°, different `angle` values →
separate rows. **This is exactly Option A + cross-product.**

### MoonBoard's own data model (for context)

Reconstructed from Drift `CREATE INDEX` statements in `libapp.so`
(see the internal research archive):

- **`problems`** — master catalogue (`grade`, `user_grade`,
  `name`, `moves`, `is_favourite`, `is_benchmark`, `climb_method`,
  `holdsets`, `setby_id`, `configuration`).
- **`problem_to_configurations`** — M:N junction with per-config
  attributes (`grade`, `user_grade`, `rating`, `is_favourite`,
  `is_benchmark`). The same problem can have different grades
  on different (hold-set × angle) configurations.
- **`holdsetups`** + `holdsetup_to_configurations` +
  `holdsetup_to_foot_rules` — the physical-board configuration
  graph.
- **`logbooks`** — ascents with a `setup` foreign key. `tries` is
  a string enum (`Flashed | 2nd try | 3rd try | more than 3 tries
  | Project`).

MoonBoard's official app treats per-config attributes as
first-class via `problem_to_configurations`. CruxCoach (and
BoardSesh) flatten this to cross-product rows — less elegant
when we eventually do per-config voting (FEAT-009 territory),
but lowest-friction for v0.2.0 and keeps Kilter + MoonBoard on
identical schema shapes.

### Concrete mappings (BoardSesh-validated)

From `packages/db/scripts/import-moonboard-problems.ts` and
`moonboard-helpers.ts`:

**Layout-id mapping (BoardSesh's `holdsetup.apiId → layoutId`):**

```
holdsetup.apiId   layoutId  variant
   1                2       MoonBoard 2016
  15                4       MoonBoard Masters 2017
  17                5       MoonBoard Masters 2019
  19                6       Mini MoonBoard 2020
```

CruxCoach can adopt the same `layoutId` numbering or pick its own
— either way, the BoardSesh table is the canonical bridge from
the dump's `holdsetup.apiId` (the official MoonBoard API's
identifier) to a local layout ID.

**Role codes (Aurora-compatible — see Kilter RE memory):**

```
42 = start  (token 'S')
43 = hand   (token 'P' on the wire, 'R' also accepted by the decoder)
44 = finish (token 'E')
```

**Frame format** stored in the `frames` column:

```
p{holdId}r{roleCode}p{holdId}r{roleCode}...
```

— concatenated without delimiters, matching Aurora's frame
convention. `holdId` is 1-based grid index `(row - 1) * 11 +
colIndex + 1`; `coordinateToHoldId("J3") = 25` for example.

**Grade mapping (French sport → integer difficulty ID):**

`MOONBOARD_GRADE_TO_DIFFICULTY` in BoardSesh maps `'5+'`/`'5A'`
through `'8B+'` to integer difficulty IDs 13-31. The same scale
CruxCoach can adopt for the MoonBoard rows so brand-aware grade
display stays unified across Kilter + MoonBoard.

**UUID derivation:**

BoardSesh generates deterministic UUID v5 from
`"moonboard:${problem.apiId}"` using the DNS namespace UUID
`6ba7b810-9dad-11d1-80b4-00c04fd430c8`. CruxCoach can adopt the
same scheme so cross-instance imports of the same dump produce
identical UUIDs (deduplication-friendly).

### Option A — extend `climbs` with `board_brand`

Add `board_brand TEXT NOT NULL DEFAULT 'kilter'` to `climbs` (and
dependent tables). Most existing fields stay applicable:
`frames` becomes brand-specific in semantics (a MoonBoard "set of
holds" vs. a Kilter "LED frame"), but the column shape stays —
a serialised list of hold positions. `layout_id` maps to a
MoonBoard variant × hold-set pair (since hold-set is the rendering
granularity per §3). `setter_username`, `name`, `description`,
`quality_average`, `ascensionist_count`, `difficulty_average`
carry over.

- **Pro:** maximum reuse of browser, detail, filter, search code.
  FEAT-006 (shipped in 0.1.4) prepared for exactly this extension.
- **Con:** some fields are awkward in MoonBoard semantics —
  Kilter's `angle` column treats angle as per-stat ("same climb
  at different angles"), but a problem on Mini 2025 vs Masters
  2024 is a genuinely different climb even with overlapping hold
  ids. The cross-product approach side-steps this by treating
  each (problem, angle) as a separate row.

### Option B — parallel `moonboard_*` tables (rejected)

A self-contained MoonBoard schema mirroring MoonBoard's own
shape more closely. Worse code reuse, cleaner separation,
easier to remove if MoonBoard support is ever dropped. BoardSesh
explicitly chose against this — they keep MoonBoard in the
unified tables. CruxCoach follows the same call.

### Implementation decisions to make

- **`tries` enum**: BoardSesh doesn't import it — the catalogue
  dump only carries `repeats` (an aggregate count). For v0.2.0
  CruxCoach can skip the string enum entirely. If the OCR
  import path (§2 escape hatch) lands later, it pulls
  start/hand/finish + name + grade + benchmark flag, none of
  which need the `tries` enum either.
- **`is_benchmark`**: extend `board_climb_stats` with
  `is_benchmark BOOLEAN` (or use BoardSesh's
  `benchmarkDifficulty` pattern of setting the same difficulty
  value into a separate nullable column). Either works.
- **`climb_method` / footrule**: a MoonBoard climb's method
  (e.g. `Feet Follow Hands`, `Footless`, `Screw-Ons Only`) is
  catalogue data but not used by the BoardSesh client. Skip in
  v0.2.0 unless the UX explicitly needs it; track as a sidecar
  modifier table if it comes back.

## 7. Authentication — resolved by §2

**No authentication. No MoonBoard account integration in v0.2.0.**

Since the data source is a server-side snapshot from a community
catalogue dump (§2), the CruxCoach client never authenticates
against any MoonBoard infrastructure — there is no account to
ask the user for, no token to manage, no login flow.

This is a cleaner posture than the Kilter side, where account-
bound writes (publishing climbs into the Kilter ecosystem) live
in FEAT-003 + FEAT-008. MoonBoard's equivalent writes
(`/createproblem`, `/addlogbookEntry`) are gated by App Check +
Play Integrity and explicitly out of scope for v0.2.0 (see the
non-goals list in the status block).

If a future release wants any user-account integration with
MoonBoard, the App Check / Play Integrity constraint resurfaces:
CruxCoach cannot be the official MoonBoard app, so any write-side
integration would need a different path (e.g. CruxCoach as a
deep-link target for sharing, with the actual write executed by
the official app on the user's phone). That's a separate spec, not
this one.

## 8. UX surfaces

Mostly reuse, modulo the variant picker and the renderer
hooks.

### Onboarding (touches FEAT-007)

The board-selection screen adds MoonBoard variants to the
picker. If FEAT-007 ships in 0.1.6 before this spec, the
variant list there is Kilter-only — this spec extends it;
coordinate the enum so FEAT-007's persistence shape
accommodates non-Aurora brands.

**Picker granularity is hold-set, not just board generation.**
A MoonBoard 2024 board fitted with `holdseta` is a different
catalogue scope than the same physical board fitted with
`holdsetf`. The picker needs to surface both axes: pick the
generation (2010 / 2016 / 2017 / 2019 / 2024 / Mini 2020 /
Mini 2025) AND the hold set installed on it. FEAT-007's
current enum probably treats variant as a single tier — this
spec needs the picker to be two-tier for MoonBoard, while the
Kilter side stays single-tier. Reconcile during FEAT-007's
implementation phase.

### BoardBrowser

- Same list, same filters. New: implicit brand-aware fit
  (a climb is "passt auf mein Board" if its variant is one
  of the user's configured boards).
- The always-on fit filter
  (`feedback_board_fit_filter_always_on.md`) extends — no
  new user-facing toggle.
- Origin chip stays Kilter / CruxCoach / All for now; a
  per-brand origin axis is a follow-up not in v0.2.0.

### Detail screen

- Same layout; board image swaps to the relevant MoonBoard
  variant's wall image.
- Send button drives the new `MoonBoardBleClient`. UI
  state machine for connect / send / disconnect mirrors
  Kilter.
- "On MoonBoard" badge (the parallel of "On Kilter") is
  out of scope for v0.2.0 since the climb-creator is also
  out — there's no CruxCoach-originated MoonBoard climb to
  badge yet.

### Settings

- Per-board pairing state (paired / unpaired) extends to
  the MoonBoard pairing.
- Forgetting a MoonBoard pairing works the same way as
  Kilter.

### Logbook

Open question: do MoonBoard ascents enter the existing
logbook? The schema can accommodate (a `Climb` is a `Climb`),
but the ascent capture UI assumes Kilter-style angle-set
send flow. Recommendation for v0.2.0: yes, local-only
ascents — no Nostr backup of MoonBoard ascents until the
parallel-backup decision is made (likely 0.2.x).

## 9. Compliance posture

Resolved by §2 — the CruxCoach Android client never talks to
MoonBoard infrastructure directly. Compliance is concentrated
on the server-side snapshot job, not the client.

### Client-side rules (CruxCoach Android)

- **Never call MoonBoard’s backend** from the client. App
  Check + Play Integrity makes any such call either fail
  cleanly (rejected) or require impersonation (the only way to
  succeed). Both outcomes are wrong.
- **Never embed Firebase API keys / App IDs** harvested from the
  RE — they're public-readable but using them outside Moon
  Climbing's own app is meaningless (App Check rejects anyway)
  and would invite scrutiny.

### Server-side rules (community-dataset snapshot job)

- **Polite User-Agent.** If the snapshot job ever fetches from
  any MoonBoard infrastructure (the community dataset itself, or
  any future polite-API path), identify CruxCoach + version +
  contact email.
- **Server-side only.** The snapshot job runs on the same kind
  of cron as `project_blossom_sync.md` — never on a user's
  device.
- **Community-dataset licence check.** Before adopting `boardlib`
  or any other community dataset, verify its licence permits
  redistribution into CruxCoach's Blossom snapshot. If it's
  CC-BY or MIT-style, fine; if it's NC or
  non-redistribution, find an alternative source.
- **Attribute upstream.** Snapshot manifests credit the upstream
  community dataset by name + URL.
- **No moonboard.com scraping** unless the licence question on
  the community dataset path turns out unworkable AND moonboard.com's
  TOS explicitly permits it. Currently neither is needed.

### What we deliberately do NOT do

- We do not impersonate the official MoonBoard app.
- We do not register a `com.trainingboard.moon`-signed CruxCoach
  variant in any store.
- We do not aggregate user data across multiple users at any
  CruxCoach-controlled endpoint (same rule as `feedback_kilter_compliance.md`,
  applies by analogy even though no MoonBoard accounts are
  involved).

## 10. Open questions

### Resolved by the 2026-05-20 design-locking pass
- ✅ ~~Data source (§2)~~ — spookykat 2023-01-30 dump +
  server-side daily snapshot, no on-device API hits.
  Validated by BoardSesh using the same dump.
- ✅ ~~Auth (§7)~~ — none; no account integration.
- ✅ ~~BLE library + UUIDs (§4)~~ — Nordic UART
  (`6e400001-...`), Red Bear UART (`713d0000-...`) as
  optional secondary scan.
- ✅ ~~BLE wire format (§4)~~ — `l#<S|P|E><serial-pos>,...#`
  from BoardSesh's encoder. Serial-position serpentine math
  ported from `bluetooth-moonboard.ts`.
- ✅ ~~BLE chunking + pacing (§4)~~ — 20-byte chunks,
  `writeWithoutResponse`, 5 ms inter-chunk delay.
- ✅ ~~Hold-role semantics (§5)~~ — 3-role catalogue model
  (start/hand/finish). Physical hold colour is a wall-side
  climber convention, not catalogue data.
- ✅ ~~Schema direction (§6)~~ — Option A (extend `climbs`
  with `board_brand`) + cross-product per (problem, angle).
  Validated by BoardSesh doing exactly this. Frame format
  `p{holdId}r{roleCode}` is Aurora-compatible.
- ✅ ~~Compliance hard rules (§9)~~ — concrete list above.

### Still open (implementation-time decisions)

- **Variant subset for v0.2.0** (§3): the spookykat dump only
  covers 2016 / Masters 2017 / Masters 2019 / Mini 2020. MVP
  recommended as the three 11×18 variants (2016 + Masters 2017
  + Masters 2019). Confirm at code-start.
- **Hold-set picker UX** (§3, §8): how to expose the two-tier
  variant × hold-set decision in onboarding without
  overwhelming the user. UX call.
- **Per-variant schema refinements** (§6): `tries` enum mapping,
  `is_benchmark` column placement, `climb_method` storage —
  all defer-to-build decisions.
- **OCR import bridge for post-2023 climbs** (§2):
  `@boardsesh/moonboard-ocr` is a Tesseract+Sharp
  screenshot parser. Worth tracking as a v0.2.x feature to
  cover Masters 2024 + Mini 2025 climbs not in the dump.
  Licence review before adoption (BoardSesh's repo licence
  + Tesseract licence both).
- **M:N future migration** (§6): if per-config voting
  (FEAT-009 backlog) returns to the release train, the
  cross-product approach needs to evolve to the M:N pattern
  MoonBoard's own schema uses. Track this dependency.
- **Logbook integration** (§8): MoonBoard ascents as
  first-class in the logbook (local-only, no Nostr backup
  for v0.2.0). Recommendation: first-class.
- **Map** (FEAT-015 relation): does FEAT-015 in v0.1.5 render
  MoonBoard pins from `moonboard.geojson` (hangtime feed
  already provides them, 1514 features), or is that a v0.2.x
  follow-up?
- **Community-dataset licence** (§9): the spookykat dump's
  status needs a licence review before adoption. Same for
  any BoardSesh code we lift verbatim (encoder + helpers).
- **Snapshot job cohabitation** (§2, §9): does the existing
  Kilter blossom-sync cron extend to cover MoonBoard, or
  does MoonBoard get its own parallel cron with separate
  manifest event-kind?
- **Masters 2024 BLE confirmation** (§4): no Masters 2024
  catalogue in the dump means no v0.2.0 climbs to send on
  that hardware, BUT if/when we add OCR-imported Masters 2024
  climbs we need to verify the BoardSesh-derived wire format
  works on real Masters 2024 hardware. The two unknown
  vendor UUIDs (`2c9285bc-...`, `5b2bf25f-...`) might be
  Masters-2024-specific and may require additional handling.
- **Red Bear UART support** (§4): pre-2017 hardware. BoardSesh
  does not cover it; if we do, that's an independent encoder
  task using community references like
  `c0d3z3r0/moonboard-bluetooth-led`.
- **Hold image rendering** (§3): use shipped board imagery
  from the official app's asset bundle (legally risky), our
  own hold-rendering, or BoardSesh's image assets? Affects
  the licence question.

## 11. Why this is a worthwhile single-release headline

CruxCoach today is a Kilter companion. Shipping MoonBoard
support — even read-only + BLE-send — moves the app to a
multi-board posture, which is a strategic pivot, not just
a feature. Once a second board's plumbing is in place
(brand-aware schema, brand-aware BLE dispatch, brand-aware
onboarding picker, brand-aware fit filter, brand-aware
renderer hooks), each subsequent board is incremental:
the spec template, the schema migration shape, the BLE
client interface, the UI surfaces are all set.

The four backlog'd v0.2.0 specs are all Kilter-internal
polish on top of an already-rich Kilter feature set; none
of them opens a new ecosystem. Reordering is the right
call.

## 12. RE + comparison references

### Static RE

An interoperability analysis of the official MoonBoard Android
app backs the decisions in §2 (data source), §7 (auth posture),
§5 (hold-role semantics) and §4 (BLE). It was done to establish
what CruxCoach can and cannot interoperate with — the outcome of
that question is §2's answer: **not via their API**.

The analysis itself lives in the internal research archive,
outside this repository, and is deliberately not reproduced or
inventoried here. Nothing from it is required to build or read
this spec; the conclusions that matter are stated inline.

### BoardSesh cross-reference

The open-source BoardSesh monorepo has a feature-complete
MoonBoard integration that independently validates this spec's
design choices. It is public, and reading it directly is the
better reference — the comparison notes are in the same internal
archive.

Most directly-portable code paths to study during
implementation:

| BoardSesh path | What CruxCoach lifts |
|---|---|
| `packages/web/app/components/board-bluetooth-control/bluetooth-moonboard.ts` | Encoder + scan filter + role map. Port to Kotlin near-verbatim. |
| `packages/web/app/components/board-bluetooth-control/bluetooth-shared.ts` | NUS UUIDs, 20-byte chunking, 5 ms pacing, GATT helpers. |
| `packages/web/app/lib/ble/web-adapter.ts` + `capacitor-adapter.ts` | Adapter-pattern reference for Android BLE transport. |
| `packages/db/scripts/import-moonboard-problems.ts` | Reference importer — JSON shape, layout-id mapping, batching. |
| `packages/db/scripts/moonboard-helpers.ts` | Grade map, UUID v5 derivation (`moonboard:{apiId}`), `coordinateToHoldId`, `movesToFrames`. |
| `embedded/libs/moonboard-protocol/src/moonboard_protocol.cpp` | Decoder reference — full 7-role grammar, aux-LED offsets, `~D` mode (not needed for client encoder but useful for understanding wire format). |
| `packages/moonboard-ocr/` | Screenshot-import bridge — track for v0.2.x. |

What NOT to copy from BoardSesh:
- The ESP32 firmware (`embedded/projects/moonboard-dev-server/`)
  — that's a dev-preview / DIY-board tool, orthogonal to
  CruxCoach's BLE-client direction.
- Their MoonBoard climb-creator UI — out of v0.2.0 scope.

Memory: [[reference-boardsesh-moonboard]].
