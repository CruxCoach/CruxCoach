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

---

## Supported Versions

| Version | Supported |
|---------|-----------|
| Latest release | Yes |
| Older releases | Best effort |

We recommend always running the latest version.
