# Changelog

All notable changes to CruxCoach will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- **Climb Creator** — set your own climbs on the board image, save drafts, publish to other CruxCoach users via Nostr (Kind-30078), and optionally push the climb to your own Kilter account so it shows up in the Kilter app too. Heatmap shows where popular hand/start/finish holds typically go for the current layout + angle. Frames are validated before publish (start/finish constraints, no duplicate frames). Drafts drawer + autosave so a kill or accidental back-press never costs you in-progress work.
- **Community climbs in BoardBrowser** — climbs other CruxCoach users have published on Nostr now appear next to the Kilter catalog. The new origin filter chip lets you filter to *CruxCoach*, *Kilter*, or *All*.
- **Setter detail + setters list** — see who in the CruxCoach community has been publishing climbs and tap through to their list. A community badge on each climb's detail screen links to the setter.
- **Auto-Note (optional)** — when you publish a community climb you can also fire a Kind-1 note to your relays announcing the climb. Off by default; toggle in *Settings → Climb Creator*. Angle is required at publish time so the announcement carries the route's grade + angle.
- **Edit my published climb** — load any climb you previously published into the Climb Creator, edit it, and re-publish. The Nostr d-tag stays stable across edits so the original event is replaced (not duplicated); the Kilter side does an UPDATE if the climb was previously synced there.
- **Live Nostr subscription for community climbs** — climbs published while you're online appear immediately, not after the next daily Blossom snapshot. Bursts of relay echoes (your own published events) are filtered.
- **Kilter publish (opt-in)** — *Settings → Kilter publish* toggles whether your community climbs are also pushed to your own Kilter account via the Kilter API. Periodic 6-hour retry worker drains transient failures.
- **On-Kilter badge** — when a community climb is also synced to Kilter, the climb-detail view shows a badge so you know it's findable in the Kilter app too.
- **Nostr profile editor** in *Settings* — set the display name and picture other CruxCoach users see next to your community climbs. Without this, your climbs show up under `npub:<hex>...` instead of a real name.
- **Stale-event protection** for community-climb ingest — old replays of your own (or someone else's) events arriving on a different relay can no longer rewind a freshly-edited climb.

### Changed
- **Internal table naming cleaned up** — board database now uses unprefixed plural names (`climbs`, `climb_stats`, `placements`, …) instead of the historical `aurora_*` prefix. In-app schema migration runs once on first launch; no user action required.
- **BLE class names** dropped the Aurora prefix in favour of `Board*`. Internal refactor only.
- **Logbook detail** removed the "X Begehungen" / "Climb Details" toolbar headers — they were redundant with the screen content.
- **Quality rating** in Climb-Detail accepts 1–5 stars (was 1–3) — Kilter migrated to a 5-star scale.

### Fixed
- **Empty climb_browse view after schema upgrade** — the recreated VIEW now correctly carries the new origin + kilter_status columns, so the BoardBrowser query stops returning zero hits after first launch on 0.1.4.
- **CruxCoach metadata preserved across Kilter blob refreshes** — when the daily Kilter sync re-imports the catalog, your community climbs' Nostr provenance, frames_hash, sync_status, and Kilter-publish flags are no longer overwritten with defaults.
- **Tombstones propagate** — climbs deleted upstream are now correctly marked `is_deleted=1` locally instead of being silently re-inserted on the next bulk import.
- **Hot-path index self-heal** — index drift after the table rename auto-recovers without requiring a sync.
- **Climb-creator UX** — empty-brush tap now deletes the hold (no more eraser chip), drag-to-move works, German climbing terms throughout, V-scale grade slider has live validation.
- **Heatmap performance** — parsed frames cached + fast IntArray parser; no more frame drops on layouts with hundreds of climbs.
- **Saving a draft** correctly refreshes the drafts list and pins the draft's UUID so re-opening the editor lands you back on the same draft.

### Security
- **Community-climb signature verification** — every incoming Kind-30078 event is parsed through Quartz `Event.fromJson` (recomputes canonical event id) and verified with `verifySignature()` before persisting. Mirrors the existing pattern at NostrProfileManager / BlossomSyncManager / BackupRepository.
- **Author-uuid guard** — incoming events whose d-tag prefix or content `pubkey_prefix` field claims a different author than the signed pubkey are dropped, and a UUID already owned by author A cannot be overwritten by an event from author B (first-author wins). Without these guards a relay (or MITM on a non-TLS connection) could spoof events under any pubkey or clobber legitimate climbs via INSERT-OR-REPLACE on the uuid alone.

### Notes
- This is the first release that publishes user-authored climbs publicly on Nostr (when you tap *Publish* in the Climb Creator). Auto-Note Kind-1 is opt-in. Kilter API push is opt-in. Identity defaults to your existing CruxCoach Account key (the one used for cloud backup).
- `SECURITY.md` has been updated with the new attack surface (community-publishing chain, Kilter API self-account writes, Auto-Note Kind-1).

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
