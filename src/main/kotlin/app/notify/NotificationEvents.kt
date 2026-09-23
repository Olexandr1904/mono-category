package app.notify

import app.db.NotificationEvents
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

/**
 * Deduplication lives in the UNIQUE(category_id, month, threshold) index, not in code:
 * a successful insert IS the permission to send. Checking with a SELECT first would
 * leave a window for a concurrent webhook redelivery.
 */
class NotificationEventRepository(private val db: Database) {

    fun claim(categoryId: Long, month: String, threshold: Int): Boolean = transaction(db) {
        NotificationEvents.insertIgnore {
            it[NotificationEvents.categoryId] = categoryId
            it[NotificationEvents.month] = month
            it[NotificationEvents.threshold] = threshold
            it[notifiedAt] = Instant.now().epochSecond
        }.insertedCount > 0
    }

    fun release(categoryId: Long, month: String, threshold: Int) = transaction(db) {
        NotificationEvents.deleteWhere {
            it.run {
                (NotificationEvents.categoryId eq categoryId) and
                    (NotificationEvents.month eq month) and
                    (NotificationEvents.threshold eq threshold)
            }
        }
        Unit
    }
}
