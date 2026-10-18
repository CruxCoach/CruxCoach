package com.cruxcoach.app.foodvision

/**
 * Transfers the system runs for the app, as Android's DownloadManager: they go
 * on while the app is closed or the screen is locked, and a relaunched app
 * finds them again by id. iOS: a background URLSession ([IosModelDownloads]).
 */
interface ModelDownloads {
    /**
     * Starts [url] (https only) into [destinationPath]; the file appears there
     * only once the whole transfer arrived with a 2xx answer and [expectedBytes]
     * bytes. [allowMetered] lets it use mobile data, a hotspot or Low Data Mode.
     * Returns the transfer's id, or null when it could not be started.
     */
    fun enqueue(url: String, destinationPath: String, expectedBytes: Long, allowMetered: Boolean): String?

    /** Where transfer [id] stands, or null when the system does not know it (any more). */
    fun query(id: String): DownloadRow?

    /** Stops transfer [id] and forgets it; what it wrote so far goes. */
    fun remove(id: String)

    companion object {
        /** Queued, or waiting for a network the transfer may use. */
        const val PENDING = "pending"
        const val RUNNING = "running"
        const val SUCCESSFUL = "successful"
        const val FAILED = "failed"
    }
}

/** One transfer: a [ModelDownloads] status code and the bytes that arrived so far. */
data class DownloadRow(val status: String, val doneBytes: Long)
