package com.cruxcoach.android.ui.sharing

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.cruxcoach.android.sharing.CategoryChoice
import com.cruxcoach.android.sharing.Completeness
import com.cruxcoach.android.sharing.Delivery
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
import com.cruxcoach.android.sharing.SharingRecord
import com.cruxcoach.android.sharing.VisibleState
import com.cruxcoach.android.ui.settings.SharingSettingsContent
import com.cruxcoach.domain.sharing.AccessEffect
import com.cruxcoach.domain.sharing.ShareCategory
import com.cruxcoach.domain.sharing.ShareDecisionSource
import com.cruxcoach.domain.sharing.ShareRule
import com.cruxcoach.domain.sharing.ShareTemplates
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The sharing screens: four states, one switch, templates, visible exceptions, one confirmation, the age question. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de")
class SharingScreenTest {
    @get:Rule val compose = createComposeRule()

    private val me = "e".repeat(64)
    private val calls = mutableListOf<String>()
    private val actions = SharingActions(
        accept = { peer, template, outgoing -> calls += "accept:${peer.take(4)}:${template.value}:$outgoing" },
        decline = { calls += "decline:${it.take(4)}" },
        setOutgoing = { peer, on -> calls += "outgoing:${peer.take(4)}:$on" },
        end = { calls += "end:${it.take(4)}" },
        request = { typed, template, _, _ -> calls += "request:$typed:${template.value}" },
        setCategory = { _, category, on -> calls += "category:$category:$on" },
        setItem = { _, item, on -> calls += "item:${item.record.id}:$on" },
        putTemplate = { calls += "template:${it.id.value}:${it.categories.sorted()}" },
        setAge = { calls += "age:$it" },
    )

    private fun person(peer: String, state: PersonState, outgoing: Boolean = true, label: String? = null) =
        SharingPerson(peer, ShareTemplates.TRAINING_PARTNER.id, outgoing, null, label, state, false, 1, null)

    private fun native(peer: String, state: String) = MarmotPeer(peer, "g", state, true, 1, 0, 0, 0, "")

    private fun friend(peer: String, visible: VisibleState, label: String, state: PersonState = PersonState.CONNECTED, outgoing: Boolean = true) =
        SharingFriend(person(peer, state, outgoing, label), native(peer, if (visible == VisibleState.PENDING) "pending" else "active"),
            visible, null, ShareScope.EMPTY, if (visible == VisibleState.ACTIVE) 3 else 0, 0)

    private val partner = ShareTemplates.TRAINING_PARTNER.copy(categories = setOf(ShareCategory.CLIMBING_PROFILE, ShareCategory.CLIMB_NOTES))

    private fun overview(
        friends: List<SharingFriend> = emptyList(),
        invitations: List<SharingInvitation> = emptyList(),
        ageAnswered: Boolean = true,
        ageConfirmed: Boolean = true,
    ) = SharingOverview(
        MarmotStatus(me, true, MarmotDiscovery(true, 0), MarmotLocal(0, 0, 2), emptyList(), emptyList(), true, 1),
        listOf(partner), friends, invitations, ageAnswered, ageConfirmed, listOf(partner, ShareTemplates.FAMILY, ShareTemplates.COACH),
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
        compose.onNodeWithText("Ausstehend · Trainingspartner").assertExists()
        compose.onNodeWithText("Aktiv · Trainingspartner · 3 erhalten").assertExists()
        compose.onNodeWithText("Gestoppt · Trainingspartner").assertExists()
        compose.onNodeWithText("Beendet · Trainingspartner").assertExists()
        compose.onNodeWithText("Ben").performScrollTo().performClick()
        assertEquals(listOf("open:person:bbbb"), calls)
        compose.onNodeWithText("2 Nachrichten warten auf ein Relay", substring = true).assertExists()
    }

    @Test fun `an invitation says what would be shared and accepting passes the chosen switch`() {
        val peer = "f".repeat(64)
        render(SharingUiState(loading = false, overview = overview(invitations = listOf(SharingInvitation(peer, 1)))), SharingPage.Overview)
        // "Share my data too" is off by default; switching it on shows what would go out.
        compose.onNodeWithText("Du würdest nur empfangen.").assertExists()
        compose.onNodeWithTag("sharing_invitation_share").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithText("Du würdest teilen: Profil & Ziele, Notizen").assertExists()
        compose.onNodeWithTag("sharing_accept").performScrollTo().performClick()
        assertEquals(listOf("accept:ffff:TRAINING_PARTNER:true"), calls)
    }

    @Test fun `a refused identity is explained and an empty field cannot be sent`() {
        render(SharingUiState(loading = false, overview = overview(), requestError = RequestError.NOT_A_PUBLIC_KEY), SharingPage.Overview)
        compose.onNodeWithText("Das ist keine öffentliche Kennung. Gib niemals eine nsec weiter.").assertExists()
        compose.onNodeWithTag("sharing_add_send").assertIsNotEnabled()
        compose.onNodeWithTag("sharing_add_id").performScrollTo().performTextInput("npub1example")
        compose.onNodeWithTag("sharing_add_send").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("request:npub1example:TRAINING_PARTNER"), calls)
    }

    private fun detail(outgoing: Boolean = true, visible: VisibleState = VisibleState.ACTIVE, state: PersonState = PersonState.CONNECTED): SharingPersonDetail {
        val peer = "b".repeat(64)
        val note = SharingRecord("note:c1", ShareCategory.CLIMB_NOTES, 1, mapOf("climbId" to "c1", "note" to "Heel hook left"))
        val profile = SharingRecord("profile:active", ShareCategory.CLIMBING_PROFILE, 1,
            mapOf("name" to "Me", "boulderGrade" to "7A", "sportGrade" to "", "climbingYears" to "3", "sessionsPerWeek" to "2", "equipment" to "", "goals" to ""))
        return SharingPersonDetail(
            friend = friend(peer, visible, "Ben", state, outgoing),
            template = partner,
            categories = mapOf(
                ShareCategory.CLIMBING_PROFILE to CategoryChoice(true, true, null, ShareDecisionSource.TEMPLATE),
                ShareCategory.SENDS to CategoryChoice(false, false, null, ShareDecisionSource.NO_GRANT),
                ShareCategory.ATTEMPTS to CategoryChoice(false, false, null, ShareDecisionSource.NO_GRANT),
                ShareCategory.CLIMB_NOTES to CategoryChoice(false, true, ShareRule(AccessEffect.DENY), ShareDecisionSource.PERSON_DENY),
            ),
            periodDays = 30,
            items = listOf(PreviewItem(profile, true, null), PreviewItem(note, true, ShareRule(AccessEffect.ALLOW))),
            received = listOf(note.copy(fields = note.fields + ("note" to "Ben's beta"))),
        )
    }

    private fun renderPerson(detail: SharingPersonDetail) =
        render(SharingUiState(loading = false, overview = overview(listOf(detail.friend)), detail = detail), SharingPage.Person(detail.friend.peer))

    @Test fun `the person page has one switch, visible exceptions and the exact preview`() {
        renderPerson(detail())
        compose.onNodeWithTag("sharing_person_state").assertTextEquals("Aktiv")
        compose.onNodeWithText("Ausnahme: weicht von der Vorlage „Trainingspartner“ ab").assertExists()
        compose.onNodeWithText("„Heel hook left“").assertExists()
        compose.onNodeWithText("Ausnahme für diesen Eintrag").assertExists()
        compose.onNodeWithText("„Ben's beta“").assertExists()
        compose.onNodeWithTag("sharing_person_switch").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithTag("sharing_category_CLIMB_NOTES").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithText("„Heel hook left“").performScrollTo().performClick()
        assertEquals(listOf("outgoing:bbbb:false", "category:CLIMB_NOTES:true", "item:note:c1:false"), calls)
    }

    @Test fun `limits are said on the person page instead of silently sending nothing`() {
        renderPerson(detail().copy(overLimit = true, skipped = 2))
        compose.onNodeWithTag("sharing_limit_over").performScrollTo()
            .assertTextEquals("Zu viel zum Senden (mehr als 1.000 Einträge). An diese Person geht nichts, bis du einen kürzeren Zeitraum wählst oder eine Kategorie ausschaltest.")
        compose.onNodeWithTag("sharing_limit_skipped").performScrollTo()
            .assertTextEquals("2 Einträge können nicht geteilt werden (zu lang oder unvollständig).")
    }

    @Test fun `within the limits no limit hint is shown`() {
        renderPerson(detail())
        compose.onNodeWithTag("sharing_limit_over").assertDoesNotExist()
        compose.onNodeWithTag("sharing_limit_skipped").assertDoesNotExist()
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

    @Test fun `a template page edits the template for everyone on it`() {
        render(SharingUiState(loading = false, overview = overview()), SharingPage.Template(partner.id))
        compose.onNodeWithTag("sharing_template_category_CLIMB_NOTES").assertIsOn()
        compose.onNodeWithTag("sharing_template_category_SENDS").performScrollTo().assertIsOff().performClick()
        assertEquals(listOf("template:TRAINING_PARTNER:[CLIMBING_PROFILE, SENDS, CLIMB_NOTES]"), calls)
    }

    @Test fun `the overview offers only visible templates`() {
        render(SharingUiState(loading = false, overview = overview()), SharingPage.Overview)
        compose.onNodeWithTag("sharing_template_TRAINING_PARTNER").performScrollTo().assertExists()
        compose.onNodeWithTag("sharing_template_FAMILY").assertDoesNotExist()
        compose.onNodeWithTag("sharing_template_COACH").assertDoesNotExist()
    }

    @Test fun `nothing can be shared before the age question is answered`() {
        render(SharingUiState(loading = false, overview = overview(ageAnswered = false, ageConfirmed = false)), SharingPage.Overview)
        compose.onNodeWithText("Bist du 16 oder älter?").assertExists()
        compose.onNodeWithTag("sharing_add_send").assertDoesNotExist()
        compose.onNodeWithTag("sharing_age_yes").performClick()
        assertEquals(listOf("age:true"), calls)
    }

    @Test fun `under sixteen sharing stays off and the answer can be changed`() {
        val peer = "f".repeat(64)
        render(SharingUiState(loading = false, overview = overview(invitations = listOf(SharingInvitation(peer, 1)),
            ageAnswered = true, ageConfirmed = false)), SharingPage.Overview)
        compose.onNodeWithTag("sharing_age_under").assertExists()
        compose.onNodeWithTag("sharing_add_send").assertDoesNotExist()
        compose.onNodeWithTag("sharing_accept").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("sharing_age_change").performScrollTo().performClick()
        assertEquals(listOf("age:true"), calls)
    }

    @Test fun `the history page lists own decisions with names`() {
        val ben = "b".repeat(64)
        val history = listOf(
            com.cruxcoach.android.sharing.HistoryEntry(2, 1_800_000_000_000, ben, com.cruxcoach.android.sharing.HistoryEvent.OUTGOING, "off"),
            com.cruxcoach.android.sharing.HistoryEntry(1, 1_799_000_000_000, ben, com.cruxcoach.android.sharing.HistoryEvent.CATEGORY_RULE, "SENDS:ALLOW"),
        )
        render(SharingUiState(loading = false, overview = overview(listOf(friend(ben, VisibleState.STOPPED, "Ben", outgoing = false))).copy(history = history)),
            SharingPage.History)
        compose.onNodeWithText("Teilen mit Ben ausgeschaltet").assertExists()
        compose.onNodeWithText("Ben: Begehungen geteilt").assertExists()
    }

    @Test fun `the climb page says who sees own records and offers never share`() {
        val toggles = mutableListOf<String>()
        val note = SharingRecord("note:c1", ShareCategory.CLIMB_NOTES, 1, mapOf("climbId" to "c1", "note" to "Heel hook"))
        val send = SharingRecord("ascent:a", ShareCategory.SENDS, 1, mapOf("climbId" to "c1", "name" to "Crimp Line", "board" to "kilter",
            "angle" to "40", "attempts" to "2", "grade" to "18", "date" to "2026-09-20"))
        compose.setContent {
            MaterialTheme {
                OwnClimbContent(listOf(
                    com.cruxcoach.android.sharing.OwnOnClimb(note, listOf("Ben", "Cleo"), never = false),
                    com.cruxcoach.android.sharing.OwnOnClimb(send, emptyList(), never = true),
                )) { id, never -> toggles += "$id:$never" }
            }
        }
        compose.onNodeWithText("Wer sieht deine Daten zu diesem Boulder?").assertExists()
        compose.onNodeWithText("Sichtbar für: Ben, Cleo").assertExists()
        compose.onNodeWithText("Wird nie geteilt").assertExists()
        compose.onNodeWithTag("boarddetail_never_note:c1").assertIsOff().performClick()
        assertEquals(listOf("note:c1:true"), toggles)
    }

    @Test fun `who sees what lists people per category with the reason`() {
        val ben = "b".repeat(64)
        val whoSees = listOf(
            com.cruxcoach.android.sharing.CategoryAudience(ShareCategory.CLIMB_NOTES, listOf(
                com.cruxcoach.android.sharing.AudienceEntry(ben, "Ben", ShareDecisionSource.TEMPLATE, null))),
            com.cruxcoach.android.sharing.CategoryAudience(ShareCategory.SENDS, emptyList()),
        )
        render(SharingUiState(loading = false, overview = overview().copy(whoSees = whoSees)), SharingPage.WhoSees)
        compose.onNodeWithText("Ben (Vorlage)").assertExists()
        compose.onNodeWithTag("sharing_whosees_nobody_SENDS").assertExists()
    }

    @Test fun `the climb page lists what friends shared about it and nothing otherwise`() {
        val shown = mutableStateOf(emptyList<ReceivedOnClimb>())
        compose.setContent { MaterialTheme { ReceivedClimbContent(shown.value) } }
        compose.onNodeWithTag("boarddetail_received").assertDoesNotExist()
        shown.value = listOf(
            ReceivedOnClimb("b".repeat(64), "Ben", SharingRecord("note:c1", ShareCategory.CLIMB_NOTES, 1, mapOf("climbId" to "c1", "note" to "Toe hook"))),
            ReceivedOnClimb("b".repeat(64), "Ben", SharingRecord("bid:x", ShareCategory.ATTEMPTS, 1, mapOf("climbId" to "c1",
                "name" to "Crimp Line", "board" to "kilter", "angle" to "40", "attempts" to "4", "date" to "2026-09-20"))),
        )
        compose.onNodeWithText("Von Freunden").assertExists()
        compose.onNodeWithText("Ben: „Toe hook“").assertExists()
        compose.onNodeWithText("Ben: Crimp Line · 2026-09-20 · 4 Versuche").assertExists()
    }

    @Test fun `the unfinished feature shows only its switch until it is turned on`() {
        val enabled = mutableStateOf(false)
        val toggles = mutableListOf<Boolean>()
        compose.setContent {
            MaterialTheme {
                SharingSettingsContent(enabled.value, onEnabledChange = { toggles += it; enabled.value = it },
                    onNavigateToSharing = { calls += "open" })
            }
        }
        compose.onNodeWithTag("settings_sharing_preview").assertExists()
        compose.onNodeWithTag("settings_open_personal_sharing").assertDoesNotExist()
        compose.onNodeWithText("Vorschau: Persönliche Daten teilen").performClick()
        assertEquals(listOf(true), toggles)
        compose.onNodeWithTag("settings_open_personal_sharing").assertExists().performClick()
        assertEquals(listOf("open"), calls)
    }

    private val hour = 60 * 60_000L

    private fun detailWith(completeness: Completeness, delivery: Delivery?) =
        detail().let { it.copy(friend = it.friend.copy(completeness = completeness, delivery = delivery)) }

    @Test fun `a recent complete state is up to date and our share is confirmed`() {
        val now = System.currentTimeMillis()
        renderPerson(detailWith(Completeness.Complete(now - hour), Delivery(now - 2 * hour, null)))
        compose.onNodeWithTag("sharing_received_state").performScrollTo().assertTextContains("Aktuell · Stand ", substring = true)
        compose.onNodeWithTag("sharing_confirmed").assertTextContains("Bestätigt ", substring = true)
    }

    @Test fun `an old complete state says nothing new came and a late confirmation says so`() {
        val now = System.currentTimeMillis()
        renderPerson(detailWith(Completeness.Complete(now - 72 * hour), Delivery(now - 72 * hour, now - 72 * hour)))
        compose.onNodeWithTag("sharing_received_state").performScrollTo().assertTextContains("seitdem nichts Neues von Ben", substring = true)
        compose.onNodeWithTag("sharing_confirmed").assertTextContains("die neuesten Änderungen warten noch", substring = true)
    }

    @Test fun `a known gap is never up to date and an old one says when it arrives`() {
        renderPerson(detailWith(Completeness.Filling(System.currentTimeMillis() - 48 * hour), Delivery(0, 1)))
        compose.onNodeWithTag("sharing_received_state").performScrollTo()
            .assertTextContains("Unvollständig seit ", substring = true)
            .assertTextContains("wird nachgeliefert, sobald Ben online ist", substring = true)
        compose.onNodeWithTag("sharing_confirmed").assertTextEquals("Noch nicht bestätigt")
    }

    @Test fun `a fresh gap is being completed`() {
        renderPerson(detailWith(Completeness.Filling(System.currentTimeMillis() - hour), null))
        compose.onNodeWithTag("sharing_received_state").performScrollTo().assertTextEquals("Wird vervollständigt …")
        compose.onNodeWithTag("sharing_confirmed").assertDoesNotExist()
    }

    @Test fun `who sees this says how many friends have what we share`() {
        val now = System.currentTimeMillis()
        val friends = listOf(
            friend("b".repeat(64), VisibleState.ACTIVE, "Ben").copy(delivery = Delivery(now - hour, null)),
            friend("c".repeat(64), VisibleState.ACTIVE, "Cleo").copy(delivery = Delivery(now - 72 * hour, now - 72 * hour)),
            friend("d".repeat(64), VisibleState.STOPPED, "Dana", outgoing = false),
        )
        render(SharingUiState(loading = false, overview = overview(friends)), SharingPage.WhoSees)
        compose.onNodeWithTag("sharing_delivery").assertTextContains("Zugestellt an 1 von 2 · 1 wartet (seit ", substring = true)
    }
}
