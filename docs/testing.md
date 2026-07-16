# Testing Workflow

CruxCoach has two complementary test layers, both runnable from the
dev server. **Always use both** when verifying a behaviour change —
each catches a different class of regression, and either alone has
known blind spots.

## Architecture

The dev server has no Android device of its own. A physical phone is
attached via USB to a separate machine ("VM host"). An SSH-reverse-
tunnel from VM host → dev server exposes the VM host's `adb` daemon
on the dev server's `localhost:6037`, and a tiny Python forwarder
maps `localhost:5037 → localhost:6037` so every adb-aware tool finds
the device on the default port.

```
   VM host (phone via USB)                  dev server (Claude Code)
   ┌──────────┐                            ┌──────────────────────────┐
   │ phone    │ adbd                       │  adb / maestro / gradle  │
   │ adbd     │←─────────┐                 │  via 127.0.0.1:5037      │
   └──────────┘  :5037   │                 │            ↓             │
                          │ SSH -R 6037:.. │      Python bridge       │
                          │   tunnel       │      :5037 → :6037       │
                          └────────────────│            ↓             │
                                           │      :6037 (tunneled)    │
                                           └──────────────────────────┘
```

Two systemd-user services keep this alive:

| Host | Service | Purpose |
|---|---|---|
| VM host | `adb-server.service` | keeps the local adb daemon up |
| VM host | `cruxcoach-adb-tunnel.service` | maintains the SSH `-R 6037:localhost:5037` tunnel to the dev server |
| dev server | `cruxcoach-adb-bridge.service` | Python TCP forwarder: `localhost:5037 → localhost:6037` |

`loginctl enable-linger $USER` is set on both hosts so the services
survive logout/reboot.

Wrappers on the dev server:

| Command | What it does |
|---|---|
| `dadb` | `adb` with `ANDROID_ADB_SERVER_PORT=6037` (kept around for explicit-tunnel use; default-port `adb` works through the bridge too) |
| `dmaestro` | Maestro CLI; same env override |

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
   filtered runs, with one `--reinstall-driver` upfront to dodge the
   1-in-3 EOFException race against tunneled adb),
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
- **`adb logcat` parallel to a Maestro run** races the tunneled adb
  session and trips `EOFException` in
  `AndroidDriver.startInstrumentationSession` ~1 in 3. The wrapper
  uses post-run `logcat -d` instead.
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
# Build APK + install on the tunneled phone
./gradlew :androidApp:assembleRelease
dadb install -r -d androidApp/build/outputs/apk/release/androidApp-release.apk

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
# Is the tunnel up?
nc -z 127.0.0.1 6037 && echo OK
nc -z 127.0.0.1 5037 && echo OK
adb devices                                # phone listed?

# Is the dev-server bridge running?
systemctl --user status cruxcoach-adb-bridge.service

# Reinstall Maestro's driver app on the phone
~/.maestro/bin/maestro test --reinstall-driver flows/smoke.yaml

# Reset the phone to the Browser screen (workaround for sticky last-
# screen restore)
dadb shell am force-stop com.cruxcoach.android
dadb shell am start -W --activity-clear-task -n com.cruxcoach.android/.MainActivity
```
