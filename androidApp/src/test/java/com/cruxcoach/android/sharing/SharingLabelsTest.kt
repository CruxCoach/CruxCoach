package com.cruxcoach.android.sharing

import com.cruxcoach.android.ui.sharing.SharingLabels
import com.cruxcoach.domain.sharing.DecisionSource
import com.cruxcoach.domain.sharing.NativeGateReason
import com.cruxcoach.domain.sharing.RelationshipStatus
import com.cruxcoach.domain.sharing.SharingCategory
import com.cruxcoach.domain.sharing.SharingCircle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FEAT-062 §11: every state the domain can be in has to be sayable in the UI.
 *
 * A missing label is not a cosmetic bug here — it is a screen that cannot
 * explain why something is blocked, which is the one thing these screens exist
 * to do. Exhaustive `when` plus these tests means adding a new enum value
 * cannot compile without a string to go with it.
 */
class SharingLabelsTest {

    @Test
    fun every_relationship_status_has_a_label() {
        RelationshipStatus.entries.forEach {
            assertTrue(SharingLabels.status(it) != 0, "no label for $it")
        }
    }

    @Test
    fun every_decision_source_has_a_label() {
        DecisionSource.entries.forEach {
            assertTrue(SharingLabels.source(it) != 0, "no label for $it")
        }
    }

    @Test
    fun every_category_has_a_label() {
        SharingCategory.entries.forEach {
            assertTrue(SharingLabels.category(it) != 0, "no label for $it")
        }
    }

    @Test
    fun every_circle_has_a_label() {
        SharingCircle.entries.forEach {
            assertTrue(SharingLabels.circle(it) != 0, "no label for $it")
        }
    }

    @Test
    fun every_gate_reason_has_a_label() {
        NativeGateReason.entries.forEach {
            assertTrue(SharingLabels.gateReason(it) != 0, "no label for $it")
        }
    }

    @Test
    fun distinct_states_do_not_share_one_label() {
        val statusLabels = RelationshipStatus.entries.map { SharingLabels.status(it) }
        assertEquals(statusLabels.size, statusLabels.distinct().size)

        val reasonLabels = NativeGateReason.entries.map { SharingLabels.gateReason(it) }
        assertEquals(reasonLabels.size, reasonLabels.distinct().size)
    }

    @Test
    fun the_two_sources_that_name_a_circle_are_marked_as_taking_an_argument() {
        assertTrue(SharingLabels.sourceTakesCircle(DecisionSource.CIRCLE_BASELINE))
        assertTrue(SharingLabels.sourceTakesCircle(DecisionSource.INHERITED_CIRCLE_BASELINE))
        assertTrue(!SharingLabels.sourceTakesCircle(DecisionSource.PERSON_DENY))
    }
}
