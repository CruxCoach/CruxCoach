package com.cruxcoach.android.data

import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.db.secure.Announcement
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnnouncementRepository @Inject constructor(
    private val database: SecureDatabase
) {
    private val queries get() = database.announcementQueries

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

    fun getAll(): List<Announcement> = queries.getAll().executeAsList()

    fun getById(id: String): Announcement? = queries.getById(id).executeAsOneOrNull()

    fun getUnreadCount(): Long = queries.getUnreadCount().executeAsOne()

    fun markRead(id: String) = queries.markRead(id)

    fun markAllRead() = queries.markAllRead()
}
