package com.cruxcoach.android.foodvision

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Model downloads try the CruxCoach Blossom mirror by SHA-256 before Hugging Face (FEAT-069). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class VisionModelMirrorTest {

    private val store get() = VisionModelStore(ApplicationProvider.getApplicationContext<Application>())
    private val file = VisionModels.SMALL.weights

    @Test
    fun `the mirror serves a file it holds with the right size`() {
        val s = store.apply { headLength = { url -> if (url.endsWith(file.sha256)) file.bytes else null } }
        assertEquals("https://blossom.cruxcoach.org/${file.sha256}", s.sourceFor(file))
    }

    @Test
    fun `a missing or wrong-sized mirror file falls back to Hugging Face`() {
        assertEquals(file.url, store.apply { headLength = { null } }.sourceFor(file))
        assertEquals(file.url, store.apply { headLength = { file.bytes - 1 } }.sourceFor(file))
    }
}
