# Übergabeprompt: unabhängiges Code-, Daten- und UX-Review 0.2.3

Du bist der unabhängige Reviewer für CruxCoach 0.2.3. Analysiere den **gesamten Release-Diff** gegenüber der ausgelieferten 0.2.2 und die für sein Verhalten notwendigen unveränderten Abhängigkeiten. Suche belastbare Probleme in Logik, Datenhaltung, Nebenläufigkeit, Integrationen, Sicherheit, Datenschutz, Performance, Nutzerführung und Design. Liefere am Ende einen ausführlichen Bericht, damit der Eigentümer mit dem Entwicklungsagenten True Positives bestätigen und erst danach Verbesserungen ableiten kann.

**Reines Code-Review:** keine Produktänderungen, keine Fix-Commits, kein ADB, keine manuellen Gerätetests, kein Maestro, keine Testausführung/Builds, keine API-Schreibversuche, keine Produktionseingriffe, keine Publikation. Vorhandene Tests lesen und ihren Beweisumfang beurteilen; keine neuen Tests schreiben. Kleine rein statische Auswertungen wie Diff-/Schema-/Ressourceninventare sind erlaubt. Keine weiteren Agenten starten. Du bist unabhängig vom manuellen Testagenten; spekuliere nicht über dessen Ergebnisse.

## 1. Baseline und prüfbarer Umfang

- Ausgeliefert: `v0.2.2` = `18fb0b4f5eb549a4321b424c04b0adeb966cb5b4`.
- 0.2.3-Anwendungscode: `f44601aa15633d0b15d25d913077cc7df1497027`, auf `feat/0.2.3-release` konsolidiert.
- Release-Worktree: `/home/myuser/dev-import-20260906/home/worktrees/cruxcoach-0.2.3-release` (Existenz/Revision bestätigen).
- Verwende exakt `git diff v0.2.2 f44601aa1`, nicht den 0.2.2-Feature-Branch und nicht nur die letzten UX-Commits. Keine automatische Aktualisierung des Reviewgegenstands; bei neuerem Kandidaten separat dessen Delta ausweisen.
- Lies `AGENTS.md`, `docs/README.md`, `docs/en/CORE_CONCEPTS.md`, `design.md`, `docs/releases/0.2.3-pre-release.md`, `0.2.3-branch-integration-2026-09-17.md`, `0.2.3-validation-2026-09-17.md`, `0.2.3-browser-ux-validation.md`, `docs/research/2026-09-16-kilter-log-upload-investigation.md`, CHANGELOG und RELEASE_NOTES.
- `changed-files.tsv` ist die vollständige Dateiliste des fixierten Diffs. Ordne **jede** Datei einer Reviewgruppe und einem Status zu. Tests, Ressourcen, Assets, Build/Release und Dokumentation nicht still überspringen. Generierte große Kartenbestände dürfen strukturell und stichprobenartig geprüft werden, mit expliziter Grenze statt Behauptung vollständiger fachlicher Verifikation.
- Refactor, Bildprototyp, iOS-Parität und separat verbliebenes Marmot/Freigaben-Paket sind nicht im Zielumfang. Vorhandene BLE-/LAN-/Session-Funktionen dagegen schon, soweit verändert/betroffen. Branch-Namen oder Squash-Historie sind keine Funktionsbeweise.
- Respektiere fremde Änderungen/Worktrees. Keine Credentials/Secrets lesen. Berichte nur öffentliche/synthetische Identifikatoren. Produktionslogs werden für diese rein statische Aufgabe nicht benötigt.

## 2. Methode: zuerst Daten- und Kontrollfluss, dann Urteil

Erstelle eine Landkarte der geänderten UI-Einstiege, ViewModels, Worker, Repositories, SQL-Queries/Migrationen, Preferences, API-/Dateiformate und externen Vertrauensgrenzen. Verfolge jeden kritischen Weg vom Nutzerereignis bis zur Persistenz/externen Wirkung und zurück zur Statusanzeige.

Prüfe systematisch: Vorbedingungen, Defaultzustand nach Update, Abbruch, Fehler, Retry, doppelte Ereignisse, späte Antworten, parallel laufende Jobs, Prozessneustart, Konto-/Boardwechsel, Offline/Online. Lies relevante Aufrufer und Gegenseiten vor einem Befund. Ein problematisch aussehender einzelner Ausdruck ohne erreichbaren Pfad ist kein belegter Bug.

Lege pro Verdacht eine kurze Gegenprüfung an: Welche Validierung, Transaktion, Mutex, Identitätsbindung oder UI-Sperre könnte ihn verhindern? Ist sie auf allen Pfaden aktiv? Gibt es bereits einen Test, und deckt er genau den realen Pfad oder nur ein isoliertes Mock ab? Ist das Verhalten neu, alt oder absichtlich eingeschränkt? Versuche eigene Hypothesen zu widerlegen, bevor du sie priorisierst.

## 3. Reviewgruppen und konkrete Fragen

### R01 – Installation, SQL-Migrationen und Datenintegrität (kritisch)

Quellen: `shared/src/commonMain/sqldelight/board/{29,30}.sqm`, `Board.sq`, unveränderte `secure/`-Schemas, DB-Factories/SQLCipher/Keystore, `BoardRepositoryImpl`, `BoardDatabaseImporter`, `BoardImportIndexes`, `BoardImportRetry`, `BlossomSyncManager`, Backupimport und Preferences.

- Migration 29: boardbezogene Übernahme aus `beta_links`, LOWER-/JOIN-Identität, HTTPS-Filter, zusammengesetzter Primärschlüssel, Duplikate, leere/teilweise Altbestände; alte Tabelle für Kompatibilität erhalten. Können reale 0.2.2-IDs beim JOIN verloren gehen? Ist das Verlust oder bewusstes Filtern? Belege mit echten möglichen Altformaten.
- Migration 30: Alias-Primärschlüssel, Canonical-Index, anschließende Importbefüllung; Board-/Layout-Kontext, kollidierende Alt-IDs und exakte Duplicate-Prüfung. Wird ein ähnlicher statt identischer Climb fälschlich zusammengeführt?
- Upgradepfad für vorhandene Daten: keine destructive fallback, keine unerwartete Datenbankneuanlage durch Pfad/Keyänderung; ausbleibende private Schemaänderung schützt nicht automatisch vor Repository-/Preference-Regression.
- Catalogue-Transaktionen inklusive Geometrie/Stats/Origin, Index-Reparatur/-Erhalt, Rollback und Cancellation; fehlgeschlagener Teilimport darf nicht „geladen“ werden. Retries auf temporäre Locks nur passend begrenzt und ohne unnötige erneute Downloads.
- Eigene Climbs, persönliche Logs, Listeinträge, Playback-Schritte und Referenzen über zwei DBs erhalten. Vollständigkeitsmarker, Sync-Cursor, Aliasauflösung und FK-/Anwendungsinvarianten müssen zusammenpassen.
- Lokale/Cloud-Exports (`CruxCoachBackup`) sowie ältere Formate: dedup/merge, Kontobindung, IDs mit/ohne Bindestrichen, importierte eigene Routen, Gegenwarts-/Zeitzonenwerte, atomare Anwendung und Wiederholung. Identifiziere nicht gesicherte Kategorien; „Schlüssel gesichert“ darf nicht implizieren, dass diese in einem Backup liegen.

### R02 – Katalogauswahl, Scheduling und Netzwerk

Quellen: `UserPreferences`, `BoardDownloadSelection`, `BoardSyncManager`, `BoardSyncWorker`, `NetworkStatus`, `BoardSyncInlineCard`, `BoardSyncViewModel`, `CatalogueSelection`, Onboarding-/Picker-Zustimmung, Aurora/Moon/Quantum-/Blossom-Importer.

- Fehlender Preferencekey bei Bestandsnutzern vs. bewusst leere Auswahl; gültige Familien, neue/ungültige Werte, Default und Wiederherstellung.
- Entwurf/Cancel/Confirm atomar; während Sync hinzugefügte Boards zuverlässig nachholen, letzte bestätigte Auswahl beachten, kein Lost Update/Race beim Ende eines laufenden Jobs. Keine stillen Downloads durch bloßes Öffnen/Boardinspection. Abwählen löscht keine Daten und missachtet keine bestätigte Auswahl.
- Worker, manuell, Onboarding und Restore konkurrieren: keine Doppelimporte, Deadlocks, fälschlich fertige Status, unendliche Retries oder durch Cancellation verschluckte Fehler.
- Reconnect im offenen UI, Netzwerkvalidierung statt bloß WLAN an; Listener-Lifecycle, Hintergrund, Captive Portal soweit Code erkennbar. Offline nicht als zugesicherte WLAN-Warteschlange darstellen, wenn kein Auftrag existiert.

### R03 – Kilter-API, Reconciliation und Diagnosen (kritisch)

Quellen: `KilterApiClient`, `KilterSyncEngine`, `KilterUploadStatus*`, `KilterAccountSection`, `BugReportScreen`, `DevContactViewModel`, HTTP-/Queue-/Status-Tests und Untersuchungsbericht.

- Tatsächlicher geprüfter Vertrag: kompakte Legacy-Climb-IDs beim Upload uppercase, native hyphenated IDs erhalten; Log-UUID separat behandeln. Route, Wandkontext, Winkel, Timestamp, Bidcount und Send/Flash nicht vermischen. Name/Setter allein beweisen keinen Match.
- Einschalten muss vorhandene passende lokale Logs nachholen; neue Änderungen und bereits abgeglichene Remote-Logs korrekt behandeln. Accountwechsel/Logout, temporäre Fehler und Pagination beim Remote-Readback untersuchen.
- POST-Duplikate: Server kann beim gleichen Log-ID-Insert 500 liefern. Remote-Readback vor Wiederholung, Teilannahme, verlorene Antwort, eigener interner HTTP-Retry und konkurrierende Sync-Auslöser. Nie lokal synced markieren, wenn Readback fehlgeschlagen/mehrdeutig ist.
- Bekannte Einschränkung: Update vorhandener Remote-Logs nicht verifiziert unterstützt. Abweichende relevante Felder führen zu sichtbarem Konflikt statt falschem Erfolg; kein automatisches Delete/Recreate. Kommentar/Rating außerhalb des belegten Vertrags und alte Fehlzuordnungen nicht als heimlich repariert behandeln.
- Status nach deaktiviert/unbekannt/running/Fehler darf alten Erfolg nicht weiterzeigen. Ein kompakter Problemhinweis plus konkrete Handlung; keine zwei identischen Banner, keine technische Textwand.
- Diagnosen nur Opt-in im Bugticket; Token/Passwort/nsec, gesamte Rohantwort, private Notizen und identifizierende Nutzdaten nicht leaken. Prüfe auch Exceptionstrings, Logger, HTTP-Interceptors, Compose-State und persistierte Fehler.

### R04 – Schlüsselverwaltung, Amber, Backup und Datenschutz (kritisch)

Quellen: `KeyImportScreen/ViewModel`, `KeyManagementScreen/ViewModel/Dialogs`, `AccountBackupDialog/ViewModel`, `BackupSettings*`, `BackupRepository`, `BackupPreferences`, `BlossomUploader`, `NostrSigner`, `DTagDeriver`, `BackupCrypto`, `CruxCoachBackup`, `NavGraph`.

- nsec sichert Zugang, nicht automatisch Daten. Opt-in im zusammenhängenden Schlüsselsicherungsdialog: korrektes Default (aus für bisher inaktiv), bestehendes aktiv beibehalten, reale Auth-/Kopieraktion/Bestätigung vor „gesichert“, Abbruch ohne Nebenwirkungen. Aktivierung/Rückkehr darf Schlüsselaufbewahrung nicht überspringen.
- Kontoidentität vs. Zugangsmethode: gleiche öffentliche Identität in verschiedenen Formaten, lokaler Schlüssel, Amber, npub-only. Vorschau darf keinen Accountwechsel ausführen, Bestätigung muss richtigen ausstehenden Target verwenden; stale Activityresult/Mehrfachklick/Neuerstellung untersuchen.
- Amber ohne lokalen Key muss Onboarding-Restore zulassen. Fehlende App/Abbruch/fehlende Signatur/Background-Permission verständlich, keine Fake-Erfolgsmeldung. Externe Intent-Resultate auf Herkunft/Format/erwartete Anfrage prüfen; keine Identitätsübernahme aus beliebiger App ohne passende Vertrauensannahme.
- Andere Identität: Worker, aktiver Backup-/Restorejob, Signer, DTag-/Relaycache, Cursor, Remote-Pointer, alte Nachrichten und lokale Daten müssen konsistent sein. Insbesondere prüfen, ob Worker-Cancel auch laufende manuelle Pipelinejobs abdeckt und Kontoänderung durch denselben Lock/Snapshot geschützt ist. Das ist ein Verdacht, kein vorab bestätigter Bug.
- Backup-Pipeline: verschlüsselter Blob verfügbar → bestätigtes Wrapped-Key-Event → Pointer; Ack-Bedeutung, Nostr-ID-/Signaturprüfung, Error/Retry, Schlüsselrotation und Cleanup alter Blobs. Kein veröffentlichter Pointer ohne wiederherstellbaren Schlüssel. Kann fehlgeschlagene Rotation den letzten gültigen Restoreweg verwaisen lassen? An erreichbarem Pfad beweisen.
- Nullung temporärer DataKeys, Lebensdauer von Klartext/nsec/Passwörtern in State/Clipboard, FLAG_SECURE, Backstack, Logs/Crashberichte; Nullung nicht als umfassende Speicherlöschung verkaufen.
- Restore-/Delete-Pipeline-Mutex, warten auf Katalogsync, beide DBs und Cancellation; kein Deadlock, kein Remote-Delete unter falschem Konto. Verschlüsselung/Authentizität, Größenlimits, Dekompressionsgrenzen, untrusted Server/Pointer, falscher Key und beschädigte Payload.
- Trenne öffentlich unvermeidbare Metadaten von privaten Nutzdaten und dokumentiertem Opt-in. Kein unkonkretes „Nostr ist unsicher“; konkreten Datenfluss und Schaden belegen.

### R05 – Browser, Filter, Logbook und Statistiken

Quellen: `BoardBrowserViewModel`, `BoardRepository*`, `Board.sq`, `BoardStatusFilter`, `BoardFilterScreen`, `AscentLogger`, `AscentLoggingDialog`, `BoardLogbook*`, `BoardStats*`, `ClimbCard`.

- Count- und Ergebnisabfrage müssen gleiche Restriktionen anwenden: Board/Layout/Größe/Winkel, Grade, Source, eigene/Community, Hidden, Status, Holds, Quantum-Occupancy. Keine Begrenzung auf erste 50; stable random order über Seiten und IDs vor Compose deduplizieren.
- Schnell wechselnde Filter und Paging: Cancellation, stale responses, Reset, Refill, sehr große Datenmenge, Empty State; keine O(n²)- oder Mainthreadarbeit für große Kataloge.
- „Gesendete ausschließen“ und Mehrfachstatus konsistent, Winkel bei Reset erhalten. Entfernte rote Count-Badge darf keinen Statuszwang oder Testannahmen hinterlassen.
- Moon-Aliasauflösung für Logs/Medien/Status/Listen, Scope/Case, kein Fremdboardmatch.
- Quicklog-/Detaildialog echte Persistenz vor Folgeaktion, Doppeltap, vorherige Versuche vs. Flash, Datum/Zeitzone, bid_count, Retry/Cancel; spätere Edit/Delete-/Filter-/Listenreaktion.
- Statistiken: unterschiedliche Probleme nach bester Kategorie innerhalb Zeitraum (Flash > Send > Versuch), Definition Board/ID/Winkel genau dokumentieren. Trainingvolumen und Sendtimeline getrennt; keine Fehlversuche in Sendtimeline, echte Wiederholungen dort erhalten. Labels/Hilfen erklären tatsächliche Einheit. Chartgrenzen, leere und sehr große Datenmengen.

### R06 – Playlist, LED/BLE und Session-Kompatibilität

Quellen: `PlaylistGeneratorScreen/ViewModel`, `PlaylistGeneratorParams`, `PlaylistPlanner`, `TrainingRanges`, `AddToListViewModel`, `PlaylistDetailScreen`, `BoardDeliveryPolicy`, `SessionCommandGate`, `SessionGattBridge`, BLE sheets.

- Aktives Board vor Profil-/Candidate-Laden publizieren, stale Profilantwort nach Boardwechsel, Gradeband-Mapping, Kandidatenpool-Reuse mit korrektem Cachekey, Winkel/Filter, Empty/knappes Angebot und deterministische Constraints.
- Bestehende Listen/Playback-Schritte, letztes Kartenelement/FAB, Navigation und Logging; UIänderung darf nicht falsche Boardzustellung auslösen.
- Automatisch vs. explizit; Relight-Aktion bleibt erreichbar. Quantum besitzt explizite Layerwahl, Browsen überschreibt keine Schicht. Vorhandene Single-/Multi-Connection-Defaults und gespeicherte Präferenzen sauber unterscheiden.
- Session-JOIN vor Mutation, rate budget pro Peer, Burst/Sustained-Limits, Threadsafe Zugriff, Reconnect-Cleanup, legitime große Queues/Legacyclients. Keine Behauptung physischer BLE-Funktion aus Quellcode.

### R07 – Medien, lokale Shares und Relay-Vertrauensgrenzen

Quellen: `BoardBetaMediaSync`, `MoonBoardBetaSync`, `BetaVideoSection`, `BetaThumbnail*`, `VerifiedBetaThumbnail`, `BlossomManifest/SyncManager`, `LocalShareSchema/Protocol/Client`, `LocalTransferLimits`, `NostrRelayPool`, `RelayInputGuard`.

- Katalog/optionale Medien getrennt, Herkunft/Board/Angle, dedup und exact alias adoption. Thumbnailhash vor Anzeige und Cache, Mirrorfallback, Keys/Cacheinvalidation und Speichergrenzen; bekannte Instagram-Logo-URL vor Cachelookup ablehnen.
- Bereits geladene Vorschauen im Compose-Recycling nicht neu als fehlend darstellen; URL-/Hash-/Climb-Wechsel darf kein falsches Bild liefern. Offlineresume, Fehlerplaceholder, leere URL, externer Intent, Provider-/Autoradresse.
- Untrusted Peer darf vorhandene Geometrie/LED nicht überschreiben; 0.2.2- und Legacy-Firstimport, neuere Alias-/Medientabellen additiv nur für gültige Zielclimbs. Snapshot-/Hash-/ZIP-/Pfad-/Größenlimits, Wiederaufnahme und Cancellation, keine persönlichen DB-Tabellen im Katalogshare.
- Relayauth vor subscriptionlokaler Dedup, rekursive Tiefe und Nachrichtengröße vor JSONparse. Können invalide Events spätere gültige blockieren oder Grenzen legitime Events ablehnen? Konkrete Größen/Flows prüfen.

### R08 – Updater, Build und Release-Vertrag

Quellen: `UpdaterRepository/Preferences`, `UpdateNotifier/Checker`, `IntegrityVerifier`, `ApkDownloader`, MainActivity/App, Gradle, Releaseworkflow/-script, vorhandene Policytests.

- Nach Wegwischen zweimal mindestens 24 h, danach 72 h; Count/Tag-Persistenz, neue Version, parallele Checktrigger, Permissiongrant, Neustart, 304/Skipped, automatische Checks aus. Benachrichtigung nicht als ausgeliefert markieren, wenn Android sie blockiert.
- Manueller Check vs. verbotene automatische Wiederbelebung gecachter Downloads; Transport/Mobile-Opt-in, Download-Cancel, fehlender Speicher, Fallbackquellen, Hash/Archivepackage/versionName/Signerlineage, ZIP-Limits. Kein Downgrade/anderes Paket durch verifiziert wirkende Metadaten.
- 0.2.3 minSdk28/versionName/stableCode9 im tatsächlichen Diff überprüfen; echter Updatepfad zu 0.2.2 package/signature/code8, Feature-IDs und Reservierung getrennt. Stabile Upgradefähigkeit ohne APK-Beweis bleibt offen.
- `.github/`, `.apktrack/`, Signierung und Gradle nur reviewen, nicht ändern. Eigentümerreview und private Stable-Publikation; Trustboundary/OIDC-Migrationsentwurf nicht als live enforced bewerten. Keine Branchcode-Ausführung mit Publishingsecrets.

### R09 – Onboarding, Tour, Settings und Design aus Nutzersicht

Quellen: `design.md`, `OnboardingScreen/ViewModel`, `BrowserTour`, `TourSpotlight`, `NavGraph`, `BoardBrowserHeader/AngleSheet`, `BoardPicker/SelectionDialog`, `Settings*`, Import-/Konto-/Profil-Screens, `InfoButton`, `CruxCoachDesignTokens`, EN/DE-Ressourcen.

Leite aus Code vollständige Nutzerwege ab: neuer Nutzer ohne Board/Netz/Konto; Bestandsnutzer nach Update; lokaler/Amber-Restore; Nutzer mit unvollständigem Griffset; große Schrift. Nicht nur einzelne Composables beurteilen.

- Screen 1 Boardwahl als Hauptaufgabe, BLE-Hilfe im Picker, großes Bild neben Name/Zoom, klare Draftbestätigung, keine zusätzliche Setup-Skipaktion. Katalogauswahl sichtbar, Sammelaktion links vom Info-Icon; kein verzichtbarer Zähler/Erklärungssatz.
- Screen 2 Katalogstatus und private Kletterdaten getrennt; laufender Download sperrt Auswahl nicht. Konto-Restore bei Imports, Key/Amber, keine losgelöste Backupaktivierungsbehauptung. Bewusste Aktionen starten Download/Import.
- Tour automatisch nur neuer Einrichtung, kein Updatezwang; installweiter und kontobezogener Zustand, Skip/Resume/Replay/Accountwechsel. Spotlight blockiert alle fremden Gesten, auch Zwischenräume/Dropdownaktionen; Skip sichtbar; reale Controls, Quicklog und danach exakte gespeicherte Log-ID. Keine Deadends ohne Hardware oder bei leerem Katalog.
- Eine Topbarzeile mit Logo/lesbarer Familie/BLE/Winkel/Filter; übrige Aktionen rechts vertikale Punkte, Reihenfolge konsistent; kein unnötiger Leerraum/Pickeroverflow und kein roter Filtercounter. 320 dp/200 % rechnerisch/Layoutcode plausibilisieren; ohne Gerät nicht visuell „bestanden“ nennen.
- Moon-Griffsets im Picker, Abbruch unverändert, zumindest ein gültiges Set, pro Layout erhalten. Keine leeren Board-Verbindungssektionen, nur tatsächlich sinnvolle spezielle Boardsettings.
- Kerninformationen/Status/Grade gut lesbar, keine horizontal zu entdeckenden Textbanner, wenige kurze Hinweise; Hintergrundinfos prägnant in Info-Karten. **Entscheidende Einwilligung, destruktive Folge und notwendiger nächster Schritt dürfen nicht allein dort versteckt werden.** Prüfe gerade dieses Spannungsverhältnis bei Backup/Identitätswechsel.
- Profil lokal speichern vs. veröffentlichen, Bugreport vs. Featurewunsch, Kontakt-/Replywirkung; keine unbeabsichtigte externe Aktion. Infoinhalte passen semantisch zum Control und zur Backendwirkung.
- TalkBack-Semantik/Focus, Touchflächen, Insets/Keyboard, Dialogscroll/Buttons, deutsche lange Labels/Plural/EN-Parität, Hell/Dunkel-Kontrast. Harte Platzannahmen/States prüfen. Andere Sprachen auf neue Fallbacks und irreführend alte Texte prüfen; keine pauschale Übersetzungsabnahme behaupten.

### R10 – Karte, Datenassets, Dokumentation und restliches Delta

Quellen: `MapScreen/Filters/Search/Stats/Venue/ViewModel`, `PagesBoardMapDataSource`, `GymBoardSearchSheet`, BoardLocationRepository, Kartenassets und Update-Script.

- Suchnormalisierung (Umlaute, Städte/Venues), Familie/Größe/Moon-Layout/LED/Wellpass, Reset/Persistenz, alle Familien in Statistik, Unknown-Werte und Deduplikation. Boardgröße aus Standortvorschlag nicht als ermittelt behaupten.
- Onlinequelle/Snapshot/Parsergrößen/Fallback und Versions-/Asset-Konsistenz, Cancel/Thread/Cache, keine versteckten massenhaften Downloads oder UI-Blockade durch große Städtebestände.
- CHANGELOG/RELEASE_NOTES/Design/Releasecheckliste auf Widersprüche zum finalen Verhalten prüfen. Veraltete Standhinweise sind nicht automatisch Produktbugs, aber falsche Upgrade-/Backup-/Sicherheitsversprechen können relevant sein.
- Jede übrige geänderte Datei im Inventar zuordnen und untersuchen. Test-only-Änderungen auf entfernte sinnvolle Assertions, gefälschte Erwartung, Mockgrenzen und übersehene Zustandsübergänge prüfen. Insbesondere lokale Java17/Quartz-Java21-Grenze: UI-Mocktest beweist keinen echten Amber-/Krypto-Restore.

## 4. Bekannte Grenzen und Hypothesen korrekt behandeln

Ein alter Validation-Eintrag ist ein Hinweis, kein aktueller Befund. Beispielsweise wurde die Send-Zeitreihe später korrigiert, obwohl eine ältere Releasecheckliste sie noch als offen aufführt. Prüfe immer den gepinnten Code.

Besonders sorgfältig unabhängig verifizieren: konkurrierender Identitätswechsel während Backup, Recovery bei Schlüsselrotation, echte Amber-Rückkehr ohne Localkey, Kilter-Editkonflikte, Queue-Ergänzung am Sync-Ende, Tour auf Update und Medienmemorycache. Weder automatisch als Bug übernehmen noch wegen existierender Unit-Tests verwerfen.

Eine absichtlich nicht unterstützte externe API-Funktion ist nur dann ein Defekt, wenn der Appvertrag sie verspricht oder Daten/Status irreführend behandelt. Ein bestehender 0.2.2-Fehler bleibt als solcher markiert; er kann releasekritisch sein, ist aber keine neue Regression.

## 5. Ergebnisformat und True-Positive-Kriterien

Schreibe `code-review-report.md`, `review-coverage.tsv` (jede geänderte Datei) und optional separate technische Ablaufnotizen in ein eigenes Ergebnisverzeichnis. Nur Berichtdateien, keine Quellcodeänderungen. Am Ende konkrete Pfade übergeben.

Bericht enthält:

1. Fixierte Baseline/Ziel-SHAs, Umfang/Methodik und Grenzen (statisch, keine Runtimeprüfung).
2. Priorisierte **belegte** Befunde `C-001` usw., je mit:
   - Schwere: kritisch (Datenverlust/Identitäts-/Secretverletzung), hoch (zentraler Ablauf blockiert), mittel (falsches Ergebnis/erhebliche UX), niedrig (begrenzter Darstellungs-/Dokufehler).
   - Präziser Trigger, Vorbedingungen, betroffene Nutzer und realistischer Schaden.
   - Soll und dessen Quelle, Ist, exakte Datei/Zeile am Zielcommit, Aufrufer→State→Persistenz/Netzwirkung; relevante kleine Codeauszüge.
   - Gegenprüfung/warum vorhandene Schutzmechanismen nicht genügen; vorhandene Testabdeckung und deren konkrete Lücke.
   - Neu eingeführt / verschärfter Altfehler / unveränderter Altfehler / dokumentierte Einschränkung.
   - Konfidenz mit Begründung, minimaler Geräte-Repro oder zukünftig sinnvoller Regressionstest; kein behauptetes Testergebnis.
   - Grobe Korrekturrichtung mit Tradeoffs, noch keine Implementierung.
3. Separat **offene Verdachtsfälle**, welche Evidenz fehlt und wie man sie falsifiziert. Keine spekulativen Securitybehauptungen im bestätigten Befundteil.
4. Separat **UX-/Designvorschläge** mit konkretem Nutzerproblem und Designregel; reine Geschmacksfrage kennzeichnen. Screenshotnotwendigkeit dem manuellen Agenten zuordnen, nicht erfinden.
5. Daten-/Migrationsmatrix: Kategorie, geänderter Leser/Schreiber, erwartete Invariante, vorhandene statische Absicherung, notwendiger Gerätebeweis. Persönliches Schema unverändert ≠ migrationssicher.
6. Vollständige Coverage R01–R10/Dateiinventar, bewusst nicht geprüfte Aspekte, blockierte Nachweise, Releaseempfehlung mit verbleibenden Risiken. Keine universelle Fehlerfreiheitsgarantie.

True-Positive-Kandidat heißt: erreichbarer Pfad + konkrete verletzte Invariante/Anforderung + nachvollziehbare Folge + Gegenprüfung. Ein bloß denkbarer Race, generisches „Input validieren“ oder stilistische Vorliebe reicht nicht. Probleme mit gleicher Ursache zusammenfassen, Auswirkungen vollständig nennen. Arbeite alle Gruppen ab, auch wenn du früh einen kritischen Fehler findest. Kritische Befunde früh melden und unabhängiges Review fortsetzen. Fixes werden erst nach gemeinsamer Bewertung beauftragt.
