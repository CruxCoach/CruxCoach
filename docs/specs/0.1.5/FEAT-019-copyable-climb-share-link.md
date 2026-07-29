---
status: planned
target: 0.1.5
---
# Feature Spec: Copyable Climb Share Link

> **Status:** Planned for 0.1.5. Lightweight UX polish on top of
> the existing FEAT-003 deep-link infrastructure — the publisher
> already mints `cruxcoach.org/c/<naddr>` URLs for community
> climbs, and `MainActivity.extractClimbAppLink` already routes
> incoming taps back to the right screen. What's missing is the
> outbound side: a way for the user looking at a climb to copy
> that link and paste it into a chat / email / Discord post.
>
> **Depends on:**
> - FEAT-003 (Climb Creator + Nostr-publish path) — provides the
>   `naddr` string + app-link URL builder. Ships in 0.1.4; this
>   spec assumes it's merged into `dev` by the time 0.1.5 work
>   starts.
>
> **Relates to:**
> - FEAT-014 (Live Training Coordination) — same `naddr` shape
>   surfaces there; consistent link-share UX across both paths.
> - FEAT-010 (Profile Editor) — uses the same clipboard-helper
>   pattern this spec installs (small reusable composable).

---

## 1. Overview

A user looking at a climb in `BoardClimbDetailScreen` today has
no in-app way to share that climb with someone else. The
publisher already constructs a stable share-URL of the shape
`https://cruxcoach.org/c/<naddr>` (FEAT-003 §4.4) and embeds
that URL in the Auto-Note Kind-1 event, but the URL never
reaches the user's clipboard from the receiver side. The
workaround today is to ask the climb's setter to re-share the
Auto-Note link, or to navigate to the Kilter app and copy a
climb name out of there for a "manual" share.

This spec adds a "Copy link to climb" entry to the existing
`BoardClimbDetailScreen` overflow menu (the same menu that
already hosts "Delete publication" for own climbs). Tapping it
copies the climb's `cruxcoach.org/c/<naddr>` URL to the system
clipboard and shows a transient snackbar confirming the copy.

### 1.1 Goals

- A user looking at any **community-published** climb
  (`origin = 'cruxcoach'`, has a non-null `nostr_d_tag` +
  `nostr_event_id` + `created_by_pubkey`) can copy a share-URL
  with one tap from the overflow menu.
- The copied URL works as an Android App Link (existing
  `intent-filter` in the manifest catches `cruxcoach.org/c/*`
  → `extractClimbAppLink` → climb-detail screen at the user's
  preferred angle).
- The URL is deterministic — every user looking at the same
  climb gets the same URL, regardless of angle / their UI
  state. The angle is intentionally NOT part of the URL
  (climbs are angle-agnostic at the data layer; the recipient
  lands on their own preferred angle).
- Snackbar confirms the copy. Accessibility: TalkBack announces
  "Climb link copied to clipboard".

### 1.2 Non-Goals

- Sharing Kilter-original climbs (`origin = 'kilter'`). These
  have no Nostr d-tag, so no `naddr`, so no
  `cruxcoach.org/c/<naddr>` URL. See §3.4 for the deferred
  approach.
- An "Android Share Sheet" intent (`ACTION_SEND` to other apps).
  Copy-to-clipboard is the universal-compatibility option and
  ships in a few lines; a full share-intent is a separate
  follow-up if user-feedback asks for it.
- Mirroring the link in the climb detail screen as a visible
  read-only field. Overflow-menu copy is sufficient; pinning
  a 60-char URL into the layout adds clutter without proportional
  UX gain.
- Generating a QR-code for the link. Nice-to-have, but the
  primary use-case is text-share in chat apps; ship copy first.

---

## 2. Today's behaviour

The `BoardClimbDetailScreen` has an overflow menu (top-right
three-dot button) that today contains:

- **"Delete publication"** — visible only for own community
  climbs (FEAT-003 / FEAT-017's `CommunityClimbDeleter` path).

That's the entire menu surface as of 0.1.4. For community
climbs the user can read the setter name + view the climb but
has no way to extract a URL for it. For Kilter-original climbs
the menu is empty (or hidden entirely depending on the route).

The `naddr` string + URL are constructed today inside
`CommunityClimbPublisher.publishClimb` for outbound publishing;
the same builder lives there but isn't exposed to the
detail-screen path.

---

## 3. Solution design

### 3.1 Surface a share-URL builder

Extract the existing URL-building logic from
`CommunityClimbPublisher` into a small standalone helper —
ideally in `shared/`, because it's pure (no Android deps):

```kotlin
// shared/src/commonMain/kotlin/com/cruxcoach/domain/community/ClimbShareLink.kt
object ClimbShareLink {
    /**
     * Builds the public share URL for a community-published climb.
     * Returns null for climbs that aren't shareable (Kilter-original,
     * never published to Nostr, or missing d-tag / event-id metadata).
     */
    fun forCommunityClimb(
        appLinkHost: String,
        nostrEventId: String?,
        nostrDTag: String?,
        createdByPubkey: String?,
        kind: Int = 30078,
    ): String?
}
```

Implementation: encodes `(kind, pubkey, d-tag, relay-hints)`
into a NIP-19 `naddr1...` bech32 string, then formats
`https://$appLinkHost/c/$naddr`. The function is pure and JVM-
testable — covers Kotlin-Multiplatform commonMain so the
publisher can call it too, eliminating the duplication.

### 3.2 Detail-screen overflow-menu item

In `BoardClimbDetailScreen`'s overflow-menu composable, add a
new `DropdownMenuItem` above the existing "Delete publication"
entry:

```kotlin
DropdownMenuItem(
    text = { Text(stringResource(R.string.climb_share_copy_link)) },
    leadingIcon = {
        Icon(Icons.Default.Link, contentDescription = null)
    },
    onClick = {
        viewModel.copyShareLinkToClipboard(context)
        overflowExpanded = false
    },
)
```

Visibility gating: the item only appears when the climb's
`origin = 'cruxcoach'` AND all three Nostr fields
(`nostrEventId`, `nostrDTag`, `createdByPubkey`) are non-null.
For Kilter-original climbs, the item is hidden — the menu
collapses to whatever else is present, or doesn't show at all
if there are no other items.

### 3.3 ViewModel + clipboard

`BoardClimbDetailViewModel.copyShareLinkToClipboard(context)`:

1. Read the climb's metadata from `state.value.climb`
   (already loaded by the detail-screen state-fetch).
2. Call `ClimbShareLink.forCommunityClimb(...)` with
   `BuildConfig.APP_LINK_HOST` and the climb's fields.
3. On null → log a `Log.w` + don't show the snackbar (the
   visibility gate above means this path is theoretically
   unreachable, but defensive).
4. On non-null URL: write to the system clipboard via the
   `ClipboardManager` from `context.getSystemService`, then
   emit a one-shot event the screen observes to show the
   "Link kopiert" snackbar.

Use the same `Channel<SnackbarEvent>` shape the screen already
uses for other one-shot UI events (e.g. the
`communityDeleteFeedback` channel from FEAT-017's M17 fix).

### 3.4 What about Kilter-original climbs?

The user wishlist included "share any climb", which is broader
than this spec. The problem: Kilter-original climbs don't have
a Nostr d-tag, so `cruxcoach.org/c/<naddr>` isn't constructible.

Options, all deferred to follow-up specs:

- **A: `cruxcoach.org/k/<climb_uuid>` redirect.** Maintainer-
  side reverse-proxy serves a small page that links into the
  Kilter app via `kilter://...` and falls back to a "this climb
  is in the Kilter catalog — open the Kilter app" page. Pure
  ops work, no app code beyond a second URL-builder branch.
- **B: NIP-78 community-tag a Kilter climb post-hoc.** Publish
  a Kind-30078 event referencing the Kilter UUID (a "mirror"
  d-tag scheme). Lets the climb participate in CruxCoach's
  Nostr ecosystem but creates a parallel-records issue.
- **C: Generate a deep-link to the Kilter app directly.** No
  CruxCoach indirection. Works only if the user's friend has
  the Kilter app installed.

None of these are part of this spec. This spec ships
community-climb sharing in 0.1.5 and leaves the Kilter-original
share path open for 0.1.6+.

---

## 4. Strings (en + de)

```xml
<string name="climb_share_copy_link">Link kopieren</string>  <!-- de -->
<string name="climb_share_copied_toast">Link in Zwischenablage kopiert</string>
<string name="climb_share_copy_link">Copy link</string>      <!-- en -->
<string name="climb_share_copied_toast">Link copied to clipboard</string>
```

The snackbar uses `SnackbarDuration.Short` (1.5 s) — long
enough to read, short enough not to block the next action.

---

## 5. Edge cases

### 5.1 Missing `BuildConfig.APP_LINK_HOST`

The fallback in `local.properties` is `cruxcoach.org`. If a
fork is built with a different host, the share-URL points to
that host — the recipient must have the fork installed to
catch the App Link. Acceptable: forks already understand
they need to ship their own URL infrastructure.

### 5.2 Tombstoned climb

If the user is looking at their own deleted climb
(`is_deleted = 1`), the URL still resolves to a Kind-30078
that exists on relays — but the relay may have honoured the
tombstone-replacement event and serve the deletion stub
instead. Acceptable: the share-URL is still technically valid,
the recipient sees "this climb was deleted" via the relay's
own behaviour. The "Copy link" item stays visible.

### 5.3 Clipboard access on Android 13+

`ClipboardManager.setPrimaryClip` works without runtime
permission on every Android version. On Android 13+ the system
shows a transient "Copied to clipboard" UI itself in addition
to our snackbar. That's fine — the duplication doesn't hurt
and the system UI is what privacy-conscious users will see.

### 5.4 Empty / malformed Nostr metadata

If a community climb somehow has `nostr_event_id = null` (data
corruption, partial restore), the URL builder returns null and
the menu item is hidden. No crash, no broken URL.

---

## 6. Testing

### 6.1 JVM

- `ClimbShareLink.forCommunityClimbTest`:
  - happy path with full metadata → returns expected
    `https://<host>/c/naddr1...` URL.
  - null `nostrEventId` → null.
  - null `nostrDTag` → null.
  - null `createdByPubkey` → null.
  - Round-trip: feed the produced naddr back into Quartz'
    Nip19 decoder and assert (kind, pubkey, d-tag) match.

### 6.2 Maestro

- New `flows/climb-share-copy-link.yaml`:
  1. Open the climb-browser, tap a community climb.
  2. Tap the overflow icon.
  3. Assert "Link kopieren" is visible.
  4. Tap "Link kopieren".
  5. Assert the snackbar "Link in Zwischenablage kopiert" appears.
  6. Read clipboard via Maestro's `clipboard` command, assert
     the value starts with `https://cruxcoach.org/c/naddr1`.

---

## 7. Estimated complexity

- 3 files touched: `ClimbShareLink.kt` (new), `BoardClimbDetailViewModel.kt`,
  `BoardClimbDetailScreen.kt`.
- 2 strings × 2 locales.
- ~80 lines new code + ~30 lines tests.
- Effort: ~0.5 day.
