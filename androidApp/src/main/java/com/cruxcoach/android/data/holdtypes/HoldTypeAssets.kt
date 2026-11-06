package com.cruxcoach.android.data.holdtypes

import android.content.Context
import com.cruxcoach.domain.holdtypes.HoldReviewSession
import com.cruxcoach.domain.holdtypes.HoldTypeDataset
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The bundled hold-type labels (#63), one file per board in `assets/hold_types/`. Labels only: no images and no
 * climbs. A board without a file simply has no hold types.
 */
@Singleton
class HoldTypeAssets @Inject constructor(@ApplicationContext private val context: Context) {
    private val cache = ConcurrentHashMap<String, HoldTypeDataset>()

    fun dataset(board: String): HoldTypeDataset? = cache[board] ?: try {
        context.assets.open("$DIR/$board.json").bufferedReader().use { HoldTypeDataset.parse(it.readText()) }
            .also { cache[board] = it }
    } catch (_: FileNotFoundException) {
        null
    }

    companion object {
        const val DIR = "hold_types"
    }
}

/**
 * The reference check's answers, one JSON file per board under the app's files directory. Kept out of the
 * databases on purpose: it is test-build tooling, and its results leave the phone only when the reviewer
 * saves or shares them.
 */
@Singleton
class HoldReviewStore @Inject constructor(@ApplicationContext private val context: Context) {
    private fun file(board: String) = File(File(context.filesDir, DIR), "$board-reference.json")

    fun load(board: String): HoldReviewSession? = file(board).takeIf { it.exists() }?.let {
        runCatching { HoldReviewSession.fromJson(it.readText()) }.getOrNull()
    }

    fun save(session: HoldReviewSession) {
        val target = file(session.board)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(session.toJson())
        if (!tmp.renameTo(target)) {
            target.writeText(session.toJson())
            tmp.delete()
        }
    }

    fun clear(board: String) {
        file(board).delete()
    }

    private companion object {
        const val DIR = "hold_review"
    }
}
