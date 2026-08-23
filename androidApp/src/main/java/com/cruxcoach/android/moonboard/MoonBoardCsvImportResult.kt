package com.cruxcoach.android.moonboard

data class MoonBoardCsvImportResult(
    val importedAscents: Int = 0,
    val importedProjects: Int = 0,
    val duplicates: Int = 0,
    val foundEntries: Int = 0,
    val notImported: Int = 0,
    val snapshotOnly: Int = 0,
    /** Rows replaced because the entry changed in Moon after the import. */
    val replacedEntries: Int = 0,
    /** Rows Moon no longer lists but that carry notes added in CruxCoach. */
    val keptOrphans: Int = 0,
    /** Sessions actually read / sessions Moon's own logbook header announced. */
    val sessionsScanned: Int = 0,
    val sessionsExpected: Int = 0,
    /** Training days already complete in the logbook, never re-read. */
    val sessionsSkipped: Int = 0,
    /** Problems Moon's own logbook header announced for the whole account. */
    val expectedEntries: Int = 0,
    /** One line per real, named deviation — never a generic failure. */
    val warnings: List<String> = emptyList(),
    val unresolvedProblemIds: List<Long> = emptyList(),
    val unresolvedLabels: List<String> = emptyList(),
    val error: String? = null,
) {
    val imported: Int get() = importedAscents + importedProjects
}
