package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.BoardSendMode

/**
 * What happens to a queue climb when the player moves to it.
 *
 * Modelled on [BoardDeliveryPolicy], and for its reason: that one settles
 * whether a send is possible at all *before* it consults any preference, so a
 * condition added later cannot accidentally sit in front of the connection
 * check. The queue path had the same conditions written out by hand in three
 * places — the send itself and two surfaces that draw the lamp — and each
 * learned them separately. The send mode was missing from one, the connection
 * state from the other two.
 */
internal object QueueDeliveryPolicy {

    enum class Decision {
        /** Put it on the wall now. */
        SEND,

        /** The wall stays as it is; the player offers the lamp. */
        AWAIT_EXPLICIT,

        /** Nothing to send, or nothing to send to. */
        NONE,
    }

    /**
     * Whether sending is possible at all.
     *
     * Asked before anything about modes, and asked by the surfaces that draw
     * the send control: a lamp that outlives the connection is a button that
     * lies, because the send path drops a request with no connection on the
     * floor without a word.
     */
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
