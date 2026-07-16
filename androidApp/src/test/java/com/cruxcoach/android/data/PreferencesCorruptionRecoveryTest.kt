package com.cruxcoach.android.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

class PreferencesCorruptionRecoveryTest {

    @Test
    fun corrupt_preferences_file_is_replaced_with_empty_preferences() = runTest {
        val file = File.createTempFile("corrupt_preferences_", ".preferences_pb")
        file.writeBytes(byteArrayOf(0x7f, 0x00, 0x42, 0x13))
        file.deleteOnExit()
        val store = PreferenceDataStoreFactory.create(
            corruptionHandler = emptyPreferencesCorruptionHandler(),
            scope = backgroundScope,
            produceFile = { file },
        )

        assertTrue(store.data.first().asMap().isEmpty())
    }
}
