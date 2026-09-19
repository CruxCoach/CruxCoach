package com.cruxcoach.app.platform

import platform.Foundation.NSUserDefaults

/**
 * [KeyValueStore] on an NSUserDefaults suite. One suite per identity keeps
 * per-account preferences apart. Non-secret data only.
 *
 * Every key is stored under [KEY_PREFIX] so [keys] reports only what this
 * store wrote, not the system defaults that `dictionaryRepresentation` merges in.
 */
class IosKeyValueStore(suiteName: String) : KeyValueStore {
    // A nil suite (invalid name, e.g. the main bundle identifier) must not silently
    // become a store shared between identities.
    private val defaults: NSUserDefaults = NSUserDefaults(suiteName = suiteName)

    override fun getString(key: String): String? = defaults.stringForKey(KEY_PREFIX + key)

    override fun putString(key: String, value: String?) {
        if (value == null) defaults.removeObjectForKey(KEY_PREFIX + key)
        else defaults.setObject(value, forKey = KEY_PREFIX + key)
    }

    override fun keys(): Set<String> =
        defaults.dictionaryRepresentation().keys
            .mapNotNull { it as? String }
            .filter { it.startsWith(KEY_PREFIX) }
            .map { it.removePrefix(KEY_PREFIX) }
            .toSet()

    private companion object {
        const val KEY_PREFIX = "cruxcoach.kv."
    }
}
