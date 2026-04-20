# Feature Specs

CruxCoach feature specifications, grouped by release.

## Conventions

- **Numbering** — sequential `FEAT-NNN` across the whole project (not
  per-release). A spec keeps its number even if its release slot shifts.
- **Folders** — one subfolder per release version (`0.2.0/`, `0.3.0/`, …).
  A spec lives in the folder for the release it targets.
- **Status markers** — each spec carries a `> **Status:**` blockquote under
  its title. Common values:
  - `Skeleton` — scope and decisions agreed, implementation details TBD
  - `Draft` — design complete, pending engineering review
  - `Implementation` — code in progress
  - `Shipped` — merged and released
- **Dependencies** — when a spec depends on another, the `> **Depends on:**`
  line in the header calls it out explicitly.

## Index

### v0.1.2 — Hardening + Auto-Update

| Spec | Title | Status | Depends on |
|------|-------|--------|------------|
| [FEAT-004](0.1.2/FEAT-004-auto-update.md) | In-App Update Notification & APK Installer (Codeberg) | Skeleton | — |

The 0.1.2 release also bundles fixes for Critical + High findings from
the RepoLens audit; see [`0.1.2/triage/`](0.1.2/triage/) for the triage
baseline and workflow.

### v0.2.0 — Nostr relay discovery + encrypted backup

| Spec | Title | Status | Depends on |
|------|-------|--------|------------|
| [FEAT-001](0.2.0/FEAT-001-nostr-relay-discovery.md) | Nostr Relay Discovery (NIP-65) | Skeleton | — |
| [FEAT-002](0.2.0/FEAT-002-nostr-backup-sync.md) | Nostr Encrypted Backup & Sync | Draft | FEAT-001 |

### v0.3.0 — Climb Creator

| Spec | Title | Status | Depends on |
|------|-------|--------|------------|
| [FEAT-003](0.3.0/FEAT-003-climb-creator.md) | Climb Creator & Nostr Community Climbs | Draft | Kilter API re-integration |
