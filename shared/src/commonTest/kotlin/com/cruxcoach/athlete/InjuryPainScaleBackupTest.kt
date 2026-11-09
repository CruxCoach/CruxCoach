package com.cruxcoach.athlete

import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.data.CruxCoachBackup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Pain 0–10 since 0.2.5: backups with the new top value pass the check, and old backups (0–9) still import. */
class InjuryPainScaleBackupTest {

    private fun backup(severity: Int) = """
        {"version":3,"app":"CruxCoach","exportedAt":"2026-11-08T10:00:00",
         "athlete":{"injuries":[{"id":"i","region":"FINGER","severity":$severity,"startedOn":"2026-11-01","updatedAt":1}]}}
    """.trimIndent()

    @Test
    fun theScaleEndsAt10() = assertEquals(10, Injury.MAX_SEVERITY)

    @Test
    fun oldBackupsWithPain9StillPass() {
        assertEquals(1, CruxCoachBackup.preview(backup(9)).healthRows)
        assertEquals(1, CruxCoachBackup.preview(backup(0)).healthRows)
    }

    @Test
    fun pain10Passes() = assertEquals(1, CruxCoachBackup.preview(backup(10)).healthRows)

    @Test
    fun painAbove10OrBelow0IsRejected() {
        assertFailsWith<IllegalArgumentException> { CruxCoachBackup.preview(backup(11)) }
        assertFailsWith<IllegalArgumentException> { CruxCoachBackup.preview(backup(-1)) }
    }
}
