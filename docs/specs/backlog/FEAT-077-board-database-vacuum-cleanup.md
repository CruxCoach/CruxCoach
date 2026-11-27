---
status: backlog
queue: parked
target: own board PR after 0.2.5 (owner 2026-10-25, F6 (a): not in 0.2.5)
base: feat/0.2.5-training-nutrition
depends_on: []
created: 2026-10-25
---

# Feature Spec: Board database – remove the dead VACUUM, reclaim space only where it cannot block

> **Status:** Backlog, its own board PR. Found in the tn-025 review
> (2026-10-25); the owner decided not to touch it in 0.2.5 (F6 (a)).

## 1. Problem

`DatabaseFactory.android.kt` calls `vacuumIfNeeded(db)` in the board
database's `onOpen`. When more than 1000 pages are free it runs

```kotlin
db.query("PRAGMA auto_vacuum = INCREMENTAL").close()
db.query("VACUUM").close()
```

and logs "VACUUM completed". The cursor is never moved, so SQLite never
executes either statement: **the VACUUM has run in no release**, and the log
line is wrong. Nothing is broken today – the database just keeps its free
pages.

Making it "work" in place would be worse than leaving it: a VACUUM in `onOpen`
on the only board connection would block the browser for several to over ten
minutes on a ~400 MB database on Nokia-class phones (estimate), need up to the
database size in native memory (`temp_store=MEMORY`), and let parallel imports
fail with `SQLITE_BUSY` – after almost every import, because imports free more
than 1000 pages.

## 2. Proposal

1. Remove `vacuumIfNeeded` and its false log line (pure cleanup, no
   behaviour change).
2. If space reclamation is wanted at all, only where it cannot block the UI:
   - right after a full board import, on the importer's own connection, while
     the import progress is still shown; or
   - as a WorkManager job when the phone is idle and charging.
   Either one measured on the Nokia before it ships.
3. `auto_vacuum = INCREMENTAL` only takes effect after a VACUUM; if (2) is
   built, decide between a one-time VACUUM with incremental vacuum afterwards
   and no reclamation at all.

## 3. Acceptance

- (1): unit/robolectric suite green; board open time unchanged; no
  "VACUUM completed" in the log.
- (2): device test with a full board database: browser usable during the job,
  imports not failing, file size before/after recorded.
