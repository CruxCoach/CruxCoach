package com.cruxcoach.android.ui.aurora

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
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
import java.io.ByteArrayOutputStream
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

    companion object {
        // Aurora exports are typically a few MB. Cap at 32 MB to bound
        // memory pressure on file read — the parser then materialises
        // the full string into JSON DOM, so an unbounded read enables
        // OOM via a single hostile file (and OOM is `Error`, not
        // `Exception`, so it escapes the catch below and crashes the
        // viewModelScope).
        const val MAX_AURORA_JSON_BYTES: Long = 32L * 1024 * 1024
    }

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
                val json = withContext(Dispatchers.IO) { readBoundedJson(uri) }
                importer.import(json) { p ->
                    _state.update { it.copy(progress = p) }
                }
            } catch (e: FileTooLargeException) {
                val capMb = (MAX_AURORA_JSON_BYTES / (1024 * 1024)).toInt()
                AuroraImportResult.parseError(
                    context.getString(R.string.aurora_migration_file_too_large, capMb)
                )
            } catch (e: Exception) {
                AuroraImportResult.parseError(e.message ?: e.javaClass.simpleName)
            }
            _state.update {
                it.copy(isImporting = false, progress = null, result = outcome)
            }
        }
    }

    /**
     * Read the SAF Uri into a String, capped at [MAX_AURORA_JSON_BYTES].
     *
     * Two-step defence: a SAF `OpenableColumns.SIZE` cursor short-circuits
     * the obvious case before any bytes are read; the byte-counted stream
     * loop catches providers that don't report SIZE (some cloud providers
     * return -1) and is the actual hard cap.
     */
    private fun readBoundedJson(uri: Uri): String {
        val declaredSize = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && !cursor.isNull(idx)) {
                    cursor.getLong(idx).takeIf { it >= 0 }
                } else null
            } else null
        }
        if (declaredSize != null && declaredSize > MAX_AURORA_JSON_BYTES) {
            throw FileTooLargeException(declaredSize)
        }
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw java.io.IOException("Could not open $uri")
        val bytes = stream.use { input ->
            val buf = ByteArray(64 * 1024)
            val out = ByteArrayOutputStream()
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_AURORA_JSON_BYTES) {
                    throw FileTooLargeException(total)
                }
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
        return String(bytes, Charsets.UTF_8)
    }

    private class FileTooLargeException(val bytesObserved: Long) : Exception()

    fun reset() {
        _state.update { State() }
    }
}
