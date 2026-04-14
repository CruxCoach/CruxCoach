package com.cruxcoach.android.ble

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Tests for BLE advertising and scanning configuration invariants.
 *
 * Protects against regressions in cross-device compatibility discovered during
 * debugging Session Nearby Share across Android 9 and Android 15:
 *
 * 1. Session scan response must NOT be null (Samsung drops ADV_IND without SCAN_RSP)
 * 2. Scanner must NOT use setLegacy(false) (breaks Climb Share on some devices)
 * 3. Session payload must fit in 31-byte legacy ADV_IND
 *
 * Android BLE Builder classes don't work in JVM unit tests, so we verify
 * invariants via protocol-level tests and source code analysis.
 */
class SessionBleConfigTest {

    // ===== Protocol payload size invariant =====
    // Legacy ADV_IND max = 31 bytes. Structure:
    // Flags AD: 3 bytes (type 0x01, length 0x02, value)
    // Manufacturer Data AD: 2 (type+length) + 2 (company ID) + payload
    // Total: 3 + 4 + payload <= 31 → payload <= 24

    @Test
    fun `session payload fits in legacy ADV_IND (max 24 bytes)`() {
        // Worst case: max-length host name
        val payload = NearbyClimbProtocol.encodeSessionAdvertisement(
            sessionId = Int.MAX_VALUE,
            participantCount = 255,
            hostName = "1234567890123" // 13 bytes = max per protocol
        )
        assertTrue(
            "Session payload must be <= 24 bytes to fit in legacy ADV_IND " +
                "(actual: ${payload.size})",
            payload.size <= 24
        )
    }

    @Test
    fun `session payload starts with CRUX magic`() {
        val payload = NearbyClimbProtocol.encodeSessionAdvertisement(123, 1, "Host")
        assertTrue("Payload must be >= 4 bytes", payload.size >= 4)
        assertEquals("Must start with C", 0x43.toByte(), payload[0])
        assertEquals("Must start with R", 0x52.toByte(), payload[1])
        assertEquals("Must start with U", 0x55.toByte(), payload[2])
        assertEquals("Must start with X", 0x58.toByte(), payload[3])
    }

    @Test
    fun `session payload type byte is SESSION_ADVERTISEMENT`() {
        val payload = NearbyClimbProtocol.encodeSessionAdvertisement(123, 1, "Host")
        // Type byte is at index 4, value 0x08 = TYPE_SESSION
        assertEquals("Type byte must be 0x08 (SESSION)", 0x08.toByte(), payload[4])
    }

    // ===== Source code invariant: scan response must not be null =====
    // Samsung BLE stacks silently drop ADV_IND when SCAN_RSP is missing.
    // The startAdvertisingSet() call MUST pass a non-null scanResponse parameter.

    @Test
    fun `advertiseSession passes non-null scan response to startAdvertisingSet`() {
        val source = readSourceFile("ClimbBleAdvertiser.kt")

        // Verify startAdvertisingSet is called with scanResponse (not null)
        // Pattern: adv.startAdvertisingSet(params, advData, scanResponse, null, null, ...
        assertTrue(
            "startAdvertisingSet must use scanResponse variable (not null literal) as 3rd argument.\n" +
                "Samsung drops ADV_IND results when SCAN_RSP is missing.",
            source.contains("adv.startAdvertisingSet(params, advData, scanResponse,")
        )

        // Verify scanResponse is built (not hardcoded null)
        assertTrue(
            "buildSessionScanResponse must be called to create non-null scan response",
            source.contains("buildSessionScanResponse(")
        )
    }

    // ===== Source code invariant: scanner must NOT use setLegacy(false) =====
    // setLegacy(false) breaks Climb Nearby Share on some devices.

    @Test
    fun `scanner does not use setLegacy false in code`() {
        val source = readSourceFile("NearbyClimbScanner.kt")

        // Check only non-comment lines for setLegacy(false)
        val codeLines = source.lines().filter { line ->
            val trimmed = line.trim()
            !trimmed.startsWith("//") && !trimmed.startsWith("*") && !trimmed.startsWith("/*")
        }
        val codeOnly = codeLines.joinToString("\n")

        assertFalse(
            "Scanner MUST NOT call .setLegacy(false) — this breaks Climb Nearby Share.\n" +
                "setLegacyMode(true) on the advertiser produces correct Legacy PDUs (AOSP confirmed).",
            codeOnly.contains(".setLegacy(false)")
        )
    }

    // ===== Source code invariant: hardware scan filter for background delivery =====
    // CRUX manufacturer data filter (COMPANY_ID 0xFFFF + magic bytes) ensures Android
    // delivers scan results even in the background. Without hardware filters, Android 8+
    // throttles scans after ~30s in the background.

    @Test
    fun `scanner uses CRUX manufacturer data hardware filter`() {
        val source = readSourceFile("NearbyClimbScanner.kt")

        assertTrue(
            "startScan must use hardware filters for background scan delivery",
            source.contains("s.startScan(filters,")
        )
        assertTrue(
            "buildScanFilters must filter on COMPANY_ID",
            source.contains("NearbyClimbProtocol.COMPANY_ID")
        )
    }

    // ===== Source code invariant: session advertising is legacy + connectable + scannable =====

    @Test
    fun `session advertising uses legacy connectable scannable mode`() {
        val source = readSourceFile("ClimbBleAdvertiser.kt")

        // All three must be set in the session advertising parameters
        assertTrue("Session advertising must use setLegacyMode(true)",
            source.contains(".setLegacyMode(true)"))
        assertTrue("Session advertising must use setConnectable(true)",
            source.contains(".setConnectable(true)"))
        assertTrue("Session advertising must use setScannable(true)",
            source.contains(".setScannable(true)"))
    }

    // ===== Source code invariant: stopSessionAdvertisingInternal before new set =====

    @Test
    fun `advertiseSession calls stopSessionAdvertisingInternal before starting new set`() {
        val source = readSourceFile("ClimbBleAdvertiser.kt")

        // Find the advertiseSession method and verify stopSessionAdvertisingInternal
        // is called before startAdvertisingSet
        val methodStart = source.indexOf("fun advertiseSession(")
        val stopCall = source.indexOf("stopSessionAdvertisingInternal()", methodStart)
        val startCall = source.indexOf("adv.startAdvertisingSet(", methodStart)

        assertTrue("stopSessionAdvertisingInternal must exist in advertiseSession",
            stopCall > methodStart)
        assertTrue("startAdvertisingSet must exist in advertiseSession",
            startCall > methodStart)
        assertTrue(
            "stopSessionAdvertisingInternal() must be called BEFORE startAdvertisingSet() " +
                "to prevent 'callback instance already associated' crash during BT recovery race",
            stopCall < startCall
        )
    }

    // ===== Source code invariant: climb advertising suppressed during session =====

    @Test
    fun `startSharing suppresses climb advertising`() {
        val source = readSourceFile("SessionGattBridge.kt")
        val startSharingMethod = source.substring(source.indexOf("fun startSharing()"))
        assertTrue(
            "startSharing must set suppressClimbAdvertising = true before advertising session",
            startSharingMethod.contains("advertiser.suppressClimbAdvertising = true")
        )
    }

    @Test
    fun `stopSharing transitions to last projected climb`() {
        val source = readSourceFile("SessionGattBridge.kt")
        val stopSharingMethod = source.substring(source.indexOf("fun stopSharing()"))
        assertTrue(
            "stopSharing must set last climb via manager and advertise via BLE",
            stopSharingMethod.contains("boardStateManager.setLastClimb") &&
                stopSharingMethod.contains("advertiser.advertiseLastClimb")
        )
    }

    @Test
    fun `joinSession suppresses climb advertising`() {
        val source = readSourceFile("SessionGattBridge.kt")
        val joinMethod = source.substring(source.indexOf("fun joinSession("))
        assertTrue(
            "joinSession must set suppressClimbAdvertising = true",
            joinMethod.contains("advertiser.suppressClimbAdvertising = true")
        )
    }

    @Test
    fun `advertiseClimb checks suppressClimbAdvertising flag`() {
        val source = readSourceFile("ClimbBleAdvertiser.kt")
        val method = source.substring(source.indexOf("fun advertiseClimb("))
        assertTrue(
            "advertiseClimb must check suppressClimbAdvertising and return early when true",
            method.contains("suppressClimbAdvertising")
        )
    }

    // ===== Helper =====

    private fun readSourceFile(fileName: String): String {
        // Walk up from test output dir to find the source file
        val projectRoot = File(System.getProperty("user.dir"))
        val sourceFile = projectRoot.walkTopDown()
            .filter { it.name == fileName && it.path.contains("src/main/") }
            .firstOrNull()
            ?: throw AssertionError("Source file $fileName not found under $projectRoot")
        return sourceFile.readText()
    }
}
