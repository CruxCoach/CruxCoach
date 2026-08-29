# Implementation plan: playable lists and player follow-ups (0.2.2)

This plan tracks three independent specs:

- **FEAT-045:** playable lists with optional training plans - implemented in
  this branch;
- **FEAT-046:** player lifecycle and gesture upgrades - still planned;
- **FEAT-043:** reach-metric wiring - still independent and planned.

The earlier plan assumed separate `list` and `playlist` database kinds. That
assumption was superseded before the first 0.2.2 release. FEAT-045 is now the
authoritative model: unique list membership plus optional ordered playback
steps.

## Status

| Package | Scope | Status |
|---|---|---|
| L1 | final SecureDB model and migration | complete |
| L2 | repository, backup, and compatibility | complete |
| L3 | unified hub, detail, and add dialog | complete |
| L4 | quick-play setup and advance rules | complete |
| L5 | training-plan editor, generator, import, and sharing | complete |
| P1 | atomic drag reorder of training-plan steps | planned in FEAT-046 |
| P2 | finger-tracking player swipe | planned in FEAT-046 |
| P3 | playback foreground service and process-death resume | planned in FEAT-046 |
| R1 | reach metric wiring | planned in FEAT-043 |

## Completed FEAT-045 packages

### L1 - Storage model

- Keep `(list_id, climb_uuid)` as the unique membership key in
  `climb_list_entries`.
- Remove the unreleased `climb_lists.kind` concept.
- Add per-list playback settings.
- Add `list_playback_steps` for repeated climbs, pinned angles, and rests.
- Define migration 10 directly from the released 0.2.1 schema. No migration is
  provided for unreleased developer-only intermediate schemas.

Gate: SQLDelight generation and secure migration verification.

### L2 - Repository and portability

- Separate membership operations from training-plan operations in
  `PersonalBoardRepository`.
- Make plan replacement transactional and preserve/establish membership for
  every referenced climb.
- Delete matching plan steps when membership is removed.
- Back up membership, plan steps, and playback settings separately.
- Keep legacy nullable wire fields readable without retaining a product-level
  playlist kind.

Gate: SQL-backed repository and secure backup round-trip tests.

### L3 - One lists workflow

- Render one Lists hub and one list card type.
- Open every object in the normal list detail.
- Keep add-to-list as an idempotent membership toggle.
- Offer plan editing as a secondary action only when relevant.
- Keep Ignored non-playable and built-in rename/delete restrictions intact.

Gate: Android compile plus ViewModel/repository coverage.

### L4 - Playback setup

- Let every eligible list play in normal-list mode.
- Offer a plan source when a plan exists.
- Configure list/shuffle, default rest, and manual/after-send/after-log advance
  in a per-play sheet.
- Persist those defaults on the list.
- Reject mixed concrete board configurations before creating a session.
- Surface unresolved catalogue references explicitly.

Gate: queue construction and coordinator transition tests.

### L5 - Plan creation and interchange

- Edit repetitions, rests, order, and pinned angles without changing list
  membership.
- Append members added since plan creation, or deliberately reset the plan
  from membership.
- Preserve full plan fidelity in V2 share links while retaining V1 parsing.
- Route generated and imported content back to normal list detail.

Gate: generation pipeline and share-link compatibility/fidelity tests.

## Remaining FEAT-046 packages

These packages are follow-ups, not part of FEAT-045 completion.

### P1 - Atomic drag reorder

- Reorder `list_playback_steps`, never `climb_list_entries`.
- Persist a complete ordered snapshot in one repository transaction.
- Serialize UI mutations so rapid drags cannot commit stale index deltas.
- Keep move up/down controls as the accessible fallback.

### P2 - Finger-tracking swipe

- Make the current player content follow horizontal drag.
- Gate anchors by `hasPrevious` and `hasNext`.
- Spring back below threshold and commit exactly once above threshold.
- Verify interaction with board taps and quick-log actions on-device.

### P3 - Playback lifecycle

- Keep the coordinator as the source of truth; the service is only a lifecycle
  and notification adapter.
- Persist a versioned snapshot for both normal-list and training-plan sessions.
- Offer explicit resume after process death; never silently resume a joined
  participant session.
- Clean up orphaned rest alarms on discard or stale snapshots.
- Use a distinct notification/channel from CruxRelay and verify both can run
  concurrently.

## Independent FEAT-043 package

Reach-metric schema, computation, and UI wiring remain independent of list
playback. Follow FEAT-043 and do not couple its migration or tests to the
SecureDB work above.

## Quality gates

- `:shared:verifyCommonMainSecureDatabaseMigration`
- `:shared:testDebugUnitTest`
- `:androidApp:testDebugUnitTest`
- `:androidApp:assembleDebug`
- `git diff --check`
- on-device checks remain required for BLE behaviour, gestures, foreground
  service lifecycle, and notification coexistence when their scopes land.
