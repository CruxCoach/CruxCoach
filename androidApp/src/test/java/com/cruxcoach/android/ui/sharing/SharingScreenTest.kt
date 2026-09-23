package com.cruxcoach.android.ui.sharing

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.cruxcoach.android.sharing.CategoryChoice
import com.cruxcoach.android.sharing.MarmotDiscovery
import com.cruxcoach.android.sharing.MarmotLocal
import com.cruxcoach.android.sharing.MarmotPeer
import com.cruxcoach.android.sharing.MarmotStatus
import com.cruxcoach.android.sharing.PersonState
import com.cruxcoach.android.sharing.PreviewItem
import com.cruxcoach.android.sharing.ReceivedOnClimb
import com.cruxcoach.android.sharing.ShareScope
import com.cruxcoach.android.sharing.SharingFriend
import com.cruxcoach.android.sharing.SharingInvitation
import com.cruxcoach.android.sharing.SharingOverview
import com.cruxcoach.android.sharing.SharingPerson
import com.cruxcoach.android.sharing.SharingPersonDetail
import com.cruxcoach.android.sharing.SharingPreset
import com.cruxcoach.android.sharing.SharingRecord
import com.cruxcoach.android.sharing.VisibleState
import com.cruxcoach.domain.sharing.AccessEffect
import com.cruxcoach.domain.sharing.SharingCategory
import com.cruxcoach.domain.sharing.SharingCircle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The v2 sharing screens: four states, one switch, visible exceptions, one confirmation. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de")
class SharingScreenTest {
    @get:Rule val compose = createComposeRule()

    private val me = "e".repeat(64)
    private val calls = mutableListOf<String>()
    private val actions = SharingActions(
        accept = { peer, circle, outgoing -> calls += "accept:${peer.take(4)}:$circle:$outgoing" },
        decline = { calls += "decline:${it.take(4)}" },
        setOutgoing = { peer, on -> calls += "outgoing:${peer.take(4)}:$on" },
        end = { calls += "end:${it.take(4)}" },
        request = { typed, circle, _, _ -> calls += "request:$typed:$circle" },
        setCategory = { _, category, on -> calls += "category:$category:$on" },
        setItem = { _, item, on -> calls += "item:${item.record.id}:$on" },
        setPreset = { calls += "preset:${it.circle}:${it.categories.sorted()}" },
    )

    private fun person(peer: String, state: PersonState, outgoing: Boolean = true, label: String? = null) =
        SharingPerson(peer, SharingCircle.FRIENDS, outgoing, null, label, state, false, 1, null)

    private fun native(peer: String, state: String) = MarmotPeer(peer, "g", state, true, 1, 0, 0, 0, "")

    private fun friend(peer: String, visible: VisibleState, label: String, state: PersonState = PersonState.CONNECTED, outgoing: Boolean = true) =
        SharingFriend(person(peer, state, outgoing, label), native(peer, if (visible == VisibleState.PENDING) "pending" else "active"),
            visible, null, ShareScope.EMPTY, if (visible == VisibleState.ACTIVE) 3 else 0, 0)

    private val presets = mapOf(
        SharingCircle.FRIENDS to SharingPreset(SharingCircle.FRIENDS, setOf(SharingCategory.PROFILE_AND_GOALS), 30),
        SharingCircle.ACQUAINTANCES to SharingPreset(SharingCircle.ACQUAINTANCES, setOf(SharingCategory.PRIVATE_NOTES), 30),
    )

    private fun overview(friends: List<SharingFriend> = emptyList(), invitations: List<SharingInvitation> = emptyList()) = SharingOverview(
        MarmotStatus(me, true, MarmotDiscovery(true, 0), MarmotLocal(0, 0, 2), emptyList(), emptyList(), true, 1),
        presets, friends, invitations,
    )

    private fun render(state: SharingUiState, page: SharingPage) {
        compose.setContent {
            MaterialTheme {
                SharingContent(state, page, onOpen = { calls += "open:${it.key.take(11)}" }, actions = actions)
            }
        }
    }

    @Test fun `people show exactly the four states and open their page`() {
        val friends = listOf(
            friend("a".repeat(64), VisibleState.PENDING, "Anna", PersonState.REQUESTED),
            friend("b".repeat(64), VisibleState.ACTIVE, "Ben"),
            friend("c".repeat(64), VisibleState.STOPPED, "Cleo", outgoing = false),
            friend("d".repeat(64), VisibleState.ENDED, "Dana", PersonState.ENDED),
        )
        render(SharingUiState(loading = false, overview = overview(friends)), SharingPage.Overview)
        compose.onNodeWithText("Ausstehend · Freunde").assertExists()
        compose.onNodeWithText("Aktiv · Freunde · 3 erhalten").assertExists()
        compose.onNodeWithText("Gestoppt · Freunde").assertExists()
        compose.onNodeWithText("Beendet · Freunde").assertExists()
        compose.onNodeWithText("Ben").performScrollTo().performClick()
        assertEquals(listOf("open:person:bbbb"), calls)
        compose.onNodeWithText("2 Nachrichten warten auf ein Relay", substring = true).assertExists()
    }

    @Test fun `an invitation says what would be shared and accepting passes the chosen switch`() {
        val peer = "f".repeat(64)
        render(SharingUiState(loading = false, overview = overview(invitations = listOf(SharingInvitation(peer, 1)))), SharingPage.Overview)
        // The default circle is the narrower one; its preset is visible before accepting.
        compose.onNodeWithText("Du würdest teilen: Notizen").assertExists()
        compose.onNodeWithTag("sharing_invitation_share").performScrollTo().performClick()
        compose.onNodeWithText("Du würdest nur empfangen.").assertExists()
        compose.onNodeWithTag("sharing_accept").performScrollTo().performClick()
        assertEquals(listOf("accept:ffff:ACQUAINTANCES:false"), calls)
    }

    @Test fun `a refused identity is explained and an empty field cannot be sent`() {
        render(SharingUiState(loading = false, overview = overview(), requestError = RequestError.NOT_A_PUBLIC_KEY), SharingPage.Overview)
        compose.onNodeWithText("Das ist keine öffentliche Kennung. Gib niemals eine nsec weiter.").assertExists()
        compose.onNodeWithTag("sharing_add_send").assertIsNotEnabled()
        compose.onNodeWithTag("sharing_add_id").performScrollTo().performTextInput("npub1example")
        compose.onNodeWithTag("sharing_add_send").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("request:npub1example:ACQUAINTANCES"), calls)
    }

    private fun detail(outgoing: Boolean = true, visible: VisibleState = VisibleState.ACTIVE, state: PersonState = PersonState.CONNECTED): SharingPersonDetail {
        val peer = "b".repeat(64)
        val note = SharingRecord("note:c1", SharingCategory.PRIVATE_NOTES, 1, mapOf("climbId" to "c1", "note" to "Heel hook left"))
        val profile = SharingRecord("profile:active", SharingCategory.PROFILE_AND_GOALS, 1,
            mapOf("name" to "Me", "boulderGrade" to "7A", "sportGrade" to "", "climbingYears" to "3", "sessionsPerWeek" to "2", "equipment" to "", "goals" to ""))
        return SharingPersonDetail(
            friend = friend(peer, visible, "Ben", state, outgoing),
            preset = presets.getValue(SharingCircle.FRIENDS),
            categories = mapOf(
                SharingCategory.PROFILE_AND_GOALS to CategoryChoice(true, true, null),
                SharingCategory.TRAINING_HISTORY to CategoryChoice(false, false, null),
                SharingCategory.PRIVATE_NOTES to CategoryChoice(false, true, AccessEffect.DENY),
            ),
            trainingDays = 30,
            items = listOf(PreviewItem(profile, true, null), PreviewItem(note, true, AccessEffect.ALLOW)),
            received = listOf(note.copy(fields = note.fields + ("note" to "Ben's beta"))),
        )
    }

    private fun renderPerson(detail: SharingPersonDetail) =
        render(SharingUiState(loading = false, overview = overview(listOf(detail.friend)), detail = detail), SharingPage.Person(detail.friend.peer))

    @Test fun `the person page has one switch, visible exceptions and the exact preview`() {
        renderPerson(detail())
        compose.onNodeWithTag("sharing_person_state").assertTextEquals("Aktiv")
        compose.onNodeWithText("Ausnahme: weicht von der Voreinstellung „Freunde“ ab").assertExists()
        compose.onNodeWithText("„Heel hook left“").assertExists()
        compose.onNodeWithText("Ausnahme für diesen Eintrag").assertExists()
        compose.onNodeWithText("„Ben's beta“").assertExists()
        compose.onNodeWithTag("sharing_person_switch").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithTag("sharing_category_PRIVATE_NOTES").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithText("„Heel hook left“").performScrollTo().performClick()
        assertEquals(listOf("outgoing:bbbb:false", "category:PRIVATE_NOTES:true", "item:note:c1:false"), calls)
    }

    @Test fun `a stopped person receives nothing and the preview says so`() {
        renderPerson(detail(outgoing = false, visible = VisibleState.STOPPED))
        compose.onNodeWithTag("sharing_person_state").assertTextEquals("Gestoppt")
        compose.onNodeWithText("Nichts – das Teilen mit dieser Person ist ausgeschaltet.").assertExists()
        compose.onNodeWithText("„Heel hook left“").assertDoesNotExist()
    }

    @Test fun `ending takes exactly one confirmation`() {
        renderPerson(detail())
        compose.onNodeWithTag("sharing_end").performScrollTo().performClick()
        assertEquals(emptyList<String>(), calls)
        compose.onNodeWithTag("sharing_end_confirm").performClick()
        assertEquals(listOf("end:bbbb"), calls)
    }

    @Test fun `an ended friendship offers only a new request or removal`() {
        renderPerson(detail(visible = VisibleState.ENDED, state = PersonState.ENDED))
        compose.onNodeWithTag("sharing_request_again").assertExists()
        compose.onNodeWithTag("sharing_remove").assertExists()
        compose.onNodeWithTag("sharing_person_switch").assertDoesNotExist()
        compose.onNodeWithTag("sharing_end").assertDoesNotExist()
    }

    @Test fun `friends inherit the acquaintances preset visibly and cannot switch it off there`() {
        render(SharingUiState(loading = false, overview = overview()), SharingPage.Preset(SharingCircle.FRIENDS))
        compose.onNodeWithTag("sharing_preset_category_PRIVATE_NOTES").assertIsOn().assertIsNotEnabled()
        compose.onNodeWithText("Enthalten, weil Bekannte es erhalten.").assertExists()
        compose.onNodeWithTag("sharing_preset_category_TRAINING_HISTORY").performScrollTo().assertIsOff().performClick()
        assertEquals(listOf("preset:FRIENDS:[PROFILE_AND_GOALS, TRAINING_HISTORY]"), calls)
    }

    @Test fun `the climb page lists what friends shared about it and nothing otherwise`() {
        val shown = mutableStateOf(emptyList<ReceivedOnClimb>())
        compose.setContent { MaterialTheme { ReceivedClimbContent(shown.value) } }
        compose.onNodeWithTag("boarddetail_received").assertDoesNotExist()
        shown.value = listOf(
            ReceivedOnClimb("b".repeat(64), "Ben", SharingRecord("note:c1", SharingCategory.PRIVATE_NOTES, 1, mapOf("climbId" to "c1", "note" to "Toe hook"))),
            ReceivedOnClimb("b".repeat(64), "Ben", SharingRecord("bid:x", SharingCategory.TRAINING_HISTORY, 1, mapOf("climbId" to "c1",
                "name" to "Crimp Line", "board" to "kilter", "angle" to "40", "attempts" to "4", "grade" to "", "date" to "2026-09-20", "outcome" to "ATTEMPT"))),
        )
        compose.onNodeWithText("Von Freunden").assertExists()
        compose.onNodeWithText("Ben: „Toe hook“").assertExists()
        compose.onNodeWithText("Ben: Crimp Line · 2026-09-20 · 4 Versuche").assertExists()
    }
}
