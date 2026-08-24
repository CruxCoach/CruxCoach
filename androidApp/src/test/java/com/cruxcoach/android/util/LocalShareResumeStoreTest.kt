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
