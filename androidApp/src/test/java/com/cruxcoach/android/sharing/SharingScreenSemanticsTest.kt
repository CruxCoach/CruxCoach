package com.cruxcoach.android.sharing

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithText
import com.cruxcoach.android.ui.sharing.SharingCategoryRow
import com.cruxcoach.android.ui.sharing.SharingExternalCopiesCard
import com.cruxcoach.android.ui.sharing.SharingCirclePicker
import com.cruxcoach.android.ui.sharing.SharingGateBanner
import com.cruxcoach.android.ui.sharing.SharingSigningBanner
import com.cruxcoach.android.ui.sharing.SharingWriteErrorMessage
import com.cruxcoach.android.ui.sharing.SharingInviteError
import com.cruxcoach.android.ui.sharing.sharingReasonText
import com.cruxcoach.android.sharing.PeerIdParseError
import com.cruxcoach.android.sharing.SharingWriteError
import com.cruxcoach.domain.sharing.AccessDecision
import com.cruxcoach.domain.sharing.AccessEffect
import com.cruxcoach.domain.sharing.DecisionSource
import com.cruxcoach.domain.sharing.NativeSharingGate
import com.cruxcoach.domain.sharing.NativeSharingGateState
import com.cruxcoach.domain.sharing.SharingCircle
import org.junit.Rule
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FEAT-062 §E: the accessibility-relevant half of the sharing screens, driven
 * against the real production composables.
 *
 * What is asserted is what a screen-reader user actually receives: one spoken
 * sentence per category row saying what it is, whether it is released and why,
 * and a readable, non-euphemistic reason for the locked native transport.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is @HiltAndroidApp and reaches for the
// Android Keystore on startup, which Robolectric has no implementation for.
// These tests render composables, not the app, so booting it buys nothing.
@Config(sdk = [34], qualifiers = "de", application = android.app.Application::class)
class SharingScreenSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun allow(source: DecisionSource, from: SharingCircle? = null) =
        AccessDecision(AccessEffect.ALLOW, source, from)

    private fun deny(source: DecisionSource) = AccessDecision(AccessEffect.DENY, source)

    @Test
    fun a_held_back_row_is_announced_as_one_sentence_with_its_reason() {
        compose.setContent {
            SharingCategoryRow(
                categoryLabel = "Videos",
                released = false,
                reason = sharingReasonText(
                    policyDecision = allow(DecisionSource.CIRCLE_BASELINE),
                    effectiveDecision = deny(DecisionSource.CONSENT_EXPANSION_PENDING),
                    peerCircle = SharingCircle.FRIENDS,
                ),
            )
        }

        compose.onNodeWithContentDescription(
            "Videos: Nicht freigegeben. Neue Kategorie – braucht erneut Zustimmung",
        ).assertIsDisplayed()
    }

    @Test
    fun a_released_row_names_the_wider_circle_it_was_inherited_from() {
        compose.setContent {
            SharingCategoryRow(
                categoryLabel = "Trainingsverlauf",
                released = true,
                reason = sharingReasonText(
                    policyDecision = allow(
                        DecisionSource.INHERITED_CIRCLE_BASELINE,
                        SharingCircle.ACQUAINTANCES,
                    ),
                    effectiveDecision = allow(DecisionSource.INHERITED_CIRCLE_BASELINE),
                    peerCircle = SharingCircle.FRIENDS,
                ),
            )
        }

        compose.onNodeWithContentDescription(
            "Trainingsverlauf: Freigegeben. Vom weiteren Kreis Bekannte geerbt",
        ).assertIsDisplayed()
    }

    @Test
    fun a_revoked_row_says_the_owner_withdrew_it() {
        compose.setContent {
            SharingCategoryRow(
                categoryLabel = "Gesundheitsdaten",
                released = false,
                reason = sharingReasonText(
                    policyDecision = allow(DecisionSource.PERSON_ALLOW),
                    effectiveDecision = deny(DecisionSource.RELATIONSHIP_REVOKED),
                    peerCircle = SharingCircle.FRIENDS,
                ),
            )
        }

        compose.onNodeWithContentDescription(
            "Gesundheitsdaten: Nicht freigegeben. Von dir zurückgezogen",
        ).assertIsDisplayed()
    }

    @Test
    fun the_locked_transport_banner_lists_readable_reasons() {
        val gate = NativeSharingGate.current() as NativeSharingGateState.Blocked
        compose.setContent { SharingGateBanner(gate) }

        compose.onNodeWithText("Vorschau – Live-Synchronisierung gesperrt").assertIsDisplayed()
        compose.onNodeWithText("Es gab keinen Testlauf auf einem echten Android-Gerät.")
            .assertIsDisplayed()
        compose.onNodeWithText(
            "Nicht über die Abhängigkeitsverwaltung dieser App installierbar.",
        ).assertIsDisplayed()
    }

    @Test
    fun the_banner_shows_the_upstream_revision_that_was_checked() {
        val gate = NativeSharingGate.current() as NativeSharingGateState.Blocked
        compose.setContent { SharingGateBanner(gate) }

        compose.onNodeWithText(
            NativeSharingGate.recordedEvidence.revision.take(12),
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun the_external_copies_warning_does_not_promise_what_it_cannot_do() {
        compose.setContent { SharingExternalCopiesCard() }

        compose.onNodeWithText("Was ein Widerruf nicht kann").assertIsDisplayed()
        compose.onNodeWithText("kann niemand zurückholen", substring = true).assertIsDisplayed()
    }

    @Test
    fun a_gate_reason_never_renders_as_a_raw_enum_name() {
        val gate = NativeSharingGate.current() as NativeSharingGateState.Blocked
        compose.setContent { SharingGateBanner(gate) }

        gate.reasons.forEach { reason ->
            compose.onNodeWithText(reason.name, substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun an_invite_error_is_announced_as_its_own_message() {
        compose.setContent {
            SharingInviteError(PeerIdParseError.NOT_A_PUBLIC_KEY)
        }

        // A red border tells a screen-reader user nothing; the reason has to be
        // readable text with its own description.
        compose.onNodeWithText(
            "Das ist ein Nostr-Code, aber kein öffentlicher Schlüssel.",
            substring = true,
        ).assertIsDisplayed()
        compose.onNodeWithContentDescription(
            "Ein privater Schlüssel (nsec) gehört hier niemals hinein.",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun every_invite_error_has_a_readable_german_message() {
        // One composition: the rule allows setContent only once, and rendering
        // them together is also closer to proving none of them falls back to an
        // enum name.
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                PeerIdParseError.entries.forEach { SharingInviteError(it) }
            }
        }

        PeerIdParseError.entries.forEach { error ->
            compose.onNodeWithText(error.name, substring = true).assertDoesNotExist()
        }
        compose.onNodeWithText("Bitte gib ein npub oder einen öffentlichen Schlüssel ein.")
            .assertIsDisplayed()
    }

    @Test
    fun every_circle_chip_is_reachable_and_labelled_in_german() {
        compose.setContent { SharingCirclePicker(selected = SharingCircle.ALL_OTHER_USERS, onSelect = {}) }

        listOf("Alle anderen Nutzer", "Bekannte", "Freunde").forEach { label ->
            compose.onNodeWithContentDescription(label).assertIsDisplayed()
        }
    }

    @Test
    fun the_selected_circle_is_the_one_reported_as_selected() {
        compose.setContent { SharingCirclePicker(selected = SharingCircle.FRIENDS, onSelect = {}) }

        compose.onNodeWithContentDescription("Freunde").assertIsSelected()
        compose.onNodeWithContentDescription("Bekannte").assertIsNotSelected()
    }

    @Test
    fun choosing_a_circle_reports_the_one_that_was_chosen() {
        var chosen: SharingCircle? = null
        compose.setContent {
            SharingCirclePicker(selected = SharingCircle.ALL_OTHER_USERS, onSelect = { chosen = it })
        }

        compose.onNodeWithContentDescription("Freunde").performClick()

        assertEquals(SharingCircle.FRIENDS, chosen)
    }

    @Test
    fun a_refused_write_is_announced_rather_than_swallowed() {
        compose.setContent { SharingWriteErrorMessage(SharingWriteError.SIGNER_UNAVAILABLE) }

        compose.onNodeWithText("Es wurde nichts gespeichert", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Es wurde nichts gespeichert", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun all_three_actions_of_a_category_row_stay_reachable() {
        // The row's chips wrap rather than sitting in a fixed Row: the German
        // "Kreis-Einstellung verwenden" is long, and a chip pushed off the
        // screen edge is one nobody can press.
        var allowed = false
        var denied = false
        var cleared = false
        compose.setContent {
            SharingCategoryRow(
                categoryLabel = "Gesundheitsdaten",
                released = false,
                reason = "Für diese Person gesperrt",
                onAllow = { allowed = true },
                onDeny = { denied = true },
                onClear = { cleared = true },
            )
        }

        compose.onNodeWithText("Erlauben", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Sperren", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Kreis-Einstellung verwenden", useUnmergedTree = true).performClick()

        assertTrue(allowed, "the allow chip must be reachable")
        assertTrue(denied, "the deny chip must be reachable")
        assertTrue(cleared, "the clear chip must be reachable")
    }

    // ------------------------------------------------ waiting for a signer

    /**
     * With an external signer a permission change is a prompt in another app,
     * and this screen goes quiet until it is answered. Silence is the wrong
     * answer for a screen reader, and for anyone wondering whether their tap
     * registered — so the wait is announced, and it says where to go and that
     * nothing else can be changed meanwhile.
     */
    @Test
    fun the_wait_for_an_external_signature_is_announced() {
        compose.setContent { SharingSigningBanner(signing = true) }

        compose.onNodeWithContentDescription(
            "Wird signiert. Bestätige die Anfrage in deiner Signer-App. " +
                "Solange sie offen ist, sind Änderungen hier gesperrt.",
        ).assertIsDisplayed()
    }

    @Test
    fun nothing_is_announced_when_no_signature_is_pending() {
        compose.setContent { SharingSigningBanner(signing = false) }

        compose.onNodeWithText("Wird signiert …").assertDoesNotExist()
    }

    /**
     * The message shown when a signature does not happen must not name a cause
     * it cannot know. It used to say external signing was unsupported, which is
     * no longer true; refused, timed out and unreachable are what it can be.
     */
    @Test
    fun a_failed_signature_is_explained_without_claiming_the_feature_is_missing() {
        compose.setContent { SharingWriteErrorMessage(SharingWriteError.SIGNER_UNAVAILABLE) }

        compose.onNodeWithText("Es wurde nichts gespeichert:", substring = true).assertIsDisplayed()
        compose.onNodeWithText("noch nicht", substring = true).assertDoesNotExist()
    }
}
