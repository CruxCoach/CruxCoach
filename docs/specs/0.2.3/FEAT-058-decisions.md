# FEAT-058 — Decision register

What was decided while building competitions, what the default is now, and what
is still an open question for a person rather than for a test.

**Nothing here has been approved by anyone.** Every "shipped" line is what the
implementation currently does, chosen so the feature could be finished; every
"open" line is a question that needs a product answer and has a recommendation
attached rather than a hidden assumption. Read it as an agenda, not as a record
of agreement.

Referenced from [`FEAT-058-competitions.md`](FEAT-058-competitions.md) §5 and
[`FEAT-058-competition-protocol.md`](FEAT-058-competition-protocol.md) §2.

---

## 1. Composable modes, not named formats

**Shipped.** Climb source, selection count, uniqueness, progression, attempts,
turn timing, rest, deferral budget and scoring are independent axes rather than
a list of named competition types. The organizer form can set every one of them
and both participant clients render every one.

**Why.** A named format ("IFSC boulder qualifier") is a bundle of decisions that
is right for one gym and wrong for the next, and every gym then asks for a
variant. Axes compose; names accumulate.

**Open — taste.** No preset is offered at all, so a first-time organizer meets
nine decisions with defaults and no shortcut. Two or three named presets that
merely *set* the axes (and say which) would probably help, and cost nothing
structurally. Recommendation: add presets once there is any evidence of what
people actually run.

## 2. Which axes the UI exposes

**Shipped.** All of them, with progressive disclosure: what an organizer must
decide is on screen; everything with a sensible default is behind a disclosure
that says the defaults are already set.

**Open — taste.** `defer_slots`, `max_consecutive_defers` and
`defer_budget_per_round` are three numbers that interact, and no screen explains
the interaction beyond one sentence each. Recommendation: leave as is until
somebody runs a competition with deferrals and reports confusion.

## 3. Scoring defaults

**Shipped.** `tops_then_attempts` (IFSC-style) is the default, with tiebreaks
`fewest_attempts, most_zones, earliest_finish, seed_order`. `points_sum` and
`hardest_n` exist. Point-based scoring is refused when entrants choose their own
climbs, because two people scoring different problems out of a shared pool is
not a ranking anybody agreed to.

**Open — business.** Whether `hardest_n` should default `n` to 3 (common in
gym-run scoring) or to the climb count. Currently the organizer sets it.

## 4. The organizer's key is the authority

**Shipped.** The competition's `authority` is the organizer's own key, and
`authority_epoch` exists so a coordinator service could be introduced later
without a protocol reset.

**Open — business.** This means an organizer who loses their key loses the
ability to run the competition, and nothing can recover it. That is the correct
default for a system with no server, and it is a real hazard at a gym. A
documented "hand the competition to a second key before the event" procedure
exists in the protocol (§5.3) but has no UI. Recommendation: build that UI
before a paid competition is run in anger.

## 5. Entry fees, and what "settled" means

**Shipped.** A fee is settled automatically only by a kind-9735 signed by the
key the competition's *own* LNURL endpoint named, over a zap request the entrant
signed, for this competition and this amount, with the invoice's description
hash binding it to that request where the invoice carries one. Recording a
payment by hand is a separate control: an `override` with a mandatory reason
that lands in the audit trail every client can read.

**The limit, stated plainly.** A zap receipt is the provider's attestation, not
proof that sats moved — NIP-57 says so itself. Requiring it to be the
organizer's own provider means the only party who can be defrauded by a rogue
zapper is the organizer who chose it.

**Open — business.** Refunds are a state (`refunded`) with no flow behind them;
a cancelled competition with paid entrants is currently an organizer's problem
to solve out of band. Recommendation: decide whether CruxCoach wants to be
anywhere near refund mechanics before gyms charge real money through it.

## 6. Competition climbs are added by share link

**Shipped.** Every competition climb names a real board climb, added by pasting
what the app already produces (`/c/<naddr>`, `/c/<uuid>`, or a bare uuid).
Community climbs are fetched and described before they are committed; a climb
the competition's board cannot load is refused. Placeholder uuids are refused by
both validators.

**Why not a catalogue browser on the website.** The board database lives in the
app's SQLite file. Putting it on the web is a much larger piece of work than
this feature, and the share link already exists and already round-trips.

**Open — taste.** An "add to competition draft" action in the app's board
browser would let an organizer build the list standing at the wall. The protocol
needs nothing for it. Recommendation: do this next.

## 7. The in-app scanner uses CameraX + ZXing, not ML Kit

**Shipped.** CameraX drives the preview and frame analysis; the ZXing core
already vendored for *generating* codes does the decoding. The camera permission
is requested when the scanner opens and nowhere else, the hardware feature is
optional, and App Link, paste and share all still work without it.

**Why not ML Kit.** It requires Google Play services, which a CruxCoach install
may not have.

## 8. Local keys: lock versus forget

**Shipped.** Signing out *locks* — the plaintext key is zeroed immediately and
the encrypted vault stays, so the same person can come back with their
passphrase. "Forget this key" is a separate, confirmed action that removes the
ciphertext. Plaintext is zeroed automatically at the absolute limit, at the idle
limit, and when the page is hidden.

**Open — taste.** The page-hide timeout is 5 minutes, which is short enough to
annoy somebody who switches to a messaging app mid-competition and long enough
to matter on a shared tablet at a gym desk. Recommendation: make it a setting
once anyone complains in either direction.

## 9. No analytics on the competition pages

**Shipped.** No beacon. The collector's path allowlist lives in a different
repository, and a label it rejects counts nothing, so a beacon here would be
silently discarded rather than merely unused.

**Open — business.** Whether competition pages should be measured at all. They
are the one part of cruxcoach.org where the visitor is identifiable by their own
public key, which makes ordinary page analytics a different proposition than it
is elsewhere on the site.

## 10. Only the two landing pages are indexable

**Shipped.** The organizer console, the participant page and the live screen
carry `noindex`. A competition is addressed by `naddr`; there is nothing at a
stable URL for a search engine to keep.

## 11. `ws://` is allowed for loopback only

**Shipped.** Both clients accept `wss://` anywhere and `ws://` only for
`localhost`, `127.0.0.1` and `[::1]`. The Android app additionally permits
cleartext to loopback in **debug builds only**; the release policy forbids it
and a test asserts the difference.

**Why.** Cleartext WebSocket to a public host lets anyone on the path rewrite a
competition's history. The development relay has no certificate to present, so
loopback is the exception that makes the runbook executable.

## 12. Terminology: "addressable", not "parameterized replaceable"

**Shipped.** NIP-33 was merged into NIP-01; the string "parameterized
replaceable" appears nowhere in NIP-01 today. This spec and both codebases say
**addressable event**.

**Open — housekeeping.** `docs/nostr-architecture.md` §1 still uses the old
wording. It is a pre-existing document and was left alone deliberately;
correcting it is a small, separate change.

## 13. `FEAT-058`, not `FEAT-051`

**Shipped.** 051 was already taken by `goal/nostr-potential-20260804`, which
holds 050–057. That branch was not edited; the survey is recorded in
`docs/specs/INDEX.md`.

## 14. What is deliberately not in this release

Private competitions (the envelope and key-rotation shape are specified;
nothing is encrypted in v1), a coordinator service, short numeric join codes
(they need a registry, and a registry is a central service), teams, leagues,
qualifiers, multi-board and remote events, and personal leaderboards
(FEAT-043 owns those).

None of these is blocked by the wire format.
