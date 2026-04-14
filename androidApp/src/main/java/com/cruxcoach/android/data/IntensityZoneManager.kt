package com.cruxcoach.android.data

import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.domain.board.IntensityZoneEngine
import com.cruxcoach.domain.board.IntensityZones
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.time.LocalDate

class IntensityZoneManager(private val personalBoardRepo: PersonalBoardRepository) {

    private val _zones = MutableStateFlow(IntensityZoneEngine.computeFallbackZones(null))
    val zones: StateFlow<IntensityZones> = _zones.asStateFlow()

    suspend fun recompute() {
        withContext(Dispatchers.IO) {
            val cutoff = LocalDate.now().minusDays(90).toString()
            val sendDiffs = personalBoardRepo.getUserSendDifficulties(cutoff)
            val bidDiffs = personalBoardRepo.getUserBidDifficulties(cutoff)
            val allDiffs = sendDiffs + bidDiffs
            _zones.value = IntensityZoneEngine.computeZones(allDiffs)
        }
    }
}
