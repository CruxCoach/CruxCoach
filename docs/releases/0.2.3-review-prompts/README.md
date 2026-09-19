# Unabhängige Abnahme von CruxCoach 0.2.3

Zwei direkt übergebbare Prompts, erstellt am 19. September 2026. Die Agents sollen
prüfen und berichten, nicht gleichzeitig korrigieren. Ihre Ergebnisse werden
anschließend gemeinsam auf True Positives geprüft.

- [Manuelle Nokia-Abnahme](01-manual-device-review.md): Inhalt vollständig an den
  Claude-Agenten mit ADB-Zugang übergeben. Keine Maestro-/UI-Testautomatisierung.
- [Statisches Code- und UX-Review](02-code-and-ux-review.md): Inhalt vollständig
  an einen zweiten Agenten mit Repositoryzugang übergeben. Kein Gerät, keine
  Builds oder Testausführung, keine Implementierung.
- [Vollständiges Dateiinventar](changed-files.tsv): beiden Agents zugänglich machen.

## Analysierter Stand

| Referenz | Commit |
| --- | --- |
| Ausgeliefertes `v0.2.2` | `18fb0b4f5eb549a4321b424c04b0adeb966cb5b4` |
| Konsolidierter `feat/0.2.3-release` | `f44601aa15633d0b15d25d913077cc7df1497027` |

Der vollständige Diff umfasst **283 Dateien, 38.084 hinzugefügte und 5.883
entfernte Zeilen**; große generierte Kartenassets sind darin enthalten.
`feat/0.2.2-release` ist nicht die ausgelieferte Baseline. Der ursprüngliche
0.2.2-Squash vor dem Release-Authentifizierungsfix würde einen anderen Diff
liefern. Das Inventar wurde direkt zwischen den oben genannten SHAs erzeugt.

Diese Dokumentationsdateien gehören nicht zum fixierten Anwendungscode. Ein
späterer Kandidat braucht eine zusätzliche Delta-Prüfung. Quellcode, frühere
Gerätebelege und veröffentlichte APKs sind getrennte Nachweise.

## Aus dem Delta abgeleitete Prüfflächen

| Bereich | Wesentliche Änderungen/Risiken | Manuelle IDs | Review |
| --- | --- | --- | --- |
| Datenhaltung/Upgrade | Neue Boardmigrationen 29/30, Alias-/Beta-Übernahme, Indexerhalt, atomarer Import, Identität/Preferences und vorhandene Beziehungen | D01–D07, U01–U07 | R01 |
| Kataloge | Explizite Auswahl, Alt-Default, Queue bei laufendem Download, Verfügbarkeit/Geometrie, Reconnect | N03–N05, S01–S03 | R02 |
| Kilter | ID-Vertrag, Backfill bei Opt-in, Readback/Retry, Konflikte, kompakter Status, Opt-in-Diagnose | S04–S07 | R03 |
| Konto/Backup | Integrierter Sicherungsdialog, lokaler/Amber-Zugang, Wiederherstellung ohne Localkey, Worker/Identitätswechsel, Wrapped-Key-Ack vor Pointer, Fehlerprivatsphäre | A01–A06, U02, N05 | R04 |
| Browser/Logging | Count/Paging/Zufall/Deduplikation, Status und Aliase, Quicklog, unterschiedliche Problemstatistik/Sendtimeline | B03–B06, L01–L02 | R05 |
| Playlist/BLE | Board-/Filterkontext, Gradband-Kandidatenpools, sichtbare Relight-Aktion, Quantumgrenzen, Session-Ratelimit | L03–L05 | R06 |
| Medien/Transport | Boardbezogene Beta-Medien, Hash-/Mirrorcache, Platzhalter, lokale Schemakompatibilität, Transfer-/Relaylimits | B07, X04 | R07 |
| Updates/Release | Reminder 24/24/72 h, Permission-/Checkgates, Archivpaket/Version/Signatur, Downloadgrenzen, API-Minimum | U01, X03 | R08 |
| UI/UX | Erstsetup/Tour, einzeilige Topbar, Picker/Griffsets, Filter, Settings, Kontextinfos, Profil, Zugänglichkeit | N01–N07, B01–B03, A01–A06, X02/X05 | R09 |
| Karte/Restdelta | Städte-/Venue-Suche, zusätzliche Filter, alle Familien in Statistik, Assets/Parser, Ressourcen und Dokuverträge | X01, X06 | R10 |

## Migration ist mehr als eine Schemaänderung

Die privaten SQLDelight-Schema-/Migrationsdateien unter `secure/` sind gegenüber
`v0.2.2` unverändert. Boardmigration 29 erstellt `climb_beta_links` und übernimmt
geeignete alte Links; Migration 30 erstellt `moonboard_climb_aliases`. Das belegt
weder einen erfolgreichen Android-Upgrade noch vollständige Datenwiederherstellung.

Der manuelle Plan verlangt unter echter 0.2.2 sowohl importierte als auch per UI
angelegte Testdaten, ein unveränderliches Vorhermanifest und feldweise Vergleiche
vor/nach Netzwerkrefresh. Die Exportkategorien des Zielcodes umfassen Profil,
Assessments, Körperdaten, Workouts, allgemeine Climb-Logs, Trainingspläne,
Boardlogbook, Boardsessions, Listen, eigene Climbs und private Notizen. Zusätzliche
Tabellen, Settings, Signerzugriff und Cache-/Lesestatus werden separat inventarisiert;
ein erfolgreicher Exportvergleich allein deckt diese nicht vollständig ab.

Drei unabhängige Nachweise sind erforderlich:

1. **In-place Upgrade** der befüllten Originalinstallation ohne Datenreset.
2. **Neuinstallation** einschließlich Onboarding/Tour und neuem Datenaufbau.
3. **Restore** aus 0.2.2-Dateien bzw. echtem vorher aktivierten Cloudbackup, lokal
   und mit Amber soweit verfügbar.

Ein Feature-APK mit anderem Paket/Signatur ersetzt Nachweis 1 nicht. Zuletzt fehlte
ein passend produktionssignierter 0.2.3-Kandidat; dessen aktuelle Verfügbarkeit
muss der Geräteagent prüfen. Dieser Auftrag autorisiert keine Stable-Publikation.

## Grenzen und spätere Bewertung

Physisches BLE, zweiter 0.2.2-Peer, offizielle Kilter-App/Testkonto, Amber,
passender Upgrade-Kandidat und langfristige Reminderbeobachtung sind jeweils
eigene Voraussetzungen. Fehlen sie, bleibt der betreffende Fall BLOCKED oder
NOT TESTED; unabhängige Arbeit geht weiter. Ein einzelnes Nokia/API 35 beweist
keine Abdeckung aller Androidversionen oder Geräte.

Bekannte Kilter-Editkonflikte sind nicht automatisch neue Bugs. Alte Dokumentation
kann inzwischen korrigierte Befunde wie die Send-Zeitreihe noch aufführen. Beide
Agents müssen am fixierten Kandidaten verifizieren und unterscheiden: Regression,
Altfehler, dokumentierte Einschränkung, noch unbelegter Verdacht oder Designvorschlag.

Am Ende werden `M-*`- und `C-*`-Befunde nach Ursache zusammengeführt. Erst bei
belegtem Trigger, verletzter Anforderung und nachvollziehbarer Folge entscheiden
wir über Fix und Regressionstest. Diese Prompts sind ein Prüfauftrag, kein bereits
abgeschlossener Test oder Freigabebeleg.
