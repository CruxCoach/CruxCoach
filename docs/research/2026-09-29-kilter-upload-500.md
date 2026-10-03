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
  exact second first, then climb, angle and day, then a day either side.

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
