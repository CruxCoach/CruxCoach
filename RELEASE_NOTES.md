# CruxCoach 0.2.6 — 2026-11-29

A fix release for 0.2.5. It also makes logs at different angles count the way the board makers' apps
count them.

## Changes
- **Per angle.** Tried and sent now count per climb and angle. A climb you sent at 40° is no longer
  "sent" at 25° in the board browser's filter, the playlist generator and the statistics. The Try and
  Top buttons show the angle on screen and all angles, for example "Try (0 · 3)". MoonBoard is
  unchanged.
- **Waistline food diary.** The Waistline import now brings your food diary too: from the database
  backup every food with its meal, amount and nutrition, and from the diary CSV the day totals. Find it
  under Nutrition → ⋮ and in the training settings.
- **All body readings.** "All entries" on the body page lists every reading by month, to correct or
  delete.
- **Strength comparison from published data.** Finger strength and weighted pull-ups are compared with
  data from Power Company Climbing and Lattice Training, for men and women separately. Instead of one
  "typical" grade the app shows the range of grades where a value is common, and how much strength
  explains: finger strength about half of the differences in grade, pull-up strength about a third.

## Fixes
- Shiny Wall shows its angles right after you switch boards; before, they appeared only after a
  restart, and an angle above 60° left the list empty.
- Swiping from the logbook opens each climb at the angle it was logged at.
- Offline sharing brings Shiny Wall along also when it updates the app, and the receiver keeps the gym
  locations of every board.
- The Shiny Wall account import waits for the catalogue instead of skipping entries.
- Waistline 3.11.1 writes only today's row into its CSV (a Waistline bug); the import says so and
  points to the database backup, which has every day.

## Compatibility
CruxCoach 0.2.6 requires Android 9 or newer, like 0.2.5, and works without Google services. It updates
0.2.5 in place, and backups from 0.2.5 restore in 0.2.6.
