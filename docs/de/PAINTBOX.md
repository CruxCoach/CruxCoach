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

## Bild vom Smartphone importieren

Das Bild-Icon direkt links neben Bluetooth öffnet die Android-Bildauswahl.
Das gewählte Bild ersetzt das Wandfoto; die Griffpositionen bleiben in der
Standardansicht fest. Ein Finger verschiebt das Bild, zwei Finger zoomen und
drehen es. Zu Beginn wird es vollständig eingepasst und zentriert. Über das aktive
Bild-Icon lässt es sich austauschen.

Die Farbe am Griffmittelpunkt wird auf die nächstmögliche Boardfarbe abgebildet.
Pixel unter 50 % Deckkraft und Positionen außerhalb des Bildes schalten Griffe aus.
BG Color zeigt „Pick Background Color“: Ein Tipp auf das Bild blendet diese Farbe
und ähnliche RGB-Farben aus (euklidischer Abstand bis 24). Erneutes Tippen auf BG
Color löscht die Auswahl; ein weiterer Tipp aktiviert die Pipette erneut.
Farbige Griffe behalten die transparente Darstellung, ausgeschaltete Positionen
bleiben als neutrale Punkte sichtbar.

OK übernimmt die Vorschau als einen Undo-Schritt. Cancel oder Zurück stellt den
vorherigen Zustand samt Undo/Redo wieder her. Bei aktiver Glühbirne folgt das Board
der Vorschau live; Cancel stellt auch eine bereits übertragene Vorschau am selben
verbundenen Board wieder zurück. Verbindungsfehler und Quantum-Grenzen werden wie
bisher angezeigt. Speichern, Laden und Boardwechsel sind während des Imports
gesperrt. Gespeichert werden nur die resultierenden Grifffarben, nicht Quelldatei
oder Transformation. Das Laden erhält Alpha und Bildausrichtung und begrenzt die
lange Kante auf 2048 Pixel. Ein unbestätigter Import bleibt bei Konfigurationswechseln
erhalten, wird bei Prozessbeendigung jedoch verworfen. 
