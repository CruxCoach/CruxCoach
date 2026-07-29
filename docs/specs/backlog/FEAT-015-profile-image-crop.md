---
status: backlog
---
# Feature Spec: Profile Image Crop UI (backlog)

> **Status:** Backlog — captured 2026-05-07. Spawned out of FEAT-010
> Tier 4 (image upload) once the basic upload pipeline shipped: users
> currently get whatever the picker hands us, scaled to the long edge,
> with a Coil `ContentScale.Crop` center-crop at render time. There is
> no way to choose *which part* of the source image becomes the
> profile picture / banner — the displayed crop is dictated by the
> image's aspect ratio + Coil's center-anchored fit.
>
> **Relates to:**
> - FEAT-010 (Nostr Profile Editor) — extends the picture / banner
>   upload tranche with an in-flow crop step. Spec lives separately
>   because it pulls in a non-trivial dependency or custom Compose
>   surface, neither of which fits into FEAT-010's polish scope.
>
> **Out of scope here (deliberately):**
> - In-app full image editor (rotate, brightness, filters). The crop
>   UI is *only* for picking the visible region.
> - Crop for non-profile images (climb beta photos, etc.). Those have
>   different aspect-ratio constraints; a separate spec when those
>   features ship.

## 1. Overview

The Nostr profile editor lets the user replace their profile picture
(1:1, ≤1024px long edge) and banner (3:1, ≤1920px long edge) by
picking from the system Photo Picker. The picked image is currently
scaled in `ImageProcessor.loadAndCompress` to the long-edge limit,
JPEG-compressed at q=85, and uploaded to Blossom. Whatever the source
aspect ratio happens to be is what other Nostr clients render — most
client UIs apply a center-crop at display time, which often clips
faces / important content out of frame.

This feature adds an in-flow crop step between picker and upload:

- **Profile picture**: square (1:1) crop frame, draggable + zoomable
  over the source image. Confirm bakes the crop into the uploaded
  bitmap so every Nostr client sees the same image.
- **Banner**: 3:1 crop frame, same interaction.

### Goals

- The user picks the visible region, not Coil
- Output is a cropped bitmap at the spec's max dimension; no
  CropContentScale.Crop guesswork at render time
- Works with HEIF/AVIF/JPEG/PNG input (FEAT-010 already covers the
  decode side via `ImageProcessor.decodeWithImageDecoder`)
- One-shot flow inside the profile editor — no separate full-screen
  navigation hop, modal at most

### Non-Goals

- Cropping for already-uploaded images (reverse pipeline)
- NIP-09 deletion of replaced Blossom blobs (orphaned blob is fine —
  Blossom is content-addressed)
- In-app crop history / undo

---

## 2. UX Flow

```
[Profile Screen]
    Tap pencil on picture or banner
        ↓
    [PickVisualMedia]
        ↓
    Picker returns Uri
        ↓
    [Crop bottom-sheet / modal]   ← new screen this spec adds
        - Source image rendered
        - Aspect-ratio-constrained crop frame
        - Drag, pinch-zoom
        - Confirm / Cancel
        ↓                  ↘
   [Confirm]            [Cancel]
        ↓                   ↓
   Crop applied to       returns to profile
   bitmap, then            without changes
   loadAndCompress
   produces cropped bytes
        ↓
   ProfileImageUploader
   pushes bytes to Blossom
        ↓
   pictureUrl / bannerUrl
   updated, save flow
   continues as today
```

Cancel is mandatory — the user might pick the wrong photo and need
to back out without aborting the entire profile-edit session.

---

## 3. Implementation Options

### A. Compose-native crop library

Drop in something like
[`SmartToolFactory/Compose-Cropper`](https://github.com/SmartToolFactory/Compose-Cropper)
(Apache 2.0). Pros: tested, handles pinch-zoom + pan + aspect-ratio
constraints out of the box. Cons: ~500 KB extra in the APK; one more
dependency to track for security / supply-chain.

### B. Custom Compose crop surface

Hand-build with `PointerInput` + `Canvas` + a transform matrix.
Pros: zero new deps, exact UX control. Cons: ~200-400 LOC of crop
math (touch slop, gesture rotation, edge-snap), and the corners-
of-rectangles UI nobody wants to maintain.

### C. Android `ACTION_CROP` intent

Built-in but [deprecated since Android 4](https://developer.android.com/about/versions/14/behavior-changes-14#crop) and OEM-unreliable.
Don't.

**Recommended:** A. The library is small, well-tested, and we can
swap it for a custom surface later if maintenance becomes a problem.

---

## 4. Technical Notes

- **Where the crop runs**: in-process, on the source bitmap loaded
  via `ImageProcessor.loadAndCompress` (less the final compress step).
  Then crop bitmap → compress → upload.
- **Memory bound**: source images can be 12+ MP. The decoder already
  applies sampleSize / setTargetSize to bring the working bitmap
  under ~2× the target's long edge; the crop widget operates on
  *that* downscaled version, not the raw 12 MP source.
- **Output**: the cropped bitmap is exactly `maxDimension × maxDimension`
  for picture or `maxDimension × maxDimension/3` for banner — so
  Coil's `ContentScale.Crop` becomes a no-op on render (the source
  matches the slot's aspect ratio exactly).

---

## 5. Risks

- **Picker UX delta**: photo picker → crop modal adds one tap
  before the upload starts; users tapping "change picture" twice may
  lose patience if the modal is slow to appear. Mitigation: show the
  source image immediately on modal open, lazy-load the
  high-res once.
- **HEIF crop on legacy Android**: `ImageDecoder.createSource(file)`
  on API 28+ already handles HEIF; FEAT-010 has the temp-file
  fallback. Crop just reuses the decoded bitmap.
- **Library churn**: if option A's library goes unmaintained, we'd
  be in scope-A→B migration territory. Realistic given the small,
  one-off nature of profile crops; stay vigilant.

---

## 6. Open Questions

- Does the same crop modal serve both profile-picture and banner,
  or two separate composables with hard-coded aspect ratios?
- How do we expose "use original aspect ratio" for users who want
  no crop (e.g. a rectangular banner is uploaded as-is, no crop
  forced)? Suggest: defaulting to source-aspect-ratio when it
  already matches the slot ±5%, and only nudging into the
  spec'd ratio otherwise.
- Should the cropped result land back in `pictureUrl` / `bannerUrl`
  before save (current model: the upload runs immediately on crop
  confirm) or stage in the editor first? Current flow is "upload
  on pick", so Confirm runs the existing upload pipeline with the
  cropped bytes.
