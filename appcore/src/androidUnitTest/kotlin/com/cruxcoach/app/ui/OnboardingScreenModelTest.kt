package com.cruxcoach.app.ui

import com.cruxcoach.app.browse.testing.BrowseTestDb
import com.cruxcoach.app.logbook.MapKeyValueStore
import com.cruxcoach.app.settings.SettingsStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** First-run flow: what it shows, and what it leaves in the preference store. */
class OnboardingScreenModelTest {
    private val db = BrowseTestDb()
    private val kv = MapKeyValueStore()

    @AfterTest
    fun tearDown() = db.close()

    private fun store(identity: String = IDENTITY) = SettingsStore(kv, identity)

    private fun model(settings: SettingsStore = store()) =
        OnboardingScreenModel(settings, kv, db.boardRepo)

    @Test
    fun `a first run suggests the active board and records an explicit empty consent`() {
        val model = model()
        val state = model.currentState

        assertEquals("board", state.step)
        assertEquals(1, state.stepNumber)
        assertEquals(2, state.stepCount)
        assertFalse(state.completed)
        assertEquals("kilter", state.boardBrandWire)
        assertEquals(listOf("kilter"), state.downloadBrandWires)
        // Skipping the download step must not leave the legacy "every board"
        // default for a later refresh to act on, so consent is written now.
        assertEquals("", kv.map["board_download_brands"])
        assertTrue(state.brands.single { it.brandWire == "kilter" }.autoDownload)
        assertTrue(state.brands.filter { it.brandWire != "kilter" }.none { it.autoDownload })
    }

    @Test
    fun `picking a board persists it immediately and narrows the download selection`() {
        val model = model()
        model.chooseBoard("moonboard", 17, 0)

        assertEquals("moonboard", kv.map["board_brand"])
        assertEquals("17", kv.map["board_layout_id"])
        assertEquals("0", kv.map["board_product_size_id"])
        assertEquals(listOf("moonboard"), model.currentState.downloadBrandWires)
        assertEquals("MoonBoard", model.currentState.boardTitle)
    }

    @Test
    fun `catalogue ticks toggle individually and as a whole`() {
        val model = model()
        model.toggleDownloadBrand("tension")
        assertEquals(listOf("kilter", "tension"), model.currentState.downloadBrandWires)
        model.toggleDownloadBrand("kilter")
        assertEquals(listOf("tension"), model.currentState.downloadBrandWires)

        model.toggleAllDownloadBrands()
        assertEquals(model.brandWires, model.currentState.downloadBrandWires)
        model.toggleAllDownloadBrands()
        assertTrue(model.currentState.downloadBrandWires.isEmpty())

        // Only brands whose catalogue this app can install are offerable.
        model.toggleDownloadBrand("quantum")
        model.toggleDownloadBrand("not-a-board")
        assertTrue(model.currentState.downloadBrandWires.isEmpty())
    }

    @Test
    fun `confirming the downloads writes the selection and moves to the data step`() {
        val model = model()
        model.toggleDownloadBrand("tension")
        model.confirmDownloads()

        assertEquals("kilter,tension", kv.map["board_download_brands"])
        val state = model.currentState
        assertEquals("data", state.step)
        assertEquals(2, state.stepNumber)

        model.back()
        assertEquals("board", model.currentState.step)
    }

    @Test
    fun `finishing marks onboarding complete for this identity only`() {
        val settings = store()
        val model = model(settings)
        assertFalse(model.currentState.completed)

        model.finish()

        assertEquals("true", kv.map["key/$IDENTITY/onboarding_completed"])
        assertTrue(model.currentState.completed)
        // A second identity on the same device starts its own first run.
        assertFalse(SettingsStore(kv, OTHER_IDENTITY).snapshot.onboardingCompleted)
        assertNull(kv.map["onboarding_completed"])
    }

    @Test
    fun `skipping completes without downloading anything`() {
        val model = model()
        model.skip()
        assertEquals("", kv.map["board_download_brands"])
        assertTrue(model.currentState.completed)
    }

    @Test
    fun `a returning user keeps the stored selection instead of the suggestion`() {
        kv.map["board_download_brands"] = "tension,soill"
        kv.map["board_brand"] = "kilter"
        val state = model().currentState
        assertEquals(listOf("tension", "soill"), state.downloadBrandWires)
        // Reading must not overwrite the stored consent.
        assertEquals("tension,soill", kv.map["board_download_brands"])
    }

    private companion object {
        /** 16 hex chars: the identity prefix length SettingsStore scopes on. */
        const val IDENTITY = "0011223344556677"
        const val OTHER_IDENTITY = "8899aabbccddeeff"
    }
}
