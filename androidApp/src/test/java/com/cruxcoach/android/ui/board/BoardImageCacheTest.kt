package com.cruxcoach.android.ui.board

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class BoardImageCacheTest {

    @Test
    fun `missing optional vendor background degrades to placements only`() = runTest {
        BoardImageCache.clear()

        val bitmap = BoardImageCache.getOrDecode(
            candidates = listOf(
                "board_images/tension/board_6_10.webp",
                "board_images/tension/board_6.webp",
            ),
            assetManager = RuntimeEnvironment.getApplication().assets,
        )

        assertNull(bitmap)
    }

    @Test
    fun `distribution contains only project-created MoonBoard backgrounds`() {
        val webpAssets = RuntimeEnvironment.getApplication().assets
            .list("board_images")
            .orEmpty()
            .filter { it.endsWith(".webp") }
            .toSet()

        assertEquals(
            setOf(
                "mini_moonboard_2020.webp",
                "moonboard_2016.webp",
                "moonboard_2017.webp",
                "moonboard_2019.webp",
                "moonboard_2024.webp",
            ),
            webpAssets,
        )
    }
}
