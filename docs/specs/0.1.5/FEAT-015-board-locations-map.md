---
status: implementation
---
# Feature Spec: Kilter Board Locations Map (FEAT-015)

> **Status:** Implementation — design locked 2026-05-05; implementation
> started 2026-05-05 on branch `feat/0.1.5-board-locations-map`.
> **Depends on:** Existing Blossom sync infrastructure
> (`BlossomSyncManager`, `BlossomManifest`, `BoardDatabaseImporter`); board
> database schema migration capability.
> **Blocks:** —
> **Target release:** v0.1.5

## 1. Overview

CruxCoach users have no way to discover where Kilter Boards are physically
installed. This feature adds an interactive world map of all known Kilter
Board installations, sourced from Kilter's own public locator and
distributed alongside the existing board database via Blossom.

The Map is invoked from the Board Browser's existing search bar (next to
hold-search), keeping the discovery flow contextual. Tapping a marker opens
a detail sheet with address, contact info, and a deep-link to browse climbs
for that board's specific layout/size — when known, the same layout/size the
user has configured in board settings can be filtered for.

### Goals

- Render ~1065 Kilter Board locations worldwide on an interactive map
- No Google Maps / no proprietary tile providers / no API keys in the APK
- Vendor-neutral renderer (MapLibre + OpenFreeMap) with auto tile cache
- Search-free discovery: pan + zoom + tap
- "Public only" + "Matches my board" filter chips
- Public/private boards visually distinguished
- Marker data syncs daily via existing Blossom infrastructure (no extra
  network plumbing)
- Graceful no-network handling: explicit dialog if tiles can't load

### Non-Goals

- Multi-board support (Tension, Moonboard etc.) — Kilter only
- "Locate me" / GPS-based recentering (deferred to a later release)
- Pre-download offline regions (no "save region" UI)
- User-contributed locations / edits
- Routing / directions
- Real-time gym status (open/closed, current crowd)
- Booking / membership integration

---

## 2. Data Source

### 2.1 Decision: Kilter's Official StoreRocket Endpoint

The Kilter Board Locator on `settercloset.com/pages/kb-locator` is a public
StoreRocket widget. Its data endpoint is publicly callable without auth:

```
GET https://storerocket.io/api/user/vo8xyNypgn/locations
```

The StoreRocket account ID `vo8xyNypgn` is embedded as the
`data-storerocket-id` attribute on Kilter's own locator page and is
therefore public information.

**Critical**: this endpoint is called by **the server-side Blossom cron**,
never by the client. Users never hit StoreRocket directly.

### 2.2 Why Not the Other Candidates

| Source | Rejected because |
|---|---|
| `@hangtime/climbing-boards` (npm) | Maintainer scrapes the Aurora API with a personal Kilter account. Re-distributing data pulled with someone else's credentials is incompatible with `feedback_kilter_compliance`. |
| Kilter Aurora API direct | Same auth/credential issue. |
| Kilter PowerSync `global_gyms[]` | Requires Keycloak auth + PowerSync integration (out of scope; see `project_kilter_aurora_split`). |
| OpenStreetMap (Overpass) | No board-specific tags exist (verified 2026-05-05: `climbing:boards` and `climbing:training~kilter` return zero elements). |
| BoardLib | Confirmed: no gym-location data, only climbs/logbooks. |

### 2.3 Source Data Shape (StoreRocket v1)

Verified 2026-05-05; subject to upstream change.

```json
{
  "success": true,
  "results": {
    "locations": [
      {
        "id": 22029860,
        "name": "1UP Bouldering",
        "lat": "-33.8994742",
        "lng": "151.0437180",
        "phone": "02 9790 0408",
        "email": "hello@oneupbouldering.com.au",
        "url": "https://www.oneupbouldering.com.au/",
        "address_line_1": "1/4 Brunker Rd",
        "address_line_2": "Chullora",
        "city": "Sydney",
        "state": "New South Wales",
        "postcode": "2190",
        "country": "Australia",
        "instagram": "...",
        "filters": [
          { "name": "Size: 12x12" },
          { "name": "Layout: Original" },
          { "name": "Access: Public" },
          { "name": "Frame: Lemur" },
          { "name": "Adjustability: Full Adjustability" }
        ]
      }
    ]
  }
}
```

Snapshot 2026-05-05: ~1065 locations across 117 countries.

### 2.4 Known Quality Issues to Normalize

| Issue | Example | Fix |
|---|---|---|
| Country duplicates | `"Germany"` and `"DE"` for the same country | Normalize to ISO-3166-1 alpha-2 via lookup table |
| Whitespace in filters | `"Access: Public"` vs `"Access:  Public"` | `trim()` + collapse internal whitespace |
| Filter prefix variance | `"Layout: Original"` vs `"Layout:  Original"` | Same |
| Empty social fields | `"facebook": ""` | Drop empty strings to NULL |
| Lat/lng as strings | `"lat": "-33.8994742"` | Parse to `Double`; drop entries where parse fails or out of WGS84 range |
| Inconsistent layout names | `"Layout: HW- Fullride"` vs `"Layout: HW- Mainline"` | See §2.5 mapping table |

### 2.5 Filter String → Structured Field Mapping

Mapping is performed by the Blossom cron at the time of normalization. Lookup
tables live with the cron source so updates don't require an app release.

**Layout mapping** (`StoreRocket "Layout: ..."` → CruxCoach `(layout_id)`):

| StoreRocket string | layout_id | Notes |
|---|---|---|
| `Original` | 1 | Kilter Board Original — primary commercial product |
| `HW- Fullride` | 8 | Homewall, full layout (mainline + auxiliary) |
| `HW- Mainline` | 8 | Homewall, mainline-only set |
| `HW- Auxiliary` | 8 | Homewall, auxiliary-only set |
| anything else | NULL | Defensive: future products land as unmapped |

**Size mapping** (`StoreRocket "Size: ..."` → CruxCoach `product_size_id`,
joining via `aurora_product_size.name`):

The cron does a left-join against the *current published board database* to
resolve size strings to `product_size_id`. If the join fails (size string
not present in the DB for the given layout), `product_size_id = NULL` and
the literal `size_label` is preserved verbatim for display.

Initial mapping (verified by reading `aurora_product_size` table — to be
filled during implementation phase 1):

| StoreRocket string | layout_id | product_size_id | DB name |
|---|---|---|---|
| `12x12` | 1 | 7 (TBD: verify against current board DB) | `12 x 12 Original` |
| `8x12` | 1 | 8 (TBD) | `8 x 12` |
| `16x12` | 1 | 14 (TBD) | `16 x 12` |
| `7x10` | 8 | varies | various Homewall sizes |
| anything else | partial match preserved | NULL | — |

**Other filter prefixes:**

| Filter prefix | Target field | Values |
|---|---|---|
| `Access:` | `access_type` enum | `PUBLIC`, `PRIVATE`, `MEMBERS`, `UNKNOWN` |
| `Adjustability:` | `adjustability` enum + optional `fixed_angle` | `FIXED`, `ADJUSTABLE`, `LIMITED`, `FULL`, `UNKNOWN`. `Fixed - 40` parses to `FIXED` + `fixed_angle=40` |
| `Frame:` | `frame_maker` string | Verbatim after dedup whitespace |

---

## 3. Distribution: Daily Blossom Sync

### 3.1 Architecture Decision

Board location data is published to Blossom alongside the board database,
via the **same daily cron** that already maintains
`project_blossom_sync.md`. The client downloads via the **existing**
`BlossomSyncManager` flow — no new network code, no new manifests, no
build-time bundling.

```
┌────────────────────────┐
│ Server-side daily cron │
│ (existing infrastructure)
├────────────────────────┤
│ 1. Pull board DB       │  ← already exists
│    chunks → Blossom    │
│ 2. Pull StoreRocket    │  ← NEW
│ 3. Normalize           │
│ 4. Build SQLite        │
│    "locations" chunk   │
│ 5. zstd-compress       │
│ 6. Upload to Blossom   │
│ 7. Update Kind 30078   │
│    manifest with new   │
│    chunk entry         │
└────────────────────────┘
              ↓
┌────────────────────────┐
│ Client (existing flow) │
├────────────────────────┤
│ BlossomSyncManager     │
│   .fetchManifest()     │
│   .download(chunk)     │
│   .verify(sha256)      │
│   .decompress(zstd)    │
│ BoardDatabaseImporter  │
│   .ingest(sqlite_file) │
│   ↓                    │
│   board.db gets new    │
│   kilter_board_location│
│   table populated      │
└────────────────────────┘
```

### 3.2 Manifest Schema Extension

`BlossomManifest.chunks[]` already supports a `type` field
(currently `"meta" | "climbs" | "stats"`). Add a new value:

```kotlin
// Extend the type enumeration to include:
//   "locations" — Kilter Board location dataset (new in v0.1.5)
```

Manifest backward-compatibility: clients on v0.1.4 and earlier ignore unknown
chunk types (they iterate over the chunks list and skip types they don't
handle). No manifest version bump required. New `locations` chunk is purely
additive.

Example manifest after the change:

```json
{
  "v": 1,
  "board": "kilter",
  "product_id": 1,
  "created_at": 1714935600,
  "compression": "zstd",
  "chunks": [
    { "name": "meta.zst", "type": "meta", "sha256": "...", "size": 12345, "urls": [...] },
    { "name": "climbs-0.zst", "type": "climbs", "sha256": "...", "size": 234567, "urls": [...] },
    { "name": "climbs-1.zst", "type": "climbs", "sha256": "...", "size": 234567, "urls": [...] },
    { "name": "stats.zst", "type": "stats", "sha256": "...", "size": 45678, "urls": [...] },
    { "name": "locations.zst", "type": "locations", "sha256": "...", "size": 56789, "urls": [...] }
  ]
}
```

### 3.3 Server-Side Cron Task (Pseudocode)

Adds to the existing daily Python (or whichever language the cron runs in)
script. Sequenced **after** the board DB chunks are built, so it can join
against them for size-ID resolution.

```python
def build_locations_chunk(board_db_path: str, output_dir: str) -> ChunkInfo:
    # 1. Fetch
    raw = httpx.get(
        "https://storerocket.io/api/user/vo8xyNypgn/locations",
        headers={"User-Agent": f"CruxCoach-Cron/{VERSION}"},
        timeout=30,
    ).raise_for_status().json()
    if not raw.get("success"):
        raise CronError("StoreRocket returned success=false")

    locations = raw["results"]["locations"]
    if len(locations) < 800:
        raise CronError(f"Sanity check failed: only {len(locations)} locations")

    # 2. Normalize
    normalized = [normalize_location(loc, board_db_path) for loc in locations]
    normalized = [n for n in normalized if n is not None]  # drops invalid lat/lng

    # 3. Build SQLite
    sqlite_path = f"{output_dir}/locations.sqlite"
    with sqlite3.connect(sqlite_path) as conn:
        conn.execute(KILTER_BOARD_LOCATION_SCHEMA)  # see §4
        conn.executemany(
            "INSERT INTO kilter_board_location VALUES (?, ?, ?, ...)",
            [tuple(n.values()) for n in normalized]
        )

    # 4. Compress + hash + upload (reuse existing helpers)
    compressed_path = zstd_compress(sqlite_path)
    sha = sha256_file(compressed_path)
    blossom_urls = blossom_upload(compressed_path)

    return ChunkInfo(
        name="locations.zst",
        type="locations",
        sha256=sha,
        size=os.path.getsize(compressed_path),
        urls=blossom_urls,
    )

def update_manifest(existing_chunks: list, locations_chunk: ChunkInfo):
    # Replace any prior locations chunk; append if first time
    chunks = [c for c in existing_chunks if c["type"] != "locations"]
    chunks.append(locations_chunk._asdict())
    return chunks
```

### 3.4 Cron Failure Handling

If the StoreRocket fetch fails or the sanity check trips:

- Log the error with full context
- **Keep the previous `locations` chunk in the manifest** (do not omit it)
- The board DB chunks publish independently — locations failure does not
  block the daily board-DB cron run

This means stale location data ships rather than no data. Acceptable: the
data changes very slowly (a handful of new gyms per month).

If the cron fails for **>14 consecutive days**, an alert fires (existing
cron alerting infrastructure). Manual intervention then.

### 3.5 First-Sync Behavior on Client

The board database is required for the app to function (climbs browser
needs it). On first install / fresh sync:

- All chunks (including `locations`) download as part of the existing
  initial sync flow
- No special bootstrap for locations — they piggyback on the same UX
- If `locations` chunk is missing from manifest (older cron), the
  `kilter_board_location` table simply stays empty; Map screen handles this
  gracefully (see §6.6)

### 3.6 Incremental Updates

`BlossomSyncManager` already does SHA-256-based diffing per chunk. If only
the `locations` chunk's hash changed (gym-list update day), only that chunk
re-downloads — typically <50 KB compressed.

---

## 4. SQLite Schema

### 4.0 Migration Slot Claimed

This feature uses **`shared/src/commonMain/sqldelight/board/2.sqm`** for the
schema migration.

Slot rationale (revised 2026-05-05): SQLDelight requires sequential, gap-free
migrations — the next free slot from the current `dev` state is `2.sqm`.
FEAT-003 (v0.1.4, Climb Creator) is also pre-implementation; whichever feature
lands on `dev` first takes `2.sqm`, the other rebases and renames to `3.sqm`.
Either ordering works; the rename is mechanical and produces no logic conflict
because the two migrations touch disjoint tables.

The new table lives in a sibling SQLDelight file
`shared/src/commonMain/sqldelight/board/com/cruxcoach/db/board/KilterBoardLocation.sq`
to keep `AuroraBoard.sq` under the project's ~500-line file limit.

```sql
CREATE TABLE kilter_board_location (
    storerocket_id INTEGER NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    lat REAL NOT NULL,
    lng REAL NOT NULL,
    address TEXT,
    city TEXT,
    country_code TEXT NOT NULL,            -- ISO-3166-1 alpha-2
    phone TEXT,
    email TEXT,
    url TEXT,
    instagram TEXT,
    layout_name TEXT,                      -- "Original", "HW- Fullride", ...
    layout_id INTEGER,                     -- nullable; mapped at cron time
    size_label TEXT,                       -- "12x12", "8x12", ...
    product_size_id INTEGER,               -- nullable; mapped at cron time
    access_type TEXT NOT NULL DEFAULT 'UNKNOWN',  -- 'PUBLIC' | 'PRIVATE' | 'MEMBERS' | 'UNKNOWN'
    adjustability TEXT NOT NULL DEFAULT 'UNKNOWN', -- 'FIXED' | 'ADJUSTABLE' | 'LIMITED' | 'FULL' | 'UNKNOWN'
    fixed_angle INTEGER,                   -- nullable; only set when adjustability='FIXED'
    frame_maker TEXT
);

CREATE INDEX idx_kbl_layout_size ON kilter_board_location(layout_id, product_size_id);
CREATE INDEX idx_kbl_country ON kilter_board_location(country_code);
CREATE INDEX idx_kbl_access ON kilter_board_location(access_type);
```

Estimated row size: ~250 bytes. 1065 rows ≈ **260 KB uncompressed**, ~50 KB
zstd-compressed in transit.

### 4.1 Repository Interface

```kotlin
// shared/src/commonMain/kotlin/com/cruxcoach/data/repository/BoardLocationRepository.kt

interface BoardLocationRepository {
    suspend fun getAll(): List<BoardLocation>
    suspend fun getMatchingBoard(layoutId: Int, productSizeId: Int?): List<BoardLocation>
    suspend fun getById(storerocketId: Long): BoardLocation?
    fun observeAll(): Flow<List<BoardLocation>>
}

data class BoardLocation(
    val id: Long,
    val name: String,
    val lat: Double,
    val lng: Double,
    val address: String?,
    val city: String?,
    val countryCode: String,
    val phone: String?,
    val email: String?,
    val url: String?,
    val instagram: String?,
    val layoutName: String?,
    val layoutId: Int?,
    val sizeLabel: String?,
    val productSizeId: Int?,
    val accessType: AccessType,
    val adjustability: Adjustability,
    val fixedAngle: Int?,
    val frameMaker: String?,
)
```

---

## 5. Map Renderer

### 5.1 Decision: MapLibre Native Android + OpenFreeMap

Library: `org.maplibre.gl:android-sdk:11.x` (BSD-2 license).

Tile source: OpenFreeMap (`https://tiles.openfreemap.org/styles/liberty` for
light theme, `https://tiles.openfreemap.org/styles/positron` for dark) —
vector tiles, no API key, no rate limit.

### 5.2 Why This Stack

- **No API key** = no public-repo leak risk
- **Vector tiles** = sharp on hi-DPI, ~5–10× smaller than raster
- **Provider-agnostic**: style URL is config; swap to Stadia/self-hosted
  without code changes
- **Auto-cache**: MapLibre's default tile cache handles offline-after-view

### 5.3 Tile Cache Configuration

MapLibre's **default 50 MB cache**, LRU eviction. No tuning in v1 — revisit
if user feedback indicates it's too small.

### 5.4 Dark-Mode Style Switching

System dark-mode toggle → swap MapLibre style URL:

- Light: `liberty`
- Dark: `positron`

Implementation: observe `Configuration.uiMode` in the `MapView` AndroidView
wrapper; call `mapboxMap.setStyle(newStyleUrl)` on change. Tile cache is
shared between styles (vector tile data is style-independent).

### 5.5 Attribution

OpenFreeMap requires attribution: `© OpenFreeMap © OpenMapTiles © OSM
contributors`. Rendered in MapLibre's default attribution control
(bottom-right). Tappable, opens links in external browser.

---

## 6. Map Screen UI

### 6.1 Entry Point: Search-Bar Integration

The map invocation lives in the **Board Browser's search-bar row**
(`BoardBrowserScreen.kt:405-449`), as a third icon button alongside the
existing text-search field and the `GridView` (hold-search) icon:

```
┌──────────────────────────────────────────────┐
│ [search field..............] [⊞] [🗺]        │
└──────────────────────────────────────────────┘
                              ↑    ↑
                          hold    NEW: map
                          search  (Icons.Outlined.Map)
```

- Icon: `Icons.Outlined.Map`
- testTag: `board_map_button`
- contentDescription: `R.string.cd_map`
- onClick: navigate to `MapScreen`
- Tint: `MaterialTheme.colorScheme.onSurfaceVariant` (matches hold-search default)

This makes Map a peer of hold-search — both are "find climbs by another
dimension" actions, sharing the discovery slot.

### 6.2 Layout

```
┌─────────────────────────────────────┐
│ ← Map                          ⓘ    │  TopAppBar: back, info
├─────────────────────────────────────┤
│ [✓ Public only] [☐ Matches my board]│  Filter chips (FilterChip row)
├─────────────────────────────────────┤
│                                     │
│           [MapLibre canvas]         │
│           with markers              │
│                                     │
│                                     │
│  © OpenFreeMap © OSM ...            │  attribution
└─────────────────────────────────────┘
```

No FAB, no locate-me button (deferred).

### 6.3 Marker Rendering

- Custom marker icon via MapLibre's `SymbolLayer`:
  - **Public access** (`access_type = 'PUBLIC'`): orange dot
    (CruxCoach `OrangeAccent`), 14 dp; selected = 22 dp
  - **Private/Members/Unknown** (anything else): grey dot
    (`MaterialTheme.colorScheme.outline`), same sizes
- Tap behavior: opens detail sheet (§7), recenters map on marker
- Cluster at low zoom (`zoom < 6`): single circle with count, MapLibre
  built-in `cluster: true` GeoJSON source feature
  - Cluster color: orange (assuming most clusters contain at least one
    public board — 75 % of source records are public)

### 6.4 Filter Chips

Two `FilterChip`s above the map canvas, both default OFF:

**`Public only`** — when active, hides markers where `access_type != 'PUBLIC'`.

**`Matches my board`** — when active, hides markers where the location's
`(layout_id, product_size_id)` doesn't match the user's currently configured
board (from `UserPreferences` / board settings). If the user has no board
configured, the chip is **disabled with a tooltip**:
`R.string.map_filter_match_disabled` ("Configure your board first").

If location's `layout_id` is NULL but matches user's `layout_id` is set, the
location is hidden when "Matches my board" is on (we don't show
ambiguously-mapped locations as matches).

Filter state persists across screen entries via `UserPreferences`
(SharedPreferences) — sensible defaults restore on next open.

### 6.5 Initial Viewport

- First open: world view (`zoom = 1`, center `LatLng(20, 0)`)
- Subsequent opens: restore last viewport from `SharedPreferences`

### 6.6 Empty Data State

If `kilter_board_location` table is empty (older client without sync, or
sync failed completely):

- Render the map (tiles still useful as world reference)
- Overlay a snackbar: `R.string.map_no_data` ("Board location data not
  available — sync the board database first")
- Tap action on snackbar: navigate to Board Sync screen

---

## 7. Marker Detail Sheet

Bottom sheet (`ModalBottomSheet`), expanded to ~50 % screen height.

```
┌──────────────────────────────────────┐
│ ━━━                                   │ drag handle
│ 1UP Bouldering                        │ name (h6)
│ Sydney, Australia                     │ city + country (caption)
├──────────────────────────────────────┤
│ 📍 1/4 Brunker Rd, Chullora, ...     │ address
│ 📞 02 9790 0408                       │ phone (tap = dial)
│ ✉  hello@oneupbouldering.com.au      │ email (tap = compose)
│ 🌐 oneupbouldering.com.au             │ website (tap = open)
├──────────────────────────────────────┤
│ Layout: Original (12×12)              │
│ Access: Public                        │
│ Adjustability: Full                   │
│ Frame: Lemur                          │
├──────────────────────────────────────┤
│  [Browse climbs for this board]       │ deep-link, only when layout_id != null
│  [Open in Maps]                       │ geo: intent
└──────────────────────────────────────┘
```

### 7.1 Empty-Field Behavior (A4 = b)

When `phone`, `email`, `url`, or `instagram` is NULL, the row **is shown
with `—` as the value** (not hidden). Justification: gives the user a
predictable layout and signals "we know this gym, this info is just absent
upstream."

For the **structured fields** (Layout / Access / Adjustability / Frame), if
the value is `UNKNOWN`, show `—`.

### 7.2 "Browse Climbs" Deep Link

Only rendered when the location's `layout_id` is non-NULL. Tapping it:

1. If `product_size_id` is also non-NULL: navigate to Board Browser with
   layout + size pre-selected for filtering
2. If only `layout_id`: pre-select layout, leave size as user's current

When `layout_id` is NULL the button is omitted (avoids a button that does
nothing useful).

### 7.3 "Open in Maps" Intent

```kotlin
Intent(Intent.ACTION_VIEW,
    "geo:${lat},${lng}?q=${lat},${lng}(${Uri.encode(name)})".toUri()
)
```

Standard chooser; opens whichever maps app the user has installed (Organic
Maps, OsmAnd, Google Maps, etc.).

---

## 8. Offline Behavior

### 8.1 Marker Data: Always Available

`kilter_board_location` lives in the on-device board database, populated on
first sync. Markers always render even without network.

### 8.2 Tile Connectivity

On Map screen `onResume`:

1. Check `ConnectivityManager.activeNetwork`
2. If no network AND tile cache has zero tiles for the current viewport
   region → show full-screen dialog (§8.3)
3. If no network but cache has some tiles → render normally; uncached tiles
   are blank-grey (MapLibre default), markers still readable

We don't probe OpenFreeMap reachability; tile-load failures are silent.

### 8.3 No-Network Dialog

```
┌────────────────────────────────────────────┐
│ No internet connection                      │
│                                             │
│ The map needs internet the first time you   │
│ open it. After that, areas you've viewed    │
│ stay available offline.                     │
│                                             │
│ Connect to Wi-Fi or mobile data and try     │
│ again.                                       │
│                                             │
│              [ Close ]                      │
└────────────────────────────────────────────┘
```

User dismisses → returns to Board Browser. We do not poll for connectivity
restoration.

---

## 9. Localized Strings

`androidApp/src/main/res/values/strings.xml` (English, default):

```xml
<string name="map_screen_title">Map</string>
<string name="cd_map">Find a Kilter Board on the map</string>
<string name="map_filter_public_only">Public only</string>
<string name="map_filter_matches_my_board">Matches my board</string>
<string name="map_filter_match_disabled">Configure your board first</string>
<string name="map_no_data">Board location data not available — sync the board database first</string>
<string name="map_no_data_action">Sync now</string>
<string name="map_offline_dialog_title">No internet connection</string>
<string name="map_offline_dialog_body">The map needs internet the first time you open it. After that, areas you\'ve viewed stay available offline.\n\nConnect to Wi-Fi or mobile data and try again.</string>
<string name="map_offline_dialog_close">Close</string>
<string name="map_marker_browse_climbs">Browse climbs for this board</string>
<string name="map_marker_open_in_maps">Open in Maps</string>
<string name="map_marker_layout">Layout</string>
<string name="map_marker_size">Size</string>
<string name="map_marker_access">Access</string>
<string name="map_marker_adjustability">Adjustability</string>
<string name="map_marker_frame">Frame</string>
<string name="map_marker_field_unknown">—</string>
<string name="map_attribution_prefix">Map data</string>

<!-- Access enum display -->
<string name="map_access_public">Public</string>
<string name="map_access_private">Private</string>
<string name="map_access_members">Members / Reservations</string>
<string name="map_access_unknown">Unknown</string>

<!-- Adjustability enum display -->
<string name="map_adjustability_fixed">Fixed</string>
<string name="map_adjustability_fixed_angle">Fixed (%1$d°)</string>
<string name="map_adjustability_adjustable">Adjustable</string>
<string name="map_adjustability_limited">Limited</string>
<string name="map_adjustability_full">Fully adjustable</string>
<string name="map_adjustability_unknown">Unknown</string>
```

`androidApp/src/main/res/values-de/strings.xml`:

```xml
<string name="map_screen_title">Karte</string>
<string name="cd_map">Kilter Board auf der Karte finden</string>
<string name="map_filter_public_only">Nur öffentliche</string>
<string name="map_filter_matches_my_board">Passt zu meinem Board</string>
<string name="map_filter_match_disabled">Konfiguriere zuerst dein Board</string>
<string name="map_no_data">Standortdaten nicht verfügbar — synchronisiere zuerst die Board-Datenbank</string>
<string name="map_no_data_action">Jetzt synchronisieren</string>
<string name="map_offline_dialog_title">Keine Internetverbindung</string>
<string name="map_offline_dialog_body">Die Karte benötigt beim ersten Öffnen eine Internetverbindung. Bereits angesehene Bereiche bleiben anschließend offline verfügbar.\n\nVerbinde dich mit WLAN oder mobilen Daten und versuche es erneut.</string>
<string name="map_offline_dialog_close">Schließen</string>
<string name="map_marker_browse_climbs">Climbs für dieses Board ansehen</string>
<string name="map_marker_open_in_maps">In Karten-App öffnen</string>
<string name="map_marker_layout">Layout</string>
<string name="map_marker_size">Größe</string>
<string name="map_marker_access">Zugang</string>
<string name="map_marker_adjustability">Verstellbarkeit</string>
<string name="map_marker_frame">Rahmen</string>
<string name="map_marker_field_unknown">—</string>
<string name="map_attribution_prefix">Kartendaten</string>

<string name="map_access_public">Öffentlich</string>
<string name="map_access_private">Privat</string>
<string name="map_access_members">Mitglieder / Reservierung</string>
<string name="map_access_unknown">Unbekannt</string>

<string name="map_adjustability_fixed">Fest</string>
<string name="map_adjustability_fixed_angle">Fest (%1$d°)</string>
<string name="map_adjustability_adjustable">Verstellbar</string>
<string name="map_adjustability_limited">Eingeschränkt</string>
<string name="map_adjustability_full">Voll verstellbar</string>
<string name="map_adjustability_unknown">Unbekannt</string>
```

(`values-en/strings.xml` is locale-detection marker only — never edited per
CONTRIBUTING.md.)

---

## 10. Privacy

### 10.1 No Location Permission

This feature does **not** request `ACCESS_FINE_LOCATION` /
`ACCESS_COARSE_LOCATION`. No GPS, no on-device location lookup, no
background scanning. "Locate me" is explicitly out of scope for v1.

### 10.2 Tile Requests

- Tiles fetched from `tiles.openfreemap.org` reveal the user's IP and
  approximate viewing region to OpenFreeMap's CDN (Cloudflare).
- App's privacy section text addition: "Map tiles are loaded from
  OpenFreeMap. Your IP address and the map regions you view are visible to
  OpenFreeMap and its CDN provider."
- No CruxCoach-side analytics.

### 10.3 Marker Data

- Stored locally in the board database; viewing markers makes no network
  call beyond tiles.
- Tap-to-call/email/website are explicit user actions; standard intents.

---

## 11. Permissions Manifest

No new permissions. The existing `INTERNET` and `ACCESS_NETWORK_STATE` (used
by Blossom sync, Nostr, BLE config) cover what MapLibre needs.

---

## 12. Classes & Packages

```
com.cruxcoach.android.ui.map/
  MapScreen.kt                  -- Compose, top-level screen
  MapViewModel.kt               -- @HiltViewModel; viewport, filters, snackbar
  MapView.kt                    -- AndroidView wrapper around MapLibre MapView
  MapMarkerLayer.kt             -- GeoJSON source + symbol layer + clusters
  BoardLocationDetailSheet.kt   -- ModalBottomSheet
  OfflineMapDialog.kt
  MapStyleProvider.kt           -- light/dark style URL switcher

com.cruxcoach.data.repository/  (in shared/)
  BoardLocationRepository.kt
  BoardLocationRepositoryImpl.kt

shared/src/commonMain/sqldelight/board/com/cruxcoach/db/board/
  AuroraBoard.sq                -- ADD kilter_board_location table + queries

androidApp/src/main/java/com/cruxcoach/android/ui/board/
  BoardBrowserScreen.kt         -- ADD third icon (Icons.Outlined.Map) in
                                   search-bar row at line ~447
```

Hilt module: `BoardLocationModule` provides `BoardLocationRepository`
as `@Singleton`.

---

## 13. Dependencies (new)

| Dependency | Version | License | Reason |
|---|---|---|---|
| `org.maplibre.gl:android-sdk` | `11.x` (latest stable) | BSD-2 | Map rendering |
| `org.maplibre.gl:android-plugin-annotation-v9` | `3.x` | BSD-2 | Higher-level marker/cluster API |

No new transitive deps from the StoreRocket side — that work happens in the
server-side cron, not the app.

APK size impact: ~5–8 MB native libs across ABIs.

---

## 14. Test Plan

### 14.1 Unit Tests

- `BoardLocationRepository` — `getMatchingBoard()` correctly filters by
  layout + size; `getAll()` returns table contents; empty-table case
- `MapViewModel` — filter chip state round-trip; viewport persistence;
  empty-data snackbar trigger
- `kilter_board_location` schema — migration applies cleanly on existing
  installs
- Cron normalization (covered server-side, not in this repo's test suite)

### 14.2 Instrumented Tests

- Map screen renders without crashing
- Tap marker → detail sheet shows correct fields
- Tap "Open in Maps" → intent fires with correct geo URI
- Tap "Browse Climbs" → Board Browser opens with layout filter (when
  `layout_id` known); button absent when `layout_id` NULL
- Filter chips toggle marker visibility correctly
- "Matches my board" chip is disabled when no board configured
- Map icon in search-bar row navigates to Map screen

### 14.3 Manual Test Checklist (Pre-Release)

- [ ] Open Map first time online: tiles load, markers appear, world view
- [ ] Pan to Germany → cluster expands as zoom increases
- [ ] Tap marker → detail sheet shows correct fields including `—` for
      missing phone/email
- [ ] Tap "Open in Maps" → external map app opens at correct location
- [ ] Tap "Browse Climbs" (where present) → Board Browser opens with layout
      pre-selected
- [ ] Toggle "Public only" → private/members markers hide
- [ ] Toggle "Matches my board" → only locations with same layout/size show
- [ ] Configure no board → "Matches my board" chip disabled with tooltip
- [ ] Toggle airplane mode mid-session, pan to a new region → blank tiles,
      no crash
- [ ] Fresh install + airplane mode + already-synced board DB → markers
      show, tiles blank, dialog appears
- [ ] System dark mode toggle → map style switches between Liberty and
      Positron
- [ ] Confirm BlossomSyncManager picks up new `locations` chunk type
      without modification (it ignores unknown types; v0.1.5 adds the
      handler)

---

## 15. Rollout

- No feature flag — feature is self-contained
- No data migration — additive table only
- Direct in release build; no debug-only gating (project ships only release
  APKs locally; debug builds are dev-only and not user-facing)
- Cron change deploys **before** the v0.1.5 client release so the
  `locations` chunk is already in the manifest by the time clients update.
  v0.1.5 clients see the new chunk on their next sync; older clients
  ignore it.

---

## 16. Open Questions

None. All design decisions resolved. Implementation may begin.

---

## 17. Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| StoreRocket endpoint changes shape | medium | medium | Cron sanity check (≥800 rows); failure keeps last good chunk in manifest |
| Kilter migrates locator off StoreRocket | low | high | Re-evaluate sources; consider PowerSync `global_gyms[]` if PowerSync ever lands |
| OpenFreeMap shuts down / changes terms | low | medium | Provider-agnostic style URL; can swap |
| MapLibre native crash on edge devices | low | medium | Existing crash reporting surfaces it |
| Layout-name → ID mapping drift (Kilter adds new product) | medium | low | Cron preserves verbatim `layout_name` and `size_label` even when ID is NULL; UI degrades gracefully |
| `kilter_board_location` schema migration fails on upgrade | low | high | Standard SQLDelight migration test; add to migration test matrix |
| Blossom cron fails for >14 days | low | medium | Alert via existing cron monitoring; manual intervention |
| User expects "near me" feature | medium | low | Document as deferred in changelog/FAQ; revisit in a future release |

---

## 18. Implementation Phases

Suggested ordering for solo work:

1. **Server-side cron extension** (1 day, separate codebase)
   - Add `build_locations_chunk()` to existing daily script
   - Verify size mappings against current board DB
   - Deploy + verify manifest includes `locations` chunk
2. **SQLite schema + repository** (½ day)
   - Add `kilter_board_location` table to `AuroraBoard.sq`
   - Migration test
   - `BoardLocationRepository` + impl + Hilt wiring
3. **BlossomSync handler for locations chunk** (½ day)
   - Extend `BoardDatabaseImporter` to ingest `locations` chunk
   - Test against fixture chunk
4. **Map renderer skeleton** (1 day)
   - MapLibre dependency, `MapView` AndroidView wrapper
   - OpenFreeMap style URL, light/dark switching
   - Empty map renders in placeholder screen
5. **Markers + clusters + public/private styling** (1 day)
   - GeoJSON source from repository
   - Symbol layer with orange/grey distinction
   - Cluster config
6. **Filter chips** (½ day)
   - Public-only + Matches-my-board chips
   - State persistence in `UserPreferences`
   - Disabled-state for unconfigured board
7. **Detail sheet + intents** (1 day)
   - Bottom sheet with all rows + `—` placeholders
   - Dial / email / website / "Open in Maps" / "Browse Climbs" intents
8. **Offline detection + dialog** (½ day)
9. **Search-bar icon integration** (½ day)
   - Add third `IconButton` in `BoardBrowserScreen.kt:405-449`
   - Navigation wiring
10. **Localization + manual test pass** (½ day)

**Estimated total: ~6–7 working days** for a single contributor, plus ~1
day server-side.
