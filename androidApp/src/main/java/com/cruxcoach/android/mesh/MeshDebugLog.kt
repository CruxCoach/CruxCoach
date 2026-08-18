package com.cruxcoach.android.mesh

import com.cruxcoach.android.fips.FipsDebugLog

/**
 * Mesh-layer trace.
 *
 * Deliberately the same sink and field grammar as the transport trace: a realm
 * problem is almost always read together with the radio events around it.
 */
internal object MeshDebugLog {
    fun event(component: String, event: String, vararg fields: Pair<String, Any?>) =
        FipsDebugLog.event("mesh_$component", event, *fields)

    fun warning(component: String, event: String, vararg fields: Pair<String, Any?>) =
        FipsDebugLog.warning("mesh_$component", event, *fields)

    fun id(value: String?): String = FipsDebugLog.id(value)
}
