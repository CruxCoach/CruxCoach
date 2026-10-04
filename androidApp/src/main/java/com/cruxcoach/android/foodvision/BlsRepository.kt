package com.cruxcoach.android.foodvision

import android.content.Context
import com.cruxcoach.athlete.logic.BlsTable
import com.cruxcoach.athlete.logic.FoodMatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The bundled BLS 4.0 extract (Max Rubner-Institut, CC BY 4.0): 7,140
 * generic German foods with energy and macros per 100 g, offline.
 */
@Singleton
class BlsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val mutex = Mutex()
    private var matcher: FoodMatcher? = null

    suspend fun matcher(): FoodMatcher = mutex.withLock {
        matcher ?: withContext(Dispatchers.IO) {
            val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            FoodMatcher(BlsTable.parse(text))
        }.also { matcher = it }
    }

    companion object {
        const val ASSET = "fuel/bls_4_0_macros.tsv"
        /** food_item.source for BLS foods; their id is "bls:" + code. */
        const val SOURCE = "bls"
        const val ATTRIBUTION = "Max Rubner-Institut (2025): Bundeslebensmittelschlüssel (BLS), Version 4.0 — " +
            "Deutsche Nährstoffdatenbank. Karlsruhe. DOI: 10.25826/Data20251217-134202-0 (CC BY 4.0)"
    }
}
