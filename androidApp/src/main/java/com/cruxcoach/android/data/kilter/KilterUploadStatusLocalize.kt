package com.cruxcoach.android.data.kilter

import android.content.Context
import com.cruxcoach.android.R

fun KilterUploadStatus.localized(context: Context): String {
    val reasonText = when (reason) {
        KilterUploadReason.NONE -> if (pending > 0) R.string.kilter_upload_pending else R.string.kilter_upload_done
        KilterUploadReason.DISABLED -> R.string.kilter_upload_disabled
        KilterUploadReason.AUTHENTICATION -> R.string.kilter_upload_auth
        KilterUploadReason.WALL_CONTEXT -> R.string.kilter_upload_wall
        KilterUploadReason.NETWORK -> R.string.kilter_upload_network
        KilterUploadReason.HTTP -> R.string.kilter_upload_http
        KilterUploadReason.CONFLICT -> R.string.kilter_upload_conflict
        KilterUploadReason.INTERNAL -> R.string.kilter_upload_internal
    }
    return context.getString(reasonText)
}
