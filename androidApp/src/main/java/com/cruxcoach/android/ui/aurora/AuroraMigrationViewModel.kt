package com.cruxcoach.android.ui.aurora

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.aurora.AuroraImportProgress
import com.cruxcoach.android.aurora.AuroraImportResult
import com.cruxcoach.android.aurora.AuroraImporter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * State + orchestration for the Aurora-JSON-import migration screen
 * (FEAT-005 §6.4 `MigrationFlowContent`). Reads the picked Uri's
 * content via [Context.contentResolver], hands the JSON to
 * [AuroraImporter], and exposes a single [State] StateFlow that the
 * Compose layer reads.
 *
 * Picker → upload is one-shot; tapping "import another" calls
 * [reset] to clear the previous result before the next file picker
 * launch.
 */
@HiltViewModel
class AuroraMigrationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importer: AuroraImporter,
) : ViewModel() {

    data class State(
        /** True from the moment the user picks a file until the result
         *  is computed. UI shows a progress overlay and disables the
         *  picker button while this is set. */
        val isImporting: Boolean = false,
        /** Last streaming progress event from the importer pipeline,
         *  or null when idle. Drives the inline progress card. */
        val progress: AuroraImportProgress? = null,
        /** Final outcome of the most recent import run, or null when
         *  the user hasn't yet imported anything in this session.
         *  Drives the result-summary card. */
        val result: AuroraImportResult? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Read the JSON text at [uri] and feed it through [AuroraImporter].
     * Errors at the read stage map to a parse-error result so the UI
     * surfaces them through the same banner.
     */
    fun importFromUri(uri: Uri) {
        if (_state.value.isImporting) return  // double-tap guard
        _state.update {
            it.copy(isImporting = true, progress = null, result = null)
        }
        viewModelScope.launch {
            val outcome = try {
                val json = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader().readText()
                    } ?: throw java.io.IOException("Could not open $uri")
                }
                importer.import(json) { p ->
                    _state.update { it.copy(progress = p) }
                }
            } catch (e: Exception) {
                AuroraImportResult.parseError(e.message ?: e.javaClass.simpleName)
            }
            _state.update {
                it.copy(isImporting = false, progress = null, result = outcome)
            }
        }
    }

    fun reset() {
        _state.value = State()
    }
}
