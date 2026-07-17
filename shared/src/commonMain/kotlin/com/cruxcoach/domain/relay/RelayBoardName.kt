package com.cruxcoach.domain.relay

/**
 * Derives CruxRelay's transparent advertised name from the REAL connected
 * board's name, so the official app resolves the right product + LED-kit
 * protocol while a human clearly sees this is a CruxRelay, not the board.
 *
 * The listing gate is the advertising service UUID (4488B571), NOT the name, so
 * any marker is safe for discovery. The name lives in the SCAN_RESPONSE (the
 * 128-bit UUID fills the 31-byte ADV_IND), so it must fit a tight byte budget.
 *
 * Pure (no Android) so the byte-budget trimming is unit-tested.
 */
object RelayBoardName {

    const val PREFIX = "CruxRelay·"
    /** Conservative UTF-8 byte budget for the scan-response local name. */
    const val MAX_NAME_BYTES = 29

    /**
     * @param realBoardName the board's advertised name as scanned, e.g.
     *   "Kilter Board Original#A1B2@3" or "Kilter Board@3".
     * @return e.g. "CruxRelay·Kilter Board Original@3", trimmed to fit
     *   [MAX_NAME_BYTES], product shortened before the "@<api>" suffix is lost.
     */
    fun transparent(realBoardName: String, maxBytes: Int = MAX_NAME_BYTES): String {
        val product = productOf(realBoardName)
        val apiSuffix = apiSuffixOf(realBoardName) // "@3" or ""
        val full = PREFIX + product + apiSuffix
        if (byteLen(full) <= maxBytes) return full

        // Trim the product (never the prefix or the @api suffix) to fit.
        val fixedBytes = byteLen(PREFIX) + byteLen(apiSuffix)
        val room = maxBytes - fixedBytes
        if (room <= 0) return (PREFIX.trimEnd('·') + apiSuffix) // degenerate: drop product
        val trimmedProduct = trimToBytes(product, room)
        return PREFIX + trimmedProduct + apiSuffix
    }

    /** Rebuilds the protocol-relevant product/API name after the scanner split it. */
    fun transparentBoard(
        productName: String,
        apiLevel: Int,
        maxBytes: Int = MAX_NAME_BYTES,
    ): String {
        val source = if (apiLevel > 0) "${productName.trim()}@$apiLevel" else productName
        return transparent(source, maxBytes)
    }

    /** Fallback: keep product + "@api" pristine, marker in the free-form serial
     *  ("Kilter Board#CR<serial>@3"). Use only if a device test shows the
     *  transparent form isn't listed / shows a confusing "Unknown Board". */
    fun serialMarked(realBoardName: String, maxBytes: Int = MAX_NAME_BYTES): String {
        val product = productOf(realBoardName)
        val apiSuffix = apiSuffixOf(realBoardName)
        val candidate = "$product#CR$apiSuffix"
        return if (byteLen(candidate) <= maxBytes) candidate
        else trimToBytes(product, maxBytes - byteLen("#CR$apiSuffix")) + "#CR" + apiSuffix
    }

    /**
     * True if [name] is one of OUR relays, so CruxCoach shows it as a
     * session/playlist JOIN entry rather than a connectable board (FEAT-044 §11).
     * Matches the transparent primary form's "CruxRelay" prefix.
     */
    fun isRelayName(name: String): Boolean = name.trimStart().startsWith("CruxRelay")

    /** Product token: everything before the first '#' or '@'. */
    internal fun productOf(name: String): String {
        val cut = name.indexOfFirst { it == '#' || it == '@' }
        return (if (cut >= 0) name.substring(0, cut) else name).trim()
    }

    /** "@<apiLevel>" suffix if present, else "". */
    internal fun apiSuffixOf(name: String): String {
        val at = name.lastIndexOf('@')
        if (at < 0) return ""
        val api = name.substring(at + 1).trim()
        return if (api.isNotEmpty() && api.all { it.isDigit() }) "@$api" else ""
    }

    private fun byteLen(s: String): Int = s.encodeToByteArray().size

    /** Longest prefix of [s] whose UTF-8 length is <= [maxBytes] (never splits a
     *  multi-byte char). */
    private fun trimToBytes(s: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        if (byteLen(s) <= maxBytes) return s
        var end = s.length
        while (end > 0 && byteLen(s.substring(0, end)) > maxBytes) end--
        return s.substring(0, end).trimEnd()
    }
}
