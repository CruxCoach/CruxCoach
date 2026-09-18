package com.cruxcoach.domain.community

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH

actual object FramesHash {
    actual fun of(frames: String, layoutId: Long): String {
        val input = framesHashInput(frames, layoutId).encodeToByteArray()
        return sha256(input).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun sha256(input: ByteArray): ByteArray {
    val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
    digest.usePinned { out ->
        if (input.isEmpty()) {
            CC_SHA256(null, 0u, out.addressOf(0))
        } else {
            input.usePinned { data -> CC_SHA256(data.addressOf(0), input.size.convert(), out.addressOf(0)) }
        }
    }
    return digest.asByteArray()
}
