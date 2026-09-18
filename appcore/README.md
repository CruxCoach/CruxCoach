# appcore — application core of the native iOS app

`:appcore` sits between the SwiftUI views in `iosApp/` and the existing `:shared`
module. It is built into the static `CruxCoachCore` framework, which re-exports
`:shared`.

**`:androidApp` does not depend on this module.** Nothing here can change the
Android APK. The Android target exists only so `commonTest`/`androidUnitTest`
run on the JVM on hosts without Xcode.

## Why most of the iOS app is Kotlin

Android keeps sync, Nostr, BLE control and the browse pipeline in Android-only
classes (OkHttp, Hilt, `Context`, `java.*`). iOS needs the same behaviour and
the same wire formats. Writing that logic here rather than in Swift means:

- it is compile-checked for `iosArm64`/`iosSimulatorArm64` on Linux (klib stage);
- it is unit-tested on the JVM against real SQLite and real libsecp256k1;
- it can later replace the Android-only copies instead of becoming a third one.

Swift is used for SwiftUI and for the four platform services Kotlin/Native
cannot reach sensibly: CryptoKit AES-GCM, Keychain, libzstd, LocalAuthentication.

## Layout

| Package | Contents |
|---|---|
| `app.platform` | Contracts the host must provide (`Platform.kt`); `iosMain` holds the Kotlin implementations |
| `app.nostr` | NIP-01 events, keys, NIP-19/44/59, relay pool |
| `app.sync` | Signed-manifest catalogue sync and the BoardDB importer |
| `app.ble` | Board discovery, connection state machine, send planning; `iosMain` CoreBluetooth central |
| `app.browse`, `app.logbook`, `app.playlist` | Ports of the Android-only browse pipeline, statistics and playback |
| `app.backup`, `app.identity` | Encrypted backup/restore and key custody |
| `app.presentation` | Presenters consumed by SwiftUI |

## Rules for code Swift can see

- No `suspend` functions and no `Flow` in a Swift-facing signature. Presenters
  own a main-thread scope, expose `StateFlow<State>` through `watch { }`, and
  take plain method calls.
- Never let an exception cross into Swift: Kotlin/Native terminates the process.
  Failures are state (`enum` codes that Swift maps to localized text).
- State is `data class` + `enum`. No user-visible strings in Kotlin: Swift owns
  EN/DE text, keyed like the Android resources.
- Secrets are `ByteArray`, never logged, never put in a `data class` `toString`.

## Verifying on Linux

```sh
export KONAN_DATA_DIR=<cache dir>   # optional; keeps the Kotlin/Native toolchain out of ~/.konan
./gradlew :appcore:testDebugUnitTest --tests '<focused class>'
./gradlew :appcore:compileKotlinIosSimulatorArm64 :appcore:compileKotlinIosArm64
```

The compile tasks type-check against Apple's APIs but do not link. Framework
linking, simulator tests and the app build run on macOS CI; BLE needs hardware.
