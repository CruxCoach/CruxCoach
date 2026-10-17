package com.cruxcoach.athlete

import com.cruxcoach.athlete.data.AthleteSnapshot
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.RoutineItem
import com.cruxcoach.data.CruxCoachBackup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Circuits in the backup envelope: new keys validated, old backups and old builds unaffected. */
class CircuitBackupTest {

    private fun backup(sets: String = "[]", routines: String = "[]") = """
        {"version":3,"app":"CruxCoach","exportedAt":"2026-10-17T10:00:00",
         "athlete":{"workouts":[{"id":"w","startedAt":1,"day":"2026-10-17","updatedAt":1}],"sets":$sets,"routines":$routines}}
    """.trimIndent()

    private fun set(extra: String) =
        """[{"id":"s","workoutId":"w","exerciseSlug":"pull.pull_up","blockIndex":0,"setIndex":0,"restS":15$extra}]"""

    private fun routine(extra: String) = """[{"id":"r","name":"R","items":[{"slug":"pull.pull_up"$extra},{"slug":"push.push_up"$extra}]}]"""

    @Test
    fun backupsWithAndWithoutCircuitsParse() {
        assertEquals(3, CruxCoachBackup.preview(backup(set(""), routine(""))).trainingRows)
        assertEquals(3, CruxCoachBackup.preview(backup(set(""","circuit":1,"roundRestS":120"""),
            routine(""","circuit":1,"circuitRestS":0,"roundRestS":120"""))).trainingRows)
        // What this build exports (null keys written out) reads back the same.
        assertEquals(3, CruxCoachBackup.preview(backup(set(""","circuit":null,"roundRestS":null"""),
            routine(""","circuit":null,"circuitRestS":null,"roundRestS":null"""))).trainingRows)
    }

    @Test
    fun absurdCircuitValuesAreRejected() {
        fun rejected(json: String) = runCatching { CruxCoachBackup.preview(json) }.isFailure
        assertTrue(rejected(backup(set(""","circuit":-1"""))))
        assertTrue(rejected(backup(set(""","circuit":10001"""))))
        assertTrue(rejected(backup(set(""","roundRestS":3601"""))))
        assertTrue(rejected(backup(set(""","roundRestS":-1"""))))
        assertTrue(rejected(backup(routines = routine(""","circuit":1,"circuitRestS":99999"""))))
        assertTrue(rejected(backup(routines = routine(""","circuit":1,"roundRestS":-5"""))))
        assertTrue(rejected(backup(routines = routine(""","circuit":-3"""))))
        assertTrue(!rejected(backup(set(""","circuit":10000,"roundRestS":3600"""), routine(""","circuit":1,"circuitRestS":3600,"roundRestS":0"""))))
    }

    @Test
    fun anOlderBuildReadsANewBackupWithoutTheCircuits() {
        // The backup writes every field (encodeDefaults); an older build ignores the keys it does not know.
        val writer = Json { encodeDefaults = true }
        val snapshot = AthleteSnapshot(
            sets = listOf(ExerciseSet("s", "w", "pull.pull_up", 0, 0, restS = 15, circuit = 1, roundRestS = 120)),
            routines = listOf(Routine("r", "R", items = listOf(RoutineItem("pull.pull_up", circuit = 1, circuitRestS = 15, roundRestS = 120)))),
        )
        val text = writer.encodeToString(AthleteSnapshot.serializer(), snapshot)
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(OldSnapshot.serializer(), text)
        assertEquals(OldSnapshot(listOf(OldSet("s", "w", "pull.pull_up", 0, 0, restS = 15)), listOf(OldRoutine("r", "R", listOf(OldRoutineItem("pull.pull_up"))))), old)
    }
}

/** The athlete part of a backup as a build from before circuits knows it (an excerpt). */
@Serializable
private data class OldSnapshot(val sets: List<OldSet> = emptyList(), val routines: List<OldRoutine> = emptyList())

@Serializable
private data class OldSet(val id: String, val workoutId: String, val exerciseSlug: String, val blockIndex: Int, val setIndex: Int, val restS: Int? = null)

@Serializable
private data class OldRoutine(val id: String, val name: String, val items: List<OldRoutineItem>)

@Serializable
private data class OldRoutineItem(val slug: String, val sets: Int = 3, val restS: Int? = null)
