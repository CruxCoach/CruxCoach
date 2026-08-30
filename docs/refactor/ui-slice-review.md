# UI slice review contract

Copy this section into the changeset notes before changing a productive screen.
One changeset may test one visual hypothesis in one named region.

## Before implementation

- Journey and user goal:
- Capability and state rows in `parity-matrix.json`:
- Preserved actions, routes, deep links and restoration behavior:
- Information hierarchy (hero, primary action, secondary disclosure):
- Semantic tokens/components used or introduced:
- Deterministic scenario IDs and fixtures:
- Baseline screenshot and merged/unmerged semantics paths:
- One-region hypothesis and observable success criterion:
- Accessibility budget: 48 dp targets; text contrast 4.5:1 (3:1 for large
  text); relevant non-text contrast 3:1; label, role, state and traversal order;
  no color-only state.
- Performance budget: record the same startup/scroll/session metric before and
  after; investigate a median regression above 5% or a frame-time regression
  above 10% before accepting the slice. These are regression tripwires, not a
  claim about absolute device performance.

If any item is missing, stop the visual implementation and create the missing
contract or fixture first.

## Evidence loop

1. Render the unchanged scenario and retain its screenshot and merged/unmerged
   semantics trees.
2. Implement the smallest version of the single-region hypothesis.
3. Run focused behavior and semantics tests.
4. Render the identical scenario and configuration again.
5. Compare screenshot, semantics, accessibility, behavior and measured metric.
6. Perform no more than three autonomous correction rounds.
7. Inspect the source diff and every changed golden. Never update a baseline as
   a side effect of validation.
8. Commit only after the evidence paths and result are recorded below.

## Changeset evidence

- Scenario/configuration:
- Before screenshot/semantics:
- After screenshot/semantics:
- Focused tests:
- Accessibility result:
- Performance result:
- Visual review decision and reviewer:
- Correction rounds used:
- Deferred external gate and exact continuation command:

An unavailable ADB device changes the deliverable to preview/Robolectric state
contracts and tests. It does not permit a claim that the result was visually
verified on Android. A real-device or emulator rendering review remains open.
