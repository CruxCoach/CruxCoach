package com.cruxcoach.android.notification

import com.cruxcoach.android.nostr.NostrConfig

internal object AnnouncementTagParser {

    const val CATEGORY_RELEASE = "release"
    const val CATEGORY_ISSUE = "issue"
    const val CATEGORY_TIP = "tip"
    const val CATEGORY_GENERAL = "general"

    /**
     * Check whether this event is a CruxCoach announcement by looking for
     * the NIP-32 namespace label `["L", "com.cruxcoach.announce"]`.
     */
    fun isAnnouncement(tags: Array<Array<String>>): Boolean {
        return tags.any { it.size >= 2 && it[0] == "L" && it[1] == NostrConfig.ANNOUNCE_NAMESPACE }
    }

    /**
     * Extract the announcement category from NIP-32 label tags.
     * Looks for `["l", category, "com.cruxcoach.announce"]`.
     */
    fun extractCategory(tags: Array<Array<String>>): String {
        for (tag in tags) {
            if (tag.size >= 3 && tag[0] == "l" && tag[2] == NostrConfig.ANNOUNCE_NAMESPACE) {
                return when (tag[1]) {
                    CATEGORY_RELEASE -> CATEGORY_RELEASE
                    CATEGORY_ISSUE -> CATEGORY_ISSUE
                    CATEGORY_TIP -> CATEGORY_TIP
                    else -> CATEGORY_GENERAL
                }
            }
        }
        return CATEGORY_GENERAL
    }

    fun extractPriority(category: String): String = when (category) {
        CATEGORY_RELEASE -> "high"
        CATEGORY_ISSUE -> "default"
        CATEGORY_TIP -> "low"
        else -> "default"
    }

    // --- Content language extraction ---

    private const val FLAG_EN = "🇬🇧"
    private const val FLAG_DE = "🇩🇪"

    /**
     * Extract the content section matching the given language from bilingual
     * announcement content. Format: `🇬🇧 English...\n\n🇩🇪 Deutsch...`
     *
     * Returns the matching section's text (without flag), or the full content
     * as fallback if no flag markers are found.
     */
    fun extractLocalizedContent(content: String, language: String): String {
        val targetFlag = if (language.startsWith("de")) FLAG_DE else FLAG_EN
        val otherFlag = if (targetFlag == FLAG_EN) FLAG_DE else FLAG_EN

        val targetIdx = content.indexOf(targetFlag)
        if (targetIdx < 0) {
            // Target language not in content — try the other language
            val otherIdx = content.indexOf(otherFlag)
            if (otherIdx >= 0) {
                return content.substring(otherIdx + otherFlag.length).trim()
            }
            // No flags at all — return as-is
            return content.trim()
        }

        val afterFlag = targetIdx + targetFlag.length
        val nextFlagIdx = content.indexOf(otherFlag, afterFlag)
        return if (nextFlagIdx > afterFlag) {
            content.substring(afterFlag, nextFlagIdx).trim()
        } else {
            content.substring(afterFlag).trim()
        }
    }
}
