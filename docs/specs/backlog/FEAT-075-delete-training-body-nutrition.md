---
status: backlog
queue: parked
target: 0.2.6 (owner 2026-10-25, F10 (a): not in 0.2.5)
base: feat/0.2.5-training-nutrition
depends_on: [FEAT-066, FEAT-067, FEAT-068, FEAT-072]
created: 2026-10-25
---

# Feature Spec: Delete training, body and nutrition data in one go; honest import counts

> **Status:** Backlog for 0.2.6. Owner decision 2026-10-25 (F10 (a), "alles wie
> empfohlen"); the import count is a cosmetic finding from the 0.2.5 device test.

## 1. Problem

**Deleting.** Settings → Data offers "Delete board data" and "Delete personal
logbook data" (ascents, projects, sessions, list entries). For training, body
and nutrition there is no such entry. Today an athlete can delete entries one
by one, "Delete all comeback data" per injury (FEAT-072), or clear the app's
data in Android, which also removes the board data and the account key. An
athlete who wants their training or health data gone has no clear way.

**Counting.** Importing the same backup twice reports the second time as
"Imported: …" with the same numbers, although nothing new arrived (device test
2026-10-25). `AthleteRepository.restore()` counts every row it writes: rows with
the same `updatedAt` as the local copy are written again (`<=`), and favourites,
food log and hydration are counted unconditionally. The board logbook part
skips duplicates ("Duplicates are skipped automatically") but has not been
checked for the same effect.

## 2. Required behaviour

### 2.1 Delete

Settings → Data → **"Delete training, body & nutrition data"** /
**„Trainings-, Körper- & Ernährungsdaten löschen“**, below the logbook entry.

- A dialog with check boxes per category, the same categories as export and
  backup: training & check-ins, body values, nutrition, and **injuries, illness
  pauses & comeback diary** (health, its own box). Nothing pre-checked except
  what the athlete taps; "Delete" stays disabled until one box is checked.
- The dialog says that it cannot be undone and that the encrypted cloud backup
  keeps its last copy until the next backup run (or offers "back up now" first
  when cloud backup is on).
- **Open question for the owner:** does deleting "training" also delete the
  coach's learning, the week plan and the profile settings? Proposal: the
  training category deletes the coach's learning with the trainings; profile
  settings (goal, week, places, units) stay unless a separate box "Settings and
  coach profile" is checked.
- Board data, logbook and account are never touched.

### 2.2 No revival of old body values

`AthleteService.importLegacyBodyStats()` copies the 0.2.3 body stats from the
secure database once and remembers it in `AthleteProfile.legacyBodyStatsImported`.
A delete that resets the profile, or deletes the athlete database file, must not
let the old values come back on the next start:

- keep the flag (or move it out of the athlete database), **and**
- delete the legacy body stats in the secure database too when "Body values"
  is deleted (they are the athlete's data as well).

### 2.3 Honest import counts

- `restore()` counts only rows that changed: new rows, or rows whose content
  differs from the local copy. Identical rows are skipped and not counted.
- The summary separates "new" from "already there" (e.g. "Imported: 12 new ·
  48 already there"); a second import of the same file says "Nothing new –
  everything was already there."
- Same check for the board logbook import.

## 3. Tests

- Robolectric: delete each category alone; other categories, board data and
  logbook remain; health only when its box is checked.
- Delete body values on a device with 0.2.3 body stats → restart → no body
  values.
- Import the same JSON twice → second summary reports nothing new; counts of
  the first import unchanged.
- Device test (Nokia): delete, restart, export → the deleted categories are
  empty.

## 4. Out of scope

- Health Connect: records CruxCoach wrote there stay in Health Connect
  (separate backlog item, audit P2 in the tn-025 run).
- Deleting single days or ranges.
