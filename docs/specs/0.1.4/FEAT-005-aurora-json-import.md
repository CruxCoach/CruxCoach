---
status: implementation
---
# Feature Spec: Aurora JSON Export Import (v0.1.4)

> **Status:** Implementation (0.1.4) — flagged 2026-05-06. Re-added to
> 0.1.4 scope after the FEAT-010 polish landed cleanly. Implementation
> mirrors `boardsesh/packages/web/app/lib/data-sync/aurora/` (Apache 2.0)
> for the empirically-proven patterns: timestamp normalisation,
> deterministic `auroraId` hash, name-resolution with public-over-draft
> tiebreaker, dedup via `INSERT … ON CONFLICT (external_id)`. Boardsesh
> verifies the §2 "Input JSON Shape" with real test fixtures (not just
> our own sample), so the field set is solid.
>
> Two corrections vs. the original skeleton (recorded inline in §2 and §4):
>
> - §4.3 retargeted from a separate `local_climb` table to FEAT-003's
>   unified `climbs` table with `source='local' / origin='cruxcoach'`.
>   FEAT-003 went the single-table route, not the two-table one the
>   skeleton assumed.
> - §2.2 dropped the 1-3 → 1-5 quality conversion. Aurora's *email
>   export* already arrives on the 1-5 scale; only the *live API*
>   (which we don't talk to) returns 1-3.
>
> **Depends on:** FEAT-003 (Climb Creator) — provides the unified
> `climbs` table where imported user-authored draft rows land. JSON
> import without FEAT-003 still ingests ascents, bids and circuits; the
> `climbs[]` section is silently skipped.

## 1. Overview

Aurora Climbing took its API offline in early 2026 (cease-and-desist over
Kilter trademark/logo use forced the shutdown of the joint board ecosystem).
Aurora is honoring data-export requests via email
(`peter@auroraclimbing.com`) and returns a single JSON file containing the
user's ascents, projects (bids), circuits/lists, and any user-created
("draft") climbs. CruxCoach already implements native Kilter sync against
the new Kilter Board API (Keycloak + PowerSync), but users coming from the
old Aurora ecosystem have no migration path for their historical logbook.

This feature adds a JSON file importer that ingests the Aurora export,
maps each entity to the equivalent CruxCoach storage, deduplicates against
already-present data (so re-uploading the same file is a no-op), and
surfaces the importer at three entry points: the data-management settings
page, onboarding step 3 (alongside the existing Kilter OAuth import), and
a v0.1.4 "what's new" announcement dialog.

### Goals

- Ingest the full Aurora export JSON (ascents, bids, circuits, climbs, user)
- Idempotent re-import — running the importer twice produces no duplicates
- Resolve climb-by-name against the local board DB (85k+ Kilter climbs)
- Report unresolved climb names back to the user (so they can investigate)
- Build inferred sessions from imported timestamps (4-hour-gap heuristic)
- Support both Kilter and Tension layouts in a single import (board-type
  field on each climb determines target)

### Non-Goals

- **Live Aurora API calls** — the API is gone; this is JSON-file only
- **Re-export to other apps** — one-way ingest only; export is a separate
  spec (already partially covered by `CruxCoachBackup` v2)
- **Climb-creator UI** — handled by FEAT-003; this spec only writes draft
  climb rows that FEAT-003's storage layer already understands
- **Conflict resolution UI** — duplicates are silently skipped, not
  surfaced as merge prompts. Re-import is "fill in what's missing", not
  three-way merge
- **Aurora-specific fields without a CruxCoach analogue** —
  `walls`/`blocks`/`beta_links`/`agreements`/`follows` are dropped at
  parse time

---

## 2. Input JSON Shape

Schema derived from a representative Aurora export sample. Field
semantics inferred from field names and observed values; `is_private`
and `created_at` semantics confirmed against the official Aurora app's
behavior.

### 2.1 Top-level

```json
{
  "user":     { "username": "...", "email_address": "...", "created_at": "..." },
  "ascents":  [ ... ],
  "attempts": [ ... ],
  "circuits": [ ... ],
  "climbs":   [ ... ],
  "likes":    [ ... ],
  "follows":  [ ... ],
  "walls":    [ ... ],
  "blocks":   [ ... ],
  "beta_links": [ ... ],
  "agreements": [ ... ]
}
```

`user.username` is the only required field. All arrays default to `[]`.
Everything from `walls` onward is dropped at parse time (oversized,
unused).

### 2.2 Ascent (one entry per logged send)

| Field | Type | Required | Notes |
|---|---|---|---|
| `climb` | string | ✓ | Climb **name**, not UUID. Resolved against local board DB |
| `angle` | int | ✓ | Board angle in degrees (e.g. `40`) |
| `count` | int | ✓ | Attempt count. `1` = flash, `>1` = redpoint |
| `stars` | int | ✓ | User rating, 1–5 |
| `climbed_at` | string | ✓ | ISO 8601 or `YYYY-MM-DD HH:MM:SS` (Aurora uses both) |
| `created_at` | string | ✓ | Aurora-internal timestamp; not stored |
| `grade` | string | ✓ | Font grade, e.g. `"6A"`, `"6A/V3"`. Converted to integer difficulty ID |

### 2.3 Attempt / Bid (one entry per logged session without a top)

Same shape as ascent **minus** `stars` and `grade`.

### 2.4 Circuit (= CruxCoach climb list)

| Field | Type | Required | Notes |
|---|---|---|---|
| `name` | string | ✓ | List name |
| `color` | string | ✓ | Hex without `#`, e.g. `"FF0000"` |
| `created_at` | string | ✓ | ISO 8601 |
| `description` | string |  | Optional |
| `is_private` | bool |  | Always `false` on import (CruxCoach lists are private by design) |
| `climbs` | string[] | ✓ | Climb **names**, resolved against local board DB |

### 2.5 Climb (= user-created / draft climb)

| Field | Type | Required | Notes |
|---|---|---|---|
| `name` | string | ✓ | Display name |
| `layout` | string | ✓ | Layout name, e.g. `"Kilter Board Original"`. Resolved to `layout_id` |
| `created_at` | string | ✓ | ISO 8601 |
| `is_draft` | bool/null |  | `true` = always upsert; falsy = treated as draft anyway (CruxCoach has no published-climb path for user content) |
| `holds` | array | ✓ | `{x:int, y:int, role:string}` per hold; `role` ∈ `{start, middle, finish, foot}` |
| `description` | string |  | Optional |

---

## 3. Mapping to CruxCoach Data Model

| Aurora export | CruxCoach target | Notes |
|---|---|---|
| `user.username` | (not stored) | Used as `setter_username` for imported climbs |
| `ascents[]` | `aurora_ascent` rows | Name resolution required |
| `attempts[]` | `aurora_bid` rows | Name resolution required |
| `circuits[]` | `climb_list` + `climb_list_entry` | New columns required (§4.2) |
| `climbs[]` | `local_climb` (FEAT-003) | Requires FEAT-003 to ship first |
| `likes[]` | (dropped) | No CruxCoach analogue in v0.1.4 |
| `walls/blocks/beta_links/agreements/follows` | (dropped) | At parse time |

---

## 4. Schema Additions

All migrations are **additive** (new columns, new indexes) so existing
rows are preserved. No data backfill needed at migration time — `NULL`
means "not from Aurora-JSON-import".

### 4.1 Idempotency markers on existing tables

Tables were renamed by FEAT-006 (`aurora_*` → plural snake_case). Schema
target is post-rename; FEAT-005 lands as `secure/5.sqm` migrating from
v5 → v6:

```sql
-- secure DB
ALTER TABLE ascents ADD COLUMN external_id TEXT;
ALTER TABLE bids    ADD COLUMN external_id TEXT;
CREATE UNIQUE INDEX idx_ascents_external ON ascents(external_id)
    WHERE external_id IS NOT NULL;
CREATE UNIQUE INDEX idx_bids_external    ON bids(external_id)
    WHERE external_id IS NOT NULL;
```

`external_id` is namespaced and deterministic (§5.1) so re-importing the
same file produces the same value, and the unique index turns "did we
already import this row?" into a single-statement upsert. Note that
`is_mirror`, `is_benchmark`, `attempt_id`, `bid_count`, `quality`,
`comment` are already on the post-FEAT-006 schema — no extra ALTER.

### 4.2 Circuit / list extensions

```sql
-- secure DB
ALTER TABLE climb_lists ADD COLUMN description TEXT;
ALTER TABLE climb_lists ADD COLUMN color       TEXT;
ALTER TABLE climb_lists ADD COLUMN external_id TEXT;
CREATE UNIQUE INDEX idx_climb_lists_external ON climb_lists(external_id)
    WHERE external_id IS NOT NULL;
```

`color` is purely cosmetic — Aurora circuits carry a hex color; CruxCoach
list UI can ignore it for v0.1.4 and pick it up later.

### 4.3 Draft-climb storage (FEAT-003 unified table)

No new table. FEAT-003 unified user-authored climbs into the existing
`climbs` table on the board DB with `source = 'local'` and
`origin = 'cruxcoach'`. The Aurora importer follows the same pattern:
imported `climbs[]` rows land in `climbs` with those provenance markers,
plus `external_id` (FEAT-006-renamed table; new column added by the
same `5.sqm` migration above).

```sql
-- board DB (note: separate DB file, not secure DB)
ALTER TABLE climbs ADD COLUMN external_id TEXT;
CREATE UNIQUE INDEX idx_climbs_external ON climbs(external_id)
    WHERE external_id IS NOT NULL;
```

Per boardsesh's empirical handling, `is_draft = true` rows from the
export are upsert-on-`external_id`; `is_draft = false / null` rows are
INSERT…ON CONFLICT(external_id) DO NOTHING (preserving the existing row
if a published climb of that name already exists in the catalogue).

---

## 5. Import Algorithm

### 5.1 Deterministic external IDs

```kotlin
fun externalIdAscent(climbUuid: String, angle: Int, climbedAtIso: String) =
    "aurora-json:ascent:" + sha256("$climbUuid:$angle:$climbedAtIso").take(32)

fun externalIdBid(climbUuid: String, angle: Int, climbedAtIso: String) =
    "aurora-json:bid:" + sha256("$climbUuid:$angle:$climbedAtIso").take(32)

fun externalIdCircuit(name: String, createdAtIso: String) =
    "aurora-json:circuit:" + sha256("$name:$createdAtIso").take(32)

fun externalIdLocalClimb(layoutId: Int, name: String, createdAtIso: String) =
    "aurora-json:climb:" + sha256("$layoutId:$name:$createdAtIso").take(32)
```

The `aurora-json:` prefix keeps these IDs distinct from Kilter-API or
Nostr-sourced IDs. Hash inputs deliberately exclude the user pubkey:
re-importing the same Aurora export under a different Nostr identity
should still dedup (otherwise key rotation creates phantom duplicates).

### 5.2 Step ordering

1. **Parse + validate** the JSON file (Zod equivalent: kotlinx-serialization
   strict-decode with `ignoreUnknownKeys = true`). Detect board-type mismatch
   by inspecting the first climb's `layout` field; warn but do not block.
2. **Import draft climbs** (FEAT-003) — populate `local_climb` first so
   ascents that reference local climbs by name can resolve them.
3. **Resolve climb names** — build `Map<String, ClimbUuid>` by querying
   `aurora_climb.name` (board DB, public climbs preferred) and
   `local_climb.name` (secure DB, user drafts). Public takes precedence over
   draft when both match. Names that fail to resolve are collected.
4. **Import ascents** — for each ascent, compute `external_id`,
   `INSERT OR IGNORE`. The unique index does the dedup.
5. **Import bids** — same pattern.
6. **Import circuits** — upsert `climb_list` row by `external_id`, then
   replace `climb_list_entry` rows for that list with the resolved UUIDs.
   If **all** climb names in a circuit fail to resolve, leave the existing
   entries alone (don't blow away a previously-resolved import).
7. **Build inferred sessions** — group all `aurora_ascent` + `aurora_bid`
   rows missing a session assignment by 4h-gap; create `BoardSession`
   rows with deterministic UUID v5
   (`uuid5("session", "$userPubkey:$firstTickMillis")`).

Steps 2–7 happen inside one SQLCipher transaction per step (not one big
transaction — a 50k-ascent import shouldn't lock the secure DB for
minutes). Per-step failure is logged but doesn't abort the next step.

### 5.3 Name resolution

```sql
-- Two-stage lookup, with batching to keep IN clauses bounded.
-- Stage 1: public board climbs (preferred)
SELECT name, uuid FROM climbs
WHERE source = 'kilter' AND name IN (?, ?, ...);
-- Stage 2: user's own draft climbs (fallback)
SELECT name, uuid FROM climbs
WHERE source = 'local' AND created_by_pubkey = ? AND name IN (?, ?, ...);
```

Batch IN clauses at 500 entries (SQLite `SQLITE_MAX_VARIABLE_NUMBER`
default is 999; 500 is well under). Same-name collisions in the public
DB are resolved by ascensionist count (highest wins) — boardsesh's
proven tiebreaker. The `created_by_pubkey` filter on stage 2 keeps
multi-identity installs from cross-resolving against another user's
drafts on the same device.

### 5.4 Unresolved climbs

Names that fail both lookups are written to the import-result struct:

```kotlin
data class ImportResult(
    val ascents:   ImportCounts,
    val bids:      ImportCounts,
    val circuits:  ImportCounts,
    val climbs:    ImportCounts,
    val unresolvedClimbNames: List<String>  // first 50, dedup'd
)

data class ImportCounts(val imported: Int, val skipped: Int, val failed: Int)
```

The UI shows the first 20 unresolved names verbatim with a "+N more"
tail. Common cause is a name change in Kilter's public DB after the
user's last sync; running a board-DB sync and re-importing usually
resolves it.

### 5.5 Timestamp normalization

Aurora exports use a mix of ISO 8601 (`2024-01-15T10:30:00Z`) and a
space-separated format (`2024-01-15 10:30:00`). Both must normalize to
the same string before being fed into `external_id` hashing — otherwise
re-importing a file written by a different Aurora client produces
phantom duplicates.

```kotlin
fun normalizeTimestamp(raw: String): String {
    val cleaned = raw.replace(' ', 'T').trim()
    val withZ = if (cleaned.endsWith('Z') || cleaned.contains('+')
        || cleaned.matches(Regex(".+-\\d{2}:?\\d{2}$"))) cleaned else "${cleaned}Z"
    return Instant.parse(withZ).toString()  // canonical ISO 8601 with Z
}
```

Microseconds are truncated to milliseconds (kotlinx-datetime
`Instant.parse` already does this).

---

## 6. UI Integration

Three entry points (Settings, Onboarding step 3, v0.1.4 WhatsNew dialog),
**one shared content composable** (§6.4). Hosts differ — content does
not. This keeps the explainer + email-CTA + file-picker flow consistent
no matter where the user enters from, and means strings/copy live in a
single place.

### 6.1 Settings — primary entry point

In the existing data-management settings section (where `DataImportScreen`
already lives), add an "Aurora JSON Import" tile next to the existing
backup-restore tile. Tap → `AuroraMigrationScreen` — a full-screen
`Scaffold` whose body is `MigrationFlowContent` (§6.4).

File picker uses the same `ActivityResultContracts.OpenDocument` pattern
already proven in `DataImportScreen.kt:48-52`:

```kotlin
val launcher = rememberLauncherForActivityResult(OpenDocument()) { uri ->
    uri?.let { vm.previewFile(it) }
}
launcher.launch(arrayOf("application/json", "text/plain"))
```

(`text/plain` included because Aurora's email export sometimes lands with
the wrong MIME on some Android file pickers.)

### 6.2 Onboarding step 3 — opt-in deep dive

Step 3 (`KilterStep` in `OnboardingScreen.kt:559`) currently offers
Kilter OAuth login only. **Step 3 itself stays minimal** — adding a full
"what happened with Aurora" explainer to the default onboarding path
would slow down every new user, including the majority who have never
heard of Aurora. Instead, add a single secondary card below the OAuth
card:

```
┌─────────────────────────────────────┐
│ [Existing OAuth card — unchanged]   │
└─────────────────────────────────────┘
┌─────────────────────────────────────┐
│ Aurora-Daten? Mehr erfahren →       │  ← new
│ Logbuch aus dem alten Kilter-App    │
│ übernehmen                          │
└─────────────────────────────────────┘
```

Tapping the card opens a `ModalBottomSheet` whose body is
`MigrationFlowContent` (§6.4). Sheet height: `0.92f` (near-fullscreen,
draggable to dismiss). Both the OAuth card, the migration card, and the
existing skip-hint card remain independently usable — completing
migration does not auto-advance onboarding; the user still taps "Done"
to finish.

State changes in `OnboardingState`:

```kotlin
data class OnboardingState(
    // existing fields …
    val auroraSheetOpen: Boolean = false,
    val auroraImport:    AuroraImportState = AuroraImportState.Idle,
)
```

`AuroraImportState` (sealed) is the same type the settings host uses,
read from the shared `AuroraMigrationViewModel`.

### 6.3 v0.1.4 "what's new" dialog — discovery entry point

Add to `WhatsNewItems.registry` in `WhatsNew.kt`:

```kotlin
val AURORA_JSON_IMPORT = WhatsNewItem(id = "aurora-json-import", sinceVersionCode = 5)

val registry: List<WhatsNewItem> = listOf(
    NOSTR_BACKUP,        // sinceVersionCode = 4 (0.1.3)
    AURORA_JSON_IMPORT,  // sinceVersionCode = 5 (0.1.4)
)
```

Create `AuroraJsonImportWhatsNewDialog.kt` mirroring
`NostrBackupWhatsNewDialog.kt`. Two buttons:
- **"Jetzt importieren"** — dismisses dialog and navigates to
  `AuroraMigrationScreen` (§6.1) so the user lands on the same
  full-screen flow they would reach from settings
- **"Später"** — dismisses, marks as seen via `setLastSeenAppVersionCode`

No toggle, no settings to capture in the dialog itself.

### 6.4 Shared `MigrationFlowContent` composable

Single source of truth for everything the user sees during migration —
embedded in the settings screen (§6.1) and the onboarding bottom sheet
(§6.2). Vertical-scroll column with three numbered sections:

```
┌─ MigrationFlowContent ──────────────┐
│                                     │
│ Was ist passiert?                   │  ← short explainer (2–3 lines)
│                                     │
│ ① Daten anfordern                  │  ← mailto: button (prefilled
│   [E-Mail an Aurora vorbereiten]    │      subject + body)
│                                     │
│ ② Datei importieren                │  ← file picker button
│   [Aurora-JSON wählen…]             │
│                                     │
│   (preview / progress / result      │  ← inline below the picker once
│    rendered here once a file is     │      a file is selected
│    selected)                        │
│                                     │
└─────────────────────────────────────┘
```

The mailto: button uses an `Intent.ACTION_SENDTO` with a prefilled
subject and body in the user's app locale (de or en), so Aurora's
support contact gets a parseable, consistent request:

```kotlin
val intent = Intent(Intent.ACTION_SENDTO, "mailto:peter@auroraclimbing.com".toUri())
    .putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.aurora_migration_email_subject))
    .putExtra(Intent.EXTRA_TEXT,    ctx.getString(R.string.aurora_migration_email_body))
ctx.startActivity(intent)
```

The preview / progress / result composables (`AuroraImportPreviewCard`,
`AuroraImportProgressList`, `AuroraImportResultSummary`) are
self-contained — they render whatever current state the
`AuroraMigrationViewModel` exposes, and they are placed inline beneath
the file-picker button. There is no separate "import-running" full-screen
takeover; the user stays in the same flow throughout.

`MigrationFlowContent` takes no platform-specific parameters (no Modal,
no Scaffold) and is therefore trivially previewable in `@Preview`
composables — important for the long copy that needs translation review.

---

## 7. Localized Strings

Two key namespaces:

- `aurora_migration_*` — strings owned by `MigrationFlowContent` (§6.4):
  the explainer header, the two numbered steps, the mailto button and
  its prefilled subject/body. Used identically by both the onboarding
  bottom sheet and the settings full-screen host.
- `aurora_json_import_*` — strings owned by the inner picker / preview /
  progress / result composables. These render inside
  `MigrationFlowContent` after a file is chosen.
- `whatsnew_aurora_json_import_*` — strings owned by the v0.1.4
  WhatsNew dialog (§6.3) only.
- `onboarding_aurora_migration_card_*` — strings owned by the entry-point
  card on onboarding step 3 (§6.2).

Both `values/strings.xml` and `values-de/strings.xml` are updated
together (per CLAUDE.md).

### 7.1 New string keys

```xml
<!-- values/strings.xml (English) -->

<!-- Onboarding step 3 entry-point card (§6.2) -->
<string name="onboarding_aurora_migration_card_title">Have Aurora data? Learn more</string>
<string name="onboarding_aurora_migration_card_desc">Bring your logbook over from the old Kilter app.</string>

<!-- Shared MigrationFlowContent (§6.4) -->
<string name="aurora_migration_explainer_title">What happened</string>
<string name="aurora_migration_explainer_body">The Aurora backend behind the old Kilter app is offline. Your ascents and lists aren\'t lost — Aurora returns them as a JSON file on email request.</string>
<string name="aurora_migration_step1_title">1. Request your data</string>
<string name="aurora_migration_step1_body">Email Aurora to ask for your export. They reply with a JSON file containing your ascents, projects, and lists.</string>
<string name="aurora_migration_step1_button">Open email</string>
<string name="aurora_migration_email_subject">Data Export Request</string>
<string name="aurora_migration_email_body">Hi Peter,\n\nI\'d like to request a JSON export of my Aurora climbing data.\n\nThanks!</string>
<string name="aurora_migration_step2_title">2. Import the file</string>
<string name="aurora_migration_step2_body">Once Aurora replies, save the attachment and pick it here.</string>

<!-- Inner picker / preview / progress / result (§5–6) -->
<string name="aurora_json_import_pick_file">Choose Aurora JSON…</string>
<string name="aurora_json_import_preview_title">Ready to import</string>
<string name="aurora_json_import_preview_summary">%1$d ascents, %2$d projects, %3$d lists, %4$d own climbs</string>
<string name="aurora_json_import_preview_reassure">Re-importing the same file won\'t create duplicates.</string>
<string name="aurora_json_import_running">Importing…</string>
<string name="aurora_json_import_done_title">Import complete</string>
<string name="aurora_json_import_unresolved_title">%1$d climb names could not be matched</string>
<string name="aurora_json_import_unresolved_hint">These climbs may have been renamed in the Kilter database. Try a board-data sync and re-import.</string>
<string name="aurora_json_import_error">Import failed: %1$s</string>

<!-- WhatsNew dialog (§6.3) -->
<string name="whatsnew_aurora_json_import_title">Import your Aurora data</string>
<string name="whatsnew_aurora_json_import_body">Aurora is sending users their data on request — drop the JSON file into CruxCoach and your old logbook is back.</string>
<string name="whatsnew_aurora_json_import_cta">Import now</string>
<string name="whatsnew_aurora_json_import_later">Later</string>
```

```xml
<!-- values-de/strings.xml (Deutsch) -->

<string name="onboarding_aurora_migration_card_title">Aurora-Daten? Mehr erfahren</string>
<string name="onboarding_aurora_migration_card_desc">Logbuch aus dem alten Kilter-App übernehmen.</string>

<string name="aurora_migration_explainer_title">Was ist passiert</string>
<string name="aurora_migration_explainer_body">Das Aurora-Backend hinter dem alten Kilter-App ist offline. Deine Begehungen und Listen sind nicht verloren — Aurora schickt sie auf E-Mail-Anfrage als JSON-Datei.</string>
<string name="aurora_migration_step1_title">1. Daten anfordern</string>
<string name="aurora_migration_step1_body">Schreib Aurora eine kurze E-Mail. Du bekommst eine JSON-Datei mit deinen Begehungen, Projekten und Listen.</string>
<string name="aurora_migration_step1_button">E-Mail öffnen</string>
<string name="aurora_migration_email_subject">Anfrage Daten-Export</string>
<string name="aurora_migration_email_body">Hallo Peter,\n\nich bitte um den JSON-Export meiner Aurora-Daten.\n\nVielen Dank!</string>
<string name="aurora_migration_step2_title">2. Datei importieren</string>
<string name="aurora_migration_step2_body">Sobald Aurora geantwortet hat, speichere den Anhang und wähle ihn hier aus.</string>

<string name="aurora_json_import_pick_file">Aurora-JSON wählen…</string>
<string name="aurora_json_import_preview_title">Bereit zum Import</string>
<string name="aurora_json_import_preview_summary">%1$d Begehungen, %2$d Projekte, %3$d Listen, %4$d eigene Climbs</string>
<string name="aurora_json_import_preview_reassure">Erneutes Importieren derselben Datei erzeugt keine Duplikate.</string>
<string name="aurora_json_import_running">Wird importiert…</string>
<string name="aurora_json_import_done_title">Import abgeschlossen</string>
<string name="aurora_json_import_unresolved_title">%1$d Climbs konnten nicht zugeordnet werden</string>
<string name="aurora_json_import_unresolved_hint">Diese Climbs wurden möglicherweise in der Kilter-Datenbank umbenannt. Versuche einen Board-Datensync und importiere erneut.</string>
<string name="aurora_json_import_error">Import fehlgeschlagen: %1$s</string>

<string name="whatsnew_aurora_json_import_title">Aurora-Daten übernehmen</string>
<string name="whatsnew_aurora_json_import_body">Aurora schickt Nutzern auf Anfrage ihre Daten — leg die JSON-Datei in CruxCoach ab und dein altes Logbuch ist wieder da.</string>
<string name="whatsnew_aurora_json_import_cta">Jetzt importieren</string>
<string name="whatsnew_aurora_json_import_later">Später</string>
```

> **Email subject/body language note** — `aurora_migration_email_body`
> follows the user's app locale. Aurora's support contact is
> English-speaking, so a German body is likely to delay the response.
> Implementation should consider always sending the EN body regardless
> of app locale (and translating only the in-app button label) — open
> question §10.

---

## 8. Validation & Error Handling

### 8.1 File-level validation

| Check | Behavior on fail |
|---|---|
| File size ≤ 200 MB | Block with toast: "File too large (max 200 MB)" |
| MIME hints `application/json` or `text/plain` | Allow (some pickers misreport) |
| `JSON.parse` succeeds | Block with toast: "Could not read file as JSON" |
| Top-level shape matches schema (Zod-equivalent) | Block, surface first 3 missing fields |
| `user.username` present | Block: "Not an Aurora export file" |
| First climb's `layout` matches selected board type | Warn (toast) but allow |

### 8.2 Per-row failures

Per-row failures (unresolvable climb name, malformed timestamp,
unknown layout) increment the `failed` count in `ImportCounts` and are
**not** propagated as exceptions. The import always runs to completion;
the result screen tells the user what didn't make it.

### 8.3 Transaction boundaries

One SQLCipher transaction per step (climbs / ascents / bids / circuits /
sessions). A failure mid-step rolls back that step's writes only. The UI
surfaces step-level failures distinctly from row-level failures —
"3 of 4 sections imported, circuits failed" is more actionable than
"50 of 51 things imported".

---

## 9. Privacy & DSGVO

- **All imported data lives in the secure (SQLCipher) DB**. Same
  encryption story as native Kilter sync — no special handling required.
- **Aurora export files contain a username and email address.** The
  importer reads `user.username` (used as `setter_username` for draft
  climbs only — already the case for native climb-creator output) but
  **drops `user.email_address` and `user.created_at`** at parse time. They
  never reach the database, never reach a log line.
- **The importer does not phone home.** No telemetry, no remote upload,
  no Nostr publishing as a side effect of import. Importing a 50k-ascent
  file is a purely local operation.
- **Re-import is non-destructive.** The dedup story (§5.1) means
  re-running the importer on the same file produces zero duplicates and
  zero deletions. No "wipe and re-import" path; users wanting that should
  use the existing `Datenverwaltung → Logbook löschen` flow first.
- The "request your Aurora data" hint in the UI (§7.1) names the email
  address `peter@auroraclimbing.com` exactly as Aurora has communicated
  it publicly. We do not auto-send anything on the user's behalf.

---

## 10. Open Questions

- **Bids table presence** — `aurora_bid` is referenced in
  `getUserLogbookPage` UNION queries but not directly read in this
  spec's exploration. Confirm it follows the same shape as
  `aurora_ascent` (specifically: it has the same `external_id` migration
  surface) before implementation. If `aurora_bid` is materially
  different, §4.1 needs revision.
- **Inferred-session ownership** — does FEAT-002 (Nostr backup) already
  serialize sessions? If yes, deterministic session IDs (§5.2 step 7)
  must match the format already in use, or backup/import will produce
  divergent session UUIDs for the same underlying ticks.
- **Layout-name → layout-id mapping table** — Aurora exports use
  human-readable layout names (`"Kilter Board Original"`). The
  resolution table exists implicitly in board DB sync code but is not a
  formal lookup. Worth surfacing as `getLayoutByName()` query on
  `AuroraBoard.sq` before implementation.
- **Tension layouts** — if the exported file's first climb is a Tension
  layout, do we reject (single board target) or accept and route ascents
  to the Tension board scope? CruxCoach v0.1.x is Kilter-focused; the
  Aurora export does not split by board. Decision pending.
- **Multi-account imports** — what happens if the user runs the importer
  again under a different Nostr identity? Each identity has its own
  secure DB file (`secure_<pubkey-prefix>.db`), so the imported data
  lands in the per-identity scope automatically — but the determinism
  prefix (§5.1) is identity-independent, so the same Aurora file is
  imported into each identity from scratch (no cross-identity leak,
  also no cross-identity dedup). Confirm this is the desired
  multi-account semantics before implementation.
- **Mailto body language** — `aurora_migration_email_body` (§7.1) is
  localized to the app's current locale. Aurora's support is
  English-speaking, so a German body likely slows the response.
  Decision: keep locale-bound body (consistent with rest of the app),
  or always send EN body regardless of app locale (faster reply,
  surprises the user when their app is in DE)? Lean towards always-EN
  for the body, locale-bound for the in-app button label.

---

## 11. Dependencies

```kotlin
// No new gradle dependencies required.
// All required libraries are already in the project:
//
// - kotlinx-serialization (JSON parsing)
// - kotlinx-datetime (Instant.parse for timestamp normalization)
// - SQLDelight + SQLCipher (storage, transactions)
// - androidx.activity.result (ActivityResultContracts.OpenDocument)
// - java.security.MessageDigest (SHA-256 for external_id hashing)
```

---

## 12. Implementation Notes

### 12.1 Batching

The importer runs in-process on the device — there is no HTTP-transport
size limit to work around. Two batching boundaries matter:

- **SQLite transaction budget** — batch ~500 rows per SQLDelight
  transaction. Smaller batches add transaction overhead; larger batches
  hold the SQLCipher write lock too long and starve UI reads of the
  same DB.
- **UI progress cadence** — emit a progress update every ~100 rows
  imported. Anything finer is invisible; anything coarser feels stuck
  on the 50k-ascent path.

### 12.2 Stable IDs across re-import

The deterministic `external_id` scheme (§5.1) is the only contract that
keeps re-import idempotent. Specifically: the hash inputs are **content,
not provenance** (no user pubkey, no import timestamp, no file checksum).
Two files containing the same logical ascent — even if they were
exported from Aurora at different times, with different surrounding
metadata — produce the same `external_id` and dedup cleanly.

This also means: deleting an imported ascent from CruxCoach and then
re-importing the same file **will recreate it**. There is no soft-delete
or tombstone for imported rows in v0.1.4. Users wanting permanent
deletion should clear the source file before re-importing.
