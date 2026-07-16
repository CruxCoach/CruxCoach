# Security Policy

## Reporting a Vulnerability

If you discover a security vulnerability in CruxCoach, please report it responsibly. **Do not open a public issue.**

### How to Report

Send an encrypted Nostr DM (NIP-17) to the developer pubkey:

```
e75a185c019d09049d5fcb0e29a2cc9bfd016ec0f6d892fc98f6ffe0181a480d
```

Alternatively, open a confidential issue on the [Codeberg repository](https://codeberg.org/CruxCoach/CruxCoach).

### What to Include

- Description of the vulnerability
- Steps to reproduce
- Affected versions (if known)
- Potential impact

### What to Expect

This is a solo-maintained project. I'll do my best to respond promptly, but cannot guarantee specific timelines. Critical issues (key exposure, data leaks) will be prioritized.

### Scope

The following are in scope:

- SQLCipher encryption bypass or key leakage
- Nostr private key exposure
- BLE protocol vulnerabilities that could affect connected devices
- Data exfiltration via backup, export, or sync
- Android component exposure (exported activities, content providers)
- **Community-publishing chain (0.1.4+)** — Climb Creator, Kind-30078
  community-climb events, Kind-1 Auto-Note announcements, and the Kilter
  API self-account write path. In particular: signature/identity
  spoofing of other users' published climbs; bypass of the
  one-author-per-uuid guard on incoming events; injection or replay
  attacks on the Auto-Note Kind-1 template; Kilter access-token leakage
  from `KilterApiClient` or its retry worker; abuse vectors that let an
  attacker forge or modify a community climb under another user's
  identity.

The following are out of scope:

- Vulnerabilities requiring physical device access with USB debugging enabled
- Social engineering attacks
- Denial of service against Nostr relays (not our infrastructure)
- Issues in third-party dependencies (report these upstream, but let us know)
- Spam / abuse of public Nostr relays at the protocol layer (relay
  operators' responsibility); but client-side amplification or
  rate-limit-bypass bugs in CruxCoach are in scope.

---

## Security Architecture

### Data at Rest
- Personal data (climb logbook, body stats, Nostr keys): **SQLCipher** with AES-256, key managed by Android Keystore
- Nostr private key: **EncryptedSharedPreferences** (AES-256-GCM, key in Android Keystore)
- Board data (community climbs): unencrypted (public data)

`androidx.security:security-crypto` and its `EncryptedSharedPreferences` API are
deprecated. CruxCoach retains the wrapper only to preserve the encrypted format
already installed on devices and pins the underlying maintained Tink engine as
an explicit dependency. Removal requires a fail-safe, read-old/write-new
migration: decrypt an existing value, write it through a direct Keystore-backed
AEAD store, verify the new copy, and only then remove the old entry. Both stores
must refuse destructive recovery while old ciphertext exists. The intended
replacement is either direct Tink with Android Keystore wrapping or the same
Keystore-wrapped-DEK pattern used by `SqlCipherKeyManager`; a flag-day format
change is not acceptable because it could rotate a Nostr identity or discard
account tokens.

### Data in Transit
- Nostr sync: NIP-17 encrypted direct messages
- Board DB updates: content-addressed via SHA-256 (Blossom protocol)
- BLE communication: unencrypted (inherent to the board hardware protocol)

### Backup Exclusions
- SQLCipher key material is excluded from Android cloud backup and device transfer
- Nostr keys are excluded from backup

### Encrypted Cloud Backup (FEAT-002, 0.1.3+)
- **Opt-in only.** Off by default; enabled per-identity via the onboarding flow, the *what's new* upgrade dialog, or *Settings → Encrypted cloud backup*.
- **Three-layer envelope, all gated by your Nostr key:**
  1. *Blob* — `gzip(SQLCipher database)` encrypted with a per-backup random 32-byte data key via **AES-256-GCM**, uploaded to [Blossom](https://github.com/hzrd149/blossom) storage servers (SHA-256 content-addressed, BUD-06 headers). The dataKey itself never reaches a server.
  2. *Backup pointer* — Kind-30078 (NIP-78 replaceable parameterized) Nostr event signed by your key. Content is NIP-44-v2-self-encrypted JSON listing the blob's SHA-256 and the Blossom servers holding it. d-tag `cruxcoach/backup/v1`. Published to your NIP-65 write-relays.
  3. *DataKey wrap* — Kind-30078 Nostr event signed by your key. Content is NIP-44-v2-self-encrypted, holding the dataKey hex. d-tag `cruxcoach/key/v1`.
- **NIP-44 v2 envelope.** Per-conversation key derived from ECDH(nsec, recipient pubkey) → HKDF-SHA-256 → ChaCha20 stream cipher + HMAC-SHA-256 (encrypt-then-MAC). For *self-encryption* the recipient is the user's own pubkey, so only the holder of the same nsec can re-derive the shared secret and decrypt the wrapped dataKey.
- **Only your Nostr private key unlocks the chain.** Restore on a new device fetches both events from relays, NIP-44-decrypts them to recover (pointer, dataKey), downloads the blob from any listed Blossom server, verifies SHA-256, and AES-256-GCM-decrypts. No additional password, no server-side decryption help.
- **Remote persistence is one-way.** Cloud backups survive every local-only action — app uninstall, *Clear app data*, identity switch (Local ↔ Amber), board-data deletion. The only path that touches remote is *Settings → Delete remote backups…*, gated by an explicit confirmation dialog with caveats spelled out.
- **No backdoor.** The maintainer cannot decrypt your backup, recover your key, or access your account by any technical means built into the app.

### Community Publishing (FEAT-003, 0.1.4+)
- **Opt-in publish.** Climbs you create in the Climb Creator are public on
  Nostr only when you tap *Publish*. Drafts and unpublished work stay
  local. Auto-Note Kind-1 announcements are off by default; toggle in
  *Settings → Climb Creator*. Kilter API push is off by default; toggle
  in *Settings → Kilter publish*.
- **Identity.** Community-climb events are signed with your existing
  CruxCoach Account key (the same key used for cloud backup). Once
  published, an event with that signature on a relay is permanent —
  relays do not delete on request.
- **Ingest is verified.** Every incoming Kind-30078 community-climb event
  is parsed via Quartz `Event.fromJson` and rejected unless both
  `verifySignature()` and `verifyId()` pass. The latter recomputes the
  canonical event id and binds the signature to the event body. Events whose
  d-tag prefix or content `pubkey_prefix` field claims a different author
  than the signed pubkey are dropped. A uuid already owned by author A
  cannot be overwritten by an event from author B (first-author wins).
  Without these guards a relay (or MITM on a non-TLS connection) could
  spoof events under any pubkey or clobber legitimate climbs.
- **Kilter API push.** When enabled, the CruxCoach app authenticates to
  the Kilter API with the user's own Kilter credentials and submits the
  climb as if the user did it from the Kilter app. The access token is
  held in `EncryptedSharedPreferences` (Android Keystore-backed). Push
  failures retry on a 6-hour periodic worker; the token is refreshed on
  401/expired-token responses.

### User Responsibility — Key Storage
The Nostr key (= the CruxCoach Account key, surfaced in *Settings → CruxCoach Account*) is a **single point of recovery** for both the account and any cloud backup encrypted with it. The app warns about this prominently on every backup-enable surface (onboarding, *what's new*, settings) and on the account screen, with a single canonical place to view + copy the key.

If a user loses their device without having stored the key elsewhere (a password manager, Amber's own backup, paper, etc.), neither their account nor their cloud backup can be restored — by them, by us, or by anyone else. This is by design: there is no centralised account that could be reset, and there is no master key that could decrypt third-party backups.

---

## Supported Versions

| Version | Supported |
|---------|-----------|
| Latest release | Yes |
| Older releases | Best effort |

We recommend always running the latest version.
