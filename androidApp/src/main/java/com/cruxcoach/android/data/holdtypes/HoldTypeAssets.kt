package com.cruxcoach.android.data.holdtypes

import android.content.Context
import com.cruxcoach.domain.holdtypes.HoldCheckSession
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

    /** Boards this build ships hold types for, in the order of [BOARD_ORDER]. */
    fun boards(): List<String> {
        val files = context.assets.list(DIR).orEmpty().filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }
        return files.sortedBy { BOARD_ORDER.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    }

    companion object {
        const val DIR = "hold_types"
        val BOARD_ORDER = listOf("kilter", "moonboard", "tension", "grasshopper", "decoy", "soill", "touchstone", "shinywall")
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

/** The label checks of one board ("looks right", corrections), one JSON file per board next to the reference file. */
@Singleton
class HoldCheckStore @Inject constructor(@ApplicationContext private val context: Context) {
    private fun file(board: String) = File(File(context.filesDir, DIR), "$board-check.json")

    fun load(board: String): HoldCheckSession? = file(board).takeIf { it.exists() }?.let {
        runCatching { HoldCheckSession.fromJson(it.readText()) }.getOrNull()
    }

    fun save(session: HoldCheckSession) {
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
