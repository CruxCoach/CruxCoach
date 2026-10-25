# Testanleitung: Persönliche Daten teilen (Marmot v2)

Stand: 23. September 2026, Branch `feat/marmot-permissions-v2` (Zielarchitektur
[marmot-permissions-v2-target.md](../architecture/marmot-permissions-v2-target.md),
[deutsche Übersicht](../architecture/marmot-permissions-v2-target-overview.de.md)).

Feature-Identität laut `scripts/feature_identity.py`: APKTrack-Track
`feat-marmot-permissions-v2-0bfee131`, Paket `com.cruxcoach.android.dev.f_0bfee1310616`.
Ein Feature-APK gilt erst als veröffentlicht, wenn APKTrack `status="published"` und
`receipt_delivered=true` meldet. Diese Anleitung beschreibt, was ein Tester mit diesem
Feature-APK durchspielen kann. Sie ist kein Nachweis, dass die Abläufe
auf Android bestanden wurden. Belege bisher: native Rust-Tests, JVM-Tests (Sync-Semantik,
Migration, UI-Semantik) und Prozess-Tests mit drei synthetischen Teilnehmern über zwei
feindliche Loopback-Relays (`scripts/marmot_network_e2e.py`,
`scripts/marmot_continuous_e2e.py`). Amber, Doze/WorkManager und arm64-Laufzeit sind
nicht auf einem Gerät abgenommen.

Die Anleitung der 0.2.3-Variante (v1, signierte Freigaben, Geräte, Snapshots) gilt für
diesen Stand nicht mehr; sie steht in der Git-Historie dieser Datei.

## 1. Was getestet wird

Eigene private Daten – **Profil & Ziele**, **Trainingshistorie** (mit Zeitraum) und
**Notizen** – gehen verschlüsselt an bestätigte Freunde. Vertraulichkeit leistet Marmot
(MLS, je Freundschaft eine Gruppe aus genau zwei Personen); Nachrichten laufen über das
Relay in der App und werden auf öffentliche Relays kopiert. Nicht Teil von v2:
Gesundheitsdaten, Videos, mehrere Geräte pro Konto, signierte Freigaben.

Die Freigaben sind **lokal**: Voreinstellung je Kreis (Freunde, Bekannte), Ausnahmen je
Person und je Eintrag. Freunde erhalten zusätzlich alles, was Bekannte erhalten. Nach
einer Neuinstallation gilt: Freunde = Profil & Ziele + Trainingshistorie (30 Tage),
Bekannte = Profil & Ziele. Bei niemandem kommt etwas an, bevor eine Person hinzugefügt
ist und angenommen hat.

Unabhängig davon und unverändert: „Teilen in der Nähe“ / CruxRelay und das Nostr-Backup
(empfangene Daten sind nicht im Backup).

| Ort | Weg | Inhalt |
|---|---|---|
| Übersicht | Einstellungen → CruxCoach-Konto → „Persönliche Daten teilen“ | Schalter „Freundschaftsanfragen empfangen“, „Deine Kennung“ (npub kopieren), Anfragen, Personen, Person hinzufügen, Voreinstellungen, Verbindung |
| Personenseite | Übersicht → Person antippen | Zustand, der eine Schalter „Meine Daten mit … teilen“, Kreis, drei Kategorien, Zeitraum, „Was … erhält“ mit Schalter je Eintrag, „Von …“, Name, Beenden |
| Voreinstellung | Übersicht → „Freunde“ bzw. „Bekannte“ | drei Kategorien, Zeitraum |
| Verbindung | Übersicht → „Verbindung“ | Relay in der App, öffentliche Relays mit Zustand, wartende Nachrichten, „Jetzt abgleichen“ |
| Boulder-Detail | Boulder öffnen → Titel antippen (Info-Blatt) → unter der eigenen Notiz „Von Freunden“ | Notizen und Versuche/Begehungen von Freunden zu diesem Boulder |

Jede Person hat genau einen von vier Zuständen: **Ausstehend** (angefragt oder in
Einrichtung), **Aktiv** (du teilst), **Gestoppt** (dein Schalter ist aus), **Beendet**.

## 2. Vorbedingungen

- Zwei Geräte mit **je eigenem Nostr-Konto**. Ein Konto auf zwei Geräten wird nicht
  unterstützt.
- Konto A lokal (Onboarding „Neues Konto“). Konto B optional mit Amber. Anfrage, Annahme
  und Relay-Anmeldung signiert das Konto; im Vordergrund darf Amber fragen, im Hintergrund
  wird nur der Content-Provider-Weg benutzt. Wer ablehnt, bleibt „Ausstehend“.
- Internet. Standard-Relays: `relay.primal.net`, `relay.damus.io`,
  `nostr-pub.wellorder.net`, `nos.lol`, `nostr.oxtr.dev`, `blossom.cruxcoach.org/nostr`.
  Einzelne rote Relays sind kein Fehler, solange eines Nachrichten annimmt.
- Testdaten: Profil ausfüllen (Einstellungen → CruxCoach-Konto → Profil), einige
  Begehungen/Versuche loggen (Board-Simulator oder Logbuch-Import), an zwei Bouldern eine
  Notiz speichern. Für P-04 an **demselben** Boulder auf beiden Geräten.
- Das Nokia der 0.2.3-QA nicht verwenden.

**Upgrade von einem v1-Build:** Die Datenbankmigration (SecureDB 33 → 34) löscht alle
v1-Freigaben, -Geräte und -Snapshots; der alte native Ordner wird entfernt. Bestehende
Freundschaften aus v1 sind danach weg und müssen neu angefragt werden. Das ist gewollt.

## 3. Testfälle

**A** fragt an, **B** wird angefragt. Erwartete Texte sind die deutschen UI-Strings.

### P-01 Einstieg und Standard-Voreinstellungen (ein Gerät)

1. Einstellungen → CruxCoach-Konto → Zeile „Persönliche Daten teilen“ (Info-Symbol,
   Pfeil) antippen. Erwartet: Übersicht ohne Dialog.
2. Unter „Voreinstellungen“ zeigt „Freunde“ „Profil & Ziele, Trainingshistorie“ und
   „Bekannte“ „Profil & Ziele“. „Freunde“ öffnen. Erwartet: „Profil & Ziele“ an, ausgegraut,
   mit „Enthalten, weil Bekannte es erhalten.“; „Trainingshistorie“ an; Zeitraum „30 Tage“.
3. „Bekannte“ öffnen, „Notizen“ einschalten, zurück. „Freunde“ öffnen. Erwartet:
   „Notizen“ ist an, ausgegraut, mit „Enthalten, weil Bekannte es erhalten.“
4. Zeitraum bei Freunden auf „90 Tage“ stellen. Zurück: Zusammenfassung bei Freunden
   „Profil & Ziele, Trainingshistorie, Notizen“, bei Bekannten „Profil & Ziele, Notizen“.
5. App beenden und neu öffnen. Erwartet: die Wahl aus 3 und 4 bleibt; nichts springt auf
   die Standardwerte zurück.

### P-02 Erreichbarkeit und Kennung (zwei Geräte)

1. Auf A und B „Freundschaftsanfragen empfangen“ einschalten.
2. „Deine Kennung“: Kopier-Symbol antippen und die npub an das andere Gerät übertragen
   (Messenger, QR, vorlesen).
3. „Verbindung“ öffnen. Erwartet: „Relay in dieser App – verbunden“, mehrere öffentliche
   Relays „verbunden“ und „nimmt Nachrichten an“.

### P-03 Anfrage und Annahme (zwei Geräte)

1. A: „Person hinzufügen“ → npub von B einfügen, Name „B“, Kreis „Freunde“ →
   „Anfrage senden“. Erwartet: B unter „Personen“ mit „Ausstehend · Freunde“; die
   Personenseite sagt „Wartet darauf, dass B annimmt. Bis dahin wird nichts geteilt.“
2. Fehlerfälle: leeres Feld (Schaltfläche inaktiv), eigene npub („Das ist deine eigene
   Kennung.“), eine `nsec…` („Das ist keine öffentliche Kennung…“), Tippfehler („Das ist
   keine gültige npub.“).
3. B: Übersicht (innerhalb weniger Sekunden, sonst „Verbindung“ → „Jetzt abgleichen“).
   Erwartet: Abschnitt „Anfragen“ mit A’s gekürzter npub und „möchte persönliche Daten
   mit dir teilen…“. Unter „Personen“ steht A noch **nicht**.
4. B: Kreis „Bekannte“ lassen; der Text zeigt „Du würdest teilen: …“ gemäß B’s
   Voreinstellung. Schalter „Auch meine Daten teilen“ aus → „Du würdest nur empfangen.“
   Wieder an, „Annehmen“.
5. Erwartet auf beiden Geräten nach kurzer Zeit: die andere Person „Aktiv“.

### P-04 Genau die Vorschau kommt an (zwei Geräte)

1. A: Personenseite von B → „Was B erhält“. Jede Zeile ist ein Eintrag mit Schalter.
2. B: Personenseite von A → „Von A“. Erwartet: exakt dieselben Einträge (Profil, Versuche
   im Zeitraum, Notizen), keine Kommentare, Hallen, Videos oder Körperdaten.
3. B: den gemeinsamen Boulder öffnen → Titel antippen → unter der eigenen Notiz „Von
   Freunden“ mit „A: „…““ bzw. „A: Name · Datum · n Versuche“. B’s eigene Notiz und sein
   Logbuch bleiben unverändert.
4. A: Personenseite → Zeitraum auf „30 Tage“. Erwartet bei B: ältere Einträge
   verschwinden.

### P-05 Laufende Änderungen (zwei Geräte)

1. A: eine geteilte Notiz ändern, eine löschen, eine Begehung loggen.
2. Erwartet bei B im Vordergrund innerhalb von Sekunden bis wenigen Minuten, im
   Hintergrund spätestens nach dem nächsten 15-Minuten-Lauf: Änderung sichtbar,
   gelöschte Notiz weg, neue Begehung da. Keine erneute Annahme nötig.
3. A: Profilnamen ändern. Erwartet bei B: der Name in der Personenliste folgt (sofern B
   keinen eigenen Namen vergeben hat).

### P-06 Einschränken (zwei Geräte)

1. A: bei B die Kategorie „Notizen“ ausschalten. Erwartet: darunter „Ausnahme: weicht von
   der Voreinstellung „Freunde“ ab“. Bei B verschwinden alle Notizen von A.
2. A: „Notizen“ wieder einschalten → der Ausnahme-Hinweis verschwindet (entspricht wieder
   der Voreinstellung); bei B kommen die Notizen zurück.
3. A: in „Was B erhält“ eine einzelne Notiz ausschalten → „Ausnahme für diesen Eintrag“.
   Erwartet: nur diese Notiz verschwindet bei B.
4. A: bei ausgeschalteter Kategorie eine einzelne Notiz einschalten. Erwartet: nur diese
   eine Notiz kommt bei B an.

### P-07 Der eine Schalter (zwei Geräte)

1. B: Personenseite von A → „Meine Daten mit A teilen“ aus. Erwartet: A steht bei B auf
   „Gestoppt“; „Was A erhält“ zeigt „Nichts – das Teilen mit dieser Person ist
   ausgeschaltet.“
2. Erwartet bei A: „Von B“ ist leer („B teilt noch nichts mit dir.“), A’s Daten bei B
   bleiben sichtbar (die Gegenrichtung ist unberührt).
3. Schalter wieder an: B’s Daten kommen bei A wieder an.

### P-08 Voreinstellung ändern (zwei Geräte)

1. A: Voreinstellung „Freunde“ → „Trainingshistorie“ aus. Erwartet: bei B verschwinden
   A’s Trainingseinträge, außer eine Personen-Ausnahme erlaubt sie.
2. „Bekannte“ → „Profil & Ziele“ an. Erwartet: auch Freunde (B) erhalten das Profil.

### P-09 Beenden und neu anfragen (zwei Geräte)

1. B: Personenseite von A → „Freundschaft beenden“ → **eine** Bestätigung „Freundschaft
   mit A beenden?“ → „Freundschaft beenden“.
2. Erwartet bei B sofort: A „Beendet“, „Von A“ leer. Bei A kurz danach: B „Beendet“, alle
   Daten von B gelöscht; B’s Kopien von A’s Daten sind gelöscht.
3. Bei „Beendet“ gibt es nur „Neue Anfrage senden“ und „Aus der Liste entfernen“.
4. A: „Neue Anfrage senden“. B sieht wieder eine Anfrage und muss neu annehmen; vorher
   kommt nichts an.

### P-10 Offline und Relay-Ausfall (zwei Geräte)

1. B in den Flugmodus. A ändert mehrere Notizen. B wieder online, App öffnen. Erwartet:
   B hat den aktuellen Stand, nichts doppelt, nichts Veraltetes.
2. A in den Flugmodus, eine Notiz ändern. „Verbindung“ zeigt „1 Nachricht wartet auf ein
   Relay“. Noch offline bei B die Kategorie „Notizen“ ausschalten oder die Freundschaft
   beenden, dann online gehen. Erwartet bei B: die offline geänderte Notiz kommt **nie**
   an.
3. App auf A während einer laufenden Übertragung beenden (Task wischen) und neu öffnen.
   Erwartet: kein Datenverlust, B erreicht denselben Stand.

### P-11 Ablehnen und Zurückziehen (zwei Geräte)

1. A fragt B an, B „Ablehnen“. Erwartet: die Anfrage verschwindet bei B; A bleibt
   „Ausstehend“, es kommt nichts an.
2. A: Personenseite → „Anfrage zurückziehen“ → Bestätigung. Erwartet: B „Beendet“ bei A;
   „Aus der Liste entfernen“ entfernt den Eintrag.

### P-12 Koexistenz und Upgrade (ein Gerät)

1. „Teilen in der Nähe“ und Board-Verbindungsblatt unverändert; keine Wechselwirkung.
2. Backup-Schalter bleibt, wie er war; empfangene Daten sind nicht im Backup.
3. Kontowechsel: danach zeigt „Persönliche Daten teilen“ den Zustand des neuen Kontos.
4. Upgrade von einem v1-Build (falls vorhanden): App startet, v1-Freundschaften sind weg,
   keine Fehlermeldung, Voreinstellungen mit den Standardwerten (die alte
   v1-Kreis-Einstellung wird nicht übernommen).

## 4. Bekannt offen

- **Keine Geräteabnahme** (Amber-Hintergrund, Doze, Prozess-Tod auf Android, arm64-Lauf).
- **Relay-Liste** ist in der UI nur sichtbar, nicht editierbar (sechs Standard-Relays plus
  aus v1 übernommene Einstellungen).
- **Boulder-Join** nur im Info-Blatt des Boulder-Details; Browser-Liste und Statistik
  zeigen Daten von Freunden noch nicht. Notizen auf der Personenseite zeigen den Text,
  nicht den Boulder-Namen (die Notiz enthält nur die Boulder-ID).
- **Grenzen:** 32 Personen, 1.000 Einträge / 1 MiB pro Richtung, 8 KiB pro Eintrag.
- Freigaben sind **Vertrauen, kein Kopierschutz**: Was jemand schon gesehen und
  festgehalten hat, lässt sich nicht zurückholen.

## 5. Befunde melden

Pro Befund: Testfall-ID (P-xx), Gerät/Android-Version, Konto-Typ (lokal/Amber),
Zustand der Seite „Verbindung“ (Relays, wartende Nachrichten), ob die Gegenstelle online
war, und ein Logcat-Ausschnitt. Keine npubs echter Personen, keine Schlüssel und keine
geteilten Inhalte in Berichte schreiben.
