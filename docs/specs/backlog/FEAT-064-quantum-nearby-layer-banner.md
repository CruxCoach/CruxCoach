---
status: backlog
queue: parked
base: post-0.2.2
depends_on: []
created: 2026-08-28
---

# Feature Spec: Quantum multi-layer Nearby banner

> **Status:** Backlog — explicitly excluded from v0.2.2.
>
> **UX decision:** The collapsed banner shows only a compact Quantum layer
> count. Climb names and layer colours appear only after the banner is opened.

## 1. Problem

The existing Nearby/on-board model describes exactly one projected climb. That
is correct for single-projection boards, but a Quantum controller can retain up
to four independent climbs at once. After a Quantum layer write, CruxCoach
therefore does not call the single-climb `advertiseClimb(...)` path. Nearby
devices see at most "board occupied", while the sending device can retain a
stale single-climb banner from an earlier board.

Advertising only the most recently sent Quantum climb would be actively
misleading: it would hide the other physically lit layers. Sending all layer
UUIDs in one legacy BLE manufacturer payload is also impossible within the
current 24-byte payload budget.

## 2. Required UX

### 2.1 Collapsed

The collapsed status banner contains no climb name and no colour swatches:

```text
Quantum · 3 Climbs aktiv                       ▾
```

Use the correct singular form for one layer. The count is the number of
controller-confirmed physical layers, not the number of locally assigned rows.

### 2.2 Expanded

Opening the banner reveals one row per confirmed physical layer:

```text
Quantum · 3 Climbs aktiv                       ▴

● Blue Monday                         40°
● Compression Session                 40°
● Unbekannter Climb                   40°
```

- The dot uses the exact controller-confirmed layer colour.
- A locally resolved layer shows its climb name and angle.
- An unresolved foreign route remains visible as `Unbekannter Climb`; its
  confirmed colour is still shown.
- A resolvable row may navigate to Climb Detail. An unresolved row is not
  clickable.
- Names and colours must never leak into the collapsed summary.

## 3. Source of truth

The local UI derives Quantum occupancy from the authoritative
`BoardLayerManager.state` controller snapshot:

- Include owned layers only when `confirmedRouteUuid` is present.
- Use `confirmedClimbUuid`, `confirmedClimbName`, `confirmedColor` and the
  confirmed route's angle/metadata, never a staged replacement preview.
- Include `externalLayers` because they are physically lit even though another
  client owns them.
- Exclude `PREVIEW`, `SENDING`, failed plans and any last-known rack whose
  Quantum controller sync is not authoritative/live.
- When an authoritative Quantum rack is present, suppress the legacy
  `BoardStateManager.lastClimb` single-climb banner. It cannot describe the
  wall truth and may belong to a previously selected board.

Introduce a presentation model separate from `OnBoardClimbEntry`, for example:

```kotlin
data class QuantumBoardLayerSummary(
    val climbUuid: String?,
    val name: String?,
    val angle: Int?,
    @ColorInt val color: Int,
    val ownedByThisInstallation: Boolean,
)
```

`BleShareUiState` carries the ordered layer summaries. The order is stable:
owned slot order first, followed by external controller order.

## 4. Nearby transport

The legacy `ClimbData` packet remains single-climb and must not be reused for a
Quantum rack.

A backward-compatible `BoardConnected` extension may advertise:

- board family = Quantum;
- confirmed layer count (0–4);
- existing disconnect/concurrency flags and sender token unchanged.

Old receivers continue to render `Board besetzt`. New receivers may render the
compact `Quantum · N Climbs aktiv` summary. Full names, UUIDs and colours are
not packed into rotating advertisements: rotation would be lossy, reorder rows
under packet loss and make the displayed rack depend on scan timing.

Full remote layer details require an explicit structured channel (an existing
session/GATT connection or a later dedicated read contract). Until that exists,
remote expanded UI shows only information actually received; it must not invent
a last-sent climb as the whole rack.

## 5. Interaction with playlists and sessions

- A private Quantum playlist uses the same physical-rack summary as direct
  layer sends.
- The playlist mini-player remains the queue/pacing UI. The Quantum banner is
  wall truth and appears only where it adds information rather than duplicating
  the current queue item.
- A joinable session may still advertise its current queue item separately.
  That item is not a replacement for the Quantum rack summary.
- Removing or replacing one layer updates the count and expanded rows
  atomically from the next authoritative controller snapshot.

## 6. Acceptance criteria

1. With two confirmed Quantum layers, the collapsed banner says only
   `Quantum · 2 Climbs aktiv`; neither name nor colour is present.
2. Expanding shows exactly two rows with their controller-confirmed colours,
   names and angles.
3. Assigning a preview does not change the banner until controller confirmation.
4. Replacing a layer never briefly shows both its old confirmed climb and new
   preview as physical layers.
5. An unresolved external route increments the count and renders an unknown row
   with its real confirmed colour.
6. Disconnecting or losing authoritative Quantum state does not label a stale
   rack as live.
7. A stale single-climb value from Kilter/MoonBoard is suppressed while the
   authoritative Quantum rack is active.
8. Non-Quantum single-climb and Nearby behaviour is unchanged.
9. Legacy receivers safely fall back to `Board besetzt` for an extended Quantum
   presence packet.
10. Unit tests cover collapsed-summary privacy, expanded rows, preview
    exclusion, replacement state, unresolved external layers, stale-state
    suppression and backward-compatible protocol decoding.

## 7. Non-goals

- Shipping this feature in v0.2.2.
- Advertising four climb UUIDs or names in one legacy BLE packet.
- Treating the latest Quantum write as the only climb on the wall.
- Making unresolved foreign controller routes clickable.
- Changing Quantum coexistence, overlap or layer-allocation rules.

