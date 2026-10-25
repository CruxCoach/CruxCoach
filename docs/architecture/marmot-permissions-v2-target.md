# Marmot permissions v2: target architecture

[Deutsch: Kurzfassung](marmot-permissions-v2-target-overview.de.md) ·
[Current v1 architecture](marmot-permissions.md) ·
[FEAT-062 policy model](../specs/0.2.3/FEAT-062-personal-information-sharing.md)

Status: design written 2026-09-23 for the branch now named
`feat/marmot-permissions-v2` (forked from `5af649ac0`, the permission branch after
the 0.2.3 QA merge; APKTrack track `feat-marmot-permissions-v2-0bfee131`). It is
not a release, a deployment or a security certification.

## 1. What changes and why

The owner's threat model requires MLS: the Nostr identity key is not the data
key (a leaked nsec must not decrypt shared data), forward secrecy against
ciphertext retained by public relays, post-compromise security, and no
home-made ratchet. Marmot/MDK therefore stays the confidentiality layer.
Plain NIP-44 events are rejected as a replacement.

v1 is complex *around* OpenMLS, not inside it. Roughly 40,000 lines (without
specs) sit above and below the engine:

| v1 area | Size | Why it goes or shrinks |
|---|---|---|
| Kotlin relay client, outbox, fences, 1221 leaf/device proofs (`LiveMarmotSnapshotPort`, `MarmotSnapshotPort`, `FriendshipTransportPreparation`) | ~400 lines Kotlin + JNI ops | Transport moves to Rust; the MLS leaf *is* the account in a single-device model |
| Native host: one-shot relay exchanges, journal outbox/inbox, ACK compaction, provenance buckets (`node.rs`, `relay.rs`) | 2,370 lines Rust | Replaced by nostr-sdk pool, in-process relay, replicator, simple inbox |
| Three application protocols (1220 snapshots, 1222 policy/consent, 1223 friendship sync with signed certificates, END certificates, ACK compaction) | ~1,300 lines Kotlin | One kind, one small sync protocol |
| Signed ledgers, multi-device authority, attestations, recovery code, encrypted permission backup, clock lock, key vault | ~6,500 lines shared + ~4,300 lines Kotlin (`SecureDbSharingRepository`, `SharingController`) | Policy becomes local and unsigned; one device per identity in v1 |
| Tests for the above | ~24,700 lines | Rewritten for what remains |

The complexity lives in two places v2 removes: (a) every private message had
to be fenced to one exact MLS epoch/leaf binding, so any epoch change (a
self-update, i.e. the very operation that provides post-compromise security)
ended data flow and required new certificates; (b) atomicity between the app
database and the native journal was reconstructed with durable obligations,
fences and compaction. v2 binds a relationship to the *group* and the two
authenticated accounts, not to an epoch, and replaces cross-database
atomicity with idempotent tokens, generations and digests (§7).

## 2. Three layers

```
 Kotlin (UI, policy, sync semantics)             app SecureDB (SQLCipher)
   SharingService ── SharingSync ── SharingStore ─ presets, person/object rules,
        │                 │                         direction state, received records
        └──── MarmotHost (ONE chokepoint: Dispatchers.IO + trace) ────┐
                                                                      │ JNI: open/call/archive/close
 Rust host (libcruxcoach_marmot)                                      ▼
   Engine: MDK AccountDeviceSession (OpenMLS)   ── one SQLCipher DB per account ──
   Store: NostrDatabase + delivery + inbox + tokens + peers (same transaction rail)
   LocalRelay (nostr-relay-builder, in-process, no socket)
   Pool: nostr-sdk Client over CruxTransport [local duplex | pinned public wss | (later) pinned peer endpoints]
   Replicator: outbound delivery, inbound subscriptions, NIP-77 catch-up, relay health
```

### 2.1 Bottom: the native layer owns transport (White Noise shape)

Relay pool, NIP-42, NIP-77, outbox, inbox, KeyPackage upkeep (30443),
Welcomes (1059) and discovery (10002/10050) live in Rust. Kotlin calls the
native layer through exactly one class, `MarmotHost`, on `Dispatchers.IO` with
a trace section per operation, and consumes a pull stream (`next`). The four
reviewed JNI exports stay (`open`, `call`, `archive`, `close`); `next` is an
operation of `call` that waits without holding the engine lock.

**Protocol-data ownership rule** (proposed for `AGENTS.md`, see §12): the
native layer owns all protocol data — groups, members, epochs, KeyPackages,
relay lists and relay state, Nostr events, delivery state and undelivered
inbox messages. Kotlin may hold UI state, short-lived lifecycle state, the
local sharing policy, per-direction sync counters and the *application*
records it has accepted from friends. It must not keep a second copy of
protocol data in SQLDelight, DataStore, SharedPreferences or singleton maps. A
missing projection is added to the native `status` operation, not cached in
Kotlin.

Native operations (`call` JSON, strict schemas, fixed error codes):

| Operation | Purpose |
|---|---|
| `status` | Projection: account, online flag, discovery, relay health (state/auth/NIP-77), local store counts, peers (state, invited-by-me, last seen, cancel time) |
| `set_relays` / `set_online` / `set_discovery` | Native-owned relay list (1–16 `wss://`), pool connect/disconnect, publish/refresh 10002, 10050, 30443 and subscribe to own 1059 |
| `invite` / `accept` / `decline` | Pairwise group creation from a verified KeyPackage; app-level acceptance of a received Welcome; silent forget |
| `send` | Durable local admission of one application message to one peer, idempotent by client token (`duplicate`, `token_conflict`) |
| `cancel` | Remove this peer's application messages that no public relay has accepted yet |
| `end` | Retire the peer's group after its pending outbound messages are delivered (bounded), purge its inbox |
| `next` / `ack` | Pull stream of decrypted application messages (bounded pages, blocking wait ≤ timeout) and acknowledgement after the app commit |
| `sync` | Connectivity edge: wake the replicator immediately |

Harness-only operations (feature `local-harness`, absent from Android):
synthetic identities, `rotate` (MLS self-update), `private_storage_matches`,
fault injection for the CruxCoach relay URL.

Signing stays behind the existing account callback: NIP-01 `sign`, NIP-44
encrypt/decrypt (gift wraps), NIP-42 `AUTH` (kind 22242) and the MDK account
identity proof. Unattended work uses Amber's content-provider route only; a
refusal marks "open the app to sign" and never falls back to an Intent.

### 2.2 Middle: the local relay is the primary target

Every account runs its own relay inside the app process:

* **Store** — a `NostrDatabase` implementation over tables in the MDK SQLCipher
  database (same connection and transaction rail as the MLS state). Admission
  is restricted: only kinds 445, 1059, 30443, 10002, 10050, own-account events
  and discovery reads we asked for; size, count and byte quotas; bounded
  retention.
* **LocalRelay** — `nostr-relay-builder` with that store, never bound to a
  socket in v1. It is reachable through `CruxTransport`, whose `connect` for the
  reserved URL `wss://local.cruxcoach.invalid` creates a `tokio::io::duplex`
  pair, hands one half to `LocalRelay::take_connection` and wraps the other as a
  client WebSocket. The relay supports NIP-77 server-side.
* **Pool** — one `nostr-sdk` `Client` with the store as its database, the
  account signer for NIP-42, and `CruxTransport`. Public URLs are dialled only
  after DNS resolution, public-address checks, TLS with the webpki roots and
  frame/message limits (the v1 `relay.rs` policy moved into the transport).
* **Replicator** — outbound: every own event in the store has per-relay
  delivery rows (local, then the relevant public relays) with bounded
  exponential backoff; inbound: live subscriptions (1059 `#p` self, 445 `#h`
  per active route) plus a NIP-77 down-sync of the last seven days on start,
  reconnect and every 15 minutes; relays without NIP-77 get a bounded REQ
  window instead. Relay OK is transport evidence, never a recipient receipt.

Publishing therefore never blocks on public relays: an MLS send commits the
ciphertext into the local store in the same SQLCipher transaction that
advances the ratchet, and replication is eventual. No central CruxCoach relay
is required; the CruxCoach URL is just one optional entry of the pool.

**Transport plurality (interface now, use later).** The configuration carries
`peer_endpoints: {account → [url]}` next to the public pool. A pinned peer
endpoint may be `ws://` on a private address — the only place the private-host
refusal is relaxed — and only for the account it is pinned to. v1 ships the
field validated and empty; LAN serving (`LocalRelay::run` on the Wi-Fi
address with NIP-42 write auth restricted to friends) and BLE are later
phases. MDK peers still need a routable relay; the group's authenticated
routing component stays public-relay based.

### 2.3 Top: sync semantics as small MLS application messages

One inner kind, one label, strict JSON (§5). Per direction (owner → friend)
the owner sends a **manifest** (categories, cutoff, generation, sequence,
count, digest), then **full** pages for a new generation, then **delta**
messages; the receiver keeps its own state, applies in order, verifies the
digest after every step and asks for a full **resync** on a gap or mismatch.
MLS gives authentication, confidentiality and per-epoch ordering of what it
delivers; relays may still reorder or duplicate, so the receiver buffers a
bounded number of out-of-order deltas. Idempotence comes from the
`(generation, sequence)` pair and per-record revisions.

## 3. Policy (local, unsigned)

* **Two presets**: `FRIENDS` and `ACQUAINTANCES`, ordered
  `ACQUAINTANCES ≤ FRIENDS` with monotonic inheritance as in FEAT-062. Each
  preset has a category set and a training period (30, 90, 365 days or all).
  A newly created `share_preset` table is seeded with the owner-chosen starting
  point: Friends = profile & goals + training history (30 days), Acquaintances =
  profile & goals. The seed runs only where the table is created (fresh install
  or migration 33); a later choice is never reset. Nothing reaches anyone before
  a person is added and accepts.
* **Three categories**: `PROFILE_AND_GOALS`, `TRAINING_HISTORY` (with period),
  `PRIVATE_NOTES`. Health information and videos are out of v1.
* **Person rules**: per person and category ALLOW/DENY; optional period
  override. **Object rules**: per person and record (`ascent:<uuid>`,
  `bid:<uuid>`, `note:<climb>`) ALLOW/DENY. One switch per person turns the
  outgoing direction on or off.
* **Resolver**: `SharingPolicyResolver` from FEAT-062 unchanged in precedence —
  object rule (deny before allow), person rule, preset baseline (inherited),
  otherwise deny. A category is in scope when the resolver allows it or when an
  object ALLOW exists in it (then only allowed objects are sent).
* The signed owner-policy ledger, relationship ledger, device manifest,
  authority attestations, multi-device authority, recovery code, encrypted
  permission backup and the clock lock are removed. One device per identity.

Outgoing projection for peer *P* at time *t*: if the switch is off, the scope is
empty; otherwise categories as above, cutoff `today(UTC) − period` (or none),
records from the existing `ContinuousSourceAdapter` projection (active profile,
ascents, bids, notes — same field whitelist and SQL triggers as v1), filtered
per record by the resolver.

## 4. Relationship lifecycle and visible states

A friendship is an accepted two-leaf MLS group. The inviter creates it from
the friend's verified KeyPackage; the friend sees an invitation and accepts
(choosing a preset for their own outgoing direction, which may be "nothing")
or declines silently. Acceptance becomes visible to the inviter when the first
manifest from the friend arrives. Four visible states, the same everywhere:

| State (DE) | Meaning |
|---|---|
| Ausstehend | Request sent or received, or accepted but no message from the other side yet |
| Aktiv | Group qualified, own outgoing direction on |
| Gestoppt | Group qualified, own outgoing direction off (the friend's data may still arrive) |
| Beendet | Ended by either side, or the group lost qualification; received data deleted |

Group qualification is checked natively on every delivery and send: current
protocol profile, not terminal, exactly two leaves, the local account and the
expected peer account as credentials (MDK account identity proof v2 binds the
leaf key to the Nostr account). Anything else ends the relationship fail-closed.
MLS epoch changes, including self-updates for post-compromise security, do
**not** end a relationship.

## 5. Kind and label register

Outer events (public relays; Marmot/NIP conventions, unchanged):

| Kind | Use | Published when |
|---|---|---|
| 445 | MLS group message, `#h` routing id | every group message |
| 1059 | NIP-59 gift wrap carrying a Welcome, `#p` recipient | inviting |
| 30443 | Marmot KeyPackage | discovery enabled; refreshed before expiry |
| 10002 | NIP-65 relay list | discovery enabled |
| 10050 | NIP-17 inbox relays | discovery enabled |
| 22242 | NIP-42 AUTH | only to a relay that challenges |

Inner application event (inside MLS, never visible to relays):

| Kind | Label tag | Content |
|---|---|---|
| **1230** | `["l","cc.share.v2","cruxcoach.private"]` | canonical JSON, `{"v":2,"type":…}` |

Message types: `manifest`, `full`, `delta`, `ack`, `resync`, `end`. The native
layer fixes kind and tag and rejects every other inner kind in both
directions. v1 kinds 1220 (snapshot), 1221 (device proof), 1222
(policy/consent) and 1223 (friendship sync) are retired; v2 never creates or
accepts them. These are CruxCoach conventions, not registered Marmot
interoperability.

```
manifest {v, type, gen, seq, categories[], cutoff|null, count, digest, asOf}
full     {v, type, gen, part, parts, records[]}                (≤ 16 records, ≤ 48 KiB)
delta    {v, type, gen, seq, upserts[], deletes[], count, digest, asOf}
ack      {v, type, gen, seq, digest}                           receiver → owner
resync   {v, type, gen, have}                                  receiver → owner
end      {v, type}                                             either side
record   {id, category, revision, fields{…}}                   v1 whitelist per category
```

Limits: 1,000 records and 1 MiB per direction, 8 KiB per record, 64 KiB
content per message (native refuses more), 128 full pages. No silent
truncation: an over-limit scope shows a visible limit state.

## 6. Data model

### 6.1 Native (MDK SQLCipher database, `noBackupFilesDir/marmot-v2/<account>.db`)

MDK tables unchanged. CruxCoach tables created by the host, accessed through a
one-function MDK extension (`cruxcoach_sql`) on the same connection and
transaction rail:

| Table | Content |
|---|---|
| `cc2_meta` | schema version, account, relay list, discovery flag, ingest cursor, KeyPackage slot |
| `cc2_event`, `cc2_event_tag` | the local relay store (raw signed events, arrival sequence, origin, expiry, single-letter tag index) |
| `cc2_delivery` | per event and relay: status, attempts, next attempt |
| `cc2_peer` | peer account → group, state (invited/pending/active/ended), inbox relays, invited-by-me, last seen, last cancel |
| `cc2_inbox` | decrypted kind-1230 messages awaiting the app (sequence, peer, content) |
| `cc2_token` | client token → event id, content hash (send idempotency) |

The MDK patch shrinks: the epoch-authenticator leaf snapshot and the opaque KV
journal are removed; the session keeps `cruxcoach_storage()` and storage gains
`cruxcoach_sql`. Two-leaf qualification uses the public `members()` API.

### 6.2 App SecureDB (schema 33 → 34, migration `33.sqm`)

| Table | Content |
|---|---|
| `sharing_preset` | `FRIENDS`/`ACQUAINTANCES` → categories, training days (created with the two defaults) |
| `sharing_person` | peer → preset, outgoing switch, optional period override, local label, created/ended time |
| `sharing_person_rule`, `sharing_object_rule` | person and object exceptions |
| `sharing_outgoing` | per peer: generation, sequence, sent scope, sent map (record id → revision), digest, last ack, heartbeat, pending end |
| `sharing_incoming` | per peer: generation, sequence, scope, digest, as-of, staged full pages, buffered deltas, resync requested |
| `sharing_received_record` | peer, record id, category, revision, climb UUID, date, fields — never in canonical tables |
| `sharing_continuous_source`/`_resource` + triggers | kept from v1 (source revisions) |

Migration 33 drops every v1 sharing table (ledgers, relationships, devices,
manifests, attestations, recovery, key vault rows, sealed items, snapshots,
policy transport, continuous grants/requests) and creates the tables above
empty except for the two default presets. No v1 consent, grant, circle baseline or rule is reinterpreted as a v2
permission. Release migrations 1–13 and the unpublished feature migrations
14–32 stay byte-identical for installed feature builds. `SecureSchemaLineage`
keeps refusing ambiguous lineages before DDL.

Downgrade: an older build opening schema 34 hits SQLite's downgrade refusal
and fails closed; nothing is reset. v1 native state (`marmot-v1/`) is not read
by v2 and is deleted after the first successful v2 start, so v1 plaintext
inbox rows do not linger.

## 7. Crash-window analysis (no app ↔ native atomicity)

Claim: without a transaction spanning the app SecureDB and the native
database, v2 loses no data permanently and never sends data excluded by the
committed policy, except messages already handed to a relay socket at the
moment of a narrowing (the same residual as v1). The native layer keeps its own
atomicity: MLS state, outbound event, token, inbox row and ingest cursor are
written in one SQLCipher transaction.

Owner (sender) direction, serialized by one sync mutex:

| # | Window | Outcome |
|---|---|---|
| O1 | Source row committed, crash before sync | SQL triggers persisted the revision; next run computes the delta. |
| O2 | Crash inside native `send` before commit | Native transaction rolls back (no ratchet step, no event); next run recomputes. |
| O3 | Native committed, app has not stored `sent`/`seq` | Next run recomputes with the same token `gen:seq:part`. Same content → native returns the existing event (`duplicate`). Different content → `token_conflict` → the owner starts generation+1 with a manifest and full pages. The receiver may briefly see the old delta; the new generation replaces it. |
| O4 | App committed, not yet replicated | Event and delivery rows are durable natively; replication resumes. |
| O5 | Some relays accepted | Remaining relays retried; duplicates are dropped by event id and `(gen, seq)`. |
| O6 | Narrowing (switch off, category/object deny, preset change, period shorter) | Order: `cancel(peer)` → app commit → new generation manifest. Crash after cancel: the policy is unchanged, the app sees `last_cancel > generation start` in `status` and starts a new generation. Crash after commit: the sent scope differs from the desired scope, the next run sends the manifest. No message computed after the commit contains excluded data. |
| O7 | End | `cancel` → app commit (delete received records, `end_pending`) → `send(end)` → `end(peer)` natively (retires after delivery, bounded) → clear `end_pending`. Every step is retried from `end_pending`. |

Receiver direction:

| # | Window | Outcome |
|---|---|---|
| R1 | Event stored, not ingested | Ingest cursor not advanced; re-ingested. MDK deduplicates. |
| R2 | Decrypted into `cc2_inbox` | Same transaction as MLS state and cursor; durable. |
| R3 | App apply not committed | Redelivered by `next`; applied. |
| R4 | App committed, `ack` lost | Redelivered; `(gen, seq)` ≤ stored → ignored. Full pages are staged by `(gen, part)`. |
| R5 | End/stop received | App deletes records, then `end`/purge natively; an interrupted purge is repeated because the state is `Beendet`, and later inbox items of that peer are acknowledged without applying. |

Divergence of any cause (O3 content race, a cancelled delta, relay loss) is
caught by the digest in every delta, ack and manifest, and by a manifest
heartbeat at least daily and after every app start. A mismatch triggers one
`resync` per generation.

Removed without loss: exact epoch fences, the durable handoff/publish split,
the native application journal, ACK compaction and provenance buckets, the
Kotlin outbox and publication authorization callbacks.

## 8. Threat-model delta against v1

Unchanged: MLS/MDK confidentiality, forward secrecy, identity ≠ data key,
account identity proof v2, SQLCipher at rest, no backup of native state,
DNS-pinned public-only dialling, no central service.

Better:
* Post-compromise security is usable: epochs may advance without ending
  relationships (v1 ended data flow on any epoch change).
* Much smaller custom surface: no signed ledgers, no custom relay client, no
  per-epoch device proofs. Relay handling is the maintained nostr-sdk pool.
* NIP-42 AUTH (Damus' 1059 reads) and NIP-77 efficient catch-up.
* Publishing is local-first; public relay outages delay but never block.

Weaker or dropped (owner-accepted):
* Policy is local and unsigned. A local attacker who can write the app
  database can change it — but could read the data directly anyway.
* One device per identity. A second device of the same account publishes its
  own KeyPackage; an invitation reaches one of them. No multi-device authority.
* No signed END certificate and no cleanup acknowledgement; remote deletion
  was and remains cooperative.
* No clock lock: wall-clock rollback can widen the training cutoff or
  retention locally, never another party's permissions.
* NIP-42: a relay that challenges learns the account key it already sees in
  `#p` filters.
* Residual: a message on a relay socket at the moment of narrowing may still
  arrive (same as v1). Received copies cannot be recalled (same as v1).
* New dependencies: nostr-sdk 0.44.1, nostr-relay-pool 0.44.3,
  nostr-relay-builder 0.44.0, nostr-database 0.44.0, negentropy 0.5.0,
  async-wsocket 0.13.2 (already in MDK's own workspace lock).

## 9. Decision: own Rust host vs MarmotKit 0.10.x

| Criterion | Own host (MDK crates + nostr-sdk + relay-builder) | MarmotKit 0.10.4 runtime |
|---|---|---|
| Local relay as primary target | Direct: custom `WebSocketTransport` + `take_connection` | Not possible without patching: client built internally, loopback only as test opt-in, private/LAN hosts always refused (`relay_plane/safety.rs`) |
| Future LAN/BLE peers | Pinned peer endpoints in our transport | Blocked by the same safety chokepoint |
| Atomic MLS state + outbound event + inbox | Same SQLCipher transaction via one-function patch | No host tables; would need an Android-side store |
| Amber | Existing callback | `ExternalAccountSignerFfi` (equal) |
| Artifact (arm64) | 35.65 MB measured for the v2 host (v1: ~31 MB) | ~49.5–54 MB stripped, plus JNA |
| Binding | 4 reviewed JNI exports, JSON ops | UniFFI/JNA, 258 methods, reflection |
| Upstream churn | Our 18 session calls have identical signatures at 615d0c1c and 0.10.4 | Breaking binding changes in every 0.10.x |
| Reproducibility | `prepare.py` archive hash + tree hash + `build.py` manifest | Prebuilt artifact or a much larger source build |
| Migration effort | M: host rewrite, Kotlin shrinks | L–XL, still needs local-relay patch |

**Decision: keep the own host**, with the nostr-sdk pool replacing `relay.rs`
and MDK's session/storage/peeler crates as today. MarmotKit cannot provide the
owner's local-relay requirement without a patch against its largest and most
volatile crate.

**MDK pin: stays at `615d0c1c` (v0.9.21+1) for v2 phase 1.** Evidence: our
session API is signature-identical at 0.10.4 and the patch rebases with one
trivial hunk, but 57 of the 116 commits since the pin touch engine, session,
storage or traits within ten days, 0.10.4 requires irreversible storage
migrations 87–89, and every 0.10.x release notes breaking changes. Separating
the transport rewrite from an engine bump keeps failures attributable. The
bump is a separate, prepared follow-up once a 0.10.x release passes without
breaking notes (owner decision, §11).

## 10. File plan

### native/marmot

| Path | Plan |
|---|---|
| `prepare.py`, `prepare_test.py`, `rust-toolchain.toml`, `build.py` | Keep (build digest already covers every `src/*.rs`) |
| `mdk-extension.patch`, `mdk-tree.sha256` | Rebuild: drop leaf snapshot and KV journal, add `cruxcoach_sql`, keep `cruxcoach_storage()` |
| `Cargo.toml`, `Cargo.lock`, `licenses/*` | Update: nostr-sdk, nostr-relay-builder, nostr-database, rusqlite, tokio multi-thread; regenerate inventory with a committed `collect_licenses.py` |
| `src/node.rs`, `src/relay.rs` | Remove; replaced by `engine.rs`, `store.rs`, `transport.rs`, `replicator.rs`, `host.rs` |
| `src/jni_bridge.rs`, `src/lib.rs` | Rebuild: same four exports, new ops, harness module |
| `tests/native_transport.rs`, `tests/relay_boundary.rs`, `tests/storage_boundary.rs`, `tests/support/relay.rs` | Rewrite for v2 flows; keep target names (CI runs them) |
| `examples/local_relay.rs` | Keep (CI fixture) |
| `examples/public_e2e.rs`, `examples/relay_read_probe.rs` | Adapt to the host API (manual probes, not CI) |
| `README.md` | Rewrite |

### androidApp `sharing/` (7,210 lines)

| File | Plan |
|---|---|
| `MarmotNative.kt`, `QuartzBackgroundSigner.kt`, `QuartzSigningAdapters.kt`, `SharingPeerIdParser.kt` | Keep |
| `PrivateApplicationSigner.kt`, `ContinuousSourceAdapter.kt`, `ContinuousSharingAutomation.kt`, `ContinuousSharingWork.kt`, `SharingDemoData.kt` | Rebuild smaller (source → `SharingSource`, automation, demo received records) |
| `AndroidMarmotFactory.kt`, `LiveMarmotSnapshotPort.kt`, `MarmotSnapshotPort.kt` | Replace by `MarmotHost.kt` (chokepoint) |
| `ContinuousSharingExchange.kt`, `ContinuousSharingModel.kt`, `SharingSnapshotExchange.kt`, `SharingPolicyTransport.kt`, `FriendshipTransportPreparation.kt` | Remove; replaced by `SharingProtocol.kt` + `SharingSync.kt` |
| `SharingController.kt`, `SecureDbSharingRepository.kt`, `SecureDbDeviceIdentity.kt`, `KeystoreWrappingKeyStore.kt`, `Nip55LedgerCrypto.kt`, `SharingPermissionClock.kt` | Remove; replaced by `SharingStore.kt` + `SharingService.kt` (signer interfaces move to `QuartzSigningAdapters.kt`) |

### androidApp `ui/sharing/` (3,293 lines)

Rebuild `SharingScreen`, `SharingPeerDetailScreen`, `SharingViewModel`,
`SharingLabels` on `SettingsDestinationRow`/`SettingsToggleRow`/`InfoButton`;
remove `MarmotTransportCard`, `SharingSnapshotCard`, `ContinuousSharingCard`,
`SharingDeviceSection`, `SharingDeviceComposables`, `SharingRecoverySection`,
`SharingPurgeConfirmDialog`, `SharingRowComposables`. Add a received-data
section to the climb detail page (`ui/board`).

### shared `domain/sharing` (6,512 lines main)

Keep `SharingModel.kt` (two circles, three categories), `SharingPolicyResolver.kt`,
`Bech32.kt`. Remove the ledgers, codecs, authority, device manifest,
attestations, recovery, key handles/crypto, native gate, signing envelope,
`EffectiveAccessResolver`, and the Android vault/backup/wrapping-key files.

### SQLDelight

Keep `1.sqm`–`32.sqm`; add `33.sqm`. Replace `Sharing.sq`, `SharingTransport.sq`,
`Snapshot.sq` with the v2 tables; trim `ContinuousSharing.sq` to source/resource
queries. Adapt `SecureSchemaLineage`.

### Tests

Remove tests of removed code (ledger, authority, device, recovery, backup,
snapshot, policy transport, 1223 exchange, clock, vault). Keep and adapt
`SharingPolicyResolverTest`, `Bech32Test`, `SharingPeerIdParserTest`,
`ContinuousSourceAdapterTest`, `SharingLabelsTest`, `ContinuousSharingAutomationTest`,
`SharingProjectionSchemaTest` (v34 migration). New: protocol codec, sync state
machine with crash windows, host JNI integration over loopback relays, UI
semantics, climb-detail join. `scripts/marmot_network_e2e.py` and
`scripts/marmot_continuous_e2e.py` are adapted, not deleted; their CI
invocation is unchanged.

## 11. Phases and effort

| Phase | Content | Estimate |
|---|---|---|
| 0 | This document, German overview, interim report | done |
| 1 | Native store/transport/relay/replicator/engine/JNI; `MarmotHost`; removal of the Kotlin port, outbox and the three v1 exchanges (interim UI shows transport status only); native and process tests | 2–3 days |
| 2 | Protocol + sync engine + local policy; SecureDB migration 33; removal of ledgers/authority; automation; focused tests; continuous process test | 2 days |
| 3 | UI on 0.2.3 patterns, climb-detail join, test guide, docs | 1.5–2 days |

Between phases 1 and 2 the branch compiles and its focused tests pass, but the
sharing screen exchanges no data.

## 12. Open owner decisions (working assumptions in brackets)

1. Branch name and APKTrack track for v2: decided 2026-09-23 as
   `feat/marmot-permissions-v2` (track `feat-marmot-permissions-v2-0bfee131`).
2. MDK bump to 0.10.x (deferred, §9).
3. Default pool: the six Blossom-Sync URLs including Damus (AUTH) and the
   CruxCoach endpoint that rejects the needed kinds (kept unchanged).
4. Presets start with defaults (owner decision 2026-09-23): Friends = profile &
   goals + 30 days of training history, Acquaintances = profile & goals; seeded
   only where the table is created. No `VACUUM` after migration 33.
5. v1 friendships, native state and sharing tables are deleted on upgrade;
   testers re-invite (yes).
6. USER/SERVER endpoint roles dropped; a server is an ordinary peer (yes).
7. NIP-42 AUTH answered automatically with the account signer (yes).
8. Pool online while in the foreground and during WorkManager runs only (yes).
9. `AGENTS.md` section on protocol-data ownership: added with the owner's explicit
   trust-boundary authorisation (2026-09-23).
10. Remote cleanup confirmation removed from the UI (yes).

## 13. As built on `feat/marmot-permissions-v2` (2026-09-23)

The implementation follows this document. Details that were settled while
building, or that differ from the text above:

* **Send tokens are scoped to the group natively** (`<group>/<token>`). A new
  friendship after an ending starts a fresh namespace, so tokens such as
  `g1:m` never collide with the previous group.
* **A withdrawal forces a new generation.** When `cancel` removed at least one
  message, or when the native `cancelled_at` is not older than the current
  generation (a crash between withdrawal and app commit), the next send is a
  manifest plus full pages of a new generation. The receiver therefore never
  waits for a delta that was withdrawn. Narrowing order: withdraw → commit →
  next manifest; a failed withdrawal sets `cancel_pending`, which runs before
  the host may go online again (also before the reachability switch).
* **`outbound_pending` counts own events that no relay has accepted.** One
  accepting relay makes an event reachable; the replicator keeps copying it to
  the others. A permanently unreachable relay therefore no longer holds every
  pass for its full wait.
* **Catch-up asks only connected relays**, and relays recorded as refusing
  NIP-77 go straight to the bounded REQ window instead of being probed again.
  A relay that is down no longer costs every `sync` its full timeouts.
* **A message that overtakes its commit is delivered.** MDK keeps such a
  message as a deferred peel and retries it only inside a convergence advance.
  The host now also advances when `deferred_peel_cutoff_delay_ms` reports it
  ready, and turns MDK's pending application events into inbox rows after
  every advance, not only after the next ingest. Found by the continuous
  process test after an MLS self-update; `native_transport` withholds the
  commit at the relay to reproduce it deterministically.
* **Invitation retry** backs off from 15 s to 10 min while the friend's
  KeyPackage is not yet available; an explicit request retries at once.
* **Default presets** are seeded in `Share.sq` (fresh install) and `33.sqm`
  (migration) right after `share_preset` is created; `SharingProjectionSchemaTest`
  checks both paths and that a stored choice survives. The v1 friends baseline
  does not carry over.
* **Presets**: friends visibly inherit what acquaintances receive; an inherited
  category cannot be switched off on the friends preset.
* **UI**: one destination with internal pages (overview, person, preset,
  connection), the `SettingsLayout` pattern. A person page has the one switch,
  circle, three category switches with visible exception markers, the period,
  the exact preview with a switch per record (object exceptions), what the
  friend shares back, and ending with exactly one confirmation. Choosing the
  preset's value again removes the exception instead of storing a redundant one.
* **Climb detail join**: the climb info sheet lists friends' notes and
  attempts/sends for the climb and its equivalent identities through a
  separate small view model; nothing enters the own logbook or notes.
