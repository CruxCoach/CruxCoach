# AI board-image prototypes

These are non-production visual prototypes for the Kilter Original 12 x 12
board with kickboard (`product_size_id=10`). They are not used by the Android
app.

## Sandstone Noir

`board_10-ai-sandstone-noir-3x.png` is the current visual target. It deliberately
separates board identity from source-photo appearance: warm, porous
sandstone-resin holds sit on a dark aubergine technical panel with a restrained
position grid. The look is intended to read as commissioned CruxCoach artwork,
not as a retouched original image. `board_10-ai-sandstone-noir-zoom.png` is a
native-resolution detail crop.

The full 3240 x 3510 preview was assembled from nine overlapping AI-generated
detail tiles. A deterministic geometry-lock pass reapplied the original alpha
mask pixel for pixel, retaining the source canvas, hold positions, rotations,
spacing, and outer silhouettes. AI generation supplies the new material,
lighting, and high-resolution surface detail; the panel and grid are generated
independently so they remain consistent across all future boards.

## Earlier baseline

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
