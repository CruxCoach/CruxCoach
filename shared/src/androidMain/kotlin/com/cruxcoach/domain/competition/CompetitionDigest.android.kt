package com.cruxcoach.domain.competition

import java.security.MessageDigest

actual object CompetitionDigest {
    actual fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.encodeToByteArray())
        return bytes.joinToString("") { byte ->
            val value = byte.toInt() and 0xff
            val hex = value.toString(16)
            if (hex.length == 1) "0$hex" else hex
        }
    }
}
