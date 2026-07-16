package com.cruxcoach.android.ui.plan

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.data.repository.PlanRepository
import com.cruxcoach.domain.model.PlannedSession
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

data class SessionDetailUiState(
    val isLoading: Boolean = true,
    val session: PlannedSession? = null,
    val error: String? = null
)

@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val planRepository: PlanRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val sessionId: Long = savedStateHandle.get<String>("sessionId")?.toLongOrNull() ?: 0

    private val _state = MutableStateFlow(SessionDetailUiState())
    val state: StateFlow<SessionDetailUiState> = _state.asStateFlow()

    init {
        loadSession()
    }

    fun clearError() { _state.update { it.copy(error = null) } }

    private fun loadSession() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Find the session by searching all plans
                    // The sessionId corresponds to the DB row id
                    // We need to find the plan that contains this session
                    val session = findSessionById(sessionId)
                    _state.update { it.copy(
                        isLoading = false,
                        session = session,
                        error = if (session == null) context.getString(R.string.error_session_not_found) else null
                    ) }
                }
            } catch (e: Exception) {
                android.util.Log.w("SessionDetailViewModel", "loadSession failed (${e.javaClass.simpleName})")
                _state.update { it.copy(
                    isLoading = false,
                    error = e.message
                ) }
            }
        }
    }

    private fun findSessionById(id: Long): PlannedSession? {
        // Since we don't have a direct getSessionById, we rely on the session ID
        // being passed from the plan's session list. We search through plans.
        // For MVP, iterate through all user plans to find the session.
        val allPlans = planRepository.getAllPlans(1) // userId=1 for MVP
        for (plan in allPlans) {
            val sessions = planRepository.getSessionsForPlan(plan.id)
            val found = sessions.find { it.id == id }
            if (found != null) return found
        }
        return null
    }
}
