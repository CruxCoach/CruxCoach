package com.cruxcoach.android.data.kilter

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class KilterUploadDiagnosticsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    @Before fun clear() {
        context.getSharedPreferences("kilter_upload_diagnostics", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun bounded_history_survives_restart_and_can_be_cleared() {
        val store = KilterUploadDiagnostics(context)
        repeat(25) { store.record(KilterUploadStatus(uploaded = it)) }
        val restored = KilterUploadDiagnostics(context)
        assertEquals(20, restored.snapshot().lines().size)
        assertEquals(24, restored.latest.value?.uploaded)
        val frozen = restored.snapshot()
        restored.record(KilterUploadStatus(uploaded = 99))
        assertFalse(frozen.contains("uploaded=99"))
        restored.clear()
        assertEquals("", KilterUploadDiagnostics(context).snapshot())
        assertNull(restored.latest.value)
    }

    @Test fun old_entries_are_removed_from_persistent_storage_on_open() {
        val stale = KilterUploadStatus(timestampMs = 1, reason = KilterUploadReason.NETWORK)
        val prefs = context.getSharedPreferences("kilter_upload_diagnostics", Context.MODE_PRIVATE)
        prefs.edit().putString("history", Json.encodeToString(listOf(stale))).commit()
        val store = KilterUploadDiagnostics(context)
        assertEquals("", store.snapshot())
        assertNull(store.latest.value)
        assertEquals("[]", prefs.getString("history", null))
    }
}
