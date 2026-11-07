package com.cruxcoach.android.ui.common

import android.content.Context
import android.widget.Toast
import androidx.compose.ui.platform.UriHandler
import com.cruxcoach.android.R

/**
 * Opens [uri] in the browser. On a phone without one nothing can take the
 * link and the handler throws; a short toast says so instead of a crash.
 */
internal fun UriHandler.openUriOrToast(context: Context, uri: String) {
    runCatching { openUri(uri) }
        .onFailure { Toast.makeText(context, R.string.link_open_failed, Toast.LENGTH_SHORT).show() }
}
