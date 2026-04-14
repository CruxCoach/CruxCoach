# Contributing to CruxCoach

Thank you for your interest in contributing to CruxCoach! This document explains how to get started.

---

## Reporting Bugs

1. Check [existing issues](https://codeberg.org/CruxCoach/CruxCoach/issues) to avoid duplicates.
2. Open a new issue with:
   - **Device** (model, Android version)
   - **Steps to reproduce**
   - **Expected vs. actual behavior**
   - **Logcat output** if applicable (`adb logcat -s CruxCoach`)

> **Tip:** You can also report bugs directly from within the app via **Settings > Contact Developer**. Your report is sent as an encrypted Nostr DM and automatically includes device info.

## Suggesting Features

Open an issue with the `feature` label. Describe:
- **What** you want the app to do
- **Why** it would be useful for your climbing workflow
- **How** you envision the UI/interaction

> **Tip:** Feature requests can also be sent directly from the app via **Settings > Contact Developer**.

---

## Development Setup

### Prerequisites

- JDK 17 (OpenJDK)
- Android SDK (API 35+36, Build Tools 36.0.0, NDK 27.2.12479018, CMake 3.22.1)
- An Android device or emulator (BLE features require a physical device)

On Debian/Ubuntu (amd64 + arm64), the setup script handles everything:

```bash
bash scripts/setup_dev_env.sh
source ~/.bashrc   # or ~/.zshrc
```

### Building

```bash
# Debug APK
./gradlew :androidApp:assembleDebug

# Run tests
./gradlew :shared:testDebugUnitTest
./gradlew :androidApp:testDebugUnitTest

# Install on device
adb install androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

### Project Structure

```
shared/                    # Kotlin Multiplatform module
├── domain/model/          # Data classes (pure Kotlin)
├── domain/board/          # Board protocol, frame codec, grade system
└── sqldelight/
    ├── board/             # Board DB schema: climbs, stats, layouts (unencrypted)
    └── secure/            # Personal data schema: logbook, body stats, Nostr (SQLCipher)

androidApp/                # Android app (Jetpack Compose)
├── ui/                    # Screens + ViewModels
├── data/                  # Repositories, Kilter API client
├── ble/                   # Bluetooth board communication (Nordic UART)
└── nostr/                 # Nostr relay pool, sync, announcements
```

---

## Coding Standards

These are non-negotiable for all contributions.

### State Management
- **Thread-safe updates only**: Use `_state.update { it.copy(...) }` (atomic). Never use `_state.value = _state.value.copy(...)`.
- State class naming: `XyzState`, `XyzViewModel`, `XyzScreen`.

### File Size
- Max ~500 lines per file. Extract composables into separate files if needed.
- One screen composable per file.
- Extracted composables use `internal` visibility.

### Code Hygiene
- No dead code. Delete unused functions, don't comment them out.
- No swallowed exceptions: always log at minimum.
- No duplicated constants: centralize shared values.
- DRY mappers: extract repeated DB-to-domain mapping logic.

### Database (SQLDelight)
- Check the highest existing `.sqm` migration number before creating a new one.
- Add composite indices for frequently filtered column combinations.
- Delete unused queries from `.sq` files.

### Language
- Code is written in English.
- UI strings are in German (primary) and English. Both `values/strings.xml` and `values-en/strings.xml` must always be updated together.

### Dependencies
- Constructor injection via Hilt. No global mutable singletons.
- Use the type that the storage layer expects (SQLDelight `Long` for INTEGER).

---

## Submitting Changes

### Branch Naming

- `feat/<short-description>` for new features
- `fix/<short-description>` for bug fixes
- `refactor/<short-description>` for refactoring
- `docs/<short-description>` for documentation

### Commit Messages

Use conventional commit style:

```
feat(board): add climb difficulty histogram
fix(ble): reconnect after Android 14 permission change
refactor(engine): extract periodization into standalone class
```

### Pull Request Checklist

Before submitting a PR:

- [ ] Code compiles: `./gradlew :androidApp:assembleDebug`
- [ ] Shared tests pass: `./gradlew :shared:testDebugUnitTest`
- [ ] Android tests pass: `./gradlew :androidApp:testDebugUnitTest`
- [ ] Both `strings.xml` files updated (if UI strings changed)
- [ ] No new warnings introduced
- [ ] No files exceed ~500 lines

### PR Description

Include:
- **Summary**: What changed and why (1-3 bullet points)
- **Test plan**: How to verify the change works

---

## Architecture Decisions

### Why KMP?
Domain logic (grade calculations, frame codec, board protocol) is pure Kotlin in `shared/`. This keeps the door open for iOS and Wear OS without rewriting business logic.

### Why SQLDelight + SQLCipher?
- SQLDelight: SQL-first, type-safe, cross-platform. Not Android-only like Room.
- Two databases: board data (community climbs, unencrypted, public) and personal data (logbook, body stats, Nostr keys, encrypted with SQLCipher).

### Why Nostr?
Decentralized sync and communication without running a server. Users control their own keys. Board database distributed via Blossom (content-addressed blobs). Crash reports and dev contact via encrypted DMs (NIP-17).

### Why not Room?
Room is Android-only. SQLDelight generates code for all KMP targets from a single `.sq` schema.

---

## Questions?

Open an issue or reach out via Nostr.
