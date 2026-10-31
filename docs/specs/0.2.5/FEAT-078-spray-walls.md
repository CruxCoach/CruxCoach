---
status: implementation
queue: done
base: feat/0.2.5-release
depends_on: []
created: 2026-11-01
updated: 2026-11-01
---

# Feature Spec: Spray walls (FEAT-078)

> **Status:** Implementation on `feat/0.2.5-spray-wall`, based on `feat/0.2.5-release`.
> Owner acceptance test on a phone is still open.

## 1. Problem

CruxCoach knows LED boards with a catalogue: Kilter, MoonBoard, the Aurora family,
Quantum and Shiny Wall. Many climbers also train on a wall that has no LEDs and no
catalogue: a spray wall at home or in a small gym, densely covered in holds. They
set their own problems there and today have nowhere in the app to keep them or to
log them.

## 2. What the climber gets

1. **Add a wall:** photograph the wall (camera or photo library), give it a name and
   its angle. The angle is fixed per wall and logged with every ascent.
2. **Mark the holds by hand** on the photo:
   - Tap to pick a hold, drag it to move it, and use the slider to size it.
   - Press and hold the bare wall to add a hold. With **Add** switched on, every tap
     adds one, so a whole wall goes quickly.
   - Undo and redo; pinch to zoom up to 6×; pan when zoomed.
3. **Set climbs:** pick a role (start, hand, foot, finish) and tap holds. Each climb
   has a name, an optional grade and an optional description. A climb without a
   grade is listed as a **project**.
4. **Browse a wall's climbs:** sort by newest, name or grade, search by name.
5. **Log on a climb:** a send (with tries, an optional felt grade and a comment) or
   attempts. Entries show and can be deleted on the climb's page.
6. **Survive resets:** the holds can be edited at any time.
   - A hold that comes off and is used by a climb stays drawn on that climb, dashed.
   - The climb shows "N holds gone" in the list and a banner on its page. A filter
     (All / Intact / Holds gone) appears once any climb has lost a hold.
   - After a full reset with a new photo, the climber adds a new wall; the old one
     keeps its climbs and logbook.
7. **Private by default and by design:** nothing about a spray wall is synced,
   published, put into offline shares or included in Android backups. The main menu
   entry is "Spray walls".

## 3. Data

All in the per-identity, encrypted **SecureDatabase**, migration `16.sqm` (16 → 17,
additive). Schema and queries are in `SprayWalls.sq`.

| Table | Holds |
| --- | --- |
| `spray_wall` | name, angle, photo file name and pixel size, timestamps |
| `spray_wall_hold` | `(wall_id, hold_id)`, centre `x`/`y` (0..1 of the photo), `radius` (fraction of the photo width), `added_at`, `removed_at` (NULL = on the wall) |
| `spray_climb` | uuid, wall, name, description, difficulty (internal 10..34 scale, NULL = project), holds as `id:role;…` |
| `spray_ascent` | uuid, climb, wall, send or attempts, tries, felt difficulty, comment, `climbed_at` (ISO local date-time) |

- The **photo** is re-encoded on import, so no camera metadata is kept. It is stored
  as a JPEG of at most 3072 px on the long side in `filesDir/spray_walls/`. That
  folder is excluded in `backup_rules.xml` and `data_extraction_rules.xml`.
- Hold ids are per wall and never reused. Saving the editor:
  - writes new holds with the next free ids;
  - updates moved or resized holds in place, because a correction is not a new hold;
  - deletes removed holds no climb uses;
  - marks removed holds that a climb uses as gone (`removed_at`).
- **Why not the board catalogue:** the board database's geometry tables are wiped on
  "delete board data" and copied into offline shares, and the LED-board model treats
  a board without a controller as map-only. A spray wall is private user content, so
  it lives next to the logbook instead.

## 4. Logic (shared, pure Kotlin, `com.cruxcoach.domain.spraywall`)

- **`SprayHoldEditorState`:**
  - Handles add, pick, move, resize and delete, with undo and redo.
  - A drag or a slider gesture counts as one undo step.
  - At most 1,500 holds per wall; the radius stays between 0.6 % and 12 % of the
    photo width.
- **`SprayHoldDiff`:** what saving changes (added / changed / removed).
- **`SprayClimbRules`:**
  - Tapping with a role toggles that role on a hold; tapping with another role
    repaints the hold.
  - A climb needs a name of at most 100 characters, one or two start holds, one or
    two finish holds, and between 2 and 100 holds in total.
  - Holds that are gone may stay on a climb that already had them, but cannot be
    added.
- **`SprayClimbList`:** the filter, sort and search behind a wall's list.
- **`SprayWallGeometry`:**
  - Places the fitted photo in the view and converts between screen and photo
    coordinates under zoom and pan.
  - Picks the hold under a finger: the nearest centre within the radius plus a slop
    that shrinks as the view zooms in.
  - Clamps pan so the photo cannot be dragged out of view.

## 5. UI (`androidApp/.../ui/spraywall`)

| Route | Screen |
| --- | --- |
| `spray_walls` | list of walls with thumbnail and counts |
| `spray_wall_setup?wallId=` | new wall (photo, name, angle) or edit (name, angle, delete) |
| `spray_holds/{wallId}` | hold editor |
| `spray_wall/{wallId}` | the wall's climbs |
| `spray_climb_editor/{wallId}?climbUuid=` | set or edit a climb |
| `spray_climb/{climbUuid}` | climb with its holds, lost-hold banner and logbook |

- A new wall goes straight from setup to the hold editor. Back from there lands on
  the wall.
- The editors ask before discarding unsaved changes.
- Strings: `strings_spray_wall.xml`, English and German.

## 6. Acceptance

1. Photo from the camera and from the library; a portrait, a landscape and a large
   (48 MP) photo all import and show undistorted.
2. 200 holds can be marked on a dense wall. Zoomed in, neighbouring holds can be
   picked apart, and moving and sizing a hold works.
3. A climb cannot be saved without a name, a start and a finish. It shows in the
   wall's list with its grade, or "Project" without one.
4. Taking a hold off the wall leaves climbs that use it with "1 hold gone" and a
   dashed ring. The filter finds them. The climb can still be opened, edited and
   logged.
5. A send and an attempt are logged and listed; only the send counts as a send.
6. Deleting a wall removes its photo file, holds, climbs and logbook entries.
7. Spray walls appear in no board picker, offline share, community publish or
   backup.

## 7. Tests

- `shared/src/commonTest/.../spraywall/SprayWallLogicTest.kt`: encoding, climb
  rules, list, editor (undo, drag as one step, clamping), diff, geometry.
- `androidApp/src/test/.../spraywall/SprayWallRepositoryTest.kt`: create and list,
  gone vs deleted holds, never-reused hold ids, climb update and delete with its
  logbook, wall delete with the photo, migration 16 → 17.
- `androidApp/src/test/.../spraywall/SprayWallBackupExclusionTest.kt`: the photo
  folder is excluded from backup and device transfer.

## 8. Out of scope / next

- Spray-wall sends in the global logbook, statistics and training load. They are kept
  separate for now, so a spray climb is never counted as a Kilter climb.
- Sharing a wall with friends (photo, holds and climbs) through the offline share,
  and an export in the encrypted backup.
- Hold suggestions from the photo.
- Climbs spanning several walls, adjustable angles, LED retrofits.
