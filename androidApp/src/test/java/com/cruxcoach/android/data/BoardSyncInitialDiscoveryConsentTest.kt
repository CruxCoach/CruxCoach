package com.cruxcoach.android.data

import android.content.Context
import android.net.Network
import com.cruxcoach.android.data.blossom.BlossomSyncManager
import com.cruxcoach.android.updater.IntegrityVerifier
import com.cruxcoach.android.util.LocalShareDiscovery
import com.cruxcoach.android.util.LocalShareProtocol
import com.cruxcoach.data.repository.BoardLocationRepository
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.PersonalBoardRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BoardSyncInitialDiscoveryConsentTest {
    private val context: Context
        get() = org.robolectric.RuntimeEnvironment.getApplication()

    private fun manifest() = LocalShareProtocol.Manifest(
        protocolVersion = LocalShareProtocol.VERSION_V2,
        sessionId = "01234567-89ab-cdef-0123-456789abcdef",
        apkVersionCode = 8,
        apkVersionName = "0.2.2",
        apk = LocalShareProtocol.Artifact(
            path = LocalShareProtocol.APK_PATH,
            sizeBytes = 3,
            sha256 = "a".repeat(64),
        ),
        board = LocalShareProtocol.BoardArtifact(
            artifact = LocalShareProtocol.Artifact(
                path = LocalShareProtocol.V2_BOARD_PATH,
                sizeBytes = 4,
                sha256 = "b".repeat(64),
            ),
            compression = "gzip",
            uncompressedSizeBytes = 8,
            uncompressedSha256 = "c".repeat(64),
            schemaVersion = 27,
            catalogues = listOf(LocalShareProtocol.BoardCatalogue("quantum", 42)),
        ),
        boardStatus = "ready",
    )

    private fun TestScope.manager(
        discover: suspend () -> LocalShareDiscovery.Found?,
        fallback: (BoardSyncManager) -> Unit = {},
        runner: suspend (LocalShareDiscovery.Found) -> Unit = {},
    ): BoardSyncManager {
        val importer = mockk<BoardDatabaseImporter>(relaxed = true)
        every { importer.isImported() } returns false
        val preferences = mockk<UserPreferences>(relaxed = true)
        every { preferences.lastSyncTimestamp } returns flowOf(null)
        return BoardSyncManager(
            importer = importer,
            blossomSyncManager = mockk<BlossomSyncManager>(relaxed = true),
            userPreferences = preferences,
            appContext = context,
            boardRepository = mockk<BoardRepository>(relaxed = true),
            personalBoardRepo = mockk<PersonalBoardRepository>(relaxed = true),
            boardLocationRepository = mockk<BoardLocationRepository>(relaxed = true),
            moonBoardCatalogueSync = mockk<MoonBoardCatalogueSync>(relaxed = true),
            auroraCatalogueSync = mockk<AuroraCatalogueSync>(relaxed = true),
            quantumCatalogueSync = mockk<QuantumCatalogueSync>(relaxed = true),
            integrityVerifier = mockk<IntegrityVerifier>(relaxed = true),
            scope = backgroundScope,
            discoverInitialShare = discover,
            initialOnlineFallback = fallback,
            initialShareRunner = runner,
        )
    }

    @Test
    fun `discovery stages exact peer and transfers only after one confirmation`() = runTest {
        val found = LocalShareDiscovery.Found(
            network = mockk<Network>(),
            baseUrl = "http://192.168.49.1:4949",
            manifest = manifest(),
        )
        var probes = 0
        var transfers = 0
        var transferred: LocalShareDiscovery.Found? = null
        val manager = manager(
            discover = { probes++; found },
            runner = { transfers++; transferred = it },
        )
        runCurrent() // manager init

        manager.startInitialSyncIfNeeded()
        runCurrent()

        assertEquals(1, probes)
        assertSame(found, manager.state.value.pendingDiscoveredShare)
        assertFalse(manager.state.value.isSyncing)
        assertFalse(manager.state.value.localShareInProgress)
        assertNull(manager.state.value.importStep)
        assertEquals(0, transfers)

        // Recomposition/proxy retries cannot probe or transfer again while
        // the offer is awaiting the user's one answer.
        manager.startInitialSyncIfNeeded()
        manager.confirmDiscoveredShare()
        manager.confirmDiscoveredShare()
        runCurrent()

        assertEquals(1, probes)
        assertEquals(1, transfers)
        assertSame(found, transferred)
        assertNull(manager.state.value.pendingDiscoveredShare)
        assertTrue(manager.state.value.isSyncing)
        assertTrue(manager.state.value.localShareInProgress)
    }

    @Test
    fun `dismiss and no-peer each choose online fallback exactly once`() = runTest {
        val found = LocalShareDiscovery.Found(
            network = mockk<Network>(),
            baseUrl = "http://192.168.49.1:4949",
            manifest = manifest(),
        )
        var dismissedFallbacks = 0
        val staged = manager(
            discover = { found },
            fallback = { dismissedFallbacks++ },
        )
        runCurrent()
        staged.startInitialSyncIfNeeded()
        runCurrent()
        staged.dismissDiscoveredShare()
        staged.dismissDiscoveredShare()
        runCurrent()
        assertEquals(1, dismissedFallbacks)
        assertNull(staged.state.value.pendingDiscoveredShare)

        var noPeerFallbacks = 0
        val absent = manager(
            discover = { null },
            fallback = { noPeerFallbacks++ },
        )
        runCurrent()
        absent.startInitialSyncIfNeeded()
        runCurrent()
        assertEquals(1, noPeerFallbacks)
        assertNull(absent.state.value.pendingDiscoveredShare)
        assertFalse(absent.state.value.isSyncing)
    }

    @Test
    fun `offline permission result can consume only the invitation the user saw`() = runTest {
        val manager = manager(discover = { null })
        runCurrent()
        val first = LocalShareProtocol.Invitation(
            baseUrl = "http://192.168.49.1:4949",
            ssid = "CruxCoach-A",
            password = "password-a",
        )
        val replacement = LocalShareProtocol.Invitation(
            baseUrl = "http://192.168.50.1:4949",
            ssid = "CruxCoach-B",
            password = "password-b",
        )

        manager.stageOfflineShare(first)
        manager.stageOfflineShare(replacement)

        assertEquals(first, manager.state.value.pendingOfflineShare)
        manager.confirmOfflineShare(replacement)
        assertEquals(
            "an asynchronous permission answer for another invitation is a no-op",
            first,
            manager.state.value.pendingOfflineShare,
        )
        assertFalse(manager.state.value.isSyncing)
        assertFalse(manager.state.value.localShareInProgress)

        manager.dismissOfflineShare()
        assertNull(manager.state.value.pendingOfflineShare)
    }
}
