# Quartz, Amethyst and the Marmot host

Dependency decision, 2026-09-11: retain Quartz 1.05.1 for account signing and the
pinned MDK/OpenMLS native host for Marmot. Consolidate the shared signer boundary;
do not migrate cryptographic engines or encrypted state without proven parity.

## What is actually integrated

Quartz supplies NIP-01 events, account signing, BIP-340 verification and NIP-44
through the app's signer, including Amber. Its provider-only `backgroundQuery`
serves unattended work without an Intent fallback. `PrivateApplicationSigner`
is the common account/authority-checked route for friendship certificates, leaf
proofs and the native account callback. A returned event must match every requested
field; native/ledger boundaries still independently verify its hash/signature.
The app never exports account private keys to JNI.

Marmot uses the Rust MDK CGKA engine/session, SQLCipher storage, Nostr peeler and
transport core under a narrow JNI host. MDK is pinned to
`615d0c1cf48dbb231ecce0e3c8cf5cc3677eda57`, OpenMLS to
`59e7d3b27a7e95237879dd5478de1fd90eff7ada`; the protocol pin remains
`4a2bc65f8db5866cec3b2a127dedb37818eaf207`. The 108-line MDK extension exposes
canonical leaf credentials and a bounded opaque journal on the same transaction;
it changes no cryptographic algorithm. The host owns exact session/source fences,
source-aware inbox disposal and durable, cancellable handoffs. Those responsibilities
remain separate from friendship's two independent owner-selected projections.

## Amethyst supports Marmot, but which implementation?

[Amethyst v1.14.0](https://github.com/vitorpamplona/amethyst/releases/tag/v1.14.0)
(source `e1ba25df556e7c84b6ce5a08ecb9671323a4a962`, 2026-08-22) ships Marmot
using Quartz's own Kotlin MLS implementation, not an MDK/OpenMLS wrapper. The
published Quartz 1.14.0 AAR contains 324 Marmot entries; our 1.05.1 AAR contains
none. Binary API inspection and an isolated, network-free TLS roundtrip confirmed
the released group-data extension `0xf2ee`. The released artifact has no
`CurrentProfileGroupFactory` or account-identity-proof-v2 component.

[Main at c495c89](https://github.com/vitorpamplona/amethyst/tree/c495c89d0f40449b4c5576ae5ecb8a69963de6f9)
adds current-profile proofs, convergence, publication and retention machinery.
Its [resync assessment](https://github.com/vitorpamplona/amethyst/blob/c495c89d0f40449b4c5576ae5ecb8a69963de6f9/quartz/plans/2026-09-08-marmot-spec-resync.md)
explicitly distinguishes landed stages from a current-MDK live interop run that
had not yet been performed. Older tests against the now-archived `whitenoise-rs`
do not establish current-profile interoperability. These main changes are not
features of the released 1.14.0 artifact. We have not independently qualified
Amethyst's complete crypto/convergence implementation.

## Official MDK Android bindings are a real alternative

[MarmotKit 0.9.21](https://github.com/marmot-protocol/mdk/releases/tag/marmotkit-v0.9.21)
ships matching generated Kotlin and four Android ABIs. The verified bundle hash is
`fc02b9f24b5b079a9dd827dd82ebd2fa7cd254bd81cbeaf5c6657d7750fd6100`;
source/builder are `fdd398a80f1626f1713787cebe416f7890b5b204`. It provides
`ExternalAccountSignerFfi`, `sendCustomEvent`, source-bearing message records,
retention/deletion and recovery. It does not require account-key export.

These APIs wrap the full app runtime. A separate `groupMlsState` debug read and
`sendCustomEvent` call cannot supply our single atomic leaf/source-fenced handoff
and per-purpose disposal transaction. Local chat deletion, whole-group removal,
relay acceptance and the application's authenticated cleanup ACK have different
meanings. A migration would need a qualified host-facing extension and encrypted
state conversion, not simply replace a text transport interface.

[White Noise Android at c6405ca](https://github.com/marmot-protocol/whitenoise-android/tree/c6405ca33630469ec91f3c44d6c24fc01a66b69f)
uses that exact bundle and its own Amber adapter. Its app requires API30; the
SDK bundle supports API26. Its provider-to-Intent fallback is unsuitable for our
unattended path without adaptation. This is inspected main source, not a claim
of an Android release or a dependency CruxCoach includes.

## Benefits, costs and decision

| Option | Useful benefit | Cost or missing evidence |
|---|---|---|
| Retain current adapter | Qualified real friendship/restart/retraction path, exact atomic native journal | Handwritten host and two Nostr/crypto/SQLite stacks to maintain |
| Targeted reuse | One existing Quartz account-signing route replaces three duplicated routes | Still requires native authorization, transport and persistence |
| Full Quartz move | Could remove Rust/NDK host; published bytecode is Java17 rather than our Quartz's Java21, improving host testability | Different released protocol/crypto engine, main-only fixes, new Kotlin/transitive dependencies and unproven MLS-state migration |
| Full UniFFI move | Official generated bindings and runtime, same underlying OpenMLS | More runtime dependencies; missing exported atomic application contract; own extensions/migration still needed |

Measured artifacts: our arm64 SO is 30,990,680 bytes, the official full-runtime SO
49,545,920 bytes, and generated Kotlin 1,244,350 bytes. Quartz AAR grows from
3,337,149 bytes (1.05.1) to 10,692,318 bytes (1.14.0). Different profiles/features
and compression prevent translating these numbers directly into an APK saving.
No comparative APK or runtime benchmark was performed.

Android-resolved normal/build Cargo closures contained 270 current-host and 456
UniFFI-workspace package identities, including build dependencies and workspace
feature unification. UniFFI adds Nostr SDK/pool/gossip, QUIC, keyring, markdown,
forensics and generated binding machinery. Optional analytics/OTLP code is not
proof of automatic telemetry. Quartz 1.14.0 adds SQLite bundled/Negentropy and
raises Kotlin, OkHttp and secp256k1 versions; keeping MDK alongside it would retain
SQLCipher/OpenSSL/Rust TLS. App SQLCipher also serves canonical data and cannot
be removed by replacing the messaging SDK alone.

No dependency was added or upgraded. Existing MIT upstream obligations and the
hash-bound native license inventory continue to apply to the GPL-3.0-only host.
Analysis artifacts and downloaded comparison libraries are not packaged.

The strongest counterargument to retention is substantial native build/size and
security-sensitive host maintenance. A released current-profile alternative that
passes our unchanged A/B/C and SERVER tests, exact fencing/cleanup/recovery
contract, external background signer tests and a reversible migration would change
the decision. Measure APK/build/memory/network costs at that point. Past work,
newer version numbers or fewer handwritten lines alone are insufficient.

The cleanup changes neither schema nor wire protocol. Existing friendships,
snapshots, bounded retention, six configurable relays and backend independence
retain their meaning. The push-triggered feature CI now runs native boundary and
independent-process acceptance before trusted-main publication eligibility.
Complete run evidence is maintained in the owner's `dependency-publication/`
artifacts; actual Android/Amber/Keystore/Doze device qualification remains separate.
