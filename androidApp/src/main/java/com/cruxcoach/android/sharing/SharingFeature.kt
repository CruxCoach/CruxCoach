package com.cruxcoach.android.sharing

import com.cruxcoach.android.BuildConfig
import com.cruxcoach.android.data.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Personal-data sharing is unfinished. It can be turned on only in debug builds
 * (the feature and test APKs) and starts switched off; a release build never
 * shows it, never schedules work for it and never opens the native host.
 *
 * Turning it off stops the automation and takes the native host offline; data
 * already stored stays where it is, so turning it on again continues.
 */
@Singleton
class SharingFeature @Inject constructor(private val preferences: UserPreferences) {
    val available: Boolean get() = BuildConfig.DEBUG

    val enabled: Flow<Boolean> = preferences.sharingPreviewEnabled.map { available && it }

    suspend fun isEnabled(): Boolean = enabled.first()

    suspend fun setEnabled(enabled: Boolean) {
        if (available) preferences.setSharingPreviewEnabled(enabled)
    }
}
