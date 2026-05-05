---
status: design-locked
---
# Feature Spec: Kilter Own-Climb Import + Backup Extension (v0.2.0)

> **Status:** Design-locked 2026-05-05. Open Q1-Q13 from skeleton revision
> resolved (see §10). Implementation gated only on M1 Kilter API endpoint
> reverse-engineering spike.
>
> **Depends on:**
> - FEAT-003 (Climb Creator) — uses the same `CommunityClimbPublisher` to
>   push imported climbs as Nostr Kind-30078 events, the same draft / source
>   / sync_status state model, and the same `markClimbPublishedNostr` row
>   mutation.
> - Existing `KilterApiClient` Keycloak Password Grant flow — provides the
>   per-user Kilter-account authentication this spec relies on for filtering
>   "my climbs" out of the Kilter catalogue.
>
> **Blocks:** none. This is purely additive. Without it, users who set their
> own climbs in the Kilter app today have no path to: (a) republish them as
> CruxCoach (Nostr) climbs without retracing every hold, or (b) carry them
> across a device migration / app reinstall through the existing CruxCoach
> backup.

---

## 1. Overview

Two related gaps in 0.1.4:

1. **No way to import own Kilter-authored climbs into the CruxCoach world.**
   Users with a linked Kilter account can publish CruxCoach-created climbs
   *to* Kilter (FEAT-003 §5), but the reverse import — pulling climbs the
   user already authored on Kilter and re-labelling them as CruxCoach climbs
   so they show up under "Quelle: CruxCoach", get a Nostr-Kind-30078 mirror,
   and are deletable / editable through the standard CruxCoach flows — has
   no implementation. Today's only option is for the user to manually
   re-create the climb in the CruxCoach editor.

2. **The CruxCoach backup envelope ignores own climbs entirely.**
   `CruxCoachBackup.Backup` (`shared/src/commonMain/kotlin/com/cruxcoach/data/CruxCoachBackup.kt`)
   carries assessments, body stats, workout logs, climb logs, training plans,
   board ascents/bids/sessions, and climb lists. It does **not** carry the
   user's own authored climbs (`source IN ('local', 'nostr')` rows or any
   row with `created_by_pubkey == ownPubkey`). Drafts in particular are
   irrecoverable on device wipe — they have no Nostr trace, no Kilter
   mirror, nothing.

This spec covers both: an opt-in "Eigene Kilter-Climbs importieren" flow
in Settings → Kilter-Konto, and a backup envelope extension that
durably captures every climb the user authored, regardless of where it
currently lives.

### Goals

- One-button import of own Kilter-authored climbs into the local DB,
  with per-climb publish-to-Nostr opt-in.
- Imported climbs become first-class CruxCoach citizens: they show up
  under "Quelle: CruxCoach" in the browser, are editable through the
  editor with `Edit-this-climb`, are deletable through `Community Delete`,
  and get the same per-row treatment (badges, lists, BLE quick-send).
- Idempotent: re-running the import after some climbs have already been
  re-published is a no-op for those rows.
- Backup envelope round-trips every own climb (drafts + Nostr-published
  + Kilter-imported-and-republished) so a fresh CruxCoach install on a
  new device reconstructs the user's full authoring history without
  needing relay traffic or Kilter token refresh.
- Backup restore re-attaches existing Nostr metadata (`nostr_event_id`,
  `nostr_d_tag`) so a republish via the editor on the restored device
  uses the same d-tag and replaces the original event rather than
  forking a duplicate.

### Non-Goals

- Importing **other** users' Kilter climbs. The Kilter blob already
  carries those (origin='kilter') via the daily Blossom sync.
- Reverse-publishing an imported climb back to Kilter as a *new* climb.
  The climb is already on Kilter — `kilter_status` is set to `synced`
  on import, and the standard `KilterClimbPublisher` UPDATE path takes
  over from there for any future edits.
- Backing up the Kilter-origin reference catalogue. The Blossom blob
  remains the source of truth for that — we only back up rows the user
  authored.
- Automated continuous sync of Kilter climbs (poll every N hours).
  Single explicit user action only.

---

## 2. Background

### 2.1 Today's data flow for an own Kilter climb

```
User opens Kilter app → creates climb → Kilter API records it
  setter_uuid = user.kilter_uuid
  origin (no equivalent column in Kilter)

CruxCoach daily Blossom sync
  → bundled chunked SQLite blob arrives
  → BoardDatabaseImporter inserts row with:
       origin = 'kilter' (default)
       source = 'kilter'
       sync_status = NULL
       created_by_pubkey = NULL  -- Plan C only fills this for cruxcoach-origin
       kilter_status = NULL
  → row is browseable but indistinguishable from any other Kilter climb
```

There is no field on `climbs` carrying the Kilter setter UUID, so
post-import we cannot answer "is this row mine?" by SQL alone — only by
calling the Kilter API live with the user's token and matching
`climb_uuid` against the API's `setter_uuid` filter result.

### 2.2 Today's `applyOriginFilter`

```kotlin
OriginFilter.CRUXCOACH -> climbs.filter { it.origin == "cruxcoach" || it.source == "local" }
OriginFilter.KILTER    -> climbs.filter { it.origin == "kilter"    && it.source != "local" }
```

(The `source == "local"` carve-out is the legacy-draft compatibility
shim from `234c66d`. With FEAT-008 the cruxcoach-side definition
broadens to include "Kilter row, but I republished it as Nostr".)

### 2.3 Today's backup envelope (`CruxCoachBackup.Backup`)

```kotlin
@Serializable
data class Backup(
    val version: Int = 2,
    val exportedAt: String,
    val nostrPubkey: String? = null,
    val profile: UserProfile? = null,
    val assessments: List<Assessment> = emptyList(),
    val bodyStats: List<BodyStat> = emptyList(),
    val workoutLogs: List<WorkoutLog> = emptyList(),
    val climbLogs: List<ClimbLog> = emptyList(),
    val trainingPlans: List<PlanWithSessions> = emptyList(),
    val boardAscents: List<AscentExport> = emptyList(),
    val boardBids: List<BidExport> = emptyList(),
    val boardSessions: List<SessionExport> = emptyList(),
    val climbLists: List<ClimbListExport> = emptyList()
)
```

No `boardClimbs` field. The header comment explicitly says "Board reference
data … is NOT included — it can be re-downloaded via board sync." That's
true for the Kilter catalogue but wrong for own climbs.

### 2.4 Schema fields relevant to this spec

`climbs` table (`shared/src/commonMain/sqldelight/board/com/cruxcoach/db/board/Board.sq`):

| column                | role for FEAT-008                                           |
|-----------------------|-------------------------------------------------------------|
| `uuid`                | Stable identity, shared between Kilter + Nostr d-tag        |
| `source`              | Will become `'nostr'` for republished imports               |
| `origin`              | Mutated `'kilter' → 'cruxcoach'` on republish (atomic)      |
| `sync_status`         | Set to `'published_nostr'` after republish                  |
| `nostr_event_id`      | Filled by `markClimbPublishedNostr`                         |
| `nostr_d_tag`         | Same uuid                                                   |
| `created_by_pubkey`   | Filled with own Nostr pubkey on republish                   |
| `frames_hash`         | Computed from frames + layout — duplicate detection         |
| `kilter_status`       | Set to `'synced'` so retry worker stays a no-op             |
| `kilter_synced_at`    | Filled with import-time epoch                               |
| `kilter_publish_via`  | `'self'` — the user's own Kilter account                    |

`kilter_publish_attempts` audit-trail table: append `op='import'` row at
import time so the attempt history reflects the new state.

---

## 3. Design — Phase A: Kilter Own-Climb Import

### 3.1 Identification

The Kilter API has no `setter_uuid` field on the `climbs` rows we already
hold locally. To answer "which uuids in my local DB did *I* author?" we
must query the live Kilter API with the user's access token, scoped to
`setter_uuid = me`.

**Endpoint discovery (open):** The Kilter mobile app surfaces a
"My climbs" tab, so the API supports the query — likely one of:

- `GET /api/users/{userUuid}/climbs?setter_uuid={userUuid}` (REST)
- `GET /api/climbs?setter_uuid={userUuid}` (filtered list)
- `POST /api/users/{userUuid}/climbs/sync` (PowerSync-style delta)

**Spike:** Reverse-engineer one tcpdump session with the official
Kilter app to settle this. Add `KilterApiClient.fetchOwnClimbs(setterUuid:
String): List<KilterClimbDto>` once the endpoint shape is known. Store
the call response unaltered (no normalisation) so the import-flow code
can be tested with replay fixtures.

**Failure mode:** if the spike reveals Kilter has stopped exposing this
endpoint server-side (similar to the Aurora API shutdown — see FEAT-005
context), fall back to a **best-effort `setter_username` text match**
between `KilterTokenStore.username` (preferred-username from JWT) and
`climbs.setter_username`. Mark this fallback explicitly as fragile in
the UI so users who renamed on Kilter understand why the list might be
incomplete.

### 3.2 Per-row state machine

The Kilter API distinguishes two row-level states the user has authored:
**`is_draft=1`** (set by user but not yet published — only visible to the
author on Kilter's side; the daily Blossom blob doesn't carry these) and
**`is_draft=0` + `is_listed=1`** (published, in the Kilter catalogue).
CruxCoach's import flow treats them as separate sub-flows because they
have different default behaviours (drafts: import only, no Nostr publish;
published: import + Nostr publish in the same atomic step).

For each row returned by `fetchOwnClimbs`, classify by Kilter-side state
and CruxCoach-side state:

```
KILTER DRAFT (is_draft=1 on Kilter)
================================================================
A1. locally absent
    → live-fetch frames/metadata from Kilter API, INSERT with
      source='local', sync_status='draft', is_listed=1, origin=
      'cruxcoach', created_by_pubkey=ownPubkey.
    → Lands in CruxCoach Drafts drawer immediately, no Nostr publish.

A2. locally present already as source='local'/sync_status='draft'
    → already imported in prior run; skip silently.

A3. locally present with any other state
    → corner case (Kilter draft uuid collides with a published row?
      impossible in practice). Skip with log warning.

KILTER PUBLISHED (is_draft=0 AND is_listed=1)
================================================================
B1. locally absent
    → live-fetch from /api/climbs/{uuid}, INSERT (source='kilter',
      origin='kilter', kilter_status='synced').
    → Then runs through B2 in the same import session.

B2. locally present, origin='kilter', sync_status=NULL or 'failed'
    → IMPORT CANDIDATE. List in UI under "Veröffentlicht auf Kilter"
      with `source='kilter'` + "verfügbar"-badge. Default checkbox
      state: unchecked.

B3. locally present, origin='kilter', sync_status='published_nostr'
    → already imported in a prior run. List greyed-out with "imported on
      {date}" timestamp. Don't show in publish-batch.

B4. locally present, origin='cruxcoach', kilter_status='synced'
    → user-authored in CruxCoach, already round-tripped to Kilter via
      standard publish. Skip silently — not import territory.

B5. locally present, origin='cruxcoach', kilter_status NOT 'synced'
    → user-authored in CruxCoach, never made it to Kilter. Out of scope
      for FEAT-008. Hide from import list.
```

### 3.3 Atomic transactions per branch

The two import branches (drafts vs. published) write different end-states.
Both are executed as single SQLDelight transactions so partial state is
impossible.

#### 3.3.1 Draft import (Kilter draft → CruxCoach draft)

Triggered for every user-selected row in the "Drafts auf Kilter" section.
**No Nostr publish.** The row simply lands in the CruxCoach Drafts drawer;
the user can later promote it to a publish via the standard editor flow.

```sql
INSERT OR REPLACE INTO climbs(
    uuid, layout_id, setter_username, name, frames, frames_count,
    is_listed, edge_left, edge_right, edge_bottom, edge_top, created_at,
    description, is_nomatch, frames_pace, hsm, move_count,
    source, created_by_pubkey, frames_hash, sync_status, origin
) VALUES (
    :uuid, :layout_id, :setter_username, :name, :frames, :frames_count,
    1, :edge_left, :edge_right, :edge_bottom, :edge_top, :created_at,
    :description, 0, 0, 0, :move_count,
    'local', :own_pubkey, :computed_frames_hash, 'draft', 'cruxcoach'
);

INSERT OR REPLACE INTO climb_stats(
    climb_uuid, angle, display_difficulty, difficulty_average,
    quality_average, ascensionist_count, benchmark_difficulty,
    fa_username, fa_at, official_kilter_difficulty
) VALUES (
    :uuid, :setter_angle, :setter_grade, :setter_grade,
    NULL, 0, NULL, NULL, NULL, NULL
);
```

Identical end-state to a fresh CruxCoach draft saved through the editor.

#### 3.3.2 Published import (Kilter published → CruxCoach + Nostr)

For each user-selected row in the "Veröffentlicht auf Kilter" section:

1. **Sign + broadcast Kind-30078**: reuse `CommunityClimbPublisher.publish`
   with a `ClimbEditorState` reconstructed from the existing row
   (frames parsed back via `BoardClimbParser.parseFrames`, bounds
   recomputed via `ClimbBounds.fromCoords` if `edge_*` is NULL — see
   `ClimbCreatorRepository.computeBounds`). Use the existing uuid as
   d-tag so any future republish replaces this event (NIP-78).

2. **On relay accept**: write all of these in a single SQLDelight
   transaction:

```sql
UPDATE climbs SET
    origin           = 'cruxcoach',           -- mutate, atomically with kilter_status
    source           = 'nostr',
    sync_status      = 'published_nostr',
    nostr_event_id   = :event_id,
    nostr_d_tag      = :uuid,
    created_by_pubkey = :own_pubkey,
    frames_hash      = :computed_frames_hash,
    kilter_status    = 'synced',              -- never NULL → retry worker no-op
    kilter_synced_at = :import_epoch_seconds,
    kilter_publish_via = 'self',
    setter_username  = :resolved_kind0_displayname  -- fall back to existing value
WHERE uuid = :uuid;

INSERT INTO kilter_publish_attempts(
    climb_uuid, attempted_at_ms, op, via, outcome, http_code, error_excerpt
) VALUES (
    :uuid, :import_epoch_ms, 'import', 'self', 'success', NULL, NULL
);
```

3. **On relay reject (zero accepted)**: leave the row untouched. Caller
   sees a per-row failure in the import UI and can retry. No partial
   state — the row is either fully cruxcoach-side or fully Kilter-side.

4. **No `kilter_publish_attempts` row on failure**: the actual publish
   to Kilter didn't happen on import — Kilter already had the climb.
   We log only successful imports.

### 3.4 Why mutating `origin` is safe

(Long-form analysis already in `feat/0.1.4-release` discussion 2026-05-05;
short version here.)

The "origin is immutable" comment in `Board.sq:43` is documentation, not
a SQL constraint. The actual constraints come from the queries that
filter on `origin`:

| consumer                          | predicate                                        | post-flip behaviour                                      |
|----------------------------------|--------------------------------------------------|----------------------------------------------------------|
| `KilterPublishRetryWorker`       | `origin='cruxcoach' AND kilter_status NOT IN ('synced','rejected')` | safe — `kilter_status='synced'` is set in same txn       |
| `markCommunityClimbDeleted`      | `WHERE origin='cruxcoach' AND created_by_pubkey=:p` | safe — the user owns the row, deleter can tombstone it   |
| `getCommunitySetterStats`        | `WHERE origin='cruxcoach' AND created_by_pubkey IS NOT NULL` | safe — row joins setter list correctly                   |
| `updateClimbBlobFields`          | (no origin predicate; updates blob-only fields)  | safe — preserved per `Board.sq:247-251`                  |
| `applyOriginFilter` UI           | `origin='cruxcoach'` family                      | safe — desired classification                            |
| `idx_climbs_origin`              | (index, not predicate)                           | safe — index path adjusts                                |

`BoardDatabaseImporter` already supports `'kilter' → 'cruxcoach'` flips
during bulk Blossom imports (Plan C). FEAT-008 reuses the same
direction-of-flip from a different trigger.

### 3.5 UX flow

**Entry point:** Settings → Kilter-Konto → "Eigene Kilter-Climbs
importieren" (new button below the existing connect/disconnect controls).

#### 3.5.1 Pre-import gates

Before showing the discovery list, the import flow runs three pre-checks
in order. Failing any gate parks the user with a CTA to remediate; passing
all three opens the discovery screen.

**Gate 1 — Nostr identity present.** If `NostrSigner.getPublicKeyHex()`
returns null/empty, present a soft prompt:

```
┌────────────────────────────────────────────────────┐
│  Nostr-Profil noch nicht eingerichtet               │
│                                                     │
│  Importierte Climbs werden mit deinem Nostr-Schlüssel│
│  signiert. Ohne Profil erscheinen sie als           │
│  "npub:abc…" — wenig hilfreich für andere Climber.  │
│                                                     │
│  [ Profil einrichten ]   [ Trotzdem fortfahren ]    │
└────────────────────────────────────────────────────┘
```

"Profil einrichten" routes to FEAT-010's Kind-0 editor; "Trotzdem
fortfahren" continues but the imported rows show npub-stub setter names
until the user fills in a profile later.

**Gate 2 — Kilter-side profile auto-fill (Q6).** When the local Kind-0
metadata has empty `name` / `picture` / `about` / `lud16` fields AND the
Kilter API exposes equivalent fields (Kilter user profile typically
carries display name + avatar URL), present a one-time pre-fill dialog:

```
┌────────────────────────────────────────────────────┐
│  Kilter-Profilfelder übernehmen?                    │
│                                                     │
│  Diese Felder sind in deinem Nostr-Profil leer.     │
│  Aus Kilter: …                                       │
│  ☐ Name:    "Alice K."                              │
│  ☐ Bild:    [thumbnail preview]                     │
│  ☐ Bio:     "Climbing since 2018, ..."              │
│                                                     │
│  [ Übernehmen ]              [ Skip ]               │
└────────────────────────────────────────────────────┘
```

Lightning address (`lud16`) is **not** sourced from Kilter — Kilter
doesn't track Lightning. The dialog only offers fields where (a) the
Kilter API actually carries the value and (b) the local Kind-0 field is
empty. If both conditions reduce the offered set to zero, the dialog
is suppressed entirely.

On "Übernehmen", the selected fields are written via FEAT-010's profile
editor (which handles the Blossom upload for `picture`) and a fresh
Kind-0 event is published. The import flow then continues with the
freshly-resolved display name baked into all subsequent setter_username
writes.

**Gate 3 — Token freshness.** Refresh the Kilter access token via
`KilterTokenStore.refresh()` if older than 1 hour. If refresh fails
(network, credentials), abort with "Mit Kilter neu anmelden"-CTA.

#### 3.5.2 Discovery screen

After the gates pass, the discovery loads `fetchOwnClimbs` and renders
a LazyColumn split into **two sticky-header sections**:

```
┌─ DRAFTS AUF KILTER (3) ──────────────────────────── ☑ alle ─┐
│  ☑  V5 / 6c   "Project Mango"                                │
│       40°  · 14 holds · last edited 2026-04-30 (Kilter)      │
│       → wird zu CruxCoach-Draft                              │
├──────────────────────────────────────────────────────────────┤
│  ☑  ?? / ??   "Setterversuch 17"                             │
│       — kein Grade gesetzt · 8 holds                         │
│       → wird zu CruxCoach-Draft                              │
├──────────────────────────────────────────────────────────────┤
│  ☑  V3 / 6a   "Trockentest"                                  │
│       30°  · 11 holds                                        │
│       → wird zu CruxCoach-Draft                              │
└──────────────────────────────────────────────────────────────┘

┌─ VERÖFFENTLICHT AUF KILTER (5) ─────────────────── ☐ alle ──┐
│  ☐  V5 / 6c   "My Project Beta"                              │
│       40°  · 12 holds · 47 ascents on Kilter                 │
│       → wird auf Nostr veröffentlicht                        │
├──────────────────────────────────────────────────────────────┤
│  ✓  V6 / 6c+  "Crimp Roof"                                   │
│       45°  · 14 holds · Bereits importiert 2026-05-12  ↗     │
└──────────────────────────────────────────────────────────────┘

[ Importieren (3 Drafts + 0 Veröffentlichungen) ]
```

**Default selection states (Q3):**
- Drafts section: all rows pre-checked (no Nostr publish, no data risk
  from accidental selection).
- Published section: all rows un-checked (publishing to Nostr is a
  visible side-effect — opt-in is the right default).
- Already-imported rows in either section: greyed out, unselectable,
  show "Bereits importiert {date}" with a link to the local detail
  screen.

**Section header bulk-toggle:** clicking the header's `☑/☐ alle`
checkbox flips all rows in that section.

**Bottom CTA**: dynamically labelled — "Importieren ({nDrafts} Drafts +
{nPublished} Veröffentlichungen)" — disabled when both counts are zero.

#### 3.5.3 Import execution

Triggered by the bottom CTA. Single foreground-service-backed
`KilterImportWorker` (single-instance via `WorkManager.unique`).

Pseudocode:

```kotlin
suspend fun importSelected(drafts: List<KilterDraftDto>,
                           published: List<KilterPublishedDto>) {
    val total = drafts.size + published.size
    var done = 0
    val failures = mutableListOf<ImportFailure>()

    // Drafts first — fast, no relay roundtrip
    for (d in drafts) {
        runCatching { writeDraftTransaction(d) }
            .onSuccess { done++ }
            .onFailure { failures += ImportFailure(d.uuid, it.message) }
        notifyProgress(done, total)
    }

    // Published — Nostr publish + atomic flip
    for (p in published) {
        runCatching {
            val state = reconstructEditorState(p)
            val event = communityClimbPublisher.publish(state, ...)
            applyPublishedFlipTransaction(p.uuid, event)
        }.onSuccess { done++ }
            .onFailure { failures += ImportFailure(p.uuid, it.message) }
        delay(1_000)  // throttle to avoid relay burst
        notifyProgress(done, total)
    }

    finalSnackbar(done, total, failures)
}
```

**Mid-import progress notification:** "Climb {done}/{total} importiert…"

**Cancel button:** stops the loop after the current row finishes.
Already-completed rows persist (idempotent on re-run).

**Failure handling (resolved §10 Q2):** in-memory only. Failed rows
listed in the post-run snackbar with retry-suggestion ("3 Climbs
fehlgeschlagen — erneut versuchen?"). No `pending_import` schema
column. Bulk-import is a one-shot action; a worker that survives
across reboots is overkill at v0.2.0 user volumes.

### 3.6 Edge cases

| case | handling |
|---|---|
| Kilter token expired mid-import | refresh via `KilterTokenStore.refresh()`. If refresh fails, surface "Bitte mit Kilter neu anmelden" snackbar and abort the import worker. Already-published climbs stay published. |
| User has no Nostr key set up yet | Hard-block the entry point: button is disabled with "Erst Nostr-Profil anlegen" CTA linking to `Settings → Nostr-Profil`. |
| `frames_hash` matches a foreign community climb | Pop the same `DuplicateClimbDialog` the editor uses (FEAT-003 §4.5). Default action: skip this climb. User can override per-row. |
| Climb has multiple angle stat rows | One climb can have stats at several angles (15°, 25°, 40°). The Nostr Kind-30078 event represents the climb definition once; the per-angle stats follow naturally via the climb_browse view. Republish posts ONE event per uuid. |
| `edge_*` columns NULL on legacy Kilter rows | Recompute bounds from frames + placement coords via `ClimbBounds.fromCoords`. Same path the editor uses on save. |
| User logs out of Kilter mid-import | Discovery list invalidates immediately; running publishes per-uuid finish (token already obtained). Settings entry point hides until re-login. |
| Climb deleted on Kilter side post-import | Local row stays. Editing still works. Republish-as-update will fail (Kilter 404 on UPDATE-climb). User sees the existing `kilter_status='diverged'` state. Out of scope — manual reconciliation. |
| Bulk-import of 50+ climbs | Foreground worker with progress notification. User can background the app. |
| Import already-running, user retriggers | Worker is single-instance via `WorkManager.unique(name=KILTER_IMPORT)`. Second tap is a no-op. |
| User has a CruxCoach-published climb that ALSO appears in `fetchOwnClimbs` | (i.e. they published via CruxCoach → it round-tripped to Kilter → Kilter API returns it as theirs) — `kilter_status='synced'` AND `origin='cruxcoach'` already, skip silently. |
| User restores backup that contains imported climbs, then reimports | Idempotent — `sync_status='published_nostr'` rows are skipped from the candidate list. |

### 3.7 Filter classification post-FEAT-008

After implementation, the `applyOriginFilter` legacy carve-out for
imported-not-yet-flipped rows becomes unnecessary, because the import
itself flips `origin`. The shim from `234c66d` (`source == "local"`
catch) stays for legacy local drafts whose `origin` column was written
when the schema default was `'kilter'`.

```kotlin
OriginFilter.CRUXCOACH -> climbs.filter { it.origin == "cruxcoach" || it.source == "local" }
OriginFilter.KILTER    -> climbs.filter { it.origin == "kilter"    && it.source != "local" }
```

(unchanged from `234c66d`).

---

## 4. Design — Phase B: Backup Extension

### 4.1 New backup envelope field

Bump `version` to **3** (current is 2). Add:

```kotlin
@Serializable
data class Backup(
    val version: Int = 3,
    // ... existing fields unchanged ...
    val boardClimbs: List<OwnClimbExport> = emptyList(),
    val boardClimbStats: List<OwnClimbStatExport> = emptyList(),
)
```

`version=2` backups remain restorable — `boardClimbs` defaults to empty.
`version=3` reading code on a `version=2` payload skips the new fields
gracefully via `ignoreUnknownKeys = true` (already set). On older
clients trying to read a `version=3` backup, the existing
`require(version in 1..2)` check throws "unsupported version" — we'll
need to relax this to `1..3` in 0.2.0 *before* anyone publishes a v3
backup. **This is a forward-compat must-do; bump version-allowed range
in the 0.2.0 release that ships restore but not export of v3, then ship
v3 export in the following point release. Or just ship both at once in
0.2.0 and accept that 0.1.4 cannot restore 0.2.0 backups.** Recommend
the latter — single release, clean version line.

### 4.2 `OwnClimbExport` shape

```kotlin
@Serializable
data class OwnClimbExport(
    val uuid: String,
    val layoutId: Long,
    val name: String,
    val description: String,
    val frames: String,           // Delta format (placement+role pairs)
    val framesCount: Long,
    val moveCount: Long,
    // edge_* may be NULL on legacy rows; restore recomputes from frames if so.
    val edgeLeft: Long? = null,
    val edgeRight: Long? = null,
    val edgeBottom: Long? = null,
    val edgeTop: Long? = null,
    val createdAt: String,        // ISO-8601
    // CruxCoach-side metadata
    val source: String,           // 'local' | 'nostr' (never 'kilter' in this list)
    val origin: String,           // 'cruxcoach' on all rows in this list (post-flip)
    val syncStatus: String?,      // 'draft' | 'published_nostr' | 'failed'
    val createdByPubkey: String?, // own pubkey, redundant but useful for cross-device verification
    val framesHash: String?,
    val setterUsername: String?,
    // Nostr publish trace — preserves d-tag continuity across device migration
    val nostrEventId: String? = null,
    val nostrDTag: String? = null,
    // Kilter mirror trace — preserves UPDATE-vs-CREATE decision on next edit
    val kilterStatus: String? = null,           // 'synced' | 'failed' | 'diverged' | 'pending' | NULL
    val kilterSyncedAt: Long? = null,           // epoch seconds
    val kilterPublishVia: String? = null,       // 'self' | 'cruxcoach'
    val kilterError: String? = null,
)

@Serializable
data class OwnClimbStatExport(
    val climbUuid: String,
    val angle: Long,
    val displayDifficulty: Double?,
    val difficultyAverage: Double?,
    val qualityAverage: Double?,
    val ascensionistCount: Long,
    val benchmarkDifficulty: Double?,
)
```

### 4.3 Export selection criteria

```sql
-- All rows the local user authored, regardless of where they currently live.
SELECT * FROM climbs
WHERE created_by_pubkey = :own_pubkey
   OR (source = 'local' AND created_by_pubkey IS NULL)  -- legacy drafts pre-pubkey
ORDER BY created_at DESC;
```

The `OR` second branch covers the corner case where a draft was saved
before NostrSigner could resolve a pubkey. These are unambiguously the
local user's climbs (they came out of the device's editor with no
pubkey resolution yet); restoring them on a new device with a
freshly-derived pubkey re-attaches the row to that pubkey.

For climb_stats: emit one row per (uuid, angle) where `uuid IN (the list above)`.

### 4.4 Restore semantics

For each `OwnClimbExport` row in the backup:

1. **uuid collision check**: if the local DB already has the row,
   compare `frames_hash` and `created_at`. Match → skip (idempotent
   restore). Mismatch → conflict — see §4.5.
2. **Insert / upsert** via a new SQLDelight `restoreOwnClimb` query
   that's symmetric to `insertLocalDraft` but takes the full envelope:

```sql
restoreOwnClimb {
    INSERT OR REPLACE INTO climbs(
        uuid, layout_id, setter_username, name, frames, frames_count, is_listed,
        edge_left, edge_right, edge_bottom, edge_top, created_at,
        description, is_nomatch, frames_pace, hsm, move_count,
        source, created_by_pubkey, frames_hash, sync_status, origin,
        nostr_event_id, nostr_d_tag, kilter_status, kilter_synced_at,
        kilter_publish_via, kilter_error
    ) VALUES (
        :uuid, :layout_id, :setter_username, :name, :frames, :frames_count, 1,
        :edge_left, :edge_right, :edge_bottom, :edge_top, :created_at,
        :description, 0, 0, 0, :move_count,
        :source, :created_by_pubkey, :frames_hash, :sync_status, :origin,
        :nostr_event_id, :nostr_d_tag, :kilter_status, :kilter_synced_at,
        :kilter_publish_via, :kilter_error
    );
}
```

3. **Per-angle stats** restored via the existing `upsertLocalClimbStat`
   path (no schema change needed — climb_stats was already export-grade).

4. **Re-validate**: post-restore, run the existing
   `countListedClimbsWithoutStats` integrity check; surface a warning
   in the restore UI if drift detected.

### 4.5 Conflict handling on restore

If a uuid exists locally with different `frames_hash`:

```
┌──────────────────────────────────────────────────────────┐
│ Konflikt: "My Project Beta"                              │
│                                                          │
│ Lokal:    aktualisiert 2026-04-30, 12 Holds              │
│ Backup:   aktualisiert 2026-05-08, 14 Holds              │
│                                                          │
│ ◯ Lokale Version behalten                                │
│ ◯ Backup-Version übernehmen                              │
│ ● Beide behalten (Backup bekommt neue UUID)              │
└──────────────────────────────────────────────────────────┘
```

"Beide behalten" generates a fresh UUIDv4 for the backup row, breaking
its Nostr d-tag continuity (republish-from-restored-row creates a new
event), but no data loss. Default selection: "Beide behalten".

### 4.6 Backup envelope size budget

Local user's own climbs are typically <100 rows × ~1-2 KB each → <200 KB
added. Stays well within the existing Blossom blob upload cap (4 MB
manifest). If a power user has 500+ climbs, compression mitigates;
upper bound for the validate guard: `MAX_COLLECTION_SIZE = 50_000`
already covers this.

### 4.7 Backup encryption

Existing `BackupCrypto` (NIP-44 / chacha20-poly1305 with key derived
from the user's Nostr private key) covers the new fields without
modification — they're added to the same plaintext JSON before
compression and encryption.

### 4.8 Backup edge cases

| case | handling |
|---|---|
| Restore on a device with a *different* Nostr pubkey | reject — show "Backup gehört zu npub:abc... — nicht zu diesem Profil. Schlüssel wechseln oder neuen Account anlegen?" Existing `BackupRepository.restore` already gates on pubkey; new fields inherit that gate. |
| Climb in backup, also in cruxcoach-blossom-sync delta | Idempotent — `INSERT OR REPLACE` keyed on uuid. Backup wins, but backup data is more authoritative for own climbs anyway. |
| Restore on fresh install (DB empty) | Straightforward — every uuid is new, no conflicts. |
| `nostr_event_id` in backup but row has been deleted on relay | Restore rewrites the metadata. Future republish via editor uses the same d-tag → resurrects the climb on the relay. Functionally a republish, fine. |
| Backup version mismatch (v2 client, v3 backup) | v2 client throws "unsupported version 3". User sees clear message. v3 client reads v2 backup transparently (defaults). |
| User exports v2 backup, restores on v3 client | Works — `boardClimbs` defaults to empty, no ownClimb data restored. User sees a one-time "Backup hat keine Climb-Daten — Drafts vor 0.2.0 nicht enthalten" toast. |
| Same uuid in `boardClimbs` AND in `boardAscents.climbUuid` | Climb restored first, ascent second. Ascent's foreign-key-style reference to climb_uuid resolves cleanly. Restore order matters — see §4.9. |

### 4.9 Restore step ordering

Mirroring FEAT-005 §5.2:

```
1. profile (if present)
2. boardClimbs        ← NEW, before any climb_uuid-referencing data
3. boardClimbStats    ← NEW
4. climbLists
5. boardAscents
6. boardBids
7. boardSessions
8. assessments / bodyStats / workoutLogs / climbLogs / trainingPlans
```

Climbs land before ascents/lists so their foreign-key-style references
resolve. (No actual SQL FK constraint, but the UI assumes the parent row
exists.)

---

## 5. Schema Additions

### 5.1 No migrations needed for Phase A

Every column referenced exists in the v0.1.4 schema. The flip transaction
in §3.3 only writes to existing columns.

### 5.2 No migrations needed for Phase B

`climbs.is_listed`, `climbs.frames`, `climb_stats` per-angle rows — all
present. The export reads from them, the import writes to them via the
new `restoreOwnClimb` SQLDelight query (which is added but introduces no
new columns).

### 5.3 Optional: explicit `setter_user_id` column

Long-term, adding `climbs.setter_user_id TEXT` (nullable) would let
"is this row mine?" be answered by SQL alone, eliminating the live
Kilter API roundtrip in §3.1. Out of scope for FEAT-008 unless the
spike in §3.1 reveals the live endpoint is unstable; document as a
0.3.0 follow-up regardless.

---

## 6. UX Flow

### 6.1 Kilter import (Settings)

```
Settings
└── Kilter-Konto
    ├── Verbindungs-Status
    ├── Trennen
    └── ▶ Eigene Kilter-Climbs importieren    [NEW]

Tap "Eigene Kilter-Climbs importieren"
    ↓
Loading state (1-3s typical)
    ↓
List of own Kilter climbs with selection checkboxes
    ↓
Bottom button: "Auf Nostr veröffentlichen (3)"
    ↓
Foreground worker progress: "Climb 2 von 3 wird veröffentlicht..."
    ↓
Done snackbar: "3 Climbs auf Nostr veröffentlicht"
    ↓
Browser → Quelle: CruxCoach → climbs are now there
```

### 6.2 Backup extension (no UI change)

The existing backup-export and backup-restore UI in `BackupSettingsSection`
already covers the user-facing flow. `OwnClimbExport` data flows
transparently through:

- Pre-backup: `BackupRepository.exportLocal` enumerates own climbs.
- Manifest: existing Blossom upload pipeline.
- Restore: existing `BackupRepository.restore` with the §4.9 step
  ordering applied to the new fields.

User sees one new line in the export-summary dialog:

```
Eigene Climbs:        12
  davon Drafts:        2
  davon publiziert:   10
```

---

## 7. Localized Strings

### 7.1 New string keys (EN + DE)

```xml
<!-- EN: values/strings.xml -->
<string name="kilter_import_own_title">Import own Kilter climbs</string>
<string name="kilter_import_own_subtitle">Republish climbs you authored on Kilter as CruxCoach community climbs</string>
<string name="kilter_import_own_loading">Looking up your Kilter climbs…</string>
<string name="kilter_import_own_empty">No own Kilter climbs found. Set a climb in the Kilter app and try again.</string>
<string name="kilter_import_own_state_available">Available</string>
<string name="kilter_import_own_state_imported">Imported on %1$s</string>
<string name="kilter_import_own_publish_button">Publish to Nostr (%1$d)</string>
<string name="kilter_import_own_progress">Publishing climb %1$d of %2$d…</string>
<string name="kilter_import_own_done">%1$d climbs published to Nostr</string>
<string name="kilter_import_own_failed_summary">%1$d climbs failed. Open Settings to retry.</string>
<string name="kilter_import_own_needs_nostr_profile">Set up a Nostr profile first.</string>

<string name="backup_summary_own_climbs">Own climbs: %1$d</string>
<string name="backup_summary_own_climbs_drafts">  drafts: %1$d</string>
<string name="backup_summary_own_climbs_published">  published: %1$d</string>
<string name="backup_restore_climb_conflict_title">Conflict: %1$s</string>
<string name="backup_restore_climb_conflict_keep_local">Keep local version</string>
<string name="backup_restore_climb_conflict_keep_backup">Use backup version</string>
<string name="backup_restore_climb_conflict_keep_both">Keep both (backup gets new UUID)</string>
```

DE equivalents in `values-de/strings.xml` per CONTRIBUTING.md rule.

---

## 8. Validation & Error Handling

### 8.1 Import-side

- `KilterApiClient.fetchOwnClimbs` failures map to existing
  `KilterApiException` taxonomy (auth, network, rate-limit).
- Per-climb `CommunityClimbPublisher.publish` zero-accepted result →
  per-row failure. Other rows continue.
- DB transaction in §3.3 wraps everything for one climb; partial state
  not possible.

### 8.2 Backup-side

Reuse the existing `CruxCoachBackup.validate()` pattern. Add per-row
validation for `OwnClimbExport`:

```kotlin
ownClimbs.forEach { c ->
    requireUuid("ownClimbs.uuid", c.uuid)
    requireLen("ownClimbs.name", c.name, MAX_NAME_LEN)
    requireLen("ownClimbs.frames", c.frames, MAX_CLIMB_FRAMES_LEN)
    require(c.source in setOf("local", "nostr")) { "invalid backup: ownClimbs.source" }
    require(c.origin in setOf("local", "cruxcoach", "kilter")) { "invalid backup: ownClimbs.origin" }
    c.nostrEventId?.let { require(HEX64_REGEX.matches(it)) { "invalid backup: ownClimbs.nostrEventId" } }
    c.createdByPubkey?.let { require(HEX64_REGEX.matches(it)) { "invalid backup: ownClimbs.createdByPubkey" } }
    requireRange("ownClimbs.kilterSyncedAt", c.kilterSyncedAt, 0L..Long.MAX_VALUE)
}
```

---

## 9. Privacy & DSGVO

- Kilter import uses the user's own Kilter token. No second-party data
  exchange. Personal data scope unchanged.
- Backup extension exports only data the user authored
  (`created_by_pubkey == ownPubkey`). No third-party climb data leaves
  the device unless the user explicitly publishes.
- Republish-to-Nostr is opt-in per climb. Existing `CommunityClimbPublisher`
  privacy semantics inherit (climb description + frames go on the
  relay, no other identity-linkable data).
- Import-time `kilter_publish_attempts` audit row contains no PII
  beyond `climb_uuid` + outcome, same as the existing CruxCoach→Kilter
  publish trail.

---

## 10. Open Questions and Decision Log

All design questions resolved 2026-05-05. Implementation gated only on
the M1 Kilter API endpoint reverse-engineering spike.

### 10.1 Resolved decisions

| ID | Topic | Decision |
|---|---|---|
| Q1 | Multi-angle: which angle in the Nostr event? | Delegated to FEAT-009. Setter publishes one anchor `(grade, setter_angle)` plus optional `valid_angles` tag list. Per-angle community grades come from FEAT-009's Bayesian aggregation over Kind-30079 vote events. |
| Q2 | `setter_username` after republish: silent Kind-0 swap, or prompt? | Silent swap, with one-time snackbar on first import explaining the cross-platform name divergence. |
| Q3 | Kilter `is_listed` during import: force or preserve? | Force `is_listed=1` on imported rows. The user's explicit "auf Nostr veröffentlichen"-action overrides any prior Kilter-side hide-state. Drafts also get `is_listed=1` (same as CruxCoach editor's saveDraft path) — visibility is gated by the source/sync_status filters, not is_listed. |
| Q4 | Backup restore: merge or wipe? | Merge per UUID. Default-on. Optional opt-in toggle "Lokale Drafts vorher löschen" in the restore dialog for users who want a clean replace. |
| Q5 | Restore conflict dialog: 3-way or 2-way? | 2-way ("Lokal behalten" / "Backup übernehmen") plus a global "Auf alle anwenden"-toggle. The 3-way "Beide behalten" (new-uuid) option is dropped — Nostr-d-tag continuity break is too subtle for a casual conflict prompt. Power users can fork through the editor post-restore. |
| Q6 | Auto-fill blank Nostr profile fields from Kilter on import? | Yes. Pre-import gate (§3.5.1 Gate 2) offers `name`/`picture`/`about` import per-field, on Kind-0 fields that are currently empty. Lightning-address (`lud16`) is excluded — Kilter doesn't expose it. |
| Q7 | Nostr-profile prerequisite: pubkey or published Kind-0? | Pubkey is enough; Kind-0 absence triggers the soft prompt at Gate 1 (§3.5.1). User can dismiss and continue with `npub:<short>` setter names. |
| Q8 | Backup version rollout: bundle 2→3, or pre-bump? | Bundle both in 0.2.0. CHANGELOG explicitly notes "0.2.0 backups can't be restored on 0.1.x". CruxCoach has no documented downgrade story. |
| Q9 | Spike approach for the endpoint: tcpdump or contact Kilter? | Reverse engineering only. Single Kilter-account-per-CruxCoach-account assumption holds; multi-account is deferred to 0.3.0+. |
| Q10 | Multi-Kilter account support? | Single-account only. Documented as a known limitation. |
| Q11 | Worker resume after Android-kill? | No. Manual re-trigger only. WorkManager `expedited` policy with one-shot retry — if it dies mid-run, user sees the import button at "ready to start"-state on next entry. |
| Q12 | Concurrent publish (editor + import worker)? | Accept the race. `CommunityClimbPublisher.publish` is idempotent per `(uuid, event_id)` at the relay level (NIP-78 replaceable); the local DB transactions in §3.3 are atomic. No mutex needed. |
| Q13 | Backup summary detail level | Minimal — single line in the export-summary dialog: "Eigene Climbs: N". Per-source/per-layout/per-angle breakdowns deferred to a future "Backup details" expand. Separate FEAT-010 covers the broader Kind-0 editor improvements. |

### 10.2 Still open

| ID | Topic | Status |
|---|---|---|
| M1 | `KilterApiClient.fetchOwnClimbs` endpoint shape | Spike pending. Estimated single-session reverse engineering against a captured tcpdump from the official Kilter Android app. Block on this before M2. |
| 0.3.0+ | `climbs.setter_user_id` schema column | Deferred. Lifts the round-trip cost of the API spike permanently and enables purely-local "is this row mine?" queries. Adds a SQL migration + a Blossom-cron field. Not worth the complexity in 0.2.0. |

---

## 11. Dependencies

### 11.1 Code touched

| file                                                                                | scope    |
|-------------------------------------------------------------------------------------|----------|
| `androidApp/src/main/java/com/cruxcoach/android/data/kilter/KilterApiClient.kt`     | + `fetchOwnClimbs` |
| `androidApp/src/main/java/com/cruxcoach/android/community/CommunityClimbPublisher.kt` | reuse    |
| `androidApp/src/main/java/com/cruxcoach/android/community/KilterImportRepository.kt`| **new**  |
| `androidApp/src/main/java/com/cruxcoach/android/community/KilterImportWorker.kt`    | **new**  |
| `androidApp/src/main/java/com/cruxcoach/android/ui/settings/KilterImportScreen.kt`  | **new**  |
| `androidApp/src/main/java/com/cruxcoach/android/ui/settings/KilterImportViewModel.kt` | **new** |
| `androidApp/src/main/java/com/cruxcoach/android/ui/settings/KilterAccountSection.kt` | + entry-point button |
| `androidApp/src/main/java/com/cruxcoach/android/ui/navigation/NavGraph.kt`          | + `KILTER_IMPORT` route |
| `shared/src/commonMain/kotlin/com/cruxcoach/data/CruxCoachBackup.kt`                | + `OwnClimbExport`, `OwnClimbStatExport`, `version=3` |
| `shared/src/commonMain/kotlin/com/cruxcoach/data/repository/BoardRepository.kt`     | + `getOwnClimbsForBackup`, `restoreOwnClimb` |
| `shared/src/commonMain/sqldelight/board/com/cruxcoach/db/board/Board.sq`            | + `restoreOwnClimb` query, + `applyKilterImportFlip` |
| `androidApp/src/main/java/com/cruxcoach/android/nostr/backup/BackupRepository.kt`   | + ownClimb export/import wiring + restore step ordering |
| `androidApp/src/main/res/values/strings.xml`                                        | + EN strings (§7) |
| `androidApp/src/main/res/values-de/strings.xml`                                     | + DE strings (§7) |

### 11.2 Out-of-tree

- Kilter API endpoint (spike).
- No cron / blossom changes — own climbs already publish via existing
  CommunityClimbPublisher path.

---

## 12. Implementation Plan / Milestones

| milestone | deliverable | gate |
|---|---|---|
| M1 — Discovery | Spike report on `KilterApiClient.fetchOwnClimbs` endpoint | Approved by user |
| M2 — Phase A backbone | `KilterImportRepository` + flip transaction + tests | Compile-clean unit tests |
| M3 — Phase A worker | `KilterImportWorker` foreground service + throttling | Manual test against fixture climbs |
| M4 — Phase A UI | Settings screen + ViewModel + nav wiring | UX sign-off, EN+DE strings reviewed |
| M5 — Phase B envelope | Backup version 3 + `OwnClimbExport` shape + validation | Round-trip backup+restore tests |
| M6 — Phase B restore conflict UI | Conflict dialog + step ordering | Manual test of cross-device restore |
| M7 — Polish | Edge-case handling, error-recovery snackbars, telemetry | Release-ready |

---

## 13. Testing Strategy

### 13.1 Phase A

- **Unit**: flip-transaction SQLDelight queries against a fresh in-memory
  DB. Verify post-flip row state for retry-worker safety
  (`kilter_status='synced'` ⇒ no enqueue).
- **Unit**: `KilterImportRepository` against mocked `KilterApiClient`
  returning fixed climb DTOs. Verify per-row state machine (§3.2).
- **Integration**: mocked relay → real `CommunityClimbPublisher` → DB
  flip → re-read row, assert browser-visible under cruxcoach origin.
- **Manual**: live import against a Kilter test account with 3-5 climbs.
  Verify resulting browser entries, edit-and-republish flow, delete
  flow.

### 13.2 Phase B

- **Round-trip**: export backup with N own climbs, wipe DB, restore.
  Diff `climbs` + `climb_stats` row contents — should be identical
  modulo `kilter_synced_at` formatting.
- **Cross-version**: restore a hand-crafted v2 backup on a v3 client —
  no `boardClimbs`, no error.
- **Conflict**: pre-populate the DB with a same-uuid different-frames
  row, restore, verify the conflict dialog and each branch.
- **Manual**: device A exports → device B (fresh install) restores →
  user signs Nostr key matching backup pubkey → climbs appear in
  browser, editable, deletable.

---

## 14. Notes for the Reviewer

- The schema-rewrite resistance from FEAT-006 (immutable `origin`
  documentation comment) was mitigated in the 2026-05-05 design
  discussion — the comment understates what the codebase actually
  permits, and the `BoardDatabaseImporter` Plan C path already flips
  in the same direction.
- Consider whether to bundle a one-time **setter_username backfill**
  with the import worker: when we discover a row is "mine" via
  `fetchOwnClimbs` and the user has a Kind-0 display_name set, write
  `setter_username = display_name` even on rows the user *doesn't*
  select for republish. Low-risk, surfaces the user's name on the
  un-republished Kilter rows in the browse list. Suggest **yes** but
  scoped under the same owner-gate the flip uses.
