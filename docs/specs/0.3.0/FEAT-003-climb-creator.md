# Feature Spec: Climb Creator & Nostr Community Climbs (v0.3.0)

> **Status:** Draft — design complete, pending engineering review before implementation.
> **Depends on:** Kilter API re-integration (§12) — independent of v0.2.0 specs.

## 1. Overview

CruxCoach users can browse 85k+ Kilter Board climbs but cannot create their own.
This feature adds a Climb Creator with live BLE LED preview, Nostr publishing for
decentralized community climbs, and dual-publishing to the official Kilter
database.

### Goals

- Create Kilter Board climbs by tapping holds on the board visualization
- Publish climbs to Nostr (Kind 30078) as community content
- Dual-publish to official Kilter DB (via user's own account or CruxCoach bridge)
- Browse and filter community climbs alongside official Kilter climbs
- Decentralized grade consensus and quality ratings via Nostr
- Full offline support (create without connectivity, sync later)

### Non-Goals

- Multi-board support (Tension, Moonboard etc.) — Kilter only
- Route creation (multi-frame) — boulders only for v0.3.0
- Climb video/beta recording
- Real-time collaborative editing

---

## 2. Kilter Board Data Format

### 2.1 Frames String

Climbs are encoded as concatenated placement-role pairs:

```
p{placement_id}r{role_id}p{placement_id}r{role_id}...
```

Example — "Narasaki Bounce":
```
p1083r15p1117r15p1164r12p1185r12p1233r13p1282r13p1303r13p1372r13p1392r14p1505r15
```

### 2.2 Hold Roles

| role_id | Name | Color | Hex | BLE byte |
|---------|------|-------|-----|----------|
| 12 | Start | Green | `#00FF00` | `0x1C` |
| 13 | Hand/Middle | Cyan | `#00FFFF` | `0x1F` |
| 14 | Finish | Magenta | `#FF00FF` | `0xE3` |
| 15 | Foot Only | Orange | `#FFB600` | `0xF4` |

Route-specific roles (42-45) exist but are out of scope for v0.3.0 (boulders
only).

### 2.3 Placement Resolution Chain

```
frames string
  → placement_id (via regex p(\d+)r(\d+))
  → hole_id (via aurora_placement table, scoped by layout_id + set_id)
  → (x, y) coordinates (via aurora_hole table)
  → LED position (via aurora_led table, scoped by product_size_id)
  → BLE packet (3 bytes per hold: position_low, position_high, color_8bit)
```

Existing code: `BoardClimbParser.parseFrames()`, `BoardClimbParser.encodeFrames()`,
`AuroraPacketEncoder.encodeClimbFromHolds()`.

### 2.4 New Kilter API Format

The new Kilter API (post-Aurora, March 2026) uses a different format:

```
h{holdPlacementId}p{placementTypeRef}
```

Example: `h1461p12h1575p13h1636p14`

Role IDs remain identical (12-15). A mapping of 692 placement IDs between
Aurora and Kilter formats exists at `~/kilter-re/analysis/placement_mapping.json`.

CruxCoach already supports both formats in `BoardClimbParser`:
- Aurora: `AURORA_PATTERN = Regex("p(\d+)r(\d+)")`
- Kilter: `KILTER_PATTERN = Regex("h(\d+)p(\d+)")`

### 2.5 Validation Rules

A valid Kilter boulder must satisfy:

| Rule | Constraint |
|------|-----------|
| Start holds (r12) | Exactly 1 or 2 |
| Finish hold (r14) | At least 1 |
| Hand holds (r13) | 0 or more |
| Foot holds (r15) | 0 or more (optional) |
| Total holds | Minimum 2 (start + finish) |
| Layout | All placement_ids valid for chosen layout_id |
| Board bounds | All holds within product_size edge boundaries |

### 2.6 Grade System

Numeric scale (10-34) mapped to V-Scale and Font:

| ID | Font | V-Scale | ID | Font | V-Scale |
|----|------|---------|-----|------|---------|
| 10 | 4a | VB- | 22 | 7A | V6 |
| 13 | 5a | V0 | 24 | 7B | V8 |
| 16 | 6A | V2 | 27 | 7C+ | V10 |
| 19 | 6B+ | V4 | 30 | 8B | V13 |
| 20 | 6C | V5 | 34 | 9A | V17 |

`difficulty_average` is a float (e.g. 20.35). Display grade = round to nearest
discrete value. Existing code: `KilterGradeMapper.difficultyToVScale()`.

---

## 3. Climb Creator UI

### 3.1 Reusable Infrastructure

`KilterBoardVisualization.kt` (340 lines) already provides:
- Canvas-based board rendering with Image background
- Hit-testing via nearest-neighbor (proportional tap radius)
- Tap + Long-Press + Drag gesture handling
- Role-based hold coloring via `holdColorForRole()`
- Coordinate transformation (board space → screen space)

The Climb Creator reuses this component with modifications for role cycling and
zoom/pan support.

### 3.2 Interaction Model

**Tap on hold** → Cycle through roles:

```
Not selected → Start (green) → Hand (cyan) → Foot (orange) → Finish (magenta) → Not selected
```

This matches the interaction pattern of the official Kilter app and all major
board apps.

```kotlin
fun cycleHoldRole(currentRole: HoldRole?): HoldRole? = when (currentRole) {
    null -> HoldRole.START
    HoldRole.START -> HoldRole.HAND
    HoldRole.HAND -> HoldRole.FOOT
    HoldRole.FOOT -> HoldRole.FINISH
    HoldRole.FINISH -> null
}
```

### 3.3 Zoom and Pan

Not currently implemented in `KilterBoardVisualization`. Must be added for
the Creator (200 holds on a phone screen need zoom for precision):

```kotlin
Canvas(
    modifier = Modifier
        .pointerInput(Unit) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                val oldScale = scale
                scale = (scale * zoom).coerceIn(1f, 5f)
                // Adjust offset to zoom toward centroid
                offset = offset + (centroid / oldScale - centroid / scale)
                offset -= pan / scale
            }
        }
        .pointerInput(holds) {
            detectTapGestures { tap ->
                val canvasPos = screenToCanvas(tap, scale, offset)
                findNearestHold(canvasPos, holds, 24.dp.toPx() / scale)
                    ?.let { onHoldTapped(it.placementId) }
            }
        }
        .graphicsLayer {
            scaleX = scale; scaleY = scale
            translationX = -offset.x * scale
            translationY = -offset.y * scale
            transformOrigin = TransformOrigin(0f, 0f)
        }
)
```

### 3.4 Undo/Redo

Immutable state stack in the ViewModel. State is small (~160 bytes per snapshot),
50 snapshots = ~8 KB.

```kotlin
data class ClimbEditorState(
    val selectedHolds: Map<Int, HoldRole> = emptyMap(),
    val name: String = "",
    val description: String = "",
    val setterGrade: Int? = null,
    val angle: Int? = null
)

class ClimbEditorViewModel : ViewModel() {
    private val _state = MutableStateFlow(ClimbEditorState())
    val state = _state.asStateFlow()

    private val undoStack = mutableListOf<ClimbEditorState>()
    private val redoStack = mutableListOf<ClimbEditorState>()

    fun toggleHold(placementId: Int) {
        val cur = _state.value
        val currentRole = cur.selectedHolds[placementId]
        val nextRole = cycleHoldRole(currentRole)
        val newHolds = if (nextRole != null) {
            cur.selectedHolds + (placementId to nextRole)
        } else {
            cur.selectedHolds - placementId
        }
        push(cur.copy(selectedHolds = newHolds))
    }

    fun undo() { /* pop from undoStack, push to redoStack */ }
    fun redo() { /* pop from redoStack, push to undoStack */ }
}
```

### 3.5 BLE Live LED Preview

When connected to a Kilter Board, hold changes are sent in real-time:

```kotlin
fun sendLedPreview(
    holds: Map<Int, HoldRole>,
    placementToLed: Map<Int, Int>,
    bleConnection: AuroraBleConnection
) {
    val ledData = holds.mapNotNull { (placementId, role) ->
        val ledPosition = placementToLed[placementId] ?: return@mapNotNull null
        Triple(ledPosition, ledPosition, role.bleColor)
    }
    bleConnection.setLeds(ledData)
}
```

Existing code: `AuroraPacketEncoder` handles the 3-byte-per-hold BLE protocol.
Preview works without any cloud API — pure local BLE.

### 3.6 Screen Flow

```
Board Browser → [+] FAB → Climb Editor
                              │
                              ├── Board visualization (interactive)
                              ├── Hold count indicators (starts: 2, hands: 5, ...)
                              ├── Undo / Redo buttons
                              ├── Validation status (✅ valid / ❌ missing finish)
                              │
                              └── [Weiter] → Metadata Screen
                                               │
                                               ├── Name (required)
                                               ├── Description (optional)
                                               ├── Grade suggestion (slider, Aurora scale)
                                               ├── Angle (dropdown, from products_angles)
                                               │
                                               └── [Veröffentlichen] → Publish Dialog
                                                                        │
                                                                        ├── ✅ Auf Nostr (als "Dein Name")
                                                                        ├── ☐ Auf Kilter
                                                                        │   ├── als "MaxM" (eigener Account)
                                                                        │   └── oder automatisch via CruxCoach
                                                                        │
                                                                        └── [Veröffentlichen]
```

---

## 4. Nostr Event Architecture

### 4.1 Climb Event

```json
{
  "kind": 30078,
  "pubkey": "<setter-pubkey>",
  "created_at": 1714000000,
  "tags": [
    ["d", "cruxcoach:climb:<pubkey-prefix-8>:<uuid>"],
    ["L", "com.cruxcoach.climb"],
    ["l", "climb", "com.cruxcoach.climb"],
    ["l", "kilterboard-og", "com.cruxcoach.board"],
    ["l", "12x12", "com.cruxcoach.size"],
    ["frames", "p1164r12p1185r12p1233r13p1282r13p1392r14"],
    ["frames_hash", "sha256:a3f2b8c..."],
    ["layout_id", "1"],
    ["setter_grade", "22", "40"],
    ["t", "kilterboard"],
    ["t", "climbing"]
  ],
  "content": "{\"name\":\"Midnight Lightning\",\"description\":\"Power endurance at 40 degrees\",\"_v\":1}"
}
```

### 4.2 Design Decisions

**d-tag format:** `cruxcoach:climb:<pubkey-prefix-8>:<uuid>` — namespace prevents
collisions, pubkey prefix adds uniqueness, UUID is the canonical identifier.

**Angle is NOT in the climb event.** The climb defines holds (frames). The angle
is a property of an ascent — the same climb can be done at 40° or 50° with
different grades. This mirrors Aurora's `climb` + `climb_stat` architecture.

**frames in tags AND content:** The `["frames", "..."]` tag allows relay-side
filtering by frames_hash. The full frames string is also in the content for
clients that need it without tag parsing.

**frames_hash:** SHA-256 of the normalized frames string (sorted by placement_id,
with layout_id prefix). Used for duplicate detection.

### 4.3 frames_hash Algorithm

```kotlin
fun computeFramesHash(frames: String, layoutId: Int): String {
    val holds = Regex("p(\\d+)r(\\d+)").findAll(frames)
        .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        .sortedBy { it.first }
        .toList()
    val canonical = holds.joinToString("") { (p, r) -> "p${p}r${r}" }
    val input = "layout:${layoutId}:${canonical}"
    return MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
```

**layout_id in hash: YES** — same placement_ids on different layouts = different
physical climbs. **Angle NOT in hash** — same climb at different angles is the
same climb.

### 4.4 Ascent Event (Opt-in Public)

```json
{
  "kind": 30078,
  "pubkey": "<climber-pubkey>",
  "tags": [
    ["d", "cruxcoach:ascent:<climb-d-tag-ref>:<angle>"],
    ["a", "30078:<setter-pubkey>:cruxcoach:climb:<pk>:<uuid>", "<relay-hint>"],
    ["L", "com.cruxcoach.ascent"],
    ["l", "ascent", "com.cruxcoach.ascent"],
    ["angle", "40"],
    ["grade_vote", "22"],
    ["quality", "4"],
    ["p", "<setter-pubkey>"]
  ],
  "content": "{\"attempts\":3,\"style\":\"redpoint\",\"comment\":\"Crux move tricky\",\"date\":\"2026-04-15\"}"
}
```

One event per user+climb+angle (replaceable via d-tag). The `a`-tag references
the climb event for efficient relay queries.

**Privacy:** Public ascents are opt-in. Private ascents use the backup system
(FEAT-002) and never contribute to community statistics.

### 4.5 Multi-Angle Handling

The climb event has no angle. The setter provides grade hints per angle via
multiple `setter_grade` tags:

```json
["setter_grade", "22", "40"],
["setter_grade", "24", "50"]
```

Grade consensus is computed client-side per angle from ascent events. This
exactly mirrors Aurora's `climb` (angle-free) + `climb_stat` (per-angle)
architecture.

### 4.6 Discovery via Relay Queries

```kotlin
// All CruxCoach community climbs
val filter = Filter(
    kinds = listOf(30078),
    tags = mapOf("L" to listOf("com.cruxcoach.climb"))
)

// All climbs by a specific setter
val filter = Filter(
    kinds = listOf(30078),
    authors = listOf(setterPubkey),
    tags = mapOf("L" to listOf("com.cruxcoach.climb"))
)

// All ascents for a specific climb
val filter = Filter(
    kinds = listOf(30078),
    tags = mapOf(
        "L" to listOf("com.cruxcoach.ascent"),
        "a" to listOf("30078:$setterPubkey:$climbDTag")
    )
)
```

---

## 5. Dual-Publishing Architecture

### 5.1 Three Publishing Channels

```
User erstellt Climb
│
├─→ Lokale SQLite DB (immer, sofort)
│
├─→ Nostr Kind 30078 (immer, wenn online — signiert vom User)
│
├─→ Kilter API (optional, zwei Wege):
│   │
│   ├─ User hat Kilter-Account verknüpft
│   │   └─→ Direkter Upload unter User's Kilter-Account
│   │       → App setzt ["kilter_uuid", "..."] Tag auf Nostr-Event
│   │
│   └─ Kein Kilter-Account
│       └─→ CruxCoach Bridge Cron-Job uploaded später (siehe 5.3)
```

### 5.2 Direct Kilter Upload (User's Own Account)

When the user has linked their Kilter account (Keycloak OIDC via
`idp.kiltergrips.com/realms/kilter`):

```kotlin
suspend fun uploadToKilter(climb: LocalClimb, token: String): String {
    // Convert Aurora frames → Kilter format
    val kilterFrames = convertFrames(climb.frames, placementMapping)

    val response = kilterApi.post("/api/climbs/create-climb/transaction") {
        header("Authorization", "Bearer $token")
        json(mapOf(
            "name" to climb.name,
            "climbConcat" to kilterFrames,
            "angle" to climb.angle,
            "layoutUuid" to KILTER_LAYOUT_UUID,
            "description" to climb.description,
            "isDraft" to false
        ))
    }

    val kilterUuid = response.body<KilterClimbResponse>().uuid

    // Update Nostr event with kilter_uuid tag
    updateNostrEventWithKilterUuid(climb.nostrDTag, kilterUuid)

    return kilterUuid
}
```

Auth uses Keycloak with `offline_access` scope for long-lived refresh tokens.
Password grant allows headless auth without browser redirect.

### 5.3 CruxCoach Bridge Cron Job

A server-side cron job (Python, like the donations tracker) polls Nostr relays
for new community climbs and uploads them to Kilter under the CruxCoach account.

**Schedule:** Every 6 hours
**Location:** `~/cruxcoach-kilter-bridge/`
**Pattern:** Identical to `~/cruxcoach-donations/` (Python + cron + git state)

#### Flow

```python
async def sync_climbs_to_kilter():
    # 1. Poll Nostr for new climb events (0 Kilter API calls)
    new_events = nostr_fetch(
        kind=30078,
        filter={"#L": ["com.cruxcoach.climb"]},
        since=state.last_sync
    )

    # 2. Filter — all checks local (0 Kilter API calls)
    to_upload = []
    for event in new_events:
        climb = parse_climb_event(event)

        # Younger than 48h → defer (wait for user to self-publish)
        if (now() - event.created_at) < 48 * 3600:
            continue

        # User already uploaded to Kilter
        if event.has_tag("kilter_uuid"):
            state_db.mark(event.id, "skipped_user_uploaded")
            continue

        # Bridge already uploaded
        if state_db.exists_by_frames_hash(climb.frames_hash):
            continue

        # Already on Kilter (check local Blossom DB copy)
        if board_db.has_frames_hash(climb.frames_hash, climb.layout_id):
            state_db.mark(event.id, "exists_on_kilter")
            continue

        to_upload.append((event, climb))

    if not to_upload:
        return

    # 3. Auth — 0-1 Kilter API call (cached offline_access token)
    token = get_or_refresh_token()

    # 4. Upload — 1 call per climb, rate-limited
    for event, climb in to_upload[:MAX_UPLOADS_PER_RUN]:
        kilter_frames = convert_frames(climb.frames, PLACEMENT_MAPPING)
        response = kilter_api.create_climb(
            name=climb.name,
            climb_concat=kilter_frames,
            angle=climb.angle,
            description=f"Created by {shorten_npub(event.pubkey)} via CruxCoach",
            token=token
        )
        kilter_uuid = response["uuid"]

        # Track state
        state_db.insert(event.id, climb.frames_hash, kilter_uuid, "uploaded")

        # Publish bridge event on Nostr
        publish_bridge_event(event, kilter_uuid)

        # Rate limit: 10s between uploads
        await asyncio.sleep(10)
```

#### Duplicate Prevention — Decision Tree

```
Neuer Climb auf Nostr
│
├─ Jünger als 48h?
│   └─ JA → DEFER (User hat Zeit selbst nach Kilter zu pushen)
│
├─ Hat "kilter_uuid" Tag? (User hat selbst gepusht)
│   └─ JA → SKIP
│
├─ frames_hash in Bridge State-DB? (Wir haben schon gepusht)
│   └─ JA → SKIP
│
├─ frames_hash in Board-DB? (Existiert bereits auf Kilter, Blossom-Kopie)
│   └─ JA → SKIP
│
└─ Kein Duplikat → UPLOAD als CruxCoach-Account
```

The 48h delay is the critical safeguard: it ensures the Blossom DB (updated
daily) has synced, AND gives the user time to upload via their own account and
update the Nostr event with the kilter_uuid tag.

#### Bridge Event (Audit Trail)

The cron publishes a separate Kind 30078 event as proof of the bridge action:

```json
{
  "kind": 30078,
  "pubkey": "<cruxcoach-bot-pubkey>",
  "tags": [
    ["d", "cruxcoach:bridge:<nostr-event-id>"],
    ["L", "com.cruxcoach"],
    ["l", "bridge", "com.cruxcoach"],
    ["a", "30078:<original-author>:<original-d-tag>"],
    ["kilter_uuid", "<kilter-uuid>"]
  ],
  "content": "{\"status\":\"uploaded\",\"uploaded_at\":\"2026-04-15T12:00:00Z\"}"
}
```

The app can query bridge events to show: "This climb was uploaded to Kilter
by CruxCoach" with the kilter_uuid.

#### Token Strategy

Keycloak `offline_access` scope provides a refresh token that stays valid as
long as it is used every 30 days. Token persisted in `.token.json` on the server.

```
Typical day (5 new community climbs):
  Nostr polling:     0 Kilter API calls
  Duplicate check:   0 Kilter API calls (local DB)
  Token:             0-1 calls (cached, 4h access token)
  Uploads:           5 calls
  ─────────────────────────────────────────
  Total:             5-6 Kilter API calls/day

Day with no new climbs:
  Total:             0 Kilter API calls/day
```

#### Rate Limiting

```python
MAX_UPLOADS_PER_RUN = 10        # Max 10 climbs per 6h cycle
DELAY_BETWEEN_UPLOADS = 10      # 10 seconds between uploads
MIN_AGE_HOURS = 48              # Don't upload climbs younger than 48h
```

Worst case: 4 runs × 10 uploads = 40 climbs + 1 token refresh = 41 API
calls/day. Less than a single user browsing the app.

#### State Database

```sql
CREATE TABLE bridge_state (
    nostr_event_id   TEXT PRIMARY KEY,
    nostr_d_tag      TEXT NOT NULL,
    author_pubkey    TEXT NOT NULL,
    frames_hash      TEXT NOT NULL,
    kilter_uuid      TEXT,
    status           TEXT NOT NULL,  -- pending, uploaded, skipped_*
    created_at       TEXT NOT NULL,
    uploaded_at      TEXT
);

CREATE INDEX idx_frames_hash ON bridge_state(frames_hash);
```

---

## 6. Community Browse & Discovery

### 6.1 UI Integration

Community climbs are integrated into the existing browse flow as a source
filter, not a separate tab:

```
Board Browser
├── Filter Bar
│   ├── Angle: [40°]
│   ├── Grade: [V3 – V8]
│   ├── Source: [Alle ▼]  ←── NEW
│   │   ├── Alle
│   │   ├── Kilter Official
│   │   └── CruxCoach Community
│   └── ...
└── Climb List
    ├── 🟢 Midnight Lightning V6 (CruxCoach) ←── Badge
    ├── Generation V5
    ├── 🟢 Nebula V4 (CruxCoach)
    └── ...
```

### 6.2 Community Badge

Climbs with `source = 'nostr'` get a subtle "CruxCoach" badge in the list and
detail views. The badge links to the setter's Nostr profile (Kind 0 metadata:
name, picture).

### 6.3 Setter Profile

```
Setter: @npub1abc... (Max M.)
├── Profile picture (from Kind 0)
├── Climbs created: 15
├── Average quality: ⭐ 4.2
├── [Folgen] (NIP-02 Contact List)
└── Alle Climbs von diesem Setter →
```

### 6.4 Sorting for Community Climbs

Community climbs initially have few ascents. Sorting options:

| Sort | Mechanism |
|------|-----------|
| Neueste | `created_at` descending |
| Von Leuten die du folgst | Filter by NIP-02 follow list |
| Beliebteste | Ascent count (when available) |
| Beste Bewertung | Wilson score interval (handles low vote counts) |

### 6.5 Subscription Management

On app start: subscribe to new climb events since last sync timestamp.
Batch-download, validate, store in local DB. Re-subscribe periodically via
WorkManager (every 6h).

```kotlin
suspend fun syncCommunityClimbs() {
    val since = prefs.lastCommunitySyncTimestamp
    val events = relayPool.queryEvents(
        kind = 30078,
        tags = mapOf("L" to listOf("com.cruxcoach.climb")),
        since = since
    )
    events.forEach { event ->
        val climb = parseClimbEvent(event)
        if (climb.isValid()) {
            boardDb.upsertCommunityClimb(climb)
        }
    }
    prefs.lastCommunitySyncTimestamp = now()
}
```

---

## 7. Grade Consensus

### 7.1 Computation

Client-side aggregation of grade votes from ascent events. Trimmed mean (10%)
reduces outlier impact compared to Aurora's simple average.

```kotlin
fun calculateConsensus(
    setterGrade: Int,
    votes: List<GradeVote>
): ConsensusGrade {
    val effective = votes
        .filter { it.isSend }
        .groupBy { it.pubkey }
        .mapValues { (_, v) -> v.maxBy { it.timestamp } }
        .values.map { it.gradeId }

    val all = listOf(setterGrade) + effective
    val sorted = all.sorted()
    val trim = (sorted.size * 0.1).toInt()
    val trimmed = if (sorted.size > 5)
        sorted.subList(trim, sorted.size - trim) else sorted
    val avg = trimmed.average()

    return ConsensusGrade(
        average = avg,
        roundedId = avg.roundToInt(),
        voteCount = effective.size,
        confidence = when {
            effective.size < 3 -> Confidence.LOW
            effective.size < 10 -> Confidence.MEDIUM
            else -> Confidence.HIGH
        }
    )
}
```

### 7.2 Display

```
Grade: V6 (7A)          ← consensus (or setter grade if < 3 votes)
  Community: V5.8 ±0.4  ← shown when confidence >= MEDIUM
  12 Bewertungen         ← vote count
  Dein Vorschlag: V5     ← user's own vote (if ascent logged)
```

### 7.3 Sybil Protection

Web-of-Trust filtering: only count grade votes from users within 2 hops of the
viewer's NIP-02 follow graph. Users with 0 connections have weight 0.

For v0.3.0, start with simple deduplication (one vote per pubkey) and add WoT
weighting in a later release.

---

## 8. Moderation & Spam

### 8.1 Client-Side Validation

Before publishing, validate:
- Minimum 2 holds (start + finish)
- At least 1 start hold (r12) and 1 finish hold (r14)
- All placement_ids valid for the selected layout
- Name is non-empty, max 100 characters
- Description max 500 characters

### 8.2 Duplicate Detection

On publish, compute frames_hash and check against:
1. Local board DB (85k+ Kilter climbs)
2. Local community climb cache
3. Cron bridge state (if accessible)

If duplicate found: warn user, allow override (same holds, different name/
description is a valid use case).

### 8.3 Spam Prevention

- NIP-13 Proof-of-Work on the CruxCoach relay (minimum difficulty)
- Rate limiting: max 10 climbs per pubkey per day on the CruxCoach relay
- WoT gating: events from pubkeys with WoT score 0 are deprioritized

### 8.4 Reporting

NIP-56 (Kind 1984) reporting for offensive content. Reports are weighted by
WoT — automated action only when multiple trusted reporters flag the same climb.

---

## 9. Schema Changes

### 9.1 Board Database Extensions (SQLDelight)

```sql
-- Add community climb fields to aurora_climb
ALTER TABLE aurora_climb ADD COLUMN source TEXT NOT NULL DEFAULT 'kilter';
  -- Values: 'kilter', 'nostr', 'local'

ALTER TABLE aurora_climb ADD COLUMN nostr_event_id TEXT;
ALTER TABLE aurora_climb ADD COLUMN nostr_d_tag TEXT;
ALTER TABLE aurora_climb ADD COLUMN created_by_pubkey TEXT;
ALTER TABLE aurora_climb ADD COLUMN frames_hash TEXT;
ALTER TABLE aurora_climb ADD COLUMN sync_status TEXT NOT NULL DEFAULT 'synced';
  -- Values: 'draft', 'published_nostr', 'published_kilter', 'published_both'

ALTER TABLE aurora_climb ADD COLUMN kilter_uuid_new TEXT;
  -- Kilter UUID if uploaded to new Kilter API (separate from legacy uuid)

CREATE INDEX idx_climb_source ON aurora_climb(source);
CREATE INDEX idx_climb_frames_hash ON aurora_climb(frames_hash);
CREATE INDEX idx_climb_pubkey ON aurora_climb(created_by_pubkey);
```

### 9.2 Community Climb Grade Cache

```sql
CREATE TABLE community_grade_cache (
    climb_d_tag TEXT NOT NULL,
    angle INTEGER NOT NULL,
    setter_grade_id INTEGER,
    consensus_average REAL,
    consensus_grade_id INTEGER,
    vote_count INTEGER NOT NULL DEFAULT 0,
    confidence TEXT NOT NULL DEFAULT 'LOW',
    last_updated INTEGER NOT NULL,
    PRIMARY KEY (climb_d_tag, angle)
);
```

### 9.3 Nostr Profile Cache

Already exists as `NostrProfile` in the secure DB. Reuse for setter profiles.

---

## 10. Offline Support

### 10.1 Offline Creation

Climbs are created locally with `sync_status = 'draft'`. BLE LED preview works
offline (direct board communication). Nostr event signing can happen offline
(only needs private key, no network).

### 10.2 Publish Queue

```kotlin
class PublishQueue @Inject constructor(
    private val db: BoardDatabase,
    private val nostrClient: NostrClient,
    private val connectivityManager: ConnectivityManager
) {
    suspend fun processQueue() {
        val drafts = db.climbQueries.getDrafts().executeAsList()
        if (drafts.isEmpty()) return
        if (!connectivityManager.isConnected()) return

        for (draft in drafts) {
            try {
                val event = buildClimbEvent(draft)
                val signed = signer.sign(event)
                nostrClient.publish(signed)
                db.climbQueries.updateSyncStatus(draft.uuid, "published_nostr")
            } catch (e: Exception) {
                Timber.w(e, "Failed to publish ${draft.uuid}, will retry")
            }
        }
    }
}
```

Triggered by: network reconnect listener + WorkManager periodic job (6h).

---

## 11. Privacy

### 11.1 Climb Creation

Public by default (Nostr events are public). Draft climbs remain local until
explicitly published.

### 11.2 Ascent Publishing

**Opt-in only.** Default: ascents are private (stored in encrypted secure DB,
synced via backup system from FEAT-002). User can explicitly choose to publish
an ascent to Nostr for community statistics.

### 11.3 Kilter Account Linkage

If user uploads to Kilter via own account, the Kilter username is NOT stored in
the Nostr event. Only the Nostr pubkey identifies the setter. The kilter_uuid
tag links the two, but does not expose the Kilter username.

### 11.4 DSGVO

No Kilter usernames on Nostr events. Setter attribution exclusively via Nostr
pubkey (user has consciously opted into Nostr). CruxCoach should operate a relay
with strict NIP-09 (deletion) compliance.

---

## 12. Kilter API Reference

### 12.1 Authentication

CruxCoach uses OIDC Resource Owner Password Credentials (`grant_type=password`)
against the Kilter Keycloak IdP. PKCE / Authorization Code would be preferable
per OAuth 2.1 / RFC 9700 best practice, but the `kilter` client has a fixed
`redirect_uri` (`com.kiltergrips:/oauthredirect`) that we cannot override, and
Dynamic Client Registration is blocked by a `Trusted Hosts` policy on Kilter's
realm (probed 2026-04-17 via anonymous POST to
`/clients-registrations/openid-connect` — returned HTTP 403
`insufficient_scope — Policy 'Trusted Hosts' rejected request`). Device Code
grant is disabled on the `kilter` client specifically (though the realm
supports it). A PKCE migration is parked as a project task, contingent on
Northtech either adding a second `redirect_uri` to the existing `kilter`
client or provisioning a dedicated `cruxcoach` client.

```
POST https://idp.kiltergrips.com/realms/kilter/protocol/openid-connect/token

grant_type=password
client_id=kilter
username=<email>
password=<password>
scope=openid offline_access
```

The token endpoint returns both an `access_token` and a `refresh_token`. The
`refresh_token` is issued under the `offline_access` scope with a **30-day idle
timeout** and is the only credential that persists across app sessions. The
`access_token` is attached to each outgoing Kilter API request
(`Authorization: Bearer`) and renewed transparently by
`KilterApiClient.refreshAccessToken()` using the `refresh_token` — no user
interaction involved. The user only re-authenticates (enters their Kilter
password once in the CruxCoach login mask) when the refresh itself fails:
either because the `refresh_token` expired after 30 days idle, or because
Keycloak invalidated the session server-side.

**Security invariants (already implemented in `KilterTokenStore` /
`KilterApiClient`):**

- The plaintext password enters `KilterApiClient.authenticate(email, password)`
  as a function parameter only. It is sent once to Keycloak via `FormBody`,
  exchanged for `access_token + refresh_token`, and then goes out of scope.
  It is never written to any file, SharedPreferences, log, or in-memory cache.
- `access_token` and `refresh_token` are stored in `KilterTokenStore` via
  `EncryptedSharedPreferences` backed by the Android Keystore (MasterKey
  `AES256_GCM`, values encrypted `AES256_GCM`, keys encrypted `AES256_SIV`).
  The prefs file is scoped per Nostr identity (`kilter_secure_prefs_<pubkey-prefix>`),
  so multi-account / key-rotation flows keep token stores isolated.
- `openOrRecreatePrefs()` wraps the ESP open in a try/catch. On a thrown
  exception (Tink keyset corruption — known Samsung S24 / Android 14 bug)
  the corrupted prefs file is deleted and recreated. Worst case the user is
  asked to log in to Kilter again; the app does not crash-loop.
- Keycloak rotates the refresh token on every refresh. `KilterApiClient`
  propagates the new refresh token to `KilterTokenStore.updateRefreshToken()`
  inside a `refreshMutex`, so concurrent refreshes never race and a stale
  token never overwrites a fresh one.
- Password Grant cannot authenticate Kilter users who signed up via SSO
  (Google / Apple) — those accounts have no Kilter-local password. The
  account-linking UI must surface this limitation and point those users at
  Pfad 3 (`cruxcoach` client or extra `redirect_uri` negotiated with
  Northtech) as the only future route.

**Why ESP here, but *not* for the Nostr backup dataKey (see FEAT-002 §11.2):**
Kilter OAuth tokens are plaintext bearer credentials — ESP *is* the at-rest
encryption boundary for them. The FEAT-002 dataKey is already NIP-44 ciphertext
(self-protected inside the blob); wrapping it again in ESP would add only
redundant failure modes (Tink keyset bugs) without additional confidentiality.
Different threat model, different storage choice. Do not harmonize the two
paths under a single "SecureStorage" abstraction — the distinction is
load-bearing.

### 12.2 Climb Upload

```
POST https://portal.kiltergrips.com/api/climbs/create-climb/transaction
Authorization: Bearer <token>
Content-Type: application/json

{
  "name": "...",
  "climbConcat": "h1461p12h1575p13h1636p14",
  "angle": 40,
  "layoutUuid": "...",
  "description": "...",
  "isDraft": false
}
```

**Important:** Payload must use camelCase — snake_case fields are silently
ignored.

### 12.3 Log Upload

```
POST https://portal.kiltergrips.com/api/logs/bulk
Authorization: Bearer <token>

[{
  "logUuid": "UUID",
  "climbUuid": "UUID",
  "userUuid": "UUID",
  "gymUuid": "UUID",
  "wallUuid": "UUID",
  "productLayoutUuid": "UUID",
  "angle": 40,
  "flashed": false,
  "topped": true,
  "attempts": 5,
  "createdAt": "2026-04-07T12:00:00Z"
}]
```

New mandatory fields vs. Aurora: gymUuid, wallUuid, productLayoutUuid.
No difficulty/quality (use separate `climb-rating` endpoint).

### 12.4 Placement Mapping

692 mappings Aurora → Kilter at `~/kilter-re/analysis/placement_mapping.json`.
Format: `{"old_placement_id": "new_hold_placement_id", ...}`.

Conversion:
```kotlin
fun convertFrames(auroraFrames: String, mapping: Map<Int, Int>): String {
    return Regex("p(\\d+)r(\\d+)").findAll(auroraFrames)
        .map { match ->
            val oldId = match.groupValues[1].toInt()
            val roleId = match.groupValues[2].toInt()
            val newId = mapping[oldId] ?: error("Unknown placement $oldId")
            "h${newId}p${roleId}"
        }
        .joinToString("")
}
```

---

## 13. Dependencies

```kotlin
// No new dependencies required for v0.3.0 beyond what v0.2.0 adds.
// All required libraries are already in the project:
//
// - com.vitorpamplona.quartz:quartz-android (Nostr, NIP-44, signing)
// - androidx.work:work-runtime-ktx (background sync)
// - Jetpack Compose Canvas (board rendering)
// - OkHttp (Kilter API calls)
// - kotlinx-serialization (JSON)
```

---

## 14. Open Questions

- **Boardsesh API integration:** Use Boardsesh's public REST API as a third
  publishing channel? Compatible data format (same Aurora frames), Apache 2.0
  license. Contact (redacted) via Discord.
- **Climb editing:** Can a setter modify holds after publishing? Nostr
  replaceable events support this natively. But what about existing ascent
  events referencing the old version?
- **CruxCoach relay:** Operate a dedicated relay optimized for Kind 30078 events
  with com.cruxcoach labels? Cost ~$5/month for a VPS with strfry.
- **Difficulty grades table:** Extract the complete mapping from the Kilter
  PowerSync global bucket or from the Aurora SQLite DB for exact grade labels.
- **Colorblind mode:** Alternative hold color palette. The new Kilter app
  supports this — CruxCoach should too.
