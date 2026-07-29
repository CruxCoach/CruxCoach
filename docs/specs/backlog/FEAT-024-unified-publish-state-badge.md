---
status: backlog
---
# Feature Spec: Unified Publish-State Signal across Browser + Detail (backlog)

> **Status:** Backlog — captured 2026-05-14. No release target.
> The Browser's "Entwurf" badge and the Detail-screen's
> provenance + delete-action routing both answer the same
> conceptual question — *"has this climb actually been
> published to a Nostr relay yet?"* — but evaluate it via
> two different SQL projections. Today the projections
> agree on every state reachable through normal flows; the
> spec captures the design move that would unify them on a
> single canonical signal (`nostr_event_id IS NULL`) so a
> future state-machine drift can't produce a divergent
> badge in one screen vs the other.
>
> **Depends on:**
> - FEAT-003 (Climb Creator + Community Climbs) — defines
>   the publish lifecycle the badge tracks.
>
> **Relates to:**
> - FEAT-005 (Aurora JSON Import) — the test bed where the
>   current divergence first surfaced (Aurora-imported
>   drafts go through `insertLocalDraft` and inherit
>   `source='local' AND sync_status='draft'`; the Detail
>   screen and the Browser arrive at "this is a draft" via
>   different SQL fields, which is correct today but isn't
>   *enforced* to stay correct).
> - FEAT-023 (Cross-Board Lists + Send Concept) —
>   independent gap, but the same design pass that
>   reconciles board identity could fold the publish-state
>   reconciliation in.

---

## 1. Today's two projections

The Browser's `ClimbCard` shows the "Entwurf" badge when:

```kotlin
showDraftBadge = climb.source == "local" &&
    (climb.syncStatus == null ||
     climb.syncStatus == "draft" ||
     climb.syncStatus == "failed")
```

The Detail-screen's provenance badge ("Auf Kilter" / "Nur
CruxCoach-Community") shows when:

```kotlin
hasLivePublication = !climb.nostrEventId.isNullOrBlank()
if (climb.origin == "cruxcoach" && hasLivePublication) {
    /* show provenance chip */
}
```

…and the Detail-screen's overflow menu picks "Entwurf
löschen" vs "Veröffentlichung löschen" via the same
`hasLivePublication` flag.

So the same conceptual decision is gated by:

- Browser: `source` + `sync_status` (string-comparison
  against three string constants)
- Detail: `nostr_event_id` (single nullness check)

## 2. Why it doesn't cause user-visible drift today

`markClimbPublishedNostr` writes both `nostr_event_id` and
`sync_status='published_nostr'` in the same UPDATE.
`upsertCommunityClimb` (live-sub) writes both
`nostr_event_id=<event>` and `sync_status='synced'`.
`insertLocalDraft` writes neither (`nostr_event_id` stays
NULL by schema default; `sync_status='draft'` is the
hardcoded constant in the SQL block).

So the matrix of *reachable* `(source, sync_status,
nostr_event_id)` triples collapses to:

| source | sync_status | nostr_event_id | Real source |
|--------|-------------|-----------------|-------------|
| local | draft | NULL | freshly authored, never published |
| local | failed | NULL | publish-attempt pre-mark or relay reject |
| local | published_nostr | set | locally published, no live-sub echo yet |
| nostr | synced | set | received via community live-sub |
| kilter | synced (default) | NULL | Kilter API import (badge irrelevant — origin gate hides it) |

Both projections agree on every row in this table:

- Browser's `source='local' AND sync_status in
  (null/draft/failed)` ↔ Detail's `nostr_event_id IS
  NULL` — both true on rows 1+2, false on rows 3+4+5.
- The hypothetical row `(local, published_nostr,
  NULL)` — where the two would disagree — is
  unreachable: `markClimbPublishedNostr` never sets
  `sync_status` without also setting `nostr_event_id`.

## 3. Why we should still unify

The unreachability argument is empirical, not enforced by
the schema. A future code path (a third write site, a
backup-restore that splits the two columns, an
import-conflict resolver that merges rows from different
events) could produce the inconsistent triple without any
SQL guard refusing it. Today's correctness depends on
every contributor remembering that the two screens use
different fields for the same decision.

A single canonical signal (`nostr_event_id IS NULL` ⇒
"draft") makes the rule unambiguous: a row has a Nostr
publication iff its `nostr_event_id` is set. Everything
else is implementation history.

## 4. Implementation sketch

The blocker is the `climb_browse` VIEW: today it omits
`nostr_event_id`, so `mapBrowse` can't populate
`ClimbWithStats.nostrEventId` for the Browser path.

Steps:

1. **Schema migration (.sqm)** — extend `climb_browse`
   VIEW to include `c.nostr_event_id`. SQLDelight regen
   surfaces the new column on the row type.
2. **`mapBrowse` update** — pass `it.nostr_event_id` into
   `mapClimb` so `ClimbWithStats.nostrEventId` is
   populated on the Browser's queries too.
3. **`ClimbCard` simplification** — replace the
   three-string-or-null `sync_status` check with
   `climb.source == "local" && climb.nostrEventId == null`.
   Keep the `source == "local"` guard so a Kilter row
   imported with `nostr_event_id=NULL` (rare but legal)
   still doesn't get a draft badge.
4. **Detail-screen — no change.** Already on the canonical
   signal.
5. **Document the invariant** in a SQL comment on the
   `nostr_event_id` column: *"set iff at least one
   publish reached at least one relay; consumers may
   treat NULL as 'draft / never published'"*.

## 5. Non-goals

- Removing `sync_status`. It still carries useful detail
  for the publish state machine (`pending_send` vs
  `failed` vs `synced` are distinct lifecycle ticks even
  when collapsed to "is published" for badge purposes).
- A migration that retroactively reconciles existing
  rows. The empirical reachability argument from §2 means
  no production row has the inconsistent triple; a
  migration would be a no-op everywhere.
- Changing the editor's `insertLocalDraft` SQL. It
  already sets `sync_status='draft'` correctly; this
  spec just stops *reading* that field for the
  presentation decision.

## 6. Success criteria

- A single SQL invariant enforces "published iff
  `nostr_event_id IS NOT NULL`" across every consumer.
- A regression that introduces a third write path
  (or a backup-restore quirk) cannot produce a row
  where the Browser shows "Entwurf" but the Detail
  screen offers "Veröffentlichung löschen", or vice
  versa.
- ClimbCard's draft-badge condition shrinks to one
  source check + one nullness check, removing the
  triple-string allowlist.
