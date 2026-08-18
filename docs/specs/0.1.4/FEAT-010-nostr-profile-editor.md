---
status: implementation
---
# Feature Spec: Nostr Profile Editor (Kind-0) Polish (v0.1.4)

> **Status:** Implementation (0.1.4) — flagged 2026-05-06. Four
> tranches landed on `feat/0.1.4-release`:
>
> - **Tier 1** (`96cb1a5`): schema migration `secure/4.sqm` + new
>   Kind-0 fields (`banner`, `nip05`, `website`) wired through
>   `NostrProfileData` / `NostrProfileManager.publishProfile()` /
>   `NostrProfileViewModel` / `NostrProfileScreen`.
> - **Tier 2** (`7f1441b`): `Nip05Verifier` + `LnurlVerifier`
>   (Amethyst-style sealed-state classes, no-redirect on NIP-05),
>   green ✓ / red ✗ / amber ⚠ trailing icons on the relevant fields,
>   on-blur and eager-on-load triggering. 8 JVM unit tests for the
>   parser helpers.
> - **Tier 3** (`0cba54d`): image upload pipeline —
>   `ImageProcessor` (two-pass Bitmap decode → JPEG q=85 at the
>   per-target long-edge cap) + `ProfileImageUploader` (BUD-02
>   single-shot, reuses `BlossomUploader.blossomAuthHeader` for
>   Kind-24242 auth, no in-app cropper per the Amethyst
>   reference). UI: 3:1 banner + 1:1 circular picture edit areas
>   at the top of the editor, system gallery picker, Coil 2.7.0
>   for inline rendering, progress overlay during upload.
> - **Tier 4** (`0cba54d`, bundled with Tier 3): markdown preview
>   for `about` via `compose-richtext` 0.20.0 (upstream of the
>   fork Amethyst vendors), character counter "X / 500" turning
>   red over budget.
>
> M5 (FEAT-008 pre-fill `applyKilterPrefill`) deferred — FEAT-008
> sits in 0.2.0 now (`90ce8fb` retarget). Wire-up will land
> alongside FEAT-008's M2 backbone work.
>
> Remaining gates before "Shipped": device-side visual QA
> (Maestro + manual on a real Kilter login pubkey to verify NIP-05
> verification flow against `cruxcoach.org` once that domain is
> NIP-05-configured). Optional polish: dirty-state "Discard
> changes?" prompt (§4.2), gradient placeholder for banner-less
> profiles already in (Tier 3). Strings reviewed across EN+DE
> (Tier 1+2+3+4 each added matching pairs).
>
> **Depends on:**
> - Existing `NostrProfileViewModel` + `NostrProfileScreen` (rudimentary
>   form with displayName / about / lud16 / picture-as-URL).
> - Existing `NostrProfileManager.publishProfile()` (Kind-0 publish path).
> - Blossom blob storage (already used for backup encryption).
>
> **Blocks:** none directly. FEAT-008's pre-import "fill blank Nostr
> fields from Kilter" gate (§3.5.1 Gate 2) calls into this spec's
> editor.

---

## 1. Overview

CruxCoach already has a Kind-0 profile editor (`NostrProfileScreen`),
but it's a basic form: four text fields (displayName, about,
lightningAddress, pictureUrl), no image upload, no banner, no
verification, no markdown preview. The result is a Kind-0 metadata
event that's functional but visibly unpolished compared to mainstream
Nostr clients.

This feature brings the editor up to parity with **Amethyst** (the
de-facto reference Android Nostr client) for the fields CruxCoach
actually uses, plus the cross-cutting integrations CruxCoach needs
(Kilter pre-fill from FEAT-008, climber-profile co-existence with
FEAT-009).

### Goals

- Profile picture: in-app crop + upload to Blossom (or NIP-94 imeta
  publication), no manual URL pasting.
- Banner image: same upload path as picture, separate aspect ratio.
- About field: multi-line, markdown-rendered preview, character
  counter (Nostr Kind-0 has no hard limit but Amethyst-style ~500
  char practical cap).
- Lightning-address field: validated against `lud16` regex + a
  best-effort LNURL fetch to confirm the address resolves.
- NIP-05 verification: optional field; on save, fetch the well-known
  endpoint to confirm and surface a green checkmark when valid.
- FEAT-008 integration: pre-import "fill blank fields from Kilter"
  dialog drives this editor's set-functions directly without round-
  tripping through the UI.
- Co-exist cleanly with FEAT-009's Kind-30080 climber-profile event
  (separate event, separate publish action, separate state).

### Non-Goals

- Multiple Nostr identities per CruxCoach install. Single-key model
  per memory `project_kilter_auth_decision.md` and `project_brand_decision.md`.
- NIP-39 external identities (Mastodon / Twitter / GitHub
  attestations). Defer to post-0.1.4.
- Reactive profile change broadcasts (NIP-65 NIPS). The existing
  publish-on-save flow is sufficient.
- Editing Kind-3 follow lists. Outside profile-editor scope.
- Banner/picture image moderation or content scanning.

---

## 2. Background

### 2.1 What's there today

`androidApp/src/main/java/com/cruxcoach/android/ui/settings/NostrProfileScreen.kt`:
four `OutlinedTextField`s in a `Column`, a publish button, no preview.

`NostrProfileViewModel.kt:33-37` state:

```kotlin
data class NostrProfileState(
    val displayName: String = "",
    val lightningAddress: String = "",
    val pictureUrl: String = "",
    val about: String = "",
    // ...
)
```

`NostrProfileManager.publishProfile()` builds a Kind-0 event:

```kotlin
val content = JSONObject().apply {
    displayName?.takeIf { it.isNotBlank() }?.let { put("name", it) }
    lightningAddress?.takeIf { it.isNotBlank() }?.let { put("lud16", it) }
    picture?.takeIf { it.isNotBlank() }?.let { put("picture", it) }
    about?.takeIf { it.isNotBlank() }?.let { put("about", it) }
}.toString()
```

### 2.2 Amethyst's profile editor — reference

`vitorpamplona/amethyst` is the most-used Android Nostr client and
sets the bar for profile-editor UX in this ecosystem.

Key elements of Amethyst's `EditProfileScreen` (observed in their
source as of 2026-04):
- **Banner**: full-width header image with edit-pencil overlay.
- **Profile picture**: 120 dp circular crop, overlapping the banner
  bottom edge by 50 %.
- **Form fields**:
  - Display name (`name`)
  - Username/handle (`display_name` — historical Nostr ambiguity,
    Amethyst maps to "Username" UI label)
  - About (multi-line, markdown-aware)
  - Website (`website`)
  - Lightning address (`lud16`) with validation
  - NIP-05 (`nip05`) with on-save fetch + green checkmark
- **Image upload** flow:
  1. Tap edit-pencil → Android system picker.
  2. In-app crop to circle (picture) or 3:1 rect (banner).
  3. Upload to user-configured Blossom server (Amethyst defaults to
     `nostr.build` and `void.cat`).
  4. Blossom returns URL → form field auto-populates.
  5. On profile save, the URL goes into the Kind-0 `picture`/`banner`
     field.
- **Save** publishes the Kind-0 event to all configured relays.

### 2.3 Blossom in CruxCoach

CruxCoach already uses Blossom for encrypted backup blobs (`BlossomSync`
+ `BackupRepository`). The same client can upload profile/banner
images. We pick a sensible default Blossom server (configurable in
Settings advanced).

### 2.4 Where the existing FEAT-008 gate calls in

FEAT-008 §3.5.1 Gate 2 ("fill blank Nostr fields from Kilter") needs
to:
1. Read current Kind-0 state via `NostrProfileViewModel` or
   `NostrProfileManager.getProfileFromCache(ownPubkey)`.
2. Identify blank fields: `name`, `picture`, `about`.
3. Read Kilter user-profile fields: `display_name`, `avatar_url`, `bio`
   (whatever the API exposes — block on FEAT-008 spike).
4. For each blank Kind-0 field with a non-blank Kilter equivalent,
   present a pre-filled checkbox.
5. On confirm, call this spec's editor with `setDisplayName` /
   `setPictureUrl` / `setAbout` and trigger the publish flow.

---

## 3. Design

### 3.1 New profile editor screen layout

```
┌───────────────────────────────────────────────┐
│                                               │
│   [           BANNER IMAGE              ✏️]    │  ← 3:1 ratio
│                                               │
│        ┌─────────────┐                        │
│        │             │                        │
│        │  PROFILE    │ ✏️                     │  ← circular, overlaps banner
│        │   IMAGE     │                        │
│        │             │                        │
│        └─────────────┘                        │
│                                               │
├───────────────────────────────────────────────┤
│                                               │
│  Display name                                 │
│  [Alice K.                                  ] │
│                                               │
│  About                                        │
│  ┌─────────────────────────────────────────┐  │
│  │ Climbing since 2018, mostly Kilter…      │  │
│  │                                          │  │
│  │ [preview ✓]               142 / 500     │  │
│  └─────────────────────────────────────────┘  │
│                                               │
│  Lightning address                            │
│  [alice@walletofsatoshi.com  ✓]               │  ← green check on valid LNURL
│                                               │
│  NIP-05 verification                          │
│  [alice@cruxcoach.org        ✓]               │  ← green check on well-known fetch
│                                               │
│  Website                                       │
│  [https://aliceclimbs.com                  ]  │
│                                               │
├───────────────────────────────────────────────┤
│                                               │
│         [ Cancel ]      [ Veröffentlichen ]   │
│                                               │
└───────────────────────────────────────────────┘
```

### 3.2 Fields and Kind-0 mapping

| UI label | Kind-0 JSON key | Validation |
|---|---|---|
| Display name | `name` | Length 1–80 |
| About | `about` | Length 0–500, markdown rendered |
| Lightning address | `lud16` | RFC 5321 + `<local>@<domain>` + best-effort LNURL probe |
| NIP-05 | `nip05` | RFC 5321 + on-save fetch of `https://<domain>/.well-known/nostr.json?name=<local>` |
| Website | `website` | URL-parseable, HTTPS preferred (warning if HTTP) |
| Profile image | `picture` | URL pointing to a Blossom-uploaded image |
| Banner image | `banner` | URL, same path, 3:1 aspect |

Existing `NostrProfileManager.publishProfile()` already handles `name`,
`about`, `lud16`, `picture`. Add `nip05`, `website`, and `banner` as
new optional parameters.

### 3.3 Image upload flow

```
User taps banner-edit-pencil
  ↓
SystemContentPicker (image/*)
  ↓
CropActivity (in-app, jet-pack-compose-cropper)
   - banner: 3:1 rect crop
   - picture: 1:1 circle crop
  ↓
Compress (JPEG q=85) + max 1024×… for picture, 1920×640 for banner
  ↓
BlossomUploader.upload(bytes, "image/jpeg")
   - retries + exponential backoff
   - returns BlossomDescriptor(url, sha256, size)
  ↓
NostrProfileViewModel.setPictureUrl(url) (or setBannerUrl)
  ↓
ImagePreview composable shows the new image inline
```

Failure modes:
- Picker cancelled: no-op, retain existing image.
- Crop cancelled: same.
- Upload failure: snackbar "Hochladen fehlgeschlagen — erneut
  versuchen?" with retry button.
- Blossom server returns non-2xx: same snackbar; specific error in
  log only.

### 3.4 Validation flows

**Lightning address (`lud16`):**

On blur of the field, do a best-effort fetch:
```
GET https://<domain>/.well-known/lnurlp/<local>
```
- 200 + valid JSON with `callback`/`metadata`: green ✓ checkmark.
- Anything else: amber ⚠️ "konnte nicht geprüft werden" — but we still
  allow saving. A wallet-side user might use a custodial address
  whose well-known endpoint is blocked by their provider.
- Cached for 1 hour.

**NIP-05 (`nip05`):**

On blur:
```
GET https://<domain>/.well-known/nostr.json?name=<local>
```
- 200 + JSON containing `names[<local>]` matching the local user's
  pubkey: green ✓.
- Mismatch: red ✗ "Pubkey stimmt nicht überein". Block save with this
  field non-empty until fixed.
- 404 / network: amber ⚠️ "konnte nicht geprüft werden". Allow save
  but with a warning toast.

### 3.5 Markdown preview for `about`

Use the existing markdown-rendering library if any (`commonmark`
or similar). If none is in dependencies yet, add `Jetpack Compose
Markdown` (`com.mikepenz:multiplatform-markdown-renderer`,
small footprint). Preview rendered inline below the editor in a
small box.

### 3.6 FEAT-008 pre-fill integration

`NostrProfileViewModel` exposes:

```kotlin
fun applyKilterPrefill(
    nameFromKilter: String?,
    pictureUrlFromKilter: String?,
    aboutFromKilter: String?,
) { … }
```

Called by FEAT-008's pre-import dialog once the user confirms which
fields to import. Sets state; user is then dropped into the editor
to review and tap "Veröffentlichen". Or, if the user already confirmed
"übernehmen + sofort publizieren", the function publishes immediately
and returns control.

The Kilter `picture_url` is fetched once and re-uploaded to our
Blossom server (so the user's profile image isn't dependent on Kilter
hosting). Image-fetch fails → fall back to the original Kilter URL
with a warning.

### 3.7 Co-existence with Kind-30080 climber profile (FEAT-009)

Two separate events:
- **Kind-0**: identity + look. Edited here.
- **Kind-30080** (Stage 2+ of FEAT-009): climber-strength state +
  style vector. Computed by FEAT-009, published from a separate
  Settings screen ("Climber-Profil"), not in this editor.

The two screens link to each other for navigability. No shared state
beyond pubkey.

---

## 4. UX Details

### 4.1 Loading state

On entry, fetch latest Kind-0 from cache then relays. Show a skeleton
for the form while loading. ~1s typical with relays available; cache-
hit is instant.

### 4.2 Dirty state and unsaved-changes

Track per-field dirty flags. If the user navigates away with dirty
fields, prompt "Änderungen verwerfen?". Save flow always re-publishes
the full Kind-0 (Nostr replaceable: latest event wins).

### 4.3 Publish state

Publish button shows progress while the Kind-0 event is signed and
broadcast. On success: "Profil aktualisiert" snackbar. On
all-relays-rejected: red snackbar + retry button.

### 4.4 Empty profile guidance

If the user lands on a fresh profile screen (all fields blank):
top-of-screen banner suggests "Tipp: Importiere dein Profil von
Kilter" linking to FEAT-008's Settings → Kilter-Konto entry. Suppress
once any field is non-empty.

### 4.5 Banner-less old profiles

For users without a banner image set, show a subtle gradient
placeholder (orange-accent → dark-background) instead of an empty
rectangle. Reduces visual emptiness for fresh accounts.

---

## 5. Schema and Storage

No DB schema changes. Existing `nostr_profiles` cache table holds the
profile fields; add columns for `banner_url`, `nip05`, `website`:

```sql
ALTER TABLE nostr_profiles ADD COLUMN banner_url TEXT;
ALTER TABLE nostr_profiles ADD COLUMN nip05 TEXT;
ALTER TABLE nostr_profiles ADD COLUMN website TEXT;
```

Migration: SQLDelight numbered migration, idempotent.

`BlossomUploader` is a new module wrapping the existing Blossom client
plumbing for image-blob uploads (≠ encrypted backup blobs). Image
blobs are public, unencrypted.

---

## 6. Localized Strings

```xml
<!-- EN -->
<string name="nostr_profile_banner_label">Banner image</string>
<string name="nostr_profile_banner_change">Change banner</string>
<string name="nostr_profile_picture_change">Change profile picture</string>
<string name="nostr_profile_nip05_label">NIP-05 identifier</string>
<string name="nostr_profile_nip05_hint">name@domain — verifies your identity</string>
<string name="nostr_profile_nip05_verified">Verified</string>
<string name="nostr_profile_nip05_mismatch">Pubkey doesn't match</string>
<string name="nostr_profile_nip05_unreachable">Couldn't verify (offline?)</string>
<string name="nostr_profile_website_label">Website</string>
<string name="nostr_profile_lud16_verified">Lightning address active</string>
<string name="nostr_profile_lud16_unreachable">Couldn't verify Lightning address</string>
<string name="nostr_profile_about_count">%1$d / %2$d</string>
<string name="nostr_profile_publishing">Publishing profile…</string>
<string name="nostr_profile_publish_failed">Couldn't publish — retry?</string>
<string name="nostr_profile_published">Profile updated</string>
<string name="nostr_profile_image_upload_failed">Image upload failed</string>
<string name="nostr_profile_kilter_prefill_hint">Tip: Import your profile from Kilter</string>
<string name="nostr_profile_dirty_discard_title">Discard changes?</string>
<string name="nostr_profile_dirty_discard_message">You have unsaved profile changes.</string>
```

DE equivalents in `values-de/strings.xml`.

---

## 7. Implementation Plan

### 7.1 Milestones

| Milestone | Deliverable |
|---|---|
| M1 | Banner + picture upload via Blossom. In-app crop. New ViewModel state for both image URLs. |
| M2 | NIP-05 + Lightning address validation flows with caching. |
| M3 | Markdown preview for `about` + character counter. |
| M4 | Website field + UI polish (gradient placeholder, dirty-state prompt). |
| M5 | FEAT-008 pre-fill API: `applyKilterPrefill` exposed and integrated with the import gate. |
| M6 | DE/EN string review + visual QA against Amethyst. |

### 7.2 Files touched

| File | Scope |
|---|---|
| `NostrProfileScreen.kt` | Major rework — banner header + profile picture overlay + new fields |
| `NostrProfileViewModel.kt` | Extend state, add validation flows + `applyKilterPrefill` |
| `NostrProfileManager.kt` | Extend `publishProfile()` with `banner`, `nip05`, `website` parameters |
| `BlossomUploader.kt` (new) | Public-image upload wrapper around the existing Blossom client |
| `ImageCropScreen.kt` (new) | Compose crop UI |
| `Nip05Verifier.kt` (new) | well-known JSON fetch + cache |
| `LnurlVerifier.kt` (new) | LNURL pay-info probe + cache |
| `nostr_profiles.sq` schema migration | Add 3 columns |
| `values/strings.xml`, `values-de/strings.xml` | Add ~15 keys per side |

### 7.3 Dependencies to add

- `com.mikepenz:multiplatform-markdown-renderer` — for `about` preview
- A jetpack-compose image cropper. Candidates:
  - `com.mr0xf00:easycrop` (small footprint, Compose-native)
  - `com.canhub:cropper` (View-based, slightly heavier)

Pick one in M1 spike.

---

## 8. Edge Cases

| Case | Handling |
|---|---|
| User pastes an HTTP (non-S) URL into picture or banner field | Allow with amber warning chip "unsicher: HTTP" |
| Blossom server returns 4xx | Snackbar with specific error code; retry button. Allow user to switch Blossom server in advanced settings. |
| User has 1 GB image | Compress before upload. If still >5 MB, hard-reject with "Bild zu groß — bitte komprimieren". |
| NIP-05 domain returns CORS-restricted response | Browser/WebView would fail; on Android we use OkHttp directly so CORS is N/A. |
| Lightning address points to a custodial wallet that returns 404 on probe | Amber warning, allow save. Many custodial wallets don't expose well-known publicly. |
| User publishes profile while another publish is in-flight | Disable publish button while in-flight. Second tap is a no-op. |
| Blossom upload succeeds but Nostr publish fails | Image is on Blossom but unreferenced. Acceptable garbage; Blossom server's GC handles eventually. Don't try to delete on failure. |
| Pubkey rotation invalidates NIP-05 verification | Re-verify on next profile open. Surface "needs revalidation" status. |
| Two devices edit the same profile concurrently | Last write wins (Nostr replaceable). Both devices end up with the latest event after relay sync. |

---

## 9. Privacy & DSGVO

- Profile fields are *intentionally public* (Kind-0 is a public Nostr
  event). Users see a clear "Public profile — visible to anyone with
  your pubkey" disclaimer at the top of the editor.
- Blossom-uploaded images live on a public server with content-
  addressed URLs. No content scanning or moderation; the user is
  responsible for what they upload.
- Lightning address verification doesn't leak the user's identity to
  arbitrary third parties — only the user's chosen LN provider.
- NIP-05 verification is a public assertion of identity at a domain;
  same disclaimer.

---

## 10. Open Questions

1. **Default Blossom server**: do we ship with `blossom.primal.net`
   (which we already use for backup) or a separate public-image
   server like `nostr.build`? Recommendation: make it configurable
   with `nostr.build` as default for images (separation of concerns:
   backup blobs vs. public images).
2. **Cropper library**: spike both candidates in M1 and pick on
   binary size + UX feel.
3. **NIP-05 issuance**: should CruxCoach run a `cruxcoach.org`
   NIP-05 service for users? (`alice@cruxcoach.org` issued on
   account creation.) Out of scope for this spec; proposed for
   FEAT-011 if the project decides to operate the service.
