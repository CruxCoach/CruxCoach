package com.cruxcoach.android.nostr

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.android.data.NostrMessageRepository
import com.cruxcoach.android.nostr.model.MessageType
import com.cruxcoach.android.nostr.model.NostrRecipient
import com.cruxcoach.db.secure.SecureDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OfflineQueueManagerTest {

    @Test
    fun failed_head_is_deferred_and_does_not_starve_next_message() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            SecureDatabase.Schema.create(driver)
            val repository = NostrMessageRepository(SecureDatabase(driver))
            val initialQueuedAt = System.currentTimeMillis() - 1_000L
            insertQueued(repository, "poison", initialQueuedAt)
            insertQueued(repository, "good", initialQueuedAt + 1L)
            val attempts = mutableListOf<String>()
            val sender = object : NostrMessageSending {
                override suspend fun retrySend(eventJsons: String): Boolean {
                    attempts += eventJsons
                    return eventJsons == "json-good"
                }

                override suspend fun deliverWraps(eventJsons: String): Boolean =
                    error("not used")

                override suspend fun buildMessage(
                    content: String,
                    type: MessageType,
                    recipients: NostrRecipient,
                    subject: String?,
                    replyToId: String?,
                    selfReplyToId: String?,
                ): SendResult = error("not used")
            }

            OfflineQueueManager(repository, sender).drainQueue()

            assertEquals(listOf("json-poison", "json-good"), attempts)
            assertNotNull(repository.getById("poison")?.queued_at)
            assertNull(repository.getById("good")?.queued_at)
        } finally {
            driver.close()
        }
    }

    private fun insertQueued(repository: NostrMessageRepository, id: String, queuedAt: Long) {
        repository.insert(
            id = id,
            type = MessageType.BUG.label,
            direction = "sent",
            content = "content",
            subject = null,
            senderPubkey = "sender",
            createdAt = System.currentTimeMillis(),
        )
        repository.markQueued(id, queuedAt, "json-$id")
    }
}
