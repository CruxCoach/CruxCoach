# Changelog

All notable changes to CruxCoach will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.2.1] - Unreleased

A follow-up to the 0.2.0 multi-board release: the offline share now
actually delivers the board database, connecting and updating got more
reliable, and the browser, lists, logbook and stats each pick up a
practical improvement.

### Added
- **"Newest" sort** in the climb browser — order the catalogue by when a
  climb was created, newest first.
- **Zone search** — in the hold search, drag a frame on the board photo
  and only climbs that live entirely inside that area match; combines
  with the hold filter.
- **Share any climb** — community climbs keep their existing link form;
  catalogue climbs (and community climbs that arrived via the daily
  catalogue) are now shareable too. Every link opens directly in
  CruxCoach.
- **MoonBoard 2016 and 2024 are adjustable** — both variants now offer
  25° and 40° in browse, detail and the creator, matching the official
  catalogue. (Their 25° problems appear once the published board
  snapshot next refreshes.)
- **Weekly consistency instead of day-streaks** — rest days are part of
  training, so day-streaks read 1–3 forever. The records row now shows
  average sessions per week (last 8 weeks) and a week streak —
  consecutive calendar weeks with at least one session; a rest day never
  breaks it, a full week off does.
- **Board download over mobile data (opt-in)** — the initial board
  database download can run over mobile data after an explicit
  confirmation with a data-size warning; an explicit per-board reload no
  longer waits for WiFi either.

### Fixed
- **Offline share: the board-database import works now.** Receiving the
  board database from a friend's phone aborted with an error right at
  the end of the import. The receiver now understands the shared
  database format: every board's climbs stay on their board, the
  sender's private drafts stay private, and gym locations come along.
  The sender also serves a consistent snapshot of its database, so a
  sync running in parallel can no longer corrupt the transfer.
- **Kilter login errors are visible now** — a wrong password or a failed
  logbook import used to fail silently; every login and import failure
  now surfaces in onboarding and in Settings.
- **Calmer, more reliable board connect** — connection retries happen
  quietly in the background (noticeable especially on Android 10) and
  the legacy "experimental" connect toggle is gone.
- **In-app updater reliability** — an update notification you swiped
  away comes back; dismissing a version stops muting newer ones; a
  download interrupted by a process kill resumes instead of stranding;
  and update checks are no longer throttled for hours right after a
  reboot.
- **Board sync survives a failed chunk** — a single failed chunk
  download no longer aborts the whole board-database refresh.
- **What's-new popup could be dismissed by accident.** On the first
  launch after an update, the highlights dialog could be closed by
  tapping outside it or pressing Back, which silently marked it as seen
  — so some users never actually read it. It now closes only via its
  buttons. Because of this, the 0.2.0 highlights are shown once more on
  0.2.1 for anyone who missed them.
- **Announcements stayed collapsed once read.** Tapping an announcement
  marked it read but also truncated it with no way to expand again; read
  announcements now stay fully readable.
- **Logbook labels lead with the outcome** — sends now read "Sent · 3
  tries" ("Top · 3 Versuche"), open projects "Open · 3 attempts"
  ("Offen · 3 Versuche"), so the two can't be confused; true flashes
  stay "Flash".
- **Grade-true stats, honest flashes** — the grade pyramid (and related
  charts) grouped Font grades through the V-scale, so a 7b top could
  display as 7b+; buckets now follow the displayed grade directly. And a
  first-try send only counts as a flash if it was the first logbook
  contact with that climb at that angle — attempts in earlier sessions
  disqualify it.
- **Backup restore is now lossless.** Restoring a backup (file import or
  Nostr cloud backup — same format) used to fold the built-in "Ignored"
  list into Favorites (hidden climbs resurfaced in browse — and came back
  favorited), strip Aurora circuits of their color/description and their
  re-import idempotency key, drop the Kilter authorship that keeps an own
  published climb re-publishable, reset benchmark markers, detach
  boulders from the workout they were logged in, and silently skip
  legitimately-distinct same-day log entries as "duplicates". All of
  these now round-trip exactly.
- **Offline share: private drafts never leave the device.** The served
  board database is a checkpointed snapshot with the sender's unpublished
  drafts (and the Kilter publish log) removed *before* transfer —
  previously they travelled along and were only filtered out during the
  receiver's import. The snapshot is also WAL-safe now (no more missing
  newest climbs in rare timing windows), and sharing between different
  app versions aborts cleanly up front instead of half-importing.

### Changed
- **Saved lists show all your climbs, across every board** (FEAT-023) — a list
  (Favoriten + custom lists) is your own selection, so it's no longer filtered
  down to your currently-configured board. Every entry appears with its board
  badge (Kilter Original vs Homewall now distinguished). Sending a climb that
  doesn't fit your active board is refused with a clear message — including the
  case where none of its holds map to your board — so nothing lights up
  incorrectly. When a list spans several boards, a **multi-select** filter row
  lets you narrow it — pick one or several boards at once, a whole brand
  ("alle MoonBoard"), or a specific variant (MoonBoard Masters 2019, Kilter
  Homewall). Entries whose board catalogue isn't downloaded are flagged
  ("N not shown"), not silently hidden.
- **Portrait only** — the app is locked to portrait orientation.

## [0.2.0] - 2026-06-21

> 0.2.0 is the multi-board release. CruxCoach now speaks **MoonBoard** as
> well as Kilter: browse the MoonBoard problem catalogue with the same
> filters you already know, light up problems on your physical board over
> Bluetooth, and pick your MoonBoard in onboarding or Settings next to the
> Kilter variants. The board-locations map and the find-your-gym picker
> that grew on the 0.1.5 line ship here too — now spanning both board
> families, with the map showing MoonBoard gyms worldwide. The default LED
> colours also got a refresh, with a one-time migration that leaves your
> custom colours alone.
>
> (0.1.5 was never released on its own — its work is folded into 0.2.0.)

### Added
- **MoonBoard support** (FEAT-027) — CruxCoach's first non-Aurora board.
  Browse the MoonBoard problem catalogue with the same browser surface as
  Kilter (grade / angle / setter / ascensionist filters all apply), open a
  problem detail screen, and send it to your physical MoonBoard over
  Bluetooth (Nordic UART). Five variants ship: **MoonBoard 2016 (40°)**,
  **Masters 2017** and **Masters 2019** (25° + 40°), **Mini 2020**, and
  **MoonBoard 2024 (40°)**. The 2024 set landed after our problem-catalogue
  dump, so for now it carries only the BoardSesh-community problems; the
  other four ship the full MoonBoard catalogue.
  Pick a MoonBoard in onboarding or Settings; the always-on
  *"passt auf mein Board"* fit filter is brand-aware.
- **Real board imagery for MoonBoard** (FEAT-029) — each variant renders
  on CruxCoach's own measured board photo with a per-hold coordinate map
  instead of a generic grid; a procedural grid (11 columns × the variant's
  row count — 18, or 12 for Mini 2020) remains the fallback.
- **Unified board picker** — every picker site (onboarding + Settings +
  the map's "browse this board") now offers the same three categories:
  Kilter Original / Kilter Homewall / MoonBoard.
- **MoonBoard gyms on the map** — the board-locations map and the
  find-your-gym picker now span both families. A **brand filter**
  (Kilter / MoonBoard / all) joins the existing filters, MoonBoard gyms
  render in a distinct colour, and searching a MoonBoard gym resolves it
  to the right variant. Offline model unchanged — locations still arrive
  in the synced data, no live fetch.
- **Map venue grouping + clustering** — boards at the same place (a gym
  with both a Kilter and a MoonBoard, or Original + Homewall) collapse to
  one pin whose detail sheet lists each board; dense regions cluster into
  count bubbles that expand on tap. Keeps the map legible now that two
  catalogues' worth of installations are plotted.
- **Tension, Grasshopper, Decoy, So iLL and Touchstone** (FEAT-031) — five
  more Aurora-protocol boards join Kilter and MoonBoard as fully interactive:
  browse each board's catalogue with the same filters, open a problem, render
  it on the board, read the hold heatmap, light it up over Bluetooth, and set
  your own climbs (published to the CruxCoach community). Pick one in Settings →
  board model. Their catalogues sync from Blossom like Kilter's; Tension also
  pulls a daily delta. Authored climbs stay on their own board — a Tension climb
  never shows up on Kilter, and Kilter keeps receiving Kilter climbs published
  by any version.
- **Variable climb angle** (FEAT-033) — climbs that exist at more than one
  angle now expose a board-specific angle control in both the climb browser
  filter and the Climb Creator, so you pick the angle your board is actually
  set to. The setter's original angle is shown as info. MoonBoard keeps its
  fixed per-variant configs.
- **Logbook "Verlauf" / sent-climbs history** (FEAT-032) — a history of the
  climbs you've sent, recorded automatically on a board-send (deduplicated,
  in your configured grade scale). Each entry carries a board badge and a
  board comparison, you can multi-select entries to delete, and the screen
  is fully localised.
- **Per-board stats heatmap selector** (FEAT-039) — the statistics sheet now
  has a board dropdown, gated to the boards you've actually logged on, so the
  hold heatmap can be read per board instead of mixing catalogues.
- **Combinable status filter in the browser** — the single status chip is now
  a multi-select set: **Neu** / **Versucht** / **Geschafft** combine as an
  OR-union (empty = Alle), unlocking views like "Versucht only" (open
  projects) or "touched, not new". Plus an **"ungraded only (projects)"**
  browse mode (ungraded climbs hidden by default) and an **"ignore unwanted
  climbs"** action so dismissed climbs stop being suggested.
- **Mounted-hold-set (hsm) filter** in the browser — narrow the list to
  climbs that use only the hold sets actually mounted on your configured
  board.
- **Browser quality-of-life** — a reset-filters button and scroll-position
  preservation when returning from a climb detail; a filter-terms info dialog
  and a **Moves** sort option; and a tap-to-zoom board preview in the board /
  gym picker.
- **Climb-list detail board badges** — each climb in a list now shows a
  board-brand badge, and the list detail reconciles its count as
  *"X of Y on this board"* against the global list count.
- **Foreign-brand map layer + egym Wellpass** — Aurora and 12climb
  installations appear as a toggleable "Other boards" info layer on the map
  (locations only — no catalogue or Bluetooth send for these two). A new
  **egym Wellpass** filter + venue badge flags gyms that accept it. Curated
  overrides correct a handful of mis-classified venues.
- **Board Locations Map** (FEAT-015) — interactive world map of all
  known Kilter Board installations, rendered locally with MapLibre +
  OpenFreeMap (no Google Maps, no API key, no proprietary tiles).
  Filters for layout family (Original / Homewall), Public-only,
  *Matches my board*, country, access type, adjustability, and size.
  Tap a marker for a detail sheet with address, phone, email, website,
  Instagram, and a "Browse climbs for this board" deep-link into the
  catalog filtered to that exact layout + size. Stats tab aggregates
  the visible markers by country, access type, adjustability, and
  size. Public-only by default; Homewall installations off until the
  user opts in.
- **Find-your-gym picker** (FEAT-007 Phase 1) — *Settings → Board-Größe
  → Ändern → Halle suchen* searches the locations dataset by gym name
  and lists the physical walls at the matching gym, ordered by how
  common that wall configuration is across all gyms. Picking a wall
  applies the right layout + product-size in one tap, no hardware
  knowledge required. The dialog still has a manual size list as the
  fallback path.
- **Always-on Board-Fit filter** in the climb browser — climbs that
  cannot exist on the user's configured board (wrong edge geometry)
  are filtered out of every list view. The filter is intentionally
  not user-togglable.
- **Map data attribution** — `NOTICE`, `THIRD_PARTY_LICENSES.md`, and
  `LEGAL.md` now cover MapLibre Native, mapbox-android-gestures,
  OpenFreeMap, OpenMapTiles, OpenStreetMap (ODbL), and the
  `@hangtime/climbing-boards` dataset.

### Changed
- **Community-climb authoring extended to every interactive board**
  (FEAT-031) — the Climb Creator no longer only authors Kilter climbs. The
  Aurora-family boards (Tension, Grasshopper, Decoy, So iLL, Touchstone) and
  MoonBoard can now author climbs published to the CruxCoach community,
  board-scoped so a climb stays on its own board. Kilter additionally gains
  publishing of your **own authored** Kilter climbs (surfaced in detail, the
  my-climbs list, and the logbook), and a backfill of your own authored and
  logged climbs from the Kilter API so PowerSync-only climbs (the ones the
  REST catalogue doesn't return) still render.
- **Logbook entries show more provenance** — a mirror-indicator badge on
  logbook entries (now a visible, non-clipping badge), and the log comment is
  shown both in logbook entries and in the climb-detail history.
- **Default LED colours refreshed** — the CruxCoach preset is now
  start = magenta, hand = blue, finish = green, foot = red (previously
  orange / blue / magenta / mint). A one-time migration on first launch
  moves anyone still sitting on a *previous* default preset onto the new
  one; genuinely custom colours and the official Kilter preset are left
  untouched.
- **Board database re-partitioned for two catalogues** — folding in the
  MoonBoard catalogue roughly doubled the board DB. Browse/count queries
  are now partitioned by board layout (layout_id denormalised onto
  `climb_stats` with rebuilt covering indexes), so a query for one board
  no longer scans past the others and detail/browser load stays fast.
- **BLE transport rework** + board-browser sort options, plus a slimmer
  APK (arm64-only native libs).
- **Connection-sheet permission flow** — opening the BLE connection
  sheet now re-checks the runtime permissions instead of relying on
  the cached pre-onboarding answer, so users who revoked permission
  in OS settings get prompted again at the right moment.
- **Singleton-init failure logging** — the eleven `runCatching {
  ... }.onFailure { ... }` sites in `CruxCoachApp.onCreate` now log
  via `Log.w` with attached stack traces instead of `Log.d` (which
  R8 strips from release builds). Triaging "X failed silently on
  startup" reports is no longer a guessing game.

### Fixed
- **MoonBoard send used the wrong variant** when the climb's board differed
  from the configured one (e.g. opening a Mini 2020 climb from a list while
  set to MoonBoard 2016) — the BLE encoder now takes the variant from the
  climb being sent, not the active-board preference, so the lit holds match.
- **MoonBoard browser was empty** on first open — the Blossom manifest
  parser required a `productId` the MoonBoard catalogue chunk doesn't
  carry; it is now optional, so MoonBoard climbs import and list.
- **Slow MoonBoard detail / browser open** — a shared per-angle index was
  scanned across all catalogues; the layout-partitioned indexes (above)
  bring opening a MoonBoard problem back to near-instant.
- **BLE menu stayed open after a MoonBoard auto-connect** — the
  connection sheet now closes on auto-connect for MoonBoard the same way
  it does for Kilter.
- **Sync banner briefly read "done" mid-sync** — during the MoonBoard
  phase the two-catalogue sync banner no longer leaks the Kilter phase's
  completed state; it shows the active phase through to finalising.
- **Map screen no longer dead-ends silently.** `MapViewModel.init`
  now catches DB / flow throws (e.g. the brief schema-migration
  window on a 0.1.4 → 0.2.0 upgrade), exits the loading state, and
  surfaces a snackbar instead of staring at an infinite spinner.
- **Tile-server outage now visible.** A 4 s reachability probe runs
  once when the map opens; if OpenFreeMap is unreachable the user
  gets a *"Kartenanbieter nicht erreichbar"* snackbar instead of a
  grey canvas with markers and no explanation.
- **Marker actions no longer crash.** Phone / email / web / "open
  in Maps" intents on `BoardLocationDetailSheet` are wrapped in a
  safe-launch helper that catches `ActivityNotFoundException` and
  shows a toast, so devices without a dialer / mail / browser don't
  bring the app down.
- **Backfill cannot wipe locations on an empty source chunk.**
  `BoardDatabaseImporter` now refuses to `DELETE FROM` the local
  `kilter_board_location` / `kilter_board_wall` tables when the
  attached source chunk has zero rows; a pipeline glitch can no
  longer silently empty the map after a sync.
- **Backfill cache files no longer race the full sync.** The
  one-time locations backfill writes its chunk cache to
  `blossom_backfill_…sqlite3` instead of the same path the regular
  full-sync uses, closing the cross-coroutine cache collision
  window during the 0.1.4 → 0.2.0 upgrade.
- **Backfill cannot hang forever.** A 120 s wall-clock cap on
  `backfillLocationsIfMissing` retires stalled chunk downloads
  (captive portal, dead TCP socket) that previously pinned the
  in-app *"Standorte werden geladen"* indicator until the next
  process restart.
- **Picker no longer disables silently on transient DB failure.**
  `GymBoardPickerViewModel` wraps its three coroutine launches in
  `try/catch`, so a brief read error in `countWalls()` /
  `productSizeFrequency()` / `searchLocations()` no longer leaves
  the picker permanently dark with no log trail.
- **Audit trail for `replace-all` imports.** `importLocations` /
  `kilter_board_wall` imports now log a release-visible
  *before → after row count* line, so support requests about
  "my map is empty / suddenly different" have something to grep.

### Security
- **Marker-action input sanitisation** (FEAT-015 hardening) — the
  phone / email / website rows in `BoardLocationDetailSheet` now
  validate their inputs before launching an intent. Phone strings
  are stripped to a dialer-safe character class (`[0-9+\-() ]`) and
  rejected entirely if no digits remain; mailto launches go through
  `EXTRA_EMAIL` against `ACTION_SENDTO` with a `Patterns.EMAIL_ADDRESS`
  check, closing the previous header-injection arm (`?subject=…&bcc=…`)
  where a malicious upstream entry could pre-compose the user's
  mail client; website launches parse the URI and accept only the
  http / https schemes (`httpx://`, `javascript:`, `intent://`,
  `file://` are now rejected, where the previous `startsWith("http")`
  guard would let `httpx://attacker.example` through).

### Internal
- **Test coverage backfill** — new unit tests for `MapFilters.apply`,
  `MapStats.from`, `GymBoardPickerViewModel`; a JDBC-driver
  integration test suite for `BoardLocationRepositoryImpl`;
  `MigrationSmokeTest` extended to cover the FEAT-015 tables;
  `FakeBoardLocationRepository` test fake added.
- **MoonBoard + multi-brand test coverage** — `BoardBrand` /
  `MoonBoardVariant` domain tests, the MoonBoard BLE frame encoder,
  brand-aware `MapFilters` and gym-picker variant resolution, the
  `board_brand` location schema/repository round-trip, and the
  one-time LED default-colour migration (every branch).

### Internal / Build
- **Core-library desugaring + lint gate** — the build now enables core
  library desugaring, backporting newer `java.*` APIs into the APK so they
  resolve on older Android, with a lint gate to catch old-API regressions.
- **Old-API crash fixes for Android < 12** — the expedited board-sync /
  update workers now override `getForegroundInfo`, so they run on Android
  before 12 instead of crashing; and two `java.util` SequencedCollection
  call sites (e.g. on the dashboard) that threw `NoSuchMethodError` on
  Android 26–34 are fixed.

## [0.1.4] - 2026-05-18

> 0.1.4 turns CruxCoach from a Kilter-catalog viewer into a small
> climbing-community-on-Nostr. You can now set your own climbs, share
> them with other CruxCoach users, see what others have set, pick a
> profile, and migrate your old Kilter-app data into the app.

### Added
- **Climb Creator** — set your own climbs on the board image, save
  drafts (with autosave), publish to other CruxCoach users via Nostr
  (Kind-30078), optionally push to your own Kilter account so they
  show up in the Kilter app too. Heatmap shows where popular
  hand/start/finish holds typically go for the current layout +
  angle. Frames are validated before publish.
- **Community climbs in BoardBrowser** — climbs other CruxCoach
  users have published appear next to the Kilter catalog. Origin
  filter chip lets you filter to CruxCoach / Kilter / All.
- **Setter detail page + setters list** — see who's been publishing
  in the community and browse their climbs. A community badge on
  each climb's detail screen links to the setter.
- **Edit my published climb** — load any climb you previously
  published into the Creator, edit, and re-publish. The Nostr d-tag
  stays stable so the original event is replaced, not duplicated.
  Kilter side does an UPDATE if previously synced there.
- **Live Nostr subscription for community climbs** — climbs
  published while you're online appear immediately, not after the
  next daily Blossom snapshot.
- **My-climbs filter** in BoardBrowser surfaces just your own
  published + draft climbs.
- **Nostr profile editor** in *Settings → Profile* — display name,
  picture, banner, NIP-05 identifier (with on-blur verification),
  Lightning address (with on-blur LNURL probe), and a markdown bio.
  Without this, your community climbs show up as `npub:<hex>...`.
- **Aurora migration** in *Settings → Migrate from Aurora* — import
  the JSON Aurora emails after a data-export request: ascents,
  attempts, circuits, and bid history land in your local logbook.
  Re-importing the same file is safe.
- **Auto-Note** (optional) — when you publish a community climb you
  can also fire a Kind-1 note to your relays announcing it. Off by
  default; editable template per climb.
- **Kilter publish (opt-in)** — *Settings → Kilter publish* toggles
  whether your community climbs are also pushed to Kilter. 6-hour
  retry worker drains transient failures. On-Kilter badge on the
  detail screen when a climb is synced to both surfaces.
- **Homewall support** — new 7-row Homewall layout is selectable in
  onboarding and via the size-picker.
- **Cloud-Backup now covers your own climbs** — v3 envelope includes
  published + draft climbs so they restore on a new device.

### Changed
- **Onboarding** auto-starts the board-database download the moment
  you reach that step.
- **Board-database import is noticeably faster on mid-range devices**
  — shared SQLite connection across chunks, WAL tuning, and a
  join-based normalisation pass.
- **Internal table renames** — board DB moved from the historical
  `aurora_*` prefix to plain plural names (`climbs`, `climb_stats`,
  …). Migration runs once on first launch; BLE class names lost
  their Aurora prefix to match.
- **Quality rating** in climb-detail accepts 1–5 stars (was 1–3)
  — Kilter migrated to a 5-star scale.
- **Detail-screen + Logbook toolbars** cleaned up — redundant
  titles dropped, overflow menu consolidated so the top bar doesn't
  overflow on narrow screens.
- **Mirror toggle moved into the climb-detail ⋮ overflow menu** — it
  was a full-width centered button between the stats and the board
  image; it's now the first item in the overflow, reclaiming that
  vertical band on every climb-detail view.

### Fixed
- **Cloud-backup deletion now sticks across devices** — pre-fix,
  tapping *Delete remote backups* could silently leave a
  recoverable copy on the index servers, because some don't honour
  the standard deletion event for replaceable items: a fresh
  restore on a new device would still find and import the backup.
  CruxCoach now also publishes a deletion marker that every relay
  must honour, so the next restore returns "no backup found"
  regardless of how thoroughly the storage servers complied. The
  confirm-dialog copy was reworded to reflect that the marker is
  the privacy guarantee and a partial storage-server result (e.g.
  1/2) is no longer presented as a security caveat. Transient
  DELETE timeouts on storage servers retry once.
- **Climb detail screen now picks the right physical board for
  every climb** — Aurora-imported climbs and any cross-board
  community climb (Homewall climb on an Original-12×12 user, a
  cropped sub-route whose extent exceeds the user's smaller
  variant, etc.) now render against a board whose edges actually
  contain the climb's holds. Pre-fix the renderer always used the
  user's Settings board, which clipped start + finish holds off
  the canvas for cross-board entries and used coordinate edges
  tuned for the wrong physical wall.
- **Climb detail screen no longer opens the wrong climb when
  navigating from Setter pages** — pre-fix tapping a climb on a
  setter's profile silently routed to the *first* climb of
  whatever browser/logbook list was last open, because the pager
  inherited stale list context. The detail screen now drops to a
  single-page render of the actual tapped climb when the cached
  list is no longer relevant.
- **Drafts no longer present as published in the detail screen** —
  Aurora-imported drafts (and any other never-published row)
  used to show the "CruxCoach community" provenance chip and
  offer "Veröffentlichung löschen" as the delete action — which
  failed because there was no Nostr event to tombstone. The
  chip now only appears for climbs that actually reached a
  relay, and the delete menu routes drafts to a local-only
  delete that succeeds and matches the action label.
- **Cloud-backup restore shows progress instead of freezing** —
  a fresh-install restore blocks on the board-database
  download to avoid a SQLite race; the dialog used to look like
  a hard freeze for the 1–3 minutes that took. It now shows a
  spinner with explicit "waiting for board-database download"
  and "restoring backup" phase copy.
- **Auto-backup interval finally persists across cold restarts** —
  previously, the daily / weekly cadence you picked could silently
  revert to whatever the board-sync was on (or disappear entirely
  if board-sync was on Manual).
- **Onboarding and Aurora-migration screens recover from internal
  errors** — instead of throwing you back to a fresh-install state.
- **Stuck "logging in" / "importing" spinners** when a backend call
  threw transiently — the spinner clears, an error surfaces, and
  the user can retry.
- **Aurora import survives bad rows** — a single malformed entry
  no longer rolls back the whole import; oversized files are refused
  with a clear message instead of crashing the import.
- **Community-climb sync queue** — if an incoming climb temporarily
  fails to save (disk pressure, lock contention), it's parked in a
  durable retry queue and re-imported on the next start instead of
  being lost.
- **Profile metadata stays correct on slow relays** — the in-app
  cache compares each incoming Kind-0 against the event's own
  timestamp, so older versions can't overwrite a newer one.
- **Tombstoned climbs propagate** — upstream-deleted climbs are
  marked `is_deleted=1` locally instead of being silently re-inserted
  on the next bulk import.
- **Cross-published climb deletion now actually hides the climb** —
  when you delete a community climb that was also pushed to your
  Kilter account, every other CruxCoach device used to keep the
  climb visible in BoardBrowser indefinitely. Three pre-fix gaps:
  (a) the daily catalog refresh kept re-introducing the row from
  Kilter's API, (b) the chunked Blossom bundle stripped tombstones
  before publishing them, and (c) the app-side delete handler
  silently no-op'd on rows whose local provenance was Kilter-flavoured.
  All three layers now treat the Nostr tombstone as the source of
  truth: an explicit delete propagates through the daily snapshot
  and through the live Nostr subscription within seconds.
- **Deleted climbs stay useful in your logbook** — the climb's name,
  grade, holds, and send-count are preserved on the device after the
  setter deletes; only the BoardBrowser visibility flips. Pre-fix a
  delete erased the metadata that the logbook still wanted to render
  for climbs you'd already attempted.
- **Live Nostr subscription doesn't flood the relay on cold start** —
  a fresh install now waits up to 30 s for the daily Blossom snapshot
  to arrive before opening the live subscription, so the relay only
  has to stream the ~24 h delta instead of all-of-history. Pre-fix
  the subscription opened first and the resulting WebSocket buffer
  blew up writer-lock contention against the bulk importer running
  in parallel.
- **Setter display names update when their profile changes** — the
  in-app Kind-0 cache now refreshes after a 30-minute TTL instead of
  pinning the first-seen snapshot forever. New display names land on
  the next setter-page open via a two-phase load (cached snapshot
  immediately, fresh fetch in the background).
- **CruxCoach setter-source filter shows community climbs first** —
  the BoardBrowser filter ran client-side over a sends-sorted SQL
  pagination, so low-send community climbs landed on page 50+ and
  were invisible without manual scroll. The filter now pre-narrows
  at the SQL level and orders by stat-quality.
- **CruxCoach metadata preserved across Kilter blob refreshes** —
  your Nostr provenance, frames-hash, sync-status, and
  Kilter-publish flags survive the daily catalog refresh.
- **Climb-creator UX** — empty-brush tap deletes the hold, drag-to-
  move works, V-scale slider has live validation, German climbing
  terms throughout, drafts re-open without showing autosave state.
- **Heatmap performance** — parsed frames cached + fast IntArray
  parser; no frame drops on layouts with hundreds of climbs.
- **Filters persist** — origin filter + "My climbs" toggle survive
  app restarts.
- **Setter usernames** — BoardBrowser uses an npub stub when the
  cached display name is missing instead of an empty label.
- **First launch after this update no longer looks frozen** — the
  one-time database preparation that runs once after updating now
  shows a clear "Preparing the climb database…" message with a
  keep-the-app-open hint instead of a bare spinner. The preparation
  itself is roughly 3× faster on slower devices, and it is atomic:
  if the app is closed mid-preparation it simply re-runs cleanly on
  the next launch — no data loss or corruption.

### Security
- **Every incoming community-climb event is signature-verified** —
  Quartz `Event.fromJson` recomputes the canonical event id +
  `verifySignature()` runs before persisting. Mirrors the existing
  pattern at NostrProfileManager / BlossomSyncManager / BackupRepository.
- **Author-uuid guard** — events whose d-tag prefix or content
  `pubkey_prefix` claims a different author than the signed pubkey
  are dropped; a UUID owned by author A cannot be overwritten by an
  event from author B (first-author wins).
- **Refresh-token revocation on logout / re-login** — both the
  re-login path and the one-time-import path now revoke the prior
  Keycloak refresh token server-side before clearing it locally,
  closing the 30-day stolen-credential window.
- **Manual backup-import pubkey binding** — importing a backup from
  a different Nostr key requires an explicit toggle with a persistent
  warning that imported climbs will publish under your current
  account if you republish. The default path hard-refuses the
  mismatch.
- **Climbs imported via backup can no longer be auto-republished
  under your active Kilter account** — the background retry worker
  is now scoped to the active Nostr identity.
- **Profile-editor NIP-05 + LNURL fetches bounded** — server
  responses capped at 64 KB and the LNURL probe refuses to follow
  redirects, so a hostile endpoint can't OOM the editor or redirect
  probes onto your LAN.
- **Kilter publish payload not logged in release builds** — debug
  logs that previously emitted the outgoing climb JSON (name,
  description, username) are stripped from release APKs via ProGuard.
- **Display name used on Kilter never falls back to email** — a
  resolution chain ensures your public Kilter handle is either your
  registered display name or a neutral placeholder, never the email
  you logged in with.

### Notes
- This is the first release that publishes user-authored climbs
  publicly on Nostr (when you tap *Publish* in the Climb Creator).
  Identity defaults to your existing CruxCoach Account key (same
  as encrypted cloud backup). Auto-Note Kind-1 + Kilter API push
  are both opt-in.
- The community-climb deletion path can remove a climb from
  CruxCoach + Nostr relays, but cannot remove it from Kilter —
  Kilter's API has no delete endpoint and enforces this for every
  client. The delete confirm-dialog warns you of this.
- `SECURITY.md` has been updated with the new attack surface:
  community-publishing chain, Kilter API self-account writes,
  Auto-Note Kind-1.

## [0.1.3] - 2026-04-26

### Added
- **Encrypted cloud backup** — opt-in during onboarding or later in Settings. Your climbing data is encrypted on your device and stored across multiple public servers (Nostr + Blossom). Only you can decrypt it.
- **Restore a backup on a new device** — during onboarding, choose "Restore backup" and import your CruxCoach Account key. CruxCoach briefly restarts and then pulls your data automatically.
- Delete remote backups from Settings — "Delete remote backups…" as an explicit destructive action, with a confirmation dialog and caveats spelled out. Local data is not touched.
- "What's new" upgrade dialog announces the encrypted-backups feature when you upgrade from a previous version (skipped on fresh installs and per-identity).
- Smarter BLE board connect — when only one Kilter Board is in range, CruxCoach connects automatically after a short scan. Two or more boards still show the picker.
- **Quick-Send mode** (Settings → Board → "Schnell-Senden") — when on, tapping the BLE icon in a climb's detail view runs the full macro: scan, auto-connect (or pick if multiple), send the climb, and disconnect — no manual connection sheet, no manual disconnect.
- Always-visible status line in *Settings → Encrypted cloud backup* covers all five states (no key / disabled / disabled-with-history / enabled-no-backup / enabled-with-last-sync), so the section never goes blank after a delete.
- Share via Zapstore — new QR code + shareable link in Settings → Share app, for pointing new users at the recommended app store.
- Copy buttons for the online-share URL, offline-share password, and offline-share download URL — no more retyping.
- Automatic NIP-65 relay discovery — CruxCoach now picks up the Nostr relays you've published as "yours" and uses them instead of just the three built-in defaults. Fully invisible, no settings to manage.
- **Prominent "save your account key" warning** wherever you enable Cloud-Backup (onboarding, *what's new*, settings) and on *Settings → CruxCoach Account*. The same key protects both your CruxCoach Account and your cloud backup, so saving it once covers both — without it, both are gone if you lose this device. *CruxCoach Account* is now the single canonical place to view, copy, and confirm "I've saved my key".
- **Backup-frequency picker in onboarding + What's new** — pick *manual* / *daily* / *weekly* right when you enable Cloud-Backup, no need to go through Settings afterwards.

### Changed
- Onboarding redesigned — down from five steps to three: welcome + board-database download in one screen, privacy + backup in one coherent screen, Kilter import as an optional last step with a prominent "Skip" button.
- Backup-error messages are now localized end-to-end (German + English). Pre-fix the snackbar said *"Backup fehlgeschlagen: Blob upload failed on all 2 servers"* — mixed locale; now you see a clean message in your locale regardless of which step failed.
- Delete-remote dialog reports per-leg outcomes in your locale instead of English diagnostic strings.
- Default Blossom servers swapped to a verified-working pair: `blossom.primal.net` + `nostr.download` (replacing `blossom.nostr.build`, which deterministically rejects encrypted blobs because of its image-only MIME policy).
- BLE Auto-Disconnect + Pause-Timer settings simplified — preset chips removed; the duration stepper is now the single source of truth and reaches every value the chips used to offer plus the in-betweens (e.g. 1 m 47 s).
- "Bildschirm anlassen" toggle moved into the Anzeige (Display) section where it thematically belongs.
- All-locale dates throughout — *last sync* / onboarding board-sync timestamps now follow the system locale (`25.04.26, 14:32` in German, `4/25/26, 2:32 PM` in English) instead of the hardcoded German format.
- *SyncInterval* chips, the *Board model* dialog, and the board-sync inline card no longer show hardcoded German labels in English locale.
- Share buttons renamed for consistency: "Share online", "Share offline", "Share via Zapstore", "Share via apps".
- The backup feature is now called *Encrypted cloud backup* / *Verschlüsseltes Cloud-Backup* throughout the UI (settings, onboarding, "what's new" dialog, error messages, restore + delete-remote flows). The underlying tech (Nostr + Blossom) is named explicitly in a single concise *how does this work* line in Settings, instead of bleeding through every status string. No functional change — same encryption, same servers, friendlier vocabulary for non-Nostr users.
- Backup default is now **manual** when you enable the feature for the first time (during onboarding or via the "what's new" dialog). No automatic first backup, no scheduled background runs. You start the first backup yourself via *Settings → Back up now*, and choose a daily/weekly cadence there if you want one. Existing users on a daily/weekly schedule keep their setting.

### Fixed
- **Empty BoardBrowser after an interrupted onboarding** — if a process kill (e.g. an Amber-pair restart while the board database was still importing) interrupted the import mid-flight, the next app start now detects the half-imported state and runs a recovery sync automatically. Pre-fix, the BoardBrowser would show zero holds until you manually tapped *Sync now*.
- Backup pipeline is now serialized — periodic and manual *Jetzt sichern* runs can no longer race and orphan a Blossom blob or mis-target the cleanup delete.
- *Backup gefunden* but restore immediately fails: pre-flight HEAD probe in *Backup wiederherstellen* checks that at least one Blossom server still holds the encrypted content before showing the confirm dialog. If every server reports the blob missing (e.g. after a previous opt-out), the snackbar explains it instead of showing a generic restore-failed.
- Restore reliability under slow relays — the backup-pointer fetch now keeps the events that arrived before a slow third relay timed out, instead of dropping all of them. Timeout raised from 10 s to 30 s.
- Process-wide Nostr cache no longer swallows the just-published backup pointer / key event when a foreground subscription saw it first — restore queries bypass the dedup cache (same fix applies to the profile-metadata refresh).
- Restore could destructively replace the backup encryption key under transient relay errors (orphaning the existing blob). Auto-regeneration is now blocked when this device has any prior backup history; you'll see *"Verschlüsselungs-Schlüssel-Event konnte nicht von den Relays geladen werden — bitte erneut versuchen"* and the existing backup stays intact.
- Background-backup retry storms with Amber + auto-approve disabled — the worker now distinguishes coroutine cancellation from programming bugs, and DataStore failures route to retry instead of permanent failure.
- Amber sign attempts no longer hang the app forever when Amber is uninstalled mid-flight — the suspended sign call is unblocked with an empty response, and you get a real error instead of a frozen progress indicator.
- *Remote-Backups löschen* now correctly counts an HTTP 404 from a Blossom server as success (the server doesn't have the blob → goal achieved) instead of confusing "1 von 2 Servern" reports when one server never accepted the upload to begin with.
- Slow-loris HTTP timeouts: every Nostr / updater HTTP client now has a hard wall-clock cap (60 s call timeout) on top of the per-segment timeouts.
- App-startup race where one failing background step (board sync, Kilter sync) skipped the rest of the chain — each step now runs independently.
- BLE Auto-Disconnect + Pause-Timer settings no longer show stale German preset labels on English locale.

### Security
- Backup pointer + key publish failures can no longer be silently stamped as success: if zero relays accept, the backup explicitly fails with a retryable error, the local *previousBlobSha256* stays unchanged, and the previous blob is **not** deleted — so a transient relay outage can never leave you with a backup pointer that no relay holds.
- Backup ciphertext upload now sends BUD-06 headers (`X-SHA-256`, `X-Content-Length`, `X-Content-Type`) — content-addressed identity is asserted at every Blossom server, not just at our end.
- Per-relay outcomes (success / OK-false-with-reason / timeout / connection-failure) are now logged separately so a 1-of-3-acked outcome has a per-relay explanation in `logcat` instead of looking identical to a real 2/3 failure.
- D-tag derivation is serialized — concurrent first-time derivers can no longer fan out into two Amber approval prompts for what's documented as "at most once per identifier+install".
- Identity switch (key import, switch-to-Amber, switch-to-local) now cancels any in-flight backup worker before swapping the signer, so a periodic run can never finish under the old identity and write into the new identity's data store.
- Forgejo CI now runs the unit-test suite before every release build — a regression in `BackupPointer.validateOrThrow`, the NIP-65 relay-cap, or the key-import format detection hard-stops the release.

### Notes
- After restoring a backup on a new device, you need to log in to your Kilter account once more in Settings. Kilter tokens are intentionally not part of the backup so a leaked Nostr key can't also hand over your Kilter account.
- The keep-screen-on toggle is now under *Anzeige* in Settings, not *Board-Einstellungen* — the behavior didn't change, only its location.
- Fresh-install users with no `Kind 10063` Blossom server list now have two working defaults (`blossom.primal.net` + `nostr.download`) instead of the previous primal-only effective coverage. Encrypted backups land on both.

## [0.1.2] - 2026-04-22

### Added
- In-app auto-updater — checks for new versions and walks you through install (automatically turned off when you installed CruxCoach through Zapstore, which handles updates itself)
- Notification reliability setup — guides you through Android's battery and autostart settings so messages from the developer and sync updates arrive reliably even after the app has been closed for a while
- App-share QR code in Settings — let nearby climbers scan a QR code to download the app

### Changed
- Receiving a board database from another climber's sharing hotspot now asks for confirmation and shows the source before importing, so you can't accidentally replace your climbs
- Board database updates no longer briefly freeze the app while they finish

### Fixed
- Kilter sync could mark the wrong ascents as synced when you logged several climbs in quick succession
- Nostr sync could forget recently-synced messages under heavy network activity
- Outgoing Nostr messages could stay stuck as "pending" after the network connection dropped
- More stable Bluetooth connection to the Kilter Board during long climbing sessions
- Board database import on older devices occasionally failed with a "database is locked" error
- Board browser could become slow after a board database update was interrupted — the speed-up indexes now repair themselves automatically on app start
- The version number shown in Settings is now always correct
- Hold heatmap on the board image is now clearly visible again, and the personal heatmap correctly shows your climbs
- Activity matrix in the logbook statistics is scrollable again
- Heatmap section moved to the bottom of the statistics sheet so primary stats are visible first
- Default foot-hold LED color now shows a readable name ("Mint Green") instead of "Custom"
- Auto-updater signature check is more robust across Android versions
- Auto-updater no longer gets blocked for 24 hours after a transient network error
- Local app-share hotspot now waits until its IP address is bound before reporting "ready"

### Security
- Downloaded board databases are now cryptographically verified and size-limited — a malicious server can't feed you tampered or oversized data
- Importing a backup file now rejects tampered or malformed data before it can corrupt your personal database
- Import links (`cruxcoach://import-board-db`) only work when they point to a device on your local network — they can't be abused by a website
- The local app-share mini-server only listens while you're actively sharing; no open ports at other times
- Notifications can only open a fixed list of app screens — other apps can't use fake notifications to slip you into sensitive areas
- Encrypted direct messages are only processed when they come from the developer account — strangers can't spoof bug-report responses
- Disconnecting your Kilter account now also logs you out on Kilter's server, and your Kilter login token is excluded from Android's system backups
- Lightning addresses in Nostr profiles are only trusted after signature verification — can't be spoofed by a relay
- The screen for entering your Nostr private key (nsec) now blocks screenshots and screen recording
- The password for local sharing hotspots now uses a cryptographically strong random source
- Build tooling is now cryptographically pinned to prevent supply-chain tampering during builds
- The update-download confirmation prompt only opens when there actually is a pending update — other apps on your phone can no longer trick CruxCoach into showing fake update dialogs

## [0.1.1] - 2026-04-16

### Added
- Move count for boulders, frame count for routes in browse and detail views
- Detect already-imported board database during onboarding (skips download)
- VPN and mobile data warning on local app share screen
- APK downloads on Codeberg releases page

### Fixed
- Local app share — receiving device can now import the shared climb database
- Progress bar stuck at 0% during initial board data import
- Download progress jumping back and forth in status banner
- Import crashes on older devices with large databases
- Splash logo not visible in light mode

### Changed
- Consistent "Kilter Board" branding in README and Zapstore listing

## [0.1.0] - 2026-04-14

### Added
- Browse 85,000+ climbs with filters for grade, angle, quality, moves, and setter
- BLE board control — light up holds on your Kilter Board
- Custom LED colors for start, hand, foot, and top holds
- Log ascents with grade opinions, attempts, and notes
- Kilter logbook import from your Kilter account
- Hold Search — tap holds on the board to find climbs that use them
- Heatmap — visualize hold popularity by type, sends, or all climbs
- Climb lists — favorites, projects, custom lists
- Nearby Sharing — share climbs with nearby CruxCoach users over Bluetooth
- Session and rest timer with auto-start after logging
- Statistics — grade progression, difficulty trends, favorite angles
- Data export/import as JSON backup
- Decentralized identity via Nostr — no email, no password, no central server
- All personal data encrypted locally with SQLCipher
- Bug reports and feature requests via encrypted Nostr DMs
