package com.cruxcoach.android.data.kilter

import android.content.Context
import com.cruxcoach.android.R

fun KilterUploadStatus.localized(context: Context): String {
    val kept = rejected + heldImported
    val reasonText = when (reason) {
        KilterUploadReason.NONE -> when {
            pending > 0 -> R.string.kilter_upload_pending
            kept > 0 -> R.string.kilter_upload_done_except
            else -> R.string.kilter_upload_done
        }
        KilterUploadReason.DISABLED -> R.string.kilter_upload_disabled
        KilterUploadReason.AUTHENTICATION -> R.string.kilter_upload_auth
        KilterUploadReason.WALL_CONTEXT -> R.string.kilter_upload_wall
        KilterUploadReason.NETWORK -> R.string.kilter_upload_network
        KilterUploadReason.HTTP -> R.string.kilter_upload_http
        KilterUploadReason.CONFLICT -> R.string.kilter_upload_conflict
        KilterUploadReason.INTERNAL -> R.string.kilter_upload_internal
    }
    val res = context.resources
    return buildList {
        add(context.getString(reasonText))
        if (rejectedByKilter > 0) add(res.getQuantityString(R.plurals.kilter_upload_rejected, rejectedByKilter, rejectedByKilter))
        if (rejectedInvalid > 0) add(res.getQuantityString(R.plurals.kilter_upload_invalid, rejectedInvalid, rejectedInvalid))
        if (rejectedConflict > 0) add(res.getQuantityString(R.plurals.kilter_upload_conflicts, rejectedConflict, rejectedConflict))
        if (heldImported > 0) add(res.getQuantityString(R.plurals.kilter_upload_held_imported, heldImported, heldImported))
    }.joinToString("\n")
}
