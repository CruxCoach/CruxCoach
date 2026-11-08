package com.cruxcoach.android.communityclient

import org.junit.Test
import kotlin.test.assertEquals

class PublishErrorTest {
    /** The relay's NIP-01 prefix picks the text; the rest of its message never reaches the screen. */
    @Test
    fun relayMessages_mapToTheTextsTheScreenShows() {
        assertEquals(PublishError.NETWORK, publishErrorOf("offline: not connected to the community"))
        assertEquals(PublishError.NETWORK, publishErrorOf("timeout: no answer from the relay"))
        assertEquals(PublishError.NETWORK, publishErrorOf("auth-required: sign in first"))
        assertEquals(PublishError.RATE_LIMITED, publishErrorOf("rate-limited: quota exceeded; retry in 3s"))
        assertEquals(PublishError.NOT_ALLOWED, publishErrorOf("restricted: not a member of this channel"))
        assertEquals(PublishError.NOT_ALLOWED, publishErrorOf("blocked: banned"))
        assertEquals(PublishError.REFUSED, publishErrorOf("invalid: bad tags"))
        assertEquals(PublishError.REFUSED, publishErrorOf(""))
        assertEquals(PublishError.REFUSED, publishErrorOf("something without a prefix"))
    }
}
