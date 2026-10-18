package com.cruxcoach.app.nutrition

import platform.Foundation.NSBundle
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.decimalSeparator

/** What the nutrition texts need from the device's language and region. */
internal object IosNutritionLocale {
    /** The app shows German (its own localization, not only the region): German names and products first. */
    fun german(): Boolean = (NSBundle.mainBundle.preferredLocalizations.firstOrNull() as? String) == "de"

    /** Numbers in typed fields use the region's decimal separator, as Android's `formatNumber` does. */
    fun decimalComma(): Boolean = NSLocale.currentLocale.decimalSeparator == ","
}
