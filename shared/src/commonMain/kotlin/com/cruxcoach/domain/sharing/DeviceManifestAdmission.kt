package com.cruxcoach.domain.sharing

/**
 * FEAT-062 §11: decides whether a manifest entry may be written at all.
 *
 * The reducer already fails a broken manifest closed, but refusing to *read*
 * something is not the same as refusing to *store* it — and the stakes here are
 * higher than on either other ledger. The manifest is append-only and is the
 * root of device authority: one inadmissible entry that reaches the table fails
 * the manifest closed for ever, which takes **every** device's authority with
 * it and cannot be undone. The app would be locked out of its own permissions.
 *
 * So admission runs first and is side-effect free by construction: it is given
 * the stored entries and the candidate, and returns a verdict. Nothing here
 * writes, and nothing downstream may write without it.
 */
object DeviceManifestAdmission {

    @Suppress("ReturnCount")
    fun check(
        existing: List<DeviceManifestEntry>,
        candidate: DeviceManifestEntry,
        verifier: DeviceManifestVerifier,
        ownerNpub: String,
        /** Every act the install holds, including the one authorising this. */
        acts: List<AuthorityAttestation> = emptyList(),
        attestationVerifier: AuthorityAttestationVerifier = AuthorityAttestationVerifier { _, _ -> false },
    ): LedgerAdmission {
        // Only the root identity writes the manifest. Which *device* may ask
        // for a manifest change is a separate question, answered by the reduced
        // manifest itself — this is the signature that makes it the owner's.
        if (candidate.signerNpub != ownerNpub) {
            return LedgerAdmission.Reject(
                "manifest entry ${candidate.id.value} was signed by ${candidate.signerNpub}, not the owner",
            )
        }

        // Authenticity before anything else: an unverifiable entry is not
        // evidence of anything and must never reach storage.
        if (!verifier.verify(candidate)) {
            return LedgerAdmission.Reject("manifest entry ${candidate.id.value} does not verify")
        }

        // A re-used id must be byte-identical, or two histories claim one name.
        existing.firstOrNull { it.id == candidate.id }?.let { stored ->
            return if (stored == candidate) {
                LedgerAdmission.AlreadyPresent
            } else {
                LedgerAdmission.Reject(
                    "manifest id ${candidate.id.value} is already stored with divergent content",
                )
            }
        }

        // A parent that exists, carrying a sequence one below this one. Not
        // "the current head", and not "the next number on disk": two of the
        // owner's devices offline both build on the head they last saw, and
        // refusing whichever arrived second puts relay order in charge of who
        // administers the estate. Siblings on one parent share a sequence —
        // that is what a fork is — and the resolver settles them per scope.
        val parent = candidate.parent
        if (parent == null) {
            if (candidate.manifestSequence != 1L) {
                return LedgerAdmission.Reject(
                    "manifest entry ${candidate.id.value} begins a history at sequence " +
                        "${candidate.manifestSequence}, not 1",
                )
            }
            if (existing.any { it.parent == null }) {
                return LedgerAdmission.Reject(
                    "manifest entry ${candidate.id.value} begins a second history; the estate already has one",
                )
            }
        } else {
            val above = existing.firstOrNull { it.id == parent }
                ?: return LedgerAdmission.Reject(
                    "manifest entry ${candidate.id.value} follows ${parent.value}, which is not stored",
                )
            if (candidate.manifestSequence != above.manifestSequence + 1L) {
                return LedgerAdmission.Reject(
                    "manifest entry ${candidate.id.value} has sequence ${candidate.manifestSequence}, " +
                        "which does not follow its parent's ${above.manifestSequence}",
                )
            }
        }

        // Finally the whole thing has to reduce. This is what catches the role,
        // generation, act-pairing and unknown-body rules without restating
        // them: if the manifest with this entry appended would not be readable,
        // it does not go in.
        val reduced = DeviceManifestReducer(verifier, ownerNpub, attestationVerifier)
            .reduce(existing + candidate, acts)
        reduced.failClosedReason?.let {
            return LedgerAdmission.Reject("manifest entry ${candidate.id.value} would fail the manifest closed: $it")
        }

        return LedgerAdmission.Accept
    }
}
