---
status: skeleton
queue: parked
base: post-0.2.3
depends_on: []
created: 2026-09-26
---

# Feature Spec: Database process performance (0.2.4)

> **Status:** Skeleton — TODO for 0.2.4, captured 2026-09-26 after a nearby
> share on a Nokia 6.1 never finished. Scope agreed with the owner: optimize
> the database processes in general, not only the paths that were fixed for
> 0.2.3. Design open; measure before changing anything.

## 1. Why

Every catalogue path moves 500–700 MB of SQLite on phones with slow eMMC and
little RAM. Two statements in the nearby-share receive path turned a
six-minute import into one that did not finish within an hour. They were
found only by profiling on the device. Nothing in CI would have caught them.
Other paths have the same structure and have never been measured this way.

## 2. What 0.2.3 already fixed (feat/0.2.3-release)

Reference device: Nokia 6.1, Android 15, SQLite 3.44.5. Reference data: a
real v2 share snapshot of 635 MB with 239,893 Kilter, 289,231 MoonBoard and
9,147 official Quantum climbs.

| Commit | Change | Measured |
|--------|--------|----------|
| `d61a2204d` | Quantum bridge copy: correlated `LOWER(TRIM(uuid))` join → non-correlated `IN (SELECT …)` | refs insert: 46 min without finishing → under 1 s |
| `d61a2204d` | Snapshot pruner: 64 MiB cache, `synchronous=OFF`, `auto_vacuum` FULL→INCREMENTAL, drop secondary indexes of cut tables | 7.4 min in app → ~1 min |
| `d4cb8a3d7` | Detached `ANALYZE` after a share, serialized with imports | share receivers previously had no `sqlite_stat1` |
| `480ea89a6` | Foreground keep-alive for the whole local receive | screen-off share: slept 11 min 51 s of 16 min → `asleep=0ms` |

Fresh receiver, Kilter + MoonBoard + Quantum, after the fixes: 6.5 min
(import phase 290 s with the screen on, 426 s with it off).
Online Blossom sync of the same boards on the same device, for comparison:
Kilter ~5 min, MoonBoard ~8 min, Quantum ~1.5 min, ANALYZE 34 s.

## 3. TODO for 0.2.4

1. **Measure every path first.** Add the per-phase timing logs that the share
   receive now has (`Local share timings: … asleep=…`) to the Blossom Kilter
   chunks, MoonBoard, Aurora-family and Quantum snapshot imports, the sender
   snapshot build, schema migrations and `backfillMoveCounts`. Record a
   baseline on the Nokia 6.1 before optimizing.
2. **Sender snapshot build** (`LocalApkServer.buildBoardDbSnapshot`,
   `scrubAndCompactBoardDbSnapshot`). It is unmeasured and the prime suspect
   for the 10–15 min the receiver showed "wird vorbereitet". It copies
   the live DB, then runs `UPDATE climbs SET …` over every row, touching
   indexed columns (`frames_hash`, `kilter_status`, `nostr_publish_via`),
   with a 2 MiB cache. It then runs `VACUUM`, which keeps `auto_vacuum=FULL`,
   and gzips single-threaded. Candidates:
   - drop the secondary indexes of the throwaway copy first; the receiver
     never reads them;
   - update only the rows that carry the columns;
   - `auto_vacuum=NONE` before `VACUUM`;
   - consider not sending what every 0.2.3+ receiver discards anyway (native
     `nostr`/`local` climbs, ~174k rows, ~25 % of the transfer). This is a
     protocol decision; 0.2.2 receivers still import those rows.
3. **Receiver lock time.** The atomic modern import holds one `EXCLUSIVE`
   transaction for 4–7 min. UI reads wait on it ("unable to grant a
   connection" warnings). Keep the all-or-nothing guarantee, but evaluate
   importing into a staging file and swapping, or WAL for the board DB.
4. **Stats import throughput.** It takes ~2 s per 10k-row batch in the app
   (peer join + correlated `layout_id` subquery per row), and the same code
   serves the online Kilter chunks. Profile with simpleperf; test larger
   batches and a join instead of the scalar subquery.
5. **SQL pattern audit + guard.** Find every lookup where the searched side
   is wrapped in a function (`LOWER`, `TRIM`, `COALESCE`) inside a join or
   correlated subquery. The Moon aliases were fixed the same way earlier;
   the Quantum bridge was missed. Add a test that runs `EXPLAIN QUERY PLAN`
   over the import statements and fails on `AUTOMATIC … INDEX` or on a
   `SCAN` inside a correlated subquery.
6. **One tuning helper for bulk connections.** The importer sets 64 MiB
   cache, `synchronous=NORMAL` and `temp_store=MEMORY` in `openTargetDb`; the
   pruner and the sender scrub each set their own subset, or none. Make one
   helper for throwaway and bulk connections.
7. **Live board DB `auto_vacuum=FULL`.** Every large delete (catalogue
   refresh, board deletion) relocates pages at COMMIT. Evaluate INCREMENTAL
   plus a maintenance `incremental_vacuum`.
8. **Background thread priority.** Imports run with
   `THREAD_PRIORITY_BACKGROUND` and appear to land on the little cores
   (screen-off import 426 s vs 290 s). Measure whether a user-visible
   foreground import should run at default priority.

## 4. Acceptance (draft)

- Every catalogue import and export path logs per-phase durations.
- On the Nokia 6.1, a nearby share (sender snapshot + transfer + receive) of
  the reference catalogue is not slower than the online download of the same
  boards.
- The query-plan guard from item 5 runs in CI.
- No regression in the local-share trust tests (`LocalShareModernSchemaTest`,
  `LocalShareSnapshotPrunerTest`, `LocalSharePeerColumnContractTest`).

## 5. Related, not database

During a share, the board rows show "wird vorbereitet" for the whole
transfer, including download, prune and import. Only the summary card
names the real step, which made the stall hard to locate.

## 6. How to measure without a second phone

A complete receive can be replayed on a device by writing the
`local_share_resume_v1` pending record plus the snapshot `.gz` into the app's
cache, the same hand-off the app uses after an APK update. Running SQL is
visible with `adb shell dumpsys dbinfo <package>`; CPU profiles come from
`simpleperf record --app <package>`, which works on the debuggable
feature builds.
