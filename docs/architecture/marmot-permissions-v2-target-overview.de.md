# Marmot-Permissions v2: Zielbild in Kürze

[English: vollständige Zielarchitektur](marmot-permissions-v2-target.md)

Stand 23. September 2026, Branch `feat/marmot-permissions-v2` (APKTrack-Track
`feat-marmot-permissions-v2-0bfee131`). Kein Release.

## Kurz gesagt

Marmot/MLS bleibt die Vertraulichkeitsschicht (Identitätsschlüssel ≠
Datenschlüssel, Forward Secrecy, Post-Compromise Security, kein Eigenbau).
Vereinfacht wird alles darüber und darunter:

1. **Unten – Transport gehört Rust.** Relay-Pool (nostr-sdk), NIP-42, NIP-77,
   Ausgang, Eingang, KeyPackages, Welcomes und Discovery laufen im nativen Host.
   Kotlin ruft ihn über genau eine Klasse (`MarmotHost`, IO-Dispatcher, Tracing)
   und holt Nachrichten per Pull (`next`). Kotlin speichert keine Kopie von
   Protokolldaten (Gruppen, Relays, Events, Zustellstatus).
2. **Lokaler Relay als erstes Ziel.** Jede App hat einen eigenen Relay im
   Prozess (nostr-relay-builder, ohne Socket, über `tokio::io::duplex`). Eine
   MLS-Nachricht landet in derselben SQLCipher-Transaktion wie der
   Ratchet-Schritt im lokalen Speicher; ein Replikator verteilt sie an die
   öffentlichen Relays und holt Eingänge per Abo und NIP-77 nach. Kein zentraler
   CruxCoach-Relay nötig. Gepinnte Peer-Endpunkte (später LAN/BLE) sind als
   Schnittstelle vorgesehen, in v1 leer.
3. **Oben – einfache Sync-Nachrichten.** Eine innere Art (Kind 1230, Label
   `cc.share.v2`): Manifest (Kategorien, Stichtag, Generation, Folge, Anzahl,
   Prüfsumme), volle Seiten, Deltas, Bestätigung, Resync, Ende. Der Empfänger
   prüft nach jedem Schritt die Prüfsumme und fordert bei Lücke oder
   Abweichung einen vollständigen Resync an.

## Richtlinie

Lokal und unsigniert. Zwei Voreinstellungen (Freunde, Bekannte; Freunde sehen
mindestens, was Bekannte sehen), drei Kategorien (Profil & Ziele,
Trainingsverlauf mit Zeitraum 30/90/365 Tage oder alles, Notizen), Personen-
und Objektausnahmen, Resolver aus FEAT-062 mit gleicher Rangfolge. Beide
Voreinstellungen starten mit Standardwerten: Freunde = Profil & Ziele +
Trainingshistorie 30 Tage, Bekannte = Profil & Ziele (nur beim Anlegen der
Tabelle, eine eigene Wahl wird nie zurückgesetzt). Gesundheitsdaten, Videos, signierte Ledger,
Mehrgeräte-Autorität, Wiederherstellungs-Code, Uhrsperre und die
Einmal-Momentaufnahmen entfallen. Ein Gerät pro Identität.

## Sichtbare Zustände

Ausstehend · Aktiv · Gestoppt · Beendet. Ein Schalter pro Person steuert die
eigene ausgehende Richtung. MLS-Epochenwechsel (z. B. Schlüsselerneuerung)
beenden keine Freundschaft mehr – in v1 taten sie das.

## Warum keine Atomarität zwischen App und nativem Teil nötig ist

Nativ bleiben MLS-Zustand, Ausgangs-Event, Token, Eingang und Lese-Cursor in
einer SQLCipher-Transaktion. Zwischen App-Datenbank und nativem Teil sorgen
idempotente Tokens (`Generation:Folge:Teil`), Generationen und Prüfsummen für
Konvergenz: Nach einem Absturz liefert derselbe Token dieselbe Nachricht
(`duplicate`) oder meldet einen Konflikt – dann beginnt eine neue Generation
mit vollem Stand. Beim Einschränken gilt die Reihenfolge `cancel` → App-Commit
→ neues Manifest; nach dem Commit berechnete Nachrichten enthalten nie
ausgeschlossene Daten. Rest wie in v1: Eine Nachricht, die im Moment des
Einschränkens schon auf einer Relay-Verbindung liegt, kann noch ankommen.
Die vollständige Tabelle der Absturzfenster steht in §7 der englischen Fassung.

## Entscheidung Host vs. MarmotKit

Eigener Host bleibt: MarmotKit baut seinen Relay-Client intern, lässt Loopback
nur als Test-Option zu und verweigert private/LAN-Adressen immer – der lokale
Relay als primäres Ziel ginge nur mit Patch. Dazu: ~50 MB statt ~31 MB
(arm64), JNA statt vier geprüfter JNI-Exporte, Breaking Changes in jeder 0.10.x.
Der MDK-Pin bleibt vorerst bei `615d0c1c`: Unsere 18 genutzten Session-Aufrufe
sind in 0.10.4 zwar signaturgleich, aber 57 der 116 neuen Commits ändern
Engine/Storage binnen zehn Tagen, und 0.10.4 verlangt unumkehrbare
Storage-Migrationen. Der Bump folgt getrennt.

## Daten und Migration

SecureDB 33 → 34: Alle v1-Sharing-Tabellen werden entfernt, neue Tabellen
starten leer bis auf die zwei Standard-Voreinstellungen; kein `VACUUM`; keine v1-Zustimmung wird als v2-Freigabe gedeutet. Ältere Builds
verweigern Schema 34 (kein Reset). Der alte native Zustand `marmot-v1/` wird
nach dem ersten erfolgreichen v2-Start gelöscht. Empfangene Datensätze bleiben
außerhalb der kanonischen Tabellen, werden aber auf der Klettertour-Detailseite
angezeigt („Anna: geschafft, 3 Versuche“).

## Phasen

1. Nativer Transport, lokaler Relay, Replikator, `MarmotHost`; alter
   Kotlin-Transport und v1-Protokolle raus (danach tauscht die App vorübergehend
   keine Daten).
2. Sync-Protokoll, lokale Richtlinie, Migration 33, Ledger raus.
3. Oberfläche im 0.2.3-Muster, Detailseiten-Join, Testanleitung.

Offene Owner-Entscheidungen und Annahmen: §12 der englischen Fassung.

## Umsetzung (Stand 23. September 2026)

Der Branch `feat/marmot-permissions-v2` setzt das Zielbild um. Beim Bauen
festgelegt: Sende-Tokens gelten je MLS-Gruppe (eine neue Freundschaft beginnt
frisch); jede Rücknahme, die Nachrichten zurückgezogen hat, startet eine neue
Generation (Manifest + vollständiger Stand), damit der Empfänger nie auf ein
zurückgezogenes Delta wartet; „wartende Nachrichten“ zählt nur, was noch kein
Relay angenommen hat; Anfragen werden nach 15 s, dann mit wachsendem Abstand
bis 10 min erneut versucht. Die Oberfläche hat eine Übersicht, eine Seite je
Person (ein Schalter, Kreis, drei Kategorien, Zeitraum, genaue Vorschau mit
Schalter je Eintrag, empfangene Daten, Beenden mit genau einer Bestätigung),
je eine Seite für die Voreinstellungen Freunde und Bekannte sowie die
Verbindungsseite. Im Info-Blatt eines Boulders erscheinen Notizen und Versuche
von Freunden zu diesem Boulder, getrennt von den eigenen Daten. Die
[Testanleitung](../releases/marmot-permissions-test-guide.md) beschreibt v2.
