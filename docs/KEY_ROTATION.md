# Android Signing Key Rotation

> Audience: CruxCoach end users and the maintainer team. This document
> describes both what users see if the signing key ever changes, and the
> developer procedure for rotating it.
>
> Status: written 2026-04-21 alongside FEAT-004 (in-app auto-updater).
> This document is a release blocker for v0.1.2 — the in-app updater
> refers users here when it detects a signing-cert mismatch.

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
- Pin the same notice to the project's Codeberg README until the
  rotation is complete.

### 2. Release — sign with the new key **and a v3 lineage**

Since 0.2.2 the release APK is signed with APK Signature Scheme **v3**
(`enableV3Signing = true`). v3 is what carries a
`SigningCertificateLineage`: a chain in which the old key
cryptographically signs off on the new one. Both Android's installer and
CruxCoach's own certificate pin accept a rotated APK **only** if that
chain is present and leads back to the certificate they already trust.

> **AGP cannot do this.** The Android Gradle Plugin's `signingConfigs`
> DSL has no lineage property, so `assembleRelease` alone can never
> produce a rotated APK that installs over an existing one. The lineage
> has to be applied with `apksigner` as a post-build step. This is the
> single most important operational fact in this document.

> The commands below were executed end-to-end against apksigner 36.0.0
> with throwaway keystores before being written down. Deviating from
> them is likely to fail — see the trap in step b.

**a. Create the lineage once**, from the old keystore to the new one:

```
apksigner rotate --out .signing/lineage.bin --old-signer --ks .signing/old.jks --ks-key-alias OLD_ALIAS --new-signer --ks .signing/new.jks --ks-key-alias NEW_ALIAS
```

Keep `lineage.bin` next to the keystores and back it up with them. It is
not secret, but losing it means no further rotation can chain from this
one.

**b. Build, then re-sign — with BOTH signers.**

The obvious command (new key + `--lineage`) does **not** work. apksigner
rejects it outright:

```
v1 signing enabled but the oldest signer in the SigningCertificateLineage is missing.  Please provide the oldest signer to enable v1 signing
```

Both keystores have to be passed, old first, joined by `--next-signer`.
That is not a formality: the **old** key signs v1/v2 and the **new** key
signs v3, and that split is exactly what makes the rotation seamless —
devices below the rotation boundary keep verifying against the key they
already trust.

```
./gradlew :androidApp:assembleRelease && apksigner sign --ks .signing/old.jks --ks-key-alias OLD_ALIAS --next-signer --ks .signing/new.jks --ks-key-alias NEW_ALIAS --lineage .signing/lineage.bin --rotation-min-sdk-version 28 --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true androidApp/build/outputs/apk/release/androidApp-release.apk
```

**c. Choose the rotation boundary deliberately** —
`--rotation-min-sdk-version` decides from which API level the *new* key
becomes the effective signer. Below it, the *old* key keeps signing.
Both variants were verified:

| `--rotation-min-sdk-version` | API 26–27 | API 28–32 | API 33+ | Old key retired for… |
|---|---|---|---|---|
| `33` (apksigner default) | old key | old key | new key | Android 13+ only |
| `28` | not applicable¹ | new key | new key | every supported device |

¹ From 0.2.3 `minSdk` is 28, so Android 8.0/8.1 are not offered the
release at all — 0.2.2 tells those users it is their last version
(`DeviceSupportGate`). Without that minSdk bump, `28` would leave them
with a rejected update instead.

**Use `28`.** The apksigner default of `33` is the safe choice for a
*hygiene* rotation on a wide minSdk range, because nobody is stranded.
It is the wrong choice for a **compromised** key: anyone holding the old
key could still sign something that every device below Android 13
accepts. With `minSdk = 28` and the boundary at `28` the two line up and
the old key is retired across the entire supported range, with no window
left open.

Both rows were verified on hardware (HTC U11, Android 9): with `28` the
rotated update installs, the lineage is recorded, and a subsequent
old-key APK is refused with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. With
the default, the same device never even records the rotation and accepts
the old-key APK.

**d. Verify before publishing.** With the default boundary, `verify`
lists *two* signers with explicit API ranges; with `28` it collapses to
one. So do not check "are two certificates listed" — check the scheme
and the ranges:

```
apksigner verify --print-certs -v androidApp/build/outputs/apk/release/androidApp-release.apk | grep -E "Verified using v3|certificate SHA-256"
```

`Verified using v3 scheme … : true` must appear. If it does not, the
lineage was not applied and the release **must not** be published — it
would break every existing install irrecoverably.

**e. Release notes** must include, near the top:
- A clearly labelled "Signing key changed" section.
- The SHA-256 of the new signing certificate (same value as announced
  in phase 1).
- A link back to this document.

### What a rotation costs, even when done correctly

1. **The old key is not retired everywhere at once.** See the table in
   step c — below the rotation boundary the old key keeps signing, by
   design. There is no configuration in which a rotation instantly
   invalidates the old key for all supported devices while keeping them
   updatable.
2. **Zapstore does not understand lineages.** Per the 2026-07-28
   research, both `zsp` and the Zapstore client publish and compare only
   the *current* signer certificate, and the client hard-blocks the
   update button on a mismatch with no override. The listing identity
   survives (it keys on package name + publisher npub, not the
   certificate), and the data model reads `apk_certificate_hash` as a
   set — so an event carrying both the old and the new hash lifts the
   block — but that has to be assembled by hand and is not a documented
   or maintainer-supported path.
3. **Only installs running 0.2.2 or newer accept the rotation via the
   in-app updater.** The lineage-aware certificate check shipped in
   0.2.2. Anyone still on 0.2.1 or older evaluates the rotated APK with
   the old rule, which compares against the current signer alone. Which
   of the three extraction paths wins there is ROM-dependent, so the
   outcome is not predictable — treat 0.2.1 and older as "must
   reinstall manually".

Consequence for sequencing: **0.2.2 has to be widely installed before
0.2.3 rotates the key.** Rotating earlier does not fail loudly; it
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

- `docs/specs/0.1.2/FEAT-004-auto-update.md` — the in-app updater spec
  that references this document (§5.4.3, §6.9).
- `CLAUDE.md` — repository rules for keystore files (`.signing/`,
  `local.properties`, never committed).
