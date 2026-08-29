package com.cruxcoach.android.ui.moonboard

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.moonboard.MoonBoardCsvImportResult
import com.cruxcoach.android.moonboard.MoonBoardCsvImporter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject

@HiltViewModel
class MoonBoardCsvImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importer: MoonBoardCsvImporter,
) : ViewModel() {
    data class State(val importing: Boolean = false, val result: MoonBoardCsvImportResult? = null)
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    fun import(uri: Uri) {
        if (_state.value.importing) return
        _state.update { State(importing = true) }
        viewModelScope.launch {
            val result = runCatching {
                val csv = withContext(Dispatchers.IO) { readBounded(uri) }
                importer.import(csv)
            }.getOrElse { MoonBoardCsvImportResult(error = it.message ?: it.javaClass.simpleName) }
            _state.update { State(result = result) }
        }
    }

    fun reset() { _state.value = State() }

    private fun readBounded(uri: Uri): String {
        val declared = context.contentResolver.query(
            uri, arrayOf(OpenableColumns.SIZE), null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) c.getColumnIndex(OpenableColumns.SIZE)
                .takeIf { it >= 0 && !c.isNull(it) }?.let(c::getLong) else null
        }
        require(declared == null || declared <= MAX_BYTES) { "MoonBoard CSV is larger than 16 MB" }
        val input = context.contentResolver.openInputStream(uri) ?: error("Could not open selected file")
        val output = ByteArrayOutputStream()
        input.use {
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_BYTES) { "MoonBoard CSV is larger than 16 MB" }
                output.write(buffer, 0, count)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private companion object { const val MAX_BYTES = 16L * 1024 * 1024 }
}
