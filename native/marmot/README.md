# CruxCoach Marmot JNI host (v2)

A narrow, source-built MDK account-device host: MLS state (MDK/OpenMLS), an
in-process Nostr relay, a nostr-sdk relay pool and a replicator, behind four
JNI exports. It is not a CruxCoach backend. See
[the v2 target architecture](../../docs/architecture/marmot-permissions-v2-target.md)
for the threat model, sync semantics and crash-window analysis.

## Layout

| File | Role |
|---|---|
| `src/engine.rs` | MLS engine: discovery (10002/10050/30443), pairwise groups, two-leaf qualification, idempotent sends, ingest, convergence, fanout confirmation |
| `src/store.rs` | Local relay store (`NostrDatabase` over SQLCipher), deliveries, peers, inbox, send tokens — on the MDK connection and transaction rail |
| `src/transport.rs` | `CruxTransport`: in-process relay over `tokio::io::duplex`, pinned public `wss://` dialling, URL policy, peer-endpoint hook |
| `src/host.rs` | Runtime, `LocalRelay`, pool, replicator (outbound delivery, live subscriptions, NIP-77 catch-up), JSON commands |
| `src/jni_bridge.rs` | `MarmotNative.{open,call,archive,close}` and the `local-harness` test exports |
| `src/protocol.rs` | Inner kind 1230 / label `cc.share.v2`, limits, default pool |

Protocol data (groups, relays, events, delivery state, undelivered messages)
lives only here. Kotlin reaches it through `MarmotHost` and keeps no copy.

## Build and qualify

Linux host: Python 3.12+, patch, a C/C++ compiler, pkg-config and the pinned Rust
1.97.1 toolchain. Android library: additionally NDK 27.2.12479018 and the
`aarch64-linux-android` Rust target. `Cargo.lock` pins transitive sources; do
not replace it with an unlocked resolution.

```sh
python3 native/marmot/prepare.py
cargo +1.97.1 fmt --manifest-path native/marmot/Cargo.toml --check
cargo +1.97.1 test --locked --manifest-path native/marmot/Cargo.toml --features local-harness --lib --test native_transport --test relay_boundary --test storage_boundary
python3 native/marmot/build.py --output /tmp/cc-marmot-host
python3 native/marmot/build.py --android-ndk /path/to/ndk/27.2.12479018 --output /tmp/cc-marmot-android
```

`prepare.py` fetches the immutable MDK archive for `615d0c1c` (v0.9.21 plus the
official terminal-budget fix #1784), checks its SHA-256, applies
`mdk-extension.patch` without fuzz and verifies the whole patched tree against
`mdk-tree.sha256`. The patch is small: `cruxcoach_storage()` on the session and
`cruxcoach_sql()` on the storage, which runs host SQL on MDK's own connection
so an outbound event, its delivery rows and the send token commit in the same
SQLCipher transaction as the ratchet step. The v1 leaf-snapshot and opaque
journal extensions are gone; two-leaf qualification uses MDK's `members()`.

`build.py` verifies the patched tree, uses fixed source time and remapped paths,
and writes a manifest with source/patch/lock/compiler/library hashes. As before,
vendored OpenSSL embeds absolute build paths, so byte-identical reproduction
requires the same absolute paths. The Android library keeps 16 KiB load
alignment and hidden static symbols; only the four `MarmotNative` exports are
public. The arm64 release library measured 35.65 MB on 2026-09-23 (v1: about
31 MB); the increase is the relay pool, relay builder and negentropy.

After any `Cargo.lock` change run `python3 native/marmot/collect_licenses.py`
(network only for crates without a bundled license file) and review the diff of
`licenses/`; `build.py` refuses a lock whose inventory hash is stale.

## Relays and transport

* The pool always contains the in-process relay at `wss://local.cruxcoach.invalid`
  (a reserved name that can never resolve; reached only through a duplex stream).
* Public relays: 1–16 `wss://` URLs, stored natively. A never-configured account
  gets the owner's six defaults. DNS answers containing any non-public address
  are refused; TLS uses the webpki roots; frames are limited to 512 KiB.
* Events are sent only to relays that are connected at that moment. A failed
  relay is reset in the pool so no frame buffered for it can be delivered after
  a later `cancel`.
* NIP-42 challenges are answered with the account signer (background-only on
  Amber). NIP-77 reconciliation runs on start, reconnect and every 15 minutes;
  relays that refuse it get a bounded REQ window.
* `peer_endpoints` (pinned LAN/BLE endpoints per friend) is validated but only
  accepted in the loopback harness in v1.

## Synthetic endpoint and independent process tests

```sh
./gradlew :androidApp:writeMarmotEndpointClasspath --console=plain
cargo +1.97.1 build --locked --manifest-path native/marmot/Cargo.toml --example local_relay
python3 scripts/marmot_network_e2e.py
python3 scripts/marmot_continuous_e2e.py
# Interactive optional endpoint; defaults to the six public relays:
python3 scripts/marmot_endpoint.py --prepared --role SERVER
```

The `local_relay` example is a deliberately unhelpful loopback fixture: it
delivers every event twice, refuses NIP-77 and can go offline on request. The
JVM endpoint drives the production `MarmotHost` chokepoint with synthetic
identities; only the loopback restart harness stores its generated key, in a
temporary SQLCipher file whose key arrives over stdin. `local-harness` exports
are absent from the Android build and `build.py` refuses to combine them.

Public probes are manual and never CI steps: `public_e2e --synthetic-public-probe`
(optionally `--six-relay-compat`) and `relay_read_probe --read-only-probe`.

## License and trust

CruxCoach adapter code follows the repository GPL-3.0 license. MDK is MIT; its
notice is retained under `licenses/MDK-MIT.txt`. Dependency attribution is in
`licenses/`. No signing, publisher, OIDC, deployment or production credential
belongs in this directory. Changing native pins, Gradle or CI requires owner review.
