package com.cruxcoach.android.foodvision

import android.content.Context
import com.cruxcoach.athlete.logic.BlsFood
import com.cruxcoach.athlete.logic.BlsTable
import com.cruxcoach.athlete.logic.FoodMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A bundled food table (BLS or USDA), read into memory once on first use. */
abstract class FoodTableRepository(
    private val context: Context,
    private val asset: String,
    /** Adjusts rows before indexing, e.g. USDA core names. */
    private val prepare: (BlsFood) -> BlsFood = { it },
) {
    private class Loaded(val matcher: FoodMatcher, val byCode: Map<String, BlsFood>)

    private val mutex = Mutex()
    private var loaded: Loaded? = null

    private suspend fun load(): Loaded = mutex.withLock {
        loaded ?: withContext(Dispatchers.IO) {
            val foods = BlsTable.parse(context.assets.open(asset).bufferedReader().use { it.readText() }).map(prepare)
            Loaded(FoodMatcher(foods), foods.associateBy { it.code })
        }.also { loaded = it }
    }

    suspend fun matcher(): FoodMatcher = load().matcher

    /** One food by its code, e.g. for the micronutrients of a logged entry. */
    suspend fun food(code: String): BlsFood? = load().byCode[code]
}
