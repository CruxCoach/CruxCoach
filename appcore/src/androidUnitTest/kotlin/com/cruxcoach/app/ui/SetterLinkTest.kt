package com.cruxcoach.app.ui

import com.cruxcoach.app.browse.testing.TestClimb
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Android ClimbCard / detail / player: which setter names open the setter's profile. */
@OptIn(ExperimentalCoroutinesApi::class)
class SetterLinkTest {
    private val pubkey = "a".repeat(64)

    @Test
    fun `only a CruxCoach climb with a signer links to the setter profile`() {
        val community = TestClimb.stats(uuid = "cc", setterUsername = "Mia", origin = "cruxcoach", source = "nostr", createdByPubkey = pubkey)
        assertEquals(pubkey, SetterLink.pubkey(community))
        assertEquals("Mia", SetterLink.display(community))

        val catalogue = TestClimb.stats(uuid = "k", setterUsername = "jwebxl", createdByPubkey = pubkey)
        assertEquals("", SetterLink.pubkey(catalogue), "a Kilter setter is plain text")
        assertEquals("jwebxl", SetterLink.display(catalogue))

        val unsigned = TestClimb.stats(uuid = "u", origin = "cruxcoach", source = "nostr", createdByPubkey = "  ")
        assertEquals("", SetterLink.pubkey(unsigned))
    }

    @Test
    fun `an own or community climb without a cached name shows an npub stub`() {
        val draft = TestClimb.stats(uuid = "d", setterUsername = null, origin = "cruxcoach", source = "local", createdByPubkey = pubkey)
        assertEquals("npub:" + pubkey.take(16), SetterLink.display(draft))
        val legacyDraft = TestClimb.stats(uuid = "l", setterUsername = "", origin = "kilter", source = "local", createdByPubkey = pubkey)
        assertEquals("npub:" + pubkey.take(16), SetterLink.display(legacyDraft))
        assertEquals("", SetterLink.pubkey(legacyDraft), "a legacy draft keeps origin kilter: no link, as on Android")
        val catalogue = TestClimb.stats(uuid = "k", setterUsername = null)
        assertEquals("", SetterLink.display(catalogue))
    }

    @Test
    fun `the player lookup answers on main and an unknown climb has no setter`() {
        val dispatcher = StandardTestDispatcher()
        TestScope(dispatcher).runTest {
            val climbs = mapOf("cc" to TestClimb.stats(uuid = "cc", setterUsername = "Mia", origin = "cruxcoach", source = "nostr", createdByPubkey = pubkey))
            val lookup = ClimbSetterLookup({ uuid -> if (uuid == "boom") error("locked") else climbs[uuid] }, dispatcher, dispatcher)
            val results = mutableListOf<ClimbSetterUi>()
            lookup.lookup("cc") { results += it }
            lookup.lookup("missing") { results += it }
            lookup.lookup("boom") { results += it }
            advanceUntilIdle()
            assertEquals(listOf("Mia", "", ""), results.map { it.display })
            assertEquals(listOf(pubkey, "", ""), results.map { it.pubkey })
            assertEquals(listOf("cc", "missing", "boom"), results.map { it.uuid })
            lookup.close()
        }
    }
}
