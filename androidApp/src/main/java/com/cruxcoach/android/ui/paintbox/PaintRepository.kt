package com.cruxcoach.android.ui.paintbox

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class PaintLibrary(val pictures: List<PaintDocument>, val unreadable: Int)

@Singleton
class PaintRepository @Inject constructor(@ApplicationContext context: Context) {
    private val directory = File(context.filesDir, "paintbox")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private fun file(id: String): AtomicFile {
        require(UUID.fromString(id).toString() == id)
        return AtomicFile(File(directory, "$id.json"))
    }
    suspend fun save(document: PaintDocument) = withContext(Dispatchers.IO) { mutex.withLock {
        document.validate()
        check(directory.isDirectory || directory.mkdirs())
        val target = file(document.id)
        val stream = target.startWrite()
        try {
            stream.write(json.encodeToString(document).toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (e: Exception) { target.failWrite(stream); throw e }
    } }
    suspend fun list(): PaintLibrary = withContext(Dispatchers.IO) { mutex.withLock {
        var unreadable = 0
        val names = directory.listFiles().orEmpty().map { it.name.removeSuffix(".bak") }
            .filter { it.endsWith(".json") }.distinct()
        val pictures = names.mapNotNull { name ->
            try {
                val id = name.removeSuffix(".json")
                json.decodeFromString<PaintDocument>(file(id).openRead().bufferedReader().use { it.readText() })
                    .also { it.validate(); require(it.id == id) }
            } catch (_: Exception) { unreadable++; null }
        }
        PaintLibrary(pictures.sortedByDescending { it.updatedAt }, unreadable)
    } }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val target = file(id)
        target.delete()
        check(!target.baseFile.exists())
    } }
}
