# Testanleitung: Permission-System (Marmot-Sharing) auf dem 0.2.3-Stand

Stand: 22. September 2026, Branch `feat/marmot-permissions-v023` nach dem Merge des
verifizierten 0.2.3-QA-Kandidaten `feat/0.2.3-qa-fixes` (`119c56914`).
Diese Anleitung beschreibt, was ein Tester mit dem Feature-APK aus APKTrack
(Track `feat-marmot-permissions-v023-d925a3c5`, Paket
`com.cruxcoach.android.dev.f_d925a3c5a593`) durchspielen kann. Sie ist kein
Nachweis, dass diese Abläufe auf Android bereits bestanden wurden: Die bisherigen
Belege sind Host-JVM- und Native-Tests sowie Prozess-Tests mit synthetischen
Teilnehmern ([Architektur](../architecture/marmot-permissions.md),
[deutsche Übersicht](../architecture/marmot-permissions-overview.de.md)).
Amber, Android-Keystore, Doze/WorkManager und arm64-Laufzeit sind noch nicht
auf einem Gerät abgenommen.

## 1. Was getestet wird

Das Permission-System teilt **eigene private Daten** (Profil und Ziele,
Trainingsverlauf, private Notizen) verschlüsselt mit bestätigten Freunden über
Nostr-Relays (Marmot/MLS im nativen Host). Es ist unabhängig von:

- **„Teilen in der Nähe" / CruxRelay** (Einstellungen → Teilen in der Nähe,
  sowie der eine Schalter „Für Board-Apps freigeben" auf dem Board-Verbindungsblatt).
  Das ist Bluetooth-/LAN-Sharing von Boulder-Problemen und Board-LEDs, kein
  Personen-Sharing. Beide Funktionen teilen sich keinen Zustand, keine Strings
  und keine Navigation; sie dürfen gleichzeitig aktiv sein.
- **Nostr-Datenbackup** (Einstellungen → Backup). Strukturierte Backups enthalten
  keine von Freunden empfangenen Kopien.

Einstiegspunkte im Merge-Stand:

| Ort | Weg | Inhalt |
|---|---|---|
| Übersicht | Einstellungen → CruxCoach-Konto → Zeile „Persönliche Daten teilen" (Info-Symbol zeigt die Zusammenfassung) | Freundschaftskarte, Personen, Einstellungen (Regeln, Einmalfreigaben, Verbindungsdetails, Wiederherstellung), „Deine Geräte" |
| Peer-Detail | Übersicht → Person antippen | Kreis, Regeln, Freundschaftskarte für diese Person, unter „Regeln, Einmalfreigaben und Verbindungsdetails": Status, Kategorien, Objektregeln, Transport, Ablauf, Notiz-Snapshot, Aktionen, Geräte |
| Snapshot (Einmalfreigabe) | Peer-Detail → Einstellungen aufklappen → „Notiz als Momentaufnahme teilen" | Legacy-Pfad: einzelne Notiz mit Ablauf und expliziter Annahme |
| Wiederherstellung | Übersicht → „Regeln, Einmalfreigaben und Verbindungsdetails" → Abschnitt „Wiederherstellungs-Code" | Code erzeugen, Backup exportieren/importieren, alleinige Kontrolle übernehmen |
| Transport-Recovery | Übersicht oder Peer-Detail → Karte „Verschlüsselte Freigaben über Nostr" | Discovery, Relay-Pool, Uhr-/Transport-Wiederherstellung |

## 2. Vorbedingungen

**Geräte und Konten**

- Feature-APK aus APKTrack (Debug-Signatur der Entwicklungs-CA). Es ist ein
  Debug-Build: der Abschnitt „Demo-Daten" und die Simulations-Schaltflächen für
  Demo-Personen sind sichtbar. In einem Release-Build fehlen sie.
- Jedes Gerät braucht ein **eigenes Nostr-Konto**. Zwei Geräte mit derselben
  Identität sind keine Freundschaft, sondern Mehrgeräte-Betrieb eines Kontos.
- Konto A: lokaler Schlüssel (Onboarding „Neues Konto") reicht.
- Konto B (optional Amber): Amber-Konto „jede Berechtigung manuell" wie in der
  QA-Sitzung angelegt. Jede Signatur (Freundschaftsanfrage, Annahme, Änderung,
  Ende, tägliche Aktualitätsnachweise) fordert dann eine Amber-Bestätigung; der
  Status „Öffne die Freigaben und synchronisiere einmal…" ist bei Amber erwartbar.
  Im Hintergrund wird ausschließlich der Content-Provider-Weg benutzt, kein
  Dialog. Wer Amber-Dialoge ablehnt, darf keine Freigabe sehen.
- Das Nokia der 0.2.3-QA ist belegt; für diese Anleitung ein anderes Gerät
  nutzen oder mit dem Owner abstimmen.

**Netz und Relays**

- Internet auf beiden Geräten. Standard-Relay-Pool (sechs Einträge, siehe
  Übersicht). Nach dem bisherigen Befund akzeptieren `relay.primal.net`,
  `nostr-pub.wellorder.net`, `nos.lol`, `nostr.oxtr.dev` die benötigten Typen;
  `relay.damus.io` verlangt Auth, `blossom.cruxcoach.org/nostr` lehnt ab. Die App
  zeigt das je Relay an; rote Einträge sind kein Testfehler, solange mindestens
  ein Relay „angenommen" meldet.
- Optional für Offline-Tests: Flugmodus auf einem Gerät.

**Testdaten**

- Damit „Trainingsverlauf" etwas enthält: einige Begehungen loggen. Ohne echtes
  Board geht das mit dem externen Board-Simulator (Kilter/Moon-Simulator der QA)
  oder über Logbuch-Import (`qa023-import-v3.json`, synthetisch).
- Profil und Ziele: Einstellungen → CruxCoach-Konto → Profil ausfüllen.
- Private Notiz: an einem Problem eine Notiz speichern.

## 3. Testfälle

Notation: **A** = anfragendes Gerät/Konto, **B** = angefragtes Gerät/Konto.
Erwartete Texte sind die deutschen UI-Strings; die englischen Entsprechungen
existieren paarweise.

### P-01 Einstieg und Gerätefreigabe (ein Gerät)

1. Einstellungen → CruxCoach-Konto. Erwartet: neben Schlüsselverwaltung und
   Profil die Zeile „Persönliche Daten teilen" mit Info-Symbol
   („Kreise, Kategorien und Ausnahmen pro Person") und Pfeil.
2. Zeile antippen. Erwartet: Screen „Persönliche Daten teilen" mit Karte
   „Freunde: private aktuelle Daten" und Abschnitt „Deine Geräte".
3. Abschnitt „Deine Geräte": Erwartet bei frischer Installation „Noch kein Gerät
   aufgenommen…" und Schaltfläche „Dieses Gerät mit dem Hauptschlüssel aufnehmen".
   Antippen (bei Amber: Signatur bestätigen). Erwartet: dieses Gerät als
   „Hauptgerät", „Vollmacht-Generation 1". Ohne diesen Schritt bleiben alle
   Änderungen gesperrt (fail-closed), das ist beabsichtigt.
4. Wenn oben ein Banner zur nativen Sperre erscheint („Geprüfte
   Upstream-Revision…" / Transport nicht verfügbar): Das APK enthält die native
   arm64-Bibliothek nicht oder sie lädt nicht. Dann sind P-04 bis P-08 blockiert;
   Befund mit Gerätemodell und Logcat melden.

### P-02 Demo-Daten (ein Gerät, Debug)

1. „Regeln, Einmalfreigaben und Verbindungsdetails" aufklappen → „Demo-Daten laden".
2. Erwartet: drei Personen „Anna (Demo)", „Ben (Demo)", „Carla (Demo)" mit
   Status (Anna angenommen, Ben eingeladen, Carla angenommen mit ausstehender
   Erweiterung). Kreis-Grundeinstellungen: Profil für alle, Trainingsverlauf für
   Bekannte, Videos für Freunde.
3. Ben öffnen → Einstellungen aufklappen → Aktionen: „Annehmen" (nur bei
   Demo-Personen sichtbar, weil der Build deren Schlüssel hält). Erwartet:
   Status wechselt auf angenommen, ohne dass ein Verifizierer gelockert wird.
4. Carla öffnen → Hinweis auf ausstehende Erweiterung → „Erneut um Zustimmung
   bitten" (bei Demo-Personen simuliert der Build die Zustimmung). Erwartet: die
   erweiterte Kategorie wird freigegeben.
5. Anna öffnen → Geräte: ein Gerätename eingeben → „Gerät freigeben", danach
   „Gerät sperren". Erwartet: „Gesperrt – kann nicht erneut freigegeben werden".
6. Kreis-Karten auf der Übersicht: Schalter je Kategorie. Geerbte Freigaben aus
   einem weiteren Kreis sind deaktiviert und mit Herkunft beschriftet.

Demo-Personen haben keine echte Gegenstelle; Freundschaftsanfragen an sie
bleiben in „Freundschaftsbestätigung ausstehend".

### P-03 Signer-Verhalten und Sperre bei Signieren (ein Gerät)

1. Mit Amber-Konto einen Kreis-Schalter umlegen. Erwartet: Amber-Dialog; während
   er offen ist, sind alle anderen Schalter/Schaltflächen deaktiviert
   („Ein Schalter, eine Signatur").
2. Amber-Dialog ablehnen. Erwartet: Schalter springt zurück, Fehlermeldung
   sichtbar, kein zweiter Dialog, nichts wurde geändert.

### P-04 Auffindbarkeit und Freundschaftsanfrage (zwei Geräte)

Vorbereitung auf B: Übersicht → Karte „Freunde" → „Freundschaftsanfragen
empfangen" (bei Amber signieren). Erwartet: Relay-Statusliste in der Karte
„Verschlüsselte Freigaben über Nostr" (Einstellungen aufklappen), mindestens
ein Relay „angenommen".

Auf A:

1. „Meine ausgehenden Daten wählen".
2. npub oder Hex-Schlüssel von B eingeben → „Personen, die diese Daten erhalten
   sollen". Ungültige Eingabe: Schaltfläche bleibt deaktiviert, Fehlertext.
3. Kategorien wählen (z. B. Profil und Ziele + Trainingsverlauf), Historie
   „Letzte 30 Tage". Erwartet: UTC-Datumsgrenze wird angezeigt.
4. „Tatsächliche Daten ansehen". Erwartet: Vorschau mit Zähler
   „n aktuelle Einträge in diesem Umfang" und den konkreten Einträgen (keine
   Kommentare, kein Standort, keine Körperdaten).
5. „Freundschaft anfragen / meine Auswahl speichern" (signieren). Erwartet:
   Eintrag „Freundschaftsanfrage ausstehend: private Verbindung wird
   vorbereitet…" mit Abbrechen; A hat damit selbst Auffindbarkeit aktiviert.

Auf B (App im Vordergrund, ggf. „Jetzt abgleichen"):

6. Erwartet innerhalb von etwa 30 s: Eintrag „Daten von <A>" mit Status
   „Freundschaftsbestätigung ausstehend" und As angebotenem Umfang.
7. Eigene ausgehende Daten wählen (auch „keine" ist gültig), optional Vorschau,
   dann „Freundschaft annehmen" (signieren).

Erwartet auf beiden Geräten: Status „Angenommen — erste Daten ausstehend", dann
„Freundschaft aktiv"; bei A „Datenstand vom …" und „Zuletzt vom Empfänger
bestätigt: …"; bei B die Einträge von A als „Schreibgeschützte Kopie…".
Bs eigenes Profil/Logbuch bleiben unverändert (prüfen: Logbuch-Zähler,
Profilname).

### P-05 Laufende Änderungen (zwei Geräte)

1. Auf A eine neue Begehung loggen (Board-Simulator oder manuell) und den
   Profilnamen ändern.
2. Erwartet auf B nach Abgleich (automatisch ca. 30 s im Vordergrund, sonst
   „Jetzt abgleichen"): neuer Eintrag und geänderter Name; „Datenstand vom …"
   aktualisiert. Kein neuer Annahme-Dialog.
3. Auf A eine geloggte Begehung löschen. Erwartet auf B: Eintrag verschwindet
   nach Abgleich.
4. Auf A „Meine Auswahl ändern" → Trainingsverlauf abwählen → Vorschau →
   speichern. Erwartet auf B: Trainingsdaten von A werden entfernt, Profil bleibt;
   B muss nichts bestätigen.

### P-06 Stoppen, Beenden, Bereinigung (zwei Geräte)

1. Auf B „Meine ausgehenden Daten stoppen". Erwartet: Status bei B „Eigene
   ausgehende Daten gestoppt"; A verliert Bs Daten, behält seine eigene Freigabe.
2. Auf A „Freundschaft beenden". Erwartet auf A sofort: „Freundschaft lokal
   beendet", Hinweis „Lokale Kopien entfernt. Bereinigung der Gegenstelle …
   steht noch aus". Empfangene Daten von B sind auf A weg.
3. B abgleichen. Erwartet auf B: Freundschaft beendet, As Daten gelöscht, ohne
   neue Zustimmung. Auf A nach weiterem Abgleich: „Die Gegenstellen-App hat die
   Bereinigung bestätigt…".
4. Neue Anfrage A → B stellen. Erwartet: neue Freundschaft mit eigener
   Generation; alte Daten kommen nicht zurück, B muss erneut annehmen.

### P-07 Offline und Signer-Wartezustände (zwei Geräte)

1. B in den Flugmodus. Auf A zwei Begehungen loggen. Erwartet auf A:
   „Änderungen oder Bestätigung ausstehend" bzw. „Keine nutzbare
   Relay-Verbindung bestätigt…", kein Fehlerdialog.
2. B wieder online, App öffnen. Erwartet: Einträge kommen an, Status zurück auf
   „Freundschaft aktiv".
3. App auf A beenden (Task wegwischen) und neu starten. Erwartet: Freundschaft,
   Warteschlange und Status bleiben erhalten.
4. Bei Amber auf B: Hintergrundsignatur nicht vorab erlaubt. Erwartet: Hinweis
   „Öffne die Freigaben und synchronisiere einmal…", kein unerwarteter Dialog
   im Hintergrund.

### P-08 Einmal-Snapshot (Legacy, zwei Geräte)

1. Peer-Detail von B auf A → Einstellungen aufklappen → Karte „Notiz als
   Momentaufnahme teilen": Rolle prüfen (kein Server), Text eingeben → „Diese
   Momentaufnahme anbieten".
2. Auf B: Momentaufnahme erscheint mit „Dieser Momentaufnahme zustimmen";
   danach „Momentaufnahme lesen" zeigt den Text schreibgeschützt.
3. Auf A „Zugriff widerrufen". Erwartet auf B nach Abgleich: nicht mehr lesbar. Bereits
   gelesener Text ist nicht rückholbar (das steht so in der UI).
4. Ablauf: auf A „1 Stunde" setzen. Erwartet: Erweiterung/Entfernung des Limits
   verlangt neue Zustimmung von B.

### P-09 Wiederherstellung und Geräte (ein Gerät, zerstörend)

1. „Wiederherstellungs-Code" → „Neuen Code erzeugen" (signieren). Code notieren.
2. Backup exportieren (SAF-Dateidialog). Erwartet: Datei ohne Fremdkopien.
3. App-Daten löschen oder zweite Installation (anderes Gerät, gleiches Konto):
   Backup importieren, Code eingeben. Erwartet: „Wiederhergestellt. Die Freigabe
   bleibt gesperrt, bis Widerrufe erneut geprüft sind." Falscher Code: „Dieser
   Code stimmt nicht."
4. „Alleinige Kontrolle übernehmen" (signieren). Erwartet: Vollmacht-Generation
   steigt, andere Geräte werden als „Durch eine Wiederherstellung gesperrt"
   gelistet.
5. Karte „Verschlüsselte Freigaben über Nostr": „Freigaben entziehen und
   wiederherstellen" (Geräteuhr) mit Bestätigungsdialog.
   Erwartet: bestehende Freigaben werden beendet, Ende-Nachrichten bleiben zur
   Zustellung vorgemerkt; danach neue Freundschaften nötig.

### P-10 Koexistenz mit 0.2.3-Funktionen (ein Gerät)

1. Einstellungen → Teilen in der Nähe → Schalter umlegen; Board verbinden
   (Simulator) → Verbindungsblatt → Schalter „Für Board-Apps freigeben".
   Erwartet: keine Wechselwirkung mit „Persönliche Daten teilen"; kein zweiter
   Dialog.
2. Einstellungen → Backup: Backup-Zustimmung bleibt aus, solange nicht bewusst
   aktiviert (QA-Befund M-005). Sharing-Aktionen dürfen den Backup-Schalter nicht
   verändern.
3. Kontowechsel (Schlüsselverwaltung): App startet neu; danach zeigt „Persönliche
   Daten teilen" den Zustand des neuen Kontos, alte Objekte sind nicht sichtbar.
4. Tour/Onboarding: die Sharing-Zeile ist nicht Teil der Tour; die Tour darf
   nicht auf ihr hängen bleiben.

## 4. Bekannt offen

- **Keine Geräteabnahme.** Alle obigen Erwartungen stammen aus Host-Tests und
  Prozess-Tests mit synthetischen JVM-Teilnehmern. Amber-Hintergrundsignatur
  (Content-Provider), Keystore-Wrapping, Prozess-Tod und WorkManager-Backstop
  sind nicht auf Android geprüft.
- **UI-Konventionen 0.2.3 nur teilweise übernommen.** Der Settings-Einstieg
  nutzt jetzt `SettingsDestinationRow` mit Info-Symbol. Die Sharing-Screens
  selbst verwenden eigene Karten, rohe `Switch`-Zeilen ohne `SettingsToggleRow`,
  viele `OutlinedButton`s und mehrere `AlertDialog`-Bestätigungen (Abstufung,
  Uhr-/Transport-Wiederherstellung, „Lokal löschen", Momentaufnahme-Text). Die Übersicht listet
  Personen doppelt (Schaltflächenliste oben, Karten unter den Einstellungen).
  Das ist ein Design-Rückstand, kein Funktionsfehler; Entscheidung beim Owner.
- **Relay-Verfügbarkeit** ist eine Momentaufnahme vom 11. September 2026; Damus
  (Auth) und der CruxCoach-Blossom-Endpunkt gelten als nicht nutzbar.
- **Grenzen:** 16 aktive Freundschaften, 1.000 Datensätze / 1 MiB pro Umfang,
  8 KiB pro Datensatz. Größere Umfänge werden komplett abgelehnt („Dieser Umfang
  überschreitet die unterstützte Größe…"), nicht gekürzt.
- **Server-Rolle** ist nur mit dem synthetischen Endpunkt
  (`scripts/marmot_endpoint.py`) testbar; es gibt keinen Produktionsserver.
- **Amber-Variante der QA (D01)** war bereits in der 0.2.3-Abnahme eine
  Abdeckungslücke; sie gilt hier weiterhin.
- Videos und Gesundheitsdaten sind als Kategorien im Legacy-Pfad sichtbar,
  werden aber im Freundschafts-Pfad nicht übertragen (nur Profil/Ziele,
  Trainingsverlauf, Notizen).

## 5. Befunde melden

Pro Befund: Testfall-ID (P-xx), Gerät/Android-Version, Konto-Typ (lokal/Amber),
Relay-Status aus der Transport-Karte, Logcat-Ausschnitt (Tags `Sharing`,
`Marmot`, `PERF`) und ob die Gegenstelle online war. Keine npubs echter Personen
und keine Schlüssel in Berichte schreiben.
