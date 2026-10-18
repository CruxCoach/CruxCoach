package com.cruxcoach.app.ui

import com.cruxcoach.app.messages.Announcement
import com.cruxcoach.app.messages.DevContactError
import com.cruxcoach.app.messages.DevContactPresenter
import com.cruxcoach.app.messages.DevContactState
import com.cruxcoach.app.messages.DevMessage
import com.cruxcoach.app.messages.DevMessageType

class DevMessageRowUi(
    val id: String,
    /** crash | bug | feature | chat */
    val typeCode: String,
    val outgoing: Boolean,
    val content: String,
    val subject: String,
    val createdAtEpochSeconds: Long,
    /** False on an outgoing message no relay stored. */
    val delivered: Boolean,
    val read: Boolean,
    val replyToId: String,
)

class AnnouncementRowUi(
    val id: String,
    val content: String,
    /** release | issue | tip | general */
    val category: String,
    /** high | default | low */
    val priority: String,
    val createdAtEpochSeconds: Long,
    val read: Boolean,
)

class DevContactScreenState(
    val isLoading: Boolean,
    val isSending: Boolean,
    val isFetching: Boolean,
    val typeCode: String,
    val draft: String,
    val subject: String,
    val messages: List<DevMessageRowUi>,
    val announcements: List<AnnouncementRowUi>,
    val unread: Long,
    val unreadAnnouncements: Long,
    val lastSentId: String,
    /** none | noIdentity | empty | sendFailed | notDelivered | fetchFailed */
    val errorCode: String,
)

/** Swift-facing maintainer conversation: reports, requests and replies. */
class DevContactScreenModel(private val presenter: DevContactPresenter) {

    val currentState: DevContactScreenState get() = map(presenter.state.value)

    fun watch(onState: (DevContactScreenState) -> Unit): Subscription {
        val watch = presenter.watch { onState(map(it)) }
        return Subscription { watch.cancel() }
    }

    fun setTypeCode(code: String) {
        type(code)?.let { presenter.setType(it) }
    }

    fun setDraft(value: String) = presenter.setDraft(value)
    fun setSubject(value: String) = presenter.setSubject(value)
    fun send() = presenter.send()
    fun reply(messageId: String) = presenter.send(messageId)
    fun fetch() = presenter.fetch()
    fun markRead(id: String) = presenter.markRead(id)
    fun markAnnouncementRead(id: String) = presenter.markAnnouncementRead(id)
    fun consumeError() = presenter.consumeError()
    fun consumeSent() = presenter.consumeSent()
    fun close() = presenter.close()

    /**
     * Android's bug report form (`BugReportScreen` → `sendBugReport`): sent as a bug report with
     * the title as subject. [osVersion], [physicalMemoryBytes] and [language] make the device
     * line (iOS version, Android's three memory tiers, never the model); [appVersion] is
     * "name (build)".
     */
    fun sendBugReport(
        title: String,
        details: String,
        steps: String,
        osVersion: String,
        physicalMemoryBytes: Long,
        language: String,
        appVersion: String,
    ) {
        val text = composeBugReport(
            title, details, steps,
            deviceLine(osVersion, physicalMemoryBytes, language), appVersion,
        )
        presenter.setType(DevMessageType.BUG)
        presenter.setSubject(title.trim())
        presenter.setDraft(text)
        presenter.send()
    }

    companion object {
        /** Android's `sendBugReport` text, in its (German) section labels the maintainer reads. */
        internal fun composeBugReport(
            title: String,
            description: String,
            steps: String,
            deviceLine: String,
            appVersion: String,
        ): String = buildString {
            append(title.trim())
            append("\n\nBeschreibung:\n")
            append(description.trim())
            if (steps.isNotBlank()) {
                append("\n\nSchritte:\n")
                append(steps.trim())
            }
            append("\n\n---\n")
            append(deviceLine)
            append("\nApp ").append(appVersion)
        }

        /** Android's `generalizedDeviceInfoLine` ("Android API 34 | mid-range | de"), for iOS. */
        internal fun deviceLine(osVersion: String, physicalMemoryBytes: Long, language: String): String =
            "iOS $osVersion | ${com.cruxcoach.app.crash.CrashReportFormat.deviceTier(physicalMemoryBytes)} | $language"
    }

    private fun type(code: String): DevMessageType? = when (code) {
        "crash" -> DevMessageType.CRASH
        "bug" -> DevMessageType.BUG
        "feature" -> DevMessageType.FEATURE
        "chat" -> DevMessageType.CHAT
        else -> null
    }

    private fun code(type: DevMessageType): String = when (type) {
        DevMessageType.CRASH -> "crash"
        DevMessageType.BUG -> "bug"
        DevMessageType.FEATURE -> "feature"
        DevMessageType.CHAT -> "chat"
    }

    private fun map(state: DevContactState) = DevContactScreenState(
        isLoading = state.isLoading,
        isSending = state.isSending,
        isFetching = state.isFetching,
        typeCode = code(state.type),
        draft = state.draft,
        subject = state.subject,
        messages = state.messages.map { row(it) },
        announcements = state.announcements.map { announcement(it) },
        unread = state.unread,
        unreadAnnouncements = state.unreadAnnouncements,
        lastSentId = state.lastSentId,
        errorCode = when (state.error) {
            DevContactError.NONE -> "none"
            DevContactError.NO_IDENTITY -> "noIdentity"
            DevContactError.EMPTY -> "empty"
            DevContactError.SEND_FAILED -> "sendFailed"
            DevContactError.NOT_DELIVERED -> "notDelivered"
            DevContactError.FETCH_FAILED -> "fetchFailed"
        },
    )

    private fun announcement(item: Announcement) = AnnouncementRowUi(
        id = item.id,
        content = item.content,
        category = item.category,
        priority = item.priority,
        createdAtEpochSeconds = item.createdAt,
        read = item.read,
    )

    private fun row(message: DevMessage) = DevMessageRowUi(
        id = message.id,
        typeCode = code(message.type),
        outgoing = message.outgoing,
        content = message.content,
        subject = message.subject ?: "",
        createdAtEpochSeconds = message.createdAt,
        delivered = message.relayAccepted,
        read = message.read,
        replyToId = message.replyToId ?: "",
    )
}
