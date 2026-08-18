---
status: implemented
queue: done
base: 0.2.2
depends_on: []
created: 2026-07-15
updated: 2026-07-18
---

# Feature Spec: Playable lists with optional training plans

> **Decision:** CruxCoach exposes one saved-object concept: a **list**. Every
> list can be played. Ordering, repetitions, pinned angles, and explicit rest
> steps are an optional **training plan** attached to that list, not a second
> kind of saved object.
>
> **Relates to:** FEAT-023 (cross-board lists), FEAT-046 (future player
> lifecycle and gesture upgrades)

## 1. Problem and goals

The pre-release 0.2.2 implementation separated plain lists from playlists.
That leaked storage details into the hub, add dialog, deletion, sharing, and
playback. It also made a climb's list membership ambiguous when a playlist
repeated it.

The final 0.2.2 model must provide:

- one hub and one list-detail surface;
- unique, predictable list membership;
- playback for every non-empty list except the built-in Ignored list;
- an optional ordered training plan with repetitions, pinned angles, and rest
  steps;
- per-list playback defaults for order, automatic advance, and rest;
- explicit handling of unavailable or mixed-board content before playback;
- lossless local backup and share links for training plans.

The generated-list workflow remains a list workflow. Generator parameters are
metadata on a list and can create a prepared training plan, but do not create a
different object type.

## 2. Data model

### 2.1 List membership

`climb_lists` has no `kind` column. `climb_list_entries` represents ordinary
membership and retains the unique primary key `(list_id, climb_uuid)`.

Consequences:

- adding from the browser is always an idempotent membership toggle;
- removing a climb from a list is unambiguous;
- hub and detail counts report distinct list members;
- playback configuration cannot accidentally create or delete membership
  duplicates.

### 2.2 Playback settings

Every row in `climb_lists` carries:

| Column | Values | Default |
|---|---|---|
| `playback_order` | `list`, `shuffle` | `list` |
| `playback_advance` | `manual`, `after_send`, `after_log` | `manual` |
| `playback_rest_seconds` | `0..3600` | `0` |

`after_send` advances only after a successful send is logged. `after_log`
advances after any logged attempt, including a send. Manual mode never
advances as a side effect of logging.

### 2.3 Optional training plan

`list_playback_steps` is a separate ordered sequence belonging to a list. A
step is either:

- a climb reference with an optional pinned angle; or
- a rest duration.

The plan may repeat a climb. Removing or resetting a plan never removes normal
list members. Creating or replacing a plan ensures every referenced climb is
also a list member. Removing a climb from the list removes all plan references
to it, so no hidden dangling plan content remains.

### 2.4 Release migration

0.2.2 is the first public release with playback and training plans. Migration
`10.sqm` therefore moves the released 0.2.1 list schema directly to this final
model. There is deliberately no migration for the discarded pre-release
`kind='playlist'` schema. Developer installations that ran that intermediate
schema must clear their local app data.

## 3. User experience

### 3.1 Lists hub and detail

- The hub has one **Lists** section and one card type.
- Generated lists and lists with a prepared training plan may show quiet
  metadata, but they are not separate categories.
- Every non-empty list except Ignored has a play action in its detail screen.
- Every editable list can be renamed or deleted. Built-ins retain their
  existing restrictions.
- The add-to-list dialog only changes membership. It never silently appends a
  repetition to a training plan.

### 3.2 Quick play

Starting a list opens a compact setup sheet:

- source: normal list, or its training plan when one exists;
- normal-list order: list order or shuffle;
- default rest between climbs;
- advance: manual, after send, or after every logged attempt.

The choices are persisted on that list. The sheet explains automatic advance
where needed and remains vertically scrollable on small screens.

Normal-list playback uses the entries currently visible after list filtering.
It requires one concrete board configuration. Mixed configurations are not
silently reduced to one board; starting is blocked with actionable feedback.

### 3.3 Training-plan editor

The plan editor supports:

- ordered climb and rest steps;
- repeated climb steps;
- pinned angles;
- move up/down controls;
- editable rest durations;
- appending newly added list members without disturbing existing work;
- reset from current membership;
- removing only the plan while preserving the list.

The screen states this ownership rule directly: editing the plan does not edit
ordinary list membership.

### 3.4 Missing catalogue data

Unavailable climb references are counted and shown before playback. They are
never removed from membership or a training plan as a side effect of a partial
catalogue download. A session may skip unresolved references only after the UI
has made the count explicit; it must not silently reinterpret the saved list.

## 4. Sharing, import, and backup

- Share-link V1 remains readable for existing climbs-only links.
- Share-link V2 preserves the full training plan: ordered climb/rest steps,
  pinned angles, order mode, advance mode, and default rest.
- Imported V1 content becomes a normal list with membership and a matching
  plan when appropriate.
- Backup exports membership separately from plan steps and playback settings.
- Restore merges lists by name, keeps membership idempotent, and restores a
  supplied plan atomically.
- The backup wire model retains nullable legacy fields only for compatibility;
  the app itself has no playlist kind.

## 5. Playback interaction

The existing session coordinator remains the single owner of active playback.
Its logging callback applies the selected advance rule only after the log was
successfully written. Ad-hoc queues and joined sessions continue to default to
manual advance unless explicitly configured by their own flow.

Foreground-service persistence, process-death resume, lock-screen controls,
and finger-tracking gestures remain the separate FEAT-046 scope. This feature
does not imply that those follow-up upgrades are complete.

## 6. Acceptance criteria

1. A climb can occur at most once in normal membership and any number of times
   in its training plan.
2. Adding or removing membership never creates a plan repetition implicitly.
3. Removing/resetting a plan preserves all normal members.
4. Removing a normal member removes every corresponding plan step.
5. Every eligible non-empty list can start normal-list playback.
6. A list with a plan offers both normal-list and training-plan playback.
7. Normal-list playback supports list/shuffle order, default rest, and all
   three advance modes.
8. Mixed concrete board configurations block start instead of silently
   dropping climbs.
9. Missing catalogue references are reported explicitly and retained.
10. Share-link V2 and secure backup round-trip repetitions, rests, angles, and
    playback settings without duplicating membership on repeated import.
11. Visible product copy uses **List** and **Training plan/Trainingsablauf**;
    “playlist” may remain only in internal technical class and route names.
12. SQLDelight migration verification and Android/shared unit suites pass.

## 7. Tests

- SQL-backed repository tests cover unique membership, repeated plan steps,
  plan replacement/removal, and playback settings.
- Generator pipeline tests cover generated membership plus prepared plans.
- Coordinator tests cover `manual`, `after_send`, and `after_log` transitions.
- Share-link tests cover V1 compatibility, V2 fidelity, invalid rest-only
  payloads, and UTF-8-safe names.
- Secure backup round-trip tests cover membership, repetitions, rests,
  settings, and idempotent re-import.

## 8. Decisions

- Lists and training plans are deliberately not two feature types.
- A plan belongs to exactly one list and cannot exist independently.
- Playback settings are per-list, not global settings.
- The ordinary list remains authoritative for membership; the plan is an
  optional playback projection.
- There is no migration for unreleased intermediate playlist schemas.
