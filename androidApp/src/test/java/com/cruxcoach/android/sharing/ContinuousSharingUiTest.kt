package com.cruxcoach.android.sharing

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.cruxcoach.android.ui.sharing.ContinuousRecords
import com.cruxcoach.domain.sharing.SharingCategory
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The received-data product is readable data without canonical edit controls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de", application = Application::class)
class ContinuousSharingUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun received_notes_are_readable_without_an_edit_or_send_action() {
        compose.setContent { ContinuousRecords(listOf(ContinuousRecord("note:synthetic-route", SharingCategory.PRIVATE_NOTES, 1,
            mapOf("climbId" to "synthetic-route", "note" to "Synthetic current beta")))) }
        compose.onNodeWithText("Synthetic current beta").assertIsDisplayed()
        compose.onNodeWithText("synthetic-route").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }
    @Test fun current_profile_fields_are_rendered_as_data_not_a_protocol_stream() {
        compose.setContent { ContinuousRecords(listOf(ContinuousRecord("profile:active", SharingCategory.PROFILE_AND_GOALS, 2,
            mapOf("name" to "Synthetic climber", "boulderGrade" to "6C", "sportGrade" to "7a", "climbingYears" to "3",
                "sessionsPerWeek" to "2", "equipment" to "Synthetic board", "goals" to "Synthetic endurance")))) }
        compose.onNodeWithText("Synthetic climber").assertIsDisplayed()
        compose.onNodeWithText("Synthetic endurance", substring = true).assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }
}
