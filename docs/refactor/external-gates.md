# External verification gates

## Android visual and accessibility evidence

Rechecked on 2026-08-30 with Nokia 6.1 `PL2GAR9841808297` attached: Android
15/API 35, physical 1080 x 1920 at 420 dpi (about 411 dp wide), system locale
`de-DE`, font scale 1.0, and Bluetooth enabled. The serial is test-lab
evidence, not a Bluetooth device address and must not be copied into public
user data. A stable `com.cruxcoach.android` v0.2.2 package was subsequently
installed by the operator; it is not debuggable and has no DesignLab activity.
The side-by-side feature package `com.cruxcoach.android.dev.f_40293f116dca`
was installed through the normal package installer from the verified APKTrack
blob on 2026-08-31. That first install was versionCode `1000013`, source commit
`c4ff4b2ece16ffdb1a5b4f33fb21d6afe61af8cc`, and uses the central development
certificate (SHA-256
`7C:79:E8:83:B3:32:26:9C:8A:36:F4:ED:B9:81:F7:34:D2:84:4F:C0:57:CF:CC:15:AE:C4:BC:04:E3:C3:E6:A5`).
Stable `com.cruxcoach.android` remains installed at v0.2.2/versionCode 8 and
was not opened, cleared, replaced, or reused for feature evidence. Do not
accept or update screenshot baselines without viewing them.

That feature package was updated in place through the same device-side flow to
versionCode `1000014`, source commit
`d780f4a6c3e32a1e3641365bdfa9cc59ef3e9509`, release SHA-256
`3a7dcd9134b2a6577c28d83fc724a77466bac39b3541e960a5e29ae42fe989d8`.
The on-device base APK hash matched the published hash before review; package
identity and central development certificate remained unchanged.

The current reviewed package is versionCode `1000015`, source commit
`f2fe146f14185e87aa6c370e9bfb8c90f8cea81f`, published by run
`33391580904` through APKTrack job `f78d93ae096345ee8a7a368e41344761`.
The terminal result was `status=published`, `receipt_delivered=true`, with
release SHA-256
`75fe23e04350f19c54c5b46fda9af7550f0f4aceb4cea5816f791a3bb330c729`.
The operator installed it through Android's normal package installer after a
byte-exact on-device hash check. Read-only ADB inspection confirmed versionName
`0.2.2`, versionCode `1000015`, and `InstallSuccess`. No `adb install` was
used. Stable remains installed separately at versionCode `8` and its package
and data were not opened, cleared, updated, or reused.

The focused scenario/semantics set and both repository validators passed again
using the writable SDK on 2026-08-31. Reproduce with:

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

After the production host changes, the focused compile, detail policy/projection
tests, shared session contract and browser/session host semantics also pass:

```sh
ANDROID_HOME=/home/myuser/android-sdk \
ANDROID_SDK_ROOT=/home/myuser/android-sdk \
./gradlew :androidApp:compileDebugKotlin
ANDROID_HOME=/home/myuser/android-sdk \
ANDROID_SDK_ROOT=/home/myuser/android-sdk \
./gradlew :shared:testDebugUnitTest \
  --tests 'com.cruxcoach.domain.board.BoardBrowserContractTest' \
  :androidApp:testDebugUnitTest \
  --tests 'com.cruxcoach.android.ui.board.BoardBrowserStateMapperTest' \
  --tests 'com.cruxcoach.android.ui.board.BoardBrowserHeaderSemanticsTest' \
  --tests 'com.cruxcoach.android.ui.board.BoardBrowserActiveSessionProjectionTest' \
  --tests 'com.cruxcoach.android.ui.board.BoardBrowserActiveSessionHostTest' \
  --tests 'com.cruxcoach.android.ui.board.ActiveSessionContinueCardSemanticsTest' \
  --tests 'com.cruxcoach.android.data.ActiveSessionStateMapperTest' \
  --tests 'com.cruxcoach.android.ui.board.ClimbDetailStateMapperTest' \
  --tests 'com.cruxcoach.android.ui.board.BoardDeliveryPolicyTest'
```

The shell must use the writable SDK path shown above. A first retry with the
default 2-GiB Kotlin daemon exhausted its heap under host memory pressure;
stopping the completed Gradle daemons and retrying the same focused command
succeeded without any repository configuration change.

The local reservation gate recorded in commit `193661ee` was superseded by the
owner's CI/CD decision on 2026-08-30. Do not reserve, build, or publish this
feature locally. Push only the clean `feat/cross-platform-refactor` branch to
the `github` remote. The existing workflows own the complete trusted path:

1. `Feature APK request` (`.github/workflows/feature-build.yml`) runs the
   credential-free tests for the deterministic feature identity.
2. `Publish verified feature APK` (`.github/workflows/feature-publish.yml`),
   triggered by `workflow_run`, checks the authorized maintainer, reserves the
   versionCode, builds and binds the transport APK, publishes through APKTrack,
   verifies `status="published"` plus `receipt_delivered=true`, and stores the
   centrally signed artifact.

Re-read all three `AGENTS.md` files, confirm the active GitHub login is present
in `.github/authorized-feature-maintainers.txt`, then reproduce with:

```sh
git status --short --branch
gh api user --jq .login
python3 scripts/feature_identity.py --branch feat/cross-platform-refactor
git push github feat/cross-platform-refactor
gh run list --branch feat/cross-platform-refactor \
  --workflow 'Feature APK request' --limit 5
gh run list --branch feat/cross-platform-refactor \
  --workflow 'Publish verified feature APK' --limit 5
```

The build workflow alone is not publication success. Continue only after the
publisher job confirms both required APKTrack fields. Preserve the deterministic
idempotency key `cruxcoach-feat-cross-platform-refactor-40293f11-<commit>` on
diagnosis; do not change workflow, signing, package identity, APKTrack policy,
credentials, or release files.

The first publication completed in publisher run `33342201518` with
`status=published`, `receipt_delivered=true`, and release SHA-256
`50b69133520f6fc7a792dd40e645e467eb469cc77820691d502d653316262a6e`.
After the DesignLab locale fix at
`d780f4a6c3e32a1e3641365bdfa9cc59ef3e9509`, feature build run `33366830151`
passed and publisher run `33367234270` reserved versionCode `1000014` and
completed its build. Its first publish attempt failed in APKTrack job
`873a8be28eb34730b89576b0b0ab1762` when the external signer volume filled.

Read-only worker-journal diagnosis on 2026-08-31 identified the precise cause:
Android `apksigner` raised `java.io.IOException: No space left on device` while
writing the signed output. The candidate is 66,535,040 bytes and the APKTrack
volume had only 72 MiB free at the later check; publication needs space for
both the uploaded candidate and a similarly sized signed copy, plus working
headroom. The wrapper, `apksigner`, development keystore and worker service
were present and readable/running. APKTrack correctly made the job terminal
and removed its staging candidate and partial signed output.

The operator removed only regenerable build/temporary artifacts, restored the
exact original candidate bytes, and reprocessed that same job without changing
its commit, candidate hash, job ID or idempotency identity. CI run
`33367234270`, attempt 2 is green. The terminal result is
`status=published`, `receipt_delivered=true`, with release SHA-256
`3a7dcd9134b2a6577c28d83fc724a77466bac39b3541e960a5e29ae42fe989d8`.
The incident is closed; it was a volume-capacity failure, not a missing key or
a code/signing-policy defect. The retained read-only diagnostic commands are:

```sh
df -h /mnt/HC_Volume_106554832/labs/apktrack-data/staging
apktrack build-status 873a8be28eb34730b89576b0b0ab1762 \
  --server-url https://stats.cruxcoach.org/apktrack
```

After success, take the confirmed `release_sha256` from the publisher log and
download only
`https://stats.cruxcoach.org/apktrack/v2/blobs/<release_sha256>` (or use the
verifying APKTrack web UI) directly on the Android device. Verify the downloaded
file's SHA-256 on-device before opening the normal package installer. Do not use
`adb install` as the default fallback. If Android requests permission to install
unknown apps or final confirmation, pause for that human action. Afterwards,
verify the feature package, versionCode and certificate read-only, while stable
`com.cruxcoach.android` remains installed unchanged beside it.

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

The complete compact matrix was first captured from versionCode `1000013` on
2026-08-31 at `/tmp/cruxcoach-designlab-v1000013`: 144/144 screenshots,
144/144 semantics XML files and 144/144 environment records. Every screenshot
was 1080 x 1920, every XML document parsed, no node extended outside the
screen, and all 728 clickable nodes met 48 dp on the 420-dpi renderer. All five
labelled contact sheets (logging, browser, session, detail and progress) were
opened and reviewed, with problem states reopened at original resolution.
Concrete findings:

- Logging saving/success/error communicate status with text and icons as well
  as colour; dialogs remain usable at 1.5 font scale in both themes.
- Detail keeps the board as hero, distinguishes connected/disconnected in text
  and iconography, and preserves both logging actions at 1.5.
- Browser, Session and Progress candidates placed top content beneath status
  icons in the edge-to-edge harness. The next source revision applies safe
  drawing insets to those full-screen harness families while retaining the
  deliberate edge-to-edge Detail hero.
- Sixteen rendered Progress history checkboxes (two rows across eight axes)
  had no accessible name. The next source revision adds bilingual per-climb
  labels and an explicit 48-dp semantics target; its focused Compose test
  passes.
- Twenty of 72 EN/DE pairs were semantically identical: every axis of
  `log/new-send`, `log/new-attempt`, `log/edit-send`, `log/saving` and
  `log/error`. Other scenario families switched locale correctly. The
  older artifact predates the `LocalResources` fix at `d780f4a6`.

VersionCode `1000014`, built from full commit
`d780f4a6c3e32a1e3641365bdfa9cc59ef3e9509`, was then downloaded through the
canonical APKTrack blob endpoint, hash-checked on-device against the published
release SHA and installed through Android's normal package installer. No
`adb install` or Stable-package mutation was used. Read-only package inspection
confirmed feature versionCode `1000014`; Stable `com.cruxcoach.android` remains
installed at versionCode `8`.

The full compact matrix was repeated at
`/tmp/cruxcoach-designlab-v1000014-d780/compact`. All 144 screenshots were
opened and reviewed and all 144 semantics trees parsed. The capture contains
3,802 semantic nodes, no out-of-bounds nodes, and no clickable target below
48 dp on the 420-dpi device. Every one of the 72 EN/DE pairs now differs,
confirming the locale fix on-device. The only automated accessibility findings
are the two unnamed Progress-history selection checkboxes across eight axes
(16 findings total). This is expected negative evidence: `d780f4a6` predates
the labelled-checkbox and safe-drawing-inset correction at `750d8ba6`.

Visual inspection found no clipped primary action in Logging, Detail, Session,
Browser or Progress at font scale 1.5. Saving, Success, Error, connection and
session phases all use text/icon cues in addition to colour. Browser, Session
and Progress DesignLab roots still draw critical content into the status-bar
region in d780; Detail deliberately keeps only its board hero edge-to-edge.
The current source correction cannot be called pixel-verified until a centrally
signed artifact containing `750d8ba6` and the later production-host commits is
installed. Validate the captured artifact with:

```sh
python3 scripts/validate_design_lab_capture.py \
  /tmp/cruxcoach-designlab-v1000014-d780/compact
```

That command intentionally exits non-zero for this historical artifact and
prints exactly the 16 checkbox findings; it must reach zero on a current-HEAD
capture before the correction is approved.

That current-HEAD gate is now closed for compact Android. The complete matrix
from versionCode `1000015` is at
`/tmp/cruxcoach-designlab-v1000015-f2fe/compact`: 144 screenshots, 144
semantics trees and 144 environment records across all 18 scenarios, EN/DE,
light/dark and font scales 1.0/1.5. Every screenshot was opened at full
resolution and every effective action/checkable label was inspected. The
validator checked 3,802 semantic nodes and reports zero missing artifacts,
out-of-bounds nodes, sub-48-dp clickable targets, or unlabelled checkables.
All 72 EN/DE pairs differ and all captures bind to the same source commit and
release hash.

The d780 regressions are visibly resolved: Browser, Session and Progress begin
their critical content below the status bar, while Detail intentionally keeps
only its non-critical board hero edge-to-edge. Progress retention controls and
both history-row checkboxes have visible shapes, checked state and localized
names; selection is also differentiated by surface, not colour alone. Logging
Saving/Success/Error, all Browser states, all four Session phases, both Detail
connection states, and Progress history/empty/error remain usable in both
themes and at 1.5 font scale. Long secondary text wraps or ellipsizes without
hiding a primary action. No correction round was warranted by the reviewed
compact pixels. Reproduce the zero-finding result with:

```sh
python3 scripts/validate_design_lab_capture.py \
  /tmp/cruxcoach-designlab-v1000015-f2fe/compact
```

The attached Nokia is about 411 dp wide and therefore covers only `compact`.
Do not use a distorted `wm size` override as expanded-layout evidence.

The reviewed Progress body was subsequently wired into
`BoardClimbHistoryScreen` without replacing the platform app bar, navigation,
select-all or delete confirmation. Its mapper and focused semantics tests pass,
and the f2fe package now verifies the production composition on-device. The
real empty Board Logbook remains distinct from device-local Progress history.
Through Lists -> History, the production Progress screen showed its app bar
below the status bar, four 126-pixel-high retention targets, the local-only
disclosure, exact `Floats Your Boat` UUID/40-degree row navigation, and a
separately focusable checkbox named `Floats Your Boat auswählen`. The ViewModel
projects initial loading and a failed repository stream into typed
loading/error states with a real collector retry; deterministic DesignLab and
focused tests cover those states because the device's current repository was
content-only.

Capture the deterministic history candidate (fixed rows and relative dates)
with:

```sh
scripts/capture_design_lab.sh \
  com.cruxcoach.android.dev.f_40293f116dca \
  progress/history light en 1.0 \
  /tmp/cruxcoach-designlab/progress-history-light-en
```

`progress/empty` and `progress/error` are reviewed in the current deterministic
compact matrix. Their production state projection is focused-test-covered; the
installed profile did not naturally enter either state during this run.

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

Both compact captures and their full EN/DE, light/dark and 1.0/1.5 font-scale
axes were reviewed for board prominence, wrapping, traversal and delivery-state
clarity. The candidate is still deliberately not wired into production because
the isolated component does not yet host the existing playback, angle/mirror,
partial-send, session-ownership and authorized management actions. Expanded
rendering remains a visual-evidence gate. A pixel-neutral
`ClimbDetailProductionHeroHost` now establishes the parity boundary around the
existing renderer, playback, layer controls and BLE feedback without replacing
those behaviors.

## Macrobenchmark preparation

The reviewed Macrobenchmark spike is now machine-readable at
`docs/refactor/macrobenchmark-plan.json` and checked by
`scripts/validate_refactor_contracts.py`. It fixes stable AndroidX Benchmark
1.4.1, development-package-only targeting, deterministic fixture inputs,
20+ iterations, retained raw/device artifacts and the 5% median / 10% frame
regression tripwires. The current f2fe package exposes all declared UIAutomator
completion markers. One force-stop/launcher diagnostic on the Nokia (not a
Macrobenchmark result) recorded first Browser content at 29,875 ms, including
a 19,283-ms filtered catalogue query. Browser-to-Detail completed in 5,142 ms,
including a 3,548-ms supported-angle query. These uncontrolled single
observations are negative evidence and a reason to execute the planned repeated
benchmark; they must not be presented as stable medians or compared to the
regression tripwires.

Creating the separate benchmark module/build variant and adding its dependency
would change Gradle trust-boundary files, so it remains an owner-reviewed gate;
do not approximate it by benchmarking Stable or clearing Stable data. After
approval and a centrally signed current feature package, implement the five
measurements from the JSON plan, then retain AndroidX raw JSON plus the exact
device, fixture, package/version/certificate and commit metadata.

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
2026-08-31 the installed feature app followed its normal onboarding, requested
Android's Nearby permission, scanned and auto-connected to the single advertised
Kilter simulator. Privacy-safe evidence: advertised name
`Kilter Board#0001@3`, address suffix only `…:26`, RSSI -48 to -52 dBm, API 3,
GATT status 0, ten discovered services and a ready connection. Aurora/Kilter
does not request a larger MTU in this client, so the run used default ATT-MTU
23 and 20-byte writes.

Opening public climb `Floats Your Boat` at 40 degrees exercised the real app
path. It resolved 15/15 placements, displayed `An Board gesendet`, and the log
recorded successful callbacks for all writes with `unmapped=0`. The exact
encoder output is fixture `kilter-simulator-api3-floats-your-boat`: 51 wire
bytes split 20/20/11, locked by `BoardPacketEncoderTest`. Android HCI snooping
was disabled, so these bytes are derived from the exact database frames,
placement-to-LED map and production encoder rather than an independent radio
capture. The manually hosted simulator GUI/log was not accessible from this
host, so its LED rendering remains an explicit external observation gate. No
Aurora API2, Moon, Quantum or physical-board claim follows from this run.

The installed `d780f4a6` feature package repeated the live path after the
versionCode `1000014` installation while the simulator was still advertising.
The scanner reported `Kilter Board#0001@3` at -37 to -46 dBm. The first GATT
attempt returned Android status 133 and the normal bounded retry connected with
status 0, discovered ten services and reached ready state. Opening `Floats Your
Boat` at 40 degrees recorded `frames=15`, `success=true`, `unmapped=0`; the
production Detail UI rendered the board and explicit `An Board gesendet` state.
The address is redacted from committed evidence. This repeat does not add an
independent radio-byte or simulator-LED claim, and it does not validate source
newer than `d780f4a6`.

The f2fe/versionCode-1000015 package repeated the same real path once more.
Android reported Bluetooth ON with SCAN and CONNECT granted. The scanner found
`Kilter Board#0001@3` at roughly -42 to -44 dBm; the address is redacted.
GATT connected with status 0 on its first attempt, discovered ten services and
became ready. This client does not request a larger MTU, so ATT-MTU 23 and
20-byte application chunks apply. Opening `Floats Your Boat` at 40 degrees
resolved 15 frames and recorded `success=true`, `unmapped=0`. Detail displayed
`An Board gesendet`; returning to Browser displayed both `Verbunden mit Kilter
Board` and the current-climb banner, so neither connection nor delivery was
encoded by colour alone. The exact 51-byte encoder fixture remains split
20/20/11; HCI snooping was not enabled, so this is application/encoder evidence,
not an independent over-the-air byte capture.

The production Browser and Detail hosts were inspected at full resolution and
through their UIAutomator trees. Browser retained board/angle selection,
connection, filter, logbook, lists, settings, random, create, search and climb
navigation. Detail retained the board hero, exact 40-degree context,
angle/mirror metadata, favorite, list, overflow, timer, BLE and both attempt
actions. No active queue/playlist existed in the installed profile, so a live
production Continue-session card could not be produced without mutating user
state; all four deterministic card states were nevertheless pixel- and
semantics-reviewed in the current artifact.

Real production d780 Browser, Detail and Progress screenshots and semantics
were also inspected. Browser retained search/filter/list/management navigation;
Detail retained board visualization, exact 40-degree context, BLE status,
favorite/list/overflow and attempt/send actions; Progress retained retention,
local-only disclosure and UUID/angle row navigation. All measured clickable
nodes were at least 48 dp. Progress still exposed the expected unnamed row
checkbox, matching the DesignLab failure and the later source correction.

Before further transport, lock the simulator-independent encoder/parser
vectors with:

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

The transport matrix is the seven IDs in
`docs/refactor/fixtures/ble-golden-frames.json`: two Aurora API3 vectors,
Aurora API2, MoonBoard Standard, MoonBoard Mini, Quantum turn-off and Quantum
empty route snapshot. Record, for every board family the simulator advertises, the
advertised name/address, negotiated API/MTU, fixture ID, bytes observed and
simulator LED/result state. Repeat Aurora API2 on physical API2 hardware; a
successful unit or simulator vector is not evidence that the 18 W scaling is
safe on a real board.

The three focused encoder test classes and JSON parse passed on 2026-08-30;
the Kilter device-derived API3 vector was added and rechecked on 2026-08-31.
There is still no repository-owned BoardSimulator launcher or automated
transport adapter to turn a fixture ID into a simulator send. Continue through
the real app path with:

```sh
adb devices -l
adb shell pm list packages | rg com.cruxcoach.android
python3 -m json.tool docs/refactor/fixtures/ble-golden-frames.json
```

To close the remaining Kilter observation without restarting the simulator,
inspect the existing simulator GUI/log for the 15-hold update corresponding to
`Floats Your Boat` and record its decoded holds and LED state. For an
independent byte capture, arrange a reviewed HCI capture before a fresh run
(it was disabled for this run), then repeat the same climb. Do not infer
Aurora API2, Moon, Quantum, or physical-board evidence from this Kilter run.

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
