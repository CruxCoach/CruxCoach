---
status: implemented-on-branch
queue: active
base: feat/0.2.4-training-body@bdcdd90c9
branch: feat/0.2.4-training-nutrition (nutrition line feat/0.2.4-food-photo@eaaa405b1, merged with training on 2026-10-07)
depends_on: [FEAT-068]
created: 2026-10-04
---

# Feature Spec: Food photo recognition on the device (0.2.4)

Owner request 2026-10-04: offer what MCI does with the paid FatSecret API
(photo → foods → nutrition estimate) without a paid API and without sending
photos anywhere. Hardware that cannot run it shows the feature greyed out.

## Flow

1. Nutrition screen → "Photo". The button is always visible; on unsupported
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

## Without a photo (owner request 2026-10-06)

- **Find food:** the "my foods" sheet also searches the 7,140 BLS foods
  while typing (two letters or more), offline on every phone. A picked BLS
  food is logged by grams and becomes one of "my foods" on first use.
- **Describe a meal:** a sentence ("Zum Frühstück 80 g Haferflocken mit
  200 ml Milch und eine Banane", typed or dictated with the keyboard
  microphone) goes through `MealTextParser` – numbers and number words,
  units (g, ml, EL, slice, glass, plate, handful …), piece weights of common
  foods, typical portions, meal words ("zum Frühstück", "abends") – into the
  same review list as a photo. Instant and identical on every phone.
- Where the photo model is installed, the review offers "analyse with the AI
  model" as a second opinion (text-only run, `text-*-v1.txt` prompts). It is
  not the default: on eight typical sentences the rules were more accurate
  than Qwen3.5-2B, which invented foods for "Döner mit allem" and doubled
  bread and toast weights; 4B was better but split a döner into parts and is
  slower.

## Packaged products and barcode (owner request 2026-10-07)

- The APK bundles an Open Food Facts extract (ODbL): 680,000 products sold in
  German- (DE, AT, CH) and English-speaking countries (GB, IE, US, CA, AU, NZ)
  with plausible energy and macros, German and English names, brand, serving
  size. It ships as the finished SQLite table (52 MB, 17.5 MB zstd); on first
  use it is unpacked and gets its FTS4 index on the phone (~91 MB together; the
  barcode is the integer row key, so a UPC-A and its EAN-13 form are the same
  product). On a Nokia 6.1 this takes 31 s; importing the same rows from
  text took 187–212 s.
- "Find food" searches own foods, BLS and these products; products of the
  app language's region come first. The search field has a barcode scanner
  (CameraX + ZXing, already in the build, no Google services). Every search
  and every scan is answered on the device; nothing is sent.
- A scanned code that is neither an own food nor in the extract can be added
  as an own food with that barcode.
- Later: newer extracts between releases through the board-catalogue channel
  (signed Kind-30078 manifest + Blossom chunks, `BlossomSyncManager`) –
  needs the production publisher.

## Everyday use, US foods, micronutrients (owner request 2026-10-07)

- Logged entries can be edited: tap (or menu → Edit). A food's amount, unit
  and meal change and its nutrients are scaled again; a quick entry without a
  food opens with its values.
- The product database is unpacked in the background when nutrition opens
  (after an install or update), so the first search does not wait. Settings →
  Delete shows the space the product database and the photo model take and
  removes either; the database is rebuilt on the next search.
- Products keep the pack's serving words ("1 cup (30 g)", "1 Riegel (40 g)",
  "330 ml") as the portion name.
- USDA FoodData Central SR Legacy (public domain, CC0): 7,793 generic US
  foods with English names, the same nutrients as BLS and household measures.
  "Find food" lists them after BLS; a USDA food's portion is its cup where
  USDA weighed one, so cups work for solid foods too. Available carbohydrate
  is by difference minus fibre, as in BLS and on EU labels.
- Weekly watch items: iron, calcium and vitamin D over the last 7 days,
  estimated from logged BLS and USDA foods (products and quick entries carry
  no micronutrients; the card says how much of the log it covers), next to
  EFSA reference values (iron PRI 16 mg for women under 50, else 11 mg;
  calcium PRI 1000 mg to 24 years, then 950 mg; vitamin D AI 15 µg). No red
  states, no diagnosis; the info text points to a doctor or sports dietitian.

- Recipes: a name, ingredients from any source with their raw weight,
  portions and, if weighed, the cooked weight. Saved as one of "my foods"
  (per 100 g of the finished dish, one portion as serving) and logged like
  any food; the ingredients are kept (settings table, `recipe:<id>`) so the
  recipe can be changed, and travel in the backup (`AthleteSnapshot.recipes`,
  additive). Foods without a weight cannot be ingredients.

- Health Connect export: logged meals (name, meal, energy, protein,
  carbohydrate, fat) and water are written to Health Connect when the athlete
  switches "Send meals and water to Health Connect" on. Each entry is one
  record with client record id `cruxcoach-food-<id>` / `cruxcoach-water-<id>`,
  timed on the entry's own day (an entry added for yesterday is not counted
  today); the shown day is synced when nutrition opens or changes, so edits
  replace and deletions remove records. Which entries were written is device
  state and stays out of Android backups. Since the merge with the training line it uses the
  app's one Health Connect layer (`athlete/health`, Jetpack
  `connect-client`, Android 9+ with the Health Connect app, built into 14+),
  next to the coach's reading of sleep and climbing sessions (FEAT-071): one
  card in the training settings with one switch per direction, each with its
  own permissions, and one explanation activity for Health Connect's
  permission screen and the Android 14 permission-usage view. The first
  version used the platform API (Android 14+ only) to avoid the Jetpack
  client's Guava, which hides `ListenableFuture` from the compile classpath;
  the barcode scanner now uses `ProcessCameraProvider.awaitInstance` instead.

## US units (owner request 2026-10-07)

- The unit system (metric / US) is an app setting next to the language and
  the same value as in the training settings (`AthleteProfile.units`). A first
  start picks it from the phone's region (US, LR, MM → US units).
- Storage stays metric. In US units food amounts are oz, drinks and water
  fl oz, and drinks can be entered in US cups (236.6 ml); drinks count 1 g per
  ml. A food is a drink by BLS group (N, P), a serving given in ml/fl oz, or
  the head of its German or English name – the part before a comma, a
  bracket or "mit/aus/im/with …", in a hyphenated compound its last part –
  so "Schwein" (not "Wein"), "Thunfisch im eigenen Saft" or "Kaiserschmarren
  (mit Milch)" stay food (`FuelUnits.isDrink`).
- Water presets: 8 fl oz and 16.9 fl oz instead of 250 and 500 ml.
- "Create food" takes nutrition per 100 g or per portion (US labels); per
  portion is the default in US units and is converted to per 100 g.
- Macros stay in grams and energy in kcal, as on US labels.

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

Only the model downloads go online, once, on the user's request: first a HEAD
request to the CruxCoach Blossom mirror (blossom.cruxcoach.org, by SHA-256),
then the files from there or from the pinned Hugging Face revision. Newer
product data arrives with the background board sync (same relays and Blossom
servers as the board catalogues) and only once the product search was used. The photo is copied to the app cache, scaled, analysed and deleted;
nothing is uploaded, nothing is stored except the log entries the user saves.

## Device test (Nokia 6.1, Android 15, 2026-10-07)

Product search, serving labels, editing, US units, the weekly micronutrient
card and the storage section work on the phone; the scanner opens and binds
the camera (a real scan needs the owner: the phone's camera privacy toggle is
on). Findings fixed right after: the product database took ~3.5 minutes to
prepare (now shipped as a finished table: 31 s), the first search said "no match" while
the BLS/USDA tables were still loading (now preloaded, "Searching…"), oat
cookies ranked before oat flakes, an Italian serving label was shown.

## Open

- A real barcode scan and the Health Connect export on a phone with Health
  Connect set up (owner), including Android 9–13 with the Health Connect app.
- Measured speed on real 6 GB and 8 GB phones (only the Nokia 6.1 is
  available; it is gated out).
- Quality evaluation on German dishes; the server run on the Mensa benchmark
  is in the run notes.
- Model mirror: the app asks blossom.cruxcoach.org first; the files still
  have to be uploaded there (cruxcoach-blossom-sync `upload_model_mirror.py`,
  operator only; the 4B file exceeds a 2 GB upload limit if the server has one).
- Product updates: the app side is in; publishing needs the operator
  (cruxcoach-blossom-sync `food_products_blossom_upload.py`, branch
  feat/food-products-dataset) and the relay allowlist entry
  `cruxcoach/food-products`.
