# Climb detail and board control UI slice

## Journey and user goal

A climber opens an exact climb and angle from the browser, a deep link, a
playlist, the logbook, or history. The board is the hero. The user must be able
to understand the problem, get it onto the physical board, and log the next
attempt without losing their navigation or active-session context.

The reference path is `detail -> connect or send -> active attempt -> log`.
Opening a deep link must still land directly on the requested climb. A
disconnected board is a recoverable state, not a dead end and not a reason to
redirect the user away from detail.

## Function and state parity

The slice must retain:

- loading, content, logbook-only fallback, not-found, and load-failure states;
- exact climb UUID/angle restoration and guarded swipe navigation;
- board visualization, multi-frame route playback, speed, loop, and preview;
- angle selection and supported mirror behavior;
- disconnected, connecting, connected, sending, sent, partial-send warning,
  board mismatch, relay, and session-owned delivery states;
- the documented automatic/explicit board-send policy and board-layer
  controls where the connected hardware supports them;
- quick send/attempt logging, full edit/delete logging, feedback, rest timer,
  and active-session integration;
- favorite/ignored state, add to list, personal note, nearby share, and climb
  history;
- setter/community identity, owner edit/delete, draft delete, publish, and
  bug-report actions when their existing authorization predicates allow them;
- browser, list, logbook, queue, notification, and deep-link entry paths.

Portable UI state requires `loading`, `content-disconnected`,
`content-connecting`, `content-connected`, `sending`, `sent`, `logbook-only`,
and typed `error`. Hardware details, Android resource IDs, exception messages,
SQLDelight rows, and GATT types remain outside it.

## Information hierarchy

1. Board visualization and climb identity: name, grade, angle, setter.
2. One delivery state/action: connect, send/light, sending, sent, or the
   session-owned equivalent. Status is explicit text plus icon/role, never
   orange or green alone.
3. Attempt actions: log attempt and log send, with active-session/rest context.
4. Progressive controls: angle/mirror and route playback, then layers where
   supported.
5. Personal and management actions: favorite, list, note, share, edit/delete,
   publish, ignore, and report.
6. Prior attempts and secondary metadata below the current-task controls.

Only the board and current climb receive hero weight. Secondary actions do not
compete as equal cards in the first viewport.

## Semantic token and component specification

- Use CruxCoach semantic colors, typography, spacing, shapes, and motion.
- The board surface has one descriptive semantic node; individual holds are
  not separately focusable unless an editing mode explicitly needs them.
- Delivery exposes a role, action label, and state description. Connected,
  offline, sending, success, warning, and mismatch each have text/icon cues.
- All action targets are at least 48 dp. Compact icon docks may reduce visual
  glyph size, not the interactive hit region.
- Angle and mirror controls expose selected/checked state. Route frame progress
  is announced without live-region noise on every animation tick.
- Quick logging announces send versus attempt and success versus failure. It
  reuses the shared `LogAttempt` contract rather than introducing detail-only
  persistence rules.
- Overflow actions keep accurate enabled and destructive semantics; hidden
  unauthorized actions are not exposed to accessibility services.

## Deterministic scenarios

The initial addressable pair uses one fixed Kilter problem at 40 degrees with
stable holds and grade labels:

- `detail/disconnected`: known climb, no board, explicit connect action;
- `detail/connected`: same climb and content, matching board connected, send
  action available.

Follow-on fixtures cover connecting, sending, sent, mismatch, route playback,
session-owned delivery, logbook-only, and typed error. The same fixture clock,
locale, theme, width, font scale, board coordinates, and formatted labels must
be used before and after a region change. No BoardSimulator is required to
render these states.

## First design hypothesis

A stable board-first hero with one stateful delivery control immediately below
it will make the physical next step clearer than the current dense collection
of equally weighted controls. Changing only the delivery region between the
disconnected and connected fixtures should preserve spatial memory: board,
identity, and logging actions do not jump.

Observable success: semantics order is board/identity, delivery, then logging;
the primary action label changes from connect to send without moving to another
screen; all existing secondary actions remain reachable through progressive
disclosure. This hypothesis is not visually approved until both fixtures are
rendered and compared on Android.

## Accessibility and performance budgets

- Interactive targets are at least 48 dp.
- Normal text contrast is at least 4.5:1; large text and meaningful non-text
  UI are at least 3:1.
- Board connection and send success are not encoded by color alone.
- At 1.5 font scale, the primary delivery and log actions remain visible and
  labelled without truncating the climb identity.
- TalkBack traversal follows the information hierarchy and never enters every
  decorative hold.
- Reduced-motion mode removes decorative transitions; delivery motion may
  explain a bounded sending/status transition only.
- Keep the existing navigation milestone. Establish before/after median detail
  open and first-meaningful-content metrics; investigate more than 5% median
  regression or 10% frame-time regression.
- Board coordinate parsing and image decoding are not repeated because of
  connection-state recomposition.

## Smallest implementation sequence

1. Add a portable detail projection and test disconnected/connected mapping.
2. Add fixed DesignLab fixtures for only the board hero and delivery region.
3. Capture baseline pixels/merged and unmerged semantics when ADB is present.
4. Implement the one-region delivery hypothesis and run focused semantics,
   mapping, detail-open, and attempt-logging tests.
5. Compare the same fixtures for at most three correction rounds before wiring
   the reviewed region into the full production screen.

## Current evidence and wiring gate

Both compact fixtures were rendered on the API-35 Nokia across EN/DE,
light/dark, and font-scale 1.0/1.5. The board remained the primary visual
region; connected/disconnected used text and icons, and both logging actions
remained visible at large text. Screenshots were opened and semantics parsed.
This approves the isolated compact hypothesis, not the full production
composition. Production wiring remains deferred because the existing screen
also owns playback, angle/mirror, session delivery, partial-send feedback and
authorized management actions that the isolated hero intentionally omits.
Expanded rendering and a parity-preserving host boundary are required first.
