---
status: implementation
queue: done
base: post-0.2.3
depends_on: []
created: 2026-10-18
updated: 2026-10-18
---

# Feature Spec: LED animations on the lit climb

> **Status:** Implementation — complete on `feat/led-animations` with JVM
> coverage, not merged and in no release artifact; per
> [the registry legend](../INDEX.md), `implementation` is not delivery. Relates to
> route playback and the Easter animation, which own the board's frame timing and
> hand it back when they finish (§4).

## 1. Overview

**LED animations** keeps the climb already on the wall moving instead of static:
the lit holds breathe, alternate colour schemes, jump around, or rotate around the
colour wheel. Every frame is a pure function of what the board was actually sent
and the animation's tick, so no caller starts, stops, or refreshes it — sending a
climb, editing an LED colour, disconnecting or clearing the board just changes an
input and the loop follows (`led/LedAnimationEngine.kt:28-47`). An effect is one
`LedAnimation` object, one `LedAnimationRegistry.ALL` entry, and two strings.

## 2. Behaviour

The section sits directly after the LED colors section, inside the per-board
settings view that shows Kilter LED colors
(`ui/settings/SettingsScreen.kt:224-238`, `588-589`). Options render from
`LedAnimationRegistry.ALL` in a bottom sheet, each with a one-line hint. The
default is **None** — byte-for-byte the pre-existing static behaviour, and the
fallback for any id the registry does not know. The id is stored as a raw string
under the DataStore key `led_animation` (`data/UserPreferences.kt:331, 1093-1104`).

| Option | id | What it does | Interval | Photosensitive |
|---|---|---|---|---|
| None (default) | `none` | Static colours, as set in LED colors | no loop | no |
| Pulse | `pulse` | Each LED keeps its hue and breathes 100/85/70/55/40/55/70/85 % | 180 ms/frame, 8 steps ≈ 1.4 s per breath | no |
| Two-phase strobe | `two_phase_strobe` | Whole board alternates the CruxCoach and Kilter Standard schemes | 333 ms | yes |
| Chaotic strobe | `chaotic_strobe` | Deterministic hue scramble `1 + (tick · 7 mod 29)`: frames land 84° apart, roles stay distinct | 333 ms | yes |
| Rainbow wave | `rainbow_wave` | One shared hue offset on every colour; static colours return every 30 steps | 150 ms/frame, 4.5 s per revolution | no |

Gates, re-checked per frame rather than only at start:

- **Kilter only** — `BoardBleLedSink.canAnimate` requires a connected link and
  `BoardBrand.KILTER` (`led/BoardBleLedSink.kt:39-42`): MoonBoard cannot parse
  Aurora packets, and the other Aurora-family boards are untested at animation
  rates.
- **≤ 120 lit LEDs** — above `MAX_ANIMATED_LEDS` the climb stays static
  (`led/LedAnimationEngine.kt:74-82`).
- A climb must be lit, no board owner may hold the floor (§4), and the app must be
  foreground or the background claimed (§5).

The gates do not hide the options: with no Kilter board connected the row shows the
informational `settings_led_anim_kilter_only` hint and the choice is still stored,
so it can be set away from the gym and takes effect at the wall.

## 3. Engine and pacing

A frame carries only the lit climb's LEDs (typically 12-40, a handful of
acknowledged 20-byte writes), and the traffic shape is the design constraint:

- **Pacing scales with the frame:** the interval is the larger of the effect's
  tempo and 45 ms per group of 16 lit LEDs, and the 120-LED cutoff bounds it at
  360 ms
  (`led/LedAnimationEngine.kt:459-465`).
- **Photosensitivity floor:** a `photosensitive` effect is clamped to
  `PHOTOSENSITIVE_MIN_FRAME_MS` = 333 ms — at most three colour changes a second —
  whatever tempo it declares (`led/LedAnimations.kt:18`); no frame gap ever falls
  below 20 ms (`led/LedAnimationEngine.kt:72`).
- **No duplicate frames:** RGB332 is coarse, so pulse and hue-cycle frames repeat;
  one identical to the previous is never sent. That skip is the traffic budget.
- **A busy link defers, it does not fail:** anything else mid-send earns
  `FrameSendResult.DEFERRED` and the frame is dropped, not queued, so an animation
  can neither interleave half-written packets with a user send nor repaint a climb
  the user moved on from. Animation frames never enter `ConnectionState.SENDING`
  (`ble/BoardBleConnection.kt:2207-2245`).
- **Three consecutive write failures end the loop** (`MAX_SEND_FAILURES`); a
  deferred frame never counts toward it.
- **A transiently un-animatable link parks the loop** on a 250 ms poll instead of
  ending it, because the watcher cannot observe a brief gate dip; a board that is
  really gone nulls the lit frame and the watcher ends the loop.

## 4. Board ownership

Features with their own frame timing claim the wall through refcounted pause owners
(`AnimationPauseOwner`, `led/LedAnimation.kt:85-102`): `ROUTE_PLAYBACK`
(`ui/board/BoardClimbDetailViewModel.kt:448`) and `EASTER_ANIMATION` (browser and
settings Easter effects). Claims count per call, so two screens or a re-triggered
effect each hold a claim and the last one out releases the animation; a release
with no matching claim clamps at zero. A `SessionQueueManager` session or a manual
step through a route is deliberately *not* an owner: it lights one climb and leaves
it alone.

Static colours are repainted exactly once when a loop that had already written the
wall stops being wanted — switching to None while a climb is lit, or a loop that
ended on its own (over the LED cutoff, or three failed writes). A pause and an
unclaimed background write nothing at all: the feature that just took the board, or
a background nobody claimed, must not be overwritten.

## 5. Background continuation

While an effect has live work, leaving the foreground claims the engine's background
half and starts an optional `connectedDevice` foreground service
(`led/LedAnimationService.kt`, `CruxCoachApp.kt:161-192`): a low-importance
notification with a **Stop** action, no wake lock, `START_NOT_STICKY`, lifecycle
slaved to `LedAnimationEngine.isRunning`.

- Returning to the foreground releases the claim and stops the service.
- **Stop** ends the keep-alive, not the setting: the wall falls silent with the
  claim and animates again next time the app is up.
- With no wake lock by design, an Android freeze or kill ends the animation and the
  wall keeps its last frame; a refused service start is caught the same way — the
  claim is released and the effect stops with the background, never a crash.

Animation frames count as activity: the idle-disconnect timer is parked for the
write and re-armed afterwards, including when the frame is cancelled mid-write, so
auto-disconnect still holds from the last frame once the animation stops
(`ble/BoardBleConnection.kt:2217-2244`).

## 6. Colour pipeline and power

Animation colours are computed on packed RGB332 bytes — the currency of the BLE
send path — so an effect cannot produce a colour the LEDs cannot show. Hue rotation
decodes by bit replication, rotates in 8-bit RGB, and re-quantises with
`rgb888ToRgb332`, a **round-to-nearest** quantiser matched to the levels the board
decodes; the shared `BoardPacketEncoder` static path keeps its floor buckets so
existing static bytes and hex parsing are unchanged (`led/Rgb332Effects.kt:130-145`).
Rotation preserves the max channel, so colours stay inside the board palette, and
greys and black keep their hue-free look.

Frames derive from the exact lit frame the board was sent, per-board catalogue
colours included, because one role-class resolution feeds both the packet and
`litFrame` (`ble/BoardBleConnection.kt:1886-1896`). Power is bounded by
construction: brightness scaling never exceeds the static colour and never drops an
on-channel to zero, only the lit climb's LEDs are addressed, and duplicate frames
cost no traffic.

## 7. Strings

| Key | English | German |
|---|---|---|
| `settings_led_anim_title` | LED animations | LED-Animationen |
| `settings_led_anim_change` | Change | Ändern |
| `settings_led_anim_none` | None | Keine |
| `settings_led_anim_none_hint` | Static colors, as set in LED colors. | Statische Farben wie in den LED-Farben eingestellt. |
| `settings_led_anim_pulse` | Pulse | Puls |
| `settings_led_anim_pulse_hint` | Holds breathe brighter and dimmer in their own colors. | Griffe atmen in ihrer eigenen Farbe heller und dunkler. |
| `settings_led_anim_two_phase` | Two-phase strobe | Zwei-Phasen-Stroboskop |
| `settings_led_anim_two_phase_hint` | Alternates between the CruxCoach and Kilter color schemes. | Wechselt zwischen dem CruxCoach- und dem Kilter-Farbschema. |
| `settings_led_anim_chaotic` | Chaotic strobe | Chaotisches Stroboskop |
| `settings_led_anim_chaotic_hint` | Colors jump around. Role colors stay distinguishable. | Die Farben springen umher. Roll-Farben bleiben unterscheidbar. |
| `settings_led_anim_rainbow` | Rainbow wave | Regenbogen-Welle |
| `settings_led_anim_rainbow_hint` | All colors rotate slowly around the color wheel. | Alle Farben wandern langsam um den Farbkreis. |
| `settings_led_anim_kilter_only` | LED animations only work with a Kilter Board. | LED-Animationen funktionieren nur mit einem Kilter Board. |
| `led_anim_notification_channel` | LED animations | LED-Animationen |
| `led_anim_notification_channel_desc` | Shown while a lit climb keeps animating in another app. | Wird angezeigt, während ein beleuchteter Kletterzug in einer anderen App weiter animiert wird. |
| `led_anim_notification_title` | LED animation running | LED-Animation läuft |
| `led_anim_notification_text` | Animating the lit climb on your board. | Der beleuchtete Kletterzug auf deinem Board wird animiert. |
| `led_anim_notification_stop` | Stop | Stopp |

## 8. Acceptance criteria

1. On the default **None** the engine emits nothing; a lit climb keeps exactly the
   bytes an ordinary send produced.
2. Options come from `LedAnimationRegistry.ALL`; a stored id the registry does not
   know resolves to None instead of crashing or lighting nothing.
3. The choice persists as the raw id under `led_animation`, and switching it while a
   climb is lit changes the wall without a restart.
4. Nothing is sent unless the board is Kilter, a climb is lit, no pause owner holds
   the board, and the app is foreground or the background is claimed.
5. Above 120 lit LEDs nothing is animated; a climb that grows past the cutoff
   mid-effect is repainted with its own static colours once.
6. A photosensitive effect never sends faster than one frame per 333 ms, and no
   frame gap falls below 20 ms.
7. A frame identical to the previous one is never sent twice.
8. `DEFERRED` costs one beat and never counts as a failure; three consecutive
   `FAILED` writes end the loop and repaint the static colours once.
9. A link that briefly cannot animate parks the loop on a 250 ms poll and resumes on
   its own; a disconnected board ends it without repainting.
10. Switching to None while a climb is lit repaints the static colours exactly once;
    a pause and an unclaimed background write nothing.
11. Two claims from one owner need two releases; a stray release is a no-op and does
    not swallow the next real claim.
12. Animation frames never enter `SENDING` and never overwrite a user send; the idle
    timer is parked for the write and re-armed after it, even when cancelled
    mid-flight.
13. Animation colours are never brighter than the static colour, and the shared
    encoder's static bytes and hex parsing are unchanged.
14. Backgrounding mid-effect starts the `connectedDevice` service; Stop, the engine
    going idle, or returning to foreground takes it down; a refused start degrades to
    the effect stopping with the background.

## 9. Testing and device checks

JVM: 67 tests under `androidApp/src/test/java/com/cruxcoach/android/led/` cover
the engine gates, pacing and the photosensitive floor, defer/failure/park
handling, refcounted ownership, the static repaint rules, the effects, the RGB332
quantiser, the Kilter sink gate, and the keep-alive observer ordering. Three
route-playback ownership tests in `ui/board/RoutePlaybackControllerTest.kt` pin
that a run which can never start claims nothing and every claim is released once.

Device checks to retain: both strobes flash at most three colour changes per
second on a real board; idle auto-disconnect still fires on schedule while an
animation runs; the keep-alive notification's **Stop** stops the service; and an
effect selected while the app is backgrounded without keep-alive stops animating,
with the wall keeping its last frame.
