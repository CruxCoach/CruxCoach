# Feature Specs

CruxCoach feature specifications, grouped by release.

## Conventions

- **Numbering** — sequential `FEAT-NNN` across the whole project (not
  per-release). A spec keeps its number even if its release slot shifts.
- **Folders** — one subfolder per release version (`0.1.3/`, `0.1.4/`, …).
  A spec lives in the folder for the release it targets.
- **Status markers** — each spec carries a `> **Status:**` blockquote under
  its title. Common values:
  - `Skeleton` — scope and decisions agreed, implementation details TBD
  - `Draft` — design complete, pending engineering review
  - `Ready` — design complete, API surface + tests + rollout specified, implementation can start
  - `Design-locked` — design frozen; remaining gates (e.g. an upstream spike) before implementation can start
  - `Implementation` — code in progress
  - `Shipped` — merged and released
  - `Failed` — abandoned; kept as a record of the attempted design, do not implement from this
- **Dependencies** — when a spec depends on another, the `> **Depends on:**`
  line in the header calls it out explicitly.

## Index

### v0.1.2 — Hardening + Auto-Update

| Spec | Title | Status | Depends on |
|------|-------|--------|------------|
| [FEAT-004](0.1.2/FEAT-004-auto-update.md) | In-App Update Notification & APK Installer | Shipped | — |

### v0.1.3 — Nostr relay discovery + encrypted backup

| Spec | Title | Status | Depends on |
|------|-------|--------|------------|
| [FEAT-001](0.1.3/FEAT-001-nostr-relay-discovery.md) | Nostr Relay Discovery (NIP-65) | Shipped | — |
| [FEAT-002](0.1.3/FEAT-002-nostr-backup-sync.md) | Nostr Encrypted Backup & Sync | Shipped | FEAT-001 |

### v0.1.4 — Climb Creator + schema cleanup + Aurora migration

| Spec | Title | Status | Depends on |
|------|-------|--------|------------|
| [FEAT-003](0.1.4/FEAT-003-climb-creator.md) | Climb Creator & Nostr Community Climbs | Implementation | — |
| [FEAT-005](0.1.4/FEAT-005-aurora-json-import.md) | Aurora JSON Export Import | Implementation | FEAT-003 |
| [FEAT-006](0.1.4/FEAT-006-schema-rename.md) | Schema Naming Cleanup | Implementation | — |
| [FEAT-010](0.1.4/FEAT-010-nostr-profile-editor.md) | Nostr Profile Editor (Kind-0) Polish | Implementation | — |

### v0.2.0 — Kilter own-climb import + difficulty rating engine

| Spec | Title | Status | Depends on |
|------|-------|--------|------------|
| [FEAT-008](0.2.0/FEAT-008-kilter-import-own-climbs-and-backup-extension.md) | Kilter Own-Climb Import + Backup Extension | Design-locked | FEAT-003 |
| [FEAT-009](0.2.0/FEAT-009-difficulty-rating-engine.md) | Difficulty Rating Engine | Skeleton | FEAT-003 |
| [FEAT-011](0.2.0/FEAT-011-setter-angle-visibility.md) | Setter Angle Visibility | Skeleton | — |
| [FEAT-012](0.2.0/FEAT-012-route-creation-and-playback.md) | Route Creation & Playback | Skeleton | FEAT-003, FEAT-005 |
