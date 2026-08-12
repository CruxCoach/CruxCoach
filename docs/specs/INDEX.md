# FEAT Spec Registry

Canonical registry of FEAT-IDs across the project. **Always allocate
the next free ID from this file** before creating a new spec on a
feature branch — prevents the ID collisions that surface at merge
time when two parallel branches both grab the same number.

When you allocate, update this file in the same commit as the new
spec file so other branches see the reservation.

> **Provenance note (2026-07-15):** `docs/specs/` was made local-only
> (untracked, commit `6ad6b85`), so the tracked INDEX (last version at
> `bba8b62`, next-free FEAT-025) was lost from working copies. This file
> re-seeds the registry in the 0.2.2 worktree: entries up to FEAT-024 come
> from the last tracked INDEX; FEAT-025+ are reconstructed from commit
> history (titles inferred from commit messages where no spec file
> survives — marked *inferred*).
>
> **Reconciled 2026-07-29**, when `docs/specs/` stopped being gitignored.
> Every path in the table was checked against the files on disk, in both
> directions. Five specs the note above assumed lost (FEAT-001–005) were
> restored from `250e9362^`; the "reserved on parallel branches" gaps at
> 017–020, 025–026, 040 and 042 turned out to be real specs that had
> simply never been registered; FEAT-008/009/011/012 were pointing at
> `0.2.0/` after moving to `backlog/`. Four genuine ID collisions remain
> and are listed under *Unresolved ID collisions*. Remaining gaps
> (029, 034–036, 038) have no file in this worktree — **verify before
> reusing**.

## Reserved + shipped

| ID | Name | Target | Status | Spec path |
|----|------|--------|--------|-----------|
| FEAT-001 | Nostr Relay Discovery (NIP-65) | v0.1.3 | shipped | `0.1.3/FEAT-001-nostr-relay-discovery.md` |
| FEAT-002 | Nostr Backup Sync | v0.1.3 | shipped | `0.1.3/FEAT-002-nostr-backup-sync.md` |
| FEAT-003 | Climb Creator + Community Climbs | v0.1.4 | shipped | `0.1.4/FEAT-003-climb-creator.md` |
| FEAT-004 | In-App Auto-Update | v0.1.2 | shipped | `0.1.2/FEAT-004-auto-update.md` |
| FEAT-005 | Aurora JSON Import | v0.1.4 | shipped | `0.1.4/FEAT-005-aurora-json-import.md` |
| FEAT-006 | Schema Rename (board_*, climb_*) | v0.1.4 | shipped | `0.1.4/FEAT-006-schema-rename.md` |
| FEAT-007 | Board Selection in Onboarding | v0.1.6 | shipped | `0.1.6/FEAT-007-board-selection-onboarding.md` |
| FEAT-008 | Kilter Own-Climb Import + Backup Extension | backlog | backlog | `backlog/FEAT-008-kilter-import-own-climbs-and-backup-extension.md` |
| FEAT-009 | Difficulty Rating Engine | backlog | backlog | `backlog/FEAT-009-difficulty-rating-engine.md` |
| FEAT-010 | Nostr Profile Editor (Kind-0) Polish | v0.1.4 | shipped | `0.1.4/FEAT-010-nostr-profile-editor.md` |
| FEAT-011 | Setter Angle Visibility | backlog | backlog | `backlog/FEAT-011-setter-angle-visibility.md` |
| FEAT-012 | Route Creation + Playback | backlog | backlog | `backlog/FEAT-012-route-creation-and-playback.md` |
| FEAT-013 | CruxCoach Controller (ESP32 BLE Multiplexer) | backlog | backlog | `backlog/FEAT-013-cruxcoach-controller-esp32.md` |
| FEAT-014 | Live Training Coordination via Nostr | backlog | backlog | `backlog/FEAT-014-live-training-coordination.md` |
| FEAT-015 | Kilter Board Locations Map | v0.1.5 | shipped | `0.1.5/FEAT-015-board-locations-map.md` |
| FEAT-016 | Kilter Homewall Support | v0.1.7 | shipped | `0.1.7/FEAT-016-homewall-support.md` |
| FEAT-017 | Background-Safe Board Sync | backlog | backlog | `backlog/FEAT-017-background-board-sync.md` |
| FEAT-018 | Opt-In Automatic Update Install | backlog | backlog | `backlog/FEAT-018-opt-in-auto-update.md` |
| FEAT-019 | Copyable Climb Share Link | v0.1.5 | shipped | `0.1.5/FEAT-019-copyable-climb-share-link.md` |
| FEAT-020 | Board Picker in the Browser TopBar | v0.1.5 | shipped | `0.1.5/FEAT-020-board-picker-in-browser.md` |
| FEAT-021 | Auto-Backup Interval Persistence | v0.1.4 | shipped | `0.1.4/FEAT-021-fix-backup-periodic-interval.md` |
| FEAT-022 | Multi-Heatmap per Board | backlog | backlog | `backlog/FEAT-022-multi-heatmap-per-board.md` |
| FEAT-023 | Cross-Board Lists + Send Concept | v0.2.1 | shipped | `backlog/FEAT-023-cross-board-lists-and-send.md` |
| FEAT-024 | Unified Publish-State Signal (Browser + Detail) | backlog | backlog | `backlog/FEAT-024-unified-publish-state-badge.md` |
| FEAT-025 | Community Send Count (privacy-preserving) | backlog | backlog | `backlog/FEAT-025-community-send-count.md` |
| FEAT-026 | Skip wasted index builds in board migrations | backlog | backlog | `backlog/FEAT-026-migration-index-build-skip.md` |
| FEAT-027 | MoonBoard Support — Catalogue + BLE Send | v0.2.0 | shipped | `0.2.0/FEAT-027-moonboard-support.md` |
| FEAT-028 | MoonBoard OCR Screenshot Import | v0.2.1 | shipped | `0.2.1/FEAT-028-moonboard-ocr-import.md` |
| FEAT-030 | *(stale spec removed — superseded)* | — | superseded | — |
| FEAT-031 | Aurora Board Brand Labeling *(inferred)* | v0.2.1 | shipped | — |
| FEAT-032 | Climb History (Verlauf) *(inferred)* | v0.2.1 | shipped | — |
| FEAT-033 | Board-Specific Angles (MoonBoard adjustable) *(inferred)* | v0.2.1 | shipped | — |
| FEAT-037 | Single-Board On-Demand Import Perf *(inferred)* | v0.2.1 | shipped | — |
| FEAT-039 | Per-Board Heatmap Selector + Community Convergence *(inferred)* | v0.2.1 | shipped | — |
| FEAT-040 | Training Plan Generation Engine | backlog | backlog | `backlog/FEAT-040-training-plan-engine.md` |
| FEAT-041 | Tombstone Delisted Community Climbs *(inferred)* | v0.2.1 | shipped | — |
| FEAT-042 | Open Climbing Data Interop via Nostr | backlog | backlog | `backlog/FEAT-042-nostr-open-climbing-interop.md` |
| FEAT-043 | Reach Metric (ReachAnalyzer) — save-time wiring | v0.2.3 | design-locked | `0.2.2/FEAT-043-reach-analyzer-integration.md` |
| FEAT-044 | CruxRelay — Transparent Board Relay | v0.2.2 | shipped | `0.2.2/FEAT-044-cruxrelay.md` |
| FEAT-045 | Lists ⇄ Playlists Full Convergence | v0.2.2 | shipped | `0.2.2/FEAT-045-lists-playlists-convergence.md` |
| FEAT-046 | Playlist Player Upgrades (FGS, Drag Reorder, Swipe) | v0.2.3 | design-locked | `0.2.2/FEAT-046-playlist-player-upgrades.md` |
| FEAT-047 | Direct Controller Reconnect | v0.2.2 | shipped | `0.2.2/FEAT-047-direct-controller-reconnect.md` |
| FEAT-048 | Per-controller capacity (not per brand) | v0.2.3 | planned | `0.2.3/FEAT-048-per-controller-capacity.md` |
| FEAT-049 | MoonBoard Hold-Set Selection (issue #9) | v0.2.2 | shipped | `0.2.2/FEAT-049-moonboard-hold-set-selection.md` |
| FEAT-050 | Survivable Database Downgrade | backlog | backlog | `backlog/FEAT-050-database-downgrade-safety.md` |
| FEAT-051 | Blossom Manifest Rollback Guard | v0.2.3 | planned | `0.2.3/FEAT-051-blossom-manifest-rollback-guard.md` |

Implementation plan for FEAT-043/045/046:
`0.2.2/IMPLEMENTATION-PLAN-convergence-player.md`.

FEAT-043 and FEAT-046 moved to v0.2.3 on 2026-07-27: neither is in the 0.2.2
build, and a milestone column that says otherwise makes the release claim
something it does not contain. FEAT-043 has its algorithm (`bd169ac8`) but not
the save-time wiring or the filter; FEAT-046 has no commits at all and its own
spec calls it a follow-up to FEAT-045. Their files stay under `0.2.2/` so
existing cross-references keep resolving.

FEAT-044/045/047/049 marked shipped on 2026-08-12, ahead of the 0.2.2 release.
All four are in the build and described in the changelog for the version, so
the working statuses they still carried said less than the release did. Each
spec file keeps its own frontmatter `status:` — those are not maintained past
implementation (`0.2.1/FEAT-028` is still `skeleton`), so this table, not the
frontmatter, is what records that a spec shipped.

## Renumbered (history)

When two parallel branches collide on a FEAT-ID, the later-merged
branch gets renumbered so existing commits / cross-refs on the
earlier branch don't have to churn.

| Old ID | New ID | Reason | When |
|--------|--------|--------|------|
| FEAT-006 (board-locations-map, on `feat/0.1.5-board-locations-map`) | FEAT-015 | Collision with FEAT-006 schema-rename on `feat/0.1.4-release` | 2026-05-06 |
| FEAT-008 (homewall-support, on `feat/0.1.5-board-locations-map`) | FEAT-016 | Collision with FEAT-008 kilter-import on `feat/0.1.4-release` | 2026-05-06 |

## Unresolved ID collisions

Found on 2026-07-29 when the tree was reconciled against the files on
disk before publication. In each case **two different features carry the
same ID**: one is in the table above, the other is a backlog spec that
never reached the registry. Nothing is renumbered here — renumbering is
the decision recorded in *Renumbered (history)*, and it has cross-branch
consequences, so it is left to a human.

Until one is resolved, treat the ID as ambiguous and cite the **path**,
not the number.

| ID | In the table above | Also on disk |
|----|--------------------|--------------|
| FEAT-015 | Kilter Board Locations Map (v0.1.5, shipped) | `backlog/FEAT-015-profile-image-crop.md` |
| FEAT-041 | Tombstone Delisted Community Climbs *(inferred)* (v0.2.1, shipped) | `backlog/FEAT-041-holistic-athlete-data-assistant.md` |
| FEAT-043 | Reach Metric (ReachAnalyzer) (v0.2.3, design-locked) | `backlog/FEAT-043-competitions-and-leaderboards.md` |
| FEAT-045 | Lists ⇄ Playlists Full Convergence (v0.2.2, implementation) | `backlog/FEAT-045-hall-directory-communities-and-handover.md` |

The four backlog specs are vision-tier or small UI items with no commits;
renumbering those is likely cheaper than renumbering the shipped side.

## Next free

**FEAT-052** is the next unallocated ID. Verify against unmerged branches
before allocating — the reconciliation below closed the previously listed
gaps, but a branch this worktree cannot see may still hold one.

## Status legend

| Status | Meaning |
|--------|---------|
| skeleton | scope captured, design open |
| planned | scope + target agreed, queued for implementation |
| design-locked | scope + design decisions agreed; implementation not started |
| implementation | code work in progress on its target branch |
| shipped | merged to main + present in a tagged release |
| backlog | no release target; idea captured for later |
| superseded | replaced by a later spec; id retired, never reused |

## Conventions

- ID format: `FEAT-NNN` zero-padded to 3 digits.
- Filename: `docs/specs/<target>/FEAT-NNN-kebab-case-name.md`.
- A spec keeps its FEAT-ID for life — renumbering only ever breaks
  cross-branch collisions, never "tidies up" numbering.
- Spec files carry YAML frontmatter (`status:` per the legend above;
  `queue:` is the machine lifecycle for the autonomous implementer).
