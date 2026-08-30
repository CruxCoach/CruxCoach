# External verification gates

## Android visual and accessibility evidence

Blocked on 2026-08-30 because `adb devices -l` returned no attached device.
Do not accept or update screenshot baselines without review.

Continue after a device or emulator is attached:

```sh
adb devices -l
python3 scripts/validate_refactor_contracts.py
./gradlew :androidApp:testDebugUnitTest --tests '*Scenario*' --tests '*Semantics*'
```

Then capture the same DesignLab scenario before and after each UI change with
`adb exec-out screencap -p` and `adb shell uiautomator dump /sdcard/window.xml`.

The debug-only DesignLab accepts `log/new-send`, `log/new-attempt`,
`log/edit-send`, `browser/content`, `browser/empty`, `browser/error`,
`session/active`, `session/resting`, `session/paused`, and
`session/active-no-climb`. After a reviewed debug APK is installed, discover
its exact package with
`adb shell pm list packages | rg com.cruxcoach.android`, then capture a state
without updating any Golden:

```sh
scripts/capture_design_lab.sh \
  com.cruxcoach.android.dev.f_40293f116dca \
  log/new-send light en 1.0 \
  /tmp/cruxcoach-designlab/log-new-send-light-en
```

Capture the portable planned-rest snapshot independently of BLE hardware:

```sh
scripts/capture_design_lab.sh \
  com.cruxcoach.android.dev.f_40293f116dca \
  session/resting light en 1.0 \
  /tmp/cruxcoach-designlab/session-resting-light-en
```

For the first browser region, capture the stable content fixture with:

```sh
scripts/capture_design_lab.sh \
  com.cruxcoach.android.dev.f_40293f116dca \
  browser/content light en 1.0 \
  /tmp/cruxcoach-designlab/browser-content-light-en
```

Repeat with `dark`, `de`, and `1.5`; use compact and expanded emulator/device
profiles for the width axis. The package shown is the permanent identity for
`feat/cross-platform-refactor`; pass the installed package reported by ADB if a
local non-published build uses a different development suffix. Inspect both
`screenshot.png` and `semantics.xml` before any baseline is reviewed.

A simulator-independent pixel capture of the tagged Compose `AlertDialog` was
also attempted with Robolectric 4.14.1 on 2026-08-30. With native graphics it
failed to reach Compose idle after 60 seconds; with the default graphics mode
it exhausted the test heap while waiting. The equivalent semantics composition
passes. Do not present Preview fixtures as reviewed pixels. Continue pixel
verification on an attached Android renderer, or in a separately reviewed
Roborazzi spike that first proves stable dialog-window capture on this stack.

The shell default points `ANDROID_HOME` at the read-only `/opt/android-sdk` and
therefore tries and fails to install NDK `27.2.12479018`. A complete writable
SDK was found at `/home/myuser/android-sdk` on 2026-08-30. Android checks can be
run without changing Gradle or repository configuration by scoping the override
to the test process:

```sh
ANDROID_HOME=/home/myuser/android-sdk \
ANDROID_SDK_ROOT=/home/myuser/android-sdk \
./gradlew --configure-on-demand :androidApp:testDebugUnitTest --tests '<test class>'
```

The logging-contract integration check now passes with:

```sh
ANDROID_HOME=/home/myuser/android-sdk \
ANDROID_SDK_ROOT=/home/myuser/android-sdk \
./gradlew --configure-on-demand :androidApp:testDebugUnitTest \
  --tests 'com.cruxcoach.android.ui.board.AscentLoggerQuickLogTest'
```

## BoardSimulator and hardware

BoardSimulator is intentionally not started by agents. When a human has
started it, first confirm its advertised endpoint/device, then run the BLE matrix represented by
`docs/refactor/fixtures/ble-golden-frames.json` for every simulator-advertised
board, then repeat the documented hardware-only API2 checks on physical
hardware. A successful unit vector is not evidence of a physical send.

## Apple toolchain

This Linux host has no Xcode, iOS SDK, simulator, or signing environment. On a
Mac, first complete the owner-reviewed target/driver changes described in
`docs/refactor/ios-readiness.md`, then run:

```sh
xcodebuild -version
./gradlew :shared:compileKotlinIosSimulatorArm64
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
./gradlew :shared:iosSimulatorArm64Test
./gradlew :shared:linkReleaseFrameworkIosArm64
```

Only after those pass should the fixture-backed SwiftUI logging shell be
compiled and checked with VoiceOver, Dynamic Type AX5, light/dark, reduced
motion and iPhone compact width. Do not add signing credentials to this
repository or to command arguments.
