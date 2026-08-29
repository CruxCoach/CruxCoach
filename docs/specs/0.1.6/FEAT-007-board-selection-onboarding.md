---
status: draft
---
# Feature Spec: Board Selection in Onboarding + Find-Your-Gym Flow (FEAT-007)

> **Status:** Draft — needs UX review and decision on the gym→board
> inference data model before promotion to "Ready".
> **Depends on:** FEAT-015 (locations dataset) — already shipped in v0.1.5.
> **Blocks:** FEAT-016 (Homewall support) benefits from this picker but
> doesn't strictly require it.
> **Target release:** v0.1.6

## 1. Overview

Today's board-picker UX is broken in two ways:

1. **Wrong moment.** The picker dialog appears *after* the first board
   sync completes (post-FTUE), surprising users who were just trying to
   download the climb database. Many users dismiss it and silently end up
   on the default `(layout=1, size=10)` (12x12 Original) regardless of
   what they actually own.

2. **No escape hatch for the unsure.** A first-time user often doesn't
   know whether their gym's wall is a 12x12 with kickboard or a 16x12
   without — the picker forces them to commit to a `(layout, size)` pair
   they have no way to verify. There's no "I'll figure it out at the
   gym" or "let me pick my gym instead" path.

This feature moves board selection into the onboarding flow as a
deliberate first-class step, with three peer entry paths:

- **Pick your board model** (current behaviour, refined)
- **Pick your gym → board is inferred** (new — uses FEAT-015 location data)
- **Skip / decide later** (new — UI works on world-default until set)

### Goals

- Board choice is made consciously during onboarding, not as a
  post-sync afterthought
- A user who knows their gym name but not the board specs can finish
  onboarding without guessing
- Users can skip and configure later from Settings, without the app
  lying about which board it's filtering for
- The post-sync dialog is removed — board choice is no longer
  conditional on sync state

### Non-Goals

- Multi-board profiles (user with home + commercial gym) — single
  active board only, switch via Settings
- GPS-based "nearest gym" auto-suggest
- Manual address entry / geocoding
- Editing gym data (read-only consumer of FEAT-015 dataset)
- Climb-history migration when board choice changes (keep separate)

---

## 2. UX

### 2.1 Onboarding step placement

Board selection slots into the onboarding flow as a dedicated step,
**after** language/theme prefs (no data dependency) and **before** the
first sync (so the user's choice can inform what gets prioritised in
the sync UI later if we add per-layout chunking).

Order:
1. Welcome / what is CruxCoach
2. Language + theme (existing)
3. **Board selection (this feature)** ← new
4. Initial board sync
5. Done → Board Browser

### 2.2 Board selection screen

A single screen with three primary actions, presented as large cards:

```
┌─────────────────────────────────┐
│ Welches Board kletterst du?    │
│                                 │
│ ┌───────────────────────────┐  │
│ │ 🧗  Ich kenne mein Board  │  │
│ │     Layout + Größe wählen │  │
│ └───────────────────────────┘  │
│                                 │
│ ┌───────────────────────────┐  │
│ │ 📍  Nach Gym suchen      │  │
│ │     Board automatisch     │  │
│ │     erkennen               │  │
│ └───────────────────────────┘  │
│                                 │
│ ┌───────────────────────────┐  │
│ │ 🤷  Weiß ich nicht       │  │
│ │     Später entscheiden    │  │
│ └───────────────────────────┘  │
└─────────────────────────────────┘
```

**Path A — "Ich kenne mein Board":** existing picker, but rendered
inline (not in a dialog). Layout dropdown + size dropdown. Includes
Original variants today; FEAT-016 will add Homewall.

**Path B — "Nach Gym suchen":** opens a search field over the
FEAT-015 location dataset. As the user types, matching gyms surface
sorted by name with country chip + city. Tap → board is set from the
gym's `(layout_id, product_size_id)`. Confirmation snackbar:
"Board für Boulderwelt München Ost: Original 12×12. Ändern?"

**Path C — "Weiß ich nicht":** sets a sentinel "unset" state in
DataStore. Board Browser shows climbs without board-specific filtering
(graceful — `getMatchingBoard` falls back to `getAll`). The Settings
page exposes a persistent "Konfiguriere dein Board" banner until set.

### 2.3 Gym search behaviour

- **Local-only search** over the already-synced location table — no
  network request
- **Fuzzy match** on `name` (case-insensitive substring) plus optional
  city pre-filter from a country/city chip row
- **Result row layout:** name (bold) → city, country (subtitle) → size
  badge ("12×12") + access chip (PUBLIC/PRIVATE)
- **No results state:** falls back to "Pick your board model" CTA
- **Long list rendering:** LazyColumn, 60 items max in initial result;
  load more on scroll

### 2.4 Settings entry point

The current Board section in Settings becomes:

- **"Mein Board"** field with current selection (e.g. "Original 12×12")
  → tap re-opens the same three-card screen for change
- "Mein Board: nicht gesetzt" with red dot when in Path-C state

The post-sync dialog (`BoardSyncViewModel.maybeShowBoardPicker`) is
**removed**. Sync no longer cares about board state.

---

## 3. Data Model

### 3.1 Sentinel state for "unset"

DataStore keys today: `BOARD_LAYOUT_ID`, `BOARD_PRODUCT_SIZE_ID`. Both
read with a default fallback (`KILTER_ORIGINAL_LAYOUT=1`,
`KILTER_DEFAULT_SIZE=10`).

Add an explicit `BOARD_CONFIGURED` boolean preference (default false)
that gates whether the layout/size values represent a real choice or
an unconfigured fallback. Existing readers keep working — the fallback
is unchanged — but `getMatchingBoard`, `BoardBrowser`, and the Map's
"Matches my board" chip check `BOARD_CONFIGURED` first.

Migration on app upgrade: any existing user (DataStore contains
either key explicitly) is treated as configured — set `BOARD_CONFIGURED
= true` once at startup if the key is missing but layout/size are present.
This keeps current users from being downgraded to "unset" on upgrade.

### 3.2 Gym → board inference

The FEAT-015 location row already carries `(layout_id, product_size_id)`
sourced from the gym's primary wall. Picking a gym means:

```kotlin
val gym = repository.getById(gymUuid) ?: return
userPreferences.setBoardLayoutId(gym.layoutId ?: defaultLayout)
gym.productSizeId?.let { userPreferences.setBoardProductSizeId(it) }
userPreferences.setBoardConfigured(true)
```

Edge cases:
- **Multi-wall gym:** location row already collapses to one wall (FEAT-015
  picks Kilter Board Original first, then Homewall). For v0.1.6 the
  picker uses that single representation. Extension to a per-wall picker
  is FEAT-007.1.
- **Gym with `layout_id == null`:** disable the row in the picker, show
  greyed-out with "Board unbekannt" hint. User can still pick the gym
  for map navigation but inference fails — fall back to Path A.

### 3.3 No new DB schema

Pure UI + DataStore work. The location table is already populated.

---

## 4. Open Questions

| # | Question | Default if unanswered |
|---|---|---|
| Q1 | Should onboarding be skippable entirely (existing users coming back after a wipe)? | Yes — Path C is itself a skip |
| Q2 | What happens if the user's gym has multiple `(layout, size)` walls — should we disambiguate? | v0.1.6: pick FEAT-015's primary wall silently. v0.1.7: per-wall picker. |
| Q3 | Should the gym search work offline if the location table is populated? | Yes — local SQLite search, no network |
| Q4 | What sentinel value do we use for "unconfigured" in Path C? | New `BOARD_CONFIGURED: Boolean` pref, default false. Layout/size keep current fallback values. |
| Q5 | If user picks a Homewall gym before FEAT-016 ships, what happens? | Inference sets layout_id=8, but Board Browser shows nothing (no Homewall climbs in DB yet). Show Snackbar: "Homewall noch nicht unterstützt — Board Browser bleibt leer." |
| Q6 | Should we expose "I climb at multiple boards" multi-select? | No, defer to FEAT-007.1. Single active board only in v0.1.6. |
| Q7 | Onboarding back-button behaviour from Path C? | Returns to step 2 (theme), no setBoardConfigured call. |

---

## 5. Implementation Sketch

### 5.1 New screens

- `OnboardingBoardChoiceScreen.kt` — 3-card layout, navigates to A/B/C
- `OnboardingBoardManualScreen.kt` — current picker, inline (not dialog)
- `OnboardingGymSearchScreen.kt` — search field + LazyColumn over
  `BoardLocationRepository`

### 5.2 ViewModel changes

- `OnboardingViewModel`: extend with `boardChoiceStep` state
- Remove `BoardSyncViewModel.maybeShowBoardPicker` and the dialog UI
- `MapViewModel.canFilterByMyBoard` switches from
  `!isBoardProductSizeDefault` to the new `BOARD_CONFIGURED` flag

### 5.3 Settings changes

- `SettingsScreen.kt`: replace current board picker with single-row
  field + chevron, route to the same 3-card screen
- Add unconfigured banner near top when `!BOARD_CONFIGURED`

### 5.4 Migration

- One-time on app start: if any existing layout/size pref is set, mark
  `BOARD_CONFIGURED=true`. Idempotent.

---

## 6. Rollout

- v0.1.6: Onboarding flow + Settings entry point + gym search (Path B)
  for already-supported layouts
- v0.1.7: Per-wall disambiguation if gym has multiple, plus Homewall
  inference once FEAT-016 ships

No release-train dependency on FEAT-016 — Path B works for Original-only
users in v0.1.6.

---

## 7. Risks

- **Gym search misses common queries.** Substring is fragile; users may
  type partial words ("munich" vs gym name "Boulderwelt München"). Plan:
  ship substring first, gather feedback, switch to a better tokeniser
  (split on whitespace, match all tokens) in a follow-up if needed.
- **Inference picks the wrong wall** in multi-wall gyms. Mitigated by
  the snackbar showing the inferred result with an "Ändern?" link to
  Path A.
- **Path C user never configures.** Acceptable — Board Browser still
  works, just without "Matches my board" filter. Settings banner nudges.

---

## 8. Out of Scope (for this Spec)

- Climb-history portability across board changes — handled by existing
  log-by-layout schema, no new work
- Geocoding "I live in Munich" → gym suggestions
- Any change to the FEAT-015 dataset shape
