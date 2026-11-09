# PaintBox

Open **Settings → Extras group → PaintBox** directly from the overview. Extras is
a separate group outside App & Help. Color overlays share the translucent halo
and small core used by the creator and logbook heatmaps, keeping the photo visible.
This only changes the preview, not the colors transmitted to LEDs.
The active board is preselected; the board
button uses the existing board picker. Tap or drag with one finger to paint or
erase holds. Use two fingers to zoom and pan. A complete stroke is one undo step.
The color button uses the same named palette as the board settings.

Pictures are editable local files, separate from climbs, logbooks and public
sharing. Save, Save as, and Pictures manage named images. The library groups
images by board family, shows their layout and size, and loads the exact saved
configuration, including MoonBoard hold sets. Missing catalogues can be downloaded
through the existing settings page. Unsaved edits are checked before navigating.

The Bluetooth button opens the usual connection sheet. Connecting never sends a
picture. **Send now** sends once. Turning on the lamp sends the current picture
and enables live painting. Turning it off stops subsequent writes; it does not
clear the wall. Controllers may clear their LEDs when disconnected. Leaving the
screen, backgrounding, changing boards or losing the connection pauses live mode.
An already executing transmission may finish; queued changes are discarded.

Kilter, Tension, Grasshopper, Decoy, So iLL and Touchstone use the existing Aurora
encoder and power limits. MoonBoard offers green, blue and red using native role
tokens. Quantum uses four colors and separate persistent PaintBox identities,
not the climb rack identities. The verified limit is 92 LEDs per color. Unknown
other layers block painting before mutation because their overlap cannot be
verified. The owner must remove those layers explicitly. Quantum replaces its
own previous picture, preserving other identities; transmission errors can leave
a partial picture and are reported as such. The generic clear-all command is
never used for Quantum painting.

## Verification

Local validation on 2026-11-06: 49 selected JVM/Robolectric tests passed, with
zero failures or skips (PaintBox, settings navigation, color semantics, connection
cleaning/ownership, board binding and Aurora pacing). This compiled the application
and test sources as test prerequisites. At the user's subsequent request for
phone installation, a local debug APK was built with the canonical feature identity
from `scripts/feature_identity.py` and installed on a Pixel 8 Pro. Installation and
cold start succeeded; Android displayed its initial permission dialog.
The Maestro flow, complete CI checks and physical LED-board acceptance have not run.

Focused JVM tests: `:androidApp:testDebugUnitTest --tests 'com.cruxcoach.android.ui.paintbox.*'`.
Also run the existing settings navigation and board controller tests affected by
navigation and connection lifetime changes. The selected Maestro flow is
`flows/paintbox.yaml`; it requires an onboarded German-language feature APK and
a downloaded board catalogue. It never connects or writes to a physical board.

Hardware acceptance: paint multiple colors, erase, pause, edit offline and resume;
compare the complete wall to the preview. Repeat a single send, disconnect and
reconnect. On Quantum test occupied layers, 92/93 LEDs, and a failed multi-color
transfer. Full builds/lint/suites belong in CI per AGENTS.md; source and JVM tests
alone do not certify a controller or a published APK.

## Import a phone image

The image icon immediately left of Bluetooth opens Android's photo picker.
The selected image replaces the board photo while hold positions stay fixed at
normal board zoom. Drag with one finger; pinch and rotate with two fingers.
The image starts fitted and centered. The highlighted image icon can replace it.

Colors are sampled at hold centers and mapped to the nearest supported board
color. Pixels below 50% opacity and positions outside the image turn holds off.
BG Color shows “Pick Background Color”: tap the image to exclude that color and
nearby RGB colors (Euclidean distance up to 24). Tap BG Color again to clear the
exclusion, then again to pick another color. Existing transparent overlays remain
visible on colored holds; neutral dots identify positions that are off.

OK applies the preview as one undo step. Cancel or Back restores the previous
picture and undo/redo state. With the lamp enabled, previews are sent live; Cancel
also restores a previously transmitted preview on the same connected board.
Bluetooth failures and Quantum capacity limits use the existing error handling.
Saving, loading and board selection are disabled until the import is finished.
Only the resulting hold colors are saved, not the source image or transformation.
Decoding preserves alpha and orientation and limits the long edge to 2048 pixels.
An unconfirmed import survives configuration changes but not process termination.
