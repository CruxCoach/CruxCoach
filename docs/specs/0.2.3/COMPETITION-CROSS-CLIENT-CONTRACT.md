# Competition cross-client contract

Status: implemented contract for `cruxcoach-competition/1`.

Android and the web application may use different layouts, but they must agree
on signed state, available actions, privacy boundaries and failure states.

## Canonical state and authority

- The organizer/host authority is the only key that turns participant intents
  into competition state. Registration and check-in are permissioned.
- Both clients reduce the byte-identical fixture set whose manifest digest is
  `1bb9ed1c97dbabfe4a0ea528926a2252f39ca4474406e2f985a664846567158f`.
- New competitions use `rules.queue_policy = automatic`. The stable order is
  `sha256(comp_id || participant_pubkey)`; accepted, checked-in and payment-
  eligible entrants are reconciled automatically without a manual seed.
- `complete_turn` atomically records the host-confirmed result and selects the
  next participant. `skip_turn` consumes no attempt. Post-finish corrections
  replay at the original operation and remain auditable.
- Participant choice means choosing a climb from the competition pool for the
  current turn. The choice is an intent; the host still confirms the result and
  sends the climb to the physical board.
- Exactly one authority key is implemented. Co-host keys are deliberately not
  simulated by sharing the host secret; adding one requires a revocable,
  explicitly authorized recipient/authority protocol.

## Physical board boundary

Every competition names the board brand, model, layout, size and angle. Android
additionally requires `board.cell_id` for newly created physical competitions,
must be a member of that BoardCell, and requires the host phone to be the active
board controller before it publishes authority operations. The browser cannot
discover or authenticate a physical BoardCell and must not claim that it did.

## Privacy boundary

`participant_data_visibility` has two values:

- missing or `local`: private/on-site;
- `online`: participant state is also published to the named Nostr relays.

The transition from local to online is explicit, confirmed and irreversible.
Both reducers reject an attempted online-to-local downgrade. Relay copies cannot
be reliably recalled.

General definition data (title, schedule, venue, board and rules) may be public
or unlisted in either mode. Registration is always a complete signed inner
intent, containing only the participant public key and display name, inside a
NIP-44 envelope to the host. A request remains pending until the host's signed
decision exists.

In local mode, names, participant list, check-in, queue, choices, attempts and
detailed results stay in the Android competition mesh. The browser:

- may show general information and send encrypted registration;
- must not publish other participant operations;
- must not render a public participant list, queue or detailed results;
- tells hosts to run on-site operations in Android.

In online mode, the signed authority chain and results may be published. Public
projection may show the leaderboard, but integrity remains fail-closed on an
incomplete chain or fork.

## UX truthfulness

Lifecycle and transport status are separate. A write is successful only after
its normal transport confirms it. Offline, incomplete and forked state must be
shown rather than guessed. Unavailable operations are explained; they are not
presented as working controls.

Android is authoritative for physical board output and local mesh recovery.
Web is authoritative for its richer catalogue-driven creation form and remote
relay recovery. Each client adopts the other's protocol behavior where it is
stronger; neither pretends to provide hardware or privacy guarantees it cannot
enforce.
