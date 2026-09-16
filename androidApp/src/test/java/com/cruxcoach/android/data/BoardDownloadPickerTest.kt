package com.cruxcoach.android.data

import com.cruxcoach.android.fakes.createTestUserPreferences
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.board.BoardBrand
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class BoardDownloadPickerTest {
    @Test fun `selecting an excluded uncached Aurora board never starts a catalogue download`() = runTest {
        val prefs = createTestUserPreferences(backgroundScope)
        prefs.setBoardDownloadBrands(setOf(BoardBrand.KILTER))
        val sync = mockk<AuroraCatalogueSync>(relaxed = true)
        val repo = mockk<BoardRepository>(relaxed = true)
        every { repo.getDefaultLayoutForBrand("tension") } returns null
        val selector = AuroraBoardSelector(prefs, sync, repo)
        assertEquals(AuroraBoardSelector.Status.DOWNLOAD_DISABLED, selector.select(BoardBrand.TENSION).status)
        coVerify(exactly = 0) { sync.sync(any(), any()) }
    }
}
