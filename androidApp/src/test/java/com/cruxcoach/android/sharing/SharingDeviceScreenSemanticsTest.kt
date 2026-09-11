package com.cruxcoach.android.sharing

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cruxcoach.android.ui.sharing.SharingPreviewRetryCard
import com.cruxcoach.android.ui.sharing.SharingDeviceRow
import com.cruxcoach.android.ui.sharing.SharingDeviceEstateBanner
import com.cruxcoach.android.ui.sharing.SharingRecoveryLockBanner
import com.cruxcoach.android.ui.sharing.SharingSovereignResetDialog
import com.cruxcoach.android.ui.sharing.sharingDeviceCapabilityText
import com.cruxcoach.android.ui.sharing.sharingDeviceRoleLabel
import com.cruxcoach.android.ui.sharing.sharingDeviceStateText
import com.cruxcoach.domain.sharing.AuthorityDeviceId
import com.cruxcoach.domain.sharing.DeviceCapability
import com.cruxcoach.domain.sharing.DeviceRole
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FEAT-062 §11: the device screen, as a German-speaking screen-reader user
 * actually receives it.
 *
 * The device list is where somebody finds out that one of their devices stopped
 * being able to change things — and *why*. A row that reads "Laptop, gesperrt"
 * and stops there is useless: the person needs to know that a restore fenced
 * it, and that re-enrolling is what fixes it. So every row is one spoken
 * sentence: which device, what it may do, and what is currently stopping it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de", application = android.app.Application::class)
class SharingDeviceScreenSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    private val laptop = AuthorityDeviceId("aaaa1111bbbb2222")

    // --------------------------------------------------------------- labels

    @Test
    fun every_role_has_a_name_a_person_can_act_on() {
        val names = mutableListOf<String>()
        compose.setContent { DeviceRole.entries.forEach { names += sharingDeviceRoleLabel(it) } }

        assertEquals(DeviceRole.entries.size, names.size)
        assertEquals(names.size, names.distinct().size, "two roles must not read the same")
        names.forEach { name ->
            assertTrue(name.isNotBlank())
            assertTrue(name.none { it == '_' }, "no enum names on screen: $name")
        }
    }

    @Test
    fun the_roles_read_as_what_they_let_you_do() {
        compose.setContent {
            androidx.compose.material3.Text(sharingDeviceRoleLabel(DeviceRole.PRIMARY))
        }
        compose.onNodeWithText("Hauptgerät").assertIsDisplayed()
    }

    @Test
    fun a_trusted_device_is_named_for_its_limit_not_its_rank() {
        compose.setContent {
            androidx.compose.material3.Text(sharingDeviceRoleLabel(DeviceRole.TRUSTED))
        }
        // Not "Zweitgerät": the useful fact is what it may not do.
        compose.onNodeWithText("Vertrautes Gerät").assertIsDisplayed()
    }

    @Test
    fun the_capabilities_are_spelled_out_rather_than_named() {
        val text = mutableListOf<String>()
        compose.setContent {
            text += sharingDeviceCapabilityText(
                setOf(DeviceCapability.READ, DeviceCapability.MUTATE_PERMISSIONS),
            )
        }

        assertTrue(text.single().contains("Berechtigungen ändern"), "got: ${text.single()}")
        assertTrue(!text.single().contains("MUTATE_PERMISSIONS"), "no enum names on screen")
    }

    @Test
    fun a_device_that_may_do_nothing_says_so_in_words() {
        val text = mutableListOf<String>()
        compose.setContent { text += sharingDeviceCapabilityText(emptySet()) }

        assertTrue(text.single().isNotBlank())
        assertTrue(text.single().contains("nichts") || text.single().contains("Nichts"), text.single())
    }

    // ---------------------------------------------------------- the states

    @Test
    fun a_fenced_device_says_a_restore_did_it_and_what_fixes_it() {
        val text = mutableListOf<String>()
        compose.setContent { text += sharingDeviceStateText(fenced = true, revoked = false) }

        // What locked it, and what unlocks it. Either half alone is useless.
        assertTrue(text.single().contains("Wiederherstellung"), text.single())
        assertTrue(text.single().contains("neu auf"), text.single())
    }

    @Test
    fun a_revoked_device_says_it_is_permanent() {
        val text = mutableListOf<String>()
        compose.setContent { text += sharingDeviceStateText(fenced = false, revoked = true) }

        val lower = text.single().lowercase()
        assertTrue(lower.contains("dauerhaft") || lower.contains("endgültig"), text.single())
        assertTrue(lower.contains("nie zurück"), "it has to say the revocation does not wear off")
    }

    // ------------------------------------------------------------ the row

    @Test
    fun a_row_is_announced_as_one_sentence() {
        compose.setContent {
            SharingDeviceRow(
                deviceLabel = "Laptop",
                roleLabel = "Vertrautes Gerät",
                capabilityText = "Darf Berechtigungen ändern",
                stateText = "",
                isThisDevice = false,
                canRevoke = true,
            )
        }

        compose.onNodeWithContentDescription(
            "Laptop: Vertrautes Gerät. Darf Berechtigungen ändern",
        ).assertIsDisplayed()
    }

    @Test
    fun a_fenced_row_carries_its_reason_into_the_same_sentence() {
        compose.setContent {
            SharingDeviceRow(
                deviceLabel = "Laptop",
                roleLabel = "Vertrautes Gerät",
                capabilityText = "Darf nichts ändern",
                stateText = "Durch eine Wiederherstellung gesperrt",
                isThisDevice = false,
                canRevoke = true,
            )
        }

        compose.onNodeWithContentDescription(
            "Laptop: Vertrautes Gerät. Darf nichts ändern. Durch eine Wiederherstellung gesperrt",
        ).assertIsDisplayed()
    }

    @Test
    fun this_device_is_marked_so_nobody_revokes_the_one_they_are_holding() {
        compose.setContent {
            SharingDeviceRow(
                deviceLabel = "Laptop",
                roleLabel = "Hauptgerät",
                capabilityText = "Darf alles",
                stateText = "",
                isThisDevice = true,
                canRevoke = true,
            )
        }

        compose.onNodeWithText("Dieses Gerät").assertIsDisplayed()
    }

    @Test
    fun revoking_is_offered_only_where_it_is_allowed() {
        compose.setContent {
            SharingDeviceRow(
                deviceLabel = "Laptop",
                roleLabel = "Vertrautes Gerät",
                capabilityText = "Darf Berechtigungen ändern",
                stateText = "",
                isThisDevice = false,
                canRevoke = false,
            )
        }

        compose.onNodeWithText("Widerrufen").assertIsNotEnabled()
    }

    @Test
    fun a_revoke_reaches_the_caller() {
        var revoked = false
        compose.setContent {
            SharingDeviceRow(
                deviceLabel = "Laptop",
                roleLabel = "Vertrautes Gerät",
                capabilityText = "Darf Berechtigungen ändern",
                stateText = "",
                isThisDevice = false,
                canRevoke = true,
                onRevoke = { revoked = true },
            )
        }

        compose.onNodeWithText("Widerrufen").assertIsEnabled().performClick()

        assertTrue(revoked)
    }

    // -------------------------------------------------------- the banners

    @Test
    fun an_install_with_no_device_is_told_what_to_do_rather_than_shown_a_blank() {
        compose.setContent { SharingDeviceEstateBanner(needsEnrolment = true, authorityGeneration = 0) }

        compose.onNodeWithContentDescription(
            "Noch kein Gerät aufgenommen. Bis dahin kann dieses Gerät keine Berechtigungen ändern. " +
                "Nimm es mit deinem Hauptschlüssel auf.",
        ).assertIsDisplayed()
    }

    @Test
    fun the_generation_is_shown_so_a_fencing_can_be_explained() {
        compose.setContent { SharingDeviceEstateBanner(needsEnrolment = false, authorityGeneration = 4) }

        compose.onNodeWithText("Vollmacht-Generation 4").assertIsDisplayed()
    }

    @Test
    fun a_recovery_lock_says_what_it_blocks_and_how_to_clear_it() {
        compose.setContent { SharingRecoveryLockBanner(reason = "Die Sicherung ist möglicherweise veraltet") }

        compose.onNodeWithContentDescription(
            "Nur-Lese-Vorschau: Die Sicherung ist möglicherweise veraltet. " +
                "Berechtigungen lassen sich ansehen, aber nicht ändern. " +
                "Synchronisiere dieses Gerät, oder starte einen souveränen Reset.",
        ).assertIsDisplayed()
    }

    // -------------------------------------------------- the reset warning

    @Test
    fun the_sovereign_reset_names_everything_it_destroys_before_asking() {
        compose.setContent { SharingSovereignResetDialog(onConfirm = {}, onDismiss = {}) }

        val warning = compose.onNodeWithContentDescription(
            "Souveräner Reset. Alle bisherigen Geräte verlieren ihre Rechte dauerhaft. " +
                "Alle Freigaben werden widerrufen. Alle Schlüssel werden erneuert. " +
                "Das lässt sich nicht rückgängig machen.",
        )
        warning.assertIsDisplayed()
    }

    @Test
    fun the_reset_is_confirmed_deliberately_rather_than_by_the_default_button() {
        var confirmed = false
        compose.setContent {
            SharingSovereignResetDialog(onConfirm = { confirmed = true }, onDismiss = {})
        }

        compose.onNodeWithText("Abbrechen").assertIsEnabled()
        compose.onNodeWithText("Alles zurücksetzen").performClick()

        assertTrue(confirmed)
    }

    // ------------------------------------------------- the preview retry

    @Test
    fun the_preview_names_the_way_forward_rather_than_leaving_a_dead_end() {
        compose.setContent { SharingPreviewRetryCard(onConfirm = {}, onDismiss = {}) }

        compose.onNodeWithContentDescription(
            "Von dieser Sicherung liess sich nicht belegen, dass sie aktuell ist — sie ist lesbar, " +
                "aber es kann nicht aus ihr geschrieben werden. Soll dieses Gerät trotzdem übernehmen, " +
                "kannst du mit derselben Datei und demselben Code alles zurücksetzen.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Mit souveränem Reset übernehmen").assertIsEnabled()
    }

    /** Nothing happens until the price has been shown and accepted. */
    @Test
    fun the_action_asks_before_it_does_anything() {
        var confirmed = false
        compose.setContent { SharingPreviewRetryCard(onConfirm = { confirmed = true }, onDismiss = {}) }

        compose.onNodeWithText("Mit souveränem Reset übernehmen").performClick()

        assertTrue(!confirmed, "the button opens the warning, it does not act")
        compose.onNodeWithContentDescription(
            "Souveräner Reset. Alle bisherigen Geräte verlieren ihre Rechte dauerhaft. " +
                "Alle Freigaben werden widerrufen und müssen jeder Person neu erteilt werden. " +
                "Alle Schlüssel werden erneuert. Kopien, die andere bereits heruntergeladen haben, " +
                "bleiben bei ihnen. Das lässt sich nicht rückgängig machen.",
        ).assertIsDisplayed()
    }

    @Test
    fun confirming_reaches_the_caller_once() {
        var confirms = 0
        compose.setContent { SharingPreviewRetryCard(onConfirm = { confirms++ }, onDismiss = {}) }

        compose.onNodeWithText("Mit souveränem Reset übernehmen").performClick()
        compose.onNodeWithText("Zurücksetzen und übernehmen").performClick()

        assertEquals(1, confirms)
    }

    @Test
    fun keeping_read_only_does_nothing_but_close_the_dialog() {
        var confirmed = false
        var dismissed = false
        compose.setContent {
            SharingPreviewRetryCard(onConfirm = { confirmed = true }, onDismiss = { dismissed = true })
        }

        compose.onNodeWithText("Mit souveränem Reset übernehmen").performClick()
        compose.onNodeWithText("Weiter nur lesen").performClick()

        assertTrue(!confirmed, "cancelling must not reset anything")
        assertTrue(dismissed)
    }
}
