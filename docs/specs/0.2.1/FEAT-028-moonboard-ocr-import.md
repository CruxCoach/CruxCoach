---
status: skeleton
---
# Feature Spec: MoonBoard OCR Screenshot Import (v0.2.1)

> **Status:** Skeleton — allocated 2026-05-20 alongside the
> design-locking pass on FEAT-027. Captures the OCR-import bridge
> that BoardSesh ships as `@boardsesh/moonboard-ocr` — a
> user-initiated path to import post-2023 MoonBoard climbs that
> aren't in our v0.2.0 community-dataset snapshot (notably
> Masters 2024 and Mini 2025 entire catalogues).
>
> **Depends on:**
> - FEAT-027 (MoonBoard Catalogue + BLE, v0.2.0) — schema,
>   variant constants, BLE encoder, browser surface must ship
>   first. This spec extends the catalogue side without touching
>   the BLE side.
>
> **In scope for v0.2.1:**
> - "Import a MoonBoard climb from a screenshot" user flow
> - OCR pipeline producing a structured `MoonBoardClimb` from a
>   PNG/JPG of an official MoonBoard app problem-detail screen
> - Insertion into the local `climbs` table with
>   `board_brand='moonboard'`, identical row shape to
>   FEAT-027-imported climbs
>
> **Out of scope for v0.2.1:**
> - Automated/batch screenshot capture (FEAT-027 spec §11 was
>   explicit that anything that smells like industrial-scale
>   scraping is off the table — see [[feedback-kilter-compliance]]
>   posture extended to MoonBoard)
> - Nostr publishing of OCR-imported climbs as community climbs
>   — that's a separate scope question tied to whether we open up
>   community climbs on MoonBoard at all
> - Server-side OCR (we run OCR on-device for privacy +
>   no-server-cost reasons unless dynamic capture proves
>   on-device is impractical)

---

## 1. Overview

FEAT-027 ships v0.2.0 with the catalogue from the spookykat
2023-01-30 dump — ~143k problems across 2016 / Masters 2017 /
Masters 2019 (per
`the internal research archive`). Missing:
- Anything published on those variants after 2023-01-30
- The entire MoonBoard Masters 2024 catalogue (variant released
  late 2023, no dump available)
- The entire Mini MoonBoard 2025 catalogue (variant released 2025)

Per FEAT-027 §2, the official API is App-Check-blocked, so we
cannot fill the gap with live API calls. The user-driven
screenshot-OCR pattern is the documented escape hatch — the user
takes a screenshot of a problem in the official MoonBoard app on
their own device, CruxCoach OCRs it, the climb lands in the
local catalogue. No CruxCoach infrastructure ever touches Moon
Climbing's servers.

### Goals (v0.2.1)

- A user can hit "Import from screenshot" inside CruxCoach.
- Screenshot is taken either from disk (file picker) or from a
  share-sheet handoff from the MoonBoard app.
- OCR pipeline returns the climb's name, setter, grade, angle,
  benchmark flag, and hold positions (start / hand / finish).
- Confidence indicators surface per-hold and per-field.
- User confirms / edits the OCR result before save.
- Confirmed climb persists into the local `climbs` table with
  `board_brand='moonboard'`, identical shape to FEAT-027 rows.

### Non-goals (v0.2.1)

- Bulk import (multi-screenshot batching beyond a one-shot helper).
- Camera-based capture (only file-picker + share-sheet).
- Automatic publishing to Nostr (see "Relates to" below).
- OCR for any board family other than MoonBoard.

---

## 2. Reference implementation — BoardSesh

`packages/moonboard-ocr/` in the BoardSesh monorepo is a feature-complete
reference under Apache 2.0:

- **Stack:** `tesseract.js` for text OCR + `sharp` for image
  processing (Node) / browser Canvas API (web).
- **Hold detection:** HSV colour-range matching on overlay
  circles drawn by the official app (red=start, blue=hand,
  yellow/green=finish — see `packages/moonboard-ocr/src/types.ts`
  `HOLD_COLORS`).
- **Header OCR:** tesseract reads the problem name, setter,
  angle, grade, benchmark indicator from the top of the
  screenshot.
- **Grid mapping:** detected pixel positions snap to the closest
  11×18 grid cell (`GRID_POSITIONS` const).
- **Output:** structured `MoonBoardClimb` with `holds.{start,
  hand, finish}: GridCoordinate[]` plus metadata + `parseWarnings`.

The library has both Node and browser entry-points
(`parseScreenshot` for files, `parseWithProcessor` for in-memory
images). Test fixtures are real screenshots (`FOR_THE_BIRDS.png`,
`IMG_0970.PNG`, etc.).

## 3. Integration approach — TBD

Three candidate paths, decision deferred to spec implementation
phase:

### 3.1 Tesseract WASM on Android

Compile / use a prebuilt `tesseract.js`-equivalent WASM module
in a WebView shim, OR use Tesseract4Android (native JNI binding).

- **Pro:** maximum reuse of BoardSesh code (lift the JS+TS
  directly into a WebView, or port logic to use Tesseract4Android)
- **Con:** Tesseract4Android pulls in ~30 MB of native lib +
  language data; significant APK-size impact. WASM-in-WebView
  is slower.

### 3.2 Android ML Kit Text Recognition

Google's on-device OCR via `com.google.mlkit:text-recognition`
(or `text-recognition-latin` for the smaller variant). Built-in
to most Android devices; pulls down models lazily.

- **Pro:** smaller APK footprint, faster, Google-maintained
- **Con:** requires Google Play Services (issue for
  GAPPS-less users); different API shape than tesseract; needs
  more porting work; result-shape mapping to the BoardSesh
  pipeline is non-trivial

### 3.3 Hybrid — colour-detection ourselves + ML Kit text OCR

Keep the BoardSesh HSV hold-detection logic (port to Kotlin —
small amount of pure-math code), use ML Kit only for text OCR.

- **Pro:** smallest APK delta, best of both worlds
- **Con:** still GMS-dependency on ML Kit side; we maintain the
  hold-detection algorithm ourselves

**Decision deferred** until: we know whether v0.2.1 wants to
support GAPPS-less devices (would push toward 3.1 or a pure-port
of BoardSesh's algorithm without ML Kit), and what the actual
on-device perf is.

## 4. Data flow

```
1. User screenshot                  (.png/.jpg in app share-sheet
                                     OR file-picker selection)
                ↓
2. ImageProcessor                   (Sharp on Node / Canvas on
                                     web / Android Bitmap on
                                     native)
                ↓
3. detectBoardRegion                (find the wall image bounds
                                     in the screenshot — usually
                                     ~60% of the image)
                ↓
4. detectHoldsFromPixelData         (HSV colour-range match for
                                     start/hand/finish overlay
                                     circles)
                ↓
5. findNearestGridPosition          (snap detected centroids to
                                     the 11×18 grid cells)
                ↓
6. runOCR                           (tesseract / ML Kit on the
                                     header text region)
                ↓
7. parseHeaderText                  (extract name, setter, angle,
                                     grade, benchmark flag from
                                     OCR output)
                ↓
8. MoonBoardClimb output            (Kotlin data class mirroring
                                     BoardSesh's TypeScript type)
                ↓
9. User-confirmation UI             (show detected holds overlaid
                                     on the variant's wall image
                                     + editable name/setter/grade
                                     fields)
                ↓
10. Persist                          (climbs row with
                                     board_brand='moonboard',
                                     frames="p{holdId}r{42|43|44}",
                                     same shape as FEAT-027 rows)
```

## 5. UX surfaces

- **Entry point:** "Import from screenshot" button in the
  MoonBoard variant of the BoardBrowser (when no climbs match
  the user's search, surface as a "+ Add climb you saw"
  affordance).
- **Share-sheet integration:** Android `ACTION_SEND` intent
  handler for image MIME types — user shares from MoonBoard app
  → CruxCoach picks up → directly into OCR flow.
- **Confirmation UI:** detected holds rendered overlaid on the
  variant's wall image. User can tap-toggle holds (mark/unmark
  as start/hand/finish), edit metadata fields, save.
- **Save destination:** local-only by default (`is_listed=true`,
  no Nostr publish in v0.2.1). User can re-edit / delete from
  their own climbs list.

## 6. Schema impact — minimal

FEAT-027 already brings `board_brand`, the MoonBoard layouts,
and the frame format. FEAT-028 just inserts rows that fit the
existing shape — no new columns required.

One open question: how to mark a row as "OCR-imported" vs
"snapshot-imported". Options:
- Add a `source` enum column (`snapshot | ocr | nostr | user`)
- Track separately via a `climb_imports` sidecar table
- Don't track — every row looks the same once inserted

Recommendation: **don't track** for v0.2.1. We can always add
provenance later if needed.

## 7. OCR confidence + error handling

Per the BoardSesh `ParseResult` shape:

```
{ success: boolean,
  climb?: MoonBoardClimb,
  error?: string,
  warnings: string[] }
```

The confirmation UI should:
- Show per-hold confidence as visual cue (faded for low
  confidence, solid for high)
- Surface `parseWarnings` to the user as edit prompts ("we
  weren't sure if J3 is a start hold — please confirm")
- Refuse to save if `success=false` (force user to retry with a
  better screenshot)
- Default state: 0 problems imported, user explicitly approves
  each one (no auto-batch even if the user picks 10 screenshots)

## 8. Privacy posture

- **All OCR is on-device.** No screenshot leaves the user's
  phone. This is the central privacy commitment.
- **No telemetry on OCR results.** Per-import success/failure
  counts may be reported in aggregated form (Crashlytics-style),
  but never the parsed climb contents.
- **Screenshots are not retained.** After parsing, the source
  image is dropped unless the user explicitly says "save
  source" (TBD whether we offer that).

## 9. Implementation sketch

- New package `shared/.../moonboard/ocr/` (or
  `androidApp/.../moonboard/ocr/` if Android-only) holding:
  - `HoldDetector` — pure-Kotlin HSV colour-range matching,
    ported from BoardSesh's `core/holds.ts`
  - `GridSnapper` — pure-math, ported from `core/regions.ts`
  - `OCREngine` — abstract; either Tesseract4Android or ML Kit
    impl picked at runtime
  - `MoonBoardClimbParser` — assembles the `MoonBoardClimb`
    from detector + OCR outputs
- New UI component for the confirmation screen.
- Share-sheet intent handler registered for image/* MIMEs.
- JVM unit tests using BoardSesh's test fixtures
  (`FOR_THE_BIRDS.png` etc.) — copy them under fair-use for test
  purposes with Apache-2.0 attribution.

## 10. Open questions

- **GMS dependency**: Is ML Kit acceptable, or must we support
  GAPPS-less devices (LineageOS, GrapheneOS-no-sandboxed-play)?
- **APK size budget**: Tesseract4Android adds ~30 MB vs ML Kit
  adds ~2 MB but pulls model lazily. What's acceptable?
- **Provenance tracking**: keep the v0.2.1 minimal "no provenance
  column" approach, or invest in source tracking now?
- **Camera capture**: scope creep for v0.2.1 or worth including?
  Reasonable answer: defer — user-flow is "screenshot in MoonBoard
  app, share to CruxCoach", camera capture is a different mental
  model.
- **Multi-screenshot batching**: scope-creep candidate that BoardSesh
  has (`parseMultipleScreenshots`) but we probably skip in v0.2.1.
- **Languages**: official MoonBoard app supports several
  languages. OCR text parsing must tolerate non-English headers,
  or we accept English-only as a v0.2.1 limitation.

## 11. Why v0.2.1 (and not v0.2.0)

FEAT-027 v0.2.0 already delivers ~143k climbs across 3
variants. The OCR bridge addresses a real but secondary gap (2024
+ Mini 2025 + post-2023 updates) that the average new
CruxCoach-MoonBoard user won't immediately notice. Shipping the
solid base in v0.2.0 first means user-facing v0.2.0 launch isn't
held up by the OCR-integration design decisions in §3 that still
need work. v0.2.1 is the cleanest follow-up window.

## 12. Reference

- BoardSesh OCR package: `packages/moonboard-ocr/` in the BoardSesh monorepo
  (Apache 2.0 — license + NOTICE attribution required when we
  port)
- Comparison context: `the internal research archive`
- Memory: [[reference-boardsesh-moonboard]]
