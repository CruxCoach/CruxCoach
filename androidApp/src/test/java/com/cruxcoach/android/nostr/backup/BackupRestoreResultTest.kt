package com.cruxcoach.android.nostr.backup

import com.cruxcoach.data.CruxCoachBackup
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupRestoreResultTest {

    @Test
    fun `display counts come from authenticated backup not inserted rows`() {
        val result = BackupRestoreResult(
            imported = CruxCoachBackup.ImportResult(
                boardAscents = 0,
                boardBids = 0,
                climbLists = 3,
                skippedDuplicates = 3,
            ),
            backupPreview = CruxCoachBackup.ImportPreview(
                boardAscents = 3,
                boardBids = 2,
                climbLists = 3,
            ),
        )

        // Restoring over intact local rows legitimately inserts nothing,
        // but the user must still see that five logbook rows were present
        // in the decrypted backup.
        assertEquals(5, result.logbookEntriesInBackup)
        assertEquals(3, result.listsInBackup)
        assertEquals(0, result.imported.boardAscents)
        assertEquals(3, result.imported.skippedDuplicates)
    }
}
