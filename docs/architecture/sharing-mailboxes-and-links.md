# Sharing over mailboxes and invitation links (0.2.6 preview, as built)

Status: implemented on `feat/permission-foundations`. It runs only in the debug preview of personal data sharing
(`SharingFeature`) and is not in a release. It is the basis for the external review (E7/Z11). The
[German overview](sharing-mailboxes-and-links-overview.de.md) summarises it.

Owner decision 2026-10-26:
- Olm between each pair of friends.
- Delivery is "kept only until delivered", through several mailboxes.
- Friends exchange directly when both are reachable.
- The level "Maximal" means direct only.
- MLS (Marmot) stays in the code but is not wired.

Identifiers in brackets (BK, SP, MD, VS, VT, PV) refer to the transport decision's addendum
(`NACHTRAG-ENTSCHEIDUNG.md`).

## 1. Layers

| Layer | What it is | Where |
|---|---|---|
| Sharing protocol v3 | Streams per audience, manifests with count and digest, deltas, snapshots per member, acks | `SharingSync`, `SharingProtocol` |
| Olm | One vodozemac session pair per friendship. Every application message is one Olm message (a *frame*). | `native/marmot/src/olm.rs` |
| Container | Frames and acks in *sections*. Each section has a 16-byte address and a MAC under the relationship key. No section names a person. | `native/marmot/src/p2p.rs` |
| Transports | Direct (LAN HTTP over a plain socket, BLE GATT) while the app is in front. Mailboxes (Nostr relays) while online. | `sharing/p2p`, `sharing/mailbox` |

The native side builds, checks and opens everything. Kotlin moves events it cannot read.

## 2. Keys and addresses

All HKDF use SHA-256. `hmac(key, parts…)` length-prefixes each part (u32 BE) before MACing.

| Value | Derivation |
|---|---|
| link key | `HKDF(salt "cc-link-v1", ikm inviter_tag ‖ acceptor_tag, info "cruxcoach share link")`. The inviter tag travels in the invitation, the acceptor tag only inside Olm. |
| mailbox key | `HKDF(salt "cc-mailbox-v1", ikm link key, info "cruxcoach share mailbox")` |
| introduction key | `HKDF(salt "cc-mailbox-intro-v1", ikm secret, info "cruxcoach share introduction")`, from the secret of a contact code or link, or from the inviter tag |
| day material | `HKDF(salt "cc-mailbox-day-v1", ikm key, info label ‖ direction ‖ day_be8)`, label `sign` or `seal` |
| address (BK3) | `hex(hmac(key, "cc-mbx-addr-v1", direction, day_be8)[0..8])`: 16 hex, changes daily and per direction |
| signing key (BK6) | day material `sign` as a secp256k1 key (rehashed in the unlikely invalid case). Sender and receiver can derive it, nobody else can. |
| pool seed | `hex(hmac(key, "cc-mbx-pool-v1", direction)[0..8])`, the input to the mailbox choice (§4) |

`direction` is 0 for frames from the inviter and 1 for frames from the acceptor. `day` is `unix_time / 86400` (UTC).

## 3. Mailbox events (BK1–BK10)

- **Kind 2437.** A regular kind that no NIP uses (checked 2026-10-22). Tags:
  - exactly one `z` with the address;
  - one `expiration` (NIP-40, 14 days);
  - optionally one NIP-13 `nonce`.
  - Nothing else.
- **Content.** Base64 of `nonce12 ‖ ChaCha20-Poly1305(day material seal, aad "cc-mbx-v1", len_u32 ‖ container ‖ zero padding)`.
  - The sealed length is exactly one size class: 1, 2, 4, 8, 16, 32 or 47 KiB (BK4).
  - The largest class keeps the event within the 65,536-byte JSON limit of standard strfry.
- **Time.** `created_at` is cut to the minute and moved back 0–2 whole minutes, never before the day of the address
  (BK5, MD4). An event waiting longer than 50 minutes is built again (strict mailboxes refuse older ones).
- **Receiving.**
  - An event is checked before it is opened: kind, exact tags, size class, time window and signature.
  - Its address must belong to a relationship or an open code, and it must be signed with that day's key (offers:
    §6).
  - Event ids and frame hashes keep duplicates from being applied twice (SP6). Frames are stored before an event
    counts as seen, so a crash in between applies nothing twice (VT10).
- **Deletion after delivery (BK7/BK8).**
  - The receiver stores first, then publishes NIP-09 kind 5 for what it took.
  - Each deletion is signed with the day key the events used and names at most 50 ids, with `k = 2437` and an
    `expiration`.
  - A sender's withdrawal (`cancel`) deletes what already sits in mailboxes.
  - Each sent and seen event stores the key it was published under, so deletions work after a key change.

## 4. Which mailboxes (SP1–SP5, SP3′)

**Pools.** The app ships a fixed list (`MailboxPools`):

| Pool | Mailboxes |
|---|---|
| CruxCoach | `wss://blossom.cruxcoach.org/post` |
| A | three relays behind Cloudflare |
| B | three relays not behind Cloudflare |
| Reserves | two, one of them on different software |

One operator counts once. The debug preview uses only the CruxCoach mailbox until the owner decides which mailboxes a
device test may write to (F6).

**Choice per relationship and direction (SP3′).**
- Both friends derive the same three mailboxes from the pool seed: CruxCoach, the A relay with the highest weight and
  the B relay with the highest weight.
- The weight is `SHA-256("cc-mbx-choice-v1|seed|id")` (rendezvous hashing over fixed ids). Removing a relay moves only
  the relationships that used it.
- Rules: at most one mailbox behind Cloudflare, at most two at Hetzner, three operators.
- Mailbox lists never go to relays (MD6). An explicit list in the Olm `Keys` message is foreseen (empty in 0.2.6) and
  would take precedence.

**Releases.**
- A new relay is only read for 30 days before anyone writes to it.
- A removed relay is still read for the 14 days a mailbox keeps events.
- Apps of neighbouring releases therefore keep meeting (VT15).

**Writing (SP4).**
- Each event goes to its three mailboxes in parallel. Accepted by two of three counts as placed.
- A dead mailbox, a refusal, or no quorum after 10 minutes brings in a reserve that keeps the rules.
- An event is done when it is on all its mailboxes, or on two of them for 40 minutes.
- **Pacing:**
  - Public relays get 6 at once, then 6 a minute. They limit per address, about 8 a minute, and block after 10
    refusals.
  - The CruxCoach mailbox gets 30 at once, then 120 a minute. It limits per event and in total (PV2).
  - One event goes first, the rest only after it was taken.
  - "rate-limited" pauses that relay for a minute, "blocked" for an hour.
  - A refusal is never repeated to the same relay.
- Relays whose NIP-11 asks for a login or payment, or that are too small, are not written to.

**Reading (SP5).**

| When | What is read |
|---|---|
| Background | CruxCoach only (it is in every choice) |
| In front | CruxCoach, all of pool B (every relationship has exactly one B relay) and one A relay in turn |
| CruxCoach unreachable, and once a day | Everything a relationship may use, the reserves included |

Requests carry at most 400 addresses. Paging goes back where a relay answers fewer events than asked.

**Direct only (P7, level "Maximal").** Nothing goes into or comes from any mailbox. Data moves only when friends meet,
with both apps open. Invitations work by QR code only.

## 5. Direct transports

| Transport | Details |
|---|---|
| LAN | HTTP/1.1 routes `/share/v1/hello` and `/share/v1/exchange` on a bounded local server. The client is a plain socket, so it reaches any address of an ordinary Wi-Fi (the network security config stays as it is). It connects only to IP literals found by NSD and never looks a name up. |
| BLE | GATT with fragments. Only rotating beacons are advertised. |

Containers are Olm-encrypted and MAC-checked, so neither transport needs TLS.

## 6. Invitations

- **Contact code (QR).** `cruxcoach:c1:` followed by base64url of `"ccc1" ‖ npub(32) ‖ tag(32)`.
  - Valid one hour, at most two shown, taken once.
  - The showing phone names its identity under the code, and the scanning phone names it before sending, so both can
    compare.
- **Link (P4).** `https://cruxcoach.org/f/#` followed by base64url of `"ccl1" ‖ npub(32) ‖ tag(32) ‖ until_u32_be`.
  - The code sits in the **fragment**, which a browser never sends to the server (MD7). A code in path or query is
    refused.
  - Valid 7 days, taken once, revocable, at most 8 open. It is a bearer token, so the issuer confirms every request.
  - Opening a link puts the code into an in-memory inbox, never into the navigation route or saved state. Nothing is
    sent before the person taps.
- **Offer.**
  - Whoever takes a code invites its owner. The invitation package is sealed to the tag:
    `ChaCha20-Poly1305(HKDF(salt "cc-offer-v1", ikm tag, info "cruxcoach share offer"))` in an offer section.
  - Directly, the package goes to the device whose beacon proves the tag.
  - Through mailboxes, it goes as an event under the introduction address of the tag (direction 0), published once a
    day while the invitation waits.
  - Others may hold the tag (a forwarded link), so **each offer event is signed with a key of its own** (BK6/O3):
    nobody but its sender can delete or replace it.
  - It expires with the code and is withdrawn once the friend answered.
- **Acceptance.**
  - The issuer reads the introduction addresses of its open codes and links, sees the request and confirms it.
  - The first Olm message (pre-key) carries the acceptance under the introduction key of the *inviter tag*
    (direction 1), the only secret the inviter has then.
  - From the inviter's first answer on, both use the link key; the inviter then stops reading the introduction.
  - Replies under the inviter tag are never trusted for acks, because an onlooker of the code may know it.

## 7. What each party sees (MD, addendum §4.1, as built)

| Party | Sees | Does not see |
|---|---|---|
| CruxCoach mailbox (proxy keeps no access log and strips IP headers, PV3) | That it is CruxCoach sharing (kind); daily addresses and daily keys; when events are placed, fetched and deleted; size classes; which addresses one connection asks for (about one device's relationships on one day); total activity | Contents, npubs, names, data types, IP (while the proxy stays so) |
| Public relay | The same, **plus the IP address**; whether it logs is unknown | Contents, npubs |
| Public relay behind Cloudflare | The same; Cloudflare also sees IP, time and size (TLS ends there) | Contents, npubs |
| Hosting provider of CruxCoach | Network flows: IP, time, amount | Contents, addresses (TLS) |
| Friend | What is shared with them (intended) | What others see |
| Network operator, Wi-Fi | Connections to `blossom.cruxcoach.org` and the relays (SNI, DNS), time, amount | Contents, addresses |
| Push services | Nothing (no push) | – |
| External signer (Amber) | One binding signature per installation | – |

Not yet built (0.2.7 and later): separate connections for invitation and relationship addresses (MD10), decoy
addresses (MD11), deletion checks of public relays (MD12), own mailboxes (SP9), Megolm distribution per audience, Tor
(MD14).

## 8. Transparency text for "Who sees this?" (MD9)

> Shared data is end-to-end encrypted. When you meet, phones exchange it directly. Otherwise it waits in up to three
> mailboxes, among them CruxCoach's own, only until your friend's app has fetched it, and at most 14 days.
> Mailboxes see neither your identity nor the content. Public mailboxes may see your IP address. With "Only directly"
> nothing goes into any mailbox.

## 9. Tests

| Test | Covers |
|---|---|
| Native (`tests/olm_mailbox.rs`, `olm_channel.rs`) | Events, addresses, sealing, deletion keys, offers, links (once, revoked, ended, quota), batched admission |
| JVM (`OlmMailboxIntegrationTest`) | Two real engines over local fake mailboxes: sharing, deletion after delivery, outages, NIP-11, links, direct only |
| T2 (`MailboxRealRelaysTest`, opt-in, `scripts/mailbox_t2`) | Three real strfry 4cd3cf6 relays: the CruxCoach write rule, a damus-like throttle behind a hostile proxy, relay.nostr.net limits; plus a hostile reserve. VT6–VT9, VT12, VT13. |
| T3 (`examples/share_swarm.rs`) | Cost of one change for 10 and 32 real friends |

Not covered without devices: BLE, NSD, camera, Doze. Those are part of device test D1.
