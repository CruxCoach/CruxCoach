# CruxCoach Marmot JNI host

This is a narrow source-built MDK account-device host, not a Nostr SDK or a
CruxCoach backend. See [the architecture](../../docs/architecture/marmot-permissions.md)
for its threat model, permission flow, relay evidence and limitations.

## Build and qualify

Linux host: Python 3.12+, patch, a C/C++ compiler, pkg-config and the pinned Rust
1.97.1 toolchain. Android library: additionally NDK 27.2.12479018 and the
`aarch64-linux-android` Rust target. Cargo.lock pins transitive sources; do not
replace it with an unlocked dependency resolution.

```sh
python3 native/marmot/prepare.py
cargo +1.97.1 fmt --manifest-path native/marmot/Cargo.toml --check
cargo +1.97.1 test --locked --manifest-path native/marmot/Cargo.toml --features local-harness --lib --test native_transport --test relay_boundary --test storage_boundary
python3 native/marmot/build.py --output /tmp/cc-marmot-host
python3 native/marmot/build.py --android-ndk /path/to/ndk/27.2.12479018 --output /tmp/cc-marmot-android
```

`build.py` verifies the complete patched upstream tree on every actual build,
uses fixed source time and remapped compiler paths, and emits a manifest with
source/patch/lock/compiler/feature/library hashes. `--target-dir` allows an
independent clean build for reproducibility comparison. The qualification found
that vendored OpenSSL embeds its absolute install paths and compiler flags:
changing the build directory changes the binary hash despite file-prefix remaps.
Reproducing the same binary therefore also requires the same absolute source,
Cargo, NDK and target paths. The manifest pins source/toolchain inputs; it does
not claim path-independent binary reproducibility. A full same-path arm64 rebuild was byte-identical. The final adapter-only clean
rebuild with those unchanged qualified dependencies also matched, SHA-256
`67fcf53886c417c0322950c18398547f31370f9c7a8a9a60aaf1de698ee67545` for the earlier snapshot adapter.
The friendship adapter's same-source/same-path clean adapter rebuild also matches,
SHA-256 `ca6557f7f2b3ca9083319004a0dd35e9a0b4967a85ac307133c4a3be7f8fbe67`.
Its four production JNI exports contain no synthetic-identity harness exports.
These hashes identify source-built library artifacts, not APK/device execution.
Android static dependency
symbols are hidden; the shared object's load segments use 16 KiB alignment.
Gradle packages only the arm64 library, consistent with the existing app ABI.
No binary is checked in. Changing native pins, Gradle or CI requires owner review.

The local extension adds canonical leaf/session commitment access and a bounded
opaque journal on the existing SQLCipher transaction rail. It does not change
MDK's cryptography or production timing policy. The post-v0.9.21 terminal-budget
fix is upstream code, not a local policy bypass.

## Synthetic endpoint and independent process test

```sh
./gradlew :androidApp:writeMarmotEndpointClasspath --console=plain
cargo +1.97.1 build --locked --manifest-path native/marmot/Cargo.toml --example local_relay
python3 scripts/marmot_network_e2e.py
python3 scripts/marmot_continuous_e2e.py
# Interactive optional endpoint; defaults to SERVER and the requested six relays:
python3 scripts/marmot_endpoint.py --prepared --role SERVER
```

The interactive endpoint requires explicit JSON-line commands to activate
bootstrap or invite; simply starting it publishes nothing. It prints its public
synthetic account. Commands include `bootstrap`, `pin` (peer, role), `invite`
(peer), `accept_invitation` (peer), `offer_category` (peer), `accept_category`
(peer), `offer_synthetic` (peer), `accept_snapshot` (id), `readable` (id), `revoke`
(id), `sync` and `status`. It accepts no production identity or private-key option.

The automated process test uses two loopback WebSocket relays and two separate
JVM endpoints, then kills and restarts both processes. Only this loopback restart
harness keeps generated identity/device/wrapping keys in a temporary SQLCipher
store. Its random master key stays in the parent process and travels over stdin,
never arguments or logs; cleanup removes the owned temporary directory. The
ordinary interactive adapter is ephemeral. `local-harness` JNI symbols are absent
from the Android build, and `build.py` rejects combining that feature with Android.
This adapter is an executable protocol reference, not a production server install.

The continuous process test uses three independent JVMs (owner plus two friends),
with separate USER and SERVER owner runs. `source_profile`, `source_training` and
`source_note` mutate the actual app repositories; `offer_continuous` names peers,
roles, category scope and history. `accept_continuous` confirms friendship and
selects only the acceptor’s own outgoing data;
`deny_category`/`end_continuous` narrow/end it. `automatic` opts into a local
headless polling runner using the same source/policy/exchange services. It does
not run an Android Worker or install a background server. `continuous_status` and
`source_hashes` report hashes/counts instead of private text. `rotate_transport`
exercises the real MDK epoch transition. `--unreachable-cruxcoach` is accepted
only with loopback fixtures and maps the canonical CruxCoach relay to a closed
local port; production builds contain no such facility.

Continuous kind 1223 adds source-preserving ACK compaction and an exact-fenced
outbox retirement API for completed/superseded application transfers. It cannot
delete core MDK fanouts or another kind's obligations. Source provenance survives
eight days in bounded buckets, while current payloads live in the independent app
replica. Read/ingest byte and row limits are unchanged. Native scans use a durable
four-route round-robin budget and continuous publication uses oldest-due bounded
batches. Ending either side deletes both received directions. Root-signed
generation evidence and real peer cleanup acknowledgements survive independently
of plaintext. See the architecture for friendship authority, metadata retention
and recovery limits.

Public probes are manual, bounded and **not CI steps**. `public_e2e` requires
`--synthetic-public-probe`; the optional `--six-relay-compat` uses all requested
relays. The default encrypted roundtrip omits the CruxCoach relay entirely. Use
only with authorization for the resulting small synthetic protocol publication.
`relay_read_probe` performs read-only fresh-account compatibility queries.

## License and trust

CruxCoach adapter code follows the repository GPL-3.0 license. MDK is MIT; its
notice is retained under `licenses/MDK-MIT.txt`. Dependency license attribution
is recorded under `licenses/`; source pins and full dependency resolution are in
`Cargo.lock`. No signing, publisher, OIDC, deployment or production credential
configuration belongs in this directory.
