# Local-share compatibility contract

CruxCoach local share uses two intentionally distinct board artifacts. This is
a wire-compatibility boundary, not merely two URLs for the same file.

- v1: `GET /v1/manifest`, protocol `1`, board path `/board.db.gz`. The served
  private snapshot is scrubbed with `LocalShareSchema.SNAPSHOT_SCRUB` in its
  historical order. It contains no Quantum catalogue, Quantum geometry, route
  UUID bridge, or Quantum vendor metadata. Existing v1 receivers therefore
  continue to receive the legacy logical artifact they understand.
- v2: `GET /v2/manifest`, protocol `2`, board path `/v2/board.db.gz`. The
  snapshot keeps public Quantum generic rows, geometry,
  `quantum_route_refs`, and `quantum_route_metadata`, while applying the same
  private-draft, account-identity, and publish-audit scrub as v1.

Receivers request v2 first. They retry v1 only when the v2 endpoint is absent
(`404`) or an old server returns its HTML landing page with `200` for the
unknown path. A valid response must declare the protocol requested, and a
ready manifest's board path must match that protocol exactly. Transport,
length, hash, or artifact failures never trigger a silent downgrade.

The APK URL remains shared between protocol generations. A v2 receiver sends
`X-CruxCoach-Share-Protocol: 2` on each APK GET/range retry so the completed APK
transfer arms only the v2 snapshot. A headerless or unknown request retains the
v1 behavior exactly and arms only the legacy scrubbed snapshot. This prevents
two large copy/VACUUM/gzip jobs from running concurrently for one v2 transfer.

The first manifest request is deliberately headerless. Once it has selected a
peer, the receiver binds every later manifest poll, APK/DB GET or HEAD, and
completion POST to that manifest's `sessionId` with
`X-CruxCoach-Share-Session`. A new server returns `409 Conflict` before arming
snapshot work, transferring an artifact, or accepting completion when a
present session header does not match. Receivers treat that as a terminal peer
replacement, not as a retryable network failure. Missing session headers remain
accepted so pre-0.2.2 clients work with a new sender, and old senders simply
ignore the additive request header.

The sender lifetime is an absolute 15 minutes from server start. Requests and
active transfers cannot postpone it. Accepted sockets are bounded, receive a
read timeout, and are all closed at shutdown so a stalled header or non-reading
download cannot keep the share alive. Explicit and timed shutdown are
idempotent.

On first-run discovery, a valid LAN manifest is only an unauthenticated offer.
The app stages the exact network, base URL, manifest, and session while no
transfer or import is active, then names the peer and its declared catalogues in
a one-answer dialog. **Use nearby share** starts the bound transfer; **Use
internet** discards it and follows the normal online catalogue path. Repeated
composition, confirmation, or dismissal cannot probe or act twice.

Resume records written across an APK replacement persist both protocol and
artifact path. Records written before 0.2.2 have neither field and are treated
as v1. A persisted protocol/path mismatch is discarded.

## Database import boundary

A modern CruxCoach database is untrusted peer input. Listed, non-draft,
non-deleted generic climbs are additive: they cannot refresh existing climb
content or assert community authorship. A peer stats row is eligible only when
its source climb passes that same filter and joins a receiver climb of the same
board brand. It may fill a missing `(climb_uuid, angle)` aggregate, but cannot
replace an existing aggregate after a same-brand UUID collision. Brand geometry
remains shareable in v2. A v1 import filters branded Quantum geometry as a
receiver-side backstop even if a malformed legacy artifact contains no Quantum
climb row that would otherwise reveal it.

For v2 and direct full-database injection, every accepted official Quantum
climb (`source=quantum`) must have exactly one syntactically valid app UUID to
route UUID/model mapping and one metadata row. Public community Quantum climbs
(`source=nostr`) import as generic rows without vendor metadata and use their app
UUID as the controller fallback. Models are trimmed, lowercased, and restricted to the known
`xl`, `l`, `m`, `s`, and `belay` wire values. Bridge rows for filtered/private
or non-Quantum climbs are ignored. The mapped model must identify the climb's
Quantum layout. Existing receiver mappings are authoritative: an identical
mapping is retained, while a peer remap aborts the import before writes. If the
climb UUID already exists, an official Quantum row is accepted only when the
receiver row is itself Quantum and is already bound to that exact authoritative
route/model mapping.

Generic climbs, stats, available geometry, and the Quantum bridge commit in
one SQLite transaction. A validation or runtime failure leaves none of those
peer rows behind. Modern databases from older releases may omit additive
geometry tables and may lack `board_brand` on geometry (such rows are Kilter);
if a table is present but lacks a required non-additive column, import fails
without partial catalogue writes.

Backup support remains format version 3. Quantum ascents, bids, own climbs,
own-climb stats, brand, and layout use the existing additive, defaulted fields;
the restore derives Quantum's hold-set mask from the restored brand. When an
active signer is supplied, selected own-climb data must carry that exact
`nostrPubkey` envelope identity; an absent or different identity fails before
database writes. No backup-version bump is required.

Both v1 and v2 serve-time copies apply the common privacy scrub after removing
private local drafts and the Kilter publish-attempt audit table. Shareable climb
rows have `kilter_author_uuid`, `kilter_error`, `kilter_status`,
`kilter_synced_at`, `kilter_publish_via`, `nostr_publish_via`, and
`frames_hash` cleared, with `sync_status` restored to `synced`. The sender's
live database is never modified. The v1 copy additionally applies the complete
historical Quantum scrub; v2 alone retains the public Quantum bridge and
metadata.
