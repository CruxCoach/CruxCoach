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

The following are out of scope:

- Vulnerabilities requiring physical device access with USB debugging enabled
- Social engineering attacks
- Denial of service against Nostr relays (not our infrastructure)
- Issues in third-party dependencies (report these upstream, but let us know)

---

## Security Architecture

### Data at Rest
- Personal data (climb logbook, body stats, Nostr keys): **SQLCipher** with AES-256, key managed by Android Keystore
- Nostr private key: **EncryptedSharedPreferences** (AES-256-GCM, key in Android Keystore)
- Board data (community climbs): unencrypted (public data)

### Data in Transit
- Nostr sync: NIP-17 encrypted direct messages
- Board DB updates: content-addressed via SHA-256 (Blossom protocol)
- BLE communication: unencrypted (inherent to the board hardware protocol)

### Backup Exclusions
- SQLCipher key material is excluded from Android cloud backup and device transfer
- Nostr keys are excluded from backup

### Encrypted Cloud Backup (FEAT-002, 0.1.3+)
- **Opt-in only.** Off by default; enabled per-identity via the onboarding flow, the *what's new* upgrade dialog, or *Settings → Encrypted cloud backup*.
- **Encryption keys never leave the device.** The data-encryption key is wrapped with a key derived from the user's Nostr private key via HKDF-SHA-256, encrypted via AES-256-GCM, and announced to relays as a Kind-30078 replaceable event. Only a holder of the matching Nostr key can unwrap and decrypt.
- **Storage layout.** Encrypted blob on [Blossom](https://github.com/hzrd149/blossom) servers (content-addressed via SHA-256, BUD-06 headers), pointer event on the user's Nostr write-relays (NIP-65 discovered).
- **Remote persistence is one-way.** Cloud backups survive every local-only action — app uninstall, *Clear app data*, identity switch (Local ↔ Amber), board-data deletion. The only path that touches remote is *Settings → Delete remote backups…*, gated by an explicit confirmation dialog with caveats spelled out.
- **No backdoor.** The maintainer cannot decrypt your backup, recover your key, or access your account by any technical means built into the app.

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
