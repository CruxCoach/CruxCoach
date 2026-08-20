package com.cruxcoach.android.ui.board

import com.cruxcoach.domain.board.BoardBrand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import java.io.File
import java.security.MessageDigest

/**
 * Verifies the FEAT-031 brand-namespaced background asset paths: Kilter keeps
 * the historical flat path, the other Aurora-family boards are namespaced, and
 * colliding product_size ids across brands resolve to distinct files.
 */
class BoardImageAssetsTest {

    @Test
    fun kilterUsesFlatHistoricalPath() {
        assertEquals("board_images/board_7.webp", boardImageAssetPath(BoardBrand.KILTER, 7))
    }

    @Test
    fun auroraFamilyBoardsAreBrandNamespaced() {
        assertEquals("board_images/tension/board_1.webp", boardImageAssetPath(BoardBrand.TENSION, 1))
        assertEquals("board_images/grasshopper/board_4.webp", boardImageAssetPath(BoardBrand.GRASSHOPPER, 4))
        assertEquals("board_images/decoy/board_2.webp", boardImageAssetPath(BoardBrand.DECOY, 2))
        assertEquals("board_images/soill/board_1.webp", boardImageAssetPath(BoardBrand.SOILL, 1))
        assertEquals("board_images/touchstone/board_1.webp", boardImageAssetPath(BoardBrand.TOUCHSTONE, 1))
    }

    @Test
    fun quantumModelsUseTheirOriginalEwallsAssets() {
        val expected = mapOf(
            9201L to Pair("board_images/quantum/board_9201.png", "73c1ddc6c11a9270dce36358953154fd47ae669403dc7907cb2943eb680bea32"),
            9202L to Pair("board_images/quantum/board_9202.png", "3f5e34981aafeff35f9aee8dbc321cc8741306e3c56e626557c14ed3a12cc51f"),
            9203L to Pair("board_images/quantum/board_9203.png", "4414bb3233b905c5699b527e76b87667bf9490ae66beb0fbef64a2d761c4dfc1"),
            9204L to Pair("board_images/quantum/board_9204.jpg", "07cbaa0f948a12b9f74a17b76f44bb2ca5307890a98f22631cdee3fa8a8af3e6"),
            9205L to Pair("board_images/quantum/board_9205.jpg", "57e31a96fb8c3ff134b6c724656f5b7249f8439f1c163d98aa44dc8358b4601d"),
        )
        expected.forEach { (sizeId, contract) ->
            val (path, digest) = contract
            assertEquals(path, boardImageAssetPath(BoardBrand.QUANTUM, sizeId))
            val file = assetFile(path)
            assertEquals(digest, file.inputStream().use { input ->
                val md = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    md.update(buffer, 0, read)
                }
                md.digest().joinToString("") { "%02x".format(it) }
            })
        }
    }

    @Test
    fun quantumViewportMatchesEwalls2014Transform() {
        assertEquals(107_280f, QUANTUM_IMAGE_EDGE_RIGHT - QUANTUM_IMAGE_EDGE_LEFT)
        assertEquals(107_600f, QUANTUM_IMAGE_EDGE_TOP - QUANTUM_IMAGE_EDGE_BOTTOM)
    }

    @Test
    fun collidingSizeIdsAcrossBrandsResolveToDistinctPaths() {
        // Kilter size 7 and (a hypothetical) Tension size 7 must not alias.
        assertNotEquals(
            boardImageAssetPath(BoardBrand.KILTER, 7),
            boardImageAssetPath(BoardBrand.TENSION, 7),
        )
    }

    private fun assetFile(path: String): File {
        val candidates = listOf(File("src/main/assets/$path"), File("androidApp/src/main/assets/$path"))
        return candidates.firstOrNull(File::isFile)
            ?: error("missing test asset $path")
    }
}
