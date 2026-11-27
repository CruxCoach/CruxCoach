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

    // Walltopia Quantum Board. Its catalogue and diode geometry are fetched
    // from a dedicated, independently versioned Blossom manifest. Quantum
    // does NOT speak Aurora's protocol: it has its own MODBUS-framed BLE
    // adapter, while still using the shared coordinate/placement renderer.
    QUANTUM("quantum"),

    // Shiny Wall Standard family (st0..st5). Its own text BLE protocol (one
    // ASCII state vector per wall), its own catalogue manifest and a
    // coordinate geometry rendered by the shared placement renderer.
    SHINYWALL("shinywall"),

    // Map-only "info layer" families (FEAT-015 Phase 2). CruxCoach ships no
    // climb catalogue or BLE send for these, so they appear on the
    // board-locations map only — never in the browser, picker, or active-
    // board pref. wireValues mirror the locations pipeline + the
    // cruxcoach.org map's brand keys. (AURORA = the original Aurora board and
    // 12climb stay here until their catalogues are wired — FEAT-031 covers
    // only the five boards above.)
    AURORA("aurora"),
    TWELVECLIMB("12climb");

    /** True for families CruxCoach actually drives (catalogue + BLE): Kilter,
     *  MoonBoard, the five Aurora-family boards (FEAT-031), Quantum and Shiny
     *  Wall. AURORA and 12climb remain map-only info-layer brands. */
    val isInteractive: Boolean get() = transport != BoardTransport.NONE

    /** Map-only family: shown on the board-locations map, never in the
     *  browser, picker or download lists. */
    val isInfoLayer: Boolean get() = !isInteractive

    /** How the app talks to this family's controller. Exhaustive on purpose:
     *  a new family must decide its transport instead of silently inheriting
     *  the Aurora send path. */
    val transport: BoardTransport
        get() = when (this) {
            KILTER, TENSION, GRASSHOPPER, DECOY, SOILL, TOUCHSTONE -> BoardTransport.AURORA
            MOONBOARD -> BoardTransport.MOONBOARD_UART
            QUANTUM -> BoardTransport.QUANTUM
            SHINYWALL -> BoardTransport.SHINY_WALL
            AURORA, TWELVECLIMB -> BoardTransport.NONE
        }

    /** Installations that never chose their catalogues download every
     *  interactive family by default. A family added later must not join
     *  that default unasked, so it is downloaded only once the user picks it. */
    val downloadedByDefault: Boolean get() = isInteractive && this != SHINYWALL

    /** Kilter + the Aurora-family boards (Tension, Grasshopper, Decoy, So iLL,
     *  Touchstone): climbs are Aurora placement-id frames, holes are lit from
     *  a placement→LED map, and the Aurora BLE protocol is shared. MoonBoard
     *  is interactive too but photo / coordinate-map based, so it is NOT
     *  Aurora-protocol. This is the single predicate the placement-geometry,
     *  LED and heatmap capabilities below delegate to. */
    val usesAuroraProtocol: Boolean get() = transport == BoardTransport.AURORA

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
    val usesAuroraPlacements: Boolean
        get() = usesAuroraProtocol || this == QUANTUM || this == SHINYWALL

    /** The connected board lights individual holds from a placement→LED
     *  address map (Kilter + the Aurora-family boards, which share the Aurora
     *  BLE protocol). MoonBoard derives its own LEDs from the climb frame, so
     *  the editor/send path uses a different transport and never builds an LED
     *  map. */
    val usesLedPreview: Boolean get() = usesAuroraProtocol || this == QUANTUM

    /** Supports the popular-co-occurring-holds heatmap, which is keyed on
     *  Aurora placement-ids (Kilter + the Aurora-family boards). MoonBoard
     *  hold-ids aren't placement-ids, so it has no heatmap layer. */
    val hasHeatmap: Boolean get() = usesAuroraProtocol || this == QUANTUM || this == SHINYWALL

    /** Browse angles must come from the active layout's catalogue instead of
     *  the historical continuous Kilter 0..70 degree control. Quantum models
     *  have different sparse angle sets, so treating the controller protocol
     *  as the angle capability produces selectable, permanently empty angles. */
    val usesCatalogueAngles: Boolean
        get() = (usesAuroraProtocol && this != KILTER) || this == QUANTUM || this == SHINYWALL

    /** Quantum's vendor catalogue is single-frame. "Route" in CruxCoach means
     *  a multi-frame circuit, not the Quantum model/size classification. The
     *  Shiny Wall standard catalogue holds single-frame boulders only. */
    val supportsClimbTypeFilter: Boolean get() = this != QUANTUM && this != SHINYWALL

    /** The mirrored Quantum API does not define an official benchmark flag.
     *  The Shiny Wall catalogue flags benchmarks per angle. */
    val supportsBenchmarkFilter: Boolean get() = this != QUANTUM

    /** BoardSesh is a Kilter-side provenance and has no Quantum or Shiny Wall
     *  catalogue. */
    val supportsBoardSeshOrigin: Boolean get() = this != QUANTUM && this != SHINYWALL

    /** The product-size edge predicate is an Aurora compatibility rule.
     *  MoonBoard variants are represented by layouts and hold-set masks and
     *  have no `product_sizes` rows. Quantum model membership is authoritative
     *  in quantum_route_models and is materialised as a unique layout id for
     *  every model. Persisted Aurora size ids must therefore be inert on both. */
    val usesProductSizeEdgeFit: Boolean get() = usesAuroraProtocol

    /**
     * Number of independent climb projections the physical controller can
     * retain at once.  Keeping this on the board capability model makes the
     * layer UI reusable: future controllers can opt in without inheriting any
     * Quantum packet details.
     */
    val maxSimultaneousClimbs: Int get() = if (this == QUANTUM) 4 else 1

    /** True when one projection can be removed without clearing the others. */
    val supportsIndependentClimbLayers: Boolean get() = maxSimultaneousClimbs > 1

    /** Climbs can be authored in the in-app editor — every interactive board.
     *  Kilter additionally pushes to the user's own Kilter account (see
     *  [supportsOfficialAppPublish]); every other interactive board (MoonBoard +
     *  the Aurora family: Tension/Grasshopper/Decoy/So iLL/Touchstone) publishes
     *  to the CruxCoach Nostr community only. The draft-insert and publish paths
     *  thread the active board's brand explicitly — not [fromLayoutId], which
     *  can't disambiguate the Aurora-family layout-ids from Kilter's — so each
     *  authored climb is tagged with, and stays on, its own board. Info-layer
     *  brands (aurora, 12climb) aren't interactive: no catalogue, no editor.
     *  Shiny Wall authors on its bundled hold geometry. */
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
            QUANTUM -> "Quantum Board"
            SHINYWALL -> "Shiny Wall"
            AURORA -> "Aurora"
            TWELVECLIMB -> "12 Climb"
        }

    companion object {
        /** The map-only info-layer families, as a group (drives the map's
         *  "other boards" filter chip). */
        val INFO_LAYER: List<BoardBrand>
            get() = entries.filter { it.isInfoLayer }

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
         * Best-effort legacy inference when only a layout id is available.
         * MoonBoard layouts 2/3/4/5/6/7 resolve to [MOONBOARD]. Layout 1 is
         * shared by Kilter Original and MoonBoard 2010, so it intentionally
         * keeps the historical [KILTER] default. Any MoonBoard-2010 write must
         * thread its explicit brand, as current authoring/publish paths do.
         *
         * The same limitation already applies to Aurora-family boards whose
         * low layout ids overlap Kilter. Prefer explicit [BoardBrand] at every
         * persistence and wire boundary; this helper exists for old defaults.
         */
        fun fromLayoutId(layoutId: Long): BoardBrand = when {
            layoutId != 1L && MoonBoardVariant.fromLayoutId(layoutId) != null -> MOONBOARD
            // Shiny Wall layouts live in their own 93xx range, so they are unambiguous.
            ShinyWallVariant.fromLayoutId(layoutId) != null -> SHINYWALL
            else -> KILTER
        }
    }
}

/** Controller protocol a board family speaks. */
enum class BoardTransport {
    /** Aurora binary packets over the Nordic UART service (Kilter + Aurora family). */
    AURORA,

    /** MoonBoard ASCII `l#…#` frames over the Nordic UART service. */
    MOONBOARD_UART,

    /** Walltopia Quantum MODBUS-framed commands with readback. */
    QUANTUM,

    /** Shiny Wall ASCII state vector on one characteristic, found across all services. */
    SHINY_WALL,

    /** Map-only family: no controller. */
    NONE,
}
