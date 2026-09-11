package com.cruxcoach.android.sharing

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cruxcoach.android.ui.sharing.SharingPurgeConfirmDialog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * FEAT-062 §9: "Lokal löschen" asks first.
 *
 * The button ran the deletion straight from the tap. It is irreversible, it is
 * next to buttons that are not, and it is the one action on the screen that
 * throws away a signed history — so the one thing it must not be is a
 * mis-tap.
 *
 * The dialog also has a job beyond confirming: it is where the *scope* gets
 * said out loud. "Delete" on a sharing screen reads as "revoke their access",
 * and it is not that — it removes what this device stores about the
 * relationship, leaves the owner's own content alone, and cannot reach a copy
 * the other person already has. Somebody who reads only this dialog should not
 * come away with the wrong idea about any of those.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is @HiltAndroidApp and reaches for the
// Android Keystore on startup, which Robolectric has no implementation for.
@Config(sdk = [34], qualifiers = "de", application = android.app.Application::class)
class SharingPurgeConfirmDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private var confirmed = 0
    private var dismissed = 0

    private fun show() {
        compose.setContent {
            SharingPurgeConfirmDialog(
                onConfirm = { confirmed++ },
                onDismiss = { dismissed++ },
            )
        }
    }

    // ------------------------------------------------------------- opening

    @Test
    fun the_dialog_names_the_action_and_offers_both_ways_out() {
        show()

        compose.onNodeWithText("Daten dieser Person löschen?").assertIsDisplayed()
        compose.onNodeWithText("Endgültig löschen").assertIsDisplayed()
        compose.onNodeWithText("Abbrechen").assertIsDisplayed()
    }

    @Test
    fun merely_opening_it_deletes_nothing() {
        show()

        assertEquals(0, confirmed, "showing a confirmation must not perform the action")
        assertEquals(0, dismissed)
    }

    // ---------------------------------------------------------- cancelling

    @Test
    fun cancelling_performs_no_deletion() {
        show()

        compose.onNodeWithText("Abbrechen").performClick()

        assertEquals(0, confirmed, "cancel must never delete")
        assertEquals(1, dismissed)
    }

    // --------------------------------------------------------- confirming

    @Test
    fun confirming_performs_the_deletion_once() {
        show()

        compose.onNodeWithText("Endgültig löschen").performClick()

        assertEquals(1, confirmed)
        assertEquals(0, dismissed, "confirming is not also a dismissal")
    }

    // ------------------------------------------------------ accessibility

    /**
     * One spoken sentence covering scope *and* irreversibility. Split across
     * two unlabelled paragraphs a screen reader gives them as fragments, and
     * the half that says "this cannot be undone" is the half most likely to be
     * skipped.
     */
    @Test
    fun the_whole_warning_is_announced_as_one_description() {
        show()

        compose.onNodeWithContentDescription(
            "Daten dieser Person löschen? " +
                "Gelöscht wird alles, was dieses Gerät über diese Beziehung gespeichert hat: " +
                "der signierte Verlauf, die Freigabe-Regeln und die Geräte dieser Person. " +
                "Deine eigenen Inhalte und Schlüssel bleiben unberührt, und andere Beziehungen ändern sich nicht. " +
                "Das lässt sich nicht rückgängig machen. " +
                "Kopien, die diese Person bereits erhalten, exportiert oder fotografiert hat, " +
                "erreicht das Löschen nicht.",
        ).assertIsDisplayed()
    }

    @Test
    fun the_scope_is_stated_rather_than_left_to_be_guessed() {
        show()

        // Says what survives, not only what goes.
        compose.onNodeWithText("Deine eigenen Inhalte und Schlüssel bleiben unberührt", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("lässt sich nicht rückgängig machen", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("bereits erhalten, exportiert oder fotografiert", substring = true)
            .assertIsDisplayed()
    }
}
