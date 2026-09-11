package com.cruxcoach.domain.sharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * FEAT-062 §D: the native live-sharing path is off unless every upstream gate
 * is *evidenced* green. The gate is data-driven so the reason it is closed can
 * be shown to a user and checked by a test, rather than living in a comment.
 */
class NativeSharingGateTest {

    private val allGreen = NativeUpstreamEvidence(
        repository = "https://github.com/marmot-protocol/mdk",
        revision = "eed8bc6e4952898c32f7e312bda69169936f9beb",
        crossSeamConvergenceUnified = true,
        publishedArtifactChecksumVerified = true,
        integratedViaDependencyStrategy = true,
        onDeviceInstrumentationRun = true,
        durableProjectionGateClosed = true,
        outboundResumeCorrelationFixed = true,
    )

    @Test
    fun a_fully_evidenced_upstream_opens_the_live_path() {
        assertIs<NativeSharingGateState.Live>(NativeSharingGate.evaluate(allGreen))
    }

    @Test
    fun absent_evidence_is_blocked_rather_than_assumed_green() {
        val state = NativeSharingGate.evaluate(null)
        assertIs<NativeSharingGateState.Blocked>(state)
        assertTrue(NativeGateReason.NO_EVIDENCE in state.reasons)
    }

    @Test
    fun every_unmet_condition_is_named_individually() {
        val state = NativeSharingGate.evaluate(
            allGreen.copy(
                onDeviceInstrumentationRun = false,
                durableProjectionGateClosed = false,
            )
        )
        assertIs<NativeSharingGateState.Blocked>(state)
        assertTrue(NativeGateReason.NO_ON_DEVICE_INSTRUMENTATION_RUN in state.reasons)
        assertTrue(NativeGateReason.DURABLE_PROJECTION_GATE_OPEN in state.reasons)
        assertFalse(NativeGateReason.CROSS_SEAM_CONVERGENCE_DIVERGENT in state.reasons)
    }

    @Test
    fun a_single_unmet_condition_is_enough_to_block() {
        val state = NativeSharingGate.evaluate(allGreen.copy(integratedViaDependencyStrategy = false))
        assertIs<NativeSharingGateState.Blocked>(state)
        assertEquals(listOf(NativeGateReason.NOT_INTEGRATED_VIA_DEPENDENCY_STRATEGY), state.reasons)
    }

    @Test
    fun this_build_is_blocked_and_says_why() {
        val state = NativeSharingGate.current()
        assertIs<NativeSharingGateState.Blocked>(state)
        assertTrue(state.reasons.isNotEmpty())
        assertTrue(state.evidence.revision.isNotBlank())
    }

    @Test
    fun this_build_records_that_upstream_did_unify_the_two_resolution_seams() {
        // Reproduced against marmot-protocol/mdk: `ForkRecovered` and the
        // pairwise fork-recovery seam are gone from the engine as of #1293.
        // Recording it honestly matters as much as the block does.
        assertTrue(NativeSharingGate.recordedEvidence.crossSeamConvergenceUnified)
    }

    @Test
    fun this_build_is_blocked_on_integration_and_on_device_evidence() {
        val state = assertIs<NativeSharingGateState.Blocked>(NativeSharingGate.current())
        assertTrue(NativeGateReason.NOT_INTEGRATED_VIA_DEPENDENCY_STRATEGY in state.reasons)
        assertTrue(NativeGateReason.NO_ON_DEVICE_INSTRUMENTATION_RUN in state.reasons)
    }

    @Test
    fun a_blocked_gate_never_reports_itself_as_live() {
        assertFalse(NativeSharingGate.current().isLive)
    }
}

/**
 * The adapter seam. While the gate is blocked there is exactly one
 * implementation and it refuses every call — loudly, and without pretending
 * anything was delivered.
 */
class BlockedNativeSharingAdapterTest {

    private val adapter = BlockedNativeSharingAdapter(NativeSharingGate.current())

    @Test
    fun publishing_is_refused_while_the_gate_is_blocked() {
        val result = adapter.publish(PeerId("npub1alice"), payload = byteArrayOf(1, 2, 3))
        assertIs<NativeSharingResult.Refused>(result)
        assertTrue(result.reasons.isNotEmpty())
    }

    @Test
    fun a_refusal_is_never_mistaken_for_a_delivery() {
        val result = adapter.publish(PeerId("npub1alice"), payload = byteArrayOf())
        assertFalse(result is NativeSharingResult.Delivered)
    }
}
