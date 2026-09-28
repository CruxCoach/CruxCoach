package com.cruxcoach.data

/** Values of `pending_import.source` (PendingImports.sq). */
object PendingImportSource {
    /** Own climbs of a CruxCoach import or restore, waiting for the board DB. */
    const val OWN_CLIMBS = "own-climbs"

    /** An Aurora JSON export, waiting for the Kilter catalogue. */
    const val AURORA = "aurora-json"

    /** The Kilter backfill of the user's own climbs, waiting for the Kilter catalogue. */
    const val KILTER_BACKFILL = "kilter-backfill"
}
