package com.cruxcoach.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.data.repository.UserRepository
import com.cruxcoach.domain.model.Assessment
import com.cruxcoach.util.DateTimeUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class AssessmentState(
    val maxHang20mm: String = "",
    val weightedPullup: String = "",
    val pullupMaxReps: String = "",
    val pushupMaxReps: String = "",
    val coreHoldSec: String = "",
    val isSaving: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class AssessmentViewModel @Inject constructor(
    private val userRepository: UserRepository
) : ViewModel() {

    private val _state = MutableStateFlow(AssessmentState())
    val state: StateFlow<AssessmentState> = _state.asStateFlow()

    init {
        loadExisting()
    }

    private fun loadExisting() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val profile = userRepository.getActiveProfile() ?: return@withContext
                val existing = userRepository.getLatestAssessment(profile.id) ?: return@withContext
                _state.update { it.copy(
                    maxHang20mm = existing.maxHang20mmKg?.toString() ?: "",
                    weightedPullup = existing.weightedPullupKg?.toString() ?: "",
                    pullupMaxReps = existing.pullupMaxReps?.toString() ?: "",
                    pushupMaxReps = existing.pushUpMaxReps?.toString() ?: "",
                    coreHoldSec = existing.coreHoldSec?.toString() ?: ""
                ) }
            }
        }
    }

    fun updateMaxHang(v: String) { _state.update { it.copy(maxHang20mm = v) } }
    fun updateWeightedPullup(v: String) { _state.update { it.copy(weightedPullup = v) } }
    fun updatePullupReps(v: String) { _state.update { it.copy(pullupMaxReps = v.filter { c -> c.isDigit() }) } }
    fun updatePushupReps(v: String) { _state.update { it.copy(pushupMaxReps = v.filter { c -> c.isDigit() }) } }
    fun updateCoreHold(v: String) { _state.update { it.copy(coreHoldSec = v.filter { c -> c.isDigit() }) } }

    fun saveAssessment(onComplete: () -> Unit) {
        val s = _state.value
        _state.update { it.copy(isSaving = true, error = null) }

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val profile = userRepository.getActiveProfile()
                        ?: throw IllegalStateException("No active profile found")

                    val hangKg = s.maxHang20mm.toDoubleOrNull()
                    val assessment = Assessment(
                        userId = profile.id,
                        date = DateTimeUtil.nowIso(),
                        maxHang20mmKg = hangKg,
                        maxHangPctBw = if (hangKg != null && profile.weightKg > 0)
                            (hangKg / profile.weightKg) * 100.0 else null,
                        weightedPullupKg = s.weightedPullup.toDoubleOrNull(),
                        pullupMaxReps = s.pullupMaxReps.toIntOrNull(),
                        pushUpMaxReps = s.pushupMaxReps.toIntOrNull(),
                        coreHoldSec = s.coreHoldSec.toIntOrNull()
                    )
                    userRepository.insertAssessment(assessment)
                }
                _state.update { it.copy(isSaving = false) }
                onComplete()
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, error = e.message) }
            }
        }
    }
}
