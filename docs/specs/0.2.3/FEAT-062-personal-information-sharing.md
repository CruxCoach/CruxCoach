---
status: hands-on-preview
queue: active
implementation_status: PRODUCTION_READY_BEHIND_NATIVE_GATE
base: auto
depends_on: []
created: 2026-08-11
revised: 2026-08-17
review_round: goal-judge-round-1
upstream_repo: https://github.com/marmot-protocol/mdk
upstream_revision_checked: 3fc4eb83974eb64ecb298856b0db70cc3055af57
upstream_head_checked: 2a16d80f7fa94c57c19e1186a94b76ea7361de90
superseded_pin: 101d79946cff6c82d2b849d3b70902a7af7bac08
---

# Feature Spec: Personal Information Sharing and Social Relationships

> **Historical annexes:** [wire contract](../social/PRIVATE-SHARING-WIRE-V1.md) ·
> [threat model](../social/PRIVATE-SHARING-THREAT-MODEL.md) ·
> [golden vectors](../social/PRIVATE-SHARING-GOLDEN-VECTORS.md)
>
> Those three describe the **superseded** bilateral one-group-per-relationship
> design. They are kept as evidence of what was investigated and why it was
> dropped; where they disagree with this document, this document wins.
>
> The multi-device authority model used to be written *only* in the wire
> annex's §12, which is to say: the rules the shipped code enforces lived in a
> document this one declares superseded, unmaintained and under a standing
> No-Go. It is now specified here, in §11 and §12. The annex's §12 is history.

## 0. Status: hands-on preview, native transport gated shut

`IMPLEMENTATION_STATUS: PRODUCTION_READY_BEHIND_NATIVE_GATE`

### 0.0 What that status claims, and what it does not

It claims the **code** is finished for this slice and verified as far as this
environment can verify it: on 2026-08-17 the suite is **3 031** unit tests
(**1 109** in `:shared` across 84 classes, **1 922** in `:androidApp` across
189), counted out of the JUnit XML reports after a full run rather than from a
summary line. Every FEAT-062 test passes, on every run.

One test outside this feature is **flaky** and has to be said out loud rather
than averaged away: `BoardBrowserCatalogueRevisionTest > a chunk committed
mid-sync reaches the browse filter before the run ends` fails intermittently
under `--rerun-tasks` — observed failing once and passing on the next run of the
same commit, with the fixture timing out before its starting state
(`isLoading=true`, `activeBrandImporting=true`). No commit on this branch
touches `BoardBrowser`, so it is pre-existing rather than a regression here, and
it is not fixed by this branch. It does mean a green full run is not currently
reproducible on demand.

`lint` reports no error in either module,
and both `:androidApp:assembleDebug` and `:androidApp:assembleRelease` build.
No dependency, licence or build file changed anywhere in this feature, which is
what "no Marmot runtime dependency was added" means in practice.

It does **not** claim the feature is releasable, and three things stop it:

- **The native gate is shut**, on four of six conditions (§4.2), re-verified
  against upstream master on 2026-08-17 (§4.1).
- **Nothing has run on a device.** No emulator or handset was attached to this
  environment, so there is no instrumentation run, and the Keystore and
  secp256k1 bindings remain NOT TESTED (§4.3). No Amber prompt has been seen by
  anybody.
- **The release APK is signed with the Android debug certificate**, because no
  release keystore is configured here (`androidApp/build.gradle.kts` falls back
  to the debug signing config). It is a build artifact, not a distributable
  one.

`READY_FOR_LIVE_PRODUCTION` needs a green Marmot integration *and* a real
Android/Amber end-to-end run. Neither has happened.

What exists and runs: the domain model, the signed append-only permission
ledger and its fail-closed reduction, the owner-signed device manifest and the
multi-device authority model on top of it (§11–§12), the encrypted SQLCipher
projection, the key vault, the key-first local cleanup pipeline, and the German
Compose UI. All of it is hands-on explorable in a debug build, and all of it is
covered by tests.

What "delete" means here is worth stating up front, because the word invites the
wrong reading: removing a person deletes what **this device** holds about that
relationship. It does not reach the other person, and it deliberately leaves the
owner's own content keys and ciphertexts alone (§3.4).

What does **not** exist: any transmission to another person. The native
Marmot/MLS path is held shut by [`NativeSharingGate`](#4-the-native-gate), and
the only adapter that exists refuses every call. Nothing in this slice sends,
receives or synchronises anything.

This is a deliberate split. Every rule a user can set, and every consequence
those rules have, is real, stored encrypted and testable today. The part that
would carry them to another device is not, and the UI says so in as many words
rather than implying a sync that is not happening.

### 0.1 What changed against the previous revision

The previous revision was `design-review` / `queue: blocked` with a No-Go, and
it described a narrower model: one bilateral Marmot/MLS group per relationship
generation, mutual `ACQUAINTANCE`/`FRIEND` levels, per-peer/per-scope grants.

This revision replaces that product design with the one in §1: **three
visibility circles, five categories, group baselines plus per-person and
per-object exceptions, device-bound consent, and a recovery code**. The
transport question is separated from the product question, which is what makes
a useful preview possible while the transport stays blocked.

## 1. Product contract

### 1.1 Circles

Three circles, exclusive — a person is in exactly one:

    ALL_OTHER_USERS  ≤  ACQUAINTANCES  ≤  FRIENDS

Baselines inherit **monotonically**: a category granted to a wider circle is
granted to every narrower one. Inheritance is derived, never stored, so the
monotonicity is an invariant rather than something a writer has to remember.

The circle a person is filed under is the owner's private social judgement. It
is stored as a **local-only** ledger entry (`PeerCircleAssigned`) and is
excluded from anything that would leave the device. The recipient learns what
they may see; they never learn what they were called.

### 1.2 Categories

Five, closed in v1:

`PROFILE_AND_GOALS`, `TRAINING_HISTORY`, `VIDEOS`, `HEALTH_INFORMATION`,
`PRIVATE_NOTES`.

### 1.3 Precedence

Highest first:

1. an **object** exception — deny before allow;
2. an explicit **person** rule — deny before allow;
3. the **circle baseline**, inherited from wider circles;
4. otherwise **deny**.

The resolver returns the rule that decided, and the UI renders it. A padlock
with no reason is not an acceptable answer in a permission screen.

### 1.4 Consent

Nothing is released before the recipient explicitly accepts. Widening a grant
by a new category requires a **fresh, visible** consent — the new category sits
in `pendingConsentCategories` and stays closed until then. Narrowing or
withdrawing is immediate and local, and asks nobody.

A recipient can never consent to more than was offered.

### 1.4a One identity per person

A peer is identified by their Nostr public key as **lower-case hex**, because
that is what every signature check compares against.

The invite field accepts either a bech32 `npub1…` or that hex and converts at
the edge (`SharingPeerIdParser`). It has to: the field was previously labelled
"npub" and stored the raw input, while verification only ever accepted
64-character hex — so a peer invited with a real npub received a `PeerId` that
no acceptance and no device authorisation could ever verify against, and
inviting one person both ways produced two unrelated relationships.

Anything else is refused with a visible, localised reason and nothing is
written: an `nsec` (never stored, anywhere), a `note`/`nprofile`/`naddr`, hex of
the wrong length or alphabet, a broken bech32 checksum, or trailing rubbish.

The bech32 decoding is implemented here rather than delegated to the Nostr
library already on the classpath, because that library compiles its NIP-19
parser to a newer bytecode level than the unit-test JVM runs. Using it would
have left this path untestable — and an untested identity parser is exactly what
produced the defect. It is decode-only, checksum-verified, and covered by a
vector decoded independently of the code under test.

### 1.4b The offer follows the policy

An offer contains exactly the categories the owner policy already grants that
peer in that circle: a person allow, a baseline of that circle or a wider one,
or a single allowed object inside the category. A category whose only rule is a
deny is not offered — there would be nothing to consent to.

Every owner-policy change re-synchronises the affected relationships and, where
the result differs, appends an owner-signed `GrantChanged`. A baseline reaches
every relationship in that circle **or a narrower one**, because baselines
inherit downwards; a person or object rule, and moving someone to another
circle, reach only that peer. Terminal
relationships are left alone, and an offer that already matches appends nothing,
so a no-op does not grow the ledger.

This had to be fixed because the manual path was broken end to end: inviting
offered nothing (there is no category picker), and policy changes never touched
the relationship ledger. Consent is clamped to what was offered, so a
hand-invited peer could accept and still receive nothing, permanently, with
nothing on screen explaining why.

Worth being precise about the severity: **enforcement was never wrong.** The
resolver consults the owner policy first, so an offer that lags behind the
policy denies rather than over-shares. What was broken was the consent workflow
sitting on top of it, and a failed re-sync is likewise a degraded workflow
rather than a leak.

### 1.5 Devices

A relationship is bound to the recipient's npub. **Each device is authorised
separately and revoked separately.** A new device never gains access
automatically. A device revocation is a sticky tombstone: that device is never
re-authorised, whatever later entries claim.

### 1.6 States

`PENDING`, `ACCEPTED`, `DECLINED`, `DELIVERY_UNCLEAR`, `REVOKED`,
`PURGE_PENDING`, `PURGED`, `FAIL_CLOSED` — all visible, all labelled in German.

Offline, only accepted data of the locally valid resource epoch is readable. A
new or widened grant needs a confirmed key delivery; while that is unconfirmed
the relationship reads `DELIVERY_UNCLEAR` and releases nothing new.

### 1.7 What a withdrawal cannot do

Stated in the UI, not buried here: a withdrawal ends future access and destroys
the local keys and data. A copy the other person exported, photographed or
backed up elsewhere stays with them. Nobody can take that back.

## 2. The permission ledger

Signed, append-only, and the factual authority. Four coordinates make reduction
decidable without asking anybody:

| Coordinate | Purpose |
|---|---|
| `policySequence` | strictly increasing, gap-free per relationship — a missing entry is detectable, not invisible |
| `authorityGeneration` | entries from a superseded authority are ignored |
| `resourceEpoch` | rotated keys make older ciphertext unreadable as current |
| `deviceGeneration` | a restore mints a fresh one and inherits no device rights |

Reduction is **idempotent** and **order-independent**, so the same code serves a
live feed, a replay and a restored backup.

It is **fail-closed**. Each of these puts the relationship into `FAIL_CLOSED`,
which releases nothing:

- a signature that does not verify;
- a gap in the policy sequence;
- two different entries claiming one sequence;
- one entry id carrying two different bodies;
- a resource epoch moving backwards;
- an entry kind this build does not understand.

The last one matters more than it looks. The codec's decode side never throws
and never drops: an unknown kind, malformed JSON or a category name from a newer
build all decode to `Unknown`, which fails the relationship closed. Dropping the
entry instead would look like a shorter, *valid* history — the exact silent
fail-open this design exists to prevent.

`expiresAt` is carried and versioned in the model and deliberately not enforced
or shown in v1, so a later release can add it without a ledger migration.

### 2.1 Signing

Entries are signed over a canonical byte form covering every access-deciding
field, with the signature field excluded and category sets sorted by name — a
set built in a different order must not stop verifying.

Every field is **length-prefixed** (`<utf8ByteLength>:<field>,`). The first
version concatenated them, which was ambiguous and dangerously so: `id="e1",
peer="npub1alice"` produced exactly the bytes of `id="e1npub1", peer="alice"`,
and `sequence=1, authorityGeneration=23` the bytes of `sequence=12,
authorityGeneration=3`. A signature over one verified for the other. A
delimiter alone would not fix it — a field can contain the delimiter — so the
length prefix makes the split a property of the encoding rather than of the
data.

The curve is BIP-340 over secp256k1, the same primitive the rest of the Nostr
code uses. The nsec is never copied, and is never read: signing goes through
the account's `NostrSigner`, which is the same call for a local key and for an
external NIP-55 signer.

An identity that cannot sign produces **no entry**.

### 2.1a What is actually signed

An external signer does not sign arbitrary bytes — it signs Nostr events, and
it computes the event id itself. So a ledger signature is a real NIP-01 event
signature. The event is derived entirely from the canonical hash and the
signer's public key:

| Field | Value |
|---|---|
| `kind` | `24062` — ephemeral, app-specific |
| `created_at` | `1735689600`, a fixed protocol constant, not a clock reading |
| `tags` | `[["cc-purpose", "<domain>"]]` |
| `content` | the canonical SHA-256, lower-case hex |

`<domain>` is `cc.sharing.ledger.v2`, `cc.sharing.ownerpolicy.v1` or
`cc.sharing.backup.v1`, so a signature obtained for one ledger cannot be
replayed into another. Because every field is either constant or derived,
verification rebuilds the event id from what the entry already carries, and
only the 64-byte signature is stored — the format is unchanged.

The event is never published: nothing hands it to a relay.

The event a signer returns is checked field by field before it is believed,
**including the id**. The id is what the signature covers, so reproducing it is
what proves the signer and this app serialised the event identically; a
divergence fails closed at signing time instead of becoming a stored entry that
will never verify. The signature is then checked under BIP-340 against exactly
the id that was fixed before the request went out.

The NIP-01 serialisation is written out in `Nip01SigningEnvelope` rather than
taken from the Nostr library, whose bytecode level the unit-test JVM cannot
load. Leaving the exact bytes of an authorisation signature untested was the
worse trade; the runtime id check keeps it honest against upstream.

### 2.1b Signing takes time now

Approval happens in another app, when the person gets to it. Three consequences
are enforced rather than hoped for:

- **One mutation at a time.** A mutation reads the next policy sequence before
  signing and writes after; overlapping mutations would read the same one. The
  controller holds a lock for the whole of every mutation, signing included.
- **Sign everything, then write.** A change made of several entries — an
  invitation, a restore — signs all of them before storing any, so an abandoned
  approval leaves nothing behind rather than half a change.
- **Check before persisting.** The signing identity is read live, so an
  identity that changed while a prompt was open returns a valid signature by
  the *wrong* key. Every signed entry is verified against itself before it is
  stored.

Cancellation — a rotation, a killed process — propagates rather than being
read as a refusal, and writes nothing.

### 2.2 Who may say what

A verified signature proves only that the bytes were not altered; it says
nothing about authority. Every entry is therefore checked against the role its
body requires:

| Body | Required signer |
|---|---|
| `RecipientAccepted`, `RecipientDeclined` | the **peer's** npub |
| `DeviceAuthorized` | the **peer's** npub |
| `DeviceRevoked` | the **owner's** npub |
| everything else, including `PeerCircleAssigned` | the **owner's** npub |
| `Unknown` | nobody — fails closed |

Authorising a device is the peer proving that *this* device is theirs, so it
carries the peer's signature. If the owner could sign it, "every device is
authorised separately" would mean nothing more than the owner naming a device.
Revocation is deliberately **not** symmetric: withdrawing access is the owner's
and must not need the cooperation of the device losing it. A revoked device
stays revoked however often the peer signs it again.

Without that check the owner could sign the recipient's acceptance, which is
consent forged by the party it exists to constrain.

Two generation rules go with it:

- the **device generation** is minted by a `RestoreCompleted` and by nothing
  else. Its envelope and body must agree, it must strictly advance, and any
  other entry carrying a different value is stale or forged and fails closed;
- a **higher authority generation** does not inherit the previous consent,
  offers or devices. It resets them and has to be re-established explicitly.
  Device tombstones survive it, because a tombstone is not an artefact of the
  authority that issued it.

### 2.3 The owner's own policy ledger

Circle baselines and per-person/per-object exceptions decide who sees what, so
they are permissions rather than settings and live in their own signed
append-only ledger with the same discipline: stable ids, a gapless monotonic
sequence, owner-only signing, and fail-closed on a bad signature, a gap, a
duplicate id, two entries on one sequence, an unknown kind or a superseded
generation. A fail-closed policy grants **nothing** rather than falling back to
the last one believed.

They were previously written straight into mutable SQL rows: unsigned,
unordered, with no history, so anything able to write the database could widen
every grant and leave nothing to notice it by. `SharingPolicy` is now
reconstructed from the ledger, and the three rule tables are a cache rewritten
inside the same transaction as the append.

### 2.4 Admission: what may be written at all

The reducer fails closed on a ledger it cannot trust, but refusing to *read*
something is not refusing to *store* it. An entry persisted first and rejected
afterwards poisons the relationship permanently: every later reduction sees the
same bad entry and stays `FAIL_CLOSED`, and an append-only ledger cannot take it
back out. One unsigned or out-of-sequence inbound message would have been enough.

`SharingLedgerAdmission.check` therefore runs **before** any write and is
side-effect free by construction — it is handed the stored entries and the
candidate and returns a verdict. It requires, in order: the candidate belongs to
this relationship; its signature verifies; an id already present is
byte-identical (idempotent) rather than divergent; the sequence is exactly the
next one; and the ledger *with the candidate appended* reduces without failing
closed, which is what enforces the role, generation, epoch and unknown-kind
rules without restating them.

Two doors use it, and they fail differently on purpose:

- `tryAppendEntry` — for anything that did not originate here. A rejection
  returns `false` and leaves no row and no projection change.
- `appendEntry` — the local path, where the app built the entry itself. A
  rejection means a broken invariant in our own code, so it throws rather than
  hiding it.

A consequence worth stating: the write path requires the exact next sequence, so
backfilling a gap is not possible. The reducer stays order-independent; the
*store* does not accept holes.

### 2.4a A write either happens or is reported

Every mutating call returns a `SharingWriteResult`, and no screen shows success
or navigates away without one. This matters most where the action is
irreversible:

- **purge** records the request first and only destroys keys and deletes rows if
  that entry was signed and stored. A purge that ran without an entry would
  leave a history saying the relationship is active and no data to match it.
- **restore** signs an entry for every relationship *before* persisting any of
  them. There is no transaction spanning several peers here, so that is as close
  to all-or-none as this slice gets: the realistic failure — an identity that
  cannot sign — is found before the first write.
- **revoke, device revocation, circle change, policy and object changes,
  invitations** all report, and the screen says so in German.
- the debug peer simulations clear their input only when an entry was really
  appended.

### 2.4b The encrypted permission backup

A backup of wrapped keys alone would be worthless: the wrapping key lives in
the Android Keystore of the phone that was lost. So the vault has exactly one
door out — `exportForBackup` unwraps a data key, `importFromBackup` re-wraps it
under this device's own Keystore — and both zeroize the raw material.

The file layout is:

```
magic(9) | version(1) | ownerPubkeyHex(64) | salt(16) | nonce(12)
         | sigLen(2) | signature | payloadLen(4) | ciphertext
```

Everything after the header is one AES-256-GCM ciphertext holding the
relationship ledger, the signed owner-policy ledger, tombstones, sealed items,
key handles and the data keys. The key is HKDF-derived from the recovery code
with a random per-file salt and a domain-separating info string, so the same
code used anywhere else yields a different key and two backups never share one.
**The recovery code is never written into the file.**

Only the owner's public key is readable, and deliberately: a restore must be
able to say "this is not yours" before it decrypts anything. The file is also
signed with the owner's Nostr key over header and ciphertext, so possession of
the code alone cannot forge a backup for another identity.

Restore validates identity, signature and code, then puts every entry through
the same admission the live write path uses, before it writes anything.
Tombstones land first, so a purged peer cannot be resurrected by its own
pre-purge history sitting in the same file. It is idempotent — admission runs
against what is already stored — and it mints a fresh device generation
afterwards, leaving the install closed until revocations are re-checked. A
wrong code, a foreign identity, a bad signature, a tampered, truncated,
oversized or foreign file writes exactly nothing.

Export and import go through SAF (`CreateDocument`/`OpenDocument`), so the app
needs no storage permission and never guesses a path.

### 2.4c While a recovery preview is open, this install authors nothing

A restore whose freshness cannot be **proved** does not write the estate; it
opens a read-only preview and sets a lock. The reason is in §2.4b: a backup at
the same authority generation can still be missing every revocation made since
it was taken, because nothing has ever synced. So the estate on screen may be
more permissive than the real one, and a change authored against it would be
authored against a history nobody can vouch for.

**What the lock stops is local administrative authorship.** Every change this
install would author — a grant, a baseline, a per-peer or per-object rule, a
circle, an invitation, an offer, a revocation, the irreversible purge, the
recovery generation bump, a device enrolment, a role change, a device revocation,
and the genesis enrolment — is refused with `RECOVERY_LOCKED`.

The refusal happens **before anything is signed**, at the one entry every such
change goes through. That placement is normative, not an optimisation: a
permission change signs its ledger entry, and a policy change its policy entry,
*before* the act that authorises it exists, so a check further down the path
arrives after the person has already been sent to their signer. `restore` is the
clearest case — it signs a fresh device generation for every relationship first,
which on a real install was one external-signer prompt per peer for entries that
were then dropped. A refusal that costs a prompt teaches people the prompt is
noise, and with an external signer that prompt is a whole other app.

The write door checks again, immediately before storing. Signing can take as long
as the person takes to approve it, and a preview may have been opened in between.
All mutations are serialised under one lock (§2.1b), so the second read is about
that gap within a single call rather than about a concurrent caller.

It does **not** stop listening. A peer's own signed entry still lands, carried by
their signature and not by any authority of ours. Inbound evidence is how an
install catches up, and the only thing that could ever resolve a preview is
evidence — refusing it would make the lock permanent. Reading is untouched too:
the preview exists so the person can look.

The lock is released only inside the transaction of a root-authorised recovery
that actually succeeds. A preview, a refused or abandoned root signature, and an
ordinary device-paired batch all leave it exactly where it was.

### 2.5 Consent cannot be produced locally

Because an acceptance must carry the peer's signature, the owner's build cannot
manufacture one. The release path exposes only `ingestPeerSignedEntry`, which
refuses anything whose signer is not the peer; with the native transport gated
shut, nothing arrives through it yet, and the pending screen says it is waiting
for that signature rather than offering a button that would forge it.

The same holds for authorising a device: there is no owner-signed version, and
the release UI shows what it is waiting for instead of a button the owner has no
authority to press.

The debug demo peers are **real keypairs** derived from fixed seeds, so their
acceptances and device authorisations are real signatures the reducer accepts on
their own merits. No verifier is relaxed to make the demo work, and
`peerSimulator()` returns `null` unless `BuildConfig.DEBUG`.

There is no API that claims to produce a wire representation.
`nonTransmittableBodyPreview` is named for what it is: a body-only preview that
omits the id, sequence, generations, signer and signature, cannot be
transmitted, and exists solely to assert that local-only bodies such as the
circle assignment stay out of anything peer-facing.

## 3. Storage

Ten additive tables behind secure-database migrations 11 and 12, holding the
relationship ledger, the reduced per-peer projection, the owner-policy ledger,
the baseline/person/object caches, devices, wrapped keys, ciphertext-only sealed
items and tombstones. Migration 13 (§3.5) widens two `CHECK` constraints.

The multi-device authority model added ten more migrations. They are listed
here rather than left to be discovered, because several are table rebuilds and
what each one does to existing rows is a decision rather than a detail:

| Migration | What it does | Existing rows |
|---|---|---|
| 14 → 15 | `sharing_device_manifest_entry` — the owner-signed device manifest | none; an empty manifest grants no device any authority |
| 15 → 16 | `sharing_authority_attestation` — the device-signed record of every act | none; nothing is retroactively attributed to a device that never signed |
| 16 → 17 | `sharing_recovery_state` — the preview lock, seeded unlocked | a lock a restart forgets is not a lock |
| 17 → 18 | `sharing_device_identity` — this install's own sealed device key | none; the first launch mints one |
| 18 → 19 | rebuilds `sharing_ledger_entry` with a `parent_entry_id`; the `(peer, sequence)` index stops being unique | `parent_entry_id` `NULL` — their links were never recorded, and inventing a chain would assert a history nobody signed |
| 19 → 20 | unique index on `subject_entry_id`: one act per entry, enforced by the schema | duplicates removed, keeping the lowest id so the choice is deterministic |
| 20 → 21 | the same DAG rebuild for `sharing_owner_policy_entry` | `parent_entry_id` `NULL`, same call |
| 21 → 22 | `manifest_head_entry_id` on acts; rebuilds the manifest so siblings may share a sequence | `NULL`, which reads as "authored before the genesis" and fails closed |
| 22 → 23 | `manifest_context` on acts — the whole signed frontier (§12.1) | `NULL`, which parses to no context and fails closed |
| 23 → 24 | `sharing_recovery_attempt` — a recovery authorisation is spendable once | none |

The three rebuilds (18, 20, 21) copy every column across explicitly rather than
relying on `SELECT *`, and none of them carries a foreign key that another table
depends on, so the drop-and-rename is safe with foreign keys enforced.

The ledger is the stored authority; the projection row is **derived** and
recomputed from the reduced entries on every append. Losing the projection costs
nothing. This is what makes it retention-independent: nothing here expires with,
or is derived from, any transport's retention.

Two design points worth stating because they are easy to get wrong:

- **Foreign keys are enforced.** They were declared nowhere and off everywhere
  before; the cascades this projection relies on would have been decoration.
  They are enabled per connection in `onConfigure`, which runs **before**
  `onUpgrade` — so they are on while a migration runs, and that is worth being
  precise about rather than describing it the comfortable way round. It is safe
  here because no migration in this feature drops or rebuilds a table another
  one references: the three rebuilds are of child tables and of tables with no
  dependants, so the implicit delete a `DROP TABLE` performs fires no cascade
  into anything that matters.
- **Tombstones carry no foreign key,** on purpose. They must survive the cascade
  a purge triggers — a tombstone a purge deletes is not a tombstone.

### 3.1 Two bugs the tests caught

Recorded because both were invisible from the outside:

1. The projection row was written with `INSERT OR REPLACE`. REPLACE deletes the
   conflicting row first, and the ledger's `ON DELETE CASCADE` took the entire
   signed history with it on every rewrite. The projection still looked correct;
   only reading the ledger back revealed it. Now an explicit insert and update.
2. The ledger insert used `INSERT OR IGNORE`, which also swallows foreign-key
   and unique-sequence violations, turning a corrupt append into a silently
   shorter history. Now a plain `INSERT`, with duplicate ids filtered by the
   caller, so corruption is loud.

### 3.2 Key destruction is durable

The wrapping keys were derived from one root with HKDF, with destroyed handles
tracked in a `Set` in memory. A crypto erase therefore lasted exactly as long as
the process: after a restart the set was empty and the identical key came back
out of the identical root, so everything "erased" was readable again.

Each handle now has its own stored key under its own alias. `get` loads an
existing alias and never creates, `getOrCreate` mints a random key when the
alias is absent, and `destroy` removes it — so a later `getOrCreate` mints a
*different* key and ciphertext wrapped under the destroyed one stays unreadable.
Aliases are SHA-256 derived, so an object id containing path separators,
newlines or emoji cannot produce an unsafe keystore alias.

### 3.3 Whose key is it

There are two kinds of data-encryption key in this feature, and telling them
apart is what keeps a removal from destroying the wrong data.

| | Named by | Protects | A removal may destroy it |
|---|---|---|---|
| **Owner key** | `SharingKeyHandles.ownerCategory` / `ownerObject` | the owner's own content | **no** |
| **Recipient key** | `SharingKeyHandles.recipientCategory` / `recipientObject` | a copy wrapped for one person | yes, that person's |

The split is **structural**, carried by `KeyScope`:

| `KeyScope` | Whose | `key_id` |
|---|---|---|
| `CATEGORY` | owner | the category name, unprefixed — `VIDEOS` |
| `OBJECT` | owner | the object id as typed, unprefixed — `video-42` |
| `RECIPIENT_CATEGORY` | one peer | `rcpt.v1\|<len>:<peer>\|<len>:<category>\|` |
| `RECIPIENT_OBJECT` | one peer | `rcpt.v1\|<len>:<peer>\|<len>:<objectId>\|` |

The separator is the **ASCII vertical bar**, `U+007C`. It is written `\|` in the
table above only because a raw one would end the Markdown cell; nothing escapes
it in the stored value. Spelled out, with no Markdown in the way:

```
rcpt.v1|10:npub1alice|6:VIDEOS|
```

`sharing_wrapped_key` is keyed by `(key_scope, key_id, resource_epoch)` with no
peer column, so `key_scope` is what separates the two kinds in the database, and
its `CHECK` enumerates all four values.

A first attempt put the distinction in the id instead — an `own.v1` or `rcpt.v1`
prefix on both kinds — and that was not enough. `key_id` is free-form, so a row
carrying a syntactically genuine recipient id under an **owner** scope was
indistinguishable from a real recipient key: `belongsTo` read the prefix, said
yes, and a removal destroyed the owner's own data. Two doors led there — any row
written before the naming existed, and any handle built directly rather than
through the factory. A scope cannot be forged by the contents of its own row, so
that is where the answer belongs. Owner ids therefore carry no prefix at all;
they are spelled exactly as they always were.

Only the recipient `key_id` encodes anything, because it packs two fields — the
peer and the subject — into one column, and an object id is free text the owner
typed. It is fully length-prefixed for the same reason the canonical ledger form
is, and `belongsTo` parses that encoding **whole**: a truncation or an appended
suffix is not a name, so nothing can ride along on a real peer's id.

`belongsTo` asks for a recipient scope *first* and only then looks at the id, so
an owner key is never a peer's whatever its id spells.

**The factory is a convention, not an enforcement.** `SharingKeyHandles` is the
only sanctioned way to name a key, but `KeyHandle` is a public data class:
nothing stops a caller constructing `KeyHandle(KeyScope.RECIPIENT_CATEGORY, …)`
directly, and such a handle would be treated as a recipient key. What the scope
buys is that this now takes a deliberate choice rather than happening by
accident to any owner row whose id looked a certain way.

### 3.4 What removing a person deletes

Everything this device holds about that relationship: its signed ledger, its
projection row, its devices, and a sticky tombstone so it cannot return. It runs
through the crypto-erase pipeline keys-first, because the ordering is part of
the contract even where a step has nothing to do yet.

It does **not** touch the owner's content keys or ciphertexts, and it does not
end the other person's access.

This was wrong until recently, and dangerously so: the code destroyed every
category key plus the object keys the removed person had rules on. Those are the
owner's — `CATEGORY:VIDEOS` is the key *the owner's* videos are sealed under —
so removing Alice destroyed the owner's videos and Bob's access to the same
category along with them. `SharingPurgeScopeTest` now holds that shut with two
relationships sharing one category and one object.

**Recipient-side crypto erase is not live.** Ending a recipient's cryptographic
access means destroying the copy wrapped *for* them, and no live path has ever
created one — the transport that would deliver it is gated shut (§4). The naming
and the scoping are in place and tested, so the operation is expressible and
correct when the gate opens; today there is simply nothing of that kind on the
device to destroy. Product-contract item 10 is therefore **partially**
implemented: the local cleanup pipeline, its ordering, the sticky tombstones and
the honest UI statement about external copies all exist; the cryptographic half
that reaches the recipient does not.

### 3.5 Migration 13 → 14

`key_scope` admitted only `CATEGORY` and `OBJECT`. Adding the two recipient
values means widening a `CHECK`, and SQLite cannot alter one in place, so
`13.sqm` rebuilds `sharing_wrapped_key` and `sharing_sealed_item`: create the
new table, copy every row, drop the old, rename.

A rebuild that loses or reshuffles rows would surface much later as wrapped keys
that no longer open anything, so it is tested rather than assumed.
`SharingProjectionSchemaTest` recreates the genuine version-13 shape — both
tables with the old `CHECK` — seeds owner rows carrying distinct blob bytes,
ids, `created_at` values and *two resource epochs of one key*, runs
`SecureDatabase.Schema.migrate(driver, 13, 14)`, and then asserts:

- every owner row survives **byte for byte**, blobs compared with `hex()`, so a
  copy that collapsed the two epochs or mixed up rows cannot pass on a count;
- `RECIPIENT_CATEGORY` and `RECIPIENT_OBJECT` rows, which version 13 refused,
  now insert into both tables while the owner rows stay;
- an unknown scope is still refused — widening the whitelist did not remove it.

Each of those three was checked against a deliberately broken migration (one
that does nothing, one that rebuilds without copying, one whose `CHECK` admits
anything) and each failed the assertion it exists for.

Every row an installed database holds is an owner key by definition — nothing
has ever written a recipient key, because the transport that would deliver one
is gated shut (§4) — so the copy needs no mapping.

## 4. The native gate

The live path is off unless **every** condition is evidenced green. Evidence is
data (`NativeUpstreamEvidence`), not a comment, so the reason the gate is shut
is rendered in the UI and asserted by tests.

### 4.1 What was reproduced

Against `https://github.com/marmot-protocol/mdk` on 2026-08-16, and re-checked
on **2026-08-17** against master head `2a16d80f7fa94c57c19e1186a94b76ea7361de90`
(committed 2026-08-16). Nothing that would open a gate has changed:

| Re-checked | Result |
|---|---|
| Repo identity | still `marmot-protocol/mdk`, id `1055628515` |
| Newest release | still `marmotkit-v0.9.12` (2026-08-13); the only later tag is an iOS snapshot |
| Android artifact shape | still `marmotkit-android-0.9.12.zip`, a release ZIP of raw `jniLibs` — no Maven coordinate, so `integratedViaDependencyStrategy` stays shut |
| `resume_outbound_fanouts` | still emits no `PublishedApplicationMessage` in `crates/marmot-account/src/runtime.rs`, so `outboundResumeCorrelationFixed` stays shut |

The 2026-08-16 findings, unchanged:

| Check | Result | How |
|---|---|---|
| Official upstream | `marmot-protocol/mdk` | `parres-hq/mdk` redirects to it; same repo id `1055628515` |
| Old pin exists and is an ancestor of master | yes, 50 commits behind | `git merge-base --is-ancestor` |
| **Cross-seam convergence fixed** | **yes** | `ForkRecovered` and the pairwise fork-recovery route are gone from `crates/cgka-engine`; upstream's own `AGENTS.md` records "fork-resolution route unification … `ForkRecoveryManager` … `GroupEvent::ForkRecovered` is deleted", landed as #1293 |
| Fix is in a published release | yes | #1293 is an ancestor of tag `v0.9.12` (`3fc4eb8`) |
| Published Android artifact | verified | `marmotkit-android-0.9.12.zip` SHA-256 `d8ce6758…c22d3` matches its published `.sha256`; manifest names source `3fc4eb83…`, rustc 1.97.1, NDK 27.2, API 26; MIT licensed |

So the specific defect the previous revision blocked on — the two resolution
seams settling on different canonical branches for one fork and staying there —
**is fixed upstream**. That is recorded as `crossSeamConvergenceUnified = true`
rather than quietly left out, because an honest gate has to record the good news
as well as the bad.

### 4.2 Why the gate is still shut

| Condition | State | Why |
|---|---|---|
| `crossSeamConvergenceUnified` | ✅ | see above |
| `publishedArtifactChecksumVerified` | ✅ | see above |
| `integratedViaDependencyStrategy` | ❌ | the artifact is a GitHub release ZIP of raw `jniLibs` (~51 MB for arm64 alone, ~194 MB across four ABIs), not a Maven coordinate the version catalogue can consume. Consuming it means vendoring binaries into the repository or adding a bespoke download-and-verify build step — neither is this project's dependency strategy |
| `onDeviceInstrumentationRun` | ❌ | no instrumentation run on an Android arm64 device has been executed here |
| `durableProjectionGateClosed` | ❌ | untouched upstream; the retention-independent inbox and host-ACK rail do not exist |
| `outboundResumeCorrelationFixed` | ❌ | `resume_outbound_fanouts` in `crates/marmot-account/src/runtime.rs` still emits no `PublishedApplicationMessage`, so a send whose first accepted delivery lands during resume cannot be correlated. On a phone, "killed while sending" is the ordinary case |

Per the working rule: not reproducibly safe ⇒ **no private fork, no native live
release**. No Marmot/MDK/Dominion/Concord runtime dependency was added; upstream
is design reference and evidence only.

### 4.3 NOT TESTED

- The real secp256k1 signing path (`Nip01LedgerCrypto`). The native library has
  no JVM implementation, so unit tests drive the signing *protocol* through a
  deterministic `LedgerCrypto` stand-in. The curve itself is upstream's.
- `KeystoreWrappingKeyStore`. The Android Keystore has no JVM implementation.
  The per-alias lifecycle it implements — including destruction surviving a
  simulated restart, and a re-minted key differing from the destroyed one — is
  tested against `InMemoryKeyAliasBackend`, which has the same contract.
  **The production Keystore binding itself is unverified in this slice.**
- The demo peers' real BIP-340 keypairs. Deriving and signing with them needs
  the native secp256k1 library, so on a JVM the demo simply does nothing
  (`demoPeers` is `null`) — which is the fail-closed answer, not a fake key.
  The demo has therefore only been exercised through a deterministic stand-in
  crypto in tests, never with the real curve.
- Anything on a real device. No instrumentation run happened, and none could:
  `adb devices` lists nothing and no emulator is installed in this environment.
  Every "on device" claim anywhere in this document would be a simulation
  reported as a device run, so there are none.
- Restore from an actual backup file. The restore *path* (recovery code, new
  device generation, fail-closed until revoke sync) is tested; reading a real
  backup blob is not implemented in this slice.

## 5. Threat model delta

The [historical threat model](../social/PRIVATE-SHARING-THREAT-MODEL.md) assumed
a live MLS transport. With the transport gated shut, this slice's exposure is
local, and the honest statements are:

- **On-device data at rest** is protected by SQLCipher plus per-category and
  per-object AES-256-GCM data keys, wrapped under Keystore-derived wrapping
  keys. The Keystore binding itself is NOT TESTED here (§4.3).
- **Crypto erase** destroys keys first, then rows, then the derived local
  surfaces. A cleanup killed part-way leaves ciphertext nobody holds a key for.
- **Ciphertext relocation** is prevented by feeding the key handle in as
  associated data: a payload cannot be replayed into another category or object
  even by someone holding the right key.
- **Secrets in logs** — secret-bearing types redact `toString`; the AEAD failure
  path throws a deliberately vague message rather than echoing key or plaintext.
- **What is not defended**: a compromised unlocked device, an OS-level attacker,
  and anything the recipient has already copied out. The relay, NIP-09 and raw
  retention are never treated as a security authority — they cannot be, and this
  slice does not use them at all.

## 6. Decision matrix

| Question | Decision | Why |
|---|---|---|
| Native live sharing now? | **No** | §4.2 — three gates open |
| Private MDK fork? | **No** | explicitly ruled out; not reproducibly safe |
| Add MDK as a dependency? | **No** | no Maven artifact; ~51 MB per ABI; design reference only |
| Ship the product model anyway? | **Yes** | it is independently valuable and fully testable without a transport |
| Circle visible to recipient? | **No** | local-only ledger entry, filtered from export |
| Unknown ledger entry kind? | **Fail closed** | a dropped entry is a silently shorter valid history |
| Enforce `expiresAt` in v1? | **No** | carried and versioned in the model, not in the UI |
| Enable `PRAGMA foreign_keys`? | **Yes** | the cascades are load-bearing; enabled after migrations run |
| Demo data in release? | **Never** | `BuildConfig.DEBUG` only, seeded through the ordinary signed-entry API |

## 7. Hands-on guide

Debug build, no device pairing, no network:

```bash
./gradlew :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Then, in the app:

1. **Settings → Persönliche Daten teilen.** The red banner at the top says
   *"Vorschau – Live-Synchronisierung gesperrt"* and lists, in plain German, the
   reasons from §4.2 plus the upstream revision that was checked.
2. **Demo-Daten laden** (debug only). Creates Anna, Ben and Carla.
3. **Invite by hand.** Paste an `npub1…` or a 64-character public key into
   *Person einladen*, pick a circle, and press *Person einladen*. A private key,
   a `note`/`nprofile`, a broken checksum or rubbish is refused with a reason and
   nothing is written; the field clears only when the entry is really stored.
   The new person is offered exactly what that circle already grants.
4. **Change someone's circle.** Open a person and pick another circle at the
   top. What they are offered follows immediately — widening waits for a fresh
   consent, narrowing takes effect at once.
5. **Add an individual exception.** On the person's screen under *Einzelne
   Ausnahmen*, type an object id, pick a category and *Erlauben* or *Sperren*,
   then *Hinzufügen*. A blank id is refused before anything is written, and each
   entry has its own *Kreis-Einstellung verwenden* button to remove it again.
6. **Kreise.** Toggle a category on *Alle anderen Nutzer* and watch it appear —
   greyed out and labelled as inherited — on *Bekannte* and *Freunde*. The
   inherited switch is disabled: it belongs to the wider circle.
7. **Anna** (accepted friend). Every category says released or not released
   *and why*. One video is blocked individually under *Einzelne Ausnahmen*.
   Set *Videos* to **Sperren** and the reason changes to
   "Für diese Person gesperrt".
8. **Ben** (invited, not answered). Everything reads
   "Wartet auf Zustimmung der Person" — an offer alone releases nothing.
9. **Carla** (grant widened). *Gesundheitsdaten* reads "Neue Kategorie – braucht
   erneut Zustimmung". Press *Erneut um Zustimmung bitten* and it flips to
   released. Narrow the grant instead and it closes immediately, with no consent
   step — restriction is one-sided by design.
10. **Geräte.** Revoke one of Anna's devices; it is listed as
   "Gesperrt – kann nicht erneut freigegeben werden" and stays that way.
11. **Widerruf / Lokal löschen.** Withdrawing shows the revoked state.
   *Lokal löschen* asks first — a dialog that names exactly what goes (this
   device's record of the relationship), what stays (your own content and keys,
   and every other relationship) and that it cannot be undone or reach copies
   the other person already has. Cancelling does nothing at all. The honest note
   about exported copies also sits under the buttons.
12. **Verschlüsselte Sicherung.** Unter *Wiederherstellungs-Code* einen Code
    erzeugen, dann *Sicherung speichern…* — der Systemdialog fragt, wohin. Die
    Datei enthält nichts Lesbares. Zum Zurückspielen den Code eintippen und
    *Aus Datei wiederherstellen…* wählen. Ein falscher Code, eine fremde
    Identität oder eine veränderte Datei ändert nichts und sagt warum.
13. **Alleinige Kontrolle übernehmen.** The code-only door, and it is not a
   restore — the screen says so above the button. Generate a code, type it back,
   and press it: the owner's key is asked to authorise the takeover (with Amber,
   a prompt), the authority generation moves on, this device is re-enrolled as
   the only `PRIMARY`, and every other device of yours is listed as fenced with
   an empty capability list. Nothing comes back from before, because there is no
   file in this path. Declining the prompt changes nothing at all.
   While a backup is being signed, *Neuen Code erzeugen* is disabled — the file
   is sealed with the code that was on screen when the export began, and
   replacing it mid-flight would leave a file only an unshown code could open.

With TalkBack on, each category row is announced as one sentence — category,
released or not, and the reason — rather than three disconnected fragments.


### 7.1 Trying it with Amber

The permission ledgers are signed through the account's signer, so this works
the same way with an external NIP-55 signer.

1. Install Amber and switch CruxCoach to it in the Nostr identity settings.
2. Make any permission change — toggle a circle baseline, invite somebody,
   change a person rule.
3. The screen shows **"Wird signiert …"** and every control that would start
   another change is switched off. Amber comes to the foreground and asks to
   sign an event of kind `24062`.
4. Approving it writes the entry. Declining it, or leaving the prompt and
   coming back, writes nothing and the screen says so — the change can simply
   be made again.

Amber's **"remember my choice"** for this app makes the following changes
silent, which is what makes bulk editing bearable. Without it every single
permission change is one prompt, and an invitation is **two** — the private
circle assignment and the offer are separate entries. Both are signed before
either is stored, so declining the second leaves nothing behind.

Backing up (§8) is one further signature, because the envelope is bound to the
same identity.

If Amber is uninstalled while it is the selected signer, the app falls back to
the local key. That key is a different identity, so signatures it produces do
not match the entries being written and are refused before anything is stored.
The result is a visible failure, not a silently forked ledger.

## 8. Test coverage

Behaviour-level, all written before the code they cover:

| Area | Tests |
|---|---|
| Circles, categories, precedence | `SharingPolicyResolverTest` |
| Ledger reduction, fail-closed, tombstones, generations | `SharingLedgerReducerTest` |
| Consent, devices, epochs, revoke, offline | `EffectiveAccessResolverTest` |
| Storage round trip, canonical form, unknown kinds | `SharingLedgerCodecTest` |
| Canonical bytes and signature protocol | `SharingLedgerSigningTest` |
| Recovery code shape and checksum | `SharingRecoveryCodeTest` |
| DEKs, AEAD, relocation, key-first erase, redaction | `AeadSharingKeyVaultTest`, `CryptoErasePipelineTest` |
| Gate evaluation and refusing adapter | `NativeSharingGateTest`, `BlockedNativeSharingAdapterTest` |
| Schema, FK enforcement, cascade, migrations 11–13 incl. byte-exact rebuild | `SharingProjectionSchemaTest` |
| Repository, purge, restart durability | `SecureDbSharingRepositoryTest` |
| Screen state, sources, actions, circle privacy | `SharingControllerTest` |
| Signer roles, device and authority generations | `SharingLedgerAuthorityTest` |
| Admission before any write | `SharingLedgerAdmissionTest` |
| bech32 decoding and checksum rejection | `Bech32Test` |
| Offer follows policy, sync rules, no churn | `SharingOfferSyncTest` |
| Owner-policy admission before any write | `OwnerPolicyAdmissionTest` |
| Backup envelope, byte inspection, fail-closed | `SharingBackupEnvelopeTest` |
| Vault key export, re-wrap, zeroize | `VaultBackupExportTest` |
| Backup round trip through a real file | `SharingBackupRoundTripTest` |
| Nothing acts without a signed append | `SharingOfferSyncTest` |
| npub/hex identity parsing and refusals | `SharingPeerIdParserTest` |
| Owner-policy reduction and fail-closed rules | `OwnerPolicyLedgerTest` |
| Owner-policy storage, canonical form, signing | `OwnerPolicyCodecTest` |
| Durable key destruction across a restart | `AliasedWrappingKeyStoreTest` |
| Signed-append-only writes, forged consent refused | `SharingWriteAuthorityTest` |
| Labels for every domain state | `SharingLabelsTest` |
| Accessibility semantics, gate and signing banner wording | `SharingScreenSemanticsTest` |
| NIP-01 event preimage, domain separation, escaping | `Nip01SigningEnvelopeTest` |
| Event id against an independently computed digest | `Nip01SigningEnvelopeDigestTest` |
| Async signing seam, cancellation, sync bridge | `AsyncLedgerSigningTest` |
| What is asked of a signer and what is refused back | `Nip55LedgerCryptoTest` |
| Serialised sequences, no partial writes, wrong key | `SharingExternalSignerTest` |
| Signing state, exactly-once, retry, lifecycle restart | `SharingSigningStateTest` |
| Removal destroys only that person's keys, never the owner's | `SharingPurgeScopeTest` |
| Delete asks first: open, cancel, confirm, spoken warning | `SharingPurgeConfirmDialogTest` |
| A recovery preview refuses every local write, and asks no signer for any of them (§2.4c) | `SharingPreviewLockTest` |
| No key-shaped literal and no raw NUL byte in the sources | `SharingSourceHygieneTest` |
| A failed removal leaves nothing behind and can be asked for again | `SharingPurgeFailureTest` |
| The same, on the path a person takes: a permanent `PurgeRequested`, then a retry that completes | `SharingPurgeRetryTest` |
| The decode diagnostic carries nothing the backup file chose | `SharingBackupEnvelopeDiagnosticTest` |
| The screen's recovery action really is the root-signed one, and reports, and cannot rotate a code mid-export | `SharingRecoveryRoutingTest` |

### 8.1 Multi-device authority (§11–§12)

| Area | Tests |
|---|---|
| Roles, capabilities, rank, and what a revoked device holds | `DeviceAuthorityResolverTest` |
| The one conflict function: generation, restrictive-first, rank, ids | `DeviceAuthorityResolverTest`, `AuthorityInvariantsTest` |
| The identical winner across live, replay, restore and both reducers | `AuthorityInvariantsTest` |
| Manifest reduction, fail-closed rules, stickiness across generations | `DeviceManifestReducerTest` |
| Manifest canonical bytes, codec, no-parent sentinel | `DeviceManifestCodecTest` |
| Manifest admission before any write | `DeviceManifestAdmissionTest` |
| Manifest forks, per-scope settlement, merge of independent scopes | `AuthorityBranchTest`, `OwnerPolicyBranchTest` |
| The frontier: canonical form, strict parse, antichain, forged contexts | `ManifestContextTest` |
| Acts: canonical form, signing, capability claims | `AuthorityAttestationTest`, `AuthorityScopeTest` |
| What a signed body requires of the act authorising it | `AuthorityPairingTest` |
| Acts re-checked on every read; an unreadable estate decides nothing | `AuthorityTrustTest` |
| A malformed DAG decides nothing | `AuthorityDagValidationTest` |
| The rank is the role held where it signed, not the role held now | `AuthorityHistoricalRoleTest` |
| Recovery gate, freshness, plan, fencing, epochs | `AuthorityRecoveryTest` |
| The root challenge, its nonce, and spending an attempt once | `RootRecoveryAuthorizationTest` |
| Manifest and acts on disk, across a restart | `SecureDbDeviceManifestTest`, `SecureDbAuthorityAttestationTest` |
| This install's own sealed device identity | `SecureDbDeviceIdentityTest` |
| Genesis: the one root-only enrolment, and refusing a second | `SharingGenesisTest` |
| Enrol, re-role, revoke, and what TRUSTED may not do | `SharingDeviceAdministrationTest`, `SharingControllerAuthorityTest` |
| A whole user action is one write or no write | `SharingCompositeMutationTest` |
| Restore and import end to end, stale backups, fencing, reset | `SharingRecoveryFlowTest` |
| The recovery core is not reachable as a public entry point | `SharingRecoveryApiSurfaceTest` |
| A preview refuses every write door, including the two manifest ones | `SharingPreviewLockTest`, `SharingPreviewLockWriteDoorTest` |
| Getting out of a preview from where the person already is | `SharingPreviewRetryTest` |
| Device list, roles, fenced and revoked wording, accessibility | `SharingDeviceScreenSemanticsTest` |
| Demo peers are debug-only and are real keypairs | `SharingDemoDataTest` |

## 9. Known limitations

See also §11.9 for the limits of the multi-device authority model.

- No transmission of any kind. This is a preview of the rules, not of sharing.
- **Recipient-side crypto erase is not implemented** (§3.4). Removing a person
  deletes this device's record of the relationship; it does not destroy a key
  held by them, because no key has ever been wrapped for them. Contract item 10
  is partially met and is described that way rather than ticked off.
- Until this round, removing one person destroyed the owner's category and
  object keys and their sealed items, taking the owner's own data and every
  other relationship's access with them. Anyone who ran *Lokal löschen* on a
  debug build before this fix has lost that data; there is no recovery for it
  beyond restoring a backup taken earlier.
- The Keystore and secp256k1 bindings are NOT TESTED here (§4.3).
- `SharingDemoData.displayName` is the only naming source; there is no contact
  or profile lookup in this slice.
- The historical annexes under `docs/specs/social/` describe the superseded
  bilateral design and are not maintained against this document.
- `awaitingRevokeSync` is set by a restore and never cleared: there is no
  revoke-resync path in this slice, so a restored install stays closed. That is
  deliberate and fail-closed, but it does mean restore is a one-way door here.
- The Amber path itself is **NOT TESTED on a device**. Everything this app
  decides — what is asked for, what is accepted back, what is refused — is
  covered by unit tests, but the two calls into the Nostr library
  (`QuartzNip01EventSigner`, `QuartzBip340Verifier`) cannot run on a unit-test
  JVM and there is no instrumentation target in this environment. They are
  pure delegation, and the runtime id check means a mismatch fails closed, but
  nobody has yet watched an Amber prompt appear.
- Changing the signature scheme to a NIP-01 event **invalidates every entry
  signed by an earlier build of this branch**. The feature has never shipped
  and is gated shut, so there is no production data; a debug install with demo
  data must be re-seeded rather than migrated. There is deliberately no
  fallback that accepts the old form, because a verifier that accepts two
  schemes is a verifier that accepts the weaker one.
- Because admission requires the exact next sequence, an entry that arrives out
  of order is refused rather than held for later. With no transport this cannot
  occur in practice, but a real inbound path would need a holding area first.
- There is no *category* picker on the invite screen: an invitation offers
  whatever the chosen circle already grants. The circle itself is now chosen
  when inviting and can be changed on the person's detail screen.
- If the owner policy is written but the offer re-sync cannot be signed, the
  screen reports the failure and the offers stay as they were. That is
  fail-closed for enforcement, but the two ledgers are briefly out of step
  until the next successful change.
- The database column holding a peer identity is still called `peer_npub`,
  which predates the canonicalisation; it holds public-key hex. Renaming it
  would need a migration for no security gain, so the name is wrong and the
  contents are right.
- Restore is transactional per database and validated before any write, but
  there is no transaction spanning the several stages (rows, then key re-wrap,
  then the RestoreCompleted entries). A crash between them leaves a restored
  state that has not yet minted its new device generation; a repeated restore
  is idempotent and completes it.

## 10. Signing that leaves the process

A signpost, not a second set of rules. The KDoc across this feature was written
against the section numbering this document used before the 2026-08-16 rewrite,
and cites `FEAT-062 §10` for everything about external signing. The rules
themselves are in **§2.1a** (what is actually signed — the NIP-01 event, its
fixed fields, and the id check that proves the signer and this app serialised it
identically) and **§2.1b** (one mutation at a time, sign everything then write,
check the identity before persisting, and cancellation propagating rather than
reading as a refusal).

Two things belong here because they are stated nowhere else:

- **The wait is bounded.** `Nip55LedgerCrypto` gives an approval two minutes
  (`DEFAULT_APPROVAL_TIMEOUT_MILLIS`) before giving up. Nothing about IPC to
  another app guarantees an answer — the signer can be killed, suspended, or
  left behind a lock screen — and this call holds the controller's mutation lock
  and the screen's signing state, so an unbounded wait is not fail-closed but
  fail-stuck: one wedged request would block every later permission change too.
  An expiry is reported as `SIGNER_UNAVAILABLE` and the change can be made
  again. The timeout's own cancellation is swallowed; an *outer* cancellation —
  the caller going away — still propagates, so no caller writes anything.
- **One user action is one write, or it is no write.** `commitAll` takes the
  whole batch a single action produced, and a rejection anywhere rolls back all
  of it (§2.4a).

## 11. Multi-device authority

The relationship ledger answers "may this peer see this". This section answers
the question underneath it: **may this device of mine say so at all**, and if
two of my devices said different things without seeing each other, **which is
true**. Both have to be answerable from signed evidence alone, identically on
every device, in live reduction, in replay and after a restore.

### 11.1 The root is an npub, and every device has its own key

There is no main `nsec` in CruxCoach and never has been. The owner's Nostr
identity is the root authority, reached through the account's signer — a local
key or an external NIP-55 signer, the same call either way (§2.1).

Every install additionally mints a **device key of its own**, kept sealed
(`sharing_device_identity`, migration 17). The manifest records the public half.
The root signature says the owner asked for a change; the device signature says
which of the owner's devices asked. Both are required, and they are checked in
different places for different reasons — see §11.4.

### 11.2 The manifest is the root of device authority

`sharing_device_manifest_entry` is owner-signed and append-only. Nothing else in
the feature may decide that a device exists, what role it holds, or which
authority generation is current: those are read off the reduced manifest. A rank
that could be written onto an event would be a claim the event makes about
itself; read off the manifest it is a fact.

Each entry carries a stable id, a `manifestSequence` that is its parent's plus
one, the `authorityGeneration` it was authored under, the `parent` it follows
(`null` only for the genesis), the owner's npub and signature, and one body:

| Body | What it does |
|---|---|
| `DeviceEnrolled` | adds a device, or re-enrols one after a rotation, under a role |
| `DeviceRoleChanged` | moves an existing device between roles |
| `DeviceRevoked` | withdraws a device for good; sticky |
| `AuthorityRotated` | establishes a new generation, fencing what came before |
| `SovereignReset` | revokes and fences the whole estate and mints a fresh authority |
| `Unknown` | a body this build cannot read — fails the manifest closed |

The reduction is fail-closed on every count it cannot settle from evidence: an
entry signed by somebody other than the owner, a signature that does not verify,
a second root (two histories in one table, not a fork), a sequence that does not
follow its parent, a parent that is not stored, a cycle, a generation that does
not advance, a re-role or revocation of a device that was never enrolled, and an
unreadable body. A failed manifest grants **nothing to anybody** — not even to
devices whose own entries were fine. That is severe on purpose: once the chain
is broken there is no evidence-based way to choose which half to trust, and
choosing wrongly means a device the owner revoked keeps its authority.

Two stickiness rules that are easy to get backwards:

- **Revocation is sticky within a generation.** Withdrawing trust from a device
  that may be lost or seized must not be undoable by a later entry, or
  revocation is only advice.
- **It is not sticky across one.** A sovereign reset revokes everything
  *including the device performing it*, so a rule that held across generations
  would leave an estate nobody could ever act in again.

A **fenced** device keeps its row — so the screen can say why it stopped
working — and carries no authority until it is deliberately re-enrolled.
Fencing is a recorded decision rather than something inferred from
`enrolledInGeneration < currentGeneration`, because authority rotates for
reasons that re-affirm every device present, and inferring it there would fence
the whole estate the instant the generation moved.

### 11.3 Roles and capabilities

Four roles, ordered most to least authority. The capability set is **derived**
from the role, never carried on an event:

| Role | `READ` | `MUTATE_PERMISSIONS` | `ADMINISTER_DEVICES` | `SOVEREIGN_RESET` |
|---|:--:|:--:|:--:|:--:|
| `PRIMARY` | ✅ | ✅ | ✅ | ✅ |
| `TRUSTED` | ✅ | ✅ | ❌ | ❌ |
| `READ_ONLY` | ✅ | ❌ | ❌ | ❌ |
| `REVOKED` | ❌ | ❌ | ❌ | ❌ |

Read off that table:

- **PRIMARY** sets permission policy, enrols and revokes devices, advances the
  generation, and performs backup and recovery.
- **TRUSTED** makes ordinary permission changes within the manifest's
  capabilities. It cannot enrol, re-role or revoke *any* device — which is what
  stops it removing the primary — and it cannot advance the generation, because
  both need `ADMINISTER_DEVICES`.
- **READ_ONLY** reads the reduced state and writes nothing: every mutation needs
  at least `MUTATE_PERMISSIONS`.
- **REVOKED** holds nothing. `roleOf` returns `null` for a revoked or fenced
  device, so its acts are not merely outranked — they are dropped before
  ordering, and admission refuses them outright. A revoked device's events are
  never accepted, however often they are re-signed.

### 11.4 Every administrative act is signed evidence

One `AuthorityAttestation` per administrative change, signed by the authoring
device's own key. It carries a stable id, the `scope` it applies to, the
`subject` entry it authorises, the `device`, the `parent` act it was built on,
the `manifestContext` it was authored against (§12.1), the `authorityGeneration`,
the `capability` the change needs and its `effect`.

There is deliberately **no timestamp, no weight, no priority and no
client-chosen sequence number**. Every field is either signed content or looked
up in the manifest, so a device cannot make itself win by asserting that it
should.

`capability`, `scope` and `effect` are what the act *claims*; what the change
actually requires is derived from the **signed body of the entry being
authorised** (`AuthorityPairing`), and the act has to match. Without that, a
`READ_ONLY` device could write `capability = READ` — which its role genuinely
allows — and attach the act to a grant.

A body the **peer** authored — their acceptance, their refusal, their own device
— needs no owner-device act at all. It is carried by the peer's signature, and
demanding an owner act for it would mean the owner authorising somebody else's
consent (§2.2).

**Acts are re-checked on every read**, not trusted because admission checked
them once. The row lives in a file, and a file can be written by a restored
backup, a rooted device or a bug in our own code. Every signature is re-verified
against the key the *manifest* binds to that device — never one carried on the
act, which would let an attacker supply both halves of the check. Nothing can
reach a resolver holding acts it has not had checked, because the only way to
obtain a `TrustedAttestations` is to produce one from a verifier and a manifest.

### 11.5 Admission before persistence

Refusing to *read* something is not refusing to *store* it, and an append-only
ledger cannot take a bad row back out. So every check happens **before** the
write, side-effect free by construction: given the stored acts, a candidate and
the reduced manifest, admission returns a verdict and writes nothing.

In order: the manifest reduces at all; the act binds exactly the required
manifest context; the scope is the current, unambiguous encoding; the device
held a role at that context; the role carries the required capability; the
generation is exactly the one that context establishes; the signature verifies
under the manifest's key for that device; a re-used id is byte-identical
(idempotent) rather than divergent; no other act already authorises that
subject; and the parent, if any, is an act already stored — but **not**
necessarily the head, because two devices building on one parent is the ordinary
concurrent case and both are kept.

The same discipline applies to the entries themselves (§2.4) and to the manifest
(`DeviceManifestAdmission`). One act per subject is enforced by the schema as
well as by admission, because admission reads and then writes and two of them
interleaving would both see nothing (migration 19).

### 11.6 One resolver, every seam

`DeviceAuthorityResolver` is the single deterministic rule for "which change
stands", used unchanged by live reduction, by replay, by restore and by both
projections. One function, so the seams cannot drift apart — a divergence there
would mean two devices disagreeing about who can see what, with nothing to
detect it.

Anything the manifest does not vouch for is **dropped before ordering**: an
unknown device, a fenced or revoked one, or a generation the manifest never
established. Dropping rather than ranking matters — an unknown device claiming a
restrictive effect could otherwise impose a denial on everybody by inventing an
id.

Causality is settled **first, by removing superseded events**, not inside the
comparison. An event whose descendant is also present was seen and built upon,
so it is not in conflict with anything: it is simply older. Doing that as a
filter rather than as a comparison step is what keeps the comparison a total
order — ancestry is partial, and a partial relation mixed into a comparator
produces intransitive results, which would let two devices sort one set
differently.

What remains is mutually concurrent, and is ordered by:

1. **higher valid authority generation** — re-established authority wins;
2. **restrictive before permissive** — `DENY`, `REVOKE`, `EXPIRE`, `PURGE`
   before `ALLOW`, `GRANT`. A concurrent withdrawal must never be silently lost
   to a concurrent grant. The other way round costs somebody a repeated tap;
   this way round costs somebody their privacy;
3. **manifest-derived role rank** — `PRIMARY` before `TRUSTED`;
4. **canonical device id**, then **canonical event id**.

There is no clock and no relay order anywhere in that list, and the last term is
a unique id, so no two distinct events ever compare equal.

The rank is the role each author held **where it signed**, not the role its
device holds now. Looking it up against the estate as it stands let an unrelated
later change rewrite a settled decision: a device since revoked had its denials
dropped — the withdrawal disappears and the grant underneath stands again — and
a demotion changed which of two concurrent changes won, months after both were
signed. Ending a device's authority stops it saying anything new; it does not
un-say what it already said.

**Only same-scope changes are conflicts.** The resolver is applied per scope and
the scopes are then unioned. Two baselines, a grant and a device revocation, two
revocations of *different* devices, enrolling a tablet while revoking a phone —
all independent, all kept. Two changes to one baseline, or to one device, still
leave exactly one standing. Resolving everything in one pool made a revocation
about one of a peer's devices delete an unrelated grant, because
restrictive-beats-permissive was being applied across subjects that never
competed.

Scopes are length-prefixed and versioned (`ccscope.v2|<n>:<len>:<field>…`),
because peer ids and object ids are chosen by other people: under a plain join,
an npub reading `npub1alice|category|VIDEOS` would land in the scope that
decides Alice's videos. A scope from the earlier ambiguous encoding is
deliberately **not** repairable — the old string does not say which subject it
meant — so it decides nothing and nothing new may be written into it.

**A malformed DAG decides nothing.** A missing subject, two acts for one
subject, an administrative entry with *no* act, an act pointing into another
scope, a cycle, a sequence that does not follow its parent — each fails the
whole thing closed rather than resolving what is left. Resolving the remainder
would hand somebody a truncated history, which has exactly the shape of a
revocation that was quietly dropped.

### 11.7 Genesis

Enrolling needs `ADMINISTER_DEVICES`, which only devices the manifest names
hold, and a fresh install names none — so without a way in, every administrative
path would be closed for ever on a release build, with the person able to read
their permissions and change nothing.

The genesis is therefore the one entry the **root identity authorises alone**,
because at that moment there is by construction no device that could. It is
refused once any manifest exists, so it cannot mint a second primary, and it is
refused while a recovery preview is open (§2.4c). Both checks run again inside
the transaction that writes it. After it, enrolling is an ordinary act-paired
change.

### 11.8 Recovery, fencing and the sovereign reset

A restore is the one moment where somebody holding a backup and a recovery code
takes over an estate. It needs **both** credentials — a real root signature
through the NIP-55/46 seam *and* the separate recovery code — because either
alone is a credential somebody else can hold too.

The root signature is over a domain-separated challenge
(`cc.sharing.recoverychallenge.v2`) binding the owner, the recovery-code digest,
the new device's public key, the generation, whether this is a reset, and a
**fresh per-attempt nonce**. Without the nonce every field stays the same from
one attempt to the next, so one captured signature was a standing licence to
redo the recovery for ever. The nonce is recorded when it is spent
(`sharing_recovery_attempt`, migration 23), in the same transaction as the
writes, so a rollback leaves it unspent and a replay finds it used.

The signature says what the owner agreed to; it says nothing about what the
caller then went on to write. So every term is checked against the actual batch
before the attempt is spent: same owner, same device and key this install
actually holds, same generation, the same *kind* of recovery, a rotation that
advances by exactly one, and exactly one enrolment — of the device that is
running it.

**Freshness is gated before any write.** A backup at the same authority
generation can still be missing every revocation made since it was taken,
because nothing has ever synced:

| Credentials | Freshness | Outcome |
|---|---|---|
| incomplete | any | `REFUSED` |
| complete | `CURRENT` | `RESTORE` |
| complete | `STALE` or `UNKNOWN` | `PREVIEW_ONLY` |
| complete | any, explicitly asked | `SOVEREIGN_RESET` |

`UNKNOWN` is treated exactly like `STALE`. "We cannot tell" and "we know it is
old" warrant the same caution; only one of them is tempting to wave through.
While the native transport is gated shut no install can show it has seen another
device's changes at all, so a file import is a preview in practice.

A restore that proceeds is **atomic**: the backup's own history and the estate it
establishes land in one transaction. It writes, in order, an `AuthorityRotated`
authored under the *old* generation that fences everything, then a
`DeviceEnrolled` of the returning device as `PRIMARY` under the *new* one — so
the returning device is the only one holding authority the moment the manifest
is read again. Every relationship's `resourceEpoch` and `deviceGeneration` both
advance: the first retires the data keys the lost device could still open, the
second the recipient-side authorisations issued to it. Native MLS rotation is
**planned and not performed** while the gate is shut, and is recorded that way
(`NativeRotationPlan.performed = false`) rather than asserted.

A **sovereign reset** is the move available when the owner has lost track of
which devices are theirs. It is explicit, root-signed, warned about in the UI,
and deliberately not parameterised — a reset that spared something would not be
a reset. It revokes and fences the entire estate, mints a fresh generation, and
withdraws every relationship's grant. Nothing is quietly revived: the devices it
revokes are recorded against the generation it *supersedes*, precisely so the
enrolment that follows under the new one can re-establish the estate, and
nothing else can.

A refused or abandoned root signature, a preview, and an ordinary device-paired
batch all leave the lock exactly where it was. Only a root-authorised recovery
that actually succeeds releases it, inside its own transaction.

#### The two doors, and what each can actually prove

There are exactly two, and the difference between them is which credentials are
real rather than nominal:

| Door | Screen | Credentials | What it recovers |
|---|---|---|---|
| `importRecovery` | *Aus Datei wiederherstellen…* | the backup file, the typed code — checked **cryptographically** against the envelope — and a root signature | the permissions in the file |
| `recoverThisDevice` | *Alleinige Kontrolle übernehmen* | a root signature, plus a code | nothing; it establishes a new authority with this device as its only holder |

The second row is stated that bluntly because the earlier wording was not. With
no file there is nothing to restore *from*, and the code it checks is the one
this process is showing: `_recoveryCode` lives in the view model and a lost
device's code cannot be known on a new install, so it is **not** evidence of
anything historical. The credential that decides a code-only recovery is the
root signature. The screen therefore no longer calls it "restore" — it says it
takes sole control of this device, that the other devices stop working, and that
no data comes back.

#### A refusal says which refusal it was

A recovery that does not apply carries a `SharingRecoveryRefusal` — a closed set
of `WRONG_CODE`, `SIGNER_UNAVAILABLE`, `PREVIEW_ONLY`, `NO_DEVICE_IDENTITY` and
`REJECTED` — and the screens map it to their own wording. Nothing derived from
an exception reaches a screen. **Every** non-applied outcome carries one:
`SharingSourceHygieneTest` scans the sources for a `SharingRecoveryOutcome`
built with `applied = false` and no reason, because the promise had already been
broken once by the import's own preview short path, which spelled its outcome
out by hand and let the field fall to its default. That is checked in the source
rather than with a constructor `require`, which would turn a future omission
into a crash inside a recovery.

#### What is decided before the owner is asked to sign

The credential check is **fail-fast**: the code, and the local preconditions a
recovery cannot proceed without, are settled before the root challenge leaves
the process. They were not. The code was folded into `rootSigned` only after the
signature came back, so mistyping it still sent the person to their signer — to
authorise a takeover of their own estate, for an attempt that had already been
decided against. A prompt that cannot lead anywhere teaches people prompts are
noise, and this is the one prompt in the feature that must never become noise.
An install with no manifest signer is refused the same way, for the same reason:
no signature is needed to discover it.

It is a named reason rather than "it did not apply" because those are different
things to do about it. Every non-applied outcome used to become *"that code is
not correct"*, so somebody who typed their code correctly and then declined the
Amber prompt was told the code was wrong, and would retype a correct code for as
long as they had patience. On the file path it was worse: a failure left the
error unset, and the label for an unset error is *the file is damaged* — so a
declined prompt reported the one artefact the person cannot replace as corrupt.

A third entry point used to sit next to these called `restore`, and it was the
one the recovery screen called. It performed none of the above: no root
challenge, no rotation, no re-enrolment, nothing fenced — it appended a
`RestoreCompleted` per relationship, which is a **device-generation bump**. It
is now named `advanceDeviceGenerations`, has no production caller, and
`SharingRecoveryApiSurfaceTest` asserts that nothing on the controller is called
`restore` again.

### 11.9 Limits of this model

- **Nothing synchronises.** Every rule here is enforced locally against locally
  stored evidence; no manifest entry, act or ledger entry has ever crossed a
  wire, because the transport is gated shut (§4). The concurrency the resolver
  settles is therefore real in the code and hypothetical in the field.
- **`syncedGeneration()` has no witness to point at**, so a file import is
  always a preview (§11.8). That is the fail-closed direction, but it does mean
  a legitimate restore currently needs an explicit sovereign reset to get past
  it.
- **An act with no readable manifest context fails the whole estate closed.**
  Rows written before migration 22 carry `NULL` and parse to no context.
  Inventing a frontier for them would be asserting a history nobody signed; the
  feature has never shipped, so a debug install is re-seeded rather than
  migrated.
- **The device key's storage is NOT TESTED on a device** — it is sealed through
  the same Keystore binding as everything else (§4.3).
- Admission requires an act's parent to be stored, so an act that arrives before
  the one it builds on is refused rather than held. With no transport this
  cannot happen; a real inbound path would need a holding area first (§9).

## 12. The manifest is a DAG

It used to refuse a fork outright, on the grounds that the manifest has one
writer — the owner's root identity — so a second entry claiming the same place
was evidence of a problem rather than of concurrency. That was only ever true of
the *signature*. The root key is reachable from every device the owner holds, so
two of them offline both ask it to sign, both produce "next sequence, parent
head", and the second to arrive was refused. Whichever device reached the
database first decided who administers the estate — the one thing the resolver
exists to eliminate.

So siblings on one parent are admitted, exactly as on the other two ledgers
(migration 21), and the same resolver settles them **per scope**: each of the
owner's devices has its own scope, and only rotations and sovereign resets are
about the estate as a whole.

### 12.1 An act binds the whole manifest frontier

The state that stands after a fork is the **union** of the branches, and a
single head names one branch of it and silently omits the other. A device that
signed against "the tablet is enrolled and the phone is revoked" could then
record only half of what it saw, leaving the reader to guess the rest from
whichever head looked canonical. Guessing is exactly what must not decide who
can see what.

So an act binds a `ManifestContext`: every maximal entry of the authorised
union, distinct and sorted, in one canonical length-prefixed byte form
(`ccctx.v1|<n>:<len>:<id>…`, migration 22). The parse is deliberately strict —
an unsorted or duplicated encoding, a padded length or trailing bytes would give
one frontier a second byte form, and the signature covers the bytes.

Reconstruction is fail-closed on anything it cannot place exactly: an id this
install does not hold (which may be *future*, and the two cannot be told apart),
an id no authorised branch holds (a branch that lost decides nothing, and
signing against it would let a device pick the estate it preferred), or a
frontier that is not an antichain.

**What a signature over the frontier does not prove** is that its author had not
seen more. A device revoked on an independent branch can sign a perfectly
well-formed act naming the frontier from before that branch existed, and every
structural check passes. Nothing in the evidence distinguishes "was offline"
from "saw the revocation and is pretending otherwise". So the rule is not
evidential but **positional: anything written now binds the frontier standing
now**, and an old partial frontier is refused at the door rather than argued
with afterwards. A device that really was offline re-signs against what it finds
on reconnect, which costs it a round trip and costs nobody their privacy.

Exactly one caller is exempt, and it is a named seam rather than a flag so that
it cannot be reached by a caller that merely wanted "inbound":
`RootAuthenticatedHistoricalImport`, for restoring a backup whose whole file has
already been authenticated against the owner's root key.

An act authorising a *manifest mutation* binds the frontier from **before** that
mutation. A change to the manifest cannot be its own justification: checked
against the state it produces, an enrolment would be authorised by the device it
enrols, and a fork would be judged against a merge that exists only because both
sides were let in.

## 13. Where the older section numbers point

The KDoc across this feature predates the 2026-08-16 rewrite of this document
and cites its section numbering. The rules did not move; the headings did. For
anyone following a reference out of the code:

| Cited as | Now in |
|---|---|
| `§1`, `§1.4`, `§1.5` | §1 — product contract, identity, devices |
| `§2` | §2 — the permission ledger |
| `§3` | §3 — storage |
| `§5` | §2.1–§2.4 — signing, who may say what, admission |
| `§7` | §3.2–§3.3 — key handles, wrapping keys, durable destruction |
| `§8` | §2.4b — the encrypted permission backup |
| `§9` | §3.4 — what removing a person deletes, and the erase pipeline |
| `§10` | §10 → §2.1a–§2.1b — signing through an external signer |
| `§11` | §11 — multi-device authority |
| `§12.1` | §12.1 — an act binds the whole manifest frontier |

The last three are the reason §10–§12 carry those numbers rather than following
on from §9: they are where 65 existing references already point.
