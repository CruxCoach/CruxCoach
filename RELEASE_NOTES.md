# CruxCoach 0.2.3 — Unreleased

This release focuses on reliable browsing, clearer statistics and quicker board navigation.

## Highlights
- **Statistics count problems.** Flash, Sent and Attempt each count distinct problems
  using the best outcome in the selected period. Ten unsuccessful tries on one
  problem count as one attempted problem. Sending it moves it into Sent; a true
  flash takes priority. Total attempts remain available as training volume.
- **Filter totals match the list.** Counts follow the same board, status, source,
  hold and visibility restrictions as browsing. Matching problems beyond the first
  50 remain available.
- **More reliable random browsing.** Random search keeps one shuffled result set
  while you scroll, fixing crashes caused by repeated problem IDs.
- **Compact board navigation.** Switch boards from the header, open the main menu
  from the app logo and reach extra actions through an overflow menu on narrow screens.
- **Beta videos close at hand.** Open videos from the climb detail card. Optional
  beta media synchronizes separately from the board catalogue; previews are verified
  before caching and fall back gracefully when a mirror is unavailable.

## Other improvements
- Board-aware quick logging and playlist generation, with candidate pools for each
  planned grade band and a usable initial plan while the logbook profile loads.
- More consistent MoonBoard links and logged status across legacy problem IDs.
- Extra space below playlists keeps the add button clear of the last card's actions.
- Stronger validation and resource limits for local sharing, relay messages and
  downloaded app updates.

## Android compatibility
CruxCoach 0.2.3 requires **Android 9 or newer**. As announced in the previous release,
0.2.2 is the last version for Android 8.0 and 8.1. Existing installations on those
versions can continue using 0.2.2.

These notes describe the release candidate branch. Stable publication is pending.
