# CruxCoach 0.2.5 — Unreleased

This release adds training and nutrition for climbers, statistics that compare your boards, a year in review and Shiny Wall.

## Highlights
- **Training, built for climbers.** A new area in the main menu suggests today's
  training from your board logbook, your week, your equipment and how you feel,
  and says why. Over 200 exercises with steps and pictograms, a training logger
  that plans loads from your own values, rest and hang timers, and a coach that
  learns from what you change. An injury mode spares what hurts. The comeback
  plan after an injury is a training aid, not a medical device: it helps you
  build up again together with your doctor or physio.
- **Nutrition that follows your training.** Protein, carbohydrate and energy
  targets follow your training and climbing days. Foods come from offline food
  tables, a barcode scan, a typed sentence or an optional photo model that runs
  on the phone; nothing is sent. Training and nutrition data stay on the phone
  and go into the cloud backup and the export, injuries only when you switch
  them on. Health Connect is optional.
- **Statistics that compare your boards.** The logbook opens with one overview
  card instead of six tiles: your hardest send and flash, the last 30 days
  against the 30 before, and your milestones. The period filter moved into the
  logbook filter. The statistics show every number once, how many tries you need
  until the send, and, if you climb on more than one board, how the boards
  compare and how they feel for you. Each logbook day and each timed session has
  a recap to share as an image.
- **Your year in review.** Your climbing year across all your boards, imported
  logbooks included, as pages to tap through: volume, hardest sends, rhythm,
  highlights and your climber type. Every page can be shared as an image,
  without your name, problem names or exact dates unless you switch them on. It
  is in the statistics all year, and a card invites you from 1 December to 31
  January. It is computed on the phone; nothing is uploaded.
- **Shiny Wall.** Choose your Shiny Wall (Mini, Standard, Max,
  Full or Max Full) in the board picker, browse its problems with its own
  categories and light them over Bluetooth; gyms with a Shiny Wall appear on the
  board map. Import your Shiny Wall logbook, favourites and lists under Settings
  → Board accounts & imports. The import only reads; your email and password go
  to Shiny Wall's sign-in service, which runs on Google Firebase. Standard walls
  only; accounts that sign in with Google can't connect.

## Other improvements
- **LED effects** for Kilter boards, contributed by Dapeebb: the lit climb can
  pulse, flash or run through a rainbow (Settings → Board & Bluetooth → LED
  effects).
- **PaintBox**, contributed by Hugie: paint pictures on your board's LEDs, every
  hold a pixel, sent once or live (Settings → Extras). A photo from the phone
  can become hold colors, fitted to the holds of your board.
- **Board size check:** if the board lights different holds than the app shows,
  the board picker and a climb's ⋯ menu light a few marked holds so you can
  compare and pick the size that fits. The board picker also shows how common
  each board, Kilter size and MoonBoard version is in gyms.
- **Community:** Settings → Community explains the CruxCoach community on Buzz,
  where you can talk to the developers and test new builds first.
- **US units:** Settings → Units shows loads, body weight, lengths, food and
  drinks in lb, in, oz and fl oz.
- **Quantum boards are marked experimental**, because they are not yet tested on a
  real Quantum board. A climb now lights as an ordinary player route for the
  board's own auto-off time, a refusal from the board shows its reason, and
  removing a layer always turns it off.
- **Nearby sharing:** a phone that never touched the sharing switch now asks
  "Release connection?" when someone nearby wants the board, instead of
  rejecting every request.
- **MoonBoard hold cleaning** with many holds shows them in sections, as many
  as the LED box takes at once.
- After sharing a board with other apps, your phone gets its own Bluetooth name
  back, also after a crash or a restart. Swiping CruxCoach away from the recent
  apps or connecting the phone to a car now ends sharing.
- Catalogue updates no longer wait 15 seconds per board when a server never
  answers, and a catalogue import frees its temporary file at once.
- The Kilter Homewall 10x12 image shows its real holds.
- Links in Settings → About and the MoonBoard data request no longer close the
  app on a phone without a browser or an email app.
- Orange and status colours are easier to read, above all in the light theme.

## Compatibility
CruxCoach 0.2.5 requires **Android 9 or newer**, like 0.2.4, and works without
Google services. It asks for two new permissions only when you use them: the
camera (food photos, barcodes) and Health Connect. After a phone
restart it checks once that the phone has its own Bluetooth name again. The app
is bigger than 0.2.4, mostly because of the offline food tables. A 0.2.4 backup
restores in 0.2.5, and 0.2.4 restores a 0.2.5 backup without the training data.
