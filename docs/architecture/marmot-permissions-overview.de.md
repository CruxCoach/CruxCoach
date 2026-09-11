# Permission-System: Funktionen und Architekturentscheidungen

Stand: 11. September 2026. Beschriebene Implementierung: `0d25e4f90` auf
`feat/marmot-permissions-v023`, einschließlich des integrierten 0.2.3-Release-Quellstands.
Diese Übersicht beschreibt Feature-Code, keine veröffentlichte App. Vollständige CI
und reale Android-/Amber-/Keystore-Gerätetests stehen zu diesem Stand noch aus.

Das System bestimmt, **wer welche persönlichen Daten unter welchen Bedingungen
bekommen und innerhalb der App weiter öffnen darf**. Die beteiligten Konten und
Geräte prüfen die Regeln selbst. Nutzerfreigaben benötigen keinen zentralen
CruxCoach-Server.

Technische Details, Abhängigkeiten und Sicherheitsgrenzen stehen in der
[Architekturbeschreibung](marmot-permissions.md). Diese Übersicht erklärt deren
Entscheidungen und unterscheidet das allgemeine Regelmodell vom bereits
integrierten Austauschumfang.

## 1. Was Nutzer damit machen können

Drei Entscheidungen sind getrennt: **eine Person verbinden, Datenkategorien
freigeben und einen konkreten Inhalt austauschen**. Eine angenommene Einladung
allein gibt keinen Zugriff auf persönliche Inhalte.

| Funktion | Bedeutung |
|---|---|
| Person hinzufügen | Empfänger über öffentlichen Nostr-Schlüssel beziehungsweise `npub` auswählen. |
| Kreis zuordnen | Eine Person als Freunde, Bekannte oder Alle anderen Nutzer einordnen. |
| Kategorien freigeben | Regeln für Profil und Ziele, Trainingsverlauf, Videos, Gesundheitsdaten und private Notizen festlegen. |
| Persönliche Ausnahme | Einer bestimmten Person eine Kategorie ausdrücklich erlauben oder sperren. |
| Einzelne Ausnahme | Eine Regel für ein bestimmtes Objekt innerhalb einer Kategorie setzen. |
| Zustimmung einholen | Der Empfänger nimmt angebotene Kategorien ausdrücklich an. |
| Inhalt senden | Eine konkrete Textkopie anbieten, die der Empfänger nochmals ausdrücklich annimmt. |
| Ablauf und Widerruf | Freigaben zeitlich begrenzen oder zurückziehen. |
| Geräte verwalten | Eigene Geräte mit unterschiedlichen Verwaltungsrechten ausstatten oder sperren. |
| Sichern und wiederherstellen | Verschlüsselte Sicherungen nutzen, ohne alte Zugriffsrechte automatisch zu reaktivieren. |

**Aktueller Austauschumfang:** private Textnotizen als unveränderliche Snapshots
zwischen zwei Teilnehmern. Ein Snapshot ist eine festgehaltene Kopie; Änderungen
am Original aktualisieren sie nicht automatisch. Technische Grenze: **32 KiB
UTF-8 und höchstens sieben Tage Gültigkeit**. Die Android-Oberfläche erstellt
Angebote mit **24 Stunden Laufzeit**, kürzer bei früher ablaufender Freigabe.

Die fünf Kategorien gehören bereits zum Regelmodell. Automatisches Teilen des
gesamten Trainingsverlaufs, Videoübertragung, beliebige Dateien und
Gruppenaustausch sind durch diesen Snapshot-Pfad noch nicht umgesetzt.

## 2. Wie Regeln zusammenwirken

Jede eingetragene Person gehört genau einem Kreis an. Eine Kategorie, die für
einen weiteren Kreis erlaubt ist, gilt grundsätzlich auch für engere Kreise:

`Alle anderen Nutzer → Bekannte → Freunde`

„Alle anderen Nutzer“ ist eine Grundeinstellung für entsprechend eingeordnete
Personen. Sie veröffentlicht sensible Inhalte nicht für sämtliche Nostr-Nutzer.

| Vorrang | Regel |
|---|---|
| 1 | Ausnahme für das konkrete Objekt und seine Kategorie |
| 2 | Persönliche Regel für die Kategorie |
| 3 | Grundeinstellung des Kreises einschließlich Vererbung |
| 4 | Ohne passende Erlaubnis: gesperrt |

Eine ausdrücklich erlaubte Objektausnahme kann eine allgemeinere Kategoriesperre
übersteuern. Die Oberfläche zeigt den Grund einer Entscheidung an.

Beispiel: Du erlaubst Freunden private Notizen, sperrst die Kategorie aber für
Alex. Dann gilt für Alex die persönliche Sperre. Erlaubst du Alex ausdrücklich
eine bestimmte Notiz, kann diese Objektausnahme greifen. **Zusätzlich müssen
Zustimmung, Geräteberechtigung, Gültigkeit und aktueller Sitzungszustand passen.**
Ein Widerruf lässt sich nicht durch eine solche Ausnahme umgehen.

## 3. Der Ablauf einer Freigabe

1. Beide Personen verwenden ihr eigenes Konto und ein autorisiertes Gerät.
2. Sie aktivieren ausdrücklich die Auffindbarkeit über die konfigurierten Relays.
3. Eine Person fügt den öffentlichen Schlüssel der anderen hinzu und lädt sie
   ein. Die Gegenseite nimmt die Verbindung an.
4. Der Eigentümer bietet Kategorien an. Der Empfänger bestätigt die gewünschten
   Kategorien. Eine Erweiterung benötigt passende neue Zustimmung.
5. Der Eigentümer bietet eine konkrete Textnotiz an. Der Empfänger nimmt genau
   dieses Angebot an.
6. Die App überträgt den Inhalt verschlüsselt. Die Empfänger-App prüft und
   speichert ihn verschlüsselt, bevor sie den Empfang bestätigt.

Der Inhaltsaustausch folgt `Angebot → Zustimmung → Inhalt → Empfangsbestätigung`.
„Vorgemerkt“, „vom Relay angenommen“ und „vom Empfänger bestätigt“ sind
unterschiedliche Zustände. Die letzte Bestätigung bedeutet keine menschliche
Lesebestätigung und ist kein Beweis gegen einen böswilligen Empfänger.

Geöffnete Freigabeansichten synchronisieren alle **15 Sekunden**. Beim Verlassen
endet das regelmäßige Abfragen. Ein durchgehend laufender Hintergrunddienst ist
nicht Teil dieser Umsetzung.

## 4. Serverunabhängigkeit und Relays

Der Transportweg lautet:

`App A → Ende-zu-Ende-verschlüsselte Marmot-Nachrichten → Nostr-Relays → App B`

Das ist dezentrale Kommunikation über Relays, keine direkte Netzwerkverbindung
zwischen zwei Handys. Relays transportieren verschlüsselte Inhalte, entscheiden
aber nicht über deren Zugriffsrechte.

| Austausch | Rolle des eigenen Servers |
|---|---|
| Nutzer A teilt mit Nutzer B | Keine erforderlich |
| Server stellt einem Nutzer Daten bereit | Eigenständiger Absender mit eigener Identität |
| Nutzer teilt Daten mit dem Server | Explizit berechtigter Empfänger |
| Server betreibt ein Relay | Optionaler Transportweg |

Die Kennzeichnung `SERVER` verleiht keine Sonderrechte und keine automatische
Gegenfreigabe. Der lokale Serveradapter wurde mit Testidentitäten geprüft.
Produktiver Betrieb und Schlüsselverwahrung sind noch nicht eingerichtet.

Alle sechs aus Blossom-Sync übernommenen Relays sind konfigurierbare Defaults.
Die begrenzten Integrationsproben am 11. September 2026 ergaben:

| Relay | Beobachtetes Ergebnis |
|---|---|
| `wss://relay.primal.net` | Alle benötigten Nachrichtentypen funktionierten |
| `wss://nostr-pub.wellorder.net` | Alle benötigten Nachrichtentypen funktionierten |
| `wss://nos.lol` | Alle benötigten Nachrichtentypen funktionierten |
| `wss://nostr.oxtr.dev` | Alle benötigten Nachrichtentypen funktionierten |
| `wss://relay.damus.io` | Einschränkungen bei Gruppennachrichten, Einladungen und teilweise Schlüsseldaten |
| `wss://blossom.cruxcoach.org/nostr` | Die benötigten Nachrichtentypen wurden abgelehnt |

Das sind Momentaufnahmen, keine Verfügbarkeitsgarantie. Die App zeigt Fehler und
Authentifizierungsanforderungen an und ergänzt keine unbekannten Ersatzrelays.
Beide Teilnehmer brauchen einen nutzbaren gemeinsamen Transportweg. Hinweise zur
Auffindbarkeit dürfen den authentisierten Relay-Zustand einer Gruppe nicht ersetzen.

Redundante Übertragung, Duplikaterkennung, dauerhafte Warteschlangen und
Wiederholungen reduzieren Ausfallabhängigkeiten. Bei vollständigem Ausfall bleibt
der Versand ausstehend. Mehrere Relays garantieren jedoch nicht, dass niemand
Nachrichten oder Zustandsänderungen zurückhält.

## 5. Technische Architektur

| Schicht | Verantwortung |
|---|---|
| Android-Oberfläche | Regeln, Einladungen, Zustimmungen, Inhalte und Fehlerzustände bedienen; Englisch und Deutsch. |
| Permission-Kern in Kotlin | Aus signierten Regeln und Zustimmungen den aktuell erlaubten Zugriff berechnen. |
| Verschlüsselter Datenspeicher | Inhalte, Freigabeverläufe, Widerrufe und Wiederherstellungszustände verwahren. |
| Austauschdienst | Angebote, Zustimmung, Versand, Empfang und Widerruf zusammenführen. |
| Native Rust-Anbindung über JNI | Kotlin mit Marmot verbinden; dauerhafte Ein- und Ausgangswarteschlangen verwalten. |
| MDK/OpenMLS | Verschlüsselte Sitzungen, authentische Teilnehmerzuordnung und kryptografische Zustandswechsel. |
| Nostr-Transport | Auffindbarkeit, Einladungen und verschlüsselte Nachrichten über die gewählten Relays. |

Die Regeln liegen als **signierte Änderungshistorien** vor. Daraus berechnet die
App den gültigen Zustand. Ein veränderter Anzeige-Cache darf keine Rechte erzeugen.
Lokale Inhalte sind durch den vorhandenen AES-GCM-Tresor geschützt; Datenbanken
verwenden SQLCipher. Die native Datenbank liegt im privaten Android-Bereich.
Kontosignaturen erfolgen über die bestehende Signer-Anbindung, etwa Amber;
der private Kontoschlüssel wird nicht an JNI exportiert.

Drei Identitäten bleiben getrennt: **Nostr-Konto**, **autorisiertes
Permission-Gerät** und **kryptografischer Marmot-Sitzungsteilnehmer**. Signierte
Nachweise binden sie aneinander. Sitzungsmitgliedschaft allein erlaubt keinen
Inhaltszugriff. Der native Adapter verwendet tatsächliche MDK-Zustände und eine
lokale Erweiterung; deren Quellen und Abhängigkeiten sind versions- und hashgebunden.

Das System prüft Berechtigungen beim Lesen des Originals, unmittelbar vor der
Netzwerkveröffentlichung, beim Empfang und beim späteren Öffnen. Ein angebotener
Inhalt ist an Empfänger, Gerät, Kategorie, Datenversion, Sitzung, Inhaltshash und
Ablauf gebunden. Unbekannte Formate, falsche Signaturen oder widersprüchliche
Zustände bleiben gesperrt.

## 6. Geräte, Neustarts und Wiederherstellung

| Eigene Geräterolle | Verwaltungsrechte |
|---|---|
| Hauptgerät | Volle Verwaltung einschließlich Geräteaufnahme und grundlegender Wiederherstellung |
| Vertrauenswürdiges Gerät | Freigaben ändern, aber nicht die Geräteliste verwalten |
| Nur lesendes Gerät | Berechtigungszustand einsehen, nichts ändern |
| Gesperrtes Gerät | Keine Autorität mehr |

Das Verwaltungsmodell bedeutet noch keinen nahtlosen Austausch über beliebig
viele Geräte. Der aktuelle Transport unterstützt ein Paar aus jeweils einem
Konto und Gerät. Gruppen-, Sitzungs- oder Gerätewechsel schließen bestehende
Snapshot-Bindungen. Neue Snapshots brauchen neue Zustimmung; eine unveränderte
Kategorie-Zustimmung kann für dasselbe weiterhin autorisierte Gerät fortbestehen.

Inbox und Outbox sind dauerhaft gespeichert. Bei einem Neustart werden
Nachrichten nicht unkontrolliert neu erzeugt. Wiederholungen verwenden die
zugeordnete Operation; auch ein alter Versandauftrag braucht weiterhin aktuelle
Autorisierung. Eine nachträglich ungültige Nachrichtenquelle darf keine alten
Zugriffe aufrechterhalten.

Wiederherstellung öffnet keine alten Freigaben automatisch. Unklare Zustände
bleiben gesperrt. Bei größeren Uhränderungen stoppt das Teilen; eine ausdrückliche
Uhrwiederherstellung entzieht zuerst bestehende Freigaben. Native Recovery
archiviert alte verschlüsselte Zustände und erfordert neue Sitzungen. Speicher-
und Netzwerkgrenzen verhindern unbegrenztes Anwachsen; Überlast wird sichtbar
behandelt und führt nicht zu großzügigeren Berechtigungen.

## 7. Bedeutung und Grenzen eines Widerrufs

Widerruf beendet weitere zulässige Zugriffe und verhindert insbesondere, dass
eine alte lokale Warteschlange später ohne aktuelle Erlaubnis veröffentlicht.
Ein offline befindlicher Empfänger erfährt Remote-Widerruf erst bei der nächsten
authentisierten Synchronisation; alternativ endet sein Zugriff mit dem Ablaufdatum.

**Bereits kopierter Klartext, Screenshots oder anderweitig gespeicherte Inhalte
lassen sich nicht zurückholen.** Empfangene Kopien werden durch diesen Dienst
nicht automatisch weiterfreigegeben; ein Empfänger kann gelesene Informationen
außerhalb des Dienstes trotzdem weitergeben.

Relays sehen weiterhin Verbindungsadressen, Zeitpunkte und Nachrichtengrößen.
Aktivierte Auffindbarkeit macht öffentliche Kontoidentität und Relay-Auswahl
sichtbar. Ein kompromittiertes Gerät oder gestohlener Kontoschlüssel wird nicht
allein durch dieses Permission-Protokoll abgesichert.

## 8. Die wesentlichen Architekturentscheidungen

| Entscheidung | Zweck und Konsequenz |
|---|---|
| Dezentrale Autorisierung | Nutzerfreigaben funktionieren ohne zentralen CruxCoach-Validator. |
| Server als optionaler Peer | Serverdaten können geteilt werden, ohne dem Server allgemeine Kontrolle zu geben. |
| Verbindung, Kategorie und Inhalt getrennt bestätigen | Ein Kontakt oder eine Sitzung gibt nicht automatisch sensible Inhalte frei. |
| Signierte Historien statt vertrauenswürdiger Caches | Nachvollziehbare Regeln; manipulierte Projektionen erzeugen keine Rechte. |
| Zugriff standardmäßig sperren | Unbekannte, veraltete oder unprüfbare Zustände öffnen nichts. |
| Bewährte Kryptographie über MDK/OpenMLS | Keine selbst entworfenen Verschlüsselungsverfahren; native Abhängigkeiten bleiben gezielt prüfbar. |
| Mehrere konfigurierbare Relays | Weniger Abhängigkeit von einzelnen Betreibern; keine automatische Freigabe der Relay-Auswahl. |
| Dauerhafte Zustellung und getrennte Empfangsbestätigung | Neustarts und Relay-Annahme werden nicht mit erfolgreichem Empfang verwechselt. |
| Begrenzter Snapshot-Umfang | Konkreter überprüfbarer Austauschpfad; größere Dateien und Gruppen brauchen zusätzliche Integration. |

## 9. Nachweise und noch offene Abnahme

Dokumentiert sind **1.193 fokussierte Shared-/Android-Tests**, zwölf native Tests,
16 Python-Prüfungen und sechs Upstream-Regressionstests. Getrennte Teilnehmerprozesse
wurden mit echter Kryptographie einschließlich Ausfall, Neustart und Widerruf
geprüft. Nutzer–Nutzer und synthetischer Server–Nutzer sind abgedeckt.

Öffentliche verschlüsselte Roundtrips bestanden mit und ohne CruxCoach-Relay auf
MDK v0.9.21. Die finale Revision ergänzt einen offiziellen Upstream-Fix; sie hat
separate lokale Native-/JNI-Prüfungen und eine erneute öffentliche Leseprobe.
Die vollständige öffentliche Ende-zu-Ende-Probe wurde nicht als auf diesem
abschließenden Pin wiederholt dokumentiert.

Offen bleiben vollständige CI inklusive APK-Bau und tatsächliche Android-arm64-,
Amber-, Keystore- und Geräte-Prozesswechsel-Tests. Eine bitgleiche native
Reproduktion wurde bei identischen Build-Pfaden belegt; Pfadunabhängigkeit wird
wegen eingebetteter OpenSSL-Buildmetadaten nicht behauptet. Andere Marmot-Clients
müssen die CruxCoach-spezifischen Nachrichtenkonventionen implementieren, um am
Permission-Austausch teilzunehmen.

Quellennavigation: [technische Architektur](marmot-permissions.md),
[Native-Build und Abhängigkeiten](../../native/marmot/README.md),
[Regelmodell](../../shared/src/commonMain/kotlin/com/cruxcoach/domain/sharing/SharingModel.kt),
[Android-Abläufe](../../androidApp/src/main/java/com/cruxcoach/android/sharing/SharingController.kt)
und [Dokumentationsindex](../README.md).
