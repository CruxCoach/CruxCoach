# Kilter log upload investigation (0.2.3)

Examined `feat/0.2.3-release` at `2b4607898` on 2026-09-16. This is
source-level investigation with synthetic tests, not a reproduction against
the reporter's account or proof of the current Kilter server contract.

## Meaning of the setting

The setting uploads logbook sends and attempts for Kilter climbs. Creating
climbs on Kilter uses the separate climb-publication setting and API.
`SettingsViewModel.setKilterPushEnabled` only persists a boolean; it does not
start a sync. The connected card offers a separate Sync now action.
Automatic upload requires both persistent sync and push enabled plus stored
credentials. Quick logging invokes upload after its undo window, via
`AscentLogger.finalizePendingQuickLog`; the immediate callback is not the
upload boundary.

## Confirmed code defects and diagnostic gaps

- `KilterSyncEngine.uploadUnsyncedLogs` returns zero when user identity or
  required wall context is absent. `syncBidirectional` then reports a clean
  success even when local rows remain queued.
- If batch 1 (200 rows) succeeds and batch 2 fails, the function returns 200.
  `uploadFailed` is false: partial failure is displayed as success. Only
  successful batches are marked synced, so the remaining rows stay queued.
- A complete upload failure sets `uploadFailed`, but its exception is lost
  from the report. HTTP code/body are read by `KilterApiClient.uploadLogs`
  and can reach logcat; they are absent from the user-facing sync result.
- Automatic upload errors only reach logs; there is no log-upload queue
  status equivalent to the separate climb-publication queue UI.
- `kilterLastSync` is updated even on an upload failure. It is not evidence
  that local logs reached Kilter.
- `DevContactViewModel.sendBugReport` attaches only the generalized Android
  API level, device tier and language, not app version/build or API diagnostics.
- The API client treats HTTP success as upload success without inspecting an
  acknowledgement. Whether the live endpoint reports individual rejection
  in a successful response still needs a real contract/sample response.
- Custom-wall creation falls back to locally generated context even on
  server rejection. Comments claim this works with Kilter; this investigation
  has not validated that assumption against the current server.

## Recommended fix

1. Replace nullable upload counts with a structured result: attempted,
   uploaded, still pending, outcome (nothing pending / success / partial /
   blocked / failed), typed reason and optional HTTP status. Preserve partial
   progress and distinguish a missing prerequisite from an empty queue.
2. Start an explicit upload/sync after saving the enabled setting, showing
   progress and counts. Serialize manual/background attempts and re-check
   opt-in when work starts. Explain that this transfers logbook entries;
   publishing self-created climbs is separate.
3. Persist the last upload outcome and show queue size, actionable failure
   and Retry now in Kilter settings. Separate last attempted sync from last
   successful upload. Authentication failures should offer reconnect;
   missing wall configuration should offer configuration/retry.
4. Preserve structured failure information from API client through engine
   and UI. Close HTTP responses with `use`. Validate server acknowledgements
   according to a verified contract before marking rows synced. Do not add
   an assumed response schema or a required immediate read-back without
   checking server behavior and consistency delay.
5. Add a bounded diagnostic buffer (e.g. latest 20 attempts, expire after
   7 days), with app version/build, operation name (`logs.bulk`), trigger,
   outcome, HTTP status, allowlisted error code, duration, counts, and
   booleans for credentials/wall-context availability. No tokens, headers,
   account/gym/wall/climb identifiers, log comments, request bodies, raw
   response bodies or unrestricted exception text. An API error body may
   echo private request data: truncation alone is not sanitization.
6. Offer a previewable “Attach technical diagnostics” option in the bug
   form and a Report problem shortcut from upload errors. Capture the
   relevant attempt when opening the form, so later background attempts
   do not replace it. Reuse the existing report delivery channel.

## Focused tests and remaining validation

The original `KilterUploadInvestigationTest` characterized pre-fix behavior for a successful
upload, complete failure, partial batch failure, missing wall context and
missing identity. It has now been replaced by `KilterUploadStatusTest`, asserting the corrected results.

Executed successfully: 5 tests, 0 failures/errors/skips, using
`ANDROID_HOME=/home/myuser/android-sdk ./gradlew :androidApp:testDebugUnitTest --tests 'com.cruxcoach.android.data.kilter.KilterUploadInvestigationTest' --console=plain`.
Only this focused class was selected; no APK build or full suite was run.

No production account was written to. `dadb devices` listed no devices.
A controlled end-to-end check still needs a connected test device and test
account: upload one known Kilter ascent, record the sanitized response, check
retrieval by log UUID and then visibility in the official app's logbook with
matching account/board/filters. The reporter's exact app version, whether
Sync now was used, displayed result, and whether “climbs” meant logbook entries
or authored problems remain unknown.

## Implementation (2026-09-17)

Implemented on `fix/0.2.3-kilter-upload`, based on the 0.2.3 release worktree.
The switch saves opt-in and schedules an immediate upload. Upload attempts
are serialized and re-check opt-in before each batch. Missing prerequisites
and partial failures have structured outcomes; concurrent local edits stay
pending. Retry uploads do not depend on a successful download first.
App-start upload also runs independently of the later download.

Kilter settings show the last attempt time, counts and actionable error,
with retry/reconnect and a shortcut to the report form. Complete sync success
is no longer timestamped on upload failure. Logbook upload and authored-climb
publication remain distinct. English and German text were updated together.

Diagnostics retain at most 20 attempts and exclude entries older than 7 days
from reports. They contain build/version, operation, trigger, duration,
counts, fixed error categories and HTTP status. HTTP response bodies and
exception messages are excluded. The form freezes a preview on opening;
attachment is unchecked by default. Disconnect disables log uploads and
clears diagnostics after in-flight upload work exits. No report was sent.

The endpoint's HTTP-success contract and custom-wall fallback are unchanged;
these require the controlled live validation described above. This patch
fixes the confirmed client-side failures, not an unverified server contract.
No database migration is required; existing log UUIDs and optimistic row-version
guards are retained. No APK or release was published.

### Final verification

The final focused Gradle run passed 34 tests, with no failures, errors or skips:
13 upload outcome/retry/concurrency tests, 1 real HTTP-client test against
MockWebServer, 2 diagnostic persistence/retention tests, 11 existing backfill
tests, 6 developer-contact tests (including diagnostic attachment opt-in),
and 1 German Compose UI test for partial failure, retry and report actions.

Command:

```sh
ANDROID_HOME=/home/myuser/android-sdk ./gradlew :androidApp:testDebugUnitTest \
  --tests 'com.cruxcoach.android.data.kilter.KilterUpload*Test' \
  --tests 'com.cruxcoach.android.data.kilter.KilterSyncEngineBackfillTest' \
  --tests 'com.cruxcoach.android.ui.devcontact.DevContactViewModelReplyTest' \
  --tests 'com.cruxcoach.android.ui.settings.KilterUploadStatusUiTest' \
  --console=plain
```

English/German resource XML and duplicate resource names were checked;
`git diff --check` passed. No full suite, Android lint or APK build was run.
ADB still listed no devices on 2026-09-17, so real-device UI/DI validation
and the official Kilter app end-to-end check remain outstanding.

## Live root cause and wire fix (2026-09-17)

A feature APK at `844ff2e05` was published as development build 1000013 and
installed on the Android API 35 test device. With the owner's authorized test
account connected, a local send for **Floats Your Boat, 40 degrees** was created
while upload was disabled, then labelled `CruxCoach upload test 2026-09-17 - NOT
a real ascent`. Enabling upload immediately attempted transmission, but the API
returned HTTP 500. The new diagnostic card correctly showed 0 uploaded / 1
pending. An independent GET of the account's logbook did not show the new send.

The local catalogue stores this climb UUID as 32 hexadecimal characters without
hyphens. `uploadPendingLogs` copies that exact local key into `KilterLog.climbUuid`,
and `uploadLogs` previously serialized it unchanged. The REST endpoint requires
the hyphenated UUID spelling.

A controlled comparison used one additional diagnostic log UUID and identical
account, wall, gym, layout, angle, attempts and timestamp. Only `climbUuid` changed:

| Wire spelling | POST /api/logs/bulk | GET /api/logs by exact log UUID |
| --- | --- | --- |
| Compact 32-hex catalogue ID | HTTP 500, empty response body | Absent |
| Same UUID, lowercase 8-4-4-4-12 | HTTP 200 | Present |

The additional diagnostic record was deleted after the comparison (HTTP 200);
read-back confirmed it absent. The original phone-created pending test send was
preserved. Credentials were consumed by scripts, not displayed, and raw payloads,
tokens and authenticated responses were not retained as diagnostic artifacts.
The API did not return a useful error body, so attaching raw server errors to a
bug report would not have explained this failure.

Fix: convert compact climb UUIDs in a copy immediately before upload
serialization. Keep local catalogue keys, local log references and `logUuid`
unchanged. This also covers queued older sends and attempts on their next retry;
no data migration or new log UUID is necessary. Already-hyphenated IDs remain
unchanged. A wire-level regression test covers lower/uppercase compact IDs,
already-hyphenated IDs, unchanged log identity, and send/attempt properties.

Historical research reported some HTTP-500 responses despite successful writes.
That behavior did not occur in this controlled comparison. Do not blanket-treat
500 as success: the invalid-UUID request really did not produce an observable log.

The device screenshot also reproduced duplicate error cards: a persistent upload
status card followed by an identical generic result card. Upload-only actions now
use the persistent card exclusively; full sync's generic result only describes
the download, keeping the upload error and retry/report controls in one place.

A separate first-run issue was observed: concurrent catalogue imports delayed
Kilter's optional climb backfill and produced SQLITE_BUSY. This is distinct from
the HTTP-500 write failure and is not changed by the UUID fix.

Validation of this follow-up: 16 focused tests passed (2 HTTP serialization/error
handling, 13 upload-status/queue tests, 1 German status-card Compose test).
`git diff --check` passed. The wire-format fix and duplicate-message change are
currently source changes in this worktree; they have not yet been republished or
installed on the phone. The successful canonical-ID comparison above used a
direct controlled REST probe, not an APK containing the new fix.

## Official-app mismatch after build 1000015 (2026-09-17)

**The earlier HTTP/read-back success does not establish a correct official-app
upload.** The owner reports the test entry as no grade, one star, three ascents,
and `null @40`, despite the correct climb name/setter.

Read-only inspection reproduces the incorrect aggregate in `/climbs/logged`:
for the hyphenated, lowercase test climb ID at 40 degrees, `currentDifficultyId=1`,
`difficultyAverage=1`, `qualityAverage=1`, and `ascentCount=3`. `/logs` still
contains exactly one test log (nine account logs total). Three aggregate ascents
must not be described as three duplicate account log rows. Whether earlier test
writes/deletion left aggregate effects is unverified.

A retained official `/climbs/curated` response from 2026-05-06 contains the same
climb under its uppercase compact ID, with grade ID 16, quality 3.04 and 77,310
ascents at 40 degrees. The phone's current catalogue has difficulty 15.96,
quality 3.13 and 97,088 ascensionists. These are different snapshots, not values
expected to match exactly. Existing account logs predominantly retain compact
uppercase IDs and return plausible grade IDs. This is strong evidence that
UUID spelling is significant for statistics joins even where climb metadata
can be resolved across spellings. Treating the two spellings as interchangeable
at the upload boundary was not justified by the prior tests.

The uploaded log also uses product layout 10 inherited from account history,
whereas the climb metadata uses product layout 8. A climb's minimum layout may
differ from the wall it was climbed on; this discrepancy alone does not prove
an invalid wall, and must not be fixed by blindly copying climb layout IDs.

A single controlled repair attempt reused the existing test log UUID and all
other upload fields, changing only the climb ID to uppercase compact spelling
(and retaining the known test comment). `/logs/bulk` returned HTTP 500; read-back
confirmed the original test log remained unchanged. Because this was an existing
log, it does not distinguish ID rejection from endpoint update/conflict behavior.
No new diagnostic log was created in this follow-up. No credentials or raw
account responses were saved.

Next contract validation: compare a same-climb, same-angle log created through
the official app, establish its write endpoint and exact identity representation,
and verify update/idempotency behavior separately. Do not guess rating fields,
replace missing ratings with catalogue averages, or retry alternate IDs on real
user logs. A fix must preserve the upstream climb/statistics identity and cover
already-marked-synced affected logs without duplicating ascents. Acceptance needs
correct official-app grade/name/angle/statistics, not merely HTTP 200 and log
presence. `null @40` has not yet been traced to its exact official-app field.

For diagnostics, distinguish transport acceptance from verified association;
record an allowlisted mismatch reason and aggregate counts, not raw account
payloads. Do not reject genuinely ungraded climbs merely because grade is null.
The owner has been asked for a reference log from the official app. No new code
fix, push, or publication is claimed by this follow-up.

### Official reference and isolated comparisons (2026-09-17, follow-up)

The owner created the requested official-app reference at 15:06 UTC. It uses the
uppercase compact catalogue ID at 40 degrees, layout 8, grade ID 16 and the
existing aggregate (97,258 ascents, difficulty 15.96, quality 3.13). The older
CruxCoach log uses a lowercase hyphenated ID and the separate incorrect aggregate.

Controlled, fresh diagnostic log UUIDs established the following. Each successful
temporary log was deleted and verified absent; the owner's official reference and
original CruxCoach test entry remain. No production-user logs were modified.

| Endpoint | Climb ID | Wall context | HTTP / read-back |
| --- | --- | --- | --- |
| `/logs/` | uppercase compact | official reference | 200, exact ID, grade 16 |
| `/logs/bulk` | uppercase compact | official reference | 200, exact ID, grade 16 |
| `/logs/bulk` | lowercase compact | official reference | 500, exact log absent |
| `/logs/bulk` | uppercase compact | original CruxCoach test | 200, exact ID, grade 16 |

This isolates **case-sensitive legacy IDs**, not layout 10 or the bulk endpoint,
as the upload failure. Hyphenating the ID avoided rejection but selected a new
statistics identity. The fix now uppercases compact 32-hex IDs on the wire while
preserving their compact form. Native hyphenated IDs, local catalogue keys, and
log UUIDs remain unchanged. The HTTP regression test explicitly checks both
compact input cases and an untouched native hyphenated ID.

The unsuccessful earlier repair reused an existing log UUID. Fresh uppercase
inserts succeed, so that failure is not evidence against the correct ID spelling.
Update/conflict semantics and repair of already-synced hyphenated legacy logs
remain separate work: do not silently delete/recreate user logs, and do not claim
that retrying the upload switch repairs already-marked-synced records. No change
of endpoint, invented grade/quality upload, or forced layout reassignment is needed
for a new correctly associated send. HTTP acceptance alone remains insufficient
as an end-to-end acceptance test.

Focused verification after the correction: `KilterUploadHttpTest` passed (2 tests,
0 failures/errors) via `:androidApp:testDebugUnitTest --tests
'com.cruxcoach.android.data.kilter.KilterUploadHttpTest'`. `git diff --check`
passed. Live probes above exercised the same corrected wire spelling directly;
the installed APK still contains the old spelling until republished and updated.
