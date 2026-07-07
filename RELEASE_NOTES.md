# CruxCoach 0.2.1

Offline sharing that actually delivers, a more reliable connect & updater, and a batch of browser, lists and logbook improvements.

- **Offline share works end-to-end now** — receiving the board database from a friend's phone no longer fails at the end of the import; every board's climbs arrive intact.
- **Kilter login errors show up** — a wrong password or a failed logbook import no longer fails silently.
- **Calmer board connect** — retries happen quietly in the background (especially noticeable on Android 10); the "experimental" toggle is gone.
- **Updater fixes** — swiped-away update notifications come back, interrupted downloads resume, and checks aren't muted for hours after a reboot.
- **Board download over mobile data** — now possible as an explicit opt-in with a data-size warning.
- **"Newest" sort** — browse the catalogue by climb creation date.
- **Zone search** — drag a frame on the board photo to find climbs that live entirely inside it.
- **Share any climb** — catalogue climbs get share links too, opening straight in CruxCoach.
- **MoonBoard 2016 + 2024 adjustable** — both variants now offer 25° and 40°.
- **Lists show all your climbs** — saved lists show every climb you added, across all boards (each with its board badge), with a multi-select board filter for mixed lists. Off-board sends are refused with a clear message instead of lighting up wrong holds.
- **Honest logbook & stats** — "Sent · 3 tries" vs "Open · 3 attempts", grade-true pyramid buckets, a flash only counts if your first-ever go was the send, and day-streaks give way to weekly consistency.
- **Consistent draft badge** — a climb's published/draft state now looks the same in the browser and on the detail screen.
- **Faster first look at a newly-added board** — browsing a board right after adding it on its own is quicker.
- **Deleted community climbs stay deleted** — even on devices that only sync via the catalogue.
- **Portrait only** — landscape layouts are gone.
- **What's-new + announcements** — the update popup can't be dismissed by accident anymore; read announcements stay fully readable.

---

# CruxCoach 0.2.0 — the multi-board release

CruxCoach now speaks **MoonBoard** alongside Kilter, adds five more Aurora boards, puts board gyms on a world map, and refreshes the default LED colours.

## New boards
- **MoonBoard** — browse the full problem catalogue with the same filters as Kilter, open any problem, and light it up on your board over Bluetooth. Five variants (2016, Masters 2017, Masters 2019, Mini 2020, 2024), each shown on a real, measured board photo.
- **Tension, Grasshopper, Decoy, So iLL, Touchstone** — five more boards, fully interactive: browse, send over Bluetooth, read the hold heatmap, and create your own climbs.
- **One board picker** for everything — choose your board in onboarding or Settings.

## Find a board near you
- **Board map** — an interactive world map of board gyms (Kilter + MoonBoard) with brand, region and access filters, gym details and opening info, and a one-tap "browse this board". Gyms at the same place share a pin; busy areas cluster.
- **Find your gym** — search by gym name and pick your exact wall in one tap, no hardware knowledge needed. Plus an **egym Wellpass** filter to spot gyms that accept it.

## Climbing & logbook
- **Variable angle** — for climbs that exist at several angles, pick the angle your board is actually set to.
- **Sent-climbs history (Verlauf)** — your sends, logged automatically, with per-board badges and bulk delete.
- **Per-board stats** — read your hold heatmap one board at a time.
- **More browser filters** — combine New / Attempted / Sent, a projects-only view, hide unwanted climbs, a mounted-hold-set filter, reset-filters, and a Moves sort.

## More
- **Refreshed default LED colours** (start magenta, hand blue, finish green, foot red) — your custom colours stay untouched.
- A smaller, faster app, plus many fixes across the map, sync and Bluetooth.

_0.1.5 was never released on its own — its work is folded into 0.2.0._
