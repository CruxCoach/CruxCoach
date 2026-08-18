# CruxCoach 0.2.2 — 2026-08-15

Your lists become training sessions you can play, the board connection adapts to the controller in front of you, two more MoonBoards join the list — and app updates no longer hang on a single download server.

> **Android 8.0 and 8.1: this is the last version for your device.** 0.2.3 will need Android 9. CruxCoach now says so on affected devices instead of letting them wait for updates that can never arrive — the app keeps working and your data stays where it is.

## Highlights
- **Playlists — play a list instead of scrolling it** — one climb at a time on a full-screen player, sent to the wall as it comes up, a rest countdown that already shows what's next, *Sent it* / *Attempt* logging without leaving the screen, and a summary with total, active and rest time at the end. Every list you already have can be played; sharing a playlist works like sharing a climb.
- **Let CruxCoach plan the session** — pick a training type (pyramid, power endurance, volume, max strength, or working your projects), a duration, an angle and how warmed up you are. The grades come from your own logbook — the grade you repeat reliably and the one you flash — not from a fixed table. Or set the grade range, the number of climbs, the tries and the rests yourself and get exactly that.
- **CruxRelay — everyone in the session sends to the board** — most controllers talk to one app at a time, so one phone owns the wall and everyone else watches. Switch sharing on and your phone stands in for the board: other CruxCoach users join and send from their own phones, and the official board apps can send through you too. It runs only while you have it on, shows a notification throughout, and restores your phone's Bluetooth name when you stop.
- **Two more MoonBoards** — the original **MoonBoard 2010** and the **Mini MoonBoard 2025**, each on its own measured board photo. Problems now also say when the setter meant them to be climbed *footless* or *without the kickboard*, and a MoonBoard climb stays lit while you are working it instead of going dark.
- **One tap back to your last board** — the board you used last is offered as a card in the connect sheet; tapping it reconnects without scanning the room again. CruxCoach also works out on connecting whether your controller takes one app or several, and you choose per case whether a climb goes to the wall the moment you open it or only when you tap.
- **Updates no longer depend on one server** — CruxCoach asks every known source for the newest version and takes whichever has it, and that list is fetched at runtime, so a download server can be added or retired without anyone installing a new version first. An APK is still installed only after its checksum and signature match. You can also choose to have updates download or install themselves — off unless you turn it on.

## More improvements
- **MoonBoard LED position** — choose below, above or both on every MoonBoard;
  finish holds safely stay below, and below remains the default.
- **Delete one board's data, not all of it** — both *delete board data* and *delete logbook data* let you pick which boards they apply to. Your own climbs and community climbs are always kept.
- **Auto-disconnect can be switched off** — the board connection stays open until you disconnect it yourself.
- **Keep a session to yourself** — when you start one, you decide whether nearby CruxCoach users can see and join it.
- **A calmer browser** — the bar echoing your active board, layout, size and angle is gone; it only repeated what Settings already shows.
- **Playlists are called playlists** — the Kilter import no longer calls them "circuits".
- **CruxCoach speaks up when board updates stop arriving** — a background catalogue sync that keeps failing used to look exactly like a successful one.
- **An anonymous count of verified updates** — once an update is downloaded and verified, CruxCoach may report which version and which source, and nothing about you, your device or your installation. On by default, switchable off under Settings → Updates.
- **Small things** — a climb's published/draft state now looks the same in the browser and on its detail screen, and browsing a board right after adding it on its own is quicker.

## Fixes & reliability
- **Nobody is told to climb during a shared rest** — in a session you had joined, your phone could show the next climb as ready while the host was still resting and the wall showed something else. Rests now reach everyone, including anyone joining mid-rest.
- **No more downloading an update that cannot be installed** — after an interrupted update, CruxCoach could re-download and re-verify an older version on every check, only for Android to refuse it at the last step — a 34 MB download for a guaranteed failure, on mobile data too.
- **Sharing CruxCoach handed out an old app** — the share QR was built from the sharing phone's own version, so an older build pointed everyone at that older build. It now opens a download page that always resolves to the current release, with a fallback host.
- **Boards no longer fail on the very first download** — each catalogue retries before giving up, instead of one unlucky moment losing a whole board.
- **Hold and zone filters reset when you switch boards** — a filter drawn on one board no longer survives onto the next, where it matched nothing.
- **No "matching allowed" badge where nobody was ever asked** — it is a Kilter setting, so it no longer appears on MoonBoard problems or community climbs.
- **Turning Bluetooth on from the connect sheet no longer closes the app.**
- **The angle picker opens instantly**, swiping between climbs no longer hides the send button, and a re-download you asked for reports why it is waiting instead of appearing to do nothing.
- **Deleted community climbs stay deleted** — even on devices that only sync via the catalogue.

---

# CruxCoach 0.2.1

Zone search on the board photo, your Kilter circuits as lists, a share link for every climb and lists that span all your boards — plus offline sharing that actually delivers and a more reliable connect, import and updater.

## Highlights
- **Zone search** — drag a frame on the board photo to find climbs that live entirely inside it; combines with the hold filter.
- **Kilter circuits import as lists** — logging in with your Kilter account now also pulls your circuits as local lists.
- **Share any climb** — catalogue climbs get share links too, opening straight in CruxCoach.
- **Lists show all your climbs** — saved lists show every climb you added, across all boards (each with its board badge), with a multi-select board filter for mixed lists. Off-board sends are refused with a clear message instead of lighting up wrong holds.
- **MoonBoard 2016 + 2024 adjustable** — both variants now offer 25° and 40°.

## More improvements
- **"Newest" sort** — browse the catalogue by climb creation date, newest-first or oldest-first via the sort-direction toggle.
- **Board download over mobile data** — now possible as an explicit opt-in with a data-size warning.
- **Honest logbook & stats** — "Sent · 3 tries" vs "Open · 3 attempts", grade-true pyramid buckets, a flash only counts if your first-ever go was the send, day-streaks give way to weekly consistency, and sessions-per-week now divides by the full 8-week window.
- **Portrait only** — landscape layouts are gone.

## Fixes & reliability
- **Offline share works end-to-end now** — receiving the board database from a friend's phone no longer fails at the end of the import or times out while the sender prepares the transfer; every board's climbs arrive intact.
- **Kilter login errors show up** — a wrong password or a failed logbook import no longer fails silently.
- **Sturdier Kilter logbook import** — re-syncs keep your local edits, large logbooks import without crashing, transient errors retry, and failures show readable messages with a working retry.
- **Calmer board connect** — retries happen quietly in the background (especially noticeable on Android 10); the "experimental" toggle is gone.
- **Updater fixes** — swiped-away update notifications come back, interrupted downloads resume, checks aren't muted for hours after a reboot, a system hiccup during a download can't crash the app anymore, no more stale "install" notification after a successful update, and the install dialog arrives reliably on newer Android versions.
- **Climb links always open** — a shared climb link now works even while another climb's detail is already on screen.
- **Sturdier backup & dev messages** — cloud backups now target only servers verified to accept them (no more "partial" result on every backup), plus a third bootstrap relay; messages to the developer finish sending even if you leave the screen immediately.
- **What's-new + announcements** — the update popup can't be dismissed by accident anymore (the 0.2.1 highlights include a short 0.2.0 board recap for anyone who missed it); read announcements stay fully readable.

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
