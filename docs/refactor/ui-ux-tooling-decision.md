# UI/UX tooling decision

**Evidence date and retrieval date:** 2026-08-30 UTC

**Repository baseline:** CruxCoach v0.2.2, `18fb0b4f5eb549a4321b424c04b0adeb966cb5b4`

**Decision scope:** the Android reference UI, deterministic visual/a11y workflow,
portable KMP boundary, and an iPhone-first native shell.

This document is the technology gate for productive UI redesign. It records
what is stable, what is experimental, and which option solves a demonstrated
CruxCoach problem. A popularity ranking or search-result snippet is not treated
as evidence.

## Current stack and constraints

The checked-in version catalog declares Kotlin 2.3.0, AGP 8.13.2, Compose BOM
2026.02.00, Activity Compose 1.13.0, Navigation Compose 2.9.7 and Robolectric
4.14.1. `androidApp` uses Compose Material 3, compile SDK 36, target SDK 35 and
min SDK 26. Compose UI test and Robolectric dependencies already exist;
Roborazzi, Material 3 Adaptive, Compose screenshot testing and Macrobenchmark do
not. `shared` applies KMP but exposes only an Android target; its Apple targets
are commented out.

Gradle and release configuration are repository trust boundaries. This gate
therefore selects experiments and exit criteria but does not silently add a
plugin or upgrade a BOM. Such changes require a separate owner-reviewed
changeset. No BoardSimulator is available and `adb devices -l` returned no
device on 2026-08-30, so no claim of rendered Android quality is made here.

## Codex execution profile and working method

Use `gpt-5.6-sol` with reasoning effort `medium` for this program. OpenAI lists
Sol as the flagship complex-work model, with image input and skills support,
and lists `medium` as its default balanced reasoning effort. The active hosted
session does not expose a trustworthy model/effort attestation to repository
code, so the repository records the required profile without pretending to
verify the orchestrator setting.

OpenAI's current guidance supports the workflow used here:

- keep domain context, hard constraints, approval boundaries and measurable
  success criteria explicit;
- plan complex work as coherent changesets and verify with representative
  evaluations;
- provide a screenshot together with the framework, component, routing and
  interaction constraints rather than asking for an unconstrained imitation;
- make small, region-specific visual changes, review the live result, and use
  Git checkpoints;
- package a repeated workflow as a skill only after it is reliable, and keep a
  repository skill under `.agents/skills` when it belongs to the project.

The practical repository implementation is `ui-scenario-matrix.json` plus
`ui-slice-review.md`: frozen inputs, a Cartesian core-state matrix, one-region
hypotheses, explicit evidence, at most three correction rounds, and no
unreviewed golden update. The proposed `cruxcoach-ui-refactor` skill is deferred
until one real ADB screenshot/semantics loop has passed; otherwise it would
encode an unproven process.

Primary OpenAI evidence:

- [GPT-5.6 Sol model](https://developers.openai.com/api/docs/models/gpt-5.6-sol)
- [GPT-5.6 model guidance](https://developers.openai.com/api/docs/guides/latest-model)
- [Prompting Codex](https://learn.chatgpt.com/docs/prompting)
- [Build skills](https://learn.chatgpt.com/docs/build-skills)

## Google Android skills review

The reviewed source is Google's public
[`android/skills`](https://github.com/android/skills) repository at immutable
commit [`ea05a536`](https://github.com/android/skills/tree/ea05a53683d1fb1fc701c3ad91f494d25d4fc7c6),
dated 2026-08-27. The owner is the official `android` GitHub organization and
the repository is Apache-2.0 licensed. It was cloned only to `/tmp` for review;
nothing was installed globally or vendored.

The complete `SKILL.md` files for adaptive Compose, edge-to-edge, Navigation 3,
testing setup and experimental Compose Styles were read. Relevant referenced
guides were also checked, including screenshot testing/common test patterns,
type-safe destinations/Nav3 migration, Compose debugging and the adaptive
list-detail recipe.

| Skill | Evidence and fit | Decision |
|---|---|---|
| `testing-setup` | Its semantics-first local Compose tests, fakes, restoration checks, and device/theme/font matrix fit the existing Robolectric stack. Its default official screenshot recommendation is alpha and is overridden below. | **adopt rules**, do not vendor |
| `edge-to-edge` | Fits compile SDK 36 and Compose; correctly distinguishes visual background from safe interactive content, IME handling and double-applied insets. | **adopt rules** per slice, do not vendor |
| `adaptive` | Requires a complete Compose app and assumes Navigation 3 Scenes. Its Flexbox/Grid/MediaQuery branches require explicit experimental opt-in. CruxCoach still uses Navigation 2 and has deep links. | **defer skill**; adopt stable window-size principles only |
| `navigation-3` | Its migration guide assumes an atomic Nav2 removal and explicitly does not cover deep links or complex nested navigation. That conflicts with CruxCoach's large string-route graph, deep links and staged-risk constraint. | **defer**; first make Nav2 routes typed and tested |
| `styles` | Explicitly experimental, custom-components only, compile SDK 37+, Compose Foundation alpha/BOM 2026.04.01+ and project-wide opt-in. It does not style Material components. | **reject for this program** |

The skills are evidence inputs, not authority to bypass this repository's
dependency, parity or review gates. The exact reviewed skill files remain
available at the commit permalink, for example
[testing setup](https://github.com/android/skills/blob/ea05a53683d1fb1fc701c3ad91f494d25d4fc7c6/testing/testing-setup/SKILL.md),
[edge-to-edge](https://github.com/android/skills/blob/ea05a53683d1fb1fc701c3ad91f494d25d4fc7c6/system/edge-to-edge/SKILL.md),
[adaptive](https://github.com/android/skills/blob/ea05a53683d1fb1fc701c3ad91f494d25d4fc7c6/jetpack-compose/adaptive/SKILL.md), and
[Navigation 3](https://github.com/android/skills/blob/ea05a53683d1fb1fc701c3ad91f494d25d4fc7c6/navigation/navigation-3/SKILL.md).

## Framework and tool decisions

Maturity labels below refer to the specifically proposed API/tool, not the
general ecosystem. `adopt` means use in a staged slice; `spike` means no
production dependency until the exit criterion passes.

### Jetpack Compose, Material 3 and Material 3 Expressive — adopt / defer alpha

- **Problem:** retain Android-native behavior while replacing inconsistent,
  card-heavy screens with a coherent branded hierarchy.
- **Maturity/version:** Compose Material 3 stable is 1.4.0 as of 2026-08-26;
  1.5.0-alpha27 is pre-stable. Google's M3 guide notes that some APIs remain
  experimental. The repo already uses M3 through BOM 2026.02.00.
- **Benefit:** smallest architectural change, native semantics and established
  components; stable Expressive ideas can improve hierarchy and state motion.
- **Cost/risk:** broad adoption of alpha Expressive APIs would create churn and
  require a trust-boundary BOM change.
- **Alternatives:** custom Canvas/components, Compose Styles alpha, or a new UI
  framework. None solves a proven problem better.
- **Decision:** **adopt** Compose + stable Material 3. Use Expressive as design
  guidance and stable APIs only; **defer** alpha/experimental APIs.
- **Exit:** the logging reference slice passes behavior, semantics, contrast,
  48-dp, locale/theme/size/font matrix and reviewed screenshots without an
  experimental opt-in.

Evidence: [Material 3 release notes](https://developer.android.com/jetpack/androidx/releases/compose-material3),
[Material 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3).

### Semantic CruxCoach design system — adopt

- **Problem:** raw colors and ad-hoc spacing make status, hierarchy and
  accessibility inconsistent.
- **Maturity:** stable Compose theming and CompositionLocal mechanisms.
- **Benefit:** brand-stable light/dark color roles, typography, spacing, shapes,
  elevation and motion without a large component abstraction.
- **Cost:** token naming and contrast review; a premature component library
  would add indirection.
- **Alternative:** use raw `MaterialTheme` roles directly on every screen.
- **Decision:** **adopt** a thin semantic layer proven by real slices. Dynamic
  color is not the core surface; orange is a precise brand/state accent and
  never the only state encoding.
- **Exit:** no new raw color literal outside theme/token definitions; each new
  token has light/dark contrast evidence and at least one real consumer.

Evidence: [Compose design systems](https://developer.android.com/develop/ui/compose/designsystems),
[theme anatomy](https://developer.android.com/develop/ui/compose/designsystems/anatomy),
[custom design systems](https://developer.android.com/develop/ui/compose/designsystems/custom).

### Window size classes and canonical adaptive layouts — adopt incrementally

- **Problem:** current screen assumptions do not provide a deliberate compact
  versus expanded hierarchy; Android 16 also removes some large-display
  orientation/aspect restrictions for target SDK 36 apps.
- **Maturity/version:** Material 3 Adaptive 1.3.0 is stable as of 2026-08-26;
  1.4.0-alpha01 is pre-stable.
- **Benefit:** explicit width-derived UI state and list-detail/supporting-pane
  patterns; supports future iPad reasoning without pretending iPad is shipped.
- **Cost:** new AndroidX dependency and careful state/restoration tests.
- **Alternatives:** local `BoxWithConstraints` breakpoints or experimental
  Grid/Flexbox/MediaQuery. The latter are not accepted.
- **Decision:** **adopt** stable size-class concepts and explicit state now;
  add stable adaptive components only in an owner-reviewed vertical slice.
- **Exit:** compact and expanded browser/detail scenarios preserve the same
  actions and selected entity through live resize and process restoration.

Evidence: [support different display sizes](https://developer.android.com/develop/adaptive-apps/guides/support-different-display-sizes),
[canonical layouts](https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts),
[Material 3 Adaptive releases](https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive).

### Edge-to-edge and insets — adopt

- **Problem:** target SDK 35 enforces edge-to-edge on Android 15+, so controls,
  board content and IME surfaces can be obscured or double padded.
- **Maturity:** stable platform and Compose inset APIs.
- **Benefit:** predictable immersive board hero with safe interactive regions.
- **Cost:** screen-by-screen audit across bars, cutouts, gesture navigation,
  three-button navigation and IME.
- **Alternative:** opt out or hardcode bar sizes; neither is durable.
- **Decision:** **adopt** the Google skill's audit sequence per slice.
- **Exit:** compact/expanded screenshots show background under system bars,
  while every control remains within safe drawing/gesture/IME regions with no
  duplicate inset consumption.

Evidence: [set up edge-to-edge](https://developer.android.com/develop/ui/compose/system/setup-e2e),
[window insets](https://developer.android.com/develop/ui/compose/system/insets).

### `@Preview` state matrix — adopt

- **Problem:** screens depend on live repositories and cannot be rendered in
  stable error/offline/session states when ADB is absent.
- **Maturity:** stable Compose tooling; multipreview supports screen size,
  font-scale and light/dark templates.
- **Benefit:** fast deterministic construction and review of fixture-driven
  screen contracts.
- **Cost:** preview-safe state/action contracts and frozen fixtures.
- **Alternative:** device-only DesignLab; unavailable today and slower alone.
- **Decision:** **adopt**, with preview functions delegating to the same
  production composables and fake repositories used by local tests.
- **Exit:** every state in `ui-scenario-matrix.json` renders without network,
  database, BLE, current clock or singleton navigation state.

Evidence: [Compose previews](https://developer.android.com/develop/ui/compose/tooling/previews).

### Roborazzi — spike

- **Problem:** previews alone do not create reviewed, versioned pixel diffs;
  the official alternative is still alpha.
- **Maturity/version:** third-party Roborazzi 1.73.0, released 2026-08-25;
  Apache-2.0, active maintainer/repository. It supports Robolectric native
  graphics and Compose, but its preview scanner and iOS support are explicitly
  experimental and not all `@Preview` options are supported.
- **Benefit:** aligns with the existing Robolectric 4.14.1 local-test stack and
  can produce images/reports without ADB.
- **Cost:** Gradle plugin/dependencies, native-rendering variance, golden storage
  and human review discipline.
- **Alternatives:** Paparazzi, device screenshots, or Google's alpha plugin.
- **Decision:** **spike** one manually enumerated component/screen test; do not
  start with preview scanning or iOS.
- **Exit:** on the same pinned JDK/SDK, five repeated validations are byte-stable;
  a deliberate one-pixel/token change fails with an intelligible diff; EN/DE,
  light/dark, compact/expanded and 1.5 font-scale inputs work; record and verify
  tasks are separate; memory/time are acceptable. Then request owner review for
  the dependency changes.

Evidence: [Roborazzi repository](https://github.com/takahirom/roborazzi),
[1.73.0 release commit](https://github.com/takahirom/roborazzi/commit/6abd5fc0a780e2ee8c4509917c33f62682df8ccc).

### Official Compose Preview Screenshot Tests — defer

- **Problem:** same golden requirement as Roborazzi.
- **Maturity/version:** Google explicitly labels the tool experimental; current
  plugin 0.0.1-alpha15. Host tasks fit AGP 8.5+, but IDE integration requires
  AGP 9 and a Canary Android Studio. Non-Android KMP targets are unsupported.
- **Benefit:** first-party preview integration and HTML diffs.
- **Cost:** experimental Gradle flags/plugin and toolchain churn.
- **Alternative:** Roborazzi spike on the already used Robolectric stack.
- **Decision:** **defer** until Google publishes a stable plugin compatible with
  the repository's supported AGP/IDE.
- **Exit:** stable release, no experimental project property, supported CI/IDE,
  and a migration spike beats the accepted Roborazzi workflow.

Evidence: [Compose Preview Screenshot Testing](https://developer.android.com/studio/preview/compose-screenshot-testing),
[release notes](https://developer.android.com/studio/preview/compose-screenshot-testing-release-notes).

### Compose semantics and accessibility checks — adopt in layers

- **Problem:** the repo has little automated evidence for labels, roles,
  traversal, target size and contrast.
- **Maturity:** Compose semantics/tests are stable. Automated Accessibility Test
  Framework integration is available from Compose 1.8 but requires
  `ui-test-junit4-accessibility` and API 34.
- **Benefit:** the same semantic contract supports tests and assistive services;
  automated checks catch contrast, touch target and traversal issues.
- **Cost:** custom board semantics require judgment; automated audits never
  replace TalkBack/manual checks.
- **Alternative:** screenshots or content descriptions alone are insufficient.
- **Decision:** **adopt** semantic assertions immediately. Add API-34 automated
  accessibility checks in an owner-reviewed test dependency changeset, then add
  device TalkBack/Accessibility Scanner evidence when ADB returns.
- **Exit:** named state/action/role assertions and merged/unmerged tree snapshots
  pass; 48 dp minimum, 4.5:1 normal text, 3:1 large/non-text, logical traversal,
  large font and non-color status cues pass.

Evidence: [Compose semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics),
[Compose accessibility testing](https://developer.android.com/develop/ui/compose/accessibility/testing),
[Android accessibility guidance](https://developer.android.com/design/ui/mobile/guides/foundations/accessibility).

### Existing Maestro journeys — adopt/retain

- **Problem:** UI refactors can preserve isolated screens while breaking routes,
  deep links, import, persistence and logging journeys.
- **Maturity/version:** Maestro is active, Apache-2.0, with v2.9.0 available and
  commits through 2026-08-28. CruxCoach already has a hardened runner, evidence
  audits and many journeys.
- **Benefit:** black-box parity and real hierarchy/screenshot artifacts.
- **Cost:** device and app installation; less useful for pixel-level component
  differences.
- **Alternative:** replace with a new E2E framework; no demonstrated benefit.
- **Decision:** **retain/adopt** existing journeys. Add deterministic scenario
  entry points, not a second runner.
- **Exit:** relevant root flows pass before and after each vertical slice with
  audited JUnit, final screenshot, hierarchy and process-health evidence.

Evidence: [Maestro repository](https://github.com/mobile-dev-inc/Maestro),
[Maestro changelog](https://github.com/mobile-dev-inc/Maestro/blob/main/CHANGELOG.md).

### Macrobenchmark — spike after deterministic routes exist

- **Problem:** Browser startup/scroll and session transitions need regression
  evidence, not subjective smoothness.
- **Maturity/version:** AndroidX Benchmark 1.4.1 stable; 1.5.0-rc02 is pre-stable.
- **Benefit:** measures startup and end-user interactions out of process under
  controlled compilation.
- **Cost:** separate benchmark module/build variant, physical/emulator device,
  stable data seeding and trust-boundary Gradle changes.
- **Alternative:** ad-hoc timing or Layout Inspector; useful diagnostically but
  not a repeatable regression baseline.
- **Decision:** **spike** stable 1.4.1 only after DesignLab routes can seed the
  browser/detail/session state without network or BLE.
- **Exit:** 20+ controlled iterations for cold/warm startup and browser scroll,
  archived raw results/device config, and a before/after comparison using the
  tripwires in `ui-slice-review.md`.

Evidence: [benchmark overview](https://developer.android.com/topic/performance/benchmarking/benchmarking-overview),
[Benchmark releases](https://developer.android.com/jetpack/androidx/releases/benchmark).

### SwiftUI iPhone shell over KMP core — adopt, Mac-gated

- **Problem:** iOS needs native navigation, sheets, search, accessibility and
  CoreBluetooth without duplicating domain/session rules.
- **Maturity:** KMP core iOS targets and Compose Multiplatform iOS are stable;
  SwiftUI `NavigationStack` and accessibility are native platform APIs. Kotlin
  Swift export remains Alpha, so the stable Objective-C framework interop is
  the baseline.
- **Benefit:** native iPhone conventions and system integration over portable
  state/action contracts.
- **Cost:** Apple compilation/signing and VoiceOver/audit verification require
  Xcode on a Mac; exported APIs must stay Swift-friendly.
- **Alternative:** shared Compose navigation/UI. That weakens native integration
  without evidence of lower total cost for this app.
- **Decision:** **adopt** SwiftUI shell and standard KMP framework integration;
  **defer** Swift export Alpha. Keep CoreBluetooth in the iOS adapter.
- **Exit:** on a Mac, build/link arm64 and simulator frameworks, launch an iPhone
  shell with `NavigationStack`, drive the logging contract using a fake board,
  pass Dynamic Type/VoiceOver/XCTest accessibility audits, then test real BLE.

Evidence: [KMP platform stability](https://kotlinlang.org/docs/multiplatform/supported-platforms.html),
[iOS integration methods](https://kotlinlang.org/docs/multiplatform/multiplatform-ios-integration-overview.html),
[Swift export status](https://kotlinlang.org/docs/native-swift-export.html),
[SwiftUI navigation stack](https://developer.apple.com/documentation/swiftui/understanding-the-navigation-stack),
[SwiftUI accessibility fundamentals](https://developer.apple.com/documentation/swiftui/accessibility-fundamentals),
[Apple accessibility audits](https://developer.apple.com/documentation/accessibility/performing-accessibility-audits-for-your-app).

### Compose Multiplatform UI board spike — defer until the core boundary exists

- **Problem:** the branded board renderer may otherwise be implemented twice.
- **Maturity:** Compose Multiplatform iOS is stable and can embed inside SwiftUI,
  but embedding adds UIKit/Compose lifecycle, accessibility and performance
  boundaries.
- **Benefit:** potential parity for the most custom, graphics-heavy surface.
- **Cost:** runtime size, interoperability, gesture/semantics tuning and two UI
  technologies in the iOS shell.
- **Alternative:** native SwiftUI Canvas backed by shared geometry/protocol data.
- **Decision:** **defer**, then run one isolated measurable spike only for the
  board visualization; never use it to pre-decide iOS navigation/sheets/search.
- **Exit:** compare SwiftUI and embedded Compose board implementations on the
  same iPhones for frame pacing, binary delta, semantic rotor/VoiceOver output,
  gesture accuracy and maintenance size. Adopt shared UI only with a measured
  advantage and no accessibility regression.

Evidence: [Compose/SwiftUI integration](https://kotlinlang.org/docs/multiplatform/compose-swiftui-integration.html),
[KMP overview and gradual UI sharing](https://kotlinlang.org/docs/multiplatform/kmp-overview.html).

## Enforced UI evidence sequence

No productive visual implementation starts until its row in
`ui-slice-review.md` is complete. The sequence is:

```text
frozen baseline + semantics
-> one named region and falsifiable hypothesis
-> smallest vertical implementation
-> focused behavior/a11y tests
-> identical screenshot + semantics capture
-> objective and visual comparison
-> at most three corrections
-> golden/source diff review
-> checkpoint commit
```

The core matrix is Cartesian across English/German, light/dark,
compact/expanded and font scales 1.0/1.5 for every listed state. Loading, empty,
error, offline/disconnected, connected, active, resting and success must be
represented by typed state, not forced through live infrastructure.

When ADB returns, the same stable scenario must be captured with a command that
names the serial explicitly. The evidence bundle must include PNG, merged and
unmerged semantics/hierarchy, locale/theme/font/window configuration and test
result. Goldens are updated only by a separate record action and accepted only
after human visual review.

## Deferred gates and continuation

- **ADB rendering:** no device listed on 2026-08-30. Continue with deterministic
  state contracts and Robolectric/preview tests. Recheck with `adb devices -l`;
  once a serial appears, use the future DesignLab deep link and
  `MAESTRO_DEVICE_SERIAL=<serial> flows/run.sh <focused-flow>`.
- **BoardSimulator:** deliberately not started. After a human starts it, execute
  the fixture-driven board-family matrix using the explicit device serial; do
  not change the board protocol to accommodate the harness.
- **Apple:** Linux cannot provide Xcode, simulator, VoiceOver, CoreBluetooth or
  signing evidence. On a Mac, run the shared Apple compile/link tasks documented
  by the iOS changeset, then Xcode tests and `performAccessibilityAudit` on the
  same deterministic logging states.
- **Dependency/plugin changes:** Roborazzi, accessibility-test, adaptive and
  Macrobenchmark additions cross the Gradle trust boundary and require a
  separately reviewable owner-approved changeset after their stated spike
  prerequisites are met.
