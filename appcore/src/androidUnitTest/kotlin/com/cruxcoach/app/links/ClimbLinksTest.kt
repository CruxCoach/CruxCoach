package com.cruxcoach.app.links

import com.cruxcoach.app.nostr.Nip19
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClimbLinksTest {
    @Test
    fun `a catalogue climb links by its uuid`() {
        assertEquals("https://cruxcoach.org/c/ABC123", ClimbLinks.shareLink(null, "ABC123"))
    }

    @Test
    fun `a community climb links by the naddr of its replaceable event`() {
        val author = "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"
        val link = assertNotNull(ClimbLinks.shareLink(author, "uuid-1"))
        assertTrue(link.startsWith("https://cruxcoach.org/c/naddr1"))
        val naddr = assertNotNull(Nip19.decodeNaddr(link.removePrefix("https://cruxcoach.org/c/")))
        assertEquals(30078, naddr.kind)
        assertEquals(author, naddr.authorHex)
        assertEquals("cruxcoach:climb:f9308a01:uuid-1", naddr.identifier)
        assertTrue(naddr.relays.isEmpty())
    }
}
