package com.cruxcoach.android.boardscan

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * One scan in test mode: what the recogniser answered and which problem was really lit. Numbers and problem names
 * only; no camera image is ever kept.
 */
data class ScanTestRecord(
    val timeMs: Long,
    val board: ScanBoard,
    /** A climber (or anything else) was in front of the board. */
    val climberInFront: Boolean,
    val answerUuid: String?,
    val answerName: String?,
    val answerMirrored: Boolean,
    val sure: Boolean,
    /** Null when the lit problem is not in the catalogue on the phone. */
    val truthUuid: String?,
    val truthName: String?,
    val truthMirrored: Boolean,
    /** Where the lit problem was among the offered answers (1-3), 0 when it was not offered. */
    val truthRank: Int,
    /** From pressing scan to the answer. */
    val msToAnswer: Long,
    val frames: Int,
    val leds: Int,
    val device: String,
    val appVersion: String,
) {
    val correct: Boolean get() = truthRank == 1
    val sureAndWrong: Boolean get() = sure && !correct
}

/** Per-board counts for the real-board gate (free-view scans, no sure-and-wrong answer). */
data class ScanTestSummary(
    val board: ScanBoard,
    val scans: Int,
    val freeView: Int,
    val freeTop1: Int,
    val freeTop3: Int,
    val freeSure: Int,
    val sureWrong: Int,
    /** Median time to the answer over the free-view scans, ms. */
    val medianMs: Long,
)

/**
 * The test-mode scan log: one CSV row per confirmed scan in the app's private files, exported through the share
 * sheet. Not thread-safe; callers serialise access.
 */
class ScanTestLog(private val file: File) {

    fun append(r: ScanTestRecord) {
        file.parentFile?.mkdirs()
        val newFile = !file.exists() || file.length() == 0L
        file.appendText((if (newFile) HEADER + "\n" else "") + toCsv(r) + "\n")
    }

    fun read(): List<ScanTestRecord> {
        if (!file.exists()) return emptyList()
        return file.readLines().drop(1).mapNotNull { fromCsv(it) }
    }

    fun clear() {
        file.delete()
    }

    /** A copy of the log for sharing, named by date; null when there is nothing to share. */
    fun exportTo(dir: File, nowMs: Long): File? {
        if (!file.exists() || file.length() == 0L) return null
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date(nowMs))
        val out = File(dir, "board-scan-tests-$stamp.csv")
        file.copyTo(out, overwrite = true)
        return out
    }

    companion object {
        const val HEADER = "time_utc,brand,layout_id,size_id,angle,view,answer_uuid,answer_name,answer_mirrored,sure," +
            "truth_uuid,truth_name,truth_mirrored,truth_rank,correct,ms_to_answer,frames,leds,device,app_version"

        fun summarise(records: List<ScanTestRecord>): List<ScanTestSummary> =
            records.groupBy { it.board.copy(angle = 0) }.map { (_, rs) ->
                val free = rs.filter { !it.climberInFront }
                val times = free.map { it.msToAnswer }.sorted()
                ScanTestSummary(
                    board = rs.last().board,
                    scans = rs.size,
                    freeView = free.size,
                    freeTop1 = free.count { it.truthRank == 1 },
                    freeTop3 = free.count { it.truthRank in 1..3 },
                    freeSure = free.count { it.sure },
                    sureWrong = rs.count { it.sureAndWrong },
                    medianMs = if (times.isEmpty()) 0 else times[times.size / 2],
                )
            }

        private fun utc(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

        private fun parseUtc(s: String): Long? = runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(s)?.time
        }.getOrNull()

        internal fun toCsv(r: ScanTestRecord): String = listOf(
            utc(r.timeMs), r.board.brand, r.board.layoutId, r.board.sizeId, r.board.angle,
            if (r.climberInFront) "climber" else "free",
            r.answerUuid.orEmpty(), r.answerName.orEmpty(), r.answerMirrored.bit(), r.sure.bit(),
            r.truthUuid.orEmpty(), r.truthName.orEmpty(), r.truthMirrored.bit(), r.truthRank, r.correct.bit(),
            r.msToAnswer, r.frames, r.leds, r.device, r.appVersion,
        ).joinToString(",") { csvField(it.toString()) }

        internal fun fromCsv(line: String): ScanTestRecord? {
            val f = splitCsv(line)
            if (f.size < 20) return null
            return runCatching {
                ScanTestRecord(
                    timeMs = parseUtc(f[0]) ?: 0L,
                    board = ScanBoard(f[1], f[2].toInt(), f[3].toInt(), f[4].toInt()),
                    climberInFront = f[5] == "climber",
                    answerUuid = f[6].ifEmpty { null },
                    answerName = unguard(f[7]).ifEmpty { null },
                    answerMirrored = f[8] == "1",
                    sure = f[9] == "1",
                    truthUuid = f[10].ifEmpty { null },
                    truthName = unguard(f[11]).ifEmpty { null },
                    truthMirrored = f[12] == "1",
                    truthRank = f[13].toInt(),
                    msToAnswer = f[15].toLong(),
                    frames = f[16].toInt(),
                    leds = f[17].toInt(),
                    device = f[18],
                    appVersion = f[19],
                )
            }.getOrNull()
        }

        private fun Boolean.bit() = if (this) "1" else "0"

        /**
         * One line per scan: a line break in a problem name becomes a space. A name starting like a spreadsheet
         * formula gets a leading apostrophe, so opening the export never runs it.
         */
        private fun csvField(s0: String): String {
            var s = s0.replace('\n', ' ').replace('\r', ' ')
            if (s.isNotEmpty() && s[0] in FORMULA_START) s = "'$s"
            return if (s.any { it == ',' || it == '"' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        }

        private const val FORMULA_START = "=+-@"

        private fun unguard(s: String) = if (s.length > 1 && s[0] == '\'' && s[1] in FORMULA_START) s.substring(1) else s

        private fun splitCsv(line: String): List<String> {
            val out = ArrayList<String>()
            val sb = StringBuilder()
            var quoted = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                    c == '"' -> quoted = !quoted
                    c == ',' && !quoted -> { out.add(sb.toString()); sb.clear() }
                    else -> sb.append(c)
                }
                i++
            }
            out.add(sb.toString())
            return out
        }
    }
}
