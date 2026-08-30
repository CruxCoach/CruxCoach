package com.cruxcoach.android.data

import com.cruxcoach.db.secure.Announcements
import com.cruxcoach.db.secure.SecureDatabase
import javax.inject.Inject
import javax.inject.Singleton

data class AnnouncementRecord(
    val id: String,
    val content: String,
    val category: String,
    val priority: String,
    val createdAt: Long,
    val isRead: Boolean,
)

@Singleton
class AnnouncementRepository @Inject constructor(
    private val database: SecureDatabase
) {
    private val queries get() = database.announcementsQueries

    fun insert(
        id: String,
        content: String,
        category: String,
        priority: String,
        createdAt: Long,
        read: Boolean = false
    ) {
        queries.insert(id, content, category, priority, createdAt, if (read) 1L else 0L)
    }

    fun getAll(): List<AnnouncementRecord> = queries.getAll().executeAsList().map { it.toRecord() }

    fun getById(id: String): AnnouncementRecord? =
        queries.getById(id).executeAsOneOrNull()?.toRecord()

    fun getUnreadCount(): Long = queries.getUnreadCount().executeAsOne()

    fun markRead(id: String) = queries.markRead(id)

    fun markAllRead() = queries.markAllRead()

    private fun Announcements.toRecord() = AnnouncementRecord(
        id = id,
        content = content,
        category = category,
        priority = priority,
        createdAt = created_at,
        isRead = read != 0L,
    )
}
