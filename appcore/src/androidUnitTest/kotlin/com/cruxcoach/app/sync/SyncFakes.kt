package com.cruxcoach.app.sync

import com.cruxcoach.app.platform.FileSystem
import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.app.testing.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class FakeKeyValueStore : KeyValueStore {
    val map = LinkedHashMap<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    override fun keys(): Set<String> = map.keys.toSet()
}

class JvmFileSystem(private val root: File, var freeSpace: Long = Long.MAX_VALUE) : FileSystem {
    override fun workDirectory(): String = root.absolutePath
    override fun exists(path: String) = File(path).exists()
    override fun size(path: String) = File(path).length()
    override fun delete(path: String) = File(path).delete()
    override fun move(from: String, to: String) = File(from).renameTo(File(to))
    override fun readBytes(path: String, maxBytes: Long): ByteArray? =
        File(path).takeIf { it.isFile && it.length() <= maxBytes }?.readBytes()
    override fun writeBytes(path: String, data: ByteArray): Boolean = runCatching { File(path).writeBytes(data) }.isSuccess
    override fun freeSpaceBytes(): Long = freeSpace
}

object ManifestFixtures {
    val events: List<JsonObject> =
        Json.parseToJsonElement(Fixtures.text("manifest-events.json")).jsonArray.map { it.jsonObject }

    fun byDTag(dTag: String): JsonObject = events.first { event ->
        event.getValue("tags").jsonArray.any { it.jsonArray[0].jsonPrimitive.content == "d" && it.jsonArray[1].jsonPrimitive.content == dTag }
    }

    /** A moment shortly after every fixture event was signed. */
    const val NOW = 1_789_800_000L
}

/** Serves canned bodies per URL; enforces the byte ceiling the way a real transport must. */
class FakeHttp : com.cruxcoach.app.platform.HttpTransport {
    val bodies = HashMap<String, ByteArray>()
    val statuses = HashMap<String, Int>()
    val failures = HashMap<String, com.cruxcoach.app.platform.HttpFailure>()
    val requested = java.util.Collections.synchronizedList(ArrayList<String>())
    val ceilings = java.util.concurrent.ConcurrentHashMap<String, Long>()

    override suspend fun request(
        method: String, url: String, headers: Map<String, String>, body: ByteArray?, maxResponseBytes: Long, timeoutSeconds: Int,
    ): com.cruxcoach.app.platform.HttpResult = error("not used")

    override suspend fun download(
        url: String, destinationPath: String, maxBytes: Long, timeoutSeconds: Int, onProgress: (Long, Long) -> Unit,
    ): com.cruxcoach.app.platform.HttpResult {
        requested += url
        ceilings[url] = maxBytes
        failures[url]?.let { return com.cruxcoach.app.platform.HttpResult.Failed(it, "scripted") }
        val data = bodies[url] ?: return com.cruxcoach.app.platform.HttpResult.Failed(com.cruxcoach.app.platform.HttpFailure.OTHER, "no route")
        if (data.size > maxBytes) return com.cruxcoach.app.platform.HttpResult.Failed(com.cruxcoach.app.platform.HttpFailure.TOO_LARGE, "ceiling")
        File(destinationPath).writeBytes(data)
        onProgress(data.size.toLong(), data.size.toLong())
        return com.cruxcoach.app.platform.HttpResult.Ok(
            com.cruxcoach.app.platform.HttpResponse(statuses[url] ?: 200, emptyMap(), ByteArray(0)),
        )
    }
}

/**
 * Stand-in codec: a "compressed" file is `ZST1` + payload. Anything else is
 * corrupt. Honours the output cap like libzstd streaming does.
 */
object FakeZstd : com.cruxcoach.app.platform.ZstdDecompressor {
    fun compress(payload: ByteArray): ByteArray = "ZST1".toByteArray() + payload
    override fun decompressFile(sourcePath: String, destinationPath: String, maxOutputBytes: Long): Long {
        val data = File(sourcePath).readBytes()
        if (data.size < 4 || String(data, 0, 4) != "ZST1") return -1
        if (data.size - 4 > maxOutputBytes) return -1
        File(destinationPath).writeBytes(data.copyOfRange(4, data.size))
        return (data.size - 4).toLong()
    }
}

fun sha256Hex(data: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
