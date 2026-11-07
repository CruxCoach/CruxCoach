package com.cruxcoach.android.ui.boardscan

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.BitmapFactory
import com.cruxcoach.android.boardscan.ScanFrame
import java.io.File

/**
 * Device checks without a lit board in front of the phone: in debuggable builds only, frames come from image files in
 * the app's private `board_scan/debug/` folder instead of the camera. `frames.txt` lists one frame per line:
 * `<file> <focal length px> [<up x> <up y> <up z>]`, served in turn.
 */
internal class FileFrames private constructor(private val dir: File, private val lines: List<String>) {
    private var next = 0

    fun next(): ScanFrame {
        val v = lines[next++ % lines.size].trim().split(Regex("\\s+"))
        val bmp = requireNotNull(BitmapFactory.decodeFile(File(dir, v[0]).path)) { "cannot read ${v[0]}" }
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val up = if (v.size >= 5) doubleArrayOf(v[2].toDouble(), v[3].toDouble(), v[4].toDouble()) else null
        return ScanFrame(px, w, h, v[1].toDouble(), up)
    }

    companion object {
        fun find(context: Context): FileFrames? {
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
            val dir = File(context.filesDir, "board_scan/debug")
            val lines = File(dir, "frames.txt").takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }
            return if (lines.isNullOrEmpty()) null else FileFrames(dir, lines)
        }
    }
}
