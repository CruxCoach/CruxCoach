# Selective board catalogue downloads

Implementation target: `feat/0.2.3-release`. This describes source behavior,
not a published release or completed physical-device validation.

## UX and compatibility

Download selection is a device-wide preference, independent of the active board,
identity, sync interval and local catalogue contents. The download card exposes a
board count and a shared checkbox dialog, using the same control as per-board
deletion. The dialog supports all/none and individual boards.
Existing installations without the preference retain all supported boards.
Onboarding reviews the selection before its first bulk download, initially
suggesting the active board. Persisted choices take precedence on subsequent visits.

Saving a selection does not itself download or delete data. An empty saved selection
pauses catalogue downloads; starting the initial download requires at least one
board. Excluded local catalogues and all personal data remain usable. The status
list explicitly labels excluded boards. A hardware/active-board change does not
silently change the download preference.

Deleting public board data also excludes those boards from subsequent downloads.
Deleting personal logbook data does not change the catalogue preference. An explicit
single-board download opts only that board back in and runs in WorkManager, with
its board argument preserved through the worker.

## Download boundary

Each online run snapshots the selection and prioritises the active board only if
it is included. Kilter is an independent lane, so MoonBoard-only or Quantum-only
runs do not fetch the Kilter manifest or chunks. Each selected catalogue checks
its own manifest even when Kilter is unchanged. Optional beta media follows the
same selection. Missing-active-board loading, Kilter recovery/backfill paths and
picker-triggered Aurora/Quantum downloads respect exclusions.

The preference is intentionally separate from the direct device-sharing protocol:
a received signed snapshot contains the sender's shared database as one artifact.
It cannot be byte-filtered by board on the receiving device. The dialog explains
that direct transfers include the full shared snapshot; accepting a transfer does
not expand the online download selection. Catalogue selection does not filter
personal account synchronisation, community events, or user-selected media playback.

## Validation

Focused tests cover preference defaults, empty selection, unknown future values,
independence from active board, scoped deletion and opt-in, sync ordering, Kilter-only
and MoonBoard-only runs, media exclusion, unchanged-Kilter/non-Kilter updates,
explicit single-board requests, and the shared checkbox dialog's accessibility and
empty-selection rules. Existing per-board deletion UI tests remain applicable.

Local validation on 2026-09-16: 47 focused JVM/Robolectric tests passed, including
existing sync/discovery/resume, per-board SQL deletion, and deletion UI regressions.
The new cases also cover queued requests after exclusion, deletion during an active
import, optional media after deletion, fresh-worker/onboarding ordering, and keeping
the full-sync timestamp unchanged after a single-board update. English/German XML
resources parse without duplicate keys; `git diff --check` passes.

Full CI and physical-device acceptance remain pending. GitHub CLI/git have no
available credentials in this session, and the connector requires reauthentication.
No feature APK was built/published locally; repository rules reserve full builds
and suites for CI.

Physical-device acceptance for the release candidate:

1. First run: review board choices, choose only Kilter, confirm; leave and reopen
   the app during download. Only Kilter receives catalogue and beta-media updates.
2. Existing multiboard install: deselect all but MoonBoard, save and update; local
   Kilter climbs and logbook stay accessible, with Kilter marked excluded.
3. Restart and switch boards: preference survives and excluded catalogues do not
   auto-download. Explicitly load another board and verify only that board is added.
4. Delete a selected public catalogue, run a scheduled/manual update, and verify
   the deleted catalogue does not return. Personal-data deletion must not opt out.
5. Disable all downloads: no catalogue requests from the periodic worker. Opt a
   board back in, test Wi-Fi/mobile consent and offline queuing.
6. Accept a direct local snapshot: all offered snapshot data can be imported,
   while subsequent online updates still honor the saved subset.
