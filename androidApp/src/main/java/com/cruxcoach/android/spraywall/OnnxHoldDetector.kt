package com.cruxcoach.android.spraywall

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.cruxcoach.domain.spraywall.detect.HoldCandidate
import com.cruxcoach.domain.spraywall.detect.RgbImage
import com.cruxcoach.domain.spraywall.detect.Resampling
import com.cruxcoach.domain.spraywall.detect.SegOutputs
import com.cruxcoach.domain.spraywall.detect.SegPipeline
import com.cruxcoach.domain.spraywall.detect.SegSettings
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
class OnnxHoldDetector(modelFile: File, threads: Int = DEFAULT_THREADS) : Closeable {
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
        },
    )
    private val inputName = session.inputNames.first()

    /** [photo] at any size at or above the working size; it is resampled here. */
    fun detect(photo: RgbImage, settings: SegSettings = SegSettings()): Result {
        val started = System.nanoTime()
        val (workWidth, workHeight) = SegPipeline.workingSize(photo.width, photo.height, settings.longSide)
        val work = Resampling.resize(photo, workWidth, workHeight, settings.resampler)
        val tiles = SegPipeline.tiles(workWidth, workHeight, settings.rows, settings.cols, settings.overlap)
        var resizeNs = System.nanoTime() - started
        var inferNs = 0L
        var postNs = 0L
        val perTile = tiles.map { tile ->
            val t0 = System.nanoTime()
            val input = SegPipeline.inputTensor(work, tile, settings)
            val t1 = System.nanoTime()
            val outputs = run(input, settings.input)
            val t2 = System.nanoTime()
            val decoded = SegPipeline.decode(outputs, tile, settings)
            val t3 = System.nanoTime()
            resizeNs += t1 - t0
            inferNs += t2 - t1
            postNs += t3 - t2
            decoded
        }
        val t4 = System.nanoTime()
        val candidates = SegPipeline.candidates(SegPipeline.merge(perTile, settings.nmsIou), workWidth, workHeight)
        postNs += System.nanoTime() - t4
        return Result(candidates, Timing(resizeNs / MS, inferNs / MS, postNs / MS), tiles.size)
    }

    private fun run(input: FloatArray, size: Int): SegOutputs {
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
        const val DEFAULT_THREADS = 4
        private const val MS = 1_000_000L
    }
}
