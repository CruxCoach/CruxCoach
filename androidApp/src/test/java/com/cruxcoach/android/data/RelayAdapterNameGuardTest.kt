package com.cruxcoach.android.data

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class RelayAdapterNameGuardTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val sharingName = "CruxCoach·Kilter Board@3"

    private val prefs
        get() = context.getSharedPreferences(RelayAdapterNameGuard.PREFS, Context.MODE_PRIVATE)

    @Before
    fun clearRecoveryRecord() {
        prefs.edit().clear().commit()
    }

    private fun seedRecord(original: String) {
        prefs.edit()
            .putBoolean(RelayAdapterNameGuard.KEY_NAME_DIRTY, true)
            .putString(RelayAdapterNameGuard.KEY_ORIGINAL_NAME, original)
            .commit()
    }

    private fun hasRecord() = prefs.getBoolean(RelayAdapterNameGuard.KEY_NAME_DIRTY, false)

    /** A Bluetooth adapter whose renames land at once, or later when [deferred]. */
    private class FakeAdapter(var name: String?) {
        var deferred = false
        var accept = true
        val queued = ArrayDeque<String>()
        val sent = mutableListOf<String>()
        val adapter: BluetoothAdapter = mockk(relaxed = true)

        init {
            every { adapter.name } answers { name }
            every { adapter.setName(any()) } answers {
                val requested = firstArg<String>()
                sent += requested
                if (!accept) return@answers false
                if (deferred) queued.addLast(requested) else name = requested
                true
            }
        }
    }

    private class FakeWakeups : RelayNameWakeups {
        var armedNow = false
        override fun setArmed(armed: Boolean) {
            armedNow = armed
        }
    }

    private fun TestScope.guard(
        fake: FakeAdapter?,
        wakeups: RelayNameWakeups = FakeWakeups(),
    ) = RelayAdapterNameGuard(
        context = context,
        adapterProvider = { fake?.adapter },
        scope = backgroundScope,
        wakeups = wakeups,
        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
    )

    @Test
    fun `lending records the own name first and giving back clears it`() = runTest {
        val fake = FakeAdapter("My phone")
        val wakeups = FakeWakeups()
        val guard = guard(fake, wakeups)

        assertEquals(RelayNameLend.LENT, guard.lend(sharingName))
        assertEquals(sharingName, fake.name)
        assertTrue(hasRecord())
        assertEquals("My phone", prefs.getString(RelayAdapterNameGuard.KEY_ORIGINAL_NAME, null))
        assertTrue("recovery wakeups armed while lent", wakeups.armedNow)
        assertEquals(RelayNameIssue.LENT, guard.status.value.issue)

        assertTrue(guard.giveBack())
        assertEquals("My phone", fake.name)
        assertFalse(hasRecord())
        assertFalse(wakeups.armedNow)
        assertEquals(RelayNameIssue.NONE, guard.status.value.issue)
    }

    @Test
    fun `a sharing name is never recorded as the phone's own`() = runTest {
        // Another CruxCoach app shares, or an earlier name never came back.
        val fake = FakeAdapter(sharingName)
        val guard = guard(fake)

        assertEquals(RelayNameLend.ALREADY_SHARING_NAME, guard.lend("CruxCoach·MoonBoard"))

        assertFalse(hasRecord())
        assertTrue(fake.sent.isEmpty())
        assertEquals(RelayNameIssue.ORIGINAL_UNKNOWN, guard.status.value.issue)
        assertEquals(sharingName, guard.status.value.currentName)
    }

    @Test
    fun `a recorded sharing name from an older version is dropped, not given back`() = runTest {
        seedRecord(sharingName)
        val fake = FakeAdapter(sharingName)
        val guard = guard(fake)

        guard.restoreIfStale().join()

        assertFalse(hasRecord())
        assertTrue(fake.sent.isEmpty())
        assertEquals(RelayNameIssue.ORIGINAL_UNKNOWN, guard.status.value.issue)
    }

    @Test
    fun `a rename that lands after a failed start is outrun by the give-back`() = runTest {
        // 0.2.4 lost the phone's name here: the start gave up after 2 s, the
        // old name still showed, the record was cleared, then the sharing name
        // landed and the next start recorded it as the phone's own.
        val fake = FakeAdapter("My phone").apply { deferred = true }
        val guard = guard(fake)

        assertEquals(RelayNameLend.NOT_APPLIED, guard.lend(sharingName))
        assertEquals("My phone", fake.name)

        val giveBack = backgroundScope.async { guard.giveBack() }
        runCurrent()
        // The give-back was sent even though the old name still shows.
        assertEquals(listOf(sharingName, "My phone"), fake.sent)
        assertTrue("record kept until the name has settled", hasRecord())

        // The late sharing name lands and the queued give-back is lost.
        fake.name = fake.queued.removeFirst()
        fake.queued.clear()
        fake.deferred = false
        advanceTimeBy(RelayAdapterNameGuard.LATE_RENAME_SETTLE_MS + 1)
        runCurrent()

        assertTrue(giveBack.await())
        assertEquals("My phone", fake.name)
        assertFalse(hasRecord())
    }

    @Test
    fun `a name the user chose while sharing is kept`() = runTest {
        val fake = FakeAdapter("My phone")
        val guard = guard(fake)
        assertEquals(RelayNameLend.LENT, guard.lend(sharingName))

        fake.name = "Renamed by hand"
        assertTrue(guard.giveBack())

        assertEquals("Renamed by hand", fake.name)
        assertFalse(hasRecord())
        assertEquals(listOf(sharingName), fake.sent)
    }

    @Test
    fun `a refused give-back is retried on its own`() = runTest {
        val fake = FakeAdapter("My phone")
        val guard = guard(fake)
        assertEquals(RelayNameLend.LENT, guard.lend(sharingName))

        fake.accept = false
        assertFalse(guard.giveBack())
        assertTrue(hasRecord())
        assertEquals(RelayNameIssue.RESTORE_PENDING, guard.status.value.issue)
        assertEquals("My phone", guard.status.value.originalName)

        fake.accept = true
        advanceTimeBy(RelayAdapterNameGuard.RETRY_DELAYS_MS.first() + 1)
        runCurrent()

        assertEquals("My phone", fake.name)
        assertFalse(hasRecord())
        assertEquals(RelayNameIssue.NONE, guard.status.value.issue)
    }

    @Test
    fun `a stale restore leaves a running share alone`() = runTest {
        val fake = FakeAdapter("My phone")
        val guard = guard(fake)
        assertEquals(RelayNameLend.LENT, guard.lend(sharingName))

        guard.restoreIfStale().join()

        assertEquals(sharingName, fake.name)
        assertTrue(hasRecord())
    }

    @Test
    fun `a record left by a dead process is given back on the next start`() = runTest {
        // Same file and keys as 0.2.2 to 0.2.4, so their records count too.
        seedRecord("My phone")
        val fake = FakeAdapter(sharingName)
        val wakeups = FakeWakeups()

        guard(fake, wakeups).restoreIfStale().join()

        assertEquals("My phone", fake.name)
        assertFalse(hasRecord())
        assertFalse(wakeups.armedNow)
    }

    @Test
    fun `without the permission the record stays and the settings say why`() = runTest {
        seedRecord("My phone")
        val adapter = mockk<BluetoothAdapter>(relaxed = true)
        every { adapter.name } throws SecurityException("no BLUETOOTH_CONNECT")
        every { adapter.setName(any()) } throws SecurityException("no BLUETOOTH_CONNECT")
        val wakeups = FakeWakeups()
        val guard = RelayAdapterNameGuard(
            context = context,
            adapterProvider = { adapter },
            scope = backgroundScope,
            wakeups = wakeups,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        guard.restoreIfStale().join()

        assertTrue(hasRecord())
        assertTrue("boot and the next connection still wake the app", wakeups.armedNow)
        assertEquals(RelayNameIssue.NEEDS_PERMISSION, guard.status.value.issue)
        assertEquals("My phone", guard.status.value.originalName)
    }

    @Test
    fun `no readable own name means no rename`() = runTest {
        val fake = FakeAdapter(null)
        val guard = guard(fake)

        assertEquals(RelayNameLend.UNKNOWN_ORIGINAL, guard.lend(sharingName))

        assertFalse(hasRecord())
        assertTrue(fake.sent.isEmpty())
    }

    @Test
    fun `the recovery receiver is enabled only while a name is out`() = runTest {
        val fake = FakeAdapter("My phone")
        val guard = RelayAdapterNameGuard(
            context = context,
            adapterProvider = { fake.adapter },
            scope = backgroundScope,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val component = ComponentName(context, RelayNameRecoveryReceiver::class.java)
        val pm = context.packageManager

        guard.lend(sharingName)
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            pm.getComponentEnabledSetting(component),
        )

        guard.giveBack()
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            pm.getComponentEnabledSetting(component),
        )
    }

    private fun systemAdapter(): BluetoothAdapter {
        org.robolectric.Shadows.shadowOf(context as Application)
            .grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        return (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    @Test
    fun `a crashing process sends the recorded name and keeps the record`() {
        val adapter = systemAdapter()
        adapter.setName(sharingName)
        seedRecord("My phone")

        RelayAdapterNameGuard.giveBackBeforeProcessEnds(context)

        assertEquals("My phone", adapter.name)
        assertTrue("the next start still verifies", hasRecord())
    }

    @Test
    fun `a crash never sends a recorded sharing name`() {
        val adapter = systemAdapter()
        adapter.setName("Shown name")
        seedRecord(sharingName)

        RelayAdapterNameGuard.giveBackBeforeProcessEnds(context)

        assertEquals("Shown name", adapter.name)
    }
}
