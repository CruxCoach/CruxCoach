package com.cruxcoach.domain.community

import java.security.MessageDigest

actual object FramesHash {
    actual fun of(frames: String, layoutId: Long): String {
        val input = framesHashInput(frames, layoutId)
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
