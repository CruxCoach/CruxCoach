package com.cruxcoach.android.ui.bodystat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.data.repository.BodyStatRepository
import com.cruxcoach.domain.model.BodyStat
import com.cruxcoach.domain.model.StatCategory
import com.cruxcoach.domain.model.StatRegistry
import com.cruxcoach.util.DateTimeUtil
import android.content.Context
import com.cruxcoach.android.R
import com.cruxcoach.android.util.toUserDoubleOrNull
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

data class BodyStatState(
    val inputs: Map<String, String> = emptyMap(),
    val lastWeight: BodyStat? = null,
    val recentEntries: List<DayStats> = emptyList(),
    val expandedCategories: Set<StatCategory> = setOf(StatCategory.BODY_COMPOSITION),
    val isSaving: Boolean = false,
    val savedMessage: String? = null,
    val error: String? = null
)

data class DayStats(
    val date: String,
    val stats: Map<String, Double>
)

@HiltViewModel
class BodyStatViewModel @Inject constructor(
    private val bodyStatRepository: BodyStatRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(BodyStatState())
    val state: StateFlow<BodyStatState> = _state.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val lastWeight = bodyStatRepository.getLatestByStatName("weight")
                val dates = bodyStatRepository.getAllDates().take(14)
                val recentEntries = dates.map { date ->
                    val stats = bodyStatRepository.getByDate(date)
                    DayStats(
                        date = date,
                        stats = stats.associate { it.statName to it.value }
                    )
                }

                _state.update { it.copy(
                    lastWeight = lastWeight,
                    recentEntries = recentEntries
                ) }
            }
        }
    }

    fun updateInput(key: String, value: String) {
        _state.update { it.copy(inputs = it.inputs + (key to value)) }
    }

    fun save() {
        val currentInputs = _state.value.inputs
        _state.update { it.copy(isSaving = true, error = null) }

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val today = DateTimeUtil.todayIso()
                    var savedCount = 0

                    for (def in StatRegistry.ALL) {
                        val input = currentInputs[def.key] ?: continue
                        val value = input.toUserDoubleOrNull()
                        if (value != null && value > 0) {
                            bodyStatRepository.upsert(
                                BodyStat(
                                    date = today,
                                    statName = def.key,
                                    value = value,
                                    unit = def.unit
                                )
                            )
                            savedCount++
                        }
                    }

                    if (savedCount == 0) {
                        _state.update { it.copy(
                            isSaving = false,
                            error = context.getString(R.string.bodystat_error_enter_value)
                        ) }
                        return@withContext
                    }

                    _state.update { it.copy(
                        isSaving = false,
                        inputs = emptyMap(),
                        savedMessage = context.resources.getQuantityString(
                            R.plurals.bodystat_values_saved,
                            savedCount,
                            savedCount,
                        )
                    ) }
                }
                loadData()
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, error = e.message) }
            }
        }
    }

    fun deleteEntry(date: String, statName: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                bodyStatRepository.deleteByDateAndStatName(date, statName)
            }
            loadData()
        }
    }

    fun toggleCategory(category: StatCategory) {
        _state.update { current ->
            val updated = if (category in current.expandedCategories) {
                current.expandedCategories - category
            } else {
                current.expandedCategories + category
            }
            current.copy(expandedCategories = updated)
        }
    }

    fun saveSingleStat(key: String) {
        val input = _state.value.inputs[key] ?: return
        val parsed = input.toUserDoubleOrNull()
        if (parsed == null || parsed <= 0) {
            _state.update { it.copy(error = context.getString(R.string.bodystat_error_invalid_value)) }
            return
        }

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    bodyStatRepository.upsert(
                        BodyStat(
                            date = DateTimeUtil.todayIso(),
                            statName = key,
                            value = parsed,
                            unit = StatRegistry.unit(key)
                        )
                    )
                }
                val label = context.localizedStatLabel(key)
                _state.update { it.copy(
                    inputs = it.inputs - key,
                    savedMessage = context.getString(R.string.bodystat_stat_saved, label)
                ) }
                loadData()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: context.getString(R.string.bodystat_error_save_failed)) }
            }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(savedMessage = null) }
    }
}
