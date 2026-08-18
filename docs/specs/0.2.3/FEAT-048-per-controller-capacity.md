---
status: planned
queue: queued
base: 0.2.3
depends_on: []
created: 2026-07-27
---

# Feature Spec: Remember controller capacity per device, not per brand

> **Decision:** what a controller can do is a property of that controller, not
> of the brand printed on it. The observation is stored against the device that
> produced it.

## 1. Problem and goals

CruxCoach probes, after connecting, whether a controller keeps advertising while
connected — the evidence that it accepts more than one client. The result drives
the send-mode split (single vs. multiple connection) and whether CruxRelay is
offered at all.

That observation is stored under one key per **brand**:

```
last_used_board_multi_client_<brand>
```

So every Kilter board a person ever touches shares a single verdict. Someone
with a multi-client board at home and an exclusive one at the gym gets the home
answer at the gym, and the app offers behaviour the gym board does not have.
The failure is silent: sends appear to work and simply do not arrive.

0.2.2 made the verdict correctable — a completed scan that observes nothing now
records a negative, the probe runs on every connect, and *Board settings →
Re-detect board capabilities* clears it by hand. That fixed staleness over
**time**. It does not fix the conflation across **devices**: connecting to the
gym board still overwrites the home board's verdict, and back again.

### Goals

- One verdict per controller, keyed by its BLE address.
- A board whose verdict is unknown behaves exactly as today's unknown does.
- No growth without bound, and no stale entries for controllers long gone.

### Non-goals

- Changing what the probe measures or how. Only where the answer is filed.
- Syncing verdicts between a user's devices.

## 2. Open questions — resolve before implementing

1. **Address stability.** Some BLE peripherals advertise a resolvable private
   address that rotates. For those, a per-address key never matches twice and
   the app is permanently in "unknown". Do the supported controllers use public
   addresses? If some do not, the key needs a fallback — serial number where the
   controller exposes one, brand otherwise — and the spec must say which.

2. **Eviction.** Per-device entries accumulate for every board ever connected
   to. Cap at N most-recently-used, or drop entries untouched for N months?
   A cap is simpler and bounded; a time limit matches how people actually use
   gyms.

3. **Migration.** An existing per-brand `true` was verified against *some*
   controller. Carry it to the currently remembered address for that brand
   (assumes the last-used board is the one it was measured on — usually right,
   not always), or discard and re-measure? Discarding costs one probe and is
   honest; carrying keeps behaviour on upgrade.

## 3. Acceptance criteria

- Connecting to board A (multi-capable) then board B (exclusive) leaves A's
  verdict intact; reconnecting to A offers multi-client behaviour without a new
  probe, while B stays exclusive.
- An unknown controller is probed on connect exactly as in 0.2.2.
- *Re-detect board capabilities* clears every stored verdict, not only the
  active brand's.
- Whatever eviction rule question 2 settles on is covered by a unit test with
  the boundary case at the limit.
- Upgrading from 0.2.2 does not leave a user worse off than the migration
  decision in question 3 describes.

## 4. Notes

Found on 2026-07-27 while testing CruxRelay against a board simulator: switching
the simulator from multi to single mode could not be reflected back, because the
verdict was write-once-true. That part is fixed in 0.2.2 (`UserPreferences.
lastUsedBoardAdvertisesWhileConnected`, `BoardCapacityProbe`). This spec is the
remaining half.
