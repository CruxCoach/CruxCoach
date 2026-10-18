package com.cruxcoach.app.health

import com.cruxcoach.app.platform.KeyValueStore

/**
 * A [HealthStore] that keeps its samples in memory and applies HealthKit's sync-id rules: a sample with a
 * known sync id replaces the stored one only with a higher version. Answers synchronously.
 */
class FakeHealthStore(
    var available: Boolean = true,
    /** "done", or "failed" as a build without the HealthKit entitlement answers. */
    var authorizationAnswer: String = "done",
    var writeAccess: String = "authorized",
    var asked: Boolean = true,
) : HealthStore {
    val sleep = mutableListOf<HealthSleepSample>()
    val workouts = mutableListOf<HealthWorkoutSample>()
    var readOk = true
    var writeAnswer = "done"

    /** Stored nutrition samples by sync id. */
    val written = linkedMapOf<String, HealthNutritionSample>()
    val writeCalls = mutableListOf<Pair<List<HealthNutritionSample>, List<String>>>()
    val authorizationRequests = mutableListOf<Pair<List<String>, List<String>>>()
    var workoutQueries = 0

    override fun isAvailable(): Boolean = available

    override fun requestAuthorization(read: List<String>, write: List<String>, onDone: (String) -> Unit) {
        authorizationRequests += read to write
        if (authorizationAnswer == "done") asked = true
        onDone(authorizationAnswer)
    }

    override fun requestStatus(read: List<String>, write: List<String>, onStatus: (String) -> Unit) =
        onStatus(if (authorizationAnswer != "done") "failed" else if (asked) "asked" else "notAsked")

    override fun writeStatus(write: List<String>): String = writeAccess

    override fun sleepSamples(fromMs: Long, toMs: Long, onResult: (HealthSleepResult) -> Unit) =
        onResult(HealthSleepResult(readOk, if (readOk) sleep.filter { it.endMs > fromMs && it.startMs < toMs } else emptyList()))

    override fun climbingWorkouts(fromMs: Long, toMs: Long, onResult: (HealthWorkoutResult) -> Unit) {
        workoutQueries++
        onResult(HealthWorkoutResult(readOk, if (readOk) workouts.filter { it.startMs in fromMs until toMs } else emptyList()))
    }

    override fun writeNutrition(samples: List<HealthNutritionSample>, deleteSyncIds: List<String>, onDone: (String) -> Unit) {
        writeCalls += samples to deleteSyncIds
        if (writeAnswer != "done") { onDone(writeAnswer); return }
        for (s in samples) {
            val old = written[s.syncId]
            if (old == null || s.version > old.version) written[s.syncId] = s
        }
        deleteSyncIds.forEach { written.remove(it) }
        onDone("done")
    }
}

class MemoryHealthKeyValues : KeyValueStore {
    val map = mutableMapOf<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    override fun keys(): Set<String> = map.keys.toSet()
}
