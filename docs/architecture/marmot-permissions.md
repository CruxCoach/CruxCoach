# Marmot permission and snapshot foundation

Status: **local foundation; native live sharing blocked**. Reviewed 2026-09-10
on `feat/marmot-permissions-v023`. This is feature work, not a 0.2.3 release,
publication, security certification or activation of the OIDC/publisher migration.

## Integration and implementation boundary

The feature started at `928c5bd304aa178963c7015bec773022460c3e38`. A targeted
GitHub fetch obtained release `2788a6fa47b6ba1e1ac5785e8805e3a2850cb20c`.
Merge `9c163faf7` contains both as ancestors. The graph merge-base was
`a1f0d93c3c8db62000ea4ad25a2b3018fcdce804`; the actual permission delta started
after `baec996ab`. Overlapping earlier app history was reconciled against that
delta: release app behavior, navigation, DI and settings were preserved, with
permission routes and EN/DE resources combined. Release itself was not changed.

The app has **no Marmot/MDK runtime dependency**. `NativeSharingGate` records
historical integration evidence and stays closed. `AppModule` injects only
`BlockedMarmotSnapshotPort`. The product provides signed local policy/device
administration, expiry controls, an explicit user/server identity pin and a
snapshot screen with an unavailable state. No private note leaves this build.

`SharingSnapshotExchange` is connected to the controller, ViewModel, existing
peer screen, SQLDelight database and AES-GCM vault. A synthetic authenticated
port exercises both endpoints in tests, including the SERVER role. This is an
executable application boundary, not an implemented network adapter or deployed
server. Existing category consent still needs authenticated peer-ledger transport
before live use; debug peer simulation is not proof of remote consent.

The current Android catalog uses Kotlin 2.3.0, AGP 8.13.2, SQLDelight 2.3.2,
SQLCipher 4.14.0 and Quartz 1.05.1. These dependencies were inherited, not upgraded.
The existing signature, Keystore, backup and AES-GCM primitives are reused.
SHA-256 binds snapshot bytes to an offer; no new encryption or signature scheme
is introduced. The JVM test signer is explicitly a synthetic identity-tagged
stand-in, not BIP-340, and the test transport does not execute MLS.

## Protocol evidence and adapter contract

The upstream source survey found MDK
[`fdd398a80f1626f1713787cebe416f7890b5b204`](https://github.com/marmot-protocol/mdk/tree/fdd398a80f1626f1713787cebe416f7890b5b204),
released as [v0.9.21](https://github.com/marmot-protocol/mdk/releases/tag/v0.9.21)
on 2026-09-10. Its release API exposed no attached binary assets when checked.
Its [workspace manifest](https://github.com/marmot-protocol/mdk/blob/fdd398a80f1626f1713787cebe416f7890b5b204/Cargo.toml)
pins the OpenMLS fork to `59e7d3b27a7e95237879dd5478de1fd90eff7ada` and declares
Nostr 0.44.8. Those are **surveyed upstream requirements**, not installed app
libraries. The August v0.9.12 artifact evidence in FEAT-062 is historical; an
upstream change alone neither proves an old defect persists nor closes our gates.

The Marmot specification surveyed was
[`4a2bc65f8db5866cec3b2a127dedb37818eaf207`](https://github.com/marmot-protocol/marmot/tree/4a2bc65f8db5866cec3b2a127dedb37818eaf207).
Relevant rules:

- An account is a raw 32-byte x-only secp256k1 Nostr key. An MLS leaf is an
  account-device membership with a **different** signature key. Account identity
  proof v2 must bind the credential account to that leaf key. Account strings,
  transport addresses and self-asserted device fields are not authentication.
  [Identity](https://github.com/marmot-protocol/marmot/blob/4a2bc65f8db5866cec3b2a127dedb37818eaf207/foundation/identity.md)
- The unsigned inner app event has exactly `id`, `pubkey`, `created_at`, `kind`,
  `tags`, `content`, no `sig`; reject duplicates/unknown top-level fields,
  validate its NIP-01 content hash and bind `pubkey` to the authenticated MLS
  sender account. MLS membership authenticates a sender, not resource access.
  [Application messages](https://github.com/marmot-protocol/marmot/blob/4a2bc65f8db5866cec3b2a127dedb37818eaf207/foundation/application-messages.md)
- Pending effects, withdrawals, source identities and exact publication
  obligations must survive restart. An uncertain operation must not silently
  become a fresh operation. [Durability](https://github.com/marmot-protocol/marmot/blob/4a2bc65f8db5866cec3b2a127dedb37818eaf207/protocol-core/durability.md)
- MLS epoch/key evolution is a transport property; application authentication,
  authorization and endpoint compromise remain distinct concerns.
  [RFC 9420 security considerations](https://www.rfc-editor.org/rfc/rfc9420.html#section-16)

The proposed app envelope uses custom kind `1220` and exact tags
`[["l","cc.sharing.snapshot.v1","cruxcoach.private"]]`. Kind 1220 was absent from
the surveyed [registry](https://github.com/marmot-protocol/marmot/blob/4a2bc65f8db5866cec3b2a127dedb37818eaf207/foundation/registries.md);
it is an application convention, not a registered interoperable Marmot standard.
Recheck collisions and negotiate support before external integration.

Current UniFFI has
[`send_custom_event`](https://github.com/marmot-protocol/mdk/blob/fdd398a80f1626f1713787cebe416f7890b5b204/crates/marmot-uniffi/src/commands/message.rs),
but [`AppMessageRecordFfi`](https://github.com/marmot-protocol/mdk/blob/fdd398a80f1626f1713787cebe416f7890b5b204/crates/marmot-uniffi/src/conversions/message.rs)
exposes sender account and source epoch, not the authenticated sender leaf needed
here. Substituting the account as a device ID would discard a security boundary.
A retained chat timeline is not a durable application inbox.

`MarmotSnapshotPort` therefore requires an adapter to supply and enforce:

1. Verified account/leaf provenance, including a binding from its local MLS
   credential to the currently enrolled local authority device.
2. A native group lock around session/read/receive/handoff callbacks. Exactly
   two leaves are allowed in this first profile. Incarnation, epoch, membership
   changes and retracted application effects permanently change the opaque
   session binding; missing or uncertain state supplies no session.
3. Durable, acknowledged application delivery independent of timeline retention,
   validated kind/tags/inner event and convergence state; acknowledgement only
   after the application transaction commits. Retractions reach the application
   before any further read, conservatively invalidating the whole binding.
4. A synchronous local durable handoff while authorization is checked. It must
   not reenter application callbacks or confuse local enqueue with remote
   delivery. The native engine may retry the **same** persisted obligation;
   the application does not generate a replacement event automatically.

A DTO or fake adapter cannot establish these guarantees. Additional leaves,
new devices and replacement groups require fresh application offers and consent;
this basis intentionally provides no automatic permission expansion or transfer.

## Authorization and snapshot lifecycle

`SecureDbSharingRepository` reduces signed owner-policy, relationship, device
manifest and authority-attestation histories. It reads circle assignments from
the signed reduction, not mutable projection caches. Default policy is deny.
Object rules use a composite category/object key in reduction, resolution and
storage. Equal IDs in unrelated categories cannot grant access, replace another
category’s denial or clear it when one exception is removed. Device authority and policy authority remain separate from
recipient category consent.

`EffectiveAccessResolver` requires a matching relationship identity, accepted
state, no fail-closed/recovery/revoke-sync condition, a currently authorized
non-revoked peer device, the **exact positive** resource epoch, and membership in
both offered and consented categories. An expired relationship denies access;
a missing/invalid clock denies time-limited access. Shortening an expiry narrows
immediately. Extending/removing a limit clears prior consent. Replayed older
acceptance cannot restore it. A changed grant never broadens consent implicitly.

For a snapshot:

1. Each endpoint explicitly pins the other full account key as USER or SERVER.
   A pin establishes local trust context, not a resource grant. Changing its role
   invalidates existing offers. The server has its own account, leaf and policy;
   no owner-root, publisher, validator, OIDC or developer identity is inherited.
2. The owner chooses an actual sealed row. `withPeerSnapshot` obtains the row's
   category, key and epoch itself, checks current account/device authority,
   recovery state and effective peer access, and decrypts within a transaction.
   Received-copy key scopes cannot be re-exported through this service.
3. The offer binds a random 256-bit snapshot ID, both accounts/roles/devices,
   session binding, category, exact epoch, SHA-256 digest and expiry. The offer
   cannot outlive an already known relationship expiry; the UI caps its one-day
   default to that limit. Local row
   IDs and circle assignments are not transmitted. The offer exposes metadata
   and a digest to an already category-authorized peer; a digest can reveal
   low-entropy guesses and is not encryption.
4. The recipient must explicitly accept that **whole offer**. Opening/listing
   it is not consent. The sender admits only CONSENT from the bound recipient
   leaf and rechecks actual policy, row bytes, state and time at CONTENT handoff.
5. CONTENT is accepted only from the bound owner for the exact CONSENTED offer.
   Digest/size checks precede encrypted local persistence. A subsequent read
   checks consent state, identity/device authority, recovery, session and expiry
   again. RECEIPT means that the cooperating recipient acknowledged acceptance;
   it is not proof against a malicious recipient or a claim of human reading.
6. Either endpoint can stop its local flow. REVOKE/DECLINE establishes a terminal
   tombstone even when it overtakes OFFER. Replayed offers/content, duplicate
   receipts, out-of-order content and changed bodies cannot resurrect it.
   Signed policy narrowing withdraws outgoing snapshots on synchronization.

No received snapshot creates a user policy grant, authorizes another resource,
enrols a device, delegates signing or grants the server access to the user's
store. A future server implementation must use a separate endpoint identity and
implement the same consent, storage and port contract. There is no server daemon
or production access in this change.

## Ordering, offline behavior and persistence

Admission and persistence of signed records now share transactions at direct
repository doors as well as controller batches. Snapshot handoff reserves an
action bit in a **committed** transaction before invoking native code. A second
instance rechecks the current state/attempt bit. The final state, policy and
payload check runs under the native session fence and database transaction, so
concurrent narrowing cannot pass between an authorization read and handoff.
All exchange instances over the app's shared database use the same coordinator;
this prevents their bookkeeping from colliding after another instance reserved
its handoff. Lock order is coordinator, native session, then database. Control
handoffs check state too. Independent processes/database handles need a common
serialization contract before integration; the app wires one database instance.

Crash or exception after reservation can lose availability: that action remains
attempted, even if it never left the process. It is not retried as a new event
after restart. The UI distinguishes pending/unclear handoff from an explicit
receipt. A new offer requires a new explicit owner action and recipient consent.
SQLite contention or unavailable dependencies may reject an operation; no
plaintext fallback or broad grant is used to make it succeed.

Offline revocation takes effect locally immediately; the other endpoint learns
it on authenticated synchronization, or loses access at expiry. An offline
recipient may retain previously granted access until then. Removed members,
changed epochs/groups and uncertain convergence close reads. No background
worker, scheduler or implicit publication was added.

The snapshot service persists a wall-clock high-water mark. Backward/negative
time locks it across restart; there is no automatic unlock that could revive
expired consent. This does not establish trusted time: clock freeze, full DB/OS
rollback and a compromised endpoint are outside that mechanism. Existing
relationship resolution alone does not provide persistent time attestation.
Independent stores/processes whose observations overtake one another may
conservatively trigger a false-positive lock; access remains blocked in that case. Resolving a false-positive clock lock requires an explicitly reviewed recovery
path; none is implemented here. Expiry is not a remote deletion guarantee.

Text is limited to 32 KiB UTF-8, expiry to seven days (the UI offers a one-day
snapshot), envelope to 220,000 characters, and retained snapshot records to 256
per account. Tombstones are not garbage-collected and are counted toward the
quota. A trusted but abusive peer can exhaust its allowed inbox quota; resolving
that availability limit safely needs an authenticated replay-horizon/compaction
scheme before broad deployment. Unknown versions, enum values, extra/duplicate
fields, oversized input and noncanonical encodings are rejected, not interpreted
as wider access. Closed storage codecs preserve original canonical encodings;
old unrecognized restrictions fail closed.

## Storage, migration and privacy

Release SecureDB migrations 1–13 are byte-identical to fetched 0.2.3. The old
unpublished feature's 11–23 are appended as 14–26. Migration 27 adds AAD version;
28 adds account-scoped snapshot, role-pin and clock tables. Migration 29
preserves existing object-rule cache rows while adding category to their primary
key. Signed rule history already carries category and is not rewritten. The
schema is now version 30. The synthetic release v14 fixture migrates/reopens with existing
profile/note data preserved and no new grants.

Version numbers on the old unpublished feature overlap release meanings.
`SecureSchemaLineage` refuses that unsupported lineage **before DDL** when
sharing tables exist without release-specific tables; it never resets data.
An old feature DB needs a separately reviewed export/conversion, not a blind
in-place upgrade. The canonical feature identity is the separate package
`com.cruxcoach.android.dev.f_d925a3c5a593`; no device was installed or migrated.

New AES-GCM AAD v2 binds domain/version, owner account, row ID, category, key
scope/ID and resource epoch. Substituting these row fields fails authentication.
Migration marks old ciphertext as AAD v1; owner-only reads retain compatibility only with an authenticated owner manifest,
but legacy or unknown versions cannot enter peer export. Re-sealing must be an
explicit owner operation, not a silent migration grant. Backups preserve
`aadVersion` and decode its absence as legacy v1. Snapshot consent, attempt bits,
role pins, clock and native session state are not portable permission backups.
Downgrading these backups into older unpublished permission clients is unsupported;
those clients do not enforce the corrected category/AAD semantics. Release 0.2.3
has no permission-backup importer.
Backup key collection also requires an authenticated owner manifest, so constructing
a repository with a different account cannot claim legacy keys. Root recovery and
signed device-generation changes invalidate existing snapshots;
restoring ciphertext/keys does not restore their consent or native session.

SQLCipher protects the production database; JDBC tests use temporary ordinary
SQLite files with synthetic data. Snapshot metadata includes peers, roles,
category, digest, time, state and handoff bits in that encrypted database. They
are sensitive local records, not a separate tamper-proof audit service. Signed
permission/device histories provide the existing authority audit. No payload or
provider exception is added to logs, and NIP-55 failures no longer log provider
throwables. New snapshot-message diagnostics redact content. Draft text is not
saved-instance state; opened text is cleared on dismissal, navigation/reload and
expiry. Immutable JVM strings and OS/UI copies cannot be reliably zeroized.

Revocation closes future access through these application paths. It cannot
recall already decrypted copies, screenshots, recipient exports or old backups
holding decryptable material. Revocation does not itself prove key destruction.
The existing key-first purge is a separate operation. MLS/SQLCipher do not hide
all relay timing/size/address metadata, guarantee endpoint security, or protect
against wholesale rollback of a trusted local database. Other existing app
publication features are not governed by this snapshot service and do not gain
a new private-data export route.

## Threat model and resolved findings

| Priority / threat | Enforcement and remaining limit |
|---|---|
| P1: ignored expiry and future resource epochs | Access-time expiry and exact epoch; fresh consent for extension. Clock trust limits above. |
| P1: same object ID widens another category | Composite category/object keys in signed reduction and the migrated cache; category comes from sealed row. |
| P1: mutable circle projection changes access | Signed authoritative reduction supplies circle; corrupted cache cannot widen it. |
| P1: raw key read or metadata substitution at export | Internal owner compatibility read; peer gate selects key/category itself; AAD v2. Compromised in-process code is outside this boundary. |
| P1: unknown restrictions disappear during decode | Bounded exact closed encoding; malformed/unknown history fails closed. |
| P2: replay, stale admission and multi-instance race | Signed branch/authority admission, transaction boundaries, exact snapshot consent, durable action bits and tombstones. Full database rollback is not solved by local records. |
| P2: signer/provider diagnostics disclose data | Redacted failure diagnostics; no payload logs. Existing unrelated app logging was not globally rewritten. |
| Integration: member/server confused with resource authority | Explicit role/account/leaf/session binding and separate grants; qualified native adapter still required. |
| Migration collision / accidental data reset | Preserve release lineage, append feature DDL, pre-DDL guard; old feature import remains explicit work. |

## Verification and remaining integration work

Final focused verification passed: 583 shared and 590 Android sharing/schema
tests, zero failures/errors/skips. The snapshot class includes 20 scenarios, with
five concurrent-instance rounds in its handoff test. Counts come from JUnit XML;
commands and intermediate regression evidence are recorded in the run report. Thirteen Python tests cover
preserved feature identity, authorized maintainers and release workflow policy.
Tests cover U–U and SERVER–U consent, wrong roles/accounts/devices, extra members,
no grant, exact epoch, replay/reordering, corruption, revocation before and after
receipt, ambiguous handoff/reopen, expiry, clock rollback, device authority,
recovery, AES-GCM binding, backup and release-schema migration. Concurrent
instances and the product's closed gate are exercised with local test identities.

The historical Python private-sharing oracle already failed on the original
feature parent (126 errors / 10,033 assertions) and the merge (126 / 10,055).
It still describes a superseded bilateral contract. Its safeguards/expected
hashes were not weakened to manufacture a pass. Detailed baseline/final logs
and the review report live in the owner run directory.

Full Gradle suites, APK/release builds, lint and CI were not run locally, per
repository rules. No push was authorized, so no branch CI was triggered.
Android/Hilt/Compose compilation is not handset validation. Keystore hardware,
real Quartz/BIP-340/Amber interaction, SQLCipher platform upgrade and actual
MDK/relay convergence were not executed by the synthetic tests.

Before enabling live sharing: qualify/pin a reproducible native artifact;
implement and test the port provenance, atomic fence, source-aware durable
inbox/retractions and exact handoff contract; connect signed policy/category
consent transport; define explicit opt-in bootstrap/KeyPackage discovery; add
an isolated server adapter; test native restart/convergence and Android signer
behavior; review quotas, clock-lock recovery and old-feature import. Preserve
existing gate requirements and obtain personal owner review of the integration.
No production credentials, publication authority or paid infrastructure is
needed or provisioned by this foundation.
