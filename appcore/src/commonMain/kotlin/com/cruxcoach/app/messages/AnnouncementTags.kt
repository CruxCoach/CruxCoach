package com.cruxcoach.app.messages

import com.cruxcoach.app.AppConfig

/**
 * The project's own announcements, port of Android `AnnouncementTagParser`.
 *
 * An announcement is a maintainer message carrying the NIP-32 namespace label
 * `["L", "com.cruxcoach.announce"]`. It travels the same private channel as a
 * reply, which is why the label — not the sender alone — decides where it goes.
 */
object AnnouncementTags {
    const val CATEGORY_RELEASE = "release"
    const val CATEGORY_ISSUE = "issue"
    const val CATEGORY_TIP = "tip"
    const val CATEGORY_GENERAL = "general"

    private const val FLAG_EN = "🇬🇧"
    private const val FLAG_DE = "🇩🇪"

    fun isAnnouncement(tags: List<List<String>>): Boolean =
        tags.any { it.size >= 2 && it[0] == "L" && it[1] == AppConfig.ANNOUNCE_NAMESPACE }

    fun category(tags: List<List<String>>): String {
        for (tag in tags) {
            if (tag.size >= 3 && tag[0] == "l" && tag[2] == AppConfig.ANNOUNCE_NAMESPACE) {
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

    fun priority(category: String): String = when (category) {
        CATEGORY_RELEASE -> "high"
        CATEGORY_ISSUE -> "default"
        CATEGORY_TIP -> "low"
        else -> "default"
    }

    /**
     * Picks one language out of a bilingual announcement
     * (`🇬🇧 English…\n\n🇩🇪 Deutsch…`).
     *
     * A missing target language falls back to the other one rather than to
     * nothing, and content without flags is returned unchanged — an
     * announcement the reader cannot see is worse than one in the wrong
     * language.
     */
    fun localized(content: String, language: String): String {
        val target = if (language.startsWith("de")) FLAG_DE else FLAG_EN
        val other = if (target == FLAG_EN) FLAG_DE else FLAG_EN
        val targetIndex = content.indexOf(target)
        if (targetIndex < 0) {
            val otherIndex = content.indexOf(other)
            if (otherIndex >= 0) return content.substring(otherIndex + other.length).trim()
            return content.trim()
        }
        val afterFlag = targetIndex + target.length
        val nextFlag = content.indexOf(other, afterFlag)
        return if (nextFlag > afterFlag) {
            content.substring(afterFlag, nextFlag).trim()
        } else {
            content.substring(afterFlag).trim()
        }
    }
}

/** One announcement from the project. */
class Announcement(
    val id: String,
    val content: String,
    val category: String,
    val priority: String,
    val createdAt: Long,
    val read: Boolean,
)
