# PaintBox

PaintBox paints pictures on your board with its LEDs: every hold is a pixel.
Open it in **Settings**, under Extras: **PaintBox**. It starts with your active board;
the board button above the picture switches to another one.

## Paint

- Pick the brush or the eraser and a color, then tap or swipe holds with one
  finger. Two fingers zoom and move the board.
- The **pipette** next to the eraser takes a color from the picture: tap it, then
  tap a hold that has a color. That color becomes the brush color and the brush
  is the tool again. A hold without a color picks nothing and says so. This is
  how you reuse a color a photo brought along: an Aurora board has 255 colors,
  the color sheet names 42. Such colors also appear in the color sheet under
  **In this picture**. On MoonBoard and Quantum the pipette picks one of the
  board's few colors.
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
  asks first. If you keep the picture, **Send** and **Live** stay off for it,
  because the LEDs and the power limit belong to the exact layout and size.
  **Switch** then opens the app's board with an empty picture; save the picture
  first to keep it.
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
| Kilter, Tension, Grasshopper, Decoy, So iLL, Touchstone | Every board color. Large bright pictures are shown dimmer, so the LED box draws about what it draws in the LED test of the maker's set-up guide (every LED in one full color), not more. The limit depends on the exact board size, so choose the size of your wall. An older LED box (API 2) lights up to 127 holds at once and has a tighter power limit. |
| MoonBoard | Green, blue and red. |
| Quantum | Four colors, up to 92 LEDs each. A picture uses its own layers, so other layers must be off before you paint. Lighting a climb replaces the picture, as on the other boards. |
| Shiny Wall | Not supported yet. |

## Turn a photo into a picture

**Use a photo** in the ⋮ menu opens Android's photo picker. The photo replaces
the board photo while the holds stay in place:

- A new photo is put in place for you: its subject fills the board, and it
  is shifted and scaled a little until its outlines fall between holds, its
  lines run over holds and a picture that mirrors lights a mirrored pattern.
- Drag it with one finger; zoom and turn it with two. **Align** then settles
  it on the holds again, close to where you put it; it keeps your rotation.
- Each hold looks at the area around it and takes the color that covers most
  of it, as the nearest board color. A hold is lit when the picture covers
  about half of it; along a line thinner than the hold spacing less is enough,
  so the line stays connected: a thin line becomes one unbroken chain of
  holds, and lines that run close together stay apart. A glow around a shape
  does not count as the shape. Shapes on a background that is left off shine
  at full brightness; a photo that fills the frame keeps its light and dark.
- Transparent parts, black parts and holds outside the photo stay off: an LED
  cannot show black. A black shape on a background that is left off lights
  white instead.
- **Keep background dark** leaves the photo's background off. PaintBox finds
  the background at the edge of the photo, whether black, white or another
  flat color, and the switch starts on when the edge is clearly one color.
  For a photo without such an edge it starts off. Slight noise and soft
  gradients in the background are covered.
- **Background** picks that color yourself: tap it, then tap the color in the
  photo. A picked color replaces the one PaintBox found and turns the switch
  on. Tap **Background** again to undo the choice.
- **Apply** applies the photo as one undo step. ✕ or Back brings back the
  previous picture, on the board too if it showed the photo live.

Only the hold colors are saved, never the photo.
