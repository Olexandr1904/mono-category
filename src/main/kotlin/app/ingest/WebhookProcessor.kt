package app.ingest

import app.db.WebhookEvents
import app.mono.StatementItem
import app.mono.toTxn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Instant

class WebhookEventRepository(private val db: Database) {

    fun record(payload: String): Long = transaction(db) {
        WebhookEvents.insertAndGetId {
            it[WebhookEvents.payload] = payload
            it[receivedAt] = Instant.now().epochSecond
        }.value
    }

    fun markProcessed(id: Long) = transaction(db) {
        WebhookEvents.update({ WebhookEvents.id eq id }) {
            it[processedAt] = Instant.now().epochSecond
        }
        Unit
    }

    /**
     * Drops processed events older than [cutoffEpochSeconds]. Every delivery is kept
     * verbatim so a restart mid-processing loses nothing, but once an event is processed
     * it is only forensic value — and nothing was deleting them, so the table grew for
     * the life of the deployment on a volume shared with the rest of the database.
     * Unprocessed rows are never touched regardless of age: those are still owed work.
     */
    fun deleteProcessedBefore(cutoffEpochSeconds: Long): Int = transaction(db) {
        // Age is measured on received_at, which is non-null; processed_at only has to be
        // set at all. (Exposed cannot build a `<` on a nullable column: Long? is not
        // Comparable, so the comparison has to run against the non-null column anyway.)
        WebhookEvents.deleteWhere {
            it.run { processedAt.isNotNull() and (receivedAt less cutoffEpochSeconds) }
        }
    }

    fun unprocessed(limit: Int = 200): List<Pair<Long, String>> = transaction(db) {
        WebhookEvents.selectAll()
            .where { WebhookEvents.processedAt.isNull() }
            .orderBy(WebhookEvents.id to SortOrder.ASC)
            .limit(limit)
            .map { it[WebhookEvents.id].value to it[WebhookEvents.payload] }
    }
}

/**
 * The webhook route only writes the raw payload and answers 200 — Monobank gives us
 * 5 seconds and disables the hook after three misses. Parsing happens here, and since
 * the payload is already on disk, a restart in between loses nothing.
 */
class WebhookProcessor(
    private val events: WebhookEventRepository,
    private val ingest: IngestService,
) {
    private val log = LoggerFactory.getLogger(WebhookProcessor::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Returns how many stored events were handled. */
    suspend fun drain(): Int {
        val batch = events.unprocessed()
        for ((id, payload) in batch) {
            // A payload we cannot parse, or can parse but not turn into a Txn (e.g. a
            // JSON-valid but absurd "time" that overflows Instant/monthKeyOf), is
            // permanently bad — it will never succeed on a retry — so it must be marked
            // processed or it would block the queue forever, same reasoning either way.
            // An ingest or database failure (e.g. SQLITE_BUSY during the hourly sync) is
            // transient: the payload itself converted fine, so leaving it unprocessed lets
            // the next drain retry it instead of discarding a real transaction for good.
            val txn = try {
                val root = json.parseToJsonElement(payload).jsonObject
                val data = root.getValue("data").jsonObject
                val itemElement = data.getValue("statementItem")
                json.decodeFromJsonElement<StatementItem>(itemElement).toTxn(
                    accountId = data.getValue("account").jsonPrimitive.content,
                    rawJson = itemElement.toString(),
                )
            } catch (e: Exception) {
                log.warn("dropping unprocessable webhook event {}", id, e)
                events.markProcessed(id)
                continue
            }
            ingest.ingest(listOf(txn))
            events.markProcessed(id)
        }
        return batch.size
    }
}
