# Kilter log upload: HTTP 500 on every attempt (0.2.3)

Reported 2026-09-29 by a 0.2.3 (vc 9) user: every upload attempt ended with
`attempted=200 uploaded=0 pending=1710 reason=HTTP http=500`. The same 200
rows were sent on every trigger; the remaining 1510 were never tried.

## Live contract (owner's Kilter test account, 2026-09-29)

Each probe used a fresh log uuid and a diagnostic comment, and was deleted
afterwards; the account was verified back in its initial state. No user
account was touched.

| Probe | HTTP | Written |
| --- | --- | --- |
| Reference climb, canonical uppercase compact id | 200 | yes |
| Climb whose canonical id is lowercase compact, exact | 200 | yes, name and grade resolved |
| Same climb uppercased (0.2.3 wire form) | **500** | no |
| One request: valid row + the uppercased row | **500** | **neither** — bulk is atomic |
| Unknown climb, random compact uppercase id | 500 | no |
| Unknown climb, random dashed id | 500 | no |
| Timestamp `YYYY-MM-DD HH:MM:SSZ` | 400 | no |
| Same log uuid again after `DELETE` | 200 | yes — no soft-delete block |
| 200 valid rows in one request | 200 | 200/200 |

## Upstream id spellings

An archived `/api/climbs/all/<productLayoutUuid>` snapshot (2026-07-26, all
layouts CruxCoach supports, 445 445 climbs) holds 239 809 compact uppercase,
109 811 compact lowercase and 95 458 dashed ids. No climb appears in two
spellings. Since the 2026-03-26 migration Kilter creates only dashed ids (plus
4 compact lowercase ones, CruxCoach's own), so the compact set is closed.

## Cause

`KilterApiClient.uploadLogs` uppercased every 32-hex climb id — right for the
uppercase majority, wrong for the lowercase quarter. The catalogue on the
device stores every uuid lowercased, so the spelling is not recoverable
locally. With an atomic bulk endpoint, an unordered queue that always starts
with the same rows and a run that stops at the first failed chunk, a single
such row blocked the whole logbook.

## Fix (0.2.4)

- `KilterClimbWireIds` resolves the spelling at the upload boundary: the
  account's own Kilter logs, CruxCoach-authored climbs (always lowercase), the
  bundled `assets/kilter/lowercase_climb_ids.bin` index
  (`scripts/kilter/build_lowercase_climb_index.py`), otherwise uppercase.
  Dashed spellings of legacy climbs are compacted.
- `KilterSyncEngine.uploadPendingLogs` reads Kilter's logbook once per run
  (logs pending deletion excluded; re-read if another upload ran since a
  caller's snapshot), settles known and twin entries locally, sends every full
  chunk, then splits refused requests down to the single row, retries it once
  in the other case, and holds it back (`KilterUploadLedger`, tied to the
  row's content and app build, 7 days) only with proof: Kilter accepted some
  upload after the row's first lone failure. Kilter answers its own failures
  with 500 too, so an outage never parks rows. Conflicts and unreadable
  timestamps cost no request and are recomputed each run. Transient statuses
  stop the run; requests per run are bounded (40).
- Aurora-imported entries (`external_id` `aurora-json:*`) stay local unless the
  user opts in; the opt-in covers the entries present and is withdrawn once a
  run worked through them. Opted-in entries are matched to Kilter's logs by
  exact second first, then to the nearest log of the same climb, angle and send
  state at most one UTC day away (greedy by time difference; logs this device
  uploaded for imported entries do not count, review 2026-10-07).

## Follow-up (2026-10-03): climbs Kilter keeps under another id, proof

Device test with the 0.2.4 feature build (vc 1000013) on the owner's test
account; every probe log was deleted again and the account verified back in
its earlier state.

- Two in-app logs were held back as "unknown to Kilter" although Kilter has the
  climb: CruxCoach's catalogue carries 496 compact ids that `/climbs/all` does
  not list. Matched by identical holds (placement → hole, roles 42–45 → 12–15;
  validated on 8 000 climbs present on both sides: 7 991 identical) and name,
  455 of them exist on Kilter under another id, mostly a dashed one from the
  March migration. A bulk request naming all 455 new ids was accepted
  (3 × HTTP 200); of 80 sampled old ids, 77 were refused in both cases and 3 are
  still accepted in uppercase. 35 + 6 have no counterpart; samples of those and
  three Boardsesh-origin climbs were refused in both cases.
- `/climbs/all` is not complete: two old dashed ids it omits are accepted.
- `DELETE /logs/{uuid}` answers 204 for a uuid Kilter does not hold.

Consequences in the upload:

- `assets/kilter/climb_aliases.tsv` (scripts/kilter/build_climb_alias_index.py)
  maps the 455 old ids to the id Kilter lists. A row names that id first and
  falls back to its own id in both cases; the id that worked is remembered.
- A lone refusal holds a row back only with proof: a request accepted after it
  in the same run, a proven refusal of the same climb, or three refusals on a
  1 h / 4 h schedule. The former rule (any acceptance in the run, or any later
  acceptance) parked rows Kilter would take when an outage began mid-run.
- Unproven refusals are retried first in the next run that sends anything,
  otherwise after a growing pause; rows of a climb refused with proof go one by
  one, one request each.
- Imported entries are matched to Kilter's logs nearest in time, so two sends of
  one climb on consecutive late evenings no longer leave one of them unmatched.
- A busy catalogue stops the run instead of sending a dashed legacy id.

## Device rounds (2026-10-03/04, feature builds 1000014–1000016)

Nokia 6.1 (Android 15), owner's test account; every check read back through
`GET /logs`, the account restored afterwards.

- Update in place 1000013 → 1000014: the two entries 1000013 held back went up
  under the ids Kilter lists ("A Bigger Squeeze" → 0D9A1ED7…, "Babylone" →
  c4ec8274-…) in one request on app start.
- Aurora import of 341 synthetic entries (all spellings, 20 moved climbs, 5
  climbs without counterpart, 3 Boardsesh climbs, 3 twins of account logs):
  kept local without opt-in (0 requests); with opt-in one run of 36 requests
  uploaded 330, settled the 3 twins, held back the 8 unknown ones with proof.
  Every log on Kilter carries the stored spelling; the moved climbs carry the
  new id; no ascent is on Kilter twice.
- Kilter acknowledges attempt-only logs (`topped=false`) with HTTP 200 and
  keeps them (re-sending the uuid is refused with 500), but 27 of 39 did not
  appear in `GET /logs` within 45 minutes; for one climb they appeared after a
  send on it was logged. Ascents always appeared at once. This is Kilter's
  listing, not the upload: the entries are on Kilter.
- Offline: the run stops before Kilter's logbook is read (reason NETWORK, no
  request, nothing held); online again, the sync uploads the entry.
- A deletion reaches Kilter with the next sync and is not downloaded again; an
  edit of an uploaded entry is kept local as a conflict without a request and
  listed with its reason; a cold start with held rows costs no request; with
  upload off nothing is sent, turning it on uploads what was logged meanwhile.
- 1000015 showed that entries refused before an update fell back to "imports
  waiting for consent"; since 1000016 they stay queued and proven across
  updates and are retried once, one by one with every id.

## Whole-catalogue check (2026-10-04)

- Kilter's `/climbs/all` fetched fresh for every layout (473 976 climbs, July:
  445 445): no climb changed its spelling; 1 924 are gone, 30 455 new (nearly
  all dashed); layouts 34–36 are empty. The alias table rebuilt from it is
  byte-identical to the bundled one.
- The real `KilterClimbWireIds.candidates()` (bundled index and aliases) for
  all 239 866 Kilter climbs of the device catalogue: for 239 818 the first id
  is exactly the id Kilter stores today (455 of them the id a moved climb has
  now), no mismatch; 48 are not in today's catalogue.
- Live on the test account: a stratified sample of 2 305 of the 239 818
  (lowercase, uppercase, dashed, dashed newer than July, layouts 1 and 8, all
  455 moved climbs) went up in 12 bulk requests with the first id, none
  refused, every log stored under exactly that id. Of the 48, Kilter takes 8
  with the first id (5 dashed, among them a draft; 3 compact uppercase) and
  refuses 40 in both cases, as it refuses all 19 Boardsesh-origin climbs:
  those are the climbs the upload lists as not on Kilter. All probe logs were
  deleted again and the account verified back in its initial state.

## Field report on build 1000019 (2026-10-05): a slow Kilter, lost answers

- The reporter's first run sent three 200-row requests: two went through, the
  third failed without HTTP status after under 16 s in all (`reason=NETWORK`);
  the manual retry failed after 30.5 s, the client's read timeout. Kilter was
  slow, not unreachable; an hour later the upload went through.
- Probe on the test account: Kilter answers a log uuid it holds and an unknown
  climb with the same empty HTTP 500, and `GET /logs/{uuid}` returns `[]` for
  any uuid. A refusal alone therefore cannot tell "already written" from "climb
  unknown"; a lost answer of a request carrying attempts (which `GET /logs`
  often leaves out) turned into "Kilter does not know this climb" on resend.
- Changes: requests of 100 rows; 90 s read and 120 s call timeout for the
  upload's calls (bulk, logbook read); `TIMEOUT` reported apart from `NETWORK`.
  Every request is saved as doubtful before it goes out and dropped once Kilter
  answered. The next run settles it: one of its rows in Kilter's logbook proves
  the whole request written (bulk is atomic), so its unlisted attempts are
  marked synced; past 5 minutes, an unlisted ascent of it proves it was not.
  A request of attempts only is sent again: taken, it was not written; refused,
  rows of up to two climbs go alone under every id, and a refusal of a climb
  Kilter has logs of, or of two climbs, means Kilter holds it ("probably on
  Kilter", held, listed). Follow-up runs after 2, 5, 15, 30, 60, 120 minutes on
  network, timeout, HTTP and internal stops, a used-up request or 5-minute time
  budget and rows waiting for Kilter; a finished run cancels them.

Device round on build 1000020 (Nokia 6.1, test account, 2026-10-06): leaving
the settings 4 s after confirming the imported upload, the run went on and
finished (330 up, 3 twins, 8 proven unknown, 36 requests, 19 s); an ascent
logged offline went up by itself 33 s after the network returned (WorkManager
`kilter-upload-retry`, 120 s delay, network constraint; `trigger=RETRY`).
Cutting Wi-Fi and data 2.5 s after confirming broke the first 100-row request,
but the run reported `UnknownHostException`: OkHttp had silently retried the
POST on a new connection (`retryOnConnectionFailure`), so no doubtful request
was noted. The bulk upload now uses a client without that retry.

## Review of the release candidate (2026-10-07)

Three independent reviews of the branch diff. Confirmed and fixed:

- Twin pool: a copy this device uploaded for one imported entry could be taken
  as Kilter's copy of another imported entry of the same climb a day away, so
  the second was marked synced without ever reaching Kilter (when a run
  stopped between them). Kilter's copies of imported entries and of the run's
  own rows are no longer twins.
- Quick log: an attempt is uploaded after the undo window; further tries
  edited the row without making it an upload, and "Top" turned it into a send
  under the same uuid, which Kilter refuses as known. Edits (count, comment) of
  an entry on Kilter and promotions/undos now queue the deletion of Kilter's
  copy and make the row an upload again; the upload pushes deletions first and
  holds such a row until its copy is gone. Rows of earlier builds: a lone row
  refused under every id is checked with `DELETE /logs/{uuid}` (200 = Kilter
  held it, now gone, the row goes once more; 204 = not held, the refusal is
  about the climb); a listed attempt under the uuid of a local send of the same
  climb and angle is replaced instead of kept as a conflict.
- Lost answers of attempt-only requests are decided by deleting one row of the
  request (verified live: 200 when held, 204 when not, the uuid is accepted
  again afterwards) instead of probing with re-sends; the "probably on Kilter"
  state is gone.
- Offline with an expired access token was reported as AUTHENTICATION (sign in
  again) and cancelled the retries; the token refresh now reports an
  unreachable endpoint as a network failure. The settings "Sync now" and the
  retry worker run detached from their caller. Non-retry triggers keep a
  running retry chain (`KEEP`) instead of restarting its back-off; rows held
  for a pause get a wake-up run at their retry time. The imported opt-in stays
  until no row it covers is open. Rows are marked synced before their doubtful
  request is dropped; a clock set back no longer ages ledger entries out; the
  list no longer waits for a running upload, and a failed load says so.
- Alias table: rebuilding it from the current full catalogue (also climbs with
  `is_listed=0`) gives the bundled 455 pairs unchanged plus 338 for climbs the
  catalogue no longer lists. Live on the test account all 338 target ids were
  accepted and stored exactly as sent (4 requests, probe logs deleted again);
  the table now has 793 pairs.

