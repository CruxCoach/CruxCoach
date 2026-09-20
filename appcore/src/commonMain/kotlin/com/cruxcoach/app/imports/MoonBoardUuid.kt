package com.cruxcoach.app.imports

/**
 * Port of Android `MoonBoardUuid`: the identity scheme the bundled MoonBoard
 * catalogue builder uses. A MoonBoard export carries only the numeric
 * ProblemId, so the importer computes the three deterministic UUIDv5 aliases a
 * catalogue snapshot can hold and resolves them locally.
 */
internal object MoonBoardUuid {
    class Candidate(val uuid: String, val encodedAngle: Int?)

    fun candidates(problemId: Long): List<Candidate> = listOf(
        Candidate(ImportUuids.v5Dns("moonboard:$problemId"), null),
        Candidate(ImportUuids.v5Dns("moonboard:$problemId:40"), 40),
        Candidate(ImportUuids.v5Dns("moonboard:$problemId:25"), 25),
    )
}
