package com.cruxcoach.android.spraywall

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import com.cruxcoach.domain.spraywall.detect.HoldCandidate
import com.cruxcoach.domain.spraywall.detect.RgbImage
import com.cruxcoach.domain.spraywall.detect.Resampling
import com.cruxcoach.domain.spraywall.detect.SegOutputs
import com.cruxcoach.domain.spraywall.detect.SegPipeline
import com.cruxcoach.domain.spraywall.detect.SegSettings
import com.cruxcoach.domain.spraywall.detect.SegTile
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer

/**
 * Runs the hold segmentation model on a photo (FEAT-078): resize to the
 * working size, cut into overlapping tiles, one inference per tile, then
 * decode, trace the outlines and merge the tiles ([SegPipeline]).
 *
 * Holds one ONNX Runtime session; close it when done. Not thread-safe: one
 * photo at a time.
 */
class OnnxHoldDetector(
    modelFile: File,
    threads: Int = DEFAULT_THREADS,
    /** Execution provider: CPU (default), or XNNPACK / NNAPI where ONNX Runtime can place the model's operators. */
    provider: Provider = Provider.CPU,
) : Closeable {
    enum class Provider { CPU, XNNPACK, NNAPI }

    /** Where the time of one photo went, in milliseconds. */
    data class Timing(val resizeMs: Long, val inferMs: Long, val postMs: Long) {
        val totalMs: Long get() = resizeMs + inferMs + postMs
    }

    class Result(val candidates: List<HoldCandidate>, val timing: Timing, val tiles: Int)

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(
        modelFile.path,
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            when (provider) {
                Provider.CPU -> Unit
                Provider.XNNPACK -> addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                Provider.NNAPI -> addNnapi()
            }
        },
    )
    private val inputName = session.inputNames.first()

    /**
     * [photo] at any size at or above the working size; it is resampled here,
     * with Android's own bitmap scaling for [SegSettings.Resampler.NATIVE].
     * [onTile] hears after every tile (done, total).
     */
    fun detect(photo: Bitmap, settings: SegSettings = SegSettings(), onTile: (Int, Int) -> Unit = { _, _ -> }): Result {
        if (settings.resampler != SegSettings.Resampler.NATIVE) {
            val pixels = IntArray(photo.width * photo.height)
            photo.getPixels(pixels, 0, photo.width, 0, 0, photo.width, photo.height)
            return detect(RgbImage(photo.width, photo.height, pixels), settings, onTile)
        }
        val started = System.nanoTime()
        val (workWidth, workHeight) = SegPipeline.workingSize(photo.width, photo.height, settings.longSide)
        val work = if (photo.width == workWidth && photo.height == workHeight) photo else Bitmap.createScaledBitmap(photo, workWidth, workHeight, true)
        val size = settings.input
        val pixels = IntArray(size * size)
        return runTiles(workWidth, workHeight, settings, started, onTile) { tile ->
            val crop = Bitmap.createBitmap(work, tile.x0, tile.y0, tile.width, tile.height)
            val stretched = Bitmap.createScaledBitmap(crop, size, size, true)
            stretched.getPixels(pixels, 0, size, 0, 0, size, size)
            if (stretched !== crop) stretched.recycle()
            if (crop !== work) crop.recycle()
            SegPipeline.normalize(pixels, size)
        }.also { if (work !== photo) work.recycle() }
    }

    /** [photo] at any size at or above the working size; it is resampled here in Kotlin. */
    fun detect(photo: RgbImage, settings: SegSettings = SegSettings(), onTile: (Int, Int) -> Unit = { _, _ -> }): Result {
        val started = System.nanoTime()
        val (workWidth, workHeight) = SegPipeline.workingSize(photo.width, photo.height, settings.longSide)
        val work = Resampling.resize(photo, workWidth, workHeight, settings.resampler)
        return runTiles(workWidth, workHeight, settings, started, onTile) { tile -> SegPipeline.inputTensor(work, tile, settings) }
    }

    private fun runTiles(
        workWidth: Int,
        workHeight: Int,
        settings: SegSettings,
        started: Long,
        onTile: (Int, Int) -> Unit,
        input: (SegTile) -> FloatArray,
    ): Result {
        val tiles = SegPipeline.tiles(workWidth, workHeight, settings.rows, settings.cols, settings.overlap)
        var resizeNs = System.nanoTime() - started
        var inferNs = 0L
        var postNs = 0L
        val perTile = tiles.mapIndexed { index, tile ->
            val t0 = System.nanoTime()
            val tensor = input(tile)
            val t1 = System.nanoTime()
            val outputs = infer(tensor, settings.input)
            val t2 = System.nanoTime()
            val decoded = SegPipeline.decode(outputs, tile, settings)
            val t3 = System.nanoTime()
            resizeNs += t1 - t0
            inferNs += t2 - t1
            postNs += t3 - t2
            onTile(index + 1, tiles.size)
            decoded
        }
        val t4 = System.nanoTime()
        val merged = if (settings.seamAware) {
            SegPipeline.merge(perTile, tiles, workWidth, workHeight, settings.nmsIou)
        } else {
            SegPipeline.merge(perTile, settings.nmsIou)
        }
        val candidates = SegPipeline.candidates(merged, workWidth, workHeight)
        postNs += System.nanoTime() - t4
        return Result(candidates, Timing(resizeNs / MS, inferNs / MS, postNs / MS), tiles.size)
    }

    private fun infer(input: FloatArray, size: Int): SegOutputs {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, size.toLong(), size.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                var boxes: Pair<FloatArray, LongArray>? = null
                var logits: Pair<FloatArray, LongArray>? = null
                var masks: Pair<FloatArray, LongArray>? = null
                // By shape, not by name: exporters do not name the outputs consistently.
                for (entry in result) {
                    val value = entry.value as? OnnxTensor ?: continue
                    val shape = value.info.shape
                    val buffer = value.floatBuffer
                    val data = FloatArray(buffer.remaining()).also { buffer.get(it) }
                    when {
                        shape.size == 3 && shape[2] == 4L -> boxes = data to shape
                        shape.size == 3 -> logits = data to shape
                        shape.size == 4 -> masks = data to shape
                    }
                }
                val b = boxes ?: error("model has no box output")
                val l = logits ?: error("model has no score output")
                val m = masks
                return SegOutputs(
                    boxes = b.first,
                    logits = l.first,
                    queries = b.second[1].toInt(),
                    classes = l.second[2].toInt(),
                    masks = m?.first,
                    maskHeight = m?.second?.get(2)?.toInt() ?: 0,
                    maskWidth = m?.second?.get(3)?.toInt() ?: 0,
                )
            }
        }
    }

    override fun close() = session.close()

    companion object {
        /**
         * Every core, up to 8: on the test phone (8 slow cores) 8 threads ran
         * the model 2.7× faster than 4.
         */
        val DEFAULT_THREADS: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        private const val MS = 1_000_000L
    }
}
