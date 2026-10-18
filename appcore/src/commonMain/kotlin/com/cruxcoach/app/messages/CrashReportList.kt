package com.cruxcoach.app.messages

/**
 * Android's `crashReportList` (DevContactViewModel.kt): each own crash report once; the
 * maintainer's replies live in the report's thread. A report sent twice (a launch whose send
 * no relay took left the file for the next launch) is listed once, by its text without the
 * wire prefix. A reply no listed report can take stays in the list on its own rather than
 * disappear.
 */
object CrashReportList {
    private val WIRE_PREFIX = "${DevMessageType.CRASH.prefix} "

    fun of(messages: List<DevMessage>): List<DevMessage> {
        val crashes = messages.filter { it.type == DevMessageType.CRASH }
        val seenContent = HashSet<String>()
        val reportIds = crashes
            .filter { it.outgoing && it.replyToId == null && seenContent.add(text(it)) }
            .mapTo(HashSet()) { it.id }
        return crashes.filter { it.id in reportIds || (!it.outgoing && it.replyToId !in reportIds) }
    }

    /**
     * Own reports hidden as older copies of a listed one that no relay took: builds before
     * 0.2.4 sent a report once per launch while offline. Dropping them keeps a drain from
     * sending the same report again and again; delivered copies stay.
     */
    fun undeliveredCopies(messages: List<DevMessage>): List<String> {
        val listed = of(messages).mapTo(HashSet()) { it.id }
        return messages
            .filter { it.type == DevMessageType.CRASH && it.outgoing && it.replyToId == null }
            .filter { it.id !in listed && !it.relayAccepted }
            .map { it.id }
    }

    /** The report as the user wrote it: iOS stores the wire text, which starts with "[CRASH] ". */
    fun text(message: DevMessage): String = message.content.removePrefix(WIRE_PREFIX).trim()
}
