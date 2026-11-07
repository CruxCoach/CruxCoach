# Board scan data

Data for the board scan (#65), read by `ScanCatalogueLoader`
(`androidApp/src/main/java/com/cruxcoach/android/boardscan/ScanCatalogue.kt`).

## `moonboard_<variant>_marks.txt`

The board's own coloured holds on the MoonBoard 2016 and 2024 (the yellow holds of 2016, the blue painted holds of
2024). They pin the regular LED grid to the right column and row (`GridReader`), and a colourful spot on one of them
is paint, not a lit LED.

One mark per line: `x y hue`. `x` and `y` are pixels of the bundled board image
(`board_images/moonboard_<variant>.webp`) with y pointing up, the unit the scan uses for MoonBoard holes; `hue` is
the mark's mean hue in degrees. Each mark is the chroma-weighted centre of one coloured region of that image. The
numbers are derived from the bundled images described in `board_images/README.md`; this folder holds no image.
