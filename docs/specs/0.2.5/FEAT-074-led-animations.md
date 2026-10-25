---
status: implementation
queue: done
base: v0.2.4 + 0.2.5 work
depends_on: []
created: 2026-10-18
updated: 2026-10-25
---

# Feature Spec: LED effects on the lit climb (FEAT-074)

> **Status:** Implementation on `feat/0.2.5-led-animations`, picked from PR #21
> (`feat/led-animations`, contributed by dapeebb, first numbered FEAT-067, which
> collided with Body tracking v2) and reworked after review for the 0.2.5 line
> (§10). Not in a release artifact; per [the registry legend](../INDEX.md),
> `implementation` is not delivery.

## 1. Overview

**LED effects** keep the climb already on the wall moving instead of static: the
lit holds pulse, flash between colour schemes, jump through colours, or travel
around the colour wheel. Every frame is a pure function of what the board was
actually sent and the effect's tick, so no caller starts, stops or refreshes it —
sending a climb, editing an LED colour, disconnecting, cleaning holds or clearing
the board just changes an input and the loop follows (`led/LedAnimationEngine.kt`).
An effect is one `LedAnimation` object, one `LedAnimationRegistry.ALL` entry and
two strings; which boards show effects is one set, `LedEffectSupport.boards`.

## 2. Behaviour

*Settings → Board & Bluetooth → LED effects* is a card of its own between "All
boards" and the per-board settings (`ui/settings/LedEffectsSection.kt`): a status
line, the effects as chips (a tap applies at once, so the wall is the preview),
the hint of the chosen effect and *Keep running in other apps*. The climb menu
offers the same choice ("LED effect: Pulse") while a board that shows effects is
connected. There is no tempo control: the owner's rule is that the effects look
and move exactly as contributed (`LedPr21GoldenTest`). The default is **None** —
byte-for-byte the static behaviour and the fallback for any unknown id. Stored in
DataStore: `led_animation` (raw id), `led_animation_in_background` (default
false), `led_strobe_notice_seen`.

| Option | id | What it does | Interval | Flashing |
|---|---|---|---|---|
| None (default) | `none` | Normal colours | no loop | no |
| Pulse | `pulse` | Each LED keeps its hue and glows 100/85/70/55/40/55/70/85 % | 180 ms | no |
| Rainbow | `rainbow_wave` | One shared hue offset on every colour; normal colours every 30 steps | 150 ms | no |
| Two-phase strobe | `two_phase_strobe` | Alternates the CruxCoach and Kilter Standard schemes | 333 ms | yes |
| Chaotic strobe | `chaotic_strobe` | Hue scramble `1 + (tick · 7 mod 29)`, 84° apart, roles stay distinct | 333 ms | yes |

Status (`LedEffectStatus`): off, no board, board without effects, waiting for a
climb, paused, ended, background, running.

## 3. Engine and pacing

Inputs, all observed: the lit frame, the board's availability (no board, a board
without effects, hold cleaning, ready — `LedBoardAvailability` from
`BoardBleLedSink`), the settings, the pause owners (§4), foreground and the
background claim (§5), and the climb whose effect has ended.

- **Interval (as contributed):** the effect's own interval, at least 45 ms per 16
  lit LEDs; above 120 lit LEDs the climb stays static (one API-2 packet holds 127).
  The next frame starts `interval − elapsed` after the start of the previous one,
  never less than 20 ms after it ended. A controller paced at 100 ms per 20-byte
  chunk (write-only and bare-name Kilter boxes) therefore runs the effect as fast
  as its writes allow, keeping the link busy most of the time; a climb send waits
  for at most one frame. Kept unchanged on purpose (owner: same look), see §10.
- **Flashing:** a `photosensitive` effect is clamped to
  `PHOTOSENSITIVE_MIN_FRAME_MS` = 333 ms between frame starts: three colour changes
  a second on average. The wall shows a frame when its last chunk is in, so uneven
  write times can bring two changes closer than 333 ms; still, since each start is
  ≥ 333 ms after the previous one and ≥ 20 ms after the previous write ended, five
  changes are ≥ 1019 ms apart: any one-second window holds at most four changes,
  i.e. two flashes. WCAG 2.3.1 allows three.
  Choosing a strobe asks once (`led_strobe_notice_seen`), and every time while
  Android's "Remove animations" is on (animator duration scale 0) — warn, don't
  block.
- **No duplicate frames**, **DEFERRED is one dropped beat**, **three consecutive
  failed writes end the loop** — as contributed.
- **Time limit:** 15 minutes on the same lit climb (`MAX_EFFECT_MS`), then the
  climb's own colours come back. Lit frames carry a send number, so sending the
  same climb again is a new frame and restarts the effect.

## 4. Board ownership

Refcounted pause owners (`AnimationPauseOwner`): `ROUTE_PLAYBACK`,
`EASTER_ANIMATION` and `WALL_GAME` (while the wall games screen is open). Hold
cleaning is reported by the link (`CLEANING`). A shared session or a manual step
through a route is not an owner: it lights one climb and leaves it alone.

On the link (`BoardBleConnection.sendAnimationLeds`), a frame is refused
(`DEFERRED`) when a user send is in flight, cleaning owns the wall, the board is
not the expected one or speaks no Aurora packets, the climb it was made from is no
longer lit, or an API-2 box would show it dark under the 18 W budget. Relayed and
easter frames, MoonBoard and Shiny Wall sends and a failed climb send clear the lit
frame. A started frame is finished even when the loop is cancelled (no torn
packets, no late write callback for the next send). Frames are handed over on the
main thread.

The normal colours are written back once when a running effect stops and nothing
else is about to paint: switched to None, ended (time limit or Stop), or the app
left without keep-alive. A pause writes nothing (its owner paints), nor does
anything get written while cleaning owns the wall or the board is gone.

## 5. Idle disconnect and background

Effect frames do not count as activity: the idle-disconnect timer runs from the
last real send, as the setting describes. Before an idle release the normal colours
are written back if an effect frame is on the wall (`restoreStaticBeforeIdleRelease`).

*Keep running in other apps* (off by default): on leaving the app, with an effect
live and its notification visible (app notifications allowed, channel not
blocked), `CruxCoachApp` claims the engine's background half and starts
`LedAnimationService` (`connectedDevice`, low importance, `START_NOT_STICKY`, no
wake lock). The service calls `startForeground` first, ends itself when the claim
is released (the app came back) or the effect stops, and survives a refused start.
**Stop** ends the effect for the lit climb with its normal colours. The 15-minute
limit applies here too. While it runs, the phone keeps the board connection; the
settings text says so.

## 6. Colour pipeline and power

As contributed: colours are packed RGB332 bytes, hue rotation round-trips through
8-bit RGB with a round-to-nearest quantiser (`led/Rgb332Effects.kt`), brightness
never exceeds the static colour and never drops an on-channel to zero, and frames
derive from the exact lit frame the board was sent. Known limit: on API-2 boxes the
18 W scale is computed per frame, so a large climb's brightness can step between
rainbow frames (the @2 encoder is not device-verified and was left alone).

## 7. Board coverage

Kilter only (`LedEffectSupport`). Tension, Grasshopper, Decoy, So iLL and
Touchstone speak the same packets and would work through the same path; they wait
for a device or simulator check. Shiny Wall has role codes, not a colour per LED
(effects there would be role changes or blinking over its partial updates);
MoonBoard has fixed role colours over a slow UART; Quantum is a controller shared
between climbers. None of these get effects in this version.

## 8. Acceptance criteria

1. On **None** nothing is sent; a lit climb keeps exactly the bytes its send produced.
2. Unknown ids resolve to None.
3. A change of effect or tempo shows on the wall without a resend.
4. Frames go out only to a connected board in `LedEffectSupport`, with a climb lit,
   no pause owner, no cleaning, and the app in front or the background claimed.
5. A board without effects runs no loop and never starts the keep-alive service.
6. Strobes: ≥ 333 ms between frame starts; a one-time notice before the first use,
   repeated while "Remove animations" is on, which never locks them.
7. Every frame and every pacing interval equals the PR #21 commit's output for the
   golden inputs (`LedPr21GoldenTest`).
8. Idle disconnect fires on schedule from the last real send while an effect runs,
   and the normal colours are written back first.
9. A frame never paints over hold cleaning, a relayed climb or a newer climb.
10. An effect ends after 15 minutes on one climb, or on Stop, with the normal
    colours; sending the climb again restarts it.
11. Leaving the app without keep-alive ends the effect with the normal colours.
12. Keep-alive only with the setting on and its notification visible.
13. Wall games, route playback and the Easter animation pause effects; claims count.

## 9. Testing and device checks

JVM: `androidApp/src/test/java/com/cruxcoach/android/led/` (the golden frames of
f949f16fd, engine gates, pacing and the flashing floor, time limit, Stop, cleaning,
status, keep-alive choice, effects, RGB332, sink), the Robolectric link tests in
`ble/BoardBleAnimationFrameTest.kt` (fences, stale frames, relayed and failed sends,
API-2 dark frames, idle restore, re-send), `ui/settings/LedEffectsSectionTest.kt`
and the route-playback ownership tests.

Device checks (Nokia + BoardSimulator as Kilter, and a real Kilter box if
available): strobes ≤ 3 changes a second; idle disconnect fires on schedule while an
effect runs and the wall shows the normal colours afterwards; cleaning, a wall game
and a relayed climb are not painted over; a write-only Kilter box stays responsive
to climb sends while an effect runs; keep-alive with and without notifications,
Stop, return to the app, the 15-minute limit.

## 10. Changes after review (0.2.5)

The contribution was reviewed on 2026-10-25 (`led-025`). Owner rule (22:35): keep
the idea and the exact look, fit it into the codebase. The frame functions
(`LedAnimations.kt`, `Rgb332Effects.kt`) and the pacing rule are unchanged and
pinned by a golden test generated on f949f16fd. Changed around them: the idle
timer is no longer reset per frame; the background needs its own switch, a visible
notification and a 15-minute limit per climb (also in the foreground); frames are
fenced against cleaning, relayed and newer climbs, other boards and dark API-2
frames, never torn, and handed over on the main thread; wall games pause effects;
availability is observed instead of polled; the normal colours come back when an
effect stops; Stop ends the effect for the climb; strobes ask once, and again while
"Remove animations" is on; the settings moved to a card of their own with a status
and a quick pick in the climb menu; German texts were reworded.

Considered and not done, because they would change the visible rhythm: timing
strobes from the end of a write instead of the start (safety is met without it, see
§3) and halving the link use on 100-ms-paced boxes (a climb send waits at most one
frame either way).
