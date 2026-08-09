# Nostr-Architektur

Referenz für alle Features, die Nostr benutzen — über beide Repos hinweg:

- **`CruxCoach/`** (diese App) — publiziert und konsumiert
- **`cruxcoach-blossom-sync/`** (Pipeline) — publiziert Kataloge, konsumiert Community-Climbs

Speicher-, Volumen- und Retention-Fragen stehen nicht hier, sondern in
`cruxcoach-blossom-sync/BLOSSOM_STORAGE.md`.

---

## Inhalt

1. [Grundlagen](#1-grundlagen)
2. [Identitäten und Schlüssel](#2-identitäten-und-schlüssel)
3. [Relay-Sets](#3-relay-sets)
4. [Feature: Board-DB-Manifest](#4-feature-board-db-manifest)
5. [Feature: Community-Climbs](#5-feature-community-climbs)
6. [Feature: Auto-Note](#6-feature-auto-note)
7. [Feature: Backup-Sync](#7-feature-backup-sync)
8. [Feature: Profil](#8-feature-profil)
9. [Feature: Relay-Discovery](#9-feature-relay-discovery)
10. [Feature: DMs und Announcements](#10-feature-dms-und-announcements)
11. [Feature: Zaps](#11-feature-zaps)
12. [Feature: Updater](#12-feature-updater)
13. [Feature: Share-Links](#13-feature-share-links)
14. [Querschnitt: Aktualität](#14-querschnitt-wie-der-aktuellste-stand-bestimmt-wird)
15. [Querschnitt: Vertrauensmodell](#15-querschnitt-vertrauensmodell)
16. [Register: Kinds, Namespaces, d-Tags](#16-register)
17. [Wo was im Code liegt](#17-wo-was-im-code-liegt)
18. [Fallstricke](#18-fallstricke-die-uns-schon-eingeholt-haben)

---

## 1. Grundlagen

Ein Nostr-Event hat sieben Felder:

```jsonc
{
  "id":         "…",   // sha256 über [0,pubkey,created_at,kind,tags,content]
  "pubkey":     "…",   // wer signiert hat
  "created_at": 1786076044,
  "kind":       30078,
  "tags":       [...], // indiziert — hierüber filtern Relays
  "content":    "…",   // freier String, für Relays undurchsichtig
  "sig":        "…"    // Schnorr über die id
}
```

Zwei Eigenschaften bestimmen die gesamte Architektur:

**`content` ist nicht durchsuchbar.** Relays filtern ausschließlich über `kind`,
`authors`, `created_at` und Tags. Alles, wonach später gesucht werden muss, gehört
in die Tags — deshalb steht bei uns vieles doppelt drin (Tag *und* Content).

**`kind` bestimmt die Speicherregel des Relays:**

| Bereich | Regel | bei uns |
|---|---|---|
| `1`, `1059`, `9734` | **Regular** — jedes Event bleibt einzeln erhalten | Notes, DMs, Zaps |
| `10000–19999` | **Replaceable** — pro `(pubkey, kind)` nur das neueste | Relay-Liste, Server-Liste |
| `20000–29999` | **Ephemeral** — wird nicht gespeichert | Blossom-Auth, Amber-Hilfssignatur |
| `30000–39999` | **Parameterized-Replaceable** — pro `(pubkey, kind, d-Tag)` nur das neueste | Manifest, Climbs, Backup, Releases |
| `5` | **Deletion** — bittet Relays, anderes zu löschen | Climb-Löschung, Backup-Opt-out |

Der `d`-Tag bei 30078 ist ein frei wählbarer Schlüssel. Bild: ein Regal, das dem
`pubkey` gehört, mit einem Fach pro `d`-Namensschild. Es passt immer nur ein Ding
rein, Neues überschreibt Altes. Fast alles hier ist darauf gebaut.

**Konsequenz, die immer wieder relevant wird:** Ein Re-Publish auf denselben d-Tag
ist idempotent. Ein Edit ist kein Update-Befehl, sondern einfach ein neueres Event.
Und niemand kann fremde Fächer beschreiben, weil der Pubkey Teil des Schlüssels ist.

---

## 2. Identitäten und Schlüssel

Drei Klassen von Signierschlüsseln, die nie vermischt werden dürfen:

| Rolle | Pubkey | Wo definiert | Signiert |
|---|---|---|---|
| **Manifest-Key** | `70b2740bff77cf65743a7d6ffa5465b3a27105ae26123458cf5450eafb1bd68d` | `BlossomSyncManager.MANIFEST_PUBKEY`, Pipeline `.env` (`BLOSSOM_NSEC`) | alle 7 Katalog-Manifeste, alle Blossom-Uploads der Pipeline |
| **Dev-/Announce-Key** | `e75a185c…` (Default, überschreibbar) | `BuildConfig.MAINTAINER_PUBKEY` → `NostrConfig.DEV_PUBKEY` | Announcements, Support-DMs |
| **User-Key** | pro Installation | `NostrKeyStore` (lokal) oder Amber | Climbs, Backups, Profil, DMs, Zaps |

Manifest- und Dev-Key sind **verschiedene** Identitäten. Das ist Absicht: Der
Manifest-Key läuft unbeaufsichtigt im Cron auf einem Server, der Dev-Key ist eine
persönliche Nostr-Identität. Kompromittierung des einen darf das andere nicht
betreffen.

Beide sind über `local.properties` fork-überschreibbar (siehe
`CONTRIBUTING.md`, „Customizing for forks"). Ein Fork, der das nicht tut, würde
sonst den Upstream-Maintainer verstärken — deshalb ist die p-Tag-Erwähnung im
Auto-Note zusätzlich per `BuildConfig.AUTO_NOTE_PTAG_MAINTAINER` gegated.

**Signer-Modi** (`SignerMode`): `LOCAL` (nsec im `NostrKeyStore`) oder `AMBER`
(externer Signer per Intent, nsec verlässt Amber nie). Der Unterschied ist an
mehreren Stellen architekturrelevant, am stärksten beim Backup — siehe §7.

---

## 3. Relay-Sets

Zwei getrennte Listen, beide in `NostrConfig.kt` einkompiliert:

**`DEFAULT_RELAYS`** — normaler User-Traffic (Climbs, DMs, Profile, Backups):

```
wss://relay.damus.io
wss://nos.lol                ← das einzige Relay, das Community-Climbs empirisch langfristig hält
wss://relay.primal.net
wss://nostr-pub.wellorder.net
wss://nostr.oxtr.dev
```

Wird zur Laufzeit **additiv** mit der NIP-65-Liste des Users gemergt (§9) — die
Defaults verschwinden also nie.

**`MANIFEST_RELAYS`** — ausschließlich für Katalog-Manifeste:

```
wss://relay.primal.net
wss://relay.damus.io
wss://nostr-pub.wellorder.net
wss://nos.lol
wss://nostr.oxtr.dev
```

Diese Liste ist nicht beliebig erweiterbar. Das Kilter-Manifest ist ~108 KB und
damit über dem 64-KB-Limit der meisten Relays — von neun am 2026-08-05 mit dem
echten Event getesteten Kandidaten nahm es genau einer an. **Jeder neue Eintrag
muss mit einem echten Publish verifiziert werden**, nicht nach Popularität gewählt.

Die Pipeline-Seite (`blossom_upload.py:DEFAULT_RELAYS`) **muss eine Obermenge
davon sein**. Auf ein Relay zu publizieren, das keine ausgelieferte App liest,
bringt keine Redundanz — genau das war 2026-08 mit `nos.lol` der Fall.

> **Einbahnstraße:** Beide Listen sind einkompiliert. Eine Erweiterung wirkt nur
> für Builds, die sie enthalten. Installationen auf 0.2.1 und älter fragen weiter
> die alten drei Relays. Dieselbe Einschränkung gilt für die Update-Source-Liste.

---

## 4. Feature: Board-DB-Manifest

Der Katalog-Kanal: wie ~96 MB Kletterdaten über Nostr verteilt werden, obwohl
Relays bei 64 KB dichtmachen.

| | |
|---|---|
| **Signierer** | Manifest-Key |
| **Kind** | 30078 |
| **Fach (d-Tag)** | `cruxcoach/board-db` (Kilter, historischer Name), `cruxcoach/moonboard-db`, `cruxcoach/{tension,grasshopper,decoy,soill,touchstone}-db` |
| **Nutzdaten** | auf Blossom-Servern, öffentlich, content-addressed |
| **Gefunden über** | `#d` + `authors` |
| **Neuestes** | `max(created_at)` über alle Manifest-Relays |

### Ablauf Publish (Pipeline)

1. **Chunking** — SQLite wird in Monatsscheiben zerlegt (`prepare_chunks`,
   `blossom_upload.py:507`): `meta`, `climbs-YYYY-MM`, `stats-YYYY-MM`, `locations`.
   Aktuell 202 Chunks bei Kilter. Ein Climb aus Mai 2018 ändert sich nie wieder →
   sein Chunk behält für immer denselben Hash.
   **Nur Kilter ist gechunkt.** MoonBoard und die Aurora-Boards liegen als einzelner
   Monolith (20–35 MB) — siehe `BLOSSOM_STORAGE.md` für die Kosten davon.
2. **zstd-Komprimierung, dann SHA-256** — der Hash ist der des komprimierten Blobs.
3. **Upload zu Blossom** mit Kind-24242-Auth (§Blossom-Auth unten). Vorher HEAD:
   liegt der Blob schon da, wird übersprungen.
4. **Manifest-Event** publishen.

### Das Manifest-Event

```jsonc
{
  "kind": 30078,
  "pubkey": "70b2740b…",
  "created_at": 1786076044,
  "tags": [
    ["d",   "cruxcoach/board-db"],
    ["t",   "cruxcoach"],
    ["alt", "CruxCoach Kilter Board database manifest"],
    ["x", "0a09d0f0…", "meta"],              // ein x-Tag pro Chunk (202 Stück)
    ["x", "f917de0c…", "climbs-2018-05"],
    …
  ],
  "content": "{\"v\":2,\"board\":\"kilter\",…}"
}
```

Content ausgepackt:

```jsonc
{
  "v": 2,
  "board": "kilter",
  "product_id": 1,
  "created_at": 1786076044,
  "compression": "zstd",
  "chunks": [
    { "name": "meta", "type": "meta",
      "sha256": "0a09d0f0…", "size": 64538,
      "urls": [ "https://cdn.hzrd149.com/0a09d0f0…",
                "https://blossom.primal.net/0a09d0f0…",
                "https://blossom.cruxcoach.org/0a09d0f0…" ] },
    …
  ]
}
```

Hashes stehen doppelt drin: die `x`-Tags sind relay-durchsuchbar („welches Manifest
referenziert Blob X"), der Content ist die Arbeitsliste für die App. Die 202 `x`-Tags
sind auch der Grund für die 108 KB Eventgröße.

### Warum content-addressed

```
https://cdn.hzrd149.com/0a09d0f0d9901837450bd15245e3ec43d9b7d5d7dbdd4baef0be5bb8976e8433
                        └────────────── SHA-256 der Datei selbst ──────────────────────┘
```

Die URL *ist* der Integritätsbeweis. Wer lädt und nachrechnet, weiß sofort, ob er
das Richtige bekommen hat — unabhängig davon, welchem Mirror er vertraut. Deshalb
können dieselben Bytes bei drei Anbietern liegen, ohne dass einer davon
vertrauenswürdig sein muss.

### Ablauf Fetch (App, `BlossomSyncManager.kt`)

```
1. REQ {"kinds":[30078],"authors":["70b2740b…"],"#d":["<board>"],"limit":1}
   an alle 5 MANIFEST_RELAYS parallel, 3 Durchläufe mit Backoff+Jitter

2. max(created_at) gewinnt   ← NICHT first-success
3. pro Antwort prüfen: pubkey, verifySignature(), verifyId(), d-Tag
4. content parsen → pro Chunk: lokal gemerkter Hash == Manifest-Hash?
                               ja  → überspringen
                               nein → laden
5. laden: erste https-URL, SHA-256 nachrechnen, sonst nächster Mirror
   (2 Durchläufe über alle Mirrors, Größendeckel, zstd-Bomb-Limit 512 MB)
6. entpacken → importieren → Hash lokal merken (SharedPreferences, pro Board)
```

**Warum `max(created_at)` und nicht first-success:** Ein Relay, das beim letzten
Publish offline war, hält weiter das Manifest von gestern. First-success würde die
App deterministisch auf das langsamste Relay festnageln und jeden frischen Publish
entwerten.

**Warum 3 Durchläufe:** Eine Neuinstallation synct sieben Kataloge nacheinander, jeder
mit eigener Manifest-Abfrage. Ein einzelnes schlechtes Zeitfenster (503 von damus,
Connect-Timeout von wellorder) ließ vorher genau einen Katalog fehlschlagen, während
die Geschwister durchliefen — der Bug „manche Boards laden beim ersten Mal nicht".

**Warum der d-Tag clientseitig nachgeprüft wird:** Kilter und MoonBoard teilen den
Signing-Key, nur der d-Tag trennt sie. Ein schlampiges Relay könnte auf die
Kilter-Anfrage das MoonBoard-Manifest zurückgeben — gültige Signatur, gültige ID,
falscher Katalog.

### Blossom-Auth (Kind 24242, BUD-02)

Blossom benutzt statt API-Keys ein signiertes Nostr-Event als HTTP-Header:

```jsonc
{
  "kind": 24242,
  "tags": [
    ["t", "upload"],              // oder "delete", "list"
    ["x", "0a09d0f0…"],           // exakt diese eine Datei
    ["size", "64538"],
    ["expiration", "1786076344"]  // 5 Minuten
  ],
  "content": "Upload board DB chunk"
}
```

→ base64 → `Authorization: Nostr <base64>`. Kind 24242 ist ephemeral, das Event
geht nie an ein Relay.

Manche Server (`nostr.download`) verlangen zusätzlich die BUD-06-Header
`X-SHA-256`, `X-Content-Length`, `X-Content-Type`. Server, die sie nicht lesen,
ignorieren sie — mitsenden ist rein additiv.

### Health-Check

`check_manifest_replication.py` fragt alle 7 d-Tags über **eine** Verbindung pro
Relay ab (nicht eine pro Manifest — das provozierte Rate-Limits, die wie
Verfügbarkeitsprobleme aussahen).

- `MIN_REPLICAS = 2` — was auf einem Relay liegt, ist einen Ausfall von unsichtbar entfernt
- `STALE_AFTER_SECONDS = 36h` — eine alte Kopie ist kein Ersatzteil, sondern eine falsche Antwort, die darauf wartet, gezogen zu werden
- `manifest_archive/` hält die eigene zuletzt verifizierte Kopie jedes Manifests, damit Reparatur nicht davon abhängt, ein flackerndes Relay im selben Lauf zu erwischen
- `relay_limits.json` merkt sich Relays, die ein Event dauerhaft wegen Größe ablehnen — ein Report, der jeden Morgen dieselbe unbehebbare rote Zeile zeigt, wird ignoriert

---

## 5. Feature: Community-Climbs

| | |
|---|---|
| **Signierer** | User-Key |
| **Kind** | 30078 (Climb), 5 (Löschung) |
| **Fach (d-Tag)** | `cruxcoach:climb:<pubkey[0:8]>:<uuid>` |
| **Nutzdaten** | im Event selbst, öffentlich |
| **Gefunden über** | `#L` (Namespace-Label) |
| **Neuestes** | dreistufig — siehe unten |

Der d-Tag aus Autor **und** Climb-UUID hat zwei Konsequenzen, die sich von selbst
ergeben: Ein Edit ist ein neues Event mit demselben d-Tag (Relay wirft das alte
weg), und niemand kann fremde Climbs überschreiben.

### Das Climb-Event

```jsonc
{
  "kind": 30078,
  "pubkey": "354c9b2d…",                            // der Setter
  "created_at": 1786076044,
  "tags": [
    ["d", "cruxcoach:climb:354c9b2d:089ccfd9…"],

    // Namespace — bestimmt, wer das Event überhaupt sieht
    ["L", "com.cruxcoach.climb"],                   // Kilter
 // ["L", "com.cruxcoach.climb.v2"],                // alle anderen Boards
    ["l", "climb", "com.cruxcoach.climb"],

    // Anzeige-Metadaten
    ["l", "kilterboard-og", "com.cruxcoach.board"],
    ["l", "12x12", "com.cruxcoach.size"],

    // Kletterdaten
    ["frames", "p1234r15p1240r13"],                 // Griff-ID + Rolle
    ["frames_hash", "sha256:…"],                    // Dublettenerkennung
    ["layout_id", "1"],
    ["board_brand", "kilter"],                      // ← hierauf ingestieren ≥0.2.0-Apps
    ["bounds", "12,88,4,140"],                      // L,R,B,T → welche Boardgrößen passen
    ["setter_grade", "17", "40"],                   // Grade-ID + Winkel

    ["t", "kilterboard"], ["t", "climbing"]
  ],
  "content": "{\"uuid\":\"…\",\"pubkey_prefix\":\"354c9b2d\",\"name\":\"…\",\"description\":\"…\",\"_v\":1}"
}
```

Kletterdaten stehen in Tags, damit der Cron Climbs eines Boards finden kann, ohne
alles herunterzuladen und auszupacken. Im Content steht nur, was niemand durchsuchen
muss.

`setter_grade` und `angle` sind **Pflicht** — Subscriber verwerfen Events ohne sie
(keine synthetischen NULL-Difficulty-Zeilen im Katalog). `buildCommunityClimbEvent`
erzwingt beides per `require()`, weil ein Event ohne Grade von jedem Relay
angenommen wird, aber für jeden anderen CruxCoach-Nutzer unsichtbar bleibt — der
0.1.4-Bug, der monatelang niemandem auffiel.

Der Content wird **handgebaut, nicht serialisiert** (`NostrCommunityClimb.kt:190`),
damit gleicher Input byte-identischen Output liefert. Bei einem Event, das wiederholt
publiziert wird, will man keinen Diff nur weil ein Serializer die Feldreihenfolge
geändert hat.

### Der v1/v2-Namespace-Split (FEAT-031)

Der wichtigste Tag von allen ist `L`. Alle Clients abonnieren `#L` statt nur
`kinds:[30078]` — 30078 ist der generische NIP-78-„App-Daten"-Kind, den alle
Nostr-Apps benutzen; ohne Namespace-Filter bekäme man die Einstellungen fremder Apps.

| Namespace | Boards | wer sieht es |
|---|---|---|
| `com.cruxcoach.climb` | Kilter | alle Versionen |
| `com.cruxcoach.climb.v2` | MoonBoard + Tension/Grasshopper/Decoy/So iLL/Touchstone | nur ≥ 0.2.0 |

Grund: Die neuen Boards recyceln niedrige `layout_id`s, die mit Kilter kollidieren
(Grasshopper und Decoy haben beide `layout_id=1`, wie Kilter Original). Apps < 0.2.0
ordnen Climbs über `layout_id` zu — ein Tension-Climb erschiene dort als kaputter
Kilter-Climb. Der v2-Namespace matcht deren Filter nie. Neue Apps abonnieren beide
und ordnen über `board_brand` zu.

### Publish (`CommunityClimbPublisher.kt`)

```
1. created_at = monotonicCreatedAtSeconds(now, letzterEigenerStempel)
2. Event bauen + signieren
3. markClimbPublishInFlight(uuid)        ← VOR dem Relay-Roundtrip
4. pool.sendEventWithStats(event)
   accepted == 0 → markClimbPublishFailed + throw
5. markClimbPublishedNostr(uuid, eventId, dTag, pubkey, createdAtIso)
6. optional: Kind-1 Auto-Note (§6)
7. optional: Push in den Kilter-Account des Users (best effort, nur Kilter-Brand)
```

Schritt 3 vor Schritt 4 ist Crash-Sicherheit: Stirbt der Prozess dazwischen, bleibt
die Zeile im Retry-Filter und der `CommunityPublishRetryWorker` publiziert erneut.
Duplikate kann das nicht erzeugen, weil 30078 replaceable ist.

Der Kilter-Push ist **nur für Kilter-Boards** (`brand.supportsOfficialAppPublish`).
Ein Aurora-Climb darf nie fälschlich als Kilter-Climb in den Account des Users
geschrieben werden. Es gibt bewusst **keine geteilte CruxCoach-Service-Identität** —
weder zum Signieren noch für Kilter-Pushes.

### Subscribe (`CommunityClimbSubscriber.kt`)

```json
{"kinds":[30078,5],"#L":["com.cruxcoach.climb","com.cruxcoach.climb.v2"],"since":<cursor>}
```

Ein REQ für beide Kinds — der Deleter labelt seine Kind-5 mit demselben `L`-Tag.

Pipeline pro Event:

| # | Schritt | warum |
|---|---|---|
| 1 | Größendeckel vor dem Parsen | größter legitimer Climb ~6 KB; alles darüber ist Missbrauch |
| 2 | `verifySignature()` + `verifyId()` | ein Relay kann sonst einen getauschten Body unter gültiger Signatur liefern |
| 3 | Clock-Skew-Grenze | ein gefälschtes Far-Future-Event würde den Cursor über alle echten Events schieben und die Subscription still abschalten |
| 4 | Self-Filter | Relays echoen eigene Events zurück; ein `INSERT OR REPLACE` würde lokale `kilter_status`-Flags überschreiben |
| 5 | Grade/Angle-Gate, Katalog-Guard, Cross-Author-Guard | |
| 6 | Stale-Check gegen `getClimbCreatedAt(uuid)` | |
| 7 | Upsert | |
| 8 | `advanceCursorIfNewer()` | **erst nach** erfolgreichem Schreiben, per `max()` |

**Backpressure statt Drop** (`:451`): Während eines Board-Syncs (190k INSERTs) wird
der Collector *suspendiert*, nicht verworfen. Ein verworfenes Event kommt in dieser
Session nie wieder — Relays re-pushen nicht, nur ein neuer REQ mit Cursor würde es
nachholen.

**DLQ:** Schlägt der SQLite-Upsert fehl, landet das rohe signierte Event in
`community_climb_dead_letters` und wird beim nächsten Start durch denselben Pfad
geschickt (inkl. Signatur-Recheck). Ohne DLQ liefe der Cursor beim nächsten Erfolg
darüber hinweg → stiller Datenverlust für diese UUID.

**Cursor-Seed bei Neuinstallation** (`:247`): auf `blossomManifestCreatedAt` minus
Lookback — alles Ältere steckt schon im Blossom-Bundle. Das drückt den Cold-Start
von „gesamte Historie" (MB–GB) auf ~24 h (~50 KB). Wartet bis zu einem Timeout auf
das Manifest, weil ein `since:null`-REQ in diesem Race die komplette Historie streamt.
Der Lookback ist nötig, weil der Manifest-Zeitstempel nur von der **Kilter**-Sync
geschrieben wird, die anderen Brands aber Tage hinterherhängen können.

### Löschen (`CommunityClimbDeleter.kt`)

Nostr hat keinen Delete-Befehl, nur Bitten — und Relays hören auf Unterschiedliches.
Deshalb beides:

```jsonc
// (1) Grabstein — nutzt die Replaceable-Regel
{
  "kind": 30078,
  "tags": [
    ["d", "cruxcoach:climb:354c9b2d:089ccfd9…"],   // identischer d-Tag
    ["L", "com.cruxcoach.climb"],
    ["l", "climb", "com.cruxcoach.climb"],
    ["deleted", "true"]
  ],
  "content": "{\"deleted\":true,\"uuid\":\"089ccfd9…\"}"
}

// (2) NIP-09 Löschanfrage
{
  "kind": 5,
  "tags": [
    ["a", "30078:354c9b2d…:cruxcoach:climb:354c9b2d:089ccfd9…"],
    ["e", "<letzte Event-ID>"],
    ["k", "30078"],
    ["L", "com.cruxcoach.climb"],                  // damit derselbe #L-Filter greift
    ["l", "climb", "com.cruxcoach.climb"]
  ],
  "content": ""
}
```

(1) wirkt immer — wer das Fach abfragt, bekommt den Grabstein. (2) wirkt nur bei
NIP-09-fähigen Relays, entfernt das Original dafür ganz.

Beide mit monoton geklemmtem `created_at`. Die Löschung reitet auf demselben
`L`-Namespace wie der Climb, den sie löscht — sonst fände ein ≥0.2.0-Subscriber das
Original auf v2, aber die Löschung auf v1.

Lokal owner-locked im SQL. Sonderfall: Ein *geclaimter* Kilter-Climb
(`kilter_author_uuid` gesetzt) wird nicht getombstoned, sondern auf einen erneut
claimbaren Import zurückgesetzt.

**Kilter wird bewusst nicht angefasst** — die REST-API hat keinen Delete-Endpoint,
die Kilter-App löscht nur Drafts und nur über PowerSync (Binärprotokoll, das wir
nicht sprechen). Die UI warnt stattdessen, wenn `kilterStatus == "synced"`.

### Cron-Seite

`update_board_db.py:subscribe_cruxcoach_climb_events` — derselbe Filter, aber `since`
aus `.cruxcoach-climb-sync-state.json` **minus 30 Tage Overlap**
(`CURSOR_REQUERY_OVERLAP_SECONDS`). Community-Events landen faktisch nur auf einem
Relay zuverlässig; ein Ausfall würde sie sonst permanent verlieren.

- `by_d_tag`: newest-wins per d-Tag
- `tombstone_intents`: latest-wins per `(pubkey, d_tag)`, gespeist aus Kind-5 **und** deleted-30078
- Ein Grabstein dropt den Climb nur, wenn er `>=` dem Climb ist
- Rate-Limit 100 Climbs/Pubkey/Lauf (behält die ältesten — Spam-Bursts klumpen zeitlich)
- Die persistierten Sets `cruxcoach_climbs` und `cruxcoach_tombstoned_uuids` werden bei **jedem** Build erneut angewandt, sonst schriebe die Kilter-Harvest die Zeile wieder als `origin='kilter'`, sobald der Cursor am Original-Event vorbei ist

`moonboard_community_merge.py` macht dasselbe für die v2-Boards, aber mit `since=0`
bei jedem Lauf (das Katalog-Blob wird neu gebaut, alle Community-Climbs müssen jedes
Mal frisch rein). Entfernungen ausschließlich über explizite Grabsteine — ein
fehlgeschlagener Fetch (`fetch_ok=False`) darf nie als „keine Climbs" durchgehen.

> **Die Konvergenz-Achse:** Online-Nutzer bekommen den Climb über den Live-Sub,
> Offline-Nutzer über das nächste Blossom-Bundle. Der Display-Stub
> `npub:<pubkey[0:16]>` ist in beiden Pfaden identisch formatiert, damit ein
> Bundle-Refresh keinen Diff erzeugt.

---

## 6. Feature: Auto-Note

Optionaler öffentlicher Kind-1-Post nach erfolgreichem Climb-Publish.

```jsonc
{
  "kind": 1,
  "tags": [
    ["p", "e75a185c…"],          // nur wenn BuildConfig.AUTO_NOTE_PTAG_MAINTAINER
    ["t", "kilterboard"],
    ["t", "climbing"]
  ],
  "content": "… naddr1… …"       // NIP-19-Referenz auf den Climb
}
```

Der Climb wird als **`naddr`** referenziert, nicht als Event-ID — `naddr` kodiert
`(kind, pubkey, d-Tag)` und überlebt damit jeden Edit.

Best effort: 0 akzeptierende Relays sind kein Publish-Fehler (das Kind-30078 ist
längst durch), aber der Editor bekommt `autoNotePublished=false` und zeigt einen
eigenen Hinweis statt des stillen „✓ veröffentlicht".

---

## 7. Feature: Backup-Sync (FEAT-002)

Struktur wie das Manifest — große Daten auf Blossom, Zeiger auf Nostr. Zwei
Unterschiede: alles ist verschlüsselt, und der d-Tag ist absichtlich unlesbar.

| | |
|---|---|
| **Signierer** | User-Key |
| **Kind** | 30078 (Pointer + Key), 5 (Opt-out), 24242 (Blossom-Auth), 10063 (Server-Liste, nur lesend) |
| **Fach (d-Tag)** | HMAC-abgeleitet, opak |
| **Nutzdaten** | verschlüsselter Blob auf Blossom; Pointer NIP-44-an-sich-selbst |
| **Neuestes** | `max(created_at)` + lokales Anti-Rollback-Gate |

### Opake d-Tags (`DTagDeriver.kt`)

`cruxcoach:backup:<npub>` wäre naheliegend — dann könnte aber jeder Relay-Betreiber
per `#d`-Query alle CruxCoach-Nutzer auflisten.

**LOCAL-Signer:**
```
d-Tag = HMAC-SHA256( HKDF(nsec, salt="cruxcoach-dtag-v1", info="hmac-key"), identifier )
identifier ∈ { "cruxcoach/backup/v1", "cruxcoach/key/v1" }
```
Deterministisch, geräteübergreifend reproduzierbar → Restore kann gezielt filtern.

**AMBER:** exponiert kein nsec. Stattdessen `SHA-256(sig)` über ein Template-Event
mit Kind **27777** (ephemeral, wird nie publiziert) und `["purpose","cruxcoach-dtag-v1"]`
— damit Ambers Bestätigungsdialog nicht „Profil-Metadaten signieren" sagt (die
Vorgängerversion missbrauchte Kind 0 dafür). Determinismus über Neuinstallationen ist
nicht garantiert (`aux_rand` variiert je Implementierung), deshalb hat Amber einen
eigenen Restore-Pfad: alle Kind-30078 des Pubkeys holen und per Probe-Entschlüsselung
zuordnen.

Alle Ableitungen werden gecacht (`BackupPreferences`), höchstens eine pro Identifier
und Installation. Der Cache-Hit-Shortcut macht den Amber-Kind-Wechsel forward-only —
Installationen mit gecachtem Kind-0-d-Tag behalten ihn, keine verwaisten Events.

### Die zwei Fächer

```jsonc
// "cruxcoach/backup/v1" — wo liegt der Blob
{ "kind": 30078, "tags": [["d","a3f9c2…"]], "content": "<NIP-44>" }
// entschlüsselt:
// { "version":1, "schema_version":2, "sha256":"…", "size":8412160,
//   "servers":["https://…"], "previous_sha256":"…", "updated_at":1786076044,
//   "device_id":"…", "categories":["WORKOUTS","CLIMBS",…] }

// "cruxcoach/key/v1" — der gewrappte Datenschlüssel
{ "kind": 30078, "tags": [["d","7b1e04…"]], "content": "<NIP-44>" }
```

### Die kritische Reihenfolge

```
1. exportieren → gzip → mit dataKey verschlüsseln → SHA-256
2. Blossom-Server ermitteln (User-Kind-10063 + DEFAULT_SERVERS)
3. hochladen (Kind-24242-Auth)
4. HEAD-Verify: liegt der Blob wirklich da?
5. ── ERST JETZT ── Pointer publishen
6. vorherigen Blob löschen (BUD-04 DELETE, best effort)
7. Key-Event mit-republishen
8. lastBackupSync merken
```

Nimmt **kein** Relay den Pointer an → `BackupException`. Sonst würde Schritt 6 den
alten Blob löschen, während kein Relay den neuen Pointer kennt: Backup weg.

Schritt 7 ist nicht redundant. Relays räumen replaceable Events nach Alter weg. Wird
nur der Pointer frisch gehalten, entsteht ein Fenster, in dem Pointer und Blob leben,
aber der Schlüssel evictet ist — ein auffindbares, aber unentschlüsselbares Backup.
Deshalb unbedingt auf **derselben Kadenz** wie der Pointer, nicht stale-gated.

### Aktualität, zwei Ebenen

1. `BackupEventSelection.newestByDTag` — filtert auf `(pubkey, kind)`, nimmt das
   höchste `created_at`, dann den passenden d-Tag.
2. **Anti-Rollback-Gate:** Ist der gefundene Pointer älter als der lokal gemerkte
   `lastBackupSync` (Toleranz `STALE_POINTER_TOLERANCE_SEC = 5 min`), wird er
   abgelehnt. Das fängt den Fall, dass *alle* Relays gemeinsam neuere Events
   zurückhalten — erkannt aus lokalem Zustand, nicht aus dem, was die Relays zeigen.

Dazu `pointer.validateOrThrow()` gegen absurde Werte: 64 MB Blob-Deckel, max. 16
Server, 60 s Clock-Skew, Längenlimits auf `device_id` und `categories`.

### Opt-out

Kind-5 auf beide d-Tags **plus** je ein Kind-30078 mit dem Klartext-Sentinel
`CRUXCOACH_BACKUP_TOMBSTONE_V1`. Der Restore-Pfad versucht dort NIP-44 zu
entschlüsseln, das scheitert garantiert — und die App meldet sauber „kein Backup
vorhanden" statt „Entschlüsselung fehlgeschlagen". Der Sentinel-Check läuft **vor**
dem Decrypt, damit auf dem Amber-Pfad kein überflüssiger Bestätigungsdialog aufgeht.

### Recovery-Kette für den dataKey

`getOrCreateDataKey()` ist dreistufig, damit ein halb persistierter lokaler Zustand
nicht jedes künftige Backup blockiert:

1. aus `BackupPreferences` entpacken (Happy Path)
2. schlägt fehl → lokalen Blob verwerfen, Key-Event von den Relays neu holen
3. auch das fehlt/undechiffrierbar → frischen dataKey erzeugen, neues Key-Event
   publishen, Pointer-Stash leeren (alter Blob verwaist, aber Backups laufen wieder)

---

## 8. Feature: Profil

| | |
|---|---|
| **Kind** | 0 |
| **Fach** | keins — `(pubkey, kind)` reicht |
| **Content** | `{"name":…,"lud16":…,"picture":…,"about":…,"banner":…,"nip05":…,"website":…}` |
| **Neuestes** | TTL-Cache 30 min + `cacheProfileIfNewer(event.createdAt)` |

Publish ist **fail-closed**: Bei 0 akzeptierenden Relays wird der lokale Cache
*nicht* geschrieben und `null` zurückgegeben — sonst divergiert die App still von
dem, was andere Clients sehen.

Es gibt bewusst **keine Live-Subscription auf Kind 0**. Stattdessen TTL plus
Zeitstempelvergleich beim Schreiben, damit ein altes Kind-0 von einem hinterherhinkenden
Relay keine veraltete `lud16` über einen frischen Publish pinnen kann. Profil-Screens
haben zusätzlich einen `refreshProfile()`-Hook, der den TTL umgeht.

Der Cron zieht Kind 0 ebenfalls (`subscribe_kind0_for_pubkeys` — eine Subscription pro
Relay mit `authors:[…]`) und cached in `.cruxcoach-profile-cache.json` mit
`kind0_created_at`, um Setter-Namen ins Bundle zu bekommen.

Verifikationen: NIP-05 (`Nip05Verifier`) und LNURL (`LnurlVerifier`) laufen
clientseitig gegen die jeweiligen HTTPS-Endpunkte.

---

## 9. Feature: Relay-Discovery (NIP-65)

| | |
|---|---|
| **Kind** | 10002 (lesend), 10063 für Blossom-Server |
| **Neuestes** | Cache mit 24 h TTL, stale-while-revalidate |

```jsonc
{ "kind": 10002,
  "tags": [
    ["r", "wss://relay.damus.io"],            // lesen + schreiben
    ["r", "wss://nos.lol", "write"],
    ["r", "wss://relay.primal.net", "read"]
  ],
  "content": "" }                             // alles in Tags
```

`RelayListResolver` ist cache-first, liefert bei Stale sofort den alten Wert und
refresht im Hintergrund, blockiert nie und wirft nie (Fallback: Defaults).

**Der Merge ist additiv** (`mergeAdditive`): User-Einträge zuerst in
Originalreihenfolge, dann Defaults, die noch nicht drin sind. Bei Kollision gewinnen
die User-Marker (`read`/`write`-Flags). Konsequenz, die man kennen muss: **Eine
restriktive User-Relay-Liste verkleinert das Set nicht.**

Kill-Switch über `isNip65DiscoveryEnabled()`. Bei Identitätswechsel invalidiert der
`KeyChangeListener` den Cache. Der Pool wird nur benachrichtigt, wenn sich die
`(url, read, write)`-Signatur wirklich ändert.

---

## 10. Feature: DMs und Announcements

Das einzige Feature, bei dem Sichtbarkeit *nicht* das Ziel ist. NIP-17 über NIP-59.

```
Rumor  (Kind 14)   die Nachricht — UNSIGNIERT
                   (unsigniert ist Absicht: nirgends als Beweis vorlegbar)
  ↓ NIP-44 an den Empfänger
Seal   (Kind 13)   vom Absender signiert — Autorschaft erst nach dem Öffnen sichtbar
  ↓ NIP-44 mit Einmalschlüssel
Gift Wrap (1059)   von einem Wegwerfschlüssel signiert, ["p", empfänger]
```

Von außen sieht ein Relay nur: irgendein Zufalls-Pubkey hat etwas an X geschickt.

**Zwei Wraps pro Nachricht** — einer an den Empfänger, einer an sich selbst
(Multi-Device). Sie haben **verschiedene Event-IDs**. Daraus folgt der
`self_root`-Tag (`NostrConfig.RUMOR_TAG_SELF_ROOT`): Der `["e",…,"reply"]`-Tag muss
die *Empfänger*-Wrap-ID tragen, sonst kann die Gegenseite nicht threaden — wodurch
die lokale Root-ID bei einem Wipe-and-Refetch unrettbar wäre. Der Zusatztag bewahrt
sie, bewusst **nicht** als zweiter `e`-Tag, weil die Gegenseite auf `e` threadet und
die Self-Wrap-ID dort einen Orphan-Thread erzeugen würde.

**Rumor-Tags:** `["L","com.cruxcoach.type"]` + `["l",<type>,…]` mit
`type ∈ {crash-report, bug-report, feature-request, chat}`, optional `["subject",…]`.

**Empfang** (`NostrRelaySubscription.kt`):

```json
{"kinds":[1059],"#p":["<eigener pubkey>"],"since": <cursor − 2 Tage>}
```

Die 2 Tage sind Pflicht, keine Kulanz: NIP-59 verschiebt `created_at` zufällig um bis
zu ±2 Tage gegen Timing-Korrelation. Ohne Rückgriff fällt eine frisch verschickte
Antwort mit `created_at = jetzt − 1,5 Tage` hinter den Cursor und kommt nie an.

Der Cursor trackt den **äußeren** Wrap-Zeitstempel, nicht die Rumor-Zeit — nur
ersterer ist das Feld, auf das `since` wirkt.

**Aktualität: keine.** DMs sind Regular Events, jede Nachricht steht für sich.
Deduplizierung über `INSERT OR IGNORE` auf der Wrap-ID, nötig weil zwei Pfade
parallel liefern:

- `NostrPushCoordinator` — prozesslebenslange Live-Subscription, an `ProcessLifecycleOwner` gehängt, wird beim Backgrounding **nicht** abgebaut (genau das ermöglicht Sub-3s-Zustellung ohne Foreground-Service)
- `NotificationPollWorker` — alle 15 min, Backstop für den Fall, dass das OS den Prozess killt

**Absender-Allowlist:** Nur Self-Wraps oder DMs vom `DEV_PUBKEY` werden ingested.
Alles andere wird verworfen, bevor es DB oder Notification berührt.

**Announcements** sind DMs vom Dev-Key mit NIP-32-Labels:
`["L","com.cruxcoach.announce"]` + `["l",<release|issue|tip|general>,…]`. Die Kategorie
steuert die Notification-Priorität. Der Content ist zweisprachig und wird an
Flaggen-Markern (🇬🇧/🇩🇪) geteilt.

---

## 11. Feature: Zaps

Kind **9734** (Zap-Request) mit `["p",<empfänger>]`, `["amount",<msat>]`,
`["relays",…]`, angehängt an den LNURL-Callback. Empfänger-`lud16` kommt aus dem
Kind-0-Profil.

Kind-9735-Receipts werden **nicht** verfolgt — die App zeigt keinen Zap-Verlauf.

---

## 12. Feature: Updater

Kind **30063** (Release) und **3063** (Asset), publiziert von Zapstore.

```json
{"kinds":[30063,3063],"authors":["<publisher>"],"#i":["<packageId>"],"limit":100}
```

Jedes Event wird gegen `pubkey`, `kind`, `verifyId()`, `verifySignature()` geprüft.

**Aktualität, zweigeteilt:** Release-Notes werden per `assetId` gruppiert und je
Gruppe über `maxByOrNull { createdAt }` aufgelöst. Die *Versionsauswahl* läuft über
`SemVer` auf dem `version`-Tag der Asset-Events — nicht über `created_at`.

**Nur EOSE gilt als vollständiges Ergebnis.** Ein Timeout liefert `null`, weil ein
Teilresultat nie beweisen kann, dass es nichts Neueres gibt.

APK-Integrität: `x`-Tag (SHA-256) plus gepinnter Zertifikats-Hash. Nostr ist eine von
vier Quellen im `UpdateSourceRegistry` (`FORGE`, `NOSTR`, `MANIFEST`, `BLOSSOM`) —
Nostr liefert die Metadaten, geladen wird über die content-addressed CDN-URL
`<cdn>/<sha256>`.

---

## 13. Feature: Share-Links

`ClimbShareLink` kodiert einen Climb als NIP-19-`naddr` über
`(30078, pubkey, dTag)` und hängt ihn an `https://<APP_LINK_HOST>/c/<naddr>`.
Aufgelöst in `MainActivity` mit Kind-Check (`nAddress.kind != 30078 → null`).

---

## 13a. Feature: Wettkämpfe (FEAT-058)

Der einzige Kanal mit einer **fortlaufenden, verketteten Historie** statt nur
„neuestes gewinnt". Die vollständige Wire-Spezifikation steht in
`docs/specs/0.2.3/FEAT-058-competition-protocol.md`; hier nur, wie er sich in
das übrige Bild einfügt.

| | |
|---|---|
| **Signierer** | Organizer-Key (Definition), Authority-Key (alles andere), User-Key (Intents) |
| **Kind** | 30078 |
| **Fach (d-Tag)** | `cruxcoach:comp:<compId>` und die abgeleiteten Fächer oben |
| **Gefunden über** | `#d` (ein Wettkampf), `#a` (sein Log), `#t` (öffentliche Suche) |
| **Neuestes** | **nicht** `max(created_at)` — die `seq`/`prev`-Kette bestimmt die Reihenfolge |

Der wichtige Unterschied zu allem anderen hier: Zeit ist nur Anzeige. Ein
Wettkampf wird über `seq` reduziert, und ein fehlender Eintrag stoppt die
Reduktion, statt übersprungen zu werden — ein selbstbewusst falscher Zwischenstand
ist schlimmer als ein sichtbarer Stillstand.

Die Protokoll- und Reduktionslogik liegt in `:shared`
(`domain/competition/`), nicht in `androidApp`, damit sie auf der JVM gegen
dieselben Fixtures getestet werden kann, die cruxcoach.org abspielt. Beide Seiten
müssen denselben `state_hash` erreichen.

**Warum NIP-19 doppelt vorkommt:** Quartz liefert Class-Files für eine neuere JVM
als die Unit-Tests laufen, deshalb hat `:shared` eine eigene bech32/NIP-19-
Implementierung (`Nip19.kt`). Der Rest der App benutzt weiterhin Quartz.

---

## 14. Querschnitt: Wie der aktuellste Stand bestimmt wird

| Feature | Mechanismus |
|---|---|
| **Manifest** | `max(created_at)` über 5 Relays parallel, dann Chunk-Hash-Diff |
| **Community-Climb** | Relay-Replaceable **+** monotone Sende-Stempel **+** lokaler `created_at`-Vergleich |
| **Climb-Löschung** | monotoner Stempel, damit der Grabstein den letzten Publish sicher überholt |
| **Backup** | `newestByDTag` **+** Anti-Rollback gegen `lastBackupSync` |
| **Profil** | 30-min-TTL **+** `cacheProfileIfNewer` |
| **Relay-Liste** | 24-h-TTL, stale-while-revalidate |
| **Updater** | SemVer auf dem `version`-Tag; `created_at` nur zur Notes-Auflösung |
| **DMs** | nicht anwendbar — Dedup über Wrap-ID |
| **Wettkampf** | `seq`/`prev`-Kette; `created_at` ist reine Anzeige |

**Die monotone Klemme** (`CommunityEventTime.kt`) ist der subtilste Teil:

```kotlin
created_at = max(jetzt, letzterEigenerStempel + 1)
```

Reine Wanduhr bricht in zwei Fällen: ein Tippfehler-Fix in derselben Sekunde, und
eine NTP-Rückwärtskorrektur. Bei Gleichstand übernimmt der Live-Sub das Event, der
Cron überspringt es — die zwei Sichten liefen dauerhaft auseinander (FEAT-039 BUG-1).
Weil zwei unabhängige Systeme dieselben Zeilen schreiben, muss „höchstes created_at
gewinnt" auf beiden Pfaden **identisch** auflösen.

---

## 15. Querschnitt: Vertrauensmodell

**Relays sind grundsätzlich nicht vertrauenswürdig.** Jeder konsumierende Pfad prüft:

```kotlin
event.verifySignature()   // authentifiziert die Wire-ID
event.verifyId()          // bindet die ID an den Body
```

Beides ist nötig. `verifySignature()` allein reicht nicht: Ein Relay kann ein
gültig signiertes Event ausliefern, dessen Tags oder Content es nachträglich
manipuliert hat — nur die Neuberechnung der ID aus dem serialisierten Inhalt fängt das.
`NostrEventPolicy` bündelt die Regel als `hasValidBodyBinding(sig, id)`.

Zusätzlich prüft jeder Pfad die Filterkriterien **clientseitig nach**, die das Relay
eigentlich schon serverseitig hätte anwenden sollen:

| Pfad | Nachprüfung |
|---|---|
| Manifest | `pubkey`, `d`-Tag |
| Community-Climb | `kind`, Clock-Skew, Größe, Autor gegen d-Tag |
| Backup | `pubkey`, `kind`, `d`-Tag, Wertebereiche des Pointers |
| Zapstore | `pubkey`, `kind`, Zertifikats-Pin |
| DM | Seal-Autor == Rumor-Autor (`hasBoundDmSender`), Absender-Allowlist |

Weitere Deckel gegen feindliche Antworten:

- **https-only** für Chunk-URLs — ein feindliches Manifest darf den Transport nicht auf MITM-bares `http://` downgraden
- **Chunk-Namen-Allowlist** `^[A-Za-z0-9_-]{1,64}$` gegen Path-Traversal
- **Größendeckel beim Streamen** (`declaredSize + 64 KB`) — SHA-Verify lehnt nur die fertige Datei ab, verhindert aber nicht, dass ein feindlicher CDN vorher `cacheDir` vollschreibt
- **zstd-Bomb-Limit** 512 MB
- **Clock-Skew** ±1 h (`NostrEventPolicy.MAX_FUTURE_SKEW_SECONDS`)

---

## 16. Register

### Kinds

| Kind | Verwendung | Richtung |
|---|---|---|
| 0 | Profil | publish + read |
| 1 | Auto-Note | publish |
| 5 | Löschung (Climbs, Backup-Opt-out) | publish + read |
| 1059 / 13 / 14 | NIP-17-DM (Wrap / Seal / Rumor) | publish + read |
| 3063 | Zapstore-Asset | read |
| 9734 | Zap-Request | publish |
| 10002 | NIP-65-Relay-Liste | read |
| 10063 | Blossom-Server-Liste | read |
| 24242 | Blossom-Auth (BUD-02) | HTTP-Header, nie Relay |
| 27777 | Amber-Hilfssignatur für d-Tag-Ableitung | nie publiziert |
| 30063 | Zapstore-Release | read |
| 24133 | NIP-46 Remote-Signing (nur Website) | publish + read |
| 30078 | Manifest, Community-Climb, Backup-Pointer, Backup-Key, Wettkampf | publish + read |

### NIP-32-Namespaces

| Namespace | Zweck |
|---|---|
| `com.cruxcoach.climb` | Community-Climbs auf Kilter (Legacy, alle App-Versionen) |
| `com.cruxcoach.climb.v2` | Community-Climbs auf allen anderen Boards (≥ 0.2.0) |
| `com.cruxcoach.board` | Board-Label am Climb |
| `com.cruxcoach.size` | Boardgrößen-Label am Climb |
| `com.cruxcoach.ascent` | reserviert |
| `com.cruxcoach.competition` | Wettkämpfe (FEAT-058) — Definition, Log, Snapshot, Ergebnis, Intent |
| `com.cruxcoach.type` | DM-Typ (crash-report / bug-report / feature-request / chat) |
| `com.cruxcoach.announce` | Announcement-Kategorie (release / issue / tip / general) |

### d-Tags

| d-Tag | Kind | Signierer |
|---|---|---|
| `cruxcoach/board-db` | 30078 | Manifest-Key |
| `cruxcoach/moonboard-db` | 30078 | Manifest-Key |
| `cruxcoach/{tension,grasshopper,decoy,soill,touchstone}-db` | 30078 | Manifest-Key |
| `cruxcoach:climb:<pubkey[0:8]>:<uuid>` | 30078 | User-Key |
| `HMAC(nsec, "cruxcoach/backup/v1")` | 30078 | User-Key |
| `HMAC(nsec, "cruxcoach/key/v1")` | 30078 | User-Key |
| `cruxcoach:comp:<compId>` | 30078 | Organizer-Key |
| `cruxcoach:comp:<compId>:log:<seq:06d>` | 30078 | Authority-Key |
| `cruxcoach:comp:<compId>:snap:<seq:06d>` | 30078 | Authority-Key |
| `cruxcoach:comp:<compId>:results` | 30078 | Authority-Key |
| `cruxcoach:comp:<compId>:intent:<pubkey[0:8]>:<nonce>` | 30078 | User-Key |

---

## 17. Wo was im Code liegt

### App (`androidApp/src/main/java/com/cruxcoach/android/`)

| Pfad | Inhalt |
|---|---|
| `nostr/NostrConfig.kt` | Relay-Listen, Pubkeys, Timeouts |
| `nostr/NostrRelayPool.kt` | Verbindungen, `sendEventWithStats`, `subscribe`, Dedup, Reconnect-Leiter |
| `nostr/NostrSigner.kt`, `NostrKeyStore.kt`, `AmberIntegration.kt` | Signer-Abstraktion, lokaler Schlüssel, Amber-IPC |
| `nostr/NostrEventPolicy.kt` | Vertrauensgrenze: Signatur/ID/Skew/DM-Bindung |
| `nostr/NostrEventBuilder.kt`, `NostrMessageSender.kt`, `ThreadIdResolver.kt` | NIP-17-Aufbau + Threading |
| `nostr/NostrRelaySubscription.kt`, `NostrEventDecryptor.kt` | DM-Empfang |
| `nostr/OfflineQueueManager.kt` | vorsignierte DMs, Drain in Batches |
| `nostr/backup/` | kompletter Backup-Pfad (Repository, Pointer, DTagDeriver, BlossomUploader, Crypto, Worker) |
| `nostr/relaydiscovery/` | NIP-65 (Fetcher, Resolver, Cache, Parser) |
| `nostr/profile/` | NIP-05, LNURL, Bild-Upload |
| `community/` | Climb-Publisher, -Subscriber, -Deleter, Retry-Worker, `CommunityEventTime` |
| `competition/` | Wettkampf: Relay-Client, Discovery, Intent-Publisher, Share-Links |
| `data/blossom/BlossomSyncManager.kt` | Manifest-Fetch + Chunk-Download |
| `notification/` | Push-Coordinator, Poll-Worker, Ingestor, Announcement-Parser |
| `payment/` | Kind-0-Profilverwaltung, Zaps |
| `updater/` | Zapstore-Client, Source-Registry |

### Shared (`shared/src/commonMain/kotlin/com/cruxcoach/domain/community/`)

| Datei | Inhalt |
|---|---|
| `NostrCommunityClimb.kt` | Event-Aufbau, d-Tag, Namespaces, Content-JSON |
| `FramesHash.kt`, `ClimbBounds.kt`, `ClimbValidation.kt` | Hilfsberechnungen am Climb |
| `AutoNoteTemplate.kt` | Template-Rendering für den Kind-1-Post |

### Shared (`shared/src/commonMain/kotlin/com/cruxcoach/domain/competition/`)

| Datei | Inhalt |
|---|---|
| `CompetitionProtocol.kt` | Kinds, d-Tags, Envelope-Gate, Parsing |
| `CompetitionReducer.kt` | Deterministische Reduktion, Fork-Erkennung |
| `CompetitionScoring.kt` | Wertung und Tiebreaks |
| `CompetitionValidation.kt` | Konfigurationsprüfung (identisch zur Website) |
| `Ccj.kt` | Kanonisches JSON + `state_hash` |
| `Nip19.kt` | bech32 / naddr (Quartz-frei, damit testbar) |

### Pipeline (`cruxcoach-blossom-sync/`)

| Datei | Inhalt |
|---|---|
| `blossom_upload.py` | Kilter: Chunking, Blossom-Upload, Manifest-Publish |
| `moonboard_blossom_upload.py`, `aurora_blossom_upload.py` | dasselbe für die Monolith-Boards |
| `update_board_db.py` | Kilter-Harvest **+** Community-Climb-Merge **+** Kind-0-Auflösung |
| `moonboard_community_merge.py` | v2-Namespace-Community-Merge, brandparametrisiert |
| `check_manifest_replication.py` | Replikations-Health, Reparatur aus `manifest_archive/` |
| `.cruxcoach-climb-sync-state.json` | Cursor, Climb-Set, Tombstone-Set |
| `.cruxcoach-profile-cache.json` | Kind-0-Cache für Setter-Namen |

---

## 18. Fallstricke, die uns schon eingeholt haben

Alles hier ist einmal live schiefgegangen — die Gegenmaßnahme steht jeweils dabei.

**Manifest**

- *First-success statt max(created_at)* → App hing dauerhaft an einem veralteten Relay
- *Ein Fetch-Durchlauf* → einzelne Kataloge scheiterten bei Neuinstallation, während ihre Geschwister durchliefen
- *Drei Relays reichen nicht* → fünf von sieben Manifesten lagen auf genau einem Relay, vier davon auf damus, während damus 503 lieferte
- *Auf ein Relay publizieren, das keine App liest* (`nos.lol`) → sieht nach Redundanz aus, ist keine
- *d-Tag nicht nachgeprüft* → MoonBoard-Manifest kann als Kilter-Antwort durchgehen

**Community-Climbs**

- *Wanduhr als `created_at`* → Same-Second-Republish ließ Live-Sub und Cron dauerhaft divergieren
- *Publish ohne `setter_grade`* → jedes Relay nahm das Event an, kein Subscriber zeigte es je an
- *Events während des Board-Syncs verwerfen statt suspendieren* → dauerhafter Verlust, Relays re-pushen nicht
- *Cursor vor dem Upsert vorrücken* → stiller Datenverlust bei SQLite-Fehler (jetzt DLQ)
- *Ungeseedeter Cursor bei Neuinstallation* → REQ ohne `since` streamt die gesamte Historie
- *Kilter-Manifest-Epoche als Seed ohne Lookback* → Events von hinterherhängenden Brands wurden unsichtbar
- *`L`-Namespace vergessen* → man bekommt die App-Daten aller anderen NIP-78-Apps

**Backup**

- *Pointer vor Blob-Verify publishen* → Zeiger auf nicht existierende Daten
- *Bei 0 akzeptierenden Relays weitermachen* → alter Blob gelöscht, neuer Pointer nirgends
- *Key-Event stale-gated republishen* → auffindbares, unentschlüsselbares Backup nach Relay-Eviction
- *Kind 0 für die Amber-Ableitung missbrauchen* → irreführender Bestätigungsdialog
- *Untargeted query-all auf 30078 (Amber)* → fremde App-Daten verdrängen die eigenen Events aus dem Limit

**DMs**

- *Cursor ohne 2-Tage-Rückgriff* → NIP-59-rückdatierte Antworten kommen nie an
- *Rumor-Zeit statt Wrap-Zeit als Cursor* → zwei Zeitdomänen, Events fallen still raus
- *Self-Wrap-ID im `e`-Tag* → Orphan-Threads auf der Gegenseite

**Profil**

- *Cache ohne TTL* → Profiländerungen erreichten andere Nutzer nie
- *Lokalen Cache vor dem Relay-Ack schreiben* → App zeigt etwas anderes als der Rest der Welt
