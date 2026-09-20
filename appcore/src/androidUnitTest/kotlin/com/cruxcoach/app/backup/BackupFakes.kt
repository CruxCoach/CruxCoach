package com.cruxcoach.app.backup

import com.cruxcoach.app.nostr.NostrEvent
import com.cruxcoach.app.platform.HttpFailure
import com.cruxcoach.app.platform.HttpResponse
import com.cruxcoach.app.platform.HttpResult
import com.cruxcoach.app.platform.HttpTransport
import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.app.util.toHex

class FakeKeyValueStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String): String? = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
    override fun keys(): Set<String> = values.keys.toSet()
}

class FakeEventSource(var stored: List<NostrEvent> = emptyList()) : BackupEventSource {
    val published = mutableListOf<NostrEvent>()
    val queries = mutableListOf<String>()
    var attempted = 3
    var accepted = 2

    override suspend fun query(filter: String, timeoutMs: Long): List<NostrEvent> {
        queries += filter
        return stored
    }

    override suspend fun publish(event: NostrEvent): PublishStats {
        published += event
        stored = stored + event
        return PublishStats(attempted, accepted)
    }
}

/** Serves Blossom blobs by hash and records what was asked of it. */
class FakeBlossomHttp : HttpTransport {
    val blobs = mutableMapOf<String, ByteArray>()
    val requests = mutableListOf<String>()
    val authHeaders = mutableListOf<String>()
    val uploadHeaders = mutableListOf<Map<String, String>>()
    var rejectUploadsWith: Int? = null
    var hideBlobsFromHead = false

    override suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        maxResponseBytes: Long,
        timeoutSeconds: Int,
    ): HttpResult {
        requests += "$method $url"
        headers["Authorization"]?.let { authHeaders += it }
        val hash = url.substringAfterLast('/')
        return when (method) {
            "PUT" -> {
                uploadHeaders += headers
                rejectUploadsWith?.let { return HttpResult.Ok(HttpResponse(it, emptyMap(), ByteArray(0))) }
                val blob = body ?: return HttpResult.Failed(HttpFailure.OTHER, "no body")
                blobs[JvmHashing.sha256(blob).toHex()] = blob
                HttpResult.Ok(HttpResponse(201, emptyMap(), ByteArray(0)))
            }
            "HEAD" -> {
                val present = !hideBlobsFromHead && blobs.containsKey(hash)
                HttpResult.Ok(HttpResponse(if (present) 200 else 404, emptyMap(), ByteArray(0)))
            }
            "GET" -> {
                val blob = blobs[hash] ?: return HttpResult.Ok(HttpResponse(404, emptyMap(), ByteArray(0)))
                // Mirrors the real transport: an over-cap body never reaches the caller.
                if (blob.size.toLong() > maxResponseBytes) {
                    HttpResult.Failed(HttpFailure.TOO_LARGE, "cap $maxResponseBytes")
                } else {
                    HttpResult.Ok(HttpResponse(200, emptyMap(), blob))
                }
            }
            "DELETE" -> {
                val existed = blobs.remove(hash) != null
                HttpResult.Ok(HttpResponse(if (existed) 200 else 404, emptyMap(), ByteArray(0)))
            }
            else -> HttpResult.Failed(HttpFailure.OTHER, method)
        }
    }

    override suspend fun download(
        url: String,
        destinationPath: String,
        maxBytes: Long,
        timeoutSeconds: Int,
        onProgress: (Long, Long) -> Unit,
    ): HttpResult = HttpResult.Failed(HttpFailure.OTHER, "unused")
}

class FakePayloadStore(private val exportJson: String? = null) : BackupPayloadStore {
    var importedJson: String? = null
    var importedPubkey: String? = null
    var failImport = false
    var summary = BackupImportSummary(
        rowsImported = 7,
        skippedDuplicates = 1,
        ascentsInBackup = 3,
        bidsInBackup = 2,
        listsInBackup = 1,
    )

    override fun exportJson(exportedAt: String, nostrPubkey: String): String? = exportJson

    override fun importJson(json: String, expectedNostrPubkey: String): BackupImportSummary? {
        importedJson = json
        importedPubkey = expectedNostrPubkey
        return if (failImport) null else summary
    }
}
