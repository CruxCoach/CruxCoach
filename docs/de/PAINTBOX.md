# PaintBox

PaintBox malt Bilder mit den LEDs deines Boards: Jeder Hold ist ein Pixel.
Du öffnest es in den **Einstellungen** unter Extras: **PaintBox**. Es startet mit deinem
aktiven Board; die Board-Taste über dem Bild wechselt zu einem anderen.

## Malen

- Wähle Pinsel oder Radierer und eine Farbe und tippe oder streiche mit einem
  Finger über die Holds. Zwei Finger zoomen und verschieben das Board.
- Ein Strich ist ein Schritt für Rückgängig und Wiederholen. **Bild leeren** im
  ⋮-Menü entfernt alle Farben und lässt sich ebenfalls rückgängig machen.
- **Speichern** und **Speichern unter** im ⋮-Menü legen das Bild unter einem Namen
  auf deinem Handy ab. **Bilder** zeigt sie nach Board geordnet und lädt eines samt seinem
  Board, auch mit den MoonBoard-Holdsets; bei einem Bild für ein anderes Board
  fragt PaintBox, bevor die App auf dieses Board wechselt. Bilder sind keine
  Climbs, werden nie geteilt oder veröffentlicht und sind nicht Teil des Backups.
- Bemalen lassen sich nur Holds, die auf der gewählten Boardgröße eine LED haben.
- Ändert sich das Board der App an anderer Stelle, etwa wenn der
  Verbindungsdialog ein anderes Board verbindet, öffnet PaintBox dieses Board.
  Bei ungespeicherten Änderungen fragt es vorher.
- Fehlt der Katalog des Boards, bietet PaintBox die Katalog-Einstellungen an.

## Das Board leuchten lassen

- **Board verbinden** unten oder die Bluetooth-Taste oben öffnet den gewohnten
  Verbindungsdialog. Verbinden allein sendet kein Bild.
- **Senden** sendet das Bild einmal. Mit **Live** folgt das Board jedem Strich.
  Ausschalten beendet das Senden; das Board behält, was es zeigt.
- Live-Malen pausiert, wenn die Verbindung abbricht, wenn du den Bildschirm oder
  die App verlässt und wenn du das Board wechselst. Wieder einschalten tust du
  es selbst.
- Solange eine gemeinsame Sitzung oder die Board-Freigabe das Board hält,
  sendet PaintBox nichts und Live bleibt aus.

| Board | Farben |
| --- | --- |
| Kilter, Tension, Grasshopper, Decoy, So iLL, Touchstone | Jede Board-Farbe. Große helle Bilder leuchten gedimmt, damit die LED-Box nie mehr Strom zieht als in ihrem eigenen Starttest (jede LED in einer vollen Farbe). Eine ältere LED-Box (API 2) leuchtet bis zu 127 Holds auf einmal und hat eine engere Leistungsgrenze. |
| MoonBoard | Grün, Blau und Rot. |
| Quantum | Vier Farben, bis zu 92 LEDs je Farbe. Ein Bild nutzt eigene Layer; andere Layer müssen vor dem Malen aus sein. Ein beleuchteter Climb ersetzt das Bild, wie bei den anderen Boards. |
| Shiny Wall | Noch nicht unterstützt. |

## Ein Foto als Bild übernehmen

**Foto verwenden** im ⋮-Menü öffnet die Android-Fotoauswahl. Das Foto ersetzt
das Boardfoto, die Holds bleiben an ihrem Platz:

- Ein Finger verschiebt es, zwei Finger zoomen und drehen es.
- Jeder Hold bekommt die Board-Farbe, die dem Foto an seiner Mitte am nächsten
  kommt. Transparente Stellen und Holds außerhalb des Fotos bleiben aus.
- **Hintergrund** lässt eine Farbe des Fotos aus, für Fotos ohne Transparenz:
  Tippe darauf und dann im Foto auf diese Farbe. Ein weiterer Tipp auf
  **Hintergrund** nimmt die Wahl zurück.
- **Übernehmen** übernimmt das Foto als einen Schritt zum Rückgängigmachen. ✕
  oder Zurück holt das vorige Bild zurück, auch auf dem Board, wenn es das Foto
  live gezeigt hat.

Gespeichert werden nur die Farben der Holds, nie das Foto.
