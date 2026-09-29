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
- `KilterSyncEngine.uploadPendingLogs` reads Kilter's logbook once per run,
  settles known and twin entries locally, splits refused requests down to the
  single row, retries it once in the other case, and holds it back
  (`KilterUploadLedger`) only with proof — an accepted request in the same run,
  or a second lone failure at least an hour later. Transient statuses stop the
  run; requests per run are bounded.
- Aurora-imported entries (`external_id` `aurora-json:*`) stay local unless the
  user opts in; opted-in entries are matched to Kilter's logs by climb, angle
  and day first.
