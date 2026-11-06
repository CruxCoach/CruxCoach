# PaintBox

Öffnen über **Einstellungen → Gruppe Extras → PaintBox**, direkt aus der
Einstellungsübersicht. „Extras“ ist eine eigene Gruppe außerhalb von „App & Hilfe“.
Die Farbflächen verwenden den transparenten Farbhof mit kleinem Farbmittelpunkt
der Heatmaps im Routeneditor und in der Logbuch-Statistik. Das Boardfoto bleibt
sichtbar; die an die LEDs gesendeten Farbwerte ändern sich dadurch nicht.
Das aktive Board ist vorbelegt;
die Boardtaste öffnet den vorhandenen Board-Picker. Mit einem Finger Griffe
antippen oder überstreichen, um sie zu bemalen oder zu radieren. Zwei Finger
zoomen und verschieben. Ein Pinselstrich ist ein Rückgängig-Schritt. Die Farbwahl
verwendet dieselbe benannte Palette wie die Boardkonfiguration.

Speichern, Speichern unter und Bilder verwalten lokale, weiter bearbeitbare
Bilder. Die Bibliothek gruppiert nach Boardfamilie und zeigt Layout und Größe.
Laden übernimmt die gespeicherte Konfiguration einschließlich MoonBoard-Griffsets.
Fehlende Kataloge lassen sich über die vorhandene Einstellungsseite herunterladen.
Vor einem Wechsel werden ungespeicherte Änderungen abgefragt. Bilder sind keine
Kletterrouten und werden nicht veröffentlicht.

Bluetooth öffnet den vorhandenen Verbindungsdialog. Verbinden allein sendet
nichts. **Jetzt übertragen** sendet einmalig. Die Glühbirne sendet beim Einschalten
das aktuelle Bild und aktiviert Live-Malen. Ausschalten pausiert weitere
Übertragungen, ohne die Anzeige zu löschen. Manche Controller löschen ihre LEDs
beim Trennen. Verlassen, App-Hintergrund, Boardwechsel und Verbindungsverlust
pausieren Live-Malen. Eine bereits laufende Übertragung kann noch abschließen;
wartende Änderungen werden verworfen.

Kilter, Tension, Grasshopper, Decoy, So iLL und Touchstone verwenden den vorhandenen
Aurora-Encoder samt Leistungsgrenzen. MoonBoard bietet Grün, Blau und Rot über
sein Rollenprotokoll. Quantum nutzt vier Farben und eigene dauerhafte
PaintBox-Identitäten, getrennt von den Routenebenen. Es gilt die verifizierte
Grenze von 92 LEDs je Farbe. Andere aktive Ebenen mit unbekannter Geometrie
blockieren die Übertragung vor der ersten Änderung; deren Besitzer muss sie
entfernen. Nur eigene PaintBox-Ebenen werden ersetzt. Ein Übertragungsfehler kann
ein Teilbild hinterlassen und wird entsprechend angezeigt. PaintBox verwendet
bei Quantum niemals den allgemeinen Befehl zum Löschen aller Ebenen.

Prüfung: gezielte `PaintBox`-JVM-Tests und der Geräteablauf `flows/paintbox.yaml`.
Am 06.11.2026 bestanden 49 gezielte JVM-/Robolectric-Tests ohne Fehler oder
übersprungene Tests. Auf anschließenden Wunsch zur Smartphone-Installation wurde
eine lokale Debug-APK mit der durch `scripts/feature_identity.py` festgelegten
Feature-Kennung gebaut und auf dem Pixel 8 Pro installiert. Installation und
Kaltstart waren erfolgreich; Android zeigte die anfängliche Berechtigungsabfrage.
Maestro, vollständige CI-Prüfungen und echte LED-Boards wurden noch nicht geprüft.
Die Hardware-Abnahme und ihre Grenzen sind im [englischen Prüfablauf](../en/PAINTBOX.md#verification)
beschrieben. Vollständige Builds, Lint und Tests laufen gemäß AGENTS.md in CI.
