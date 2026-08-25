package com.cruxcoach.android.data

import android.content.Context
import com.cruxcoach.android.data.blossom.BlossomSyncManager
import com.cruxcoach.android.updater.IntegrityVerifier
import com.cruxcoach.android.util.LocalShareProtocol
import com.cruxcoach.android.util.LocalShareResumeStore
import com.cruxcoach.android.util.ShareCompression
import com.cruxcoach.data.repository.BoardLocationRepository
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.PersonalBoardRepository
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Pins the protocol trust decision across the PackageInstaller hand-off. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BoardSyncLocalShareResumeProtocolTest {
    private val context: Context
        get() = org.robolectric.RuntimeEnvironment.getApplication()

    private val resumePreferences
        get() = context.getSharedPreferences("local_share_resume_v1", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        resumePreferences.edit().clear().commit()
    }

    @After
    fun tearDown() {
        resumePreferences.edit().clear().commit()
        context.cacheDir.listFiles()
            ?.filter { it.name.startsWith("resume-protocol-") }
            ?.forEach { it.delete() }
    }

    private fun compressedArtifact(
        name: String,
        artifactPath: String,
    ): Pair<File, LocalShareProtocol.BoardArtifact> {
        val raw = File(context.cacheDir, "resume-protocol-$name.raw").apply {
            writeText("verified board payload for $name")
        }
        val compressed = File(context.cacheDir, "resume-protocol-$name.db.gz")
        ShareCompression.gzip(raw, compressed)
        return compressed to LocalShareProtocol.BoardArtifact(
            artifact = LocalShareProtocol.Artifact(
                path = artifactPath,
                sizeBytes = compressed.length(),
                sha256 = LocalShareProtocol.sha256(compressed),
            ),
            compression = "gzip",
            uncompressedSizeBytes = raw.length(),
            uncompressedSha256 = LocalShareProtocol.sha256(raw),
            schemaVersion = 27,
            catalogues = emptyList(),
        )
    }

    private fun TestScope.manager(
        importer: BoardDatabaseImporter,
    ): BoardSyncManager {
        every { importer.isImported() } returns false
        val preferences = mockk<UserPreferences>(relaxed = true)
        every { preferences.lastSyncTimestamp } returns flowOf(null)
        val personal = mockk<PersonalBoardRepository>(relaxed = true)
        every { personal.getAllClimbKeys() } returns emptyList()
        return BoardSyncManager(
            importer = importer,
            blossomSyncManager = mockk<BlossomSyncManager>(relaxed = true),
            userPreferences = preferences,
            appContext = context,
            boardRepository = mockk<BoardRepository>(relaxed = true),
            personalBoardRepo = personal,
            boardLocationRepository = mockk<BoardLocationRepository>(relaxed = true),
            moonBoardCatalogueSync = mockk<MoonBoardCatalogueSync>(relaxed = true),
            auroraCatalogueSync = mockk<AuroraCatalogueSync>(relaxed = true),
            quantumCatalogueSync = mockk<QuantumCatalogueSync>(relaxed = true),
            integrityVerifier = mockk<IntegrityVerifier>(relaxed = true),
            scope = backgroundScope,
        )
    }

    @Test
    fun `resume dispatches v2 as full Quantum and pre-0_2_2 record as legacy`() = runTest {
        val includeQuantumCalls = mutableListOf<Boolean>()
        val importer = mockk<BoardDatabaseImporter>(relaxed = true)
        every { importer.importFromLocalDb(any(), any(), any()) } answers {
            includeQuantumCalls += secondArg<Boolean>()
        }

        val (v2Compressed, v2Board) = compressedArtifact(
            name = "v2",
            artifactPath = LocalShareProtocol.V2_BOARD_PATH,
        )
        LocalShareResumeStore(context).save(
            LocalShareResumeStore.Pending(
                requiredVersionCode = 0,
                protocolVersion = LocalShareProtocol.VERSION_V2,
                apkPath = null,
                apkVersionName = "0.2.2",
                boardPath = v2Compressed.absolutePath,
                board = v2Board,
            ),
        )

        manager(importer)
        runCurrent()

        val (legacyCompressed, legacyBoard) = compressedArtifact(
            name = "legacy",
            artifactPath = LocalShareProtocol.BOARD_PATH,
        )
        // Exact pre-0.2.2 persistence shape: no protocolVersion or
        // boardArtifactPath, both of which must default to v1.
        resumePreferences.edit().putString(
            "pending",
            JSONObject()
                .put("requiredVersionCode", 0)
                .put("boardPath", legacyCompressed.absolutePath)
                .put("boardSizeBytes", legacyBoard.artifact.sizeBytes)
                .put("boardSha256", legacyBoard.artifact.sha256)
                .put("boardUncompressedSizeBytes", legacyBoard.uncompressedSizeBytes)
                .put("boardUncompressedSha256", legacyBoard.uncompressedSha256)
                .put("boardSchemaVersion", legacyBoard.schemaVersion)
                .toString(),
        ).commit()

        manager(importer)
        runCurrent()

        assertEquals(listOf(true, false), includeQuantumCalls)
    }
}
