package com.cruxcoach.android.fips

/** Idempotent logical leases for the process-wide native mesh runtime. */
internal class FipsRuntimeOwners {
    data class Change(
        val changed: Boolean,
        val count: Int,
        val becameActive: Boolean = false,
        val becameIdle: Boolean = false,
    )

    private val owners = linkedSetOf<String>()

    @Synchronized
    fun acquire(owner: String): Change {
        require(owner.isNotBlank())
        val wasEmpty = owners.isEmpty()
        val changed = owners.add(owner)
        return Change(changed, owners.size, becameActive = changed && wasEmpty)
    }

    @Synchronized
    fun release(owner: String): Change {
        val changed = owners.remove(owner)
        return Change(changed, owners.size, becameIdle = changed && owners.isEmpty())
    }

    @Synchronized fun isActive(): Boolean = owners.isNotEmpty()
    @Synchronized fun count(): Int = owners.size
}
