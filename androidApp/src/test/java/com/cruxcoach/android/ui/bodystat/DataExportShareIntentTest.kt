package com.cruxcoach.android.ui.bodystat

import android.app.Application
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DataExportShareIntentTest {
    @Test
    fun `share intent offers generic file targets while retaining actual MIME type`() {
        val uri = Uri.parse("content://com.cruxcoach.android.fileprovider/export.xlsx")
        val mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

        val intent = dataExportShareIntent(ExportShare(uri, mimeType))

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("*/*", intent.type)
        assertArrayEquals(arrayOf(mimeType), intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
        assertNotNull(intent.clipData)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }
}
