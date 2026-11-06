# Community client: protocol map

Status: spike for [#61](https://github.com/CruxCoach/CruxCoach/issues/61), 2026-11-06.

The CruxCoach community runs on a standard [Buzz](https://github.com/block/buzz) server (Apache-2.0).
This document lists what the Buzz phone app does, which events and endpoints each feature uses, how
it authenticates, and what a native client inside CruxCoach needs. It then compares the pinned
version with upstream `main` to estimate upkeep.

- **Pinned version:** `d676c7b8db30ccc15611b9ef51ed9c6f2e8a954a` (2026-10-26), image
  `ghcr.io/block/buzz:sha-d676c7b`. Everything below is read from the source at this commit.
- **Compared with:** upstream `main` at `744f3946f6fe` (2026-11-06).
- **Sources:** `NOSTR.md`, `docs/nips/NIP-*.md`, `crates/buzz-core/src/kind.rs`,
  `crates/buzz-relay/src/{router.rs,api/,handlers/}`, `crates/buzz-auth/src/`,
  `mobile/lib/` (the Flutter phone app), `deploy/compose/`.

## 1. Connection and identity

| Piece | How it works at `d676c7b` | Client needs |
|---|---|---|
| Relay | One WebSocket per community at `wss://<host>` (NIP-01). The host decides the community; there is no tenant tag on the wire. | One dedicated socket, not the app's public-relay pool. |
| Relay info | `GET /` with `Accept: application/nostr+json` (NIP-11). `self` is the relay key that signs relay-only kinds; `limitation.max_limit` is 1000. | Read `self` and drop relay-only kinds (39000–39007, 44100/44101) signed by any other key. |
| Sign-in | NIP-42. The relay sends `["AUTH", <challenge>]` right after connect. The client answers with kind 22242, tags `relay` = `wss://<host>` (no path) and `challenge`; ±60 s clock window. | Answer before any REQ. With `BUZZ_REQUIRE_AUTH_TOKEN` every read needs it. |
| Membership | NIP-43 closed community: after AUTH the relay checks `relay_members`. A non-member gets `OK false "restricted: not a relay member"`. | Treat that as "join with an invite", not as a network error; do not retry. |
| HTTP auth | NIP-98 kind 27235: exactly one `u` (full URL, normalised) and one `method` tag, `payload` = hex SHA-256 of the body on POST routes, ±60 s, each event usable once (replay set). Header `Authorization: Nostr <base64(event)>`. | Sign per request. With Amber this is one approval per request unless the user allows kind 27235 permanently. |
| Agents | Agent keys carry a NIP-OA `auth` tag (owner attestation) in kind 0; owners publish kind 30177 managed-agent records. Agents may authenticate through their owner (`BUZZ_ALLOW_NIP_OA_AUTH`). | Read-only: show an "agent" badge and the owner. Nothing to sign. |

## 2. Joining

| Step | Endpoint / event | Auth | Notes |
|---|---|---|---|
| Invite link | `https://<host>/invite/<code>`; app hand-off `buzz://join?relay=<wss://host>&code=<code>` | — | v2 codes are `v2.` + base64url of 32 bytes. Minted by owners/admins with `POST /api/invites` (`ttl_secs`, `max_uses`). |
| Rules | `GET /api/join-policy` → `{"policy":{terms_markdown, privacy_markdown, age_attestation_required, version}}` or `{}` | none | HTML versions at `/api/join-policy/terms` and `/privacy`. |
| Accept | `POST /api/invites/accept-policy` `{code, policy_version, age_confirmed}` → `{"receipt": …}` | none | The receipt is bound to the code and the policy version. |
| Claim | `POST /api/invites/claim` `{code, policy_receipt}` → `{"status":"joined"\|"already_member", host, role}` | NIP-98 by the joining key | Errors: `invite_expired`, `invite_exhausted`, `invite_invalid`, `join_policy_required`; 429 after 10 tries per minute. |
| Starter rooms | The Buzz apps then create or join `#general` and `#welcome-everyone` themselves (`mobile/lib/app.dart`). | WS | **Our client does not do this**: it would put a new account into open rooms automatically. Upstream moved this to the server (see §5). |

## 3. Capabilities of the Buzz phone app

"Spike" says what the CruxCoach client does today. Kinds are Nostr event kinds; "WS" is the
authenticated WebSocket, "HTTP" a NIP-98 request.

### Rooms and forums

| Capability | Events / endpoints | Auth | What our client needs | Spike |
|---|---|---|---|---|
| My rooms | REQ kind 39002 `#p:[me]` (relay-signed member lists, `["p", key, "", role]`), then kind 39000 `#d:[ids]` | WS | Both are relay-signed; check `self`. Role tells guest (read-only) from member. | ✅ |
| Room directory | REQ kind 39000 without `#d` (relay filters by access; open rooms and mine) | WS | Paging over 1000 rooms; private and DM rooms must stay out. | ✅ |
| Room metadata | kind 39000: `d`, `name`, first `t` = type (`stream`, `forum`, `dm`, `workflow`; `system` only on request), later `t` = labels, `P` creator, `created_at`, `join_role`, `public`/`private`, `restricted`, `closed`, `hidden`, `about`, `topic`, `archived` | WS | Live 39000 updates are not fanned out; refresh on membership notices. | ✅ |
| Membership changes | kinds 44100 / 44101, `#p:[me]` required | WS | Live refresh of the room list. | ✅ |
| Join / leave a room | kind 9021 / 9022 with `["h", room]` | WS | Join gives the room's `join_role`; `guest` rooms are announce rooms. | Join ✅, leave — |
| Create / edit rooms | kind 9007 (`h`, `name`, `visibility`, `channel_type`), 9002 partial edit, 9008 delete | WS | Admin features, phase 2+. | — |
| Forum topics | kind 45001 with `h`; list by REQ `#h` | WS | First line as title. | ✅ |
| Forum comments | kind 45003 with `h` and `["e", parent, "", "reply"]` | WS | Comment counts per topic. | ✅ |
| Forum votes | kind 45002 | WS | Not used by the phone app at `d676c7b`. | — |

### Messages and threads

| Capability | Events / endpoints | Auth | What our client needs | Spike |
|---|---|---|---|---|
| Stream messages | kind 9 with `["h", room]` (40002 rich messages are read too; 40001 legacy) | WS | Markdown content. | ✅ |
| Timeline pages | NIP-CW channel window over `POST /query`: `top_level`, `include_summaries`, `include_aux`, composite cursor `until` + `before_id`; returns rows, aux, kind 39005 thread summaries and exactly one kind 39006 bounds | HTTP | Only top-level rows; exhaustion only from 39006 (`has_more`), `d` tag must echo the request cursor. Not served on WS REQ. | ✅ head page |
| Live messages | REQ `#h:[room]`, `since` now, kinds 9/40002/40099 + aux | WS | Merge by id; replies update thread counts. | ✅ |
| Threads | NIP-10 marked tags: direct reply `["e", head, "", "reply"]`; deeper `root` + `reply`. Broadcast reply adds `["broadcast", "1"]`. Thread reads: REQ `#e:[head]`, or NIP-CW thread windows / legacy oldest-first paging over `/query` (`depth_limit`, `thread_cursor`) | WS / HTTP | Max depth 100. Unknown parents are refused. | ✅ via REQ |
| Thread summaries | kind 39005 (`reply_count`, `descendant_count`, `last_reply_at`, `participants`) | HTTP overlay | Only via the window query. | ✅ |
| Reactions | kind 7 with `["e", target]`; the relay derives the room from the target. Custom emoji: NIP-30 `emoji` tags, kind 30030 sets | WS | Subscribe with `#h` to get them live. | shown, not sent |
| Edits | kind 40003 with `h` and `["e", original]`; author (or the agent's owner) only | WS | Newest edit wins. | shown, not sent |
| Deletes | kind 5 (own, NIP-09) or kind 9005 (own, or admin) | WS | Hide the row. | shown, not sent |
| Mentions | `["p", key]` notifies (key must be a room member), `["mention", key]` names without notifying | WS | Member picker; the relay rejects `p` for non-members. | — |
| Pins, bookmarks, scheduled | kinds 40004, 40005, 40006, reminders 40007 / 30300 (NIP-ER, NIP-44-encrypted to self) | WS | Phase 2. | — |
| Diffs, system rows | kind 40008, 40099 | WS | Show 40099 as a small system row. | read |

### Media, search, DMs, canvases

| Capability | Events / endpoints | Auth | What our client needs | Spike |
|---|---|---|---|---|
| Upload | `PUT /upload` (or `/media/upload`), BUD-02, body = file; images JPEG/PNG/GIF/WebP, video MP4 (100 MB), audio M4A/AAC; no APK | Blossom kind 24242 `t=upload`, `x`=sha256, `expiration` (60 s) + `X-SHA-256` header | Strip metadata, transcode HEIC/video like the phone app does. | — |
| Media in messages | `imeta` tag (`url`, `m`, `x`, `size`, `dim`, `blurhash`, `thumb`, `duration`, `image`, `filename`) + Markdown link in content | — | Render from `imeta`. | — |
| Media download | `GET /media/<sha256>.<ext>` | Blossom kind 24242 `t=get`, **a fresh signature per request** | With Amber: one signature per image unless permanently allowed. Cache aggressively. | — |
| Voice messages | an `audio/mp4` upload, `![audio](url)` + `imeta` with `duration`; waveform drawn client-side | as upload | Recorder + player. | — |
| Search | NIP-50: REQ with `search`, kinds 9/40002/45001/45003, optional `#h`; one-shot, relevance order. `POST /query` adds `search_sort: "newest"` and `page`. People: `POST /profiles/search` | WS / HTTP | | — |
| Direct messages | kind 41010 command opens a DM room (relay answers `response:{channel_id}` in OK); then ordinary kind 9 in a `dm` room (readable by the relay). Hidden DMs: kind 30622. NIP-17 gift wraps (kind 1059, `#p:[me]`) are accepted too. | WS | Decide which DM model to offer (owner question). | — |
| Canvases | kind 40100 with `h`, Markdown content, newest wins | WS | Read view first. | — |

### People, presence, agents, huddles, notifications

| Capability | Events / endpoints | Auth | What our client needs | Spike |
|---|---|---|---|---|
| Profiles | kind 0 (`display_name`, `name`, `picture`, `about`, `nip05` only on the community domain) | WS | Batch fetch by author. | ✅ names |
| Presence | kind 20001 (ephemeral, `online`/`away`/`offline`), owner-for-agents 20003, snapshot 40902 | WS | Optional. | — |
| Typing | kind 20002 (ephemeral) | WS | Optional. | — |
| Status | kind 30315 (NIP-38) | WS | Optional. | — |
| Unread state | Local last-read per room; cross-device sync with NIP-RS kind 30078 `d=read-state…`, `t=read-state`, content NIP-44-encrypted to self | WS | NIP-44 via Amber or the local key. | — |
| Agents | NIP-OA `auth` tag in kind 0, agent profiles kind 10100, personas 30175, managed agents 30177, job kinds 43001–43006, agent presence 20003 | WS | Show badges and job progress rows. | — |
| Huddles | kinds 48100–48106 (lifecycle, guidelines), reactions 24810, audio over WebSocket `/huddle/<room>/audio` | WS | Own audio stack; phase 4. | — |
| Push | NIP-PL push leases (kind 30350) registered with the relay; delivery through a push gateway that serves **APNs only**. The Android phone app has no push at `d676c7b`. | WS | UnifiedPush needs a small gateway next to the relay (phase 3). | — |

### Moderation

| Capability | Events / endpoints | Auth | What our client needs | Spike |
|---|---|---|---|---|
| Report | kind 1984 | WS | Everyone. | — |
| Ban / timeout | kinds 9040–9043; resolve report 9044 | WS | Admins. | — |
| Remove message / member | kind 9005 (admin delete), 9001 (remove user), 9000 (add, roles) | WS | Admins and owners. | — |
| Relay membership | kinds 9030–9033 (add, remove, role, workspace icon); roster kind 13534 | WS | Owners and admins. | — |
| Queues | `GET /moderation/reports`, `/moderation/audit`, `/moderation/restricted` | HTTP + moderator role | Admin screens. | — |

## 4. What the spike implements

Code: `shared/src/commonMain/kotlin/com/cruxcoach/communityclient/` (protocol, state) and
`androidApp/src/main/java/com/cruxcoach/android/communityclient/` (OkHttp transport, Quartz
signing with the local key or Amber, Compose screens).

- `CommunitySession`: one authenticated WebSocket; NIP-42 before any REQ; subscriptions survive
  reconnects; every event's signature is checked, and relay-only kinds must come from `self`.
- `CommunityApi`: NIP-11, rules, accept, claim (NIP-98), `POST /query`, NIP-CW channel windows.
- `CommunityClient`: room list (memberships + directory, live refresh), stream timeline (window +
  live), threads, forums, profiles, join, send, reply, forum post and comment.
- Tag shapes for every event we write match the phone app's at the pinned version.

## 5. Upstream since the pin (`d676c7b` → `main` @ `744f3946f`, 11 days)

Volume: **323 commits** in 11 days (about 29 a day; the 30 days before the pin had 294). 165 of
them touch the phone app (`mobile/lib`, +34k / −12k lines), 64 the relay crate, 20 the protocol
documents (`NOSTR.md`, `docs/nips`).

Changes that touch what the client uses:

| Change | Commit | Effect on our client |
|---|---|---|
| Unlisted open rooms: `["unlisted"]` flag on 39000, `unlisted` tag on 9002/9007, left out of directory reads for non-members | `a3a47e629` | Additive. Show a badge; nothing breaks. |
| Default rooms: `auto_join` on open stream rooms; the **relay** joins every new member into them after an invite claim; bare `["auto_join"]` on 39000 | `cde6c1bf5` | The client-side starter-room logic becomes unnecessary. A new test account would land in default rooms automatically once the server is upgraded and a room is flagged. |
| Invite revocation and claim restrictions; new errors `invite_restricted`, `invite_owner_mismatch` | `d6196a16c` | Request and response unchanged; two more error codes to map (today they show as "not valid"). |
| A second listed open room with the same name is refused | `5f8d0aab9` | Only for room creation. |
| kind 9007 must carry `h` (documented) | NOSTR.md | We already send it. |
| Compact membership discovery (kinds 39008/39009 reserved, NIP-98 query) | `66c3d6917` | Defined, not enabled. Later replaces the "my rooms via 39002" read; 39002 stays compatible. |
| WS search and REQ with a failed history read now end with `CLOSED` instead of `EOSE` | `1bb44ae9e`, `76cd4294f` | We already treat `CLOSED` as the end of a query. |
| Custom emoji entries kind 30180; read-state frontier (NIP-RS) | `5c91e0d6f`, `dafe31bfe` | Additive, phase 2. |
| Several people in one kind 9000; kind 9000 atomic | `d0dee1cb5`, `78b5dae1f` | Admin features only. |
| NIP-FI federated identity, agent tokens, NIP-AR artifact sync | several | Server/agent side; not used by the client. |

Assessment: in these 11 days nothing changed the wire format the spike uses (kinds, tags, NIP-42,
NIP-98, invite claim, NIP-CW). The changes are additive flags and server-side behaviour. The phone
app moves fast; the protocol documents move slower but steadily (20 commits). Expected upkeep:
a review of `NOSTR.md`/`docs/nips` and the relay routes before each server update, plus the CI
integration test against the new image. See the spike report for the estimate in days.
