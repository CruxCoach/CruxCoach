---
status: planned
target: 0.1.5
---
# Feature Spec: Board Picker in the Browser TopBar

> **Status:** Planned for 0.1.5. UX accessibility fix — a user
> reported they couldn't find the "change board" affordance
> anywhere outside the first-launch onboarding step. The picker
> is reachable today only via the `firstSyncModel` dialog
> (gated on first-sync edge cases) or by clearing app data, so
> a multi-board user (Home Wall + 12×12 Kilter, etc.) is
> effectively pinned to whichever board they picked at
> onboarding.
>
> **Depends on:**
> - None. Self-contained UI addition; consumes the existing
>   `boardLayoutId` / `boardProductSizeId` DataStore preferences
>   + `boardRepository.getBoardLayouts()` and friends.
>
> **Relates to:**
> - FEAT-017 (Background Board Sync) — switching to a board that
>   has never been synced should trigger a sync the same way the
>   first-launch path does. The picker re-uses that codepath; no
>   new sync trigger is invented here.
> - FEAT-008 (Kilter Import Own Climbs + Backup Extension) —
>   "own climbs" filter is per-pubkey, not per-board; this
>   spec doesn't change that semantics.

---

## 1. Overview

CruxCoach supports multiple board configurations: distinct
`boardLayoutId × boardProductSizeId` pairs (e.g. Kilter 12×12,
Kilter Home Wall, Kilter 7×10, future Tension boards). The
user picks one at onboarding (BOARD_SETUP step) and that
selection is persisted in `UserPreferences.boardLayoutId` +
`UserPreferences.boardProductSizeId`. Every board-derived
screen — BoardBrowser, BoardClimbDetail, ClimbEditor —
reads these prefs and renders climbs from that board only.

A user who later wants to switch boards has no direct path.
The existing affordances are:

- **Onboarding BOARD_SETUP** — only on first launch, never
  reachable again.
- **`firstSyncModel` dialog** — pops once on the first sync
  after detecting that the model isn't set; dismissed
  permanently once a pick lands. Not user-triggerable.
- **App-data wipe** — kills everything else too.

Net result: a user with a Home Wall AND a public-gym Kilter
who wants to switch contexts must dig through Settings,
expecting a "Board switch" entry, fail to find it, and
eventually settle on app-data-wipe (with all its losses) or
forgoing the second board entirely.

This spec adds a **board picker affordance directly in the
BoardBrowser** — the screen the user is already on when they
want to switch context. Tapping the affordance opens a bottom
sheet listing all available `(layout, productSize)` pairs;
tapping a row sets the prefs and refreshes the browser to
show that board's climbs.

### 1.1 Goals

- BoardBrowser TopBar carries a board-identity chip / button
  showing the currently-selected board (e.g. "Kilter 12×12").
- Tapping the chip opens a bottom sheet with the available
  options.
- Selecting an option persists the new prefs and the browser
  refreshes its content to the new board within ~1 s (no app
  restart, no Activity recreation).
- The picker exposes only boards whose data is already
  imported (i.e. has rows in the board DB for that layout).
  Boards that have no data yet show a "Sync first" call-to-
  action that routes to BoardSyncScreen → triggers a sync
  for that layout, then returns.
- The current selection is visually highlighted in the picker
  (checkmark + bold).

### 1.2 Non-Goals

- A second-tier "manage all boards" Settings screen. Out of
  scope; the picker in the browser is sufficient for the
  common case (switch between two regularly-used boards).
- Cross-board search ("find this climb on any board"). The
  data layer doesn't index across boards today; this would
  require new schema work.
- Removing / hiding boards the user no longer cares about.
  The picker shows everything the DB has; pruning is a
  separate cleanup feature.
- Auto-detecting which board the user is currently in front
  of (via BLE scan / NFC tag / etc.). Possible future work,
  out of scope here.

---

## 2. Today's behaviour

`BoardBrowserScreen.kt`'s TopBar today carries:

- A title slot showing the app name.
- A search icon → opens the search bar.
- A BLE-status icon → shows the connection state.
- A settings cog → navigates to `SettingsScreen`.
- A `FilterChip` row below the top bar for angle / grade / etc.

Nowhere on this screen is the active board surfaced. A user
can read off-screen that they're on a 12×12, but only by
context (climbs that look familiar, grades on a scale they
expect). New users who installed expecting "the board" don't
realise they only see one layout's content.

`BoardBrowserViewModel.state.boardSize` is already populated
with the current `BoardSize` row (from
`boardRepository.getProductSize(prefSizeId)`); the picker
needs the same lookup for the OTHER pairs.

---

## 3. Solution design

### 3.1 New repo methods

`BoardRepository` already exposes per-id lookups; we need a
"list everything that has imported data" query:

```kotlin
data class BoardOption(
    val layoutId: Long,
    val productSizeId: Long,
    val layoutName: String,        // e.g. "Kilter"
    val productSizeName: String,   // e.g. "12 x 12"
    val climbCount: Long,          // 0 = not yet synced
)

fun getAvailableBoards(): List<BoardOption>
```

Implementation joins `layouts` × `product_sizes` × `climbs`
(GROUP BY `(layout_id, product_size_id)` with COUNT(uuid)).
Cheap — the join is small (`< 20` rows in practice).

### 3.2 BoardBrowser TopBar chip

Replace the title-slot label with a clickable chip:

```
┌─────────────────────────────┐
│  [Kilter 12×12 ▾]   🔍 ⚙   │
└─────────────────────────────┘
```

The chip:
- Reads `state.boardSize.name` for the label.
- Falls back to "Select board" when `boardSize == null`
  (fresh install, prefs not yet set — rare given onboarding
  forces a selection, but defensive).
- Opens a ModalBottomSheet on tap.

### 3.3 Picker bottom sheet

```
┌─ Select board ─────────────────┐
│                                │
│  ● Kilter 12 × 12     ✓        │   ← currently active
│      270 312 climbs             │
│                                │
│    Kilter Home Wall            │
│      4 138 climbs               │
│                                │
│    Kilter 7 × 10               │
│      (not yet synced)  → sync  │   ← row tap routes to sync
│                                │
└─────────────────────────────────┘
```

Each row shows:
- Layout + product-size name.
- Climb count (`climbCount > 0`) or "(not yet synced)".
- Checkmark on the active row.
- Trailing arrow + "sync" action on rows with `climbCount = 0`.

Tap behaviour:
- Active row → dismiss sheet (no-op).
- Other row with `climbCount > 0` → call
  `viewModel.selectBoard(option)`, close sheet.
- Row with `climbCount = 0` → navigate to BoardSyncScreen
  with the target `(layoutId, productSizeId)` pre-filled, so
  the sync run targets that pair.

### 3.4 ViewModel changes

```kotlin
class BoardBrowserViewModel {
    fun openBoardPicker() {
        viewModelScope.launch {
            val options = boardRepository.getAvailableBoards()
            _state.update { it.copy(boardPickerOptions = options, boardPickerOpen = true) }
        }
    }

    fun dismissBoardPicker() {
        _state.update { it.copy(boardPickerOpen = false) }
    }

    fun selectBoard(option: BoardOption) {
        viewModelScope.launch {
            userPreferences.setBoardLayoutId(option.layoutId.toInt())
            userPreferences.setBoardProductSizeId(option.productSizeId.toInt())
            // Re-fetch the active boardSize + reload the browser content.
            refreshFromPrefs()
            _state.update { it.copy(boardPickerOpen = false) }
        }
    }
}
```

`refreshFromPrefs()` already exists in spirit — the
`BoardBrowserViewModel` reacts to pref changes via its initial
`init` block. Extract that into a callable suspend method so
`selectBoard` can re-trigger it without an Activity recreation.

State additions:
```kotlin
data class State(
    …,
    val boardPickerOpen: Boolean = false,
    val boardPickerOptions: List<BoardOption> = emptyList(),
)
```

### 3.5 Routing for "sync this board"

When the user picks a not-yet-synced board, the navigator
sends them to BoardSyncScreen with extras
`layout_id=X&product_size_id=Y`. The BoardSyncScreen reads
the extras, pre-populates the sync target, and runs the sync
against that specific pair. On completion it pops back to the
browser, which sees the now-populated prefs and renders.

The existing BoardSyncScreen already supports an
`initialLayoutId` / `initialProductSizeId` extras pair — the
plumbing exists; this is just a new caller.

---

## 4. Strings (en + de)

```xml
<string name="board_picker_title">Board auswählen</string>
<string name="board_picker_active_marker">aktuell</string>
<string name="board_picker_climbs_count">%1$d climbs</string>
<string name="board_picker_not_synced">(noch nicht synchronisiert)</string>
<string name="board_picker_sync_cta">Synchronisieren</string>
<string name="board_picker_topbar_cd">Aktives Board, tippen zum Wechseln</string>
```

English mirrors with `Board picker`, `current`, `climbs`,
`(not yet synced)`, `Sync`, `Active board, tap to switch`.

---

## 5. Edge cases

### 5.1 Only one board imported

If `getAvailableBoards().size == 1`, the picker is still
reachable (the chip stays a button) but the sheet contains
exactly one row. The user can still tap it (no-op) — useful
sanity check, no harm. We don't auto-hide the chip because
that would break muscle memory once a second board is added.

### 5.2 No boards at all (zero imported)

Only possible if the user somehow cleared the board DB
without re-triggering onboarding. The chip shows "Select
board" and routes to BoardSyncScreen. Recoverable; not a
regression from today's state.

### 5.3 Selection while a sync is in flight

`BoardSyncManager.isSyncing == true` while the user picks a
different board: defer the selection until the sync completes
to avoid contention with the bulk-import writer-lock. Show a
small "Sync läuft — wir wechseln, sobald sie fertig ist"
inline message in the sheet and let the user dismiss. If
they confirm, queue the switch behind the sync; emit a
snackbar after the switch lands.

(Simpler v1 alternative: disable the picker rows entirely
while `isSyncing == true` with the same inline message and a
disabled-state visual. Ship the simpler version first; revisit
if users hit it often.)

### 5.4 BLE-connected to a physical board during the switch

The BLE connection is layout-agnostic from the protocol side
(holds + roles are layout-derived but the BLE channel itself
doesn't carry layout context). Switching the user's preferred
layout while connected to a 12×12 board would send
homewall-style frames over a 12×12 channel — visually wrong.

Mitigation: if `bleConnection.state.value.isConnected == true`
when `selectBoard` fires, show a confirm dialog:

> Du bist gerade mit einem Board verbunden. Möchtest du das
> Layout trotzdem wechseln? Hilfreich, wenn du gleichzeitig
> in einer anderen Halle planst.

Default action is "Stay" (cancel switch). "Wechseln" goes
through. User-confirmed override; not a hard block.

### 5.5 ClimbDetailScreen open in the back-stack while switching

If the user has a climb-detail screen on the back-stack from
the previous board, popping back to it after switching the
board prefs would surface a climb that doesn't exist on the
new layout (or whose stats don't apply). Pop the entire
detail/back-stack down to BoardBrowser on a successful
`selectBoard`. Cheap navigation pop; no surprise to the user
(they just chose a new board, of course the prior detail goes
away).

---

## 6. Testing

### 6.1 JVM

- `BoardRepository.getAvailableBoards` returns the expected
  shape on a populated DB; honours filter on
  `is_deleted = 0` for the climb-count column.
- `BoardBrowserViewModel.selectBoard` writes both prefs, then
  triggers `refreshFromPrefs`; observes Turbine for the state
  transition.

### 6.2 Maestro

- `flows/board-picker-switch.yaml`:
  1. Open BoardBrowser. Assert the chip reads "Kilter 12×12".
  2. Tap the chip. Assert the picker opens.
  3. Tap a different available row.
  4. Assert the picker closes and the chip label updated.
  5. Assert the climb list refreshed (first climb's name
     changes — uses a name-anchor fixture from the test board).

### 6.3 Manual qual

- Switch between 12×12 and Home Wall mid-app-session and
  verify no Activity-recreation flash (no `MainActivity`
  re-creation in logcat).
- Switch while BLE-connected → confirm dialog appears.
- Switch while sync running → inline message visible, switch
  defers until sync completes.

---

## 7. Estimated complexity

- 4 files touched: `BoardBrowserScreen.kt`,
  `BoardBrowserViewModel.kt`, `BoardRepository.kt` (interface),
  `BoardRepositoryImpl.kt` (impl + SQL append in `Board.sq`).
- 6 strings × 2 locales.
- ~150 lines new code + ~60 lines tests + 1 new Maestro flow.
- Effort: ~1 day.
