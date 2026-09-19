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
