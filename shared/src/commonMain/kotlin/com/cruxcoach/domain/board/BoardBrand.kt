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

    // Map-only "info layer" families (FEAT-015 Phase 2). CruxCoach ships no
    // climb catalogue or BLE send for these, so they appear on the
    // board-locations map only — never in the browser, picker, or active-
    // board pref. wireValues mirror the locations pipeline + the
    // cruxcoach.org map's brand keys.
    TENSION("tension"),
    GRASSHOPPER("grasshopper"),
    DECOY("decoy"),
    SOILL("soill"),
    TOUCHSTONE("touchstone"),
    AURORA("aurora"),
    TWELVECLIMB("12climb");

    /** True for families CruxCoach actually drives (catalogue + BLE). The
     *  rest are map-only info-layer brands. */
    val isInteractive: Boolean get() = this == KILTER || this == MOONBOARD

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
    }
}
