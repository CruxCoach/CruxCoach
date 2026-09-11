package com.cruxcoach.domain.sharing

/**
 * FEAT-062 §D: the fail-closed gate in front of native (Marmot/MLS) live
 * sharing.
 *
 * The rule is deliberately inverted from the usual feature flag: the live path
 * is off unless *every* condition below is evidenced green. Evidence is data,
 * not a comment, so the reason the gate is shut can be rendered in the UI and
 * asserted in a test.
 *
 * See `docs/specs/0.2.3/FEAT-062-personal-information-sharing.md` for how each
 * field of [recordedEvidence] was established.
 */
data class NativeUpstreamEvidence(
    val repository: String,
    val revision: String,
    /**
     * Upstream resolves same-epoch forks through **one** seam.
     *
     * The old pin had two — a pairwise fork-recovery fast path and
     * witness-aware stored convergence — which could settle on different
     * canonical branches for one fork and stay there.
     */
    val crossSeamConvergenceUnified: Boolean,
    /** The published binary's SHA-256 matched its published checksum. */
    val publishedArtifactChecksumVerified: Boolean,
    /** Consumable through this repository's existing dependency strategy. */
    val integratedViaDependencyStrategy: Boolean,
    /** An instrumentation run actually executed on an Android arm64 device. */
    val onDeviceInstrumentationRun: Boolean,
    /** A retention-independent durable projection and acknowledged inbox exist. */
    val durableProjectionGateClosed: Boolean,
    /** A send whose first accepted delivery lands during resume is correlated. */
    val outboundResumeCorrelationFixed: Boolean,
) {
    companion object {
        /** Used when there is no evidence at all. */
        val NONE = NativeUpstreamEvidence(
            repository = "",
            revision = "",
            crossSeamConvergenceUnified = false,
            publishedArtifactChecksumVerified = false,
            integratedViaDependencyStrategy = false,
            onDeviceInstrumentationRun = false,
            durableProjectionGateClosed = false,
            outboundResumeCorrelationFixed = false,
        )
    }
}

/** Why the native path is closed. Each value maps to one user-visible line. */
enum class NativeGateReason {
    NO_EVIDENCE,
    CROSS_SEAM_CONVERGENCE_DIVERGENT,
    ARTIFACT_CHECKSUM_UNVERIFIED,
    NOT_INTEGRATED_VIA_DEPENDENCY_STRATEGY,
    NO_ON_DEVICE_INSTRUMENTATION_RUN,
    DURABLE_PROJECTION_GATE_OPEN,
    OUTBOUND_RESUME_CORRELATION_UNFIXED,
}

sealed interface NativeSharingGateState {
    val isLive: Boolean
    val evidence: NativeUpstreamEvidence

    data class Live(override val evidence: NativeUpstreamEvidence) : NativeSharingGateState {
        override val isLive: Boolean get() = true
    }

    data class Blocked(
        val reasons: List<NativeGateReason>,
        override val evidence: NativeUpstreamEvidence,
    ) : NativeSharingGateState {
        override val isLive: Boolean get() = false
    }
}

object NativeSharingGate {

    /**
     * What was actually established for this build, on 2026-08-16, against
     * `marmot-protocol/mdk`.
     *
     * Two things are true at once and both are recorded honestly:
     *
     *  - upstream **did** fix the cross-seam divergence. `ForkRecovered` and the
     *    pairwise fork-recovery route are deleted from the engine as of
     *    "Unify same-epoch fork resolution through distributed convergence"
     *    (#1293), which is contained in the published `v0.9.12`; the published
     *    Android artifact's SHA-256 matches its published checksum and its
     *    manifest names the same source revision;
     *  - and that is still not enough to switch the live path on. The artifact
     *    is a GitHub release ZIP of raw `jniLibs` (~51 MB for arm64 alone), not
     *    a Maven coordinate this repository's version catalogue can consume; no
     *    on-device instrumentation run has been executed here; the durable
     *    projection gate is untouched upstream; and the resume path still never
     *    emits `PublishedApplicationMessage`, so a send whose first accepted
     *    delivery happens during resume cannot be correlated.
     */
    val recordedEvidence = NativeUpstreamEvidence(
        repository = "https://github.com/marmot-protocol/mdk",
        revision = "3fc4eb83974eb64ecb298856b0db70cc3055af57",
        crossSeamConvergenceUnified = true,
        publishedArtifactChecksumVerified = true,
        integratedViaDependencyStrategy = false,
        onDeviceInstrumentationRun = false,
        durableProjectionGateClosed = false,
        outboundResumeCorrelationFixed = false,
    )

    fun current(): NativeSharingGateState = evaluate(recordedEvidence)

    fun evaluate(evidence: NativeUpstreamEvidence?): NativeSharingGateState {
        if (evidence == null) {
            return NativeSharingGateState.Blocked(
                listOf(NativeGateReason.NO_EVIDENCE),
                NativeUpstreamEvidence.NONE,
            )
        }
        val reasons = buildList {
            if (!evidence.crossSeamConvergenceUnified) add(NativeGateReason.CROSS_SEAM_CONVERGENCE_DIVERGENT)
            if (!evidence.publishedArtifactChecksumVerified) add(NativeGateReason.ARTIFACT_CHECKSUM_UNVERIFIED)
            if (!evidence.integratedViaDependencyStrategy) add(NativeGateReason.NOT_INTEGRATED_VIA_DEPENDENCY_STRATEGY)
            if (!evidence.onDeviceInstrumentationRun) add(NativeGateReason.NO_ON_DEVICE_INSTRUMENTATION_RUN)
            if (!evidence.durableProjectionGateClosed) add(NativeGateReason.DURABLE_PROJECTION_GATE_OPEN)
            if (!evidence.outboundResumeCorrelationFixed) add(NativeGateReason.OUTBOUND_RESUME_CORRELATION_UNFIXED)
        }
        return if (reasons.isEmpty()) {
            NativeSharingGateState.Live(evidence)
        } else {
            NativeSharingGateState.Blocked(reasons, evidence)
        }
    }
}

/** Outcome of an attempt to hand a payload to the native transport. */
sealed interface NativeSharingResult {
    data class Delivered(val transportId: String) : NativeSharingResult
    data class Refused(val reasons: List<NativeGateReason>) : NativeSharingResult
}

/**
 * The seam a real Marmot/MDK adapter would implement. Kept deliberately narrow:
 * CruxCoach's ledger stays the factual authority, the adapter only moves bytes.
 */
interface NativeSharingAdapter {
    fun publish(peer: PeerId, payload: ByteArray): NativeSharingResult
}

/**
 * The only adapter that exists while the gate is blocked. It refuses every
 * call and says why. It never buffers, never retries and never reports a
 * delivery, because a queued payload that looks delivered is exactly the
 * failure this gate exists to prevent.
 */
class BlockedNativeSharingAdapter(
    private val gate: NativeSharingGateState = NativeSharingGate.current(),
) : NativeSharingAdapter {

    override fun publish(peer: PeerId, payload: ByteArray): NativeSharingResult {
        val reasons = (gate as? NativeSharingGateState.Blocked)?.reasons
            ?: listOf(NativeGateReason.NO_EVIDENCE)
        return NativeSharingResult.Refused(reasons)
    }
}
