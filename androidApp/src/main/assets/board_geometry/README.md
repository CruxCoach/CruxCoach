# Bundled board geometry

One small SQLite file per Aurora-family board with the hold geometry the app
needs to draw, size-fit and light a climb: `placements`, `product_sizes`,
`board_images`, `leds`, `placement_roles` (the app's own schema, brand-namespaced).

Why: community climbs arrive live over Nostr and carry only `frames`
(placement ids and roles). The geometry used to come only with a board's full
catalogue; since 0.2.3 a user can skip or delete catalogues. The app seeds a
board from here when it has no geometry (`BundledBoardGeometry`,
`BoardSyncManager.seedBundledGeometry`), without overwriting imported rows; the
next catalogue import replaces the seeded rows.

Built by `scripts/build_board_geometry_assets.py` from the signed catalogue
manifests (pubkey, event id, BIP-340 signature, d-tag and chunk SHA-256
checked like the app does), using the importer's own SQL. Checked on device:
Kilter and So iLL rows are identical to a real catalogue import.

Not included: Quantum (authorised eWalls snapshot; bundling in the APK needs
an owner decision) and MoonBoard (geometry bundled with its images).

## Rights

Functional hardware reference data (hold positions, sizes, LED addresses),
the same rows the signed catalogue chunks already distribute. The boards'
makers keep their rights; CruxCoach claims none and the files are not covered
by its GPLv3 license. Basis and removal contacts: `LEGAL.md` ("Bundled board
geometry") and `THIRD_PARTY_LICENSES.md`.

## Sources
| Board | Manifest event | Manifest created_at | Chunk | Chunk SHA-256 |
|---|---|---|---|---|
| decoy | c944102ad87040bb6f1c352cbd68dfc45e08e88907d2c7b123068d9e410bb1e4 | 1789818530 | decoy-full | 9ed7db56fcc5715a8867eee5fe88a9c97b560010162056f189c614b597296b5a |
| grasshopper | d43fce1fc96745ad653c8b0e0411af126e233f1ef73a1b6bdf39c8e5b5f32a7e | 1789818528 | grasshopper-full | cb450f80436ce09cac439f83df3b352cade32e71717011a1c88c6b54ae69664f |
| kilter | 48eb309959a241b13266e94cbed46560be067a22ea33d5dc8283ff6e3bd1808f | 1789946006 | meta | 0a09d0f0d9901837450bd15245e3ec43d9b7d5d7dbdd4baef0be5bb8976e8433 |
| soill | 592852a162a5d805a8985c6296e65fc99c3c513da61797e48a2290602aeb99ec | 1789818532 | soill-full | 04a896f9a773c8c71b81c74ab58eec2d7d22b692a50e8c57ab7330b7d6a18212 |
| tension | 063c4b66ff562fe8e3c22a77492203891b60615534427a29468c1b2af87972bc | 1790222624 | tension-full | d769fa0e21761e4e61b543f0eae2dc82ac85e2fcb44a9a69d4e1d34da6617444 |
| touchstone | bceb8ca2130926e42e691fc73c23bf79f074d24aa86a3d26b37c6e0d1c487500 | 1789818534 | touchstone-full | 60e953f7144bc27f7ddaefe71a1bcebcb5bccf49b519a6138fea6a8da9073712 |
