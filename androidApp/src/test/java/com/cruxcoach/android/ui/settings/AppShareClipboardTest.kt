package com.cruxcoach.android.ui.settings

import android.content.ClipboardManager
import android.content.Context
import android.content.ClipDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class, sdk = [33])
class AppShareClipboardTest {
    @Test
    fun `hotspot credentials can be copied as sensitive clipboard data`() {
        val context: Context = org.robolectric.RuntimeEnvironment.getApplication()

        copyToClipboardWithToast(
            context = context,
            label = "CruxCoach WiFi password",
            text = "test-secret",
            toastMessage = "copied",
            sensitive = true,
        )

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip!!
        assertEquals("test-secret", clip.getItemAt(0).text.toString())
        assertTrue(
            clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true,
        )
    }
}
