# Hold types

One file per board with a hold-type label for every placement
([#63](https://github.com/CruxCoach/CruxCoach/issues/63)). They are read by `HoldTypeAssets`. A board without a file has
no hold types.

| File | Board | Placements | With a type |
|---|---|---:|---:|
| `kilter.json` | Kilter (Original, Homewall) | 1,191 | 1,140 |
| `moonboard.json` | MoonBoard (all seven generations) | 1,022 | 1,022 |
| `tension.json` | Tension (Board 1, Board 2 Mirror, Board 2 Spray) | 1,683 | 1,299 |
| `grasshopper.json` | Grasshopper | 466 | 466 |
| `decoy.json` | Decoy | 1,036 | 454 |
| `soill.json` | So iLL | 340 | 0 (235 with a direction) |
| `shinywall.json` | Shiny Wall (five wall sizes; the same holds repeat across sizes) | 2,042 | 2,042 |

The files hold labels only, with no images and no climbs. Placements without a usable picture in the app have no
type. Each such entry says why in `review`.

## Format

`cruxcoach-hold-types-v1`, parsed by `HoldTypeDataset` (`shared/.../domain/holdtypes/HoldTypes.kt`). Per placement:
- the type (jug, edge, sloper, pinch, pocket, volume, foot chip) and an optional second grip
- a size class and the measured footprint
- the direction the usable side faces, in eight steps or all round
- foot only
- a confidence and a source for each field
- the reasons it is on the review list

`referenceSet` lists the holds of the blind review in test builds. `blindRound` is added once a blind round on the
board is evaluated: its date, the holds answered and how often the answers agreed with the labels. The statistics show
a board's hold types only when the board's file has it. The same step sets each type's `confidence.type` to the share
of its reference holds whose label was right, and the statistics count a type only from 0.7.

Aurora boards and Shiny Wall are keyed by placement id. MoonBoard is keyed by layout (generation) and hold id, and
its reference keys are `layout * 1000 + hold id`.

How the labels were made, and how sure they are, is in the reports linked from #63.

## Licence

The label files in this folder (`*.json`) are licensed under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Credit them as "Hold types by CruxCoach
(https://github.com/CruxCoach/CruxCoach), CC BY 4.0". The app's code stays under GPL-3.0 (see `LICENSE`).

The labels describe the boards. The boards, their holds and their images belong to their makers
(see `board_images/README.md`).
