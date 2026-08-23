# Quantum-Layer und Playlist: Backlog in der Zeit, Rack in der Gegenwart

> **Normativer Satz:** Die Playlist ist eine geordnete Liste von Vorhaben in der Zeit.
> Das Rack ist die Menge dessen, was gerade gleichzeitig leuchtet. Beide beschreiben
> dieselbe Session, aber nicht dieselbe Sache — und keine der beiden darf zur anderen
> gemacht werden.

Dieses Dokument ergaenzt [QUANTUM_LAYER_ARCHITECTURE.md](QUANTUM_LAYER_ARCHITECTURE.md)
(Layer, Identitaet, Controller-Wahrheit) und
[BOARD_PLAYLIST_ARCHITECTURE.md](BOARD_PLAYLIST_ARCHITECTURE.md) (kanonischer Playlist-
Zustand, Occurrence-Identitaet, Single Writer). Es beschreibt ausschliesslich die
Verbindung zwischen beiden.

## 1. Warum nicht eine Playlist pro Layer

Vier Layer sind keine vier Gruppen. An einer Quantum-Wand steht eine Gruppe, die *eine*
gemeinsame Reihenfolge abarbeitet; dass vier Climbs gleichzeitig sichtbar sein koennen,
aendert daran nichts. Vier Listen wuerden erzeugen:

- vier Historien, die niemand zusammenfuehren kann,
- vier Rest-Timer und vier „Was kommt als Naechstes“,
- eine Zuordnung Person → Lane, die genau dann falsch ist, wenn jemand die Wand wechselt,
- und eine Frage, die niemand beantworten kann: *in welcher Liste steht der Warm-up?*

Deshalb bleibt es bei genau einer geteilten, geordneten Playlist. Das Rack ist eine
zweite, viel kleinere Struktur daneben: vier neutrale Lanes `L1`–`L4`, die nicht an
Personen gebunden sind.

```text
Backlog (Zeit)                        Rack (Gleichzeitigkeit)
┌──────────────────────────┐          ┌──────┬──────┬──────┬──────┐
│ 1 Warm-up          L1 ●  │          │  L1  │  L2  │  L3  │  L4  │
│ 2 Zombie Hands     L2 ●  │   ⟶      │ Warm │ Zomb │ frei │ frei │
│ 3 Zombie Hands     L2 ◐  │          │ gruen│ cyan │      │      │
│ 4 Crimp Ladder     ·2    │          └──────┴──────┴──────┴──────┘
│ 5 Projekt          L3 ✓  │
└──────────────────────────┘
```

Eintrag 3 ist eine zweite Occurrence desselben Climbs — ein 4x4 schreibt genau das hin —
und traegt deshalb eine eigene Lane-Praeferenz, obwohl Climb und Winkel identisch sind.
Adressiert wird immer die Entry-ID, nie die Climb-UUID.

## 2. Fuenf Wahrheiten, strikt getrennt

| Ebene | Reichweite | Autoritaet | UI |
|---|---|---|---|
| lokal ausgewaehlte Occurrence | ein Geraet | Nutzer dieses Geraets | Orange Cursor |
| Lane-Praeferenz einer Occurrence | ein Geraet (heute) | Nutzer dieses Geraets | Lane-Chip `L2` |
| geplanter Ersatz einer Lane (Preview) | ein Geraet | `BoardLayerManager` | `◐` |
| laufender Controller-Write | ein Geraet | Send-Transaktion | `…` / Spinner |
| bestaetigte physische Projektion | reale Wand | Quantum-Readback | `●`, Gruen |

Die Reihenfolge ist eine Rangfolge: **Gruen gewinnt immer.** Eine Lane, die bestaetigt
leuchtet, wird nicht dadurch schwaecher markiert, dass jemand lokal etwas anderes plant.
Ein Preview darf niemals so aussehen, als sei die alte Projektion schon verschwunden;
`BoardClimbLayer` traegt genau deshalb `routeUuid` und `confirmedRouteUuid` getrennt.

### 2.1 Navigation schreibt nicht

Pfeile, Row-Taps und das Oeffnen des Players bewegen ausschliesslich den lokalen Cursor.
Auch die Lane-Zuweisung schreibt nicht: sie ist Planung. Genau eine Handlung veraendert
eine Diode — die Lampe. Das ist dieselbe Regel wie in
[PLAYLIST_OCCURRENCE_FOCUS_AND_COMMIT.md](PLAYLIST_OCCURRENCE_FOCUS_AND_COMMIT.md),
nur um eine Zieladresse erweitert.

## 3. Das effektive Rack

Die Kompatibilitaetsmatrix rechnet nicht gegen das, was leuchtet, sondern gegen das
**effektive Rack**:

```text
effektive Lane L = Preview(L)  falls vorhanden
                 = bestaetigte Projektion(L)  sonst
                 = frei
```

Fremde Controller-Spieler kommen unveraendert dazu; sie sind Teil jedes Vergleichs und
niemals ein Ziel. Im Code tragen sie negative Lane-Ids, damit genau diese beiden
Eigenschaften aus einer einzigen Sammlung folgen und nicht aus zwei, die auseinanderlaufen
koennen.

Ohne diese Regel koennten zwei Previews, die einander widersprechen, gleichzeitig gueltig
aussehen: jedes pruefte nur gegen die physischen Layer, die es ohnehin ersetzen will.

## 4. Ueberlappungssemantik, genau

Fuer eine Kandidaten-Occurrence und eine Ziel-Lane `T`:

1. Die aktuelle Belegung von `T` wird **ausgeschlossen** — was `T` zeigt, kann mit seinem
   eigenen Ersatz nicht kollidieren. Ohne diesen Ausschluss waere jedes Resend unmoeglich.
2. Gegen jede andere effektive Lane wird die Zahl gemeinsamer Placement-IDs bestimmt
   (`perLane`).
3. Zusaetzlich wird die Menge der **eindeutigen** Placement-IDs bestimmt, die der Kandidat
   mit irgendeiner anderen Lane teilt (`uniqueOverlapCount`).

Punkt 3 ist nicht die Summe aus Punkt 2. Ein Hold, das zwei andere Lanes gleichzeitig
belegen, ist genau ein Hold an der Wand — und die Diodenregel handelt von Holds. Beide
Zahlen werden aufbewahrt, weil sie verschiedene Fragen beantworten:

| Frage | Zahl |
|---|---|
| Kann ich senden? | `uniqueOverlapCount == 0` |
| Mit *wem* kollidiere ich? | `perLane` |
| Wie nah dran ist der Climb? | `uniqueOverlapCount == 1` |

### 4.1 `0` heisst sendbar, `<= 1` heisst nicht sendbar

`0 Ueberlappungen` ist eine Zusage: der Controller nimmt den Write an.

`<= 1 Ueberlappung` ist **ausschliesslich ein Such- und Planungsfilter**. Der Controller
kann einer Diode nicht zwei Userfarben geben; eine einzelne Ueberlappung ist weiterhin ein
abgelehnter Send. Die UI nennt diesen Zustand „ein Hold entfernt“ und benennt, wenn
moeglich, welche Lane und welches Hold. Sie behauptet nie Sendbarkeit.

### 4.2 Eligibility wird abgeleitet, nie gespeichert

Eine Lane ist send-eligible, wenn *alle* folgenden Bedingungen gelten:

- jede verbleibende Lane ist bekannt (siehe Abschnitt 6) und ueberlappungsfrei,
- Board- und Modell-Identitaet des Racks passen zum verbundenen Controller,
- der Controller hat einen Platz (das Ersetzen einer eigenen bestaetigten Identitaet
  verbraucht keinen zusaetzlichen),
- eine freie Protokollfarbe existiert,
- die Lane ist nicht mitten in einem Write,
- eine eventuelle Claim-Policy erlaubt es.

Gespeichert wird nichts davon. Eligibility ist eine Aussage ueber *jetzt*, und „jetzt“
aendert jemand anderes an derselben Wand ohne zu fragen. Ein zwischengespeichertes
„Lane 3 passt fuer diesen Eintrag“ ist ein Send, der seit zehn Minuten unmoeglich ist.

Die Reihenfolge der Ablehnungsgruende ist bewusst: eine Lane auf einem anderen Board ist
nicht „voll“, eine Lane mitten im Write ist nicht „kollidierend“. Gemeldet wird der erste
Grund, an dem jemand etwas aendern kann.

## 5. Automatik, Vorschlag und Konflikt

- **Genau eine eligible Lane:** es gibt genau einen `✓`-Chip. Die App stellt keine Frage
  mit nur einer Antwort.
- **Mehrere eligible Lanes:** die beste wird markiert (leer vor Preview vor belegt, dann
  aufsteigende Nummer, damit der Vorschlag ueber Redraws stabil bleibt); gewaehlt wird
  trotzdem per Tap.
- **Keine eligible Lane:** die Occurrence bleibt **unzugewiesen im Backlog**. Sie wird
  nicht abgelehnt, nicht dupliziert und nicht verschoben. Die Wand aendert sich gleich,
  und dann passt sie.
- **Eine bestehende Praeferenz wird ungueltig:** sie wird **nicht** still auf eine andere
  Lane umgebogen. Der Konflikt wird angezeigt. Eine heimliche Umbuchung ist, wie jemand
  Lane 4 beleuchtet, waehrend er auf Lane 2 schaut.

### 5.1 Warum die Lampe ohne Zuweisung nicht selbst eine Lane sucht

Die Adresse der Lampe ist: **Zuweisung, sonst die Lane, in der diese Occurrence schon
liegt (Resend), sonst die kanonische Lane.** Sie sucht sich bewusst keine freie Lane.

Zwei Gruende. Erstens waere das eine Aenderung an einer Wand, die alle sehen, ohne dass
jemand sie verlangt hat: der Gruppen-Current wuerde ploetzlich zusaetzlich leuchten statt
zu ersetzen. Zweitens duerfte eine unaufloesbare fremde Ebene sonst die ganz normale Lampe
blockieren, die seit jeher funktioniert. Ein Write zu versuchen und den Controller
antworten zu lassen ist etwas anderes, als zu behaupten, die Wand sei frei — und die
Behauptung machen ausschliesslich die Chips, die in genau diesem Fall `?` zeigen.

Eine **benannte** Lane ist dagegen ein Versprechen ueber einen konkreten Platz. Sie wird
vor dem Write gegen das Rack geprueft und mit Begruendung abgelehnt, statt sie dem
Controller zum Ablehnen zu ueberlassen.

## 6. Unbekannte Holds sind nicht „keine Holds“

Ein Controller nennt seine Spieler ueber Route-IDs, nicht ueber Diodenlisten. Nach einem
Reconnect rekonstruierte eigene Layer und alle fremden Spieler kommen deshalb ohne Holds
an. Wo die Route lokal aufloesbar ist (`quantum_route_refs` → `climbs.frames`), werden die
Holds nachgezogen. Wo nicht, bleibt der Zustand **unbekannt**:

- `placements == null` bedeutet unbekannt; eine leere Menge bedeutet „leuchtet nichts“.
  Die beiden zu verwechseln ist der einzige Weg, einen unsicheren Send sicher aussehen zu
  lassen.
- Eine Lane mit unbekannten Nachbarn ist **nicht** eligible. Der Grund heisst
  `UNKNOWN_LAYER` und nicht `HOLD_CONFLICT`, weil er anders behoben wird.
- Der Row-Chip zeigt `?`, nicht `✓` und nicht `·0`.

## 7. Entfernen, Umsortieren, Leeren — und das Licht

> Eine Aenderung an einer Liste ist kein Befehl an einen Controller.

| Aktion | Backlog | Rack |
|---|---|---|
| Occurrence entfernen | Eintrag weg, Lane-Praeferenz weg | unveraendert, Lane leuchtet weiter |
| Umsortieren | Reihenfolge neu | unveraendert |
| Liste leeren | alle Eintraege weg | unveraendert |
| Gruppe verlassen | lokale Sicht endet | unveraendert |
| Claim laeuft ab | Reservierung weg | unveraendert |

Eine Lane, deren Occurrence die Liste verlassen hat, wird als **verwaist** gefuehrt:
weiterhin physisch wahr, ohne Zeile, die auf sie zeigt. Der einzige Weg zurueck ist eine
bewusste Handlung — ersetzen oder entfernen. Deshalb sind
`BoardQuantumRackState.assignments` (welche Lane soll diese Occurrence bekommen) und
`QuantumLanePlan.committed` (fuer welche Occurrence wurde diese Lane zuletzt geschrieben)
zwei getrennte Felder: das Erste folgt der Liste, das Zweite der Wand.

## 8. Wer schreiben darf

Das Mesh traegt genau eine kanonische `BoardProjection` — Climb, Winkel,
Disconnect-Semantik. Ein Layer braucht zusaetzlich Route-ID, Slot-Identitaet, Farbe und
einen eigenen Readback. Ein Lane-Kommando durch diesen Pfad kaeme als „angenommen“
zurueck, ohne je eine Lane genannt zu haben — und das Rack zeigte einen bestaetigten Layer
auf einem Controller, der nie davon gehoert hat.

Deshalb:

| Rolle | Planen | Kompatibilitaet lesen | Lane senden |
|---|---|---|---|
| Geraet am Controller (BoardCell-Controller) | ja | ja | ja |
| Mesh-Mitglied | ja¹ | ja | nein, mit Begruendung |

¹ Die Zuweisung eines Mitglieds haette heute keine Wirkung auf den Write, weil der
Controller seinen eigenen lokalen Plan liest. Deshalb ist die Zuweisung fuer Mitglieder
deaktiviert statt wirkungslos angeboten. Eine wirkungslose Schaltflaeche ist eine
Behauptung.

Die kanonische Projektion der Playlist bleibt fuer Mitglieder unveraendert erreichbar: die
normale Lampe geht wie bisher durch den Controller und landet in der kanonischen Lane.

### 8.1 Die kanonische Lane

Auf einem Vier-Lane-Board hat die geteilte Liste weiterhin genau einen kanonischen
Current. Ohne ausdrueckliche Zuweisung schreibt die Playlist deshalb nach `L1`. Das ist
kein Raten, sondern eine stabile Adresse: jeder findet den Gruppen-Current an derselben
Stelle, und weil der Write jetzt die installationseigene Slot-Identitaet traegt, kann die
App ihre eigene Projektion nach einem Reconnect wiedererkennen und gezielt entfernen —
was mit der frueheren Null-UUID unmoeglich war.

## 9. Claims als Leases — Entwurf und bewusste Grenze

Lanes bleiben neutral. Wenn eine Gruppe Koordination braucht, ist die additive Antwort ein
**Claim**: die zeitweise Reservierung einer *Lane*, nicht der Besitz eines Climbs und
keine Aussage darueber, wer klettern darf.

- **weich:** ein fremder Claim fuehrt zu einer ausdruecklichen Uebernahmefrage, nicht zum
  stillen Ueberschreiben. Der haeufigste reale Grund fuer zwei Anspruecke auf Lane 2 ist,
  dass eine Person fertig ist und es nicht gesagt hat.
- **Lease:** ein Claim laeuft ab und wird erneuert. Wer mit dem Telefon in der Tasche
  weggeht, blockiert keine Lane fuer den Rest der Session.
- **idempotent:** `revision` pro Lane, monoton, sodass eine ueberholende Erneuerung
  trotzdem als die neuere gilt.
- **Ablauf veraendert nie Licht.** Ablauf, Freigabe, Verschwinden eines Teilnehmers,
  Entfernen aus der Playlist und Verlassen der Gruppe tun mit der Wand dasselbe: nichts.
- **Einzelnutzung zeigt keine Claims.** Ohne zweiten Teilnehmer gibt es nichts zu
  koordinieren.
- **Technische und menschliche Identitaet bleiben getrennt.** Die Quantum-User-UUID ist
  eine installationsabgeleitete technische Adresse fuer einen Controller-Slot; sie an eine
  Person zu binden hiesse, einen stabilen Identifikator auf eine Wand zu senden, die jeder
  mithoeren kann.

**Bewusste Grenze dieses Increments:** `BoardQuantumRackState`, `BoardQuantumLaneClaim` und
`BoardQuantumRackPolicy` existieren, sind serialisierbar, versioniert, deterministisch
mergebar und getestet — aber sie liegen **nicht** auf dem BoardCell-Wire und in keinem
kanonischen State-Hash. Ein geraetelokaler Lock waere keine verteilte Korrektheit, sondern
eine Behauptung darueber; deshalb wird in dieser Stufe keine Claim-UI angeboten.

Was der Transport-Schritt braucht (und was ihn reviewbar macht):

1. Schema-Version anheben, mit unveraenderter Legacy-Hash-Verzweigung, solange
   `usesPreRackShapeOnly` gilt. Alles, was nicht gehasht wird, ist veraenderbar, ohne den
   Hash zu brechen — genau so entsteht ein Authentifizierungsloch.
2. Rollout-Fenster mit gemischten Clients: alte Replikate schreiben leere Felder und
   duerfen dadurch nicht ungueltig werden.
3. Deterministisches Merge pro Lane und pro Occurrence (bereits implementiert in
   `BoardQuantumRackPolicy.merge`), nicht per Dokument.
4. Ein Controller, der Lane-Kommandos ablehnt, die er physisch nicht ausfuehren kann,
   statt sie zu bestaetigen.

## 10. Faehigkeitsgrenze und Nicht-Quantum-Invarianten

Alles hier ist additiv und faehigkeitsgesteuert:

- Die Lane-Ableitung ist inert, solange
  `connectedBoardBrand.supportsIndependentClimbLayers` nicht wahr ist. Inert heisst: keine
  Chips, keine Ziel-Lane, keine geaenderte Aktion.
- `BoardPlaylistState.currentEntryId` und `pendingProjection` behalten ihre Bedeutung. Es
  gibt kein zweites „Current“.
- Kilter, Aurora und MoonBoard behalten ihren Single-Projection-Pfad byte-fuer-byte. Der
  Lane-Sender wird ausschliesslich betreten, wenn der **verbundene** Controller Quantum ist
  — nicht wenn eine Praeferenz das behauptet, denn ein Boardwechsel in den Einstellungen
  trennt die Verbindung nicht.
- Ein Boardwechsel sendet, entfernt und veraendert keinen physischen Layer. Er verwirft
  lokalen Plan und Rack, weil beide fuer einen anderen Controller gedacht waren.

## 11. Browser-Filter „passt noch auf die Wand“

Ein Quantum-only Filter mit drei Zustaenden: `aus`, `genau 0 Ueberlappungen`,
`hoechstens 1 Ueberlappung`.

- **Gemessen wird gegen bestaetigte Layer**, nicht gegen das effektive Rack. Das
  Versprechen des Filters lautet „passt neben das, was jetzt an der Wand ist“; ein Preview
  ist die Idee einer Person und hat kein Hold belegt. Das ist bewusst die Umkehrung von
  Abschnitt 3 — Planung und Einkauf sind verschiedene Fragen.
- **Inert, wenn nichts leuchtet.** Bei leerem Rack hat jeder Climb null Ueberlappungen;
  ein Filter, der dann „filtert“, behauptet Arbeit, die er nicht leistet, und scannt dafuer
  den Katalog.
- **Inert auf jedem anderen Board**, und beim Boardwechsel auf `OFF` zurueckgesetzt — in
  derselben Zustandsuebergabe wie die Hold-Set-Maske, damit kein Lesevorgang dazwischen den
  neuen Katalog unter der alten Regel sieht. Persistiert wird der Name des Zustands, nicht
  sein Ordinal.
- **Pagination:** der Filter ist ein Praedikat auf der geholten Seite, nicht ein
  UUID-Gate. Jede Verzweigung von `fetchFiltered` erhoeht ihren DB-Offset um die
  *ungefilterte* Seitengroesse, sodass Endlos-Scrollen nachfuellt statt Zeilen zu
  verlieren. Ein vorberechnetes UUID-Set haette auf genau dem Katalog, auf dem der Filter
  am nuetzlichsten ist, eine `IN`-Liste mit Tausenden Parametern erzeugt.
- **Zaehlung:** die exakte Trefferzahl kommt aus genau einem Frames-Scan pro Rack-Stand,
  nicht aus einem Scan pro Seite. Ergebnis ist eine ehrliche Zahl statt „50+“.
- **Unbekannt bleibt sichtbar.** Kann ein bestaetigter Layer nicht aufgeloest werden, wird
  weiterhin nach Bekanntem gefiltert, aber das Ergebnis ausdruecklich nicht als Zusage
  praesentiert.

## 12. UI-Zustaende und barrierefreie Sprache

Chips sind **Nummer + Symbol**, nie Farbe allein. Zwei der vier Protokollfarben sind
schwer zu unterscheiden, und ein Teil der Nutzer unterscheidet sie gar nicht.

| Symbol | Bedeutung | Farbe (redundant) |
|---|---|---|
| `L2 ●` | diese Occurrence leuchtet in L2 | Gruen |
| `L2 ◐` | fuer L2 geplant, nichts geschrieben | Orange |
| `L2 …` | Write laeuft | Orange, Spinner |
| `L2 ✓` | frei von Konflikten, sendbar | neutral |
| `L2 ·1` | ein Hold im Weg — nicht sendbar | Gelb |
| `L2 ·3` | drei Holds im Weg | Gelb |
| `L2 ?` | ein Layer ist unbekannt | neutral, Fragezeichen |
| `L2 ✕` | Kapazitaet, Farbe oder Claim | Rot |

Jeder Chip traegt eine vollstaendige Contentbeschreibung („Lane 2, ein Hold ueberlappt mit
Lane 1“). Die erweiterte Ansicht benennt kollidierende Lanes und, wo moeglich, das Hold.
Deaktivierte Aktionen nennen ihren Grund; „geht nicht“ ohne Grund ist der einzige
Fehlertext, der garantiert nicht hilft.

## 13. Fehler- und Verbindungsverhalten

| Situation | Verhalten |
|---|---|
| Disconnect | Plan und Rack bleiben; ein Reconnect zum selben Board ist kein Boardwechsel |
| Reconnect, eigene Layer | ueber stabile Slot-UUIDs rekonstruiert, Holds nachgezogen |
| Fremder Layer, Route unbekannt | read-only, kapazitaetsrelevant, Kompatibilitaet `?` |
| Rack fuer anderes Board | kein Write, klare Meldung; Fence direkt vor dem Write |
| Timeout / Exception | `FAILED`, niemals `CONFIRMED`; die vorherige Lane bleibt wie sie war |
| Controller voll | kein Verdraengen, kein `TURN_OFF_ALL` |
| Bekannte Ueberlappung | vor BLE abgelehnt, mit der Lane im Text |

## 14. Testvertraege

Mindestens diese Eigenschaften existieren als Tests:

- Ziel-Lane wird aus dem Vergleich ausgeschlossen; Resend derselben Lane bleibt moeglich.
- `perLane` und `uniqueOverlapCount` sind getrennt korrekt, inklusive eines Holds, das mit
  zwei Lanes gleichzeitig kollidiert.
- Previews gehen in das effektive Rack ein; zwei einander widersprechende Previews sind
  nicht beide gueltig.
- Unbekannte Layer erzeugen `UNKNOWN_LAYER`, niemals `0`.
- Doppelte Occurrences desselben Climbs tragen getrennte Lane-Praeferenzen.
- Entfernen einer Occurrence entfernt die Praeferenz und nicht die Lane.
- Claim-Ablauf entfernt die Reservierung und veraendert keinen physischen Zustand.
- Navigation und Row-Auswahl erzeugen keinen Command und keinen Write.
- Quantum-Filter sind auf jedem anderen Board inert und werden beim Boardwechsel
  zurueckgesetzt.
- Alte serialisierte Zustaende ohne Rack-Felder bleiben lesbar; unbekannte Felder brechen
  nichts.
