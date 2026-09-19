# CruxCoach: UI- und UX-Leitlinien

Dieser Leitfaden beschreibt die aus den Browser-Verbesserungen für 0.2.3 abgeleiteten Gestaltungsregeln. Die App soll am Board schnell bedienbar bleiben, ihre tatsächliche Konfiguration zeigen und folgenreiche Aktionen verständlich machen. Häufige Handlungen bleiben direkt erreichbar; zusätzliche Erklärungen und seltene Optionen erscheinen bei Bedarf.

## Geltung und Belegstand

Stand: 18. September 2026, Branch `feat/0.2.3-browser-ux`, geprüfter Commit `fbd5a713b`. Parallel entstehender Anwendungscode ist kein Nachweis einer abgeschlossenen Umsetzung. **Bestand** bezeichnet hier im Quellcode nachvollziehbares Verhalten; **Designziel** bezeichnet eine Vorgabe, deren Umsetzung und Prüfung noch ausstehen. Gerätebelege gelten ausschließlich für den jeweils dokumentierten Kandidaten.

Maßgeblich sind [Dokumentationsindex](docs/README.md), [Kernkonzepte und Source Map](docs/en/CORE_CONCEPTS.md), [Browser-UX-Validierung](docs/releases/0.2.3-browser-ux-validation.md) und [0.2.3-Checkliste](docs/releases/0.2.3-pre-release.md). Der Index beschreibt einen älteren Prüfstand; für die folgenden Browser-Änderungen ist das spezifische Validierungsprotokoll genauer. Quellcode belegt Implementierung, Prüfprotokolle belegen Tests und Release-Artefakte belegen Veröffentlichung.

| Bereich | Nachweis und Grenze |
| --- | --- |
| Reale Spotlight-Tour, Quicklog und Bearbeitung des gespeicherten Versuchs | Geräteprüfung für Feature-Build 1000019 dokumentiert. |
| Kompakter Header, responsive Zusatzaktionen und vertikal zentrierter Picker | Geräteprüfung bis Feature-Build 1000022 dokumentiert, einschließlich 320 dp und 200 % Schriftgröße. |
| Vertikale Overflow-Punkte und MoonBoard-Griffsets im Picker | Im Quellcode vorhanden; nach dem Protokoll neuer als Build 1000022. Für Griffsets sind fokussierte Tests dokumentiert, eine passende Geräteprüfung steht aus. |
| Trennung von Kontowiederherstellung und Datenbackup | Auf Build 1000024 geprüft; dabei gefundene Navigationskorrektur im Folge-Quellstand. |
| Ein zusammenhängender, zustandsbehafteter Account-Backup-Dialog | Im begleitenden Quellcode und fokussierten Flow-/Dialogtests umgesetzt; Geräteabnahme der neuen APK noch ausstehend. |

Diese Belege zertifizieren keine stabile 0.2.3-Veröffentlichung. Physisches BLE-Leuchten und das produktiv signierte Upgrade von 0.2.2 bleiben laut Protokoll ungeprüft. Die vollständige Kandidatenhistorie gehört ins Validierungsprotokoll, nicht in diesen Leitfaden.

## Einstieg und Tour bilden einen zusammenhängenden Ablauf

**Bestand und zu erhaltende Regeln:** Die Board-Auswahl ist die Hauptaufgabe im ersten Setup-Schritt. Eine untergeordnete Bluetooth-Aktion direkt darunter hilft optional bei der Auswahl. Die separat folgende Download-Auswahl zeigt alle Familien als vollständig antippbare Mehrfachauswahl-Zeilen; Details stehen am Info-Symbol. Der feste Weiter-Button benennt den Download, solange mindestens ein Katalog gewählt ist. Ein erkannter Gerätename darf eine Board-Familie vorschlagen, aber weder andere Familien verstecken noch Layout, Größe oder Winkel behaupten. Die Katalogauswahl bleibt sichtbar; Downloads benötigen eine bewusste Auswahl beziehungsweise Zustimmung. Die minimale Einrichtung hat keinen Überspringen-Button. Bluetooth und Import bleiben optional; die Katalogauswahl kann gesammelt an- oder abgewählt werden und startet erst nach Bestätigung einen Download. CruxCoach-Wiederherstellung gehört in den Import-Kontext neben den vorhandenen Importwegen.

Der zweite Setup-Schritt trennt öffentliche Board-Datenbanken und private Kletterdaten durch zwei klare Abschnittsüberschriften. Der Download-Status nennt Zustand und nächsten Schritt; Einzelkataloge und technische Fehlerdetails werden bei Bedarf geöffnet. Offline ist nicht automatisch gleichbedeutend mit einem geplanten WLAN-Download. Importquellen öffnen ihre Aktionen direkt unter der jeweiligen Zeile; ein bloßes Öffnen startet keinen Import. Weitergehen während des Downloads bleibt möglich.

Die optionale Browser-Tour führt durch die tatsächlichen Bedienelemente. Spotlights orientieren sich an deren gemessenen Grenzen; kurze Hinweise mit Zeiger erklären die nächste Handlung unmittelbar am Ziel. Keine nachgebauten Schaltflächen und keine synthetischen Klicks ersetzen die echte Interaktion. BLE hat Vorrang, lässt sich aber ohne Hardware zurückstellen. Ablehnen der Bluetooth-Aktivierung beziehungsweise Schließen der Suche ermöglicht das Weitergehen.

Der abgedunkelte Hintergrund nimmt weder Berührungen noch Ziehgesten an. Mehrere hervorgehobene Ziele erhalten getrennte Eingabeflächen: Zwischen Versuch und Top bleibt die dazwischenliegende Aktion gesperrt. In geöffneten Menüs sind unbeteiligte Aktionen deaktiviert; Überspringen bleibt erreichbar. Tour-Fortschritt folgt echten Aktionen und gegebenenfalls erfolgreicher Speicherung, nicht dem bloßen Anzeigen einer Anleitung.

Quicklog weist vorab darauf hin, dass ein echter Eintrag entsteht. Erst dessen erfolgreiche Speicherung führt weiter. Die Tour merkt sich seine UUID und führt über die reguläre Navigation zum richtigen Logbucheintrag. Fehlt dieser später, darf kein anderer Eintrag als Ersatz markiert werden. Bearbeiten bleibt optional; Überspringen erzeugt keinen Eintrag. Abbruch bleibt über Neustarts hinweg erhalten. Automatischer Start erfolgt nur beim ersten Einrichtungsabschluss: sowohl der kontobezogene Einrichtungsstatus als auch der Installationsnachweis verhindern einen Neustart bei Bestandsnutzern. Bereits begonnene Touren werden nicht auf den Anfang zurückgesetzt. Das Logo-Menü erlaubt eine ausdrücklich gewählte Wiederholung. „Tour überspringen“ ist eine kontrastreiche Schaltfläche mit Schließen-Symbol, zentriert am dem Ziel gegenüberliegenden Bildschirmrand; der Hinweistext reserviert dafür Platz. Geöffnete Tour-Menüs haben ebenfalls eine hervorgehobene Ausstiegsschaltfläche.

Quellen: [OnboardingScreen.kt](androidApp/src/main/java/com/cruxcoach/android/ui/onboarding/OnboardingScreen.kt), [OnboardingViewModel.kt](androidApp/src/main/java/com/cruxcoach/android/ui/onboarding/OnboardingViewModel.kt), [BrowserTour.kt](androidApp/src/main/java/com/cruxcoach/android/ui/onboarding/BrowserTour.kt), [TourSpotlight.kt](androidApp/src/main/java/com/cruxcoach/android/ui/onboarding/TourSpotlight.kt) und das Validierungsprotokoll.

## Der Header zeigt die aktuelle Klettersituation

**Bestand:** Eine kompakte Zeile enthält Logo, Board-Auswahl, Winkel, BLE und Filter. Diese primären Elemente bleiben direkt erreichbar. Die Board-Familie bleibt lesbar; ergänzende Modell- und Größenangaben dürfen gekürzt werden. Der Picker nutzt nicht unbegrenzt freien Platz: Seine derzeitige Breitenobergrenze beträgt 132 dp. Sein Textblock ist innerhalb der Berührungsfläche vertikal zentriert.

Verbleibender Platz nimmt Logbuch, Listen und Einstellungen in dieser Reihenfolge auf. Nicht passende Zusatzaktionen liegen rechts im Menü mit **vertikalen drei Punkten**. Sie werden nicht zusätzlich im Logo-Menü dupliziert. Dieses enthält weiterhin Board-Navigation, Karte und Tour-Wiederholung. Die Tour muss der tatsächlich sichtbaren direkten Aktion oder dem Overflow-Weg folgen.

Die Board-Auswahl zeigt eine kompakte, aber vollständige Identifikation: Familie, Modell beziehungsweise Layout sowie die relevante physische Größe/Konfiguration. Im geöffneten Picker stehen große Bildvorschauen direkt neben den auswählbaren Modell-/Größennamen. Antippen des Bildes öffnet die zoombare Vollbildansicht, ohne die Auswahl zu ändern; Name und Auswahlindikator bilden eine separate Auswahlfläche. Eine abgesetzte Vorschau oberhalb der Auswahl entfällt. Eine BLE-Verbindung allein ist kein Nachweis für die montierten Griffe oder die gewählte Größe. Winkel verwenden die Fähigkeiten des ausgewählten Boards; Filter zurücksetzen verändert den Winkel nicht.

Quellen: [BoardBrowserHeader.kt](androidApp/src/main/java/com/cruxcoach/android/ui/board/BoardBrowserHeader.kt), [BoardBrowserScreen.kt](androidApp/src/main/java/com/cruxcoach/android/ui/board/BoardBrowserScreen.kt), [BoardSelectionLabel.kt](androidApp/src/main/java/com/cruxcoach/android/ui/settings/BoardSelectionLabel.kt) und [BoardSelectionDialog.kt](androidApp/src/main/java/com/cruxcoach/android/ui/settings/BoardSelectionDialog.kt).

## Hardware-Konfiguration gehört in den Board-Picker

**Bestand:** MoonBoard-Griffsets sind Teil der Board-Auswahl. Bei Varianten mit mehreren Sets öffnet eine kurze Frage nach fehlenden Griffen die zusätzliche Auswahl. Standardmäßig sind alle Sets ausgewählt. Bereits gespeicherte Teilmengen werden übernommen und sichtbar gemacht; mindestens ein gültiges Set bleibt ausgewählt. Fehlende Katalog-Metadaten werden dort erklärt, wo sie die Entscheidung beeinflussen.

Änderungen bleiben bis zur Bestätigung lokale Entwürfe pro Variante. Variantenwechsel und Abbrechen schreiben keine solchen Entwürfe in die Einstellungen. Bestätigung übernimmt Variante und Griffsets gemeinsam, nach gegebenenfalls erforderlicher Download-Zustimmung. Eine zweite Griffset-Auswahl in den MoonBoard-Einstellungen würde dieselbe Entscheidung auf zwei Orte verteilen und soll nicht wieder eingeführt werden. Bestehende LED-Positionsoptionen bleiben eine eigene Aufgabe.

Quellen: [BoardPicker.kt](androidApp/src/main/java/com/cruxcoach/android/ui/settings/BoardPicker.kt), [BoardSelectionDialog.kt](androidApp/src/main/java/com/cruxcoach/android/ui/settings/BoardSelectionDialog.kt) und [MoonBoardPickerDraftTest.kt](androidApp/src/test/java/com/cruxcoach/android/ui/settings/MoonBoardPickerDraftTest.kt). Gerätebeleg für diese Änderung steht noch aus.

## Filter sind geordnet und ihre Wirkung bleibt sichtbar

**Bestand und Gestaltungsregel:** Gradbereich, Ausschluss bereits gekletterter Probleme beziehungsweise Status und Sortierung bilden den Einstieg. Weitere Einschränkungen werden gemeinsam aufgeklappt. Aktive zusätzliche Einschränkungen bleiben auch im eingeklappten Zustand zusammengefasst sichtbar. Umfangreiche Auswahllisten stehen vertikal statt in horizontal wegscrollenden Chip-Reihen.

Statusauswahl und Ausschlussschalter bearbeiten denselben Filterzustand. Neu, versucht und geschafft sind unterschiedliche Ergebniskategorien; mehrere ausgewählte Kategorien werden kombiniert. Die Ergebniszahl muss zur tatsächlich erreichbaren gefilterten Liste passen. Eine leere Liste braucht einen sichtbaren Weg zur Änderung oder zum Zurücksetzen der Filter. Ein unvollständiger Katalogimport darf nicht vorschnell als „keine passenden Ergebnisse“ erklärt werden; die Release-Checkliste dokumentiert dazu einen offenen Befund.

Zurücksetzen, Winkelwahl und Rückkehr zu den Ergebnissen bleiben auch bei großer Schrift bedienbar. Begriffe werden bei Bedarf erklärt, ohne zur Voraussetzung für alltägliches Filtern zu werden.

Quellen: [BoardFilterScreen.kt](androidApp/src/main/java/com/cruxcoach/android/ui/board/BoardFilterScreen.kt), [BoardStatusFilter.kt](androidApp/src/main/java/com/cruxcoach/android/ui/board/BoardStatusFilter.kt), [BoardBrowserViewModel.kt](androidApp/src/main/java/com/cruxcoach/android/ui/board/BoardBrowserViewModel.kt) und die Kernkonzepte.

## Kurze Zustände, zusätzliche Erklärung bei Bedarf

Titel, aktueller Wert und Handlung geben die erste Orientierung. Kurze Statusmeldungen sagen, was gerade geschieht und was als Nächstes möglich ist. Detailerklärungen öffnen sich über Info-Aktionen oder aufklappbare Bereiche. Hilfe zu öffnen verändert keine Einstellung. Fehler, fehlende Voraussetzungen und Warnungen vor Datenverlust bleiben direkt an der betroffenen Handlung sichtbar.

Bestehende Muster sind [SettingsLayout.kt](androidApp/src/main/java/com/cruxcoach/android/ui/settings/SettingsLayout.kt) und [InfoButton.kt](androidApp/src/main/java/com/cruxcoach/android/ui/common/InfoButton.kt). Neue Oberflächen sollen diese Muster weiterverwenden. Ein Upload ist erst nach bestätigtem Abschluss erfolgreich; eine gestartete Hintergrundaufgabe rechtfertigt keine Erfolgsmeldung.

## Kontozugang sichern und Daten sichern sind verschiedene Aufgaben

Ein gesicherter privater Kontoschlüssel ermöglicht den Zugang zur gleichen Identität. Er ist kein Backup von Logbuch, Listen oder anderen App-Daten. Deren Wiederherstellung benötigt zusätzlich erreichbare, passende Backup-Daten und die Entschlüsselungsmöglichkeit dieser Identität. Importieren oder Wiederherstellen darf nicht automatisch die Zustimmung zu neuen Uploads ersetzen.

**Bisheriger belegter Stand:** Der Ablauf erklärt diese Unterscheidung und führt zu den Backup-Einstellungen. [BackupPreferences.kt](androidApp/src/main/java/com/cruxcoach/android/nostr/backup/BackupPreferences.kt) setzt neue Backup-Zustimmung auf aus und den fehlenden Zeitplan auf manuell; ein Wechsel zu einer anderen Identität setzt beides zurück. Das ist die Ausgangslage, kein Beleg für den folgenden neuen Dialog.

### Ein zustandsbehafteter Dialog

Der Account-Backup-Ablauf bleibt in einem Dialog. Die neue Umsetzung liegt in [AccountBackupDialog.kt](androidApp/src/main/java/com/cruxcoach/android/ui/settings/AccountBackupDialog.kt) und [AccountBackupViewModel.kt](androidApp/src/main/java/com/cruxcoach/android/ui/settings/AccountBackupViewModel.kt). Sie ersetzt den Einstellungsumweg; eine Geräteabnahme der neuen Umsetzung steht noch aus.

| Zustand | Darstellung und erlaubte Wirkung |
| --- | --- |
| Einstieg | Datenbackup-Schalter für bisher nicht aktiviertes Backup standardmäßig aus. Ein bereits aktiviertes Backup wird als aktiv übernommen und durch das Öffnen nicht deaktiviert oder neu konfiguriert. Kontoschlüssel und Datenbackup werden knapp unterschieden. |
| Schlüssel kopieren | Explizite Kopieraktion mit Geräteauthentifizierung. Abbruch oder Fehlschlag der Authentifizierung zeigt keinen Erfolg und startet keinen Upload. |
| Extern sichern | Aufforderung, den kopierten Schlüssel außerhalb von CruxCoach sicher einzufügen und aufzubewahren. Rückkehr führt zum selben Dialogzustand. Kopieren allein gilt nicht als abgeschlossene Sicherung. |
| Speicherung bestätigen | Separate ausdrückliche Bestätigung, dass der Schlüssel sicher gespeichert wurde. Diese ist eine Erklärung des Nutzers, keine technische Prüfung des externen Speicherorts. |
| Erstes Datenbackup | Nur bei aktiv gewähltem Datenbackup und nach dieser Bestätigung ersten Upload und tägliche Planung auslösen. Der Dialog erklärt diese Folge vor der Bestätigung. Bleibt der Schalter aus, entsteht daraus weder Upload noch täglicher Auftrag. |
| Verarbeitung und Ergebnis | Fortschritt, bestätigter Erfolg oder verständlicher Fehler mit Wiederholen erscheinen inline. Wiederholtes Tippen beziehungsweise erneutes Öffnen darf keine doppelten Aufträge erzeugen. Ein Fehler bleibt als Fehler sichtbar. |

Der Ablauf erhält seinen Zustand über den Wechsel zur externen Ablage und die nötige UI-Neuerstellung, ohne Klartextschlüssel als UI-Zustand zu persistieren. Ein Abbruch vor Freigabe aktiviert bei bisher ausgeschaltetem Backup keine Übertragung. Bereits aktive Backups folgen ihrer vorhandenen Zustimmung; das Öffnen des Dialogs darf diese nicht stillschweigend ändern. Fehleranzeige und Wiederholen müssen berücksichtigen, ob tägliche Planung bereits eingerichtet wurde.

Für Amber-verwaltete Konten darf die Oberfläche keinen lokalen Schlüsselbesitz behaupten: Schlüsselsicherung erfolgt in Amber, Datenbackup bleibt eine separate Aufgabe. Die konkrete Einbindung dieser Konten in den neuen Dialog ist vor dessen Abnahme zu prüfen. Der neue lokale nsec-Dialog wird für Amber-Konten nicht angeboten; sie behalten ihre separate Schlüsselverwaltung. Als Integrationsstellen dienen [NavGraph.kt](androidApp/src/main/java/com/cruxcoach/android/ui/navigation/NavGraph.kt), [BackupSettingsSection.kt](androidApp/src/main/java/com/cruxcoach/android/ui/settings/BackupSettingsSection.kt) und das [Backup-Modul](androidApp/src/main/java/com/cruxcoach/android/nostr/backup/); diese Referenzen bestätigen nicht den neuen Ablauf.

### Kurze Oberfläche, gezielte Info-Karten

Kontoverwaltung und Backup verwenden dieselbe Informationshierarchie wie der Browser:

- **Direkt sichtbar:** die nächste Handlung und ihre entscheidende Folge. Der Schlüssel ermöglicht Kontozugang und lässt sich nicht zurücksetzen; eine Schlüsselkopie sichert keine App-Daten. Beim gewählten Daten-Backup bleiben öffentlicher verschlüsselter Speicher, Start erst nach Bestätigung und anschließende tägliche Sicherung sichtbar.
- **Am Info-Symbol:** Bedeutung von „nsec“, sichere externe Aufbewahrung, Zwischenablage-Frist, genaue Datenkategorien, sichtbare Verbindungsdaten und Löschgrenzen. Kurze Abschnitte mit verständlichen Überschriften statt eines unstrukturierten Textblocks. Lesen verändert weder Auswahl noch Konto oder Backup.
- **Optional aufklappbar:** Amber für lokale Konten und öffentliche Konto-ID. Aktive Amber-Konten behalten ihre Schlüsselverwaltung direkt sichtbar. Kontozugang sichern, Daten sichern und ein bestehendes Konto wiederherstellen bleiben getrennte, direkt erkennbare Aufgaben.

Ein fehlender Bestätigungsvermerk zur Schlüsselaufbewahrung ist eine wichtige Aufgabe, kein technischer Fehler: kompakter neutraler Hinweis mit klarer Aktion statt großem rotem Warnbanner. Aktuelle Fehler und irreversible Aktionen behalten angemessene Warnungen. Wesentliche Einwilligungen dürfen nicht ausschließlich in einer Info-Karte stehen. Fließtext bleibt in normaler lesbarer Größe; weniger Inhalt ersetzt kleinere Schrift.

## Barrierefreiheit, Sprache und Vertrauen

**Abnahmekriterien:** Primäre Bedienelemente behalten ausreichend große Berührungsflächen; der Header verwendet für seine Icon-Aktionen und den Winkel 48 dp. Große Schrift darf keine notwendigen Aktionen abschneiden. Dialogtexte und längere Auswahlbereiche scrollen; wichtige Bestätigungs- und Abbruchaktionen bleiben erreichbar. Board-Familie und reale gerenderte Grenzen sind bei 320 dp und 200 % Schriftgröße zu prüfen, nicht nur die Existenz eines UI-Knotens.

Icons benötigen verständliche zugängliche Namen, Schalter einen eindeutigen Zustand und Dialoge eine sinnvolle Fokusreihenfolge. Information darf nicht ausschließlich von Farbe abhängen. TalkBack- und Tastaturfokus müssen bei einer Tour auf erlaubte Aktionen begrenzt bleiben beziehungsweise einen bedienbaren Ausstieg bieten. Die vorhandene Sperre von Zeigereingaben belegt noch keine vollständige Screenreader-Barrierefreiheit; dafür ist eine eigene Prüfung nötig.

Neue UI-Texte werden gemeinsam in [Englisch](androidApp/src/main/res/values/strings.xml) und [Deutsch](androidApp/src/main/res/values-de/strings.xml) gepflegt. Kurze Texte müssen die gleiche Bedeutung und dieselben Folgen vermitteln. Singular und Plural passen zur tatsächlichen Aktion, insbesondere beim Löschen eines einzelnen Eintrags. Lange deutsche Beschriftungen gehören in die Layout-Prüfung. Dynamische Werte verwenden Ressourcenparameter statt zusammengesetzter Satzfragmente.

Privatsphäre wird konkret erklärt: öffentliche Profile und veröffentlichte Probleme, verschlüsselte Datenbackups und lokaler Kontoschlüssel sind unterschiedliche Vorgänge. Verschlüsselter Inhalt bedeutet nicht unsichtbare Server-Metadaten oder garantierte Löschung aller entfernten Kopien. „Alles ist verschlüsselt“ wäre für die App insgesamt falsch; die Kernkonzepte unterscheiden BoardDB, SecureDB und weitere Dateien. Screenshotschutz und maskierte Schlüsseleingabe sind zu erhalten; Geheimnisse gehören weder in Logs noch in Screenshots, Testberichte oder gespeicherten UI-Zustand.

Destruktive Aktionen erhalten eine eigene, konkrete Bestätigung: welcher Eintrag, welche lokale Schlüsselkopie oder welche Backup-Daten betroffen sind und was erhalten bleibt. Abbrechen verändert nichts. Einen Schlüssel kopieren, externe Speicherung bestätigen, Datenbackup aktivieren und Daten löschen dürfen nicht als dieselbe Zustimmung behandelt werden. Im Quicklog bearbeitet ein Versuch weiterhin einen Versuch; daraus darf kein erfolgreicher Send werden.

## Prüfung und Pflege

UI-Änderungen benötigen verhaltensbezogene Nachweise. Die vorhandenen Tests sind Ausgangspunkte, kein pauschaler Beleg für spätere Änderungen:

| Änderung | Erwartete Prüfung |
| --- | --- |
| Setup und Tour | Download-Zustimmung, Katalog-Sammelauswahl, Tourüberspringen ohne Nebenwirkung, BLE ohne Hardware, tatsächliche Zielaktionen, gesperrte Hintergrundgesten und Zwischenräume, Neustart und gespeicherte Eintrags-UUID. Ausgangspunkte: `BrowserUxTest`, `BrowserTourTest`, `OnboardingDownloadSelectionTest`. |
| Header und Picker | 320 dp/200 %, normale Breite, lesbare Familie, sichtbare Primäraktionen, direkte und versteckte Zusatzaktionen, vertikale Ausrichtung, vollständige Board-Angabe. Ausgangspunkte: `BoardBrowserHeaderTest`, `BoardSelectionLabelTest`. |
| MoonBoard und Filter | Entwürfe, Abbrechen, gemeinsame Speicherung, minimale Griffset-Auswahl, Download-Zustimmung; konsistente Statusauswahl, Ergebniszahl, Nulltreffer und Winkel nach Reset. Ausgangspunkte: `MoonBoardPickerDraftTest`, `BoardBrowserStatusFilterTest`. |
| Neuer Backup-Dialog | Ausgeschalteter Standard und bestehender aktiver Zustand; Authentifizierungsabbruch; Kopieren ohne Freigabe; externe Rückkehr und Neuerstellung; ausdrückliche Speicherbestätigung; erster Upload plus tägliche Planung; Fehler und Wiederholen ohne Doppelauftrag; kein Einstellungsumweg; Identitätswechsel und Amber. Die fokussierten Flow-/Dialogtests sind erfolgreich; externe App-Rückkehr, Prozess-Neustart, echte Uploads und Gerätedarstellung der neuen APK bleiben gesondert abzunehmen. |

Die genannten Browser-Tests liegen unter [UI-Tests](androidApp/src/test/java/com/cruxcoach/android/ui/). Navigation muss das richtige Ziel erreichen; ein beliebiger sichtbarer Text genügt als Nachweis nicht. Geräteprüfungen dokumentieren Kandidat, Sprache, Breite, Schriftgröße, tatsächliche Beobachtungen und Aufräumen erzeugter Testdaten. Physische BLE-Prüfungen bleiben von simulierten Verbindungsabläufen getrennt.

Gemäß [AGENTS.md](AGENTS.md) und [Testleitfaden](docs/testing.md) laufen lokal nur fokussierte, änderungsspezifische Prüfungen; vollständige Tests, APK-Builds und Android-Lint gehören in CI. Der Dokumentationsagent führte selbst keine Anwendungstests oder Geräteprüfungen aus; die begleitenden Implementierungsprüfungen sind im Validierungsprotokoll dokumentiert. Bei späteren Änderungen zuerst Quellen und betroffene Zustände prüfen, dann diesen Leitfaden anpassen. Ein Designziel wird erst mit konkretem Implementierungsnachweis zum Bestand; Geräteprüfung und Veröffentlichung bleiben gesonderte Aussagen.
