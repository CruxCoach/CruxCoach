---
status: backlog
queue: needs-clarification
base: auto
depends_on: []
created: 2026-08-05
---

# Feature Spec: Survivable Database Downgrade (backlog)

> **Status:** Backlog — captured 2026-08-05 from a crash report. No release
> target. Deliberately *not* scoped into 0.2.2: the handler being specified
> here always runs in the **older** app, so nothing built here can help a
> device that is already on 0.2.1, and the first downgrade it can actually
> catch is the one *after* the release that ships it.
>
> **Depends on:** none
> **Relates to:**
> - FEAT-003 (Climb Creator + Community Climbs) — puts unpublished local
>   drafts into the board DB, which is what makes "the board DB is a
>   disposable cache" false (see §3.2).
> - FEAT-004 (In-App Auto-Update) — `VersionChecker.pickNewerStable` is the
>   reason ordinary users cannot reach this state (see §1.3).
> - FEAT-026 (Skip wasted index builds) — same board-migration surface.

---

## 1. Overview

### 1.1 The crash

A crash report from 2026-08-04 (app 0.2.1, versionCode 7, API 35, low-end
device):

```
java.lang.RuntimeException: Unable to create application
  com.cruxcoach.android.CruxCoachApp:
  android.database.sqlite.SQLiteException:
  Can't downgrade database from version 11 to 10
    at androidx.sqlite.db.SupportSQLiteOpenHelper$Callback.onDowngrade
    at net.zetetic.database.sqlcipher.SupportHelper$1.onDowngrade
    …
    at com.cruxcoach.android.di.AppModule.provideSecureDatabase
    at com.cruxcoach.android.CruxCoachApp.onCreate
```

The device's encrypted personal DB stood at schema 11 — a 0.2.2 build had
already migrated it — and 0.2.1 code expects 10. The failure lands in
`Application.onCreate` during Hilt graph construction, i.e. **before the
first frame**. Every subsequent launch repeats it: an unrecoverable crash
loop, with the only user-reachable escape being "clear app data", which
destroys the logbook.

### 1.2 Root cause

Neither driver factory in
`shared/src/androidMain/kotlin/com/cruxcoach/data/DatabaseFactory.android.kt`
overrides `onDowngrade`:

- `SecureDriverFactory.createDriver` passes only the SQLCipher
  `SupportOpenHelperFactory` — no callback at all, so the framework default
  applies.
- `BoardDriverFactory.createDriver` does pass an
  `AndroidSqliteDriver.Callback` (PRAGMAs, hot-path index self-heal,
  conditional VACUUM) but does not override `onDowngrade` either.

`SupportSQLiteOpenHelper.Callback.onDowngrade` throws unconditionally. A
downgrade is therefore not "refused with a message" — it is a hard crash at
process start.

Schema versions across the two release lines:

| DB | v0.2.1 (tag) | 0.2.2 | Migrations added |
|----|--------------|-------|------------------|
| secure (`cruxcoach_secure_<pubkey>.db`) | 10 (`1..9.sqm`) | **11** | `secure/10.sqm` (playlist data model, commit `9d187f37`) |
| board (`cruxcoach.db`) | 24 (`1..23.sqm`) | **26** | `board/24.sqm`, `board/25.sqm` |

### 1.3 Why now, and how bad

Ordinary users cannot reach this state. The in-app updater accepts only
strictly greater SemVer (`VersionChecker.pickNewerStable`, and see the
downgrade-attack note in `updater/SemVer.kt`), and both Android and Zapstore
refuse installing a lower `versionCode` over an existing one. Reaching it
takes a deliberate data-preserving sideload (`adb install -d`).

That is exactly what the project's own QA does:
`cruxcoach-0.2.2-pre-release-geraetetest.md` step 2a — *"Update-Erkennung
nach Downgrade"* — installs an older build to exercise update detection.
The reported crash is that procedure meeting a migration for the first time.

So: a self-inflicted wound today, a real one the moment any user rolls back
a release they dislike. The cost of leaving it is that every future release
inherits the same trap.

**Goals**

- A downgrade never crashes in `Application.onCreate`. Either the older app
  runs correctly on the newer DB, or it fails in a way the user can act on.
- The secure DB is never deleted, under any code path in this feature. It
  holds the only local copy of the logbook.
- The board DB's non-catalogue rows (local drafts, publish queue) survive
  whatever the board-DB path decides to do.
- The conditions that make tolerance safe are enforced by tests, not by
  reviewer memory.

**Non-goals**

- Helping any already-released build, 0.2.1 included. Impossible by
  construction (§1.4).
- Supporting downgrades across arbitrary distance (e.g. 0.3.x → 0.1.x).
  A bounded, declared compatibility window is enough.
- Any change to `versionName`/`versionCode` or to the updater's refusal to
  install older releases. Downgrade stays a manual, deliberate act.
- Migrating data backwards (writing reverse migrations). Explicitly out.

### 1.4 The constraint that shapes everything

`onDowngrade` is invoked by the DB helper of the app that is *currently
starting* — the older one. The newer app is already gone from the device.
Therefore:

- Nothing in this spec repairs a device that is already on 0.2.1.
- Shipping this in release *R* protects downgrades from *R+1* back to *R*,
  and later.

The correct fix for a device stuck in the 0.2.1 crash loop is operational,
not code: reinstall the newer build (`versionCode` 8 ≥ 7, so a plain
`adb install -r` works, no `-d` needed). The DB is already at 11, matches,
and all data is intact. This belongs in the QA runbook, not in the app.

---

## 2. Today's behaviour

- Secure DB: default `onDowngrade` → `SQLiteException` → crash loop.
- Board DB: same, one migration later in the sequence (the secure DB is
  constructed first in the Hilt graph, so the secure crash is what users
  actually see).
- No app-level catch: `CruxCoachApp.onCreate` injects `UserPreferences` and
  friends directly, so a DI failure is a process failure. `CruxCoachCrashHandler`
  records the report but cannot keep the app alive.

---

## 3. Solution design

The two databases need **opposite** treatment. This asymmetry is the core
of the spec.

### 3.1 Secure DB — tolerate a newer schema (no-op downgrade)

Tolerance is safe here, and it was verified against the actual 11→10 case
rather than assumed:

- Every `INSERT` in `ClimbLists.sq` names its columns explicitly
  (`ClimbLists.sq:32`, `:40`, `:80`, `:134`), so columns added by a newer
  migration do not shift binding positions or trip
  "table has N columns but M values were supplied".
- `ALTER TABLE … ADD COLUMN` always appends, so positional cursor reads in
  older generated code keep their indices.
- The new columns from `secure/10.sqm` are `NOT NULL DEFAULT` or nullable,
  so inserts from older code that omit them still succeed.
- A table the older code has never heard of (`list_playback_steps`) is
  inert: unreferenced, and its rows are simply not maintained while the
  older build runs.

Design:

1. Give `SecureDriverFactory.createDriver` an `AndroidSqliteDriver.Callback`
   (the `AndroidSqliteDriver` constructor takes `factory` *and* `callback`,
   so this composes with the SQLCipher `SupportOpenHelperFactory`).
2. Override `onDowngrade(db, oldVersion, newVersion)` to consult a pure
   decision function (§3.3) and, on `TOLERATE`, return without touching
   anything. `user_version` deliberately stays at the higher value, so a
   later re-upgrade correctly runs no migrations.
3. On `REFUSE`, surface it as a typed exception (`SecureDbTooNewException`)
   carrying both versions, for the app-start path to render (Q2).

**Never** delete or recreate this DB.

### 3.2 Board DB — tolerance is unsafe, and so is a blind wipe

Two findings, both verified against v0.2.1 and the 0.2.2 tree:

**(a) It cannot be tolerated.** v0.2.1 queries the browse view with
`SELECT * FROM climb_browse` in 20 places (`Board.sq:537`–`:654`), and
SQLDelight maps `SELECT *` results **positionally**. The newer view is not
an append-only extension of the older one:

| | v0.2.1 view | 0.2.2 view (after `25.sqm`) |
|---|---|---|
| idx 8 | `frames_pace` | **`method`** |
| … | shifted by 1 | |
| after `kilter_status` | `created_at` | **`nostr_event_id`**, then `created_at` |

`25.sqm` inserts `method` *between* `is_nomatch` and `frames_pace`; `23.sqm`
adds `nostr_event_id` before `created_at`. Older code would read a TEXT
method where it expects an INTEGER pace, and everything past `kilter_status`
lands two positions off. That is not a crash — it is wrong grades, wrong
flags, silently. Strictly worse than the honest exception we have today.

**(b) It cannot be blindly wiped either.** "Board DB = disposable
catalogue cache" is false. It also holds rows that exist nowhere else:

- unpublished local climbs — `source='local' AND sync_status='draft'`, put
  there by `insertLocalDraft` (`Board.sq:1496`, drafts query at `:2045`);
- `kilter_publish_attempts` — in-flight publish queue state;
- `beta_links`, `community_climb_dead_letters`.

A `File.delete()` on downgrade would destroy user-authored climbs that were
never published anywhere. Unacceptable.

So the board path needs a preserve-then-rebuild, or a refusal — see Q1. In
either case the version check must happen **before** the driver is built:
the board DB is unencrypted, so `PRAGMA user_version` can be read cheaply
off the file (or via `SQLiteDatabase.openDatabase` read-only) without
constructing `AndroidSqliteDriver` and without the callback's open-time work
(WAL, index self-heal, VACUUM) running against a DB we are about to discard.

### 3.3 The compatibility decision

One pure function, shared by both databases, so the policy is testable
without Android:

```kotlin
enum class DowngradeAction { PROCEED, TOLERATE, REFUSE }

fun decideDowngrade(
    dbVersion: Int,
    schemaVersion: Int,
    minReadableSchema: Int,   // see Q3
): DowngradeAction
```

- `dbVersion <= schemaVersion` → `PROCEED` (normal open/upgrade, untouched).
- `dbVersion > schemaVersion && schemaVersion >= minReadableSchema` →
  `TOLERATE`.
- otherwise → `REFUSE`.

`minReadableSchema` is the escape hatch: the moment someone must write a
destructive migration (a `DROP COLUMN`, a positional reshuffle), raising it
turns silent misreads into an explicit refusal. How that value reaches the
older code is **Q3** — a shipped constant cannot describe migrations written
after it, which is the whole problem in miniature.

---

## 4. Strings (en + de)

Provisional — needed only if Q2 resolves to a recovery screen. If it
resolves to "fail with a clearer log line", this section becomes
`n/a (no UI strings)`.

| Key | en | de |
|-----|----|----|
| `db_too_new_title` | This version is older than your data | Diese Version ist älter als deine Daten |
| `db_too_new_body` | Your training data was saved by a newer version of CruxCoach (%1$s) and this one can't read it. Install the newer version again — nothing is lost. | Deine Trainingsdaten stammen aus einer neueren CruxCoach-Version (%1$s), die diese hier nicht lesen kann. Installiere die neuere Version wieder — es geht nichts verloren. |
| `db_too_new_action_update` | Get the newer version | Neuere Version holen |
| `db_too_new_hint_no_wipe` | Don't clear the app's data — that would delete your logbook. | Lösche die App-Daten nicht — das würde dein Logbuch löschen. |

Both files together (`values/strings.xml` + `values-de/strings.xml`); never
`values-en/`.

---

## 5. Acceptance criteria

1. `decideDowngrade` returns `PROCEED` / `TOLERATE` / `REFUSE` per the table
   in §3.3, including the boundaries `dbVersion == schemaVersion` and
   `schemaVersion == minReadableSchema`. (JVM unit test.)
2. Opening the secure DB whose `user_version` was raised above
   `SecureDatabase.Schema.version` succeeds, and a subsequent
   `insertClimbList` + read-back round-trips correctly. (JVM test against the
   JDBC SQLite driver — SQLCipher itself is Android-only, so the *encrypted*
   variant is an on-device check.)
3. After a tolerated open, `user_version` is unchanged — the older code did
   not renumber the DB downward. (JVM unit test.)
4. No `.sqm` under `sqldelight/secure/` contains `DROP COLUMN`,
   `RENAME COLUMN`, or `DROP TABLE`. If one legitimately must, the test
   fails until `minReadableSchema` is raised in the same change. (JVM test
   reading the migration resources, in the spirit of the existing
   `HotPathIndexDriftTest`.)
5. The canonical `climb_browse` column order in `Board.sq` starts with the
   checked-in golden prefix; new columns may only be appended. A mid-list
   insertion like `25.sqm`'s `method` fails the test. (JVM test + golden
   file.)
6. A board DB whose `user_version` exceeds the board schema version does not
   reach `AndroidSqliteDriver` construction — the pre-open probe short-
   circuits it. (JVM unit test on the probe; on-device check for the real
   file.)
7. Whatever Q1 chooses, no local draft (`source='local' AND
   sync_status='draft'`) and no `kilter_publish_attempts` row is lost across
   a simulated board downgrade. (JVM test over the preserve/restore step.)
8. On-device: install release *R*, then a build with a bumped schema, then
   `adb install -d` back to *R*. The app starts. The logbook is intact. This
   is the QA step from §1.3 and it must pass.

---

## 6. Edge cases

1. **Re-upgrade after a tolerated downgrade.** DB stayed at the higher
   version → the newer build runs zero migrations. Rows written by the older
   build during the interval must still satisfy the newer schema's
   constraints (they do for `10.sqm`: defaults cover every added column).
2. **Older build writes to a table the newer schema extended.** Explicit
   column lists make this safe; criterion 4 keeps it that way.
3. **Multi-identity secure DBs.** The secure DB is per-pubkey
   (`cruxcoach_secure_<prefix>.db`, `AppModule:106`). A downgrade must be
   decided per file, not once per process — a user who switched identities
   can have several at different versions.
4. **WAL/SHM siblings.** Any file-level board-DB operation must handle
   `cruxcoach.db-wal` and `-shm`; leaving a stale WAL next to a replaced DB
   is corruption.
5. **Downgrade during a pending forced resync.** `vacuumIfNeeded` already
   keys off `post_v8_force_resync` / `homewall_force_resync` markers in
   `sync_states`. A board-DB rebuild must not lose a pending marker, or the
   catalogue silently stays stale.
6. **Process kill mid-rebuild.** Whatever Q1 chooses must be re-entrant:
   killed halfway, the next start must reach a valid state rather than a
   half-written DB.
7. **Two-major-version jump** (DB from 0.4, code from 0.2). This is what
   `minReadableSchema` refuses; verify it refuses rather than tolerating a
   distance nobody tested.
8. **Refusal path must not itself crash in `onCreate`.** The whole point is
   replacing a crash with something actionable; a `REFUSE` that throws
   through Hilt has changed nothing.

---

## 7. Testing

**JVM unit tests (`shared/src/commonTest`, `androidApp/src/test`)**

- `DowngradePolicyTest` — criteria 1, 7 (matrix incl. boundaries).
- `SecureDowngradeToleranceTest` — criteria 2, 3, against the JDBC driver
  with a manually raised `user_version`.
- `SecureMigrationAdditivityTest` — criterion 4, parses
  `sqldelight/secure/*.sqm`.
- `BrowseViewColumnOrderTest` — criterion 5, golden prefix.
- `BoardDowngradeProbeTest` — criterion 6.

**On-device (owed, not automatable here)**

- Criterion 8, the full downgrade round-trip, on the low-end device from the
  Gerätetest doc — SQLCipher and the real `SupportHelper` path only exist
  there.
- If Q2 yields a screen: render it in de and en, confirm it appears *instead
  of* the crash.

---

## 8. Open questions

**Q1 — What should the board DB do on a detected downgrade?** It can be
neither tolerated (§3.2a, silent misreads) nor blindly wiped (§3.2b, local
drafts die). Options: (a) export the non-catalogue rows, delete, recreate at
the older schema, re-import them, and force a catalogue resync — correct but
costs a full ~190k-climb download on exactly the low-end device most likely
to be affected; (b) refuse the downgrade for the board DB with the same
recovery screen as the secure DB, leaving the file untouched; (c) rebuild
only the offending views to the older definition and tolerate the rest —
cheapest, but the older code's view definition is not available to it at
runtime, so this needs a stored copy of the canonical DDL per schema
version. Which trade-off do we want?

**Q2 — Crash, or recovery screen?** A screen has to render when the Hilt
graph failed to build, so it means a `try`/`catch` in `CruxCoachApp.onCreate`
plus a non-Hilt `Activity` — a real chunk of scope, and a code path that is
itself hard to test. The alternative is to let it fail but with an
unmistakable log line and crash-report entry, and document the reinstall in
the runbook. Screen, or documented failure?

**Q3 — How does the older code learn `minReadableSchema`?** A constant
compiled into release *R* cannot describe a destructive migration written in
*R+2*, which is the same blind spot that caused this crash. The alternative
is for the newer app to record it *in the DB* — a one-row `schema_compat`
table saying "any build with schema ≥ X can read me" — which the older code
reads defensively (table absent → refuse). That is forward-compatible but
adds a secure-DB migration and a rule that every future destructive
migration must bump it. Constant, or DB-carried marker?

**Q4 — Does this deserve a release target at all, given §1.4?** Its entire
payoff is deferred by one release, and the exposure today is the QA
procedure plus deliberate sideloaders. Fixing the runbook (reinstall the
newer build) costs nothing and covers the known case. Is this worth
implementing now, or does it stay captured here until a user actually rolls
back a release?
