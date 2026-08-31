# UI slice: log a board-climb attempt

Status: compact Android flow reviewed; corrected locale build pending

Decision date: 2026-08-30

This specification is the precondition for changing the existing
`AscentLoggingDialog`. It covers only the explicit logging form. The quick-log
dock, board hero, navigation, logbook layout, persistence schema, sync, and
session lifecycle are outside this visual changeset.

## Journey and user goal

1. A climber opens a climb detail, directly or through any supported deep link.
2. They open the explicit log form after a burn.
3. They choose `Send` or `Attempt` and record the number of attempts.
4. Send-only details are disclosed only for a send; the note stays optional.
5. They save and return to the same climb variant.
6. The durable entry appears in the logbook and the active-session total is
   updated. A send also updates recent climb history.

The primary goal is to record an honest result quickly with one hand, without
losing the richer fields used for later analysis.

## Functional and state parity

| Existing behavior | Required after this slice | Evidence/gate |
|---|---|---|
| Create send or unfinished attempt | Preserve | `LogAttemptUseCaseTest`, focused Compose test |
| Attempt count has a minimum of one | Preserve | semantics/interaction test |
| Optional quality of one to five stars on sends | Preserve; no 36 dp targets | semantics/interaction test |
| Optional benchmark marker | Preserve for sends; attempts never persist it | use-case validation and UI test |
| Optional comment | Preserve and normalize blank to absent | `LogAttemptUseCaseTest` |
| Edit existing send or attempt | Preserve; outcome remains immutable while editing | existing logbook path and fixture preview |
| Cancel without writing | Preserve | focused Compose callback test |
| Save into current-format ascent/bid tables | Preserve | writer adapter plus existing repository tests |
| Refresh detail history and active-session totals | Preserve | existing logger integration test |
| Quick-log consolidation, promotion, seven-second undo | Unchanged and outside visual slice | `AscentLoggerQuickLogTest` |
| Loading/saving/error feedback | Saving disables duplicate submission and dismissal; failure preserves the form and exposes retry | portable submission state, logger failure test, focused Compose test |

Historical backup, database, share/import, playlist, and BLE formats are not
read by this UI and must not be changed by the slice.

## Information hierarchy

1. Title and dismiss affordance establish the bounded task.
2. Outcome is the first decision and is expressed by text, selection semantics,
   and iconography—not color alone.
3. Attempt count is the primary numeric input, with decrement, value, and
   increment in one 48 dp minimum row.
4. Quality and benchmark are progressively disclosed for sends. They do not
   appear for unfinished attempts because those values are not persisted.
5. Note is optional and visually secondary.
6. `Save` is the sole primary action; `Cancel` remains available and does not
   mutate the form.

The climb/board visual remains the page hero behind this focused modal. This
slice must not introduce another decorative hero or persistent animation.

## Semantic token and component specification

- Use `MaterialTheme.colorScheme` plus CruxCoach semantic aliases; no raw color
  literals in the component.
- Brand orange identifies the primary action and selected control treatment.
  Positive status colors are reserved for confirmed outcomes, not generic
  decoration.
- Spacing steps used here: 4, 8, 12, 16, and 24 dp. Interactive controls have a
  minimum 48 dp touch target.
- Shapes use small/medium/large theme roles. The dialog does not own arbitrary
  radii.
- Typography uses Material roles and must reflow at 1.5 font scale without
  clipped labels or unreachable actions.
- Outcome choice is one mutually exclusive selectable group. Attempt stepper
  controls expose localized action labels and the value exposes a state
  description. Quality exposes both the selected rating and each selectable
  rating to accessibility services.
- Motion is limited to content-size/layout changes when send-only details are
  disclosed. No repeating or decorative animation.

## Deterministic scenarios

The preview and Robolectric fixtures use no repositories, clock, random IDs,
BLE state, or network:

| Scenario | Form state | Purpose |
|---|---|---|
| `log/new-send` | send, 1 attempt, no quality/note | default hierarchy |
| `log/new-attempt` | attempt, 3 attempts | progressive disclosure |
| `log/edit-send` | edit, 2 attempts, quality 4, benchmark, note | maximum content |
| `log/success` | durable send confirmation for a fixed climb | end-state clarity |
| `log/error` | failed edit with all inputs retained | recovery and retry |
| `log/large-text` | edit-send fixture at font scale 1.5 | reflow and targets |

Render coverage must include English/German, light/dark, compact/expanded,
and font scales 1.0/1.5 according to `ui-scenario-matrix.json`. A preview is a
fixture, not visual evidence until its pixels have actually been rendered and
reviewed.

## Design hypothesis and comparison region

Hypothesis: making outcome and attempt count the first two controls, hiding
non-persisted send-only fields for attempts, and enlarging every compact icon
target will reduce interpretation and mis-taps while retaining every durable
field.

The comparison region is the logging dialog only. Baseline and candidate must
use the same scenario, locale, theme, width, font scale, and fixed content.
At most three correction rounds are allowed before the result is escalated for
human direction. Golden changes are never accepted automatically.

## Accessibility and performance budgets

- Minimum interactive target: 48 dp.
- Text contrast: WCAG 2.2 AA 4.5:1 for normal text and 3:1 for large text;
  meaningful non-text controls: 3:1 against adjacent colors.
- Color is never the only signal for outcome, selection, rating, or disabled
  state.
- Dialog title, mutually exclusive outcome group, controls, editable note, and
  actions must have deterministic traversal order and meaningful roles/labels.
- German and 1.5 font-scale fixtures must not clip or overlap. The dialog may
  scroll vertically when height is constrained.
- No frame-by-frame work, I/O, repository construction, or unbounded animation
  is permitted in the component. The change must not add work to app startup.
- Macrobenchmark is not meaningful for an isolated dialog; the later complete
  detail/session slice owns before/after navigation and frame baselines.

## Verification record

- Baseline screenshot: blocked on 2026-08-30; `adb devices -l` lists no device.
- A direct Robolectric capture of the dialog window was also attempted in
  native and default graphics modes; it failed at the documented renderer
  boundary and produced no accepted baseline.
- Baseline semantics passed before the visual change. Candidate semantics now
  cover exclusive outcome selection, progressive send-only fields, named
  actions, and 48 dp minimum width and height for compact controls.
- Android candidate, logger integration, and token contrast tests pass using
  the process-local SDK override documented in `external-gates.md`.
- Explicit persistence failure now preserves every form field and returns the
  portable submission state to `FAILED`; retrying transitions through `SAVING`
  and duplicate save/dismiss actions are disabled while I/O is active.
- Correction rounds: zero pixel-based rounds; pixels have not been rendered
  successfully and visual quality is therefore not claimed.
- Apple comparison: deferred until the shared contract is exported and the
  native SwiftUI shell exists on a Mac.

Exact continuation commands remain in `docs/refactor/external-gates.md`.
