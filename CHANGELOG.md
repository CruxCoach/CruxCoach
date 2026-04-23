# Changelog

All notable changes to CruxCoach will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [0.1.3] - 2026-04-23

### Added
- Encrypted cloud backup over Nostr — opt-in during onboarding or later in Settings. Your climbing data is encrypted on your device and uploaded to Blossom servers; only you can decrypt it with your Nostr key.
- Restore a backup on a new device — during onboarding, choose "Restore backup" and import the Nostr key you used before. CruxCoach briefly restarts and then pulls your data automatically.
- Delete remote backups from Settings — "Delete remote backups…" as an explicit destructive action, with a confirmation dialog and caveats spelled out. Local data is not touched.
- Share via Zapstore — new QR code + shareable link in Settings → Share app, for pointing new users at the recommended app store.
- Copy buttons for the online-share URL, offline-share password, and offline-share download URL — no more retyping.
- Automatic NIP-65 relay discovery — CruxCoach now picks up the Nostr relays you've published as "yours" and uses them instead of just the three built-in defaults. Fully invisible, no settings to manage.

### Changed
- Onboarding redesigned — down from five steps to three: welcome + board-database download in one screen, privacy + backup in one coherent screen, Kilter import as an optional last step with a prominent "Skip" button.
- Share buttons renamed for consistency: "Share online", "Share offline", "Share via Zapstore", "Share via apps".

### Notes
- After restoring a backup on a new device, you need to log in to your Kilter account once more in Settings. Kilter tokens are intentionally not part of the backup so a leaked Nostr key can't also hand over your Kilter account.

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
