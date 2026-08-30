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
Mac, verify the shared framework for `iosArm64` and `iosSimulatorArm64`, compile
the SwiftUI shell, and run VoiceOver, Dynamic Type XXXL, light/dark, and iPhone
compact-width checks. Do not add signing credentials to this repository or to
command arguments.
