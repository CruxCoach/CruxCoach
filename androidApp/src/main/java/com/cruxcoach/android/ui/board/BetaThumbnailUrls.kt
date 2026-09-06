package com.cruxcoach.android.ui.board

import java.net.URI

/** Only content-addressed images on known public Blossom mirrors get failover. */
internal fun betaThumbnailUrls(url: String): List<String> {
    val mirrors = listOf("nostr.download", "blossom.primal.net", "cdn.hzrd149.com")
    val uri = runCatching { URI(url) }.getOrNull() ?: return emptyList()
    if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443)) return emptyList()
    if (uri.host !in mirrors || uri.rawQuery != null || uri.fragment != null ||
        !Regex("/[0-9a-f]{64}").matches(uri.path ?: "")) return listOf(url)
    return (listOf(url) + mirrors.map { "https://$it${uri.path}" }).distinct()
}
