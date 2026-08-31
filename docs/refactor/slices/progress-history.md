# Progress history UI slice

## Journey and user goal

From the board browser, a climber opens the device-local history to find a
recently viewed or climbed problem and return to its detail screen. They can
also choose how long this lightweight history is retained and remove selected
entries. This is browsing history, not the send/attempt logbook.

Success means the climber can distinguish those concepts, scan the newest
entries first, open a climb, and understand that the history is not backed up.

## Function and state parity

The slice must retain:

- newest-recorded-first ordering;
- navigation to the exact climb UUID and angle;
- grade formatting in the user's selected scale;
- board/layout identity, angle, and recorded date;
- retention choices: off, 30, 90, and 365 days;
- contextual single/multi-selection, select all/deselect all, confirmation,
  and deletion;
- the explicit device-local/not-in-backup disclosure;
- pruning and selection cleanup when repository data changes.

Required screen states are `loading`, `content`, `empty`, and `error`.
Selection is an orthogonal content mode, not a fifth data-loading state.
Repository, preference, and time failures must become typed state; raw
exceptions and database details do not cross the screen contract.

## Information hierarchy

1. Page identity or contextual selection count, plus back navigation.
2. Retention control and the device-local disclosure.
3. A quiet chronological list: climb name is primary; grade, angle,
   board/layout, and date are supporting metadata.
4. Selection actions appear only when they are relevant.
5. Empty and error recovery replace the list without removing navigation.

The board/climb remains the visual identity. Orange marks selection and the
primary recovery/action state; it is never the only selection signal.

## Semantic component specification

- Use CruxCoach semantic color, typography, spacing, shape, and motion tokens.
- Retention is one labelled, mutually exclusive control group. Labels are
  resources in English and German, never Kotlin literals.
- Every row exposes one concise accessible name assembled from the same
  primary and supporting data shown visually.
- Selection exposes checked state as well as color. Select-all and delete use
  accurate action labels; a disabled delete action is not announced as
  clearing selection.
- The local-only disclosure is explanatory text, not a warning-colored card.
- Loading, empty, and error regions expose a heading; retry is the single
  primary error action.

## Deterministic scenarios

`progress/history` uses a fixed `2026-08-30T12:00:00Z` clock and fixture rows
covering two board families, a long climb name, a missing grade, and selection.
The required state set is:

- loading;
- content;
- content with multiple selected rows;
- empty;
- error.

Each state is exercised across the repository-wide Cartesian EN/DE,
light/dark, compact/expanded, and 1.0/1.5 font-scale matrix. Dates and grade
labels are preformatted at the platform boundary so fixtures remain stable.

## First design hypothesis

A restrained list with one compact retention control above it will make the
history faster to scan than equal-weight cards. The row uses name and grade as
the first visual anchor, while board, angle, and date form a secondary line.
Contextual selection changes the app-bar title and actions without changing
row geometry. This hypothesis is not approved until the same DesignLab state
has been rendered and reviewed on Android.

## Accessibility and performance budgets

- Interactive targets are at least 48 dp.
- Normal text contrast is at least 4.5:1; large text and meaningful non-text
  UI are at least 3:1.
- Selection, loading, and error are never encoded by color alone.
- TalkBack traversal follows app bar, retention/disclosure, then list order.
- At 1.5 font scale, labels wrap or reflow without clipping or hiding actions.
- Stable keys prevent unnecessary list item recreation; no date or grade work
  runs per animation frame.
- Establish a Macrobenchmark baseline for opening and scrolling a populated
  history before a broad production redesign; this contract adds no new
  production dependency.

## Smallest implementation sequence

1. Localize the existing retention labels and correct action semantics.
2. Add portable state/action contracts and fixture tests without changing the
   database or navigation.
3. Add addressable deterministic scenarios and semantics assertions.
4. Capture the existing screen and one-region candidate on a real Android
   renderer; allow at most three reviewed correction rounds.
5. The reviewed candidate body is wired into the Android production screen:
   the existing app bar, exact UUID/angle navigation,
   select-all/delete confirmation, repository ordering, pruning, and grade
   preference remain platform-owned. The portable mapper and Compose semantics
   tests pass. Pixel verification of this production composition still needs a
   centrally signed APK containing that commit.

The production ViewModel now exposes initial loading and maps a failed history
stream to `LOAD_FAILED`; retry cancels and starts that collector again. Thus
loading/error/retry are real production states without exposing exception or
database details. Retention-update and delete failures are still logged only;
their typed, retryable in-content feedback remains the next architecture step.
