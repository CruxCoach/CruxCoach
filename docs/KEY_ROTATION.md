# Android Signing Key Rotation

> Audience: CruxCoach end users and the maintainer team. This document
> describes both what users see if the signing key ever changes, and the
> developer procedure for rotating it.
>
> Status: public operating procedure for the in-app updater (FEAT-004).
> Review it before any release whose signing certificate could change.

## What is a signing key?

Every Android APK is signed by its developer with a private key.
Android's installer enforces a strict rule: an app can only be updated
by an APK signed with the **same key** as the originally installed
version. This rule is the platform's main defense against a malicious
APK pretending to be an update for an app you trust.

CruxCoach also pins the SHA-256 of its signing certificate inside the
app the first time you launch it (TOFU — *Trust on First Use*). The
in-app updater refuses to install any APK whose certificate does not
match the pinned hash, even before handing the file to Android's
installer.

This means: as long as the same signing key is used for every release,
the auto-updater works silently in the background. If the key ever
**changes**, both the in-app pin and the platform's same-signature rule
will refuse to install the new APK — and the user has to take action.

## What you see as a user when the key changes

Inside the app, instead of a normal "Update verfügbar" notification,
you will see:

> **Update kann nicht automatisch installiert werden**
>
> Die Signatur der neuen Version unterscheidet sich von der
> installierten. Das kann ein legitimer Schlüsselwechsel oder ein
> Angriff sein. Bitte manuell von Codeberg neu installieren.
>
> [Auf Codeberg öffnen]   [Später]

### What to do

1. **First, ask before you install.** A signature change is rare. The
   maintainers will always announce a planned key rotation in advance,
   on the project's Nostr channel and in the Codeberg release notes for
   the version that introduces the new key. **If you did not see such
   an announcement, do not install** — ask in the project's Dev-Chat
   first. An unannounced signature change is the exact pattern that a
   malicious APK trying to impersonate CruxCoach would also create.
2. If the rotation **was** announced and you trust the source:
   - Tap **"Auf Codeberg öffnen"**. Your browser opens the Codeberg
     release page for the new version.
   - Download the APK from that page. Verify the SHA-256 against the
     `.sha256` file published next to it (most file managers can do
     this; on a desktop, `sha256sum cruxcoach-release.apk` works).
   - Uninstall the currently installed CruxCoach (Android cannot
     replace an app with a differently signed APK, even when you do
     it manually).
   - **Important — back up your data first.** Uninstalling deletes the
     local CruxCoach database. Use Settings → "Back up data" before
     you uninstall, then restore after the new install.
   - Install the downloaded APK. On the first launch, CruxCoach
     re-pins to the new signing certificate. The auto-updater is
     silent again from that point forward.
3. If the rotation was **not** announced — keep the old version,
   contact the maintainers, and report the unexpected signature change
   in the Dev-Chat. Do not tap "Auf Codeberg öffnen" speculatively.

## Why the app cannot just "trust" the new key automatically

Auto-overriding the signing-cert pin would defeat its only purpose. An
attacker who managed to push a malicious APK to a tampered Codeberg
mirror, or to a network endpoint via TLS interception, would also be
able to convince the app to swap the pin to their own key — which is
exactly the situation we want the user to be aware of.

By forcing a manual step, CruxCoach gives you the chance to catch a
malicious APK before it is installed: an unexpected signature prompt is
a strong, visible signal that something is different about this update.

## Developer procedure for rotating the signing key

> This section is for the CruxCoach maintainer team. It is published
> here so that contributors can verify the procedure before a rotation
> happens.

A rotation is a coordinated action with three phases.

### 1. Announce — at least 14 days before the first release with the new key

- Post a short notice in the project's Nostr channel that includes:
  - The reason for the rotation (e.g., "scheduled key hygiene", "old
    key compromised — see […]", "moving signing to hardware token").
  - The version number of the first release that will be signed with
    the new key.
  - The SHA-256 hash of the new signing certificate, so technical
    users can verify it independently after install.
- Pin the same notice to the project's Codeberg README until the
  rotation is complete.

### 2. Release — sign with the new key

- Generate the new keystore using the public release-signing procedure in
  [`CONTRIBUTING.md`](../CONTRIBUTING.md#release-signing). The keystore itself
  and its credentials are never committed.
- Update the signing config in the ignored `local.properties` file. The
  template and required fields are documented in
  [`local.properties.example`](../local.properties.example).
- Replace [`release-cert-sha256.txt`](../release-cert-sha256.txt) with the
  SHA-256 fingerprint of the new certificate. The release workflow rejects an
  APK whose signer does not match this file.
- Build the release APK as usual: `./gradlew :androidApp:assembleRelease`.
- The first release notes after a rotation must include, near the top:
  - A clearly labelled "Signing key changed" section.
  - The SHA-256 hash of the new signing certificate (same value as
    announced in phase 1).
  - A link back to this document.

### 3. Hold the old key

- Do not delete the old keystore for at least 12 months after the
  rotation. Some users are slow to update; the maintainer team may
  need to publish a stop-gap release on the old key for users who
  cannot or will not switch immediately.
- Once the old keystore is no longer in active use, store it offline
  in a separate location from the new keystore, encrypted, with the
  password also stored separately. Do not destroy it for at least 24
  months — incident response (forensic verification of an old build)
  may need it.

### Things that are NOT a key rotation

- Changing CI infrastructure (Forgejo runner image, Codeberg
  organization settings, etc.) — the *signing key* is what matters,
  not where the build runs.
- Switching maintainers — as long as the keystore is handed over
  intact, the signing key is unchanged.
- Switching from one Codeberg release artifact name to another — the
  updater pins the cert hash, not the file name.

If you are a maintainer about to do anything that *might* change the
signing certificate fingerprint and you are not sure, build a release
APK in a test environment, run `apksigner verify --print-certs
cruxcoach-release.apk`, and compare the SHA-256 to the hash currently
pinned in installed devices. If they match, you have not rotated the
key — proceed normally.

## See also

- [`ROADMAP.md`](../ROADMAP.md) — public status index for FEAT-004.
- [`CONTRIBUTING.md`](../CONTRIBUTING.md#release-signing) — keystore setup and
  APK verification.
- [`.forgejo/workflows/release.yml`](../.forgejo/workflows/release.yml) — the
  executable signer-fingerprint and release-artifact checks.
- [`release-cert-sha256.txt`](../release-cert-sha256.txt) — expected upstream
  release certificate fingerprint.
