---
status: implemented-on-branch
queue: active
base: feat/0.2.4-training-body@a49624dc6
branch: feat/0.2.4-food-photo
depends_on: [FEAT-068]
created: 2026-10-04
---

# Feature Spec: Food photo recognition on the device (0.2.4)

Owner request 2026-10-04: offer what MCI does with the paid FatSecret API
(photo → foods → nutrition estimate) without a paid API and without sending
photos anywhere. Hardware that cannot run it shows the feature greyed out.

## Flow

1. Fueling screen → "Photo". The button is always visible; on unsupported
   hardware it is greyed out and a tap explains why.
2. First use: download one model (one-time, Wi-Fi by default, system
   notification), SHA-256 check, then a probe run on a drawn plate.
3. Photo (camera intent or photo picker) → scaled to 640 px → model in the
   `:vision` process → JSON list `{name_de, name_en, grams}` (grammar
   constrained) → each name matched against BLS 4.0 → review list.
4. Review: include/exclude, change grams, change the food (suggestions or
   search over all 7,140 BLS foods), "without nutrients", add a missed food,
   pick the meal. Water goes to the hydration log. Saving writes food log
   entries for the selected day; BLS foods become reusable "my foods"
   (`id = bls:<code>`, `source = bls`, per 100 g).

Nutrients never come from the model; the model only names foods and guesses
portions.

## Hardware gate (VisionCapability)

| Check | Rule |
|---|---|
| ABI | `arm64-v8a` |
| CPU | every core lists `asimddp` and `asimdhp` (ARMv8.2 dot product + FP16); the native library is compiled for it and is never loaded otherwise |
| RAM (`totalMem`) | ≥ 5.0 GB → Qwen3.5-2B (≈ 1.9 GB download, ≈ 2.4 GB running); ≥ 7.0 GB → Qwen3.5-4B (≈ 3.4 GB, ≈ 3.9 GB); `isLowRamDevice` → off |
| Probe | estimated full photo ≤ 45 s fine, ≤ 120 s shown with the duration, > 120 s → off with "remove model" |

The Android version is not a criterion. The Nokia 6.1 test phone (Snapdragon
630, 2.8 GB, Android 15) fails both CPU and RAM; a Pixel 6a (6 GB) gets 2B.

## Components

- `androidApp/src/main/cpp/vision/`: llama.cpp b11396 + libmtmd fetched by
  CMake (pinned SHA-256), statically linked into `libcruxcoach-vision.so`
  (≈ 5 MB stripped), built `-O3` even in debug APKs; Qwen chat format with
  thinking off, greedy decoding, grammar checked only when the greedy token
  breaks it, generation stops at the first exactly repeated item.
- `FoodVisionService` (`:vision` process, Messenger IPC, not in Hilt;
  `CruxCoachApp` skips all app start-up in that process; the process ends
  when the sheet closes).
- `VisionModelStore`: DownloadManager into `getExternalFilesDir("foodvision")`,
  hash check, probe result.
- Shared logic: `FoodVisionParser`, `VisionCapability`, `FoodMatcher`,
  `BlsTable` (tests: `FoodVisionTest`, `BlsAssetMatchingTest`).
- Prompt files: `assets/foodvision/{system,user}-v1.txt`, `grammar-v1.gbnf`;
  `tools/food-vision` runs the same engine and files on a desktop.

## Privacy

Only the two model URLs (Hugging Face) are contacted, once, on the user's
request. The photo is copied to the app cache, scaled, analysed and deleted;
nothing is uploaded, nothing is stored except the log entries the user saves.

## Open

- Measured speed on real 6 GB and 8 GB phones (only the Nokia 6.1 is
  available; it is gated out).
- Quality evaluation on German dishes; the server run on the Mensa benchmark
  is in the run notes.
- Mirroring the model files on our own Blossom server instead of Hugging
  Face (needs the owner's go; storage).
