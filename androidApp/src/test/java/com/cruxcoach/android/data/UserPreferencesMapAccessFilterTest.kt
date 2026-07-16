package com.cruxcoach.android.data

import com.cruxcoach.android.fakes.createTestUserPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UserPreferencesMapAccessFilterTest {

    @Test
    fun `adding private from safe default keeps public selected`() = runTest {
        val preferences = createTestUserPreferences(backgroundScope)
        assertEquals(emptySet<String>(), preferences.mapFilterAccessTypes.first())

        preferences.toggleMapFilterAccessType("PRIVATE")

        assertEquals(
            setOf("PUBLIC", "PRIVATE"),
            preferences.mapFilterAccessTypes.first(),
        )
    }

    @Test
    fun `removing last explicit non-public type canonicalises to safe default`() = runTest {
        val preferences = createTestUserPreferences(backgroundScope)
        preferences.toggleMapFilterAccessType("PRIVATE")

        preferences.toggleMapFilterAccessType("PRIVATE")

        assertEquals(emptySet<String>(), preferences.mapFilterAccessTypes.first())
    }
}
