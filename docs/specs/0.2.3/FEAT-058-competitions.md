---
status: implementation
queue: n/a
---
# FEAT-058 — Competitions

**Target:** app v0.2.3 + cruxcoach.org
**Wire contract:** [`FEAT-058-competition-protocol.md`](FEAT-058-competition-protocol.md)
**Conformance:** [`FEAT-058-conformance.md`](FEAT-058-conformance.md)
**Runbook:** `cruxcoach-pages`, `tools/dev/RUNBOOK-competitions.md`

> Supersedes §13 of `backlog/FEAT-043-competitions-and-leaderboards.md`, which
> said competitions "need a separate implementation spec before work starts".
> This is that spec. Leaderboards remain FEAT-043's; nothing here publishes a
> personal score card or touches the private logbook.

---

## 1. What this is

A gym runs an evening comp on its Kilter board. Twenty people enter, the
organizer decides who is in, everyone climbs in a called order, a screen by the
door shows who is on the wall and who is next, and at the end there is a result
everyone can check.

CruxCoach does that with no account, no server and no company in the middle. The
competition is a signed document on Nostr; every decision the organizer makes is
another signed, linked entry; every client replays that record and arrives at
the same standings. Nobody has to trust cruxcoach.org, because cruxcoach.org is
a static site that holds nothing.

## 2. Who it is for

**Ines, who runs the gym.** Has a phone, forty minutes before the comp starts
and no patience for a settings screen. Needs to create the thing, share one
link, let people in, and then stand at the wall calling names and tapping *Top*.
Her key is the authority; if she loses her phone the competition stops, and she
needs to know that up front rather than discovering it.

**Tobi, who is entering.** Has the Android app. Scans the QR on the door with
his camera, lands in the app, registers, and then wants one screen that says: is
it my turn, how many people are ahead of me, how many attempts have I got left,
and where am I.

**Mara, who is entering, on an iPhone.** There is no iOS app. She gets the same
link, the same registration and the same live view in her browser, and nothing
about the competition's integrity depends on which of the two she used.

**Whoever is watching.** A projector or a spare tablet by the entrance. Read-only,
holds no key, safe to leave running.

## 3. Journeys

### 3.1 Ines creates and runs a competition

1. Opens `cruxcoach.org/competitions/organizer.html`, signs in. Extension first,
   remote signer second, a key generated in the page last and only after the
   trade-off is stated.
2. Fills a guided form. Everything she must decide is on screen; the twenty
   fields with sensible defaults are behind two disclosures that say so.
   Validation names the field and what is wrong with it, not "invalid input".
3. Publishes. She gets a join link and a QR immediately.
4. Opens registration. Requests arrive; she accepts, waitlists or rejects each.
   A rejection carries a reason, and the reason is recorded forever.
5. Closes registration, opens check-in, checks people in as they arrive.
6. Seeds the running order — one button, a reproducible shuffle — and starts.
7. Calls climbers and records outcomes: **Top**, **Zone**, **Fall**, **Time up**.
   One tap each.
8. Grants a deferral when someone asks. Moves to the next climb, the next round.
9. Finishes, and publishes results that are immutable from then on.

### 3.2 Tobi enters from the app

1. Points his camera at the QR on the door. Android opens CruxCoach, because the
   app claims `/comp/` as an App Link.
2. Sees the competition: what it is, when, the format, the fee (or that there is
   none), and the terms.
3. Picks a name for the screen. The field says, in one sentence, that it appears
   on a public screen and on public relays, and that a nickname is fine.
4. Accepts the terms and registers. The screen says **sent**, not *registered*:
   Ines decides.
5. Once accepted, checks in.
6. During the competition, one screen answers the four questions in that order,
   and a countdown for the open turn.
7. When it is his turn and he needs a minute, **Defer my turn** — with the cost
   written above it before he presses.

### 3.3 Mara enters from her phone's browser

Identical, at `cruxcoach.org/comp/<naddr>`, which `404.html` rewrites to the
participant page. She signs in the same three ways. Nothing about eligibility,
ordering, attempts or results differs between her and Tobi — that is the point
of the shared protocol and the shared fixtures.

### 3.4 The live screen

`/competitions/live.html#<naddr>` on a projector. Before the start: what it is,
when, where, the join link and its QR. Once running: who is on the wall, who is
next, the countdown, the queue and the standings, all sized to be read at
distance. It survives a refresh because it holds nothing — the state is in the
log.

## 4. Screens and states

### 4.1 Organizer console

| State | What is on screen |
|---|---|
| Signed out | An explanation and three ways in, in order of decreasing exposure |
| Signed in, no competition | The create form |
| Draft | *Publish* |
| Published | *Open registration* |
| Registration open | Incoming requests with accept / waitlist / reject; *Close registration* |
| Registration closed | *Open check-in* |
| Check-in open | Per-entrant *Check in*; *Seed the order*; *Start* |
| Running | Current climber, four outcome buttons, *Call the next climber*, defer, next climb, next round, announcement, *Pause*, *Finish* |
| Paused | *Resume*, *Finish* |
| Finished | Standings, *Publish results* |
| Cancelled | The record, read-only |
| Not the authority | The competition, read-only, and a line saying so |

### 4.2 Participant

| State | What is on screen |
|---|---|
| Loading | "Loading the competition…" |
| Not found | What that means and why it might be — not "error" |
| Needs a newer version | Say so, and say to update. Never a partial view |
| Registration open, not entered | The registration form |
| Registration closed, not entered | "Registration is closed." |
| Full, no waitlist | "This competition is full." |
| Request sent | The request state, and *Withdraw* |
| Accepted, before check-in | The state, and *Check in* when it opens |
| Running | The four questions, the countdown, and *Defer* when it is possible |
| Finished | The standings, with the entrant's own row marked |

### 4.3 Live screen

Two layouts: pre-start (facts + join QR) and running (current, next, queue,
countdown, latest announcement, standings). No control that writes anything.

## 5. Configuration

Set by the organizer, validated by both clients identically (FEAT-058 §16.1).

**Always visible:** title, summary, description, organizer name and contact,
visibility, dates and windows, venue, board model / size / angle, number of
climbs, attempts per climb, capacity, entry fee (and Lightning address if there
is one).

**Behind *Advanced*:** progression (synchronous rounds / asynchronous turns),
turn deadline, deferrals per round, how far a deferral moves you back, minimum
rest between turns.

**Behind *What entrants read*:** eligibility, terms, participant instructions,
spectator information, refund policy.

Modes are composable rather than a list of named formats: climb source,
selection count, uniqueness, progression, attempts, timing, rest, deferral and
scoring are independent axes. The first release's UI exposes a coherent subset
and the protocol carries all of them (see `DECISIONS-TO-REVIEW.md`).

## 6. The rules that need stating in words

### 6.1 A request is not a registration

An entrant publishes a *request*. The organizer's decision is what makes them a
participant. Every screen uses that language, and the reducer enforces it: a
registration request changes nothing at all until a decision references it.

### 6.2 Deferral

One per round by default, at most one in a row. A granted deferral moves you
back exactly two places **within the current round** — never to the end, and
never into the next round. It gives you no extra attempts. If your turn's
deadline passes with nothing recorded and no granted deferral, that is a
**timeout**, and a timeout costs one attempt. When your attempts run out that
way, the climb is a DNF and the queue moves on.

The screen shows deferrals remaining, what pressing it will cost, and the live
countdown. When you have none left the control is **not there**, and a sentence
says why.

### 6.3 Nothing is shown that cannot be vouched for

If an entry is missing from the record, the standings are not displayed and the
screen says which entry it is waiting for. If two entries exist at the same
position, every client picks the same one, says the record conflicts, and
refuses to treat the result as final. A confident wrong standing is worse than
an honest stall.

### 6.4 Payment

Zero fee is the default and removes the whole payment surface. With a fee, an
entrant's state is *pending* until the organizer's own client verifies a NIP-57
receipt signed by the organizer's own Lightning provider. No client ever treats
an entrant's claim, or a receipt it merely saw on a relay, as payment. An unpaid
entrant appears in the running order and still cannot be given a turn.

## 7. Failure and recovery

| Situation | Behaviour |
|---|---|
| No relay reachable | Last known state, and a line saying it may be stale. Nothing is invented. |
| A publish no relay accepted | Reported as a failure. The organizer's own view does not move. |
| A relay withholds an entry | Reduction stops at the gap; the gap is named. The organizer cannot write on top of it. |
| The app is killed mid-action | Nothing is lost: intents are addressable and a retry reuses its nonce, so it replaces rather than duplicates. |
| Reconnect | A pure re-read. There is no session to restore. |
| A competition needs a newer client | Named, with an instruction to update. Never a partial leaderboard. |
| The organizer's key is compromised | A second signer produces a fork, which every client detects and shows. Recovery is an authority-epoch change. |
| The organizer loses their phone | The competition stops. This is stated in the create flow, not discovered. |

## 8. Accessibility

- Every control is at least 44 dp / 2.75 rem.
- Two live regions per web page: polite for state, assertive for "your turn" and
  errors. Compose uses `liveRegion` for the same two.
- The drawer handle is a labelled button; the logo inside it is decorative, so a
  screen reader announces one control rather than an unlabelled image in one.
- Focus is visible everywhere (`:focus-visible`, 3 px, offset).
- `prefers-reduced-motion` and `prefers-contrast: more` are both honoured.
- Colour is never the only carrier: every badge has a word.
- The projector is readable at distance by construction and reflows to one
  column under 46 rem.

## 9. Privacy

Published, publicly and permanently: public key, chosen display name, division,
registration / check-in / payment *state* (never an amount), attempts, outcomes,
standings.

Never published: real name, contact details, age, the waiver text someone
accepted, anything about a person who only looked.

The display-name field says where the name will appear before it is typed. The
relay operator learns your IP and which competition you asked for; a fresh key
does not change that, and both privacy pages say so rather than implying
otherwise. Nostr events cannot reliably be deleted once relays have copied them,
and the pages say that too.

## 10. Acceptance criteria

Each is mapped to automation in [`FEAT-058-conformance.md`](FEAT-058-conformance.md).

| # | Criterion |
|---|---|
| AC-1 | The app and the website reduce the same event stream to the same `state_hash`, full state and standings. |
| AC-2 | Reduction does not depend on delivery order, and duplicate delivery changes nothing. |
| AC-3 | A gap in the record stops reduction, is named, and blocks both standings and further authority writes. |
| AC-4 | A fork is detected, resolved identically by every client, surfaced, and blocks final results. |
| AC-5 | Only entries signed by the named authority are applied; a forged entry is stored by the relay and refused by every client. |
| AC-6 | A registration request alone makes nobody a participant. |
| AC-7 | Capacity is enforced by the reducer, not by the organizer's client. |
| AC-8 | A second claim on an already-claimed climb is refused, deterministically. |
| AC-9 | A granted deferral moves back exactly `defer_slots`, never to the end, and grants no attempts. |
| AC-10 | A second consecutive deferral, or one with no budget, is refused with a stable code. |
| AC-11 | An expired turn is recorded as a timeout and costs exactly one attempt. |
| AC-12 | With a fee, an entrant is `pending` until the authority confirms; an unpaid entrant cannot be given a turn. |
| AC-13 | Every rejection code in the closed set is exercised by a fixture in both clients. |
| AC-14 | A join link is recognised as URL, fragment, `nostr:` URI or bare `naddr`; anything else is refused. |
| AC-15 | An `naddr` for another kind, or for a non-competition d-tag, is refused. |
| AC-16 | The QR a projector renders decodes back to exactly the join link, with valid error correction. |
| AC-17 | The site's crypto matches the published BIP-340, RFC 8439 and NIP-44 vectors. |
| AC-18 | A local key is never persisted in plaintext, is zeroed on logout and expiry, and writes nothing on a shared device. |
| AC-19 | Backup confirmation requires three specific characters of the nsec, not a tick box. |
| AC-20 | NIP-46 learns the *user* pubkey via `get_public_key`, not from the bunker URI, and every request times out. |
| AC-21 | A publish no relay accepted is reported as a failure and does not move local state. |
| AC-22 | Every English string has a German counterpart with matching format arguments, in both clients. |
| AC-23 | No competition page carries an inline script or style, and none assigns `innerHTML`. |
| AC-24 | The protocol layer contains no DOM reference, so the shipped code is what the tests run. |
| AC-25 | No test or fixture contacts a public relay or spends a satoshi. |
| AC-26 | A whole competition runs end to end on a loopback relay, with four independent readers agreeing. |
| AC-27 | Signing in publishes or confirms a kind-0 profile with a usable name before anything else is offered, on all three signer paths, and never deletes a field it did not write. |
| AC-28 | An unreachable relay is distinguishable from "you have no profile"; the second invites an overwrite and the first must not. |
| AC-29 | Every competition climb names a real board climb. Placeholder UUIDs are refused by both validators, and duplicates within one competition are refused. |
| AC-30 | The organizer form can set every mode axis, and every configured mode is offered in both participant clients. |
| AC-31 | With participant-chosen climbs, entrants publish their picks, the authority decides them in registration order — not in pubkey order — and the loser of a race is told and can pick again. |
| AC-32 | An attempt on a climb the participant does not hold, or that the competition does not run, is refused by both reducers. |
| AC-33 | In asynchronous turns, the next-climb chooser exists only when that participant may act; every reason it is absent is stated. |
| AC-34 | Android resolves a competition climb against its own board data before opening it: a missing board offers a retry, an unrenderable one says so, and neither opens an empty board screen. |
| AC-35 | The entry fee resolves through LNURL-pay over https only, and an invoice whose amount differs from the fee is refused rather than displayed. |
| AC-36 | A fee is settled automatically only by a kind-9735 signed by the key the competition's own payment endpoint named, over a zap request the entrant signed, for this competition and this amount. |
| AC-37 | Recording a payment by hand is an override carrying a mandatory reason, visible in the audit trail of every client. |
| AC-38 | The in-app scanner requests the camera only when opened, explains a refusal and a permanent refusal separately, releases the camera with the screen, and accepts only competition links — naming what a rejected code was instead. |
| AC-39 | Cleartext to loopback is permitted in the debug build only; the release policy forbids it, and a test asserts the difference. |

## 11. Non-goals

Explicitly out of scope for this release, and none of them is blocked by the
wire format:

- **Private competitions.** The envelope and key-rotation shape are specified
  (protocol §13.2); nothing is encrypted in v1.
- **A coordinator service.** The `authority` field and `authority_epoch` exist
  so one can be introduced without a protocol reset (§14). There is no server.
- **Short numeric join codes.** They need a registry, and a registry is a
  central service. The `naddr` is the code.
- **Teams, leagues, qualifiers, multi-board and remote events.** The protocol is
  extensible to them; the UI is not built for them.
- **Personal leaderboards and score cards.** FEAT-043 owns those.
- **A board catalogue browser inside the organizer form.** Competition climbs
  are real board climbs (§7), added by pasting the share link the app already
  produces. Browsing the catalogue from the website would need the board
  database on the web, which is a far larger piece of work than this feature.
- **Organizing from the Android app.** The app is a participant client; the
  console is the website.
- **Analytics on the competition pages.** The collector's allowlist is in
  another repository, and a label it rejects counts nothing.
