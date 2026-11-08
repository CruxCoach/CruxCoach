package com.cruxcoach.android.ui.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.data.shinywall.ShinyWallAccountClient
import com.cruxcoach.android.data.shinywall.ShinyWallAuthResult
import com.cruxcoach.android.data.shinywall.ShinyWallImportPreview
import com.cruxcoach.android.data.shinywall.ShinyWallListNames
import com.cruxcoach.android.data.shinywall.ShinyWallSyncEngine
import com.cruxcoach.android.data.shinywall.ShinyWallTokenStorage
import com.cruxcoach.android.data.shinywall.formatShinyWallImportSummary
import com.cruxcoach.android.data.shinywall.isShinyWallSessionExpired
import com.cruxcoach.android.data.shinywall.localizeShinyWallImportError
import com.cruxcoach.android.data.shinywall.localized
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ShinyWallAccountState(
    val isConnected: Boolean = false,
    val displayName: String = "",
    val lastImportAtMs: Long? = null,
    val showLoginSheet: Boolean = false,
    val loginEmail: String = "",
    val loginPassword: String = "",
    val isLoggingIn: Boolean = false,
    val loginError: String? = null,
    val preview: ShinyWallImportPreview? = null,
    val isImporting: Boolean = false,
    val resultMessage: String? = null,
    val resultIsError: Boolean = false,
    val showDisconnectConfirm: Boolean = false,
    /** Shiny Wall no longer accepts the stored sign-in; the card offers to sign in again. */
    val sessionExpired: Boolean = false,
)

/**
 * Backs [ShinyWallAccountSection]: sign-in, preview and the read-only import
 * of the user's own Shiny Wall logbook. Kept apart from SettingsViewModel so
 * the Shiny Wall account has no ties to the Kilter state.
 */
@HiltViewModel
class ShinyWallAccountViewModel @Inject constructor(
    application: Application,
    private val tokenStore: ShinyWallTokenStorage,
    private val client: ShinyWallAccountClient,
    private val engine: ShinyWallSyncEngine,
) : AndroidViewModel(application) {

    private companion object {
        const val TAG = "ShinyWallAccountVM"
    }

    private val context get() = getApplication<Application>()

    private val _state = MutableStateFlow(connectedState(ShinyWallAccountState()))
    val state: StateFlow<ShinyWallAccountState> = _state.asStateFlow()

    private fun connectedState(base: ShinyWallAccountState): ShinyWallAccountState {
        val session = runCatching { tokenStore.session() }.getOrNull()
        return base.copy(
            isConnected = session != null,
            displayName = session?.displayName.orEmpty(),
            lastImportAtMs = runCatching { tokenStore.lastImportAtMs() }.getOrNull(),
        )
    }

    fun showLogin() = _state.update { it.copy(showLoginSheet = true, loginError = null) }

    fun dismissLogin() = _state.update {
        if (it.isLoggingIn) it else it.copy(showLoginSheet = false, loginPassword = "", loginError = null)
    }

    fun updateEmail(value: String) = _state.update { it.copy(loginEmail = value) }
    fun updatePassword(value: String) = _state.update { it.copy(loginPassword = value) }

    fun login() {
        val current = _state.value
        if (current.isLoggingIn || current.loginEmail.isBlank() || current.loginPassword.isBlank()) return
        _state.update { it.copy(isLoggingIn = true, loginError = null) }
        viewModelScope.launch {
            when (val result = client.signIn(current.loginEmail, current.loginPassword)) {
                is ShinyWallAuthResult.Success -> {
                    tokenStore.storeSession(result.session)
                    _state.update {
                        connectedState(it).copy(
                            isLoggingIn = false, showLoginSheet = false, loginPassword = "", sessionExpired = false,
                        )
                    }
                    loadPreview()
                }
                is ShinyWallAuthResult.Error -> {
                    Log.w(TAG, "Sign-in failed: ${result.reason} ${result.cause ?: ""}")
                    _state.update { it.copy(isLoggingIn = false, loginError = result.localized(context)) }
                }
            }
        }
    }

    /** Counts what an import would bring, then shows the preview dialog. */
    fun loadPreview() {
        if (_state.value.isImporting) return
        _state.update { it.copy(isImporting = true, resultMessage = null) }
        viewModelScope.launch {
            engine.previewImport()
                .onSuccess { preview -> _state.update { it.copy(isImporting = false, preview = preview) } }
                .onFailure { error ->
                    Log.w(TAG, "Preview failed (${error.javaClass.simpleName})")
                    _state.update { it.failedWith(error) }
                }
        }
    }

    fun dismissPreview() = _state.update { if (it.isImporting) it else it.copy(preview = null) }

    fun importNow() {
        if (_state.value.isImporting) return
        _state.update { it.copy(isImporting = true, resultMessage = null) }
        viewModelScope.launch {
            val names = ShinyWallListNames(
                favorites = context.getString(R.string.shinywall_list_favorites),
                ownRoutes = context.getString(R.string.shinywall_list_own_routes),
            )
            engine.importAll(names)
                .onSuccess { result ->
                    _state.update {
                        connectedState(it).copy(
                            isImporting = false,
                            preview = null,
                            resultMessage = formatShinyWallImportSummary(context, result),
                            resultIsError = false,
                        )
                    }
                }
                .onFailure { error ->
                    Log.w(TAG, "Import failed (${error.javaClass.simpleName})")
                    _state.update { it.failedWith(error).copy(preview = null) }
                }
        }
    }

    /** An expired sign-in shows on the card with its action; any other failure as a message. */
    private fun ShinyWallAccountState.failedWith(error: Throwable): ShinyWallAccountState =
        if (isShinyWallSessionExpired(error)) copy(isImporting = false, sessionExpired = true)
        else copy(isImporting = false, resultMessage = localizeShinyWallImportError(context, error), resultIsError = true)

    fun showDisconnectConfirm() = _state.update { it.copy(showDisconnectConfirm = true) }
    fun dismissDisconnectConfirm() = _state.update { it.copy(showDisconnectConfirm = false) }

    /** Forgets the sign-in on this phone. Imported entries and lists stay. */
    fun disconnect() {
        runCatching { tokenStore.clearSession() }
        _state.value = ShinyWallAccountState()
    }

    fun dismissResult() = _state.update { it.copy(resultMessage = null) }
}
