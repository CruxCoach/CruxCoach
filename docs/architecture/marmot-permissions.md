# Marmot permissions and live snapshot exchange

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

The usable profile is **two distinct account/device leaves and immutable UTF-8
text snapshots**, up to 32 KiB and seven days. Both endpoints choose the peer's
full account public key and USER/SERVER role, accept the transport invitation,
consent to categories and separately accept each snapshot. A server is an
optional equal peer with its own isolated identity. User–user traffic requires
neither a CruxCoach backend nor a CruxCoach relay. There is no central permission
validator, account registry or inherited publisher/owner-server privilege.

`AppModule` binds `AndroidMarmotFactory.port`, an actual
`LiveMarmotSnapshotPort`. `BlockedMarmotSnapshotPort` remains only for unsupported
hosts and explicit tests. `NativeSharingGate` is historical evidence; its old
qualification flags have not been invented or toggled to enable this binding.

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
(device binding) and 1222 (policy/consent), with exact `cruxcoach.private` labels,
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
inbox evidence is retained for eight days, longer than the seven-day snapshot
lifetime. Retired bindings and ambiguous core fanouts are not discarded silently.

`SharingPermissionClock` combines durable high water with monotonic elapsed time.
A wall-clock jump/rollback beyond five minutes locks sharing. Explicit recovery
first withdraws all local grants, invalidates snapshots/consents, then resets the
clock. It cannot recover rights by rolling time backwards. Native-storage recovery
similarly requires withdrawal and archives the old encrypted database before a
fresh session; pending data is retained in the archive, not automatically sent.
Whole-device rollback, a frozen/replaced OS clock and a compromised endpoint are
not solved by a local clock. Offline receivers learn remote revocation on sync or
lose access on expiry; decrypted copies cannot be recalled.

The UI exposes discovery, relay configuration/status, invitation and category
consent, sender/recipient snapshot states, failures and explicit recovery in
English and German. Foreground sharing screens synchronize every 15 seconds;
leaving them stops polling. No system scheduler or production worker is installed.

## Storage, migration and privacy

SecureDB is now schema 31: migration `30.sqm` adds only account-scoped incoming
and outgoing policy transport tables. Existing grants, snapshot tombstones,
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
removal, clock recovery, quota collection and schema migration. A standalone
synthetic endpoint and two-process local test are in `scripts/marmot_*.py`.
No mock transport substitutes for their native encrypted path.

Security review priorities are unauthorized export, confused account/device/
server authority, stale/replayed consent, revoked queued ciphertext, source
retraction, crash windows, malicious relay input and quota/clock denial of service.
The corresponding enforcement is at storage, native handoff/publication, inbox
admission and read doors, not only the UI. The detailed continuation review and
command/log evidence live in the owner's run directory under `live-integration/`.

This profile closes existing snapshot access on epoch/group/device changes;
each new snapshot requires fresh consent for its new binding. Signed category
consent may remain valid for the same account and still-authorized permission
device with unchanged rights; it never authorizes a new permission device by
virtue of MLS membership alone. Other clients must implement the same private application conventions.
The local server adapter uses synthetic identities only; production server key
custody/operation is not configured. Complete CI, an Android arm64 runtime test,
Amber interactions and Android Keystore/process-death behavior still need the
owner's normal review and device/CI workflow. No push, APK build, device install,
release, signing, production deployment or remote PR occurs in this task.

## First exchange in the app

1. Enrol the current permission device using the existing device-administration
   flow if it has not been enrolled. Each participant uses their own account.
2. In Sharing, explicitly activate discovery and review the relay pool/status.
   Both participants need at least one mutually configured usable route.
3. Add the other account/npub, choose its USER/SERVER trust role, then invite it.
   The recipient accepts the displayed invitation for the exact account.
4. The owner offers the required categories. The recipient explicitly accepts
   the incoming category proposal; receiving the proposal alone grants nothing.
5. The owner creates a private text snapshot. The recipient accepts that exact
   offer, synchronizes and opens the encrypted copy. The owner's delivery state
   advances only after the recipient's protocol receipt.
6. Revoke the snapshot to close subsequent application reads and queue an
   authenticated retraction. Already copied plaintext cannot be recalled.

The local synthetic endpoint performs its own test-device genesis automatically;
that test setup is not a production identity enrolment or server installation.
