---
status: backlog
---
# Feature Spec: Live Training Coordination via Nostr (backlog)

> **Status:** Backlog — captured 2026-05-06. Split out of FEAT-013
> on the same day so this can ship phone-first without waiting on
> hardware. No release target. Tier 1 (climb-share + grade-vote
> stream) likely lands shortly after FEAT-009 once the relay-side
> aggregation pipeline is proven; Tier 2 (trainer/group sessions)
> is more open-ended UX work and stays backlog until user demand
> shows up.
>
> **Depends on:**
> - FEAT-009 (Difficulty Rating Engine) — the Kind-30079 grade-vote
>   wire format + per-(climb, angle) Bayesian aggregator are FEAT-014's
>   primary data substrate. FEAT-014 ships only after FEAT-009 is
>   live with at least 50 active voters.
> - FEAT-003 (Climb Creator) — Kind-30078 climb-publish path is the
>   subscribe-target for live-share.
> - FEAT-010 (Profile Editor) — Kind-0 display name resolution for
>   "@user is sending Kxxxxx now" surfaces.
>
> **Relates to:**
> - **FEAT-013 (CruxCoach Controller)** — pure-hardware BLE
>   multiplexer. FEAT-014 features run on the phone client first;
>   when both ship, the controller's optional display can become
>   an additional surface for FEAT-014 events (Phase C of FEAT-013).
>   FEAT-014 itself does NOT require the controller. A phone with
>   internet + Nostr relays is sufficient.

---

## 1. Overview

CruxCoach today publishes climbs and votes via Nostr (Kind-30078,
Kind-30079) but does little with the SUBSCRIBE side beyond the
basic browser-list refresh. This spec turns the relay-fed event
stream into live, real-time coordination features in the app:

- **Live climb-share visualisation**: see new climbs from setters
  you follow appear in real-time
- **Live grade votes**: see grades update as voters cast Kind-30079
- **Trainer / coach mode**: a coach (remote or in-gym) publishes a
  session-plan event; climbers in their app see the queue and tap
  through climbs as they go
- **Group / pair sessions**: two climbers' apps coordinate
  alternating turns on the same board
- **Session broadcasts**: opt-in Kind-1 publication "started
  training at @gym, sent V8 X" for friend-graph visibility
- **Climb of the day**: configurable Nostr feed surfaces a curated
  pick on the home screen
- **Lightning zap visuals**: NIP-57 zap-receipts to setter pubkeys
  surface as flashing-gold animations on the climb card

These features all share a common substrate: a relay-fed event
stream the app subscribes to and renders into UI surfaces. Each is
modular — users can opt in/out per feature. None of them require
hardware. None of them require a CruxCoach-operated server. Each
respects the app's offline-first stance: if relays aren't reachable,
the feature gracefully shows "offline, last update X minutes ago"
and the app keeps working.

### 1.1 Goals

- Phone-first: every feature works on a phone with internet, no
  hardware required.
- Decentralised: only Nostr relays the user already configured.
  No CruxCoach-operated backend.
- Modular: each feature is opt-in via a Settings toggle.
- Graceful degradation: relay loss doesn't break the app, only
  pauses the live updates.
- Privacy-respecting: all social broadcasts are opt-in, default off
  for fresh installs.

### 1.2 Non-Goals

- Controller-side rendering of these features. That's FEAT-013
  Phase C, an additive surface, designed separately when FEAT-014
  is real.
- Account-server / login-flow / auth tokens. Pure pubkey-based
  via Nostr.
- Real-time video / streaming. Audio cues only (rest timer beeps,
  optional gong on session end).
- Replicating BoardSesh's commercial coaching tooling — we offer a
  distinct, decentralised alternative, not a feature-parity
  competitor.

---

## 2. Background

### 2.1 What's already in place

- **Kind-30078 publish** (FEAT-003): climbs go to relays on save.
  Subscribe-side is ad-hoc per-screen.
- **Kind-30079 vote events** (FEAT-009): wire format defined,
  on-phone Bayesian aggregator designed. Subscribe-side is the
  same per-screen ad-hoc.
- **Relay-pool** (NostrRelayPool, NIP-65 discovery): connection
  management exists, scales to ~10 connected relays.
- **Profile cache** (NostrProfileManager): Kind-0 lookups
  cached locally with relay refresh.

### 2.2 The gap FEAT-014 fills

There's no central place that maintains an active subscription to
"all events relevant to my training session". Each screen handles
its own queries independently, with no unified delivery to UI
surfaces like a session-overview, a coach-orchestrated queue, or
a homescreen "live activity" feed.

FEAT-014 introduces a **LiveTrainingHub** ViewModel + service
layer that:
- Maintains long-running subscriptions to relevant Nostr filters
- Routes incoming events to the registered UI surfaces
- Persists session state (last seen event id, queue position)
- Handles relay reconnect + delta-sync via NIP-77 negentropy where
  available

---

## 3. Feature Catalogue

### 3.1 Live climb-share feed (Tier 1)

Subscribe to Kind-30078 events from a follow-graph-derived list of
setter pubkeys. Surface as a "new climbs" feed on the home screen
with thumbnail + setter + grade + tap-to-detail.

UX: opt-in Settings toggle "Show new climbs from people I follow".
Default off; explicit user action turns it on with a one-time
explainer ("This subscribes to climb events from [N] pubkeys you
follow on Nostr").

### 3.2 Live grade-vote stream (Tier 1)

Subscribe to Kind-30079 votes for the climbs in the user's
currently-loaded browser page. As votes come in, the per-climb
Bayesian posterior (FEAT-009) updates and the displayed grade
animates to the new value.

UX: subtle — grades just smoothly transition. No notifications,
no badges. The user sees "live data" implicitly via fresh values.

### 3.3 Trainer/coach mode (Tier 2)

A coach (remote or in-gym) publishes a session-plan as a
Kind-30079 (or new Kind, TBD — likely a list-style replaceable
event with d-tag = `cruxcoach/session/<uuid>`) containing:
- Ordered list of climb-uuids
- Optional per-climb timer / rest interval
- Coach pubkey signing the event
- Target climber pubkey(s) as `p`-tags

Climbers' apps subscribe by `#p`-tag matching their own pubkey.
When a session arrives:
- Notification on phone: "Coach has queued a session for you"
- A new "Session Queue" tab appears in the bottom nav (only when
  active queues exist)
- Tap → Queue screen shows climbs in order
- Tap-to-load: sends climb to the connected board via existing
  BLE pipeline
- Mark-as-done: implicit (next climb auto-loads when current is
  flashed) or explicit (button)
- Session ends when queue is exhausted or coach publishes a
  superseding event

### 3.4 Group / pair sessions (Tier 2)

Two or more climbers in the same gym coordinate alternating
turns. One climber initiates a "pair session", the other accepts.
Both apps subscribe to a shared session event published by either
party.

UX:
- "Start pair session" button on home screen → QR code with
  pubkey + session id
- Other climber scans → joins the session
- Each turn sent goes through one phone's BLE; the other phone
  shows "waiting for your turn"
- Configurable: alternate per send, or per N-minute time slot

### 3.5 Session broadcasts (Tier 2)

Opt-in: at the end of a board session, the app publishes a Kind-1
note like:
> "Trained for 90 min at Boulderlounge Berlin — sent V8 'Floats Your Boat',
> projected V9 'Crux Move'."

Friend-graph-visible. No identifier of the gym beyond what the
user types. No location data unless explicitly added.

### 3.6 Climb of the day (Tier 1)

A configurable Nostr feed (e.g. a curated list, a setter pubkey,
a community Kind-30000 list) provides a daily pick. The home
screen shows it as "Today's project" with a "try this" CTA that
navigates straight to the climb detail.

UX: opt-in toggle in Settings, with a default suggestion (e.g.
@cruxcoach official curated list pubkey) and an "Add custom
feed" option.

### 3.7 Lightning zap visuals (Tier 2)

When a climb's setter has a `lud16` in their Kind-0:
- Climber sees a "Tip the setter" button on the climb detail
- Tap → NIP-57 zap flow (LNURL fetch, payment via wallet)
- Zap-receipts (Kind-9735) targeting that setter+climb surface in
  the climb's detail screen as small gold flash animations
- Cumulative tip total visible on the climb card (anonymised
  aggregation, individual zap amounts hidden)

### 3.8 Live setter mode (Tier 2)

When the user is the setter for a climb they're actively editing:
- Editor screen shows a "Sharing live preview" toggle
- When on: every hold-tap publishes an ephemeral Kind-30000
  event with the current frames state
- Followers of the setter see this preview update on their
  detail screen if they have the climb open
- Used for collaborative setting / coaching

---

## 4. Architecture

### 4.1 LiveTrainingHub

Single Hilt-scoped service that owns:
- A long-running coroutine subscribing to N filters in parallel
- A Kotlin Flow per registered surface (home-feed, grade-stream,
  queue, etc.)
- Reconnect logic on relay drop; resume from last-seen event id

```kotlin
@Singleton
class LiveTrainingHub @Inject constructor(
    private val relayPool: NostrRelayPool,
    private val nostrSigner: NostrSigner,
    private val perfLogger: PerfLogger,
) {
    val newClimbsFeed: Flow<NewClimbEvent>
    val gradeVoteStream: Flow<GradeVoteEvent>
    val activeSessionQueue: StateFlow<TrainerQueue?>
    val pairSessionState: StateFlow<PairSessionState?>
    val climbOfTheDay: StateFlow<Kind30078Event?>
    val zapStream: Flow<ZapReceiptEvent>

    fun startSession(filters: List<NostrFilter>): Job
    fun stopSession()
    fun publishSessionSummary(sends: List<AscentExport>)
}
```

### 4.2 New Nostr event kinds (proposed)

| Kind | Purpose | Replaceable? |
|---|---|---|
| existing 30078 | climb definition (FEAT-003) | yes |
| existing 30079 | grade vote (FEAT-009) | yes (per voter) |
| **NEW** 30090 | training session (coach assigns climbs to climbers) | yes (per session) |
| **NEW** 30091 | pair session invitation + state | yes (per session) |
| **NEW** 30092 | live edit preview (setter's WIP frames) | yes (per setter) |

Kind numbers are placeholder pending registration in NIPs repo.

### 4.3 UI surfaces

| Surface | Feature | Activation |
|---|---|---|
| Homescreen "live activity" card | 3.1 climb-share, 3.6 climb-of-day | always visible if any feature is on |
| Browser cards | 3.2 grade-vote stream | always visible |
| New "Session Queue" bottom-nav tab | 3.3 trainer, 3.4 pair | visible only when queue active |
| Detail screen | 3.7 zap visuals, 3.8 live preview | per-climb visibility |
| End-of-session dialog | 3.5 session broadcast | post-session prompt |

---

## 5. Implementation Phases

### Phase A — Live data substrate (1-2 weeks)

LiveTrainingHub skeleton + filter management + relay subscribe
plumbing. Wires Kind-30079 stream into FEAT-009 aggregator
real-time-update path.

### Phase B — Tier 1 features (2-3 weeks)

3.1 climb-share feed + 3.2 vote stream (already mostly in §A) +
3.6 climb-of-the-day. Opt-in toggles + Settings UI.

### Phase C — Tier 2 features (open-ended)

3.3 trainer mode + 3.4 pair sessions + 3.5 session broadcast +
3.7 zap visuals + 3.8 live setter preview. Each as its own
incremental ship; user demand and feature-flag rollout drive
ordering.

### Phase D — Optional FEAT-013 controller surface

If FEAT-013 ships and gains deployment, add a thin firmware-side
Nostr subscriber that mirrors a SUBSET of FEAT-014's events
(specifically the trainer queue + climb-of-the-day) onto the
controller's display. Designed separately when both specs are
real — see FEAT-013 §5 Phase C.

---

## 6. Open Questions

1. **Kind 30090/30091/30092 NIP registration**: open a draft NIP
   PR documenting the new kinds before any user-facing rollout.
   Mirrors what FEAT-009 already proposes for 30079/30080.
2. **Coach-discovery UX**: how do climbers find their coach's
   pubkey? In-gym QR code? NIP-05 lookup? Manual paste? First
   pass: manual paste; iterate.
3. **Spam / abuse on session broadcasts**: a malicious coach could
   spam "fake sessions" to follower lists. Mitigation: client-
   side filter "only accept sessions from pubkeys I'm explicitly
   following or have in a whitelist".
4. **Session-summary privacy**: end-of-session Kind-1 broadcasts
   are opt-in but the gym name is freeform user input. Default
   anonymised with a "Add gym" optional field.
5. **Live grade-vote update rate-limit**: incoming Kind-30079
   updates could thrash the displayed grade if multiple votes
   arrive in quick succession. Debounce to ~1 grade update per
   2 seconds per (climb, angle).
6. **Battery impact**: long-running relay subscription on phone.
   Test with WorkManager-backed background sync vs foreground-
   only mode. Default: foreground-only, user-toggleable to
   background.
7. **Notification permission on Android 13+**: trainer mode and
   pair sessions need notifications to be useful. Request the
   permission only when user activates one of those features.

---

## 7. Caveats

- Building a long-running Nostr subscription layer on Android has
  battery / network / OS-doze implications that need careful
  testing. WorkManager has limits, foreground service is the
  honest pattern for "live updates while session active".
- Trainer mode's coach-pushes-climb metaphor breaks the
  decentralised stance slightly: the climber's app needs to TRUST
  a specific coach pubkey enough to surface their queue events.
  We don't have a built-in trust framework yet — manual whitelist
  is the v1 stopgap.
- Pair sessions need both apps online + on the same relay set to
  coordinate. Air-mode user gets a "session paused" screen.
- Lightning zap visuals depend on the setter actually receiving
  the zap (resolved Lightning-address). Without that, the gold-
  flash animation never fires regardless of UI clicks.
- Spec is a brainstorm of tier-2 features. Realistic ship list
  is probably Tier 1 only for v0.3.0; Tier 2 spreads across
  later releases per user demand.
