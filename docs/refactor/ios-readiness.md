# iOS and portable-core readiness

Status and evidence date: **2026-08-30**. This is an implementation inventory,
not a claim that an iOS binary has compiled.

## Current result

The domain and repository contracts in `shared/src/commonMain` have no Android,
AndroidX, JVM, or CruxCoach Android-package imports. Community climb hashing is
now a dependency-free Common Kotlin implementation and is covered by standard
SHA-256 vectors plus the pre-existing canonical frames-hash format.

The module is nevertheless **not an active Apple KMP module**:

- `shared/build.gradle.kts` declares only `androidTarget`; its Apple targets are
  commented out.
- There is no `iosMain` source set and therefore no iOS `actual` for
  `BoardDriverFactory` or `SecureDriverFactory`.
- `./gradlew :shared:compileKotlinMetadata` succeeds but reports the compilation
  task as `SKIPPED`. With only the Android target, this is not evidence that
  Kotlin/Native can compile the core.
- This Linux host has neither `xcodebuild` nor the Apple SDK. SwiftUI, framework
  linkage, CoreBluetooth, VoiceOver, Dynamic Type and simulator behavior cannot
  be verified here.

No SwiftUI surface is added until the shared framework has compiled on a Mac.
That keeps the first shell small and prevents a large uncompiled Swift change.

## Boundaries proven on Linux

| Boundary | Evidence | Status |
| --- | --- | --- |
| Common source imports | Search rejects `android*`, `java.*`, `javax.*`, and `com.cruxcoach.android` imports | ready |
| Session presentation contract | `ActiveSessionState` and Android-to-portable mapper have focused tests | ready |
| Attempt logging contract | `LogAttempt`, repository port and Android adapter integration have focused tests | ready |
| Community frames hash | Common SHA-256 standard vectors and canonical output test pass | ready |
| Board/session protocol logic | Common Kotlin and simulator-independent Golden frames remain covered | ready for native compile |
| Board database driver | Android implementation only | Apple implementation required |
| Secure database driver | Android SQLCipher implementation only | product/security decision required |
| SwiftUI application shell | deliberately absent | framework-first gate |

The APKPure legacy package identifier was removed from `SupportedBoard`: it is
an Android data-acquisition detail and now lives in `ApkDownloader`. The Common
relay model also describes a platform transport instead of linking to the
Android BLE implementation.

## Owner-reviewed build changes required

`shared/build.gradle.kts`, the version catalog, and Gradle configuration are
repository trust-boundary files. An owner must review the following as a
dedicated changeset before implementation:

1. Enable `iosArm64()` and `iosSimulatorArm64()` (keep `iosX64()` only if an
   Intel-simulator requirement still exists).
2. Add the SQLDelight Native driver matching the repository's SQLDelight
   `2.3.2` line to `iosMain`; do not upgrade unrelated dependencies in the same
   change.
3. Export a static `shared` framework first. Do not enable experimental Swift
   export for v1; the tooling decision records it as deferred.
4. Configure the generated database interfaces for those native compilations
   and prove all migrations compile. Do not fork or remove historical schema
   files.

The public board catalogue can use SQLDelight's native SQLite driver. The
secure database cannot silently lose Android SQLCipher parity. Before an iOS
`SecureDriverFactory` is accepted, the owner must choose and threat-model one
of these explicit strategies:

- an audited SQLCipher-capable native driver with keys held in Keychain, or
- field/blob encryption above ordinary SQLite with a documented migration and
  interoperability story.

Plaintext fallback, hard-coded keys, and copying Android key material into
Swift or process arguments are rejected. Until this decision is made, the iOS
shell must use fake/in-memory repositories for UI work and must not expose
login, backup, Nostr identity, or other secure persistence as complete.

## Mac continuation gate

After the owner-reviewed KMP target/driver changes land, run from the repository
root on a Mac with a supported Xcode selected:

```sh
xcodebuild -version
./gradlew :shared:compileKotlinIosSimulatorArm64
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
./gradlew :shared:iosSimulatorArm64Test
./gradlew :shared:linkReleaseFrameworkIosArm64
```

Use the task names reported by `./gradlew :shared:tasks --all` if Kotlin changes
their generated spelling. A passing simulator framework is not the device gate;
the `iosArm64` link must pass separately.

Only then add an iPhone-first SwiftUI shell containing one fixture-backed
vertical path:

```text
Climb detail -> active attempt -> log attempt -> confirmed logbook entry
```

The shell uses `NavigationStack`, native sheets/toolbars and SwiftUI
accessibility. It consumes the shared screen-state/action contracts and does
not import SQLDelight types. Its first Xcode verification matrix is light/dark,
English/German, default/AX5 Dynamic Type, VoiceOver traversal, iPhone compact
width, reduced motion, offline/error/success, plus one iPad split-width smoke
check. iPad remains architectural coverage, not a v1 delivery promise.

## Exit criteria

iOS Core Readiness is complete only when all of the following are evidenced on
a Mac:

- both simulator and device frameworks link;
- all common tests, historical database migration fixtures and canonical hash
  vectors pass on Kotlin/Native;
- the fixture-backed SwiftUI logging flow compiles and its state/action mapping
  is covered by tests;
- secure persistence has an approved implementation or is explicitly excluded
  behind unavailable capabilities;
- no signing material is committed or placed in command arguments.
