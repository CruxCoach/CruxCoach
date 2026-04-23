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
| [FEAT-001](0.1.3/FEAT-001-nostr-relay-discovery.md) | Nostr Relay Discovery (NIP-65) | Ready | — |
| [FEAT-002](0.1.3/FEAT-002-nostr-backup-sync.md) | Nostr Encrypted Backup & Sync | Ready | FEAT-001 |

### v0.1.4 — Climb Creator

| Spec | Title | Status | Depends on |
|------|-------|--------|------------|
| [FEAT-003](0.1.4/FEAT-003-climb-creator.md) | Climb Creator & Nostr Community Climbs | Failed | Kilter API re-integration |
