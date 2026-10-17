package com.cruxcoach.data.repository

import app.cash.sqldelight.coroutines.asFlow
import com.cruxcoach.db.secure.SecureDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Emits once at the start and whenever board sessions, sends or attempts since [fromDay] change, so the
 * coach re-reads a day's climbing load (a session just finished shows at once on Today and in nutrition).
 */
fun trainingDayChanges(db: SecureDatabase, fromDay: String): Flow<Unit> {
    val q = db.trainingDaysQueries
    return combine(
        q.boardSessionMinutesSince(fromDay).asFlow(),
        q.ascentCountsSince(fromDay).asFlow(),
        q.bidCountsSince(fromDay).asFlow(),
    ) { _, _, _ -> }
}
