package com.cruxcoach.android.ui.board

import android.content.Context
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.Options
import okio.buffer
import okio.source
import java.io.File
import java.io.IOException

/** A typed model prevents fallback to Coil's unrestricted remote URL fetcher. */
private data class VerifiedThumbnail(val url: String)

/**
 * Resolve memory before verification/disk/network work. The previous row-local
 * produceState reset to null whenever LazyColumn disposed and recreated a row,
 * hiding even a hot decoded image behind another disk hash verification.
 * Mirror URLs for the same signed content share one stable memory key.
 */
internal fun betaThumbnailRequest(
    context: Context,
    url: String,
    load: suspend (File, String) -> File? = VerifiedBetaThumbnail::load,
): ImageRequest? {
    // Keep host/hash/logo rejection before BOTH cache lookup and fetching.
    val hash = betaThumbnailHash(url) ?: return null
    val key = "verified-beta-thumbnail-v1:$hash"
    return ImageRequest.Builder(context)
        .data(VerifiedThumbnail(url))
        .memoryCacheKey(key)
        .placeholderMemoryCacheKey(key)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .crossfade(false)
        .fetcherFactory(object : Fetcher.Factory<VerifiedThumbnail> {
            override fun create(data: VerifiedThumbnail, options: Options, imageLoader: ImageLoader): Fetcher =
                object : Fetcher {
                    override suspend fun fetch(): SourceResult {
                        val file = load(options.context.cacheDir, data.url)
                            ?: throw IOException("Verified beta thumbnail unavailable")
                        return SourceResult(
                            source = ImageSource(file.source().buffer(), options.context),
                            mimeType = null,
                            dataSource = DataSource.DISK,
                        )
                    }
                }
        })
        .build()
}
