---
status: implementation
queue: done
base: feat/0.2.5-release
depends_on: []
created: 2026-11-01
updated: 2026-11-01
---

# Feature Spec: Spray walls (FEAT-078)

> **Status:** Implementation on `feat/0.2.5-spray-wall`, based on `feat/0.2.5-release`
> (draft PR #41). Owner acceptance test on a phone is still open.

## 1. Problem

CruxCoach knows LED boards with a catalogue: Kilter, MoonBoard, the Aurora family,
Quantum and Shiny Wall. Many climbers also train on a wall that has no LEDs and no
catalogue: a spray wall at home or in a gym, densely covered in holds. They set their
own problems there and today have nowhere in the app to keep them, log them or share
them with the people who climb on the same wall.

## 2. What the climber gets

1. **Add a wall in three steps**, each with a line that explains it:
   1. **Photo:** camera or photo library, then crop and turn in quarter turns.
   2. **Holds:** the phone looks for holds right away, with a scan running over the
      photo. Suggestions are dashed rings. A sensitivity slider shows more or fewer.
      "Take all" takes the visible ones; tapping one takes just that one. Then the
      climber fixes the rest by hand (see 3).
   3. **Details:** name (prefilled), angle (fixed per wall), private or public, gym.
   - The first marking can be saved as often as needed without adding history.
2. **Hold detection on the phone:** no network, no model file, no new dependency
   (section 4). It handles coloured, black and white holds on plywood or painted
   panels, uneven light and touching holds. It does not suggest T-nut bolt holes.
3. **The hold editor:**
   - Tap to pick a hold, drag to move it, use the slider to size it.
   - Press and hold to add a hold, or switch on **Add** to place several in a row.
   - Undo and redo cover suggestions too. Pinch to zoom up to 6×.
4. **Climbs:** pick a role (start, hand, foot, finish) and tap holds. Each climb has a
   name, an optional grade ("Project" without one) and a description. A wall's list
   can be sorted by newest, name or grade and searched by name.
5. **Private logbook per climb:** sends (tries, felt grade, comment) and attempts.
   Logbook entries are never published.
6. **Versions:** every saved hold edit, every reset with a new photo ("Umschrauben")
   and every restore adds an immutable version, and each climb remembers the version
   it was set on.
   - **After a hold edit:** a climb that uses a hold that came off shows "N holds gone"
     and draws the hold dashed at its last place. The filter Current / Intact / Holds gone
     finds those climbs.
   - **After a reset:** earlier climbs move to the **Earlier** filter and open on the
     photo they were set on. **Current** shows the climbs on the current photo. A filter
     with nothing left to show (Earlier after a restore) falls back to Current.
   - **Versions screen:** lists every version with what changed, its date, holds and
     climbs, and shows it. **Restore** makes an old version current again as a new
     version.
   - An unfinished reset (new photo, no holds yet) can be discarded.
7. **Private or public:**
   - A private wall never leaves the phone.
   - A public wall is published over Nostr with the climber's CruxCoach account
     (section 5). Going public asks first: the photo of every version, the holds and
     the climbs become public, and copies others have can stay after a withdrawal.
   - Withdrawing a wall sends tombstones and a deletion request; the wall stays on
     the phone as a private wall.
   - Publishing needs an existing account key (local key or Amber); it never creates
     one.
8. **Gyms:**
   - A wall can belong to a venue of the board map. The gym picker searches the map's
     places by name or town.
   - The map's place sheet has **Spray walls here**:
     - The public walls at that place are loaded only when the climber taps
       "Show spray walls"; there is no background traffic.
     - **Add** stores one of them on the phone. Its holds are read-only; the climber
       can set, publish and log climbs on it.
     - **Set up a spray wall here** starts a new wall at that gym.
   - Coordinates of a private or home wall are never published. A public wall at a
     gym carries only the venue key of that public place.
9. **Privacy by default:** spray-wall photos and data are not in Android backups or
   offline shares, and appear in no board picker.

## 3. Data

All in the per-identity, encrypted **SecureDatabase**, migration `16.sqm` (16 → 17,
additive). Schema and queries: `SprayWalls.sq`.

| Table | Holds |
| --- | --- |
| `spray_wall` | name, angle, visibility, gym (venue key + name), owner pubkey (NULL = mine), current version, highest published version, timestamps |
| `spray_wall_version` | `(wall_id, version)`, kind (created / holds / reset / restored), based_on, photo file and pixel size, Blossom sha256 + URL, hold count |
| `spray_wall_hold` | `(wall_id, version, hold_id)`, centre `x`/`y` (0..1 of the photo), `radius` (fraction of the photo **width**) |
| `spray_climb` | uuid, wall, version, name, description, difficulty (internal 10..34, NULL = project), holds `id:role;…`, author pubkey (NULL = mine), published_at |
| `spray_ascent` | uuid, climb, wall, send or attempts, tries, felt difficulty, comment, `climbed_at` — never published |

- **Photo:** re-encoded on import (no camera metadata) as a JPEG of at most 3072 px
  on the long side in `filesDir/spray_walls/`. The folder is excluded in
  `backup_rules.xml` and `data_extraction_rules.xml`. Versions on the same photo
  share the file; other people's photos are stored once per Blossom hash.
- **Hold ids** are per wall and never reused. A moved or resized hold keeps its id,
  because that is a correction, not a new hold. Every hold on a new photo gets a new
  id.
- **Why not the board catalogue:** the board database's geometry tables are wiped on
  "delete board data" and copied into offline shares. A spray wall is user content,
  so it lives next to the logbook instead.

## 4. Logic (shared, pure Kotlin, `com.cruxcoach.domain.spraywall`)

- **`detect.SprayHoldDetector`** behind **`detect.HoldDetector`**, the seam for a
  trained model later. It works on at most 1024 px:
  1. **Panel model:** the panel colour is the densest spot of the chroma histogram;
     its brightness is estimated per grid cell and interpolated, so a light gradient
     stays panel.
  2. **Saliency:** the log brightness ratio and the chroma distance to the panel, in
     units of the panel's own noise. Darkening by up to a third looks like a cast
     shadow and does not count.
  3. **Regions:** salient pixels form hold regions; small gaps such as bolt holes are
     filled. Each local maximum of the distance to the panel is the middle of a hold,
     so touching holds split at their neck. Rays that stop at the panel or at the
     saddle towards the next hold give the radius.
  4. **Blobs:** a scale-space difference of Gaussians finds holds too faint for a
     region. A blob confirms a region, or becomes a suggestion where there is none.
     Panel seams and bumps on a hold's rim are dropped.
  5. **T-nut holes:** small, dark, grey blobs that share a size and a grid spacing are
     the bolt holes and are dropped.
  6. **Confidence:** contrast, compact shape and region/blob agreement, calibrated so
     that the editor's default threshold of 0.5 keeps the clear holds.
- **`SprayHoldEditorState`:** holds, the picked hold, suggestions, threshold and undo
  or redo. A drag, a slider gesture or "take all" is one undo step. At most 1,500
  holds per wall; the radius stays between 0.6 % and 12 % of the photo width.
- **`SprayWallHistory`:**
  - which versions belong to an earlier photo;
  - where a hold was last seen;
  - whether the current version can still be corrected in place (no climb on it,
    never published);
  - ids for new holds.
- **`SprayClimbRules`:** tapping toggles or repaints a role. A climb needs a name of
  at most 100 characters, one or two start holds, one or two finish holds, and 2 to
  100 holds. Holds that are gone may stay on a climb that had them but cannot be
  added.
- **`SprayClimbList`:** filter (Current, Intact, Holds gone, Earlier; only the ones
  with climbs get a chip), sort and search.
- **`SprayCropBox`** and **`SprayWallGeometry`:** crop handles and quarter turns; the
  photo fit, screen ↔ photo coordinates under zoom and pan, hold picking with a slop
  that shrinks when zoomed in.
- **`SprayWallNostr`:** builders and parsers for the events in section 5, as pure
  functions.

## 5. Publishing (`SprayWallPublisher`, `SprayWallFetcher`)

Kind **30078** only, under the label namespace `L` = `com.cruxcoach.spraywall`: no new
kind and no new Amber permission.

| Event | `d` tag | Content |
| --- | --- | --- |
| Wall head | `cruxcoach:spraywall:<pk8>:<wallId>` | name, angle, current version, gym; replaced on change |
| Version | `cruxcoach:spraywall:<pk8>:<wallId>:v:<000n>` | kind, based_on, photo (Blossom sha256, URL, size), holds `id,x,y,r;…` in 1/10000; one per version, never replaced |
| Climb | `cruxcoach:sprayclimb:<pk8>:<uuid>` | wall, version, name, description, grade, holds; `a` tag to the wall head |

- A wall at a gym also carries `["L","com.cruxcoach.venue"]` and
  `["l",<venueKey>,"com.cruxcoach.venue"]`, where `venueKey` is the map's
  `venueKey(lat, lng)`. That is how the map asks for the walls of one place.
- Versions and climbs carry `["a","30078:<owner>:<wall d>"]`, so one request returns
  everything that belongs to a wall.
- The largest wall (1,500 holds) stays far below 64 KB per event.
- **Publishing:** photos go to Blossom through `ProfileImageUploader`. Then every
  version not yet published goes out, then the head, then the climbs on public walls.
  This runs after each save; on failure the wall shows "not published yet" with a
  retry. Signing and relays: `NostrSigner`, `NostrRelayPool.sendEventWithStats`.
- **Withdrawing:** every event (head, versions, my climbs) is replaced by a tombstone
  (`["deleted","true"]`, `{"deleted":true}`; climb tombstones keep the `a` tag) and
  named in one kind-5 deletion, as `CommunityClimbDeleter` does.
- **Ingest** (`NostrRelayPool.fetchStored`, on demand only) uses the same checks as
  `CommunityClimbSubscriber`:
  - a size cap, id and signature verification, and a `d` tag that must belong to its
    author;
  - plain ids, holds and coordinates in range, `https` photo URLs, a valid venue key,
    no events from the future;
  - newest per `d` tag, and tombstones win.
  Photos are downloaded by hash through `BlossomUploader.download` (hash checked,
  16 MB cap).
- **Other people's walls are read-only.** Anyone can set and publish climbs on a
  public wall; a climb someone takes back stays on the phone only while it carries
  logbook entries. A withdrawn wall stays as a private copy for its climbs and
  logbook.

## 6. UI (`androidApp/.../ui/spraywall`)

| Route | Screen |
| --- | --- |
| `spray_walls` | list of walls with thumbnail and counts |
| `spray_wall_new?gymId=&gymName=` | step 1: photo, crop and rotate (gym preset from the map) |
| `spray_holds/{wallId}?first=` | hold editor; with `first` step 2, saving goes on to the details |
| `spray_wall_details/{wallId}?first=` | name, angle, private/public, gym, delete; with `first` step 3 |
| `spray_wall_reset/{wallId}` | reset with a new photo |
| `spray_wall/{wallId}` | the wall's climbs, publish state, menu (holds, reset, versions, details or remove) |
| `spray_versions/{wallId}` | versions, preview, restore |
| `spray_climb_editor/{wallId}?climbUuid=` | set or edit a climb |
| `spray_climb/{climbUuid}` | climb with its holds, banners and logbook |

The map's place sheet gets the **Spray walls here** section (`SprayWallsAtVenue`).
Strings: `strings_spray_wall.xml`, English and German.

## 7. Acceptance

1. A portrait, a landscape and a large (48 MP) photo from camera and library can be
   cropped, turned and shown undistorted.
2. On a typical wall, taking the suggestions saves most of the manual marking. No
   suggestion lands on a T-nut hole.
3. 200 holds can be marked on a dense wall; zoomed in, neighbouring holds can be
   picked apart.
4. A climb cannot be saved without a name, a start and a finish.
5. After a hold edit, climbs using a removed hold show "1 hold gone" and a dashed
   ring. After a reset, they are under "Earlier" and open on their photo. Restoring
   version 1 brings them back.
6. Making a wall public asks first and needs an account key. A second phone sees the
   wall under "Spray walls here" at its gym, adds it, and sees the climbs; a climb set
   there shows up on the first phone after reopening the wall.
7. Withdrawing removes the wall from "Spray walls here".
8. Spray walls appear in no board picker, offline share, community climb list or
   backup. Logbook entries never leave the phone.

## 8. Tests

- `shared/src/commonTest/.../spraywall/SprayWallLogicTest.kt`: encoding, climb rules,
  list incl. Earlier, editor incl. suggestions, history, crop box, geometry.
- `.../spraywall/SprayWallNostrTest.kt`: builders and parsers round trip; foreign
  `d` tags, hostile or malformed events and oversize content are rejected; the
  largest wall stays under the event limit.
- `.../spraywall/detect/SprayHoldDetectorTest.kt` on `SyntheticWall`. Seeded walls with
  known holds: panels, colours, light, shadows, blur, noise, touching holds and
  T-nut grids.
- `androidApp/src/test/.../spraywall/SprayWallRepositoryTest.kt`:
  - versions: in-place first marking, new versions, a reset, a restore, discarding;
  - climbs keeping their version, hold ids never reused, deleting with photos;
  - followed walls and remote climbs;
  - migration 16 → 17.
- `SprayWallBackupExclusionTest.kt`: the photo folder is excluded from backup and
  device transfer.

Detector, measured on the synthetic scenarios at the default threshold (0.5):

| Scenario | Precision | Recall |
| --- | --- | --- |
| Coloured holds on plywood | 0.98 | 1.00 |
| Mixed incl. black/white/grey, strong uneven light | 0.99 | 0.97 |
| Black panel | 0.97 | 0.85 |
| White panel | 0.99 | 0.87 |
| Grey panel | 0.98 | 0.93 |
| Dense, many touching holds | 0.96 | 0.99 |
| Close-up (large bolt holes) | 0.96 | 1.00 |
| Portrait photo | 0.98 | 1.00 |
| Hard light with strong shadows | 0.95 | 1.00 |
| Blurry and noisy | 0.98 | 0.95 |
| Wooden holds on plywood | 0.99 | 0.69 |
| Bare walls with T-nut grids | no suggestions | — |

Misses are mostly black holds on a black panel, white on white and wooden holds on
plywood. Real photos will differ from synthetic ones. Tuning on real walls is the
next step.

## 9. Out of scope / next

- Spray-wall sends in the global logbook, statistics and training load. They are kept
  separate for now, so a spray climb is never counted as a board climb.
- A trained hold detector behind `HoldDetector`, tuned on real wall photos.
- Live updates of public walls. They are fetched when a wall is opened or added.
- Climbs spanning several walls, adjustable angles, LED retrofits.
