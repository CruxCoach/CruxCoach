# Competition data boundary

Status: implemented Android contract for the FIPS competition mesh. The website
implementation lives in the separate `cruxcoach-pages` repository and must use
the same boundary.

## The two simple modes

Every new competition starts in **Private / on site** mode
(`participant_data_visibility = local`). The host can make one explicit,
confirmed and irreversible transition to **Published online**
(`participant_data_visibility = online`). There is intentionally no automatic
switch based on connectivity and no silent fallback from encrypted to clear
text.

| Data | Private / on site | Published online |
|---|---|---|
| Title, description, schedule, venue, rules, board model/cell | Public or unlisted Nostr definition | Same |
| Registration request | NIP-44 encrypted to host on Nostr and also local mesh | Same |
| Host registration/check-in confirmation | Minimal NIP-44 receipt to that participant | Same |
| Names, participant list, queue, climb choices, attempts, detailed results | Signed authority chain in local competition mesh only | Signed authority chain additionally copied to the configured Nostr relays |
| On-site leaderboard | Derived locally from the signed chain | Derived locally and online |

The public definition never proves registration. A request is only an intent;
the host's signed `registration_decision` and `checkin` entries remain the
canonical state. The encrypted receipt merely lets a remote participant see the
host's answer before arriving. At the venue the complete canonical state is
reconciled from the competition mesh.

## Registration envelope

The participant signs the ordinary intent for local mesh delivery. For online
delivery, that complete signed intent is NIP-44 encrypted to the competition
authority and placed in a separately signed kind `30078` event marked with
`["enc", "nip44"]`. After decryption the host verifies the inner event again;
therefore online delivery and later local history replay retain one stable
intent id. Only the minimal `display` value is in the intent data; the signing
pubkey is authenticated by the event. Division is assigned by the host (the
competition's first division is the current default). Waiver text and
acceptance are UI/legal workflow and are not copied into the online request.

The initial implementation encrypts only to the host authority. A co-host must
not be added by sharing the host secret. Future co-host support requires an
explicit, revocable recipient list and separately encrypted envelopes for each
co-host.

## Board binding

New physical competitions include `board.cell_id`, the hashed BoardCell id of
the concrete connected wall. Raw BLE addresses and serial numbers are not
published. Creation fails if there is no active BoardCell or if the configured
cell does not match it. Competition mesh frames from another physical board or
cell are rejected before their event payload is ingested.

## Host wording

The host surface must always say which state applies:

- **Private by default:** general competition information is online; participant
  data and detailed results stay on site.
- **Publish participants and results online…:** opens a confirmation naming
  names, registration, check-in and detailed results and explaining that relay
  copies cannot be reliably recalled.
- Once confirmed, the UI says **Online publication active**. There is no fake
  “undo” control.

## Website requirements

The website may render the general definition in both modes. In local mode it
must not infer participant state from encrypted envelope metadata, expose a
participant list, or claim that a request was accepted. It may create an
encrypted registration intent for the host and decrypt a private host receipt
for the participant identity. It may render participant state/results only
after the signed authority chain contains the explicit online visibility update
and is complete and unforked.
