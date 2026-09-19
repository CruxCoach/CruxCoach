package com.cruxcoach.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.Foundation.NSFileHandle
import platform.Foundation.fileHandleForReadingAtPath
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.BetaInteropApi

/** CommonCrypto / Security.framework implementation of [Hashing]. */
@OptIn(ExperimentalForeignApi::class)
class IosHashing : Hashing {

    override fun sha256(data: ByteArray): ByteArray {
        val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
        digest.usePinned { out ->
            val md = out.addressOf(0).reinterpret<UByteVar>()
            if (data.isEmpty()) {
                // A null pointer with length 0 is valid; pinning an empty array is not.
                CC_SHA256(null, 0u, md)
            } else {
                // CC_LONG is 32 bit, and a ByteArray never exceeds Int.MAX_VALUE.
                data.usePinned { input -> CC_SHA256(input.addressOf(0), data.size.toUInt(), md) }
            }
        }
        return digest
    }

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = ByteArray(CC_SHA256_DIGEST_LENGTH)
        // One-element stand-ins give CCHmac a valid pointer while the length stays 0.
        val keyBuffer = if (key.isEmpty()) ByteArray(1) else key
        val dataBuffer = if (data.isEmpty()) ByteArray(1) else data
        mac.usePinned { out ->
            keyBuffer.usePinned { k ->
                dataBuffer.usePinned { d ->
                    CCHmac(
                        kCCHmacAlgSHA256,
                        k.addressOf(0), key.size.toULong(),
                        d.addressOf(0), data.size.toULong(),
                        out.addressOf(0),
                    )
                }
            }
        }
        return mac
    }

    override fun randomBytes(count: Int): ByteArray {
        require(count >= 0) { "count must not be negative" }
        if (count == 0) return ByteArray(0)
        val out = ByteArray(count)
        val status = out.usePinned { SecRandomCopyBytes(kSecRandomDefault, count.toULong(), it.addressOf(0)) }
        // Never fall back to a weaker generator: keys and nonces are derived from this.
        check(status == errSecSuccess) { "SecRandomCopyBytes failed: $status" }
        return out
    }

    @OptIn(BetaInteropApi::class)
    override fun sha256OfFile(path: String): ByteArray? {
        val handle = NSFileHandle.fileHandleForReadingAtPath(path) ?: return null
        try {
            return memScoped {
                val context = alloc<CC_SHA256_CTX>()
                CC_SHA256_Init(context.ptr)
                var failed = false
                while (!failed) {
                    // Each block is an autoreleased NSData; drain it per iteration so a
                    // multi-hundred-megabyte file never accumulates in memory.
                    val more: Boolean = autoreleasepool {
                        // The error-returning variant: readDataOfLength raises an Objective-C
                        // exception on I/O errors, which Kotlin/Native cannot catch.
                        val block = handle.readDataUpToLength(BLOCK_BYTES.toULong(), error = null)
                        when {
                            block == null -> { failed = true; false }
                            block.length == 0uL -> false
                            else -> {
                                CC_SHA256_Update(context.ptr, block.bytes, block.length.toUInt())
                                true
                            }
                        }
                    }
                    if (!more) break
                }
                if (failed) return@memScoped null
                val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
                digest.usePinned { CC_SHA256_Final(it.addressOf(0).reinterpret(), context.ptr) }
                digest
            }
        } finally {
            handle.closeAndReturnError(null)
        }
    }

    private companion object {
        const val BLOCK_BYTES = 64 * 1024
    }
}
