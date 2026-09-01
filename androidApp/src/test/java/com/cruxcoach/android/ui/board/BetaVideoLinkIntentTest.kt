package com.cruxcoach.android.ui.board

import android.content.Intent
import com.cruxcoach.data.repository.ClimbBetaLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class BetaVideoLinkIntentTest {
    @Test
    fun instagramUsesPackageFirstAndIdenticalHttpsFallback() {
        val url = "https://www.instagram.com/reel/abc123/"
        val intents = betaLinkIntents(
            ClimbBetaLink("moonboard", "climb", url, "instagram")
        )

        assertEquals(2, intents.size)
        assertEquals(Intent.ACTION_VIEW, intents[0].action)
        assertEquals("com.instagram.android", intents[0].`package`)
        assertEquals(url, intents[0].dataString)
        assertNull(intents[1].`package`)
        assertEquals(url, intents[1].dataString)
    }

    @Test
    fun rejectsNonHttpsAndDoesNotPackageOtherProviders() {
        assertEquals(
            emptyList<Intent>(),
            betaLinkIntents(ClimbBetaLink("kilter", "climb", "file:///private/video", "instagram")),
        )
        assertEquals(
            emptyList<Intent>(),
            betaLinkIntents(ClimbBetaLink("kilter", "climb", "https:///missing-host", "instagram")),
        )
        assertEquals(
            emptyList<Intent>(),
            betaLinkIntents(ClimbBetaLink("kilter", "climb", "https://user:pass@example.com/video", "instagram")),
        )
        val generic = betaLinkIntents(
            ClimbBetaLink("kilter", "climb", "https://example.com/video", "unknown")
        )
        assertEquals(1, generic.size)
        assertNull(generic.single().`package`)
    }
}
