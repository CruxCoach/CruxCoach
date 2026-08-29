# FEAT-063 — Nostr Background Privacy Control

> **Status:** Planned
>
> **Target:** v0.2.3
>
> **Depends on:** FEAT-001 relay discovery and the existing Nostr consumers

## Problem

CruxCoach currently starts relay discovery, DM delivery, relay reconnection,
notification polling and community-climb subscriptions with the application
process. Even when no content is published, relays can correlate a stable Nostr
public key or subscription filter with an IP address, time and usage pattern.
That unattended metadata disclosure conflicts with the app's local-first and
privacy-first positioning.

## Product boundary

Add one persistent Settings control for **Nostr background services**. It
controls unattended Nostr traffic only; it is not a master kill switch for
explicit user actions.

When disabled:

- NIP-65 discovery does not refresh automatically;
- live DM and community-climb subscriptions are stopped;
- network-change and foreground hooks do not reconnect relays;
- periodic and foreground Nostr polling self-skip;
- no new background Nostr worker is scheduled.

The following remain available after an explicit user action:

- publish or delete a profile or community climb;
- manually refresh a profile, messages or community data;
- send developer feedback;
- perform an encrypted backup when enabled through its separate backup control.

Board control, local logbook/training, public board-catalogue sync, Kilter sync
and app updates are outside this setting.

## Required implementation properties

1. Persist the choice in DataStore and expose it as a `Flow<Boolean>`.
2. Fresh installs fail closed: background Nostr traffic is off until enabled.
3. Upgrades receive a one-time disclosure rather than silently changing or
   silently preserving the old always-on behavior.
4. Toggling off takes effect during the current process: cancel subscriptions,
   unregister connectivity callbacks and cancel relevant work.
5. Toggling on is idempotent and starts exactly one instance of each consumer.
6. Manual actions acquire a short-lived relay session and release it when the
   operation completes. Shared relay ownership must prevent one consumer from
   closing sockets still used by an explicit operation or backup.
7. Queued publications that originated from explicit user confirmation are
   shown to the user; whether to retry them while background services are off
   must be an explicit, separately worded choice.
8. No generated Nostr key, public key or relay filter is sent merely by opening
   Settings or reading the preference.

## Acceptance tests

- Cold start with the setting off makes no Nostr relay connection.
- Foreground and connectivity changes with the setting off make no connection.
- Every app-scoped subscriber and Nostr worker stops when the setting flips off.
- Local features and Bluetooth board control remain functional while off.
- Explicit publish and manual refresh work while off, then leave no background
  relay subscription behind.
- Backup behavior remains governed only by the separate backup opt-in.
- Process restart preserves the selected state.
- Identity switching cannot re-enable background traffic.
