# AI board-image prototype

`board_10-ai-full-preview-3x.png` is a non-production visual prototype for the
Kilter Original 12 x 12 board with kickboard (`product_size_id=10`). It is not
used by the Android app.

The preview was assembled from nine overlapping AI-edited image tiles. The
original bundled asset supplied the immutable alpha mask for every connected
hold component, so the final 3240 x 3510 preview retains the source canvas,
hold positions, rotations, spacing, and outer silhouettes. AI editing supplies
the high-resolution surface material, lighting, and micro-detail. A local
graphite panel is included so the transparent hold layer can be judged against
CruxCoach's dark UI.

Known prototype limitations:

- Inner details such as bolt wells and secondary screw marks are not yet
  independently registered and can drift slightly within a locked silhouette.
- Some tonal variation remains between independently generated tiles.
- This experiment does not change the existing image provenance or legal
  analysis. Production adoption needs a separate review.

