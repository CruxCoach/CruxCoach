package com.cruxcoach.android.util

import java.net.URI

/** Validation policies immediately before remote-derived strings enter an
 * Android URI/intent dispatcher. The functions stay platform-free so their
 * exact boundary behaviour is covered by plain JVM tests. */
internal object ExternalInputPolicy {

    /** Accept only HTTPS URLs on the same host and effective port as the
     * configured updater API. This supports self-hosted forks without letting
     * an API response choose an arbitrary intent scheme or origin. */
    fun trustedReleasePageUrlOrNull(raw: String, updaterApiBase: String): String? {
        if (raw.isEmpty() || raw.length > MAX_RELEASE_URL_CHARS || raw != raw.trim()) return null
        if (raw.any { it.isISOControl() }) return null
        val candidate = runCatching { URI(raw) }.getOrNull() ?: return null
        val trusted = runCatching { URI(updaterApiBase) }.getOrNull() ?: return null
        if (!candidate.scheme.equals("https", ignoreCase = true)) return null
        if (!trusted.scheme.equals("https", ignoreCase = true)) return null
        if (candidate.userInfo != null || trusted.userInfo != null) return null
        val candidateHost = candidate.host ?: return null
        val trustedHost = trusted.host ?: return null
        if (!candidateHost.equals(trustedHost, ignoreCase = true)) return null
        if (effectiveHttpsPort(candidate) != effectiveHttpsPort(trusted)) return null
        return raw
    }

    /**
     * Validate the BOLT-11 envelope before passing it to a `lightning:` intent.
     * This checks Bech32 casing/alphabet/checksum, a supported Bitcoin-network
     * HRP, amount grammar, and a conservative size bound. Signature validation
     * remains the wallet's job.
     */
    fun validBolt11OrNull(raw: String): String? {
        if (raw.isEmpty() || raw.length > MAX_BOLT11_CHARS || raw != raw.trim()) return null
        if (raw.any { it.code !in 33..126 }) return null
        val hasLower = raw.any(Char::isLowerCase)
        val hasUpper = raw.any(Char::isUpperCase)
        if (hasLower && hasUpper) return null

        val invoice = raw.lowercase()
        val separator = invoice.lastIndexOf('1')
        if (separator <= 0) return null
        val hrp = invoice.substring(0, separator)
        val encodedData = invoice.substring(separator + 1)
        // 7 timestamp groups + 104 signature groups + 6 checksum groups.
        if (encodedData.length < MIN_BOLT11_DATA_CHARS) return null

        val networkPrefix = BOLT11_NETWORK_PREFIXES.firstOrNull(hrp::startsWith) ?: return null
        if (!validBolt11Amount(hrp.removePrefix(networkPrefix))) return null

        val values = IntArray(encodedData.length)
        encodedData.forEachIndexed { index, char ->
            val value = BECH32_REVERSE[char.code.takeIf { it < BECH32_REVERSE.size } ?: return null]
            if (value < 0) return null
            values[index] = value
        }
        if (bech32Polymod(hrpExpand(hrp), values) != BECH32_CHECKSUM_CONSTANT) return null
        return raw
    }

    private fun validBolt11Amount(amount: String): Boolean {
        if (amount.isEmpty()) return true
        val multiplier = amount.last().takeIf { it in "munp" }
        val digits = if (multiplier == null) amount else amount.dropLast(1)
        if (digits.isEmpty() || digits.first() == '0' || digits.any { !it.isDigit() }) return false
        if (multiplier == 'p' && digits.last() != '0') return false
        return true
    }

    private fun effectiveHttpsPort(uri: URI): Int = if (uri.port == -1) 443 else uri.port

    private fun hrpExpand(hrp: String): IntArray = IntArray(hrp.length * 2 + 1).also { out ->
        hrp.forEachIndexed { index, char -> out[index] = char.code ushr 5 }
        out[hrp.length] = 0
        hrp.forEachIndexed { index, char -> out[hrp.length + 1 + index] = char.code and 31 }
    }

    private fun bech32Polymod(hrpValues: IntArray, dataValues: IntArray): Int {
        var checksum = 1
        for (value in hrpValues + dataValues) {
            val top = checksum ushr 25
            checksum = (checksum and 0x1ffffff) shl 5 xor value
            for (index in BECH32_GENERATORS.indices) {
                if ((top ushr index) and 1 == 1) checksum = checksum xor BECH32_GENERATORS[index]
            }
        }
        return checksum
    }

    private const val MAX_RELEASE_URL_CHARS = 2_048
    private const val MAX_BOLT11_CHARS = 8_192
    private const val MIN_BOLT11_DATA_CHARS = 117
    private const val BECH32_CHECKSUM_CONSTANT = 1
    private val BOLT11_NETWORK_PREFIXES = listOf("lnbcrt", "lntbs", "lntb", "lnbc")
    private const val BECH32_ALPHABET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val BECH32_REVERSE = IntArray(128) { -1 }.also { reverse ->
        BECH32_ALPHABET.forEachIndexed { index, char -> reverse[char.code] = index }
    }
    private val BECH32_GENERATORS = intArrayOf(
        0x3b6a57b2,
        0x26508e6d,
        0x1ea119fa,
        0x3d4233dd,
        0x2a1462b3,
    )
}
