# AI board-image prototypes

These are non-production visual prototypes for the Kilter Original 12 x 12
board with kickboard (`product_size_id=10`). They are not used by the Android
app.

## Graphite Studio

`board_10-ai-graphite-studio-3x.png` is the current visual target. It retains the
real board's neutral-gray hold identity while increasing perceived depth with
high-resolution surface detail, directional edge light, tight contact
occlusion, and broader cast shadows. The original dark void is replaced by a
custom blue-black graphite panel with restrained facets and coordinate crosses.
`board_10-ai-graphite-studio-zoom.png` is a native-resolution detail crop.

The full 3240 x 3510 preview was assembled from nine overlapping AI-generated
hold-detail tiles. A deterministic geometry-lock pass reapplied the original alpha
mask pixel for pixel, retaining the source canvas, hold positions, rotations,
spacing, and outer silhouettes. AI generation supplies the high-resolution
surface detail and the original graphite panel; deterministic compositing keeps
the neutral hold color, lighting system, and position grid consistent across
future boards.

## Earlier studies

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
