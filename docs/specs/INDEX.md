# FEAT Spec Registry

Canonical registry of FEAT-IDs across the project. **Always allocate
the next free ID from this file** before creating a new spec on a
feature branch — prevents the ID collisions that surface at merge
time when two parallel branches both grab the same number.

When you allocate, update this file in the same commit as the new
spec file (or in a precursor commit on `dev`/`main`) so other
branches see the reservation.

## Reserved + shipped

| ID | Name | Target | Status | Spec path |
|----|------|--------|--------|-----------|
| FEAT-001 | Nostr Relay Discovery (NIP-65) | v0.1.3 | shipped | `0.1.3/FEAT-001-nostr-relay-discovery.md` |
| FEAT-002 | Nostr Backup Sync | v0.1.3 | shipped | `0.1.3/FEAT-002-nostr-backup-sync.md` |
| FEAT-003 | Climb Creator + Community Climbs | v0.1.4 | implementation | `0.1.4/FEAT-003-climb-creator.md` |
| FEAT-004 | In-App Auto-Update | v0.1.2 | shipped | `0.1.2/FEAT-004-auto-update.md` |
| FEAT-005 | Aurora JSON Import | v0.1.4 | implementation | `0.1.4/FEAT-005-aurora-json-import.md` |
| FEAT-006 | Schema Rename (board_*, climb_*) | v0.1.4 | implementation | `0.1.4/FEAT-006-schema-rename.md` |
| FEAT-007 | Board Selection in Onboarding | v0.1.6 | skeleton | `0.1.6/FEAT-007-board-selection-onboarding.md` |
| FEAT-008 | Kilter Own-Climb Import + Backup Extension | v0.2.0 | design-locked | `0.2.0/FEAT-008-kilter-import-own-climbs-and-backup-extension.md` |
| FEAT-009 | Difficulty Rating Engine | v0.2.0 | skeleton (algorithm-locked) | `0.2.0/FEAT-009-difficulty-rating-engine.md` |
| FEAT-010 | Nostr Profile Editor (Kind-0) Polish | v0.1.4 | implementation | `0.1.4/FEAT-010-nostr-profile-editor.md` |
| FEAT-011 | Setter Angle Visibility | v0.2.0 | skeleton | `0.2.0/FEAT-011-setter-angle-visibility.md` |
| FEAT-012 | Route Creation + Playback | v0.2.0 | skeleton | `0.2.0/FEAT-012-route-creation-and-playback.md` |
| FEAT-013 | CruxCoach Controller (ESP32 BLE Multiplexer) | backlog | backlog | `backlog/FEAT-013-cruxcoach-controller-esp32.md` |
| FEAT-014 | Live Training Coordination via Nostr | backlog | backlog | `backlog/FEAT-014-live-training-coordination.md` |
| FEAT-015 | Kilter Board Locations Map | v0.1.5 | implementation | `0.1.5/FEAT-015-board-locations-map.md` |
| FEAT-016 | Kilter Homewall Support | v0.1.7 | skeleton | `0.1.7/FEAT-016-homewall-support.md` |
| FEAT-022 | Multi-Heatmap per Board | backlog | backlog | `backlog/FEAT-022-multi-heatmap-per-board.md` |
| FEAT-023 | Cross-Board Lists + Send Concept | backlog | backlog | `backlog/FEAT-023-cross-board-lists-and-send.md` |
| FEAT-024 | Unified Publish-State Signal across Browser + Detail | backlog | backlog | `backlog/FEAT-024-unified-publish-state-badge.md` |

## Renumbered (history)

When two parallel branches collide on a FEAT-ID, the later-merged
branch gets renumbered so existing commits / cross-refs on the
earlier branch don't have to churn. Document the renumbering here
so anyone searching git history for the old ID lands on the right
file.

| Old ID | New ID | Reason | When |
|--------|--------|--------|------|
| FEAT-006 (board-locations-map, on `feat/0.1.5-board-locations-map`) | FEAT-015 | Collision with FEAT-006 schema-rename on `feat/0.1.4-release` | 2026-05-06 |
| FEAT-008 (homewall-support, on `feat/0.1.5-board-locations-map`) | FEAT-016 | Collision with FEAT-008 kilter-import on `feat/0.1.4-release` | 2026-05-06 |

## Next free

**FEAT-025** is the next unallocated ID. Allocate the next one
when starting a new spec. (FEAT-017–021 are reserved on parallel
branches and not yet merged into this index — verify there before
allocating to avoid collisions.)

## Status legend

| Status | Meaning |
|--------|---------|
| skeleton | scope captured, design open |
| design-locked | scope + design decisions agreed; implementation not started |
| implementation | code work in progress on its target branch |
| shipped | merged to main + present in a tagged release |
| backlog | no release target; idea captured for later |

## Conventions

- ID format: `FEAT-NNN` zero-padded to 3 digits.
- Filename: `docs/specs/<target>/FEAT-NNN-kebab-case-name.md` where
  `<target>` is the version directory (e.g. `0.2.0`, `backlog`).
- A spec may move between version directories as the release-train
  decision changes (see FEAT-008 originally targeted 0.2.0 then
  retargeted internally — the file path follows the current
  decision; this registry's "Target" column is authoritative).
- Spec files have YAML frontmatter `--- status: <state> ---`
  matching the legend above.
- A spec keeps its FEAT-ID for life — renumbering is only used to
  break ID collisions across parallel branches, never to "tidy up"
  numbering after the fact.
