# CruxCoach 0.2.4 — Unreleased

This release connects the newest Kilter boards, gets the Kilter logbook upload working again, and brings a reworked board map and hold cleaning.

## Highlights
- **Newer Kilter boards connect.** Kilter boards with the controller Kilter has
  shipped since mid-2025 appear only as “Kilter Board”. CruxCoach now finds and
  lights them; before, the Bluetooth search kept spinning. A search that stays
  empty now says what to check.
- **The Kilter logbook upload works again.** It no longer stalls on one entry,
  sends each climb under the id Kilter stores, never uploads an entry twice and
  tries again by itself after a timeout or a lost connection. Entries from an
  Aurora export stay local unless you choose to upload them. Settings → Kilter →
  “Show entries” lists every entry Kilter did not take, with the reason.
- **A board map that is easier to use.** Every place shows its board families,
  a list at the bottom shows the places in view, and “My location” frames you
  with the nearest boards. “Gyms near me” finds your gym without typing its
  name. Location is asked for only when you tap one of them and stays on the
  phone.
- **Clean holds.** The logbook menu lights the holds of today's problems on the
  connected board, so you can brush them before you leave.

## Other improvements
- A board switch takes effect at once, even while a catalogue downloads.
- A board session continues after a rest timer runs out instead of staying paused.
- Climbs set on a larger board (for example 16 x 12) are shown on their own size
  instead of with clipped holds.
- A confirmed catalogue download starts for every board family.
- The updater's built-in fallback also checks GitHub.

## Android compatibility
CruxCoach 0.2.4 requires **Android 9 or newer**, like 0.2.3.
