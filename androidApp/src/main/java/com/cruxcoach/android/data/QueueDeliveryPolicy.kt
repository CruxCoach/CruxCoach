package com.cruxcoach.android.data

/** What happens to a queue climb when the player moves to it. */
internal object QueueDeliveryPolicy {
    enum class Decision {
        /** Put it on the wall now. */
        SEND,

        /** The wall stays as it is; the player offers the lamp. */
        AWAIT_EXPLICIT,

        /** Nothing to send, or nothing to send to. */
        NONE,
    }

    /** Whether sending is possible at all. */
    fun canSend(isHost: Boolean, boardConnected: Boolean): Boolean =
        isHost && boardConnected

    /**
     * @param explicitRequest the user asked for this one — the lamp, or the
     *   first send of a freshly loaded queue. Advancing does not count.
     */
    fun decide(
        isHost: Boolean,
        boardConnected: Boolean,
        sendMode: BoardSendMode,
        explicitRequest: Boolean,
    ): Decision = when {
        !canSend(isHost, boardConnected) -> Decision.NONE
        explicitRequest || sendMode == BoardSendMode.AUTOMATIC -> Decision.SEND
        else -> Decision.AWAIT_EXPLICIT
    }
}
