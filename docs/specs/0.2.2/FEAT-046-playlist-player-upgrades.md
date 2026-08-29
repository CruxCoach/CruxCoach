---
status: planned
queue: needs-clarification
base: 0.2.2
depends_on: [FEAT-045]
created: 2026-07-15
updated: 2026-07-18
---

# Feature Spec: Playback lifecycle and interaction upgrades

> **Depends on:** FEAT-045. The saved model is one list with an optional
> training plan; there is no playlist kind.
>
> **Relates to:** FEAT-044 (CruxRelayService may run concurrently)

## 1. Overview

This follow-up contains three independent player upgrades:

1. a media-style foreground service and explicit process-death resume;
2. atomic drag reorder in the training-plan editor;
3. finger-tracking horizontal swipe in the active player.

The internal `Playlist*` class names may remain until a dedicated technical
rename. Product copy must use **playback**, **session**, **list**, and
**training plan**.

## 2. Current behaviour

- `PlaylistPlaybackCoordinator` owns the active queue and GATT session in
  memory. Process death ends that state while an exact rest alarm can outlive
  it.
- The training-plan editor supports deterministic move up/down controls. It
  does not yet provide long-press drag reorder.
- Player swipe commits only after release and provides no finger-tracking
  movement.
- Normal-list and training-plan playback both use the same coordinator and
  therefore need the same lifecycle treatment.

## 3. Foreground service and resume

### 3.1 Ownership

The coordinator remains the single source of truth and only playback mutator.
The foreground service observes coordinator state, renders a notification,
and forwards transport actions. Playback logic must not move into the service.

Use a `mediaPlayback` foreground-service type with a distinct notification id
and channel from CruxRelay. MediaSessionCompat plus MediaStyle is sufficient;
no audio engine or media3 dependency is required.

Notification actions map to the existing coordinator operations:

- previous;
- play/pause;
- next or skip rest;
- stop through the normal session stop path.

### 3.2 Snapshot

Persist a versioned host snapshot whenever queue, index, phase, or relevant
settings change. It must contain enough data to restore either source:

- source label/type for UI only;
- queue items with UUID, angle, and attached rest;
- current index and phase;
- host/session metadata;
- rest end timestamp when resting;
- saved-at timestamp.

Do not reconstruct from the current list or plan on resume: the saved list may
have changed after playback started. Resume the exact active queue snapshot.

Participant sessions are never resumable because their GATT relationship dies
with the process.

### 3.3 Resume UX

After app start, a fresh host snapshot offers **Resume session?** or
**Discard**. Resume is explicit because the board connection must be
re-established and the previously lit state may no longer be current.

A first implementation may use a six-hour staleness threshold. Stale or
discarded snapshots must clear both snapshot storage and any orphaned rest
alarm. No wakelock is required; the existing exact alarm remains responsible
for the rest boundary.

## 4. Atomic training-plan reorder

Drag reorder operates only on `list_playback_steps`. It must never reorder or
duplicate `climb_list_entries` membership.

- Add a repository operation that replaces the full ordered step-id snapshot
  in one transaction and writes dense positions.
- Serialize editor mutations with a `Mutex` or equivalent single-writer
  mechanism.
- Persist the complete UI order, not relative index deltas.
- Keep move up/down buttons as keyboard and accessibility fallback.
- Long-press drag swaps at neighbour midpoints and animates displaced rows.
- A missing/concurrently removed step cancels or reconciles safely on refresh.

New list members are not plan steps until the user selects the existing
**Append new list climbs** action. That separation removes the former
add-dialog-versus-reorder race.

## 5. Finger-tracking swipe

Use a single-render anchored horizontal drag rather than a pager:

- center, previous, and next anchors;
- previous/next anchors present only when allowed;
- content translation follows the finger after touch slop;
- below-threshold drag springs back;
- threshold or qualifying fling advances exactly once;
- after commit, reset the drag anchor while the existing content transition
  renders the new queue item.

Board taps and quick-log buttons must remain independently tappable. The rest
screen does not need swipe; transport controls already skip or navigate it.

## 6. Strings

| Key | English | German |
|---|---|---|
| `playback_notification_channel` | Active session | Aktive Session |
| `playback_notification_channel_desc` | Controls for active list playback | Steuerung der aktiven Listenwiedergabe |
| `playback_notification_resting` | Rest - %s | Pause - %s |
| `playback_notification_paused` | Paused | Pausiert |
| `playback_resume_title` | Resume session? | Session fortsetzen? |
| `playback_resume_message` | Your last session ended unexpectedly. Continue where you left off? | Deine letzte Session wurde unerwartet beendet. Dort weitermachen? |
| `playback_resume_confirm` | Resume | Fortsetzen |
| `playback_resume_discard` | Discard | Verwerfen |
| `cd_playback_previous` | Previous problem | Vorheriges Problem |
| `cd_playback_next` | Next problem | Naechstes Problem |
| `cd_playback_play_pause` | Play/pause | Wiedergabe/Pause |
| `cd_drag_handle` | Reorder | Verschieben |

Final German resources should use the repository's established UTF-8 spelling
(`Nächstes`) even though this specification remains ASCII-safe.

## 7. Acceptance criteria

1. Random rapid reorder operations converge to the final editor order in the
   database, with dense step positions and unchanged list membership.
2. Move up/down and drag use the same serialized persistence path.
3. A playback snapshot round-trips normal-list and training-plan sessions
   without reading current list contents.
4. Fresh host snapshots prompt; participant, stale, absent, or invalid
   snapshots do not.
5. Discard/stale cleanup clears persisted rest alarms.
6. The playback service follows active state and does not conflict with
   CruxRelay's service id, channel, or lifecycle.
7. Notification actions advance, pause, and skip rest through the coordinator.
8. A sub-threshold swipe springs back; a committed swipe advances exactly
   once; unavailable directions cannot be dragged.
9. Board tap and quick-log interactions remain functional.

## 8. Testing and open decisions

JVM coverage is required for reorder convergence, snapshot serialization,
resume decisions, alarm cleanup, action mapping, and swipe threshold math.
On-device coverage is required for notification/lock-screen behaviour,
process death, Doze rest expiry, drag feel, swipe gestures, and concurrent
relay plus playback services.

Open decisions before implementation:

- confirm MediaSessionCompat rather than media3;
- confirm the six-hour snapshot staleness threshold;
- confirm hand-rolled drag state rather than a dependency;
- confirm anchored draggable rather than a pager.

## 9. Android 9 GATT fallback and concurrent edits

The 0.2.2 playlist implementation remains the compatibility transport for
Android 9 devices. The host is the single sequencer and its queue is always the
authoritative state. Participants do not optimistically mutate their local
queue; they wait for the host's state/event broadcast.

New clients append a versioned semantic context and a request id after the
legacy command bytes. Existing 0.2.2 hosts decode the unchanged prefix and
ignore the extension. New hosts use the extension to make concurrent edits
safe:

- add and resend are independent and can be applied in arrival order;
- remove and select-current follow the referenced climb rather than a stale
  numeric index;
- next/previous require the same current climb and adjacent target;
- move follows the moved climb and its destination neighbours;
- repeated identical climbs are accepted only while their occurrence remains
  unambiguous.

Commands are consumed from a bounded GATT channel. If it is full, Android
returns a failed characteristic write instead of acknowledging and silently
dropping the action. That channel belongs logically to one host session even
though its server object is process-wide: server stop and the next host start
discard any orphaned backlog, and JOIN is accepted only from an address still
present in the server's authoritative connected set. Commands accepted during
teardown therefore cannot create a participant or mutate the next queue. The
host caches recent request results to make a repeated
write idempotent and sends the result only to the requesting device. The UI
shows outstanding changes and reports transport failures or semantic
conflicts. A result indicator expires after five seconds without retry when
talking to an older host: retrying an add against a host that does not
understand request ids could duplicate it.

Legacy capability is connection-scoped, not selectable per command. A peer
that has never sent a valid semantic context keeps the historical raw-command
fallback (including Android 9 links whose MTU cannot carry the extension).
Once a joined connection has proved context support, every later mutation on
that connection must retain it; a context-free downgrade is dropped. The
capability bit is cleared with JOIN authority on disconnect, LEAVE, server stop,
or host restart, so a legacy peer is never permanently classified by address.

The authoritative full queue broadcast remains the recovery mechanism after a
dropped notification or reconnect. Command-result notifications fit the
default 20-byte ATT payload; the existing MTU negotiation continues to carry
the semantic request extension and paged full-state frames.

Host migration preserves the queue but does not inherit publication consent.
A newly advertised successor is also only an unauthenticated BLE hint. The
first elected participant always promotes locally and cannot be diverted by an
ambient advertisement. A later participant may see that new host; the app
shows its name with an explicit warning and does not open GATT until the user
chooses **Join session**. Confirmation re-resolves the exact live session-id and
device-address pair once; a decline, dismissal, vanished offer, or replaced
advertisement keeps/promotes the preserved queue locally. Bluetooth recovery
first restores the existing host's own authoritative transport and applies the
same one-answer boundary before any deliberate switch to another group. A
confirmed recovery switch sends the ordinary migrate sentinel and keeps the
server alive for its bounded delivery window before stopping GATT and joining;
explicit leave/manual join cancels any older delayed election generation.

A promoted participant becomes a local-only host and receives one persistent
local/joinable choice. The transport refuses a local-only `startSharing()` call;
only an explicit joinable request can open GATT and advertising. The chosen
state stays visible in both compact and expanded session status. Saved/running
playlists remain local-only and never show this choice.
