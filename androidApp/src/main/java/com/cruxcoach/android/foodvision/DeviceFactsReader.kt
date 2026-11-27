package com.cruxcoach.android.foodvision

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.cruxcoach.athlete.logic.DeviceFacts
import com.cruxcoach.athlete.logic.VisionCapability
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/** Reads what [VisionCapability.assess] needs; no permission involved. Open for tests. */
open class DeviceFactsReader @Inject constructor(@ApplicationContext private val context: Context) {

    open fun read(): DeviceFacts {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val cpuinfo = runCatching { File("/proc/cpuinfo").readText() }.getOrDefault("")
        return DeviceFacts(
            totalRamBytes = memory.totalMem,
            lowRamDevice = am.isLowRamDevice,
            arm64 = "arm64-v8a" in Build.SUPPORTED_64_BIT_ABIS,
            cpuFeatures = VisionCapability.cpuFeatures(cpuinfo),
        )
    }

    companion object {
        /** Threads for inference: about the number of big cores, two to four. */
        fun inferenceThreads(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            return if (cores >= 8) 4 else (cores / 2).coerceAtLeast(2)
        }
    }
}
