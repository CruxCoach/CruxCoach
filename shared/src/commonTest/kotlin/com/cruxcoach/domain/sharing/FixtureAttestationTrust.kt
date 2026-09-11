package com.cruxcoach.domain.sharing

/**
 * The signing convention every attestation fixture in this module uses: an
 * act's signature is the key the manifest binds to its device, then the act's
 * own id.
 *
 * Real enough to be worth checking — moving an act onto another device, or
 * renaming it, breaks it — and cheap enough that a test about which branch wins
 * does not have to carry a key schedule to say what it means. The cryptography
 * itself is exercised where it belongs, over the canonical bytes.
 */
internal val FIXTURE_ATTESTATION_VERIFIER = AuthorityAttestationVerifier { act, publicKey ->
    act.signature == "$publicKey:${act.id.value}"
}

/**
 * The acts, checked against the manifest, as everything that decides access
 * now requires — see [TrustedAttestations].
 */
internal fun trusted(
    acts: List<AuthorityAttestation>,
    manifest: DeviceAuthorityState,
    verifier: AuthorityAttestationVerifier = FIXTURE_ATTESTATION_VERIFIER,
): TrustedAttestations = TrustedAttestations.of(acts, manifest.readableAtItsOwnHead(), verifier)

/** The context every fixture here signs against: the estate's only frontier. */
internal val FIXTURE_MANIFEST_CONTEXT = ManifestContext.of(listOf(LedgerEntryId("m-1")))

private const val FIXTURE_OWNER = "npub1owner"

/**
 * Gives a hand-assembled state a real manifest behind it.
 *
 * These fixtures declare an estate directly — "these devices, these roles" —
 * rather than reducing entries, so there is nothing for a bound context to be
 * replayed against. This synthesises the manifest that would have produced
 * them: one entry per device, chained, all under `m-1`'s ancestry, so the whole
 * estate is reachable from the single frontier id the fixtures sign against.
 *
 * A fixture that says anything about *history* — a promotion, a demotion, a
 * revocation, a merge — builds its own entries and reduces them for real.
 */
internal fun DeviceAuthorityState.readableAtItsOwnHead(): DeviceAuthorityState {
    if (history.isNotEmpty() || devices.isEmpty()) return this
    val bodies = mutableListOf<DeviceManifestBody>()
    devices.values.sortedBy { it.device.value }.forEach { record ->
        bodies += DeviceManifestBody.DeviceEnrolled(record.device, record.publicKey, record.role)
        // A device the fixture says holds nothing must hold nothing here too,
        // or an act it authored would verify against a synthetic estate that
        // is kinder than the one the test declared.
        if (record.fenced || !record.role.carriesAuthority) {
            bodies += DeviceManifestBody.DeviceRevoked(record.device)
        }
    }
    // The chain ends at `m-1`, the id these fixtures sign against, so its
    // ancestor closure is the whole estate.
    val entries = bodies.mapIndexed { index, body ->
        DeviceManifestEntry(
            id = if (index == bodies.lastIndex) LedgerEntryId("m-1") else LedgerEntryId("m-1-$index"),
            manifestSequence = index + 1L,
            authorityGeneration = authorityGeneration,
            parent = if (index == 0) null else LedgerEntryId("m-1-${index - 1}"),
            signerNpub = FIXTURE_OWNER,
            signature = "sig",
            body = body,
        )
    }
    return copy(frontier = listOf(LedgerEntryId("m-1")), history = entries)
}
