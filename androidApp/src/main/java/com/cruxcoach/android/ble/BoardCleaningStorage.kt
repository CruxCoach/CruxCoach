package com.cruxcoach.android.ble

import android.content.Context
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Small installation-local day cache; no climb IDs, logbook or account data.
 * Controller addresses are hashed and old days are discarded on the next send. */
internal class BoardCleaningStorage(context: Context) {
    private val preferences = context.getSharedPreferences("board_cleaning", Context.MODE_PRIVATE)

    fun load(): Map<String, CleaningDay> = runCatching {
        Json.decodeFromString<Map<String, CleaningDay>>(preferences.getString("days", "{}") ?: "{}")
    }.getOrDefault(emptyMap())

    fun save(days: Map<String, CleaningDay>) {
        preferences.edit().putString("days", Json.encodeToString(days)).apply()
    }

    companion object {
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
