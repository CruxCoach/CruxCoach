# CruxCoach documentation

Start with [the app overview](../README.md), then choose a guide below.

## Permission feature branch overlay (2026-09-11)

`feat/marmot-permissions-v023` merges release source `2788a6fa4` into the retained
permission work. Its continuation adds native MDK/JNI snapshot exchange,
authenticated permission/consent transport, the six configured relays, Android
sender/recipient UI and a local synthetic server endpoint. No server deployment,
release or production publisher activation is included.
See [Marmot permissions architecture](architecture/marmot-permissions.md) for
current behavior, migration rules, threat model, measured relay compatibility and
remaining CI/device qualification.
The release-line inventory below describes the imported release source, before
this feature overlay. It is not evidence of publication or production activation.

## What state does this describe?

This audit describes source at `4fc067e87` on the **0.2.3 preparation line**,
reviewed on 2026-09-10. The published app is still **0.2.2**, according to the
release owner's handoff. A version in Gradle, a spec folder or a merged code
change is not evidence of publication.

| Label | Meaning here |
|---|---|
| Implemented | Present in this source tree; not necessarily released or device-validated |
| Published | Included in an actually distributed release; confirm release artifacts and receipts |
| Proposed | Design or work on a separate branch; no promise that this tree contains it |
| Historical / archived | Retained context for an earlier design, experiment or operating environment |

Competitions, the separate key-custody and personal-sharing work, and
BoardCell/FIPS mesh were intentionally **not integrated** into this line.
Existing nearby BLE sharing and LAN app/catalogue transfer are distinct from
those proposals. The OIDC/private-signing migration is **draft PR14**, not an
activated production boundary. See [release and CI status](RELEASE_GITHUB.md).

## Read by task

| Task | Start here |
|---|---|
| Understand boards, climbs, logs, filters and playlists | Core concepts: [English](en/CORE_CONCEPTS.md) · [Deutsch](de/CORE_CONCEPTS.md) |
| Find implementation and architectural boundaries | [Source map and architecture](en/CORE_CONCEPTS.md#source-map) |
| Make a contribution or orient an agent | [CONTRIBUTING](../CONTRIBUTING.md) and mandatory [AGENTS](../AGENTS.md) |
| Choose focused checks or use a device | [Testing](testing.md) |
| Understand release authority and migration state | [Release and CI](RELEASE_GITHUB.md), [key rotation](KEY_ROTATION.md) |
| Review privacy and trust | [Security](../SECURITY.md), [Nostr architecture](nostr-architecture.md), [update metrics](anonymous-update-metrics.md) |
| Work on board interoperability | [Quantum layers](QUANTUM_MULTI_CLIMB.md), [LAN share contract](en/LOCAL_SHARE_CONTRACT.md) |
| Find proposals and their decisions | [Spec guide](specs/README.md) and [FEAT registry](specs/INDEX.md) |
| Prepare the 0.2.3 candidate and review remaining checks | [Pre-release checklist](releases/0.2.3-pre-release.md) |
| Read release history | [Changelog](../CHANGELOG.md), [release notes](../RELEASE_NOTES.md) |

## Historical and proposed architecture

These documents retain their original paths so existing links and decisions
remain usable. Their statements about “current” mesh behavior refer to the
examined experimental branch, not to this release line.

- FIPS comparison: [Deutsch](de/FIPS_MESH_ARCHITECTURE_COMPARISON.md) · [English](en/FIPS_MESH_ARCHITECTURE_COMPARISON.md)
- [BoardCell design](specs/0.2.3/OFFLINE-BOARDCELL-FIPS-ARCHITECTURE.md) and [experimental device protocol](FIPS_DEVICE_TEST_PROTOCOL.md)
- [Competition design](specs/0.2.3/FEAT-058-competitions.md) and its linked contracts/decision register
- [iOS feasibility](IOS_BOARD_ACCESS.md), [Myco/FIPS research](research/2026-08-12-myco-fips-evaluation.md)

## Maintaining this guide

Code and schemas establish implementation; release artifacts establish
publication; specs record intent. Keep those claims separate. The
[registry](specs/INDEX.md) preserves known FEAT-ID collisions: cite full paths
when an ID is ambiguous. Historical frontmatter is not a release checklist.

New reader guides have paired [English](en/README.md) and [German](de/README.md)
versions. Existing technical contracts stay at established paths; do not
create duplicate normative copies merely to fill a language directory.

Remaining audit limits: historical specs have not all been revalidated
line-by-line; external services, production configuration and hardware
compatibility were not retested. Release configuration and the log screen are
under separate owner review. This documentation does not certify their final
state or authorize publication.
