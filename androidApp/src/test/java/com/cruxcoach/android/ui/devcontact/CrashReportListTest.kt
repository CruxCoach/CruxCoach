package com.cruxcoach.android.ui.devcontact

import com.cruxcoach.android.nostr.model.MessageType
import org.junit.Assert.assertEquals
import org.junit.Test

/** One crash report is one list row; the developer's replies go to its thread. */
class CrashReportListTest {

    private val report = "--- CruxCoach Crash Report ---\nTime: 2026-09-24T10:09:00Z"

    private fun message(
        id: String,
        content: String,
        isSent: Boolean,
        replyToId: String? = null,
        type: MessageType = MessageType.CRASH,
    ) = UiMessage(
        id = id,
        content = content,
        subject = null,
        isSent = isSent,
        timestamp = 0L,
        isRead = true,
        replyToId = replyToId,
        type = type.label,
    )

    @Test
    fun `a reply to a report stays out of the list`() {
        val messages = listOf(
            message("reply", "angekommen", isSent = false, replyToId = "selfwrap"),
            message("selfwrap", report, isSent = true),
        )

        assertEquals(listOf("selfwrap"), crashReportList(messages).map { it.id })
    }

    @Test
    fun `an old report and its relay echo are one row and its reply stays visible`() {
        // Before 0.2.3: random row id, echo stored again with the wire prefix,
        // reply anchored to a recipient-wrap id no local row knows.
        val messages = listOf(
            message("reply", "angekommen", isSent = false, replyToId = "recipientwrap"),
            message("5f0c-uuid", report, isSent = true),
            message("selfwrap", "[CRASH] $report", isSent = true),
        )

        assertEquals(listOf("reply", "5f0c-uuid"), crashReportList(messages).map { it.id })
    }

    @Test
    fun `other message types are not crash reports`() {
        val messages = listOf(
            message("bug", "QA", isSent = true, type = MessageType.BUG),
            message("chat", "hi", isSent = false, type = MessageType.CHAT),
        )

        assertEquals(emptyList<String>(), crashReportList(messages).map { it.id })
    }
}
