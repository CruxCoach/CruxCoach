package com.cruxcoach.android.data.kilter

import android.content.SharedPreferences
import io.mockk.mockk
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KilterTokenStoreRecoveryTest {
    @Test
    fun `open failure never deletes existing encrypted credentials`() {
        val storageFile = testFile("existing").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        var resetCalls = 0

        try {
            assertFailsWith<IllegalStateException> {
                openEncryptedPrefsPreservingExisting(
                    storageFile = storageFile,
                    create = { error("simulated provider failure") },
                    resetEmptyStore = { resetCalls++ },
                )
            }
            assertEquals(0, resetCalls)
            assertTrue(storageFile.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        } finally {
            storageFile.delete()
        }
    }

    @Test
    fun `fresh store may recover after an initial open failure`() {
        val storageFile = testFile("fresh")
        val recovered = mockk<SharedPreferences>()
        var createCalls = 0
        var resetCalls = 0

        val result = openEncryptedPrefsPreservingExisting(
            storageFile = storageFile,
            create = {
                createCalls++
                if (createCalls == 1) error("simulated first-open failure")
                recovered
            },
            resetEmptyStore = { resetCalls++ },
        )

        assertSame(recovered, result)
        assertEquals(2, createCalls)
        assertEquals(1, resetCalls)
    }

    private fun testFile(suffix: String): File =
        File("androidApp/build/tmp/kilter-store-recovery-$suffix.xml").apply {
            parentFile?.mkdirs()
            delete()
        }
}
