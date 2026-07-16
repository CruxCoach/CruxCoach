package com.cruxcoach.android.crash

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class CrashReportSanitizerTest {
    @Test
    fun `crash sequence starts at one and advances from the pending report`() {
        kotlin.test.assertEquals(1, CruxCoachCrashHandler.nextCrashSequence(null))
        kotlin.test.assertEquals(
            2,
            CruxCoachCrashHandler.nextCrashSequence("legacy report without a sequence"),
        )
        kotlin.test.assertEquals(
            8,
            CruxCoachCrashHandler.nextCrashSequence("Crash sequence: 7\nStack"),
        )
    }

    @Test
    fun `renders allow-listed structure without messages or file paths`() {
        val secretMessage =
            "boom nsec1secret npub1identity ${"a".repeat(64)} Bearer token?access_token=secret\nFORGED"
        val cause = IllegalArgumentException("cause /data/user/0/app/private.db /home/alice/source.kt")
        cause.stackTrace = arrayOf(
            StackTraceElement("com.example.Cause", "parse-payload", "/home/alice/Cause.kt", 9),
        )
        val failure = IllegalStateException(secretMessage, cause)
        failure.stackTrace = arrayOf(
            StackTraceElement("com.example.SecretWorker", "runTask", "/data/user/0/app/Secret.kt", 42),
        )

        val rendered = CrashReportSanitizer.renderStack(failure)

        assertContains(rendered, "java.lang.IllegalStateException")
        assertContains(rendered, "com.example.SecretWorker.runTask(line 42)")
        assertContains(rendered, "java.lang.IllegalArgumentException")
        assertContains(rendered, "com.example.Cause.parse_payload(line 9)")
        listOf(
            "boom", "nsec1secret", "npub1identity", "a".repeat(64), "Bearer", "access_token",
            "FORGED", "/data/", "/home/", "Secret.kt", "Cause.kt",
        ).forEach { forbidden -> assertFalse(rendered.contains(forbidden), forbidden) }
    }
}
