---
status: backlog
---
# Feature Spec: Skip wasted index builds in 2–5.sqm board migrations (backlog)

> **Status:** Backlog — captured 2026-05-18. No release target.
> Residual ~40s of the 0.1.3→0.1.4 first-launch-after-upgrade block,
> remaining AFTER the two fixes that shipped in 0.1.4
> (6.sqm copy-skip + onOpen VACUUM guard, which took it 146s → ~40s).
> Not a release blocker — the ~40s is one-time, behind an informative
> "Climb-Datenbank wird vorbereitet…" message, and the migration is
> atomic (force-kill safe, re-runs). This spec is a placeholder for an
> optional later cleanup, NOT urgent.

---

## 1. Problem statement

On 0.1.3→0.1.4, board schema migrations 2.sqm..5.sqm each run
`CREATE INDEX` on the climbs (~190k rows) and climb_stats (~326k rows)
tables while they are still fully populated from the fresh-0.1.3 board
download:

- 2.sqm: ALTER…RENAME (fast) + ~8 `CREATE INDEX` on climbs+climb_stats
  (+ index recreations on placements/board_images/beta_links — those
  tables are NOT dropped later, keep them).
- 3.sqm: 3 `CREATE INDEX` on climbs.
- 4.sqm: 2 `CREATE INDEX` on climbs.
- 5.sqm: 1 `CREATE INDEX` on climbs.

6.sqm then `DROP TABLE climbs` / `DROP TABLE climb_stats` and rebuilds
them; 7.sqm drops + rebuilds them again (BINARY collation, empty
climb_stats) and sets the post_v8_force_resync marker so the whole
catalog is re-downloaded from Blossom. So every climbs/climb_stats
index built in 2–5.sqm is discarded a few migrations later — ~14
indexes built over 190k+326k rows on slow eMMC (Nokia 6.1) for
nothing. Measured residual ≈ 40s.

Note: 0.1.3 shipped at board schema v2 (only 1.sqm existed at the
`v0.1.3` tag — verified via `git ls-tree v0.1.3`), so 2.sqm..11.sqm
ALL run on the 0.1.3→0.1.4 upgrade. These migrations were never on a
production device before 0.1.4, so they are editable — but see
"Risk" below.

## 2. Options

| Option | Effort | Risk | Effect |
|---|---|---|---|
| A. Remove only the climbs/climb_stats `CREATE INDEX` from 2/3/4/5.sqm (keep placements/board_images/beta_links ones). 6/7.sqm already build the final index set on the rebuilt tables. | 4 files, careful per-index "is the target table dropped by 6.sqm?" review | medium — 4 migration files, must keep cumulative schema == .sq (verifyCommonMainBoardDatabaseMigration) and not break any intermediate query (none observe between migrations, run back-to-back) | residual ~40s → likely ~10s |
| B. Have 2.sqm delete the kilter catalog right after the RENAME so 3/4/5.sqm index a near-empty table | conceptually invasive (force-resync infra only exists from 7.sqm; moves the data-wipe far earlier) | higher | similar to A |
| C. Do nothing — accept ~40s | none | none | one-time, message-mitigated, atomic |

## 3. Recommendation

Default to **C (do nothing)** unless field reports show the ~40s is a
real upgrade-abandonment problem. The big safe wins already shipped
(146s → ~40s). If pursued later, **Option A**, with:
- Per-index audit: only drop `CREATE INDEX` whose table is later
  `DROP TABLE`'d by 6.sqm (climbs, climb_stats). Keep placements /
  board_images / beta_links / sync_states index work.
- Must keep `:shared:verifyCommonMainBoardDatabaseMigration` green
  (cumulative schema unchanged) + `:shared:testDebugUnitTest` (migration
  round-trip).
- Verify on-device via the full 0.1.3→0.1.4 redo on the Nokia 6.1
  (the ~40s path); expect drop toward single-digit seconds.

## 4. Out of scope

- The 6.sqm copy-skip and the VACUUM guard (already shipped in 0.1.4).
- Any change to released migrations on a real production schema
  version (none here — 0.1.3 was schema v2).

See [[reference_release_apk_archive]] for where the 0.1.3 / 0.1.4-rc
APKs used to measure this live, and [[reference_dev_adb_setup]] for the
tunneled-phone redo procedure.
