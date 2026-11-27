---
status: backlog
queue: parked
target: after 0.2.5 (owner 2026-10-25, F8: size accepted for 0.2.5)
base: feat/0.2.5-training-nutrition
depends_on: [FEAT-068, FEAT-069]
created: 2026-10-25
---

# Feature Spec: Smaller APK – product database and photo library on demand

> **Status:** Backlog. The owner accepted the larger APK for 0.2.5 (F8) and
> asked for the slimming as a backlog item. Numbers from the tn-025 data audit
> (2026-10-25, debug transport APKs; release estimated).

## 1. Problem

Training & nutrition makes the APK about **+30 MB (+44 %)** larger in debug,
about **+25 MB** in release:

- `off_products.db.zst` – the packaged-products database (Open Food Facts
  extract), **17.5 MB**, bundled in every APK. It is stored deflate-compressed
  (type 8) although it is already zstd-compressed: 17,497,973 instead of
  17,492,653 bytes, no gain, but extra work at install.
- The native photo-recognition library, **5.3 MB** uncompressed, in every APK,
  also on phones that cannot run the model (no ARMv8.2 dot product, little RAM).
- On the phone, opening Nutrition unpacks the product database plus its search
  index (**51.6 MB**) in the background (`OffRepository.prepareInBackground`).

Every update and the offline P2P APK hand-over carry this weight; the P2P
hand-over is size-sensitive (140 MB timed out earlier, debug is now ~100 MB).

## 2. Proposal

1. **`noCompress("zst")`** in the packaging options – no behaviour change, a
   cleaner APK. (Gradle build file: trust boundary, owner review.)
2. **Product database on demand.** Not bundled; the first "Find food" with
   products asks once ("Download 18 MB product list? Wi-Fi recommended") and
   fetches it over the existing signed channel of `FoodProductsSync`
   (hash-checked, same source as the update). Generic foods (BLS, USDA) and
   the own foods stay bundled, so search works offline from the start.
3. **Photo library with the model.** Load the native library together with the
   optional 2–4 GB model instead of bundling it. Needs a signature check of
   the native code before loading – larger work, separate security review.

## 3. Acceptance

- Release APK at least 17 MB smaller with (2); with (3) about 22 MB.
- Product search without the download: says that the product list is not
  there yet and offers it; BLS/USDA/own foods still found.
- Download interrupted / hash wrong → nothing half-installed, retry possible.
- P2P APK hand-over tested once with the new size.

## 4. Risks

- (2) adds a network step to a feature that works offline today; the app must
  say why and offer it only on Wi-Fi by default (as the product update does).
- (3) loads native code downloaded after install – only with a verified
  signature, otherwise not at all.
