# Public Feature Index

CruxCoach uses `FEAT-NNN` identifiers in source comments, release notes, and
security documentation. This file is the public referent for identifiers that
appear in repository documentation; it records implementation status, not a
promise of future delivery or API stability.

| ID | Status | First release | Public description |
|----|--------|---------------|--------------------|
| FEAT-002 | Shipped | 0.1.3 | Opt-in end-to-end encrypted backup over Nostr and Blossom. See the threat model in [SECURITY.md](SECURITY.md#encrypted-cloud-backup-feat-002-013). |
| FEAT-003 | Shipped | 0.1.4 | Community climb authoring and publishing, with explicit publish controls and signed-event validation. |
| FEAT-004 | Shipped | 0.1.2 | In-app APK updater with certificate pinning. See [docs/KEY_ROTATION.md](docs/KEY_ROTATION.md). |
| FEAT-007 | Phase 1 shipped | 0.2.0 | Find-your-gym board picker based on the board-locations catalogue. |
| FEAT-009 | Planned | — | Community quality/vote aggregation. References in tests describe isolated calculation work; it is not advertised as a shipped user feature. |
| FEAT-015 | Shipped | 0.2.0 | Privacy-filtered board-locations map. Dataset and map-provider details are in [LEGAL.md](LEGAL.md#gym--wall-locations-dataset). |
| FEAT-023 | Implemented | 0.2.1 (unreleased) | Saved lists display their climbs across supported board families. |
| FEAT-027 | Shipped | 0.2.0 | MoonBoard catalogue, visualization, and BLE support. |
| FEAT-029 | Shipped | 0.2.0 | Project-created MoonBoard imagery and hold-coordinate maps. |
| FEAT-031 | Shipped | 0.2.0 | Tension, Grasshopper, Decoy, So iLL, and Touchstone support plus multi-board authoring. |
| FEAT-032 | Shipped | 0.2.0 | Local history of climbs sent to a board. |
| FEAT-033 | Shipped | 0.2.0 | Board-specific variable climb angles. |
| FEAT-039 | Shipped | 0.2.0 | Per-board selector for the statistics hold heatmap. |

The user-visible detail and later fixes for released work are maintained in
[CHANGELOG.md](CHANGELOG.md). Internal design notes are not a public interface
and are not required to understand or contribute to the current code. Propose
new work through a public issue, without sensitive or personal data, before
depending on a new identifier.
