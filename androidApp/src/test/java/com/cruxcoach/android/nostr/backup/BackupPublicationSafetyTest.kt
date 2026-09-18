package com.cruxcoach.android.nostr.backup

import android.app.Application
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.android.nostr.NostrSigner
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BackupPublicationSafetyTest {
    private val prefs = mockk<BackupPreferences>(relaxed = true)
    private val pool = mockk<NostrRelayPool>()
    private val signer = mockk<NostrSigner>()
    private val deriver = mockk<DTagDeriver>()
    private fun repository() = BackupRepository(pool, signer, mockk(), deriver, prefs,
        mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk())

    @Test fun `missing wrapped key fails instead of acknowledging backup`() = runTest {
        coEvery { prefs.getWrappedDataKey() } returns null
        val error = runCatching { repository().republishKeyEvent() }.exceptionOrNull()
        assertTrue(error is BackupException)
        coVerify(exactly = 0) { prefs.setLastKeyEventPublish(any()) }
    }

    @Test fun `key publication failure propagates and never updates publication time`() = runTest {
        coEvery { prefs.getWrappedDataKey() } returns "test-wrapped-ciphertext"
        val repository = spyk(repository())
        coEvery { repository.publishKeyEvent(any()) } throws
            BackupException(BackupErrorReason.KeyEventNotDurable(2))
        val error = runCatching { repository.republishKeyEvent() }.exceptionOrNull()
        assertTrue(error is BackupException)
        assertEquals(BackupErrorReason.KeyEventNotDurable(2), (error as BackupException).reason)
        coVerify(exactly = 0) { prefs.setLastKeyEventPublish(any()) }
    }
}
