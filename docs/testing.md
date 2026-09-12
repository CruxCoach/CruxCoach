# Testing Workflow

Choose checks that exercise the behavior changed. Follow [AGENTS.md](../AGENTS.md):
full Gradle suites, full APK builds and Android lint belong in CI. Locally run
only focused tests or selected device flows; documentation needs link/reference
checks, not an APK build. The two layers below are complementary, not a demand
to run every test twice.

Feature unit-test jobs register `scripts/ci-test-problems.json` when running
in GitHub Actions. Failed test names, exception locations and Gradle failure
summaries become public check annotations, even when full job logs require
sign-in. This also covers the trusted publisher's separate test job. The
matcher does not change test results or publication gates; a successful test
run still does not prove that an APK was published. Gradle configuration
changes require owner review before integration.

## Device access and environment

The old direct reverse-tunnel/port-6037 setup is historical. The 2026-09-06
handoff describes a transitional loopback ADB bridge on `localhost:5037`
through a restricted production proxy; `dadb devices` is the starting check.
On the development host, read `~/SERVER-SPLIT-STATUS.md` and parent agent rules
before using it. Do not expose ADB publicly, replace the bridge or stop the
original device tunnel. These are operator-owned dependencies.

The transferred emulator/AVD was listed successfully, but an operational
emulator without KVM was not confirmed. A physical device remains necessary
for meaningful BLE tests. Device tests can change application state: use the
agreed test device and document setup/cleanup.

## Two test layers

### Layer 1 — JVM tests (Robolectric / pure Kotlin)

Run via Gradle. No device required. Fast (seconds). Covers:

- Pure-logic functions (`BrowserOriginFilter`, Bayesian aggregator
  math from FEAT-009, etc.).
- ViewModel state-flow assertions via Turbine.
- Repository round-trips against an in-memory SQLite driver.
- Compose snapshot assertions (when needed).

```
./gradlew :androidApp:testDebugUnitTest --tests "com.cruxcoach.android.ui.board.BrowserOriginFilterTest"
```

Test-stack dependencies are already in `gradle/libs.versions.toml`:
Robolectric, Turbine, mockk, Compose UI test (junit4 + manifest),
Hilt-android-testing, sqldelight-sqlite-driver, OkHttp MockWebServer.

### Layer 2 — Maestro UI flows (real device + logcat)

Run selected flows via the wrapper (inspect device-state effects first):

```
flows/run.sh smoke             # one flow
flows/run.sh smoke detail-open # several
```

The wrapper:

1. clears the device's logcat ring buffer,
2. invokes Maestro (single session for full suite, iterative for
   filtered runs, with one `--reinstall-driver` upfront to dodge the
   1-in-3 EOFException race against tunneled adb),
3. snapshots `adb logcat -d -s PERF:D` post-run,
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

These calls cost nothing measurable and feed both this test
infrastructure and the in-app `PerfLogger.reportStartupTimeline()`
diagnostics.

## Quick reference

```sh
# Confirm the agreed test device is available
dadb devices

# Focused JVM test; full suites/build/lint run in CI
./gradlew :androidApp:testDebugUnitTest --tests "*BrowserOriginFilterTest*"

# Selected device flow against an already available test APK
flows/run.sh smoke
```

The wrapper leaves artifacts under `/tmp/cruxcoach-flows-<timestamp>/`.
Inspect its log and PERF snapshot together. If the device is unavailable,
report that limit; do not repair shared infrastructure or substitute a full
local build for missing device evidence.
