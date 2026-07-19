package com.cruxcoach.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.BuildConfig
import com.cruxcoach.android.updater.ZapstoreReleaseClient
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class AppShareViewModel @Inject constructor(
    private val zapstoreReleaseClient: ZapstoreReleaseClient,
) : ViewModel() {
    private val _zapstoreApk = MutableStateFlow<ZapstoreApkState>(ZapstoreApkState.Loading)
    val zapstoreApk: StateFlow<ZapstoreApkState> = _zapstoreApk.asStateFlow()

    init {
        loadZapstoreApk()
    }

    fun refreshZapstoreApk() {
        if (_zapstoreApk.value == ZapstoreApkState.Loading) return
        loadZapstoreApk()
    }

    private fun loadZapstoreApk() {
        _zapstoreApk.value = ZapstoreApkState.Loading
        viewModelScope.launch {
            _zapstoreApk.value = when (val result = zapstoreReleaseClient.fetchReleases()) {
                is ZapstoreReleaseClient.Result.Success -> {
                    result.releases.firstOrNull {
                        it.versionName == BuildConfig.VERSION_NAME &&
                            it.versionCode == BuildConfig.VERSION_CODE
                    }?.let { ZapstoreApkState.Ready(it.apkUrl) }
                        ?: ZapstoreApkState.Unavailable
                }
                is ZapstoreReleaseClient.Result.Error -> ZapstoreApkState.Unavailable
            }
        }
    }

    sealed interface ZapstoreApkState {
        data object Loading : ZapstoreApkState
        data class Ready(val url: String) : ZapstoreApkState
        data object Unavailable : ZapstoreApkState
    }
}
