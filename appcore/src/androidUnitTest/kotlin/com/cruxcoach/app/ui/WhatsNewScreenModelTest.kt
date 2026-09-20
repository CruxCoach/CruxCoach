package com.cruxcoach.app.ui

import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The rules that keep release notes from becoming noise. */
class WhatsNewScreenModelTest {
    private val store = MemoryKeyValueStore()

    private fun model(version: Int = 9) = WhatsNewScreenModel(store, version)

    @Test
    fun `a fresh install is told nothing`() {
        val model = model()
        model.start(onboardingCompleted = false)
        assertEquals("", model.currentId)
        // The watermark is set anyway, so the first upgrade shows only what
        // is genuinely new.
        assertEquals("9", store.values[WhatsNewScreenModel.KEY_LAST_SEEN])
    }

    @Test
    fun `an upgrade shows every note it skipped, oldest first`() {
        store.values[WhatsNewScreenModel.KEY_LAST_SEEN] = "7"
        val model = model()
        model.start(onboardingCompleted = true)
        assertEquals("release-0.2.2", model.currentId)
        model.dismiss()
        assertEquals("release-0.2.3", model.currentId)
        // Not written until the whole queue has been read.
        assertEquals("7", store.values[WhatsNewScreenModel.KEY_LAST_SEEN])
        model.dismiss()
        assertEquals("", model.currentId)
        assertEquals("9", store.values[WhatsNewScreenModel.KEY_LAST_SEEN])
    }

    @Test
    fun `an install from before the mechanism existed sees the whole history once`() {
        val model = model()
        model.start(onboardingCompleted = true)
        val seen = generateSequence(model.currentId.takeIf { it.isNotEmpty() }) {
            model.dismiss()
            model.currentId.takeIf { it.isNotEmpty() }
        }.toList()
        assertEquals(
            listOf("nostr-backup", "aurora-json-import", "release-0.2.1", "release-0.2.2", "release-0.2.3"),
            seen,
        )
        assertEquals("9", store.values[WhatsNewScreenModel.KEY_LAST_SEEN])
    }

    @Test
    fun `nothing is replayed once it has been read`() {
        store.values[WhatsNewScreenModel.KEY_LAST_SEEN] = "9"
        val model = model()
        model.start(onboardingCompleted = true)
        assertEquals("", model.currentId)
    }

    @Test
    fun `the watermark is never lowered by an older build`() {
        store.values[WhatsNewScreenModel.KEY_LAST_SEEN] = "12"
        val model = model(version = 9)
        model.start(onboardingCompleted = true)
        assertEquals("", model.currentId)
        assertEquals("12", store.values[WhatsNewScreenModel.KEY_LAST_SEEN], "a downgrade must not replay notes")
        assertTrue(store.values.containsKey(WhatsNewScreenModel.KEY_LAST_SEEN))
    }
}
