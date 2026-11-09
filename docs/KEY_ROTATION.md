# Android Signing Key Rotation

> **Release scope (2026-11-09):** 0.2.5 is planned as the first release
> signed by the new release key, which lives on a YubiKey at the maintainer's
> PC (signing ceremony on 10 November 2026). Every release up to 0.2.4 is
> signed by `7f8308e8…1bc2c4`. Publication still needs the owner's
> authorization of each release. See [release status](RELEASE_GITHUB.md).

> Audience: CruxCoach end users and the maintainer team. This document
> describes both what users see if the signing key ever changes, and the
> developer procedure for rotating it.
>
> Status: written 2026-04-21 alongside FEAT-004 (in-app auto-updater).
> Originally a release blocker for v0.1.2, the updater still refers users
> here when it detects a signing-cert mismatch.

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

**In the normal case: nothing.** Since 0.2.2 CruxCoach understands
Android's signing-certificate lineage. When the maintainers rotate the
key properly, the new release carries proof — signed with the *old* key
— that it is authorised to succeed it. Both Android and the app's own
pin verify that proof, and the update installs like any other.

You only see the prompt below when that proof is missing or does not
lead back to the certificate this install trusts. Two situations produce
it, and they look identical from inside the app:

- your device is older than the API level the maintainers chose as the
  rotation boundary *and* they retired the old key at that boundary
  (see the developer section below); or
- the new APK genuinely is signed by a different, unauthorised key —
  the case the pin exists to catch.

In that case you will see, instead of a normal "Update verfügbar"
notification:

> **Update kann nicht automatisch installiert werden**
>
> Die Signatur der neuen Version unterscheidet sich von der
> installierten. Das kann ein legitimer Schlüsselwechsel oder ein
> Angriff sein. Bitte manuell von der Release-Seite neu installieren.
>
> [Update prüfen]

*Settings → Updates* then shows **"Update blockiert — Signatur hat sich
geändert"** with a **"Release-Seite öffnen"** button.

### What to do

1. **First, ask before you install.** A signature change is rare. The
   maintainers will always announce a planned key rotation in advance,
   on the project's Nostr channel and in the GitHub release notes for
   the version that introduces the new key (the Codeberg release mirror
   carries the same notes). **If you did not see such
   an announcement, do not install** — ask in the project's Dev-Chat
   first. An unannounced signature change is the exact pattern that a
   malicious APK trying to impersonate CruxCoach would also create.
2. If the rotation **was** announced and you trust the source:
   - Tap **"Update prüfen"**, then **"Release-Seite öffnen"** in
     *Settings → Updates*. Your browser opens the release page for the
     new version — normally the GitHub release; the Codeberg mirror and
     the install page on cruxcoach.org offer the identical APK.
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
   in the Dev-Chat. Do not tap "Release-Seite öffnen" speculatively.

## Why the app cannot just "trust" the new key automatically

Auto-overriding the signing-cert pin would defeat its only purpose. An
attacker who managed to push a malicious APK to a tampered mirror, or to
a network endpoint via TLS interception, would also be able to convince
the app to swap the pin to their own key — which is exactly the
situation we want the user to be aware of.

**A v3 lineage is not "just trusting" the new key.** It is a chain in
which the *old* key signs a statement authorising the new one. Only
someone holding the old private key can produce it, so accepting a
lineage requires no new trust — it verifies the same anchor the app
pinned on first launch. That is why, since 0.2.2, CruxCoach accepts an
update whose certificate history *contains* the pinned certificate, and
still refuses one that merely presents a different certificate.

Once the rotated release is installed, the pin **moves forward** onto the
new certificate at the next start-up, and only if the old pin is present
in the chain the platform verified. From then on an APK signed with the
superseded key is rejected by CruxCoach as well, not just by Android —
which is the point of retiring a key. A later rotation chains on from the
new certificate in exactly the same way.

A bare key change with no lineage remains a hard stop, and still shows
the manual-reinstall prompt below.

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
- Pin the same notice to the project's GitHub README until the
  rotation is complete.

### 2. Release — the new key alone, v3 only, with a lineage

From 0.2.5 on, the release key is a YubiKey at the maintainer's PC. The
release runner never holds a release key: it builds the APK unsigned, the
maintainer signs it on the PC, and the runner checks the result before
anything is published.

A rotated release is signed with APK Signature Scheme **v3 only**, by the
**new key alone**. Its v3 block carries a `SigningCertificateLineage`: a
chain in which the old key (`7f8308e8…1bc2c4`) cryptographically signs off on
the new one. Both Android's installer and CruxCoach's own certificate pin
accept the rotated APK **only** if that chain is present and leads back to the
certificate they already trust.

Why v3 only is enough:

- `minSdk` is 28, and Android verifies v3 from Android 9 (API 28) on, so every
  device the app installs on reads the v3 block. v1 and v2 would only serve
  devices the app no longer supports.
- With v1 and v2 off, the new key and `--lineage` are all apksigner needs. The
  old requirement to pass both keystores came from v1: apksigner refuses
  "v1 signing enabled but the oldest signer in the SigningCertificateLineage
  is missing".
- The in-app updater (0.2.2 and newer, so also 0.2.4) reads the signer only
  through PackageManager: `GET_SIGNING_CERTIFICATES`, which returns the whole
  certificate history, so the lineage matches the pinned old certificate;
  failing that `GET_SIGNATURES`. No path reads v1 files from the zip any more,
  so v1 is not needed.
- On ROMs where the modern path fails (seen on an HTC with Android 9, which
  returns no `signingInfo`), the updater falls back to `GET_SIGNATURES`.
  AOSP returns the oldest certificate of a rotated APK there, so checks unaware
  of rotation keep working, and that matches the pin. Where a ROM returns
  neither, the in-app update is refused and the update is manual: download
  the APK from the release page and install it over the installed app.
  Android verifies the lineage itself and accepts it, so there is no need to
  uninstall.

> **AGP cannot do this.** The Android Gradle Plugin's `signingConfigs`
> DSL has no lineage property, so `assembleRelease` alone can never
> produce a rotated APK that installs over an existing one. The release
> workflow therefore builds with `-PunsignedRelease=true`, which gives
> `androidApp-release-unsigned.apk`, and the lineage is applied by
> `apksigner` when the APK is signed.

**a. Create the lineage once**, offline, at the signing ceremony: from the
old keystore to the new key, while the new key is still a file, before it
goes onto the YubiKeys:

```
apksigner rotate --out lineage.bin --old-signer --ks old.jks --ks-key-alias OLD_ALIAS --new-signer --ks new-key-keystore --ks-key-alias NEW_ALIAS
```

Keep `lineage.bin` with the old keystore's backup. It is not secret, but
losing it means no further rotation can chain from this one.

**b. Sign each release on the PC**, v3 only, with the YubiKey and the
lineage. apksigner reaches the YubiKey through a PKCS#11 provider
(`--ks NONE --ks-type PKCS11 --provider-class …`).

The expected SHA-256 of `unsigned.apk` comes from the run, never from the
drop folder: the drop folder can be written from outside the run, the
run's log cannot. The maintainer's signing script takes it from the run's
"Waiting for the YubiKey signature" notice as an argument
(`sign-release.sh vX.Y.Z <sha256>`) and refuses on any mismatch.

```
apksigner sign <YubiKey options> --lineage lineage.bin --rotation-min-sdk-version 28 --v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true --v4-signing-enabled false --in unsigned.apk --out signed.apk
sha256sum signed.apk > signed.apk.sha256
```

`--v4-signing-enabled false` matters with a YubiKey that asks for a touch on
every signature: a v4 signature would be a second touch and an extra
`.idsig` file that nothing uses.

This was checked with throwaway keystores against apksigner 36.0.0 and
37.0.0: `verify` reports v3 only and exactly one signer, the new
certificate, and `lineage --print-certs` lists the old certificate first and
then the new one.

**c. Keep the rotation boundary at 28.** `--rotation-min-sdk-version`
decides from which API level the *new* key becomes the effective signer. The
apksigner default is `33`. It puts the rotation into a separate v3.1 block
for Android 13+ and leaves older devices on the old key. That is the wrong
choice for this app: with `minSdk = 28` and the boundary at `28`, the old key
is retired across the entire supported range, with no window left open.
The release workflow refuses an APK with a v3.1 block.

The boundary was verified on hardware (HTC U11, Android 9): with `28` the
rotated update installs, the lineage is recorded, and a later old-key APK
is refused with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. With the default, the
same device never records the rotation and accepts the old-key APK.

**d. What the release workflow does.** `.github/workflows/release.yml`
builds the unsigned APK, and `scripts/wait-for-release-signature.sh` puts
it as `unsigned.apk` (with `unsigned.apk.sha256`) into
`$CRUXCOACH_RELEASE_DROP/<tag>/`. It then waits up to 30 minutes for
`signed.apk` and `signed.apk.sha256` there. Before anything is published,
`scripts/verify-release-signature.py` requires:

- the same zip entries as the unsigned build, each with the same bytes,
  compression and alignment. Signing adds a signature block, never a file;
- `apksigner verify` passing, with v3 on, v1 and v2 off, and exactly one
  signer: the pinned release certificate (`RELEASE_CERT_SHA256` in the
  workflow). For a minSdk-28 APK apksigner reports v1 and v2 as false even
  when they are present, so the script also reads the APK Signing Block:
  a v3 block, no v2 and no v3.1 block. A v1 signature would show up as
  extra `META-INF` entries in the first check;
- `apksigner lineage` listing exactly the old certificate
  (`PREVIOUS_RELEASE_CERT_SHA256`), then the new one. The old certificate
  keeps its installed-data capability and has no rollback capability
  (`apksigner rotate`'s defaults), so updates keep the app's data and the
  old key cannot sign an update any more.

`scripts/publish-github-release.sh`, the by-hand path, runs the signature and
lineage checks as well; it has no unsigned build to compare with.

Only then does the signed APK go where the publishing steps take it from.
The new certificate's SHA-256 is pinned in `release.yml`,
`scripts/publish-github-release.sh` and `.apktrack/project.toml`, and
`scripts/release_workflow_policy_test.py` keeps those pins equal. APKTrack
stable checks against its live Root policy, which needs a new full version
with the new certificate before the first release with it;
`.apktrack/policy.unsigned.json` is the signed version 1 it started from and
stays as it is.

**e. Release notes** must include, near the top:
- A clearly labelled "Signing key changed" section.
- The SHA-256 of the new signing certificate (same value as announced
  in phase 1).
- A link back to this document.

### What a rotation costs, even when done correctly

1. **The old key is retired one install at a time.** A device records the
   lineage when it installs the rotated release; from then on Android and
   the app refuse an APK signed by the old key. A device that has not
   updated yet still trusts the old key. With the boundary at 28 (step c)
   no supported device stays on the old key once it has updated.
2. **Zapstore needs a proof for the new certificate first.** zsp 2.x
   reads the lineage itself: it publishes the new certificate as
   `apk_certificate_hash` and the old one as `apk_certificate_ancestor`.
   It publishes only with an active NIP-C1 proof for the *current*
   certificate (a proof for an ancestor does not count) that authorizes
   the publishing key. The proof for the new certificate therefore has to be
   on the relays before the first release with the new key. The release
   workflow checks this with `zsp check` before the GitHub release is public.
   Whether the Zapstore client offers the update across the rotation has
   not been verified here.
3. **Only installs running 0.2.2 or newer accept the rotation via the
   in-app updater**, and only where PackageManager hands the app the
   certificate history (see section 2 for the ROM exception). The lineage-aware certificate check shipped in
   0.2.2. Anyone still on 0.2.1 or older evaluates the rotated APK with
   the old rule, which compares against the current signer alone. Which
   of the three extraction paths wins there is ROM-dependent, so the
   outcome is not predictable — treat 0.2.1 and older as "must
   reinstall manually".

Consequence for sequencing: **0.2.2 has to be widely installed before
a future release rotates the key.** Rotating earlier does not fail loudly; it
strands users quietly.

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

- Changing CI infrastructure (GitHub Actions runner, GitHub or
  Codeberg organization settings, etc.) — the *signing key* is what
  matters, not where the build runs.
- Switching maintainers — as long as the keystore is handed over
  intact, the signing key is unchanged.
- Switching from one release artifact name to another — the updater
  pins the cert hash, not the file name.

If you are a maintainer about to do anything that *might* change the
signing certificate fingerprint and you are not sure, build a release
APK in a test environment, run `apksigner verify --print-certs
cruxcoach-release.apk`, and compare the SHA-256 to the hash currently
pinned in installed devices. If they match, you have not rotated the
key — proceed normally.

## See also

- `docs/specs/0.1.2/FEAT-004-auto-update.md` — the in-app updater spec
  that references this document (§5.4.3, §6.9).
- `CLAUDE.md` — repository rules for keystore files (`.signing/`,
  `local.properties`, never committed).
