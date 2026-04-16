# Changelog

All notable changes to CruxCoach will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [0.1.1] - 2026-04-16

### Fixed
- Climb database sharing via local app share — receiver can now import the shared board DB
- CursorWindow overflow on older devices during bulk import (batch by rowid ranges)
- Splash logo transparency in light mode
- Download progress in status banner using cumulative byte counts
- Indeterminate progress shown during bulk import instead of stuck at 0/N

### Added
- Move count for boulders, frame count for routes in browse and detail views
- Detect already-imported board database during onboarding
- VPN warning for local app share
- Codeberg releases with APK attachment (main = stable, dev = pre-release)

### Changed
- Consistent "Kilter Board" branding in README and Zapstore description

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
