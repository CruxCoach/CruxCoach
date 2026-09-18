package com.cruxcoach.android.ui.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cruxcoach.android.data.SyncInterval
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.nostr.backup.BackupPreferences
import com.cruxcoach.android.nostr.backup.BackupRepository
import com.cruxcoach.android.nostr.backup.BackupSyncWorker
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AccountBackupFlowTest {
    private val dispatcher = StandardTestDispatcher()
    private val prefs = mockk<BackupPreferences>(relaxed = true)
    private val user = mockk<UserPreferences>(relaxed = true)
    private val repository = mockk<BackupRepository>(relaxed = true)
    private fun vm(enabled: Boolean = false): AccountBackupViewModel {
        every { prefs.backupEnabled } returns flowOf(enabled)
        return AccountBackupViewModel(prefs, user, repository, ApplicationProvider.getApplicationContext())
    }
    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { prefs.isBackupFeatureEnabled() } returns true
        mockkObject(BackupSyncWorker.Companion)
        every { BackupSyncWorker.schedule(any(), any(), any()) } just Runs
    }
    @After fun tearDown() { Dispatchers.resetMain(); unmockkAll() }

    @Test fun `selecting backup or copying never enables uploads and cancel discards consent`() = runTest(dispatcher) {
        val vm = vm(); vm.open(); runCurrent()
        assertFalse(vm.state.value.wantsBackup)
        vm.chooseBackup(true)
        vm.confirmStored() // cannot confirm before copying
        assertTrue(vm.beginAuthentication())
        vm.authenticationFinished(true)
        assertEquals(AccountBackupStep.STORE, vm.state.value.step)
        vm.close(); runCurrent()
        coVerify(exactly = 0) { repository.performFullBackup(any()) }
        coVerify(exactly = 0) { prefs.setBackupEnabled(any()) }
        coVerify(exactly = 0) { user.setKeyBackedUp(any()) }
        vm.open(); runCurrent(); assertFalse(vm.state.value.wantsBackup)
    }
    @Test fun `key only confirmation never activates cloud backup`() = runTest(dispatcher) {
        val vm = vm(); vm.open(); runCurrent()
        vm.beginAuthentication(); vm.authenticationFinished(true); vm.confirmStored(); runCurrent()
        assertEquals(AccountBackupStep.SUCCESS, vm.state.value.step)
        coVerify { user.setKeyBackedUp(true) }
        coVerify(exactly = 0) { repository.performFullBackup(any()) }
        coVerify(exactly = 0) { prefs.setBackupEnabled(any()) }
    }
    @Test fun `daily backups start only after successful first upload and duplicate taps do nothing`() = runTest(dispatcher) {
        val uploaded = CompletableDeferred<Unit>()
        coEvery { repository.performFullBackup(any()) } coAnswers { uploaded.await(); 0 }
        val vm = vm(); vm.open(); runCurrent(); vm.chooseBackup(true)
        vm.beginAuthentication(); vm.authenticationFinished(true)
        vm.confirmStored(); vm.confirmStored(); runCurrent()
        assertEquals(AccountBackupStep.RUNNING, vm.state.value.step)
        vm.close(); assertEquals(AccountBackupStep.RUNNING, vm.state.value.step)
        coVerify(exactly = 0) { prefs.setBackupEnabled(any()) }
        uploaded.complete(Unit); runCurrent()
        assertEquals(AccountBackupStep.SUCCESS, vm.state.value.step)
        coVerify(exactly = 1) { repository.performFullBackup("key-storage") }
        coVerify { prefs.setBackupInterval(SyncInterval.DAILY); prefs.setBackupEnabled(true) }
        verify { BackupSyncWorker.schedule(any(), true, SyncInterval.DAILY) }
    }
    @Test fun `failure stays inline and retry does not require recopying the key`() = runTest(dispatcher) {
        coEvery { repository.performFullBackup(any()) } throws IllegalStateException("test failure")
        val vm = vm(); vm.open(); runCurrent(); vm.chooseBackup(true)
        vm.beginAuthentication(); vm.authenticationFinished(true); vm.confirmStored(); runCurrent()
        assertEquals(AccountBackupStep.ERROR, vm.state.value.step)
        coVerify(exactly = 0) { prefs.setBackupEnabled(any()) }
        coEvery { repository.performFullBackup(any()) } returns 0
        vm.confirmStored(); runCurrent()
        assertEquals(AccountBackupStep.SUCCESS, vm.state.value.step)
    }
    @Test fun `existing automatic or manual backup configuration is preserved`() = runTest(dispatcher) {
        val vm = vm(true); vm.open(); runCurrent(); vm.chooseBackup(false)
        assertTrue(vm.state.value.wantsBackup)
        vm.beginAuthentication(); vm.authenticationFinished(true); vm.confirmStored(); runCurrent()
        assertEquals(AccountBackupStep.SUCCESS, vm.state.value.step)
        coVerify(exactly = 0) { prefs.setBackupEnabled(any()); prefs.setBackupInterval(any()) }
        verify(exactly = 0) { BackupSyncWorker.schedule(any(), any(), any()) }
    }
    @Test fun `already stored key entry still requires explicit confirmation`() = runTest(dispatcher) {
        val vm = vm(); vm.open(alreadyStored = true); runCurrent()
        assertEquals(AccountBackupStep.STORE, vm.state.value.step)
        assertFalse(vm.state.value.copiedInFlow)
        coVerify(exactly = 0) { user.setKeyBackedUp(any()) }
        vm.close(); runCurrent()
        coVerify(exactly = 0) { repository.performFullBackup(any()) }
    }
    @Test fun `disabled backup feature cannot publish through the key flow`() = runTest(dispatcher) {
        coEvery { prefs.isBackupFeatureEnabled() } returns false
        val vm = vm(); vm.open(alreadyStored = true); runCurrent(); vm.chooseBackup(true)
        vm.confirmStored(); runCurrent()
        assertEquals(AccountBackupStep.ERROR, vm.state.value.step)
        coVerify(exactly = 0) { repository.performFullBackup(any()) }
        coVerify(exactly = 0) { prefs.setBackupEnabled(any()) }
    }
    @Test fun `cancelled authentication and recopy allow another attempt`() = runTest(dispatcher) {
        val vm = vm(); vm.open(); runCurrent()
        assertTrue(vm.beginAuthentication()); assertFalse(vm.beginAuthentication())
        vm.authenticationFinished(false)
        assertEquals(AccountBackupStep.COPY, vm.state.value.step)
        assertTrue(vm.beginAuthentication()); vm.authenticationFinished(true)
        assertTrue(vm.beginAuthentication()); vm.authenticationFinished(false)
        assertEquals(AccountBackupStep.STORE, vm.state.value.step)
    }
}
