package com.cruxcoach.domain.sharing

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/** Associated data for the existing AES-GCM vault, not an encryption scheme. */
object SharingPayloadBinding {
    const val VERSION = 2L

    fun bytes(owner: String, itemId: String, category: SharingCategory, handle: KeyHandle, epoch: Long): ByteArray =
        buildJsonArray {
            listOf("cc.sharing.payload.v2", owner, itemId, category.name,
                handle.scope.name, handle.id, epoch.toString()).forEach { add(JsonPrimitive(it)) }
        }.toString().encodeToByteArray()
}
