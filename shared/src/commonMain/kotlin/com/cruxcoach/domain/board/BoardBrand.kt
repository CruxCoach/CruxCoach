package com.cruxcoach.domain.board

/**
 * Board family a climb belongs to — the typed form of the
 * `climbs.board_brand` column (added by board-DB migration 14.sqm,
 * FEAT-027).
 *
 * `kilter` is the historical catalogue and the column's DEFAULT, so
 * every pre-0.2.0 row resolves to [KILTER] on upgrade. `moonboard` is
 * the FEAT-027 MoonBoard catalogue.
 */
enum class BoardBrand(val wireValue: String) {
    KILTER("kilter"),
    MOONBOARD("moonboard"),

    // Aurora-family boards (FEAT-031). These share Aurora's placement / LED /
    // frame model and the Aurora BLE protocol with Kilter; their catalogues
    // are mirrored per board via Blossom (manifest d-tag cruxcoach/<board>-db)
    // and they are fully interactive — browse, render, heatmap, BLE send.
    TENSION("tension"),
    GRASSHOPPER("grasshopper"),
    DECOY("decoy"),
    SOILL("soill"),
    TOUCHSTONE("touchstone"),

    // The CruxCoach Board (this project): an open, self-built LED wall driven
    // by CruxCoach's own native L1 WebSocket protocol (see
    // [CruxBoardFrameEncoder]), NOT the Aurora BLE stack. It is fully
    // interactive — authoring, per-hold LED preview and the co-occurrence
    // heatmap — but has no Aurora placement rows, so [usesAuroraProtocol] and
    // [usesAuroraPlacements] stay false while its interactive capabilities are
    // declared directly rather than delegated to the Aurora predicate.
    CRUXCOACH("cruxcoach"),

    // Map-only "info layer" families (FEAT-015 Phase 2). CruxCoach ships no
    // climb catalogue or BLE send for these, so they appear on the
    // board-locations map only — never in the browser, picker, or active-
    // board pref. wireValues mirror the locations pipeline + the
    // cruxcoach.org map's brand keys. (AURORA = the original Aurora board and
    // 12climb stay here until their catalogues are wired — FEAT-031 covers
    // only the five boards above.)
    AURORA("aurora"),
    TWELVECLIMB("12climb");

    /** True for families CruxCoach actually drives (catalogue + live board
     *  send): Kilter, MoonBoard, the five Aurora-family boards (FEAT-031) and
     *  the native CruxCoach Board. AURORA and 12climb remain map-only
     *  info-layer brands. */
    val isInteractive: Boolean
        get() = usesAuroraProtocol || this == MOONBOARD || this == CRUXCOACH

    /** Kilter + the Aurora-family boards (Tension, Grasshopper, Decoy, So iLL,
     *  Touchstone): climbs are Aurora placement-id frames, holes are lit from
     *  a placement→LED map, and the Aurora BLE protocol is shared. MoonBoard
     *  is interactive too but photo / coordinate-map based, so it is NOT
     *  Aurora-protocol. This is the single predicate the placement-geometry,
     *  LED and heatmap capabilities below delegate to. */
    val usesAuroraProtocol: Boolean
        get() = when (this) {
            KILTER, TENSION, GRASSHOPPER, DECOY, SOILL, TOUCHSTONE -> true
            else -> false
        }

    // ── Capability model ────────────────────────────────────────────────
    // The single, typed answer to "what can this board family do?". Call
    // sites ask for the *capability* they care about (board.usesLedPreview)
    // instead of re-deriving it from brand identity (board == KILTER) — the
    // intent reads at the use site and new boards only have to declare their
    // capabilities here. The Aurora-derived family (Kilter) and the
    // photo/coordinate-map family (MoonBoard) currently differ cleanly along
    // these axes; info-layer brands are non-interactive so every capability
    // is false for them.

    /** Climbs are stored as Aurora placement-id frames with measured
     *  placement geometry + per-placement LED addresses (Kilter + the
     *  Aurora-family boards). MoonBoard renders from a bundled photo + a
     *  measured hold-coordinate map, so it has no placement rows — anything
     *  that resolves placement geometry (board images, edge bounds, the
     *  co-occurrence heatmap, the LED map) must be skipped when this is
     *  false. */
    val usesAuroraPlacements: Boolean get() = usesAuroraProtocol

    /** The connected board lights individual holds from a placement→LED
     *  address map (Kilter + the Aurora-family boards, which share the Aurora
     *  BLE protocol). MoonBoard derives its own LEDs from the climb frame, so
     *  the editor/send path uses a different transport and never builds an LED
     *  map. The CruxCoach Board also drives per-hold LEDs — via its native L1
     *  SET_ROUTE frames ([CruxBoardFrameEncoder]) rather than an Aurora LED
     *  map — so this capability is decoupled from [usesAuroraProtocol]. */
    val usesLedPreview: Boolean get() = usesAuroraProtocol || this == CRUXCOACH

    /** Supports the popular-co-occurring-holds heatmap. On the Aurora-family
     *  boards (Kilter + the five) it is keyed on Aurora placement-ids; MoonBoard
     *  hold-ids aren't placement-ids, so it has no heatmap layer. The CruxCoach
     *  Board declares the heatmap capability directly (its own hold model feeds
     *  the co-occurrence layer), so this is decoupled from [usesAuroraProtocol]
     *  even though CruxCoach carries no Aurora placements. */
    val hasHeatmap: Boolean get() = usesAuroraProtocol || this == CRUXCOACH

    /** Climbs can be authored in the in-app editor — every interactive board.
     *  Kilter additionally pushes to the user's own Kilter account (see
     *  [supportsOfficialAppPublish]); every other interactive board (MoonBoard +
     *  the Aurora family: Tension/Grasshopper/Decoy/So iLL/Touchstone) publishes
     *  to the CruxCoach Nostr community only. The draft-insert and publish paths
     *  thread the active board's brand explicitly — not [fromLayoutId], which
     *  can't disambiguate the Aurora-family layout-ids from Kilter's — so each
     *  authored climb is tagged with, and stays on, its own board. Info-layer
     *  brands (aurora, 12climb) aren't interactive: no catalogue, no editor. */
    val supportsAuthoring: Boolean get() = isInteractive

    /** Authored climbs can be mirrored to the board vendor's own app
     *  (Kilter → the user's Kilter account). MoonBoard is CruxCoach-community
     *  only: there is no CruxCoach→official-MoonBoard-app publish path, so
     *  the official-app leg of publishing is skipped when this is false. */
    val supportsOfficialAppPublish: Boolean get() = this == KILTER

    /** Human-facing brand name (proper noun — not localized). Used by the
     *  board picker, what's-new and map chips so a newly-promoted board needs
     *  no per-board string resource. */
    val displayName: String
        get() = when (this) {
            KILTER -> "Kilter"
            MOONBOARD -> "MoonBoard"
            TENSION -> "Tension"
            GRASSHOPPER -> "Grasshopper"
            DECOY -> "Decoy"
            SOILL -> "So iLL"
            TOUCHSTONE -> "Touchstone"
            CRUXCOACH -> "CruxCoach Board"
            AURORA -> "Aurora"
            TWELVECLIMB -> "12 Climb"
        }

    companion object {
        /** The map-only info-layer families, as a group (drives the map's
         *  "other boards" filter chip). */
        val INFO_LAYER: List<BoardBrand>
            get() = entries.filter { !it.isInteractive }

        /**
         * Parse a `board_brand` column value. Defaults to [KILTER] for
         * null / empty / unrecognised values: a missing brand can only mean
         * a row that predates the multi-board split (Kilter by definition),
         * and an unrecognised one is safest shown as the historical default
         * rather than dropped.
         */
        fun fromWire(value: String?): BoardBrand =
            entries.firstOrNull { it.wireValue == value } ?: KILTER

        /**
         * Strict parse for UNTRUSTED wire input (community-climb ingest):
         * returns null for null/empty/unrecognised instead of defaulting to
         * [KILTER], so a forged or unknown `board_brand` tag can be REJECTED
         * rather than silently mis-filed onto the Kilter board. DB-column reads
         * keep using the lenient [fromWire] (a missing column legitimately means
         * a pre-split Kilter row); only the ingest gate needs this.
         */
        fun fromWireOrNull(value: String?): BoardBrand? =
            entries.firstOrNull { it.wireValue == value }

        /**
         * Derive the board family from a layout id — the single source of
         * truth for "which board does this layout belong to". MoonBoard
         * variant layouts (2/4/5/6 via [MoonBoardVariant.fromLayoutId]) →
         * [MOONBOARD]; everything else (Kilter Original 1, Homewall 8, …) →
         * [KILTER]. Used wherever only a layout_id is in hand and the brand
         * must be inferred rather than threaded through — notably the
         * local-draft insert and community-climb ingest write paths, so an
         * authored or received MoonBoard climb lands with the right
         * `board_brand` automatically.
         *
         * NOTE: this only disambiguates Kilter vs MoonBoard. The Aurora-family
         * boards (Tension, Grasshopper, Decoy, So iLL, Touchstone) reuse low
         * layout-ids that OVERLAP Kilter's, so a layout_id alone can't tell
         * them apart — they would resolve to [KILTER] here. Any Aurora-family
         * write path must therefore thread the active board's brand explicitly
         * instead of calling this — which the draft-insert (insertLocalDraft)
         * and publish (buildCommunityClimbEvent) paths do, so authoring works
         * correctly for every interactive board.
         */
        fun fromLayoutId(layoutId: Long): BoardBrand =
            if (MoonBoardVariant.fromLayoutId(layoutId) != null) MOONBOARD else KILTER
    }
}
