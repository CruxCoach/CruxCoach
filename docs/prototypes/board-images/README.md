# AI board-image prototypes

These are non-production visual prototypes for the Kilter Original 12 x 12
board with kickboard (`product_size_id=10`). They are not used by the Android
app.

## Clean Gray

`board_10-ai-clean-gray-native.png` is the current visual target. It retains the
real board's neutral-gray hold identity while using smooth matte polyurethane,
controlled studio light, tight contact occlusion, and broader cast shadows for
depth. It deliberately contains no stone pores, random grain, sharpening, or
artificial enlargement. The 2853 x 3091 composition stays at the native output
resolution of its detail tiles. `board_10-ai-clean-gray-zoom.png` is an unscaled
1080 x 1080 inspection crop.

The full 3240 x 3510 preview was assembled from nine overlapping AI-generated
hold-detail tiles. A deterministic geometry-lock pass reapplied the original alpha
mask pixel for pixel, retaining the source canvas, hold positions, rotations,
spacing, and outer silhouettes. AI generation supplies the high-resolution
clean hold rendering and the original graphite panel; deterministic compositing
keeps the neutral hold color, lighting system, and position grid consistent
across future boards.

## Earlier studies

`board_10-ai-graphite-studio-3x.png` used a porous surface treatment plus
post-sharpening. It is retained for comparison but rejected because the
combination reads as noise when viewed at native size.

`board_10-ai-sandstone-noir-3x.png` explored a deliberately different warm
material treatment. It remains as a useful style study but is no longer the
target because the physical holds are gray.

`board_10-ai-full-preview-3x.png` was the first geometry-lock experiment. It
proved the tiled workflow, but intentionally stayed close to the original gray
material and is retained only for comparison.

Known prototype limitations:

- Inner details such as bolt wells and secondary screw marks are not yet
  independently registered and can drift slightly within a locked silhouette.
- Exact real-world micro-topology needs a per-hold photo or scan catalogue;
  reference-image generation alone cannot guarantee a manufacturing-grade
  digital twin.
- This experiment does not change the existing image provenance or legal
  analysis. Production adoption needs a separate review.
