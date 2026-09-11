package com.cruxcoach.android.sharing

import com.cruxcoach.domain.sharing.Nip01SignedEvent

/** A refused unattended request never falls back to an interactive signature.
 * Used by actual friendship root certificates as well as transport preparation. */
internal class PrivateApplicationSigner(
    private val foreground: Nip01EventSigner,
    private val unattended: () -> Boolean,
    private val provider: Nip01EventSigner,
) : Nip01EventSigner {
    override suspend fun sign(createdAt: Long, kind: Int, tags: List<List<String>>, content: String): Nip01SignedEvent? =
        (if (unattended()) provider else foreground).sign(createdAt, kind, tags, content)
}
