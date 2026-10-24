package com.cruxcoach.android.ble

import android.content.Context
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Installation-local "cleaned" marks: per hashed controller one date, one
 * local time and LED positions. No climb IDs, logbook or account data; the
 * collection itself is read from the logbook each time. Other days' marks are
 * dropped on the next save. */
internal class BoardCleaningStorage(context: Context) {
    private val preferences = context.getSharedPreferences("board_cleaning", Context.MODE_PRIVATE)

    fun load(): Map<String, CleaningMark> {
        // 0.2.4 builds before the logbook source kept every sent climb's LEDs per day.
        if (preferences.contains(LEGACY_DAYS)) preferences.edit().remove(LEGACY_DAYS).apply()
        return runCatching {
            Json.decodeFromString<Map<String, CleaningMark>>(preferences.getString(MARKS, "{}") ?: "{}")
        }.getOrDefault(emptyMap())
    }

    fun save(marks: Map<String, CleaningMark>) {
        preferences.edit().putString(MARKS, Json.encodeToString(marks)).apply()
    }

    companion object {
        private const val MARKS = "marks"
        private const val LEGACY_DAYS = "days"

        fun key(board: DiscoveredBoard): String? {
            // A relay address names a phone, which may switch physical boards.
            if (board.isCruxRelay) return null
            if (!board.boardBrand.usesAuroraProtocol && board.boardBrand != com.cruxcoach.domain.board.BoardBrand.MOONBOARD) return null
            val identity = "${board.boardBrand.wireValue}|${board.address.uppercase()}|${board.apiLevel}"
            return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }
}
