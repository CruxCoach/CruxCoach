package com.cruxcoach.domain.community

/**
 * Renders the auto-Note template that the user can opt into when publishing
 * a climb (FEAT — auto Kind-1 announcement). Pure substitution, no I/O —
 * the caller resolves variable values (NIP-19 `naddr`, `npub`, App-Link
 * URL) and hands them in as a map.
 *
 * Supported placeholders:
 *  - `{name}`             — climb name
 *  - `{naddr}`            — NIP-19 naddr (for Nostr-native clients)
 *  - `{npub_cruxcoach}`   — CruxCoach maintainer npub (mention in feed)
 *  - `{cruxcoach_url}`    — `https://cruxcoach.org/c/<naddr>` (App Link)
 *
 * Unknown placeholders are left in place so a typo in a future
 * user-editable template is visible in the rendered text rather than
 * silently swallowed. Empty values for known placeholders simply produce
 * an empty substitution.
 */
object AutoNoteTemplate {
    private val placeholderRegex = Regex("""\{([a-z_][a-z0-9_]*)\}""")

    fun render(template: String, vars: Map<String, String>): String =
        placeholderRegex.replace(template) { match ->
            val key = match.groupValues[1]
            vars[key] ?: match.value
        }
}
