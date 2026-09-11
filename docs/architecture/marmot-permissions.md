# Marmot permissions and ongoing private data sharing

[Deutsch: Funktionen und Architekturentscheidungen](marmot-permissions-overview.de.md)

Status: native integration on `feat/marmot-permissions-v023`, reviewed 2026-09-11.
This is feature work, not a release, deployment, security certification or
activation of the OIDC/account-ID/npub publisher boundary.

## Scope and provenance

The permission work began at `928c5bd304aa178963c7015bec773022460c3e38`.
Merge `9c163faf7` incorporates release
`2788a6fa47b6ba1e1ac5785e8805e3a2850cb20c`, preserving the release application
and permission routes, DI and EN/DE resources. The live continuation starts at
`66b048f7eeed3da36d68a3f8a1e3c0362df8922f`; it performs no further release merge.

The continuous-sharing pass starts from the owner's linearized equivalent tree
`4fd3ed337a3cc665cc5d248d5d472ca8e652cee4`. Earlier SHAs above are historical
provenance; retained merge history is under
`chore/preserve-marmot-permissions-merge-history-20260911`. This continuation
performs no merge, reset/rebase or push. It adds mutually confirmed friendships
and current projections of real profile/goals, training history and private climb
notes over pairwise sessions.
Legacy immutable text snapshots remain available with their separate per-snapshot
consent (32 KiB/seven days); they never become friendship authority. A server is
an optional equal peer. User–user sharing requires no CruxCoach backend, relay or
central authorization service.

`AppModule` binds `AndroidMarmotFactory.port`, an actual
`LiveMarmotSnapshotPort`. `BlockedMarmotSnapshotPort` remains only for unsupported
hosts and explicit tests. `NativeSharingGate` is historical evidence; its old
qualification flags have not been invented or toggled to enable this binding.

## Dependency decision

The subsequent owner-authorized dependency/publication pass retains Quartz 1.05.1
for account signing and the pinned native MDK host. [The critical dependency
comparison](marmot-dependencies.md) separates released Amethyst/Quartz, current main
and official UniFFI APIs, measured costs and migration limits. Account signing is
now shared by friendship certificates, leaf proofs and native callbacks. This
pass authorizes only normal feature publication; main/stable remain owner-only.

Native development/test builds keep limited debug information and disable
incremental scratch, while retaining explicit debug assertions and overflow
checks. A clean same-source host comparison reduced target storage by 43%;
the Android release profile is unchanged and the same-path native build retains
identical library bytes. Feature APK
compiler diagnostics are surfaced as bounded GitHub annotations without changing
publication authority. Build measurements and exact-SHA CI results remain separate
from Android runtime/device qualification.

## Friendship and current private data

A root-account-signed request and an explicitly signed acceptance establish one
mutually confirmed friendship generation. The requester selects **only their own
outgoing data**; the acceptor selects **only their own outgoing data**, including
none. Friendship expresses general interest in ongoing reception. Later changes
or expansions consciously signed by the data owner require no new recipient
acceptance. Neither side can select or widen the other side's outgoing rights.
A Nostr follow list, MLS membership, old category consent or an old snapshot does
not establish this friendship.

Pairwise fanout reuses the qualified two-leaf MDK adapter and isolates different
history/categories/object exceptions before encryption. Groups per identical
scope could reduce fanout but need additional qualified membership, split and
rekey behavior. One group containing differently authorized friends would expose
fields to all members. Current circle members are selected explicitly for review;
future friends receive a new request and only the offered current/history scope.
A SERVER is an explicitly pinned equal endpoint, with no additional rights.

| Selectable source | Current projection | Excluded |
|---|---|---|
| `user_profiles`, `UserRepositoryImpl` | Active name, climbing grades/experience, weekly frequency, equipment, goals | Age, body measurements, injuries, assessments |
| `ascents` / `bids`, `PersonalBoardRepositoryImpl` | Stable UUID, climb/board/angle, outcome, attempts, stored difficulty, date | Comments, location, external IDs, sync flags, frames |
| `climb_notes`, actual private editor | Current text and climb identity, including changes/deletions | Earlier note versions; notes are not implicit in training |

The training preview offers today or 30/90 days of history plus later entries,
with an explicit UTC calendar cutoff. No health/binary-video adapter is claimed.
Public Blossom URLs do not encrypt private media. Preview, initial state and
deltas use the same `ContinuousSourceAdapter` over actual app repositories.
Transactional SQL triggers cover insert/update/delete/import paths, including
active-profile ordering and UUID changes. Source generation and monotonic global/
resource revisions identify `profile:active`, `ascent:<uuid>`, `bid:<uuid>` and
`note:<climb_uuid>` within the owner estate. Foreign records never enter canonical
owner tables, statistics, normal logbook/backup sources or local ownership.

### Authentication, generations and ordering

Private application kind **1223** now uses wire version **2**, purpose/label
`cc.friendship.sync.v1`. Owner selections use `cc.friendship.selection.v1`; all
friendship root signatures use the separate existing NIP-01 signing-envelope
domain `cc.sharing.friendship.v1`. This is an app convention above Marmot, not a
new cryptographic primitive. Strict formats, canonical encoding and actual BIP-340
verification remain mandatory. The bounded verification cache only caches exact
immutable signed bytes, never mutable authorization/session decisions.

The initial request selects a random friendship ID. Its owner-signed selection
binds both accounts and USER/SERVER roles, actual authority devices, source and
writer-authority generations, native session binding, current per-peer relationship
ledger head, scope and creation time. The acceptance includes the acceptor's independently
signed initial selection, referencing the exact signed request hash and same
friendship. A reverse-direction PAGE carries both initial certificates as well,
so redundant-relay reordering can admit the exact already-signed acceptance before
its separate ACCEPT arrives. This only applies to a locally existing pending
request; it cannot establish an unsolicited friendship. Both initial certificates
remain necessary to activate either outgoing direction. Each direction has its own stable grant ID and strictly
increasing owner-signed selection version. A later selection may expand without
recipient action; a recipient signature cannot do so. Unknown generations cannot
bootstrap on a PAGE, ACK, resync or selection. Legacy formats never upgrade.

The controller commits the local policy, peer registry/role and signed outgoing
selection in one app database transaction under the shared session lock. A late
signer refusal rolls back earlier ALLOW changes; native publication occurs only
after the commit. The signer's bounded wait can hold this local transaction;
actual Android/Amber UI responsiveness needs device qualification. Tightening
policy removes data; later owner-signed policy can restore only categories still
inside that owner's signed selection. An explicitly removed selection cannot be
expanded by a circle rule or a receiver action.

A signed durable local request intent bridges discovery/session preparation.
It binds peer, own scope, role, source/authority generation and a seven-day request
deadline; it contains no source records. After native authentication is ready the
same request ID becomes the signed session-bound offer. Refused external signing
leaves it pending. Explicit discovery opt-in permits quarantined preparation of
incoming authenticated two-leaf connections, never friendship acceptance or data
selection. Established sessions are not automatically replaced by new Welcomes.

Friendship itself has no recurring renewal deadline. Requests expire after seven
days; transport envelopes after one day. Received payloads have a seven-day
freshness lease and are actually deleted when it expires. Resumption requests a
full current permitted projection without re-consenting to friendship. Source or
writer-authority replacement and an authenticated device/epoch binding change
fail closed; recovery cannot take over existing grants by reception time. A new
friendship is an explicit new generation. Missing connectivity alone does not
establish a changed identity.

Every transfer names direction/generation, owner selection, sequence/base,
source revision/time, current allowed categories and complete target digest.
Initial/resync transfers are full current projections; ordinary updates are
upsert/tombstone deltas from the last **recipient-acknowledged** state. Durable
page staging installs a validated complete target atomically before ACK. Missing
or deleted bases request full resync. Reordering/duplicates are idempotent;
conflicting pages, stale versions, wrong identities and formats fail closed.
One in-flight target coalesces bursts; an exact durable obligation precedes JNI,
and source, current owner policy, device authority and native binding are checked
again at actual handoff/publication. Withdrawing a queued update stops export.

### Narrowing, ending and actual deletion

Changing one's categories/history removes excluded received records when the
peer processes the authenticated selection/current projection. Existing object
rules filter the actual payload; category ceilings still apply. A circle downgrade
changes only that owner's outgoing direction. The UI distinguishes keeping listed
personal exceptions from clearing them; clearing and circle reassignment commit
as one signed/attested batch, so refusing a later signature cannot transiently
remove a DENY. The reverse direction is independently owned and remains unchanged.

Ending from either side ends the whole friendship. The triggering client first
commits removal of both local directional caches, received records and staged/
pending data, then signs a durable `cc.friendship.end.v1` end certificate. A signer
failure cannot undo local deletion. Known obsolete native handoffs/inbox payloads
are removed. The peer verifies the end, clears both directions, stops its exports
and returns an authenticated cleanup ACK. End requires no new consent. Even an
end received before its delayed request leaves a closed-generation tombstone.
Remote cleanup is pending until that peer synchronizes. Relay OK is not cleanup
confirmation; an app ACK is cooperation, not evidence against modified clients,
screenshots or already exported copies.

Minimal generation IDs and signed terminal control evidence remain. Normal
structured backup excludes foreign replicas, Android backup/device transfer
excludes databases, and native storage is under `noBackupFilesDir`. Supported
permission recovery clears continuous bodies/request intents while retaining
closed IDs. Old traffic/resync cannot restore those generations. Arbitrary manual
rollback of an entire device image is outside the supported restore contract.
There are no foreign-data search indexes, media caches, thumbnails or binary
transfers in this text-data projection; the Compose views derive from the replica
rows and react to deletion. No physical flash/SQLite or JVM-memory erasure is
claimed.

Native source ACK removes the plaintext inbox payload and retains source/fence
provenance for eight days. Explicit disposal matches the exact stored fence and
kind even if current connectivity is missing. A dedicated read of native canonical
retirement clears replicas even when no replacement session exists; absent
connectivity alone cannot supply that evidence. At provenance quota, a cleared
acknowledged row preserves provenance without preventing deletion. Replayed
engine notifications cannot recreate an acknowledged plaintext row. Superseded
kind-1223 outbox obligations and correlations are removed; MDK core fanouts and
legacy kinds are not indiscriminately deleted. SDK canonical encrypted MLS wires
remain for convergence; this host does not enable SDK plaintext chat projection.
Synthetic inspection measures the actual host journal, SDK message/pending-event/
queued-intent stores and asserts the unused SDK app-event store is empty.

Limits are explicit: 16 active friendships, 512 total directional/generation rows,
1,000 records/1 MiB per projection, 8 KiB per record, 16 changes/48 KiB per page,
128 pages and 64 KiB wire maximum. No silent truncation. Work budgets are eight
handoffs/publications per run and two pages per direction; route scans rotate
across bounded batches. Existing native 32 MiB/4,096-row journal and 256 MiB database
limits remain. A full source/provenance/transport store shows a capacity/pending
state; it does not silently broaden scope or discard pending permitted data.

Clock repair validates the active account/device, closes friendship data locally,
and only unlocks after the app rows are payload-free terminal generations. Signed
END obligations remain available for later peer cleanup. Native state is rehydrated
and obsolete plaintext is disposed before a recovery archive can be created.
A native `reopen_required` response closes that poisoned handle; the next operation
must hydrate durable state and pass authorization again. Garbage collection runs
when the account/app executes; an offline or stopped app cannot enforce a wall-clock
physical deletion deadline. Dormant stores are unreadable past their lease when
opened; no flash-erasure or arbitrary device-image rollback guarantee is made.

### Automation and honest UX

Application lifecycle, network reconnect and transaction-completed SQL source
notifications drive `ContinuousSharingAutomation`; changes coalesce for two
seconds. Foreground activity polls about every 30 seconds. Existing WorkManager
2.11.1 adds connected-network work with backoff and a 15-minute periodic backstop.
Explicitly enabled discovery can poll for initial requests without an existing
friendship. There is no required backend, push server or permanent VPS process.
Android Doze, force-stop, offline state and signer permissions prevent an
unconditional real-time guarantee.

Unattended signing uses the actual pinned Quartz 1.05.1 `backgroundQuery` provider
API without foreground/Intent fallback. Friendship selection and END root signatures are explicitly bound to the same provider-only route as native discovery/account proofs; a refusal cannot try a second interactive channel. Missing permission is visible and needs
opening the app; ordinary source edits use MLS without a new category/snapshot
acceptance. UI strings are paired EN/DE: own selection and actual preview,
request/accept/decline, read-only current data with origin/time, pending changes,
last recipient confirmation, offline/signer/capacity, local end, remote cleanup
pending and real cleanup confirmation. Host tests cannot qualify actual Android,
Amber, Keystore, WorkManager scheduling or battery behavior; CI/device evidence
is reported separately.

## Qualified native dependency

`native/marmot/prepare.py` fetches and checks an immutable official MDK archive,
applies a narrow reviewed extension without fuzzy patching, and verifies the
entire patched source tree, including safe symlink targets. It refuses a changed
cache. `Cargo.lock`, `rust-toolchain.toml` and the archive/patch/tree hashes pin
inputs. No upstream install script, Marmot app, Nostr SDK, keyring, backend or
hosted authorization service is included.

| Component | Pin |
|---|---|
| MDK engine/session/storage | `615d0c1cf48dbb231ecce0e3c8cf5cc3677eda57` |
| MDK archive SHA-256 | `414bb00dd79cc4fed3615fd9c0e0fdc6b7340d9987edbc75ecc22160e4b9ce1e` |
| OpenMLS fork | `59e7d3b27a7e95237879dd5478de1fd90eff7ada` |
| Nostr | `0.44.8` |
| Rust / Android NDK | `1.97.1` / `27.2.12479018` |
| Native SQLite binding | rusqlite `0.40.1`, SQLCipher `4.17`, pinned upstream fork |
| JNI target | arm64 Android API 28; 16 KiB ELF load alignment |

The MDK pin is [v0.9.21](https://github.com/marmot-protocol/mdk/releases/tag/v0.9.21)
plus the official [terminal-group deferred-budget fix #1784](https://github.com/marmot-protocol/mdk/commit/615d0c1cf48dbb231ecce0e3c8cf5cc3677eda57).
The upstream tests for terminal retirement and interrupted writes pass using
its explicit test-only policy-override feature. That feature is **not** enabled
in the application. The production convergence policy remains the upstream
one-second quiescence/five-second maximum. A different absolute build directory changes embedded OpenSSL build metadata
and thus the ELF hash; reproduction must also preserve the build paths. A clean
same-path arm64 rebuild was byte-identical. This is
not hidden by stripping or rewriting the cryptographic library. See the pinned
[workspace](https://github.com/marmot-protocol/mdk/blob/615d0c1cf48dbb231ecce0e3c8cf5cc3677eda57/Cargo.toml)
and [local build instructions](../../native/marmot/README.md).

The Android catalog still uses Kotlin 2.3.0, AGP 8.13.2, SQLDelight 2.3.2,
SQLCipher Android 4.14.0 and Quartz 1.05.1. Native static symbols are hidden to
avoid interposing its separate SQLCipher on the Android database library.

## Protocol and identity boundaries

The surveyed Marmot specification is
[`4a2bc65f8db5866cec3b2a127dedb37818eaf207`](https://github.com/marmot-protocol/marmot/tree/4a2bc65f8db5866cec3b2a127dedb37818eaf207).
Its [identity rules](https://github.com/marmot-protocol/marmot/blob/4a2bc65f8db5866cec3b2a127dedb37818eaf207/foundation/identity.md)
require account-identity proof v2 binding the Nostr account to the actual MLS
leaf signature key. The account key, MLS leaf key and permission-device key
are distinct. Existing UniFFI timeline DTOs did not expose the canonical leaf
and durable application-source contract required here; a local Rust extension
exposes actual engine state instead of substituting account strings as devices.

The extension returns a hash commitment to group ID, epoch and the native epoch
authenticator, plus verified canonical member credentials and signature keys.
It never exports the epoch authenticator or encryption keys. Exactly two distinct
accounts/leaves, a stable current profile and no unresolved convergence are
required. A replaced group, changed epoch/member set, terminal group or withdrawn
admitted source invalidates the old binding. Retired bindings cannot revive.

Inner kind 1221 carries a permission-device proof of possession bound to that
actual leaf/session, countersigned by the account root. Both BIP-340 signatures,
NIP-01 event IDs, exact tags, timestamps and 24-hour expiry are checked. The local
device must also pass the existing signed manifest/authority reduction. A changed
local device cannot reuse an old cached endorsement. Neither this proof nor MLS
membership grants content access. The real-signature tests also found and fixed
a missing SHA-256 implementation in `SecureDbDeviceIdentity.DeviceCrypto` that
previous synthetic signers had masked.

[Application message rules](https://github.com/marmot-protocol/marmot/blob/4a2bc65f8db5866cec3b2a127dedb37818eaf207/foundation/application-messages.md)
and [durability rules](https://github.com/marmot-protocol/marmot/blob/4a2bc65f8db5866cec3b2a127dedb37818eaf207/protocol-core/durability.md)
are enforced by the actual MDK ingest/send paths. Kinds 1220 (snapshots), 1221
(device binding), 1222 (policy/consent) and 1223 (friendship data synchronization), with exact `cruxcoach.private` labels,
are CruxCoach application conventions, not a claim of registered Marmot
interoperability. Unknown versions, fields, actions, signatures and legacy
permission restrictions fail closed. No new cipher or signature primitive is
implemented; MDK/OpenMLS, Nostr BIP-340/NIP-44 and the existing AES-GCM vault do
the cryptographic work. [RFC 9420 §16](https://www.rfc-editor.org/rfc/rfc9420.html#section-16)
explains the remaining endpoint and delivery-service assumptions.

## Discovery and redundant relays

The configurable default pool is exactly the six URLs confirmed in the owner's
Blossom-Sync source. Defaults apply only to a never-configured account. Malformed,
unknown or wrong-typed saved relay settings pause transport before database-key
access; they never silently fall back to public relays. The EN/DE UI displays the
invalid configuration and allows explicit correction. No publisher, cron job, upload or secret from that repository
is used. Explicit discovery activation publishes signed NIP-65 kind 10002,
NIP-17 inbox kind 10050 and actual MDK KeyPackage kind 30443. Consumed/expired
packages refresh; the explicit refresh action can rotate discovery packages.
Inviting checks the peer's signatures, account proof, package lifetime and exact
Marmot metadata before creating the group. A Welcome (1059) is durably received
but remains an unaccepted invitation. Replacement invitations require a new
user action and invalidate earlier snapshot bindings when accepted.

Directory relay hints are intersected with the local configured pool. Group
445 messages follow authenticated MLS routing components. Prior routing data is
retained only while the actual MDK transport index retains that route; directory
hints never change canonical group state.

Bounded public probes on 2026-09-11 used ephemeral identities and nonsensitive
synthetic data. These probes ran the stable v0.9.21 native engine before the
additional terminal-budget fix; local qualification covers the final pin.

| Default relay | NIP-11 advertised max REQ limit / message bytes | Observed protocol behavior |
|---|---|---|
| `wss://relay.primal.net` | 500 / 1,048,576 | All five needed kinds accepted and read back |
| `wss://relay.damus.io` | 500 / 1,048,576 | Group 445 rejected; Welcome rejection/non-readback/AUTH and transient KeyPackage failure |
| `wss://nostr-pub.wellorder.net` | no advertised bounds | All five needed kinds accepted and read back |
| `wss://nos.lol` | 500 / 131,072 | All five needed kinds accepted and read back |
| `wss://nostr.oxtr.dev` | 500 / 131,072 | All five needed kinds accepted and read back |
| `wss://blossom.cruxcoach.org/nostr` | 50 / 262,144 | All five needed PUB/REQ kinds rejected |

The continuous product pass repeated a **read-only** probe: 30 fresh synthetic
filters, no matched user events and no publications. The same four relays
completed all five reads; Damus required AUTH for 1059 and was unavailable for
445; Blossom rejected all five. This is request compatibility evidence, not a
new public continuous-delivery roundtrip or a global-freshness guarantee.

NIP-11 is an advertisement, not proof of transport support. Exact event readback
and native encrypted roundtrips supplied the evidence above. Availability and
policy can change. Upstream Marmot app marks Damus retired; this integration does
not import that app's pool. It retains the explicitly requested URL and reports
its observed failure rather than silently changing the owner's list.

The client pins validated DNS addresses, rejects private targets in production,
uses TLS with an explicit standard provider/root store, verifies signatures and
REQ filters, and bounds frame size, bytes, event count and connection duration.
NIP-42 AUTH requirements are displayed; it does not silently disclose an account
to satisfy a challenge. No automatic alternative relays are added. A pool with
no usable route remains visibly pending/failed and preserves queued data.

## Permission and snapshot lifecycle

The existing signed owner-policy, relationship, device-manifest and authority
DAGs remain authoritative. Mutable caches, circles, UI state and group membership
cannot grant rights. Rules are deny by default and bind category **and** object;
resource epochs must match exactly. AAD v2 binds the owner, row, category, key
scope and epoch. Unknown/legacy AAD is owner-compatible only and cannot enter
peer export. Received-copy scopes cannot be exported through this service.

`SharingPolicyTransport` sends a current signed relationship head and requested
categories over the authenticated session. Only an explicit recipient action
signs `RecipientAccepted`. Its signer, device, parent, head, categories, sequence,
session and lifetime must match; the owner admits it through the existing
transactional authority reducer. Extending permission does not reuse old consent.

A snapshot then follows OFFER → explicit CONSENT → encrypted CONTENT → RECEIPT.
Its immutable offer binds both accounts, endpoint roles, authority devices,
session commitment, category, exact resource epoch, digest and expiry. The owner
reads the actual sealed resource and checks current permission both at handoff
and immediately before network publication, under the same database transaction
and session fence. The recipient validates the exact consent/digest and persists
the copy encrypted before acknowledging. Every later read rechecks session,
authority, expiry and terminal state. RECEIPT is cooperating recipient acceptance,
not human reading or proof against a malicious recipient.

REVOKE/DECLINE are terminal even when they precede OFFER. Duplicate, stale,
reordered and differently bound messages cannot resurrect a grant. Local policy
narrowing immediately blocks export and withdraws snapshots at synchronization.
Server role conveys no reverse grant, device enrolment or access to another row.

## Atomicity, offline recovery and limits

One native owner holds an exclusive process lease per encrypted database. A
Kotlin monitor spans fence acquisition, callbacks and handoff; a native mutex and
SQLCipher transaction span actual engine mutations, source journal and fence
reconciliation. A rolled-back mutation poisons the in-memory engine and requires
reopen. Application database commits precede native inbox acknowledgements.

The durable inbox is keyed by actual MDK source identity and checks its canonical
processed state, group and epoch even after acknowledgement. Retraction clears
payload and retires the whole former binding before future reads. Native and
application stores are separate: idempotent handoff and durable unacked delivery
bridge their crash windows without claiming a distributed SQL transaction.

An outbox contains frozen exact ciphertext, a stable operation correlation and
per-relay attempts, acceptance and exponential backoff. Native MDK fanout records
are confirmed only from matching relay OK results. Reconnect never regenerates a
private message or publishes it without current application authorization. A
relay OK is not a recipient RECEIPT. Total outage keeps the operation pending.

Relay history uses durable per-source checkpoints, backwards pages of 50 and a
two-day overlap for backdated envelopes. A saturated same-second page remains
LIMITED instead of skipping history. At least one current authenticated route
must have a recent complete local scan before private publication; EOSE is **not**
proof of global freshness, consensus or the absence of a withheld commit.
Redundant relays improve availability, not truthfulness of every relay.

Limits include 512 KiB frames, 8 MiB per relay exchange, 256 distinct events/300
frames, 64 received live groups, a 32 MiB/4096-row host journal, 1 MiB journal rows,
256 application snapshots and a 256 MiB native database threshold. JNI inbox and
pending transfers use pages of 16. Overflow fails closed with a visible state;
limits are not bypassed to finish sync. Expired snapshots/tombstones are collected
only after their replay lifetime; original owner resources remain intact. Native
inbox evidence for snapshots is retained for eight days, longer than their seven-day
lifetime. Friendship provenance uses the eight-day bounded retention described above. Retired bindings and ambiguous core fanouts are not discarded silently.

`SharingPermissionClock` combines durable high water with monotonic elapsed time.
A wall-clock jump/rollback beyond five minutes locks sharing. Explicit recovery
first withdraws all local grants, invalidates snapshots/consents, then resets the
clock. It cannot recover rights by rolling time backwards. Native-storage recovery
similarly requires withdrawal and archives the old encrypted database before a
fresh session. Legacy snapshot recovery can retain pending encrypted data in the
archive, never automatically send it. Friendship recovery first removes received
application payloads and obsolete native obligations as described above.
Whole-device rollback, a frozen/replaced OS clock and a compromised endpoint are
not solved by a local clock. Offline receivers learn remote revocation on sync or
lose access on expiry; decrypted copies cannot be recalled.

The UI exposes discovery, relay configuration/status, invitation and category
consent, sender/recipient snapshot states, failures and explicit recovery in
English and German. Legacy controls remain separate; friendship synchronization
uses the app-lifecycle/source/reconnect/Android WorkManager path described above.
No production server scheduler or service is installed.

## Storage, migration and privacy

SecureDB is schema 33. Migration `30.sqm` added account-scoped policy transport;
`31.sqm` adds source generation/revisions, mutation triggers and initially empty
continuous storage. `32.sqm` deletes the previous continuous-v1 replicas without upgrading consent and adds pending signed request intents. Existing snapshot acceptance does not establish friendship. Existing grants, snapshot tombstones,
clock locks and sealed data retain their meaning. Release migrations 1–13 remain
byte-identical; earlier feature migration-lineage and AAD guards remain enforced.
Unsupported overlapping unpublished feature schemas are refused before DDL,
never reset into permissive defaults. Restoring keys/ciphertext does not restore
session or consent. No device migration was executed in this run.

Android derives a domain-separated native database key using the existing key
manager, uses owner-private SQLCipher under `noBackupFilesDir`, and keeps account
signing behind the actual Quartz/Amber callback. Account private keys are not
exported to JNI. The permission-device secret is opened only for signing and its
buffers are erased afterwards. Relay lists are account-scoped app preferences;
MLS/policy payloads, source state and detailed outbox metadata are encrypted.
Native/adapter errors redact payloads and provider messages. Protocol traffic
still reveals relay timing, sizes and connection addresses; discovery reveals
public account presence and relay choices. Immutable JVM strings, UI copies and
recipient screenshots cannot be reliably erased by revocation.

## Tests, threat model and operational boundary

Focused tests cover actual MDK cryptography through JNI and the application
repository: user–user and isolated server–user bootstrap/consent/send/decrypt/
receipt/revoke, duplicate relays, all/partial failure, offline backfill, restart,
wrong identity/key, replay, stale epochs, authentic route rotation and member
removal, clock recovery, quota collection and schema migration. The standalone
synthetic endpoint and local process tests are in `scripts/marmot_*.py`.
No mock transport substitutes for their native encrypted path.

Final friendship process run v4 passed all three paths: USER plus two independent
USER friends, SERVER plus two USER friends, and USER to SERVER. It asserts actual
native queued payload during total relay outage before withdrawal, distinct own
scopes, expansion without another acceptance, storage deletion and the unavailable
CruxCoach route. The two three-participant runs each passed 22 named phases,
including restart, both-direction cleanup, new friendship and actual MLS epoch
retirement. Final native transport/relay/storage checks passed 15 cases; the final
legacy native integration regression passed all eight cases. Focused source,
schema, backup/recovery, signer, UI and automation results and corrected intermediate
failures are enumerated without double-counting in `friendship-sharing/verification.md`.

Security review priorities are unauthorized export, confused account/device/
server authority, stale/replayed consent, revoked queued ciphertext, source
retraction, crash windows, malicious relay input and quota/clock denial of service.
The corresponding enforcement is at storage, native handoff/publication, inbox
admission and read doors, not only the UI. The detailed continuation review and
command/log evidence live in the owner's run directory under `live-integration/`,
`continuous-sharing/`, `friendship-sharing/` and `dependency-publication/`.

For the preserved legacy path, this profile closes existing snapshot access on
epoch/group/device changes;
each new snapshot requires fresh consent for its new binding. Signed category
consent may remain valid for the same account and still-authorized permission
device with unchanged rights; it never authorizes a new permission device by
virtue of MLS membership alone. Other clients must implement the same private application conventions.
The local server adapter uses synthetic identities only; production server key
custody/operation is not configured. Full tests and native participant acceptance
run in feature CI; exact-commit APK build/publication needs its own successful
trusted publisher and delivered receipt. That evidence is recorded separately
from host tests. Android arm64 runtime, Amber and Keystore/process-death behavior
still require device qualification. Normal feature publication is owner-authorized;
main/stable merging, production administration and device installation are not.

## First friendship in the app

1. Each participant uses their own account and authorized permission device.
2. Enable discovery deliberately to receive requests; review the six relay statuses.
3. In the friendship card choose the other public account/npub, own data/history,
   explicit endpoint role and actual preview, then request friendship. Pending
   native discovery/Welcome/account-device proofs are prepared automatically.
4. The recipient confirms friendship and chooses only their own outgoing data
   (including none). Both directions now update automatically; later owner-signed
   scope changes need no new receiving dialog.
5. Current records show origin, age and recipient confirmation. Stop one's own
   outgoing data independently or end friendship to remove both received directions.
   Remote cleanup remains pending until its authenticated app acknowledgement.

The synthetic endpoint's automatic test-device genesis is not a production
identity enrolment or server installation. Full CI/device qualification remains
separate from the focused host/native evidence.
