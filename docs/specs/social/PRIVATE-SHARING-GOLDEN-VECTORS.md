---
status: design-review
owner: FEAT-062
protocol: com.cruxcoach.private-sharing/v1
marmot_pin: 4ad4ae21479c3f3fa9950c6fc4556a76941a62e1
mdk_pin: 101d79946cff6c82d2b849d3b70902a7af7bac08
created: 2026-08-11
revised: 2026-08-13
---

# Private Sharing Golden Vectors

> **SUPERSEDED (2026-08-16).** This annex describes the withdrawn bilateral
> one-group-per-relationship design. The shipped model — three visibility
> circles, five categories, group baselines plus person and object exceptions,
> device-bound consent and a recovery code — is specified in
> [FEAT-062](../0.2.3/FEAT-062-personal-information-sharing.md), which wins
> wherever the two disagree. Kept as evidence of what was investigated.

Byte-exact fixtures for the
[wire contract](PRIVATE-SHARING-WIRE-V1.md) of
[FEAT-062](../0.2.3/FEAT-062-personal-information-sharing.md). Hex is lowercase;
concatenation adds no bytes beyond those shown; every JCS block is the exact
UTF-8 sequence with no trailing newline.

> **No-Go.** This annex is `design-review`. It does not authorise product code
> or release. The isolated upstream-first spike must pass all eight FEAT-062 §5
> gates, including the full pinned encoder vector, ACK/replay retention tests,
> exact group profile and signer cases, and an independent review must confirm
> all evidence.

## 1. Conformance boundary

These vectors normalise CruxCoach `content`, deterministic identifiers,
Marmot-app-event plaintext bytes and application reducer outcomes. They do not
invent an MLS ciphertext, Welcome, KeyPackage, epoch secret, outer transport
event or routing id. Those remain Marmot/MDK-owned.

For every event below:

```json
[["L","com.cruxcoach.private-sharing"],["l","v1","com.cruxcoach.private-sharing"]]
```

`kind = 1220`, `created_at = envelope.issued_at`, and the inner id is SHA-256 of
the whitespace-free UTF-8 NIP-01 array
`[0,pubkey,created_at,1220,tags,content]`. The `l` tag keeps its NIP-32
namespace as the third element; a two-element `["l","v1"]` is invalid and any
companion document that still asks for one is superseded by the wire contract.

### 1.1 Checked-in reproduction

Every value in this document is reproduced by the checked-in reference harness:

```text
python3 docs/specs/social/scripts/verify_private_sharing.py
```

The harness re-derives each value from the closed inputs in
[`fixtures/positive-vectors.json`](fixtures/positive-vectors.json), runs every
case in [`fixtures/negative-corpus.json`](fixtures/negative-corpus.json) and
every scenario in
[`fixtures/reducer-scenarios.json`](fixtures/reducer-scenarios.json), and
compares the results against this document in both directions: a value printed
here that no fixture derives is an error, and a fixture derivation this document
does not print is also an error.

The harness is a **spec oracle** — a second, independent Python statement of the
wire contract. It executes no Kotlin, no Rust, no MDK, no Marmot and nothing on
a device, so a green run is evidence about this specification only. It leaves
all eight adapter gates of FEAT-062 §5 open and does not lift the No-Go.

It is the **S9** specification stage. **R6** — the executed real-adapter spike —
is a different thing entirely. R6 has now run and returned NO-GO; it produced
real host-scope runtime evidence for several capabilities and closed no gate, so
the eight R6 gates recorded in
[wire §11.1](PRIVATE-SHARING-WIRE-V1.md#111-open-mdk-capability-gates-r6) all
remain open. The harness asserts that on every run, per gate, together with what
R6 actually proved and on which surface. No vector, fixture or green run below
may be presented as R6 evidence.

## 2. Accounts and generation derivation

| Name | Exact value |
|---|---|
| account A, sorts lower, so `endpoint_low` and the only permitted creator | `79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798` |
| account B, sorts higher, so `endpoint_high` and the only permitted acceptor | `c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5` |
| group 1 id bytes | `3f1c9d7e5b204a6c8e0f1d2a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e` |
| group 2 id bytes | `0a2b4c6d8e0f10213243546576879809a1b2c3d4e5f60718293a4b5c6d7e8f90` |

Both group ids are 32 bytes, so their length prefix is `0020`.

Group 1:

```text
preimage_hex=63632d70732d67656e65726174696f6e2d76310000203f1c9d7e5b204a6c8e0f1d2a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5
sha256=4026ec6e92d14da99520555f4c674a704e3e806d12dc8cbc97e472060839f269
generation_id=4026ec6e-92d1-8da9-9520-555f4c674a70
```

Group 2:

```text
preimage_hex=63632d70732d67656e65726174696f6e2d76310000200a2b4c6d8e0f10213243546576879809a1b2c3d4e5f60718293a4b5c6d7e8f9079be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5
sha256=fdda72600a816f789a32872a8e555719d1f3c4de0fdbe3a59c19affeecd35af1
generation_id=fdda7260-0a81-8f78-9a32-872a8e555719
```

The UUID values are the first 16 digest bytes after setting version 8 and the
RFC 4122 variant. The private group bytes occur in neither envelope generation.

The local-only duplicate-detection key for the same pair:

```text
preimage_hex=63632d70732d656e64706f696e74732d76310079be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5
sha256=0343ca43030fd27cb945cf4e105b2c999e636815b41f84ccba0fc0e96f137a25
```

### 2.1 Bootstrap attempt and the `0x8001` marker

The fixture attempt identifier is a lowercase canonical UUIDv4:

```text
attempt_id=9f6c2d81-4b7e-4a3c-9d2f-16b83a7c5e40
version_nibble=4
variant_nibble=9
```

The `0x8001` group profile carries the fixed name and the closed JCS marker.
The name is exactly 25 UTF-8 bytes:

```text
CruxCoach private sharing
```

The description is exactly this JCS object with no trailing byte:

```json
{"attempt_id":"9f6c2d81-4b7e-4a3c-9d2f-16b83a7c5e40","endpoint_high":"c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5","endpoint_low":"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","protocol":"com.cruxcoach.private-sharing/bootstrap/v1"}
```

```text
description_size=274
description_sha256=12c58e6819fd87b7ab0f5d9dae1c0202638e43e76511ed66b8ba9abc683b3db6
```

MDK encodes the component state as a QUIC varint length followed by the bytes,
once per field. With a 25-byte name and a 274-byte description the varints are
`0x19` and `0x4112`, so the complete `0x8001` component data is:

```text
component_data_hex=1943727578436f61636820707269766174652073686172696e6741127b22617474656d70745f6964223a2239663663326438312d346237652d346133632d396432662d313662383361376335653430222c22656e64706f696e745f68696768223a2263363034376639343431656437643664333034353430366539356330376364383563373738653462386365663363613761626163303962393563373039656535222c22656e64706f696e745f6c6f77223a2237396265363637656639646362626163353561303632393563653837306230373032396266636462326463653238643935396632383135623136663831373938222c2270726f746f636f6c223a22636f6d2e63727578636f6163682e707269766174652d73686172696e672f626f6f7473747261702f7631227d
component_data_size=302
component_data_sha256=0f81dfbcd0f7efea4b7df7d8a9fe879b61ef0fe46fa76b8e9d7bb4637c0a006c
```

The marker never travels in an application envelope; it is authenticated
GroupContext state delivered by the Welcome. A conforming reader recomputes both
endpoints from its own sorted accounts and requires byte equality.

### 2.2 Exact minimal component profile, by phase

Component **ids and placement are invariant**; two component **values** are not.
At the MDK pin the creator is the implicit sole admin of the group it creates,
promotion is a separate commit, and a disband commit reduces both the leaf set
and the admin policy to exactly the committer.

```json
{"group_context_required":["0x8001","0x8003","0x8004","0x8009","0x800c"],"group_context_state":["0x8001","0x8003","0x8004","0x800c"],"leaf_only":["0x8009"],"name":"exact_minimal_profile"}
```

| Phase | Leaves | `0x8003` admin policy | `0x800c` | Outcome |
|---|---|---|---|---|
| `PROVISIONING_SELF_ONLY` | `[endpoint_low]` | `[endpoint_low]` | `0x00` | `PROVISIONING` |
| `PROVISIONING_INVITED` | `[endpoint_low,endpoint_high]` | `[endpoint_low]` | `0x00` | `PROVISIONING` |
| `ACTIVE` | `[endpoint_low,endpoint_high]` | `[endpoint_low,endpoint_high]` | `0x00` | `ACTIVE` |
| `DISBANDED` | `[committer]` | `[committer]` | `0x01` | terminal |

A missing high admin **before** the canonical promotion is the expected
provisioning state, not a fault. A high admin present before the acceptance was
applied, and a missing high admin after promotion, both terminalise. Every other
component id is forbidden. A message retention component terminalises even when
it carries the value zero:

```json
{"group_context_state":["0x8001","0x8003","0x8004","0x8005","0x800c"],"name":"retention_component_value_zero","retention_seconds":0}
```

## 3. Deterministic slot and row ids

### 3.1 Offer slots

```text
name=offer_A
preimage_hex=63632d70732d6f666665722d736c6f742d7631004026ec6e92d18da99520555f4c674a7079be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798
sha256=f84a6a07c1a50abe6c514526d1f132186b6cd2b865e281b1f7c852b9a5374a8f
object_id=f84a6a07-c1a5-8abe-ac51-4526d1f13218
```

```text
name=offer_B
preimage_hex=63632d70732d6f666665722d736c6f742d7631004026ec6e92d18da99520555f4c674a70c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5
sha256=9d5218dd000fe57a319a6354657542834a8de0ade496ede867d12f50aa987d04
object_id=9d5218dd-000f-857a-b19a-635465754283
```

### 3.2 Grant slots

```text
name=grant_A_to_B_LOGBOOK_SUMMARY
preimage_hex=63632d70732d6772616e742d736c6f742d7631004026ec6e92d18da99520555f4c674a7079be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f8179801
sha256=c937169b5edcc3a4253c14c6eef4cf189ea8ebf2c05eb3ca6f297b62821aa5e1
object_id=c937169b-5edc-83a4-a53c-14c6eef4cf18
```

```text
name=grant_A_to_B_LOGBOOK_DETAIL
preimage_hex=63632d70732d6772616e742d736c6f742d7631004026ec6e92d18da99520555f4c674a7079be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f8179803
sha256=5c05ca0c3d1058626dd4f3cb6b97d23741b7bdf1c7a0afe484b03589ed8cff45
object_id=5c05ca0c-3d10-8862-add4-f3cb6b97d237
```

The recipient-specific plan UUID is
`16147ec9-8d19-8886-a3b8-8b3011f59284`:

```text
name=grant_A_to_B_PLAN_DETAIL
preimage_hex=63632d70732d6772616e742d736c6f742d7631004026ec6e92d18da99520555f4c674a7079be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980416147ec98d198886a3b88b3011f59284
sha256=946d69f83c45ce6f31d8836217d357620459ca47afcf6dffb3d993d7515a4c82
object_id=946d69f8-3c45-8e6f-b1d8-836217d35762
```

### 3.3 Grant identifiers

Every `grant_id` is a freshly minted UUIDv7 whose 48 timestamp bits are exactly
`issued_at × 1000` milliseconds. A receiver checks version, variant and
generation-wide non-reuse; it never treats the timestamp bits as authority time
and never correlates them with `issued_at` as an authorisation input.

| Grant | `issued_at` | `grant_id` | timestamp bits |
|---|---:|---|---:|
| `LOGBOOK_DETAIL` | 1786000180 | `019fd5e8-0320-700b-8000-00000000000b` | 1786000180000 |
| `LOGBOOK_SUMMARY`, first | 1786000300 | `019fd5e9-d7e0-700a-8000-00000000000a` | 1786000300000 |
| `LOGBOOK_SUMMARY`, narrowed | 1786000400 | `019fd5eb-5e80-700c-8000-00000000000c` | 1786000400000 |
| `PLAN_DETAIL` | 1786000900 | `019fd5f2-ffa0-700d-8000-00000000000d` | 1786000900000 |

### 3.4 Projection slots

The projection slot preimage binds the **publisher** as well as the generation
and the grant. Without that binding a peer could reuse the other publisher's
`grant_id` and claim the same slot.

```text
name=projection_grant_019fd5e9-d7e0-700a-8000-00000000000a
preimage_hex=63632d70732d70726f6a656374696f6e2d736c6f742d7631004026ec6e92d18da99520555f4c674a7079be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798019fd5e9d7e0700a800000000000000a
sha256=ccbf0acc55a78f3a32b65142176bf06d9b559c120de716d696bd74a03e28b250
object_id=ccbf0acc-55a7-8f3a-b2b6-5142176bf06d
```

```text
name=projection_grant_019fd5e8-0320-700b-8000-00000000000b
preimage_hex=63632d70732d70726f6a656374696f6e2d736c6f742d7631004026ec6e92d18da99520555f4c674a7079be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798019fd5e80320700b800000000000000b
sha256=ff8f4248995538b35b85f9fd9108e024abe0ba78cf47395074466995312f419c
object_id=ff8f4248-9955-88b3-9b85-f9fd9108e024
```

```text
name=projection_grant_019fd5f2-ffa0-700d-8000-00000000000d
preimage_hex=63632d70732d70726f6a656374696f6e2d736c6f742d7631004026ec6e92d18da99520555f4c674a7079be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798019fd5f2ffa0700d800000000000000d
sha256=ecf57e21dc71985fd4d423e3d2ac4d7ad19cc0b6f936251393d5e64220ef7631
object_id=ecf57e21-dc71-885f-94d4-23e3d2ac4d7a
```

The cross-publisher control vector: the **same** `grant_id`
`019fd5e8-0320-700b-8000-00000000000b` published by B derives a **different**
slot, so the two can never share one linear chain:

```text
name=projection_cross_publisher_019fd5e8-0320-700b-8000-00000000000b
preimage_hex=63632d70732d70726f6a656374696f6e2d736c6f742d7631004026ec6e92d18da99520555f4c674a70c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5019fd5e80320700b800000000000000b
sha256=dfb7a3e366144f3efd8fe6b4e150085783a0aaca2f2d94f60dee438bae154d8a
object_id=dfb7a3e3-6614-8f3e-bd8f-e6b4e1500857
```

Deriving a distinct slot is necessary but not sufficient: a `grant_id` is unique
generation-wide, so B publishing a grant that reuses A's `grant_id` is the
terminal `GRANT_ID_COLLISION` fault of wire §8.4, not a second usable stream.

### 3.5 Board, hold and problem

```json
{"angle_mdeg":40000,"ecosystem":"KILTER","layout_id":1,"product_id":1,"size_id":7,"type":"cc.board","v":1}
```

```text
board_config_id=4dc81ed591f36e3b69f85482356cb267d86881d687481fdf8abdd764a3bcb6c2
```

The one Kilter frame contains sorted canonical holds
`(1164,12),(1233,13),(1392,14)`:

```text
hold_preimage_hex=63632d686f6c64732d76310001000100030000048c000c000004d1000d00000570000e
hold_fingerprint=53db6294f8a7569860457bd81893b03def8ba623bedd8aefef90536a7e0847c3
```

```json
{"board":{"angle_mdeg":40000,"ecosystem":"KILTER","layout_id":1,"product_id":1,"size_id":7,"type":"cc.board","v":1},"hold_fingerprint":"53db6294f8a7569860457bd81893b03def8ba623bedd8aefef90536a7e0847c3","is_mirror":false,"provider_problem_id":"123","type":"cc.problem","v":1}
```

```text
problem_hash=1351a2b3db68c914b4812b281cb069531c8e463be73b20c74bb719317a6ddf02
```

### 3.6 Recipient-specific row ids

Local session UUID 1 is `018bcfe5-6800-7000-8000-0000000000aa`:

```text
preimage_hex=63632d70732d73657373696f6e2d7631004026ec6e92d18da99520555f4c674a70c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5018bcfe56800700080000000000000aa4dc81ed591f36e3b69f85482356cb267d86881d687481fdf8abdd764a3bcb6c2
sha256=d9b59d245e26a369c1533cc2dce339763a81e11d061121354bbc242299388bb9
session_uuid=d9b59d24-5e26-8369-8153-3cc2dce33976
```

Local session UUID 2 is `018bcfe5-6800-7000-8000-0000000000ab`; it carries the
900-second boundary vector of §6.5:

```text
preimage_hex=63632d70732d73657373696f6e2d7631004026ec6e92d18da99520555f4c674a70c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5018bcfe56800700080000000000000ab4dc81ed591f36e3b69f85482356cb267d86881d687481fdf8abdd764a3bcb6c2
sha256=33b26755ca712401ca756b1379d04c35cb9ddbd3cbff2b806b8f01ba4744bbf4
session_uuid=33b26755-ca71-8401-8a75-6b1379d04c35
```

Local plan UUID is `018bcfe5-6800-7000-8000-0000000000bb`:

```text
preimage_hex=63632d70732d706c616e2d7631004026ec6e92d18da99520555f4c674a70c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5018bcfe56800700080000000000000bb
sha256=16147ec98d19d88623b88b3011f592846daefa6e0d16bc381232bce865a6057f
plan_uuid=16147ec9-8d19-8886-a3b8-8b3011f59284
```

## 4. Acceptance, offer events and pinned full encoding

### 4.1 Provisioning acceptance

`endpoint_high` accepts the Welcome locally and sends exactly one
`cc.generation.accept.v1`. Its slot is deterministic:

```text
name=acceptance_slot
preimage_hex=63632d70732d616363657074616e63652d736c6f742d7631004026ec6e92d18da99520555f4c674a70c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5
sha256=7403ba8b3fffbdf157beb3fd83d21a7dd3fbe20413926f48667f6f916bcb0f29
object_id=7403ba8b-3fff-8df1-97be-b3fd83d21a7d
```

```json
{"author":"c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5","body":{"acceptance_revision":1,"type":"cc.generation.accept.v1","v":1},"endpoints":["79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"],"generation_id":"4026ec6e-92d1-8da9-9520-555f4c674a70","issued_at":1786000050,"object_id":"7403ba8b-3fff-8df1-97be-b3fd83d21a7d","seq":1,"type":"cc.envelope.v1","v":1}
```

```text
content_size=464
content_sha256=bec39b1d75e190e48365a6144d1b91b70d74e15c921c3036639898ac812caa37
inner_event_id=782511be311ffcfe9d94d1a7b1b5d247ba89cddd4b9ef253adba61d6539f5688
```

Exact full `MarmotAppEvent::encode()` output at MDK pin `101d7994`:

```marmot-event
{"id":"782511be311ffcfe9d94d1a7b1b5d247ba89cddd4b9ef253adba61d6539f5688","pubkey":"c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5","created_at":1786000050,"kind":1220,"tags":[["L","com.cruxcoach.private-sharing"],["l","v1","com.cruxcoach.private-sharing"]],"content":"{\"author\":\"c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5\",\"body\":{\"acceptance_revision\":1,\"type\":\"cc.generation.accept.v1\",\"v\":1},\"endpoints\":[\"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798\",\"c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5\"],\"generation_id\":\"4026ec6e-92d1-8da9-9520-555f4c674a70\",\"issued_at\":1786000050,\"object_id\":\"7403ba8b-3fff-8df1-97be-b3fd83d21a7d\",\"seq\":1,\"type\":\"cc.envelope.v1\",\"v\":1}"}
```

```text
full_event_size=790
full_event_sha256=7a4fb55ae54b1046f6070845462023fa2f19147b69eebbf8d4b82813b8fc29c7
```

This event authorises nothing. It only evidences that the user of
`endpoint_high` explicitly accepted this exact group. `endpoint_low` may promote
`endpoint_high` to admin only after it is canonically applied, and the offer,
grant and projection events below are send-blocked and apply-blocked until the
generation is `ACTIVE`.

The same content authored by `endpoint_low` is rejected: only `endpoint_high`
may occupy the acceptance slot, and the slot admits `seq = 1` only.

A **second** acceptance at that same `seq = 1`, structurally and semantically
valid in its own right, differing only in `acceptance_revision` and `issued_at`:

```json
{"author":"c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5","body":{"acceptance_revision":2,"type":"cc.generation.accept.v1","v":1},"endpoints":["79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"],"generation_id":"4026ec6e-92d1-8da9-9520-555f4c674a70","issued_at":1786000060,"object_id":"7403ba8b-3fff-8df1-97be-b3fd83d21a7d","seq":1,"type":"cc.envelope.v1","v":1}
```

```text
content_size=464
content_sha256=1e0830453f2dc1b404db177f21e7a8056768ee5e0749f2429b1db174e46508cd
inner_event_id=db7d81263dba68c587be0b972eff078bd973a47336db817d5865157aebe85d65
```

Paired with the acceptance above this is the equal-sequence conflict of wire §8.2
on the acceptance slot: the whole generation is terminal with
`EQUAL_SEQUENCE_CONFLICT` under either arrival order, and neither the higher
`acceptance_revision` nor the later `issued_at` makes it a winner. Sequences
7.0e and 7.0e-reverse drive both orders and the corpus case
`accept_equal_sequence_conflict` carries the bytes.

### 4.2 A1: A offers `ACQUAINTANCE`

```json
{"author":"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","body":{"offer":"ACQUAINTANCE","type":"cc.relationship.offer.v1","v":1},"endpoints":["79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"],"generation_id":"4026ec6e-92d1-8da9-9520-555f4c674a70","issued_at":1786000100,"object_id":"f84a6a07-c1a5-8abe-ac51-4526d1f13218","seq":1,"type":"cc.envelope.v1","v":1}
```

```text
content_size=464
content_sha256=1d32b8590404f21262c416cd43ec1db680171f6a5c5181a2406c7c0faab1f77b
inner_event_id=aedf77229c731ee55dc59d9bacd49259a788ad71bc81d29f3ba3558d23b13240
```

Exact full `MarmotAppEvent::encode()` output at MDK pin `101d7994`, in struct
order rather than JCS key order:

```marmot-event
{"id":"aedf77229c731ee55dc59d9bacd49259a788ad71bc81d29f3ba3558d23b13240","pubkey":"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","created_at":1786000100,"kind":1220,"tags":[["L","com.cruxcoach.private-sharing"],["l","v1","com.cruxcoach.private-sharing"]],"content":"{\"author\":\"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798\",\"body\":{\"offer\":\"ACQUAINTANCE\",\"type\":\"cc.relationship.offer.v1\",\"v\":1},\"endpoints\":[\"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798\",\"c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5\"],\"generation_id\":\"4026ec6e-92d1-8da9-9520-555f4c674a70\",\"issued_at\":1786000100,\"object_id\":\"f84a6a07-c1a5-8abe-ac51-4526d1f13218\",\"seq\":1,\"type\":\"cc.envelope.v1\",\"v\":1}"}
```

```text
full_event_size=792
full_event_sha256=aa93bfdc0a2ee34e684e6b190fb9ab07e027e8046df288f6dce39825abc4809b
```

The field order is exactly `id,pubkey,created_at,kind,tags,content`; there is no
`sig`.

### 4.3 B1: B offers `FRIEND`

```json
{"author":"c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5","body":{"offer":"FRIEND","type":"cc.relationship.offer.v1","v":1},"endpoints":["79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"],"generation_id":"4026ec6e-92d1-8da9-9520-555f4c674a70","issued_at":1786000200,"object_id":"9d5218dd-000f-857a-b19a-635465754283","seq":1,"type":"cc.envelope.v1","v":1}
```

```text
content_size=458
content_sha256=af8e81541dfdaa713980aa707eaa9df3e217ad010ce9cc57a4f53a0eb14113b1
inner_event_id=ae06df73c4717b0e84077429514ca4044bdf04c35a4c322595ca44613d5b3823
```

### 4.4 A2: A raises to `FRIEND`

```json
{"author":"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","body":{"offer":"FRIEND","type":"cc.relationship.offer.v1","v":1},"endpoints":["79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"],"generation_id":"4026ec6e-92d1-8da9-9520-555f4c674a70","issued_at":1786000150,"object_id":"f84a6a07-c1a5-8abe-ac51-4526d1f13218","previous_event_id":"aedf77229c731ee55dc59d9bacd49259a788ad71bc81d29f3ba3558d23b13240","seq":2,"type":"cc.envelope.v1","v":1}
```

```text
content_size=545
content_sha256=7f8cbbc79d3c2f88c84d51e00f99fadf67264c59be704524ac3fff13c0a41702
inner_event_id=886def636941f4d7eca4dbd618919a5c7f42b8b4a13d2ea9363fa30d24f21a44
```

A2's `issued_at` precedes B1's. With both gapless heads, level is `FRIEND` and
display transition time is `max(1786000150,1786000200) = 1786000200`.

### 4.5 Equal-sequence conflict event

This event is independently valid at A's sequence 2 but differs from A2:

```json
{"author":"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","body":{"offer":"ACQUAINTANCE","type":"cc.relationship.offer.v1","v":1},"endpoints":["79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"],"generation_id":"4026ec6e-92d1-8da9-9520-555f4c674a70","issued_at":1786000160,"object_id":"f84a6a07-c1a5-8abe-ac51-4526d1f13218","previous_event_id":"aedf77229c731ee55dc59d9bacd49259a788ad71bc81d29f3ba3558d23b13240","seq":2,"type":"cc.envelope.v1","v":1}
```

```text
content_size=551
content_sha256=75db2459d7f2de4f398e9152dbc4d5fe638e831a11f388ec52ee3a4ea9e19493
inner_event_id=68d1ace634fba5e9412332cb61e45766c1891bf6f481aff34067b4282770c939
```

Receiving both sequence-2 events terminalises the whole generation under either
arrival order. No event id wins.

### 4.6 A3: A downgrades to `ACQUAINTANCE`

```json
{"author":"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","body":{"offer":"ACQUAINTANCE","type":"cc.relationship.offer.v1","v":1},"endpoints":["79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"],"generation_id":"4026ec6e-92d1-8da9-9520-555f4c674a70","issued_at":1786001100,"object_id":"f84a6a07-c1a5-8abe-ac51-4526d1f13218","previous_event_id":"886def636941f4d7eca4dbd618919a5c7f42b8b4a13d2ea9363fa30d24f21a44","seq":3,"type":"cc.envelope.v1","v":1}
```

```text
content_size=551
content_sha256=14e37065816c77152f7f9ab9c6f1f022d7578be21e696d92c6ca7c7e12a64806
inner_event_id=12754e995446966ee29aa268efcc319031d54d94310f9a3a8839d568cb715b68
```

With B1 still at `FRIEND`, the effective level becomes `ACQUAINTANCE`.
Every detail grant is permanently retired and its data is hidden immediately.

### 4.7 A4: A raises to `FRIEND` again

```json
{"author":"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","body":{"offer":"FRIEND","type":"cc.relationship.offer.v1","v":1},"endpoints":["79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"],"generation_id":"4026ec6e-92d1-8da9-9520-555f4c674a70","issued_at":1786001200,"object_id":"f84a6a07-c1a5-8abe-ac51-4526d1f13218","previous_event_id":"12754e995446966ee29aa268efcc319031d54d94310f9a3a8839d568cb715b68","seq":4,"type":"cc.envelope.v1","v":1}
```

```text
content_size=545
content_sha256=91eb20006dfea322b6f5e31548b9837a2bb616a869019fa6c69172d33efc8955
inner_event_id=8386f0532fd20931fb5a109e07a990bd2a74f59c22a044299941e7356295475b
```

The effective level is `FRIEND` again, but A3's retirement remains sticky:
neither an old detail grant nor its old projections reactivate.

### 4.8 A5: A offers `NONE`

```json
{"author":"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","body":{"offer":"NONE","type":"cc.relationship.offer.v1","v":1},"endpoints":["79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"],"generation_id":"4026ec6e-92d1-8da9-9520-555f4c674a70","issued_at":1786001300,"object_id":"f84a6a07-c1a5-8abe-ac51-4526d1f13218","previous_event_id":"8386f0532fd20931fb5a109e07a990bd2a74f59c22a044299941e7356295475b","seq":5,"type":"cc.envelope.v1","v":1}
```

```text
content_size=543
content_sha256=fdcc8d399057a2b5eb1be0e2045e7885281c5a1ffe1437ed2309ab10d77653f0
inner_event_id=b74db1a09317df4a52306a2291c26828cc346b9bfe3166ef7db6f327defedf8d
```

Applying A5 terminalises the generation with the closed reason
`OFFER_NONE_RECEIVED`; no later offer can reopen it. A merely **computed**
`NOT_ESTABLISHED` level never has that effect — see §7.3 and §7.4, where B has
no head at all and the generation stays open.

## 5. Grant events

### 5.1 Initial summary grant set

```json
{"author":"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","body":{"expires_at":1793776300,"fields":["all_time_send_count","hardest_grade_milli"],"grant_id":"019fd5e9-d7e0-700a-8000-00000000000a","issued_at":1786000300,"offer_heads":["886def636941f4d7eca4dbd618919a5c7f42b8b4a13d2ea9363fa30d24f21a44","ae06df73c4717b0e84077429514ca4044bdf04c35a4c322595ca44613d5b3823"],"publisher":"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","recipient":"c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5","scope":"LOGBOOK_SUMMARY","type":"cc.grant.set.v1","v":1},"endpoints":["79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"],"generation_id":"4026ec6e-92d1-8da9-9520-555f4c674a70","issued_at":1786000300,"object_id":"c937169b-5edc-83a4-a53c-14c6eef4cf18","seq":1,"type":"cc.envelope.v1","v":1}
```

```text
content_size=918
content_sha256=d0b453777b9d51c0715bc82fa8c28cecf55cbab6a3a4c8d7705c5e8d2a1b6c07
inner_event_id=678cf7729571aadc3ba2a51d99af7a2993b2035fe7dce79cd62b3c9dcfe1e443
```

Expiry minus issue is exactly 7,776,000 seconds. Summary has no prospective
boundary member.

### 5.2 Superseding narrower set

```json
{"author":"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","body":{"expires_at":1793776400,"fields":["all_time_send_count"],"grant_id":"019fd5eb-5e80-700c-8000-00000000000c","issued_at":1786000400,"offer_heads":["886def636941f4d7eca4dbd618919a5c7f42b8b4a13d2ea9363fa30d24f21a44","ae06df73c4717b0e84077429514ca4044bdf04c35a4c322595ca44613d5b3823"],"publisher":"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","recipient":"c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5","scope":"LOGBOOK_SUMMARY","supersedes_grant_id":"019fd5e9-d7e0-700a-8000-00000000000a","type":"cc.grant.set.v1","v":1},"endpoints":["79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"],"generation_id":"4026ec6e-92d1-8da9-9520-555f4c674a70","issued_at":1786000400,"object_id":"c937169b-5edc-83a4-a53c-14c6eef4cf18","previous_event_id":"678cf7729571aadc3ba2a51d99af7a2993b2035fe7dce79cd62b3c9dcfe1e443","seq":2,"type":"cc.envelope.v1","v":1}
```

```text
content_size=1044
content_sha256=6ff5f924f034f66d87da3f53302467ef28c729acd1c48f03546823b6250ee86a
inner_event_id=78d8e4e50f872e0b176fcc5a6cb6e7eaa858b0600fee7f979f75b3ee24253609
```

The old grant becomes hidden, retired and purged in the same committed local
transition.

### 5.3 Revoke the current grant

```json
{"author":"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","body":{"grant_id":"019fd5eb-5e80-700c-8000-00000000000c","publisher":"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","reason":"REVOKED_BY_USER","recipient":"c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5","scope":"LOGBOOK_SUMMARY","type":"cc.grant.revoke.v1","v":1},"endpoints":["79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"],"generation_id":"4026ec6e-92d1-8da9-9520-555f4c674a70","issued_at":1786000500,"object_id":"c937169b-5edc-83a4-a53c-14c6eef4cf18","previous_event_id":"78d8e4e50f872e0b176fcc5a6cb6e7eaa858b0600fee7f979f75b3ee24253609","seq":3,"type":"cc.envelope.v1","v":1}
```

```text
content_size=783
content_sha256=783202c0173202890cab6dc231aef3d8d17ffe8d9787f4914cf30d05c6ee6f3b
inner_event_id=09e82a30f7ba564a478dc9f25691fe6f993867a837e54ca5a0d02b8a0414eb6d
```

Its `author`, `body.publisher` and the named grant's publisher are the same
account; a revoke authored by the other endpoint is rejected. The grant is
terminal and must purge within 24 hours. A later set on this slot must use a new
grant id and name `019fd5eb-5e80-700c-8000-00000000000c` as its superseded
grant.

### 5.4 Backdated detail grant

The grant issue time is 1786000180, but B1's qualifying head is later at
1786000200. The required prospective boundary is therefore 1786000200.

```json
{"author":"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","body":{"expires_at":1793776180,"fields":["board_config_id","duration_minutes","sends","started_at_utc"],"grant_id":"019fd5e8-0320-700b-8000-00000000000b","issued_at":1786000180,"not_before":1786000200,"offer_heads":["886def636941f4d7eca4dbd618919a5c7f42b8b4a13d2ea9363fa30d24f21a44","ae06df73c4717b0e84077429514ca4044bdf04c35a4c322595ca44613d5b3823"],"publisher":"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","recipient":"c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5","scope":"LOGBOOK_DETAIL","type":"cc.grant.set.v1","v":1},"endpoints":["79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"],"generation_id":"4026ec6e-92d1-8da9-9520-555f4c674a70","issued_at":1786000180,"object_id":"5c05ca0c-3d10-8862-add4-f3cb6b97d237","seq":1,"type":"cc.envelope.v1","v":1}
```

```text
content_size=959
content_sha256=a2634899186aa486270e5b97ad36715ae286d9f8c7744642be9bd08f20578cd4
inner_event_id=55f74965758ef045362d067ef481cfb7d4fc6169063eeca8532412ef64136324
```

## 6. Projection events

### 6.1 All-time summary snapshot

```json
{"author":"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","body":{"as_of":1786000600,"fields":["all_time_send_count","hardest_grade_milli"],"grant_event_id":"678cf7729571aadc3ba2a51d99af7a2993b2035fe7dce79cd62b3c9dcfe1e443","grant_id":"019fd5e9-d7e0-700a-8000-00000000000a","payload":{"all_time_send_count":128,"hardest_grade_milli":21000,"type":"cc.logbook_summary","v":1},"scope":"LOGBOOK_SUMMARY","type":"cc.projection.snapshot.v1","v":1},"endpoints":["79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"],"generation_id":"4026ec6e-92d1-8da9-9520-555f4c674a70","issued_at":1786000600,"object_id":"ccbf0acc-55a7-8f3a-b2b6-5142176bf06d","seq":1,"type":"cc.envelope.v1","v":1}
```

```text
content_size=776
content_sha256=109bb3ab57161631643d109c39f44e7aa045e66095feca4d433c1c6bff3e8e2b
inner_event_id=254536297d591fc7ae426e7867906b32c1d9befe73dfe3298006c85a6dd8a146
```

The aggregate may include eligible pre-grant ascents. No contributing detail row
is present. The envelope author equals the referenced grant's publisher; a
projection authored by the other endpoint is rejected and occupies no slot.

### 6.2 Logbook-detail snapshot

The private unrounded session start is 1786003345, so it is eligible. Its wire
value is `floor(1786003345 / 900) * 900 = 1786003200`, which is at or after the
boundary 1786000200.

```json
{"author":"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","body":{"as_of":1786000700,"fields":["board_config_id","duration_minutes","sends","started_at_utc"],"grant_event_id":"55f74965758ef045362d067ef481cfb7d4fc6169063eeca8532412ef64136324","grant_id":"019fd5e8-0320-700b-8000-00000000000b","payload":{"prospective_from":1786000200,"sessions":[{"board_config_id":"4dc81ed591f36e3b69f85482356cb267d86881d687481fdf8abdd764a3bcb6c2","duration_minutes":75,"sends":[{"grade_milli":21000,"problem_hash":"1351a2b3db68c914b4812b281cb069531c8e463be73b20c74bb719317a6ddf02"}],"session_uuid":"d9b59d24-5e26-8369-8153-3cc2dce33976","started_at_utc":1786003200}],"type":"cc.logbook_detail","v":1},"prospective_from":1786000200,"scope":"LOGBOOK_DETAIL","type":"cc.projection.snapshot.v1","v":1},"endpoints":["79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"],"generation_id":"4026ec6e-92d1-8da9-9520-555f4c674a70","issued_at":1786000700,"object_id":"ff8f4248-9955-88b3-9b85-f9fd9108e024","seq":1,"type":"cc.envelope.v1","v":1}
```

```text
content_size=1116
content_sha256=e6348f033031bcc1ef064f461708eb33e7898001efea7b670669022ae0110507
inner_event_id=d622c46d83336c82aea53532c1b103da031dc27af81666c4c241118bf93b9e56
```

### 6.3 Logbook-detail delta

```json
{"author":"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","body":{"as_of":1786000800,"fields":["board_config_id","duration_minutes","sends","started_at_utc"],"grant_event_id":"55f74965758ef045362d067ef481cfb7d4fc6169063eeca8532412ef64136324","grant_id":"019fd5e8-0320-700b-8000-00000000000b","ops":[{"op":"UPSERT_SESSION","row":{"board_config_id":"4dc81ed591f36e3b69f85482356cb267d86881d687481fdf8abdd764a3bcb6c2","duration_minutes":90,"sends":[{"grade_milli":21000,"problem_hash":"1351a2b3db68c914b4812b281cb069531c8e463be73b20c74bb719317a6ddf02"}],"session_uuid":"d9b59d24-5e26-8369-8153-3cc2dce33976","started_at_utc":1786003200}}],"prospective_from":1786000200,"scope":"LOGBOOK_DETAIL","type":"cc.projection.delta.v1","v":1},"endpoints":["79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"],"generation_id":"4026ec6e-92d1-8da9-9520-555f4c674a70","issued_at":1786000800,"object_id":"ff8f4248-9955-88b3-9b85-f9fd9108e024","previous_event_id":"d622c46d83336c82aea53532c1b103da031dc27af81666c4c241118bf93b9e56","seq":2,"type":"cc.envelope.v1","v":1}
```

```text
content_size=1150
content_sha256=c4b78219fce9e409cd8e6f2a09dd2312194f383068a23e7bf3729a2aaf3ae919
inner_event_id=93debe85e9f8186975d6984963fe41eae0e403556688b0a0f12271eebd07687d
```

### 6.4 Plan-detail grant and snapshot

```json
{"author":"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","body":{"expires_at":1793776900,"fields":["focus_areas","phase","sessions","sessions_per_week"],"grant_id":"019fd5f2-ffa0-700d-8000-00000000000d","issued_at":1786000900,"not_before":1786000900,"offer_heads":["886def636941f4d7eca4dbd618919a5c7f42b8b4a13d2ea9363fa30d24f21a44","ae06df73c4717b0e84077429514ca4044bdf04c35a4c322595ca44613d5b3823"],"publisher":"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","recipient":"c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5","resource_id":"16147ec9-8d19-8886-a3b8-8b3011f59284","scope":"PLAN_DETAIL","type":"cc.grant.set.v1","v":1},"endpoints":["79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"],"generation_id":"4026ec6e-92d1-8da9-9520-555f4c674a70","issued_at":1786000900,"object_id":"946d69f8-3c45-8e6f-b1d8-836217d35762","seq":1,"type":"cc.envelope.v1","v":1}
```

```text
content_size=1000
content_sha256=85cba44d83d0002eababcd71dcaca60457bc8ed5718c30712bf083c0ea376ca5
inner_event_id=c0c75c501a66b2e20c193b6ef209fd72d84b5ce1678c85d48e935756eff626ae
```

```json
{"author":"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","body":{"as_of":1786001000,"fields":["focus_areas","phase","sessions","sessions_per_week"],"grant_event_id":"c0c75c501a66b2e20c193b6ef209fd72d84b5ce1678c85d48e935756eff626ae","grant_id":"019fd5f2-ffa0-700d-8000-00000000000d","payload":{"plan":{"focus_areas":["finger_strength","power"],"phase":"POWER","sessions":[{"day_of_week":1,"session_type":"POWER","target_duration_min":90,"target_rpe_deci":80}],"sessions_per_week":3},"prospective_from":1786000900,"type":"cc.plan_detail","v":1},"prospective_from":1786000900,"resource_id":"16147ec9-8d19-8886-a3b8-8b3011f59284","scope":"PLAN_DETAIL","type":"cc.projection.snapshot.v1","v":1},"endpoints":["79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"],"generation_id":"4026ec6e-92d1-8da9-9520-555f4c674a70","issued_at":1786001000,"object_id":"ecf57e21-dc71-885f-94d4-23e3d2ac4d7a","seq":1,"type":"cc.envelope.v1","v":1}
```

```text
content_size=1025
content_sha256=c8bb1841401674de65a562c7eaad43b705087fc5778285afa51dea4f694da4fb
inner_event_id=123ca12dfd42c469a47a31fe0e012cbd4b6bc0c84b57b89f45abc0b8783de34e
```

The body `resource_id` identifies exactly one selected resource; the plan
contains only the four granted plan tokens and planned-session scalars. It has
no identity companion or free-text channel. Its private local start boundary is
exactly 1786000900; an otherwise identical plan already active before that
instant is ineligible and emits no projection. A `plan: null` payload is valid
only from sequence 2 onward.

### 6.5 The 900-second prospective boundary

`prospective_from = 1786000200` is an ordinary Unix second and is **not**
900-aligned, while every `started_at_utc` on the wire **is**. The two rules that
close that gap are stated once, here and in wire §7.4:

- **Producer.** A session is sendable only when its private unrounded start is
  at or after `prospective_from`, the session does not span the boundary, **and**
  `floor900(start) >= prospective_from`.
- **Receiver.** A session row is valid only when
  `started_at_utc mod 900 == 0` **and** `started_at_utc >= prospective_from`.

Both sides are therefore decidable from the wire value alone, and the promise is
provable by an auditor who only sees the envelope.

| Private unrounded start | `floor900` | Sendable | Why |
|---:|---:|---|---|
| 1786000199 | 1785999600 | no | before the boundary |
| 1786000250 | 1785999600 | no | the serialised value would be 600 s before the boundary |
| 1786000499 | 1785999600 | no | same bucket; still before the boundary |
| 1786000500 | 1786000500 | **yes** | first sendable instant, `ceil900(1786000200)` |
| 1786003345 | 1786003200 | **yes** | the §6.2 vector |

The cost is explicit and deliberate: up to 899 seconds of otherwise legitimate
data immediately after a boundary are unsendable rather than serialised to a
pre-boundary value. A session that began before the boundary and ends after it
is excluded regardless of rounding.

This delta adds the first sendable session at exactly `ceil900(1786000200)`:

```json
{"author":"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","body":{"as_of":1786001050,"fields":["board_config_id","duration_minutes","sends","started_at_utc"],"grant_event_id":"55f74965758ef045362d067ef481cfb7d4fc6169063eeca8532412ef64136324","grant_id":"019fd5e8-0320-700b-8000-00000000000b","ops":[{"op":"UPSERT_SESSION","row":{"board_config_id":"4dc81ed591f36e3b69f85482356cb267d86881d687481fdf8abdd764a3bcb6c2","duration_minutes":60,"sends":[],"session_uuid":"33b26755-ca71-8401-8a75-6b1379d04c35","started_at_utc":1786000500}}],"prospective_from":1786000200,"scope":"LOGBOOK_DETAIL","type":"cc.projection.delta.v1","v":1},"endpoints":["79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"],"generation_id":"4026ec6e-92d1-8da9-9520-555f4c674a70","issued_at":1786001050,"object_id":"ff8f4248-9955-88b3-9b85-f9fd9108e024","previous_event_id":"93debe85e9f8186975d6984963fe41eae0e403556688b0a0f12271eebd07687d","seq":3,"type":"cc.envelope.v1","v":1}
```

```text
content_size=1047
content_sha256=0c35e9782f32d0ca0d155a1cd29858ecc710ef8b55b766ddb18e06231a31db1e
inner_event_id=bf98dfd812eabac44e717faf84407a3a573b44e364b37362c015b6ad1318432b
```

The corresponding negative rows — `started_at_utc = 1785999600` and an
unrounded `started_at_utc = 1786003345` — are checked-in cases
`detail_start_below_prospective_from` and `detail_start_not_900_aligned`, both
`REJECT_NO_SLOT` with reason `BODY_SCHEMA_INVALID`.

## 7. Reducer sequences

Notation uses the exact events above.

Sequences 7.1 onward assume the generation is already `ACTIVE`, that is: the
acceptance of §4.1 was applied, `endpoint_high` was promoted afterwards, and the
exact profile, marker, roster and admin set hold.

| # | Delivery sequence | Required outcome |
|---:|---|---|
| 7.0a | acceptance only | generation stays `PROVISIONING` until promotion is canonical; zero offers, grants or projections may be sent or applied |
| 7.0b | A1 before the acceptance | A1 is rejected, occupies no slot, and is not merely held; sending it is equally forbidden |
| 7.0c | acceptance authored by `endpoint_low` | rejected; the acceptance slot admits only `endpoint_high` |
| 7.0d | promotion committed before the acceptance was applied | topology is not accepted as `ACTIVE`; the generation stays `PROVISIONING` and terminalises if the promotion cannot be reconciled |
| 7.0e | two different valid acceptance events at `seq = 1` | whole generation terminal with `EQUAL_SEQUENCE_CONFLICT`; nothing is sendable |
| 7.0e-reverse | the same pair in the other arrival order | byte-identical outcome; no comparison chooses a winner |
| 7.1 | A1, B1 | A head `ACQUAINTANCE`, B head `FRIEND`, effective `ACQUAINTANCE` |
| 7.2 | B1, A1 | byte-identical state to 7.1 |
| 7.3 | A1, A2 with B1 absent | A head `FRIEND`; B has no head, so the computed level stays `NOT_ESTABLISHED`; the generation is **not** terminal and no `NONE` is synthesised |
| 7.4 | A2 before A1, B1 absent | A2 held; after A1 it becomes A's head, but the computed level stays `NOT_ESTABLISHED`; only B1 can establish `FRIEND` |
| 7.5 | A2, A1, B1 | A2 held then released; final `FRIEND`; transition display time 1786000200 |
| 7.6 | initial grant before A2 and B1 | grant held; after both exact heads arrive it applies; no quarantine |
| 7.7 | summary snapshot before its grant | snapshot held; after grant and heads it applies |
| 7.8 | logbook delta before logbook snapshot | projection object immediately `RECONCILING`, its data hidden, delta held |
| 7.9 | 7.8 then missing snapshot | snapshot applies as sequence 1, delta applies as sequence 2, object exits `RECONCILING`; final duration 90 |
| 7.10 | each event delivered twice | second copy is a no-op; no duplicate row/count/notification |
| 7.11 | A2 plus the alternate valid A sequence-2 event | whole generation terminal with `EQUAL_SEQUENCE_CONFLICT` |
| 7.11-reverse | the same pair in the other arrival order | byte-identical outcome; no comparison chooses a winner |
| 7.12 | initial grant, summary snapshot, superseding set | old summary hidden and purged in the same commit; new grant has no data until its own projection |
| 7.13 | superseding set then revoke | current grant terminal and hidden; purge within 24 hours |
| 7.14 | A3 after the detail grant and snapshot | effective level downgrades to `ACQUAINTANCE`; detail is hidden and permanently retired; purge within 24 hours |
| 7.15 | 7.14 then A4 | effective level returns to `FRIEND`; the retired detail grant and data remain inactive |
| 7.16 | A5 | generation terminal with `OFFER_NONE_RECEIVED`, all data hidden, no later event can reopen it |
| 7.17 | A3, A4, then a delayed or backdated grant referencing A2/B1 | the intervening downgrade is found on the gapless offer chains; the grant is permanently retired and cannot use the old FRIEND boundary |
| 7.18 | detail snapshot, delta, boundary delta | two sessions; every wire start is 900-aligned and the earliest is exactly `ceil900(prospective_from)` |
| 7.19 | the §8.1 wrong-generation event after a healthy prefix | rejected with `GENERATION_BINDING_INVALID`, occupies no slot, changes no applied state |

A wrong predecessor is rejected without occupying its sequence. Substituting the
correct predecessor later must apply normally.

Every row above is a `sequences` scenario of the same id in
[`fixtures/reducer-scenarios.json`](fixtures/reducer-scenarios.json), with one
stated exception, and the harness asserts that correspondence in both
directions — a printed row nothing executes, or a scenario this table stops
printing, fails the run. A row a reader assumes is executed and is not is the
decoration this bundle otherwise refuses, and this table was the last place one
could hide:

- **7.0c** is the exception. It is decided by the negative corpus rather than by
  a sequence: the case `accept_wrong_author_endpoint_low` carries the concrete
  bytes of an acceptance authored by `endpoint_low` and pins it `REJECT_NO_SLOT`
  with `AUTHOR_BINDING_INVALID`. The exemption is a closed list in the harness
  and the named case's disposition and reason are checked, so "decided by the
  corpus" cannot become a place to park an unexecuted row.
- **7.0e** was the second exception until this round, and it is the reason the
  binding above exists. The rule it states is the slot-agnostic equal-sequence
  rule of wire §8.2, which the corpus drove on the offer slot only, so §6.1's
  acceptance-conflict sentence had no vector of its own. §4.1 now carries the
  missing byte-exact input — a second structurally and semantically valid
  acceptance at `seq = 1` — and sequences 7.0e and 7.0e-reverse drive both
  arrival orders, as 7.11 and 7.11-reverse now do on the offer slot.

## 8. Wrong context, invalidation, topology and restore

### 8.1 Wrong generation

This A1-shaped content asserts group 2's generation while retaining group 1's
offer slot:

```json
{"author":"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","body":{"offer":"ACQUAINTANCE","type":"cc.relationship.offer.v1","v":1},"endpoints":["79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"],"generation_id":"fdda7260-0a81-8f78-9a32-872a8e555719","issued_at":1786000100,"object_id":"f84a6a07-c1a5-8abe-ac51-4526d1f13218","seq":1,"type":"cc.envelope.v1","v":1}
```

```text
content_size=464
content_sha256=226639b9c27f03c38b1b8e3c5d576a8895c117c07b3f69fc65f2dee0a3175181
inner_event_id=90ebf173edfaf34c62ebe2daf8c36073f8ab4ab199f8fb53c7d4d63bddc7765c
```

On group 1 it fails generation recomputation. On group 2 it also fails the
deterministic slot formula. It occupies no slot.

### 8.2 Wrong sender

The exact A1 content carried with inner `pubkey = B` encodes as:

```marmot-event
{"id":"ac415bda9c41e0a1e02631f6525663e7d56521749dfd2930856d93f1a9d722b7","pubkey":"c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5","created_at":1786000100,"kind":1220,"tags":[["L","com.cruxcoach.private-sharing"],["l","v1","com.cruxcoach.private-sharing"]],"content":"{\"author\":\"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798\",\"body\":{\"offer\":\"ACQUAINTANCE\",\"type\":\"cc.relationship.offer.v1\",\"v\":1},\"endpoints\":[\"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798\",\"c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5\"],\"generation_id\":\"4026ec6e-92d1-8da9-9520-555f4c674a70\",\"issued_at\":1786000100,\"object_id\":\"f84a6a07-c1a5-8abe-ac51-4526d1f13218\",\"seq\":1,\"type\":\"cc.envelope.v1\",\"v\":1}"}
```

```text
content_size=464
content_sha256=1d32b8590404f21262c416cd43ec1db680171f6a5c5181a2406c7c0faab1f77b
inner_event_id=ac415bda9c41e0a1e02631f6525663e7d56521749dfd2930856d93f1a9d722b7
full_event_size=792
full_event_sha256=284ec0d016e6036c84066ab94b11aec51a39383379e08d839bb291ac4a9137ad
```

If MLS authenticates A, MDK rejects the inner sender mismatch. If MLS
authenticates B, the adapter rejects envelope author A. No content applies.

### 8.3 Any stored-event invalidation is terminal

```json
{"inner_event_id":"d622c46d83336c82aea53532c1b103da031dc27af81666c4c241118bf93b9e56","reason":"LOSING_BRANCH","source_message_id":"1111111111111111111111111111111111111111111111111111111111111111"}
```

Whether the named snapshot is applied or held, this exact invalidation makes
generation `4026ec6e-92d1-8da9-9520-555f4c674a70` terminal with
`STORED_EVENT_INVALIDATED`, hides every object and preserves all
revoke/downgrade/block/remove tombstones. Zero inner matches give
`SOURCE_MAPPING_MISSING` and multiple matches give `SOURCE_MAPPING_AMBIGUOUS`;
both are equally terminal and neither fabricates an inner id.

The `reason` above is usable only because the signal carries one of the four
closed adapter reasons of wire §8.4 **and** it survives a restart. The scenario
fixtures decide all four with that second dimension: a reason outside the closed
set, and a `LOSING_BRANCH` that the adapter cannot reproduce after a restart —
the pin's own shape, where the invalidated boolean persists and the reason does
not — are each recorded as *no* durable reason and report FEAT-062 §5 gate 5 as
**FAIL**. Neither softens the outcome, and neither repairs a mapping: a zero- or
multi-match stays terminal on its own code even when the reason is perfect.

### 8.4 Commit rollback at an authority boundary

MDK emits a second invalidation class for *group state*: `GroupStateInvalidated`
with reason `SupersededByBranchSelection`, paired with the commit-rollback seams
`ForkRecovered` and `CommitRolledBack`. Each names the rolled-back commit's
transport `MessageId`.

```json
{"commit_class":"promotion","event":"GroupStateInvalidated","generation_state":"ACTIVE","invalidated_commit_id":"5555555555555555555555555555555555555555555555555555555555555555","reason":"SupersededByBranchSelection"}
```

| Rolled-back commit class | Generation state | Outcome |
|---|---|---|
| `promotion` | `ACTIVE` | terminal, `COMMIT_ROLLBACK_AFTER_ACTIVATION` |
| `admin_policy_0x8003` | `ACTIVE` | terminal, `COMMIT_ROLLBACK_AFTER_ACTIVATION` |
| `profile_0x8001` | `ACTIVE` | terminal, `COMMIT_ROLLBACK_AFTER_ACTIVATION` |
| `lifecycle_0x800c` | `ACTIVE` | terminal, `COMMIT_ROLLBACK_AFTER_ACTIVATION` |
| `promotion` | `PROVISIONING` | hold, re-read, back to `PROVISIONING` |
| `admin_policy_0x8003` | `PROVISIONING` | hold, re-read, back to `PROVISIONING` |
| `profile_0x8001` | `PROVISIONING` | hold, re-read, back to `PROVISIONING` |
| `lifecycle_0x800c` | `PROVISIONING` | hold, re-read, back to `PROVISIONING` |
| `unrelated` | any | hold, then one fresh session-consistent read decides |

Every authority class appears twice on purpose. Wire §8.4a states two different
outcomes for the same signal, and which one applies is decided by the generation
state rather than by the class, so a class exercised on one side of activation
only would leave the other half of its own rule undecided. The fixtures drive
each of the four classes, and each of the three seams, on **both** sides.

Safety tombstones stay sticky through every one of these. While a rollback or
fork is unresolved the generation holds: no send, no apply, and never fail-open.

### 8.5 Wrong creator, duplicate group and topology

Wrong creator fixture:

```json
{"actual_creator":"c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5","admin_accounts":["79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"],"group_id_hex":"3f1c9d7e5b204a6c8e0f1d2a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e","leaf_accounts":["79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"],"name":"wrong_creator"}
```

Even with a bilateral roster, B is not the permitted creator. The group is not
activated and the generation is terminally rejected with `WRONG_CREATOR`.

Duplicate fixture:

```json
{"endpoints":["79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"],"group_ids":["0a2b4c6d8e0f10213243546576879809a1b2c3d4e5f60718293a4b5c6d7e8f90","3f1c9d7e5b204a6c8e0f1d2a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e"],"name":"duplicate_nonterminal_groups"}
```

Both derived generations become terminal. Neither group id, generation id nor
arrival time is a winner.

Nonbilateral fixture:

```json
{"admin_accounts":["79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"],"group_id_hex":"3f1c9d7e5b204a6c8e0f1d2a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e","leaf_accounts":["79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5","f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"],"name":"third_leaf"}
```

Before activation it sends/applies nothing; once observed it terminalises. The
same terminal result applies to duplicate leaves, wrong/missing/extra admins,
wrong component, any retention component including the signed zero of §2.2,
signed lifecycle other than active, MDK quarantine or `Unrecoverable`.

Promotion-before-acceptance fixture:

```json
{"acceptance_applied":false,"admin_accounts":["79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"],"group_id_hex":"3f1c9d7e5b204a6c8e0f1d2a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e","name":"promotion_before_acceptance"}
```

Two endpoint admins are not sufficient. Without the applied acceptance the
activation gate fails and nothing is sent or applied.

### 8.6 Mutated bootstrap marker

```json
{"attempt_id":"9f6c2d81-4b7e-4a3c-9d2f-16b83a7c5e40","endpoint_high":"c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5","endpoint_low":"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","protocol":"com.cruxcoach.private-sharing/bootstrap/v2"}
```

This differs from §2.1 only in the protocol string. Before activation it is not
a FEAT-062 group at all, so it is dropped before any parse and stores nothing;
observed after activation it is a profile change and terminalises with
`PROFILE_MARKER_CHANGED`. The same holds for a changed name, a missing or extra
member, uppercase hex, non-JCS member order and endpoints that disagree with the
locally sorted accounts.

### 8.7 Disband is terminal

```json
{"admin_accounts":["79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"],"generation_id":"4026ec6e-92d1-8da9-9520-555f4c674a70","leaf_accounts":["79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"],"lifecycle_byte":"0x01","name":"admin_disband","signed_lifecycle":"disbanded"}
```

Either admin may author this. The disband commit itself removes every
candidate-parent leaf except the committing leaf and replaces the admin policy
with exactly the committer, which is why the fixture shows a single leaf and a
single admin. It terminalises the generation with `LIFECYCLE_NOT_ACTIVE`, hides
everything, purges within 24 hours, requires no separate MDK removal and keeps
every tombstone sticky. `disbanded` is absorbing, so no later event reopens it.

### 8.8 Restore does not send

```json
{"generation_id":"4026ec6e-92d1-8da9-9520-555f4c674a70","outbox_rows":3,"restored_on_new_device":true,"session_uuid":null,"state":"INACTIVE_READ_ONLY"}
```

Expected: zero outbox drains, zero sends and zero applies. Blocks/tombstones stay
sticky. The `NULL` `session_uuid` is preserved as `NULL` and makes its row
ineligible; restore mints no replacement. A user must explicitly create a fresh
group and derived generation.

The new-device rule and the row rules are separate, so the same-device branch is
asserted too and cannot be masked by it:

```json
{"plan_uuid":"16147ec9-8d19-8886-a3b8-8b3011f59284","restored_on_new_device":false,"session_uuid":"d9b59d24-5e26-8369-8153-3cc2dce33976"}
```

A same-device reopen is not the new-device restore: the rows are neither
read-only nor unsendable. Restore still mints no identifier and tombstones stay
sticky, because those two hold on every path.

Product size follows the same preserve-or-`NULL` rule as the row ids. An unset or
cross-brand `product_size_id` stays `NULL` and makes the affected item
ineligible — never defaulted to a plausible value:

```json
{"product_size_id":null,"requires_product_size":true,"restored_on_new_device":false,"session_uuid":"d9b59d24-5e26-8369-8153-3cc2dce33976"}
```

The row is ineligible and unsendable even though the session id is present and
the device is the same one, which is why this vector is stated on the same
device: the new-device rule must not be what produces the answer. With a real
`product_size_id` the identical state is eligible, so the `NULL` is provably the
cause. An item that carries no product-size dimension at all is untouched by the
rule.

### 8.9 Outbox, leases and trusted time

An expired lease inside the same boot, with nothing yet handed to the transport:

```json
{"attempts":3,"current_boot_id":"boot-1","external_effect_phase":"PRE_EXTERNAL_EFFECT","inner_event_id":"09e82a30f7ba564a478dc9f25691fe6f993867a837e54ca5a0d02b8a0414eb6d","lease_boot_id":"boot-1","lease_owner":"worker-a","lease_until":1786000900,"name":"expired_lease_pre_external_effect","now":1786001000,"persisted_transport":null,"receipt":null,"state":"IN_FLIGHT"}
```

`RECOVER_QUEUED`. This is the one automatic requeue there is: no external effect
can have begun, so any worker returns the row to `QUEUED` and releases the
lease. The recovery sends nothing; a later drain attempt drives exactly
`09e82a30…` as a **first** delivery, through the write-ahead gate of §8.7c. A
second inner event id is a failure. A worker whose `lease_owner` differs must
not advance the row while the lease still holds — a live lease is `LEASE_HELD`
whatever the phase says.

A lease left behind by a previous boot, whose worker died after the send may
already have left the device:

```json
{"attempts":3,"current_boot_id":"boot-2","external_effect_phase":"EXTERNAL_EFFECT_UNCLEAR","inner_event_id":"09e82a30f7ba564a478dc9f25691fe6f993867a837e54ca5a0d02b8a0414eb6d","lease_boot_id":"boot-1","lease_owner":"worker-a","lease_until":9999999999,"name":"lease_from_previous_boot_unclear","now":10,"persisted_transport":null,"receipt":null,"state":"IN_FLIGHT"}
```

`RECOVERY_BLOCKED` with `RECOVERY_EXTERNAL_EFFECT_UNCLEAR`. A monotonic
`lease_until` from another boot is not comparable with this boot's clock, so the
boot check runs **first**: a foreign `lease_boot_id` means the lease is expired
and reclaimable, whatever the stored deadline says. But reclaiming the lease
authorises no requeue. Because the external effect may already have begun and no
identical transport message is persisted, the row becomes `DELIVERY_UNCLEAR`:
visible, never automatically sendable, never pruned by age, claiming neither
delivery nor non-delivery. The same row with `PRE_EXTERNAL_EFFECT` instead is
`RECOVER_QUEUED` — the phase decides, not the boot change.

A row that even claims a restart-durable, replayable transport message under its
one source id:

```json
{"attempts":3,"current_boot_id":"boot-1","external_effect_phase":"EXTERNAL_EFFECT_UNCLEAR","inner_event_id":"09e82a30f7ba564a478dc9f25691fe6f993867a837e54ca5a0d02b8a0414eb6d","lease_boot_id":"boot-1","lease_owner":"worker-a","lease_until":1786000900,"name":"unclear_with_replayable_transport","now":1786001000,"persisted_transport":{"replayable":true,"restart_durable":true,"source_message_id":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"},"receipt":null,"state":"IN_FLIGHT"}
```

`RECOVERY_BLOCKED` with `RECOVERY_RETRY_GATE_OPEN`. The transport-identical
branch of [wire §8.7b](PRIVATE-SHARING-WIRE-V1.md#87b-lease-recovery-before-and-after-external-effect)
is admitted only by all three of its conditions together: this row does carry a
restart-durable replayable record, but `R6-G2-TRANSPORT-IDENTICAL-RETRY` is open
**and** the resume path yields no source-correlated receipt. The reported reason
names the gate because it is the nearer condition; were the gate closed, the
same row would block with `RECOVERY_SOURCE_CORRELATION_UNAVAILABLE`. The source
id `cccc…` is shown on the row, not re-driven, and no second id is minted.

An external effect that a durable typed receipt does evidence:

```json
{"attempts":3,"current_boot_id":"boot-1","external_effect_phase":"EXTERNAL_EFFECT_OBSERVED","inner_event_id":"09e82a30f7ba564a478dc9f25691fe6f993867a837e54ca5a0d02b8a0414eb6d","lease_boot_id":"boot-1","lease_owner":"worker-a","lease_until":1786000900,"name":"external_effect_observed","now":1786001000,"persisted_transport":null,"receipt":{"accepted_relays":[],"canonical_at":null,"inner_event_id":"09e82a30f7ba564a478dc9f25691fe6f993867a837e54ca5a0d02b8a0414eb6d","intent_id":"intent-4","source_message_ids":["dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"]},"state":"IN_FLIGHT"}
```

`RESOLVE_FROM_RECEIPT`. The receipt decides the state — here `SENT_LOCALLY`,
because no accepted relay endpoint is persisted — and nothing is handed to the
transport again. The same phase *without* a durable receipt is not an observed
delivery at all: it is `RECOVERY_BLOCKED` with `RECOVERY_EVIDENCE_INCOMPLETE`.

| Lease | Phase | Persisted evidence | Outcome | Row becomes |
|---|---|---|---|---|
| live | any | any | `LEASE_HELD` | `IN_FLIGHT` |
| expired | `PRE_EXTERNAL_EFFECT` | none | `RECOVER_QUEUED` | `QUEUED` |
| expired | `EXTERNAL_EFFECT_OBSERVED` | receipt with its one source id | `RESOLVE_FROM_RECEIPT` | `SENT_LOCALLY` or `MDK_CANONICAL` |
| expired | `EXTERNAL_EFFECT_OBSERVED` | none | `RECOVERY_BLOCKED`, `RECOVERY_EVIDENCE_INCOMPLETE` | `DELIVERY_UNCLEAR` |
| expired | `EXTERNAL_EFFECT_UNCLEAR` | none | `RECOVERY_BLOCKED`, `RECOVERY_EXTERNAL_EFFECT_UNCLEAR` | `DELIVERY_UNCLEAR` |
| expired | `EXTERNAL_EFFECT_UNCLEAR` | transport record that is not restart-durable, or not repeatable | `RECOVERY_BLOCKED`, `RECOVERY_EXTERNAL_EFFECT_UNCLEAR`; its known source id stays visible | `DELIVERY_UNCLEAR` |
| expired | `EXTERNAL_EFFECT_UNCLEAR` | replayable identical transport, gate open | `RECOVERY_BLOCKED`, `RECOVERY_RETRY_GATE_OPEN` | `DELIVERY_UNCLEAR` |

**The phase is written write-ahead of the handover.** A transport call is not
undoable and no local transaction spans it, so the order of
[wire §8.7c](PRIVATE-SHARING-WIRE-V1.md#87c-write-ahead-before-a-non-atomic-handover)
decides what a crash can leave behind. This write-ahead result authorises
nothing:

```json
{"write_ahead_commit":"unproven"}
```

`HANDOVER_REFUSED` with `WRITE_AHEAD_COMMIT_UNPROVEN`: the committing process
never learned the outcome, and unknown is not committed, so the transport is not
called at all. Refusing is not the same as knowing where the row stands, and the
three refusals differ there:

| Refusal | Admissible durable phases | Recovery from each |
|---|---|---|
| `not_attempted` | `PRE_EXTERNAL_EFFECT` | `RECOVER_QUEUED` — a later ordinary first delivery |
| `failed`, provably | `PRE_EXTERNAL_EFFECT` | `RECOVER_QUEUED` |
| `unproven` | `PRE_EXTERNAL_EFFECT` **or** `EXTERNAL_EFFECT_UNCLEAR` | `RECOVER_QUEUED` from the first, `RECOVERY_BLOCKED` → `DELIVERY_UNCLEAR` from the second |

The unproven commit may still have landed, and nothing writes it back, so the
harness asserts *both* observations through the same recovery path rather than
assuming one. Only `committed` returns `HANDOVER_AUTHORISED`, with
`EXTERNAL_EFFECT_UNCLEAR` already durable before the call — from that moment
recovery can only block.

The crash window the order exists to close:

```json
{"crash_point":"after_commit_before_handover"}
```

The recovering worker finds `EXTERNAL_EFFECT_UNCLEAR`, not `PRE_EXTERNAL_EFFECT`,
because the phase was committed first. Durable state cannot tell this apart from
a crash inside the handover, so both end `RECOVERY_BLOCKED` →
`DELIVERY_UNCLEAR`. A crash *inside* the commit is the interesting benign case:

```json
{"crash_point":"during_write_ahead_commit"}
```

Either phase may be on disk, and both are safe — `PRE_EXTERNAL_EFFECT`
requeues, because an unproven commit never authorised a call, and
`EXTERNAL_EFFECT_UNCLEAR` blocks conservatively.

| Crash point | Durable phase found | Handover authorised | Recovery |
|---|---|---|---|
| before the write-ahead commit | `PRE_EXTERNAL_EFFECT` | no | `RECOVER_QUEUED` |
| inside the write-ahead commit | `PRE_EXTERNAL_EFFECT` or `EXTERNAL_EFFECT_UNCLEAR` | no | `RECOVER_QUEUED` / `RECOVERY_BLOCKED` |
| after the commit, before the handover | `EXTERNAL_EFFECT_UNCLEAR` | yes | `RECOVERY_BLOCKED` |
| during the handover | `EXTERNAL_EFFECT_UNCLEAR` | yes | `RECOVERY_BLOCKED` |
| after the handover, before receipt persistence | `EXTERNAL_EFFECT_UNCLEAR` | yes | `RECOVERY_BLOCKED` |
| after the typed receipt is persisted | `EXTERNAL_EFFECT_OBSERVED` | yes | `RESOLVE_FROM_RECEIPT` |

No window in which a handover was authorised leaves a row readable as
`PRE_EXTERNAL_EFFECT`. A row that pairs them anyway is `RECOVERY_BLOCKED` with
`RECOVERY_WRITE_AHEAD_INCONSISTENT` — the evidence wins, the phase does not.

**Authorisation is not a call result.** The gate of §8.7c reports only whether
the transport may be called. What the call did is
[wire §8.7d](PRIVATE-SHARING-WIRE-V1.md#87d-after-an-authorised-transport-call),
and the same word means opposite things on the two sides of it. Refused *at the
gate* — `not_attempted`, `failed` or `unproven` — establishes one thing only:
no call was made. Where that leaves the row is the table above, and only the
first two answer it uniformly — after `not_attempted`, or a `failed` commit
that provably did not land, the row is durably `PRE_EXTERNAL_EFFECT`, so a
later attempt is an ordinary first delivery. An `unproven` commit may still have
landed, so the row is read rather than assumed: found as `PRE_EXTERNAL_EFFECT`
it may be driven as that same ordinary first delivery, and found as
`EXTERNAL_EFFECT_UNCLEAR` it fails closed to `DELIVERY_UNCLEAR` — never
requeued, never retried automatically, and never written back to pre-effect.
Refused *after* authorisation is this:

```json
{"transport_call_result":"refused"}
```

`POST_HANDOVER_UNCLEAR` with `CALL_REFUSED_AFTER_AUTHORISATION`, durable phase
`EXTERNAL_EFFECT_UNCLEAR`. The commit that authorised the call had already made
that phase durable, so there is nothing to revert to: no automatic retry, no
requeue, no transition back to pre-effect. `error` and

```json
{"transport_call_result":"no_answer"}
```

behave identically, with `CALL_ERROR_AFTER_AUTHORISATION` and
`CALL_WITHOUT_ANSWER`. A returned refusal is a statement about the call, never
durable evidence that no external effect began; reverting the phase would need a
synchronous, durable no-effect proof *and* a fail-closed write-back, neither of
which is established at the pin and neither of which this bundle assumes. Each
of the three ends at `RECOVERY_BLOCKED` → `DELIVERY_UNCLEAR` after recovery.
Only `receipt_persisted` reaches `EXTERNAL_EFFECT_OBSERVED`, where the receipt
resolves the row to `SENT_LOCALLY` without a second handover.

Trusted-time cases, all evaluated against the reboot-aware clock of wire §8.8:

| Case | Persisted | Observation | Result |
|---|---|---|---|
| ordinary advance | `boot-1`, anchor 1000, high water 1786000000 | `boot-1`, monotonic 1100, wall 1786000100 | trusted, `effective_now = 1786000100` |
| rollback before the deadline | `boot-1`, anchor 1000, high water 1786000100 | `boot-1`, monotonic 1200, wall 100 | `TIME_UNTRUSTED`, `WALL_ROLLBACK` |
| frozen wall clock | `boot-1`, anchor 1000, high water 1786000100 | `boot-1`, monotonic 1901, wall 1786000100 | `TIME_UNTRUSTED`, `WALL_FROZEN` |
| reboot | `boot-1`, anchor 1000, high water 1786000100 | `boot-2`, monotonic 5, wall 1786000500 | `TIME_UNTRUSTED`, `BOOT_CHANGED` |
| monotonic reset in one boot | `boot-1`, anchor 1000, high water 1786000100 | `boot-1`, monotonic 10, wall 1786000200 | `TIME_UNTRUSTED`, `MONOTONIC_RESET` |
| explicit trusted re-anchor | after the reboot case | `boot-2`, monotonic 5, trusted wall 1786000500 | trusted again, high water 1786000500 |

```json
{"expires_at":1793776300,"high_water":1793776400,"name":"backwards_clock_cannot_revive","wall_clock":1793776400}
```

Because the persisted high-water mark is already past `expires_at`, the grant
stays expired and its purge deadline keeps running. That case alone was never
sufficient: the rollback row above is the counterexample it did not cover, and
it is why a rollback is `TIME_UNTRUSTED` rather than merely a failure to
advance.

While `TIME_UNTRUSTED` holds, every time-bounded grant is hidden, permissive
sends and applies are blocked, and every due-or-unknown purge is immediately
due. Restrictive local actions — revoke, downgrade, block, disband — are never
blocked by it. The only exit is the explicit trusted re-anchor above; peer time
is never an input.

### 8.10 Adapter runtime gates from R5 and R6

The executed R5 and R6 adapter spikes both returned NO-GO and left eight named
MDK-capability gates open ([wire §11.1](PRIVATE-SHARING-WIRE-V1.md#111-open-mdk-capability-gates-r6)).
Four of them have decidable local behaviour, so they are fixtures rather than
prose. Every case below is executed by the checked-in harness.

R6 changed the *reason* several of these gates stay open without changing the
fixtures' verdicts, and that distinction is the point of this section: the
fail-closed rules below are what CruxCoach does locally, and they hold whether a
capability is missing at the pin or merely unproven on the surface CruxCoach
would use. The per-gate reconciliation — capability, evidence scope and blocking
remainder — is asserted separately, one case per gate, from the `r6_gates`
fixture section.

**One inner event binds at most one distinct source id.** This receipt is
terminal with `RETRY_NOT_TRANSPORT_IDENTICAL`:

```json
{"inner_event_id":"09e82a30f7ba564a478dc9f25691fe6f993867a837e54ca5a0d02b8a0414eb6d","intent_id":"intent-3","retry_kind":"none","source_message_ids":["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]}
```

So is a receipt whose `retry_kind` is `new_transport_id`: a re-encrypted or
re-queued send is a new event, never a retry of the same inner id. The same
transport message observed twice is one source id and applies normally, and an
empty `source_message_ids` is an ordinary offline state. Gate
`R6-G2-TRANSPORT-IDENTICAL-RETRY` stays open, and until every condition of wire
§8.7b is met — not merely that gate — an unclear or partial first delivery is
re-driven identically or not at all.

R6 proved the pin really can perform that identical re-drive, which refutes R5's
finding of absence — and it also showed why closing the gate would not be
enough. The path that performs the replay, `resume_outbound_fanouts`, emits no
`PublishedApplicationMessage`, so a first delivery accepted there binds no
source id at all. That is `ACCEPTED_WITHOUT_SOURCE_RECEIPT`: the transport took
it, nothing correlates it, and the row stays `EXTERNAL_EFFECT_UNCLEAR` rather
than being labelled sent.

**A lagged live subscription proves nothing.** This ingress state holds with
`HOLD_REPLAY_INCOMPLETE` — it does not terminalise and it does not claim
completeness:

```json
{"durable_replay_available":true,"live_lagged":true,"pruned_before_ack":false,"replay_gap_closed":false}
```

With `replay_gap_closed` true it applies; with `pruned_before_ack` true it is
terminal with `PROJECTION_AUTHORITY_INVALID`, because retention already removed
the bytes replay would have needed. Gate `R6-G3-BROADCAST-LAG-REPLAY` stays
open: R6 forced a real overflow, lost 2152 events and recovered the custom event
from the store exactly once, but only from a crate-internal test against a
private broadcast sender — no surface CruxCoach's adapter could use.

**The MDK raw app store is never the authority.** R5 criterion E proved a real
retention sweep deletes expired raw custom rows while a later survivor remains,
so this state is terminal with `PROJECTION_AUTHORITY_INVALID`:

```json
{"authority":"mdk_raw","host_acked":true,"raw_pruned":true}
```

The durable CruxCoach projection survives the same pruning once the item is
host-ACKed; an item pruned *before* its ACK is terminal. Gate
`R6-G1-DURABLE-PROJECTION` stays open, which is exactly why the product design
is No-Go — and R6 decided nothing about it, so the lock is untouched. R6 finding
1 sharpens the risk instead: a first delivery accepted during resume never runs
`finalize_published_app_message_source_retention`, so its raw row keeps the
retention it was created with and can be pruned before CruxCoach ever ACKs it.

**Epoch advances are not a retention mechanism.** Wire §8.5 requires that
neither pruning nor more than five epoch advances remove an unacknowledged item,
so the epoch count never decides an outcome by itself. What decides is whether
the item is still there before its host ACK. This state is terminal with
`PROJECTION_AUTHORITY_INVALID`:

```json
{"durable_replay_available":true,"epoch_advances":6,"host_acked":false,"live_lagged":false,"pruned_before_ack":false,"replay_gap_closed":true,"unacked_item_present":false}
```

With `unacked_item_present` true the same six advances apply normally — the
count is not a fault. With `host_acked` true the same absence also applies,
because the CruxCoach transaction has committed and the idempotency row of §8.5
owns the outcome from then on. That is the boundary the host ACK exists to draw,
and it is asserted in both directions so neither half can be softened by edit.

**Exactly-once has one permitted scope.** This claim is `FORBIDDEN` with
`EXACTLY_ONCE_SCOPE_EXCEEDED`:

```json
{"scope":"live_emission"}
```

`relay_delivery`, `network_delivery`, `raw_row_count` and `peer_receipt` are
equally forbidden; `cruxcoach_reduction`, `inner_event_id` and
`source_message_id` are permitted. Gate `R6-G4-EXACTLY-ONCE-SCOPE` stays open.
R6 did hold live emission at exactly one raw row under two rounds of deliberate
redelivery pressure — in one host process against a MockRelay, which bounds
nothing about relays or the network, and those scopes stay forbidden regardless.

The remaining four — `R6-G5-CUSTOM-REORG`, `R6-G6-UNIFFI-FORWARDING`,
`R6-G7-INBOUND-NATIVE-DELIVERY` and `R6-G8-INSTRUMENTATION-RUN` — have no local
decidable behaviour to pin here. R6 produced real host-scope evidence for the
middle two and the app half of the first; `R6-G8-INSTRUMENTATION-RUN` was never
run at all, which is why none of that evidence is native or on-device. They
remain native, on-device obligations, and no fixture in this document may be
read as evidence for them.

### 8.11 Generation state machine

The lifecycle is executed as an explicit machine, so the **forbidden** edges are
asserted and not merely described. From `TERMINAL`:

```json
{"start_state":"TERMINAL","sticky_deny":["revoke:grant-summary","downgrade:detail"],"transitions":[{"event":"activation_gate_passed"},{"event":"return_to_provisioning"},{"event":"replay_after_terminal"}]}
```

`activation_gate_passed` and `return_to_provisioning` are both refused, the
state stays `TERMINAL`, replay changes nothing and both sticky denials survive.

| Start | Event | Result |
|---|---|---|
| `PROVISIONING` | `activation_gate_passed` | `ACTIVE`, send and apply allowed |
| `PROVISIONING` | `convergence_unresolved` | `PROVISIONING`, holding, not terminal |
| `PROVISIONING` | `rollback_authority_commit` | `PROVISIONING`, holding |
| `ACTIVE` | `return_to_provisioning` | **refused**; a generation cannot un-become active |
| `ACTIVE` | `rollback_unrelated_commit` | `ACTIVE`, holding until a fresh session-consistent read |
| `ACTIVE` | `rollback_authority_commit` | `TERMINAL`, `COMMIT_ROLLBACK_AFTER_ACTIVATION` |
| `ACTIVE` | `terminal_fault` | `TERMINAL` with the named closed code |
| `TERMINAL` | `activation_gate_passed` | **refused**; terminal is absorbing |

Send and apply are gated together: they are allowed only in `ACTIVE` and only
while nothing holds. The sticky-deny set only ever grows, through every hold,
rollback and replay.

The rows above are the ones worth reading; the fixtures decide the **whole**
matrix. Every combination of the three states and the nine lifecycle events is
exercised by some scenario, accepted or refused, and the harness fails if one is
not — a defined transition that nothing decides is exactly the gap criterion 26g
exists to close. That is what pins `TERMINAL` as absorbing against the entire
vocabulary rather than against the two edges printed above: from `TERMINAL`,
`convergence_unresolved`, `fresh_read_ok`, both rollback events and even a
second `terminal_fault` are all refused, and only recording a further sticky
denial still changes anything.

## 9. Mandatory negative corpus

Every case below exists as concrete bytes or a concrete observed state in
[`fixtures/negative-corpus.json`](fixtures/negative-corpus.json) with exactly one
expected disposition, and the checked-in harness executes all of them against
the reference parser and reducer. A case name alone does not satisfy this
corpus.

The five dispositions are closed:

| Disposition | Meaning | Reason vocabulary |
|---|---|---|
| `DROP_BEFORE_PARSE` | wrong kind/tags, or MDK rejected the carrier: nothing is read, nothing is stored, not even a diagnostic | none |
| `BOUNDED_DIGEST_ONLY` | the namespace matched but the content never became an envelope; only the §8.6 digest record survives | wire §8.6 codes |
| `REJECT_NO_SLOT` | a structurally or semantically invalid event; the sequence stays free for a later correct event | wire §8.6 codes |
| `HELD` | a missing predecessor, cross-stream dependency or unreplayed live gap; durably stored and re-evaluated | `HOLD_PREDECESSOR_MISSING`, `HOLD_GRANT_MISSING`, `HOLD_OFFER_HEAD_MISSING`, `HOLD_REPLAY_INCOMPLETE` |
| `TERMINAL` | the whole generation stops; tombstones stay sticky | wire §8.4 codes |

Mandatory coverage, one corpus area per row:

| Area | Mandatory mutation coverage |
|---|---|
| marmot_app_event | id, sender, kind, tags, extra/missing field, `sig` |
| canonical_content | duplicate key at every depth, non-JCS order, float, unsafe integer, non-NFC, not UTF-8 at all, oversize, unknown body type |
| cc.envelope.v1 | version, generation, endpoints, author, slot, sequence, predecessor, unknown field |
| cc.generation.accept.v1 | wrong author, `seq > 1`, missing or out-of-range `acceptance_revision`, unknown member, a second valid acceptance at `seq = 1` |
| bootstrap_marker_0x8001 | changed name, changed protocol string, missing/extra member, uppercase hex, non-JCS order, endpoint mismatch, post-activation change |
| cc.relationship.offer.v1 | unknown offer, wrong actor slot, unknown member |
| cc.grant.set.v1 | every required/conditional member, level, heads, fields, expiry, supersession, resource, publisher binding |
| cc.grant.revoke.v1 | current grant, publisher/author equality, recipient, scope and reason |
| projection_bodies | grant binding, publisher/author equality, boundary, first-event type, operation count, unknown field |
| board_and_problem | ecosystem-specific ids, provider-id grammar, ranges, hold fingerprint, `catalogue_revision` |
| summary_payloads | exact granted-token projection, ranges, absence state, grant equality |
| logbook_detail_rows | 900-second boundary and alignment, completeness, ordering, caps, token match |
| plan_detail_rows | one body resource, no plan identity companion, non-null initial plan, ordering, caps, enums, token match |
| delta_operations | closed operation name, exact row/id shape, scope compatibility, resulting-state bound |
| removed_shapes | multipart members, `base_seq`, projection request, connection intent, kind 1221, `catalogue_revision`, `sends_truncated` |
| payload_prohibition | one concrete case per forbidden member of wire §7.7 |
| component_profile | each required id missing, each forbidden id present including a signed zero, unknown id, `0x8009` in GroupContext, `0x0002` in GroupContext, phase-wrong admin policy |
| bootstrap_and_provisioning | creator, duplicate group, `attempt_id` reuse/derivation, crash-retry second group, any traffic while `PROVISIONING` |
| reducer_and_topology | equal sequence, cross-publisher grant id, invalidation zero/one/many, quarantine, `Unrecoverable`, disband, commit rollback, storage bounds |
| adapter_runtime_gates | second source id for one inner event, re-queued payload presented as a retry, unreplayed broadcast lag, gap already pruned by retention, MDK raw store used as authority, item pruned before host ACK |

**Carrier:** `sig`; any unknown extra peer field; duplicate top-level key;
wrong NIP-01 id; inner pubkey different from MLS sender; wrong kind; either tag
missing, reordered, changed or supplemented, including the two-element
`["l","v1"]`. Wrong kind/tags must cause zero content parses and zero diagnostic
records.

**Canonical content:** duplicate key at every nesting depth; non-JCS member
order; unknown version; float; unsafe integer; non-NFC string; unknown member;
content above the 65,536-byte cap; unknown body type.

**Removed shapes that must now be rejected:** any multipart member (`part`,
`part_index`, `part_count`), any `base_seq`, any projection-request body, any
`catalogue_revision` member on a board or problem, any `exercise` row or
exercise name, any `sends_truncated` companion, and any bootstrap or connection
intent body such as a `cc.connection.intent.v1` or a kind-1221 carrier. Each was
considered and removed; an event carrying one is invalid, not merely unknown.

**Envelope and slot:** unsorted/equal/wrong endpoints; wrong generation;
wrong author; random object UUID; previous id at sequence 1; missing/wrong/cross-
slot predecessor above sequence 1; author change; sequence zero. A wrong
predecessor must not occupy the slot.

**Offer/grant:** unknown offer; absent or reversed offer-head pair; missing head
held rather than rejected; existing nonqualifying heads rejected; summary with a
detail boundary; detail without the exact boundary; expiry beyond 90 days;
reused grant id; the other publisher's grant id, which is the terminal
`GRANT_ID_COLLISION`; missing/wrong supersession; more than one plan resource;
resource on another scope; ungrantable field; stale qualifying heads after an
intervening downgrade, including after re-upgrade; revoke of a noncurrent grant;
revoke whose author is not the grant's publisher.

**Projection:** non-snapshot sequence 1; wrong grant event id; mismatching
scope/fields/resource/boundary; a projection authored by an endpoint that is not
the grant's publisher; delta with zero or 51 operations; partial snapshot;
logbook state above 100 sessions, whether by snapshot or as the *result* of a
delta; session with 101 distinct sends when that token is granted; any selected
subset presented as complete; payload above the byte cap.

**Payload:** every raw row, bid/bid-count, attempt/count/timestamp, private-note,
`adaptationNotes`, health/injury/body/weight/sleep/pain/skin/mood, workout,
payment, location/gym/wall/hall/GPS, credential/key/key-reference,
backup/backup-location, MLS/MDK secret, local-id, `userId`, `generatedBy`,
`planVersion`, generated/adaptation, climb/setter/description,
hold-sequence/frame, relay/group/epoch field; missing hold fingerprint;
malformed canonical hold sequence; ungranted companion field; plan title, note,
payload-level `plan_uuid`, exercise-shaped row or free text; planned-session
unknown member; JSON float target RPE; plan whose private local start precedes
or spans its boundary.

**Bootstrap and provisioning:** a group created by `endpoint_high`; a second
group for the same endpoint pair; a create retried after a crash that produces a
second group instead of returning the bound one; an `attempt_id` reused after an
explicit abort; an `attempt_id` derived from a clock or an account value; a
group whose `0x8001` marker is absent, mutated or endpoint-mismatched; promotion
committed before the acceptance applied; any offer, grant or projection sent or
applied while `PROVISIONING`.

**Component profile:** each required id missing; each forbidden id present,
including a retention component carrying zero; an unknown component id; `0x8009`
placed in the GroupContext dictionary instead of the LeafNode dictionary; a
`0x0002` entry in the GroupContext dictionary; an admin policy that does not
match the phase of §2.2 in either direction.

**Reducer/topology:** second valid event at one slot/sequence must terminalise,
on the acceptance slot exactly as on the offer slot and under either arrival
order;
any stored-event invalidation, zero/multiple source mapping, MDK quarantine,
`Unrecoverable`, an authenticated disband and a rolled-back promotion, admin-
policy, profile or lifecycle commit after activation must terminalise; a fresh
snapshot must not heal a conflicting prefix; a gap must recover only on its
missing chain; wrong creator, duplicate group, extra/duplicate leaf, admin
mismatch, component mismatch and any retention component must never fail open;
exceeding a `HELD` or accepted-history bound must terminalise the offending
generation before the insert, never evict, never prune a safety prefix or
tombstone, and never touch another generation.

**Outbox, clock and ACK:** advancing an `IN_FLIGHT` row without holding its
lease; recovering an expired lease into a second inner event id; calling the
transport before the write-ahead phase commit is proven, or on an unattempted,
failed or unproven commit; writing the phase only after the handover, or any
claim that a local transaction spans a transport call; reverting an authorised
send to `PRE_EXTERNAL_EFFECT`, retrying or requeueing it after a returned
refusal, error or missing answer, or assuming a synchronous durable proof that
no external effect began; requeueing a row that
pairs `PRE_EXTERNAL_EFFECT` with durable evidence of an authorised handover;
requeueing an expired or foreign-boot row whose external effect may already have
begun instead of blocking it as `DELIVERY_UNCLEAR`; admitting a transport-identical
replay on anything less than all three conditions of wire §8.7b together — a
restart-durable persisted identity, a closed `R6-G2-TRANSPORT-IDENTICAL-RETRY`
and a source-correlated receipt on the replaying path; draining, retrying
or age-pruning a `DELIVERY_UNCLEAR` row, or relabelling it as queued, sent or not
sent; comparing a monotonic `lease_until` across a boot change instead of
expiring the lease; pruning an exhausted `FAILED` row by age; a permissive row
superseding a restrictive one; marking `SUPERSEDED` before the replacement is
durably enqueued; a backwards, frozen or rebooted clock that revives an expired grant or
postpones a purge instead of entering `TIME_UNTRUSTED`; claiming
**Published to at least one configured relay** without a persisted typed receipt
naming a concrete accepted relay endpoint; a host ACK before the CruxCoach
transaction commits; pruning an unacknowledged inbox item; an outbound receipt
that is empty or fabricated after a crash between publication and host
persistence.

**Adapter runtime gates:** a receipt binding two distinct source message ids to
one inner event id; a re-encrypted or re-queued send presented as a retry of the
same inner id; a lagged live subscription treated as complete instead of holding
until durable replay closes the gap; a gap whose bytes retention already pruned
treated as recoverable; the MDK raw application store read as authority for
CruxCoach state; an inbox item pruned before its host ACK; and any exactly-once
claim outside idempotent local reduction — live emission, relay delivery,
network delivery, raw row count or a peer receipt.

**Runtime/restore:** any chat/list/unread/Markdown/NewMessage effect; any social
`NostrRelayPool` call or second cursor; a kind-30443 or kind-10050 publication
without explicit consent; `foreign_keys` left off on the production connection;
new-device restore that drains one outbox row or mints a `session_uuid` or
`plan_uuid` instead of preserving it or leaving it `NULL`; a `NULL` or
cross-brand product size that is defaulted instead of failing closed.

## 10. Independent reproduction

Every exact value in this document is reproducible from the inputs printed
beside it. No exact input is abbreviated or elided.

The conforming checker is checked in at
[`scripts/verify_private_sharing.py`](scripts/verify_private_sharing.py). It:

- parses every fenced `json` block and requires exact canonical JCS;
- recomputes the generation, endpoint-pair, acceptance, offer, grant, projection
  and row values from their printed preimages;
- recomputes the bootstrap marker JCS, its size and digest, and the QUIC-varint
  `0x8001` component encoding;
- recomputes board/problem/hold and envelope SHA-256 values;
- recomputes every printed NIP-01 event id and UTF-8 content size;
- binds **every** value printed in this document to the fixture record it claims
  to come from — each derivation block's `sha256` and rendered identifier to the
  fixture the printed preimage names, each event block's `content_size` and
  `content_sha256` to the fixture event its `inner_event_id` names, each
  `full_event_size`/`full_event_sha256` and each `marmot-event` block to that
  same event's stored encoder bytes — and compares the two sets in both
  directions, so neither a corrupted printed value nor a fixture value this
  document stops printing can pass. `MANIFEST.json` does not cover this: it is a
  whole-file digest that cannot tell a prose edit from a corrupted golden value,
  and regenerating it is the documented response to any bundle edit;
- checks that every `grant_id` is a UUIDv7 whose timestamp bits equal
  `issued_at × 1000`;
- parses the full app-event fixtures and requires the pinned struct order
  `id,pubkey,created_at,kind,tags,content` with no `sig`;
- executes every negative case and asserts its disposition and closed reason;
- executes the reducer, phase, generation state-machine, rollback, clock,
  outbox, send-handover, bound, restore and adapter-runtime-gate scenarios,
  including the refused state transitions and every external-effect phase of an
  expired-lease recovery — the permitted requeue, the receipt-resolved row and
  the blocked `DELIVERY_UNCLEAR` row;
- executes the write-ahead order in front of the transport handover: which
  commit results authorise a call, that an unproven one never does, what each
  crash window of one send attempt leaves for recovery, and — separately from
  the authorisation gate — what every closed transport-call result leaves
  behind, including that a refusal after authorisation never reverts, retries or
  requeues, and that a transport acceptance carrying no correlatable source
  receipt stays unclear instead of counting as a delivery;
- mutates every expectation field in every fixture file and requires the scoped
  check to turn red, so a decorative expectation cannot pass as an assertion;
- asserts that all eight R6 gates are still recorded `OPEN`, that none claims a
  CruxCoach-suitable closure, that each names a non-empty blocking remainder,
  and that no gate's evidence scope is native, on-device or release — separately
  from the capability R6 demonstrated, so a proven capability can never be read
  as a discharged gate and host evidence can never widen into native evidence;
- asserts that the binding R6 record is the approved one **and** that wire §11.1
  still prints it — the `GO-NO-GO_1.md` record, its patch digest and both pins —
  and that each document still carries its pinned verdict sentence, together
  with the threat model's forbidden claims on the exactly-once scope, on S9 not
  being R6/native/on-device evidence, and on an uncorrelated acceptance not
  being a delivery; beyond those structural checks it screens the bundle prose
  for claims that a gate is proven or closed, which is a backstop rather than a
  proof;
- rejects incomplete exact fixtures and every removed legacy schema token;
- asserts every content is at most 65,536 bytes;
- checks every relative link and anchor in the bundle;
- recomputes the bundle and evidence manifests of `social/README.md`.

Scripts outside this repository are **not** normative. Where an earlier
throwaway reproducer under `/tmp` disagrees with the checked-in harness, the
checked-in harness wins; the same applies to any handoff report that predates
this bundle.

The harness is a spec oracle. Its green run is evidence that this specification
is internally consistent and that these fixtures reproduce. It is **not**
evidence about product Kotlin, Rust, MDK, Marmot or on-device behaviour, none of
which it executes, and it leaves all eight adapter gates of FEAT-062 §5 open.
The same checked-in fixture files are meant to be consumed unchanged by the
later native and on-device conformance tests; that consumption has not happened.

Changing an expected value to match implementation output without explaining
the byte-level cause is forbidden. Any mismatch blocks design-lock and release.
