package com.cruxcoach.android.foodvision

import android.content.Context
import com.cruxcoach.athlete.logic.UsdaNames
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The bundled BLS 4.0 extract (Max Rubner-Institut, CC BY 4.0): 7,140
 * generic German foods with energy, macros, iron, calcium and vitamin D per
 * 100 g, offline.
 */
@Singleton
class BlsRepository @Inject constructor(@ApplicationContext context: Context) : FoodTableRepository(context, ASSET) {
    companion object {
        const val ASSET = "fuel/bls_4_0_macros.tsv"
        /** food_item.source for BLS foods; their id is "bls:" + code. */
        const val SOURCE = "bls"
        const val ATTRIBUTION = "Max Rubner-Institut (2025): Bundeslebensmittelschlüssel (BLS), Version 4.0 — " +
            "Deutsche Nährstoffdatenbank. Karlsruhe. DOI: 10.25826/Data20251217-134202-0 (CC BY 4.0)"
    }
}

/**
 * The bundled USDA FoodData Central SR Legacy extract (public domain, CC0):
 * 7,793 generic US foods with English names, the same nutrients as BLS and
 * household measures (cup, tbsp, slice) with their weight, offline.
 */
@Singleton
class UsdaRepository @Inject constructor(@ApplicationContext context: Context) :
    FoodTableRepository(context, ASSET, UsdaNames::indexed) {
    companion object {
        const val ASSET = "fuel/usda_sr_legacy.tsv"
        /** food_item.source for USDA foods; their id is "usda:" + FDC id. */
        const val SOURCE = "usda"
        const val ATTRIBUTION = "U.S. Department of Agriculture, Agricultural Research Service. FoodData Central: " +
            "SR Legacy (2018). https://fdc.nal.usda.gov/ (public domain, CC0 1.0)"
    }
}
