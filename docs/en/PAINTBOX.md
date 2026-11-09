# PaintBox

PaintBox paints pictures on your board with its LEDs: every hold is a pixel.
Open it in **Settings**, under Extras: **PaintBox**. It starts with your active board;
the board button above the picture switches to another one.

## Paint

- Pick the brush or the eraser and a color, then tap or swipe holds with one
  finger. Two fingers zoom and move the board.
- One stroke is one step for undo and redo. **Clear picture** in the ⋮ menu
  removes all colors and can be undone too.
- **Save** and **Save as** in the ⋮ menu keep the picture on your phone under a
  name. **Pictures** lists them by board and loads one together with its board,
  including MoonBoard hold sets; a picture of another board asks before the app
  switches to it. Pictures are separate from climbs, are never shared or
  published, and are not part of the backup.
- Only holds with an LED on the chosen board size can be painted.
- If the app's board changes elsewhere, for example when the connection sheet
  connects another board, PaintBox opens that board. With unsaved changes it
  asks first.
- If the board's catalogue is missing, PaintBox offers the catalogue settings.

## Light up the board

- **Connect board** at the bottom, or the Bluetooth button at the top, opens
  the usual connection sheet. Connecting never sends a picture.
- **Send** sends the picture once. **Live** makes the board follow every stroke.
  Turning it off stops sending; the board keeps what it shows.
- Live painting pauses when the connection drops, when you leave the screen or
  the app, and when you change boards. You turn it on again yourself.
- While a session, a playlist or board sharing holds the board, PaintBox
  sends nothing and Live stays off.

| Board | Colors |
| --- | --- |
| Kilter, Tension, Grasshopper, Decoy, So iLL, Touchstone | Every board color. Large bright pictures are shown dimmer, so the LED box never draws more than in its own start-up test (every LED in one full color). An older LED box (API 2) lights up to 127 holds at once and has a tighter power limit. |
| MoonBoard | Green, blue and red. |
| Quantum | Four colors, up to 92 LEDs each. A picture uses its own layers, so other layers must be off before you paint. Lighting a climb replaces the picture, as on the other boards. |
| Shiny Wall | Not supported yet. |

## Turn a photo into a picture

**Use a photo** in the ⋮ menu opens Android's photo picker. The photo replaces
the board photo while the holds stay in place:

- Drag it with one finger; zoom and turn it with two.
- Each hold takes the board color nearest to the photo at its centre.
  Transparent parts and holds outside the photo stay off.
- **Background** leaves one color of the photo off, for photos without
  transparency: tap it, then tap that color in the photo. Tap **Background**
  again to undo the choice.
- **Apply** applies the photo as one undo step. ✕ or Back brings back the
  previous picture, on the board too if it showed the photo live.

Only the hold colors are saved, never the photo.
