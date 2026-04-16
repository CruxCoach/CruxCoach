# CruxCoach Brand Assets

Master source and exported renders of the CruxCoach logo and app icon.

| File | Use |
|------|-----|
| `cruxcoach-logo-final.svg` | Master vector source — edit this, re-export |
| `play_store_icon_512.png` | 512×512 store-listing icon (currently for Zapstore) |
| `preview/logo-{48,256,512}.png` | Pre-rendered raster previews for documentation and quick embeds |

## Derived assets

The Android launcher icons under
`androidApp/src/main/res/mipmap-*/ic_launcher*.png` and
`androidApp/src/main/res/drawable/ic_launcher_monochrome.xml` are exported
from `cruxcoach-logo-final.svg` via Android Studio's *Image Asset Studio*
(File → New → Image Asset). When the master changes, regenerate them so all
densities stay in sync.

## Licensing

These assets are **not** covered by the repository's GPLv3 source-code
license. See [`LICENSE`](LICENSE) in this directory for what is permitted,
and [`../TRADEMARK.md`](../TRADEMARK.md) for the broader name + logo policy.

If you fork CruxCoach and intend to distribute modified binaries, replace
the master SVG and re-export the launcher icons before publishing.
