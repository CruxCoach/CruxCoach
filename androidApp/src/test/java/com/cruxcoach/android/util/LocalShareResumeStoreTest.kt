package com.cruxcoach.android.util

import android.content.Context
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class LocalShareResumeStoreTest {
    private lateinit var context: Context
    private lateinit var store: LocalShareResumeStore

    @BeforeTest
    fun setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        context.getSharedPreferences("local_share_resume_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = LocalShareResumeStore(context)
    }

    @Test
    fun v2ProtocolAndArtifactPathSurviveApkUpdateHandoff() {
        val cached = File(context.cacheDir, "quantum-board.gz").apply { writeText("payload") }
        val hash = "a".repeat(64)
        store.save(
            LocalShareResumeStore.Pending(
                requiredVersionCode = 22,
                protocolVersion = LocalShareProtocol.VERSION_V2,
                apkPath = null,
                apkVersionName = "0.2.2",
                boardPath = cached.absolutePath,
                board = LocalShareProtocol.BoardArtifact(
                    artifact = LocalShareProtocol.Artifact(
                        LocalShareProtocol.V2_BOARD_PATH,
                        cached.length(),
                        hash,
                    ),
                    compression = "gzip",
                    uncompressedSizeBytes = 99,
                    uncompressedSha256 = "b".repeat(64),
                    schemaVersion = 27,
                    catalogues = listOf(LocalShareProtocol.BoardCatalogue("quantum", 1)),
                ),
            ),
        )

        val restored = store.load()!!
        assertEquals(LocalShareProtocol.VERSION_V2, restored.protocolVersion)
        assertEquals(LocalShareProtocol.V2_BOARD_PATH, restored.board!!.artifact.path)
        assertEquals(listOf("quantum"), restored.board.catalogues.map { it.boardBrand })
    }

    @Test
    fun pre022RecordDefaultsToExactLegacyV1Artifact() {
        val cached = File(context.cacheDir, "legacy-board.gz").apply { writeText("payload") }
        context.getSharedPreferences("local_share_resume_v1", Context.MODE_PRIVATE).edit()
            .putString(
                "pending",
                JSONObject()
                    .put("requiredVersionCode", 21)
                    .put("boardPath", cached.absolutePath)
                    .put("boardSizeBytes", cached.length())
                    .put("boardSha256", "c".repeat(64))
                    .put("boardUncompressedSizeBytes", 123)
                    .put("boardUncompressedSha256", "d".repeat(64))
                    .put("boardSchemaVersion", 25)
                    .toString(),
            )
            .commit()

        val restored = store.load()!!
        assertEquals(LocalShareProtocol.VERSION, restored.protocolVersion)
        assertEquals(LocalShareProtocol.BOARD_PATH, restored.board!!.artifact.path)
    }

    private fun shinyWallFamily(file: File) = LocalShareProtocol.FamilyArtifact(
        boardBrand = LocalShareProtocol.SHINYWALL_BRAND,
        format = LocalShareProtocol.SHINYWALL_FORMAT,
        artifact = LocalShareProtocol.Artifact(
            LocalShareProtocol.V2_SHINYWALL_PATH,
            file.length(),
            "1".repeat(64),
        ),
        compression = "gzip",
        uncompressedSizeBytes = 4096,
        uncompressedSha256 = "2".repeat(64),
        climbCount = 812,
    )

    private fun v2Board(file: File) = LocalShareProtocol.BoardArtifact(
        artifact = LocalShareProtocol.Artifact(LocalShareProtocol.V2_BOARD_PATH, file.length(), "a".repeat(64)),
        compression = "gzip",
        uncompressedSizeBytes = 99,
        uncompressedSha256 = "b".repeat(64),
        schemaVersion = 27,
        catalogues = listOf(LocalShareProtocol.BoardCatalogue("kilter", 1)),
    )

    @Test
    fun shinyWallCatalogueSurvivesTheApkUpdateNextToTheBoard() {
        val board = File(context.cacheDir, "kilter-board.gz").apply { writeText("payload") }
        val shiny = File(context.cacheDir, "local_shinywall_test.gz").apply { writeText("shiny") }
        val family = shinyWallFamily(shiny)
        store.save(
            LocalShareResumeStore.Pending(
                requiredVersionCode = 26,
                protocolVersion = LocalShareProtocol.VERSION_V2,
                apkPath = null,
                apkVersionName = "0.2.5.1",
                boardPath = board.absolutePath,
                board = v2Board(board),
                shinyWallPath = shiny.absolutePath,
                shinyWall = family,
            ),
        )

        val restored = store.load()!!
        assertEquals(board.absolutePath, restored.boardPath)
        assertEquals(shiny.absolutePath, restored.shinyWallPath)
        assertEquals(family, restored.shinyWall)
    }

    @Test
    fun shinyWallCatalogueAloneSurvivesWithoutABoardSnapshot() {
        // The receiver held the sender's board snapshot already; only Shiny Wall was new.
        val shiny = File(context.cacheDir, "local_shinywall_alone.gz").apply { writeText("shiny") }
        store.save(
            LocalShareResumeStore.Pending(
                requiredVersionCode = 26,
                protocolVersion = LocalShareProtocol.VERSION_V2,
                apkPath = null,
                apkVersionName = "0.2.5.1",
                boardPath = null,
                board = null,
                shinyWallPath = shiny.absolutePath,
                shinyWall = shinyWallFamily(shiny),
            ),
        )

        val restored = store.load()!!
        assertNull(restored.board)
        assertNull(restored.boardPath)
        assertEquals(shiny.absolutePath, restored.shinyWallPath)
        assertEquals(812L, restored.shinyWall!!.climbCount)
    }

    @Test
    fun recordWrittenByOneZeroTwoFiveLoadsWithoutShinyWall() {
        // Exact 0.2.5 shape: v2 board fields, no Shiny Wall fields at all.
        val cached = File(context.cacheDir, "v025-board.gz").apply { writeText("payload") }
        context.getSharedPreferences("local_share_resume_v1", Context.MODE_PRIVATE).edit()
            .putString(
                "pending",
                JSONObject()
                    .put("requiredVersionCode", 25)
                    .put("protocolVersion", LocalShareProtocol.VERSION_V2)
                    .put("apkVersionName", "0.2.5")
                    .put("boardPath", cached.absolutePath)
                    .put("boardArtifactPath", LocalShareProtocol.V2_BOARD_PATH)
                    .put("boardSizeBytes", cached.length())
                    .put("boardSha256", "c".repeat(64))
                    .put("boardUncompressedSizeBytes", 123)
                    .put("boardUncompressedSha256", "d".repeat(64))
                    .put("boardSchemaVersion", 27)
                    .toString(),
            )
            .commit()

        val restored = store.load()!!
        assertEquals(LocalShareProtocol.V2_BOARD_PATH, restored.board!!.artifact.path)
        assertNull(restored.shinyWall)
        assertNull(restored.shinyWallPath)
    }

    @Test
    fun untrustedShinyWallPartIsDroppedButTheBoardStays() {
        val board = File(context.cacheDir, "kilter-board-2.gz").apply { writeText("payload") }
        val outside = File(context.filesDir, "outside-cache.gz").apply { writeText("shiny") }
        store.save(
            LocalShareResumeStore.Pending(
                requiredVersionCode = 26,
                protocolVersion = LocalShareProtocol.VERSION_V2,
                apkPath = null,
                apkVersionName = "0.2.5.1",
                boardPath = board.absolutePath,
                board = v2Board(board),
                shinyWallPath = outside.absolutePath,
                shinyWall = shinyWallFamily(outside),
            ),
        )

        val restored = store.load()!!
        assertEquals(board.absolutePath, restored.boardPath)
        assertNull(restored.shinyWall)
        assertNull(restored.shinyWallPath)
        outside.delete()
    }

    @Test
    fun persistedProtocolPathMismatchIsRejectedAndCleared() {
        val cached = File(context.cacheDir, "tampered-board.gz").apply { writeText("payload") }
        context.getSharedPreferences("local_share_resume_v1", Context.MODE_PRIVATE).edit()
            .putString(
                "pending",
                JSONObject()
                    .put("requiredVersionCode", 22)
                    .put("protocolVersion", LocalShareProtocol.VERSION_V2)
                    .put("boardArtifactPath", LocalShareProtocol.BOARD_PATH)
                    .put("boardPath", cached.absolutePath)
                    .put("boardSizeBytes", cached.length())
                    .put("boardSha256", "e".repeat(64))
                    .put("boardUncompressedSizeBytes", 123)
                    .put("boardUncompressedSha256", "f".repeat(64))
                    .put("boardSchemaVersion", 27)
                    .toString(),
            )
            .commit()

        assertNull(store.load())
        assertNull(
            context.getSharedPreferences("local_share_resume_v1", Context.MODE_PRIVATE)
                .getString("pending", null),
        )
    }
}
