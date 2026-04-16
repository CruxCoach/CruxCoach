# Changelog

All notable changes to CruxCoach will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

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
