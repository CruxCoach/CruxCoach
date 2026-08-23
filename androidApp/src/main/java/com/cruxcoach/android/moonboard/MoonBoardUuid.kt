package com.cruxcoach.android.moonboard

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Identity scheme used by the bundled MoonBoard catalogue builder. */
object MoonBoardUuid {
    private val DNS_NAMESPACE = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")

    fun candidates(problemId: Long): List<Candidate> = listOf(
        Candidate(v5("moonboard:$problemId"), null),
        Candidate(v5("moonboard:$problemId:40"), 40),
        Candidate(v5("moonboard:$problemId:25"), 25),
    )

    data class Candidate(val uuid: String, val encodedAngle: Int?)

    internal fun v5(name: String): String {
        val ns = ByteBuffer.allocate(16)
            .putLong(DNS_NAMESPACE.mostSignificantBits)
            .putLong(DNS_NAMESPACE.leastSignificantBits)
            .array()
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(ns)
        val bytes = digest.digest(name.toByteArray(Charsets.UTF_8)).copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long).toString()
    }
}
