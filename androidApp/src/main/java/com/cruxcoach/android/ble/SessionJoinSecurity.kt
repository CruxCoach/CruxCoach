package com.cruxcoach.android.ble

import java.security.MessageDigest
import java.security.SecureRandom

/** Application-layer authorization for the otherwise unbonded session GATT
 * channel. The short code is shown by the host and entered out-of-band by a
 * participant; it is never advertised or persisted. */
internal object SessionJoinCode {
    private val secureRandom = SecureRandom()
    private val format = Regex("[0-9]{6}")

    fun generate(nextInt: (Int) -> Int = secureRandom::nextInt): String {
        val value = nextInt(CODE_SPACE)
        require(value in 0 until CODE_SPACE)
        return value.toString().padStart(CODE_LENGTH, '0')
    }

    fun isValid(code: String): Boolean = code.matches(format)

    fun matches(expected: String, supplied: String): Boolean {
        if (!isValid(expected) || !isValid(supplied)) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.US_ASCII),
            supplied.toByteArray(Charsets.US_ASCII),
        )
    }

    const val CODE_LENGTH = 6
    private const val CODE_SPACE = 1_000_000
}

/** Tracks the GATT connections that completed a valid join for this process-
 * local host session. Addresses are removed on disconnect and the complete set
 * is cleared whenever hosting starts or stops. */
internal class SessionCommandGate {
    private val admittedDevices = mutableSetOf<String>()

    @Synchronized
    fun admit(deviceAddress: String, expectedCode: String, suppliedCode: String): Boolean {
        if (!SessionJoinCode.matches(expectedCode, suppliedCode)) return false
        admittedDevices += deviceAddress
        return true
    }

    @Synchronized
    fun isAdmitted(deviceAddress: String): Boolean = deviceAddress in admittedDevices

    @Synchronized
    fun remove(deviceAddress: String) {
        admittedDevices.remove(deviceAddress)
    }

    @Synchronized
    fun clear() {
        admittedDevices.clear()
    }
}
