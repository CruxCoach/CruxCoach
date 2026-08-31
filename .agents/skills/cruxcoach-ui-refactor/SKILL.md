---
name: cruxcoach-ui-refactor
description: Refine one CruxCoach Android Compose UI slice through deterministic DesignLab states, device screenshots, semantics, focused tests, and reviewed checkpoints. Use for concrete CruxCoach visual or accessibility changes; do not use for generic architecture, BLE transport, release, or publishing work.
---

# CruxCoach UI Refactor

Use the smallest evidence-producing slice. Read the applicable `AGENTS.md` files and `docs/refactor/external-gates.md` before commands or edits.

## Required inputs

Establish these before visual implementation:

- journey and user goal;
- one target region and an explicit design hypothesis;
- reachable functions and states that must remain at parity;
- DesignLab scenario IDs and applicable EN/DE, Light/Dark, 1.0/1.5 font-scale, and compact/expanded axes;
- semantic token/component specification;
- accessibility and performance budgets;
- installed package version/commit, when using a device.

If one is missing, inspect the repository and derive it when unambiguous. Stop for user direction only when the missing choice would materially change product behavior.

## Evidence loop

1. Record the baseline before changing the region. Use existing scenarios and fixtures; do not substitute live data.
2. Capture a single state with `scripts/capture_design_lab.sh`. Use `scripts/capture_design_lab_matrix.sh` only for the complete width-specific matrix. Let its width guard reject mislabeled compact or expanded evidence.
3. Open every resulting screenshot and inspect every `semantics.xml`. Contact sheets may make a matrix reviewable, but reopen defects at original resolution. Never accept or update a Golden from an unchecked diff.
4. Check content hierarchy, clipping, system insets, large-text wrapping, 48-dp targets, accessible names/roles/states, traversal, text and relevant non-text contrast, and state cues beyond colour.
5. Implement only the target region and retain navigation IDs, test tags, parity, and EN/DE strings. Keep raw colours inside the design system.
6. Run repository validators and the narrow Compose/scenario tests for the changed contract. Do not duplicate CI's full Gradle, lint, or APK loops.
7. Recapture the same state and compare pixels, semantics, and behavior to the baseline. Perform at most three reasoned correction rounds for that region.
8. Review the full diff, update parity/gate evidence, and create a coherent checkpoint commit only after focused checks pass.

Use `/tmp/cruxcoach-designlab-<version-or-commit>` for unversioned capture evidence. Record durable findings and exact reproduction commands in `docs/refactor/external-gates.md`; do not commit unchecked screenshots.

## Stop and defer precisely

- Without ADB, build deterministic scenarios and run semantics/tests, but do not claim rendered quality.
- If the installed APK does not contain the source change, mark that pixel axis unverified. Do not infer device success from unit tests.
- On compact hardware, leave expanded as a separate renderer gate; do not distort `wm size` as evidence.
- Treat signing, CI, APKTrack, simulator GUI, and human permission gates as external. Preserve their identifiers and authorized retry rules; continue independent UI work.
- Do not start or stop BoardSimulator, publish, install with `adb install`, or modify trust-boundary files merely to complete this loop.

## Handoff

Report the reviewed scenarios and axes, concrete visual and semantic findings, correction rounds, focused test results, source/APK identity, remaining external gates with commands, and the checkpoint commit. Distinguish rendered evidence from inferred or unit-only evidence.
