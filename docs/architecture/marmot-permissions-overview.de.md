# Private Daten mit Freunden aktuell teilen

Stand: lokaler Produktdurchgang auf `feat/marmot-permissions-v023`, ausgehend vom
linearisierten Dateibaum `4fd3ed337a3cc665cc5d248d5d472ca8e652cee4` am 11. September
2026. Feature-Code, keine veröffentlichte App. Vollständige CI und tatsächliche
Android-/Amber-/Keystore-Geräteabnahme sind gesonderte Nachweise.

**Freundschaft anfragen → annehmen und eigene Daten auswählen → fertig.**
Anfrage und Annahme bestätigen die Freundschaft beidseitig und das allgemeine
Interesse am fortlaufenden Empfang. Jede Person bestimmt ausschließlich, welche
**eigenen** Daten sie preisgibt. Auch keine eigene Freigabe ist eine gültige Wahl.
Später bewusst ergänzte Kategorien und normale Änderungen benötigen innerhalb
dieser aktiven Freundschaft keine weitere Empfängerannahme.

Empfangene Daten erscheinen als zusammenhängender, schreibgeschützter Bestand mit
Herkunft und Zeitstand. Dafür sind weder CruxCoach-Backend noch CruxCoach-Relay nötig.
Alte Einmalfreigaben und das vorherige Kategorie-Abonnement begründen diese weiter
reichende Freundschaft **nicht**: Eine neue ausdrückliche Anfrage und Annahme sind
notwendig. Eine Nostr-Follow-Liste ist kein Freundschaftsnachweis.

## Quartz und Amethyst: warum diese Integration?

Quartz 1.05.1 übernimmt unsere Nostr-Kontosignaturen und Amber-Anbindung. Marmot
selbst läuft im gepinnten nativen MDK-/OpenMLS-Host. Amethyst unterstützt Marmot
tatsächlich, verwendet dafür aber eine eigene Kotlin-MLS-Implementierung in Quartz.
Das veröffentlichte Quartz 1.14.0 und die neueren Protokollanpassungen auf Amethysts
Hauptzweig sind unterschiedliche Stände. Sie sind kein austauschbarer MDK-Wrapper.

Der kritische Vergleich entscheidet für Beibehalten mit gezielter Bereinigung:
Ein gemeinsamer Quartz-Signierpfad ersetzt drei bisher getrennte Aufrufwege.
Offizielle MDK-Android-Bindings gibt es ebenfalls, einschließlich externem Signieren;
ihr vollständiger App-Laufzeitkern liefert jedoch nicht unmittelbar unsere atomare
Freigabe-/Quellenbindung und gezielte Datenbereinigung. Ein Umstieg bleibt möglich,
wenn eine veröffentlichte Variante die gleichen Prüfungen und eine sichere
Migration nachweist. Die zusätzliche native Buildlast ist ein echtes Gegenargument,
kein übersehener Nachteil. [Belege und Vergleich](marmot-dependencies.md).

Der Eigentümer hat für diesen folgenden Durchgang normalen Feature-Push und
APKTrack-Feature-Veröffentlichung autorisiert. Tatsächliche CI-/Receipt-Nachweise
werden gesondert erfasst; damit sind weder Main-Merge noch Stable autorisiert.

## Was tatsächlich geteilt werden kann

| Auswahl | Tatsächliche Quelle und Inhalte | Nicht enthalten |
|---|---|---|
| Profil und Ziele | Aktives App-Profil: Name, Klettergrade, Erfahrung, Trainingshäufigkeit, Ausrüstung, Ziele | Alter, Gewicht, Körpermaße, Verletzungen, Messwerte |
| Trainingsverlauf | Tatsächliche Begehungen und Versuche: Route, Board, Winkel, Datum, Versuche, Ergebnis und gespeicherte Schwierigkeit | Private Kommentare, Standort, externe Importkennungen, Synchronisationsfelder |
| Private Notizen | Vorhandene Notizen zu Routen, einschließlich späterer Bearbeitung und Löschung | Frühere Textversionen; Notizen gehören nicht automatisch zum Trainingsverlauf |

Das sind Adapter für die vorhandenen App-Repositories und Schreibpfade. Die
Vorschau, die erste Übernahme und spätere Änderungen lesen dieselben Originaldaten.
Geplante Trainingseinheiten werden nicht als absolvierte Trainings ausgegeben.
Gesundheitsdaten und private Videos werden hier nicht übertragen. Eine öffentliche
Blossom-URL wäre keine Verschlüsselung privater Medien.

Beim Trainingsverlauf entscheidest du zwischen **ab heute**, **30 Tagen** oder
**90 Tagen** Vorgeschichte, jeweils einschließlich späterer Einträge. Die konkrete
UTC-Datumsgrenze wird angezeigt. Profil und Notizen bedeuten den aktuellen und
zukünftigen Stand; frühere Versionen werden nicht nachgeliefert. Ein neuer Freund
bekommt keine alte Freigabe oder komplette Vergangenheit automatisch.

## Der Produktablauf

1. Beide Personen verwenden ihr eigenes Konto und ein autorisiertes Gerät.
   Der Empfänger aktiviert die Auffindbarkeit bewusst. Eine Anfrage des Senders
   aktiviert seine Auffindbarkeit und bereitet die private Verbindung vor. Die App
   bündelt Schlüsselsuche und Verbindungsannahme; sie ersetzen keine Freundschaft.
   Vollständige öffentliche Kontokennungen bleiben zur Identitätsprüfung sichtbar.
2. A wählt B beziehungsweise aktuelle Mitglieder eines Kreises, seine eigenen
   Datenbereiche und Trainingsvorgeschichte. Die Vorschau liest die tatsächlich
   ausgewählten Daten unter Berücksichtigung bestehender Objektsperren.
3. B sieht die Anfrage und As angebotenen Umfang. B bestätigt die Freundschaft und
   wählt ausschließlich **Bs eigene ausgehende** Daten, optional keine. Bei einem
   Server bestätigt jede Seite dessen eigenständige Rolle ausdrücklich.
4. Beide Apps übernehmen die jeweils erlaubten aktuellen Daten und danach
   Änderungen und Löschungen automatisch. Fremddaten überschreiben weder eigene
   Profile und Notizen noch Trainingsstatistiken oder Sicherungen.
5. Jede Person kann ihre eigene Auswahl ändern. Eine Erweiterung wird von ihrem
   Konto signiert; der Freund braucht keinen neuen Dialog. Entfernte Kategorien,
   Objekte oder Vorgeschichte werden in der normalen Empfänger-App nach dem
   authentisierten Abgleich tatsächlich gelöscht. Andere erlaubte Daten bleiben.
6. „Eigene Daten stoppen“ leert nur die eigene ausgehende Auswahl. Eine Abstufung
   ändert ebenfalls nur die eigenen ausgehenden Rechte. Persönliche Ausnahmen
   werden ausdrücklich angeboten: behalten oder gemeinsam mit dem Kreiswechsel
   entfernen. Die Auswahl der Gegenseite verändert sich dadurch nicht.
7. **Freundschaft beenden wirkt immer in beide Richtungen.** Die auslösende App
   löscht sofort alle aus dieser Freundschaft erhaltenen Daten und stoppt Exporte
   und wartende Sendungen. Die Gegenstelle löscht beim Empfang des authentisierten
   Endes ohne neue Zustimmung. Eine neue Freundschaft hat eine neue Generation;
   alte Nachrichten und Annahmen können sie nicht wiederbeleben.

Eine aktive Freundschaft benötigt keine regelmäßige neue Zustimmung. Begrenzte
Aufbewahrung, technische Geräteprüfungen und sichere Wiederherstellung gelten
weiterhin; eine unterbrochene Internetverbindung allein beendet keine Freundschaft.

## Warum getrennte Verbindungen pro Freund?

| Variante | Entscheidung |
|---|---|
| Paarweiser Versand | Nutzt die tatsächlich integrierten authentisierten Sitzungen. Jede Person kann nur ihren ausgewählten Inhalt entschlüsseln. Mehrere Freunde verursachen mehrere verschlüsselte Übertragungen. |
| Gruppe pro identischem Umfang | Könnte Nachrichten sparen, benötigt aber identische ausgehende Rechte, Vorgeschichte und Geräte sowie sichere Gruppenteilung bei Änderungen. Das ist in der qualifizierten Anbindung nicht vorhanden. |
| Ein Gruppenchat für alle Freunde | Eignet sich bei verschiedenen Rechten nicht: Bereits an alle entschlüsselbar gesendete Felder lassen sich nicht durch Ausblenden schützen. |

Deshalb verwendet die App paarweise Verbindungen. Sie fasst Änderungen zusammen,
sendet Deltas zum zuletzt vom Empfänger bestätigten Stand und begrenzt Arbeit und
Warteschlangen. Unbekannte Formate und unprüfbare Zustände bleiben gesperrt.

## Was „aktuell“ bedeutet

Die Automatik hängt am **App-Lebenszyklus, Netzwerk-Reconnect und tatsächlichen
Datenänderungen**, unabhängig vom geöffneten Sharing-Screen. Schreibvorgänge lösen
erst nach einer abgeschlossenen Datenbanktransaktion Arbeit aus. Kurze Änderungs-
serien werden zusammengefasst. Bei aktiver App läuft zusätzlich ungefähr alle
30 Sekunden ein Abgleich. Android WorkManager übernimmt netzgebundene Arbeit und
einen periodischen Rückhalt von mindestens 15 Minuten.

Android kann Arbeit wegen Doze, fehlendem Netz, beendeter beziehungsweise
zwangsbeendeter App oder ausstehender Signer-Genehmigung verzögern. Es gibt daher
keine Zusage „immer in Echtzeit“. Sichtbare Zustände sind unter anderem:

- **Stand von …**: Zeitpunkt des zuletzt vollständig übernommenen Eigentümerstands.
- **Änderungen ausstehend**: Es gibt noch keine passende Empfängerbestätigung.
- **Zuletzt bestätigt …**: Die Empfänger-App hat genau diese Übernahme bestätigt.
- **Offline / erneut versuchen**: Die Arbeit bleibt dauerhaft vorgemerkt.
- **App für Genehmigung öffnen**: Eine erforderliche Hintergrund-Signer-Anfrage
  wurde nicht bereits erlaubt; es öffnet sich kein unerwarteter Hintergrunddialog.
- **Stand abgelaufen**: Seit sieben Tagen kam keine frische Eigentümerbestätigung;
  die Inhalte werden lokal gelöscht. Eine Vollübernahme des aktuell erlaubten Bestands kann ohne neue Freundschaftsannahme folgen.
- **Freigabe beendet / Verbindung erneuern**: Keine automatische Wiederbelebung.

Bereits erlaubte Amber-Anfragen können über dessen Content Provider automatisch
laufen. Die Anbindung verwendet dafür die echte API der gepinnten Quartz-Version
und keinen Dialog-Fallback. Eine unveränderte Freigabe bekommt etwa täglich einen
kleinen Aktualitätsnachweis unter derselben Zustimmung. Tatsächliches Verhalten
auf Android/Amber muss weiterhin auf Geräten geprüft werden.

Ein Relay-Ack ist **keine Empfängerbestätigung**. Auch ein erfolgreicher Abgleich
beweist nicht, dass sämtliche Relays alle Nachrichten oder Widerrufe gezeigt haben.

## Regeln, Identitäten und Änderungen

Kontoschlüssel, autorisiertes Permission-Gerät und tatsächlicher Marmot-/MLS-
Teilnehmer bleiben getrennte Identitäten. Signierte Nachweise binden sie an die
reale Sitzung. Gruppenmitgliedschaft allein gibt keine Daten frei. Die vorhandene
signierte Regelhistorie, Objektausnahmen, Geräteberechtigung, beide ursprünglichen
Freundschaftsbestätigungen und die eigene Auswahl müssen zusammenpassen. Die
bisherige Kategorie-Zustimmung gilt weiterhin nur für alte Einmalfreigaben.

Die Rechteprüfung findet beim Lesen der Quelle, unmittelbar vor dem tatsächlichen
Versand, beim Empfang und beim Öffnen statt. Der Eigentümer bleibt für seine Daten
maßgeblich. Empfangszeitpunkte lösen keine Mehrgeräte-Konflikte. Ein Geräte-,
Gruppen-, Epoch- oder Autoritätswechsel kann alte Freigaben schließen und verlangt
eine bewusste neue Verbindung/Freigabe. Die bestehende Kontowechsel-Funktion startet
die App neu; bis dahin verweigern Objekte des alten Kontos den Zugriff.

Ein Angebot benennt Eigentümer, Empfänger, Geräte, Rolle, Freigabe-ID, Datenquelle,
Umfang und Auswahlrevision. Beide ursprünglichen Kontosignaturen binden die
Freundschaft. Auch eine erste Datenantwort vor der separat zugestellten Annahme
kann nur anhand dieser beiden Nachweise zur bereits gestellten Anfrage gehören. Vollübernahmen und Änderungen tragen Version, Basisversion,
Quellrevision, Inhaltsbindung und Seitennummern. Empfangene Seiten werden erst
nach vollständiger Prüfung gemeinsam sichtbar. Fehlende Basen lösen eine neue
Vollübernahme aus; doppelte oder ungeordnet empfangene Seiten überschreiben keinen
neueren Bestand. Eine fremde Person kann auch durch eine kollidierende Freigabe-ID
keine andere Freigabe verändern.

Nach Neustart bleiben Quelle, Warteschlange, Seiten und Bestätigungsstand erhalten.
Veraltete wartende Daten verlieren beim Widerruf ihre Exportberechtigung. Bestätigte
native Inbox-Payloads können entfernt werden, während Herkunft und Sitzungsbindung
für spätere Rücknahmen begrenzt prüfbar bleiben. Sicherheitsregeln werden bei
Speicherknappheit nicht gelockert.

## Grenzen und Wiederherstellung

Pro Konto sind derzeit 16 aktive Freundschaften und 512 insgesamt aufbewahrte Richtungs-/Generationsdatensätze vorgesehen.
Ein aktueller Umfang darf höchstens 1.000 Datensätze beziehungsweise 1 MiB enthalten,
ein Datensatz höchstens 8 KiB. Größere Umfänge werden vollständig abgelehnt und als
Limit angezeigt; es wird keine stillschweigend unvollständige Erstübernahme gesendet.
Nachrichten und Hintergrundarbeit sind ebenfalls begrenzt. Einzelne Datensätze
brauchen keine neue Vollübertragung des gesamten Bestands pro Tastendruck.

Ungewöhnliche Uhrsprünge sperren das Teilen. Die bewusste Uhrwiederherstellung
beendet zuerst Freigaben, bewahrt deren signierte Ende-Nachrichten für die spätere
Zustellung und bereinigt native Restinhalte nach dem sicheren Entsperren. Native Speicher-Recovery zieht Rechte zurück und
archiviert den alten verschlüsselten Zustand; neue Sitzungen und Zustimmungen sind
nötig. Aufräumen erfolgt, wenn das jeweilige Konto beziehungsweise die App ausgeführt
wird; bei gestoppter App gibt es keine physische Löschfrist auf die Sekunde.
Das ist kein unbeschränkter Langzeitspeicher und keine Lösung für einen
vollständig zurückgerollten oder kompromittierten Rechner.

Schema 33 ergänzt die bisherigen Daten um Änderungsrevisionen und getrennte
Freundschaftstabellen. Migration 32→33 löscht nur die bisherigen fortlaufenden
Fremdkopien und Zustimmungen; sie deutet sie nicht in Freundschaften um. Originale,
alte Snapshot-Zustimmungen, Widerrufe und Uhrschutz bleiben erhalten.

Der normale Client löscht beim Entzug tatsächliche empfangene Datensätze sowie
unvollständige Übernahmen, native Klartext-Inbox und überholte Anwendungs-Outbox.
Es gibt für diese Fremddaten keinen Suchindex, Thumbnailbestand oder Binärtransfer.
Minimale Generationstombstones und Herkunftsnachweise verhindern Wiederbelebung.
Native verschlüsselte MLS-Transkripte können zur sicheren Zustandsverarbeitung
verbleiben; das ist kein weiterhin lesbarer Datenbestand. Strukturierte App-Backups
enthalten keine Fremdkopien. Unterstützte Permission-Recovery leert Fremdinhalte
und vorgemerkte Anfragen; alte IDs bleiben geschlossen.

Der Status unterscheidet **lokal beendet**, **Gegenstelle noch ausstehend** und
**Bereinigung bestätigt**. Letzteres verlangt die Bestätigung der Empfänger-App,
nicht bloß Relay-OK. Offline kann die Gegenstelle ihren alten Stand bis zum
Abgleich beziehungsweise der lokalen Aufbewahrungsgrenze behalten. Eine solche
Bestätigung belegt kooperatives Client-Verhalten, keine Sicherheit gegen eine
veränderte App. Bereits exportierte Kopien und Screenshots sind nicht rückholbar.
Physische Flash-/SQLite-Löschung und beliebig zurückgerollte komplette Geräteabbilder
werden nicht garantiert.

Relays sehen weiterhin Verbindungsadressen, Zeiten und Größen. Auffindbarkeit zeigt
öffentliche Identität und Relay-Auswahl; private Nutzdaten werden nicht im Klartext
publiziert. Herkunftsnachweise konsumierter Nachrichten bleiben im nativen Transport acht
Tage unter festen Speichergrenzen erhalten. Payloadfreie geschlossene Generationen bleiben
begrenzt erhalten, damit Replay nichts wiederherstellt.

## Server und die sechs Relays

Ein eigener Server ist nur ein optionaler Datenanbieter/-empfänger mit eigener
isolierter Identität und expliziten Freigaben. Er ist weder zentraler Validator
noch Voraussetzung für Nutzer–Nutzer. Der lokale synthetische Testendpunkt führt
dieselben Freundschaftsabläufe aus; er installiert keinen Produktionsdienst.

| Konfigurierbarer Standard-Relay | Begrenzte Beobachtung aus dem vorherigen Integrationsdurchgang am 11. September 2026 |
|---|---|
| `wss://relay.primal.net` | Benötigte Transporttypen angenommen und wieder gelesen |
| `wss://relay.damus.io` | Einschränkungen bei Gruppen, Einladungen und teilweise Schlüsseldaten |
| `wss://nostr-pub.wellorder.net` | Benötigte Transporttypen angenommen und wieder gelesen |
| `wss://nos.lol` | Benötigte Transporttypen angenommen und wieder gelesen |
| `wss://nostr.oxtr.dev` | Benötigte Transporttypen angenommen und wieder gelesen |
| `wss://blossom.cruxcoach.org/nostr` | Benötigte Transporttypen abgelehnt |

Im neuen Produktdurchgang wurden ausschließlich 30 öffentliche Leseabfragen mit
frischen synthetischen Filtern wiederholt, ohne Treffer und ohne Veröffentlichung.
Die vier nutzbaren Relays beantworteten alle fünf Typen; Damus verlangte für
Einladungen Authentifizierung und war bei Gruppennachrichten nicht erreichbar;
Blossom lehnte erneut alle Typen ab. Das ist kein neuer öffentlicher Nachweis
fortlaufender Zustellung.

Diese Momentaufnahme wird nicht als neue Verfügbarkeitsgarantie ausgegeben. Die
App zeigt tatsächliche Ablehnung, Limits, Auth-Anforderungen und Ausfälle. Es gibt
keinen heimlichen Ersatzpool und keine automatische Auth-Offenlegung. Relay-Hinweise
ersetzen keinen authentisierten Gruppenzustand. Der lokale Ausfalltest ordnet den
CruxCoach-Relay ausdrücklich einem unerreichbaren Test-Port zu; die übrigen lokalen
Relays liefern Duplikate und ungeordnete Nachrichten.

## Nachweise richtig einordnen

Der neue Produktdurchgang prüft reale Quell-Repositories, Schema-Migration,
beidseitige Freundschaft, unabhängige ausgehende Auswahlen, Vollübernahme, Änderung,
Löschung, Replay, Widerruf, Quellen-/Gerätewechsel, Offline-Neustart und Arbeit ohne
CruxCoach-Backend. `scripts/marmot_continuous_e2e.py` betreibt einen Eigentümer und
zwei unabhängige JVM-Teilnehmer mit echter nativer MDK-Kryptographie, zusätzlich
mit einem Server als gewöhnlichem Eigentümer sowie einem Server als Empfänger.
Der abschließende Lauf v4 hat alle drei Pfade bestanden, einschließlich tatsächlich
verschlüsselter wartender Inhalte beim Relay-Ausfall und gemessener Speicherlöschung.
Die beiden Drei-Teilnehmer-Läufe bestanden jeweils 22 benannte Prüfschritte.
Zusätzlich sind 15 native Transport-/Relay-/Speichertests und alle acht bisherigen
nativen Snapshot-Integrationstests grün. Die fokussierten Quell-, Migrations-,
Backup-, Recovery-, Signer-, UI- und Automatikprüfungen sowie behobene Zwischenfehler
sind im Run-Verzeichnis `friendship-sharing/verification.md` einzeln belegt.

Fokussierte Host-JVM/native Prüfungen belegen keine vollständige CI, keinen
Android-arm64-Lauf und keine echte Amber-/Keystore-/Doze-Geräteabnahme. Andere
Marmot-Clients benötigen die CruxCoach-Anwendungskonventionen für diesen Austausch.
Der native Build ist quellen- und versionsgebunden; bitgleiche Reproduktion setzt
wegen OpenSSL-Metadaten auch gleiche Build-Pfade voraus.

Weiterlesen: [technische Architektur und Primärquellen](marmot-permissions.md),
[Native-Build und Testendpunkt](../../native/marmot/README.md),
[Dokumentationsindex](../README.md).
