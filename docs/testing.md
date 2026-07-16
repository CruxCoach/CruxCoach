# Testing Workflow

CruxCoach has two complementary test layers. JVM tests run on any development
machine; Maestro flows run through standard `adb` against a connected Android
device. Pick the layer that exercises the changed boundary, and use both for a
change that spans logic and UI/navigation.

## Portable device setup

1. Install Android SDK Platform Tools so `adb` is on `PATH`.
2. Install the Maestro CLI using its
   [official installation guide](https://docs.maestro.dev/getting-started/installing-maestro).
3. Enable USB debugging on a physical device (BLE coverage needs real
   hardware), connect it, accept the authorization prompt, and verify:

   ```sh
   adb devices
   ```

   Exactly one intended target should be listed as `device` rather than
   `unauthorized` or `offline`.
4. Build and install the debug APK, then run a smoke flow:

   ```sh
   ./gradlew :androidApp:assembleDebug
   adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
   flows/run.sh smoke
   ```

Remote-device arrangements are optional and deliberately not part of the
repository contract. If one is needed, point the runner at compatible wrapper
commands with the `ADB=/path/to/adb-wrapper` and
`MAESTRO=/path/to/maestro-wrapper` environment variables.

## Two test layers

### Layer 1 — JVM tests (Robolectric / pure Kotlin)

Run via Gradle. No device required. Fast (seconds). Covers:

- Pure-logic functions (`BrowserOriginFilter`, Bayesian aggregator
  math from FEAT-009, etc.).
- ViewModel state-flow assertions via Turbine.
- Repository round-trips against an in-memory SQLite driver.
- Compose snapshot assertions (when needed).

```
./gradlew :androidApp:testDebugUnitTest        # all JVM tests
./gradlew :androidApp:testDebugUnitTest --tests "com.cruxcoach.android.ui.board.BrowserOriginFilterTest"
./gradlew :shared:testDebugUnitTest             # shared module
```

Test-stack dependencies are already in `gradle/libs.versions.toml`:
Robolectric, Turbine, mockk, Compose UI test (junit4 + manifest),
Hilt-android-testing, sqldelight-sqlite-driver, OkHttp MockWebServer.

### Layer 2 — Maestro UI flows (real device + logcat)

Run via the wrapper:

```
flows/run.sh                   # all flows
flows/run.sh smoke             # one flow
flows/run.sh smoke detail-open # several
```

The wrapper:

1. clears the device's logcat ring buffer,
2. invokes Maestro (single session for full suite, iterative for
   filtered runs, with one `--reinstall-driver` upfront to recover from an
   occasional driver-start `EOFException`),
3. temporarily enables release diagnostics with `log.tag.PERF=DEBUG`, then
   snapshots `adb logcat -d -s PERF:D` post-run,
4. greps the snapshot for every pattern listed in `flow.expects`.

A flow passes only if Maestro reports it Passed AND every logcat
expectation is matched.

## When to run each layer

| Change | JVM tests | Maestro flows |
|---|---|---|
| Pure-logic / VM filter / SQL query | required | optional |
| New Composable / nav route / TopAppBar action | optional | required |
| Resource-string change (en + de) | optional | run smoke + the screen the string lives on |
| Hilt module / DI graph | required | required (a misbound graph crashes at run time) |
| Build-config / dependency bump | required | required (smoke at minimum) |

## Why both: false-positive failure modes each layer can't catch

- **Maestro alone is a leaky test.** A flow that does
  `tapOn: "Some text"` followed by `assertVisible: "Some other text"`
  proves a tap was accepted and a label rendered, but not that the
  *intended* composable was activated. Real example: `tapOn: "von .*"`
  on a climb card looks like it opens the climb-detail screen, but on
  cruxcoach-origin rows it hits the setter-name link and navigates to
  the SetterDetail screen instead. The UI shows plausibly-detail-like
  content either way, and pure UI assertions wave it through.
- **JVM tests alone miss layout, navigation, and data-state bugs.** A
  VM unit test passes because the state-flow says "navigated to X"
  but the actual NavHost route disagrees, or the Compose surface that
  was supposed to receive the click is z-ordered behind a sibling.

The Maestro flows that hit a navigation path therefore carry a
companion `flow.expects` file with PERF-tag logcat patterns proving
the *correct composable* entered. Example, `detail-open.expects`:

```
🧭 NAV START: BoardBrowser → ClimbDetail\(
🧭 .*BOARD_CLIMB_DETAIL composable entered
🧭 .*BoardClimbDetailVM\.init start
🧭 .*loadClimb start
🧭 .*loadClimb complete
🧭 .*NAV COMPLETE: BoardClimbDetail\(
```

These markers come from `PerfLogger.navStart()` /
`PerfLogger.navMilestone()` calls baked into the navigation pipeline
and ViewModel `init` blocks. They emit only on the genuine
ClimbDetail path, not on SetterDetail or any other screen that might
happen to render similar UI.

## Authoring new Maestro flows — the checklist

When you write a new flow, walk through this list before declaring
it ready:

1. **Tap target** — does each `tapOn` target the *intended* composable
   tree? Cards with overlapping clickable children (setter-link inside
   a card with its own onClick) are the usual trap. Tap on a Text
   element that has no own `clickable` modifier (e.g. the per-card
   "X Züge" move-counter) so the click propagates to the parent
   surface.
2. **Assertion uniqueness** — does each `assertVisible` target a
   string that's *only* visible on the intended screen? Strings
   like "Filter" or "Bluetooth" appear on multiple screens — combine
   two co-occurring strings (e.g. "Neuer Climb" + "Weitere Optionen"
   pin the climb editor uniquely).
3. **State coupling** — does the assertion depend on persistent
   device state (favorites, drafts, search history, Nostr key
   presence)? If so, document the assumption at the top of the flow
   AND keep the flow idempotent (toggle on, toggle off). For
   filter/personalisation flows where the dataset matters, prefer
   Layer 1 JVM tests — they control state precisely.
4. **Logcat expectation** — for any flow that exercises navigation,
   add a `flow.expects` file with `🧭 NAV START: ...` and the
   `BoardClimbDetailVM.init start`-style milestones. If no PERF
   marker exists for the path, consider adding one in the production
   code (`PerfLogger.milestone` is cheap and useful for both
   testing and field debugging).
5. **Cleanup** — does the flow leave persistent device state behind
   (a saved draft, a favorite, a non-default filter)? Tag with
   `mutates-state` and either undo at the end or accept the leak in
   the flow's docstring.
6. **YAML separator hygiene** — exactly *one* `---` between header
   and commands. A spurious second `---` from a multiline comment
   block crashes the Maestro parser.

## Common Maestro pitfalls observed on this device

- **Search bars need a focus-tap.** Tapping the search icon shows the
  EditText but doesn't focus it; a second tap on the same matcher is
  needed before `inputText` lands. See `flows/browser-search.yaml`.
- **Compose `Modifier.testTag(...)` does NOT surface as resource-id**
  in UIAutomator. Setting `testTagsAsResourceId = true` at the root
  was tried and doesn't propagate to descendants — it'd require
  per-composable opt-in. Stick to text / content-desc matchers.
- **`FilterChip` selected state** is exposed as
  `checkable=true checked=true/false` on a *parent* `<View>`, not as
  `selected=...` on the text node — Maestro's `selected:` matcher
  doesn't read it. Verify chip behaviour via downstream UI changes
  (or just delegate to a JVM test).
- **`adb logcat` parallel to a Maestro run** can race the Maestro driver
  session. The wrapper uses post-run `logcat -d` instead.
- **Maestro driver instrumentation app** can be evicted by Android's
  memory manager between sessions. The wrapper passes
  `--reinstall-driver` to the very first invocation; subsequent
  runs reuse the now-present driver.

## Adding logcat coverage for new code paths

If a flow needs to verify navigation-style behaviour but no PERF
marker exists, add one inline. Pattern (matches existing usage in
`BoardBrowserScreen.kt`, `BoardClimbDetailViewModel.kt`):

```kotlin
import com.cruxcoach.android.util.PerfLogger
…
PerfLogger.milestone("$name composable entered")
// or for navigation start:
PerfLogger.navStart(from = "$current", to = "$next")
```

These calls are gated off in normal release execution and feed both this test
infrastructure and the in-app `PerfLogger.reportStartupTimeline()` diagnostics
when enabled.

`PerfLogger` is automatic in debug builds and runtime-gated in release builds.
For a manual release-build investigation, set the property before starting the
app, then clear it afterward:

```sh
adb shell setprop log.tag.PERF DEBUG
adb shell am force-stop com.cruxcoach.android
adb shell monkey -p com.cruxcoach.android 1
adb logcat -s PERF:I
adb shell setprop log.tag.PERF ''
```

The flow runner performs this enable/restore sequence itself and fails if a
selected `.expects` file requires markers but the PERF snapshot is empty.

## Quick-reference command summary

```sh
# Build APK + install on a connected phone
./gradlew :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk

# Run Maestro UI flows
flows/run.sh                                # all
flows/run.sh smoke                          # only smoke
flows/run.sh detail-open detail-favorite-toggle

# Run JVM tests
./gradlew :androidApp:testDebugUnitTest
./gradlew :androidApp:testDebugUnitTest --tests "*BrowserOriginFilterTest*"

# Inspect a Maestro run's artifacts
ls /tmp/cruxcoach-flows-<timestamp>/
# maestro.log    — full Maestro stdout
# logcat-perf.txt — post-run PERF-tag snapshot

# Debug a single flow with screenshot capture on failure
~/.maestro/bin/maestro test --debug-output /tmp/dbg flows/$flow.yaml
```

## Health-checks if a run is failing in unexpected ways

```sh
# Is exactly one authorized phone listed?
adb devices

# Reinstall Maestro's driver app on the phone
~/.maestro/bin/maestro test --reinstall-driver flows/smoke.yaml

# Reset the phone to the Browser screen (workaround for sticky last-
# screen restore)
adb shell am force-stop com.cruxcoach.android
adb shell am start -W --activity-clear-task -n com.cruxcoach.android/.MainActivity
```
