package com.cruxcoach.android.foodvision

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Newer product data is looked for only on unmetered networks and at most every few days. */
class FoodProductsSyncGateTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 100 * day

    @Test
    fun `mobile data never pulls product data along with a board sync`() {
        assertFalse(FoodProductsSync.shouldCheck(now, lastCheckMs = 0L, metered = true))
    }

    @Test
    fun `the first look and one after the interval go ahead, one in between waits`() {
        assertTrue(FoodProductsSync.shouldCheck(now, lastCheckMs = 0L, metered = false))
        assertFalse(FoodProductsSync.shouldCheck(now, lastCheckMs = now - day, metered = false))
        assertTrue(FoodProductsSync.shouldCheck(now, lastCheckMs = now - FoodProductsSync.CHECK_INTERVAL_MS, metered = false))
    }

    @Test
    fun `a clock set back does not block the look for good`() {
        assertTrue(FoodProductsSync.shouldCheck(now, lastCheckMs = now + 30 * day, metered = false))
    }
}
