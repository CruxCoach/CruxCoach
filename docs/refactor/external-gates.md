# External verification gates

## Android visual and accessibility evidence

Rechecked on 2026-08-30 with Nokia 6.1 `PL2GAR9841808297` attached: Android
15/API 35, physical 1080 x 1920 at 420 dpi (about 411 dp wide), system locale
`de-DE`, font scale 1.0, and Bluetooth enabled. The serial is test-lab
evidence, not a Bluetooth device address and must not be copied into public
user data. No package matching `cruxcoach` was installed, no APK existed under
the worktree's build outputs, and no remote workflow run/artifact existed for
`feat/cross-platform-refactor`. Do not accept or update screenshot baselines
without viewing them.

The focused scenario/semantics set and both repository validators passed using
the writable SDK on 2026-08-30. Reproduce with:

```sh
adb devices -l
python3 scripts/validate_refactor_contracts.py
ANDROID_HOME=/home/myuser/android-sdk \
ANDROID_SDK_ROOT=/home/myuser/android-sdk \
./gradlew :androidApp:testDebugUnitTest \
  --tests 'com.cruxcoach.android.ui.board.AscentLoggingScenarioTest' \
  --tests 'com.cruxcoach.android.ui.board.AscentLoggingDialogSemanticsTest' \
  --tests 'com.cruxcoach.android.ui.board.AttemptLogConfirmationSemanticsTest' \
  --tests 'com.cruxcoach.android.ui.board.BoardBrowserScenarioTest' \
  --tests 'com.cruxcoach.android.ui.board.BoardBrowserErrorContentSemanticsTest' \
  --tests 'com.cruxcoach.android.ui.board.BoardBrowserHeaderSemanticsTest' \
  --tests 'com.cruxcoach.android.ui.board.ActiveSessionScenarioTest' \
  --tests 'com.cruxcoach.android.ui.board.ActiveSessionContinueCardSemanticsTest' \
  --tests 'com.cruxcoach.android.ui.board.ClimbDetailScenarioTest' \
  --tests 'com.cruxcoach.android.ui.board.ClimbDetailHeroSemanticsTest' \
  --tests 'com.cruxcoach.android.ui.board.ProgressHistoryScenarioTest' \
  --tests 'com.cruxcoach.android.ui.board.ProgressHistoryContentSemanticsTest'
```

The remaining rendering gate is APK availability, not ADB. Repository rules
assign full APK builds to CI. A push/PR would violate this refactor session's
explicit no-push/no-PR constraint, and the branch currently has no CI artifact.
If the owner explicitly authorizes the minimal local transport build, use the
existing feature identity and CI's non-publish placeholder version code exactly
as follows; this command is intentionally documented, not executed:

```sh
ANDROID_HOME=/home/myuser/android-sdk \
ANDROID_SDK_ROOT=/home/myuser/android-sdk \
./gradlew :androidApp:assembleDebug --console=plain \
  -PfeatureBranch=feat/cross-platform-refactor \
  -PfeatureTrack=feat-cross-platform-refactor-40293f11 \
  -PfeaturePackage=com.cruxcoach.android.dev.f_40293f116dca \
  -PfeatureLabel=cross-platform-refactor \
  -PfeatureVersionCode=1000013
adb -s PL2GAR9841808297 install -r \
  androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb -s PL2GAR9841808297 shell pm list packages \
  | rg '^package:com\.cruxcoach\.android\.dev\.f_40293f116dca$'
```

`1000013` is the credential-free transport placeholder already used by
`.github/workflows/feature-build.yml`; this APK must never be published. Do not
change signing, package identity, APKTrack configuration, or release files.

Then capture the same DesignLab scenario before and after each UI change with
`adb exec-out screencap -p` and `adb shell uiautomator dump /sdcard/window.xml`.

The debug-only DesignLab accepts `log/new-send`, `log/new-attempt`,
`log/edit-send`, `log/saving`, `log/success`, `log/error`, `browser/content`,
`browser/empty`, `browser/error`,
`session/active`, `session/resting`, `session/paused`,
`session/active-no-climb`, `progress/history`, `progress/empty`,
`progress/error`, `detail/disconnected`, and `detail/connected`. After a
reviewed debug APK is installed, discover
its exact package with
`adb shell pm list packages | rg com.cruxcoach.android`, then capture a state
without updating any Golden:

```sh
scripts/capture_design_lab.sh \
  com.cruxcoach.android.dev.f_40293f116dca \
  log/new-send light en 1.0 \
  /tmp/cruxcoach-designlab/log-new-send-light-en
```

Capture the durable result and preserved failure form with:

```sh
scripts/capture_design_lab.sh \
  com.cruxcoach.android.dev.f_40293f116dca \
  log/success light en 1.0 \
  /tmp/cruxcoach-designlab/log-success-light-en
scripts/capture_design_lab.sh \
  com.cruxcoach.android.dev.f_40293f116dca \
  log/error light en 1.0 \
  /tmp/cruxcoach-designlab/log-error-light-en
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

Once installed, capture all 144 combinations for each verified width class.
The wrapper refuses a mismatched renderer instead of labelling compact pixels
as expanded (or vice versa):

```sh
scripts/capture_design_lab_matrix.sh \
  com.cruxcoach.android.dev.f_40293f116dca compact \
  /tmp/cruxcoach-designlab
# Repeat on an emulator/device whose effective width is at least 600 dp:
scripts/capture_design_lab_matrix.sh \
  com.cruxcoach.android.dev.f_40293f116dca expanded \
  /tmp/cruxcoach-designlab
```

The attached Nokia is about 411 dp wide and therefore covers only `compact`.
Do not use a distorted `wm size` override as expanded-layout evidence.

Capture the deterministic history candidate (fixed rows and relative dates)
with:

```sh
scripts/capture_design_lab.sh \
  com.cruxcoach.android.dev.f_40293f116dca \
  progress/history light en 1.0 \
  /tmp/cruxcoach-designlab/progress-history-light-en
```

Also inspect `progress/empty` and `progress/error`; neither is a reviewed
production design until its pixels and semantics have been checked on the
attached renderer.

Capture the same climb identity in both delivery states without BoardSimulator:

```sh
scripts/capture_design_lab.sh \
  com.cruxcoach.android.dev.f_40293f116dca \
  detail/disconnected light en 1.0 \
  /tmp/cruxcoach-designlab/detail-disconnected-light-en
scripts/capture_design_lab.sh \
  com.cruxcoach.android.dev.f_40293f116dca \
  detail/connected light en 1.0 \
  /tmp/cruxcoach-designlab/detail-connected-light-en
```

The candidate is deliberately not wired into the production detail screen
until both captures have been reviewed for board prominence, large-text
wrapping, traversal order, and delivery-state clarity.

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

BoardSimulator is intentionally not started or stopped by agents. On
2026-08-30 a human confirmed a nearby Kilter advertisement, but the repository
client could not scan or connect because no CruxCoach APK was installed. No
name, Bluetooth address, GATT API, MTU, response, or LED state was therefore
recorded, and no Kilter transport claim is made. Before transport, lock the
simulator-independent encoder/parser vectors with:

```sh
python3 scripts/validate_refactor_contracts.py
ANDROID_HOME=/home/myuser/android-sdk \
ANDROID_SDK_ROOT=/home/myuser/android-sdk \
./gradlew --configure-on-demand :shared:testDebugUnitTest \
  --tests 'com.cruxcoach.board.BoardPacketEncoderTest' \
  --tests 'com.cruxcoach.board.MoonBoardFrameEncoderTest' \
  --tests 'com.cruxcoach.board.QuantumBoardPacketEncoderTest'
python3 -m json.tool docs/refactor/fixtures/ble-golden-frames.json >/dev/null
```

The transport matrix is the six IDs in
`docs/refactor/fixtures/ble-golden-frames.json`: Aurora API3, Aurora API2,
MoonBoard Standard, MoonBoard Mini, Quantum turn-off and Quantum empty route
snapshot. Record, for every board family the simulator advertises, the
advertised name/address, negotiated API/MTU, fixture ID, bytes observed and
simulator LED/result state. Repeat Aurora API2 on physical API2 hardware; a
successful unit or simulator vector is not evidence that the 18 W scaling is
safe on a real board.

The three focused encoder test classes and JSON parse passed on 2026-08-30.
There is still no repository-owned BoardSimulator launcher or automated
transport adapter to turn a fixture ID into a simulator send. After the
reviewed debug client is installed while the human-run simulator remains
available, continue through the real app path with:

```sh
adb devices -l
adb shell pm list packages | rg com.cruxcoach.android
python3 -m json.tool docs/refactor/fixtures/ble-golden-frames.json
```

Select only the advertised Kilter device in-app, record a redacted address
(last two octets only), negotiated API/MTU, the matching Kilter golden fixture
ID and bytes, simulator response, and LED state. Do not infer Aurora API2,
Moon, Quantum, or physical-board evidence from this Kilter simulator run.

## Local-share v1 sender compatibility decision

Resolved on 2026-08-30: bidirectional published interoperability wins. The v1
responder remains, but is named and routed separately from the v2 default
writer as `PUBLISHED_V1_COMPATIBILITY_RESPONDER`. Current receivers retain the
v2-first, v1-only-when-missing fallback and both versions retain their decoder
coverage. The machine-readable matrix marks v1 `write=false` and
`compatibilityResponseWrite=true`, so no unrelated writer may emit v1.

Recheck the boundary with:

```sh
rg -n 'VERSION_V2|MANIFEST_PATH|BOARD_PATH|responseContractForApkRequest' \
  androidApp/src/main/java/com/cruxcoach/android/util \
  androidApp/src/test/java/com/cruxcoach/android/util/LocalShareProtocolTest.kt
sed -n '1,45p' docs/en/LOCAL_SHARE_CONTRACT.md
ANDROID_HOME=/home/myuser/android-sdk \
ANDROID_SDK_ROOT=/home/myuser/android-sdk \
./gradlew :androidApp:testDebugUnitTest \
  --tests 'com.cruxcoach.android.util.LocalShareProtocolTest'
```

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
