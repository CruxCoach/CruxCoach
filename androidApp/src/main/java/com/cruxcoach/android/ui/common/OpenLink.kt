package com.cruxcoach.android.ui.common

import android.content.Context
import android.widget.Toast
import androidx.compose.ui.platform.UriHandler
import com.cruxcoach.android.R

/**
 * Opens [uri] in the browser (or the mail app for a `mailto:` link). On a phone
 * without one nothing can take the link and the handler throws; a toast with
 * [failMessage] says so instead of a crash.
 */
internal fun UriHandler.openUriOrToast(
    context: Context,
    uri: String,
    failMessage: CharSequence = context.getString(R.string.link_open_failed),
    duration: Int = Toast.LENGTH_SHORT,
) {
    runCatching { openUri(uri) }
        .onFailure { Toast.makeText(context, failMessage, duration).show() }
}
