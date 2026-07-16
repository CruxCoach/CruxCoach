package com.cruxcoach.android.data.kilter

import android.content.Context
import com.cruxcoach.android.R

/**
 * Localized per-object summary of a Kilter import for the onboarding/settings
 * result message. Shows the new ascent + project counts always; appends the
 * recognized own-climb and catalogue-backfill counts when non-zero, and the
 * already-present (deduped) total when any fetched logs were re-imports.
 *
 * Replaces the old single "%d Einträge importiert" total, which both lumped
 * every object type into one number and counted re-imported (idempotent) rows
 * as freshly imported.
 */
fun formatKilterImportSummary(context: Context, r: KilterImportResult): String {
    // Nothing came back at all (fresh account, no Kilter history) — a
    // friendly empty state beats a bare "0 ascents · 0 projects".
    if (r.newAscents == 0 && r.newBids == 0 && r.ownClimbs == 0 &&
        r.backfilledClimbs == 0 && r.circuits == 0 && r.duplicateLogs == 0
    ) {
        return context.getString(R.string.kilter_import_empty)
    }
    val parts = mutableListOf<String>()
    parts += context.resources.getQuantityString(R.plurals.kilter_import_part_ascents, r.newAscents, r.newAscents)
    parts += context.resources.getQuantityString(R.plurals.kilter_import_part_projects, r.newBids, r.newBids)
    if (r.ownClimbs > 0) {
        parts += context.resources.getQuantityString(R.plurals.kilter_import_part_own, r.ownClimbs, r.ownClimbs)
    }
    if (r.backfilledClimbs > 0) {
        parts += context.resources.getQuantityString(R.plurals.kilter_import_part_catalogue, r.backfilledClimbs, r.backfilledClimbs)
    }
    if (r.circuits > 0) {
        parts += context.resources.getQuantityString(R.plurals.kilter_import_part_circuits, r.circuits, r.circuits)
    }
    val head = parts.joinToString(" · ")
    return if (r.duplicateLogs > 0) {
        context.getString(R.string.kilter_import_summary_with_dupes, head, r.duplicateLogs)
    } else {
        head
    }
}
