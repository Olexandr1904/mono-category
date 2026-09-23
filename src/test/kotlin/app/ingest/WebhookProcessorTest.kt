package app.ingest

import app.budget.BudgetService
import app.budget.CategoryRepository
import app.budget.ConduitMccRepository
import app.budget.CounterpartyRepository
import app.budget.KYIV
import app.budget.TransactionRepository
import app.db.Crypto
import app.db.SettingsRepository
import app.db.connectDb
import app.notify.FakeTelegramClient
import app.notify.NotificationEventRepository
import app.notify.Notifier
import app.withTestDb
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebhookProcessorTest {

    private val august = 1_787_011_200L
    private val clock = Clock.fixed(Instant.ofEpochSecond(august), KYIV)

    private fun payload(id: String, mcc: Int = 5411, amount: Long = -84_000, time: Long = august) = """
        {"type":"StatementItem","data":{"account":"acc-uah","statementItem":{
          "id":"$id","time":$time,"description":"ATB","mcc":$mcc,"originalMcc":$mcc,
          "hold":false,"amount":$amount,"operationAmount":$amount,"currencyCode":980}}}
    """.trimIndent()

    private fun wire(db: Database): Pair<WebhookProcessor, TransactionRepository> {
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val transactions = TransactionRepository(db)
        val settings = SettingsRepository(db, Crypto(ByteArray(32) { it.toByte() }))
        val ingest = IngestService(
            transactions, categories,
            BudgetService(categories, transactions),
            Notifier(FakeTelegramClient(), settings, NotificationEventRepository(db)),
            CounterpartyRepository(db), ConduitMccRepository(db), AccountRepository(db),
            clock = clock,
        )
        return WebhookProcessor(WebhookEventRepository(db), ingest) to transactions
    }

    @Test
    fun `drain turns stored payloads into transactions`() = withTestDb { db ->
        val events = WebhookEventRepository(db)
        events.record(payload("t1"))
        val (processor, transactions) = wire(db)
        assertEquals(1, runBlocking { processor.drain() })
        assertEquals("ATB", transactions.byId("t1")!!.description)
        assertEquals("acc-uah", transactions.byId("t1")!!.accountId)
        assertTrue(events.unprocessed().isEmpty())
    }

    @Test
    fun `a redelivered payload does not double count`() = withTestDb { db ->
        val events = WebhookEventRepository(db)
        events.record(payload("t1"))
        events.record(payload("t1"))
        val (processor, transactions) = wire(db)
        runBlocking { processor.drain() }
        assertEquals(1, transactions.count(null, null, false))
    }

    @Test
    fun `an unparsable payload is marked processed and does not block the queue`() = withTestDb { db ->
        val events = WebhookEventRepository(db)
        events.record("garbage")
        events.record(payload("t1"))
        val (processor, transactions) = wire(db)
        assertEquals(2, runBlocking { processor.drain() })
        assertTrue(events.unprocessed().isEmpty())
        assertEquals(1, transactions.count(null, null, false))
    }

    @Test
    fun `a payload with an absurd time value is marked processed and does not block the queue`() = withTestDb { db ->
        // JSON-valid, schema-valid (time is a Long), but Instant.ofEpochSecond throws
        // DateTimeException on a value this far outside the representable range. That
        // conversion failure happens in toTxn/monthKeyOf, AFTER the JSON decode succeeds —
        // it must be treated the same as an unparsable payload (permanently bad, mark
        // processed), not the same as a transient ingest failure (item 5's fix would
        // otherwise leave it stuck at the head of the queue forever).
        val events = WebhookEventRepository(db)
        events.record(payload("absurd", time = Long.MAX_VALUE))
        events.record(payload("t1"))
        val (processor, transactions) = wire(db)

        assertEquals(2, runBlocking { processor.drain() })

        assertTrue(events.unprocessed().isEmpty())
        assertEquals(null, transactions.byId("absurd"))
        assertEquals("ATB", transactions.byId("t1")!!.description, "a later good event in the same drain must still be handled")
    }

    @Test
    fun `an ingest failure leaves the event unprocessed so a later drain retries it`() = withTestDb { db ->
        val events = WebhookEventRepository(db)
        events.record(payload("t1"))

        // A syntactically valid payload, but ingest.ingest() itself throws: point its
        // repositories at a database that never ran migrations, so the very first query
        // inside ingest() ("no such table") fails. Unlike an unparsable payload, this
        // failure is transient (the real-world case is SQLITE_BUSY during the hourly
        // sync) — the event must NOT be marked processed, or the transaction is lost for
        // good instead of retried on the next drain.
        val brokenDir = Files.createTempDirectory("webhook-broken-db")
        try {
            val brokenDb = connectDb(brokenDir.resolve("unmigrated.db").toString())
            val categories = CategoryRepository(brokenDb, ConduitMccRepository(brokenDb))
            val transactions = TransactionRepository(brokenDb)
            val settings = SettingsRepository(brokenDb, Crypto(ByteArray(32) { it.toByte() }))
            val ingest = IngestService(
                transactions, categories,
                BudgetService(categories, transactions),
                Notifier(FakeTelegramClient(), settings, NotificationEventRepository(brokenDb)),
                CounterpartyRepository(brokenDb), ConduitMccRepository(brokenDb), AccountRepository(brokenDb),
                clock = clock,
            )
            val processor = WebhookProcessor(events, ingest)

            assertFailsWith<Exception> { runBlocking { processor.drain() } }
            assertTrue(events.unprocessed().isNotEmpty(), "a transient failure must leave the event for a retry")
        } finally {
            brokenDir.toFile().deleteRecursively()
        }

        // The event survived, unprocessed, in the real (migrated) database. A later
        // drain with a healthy ingest recovers the transaction.
        val (processor, transactions) = wire(db)
        assertEquals(1, runBlocking { processor.drain() })
        assertEquals("ATB", transactions.byId("t1")!!.description)
        assertTrue(events.unprocessed().isEmpty())
    }

    @Test
    fun `payloads that survived a restart are drained on the next run`() = withTestDb { db ->
        val events = WebhookEventRepository(db)
        events.record(payload("t1"))
        events.record(payload("t2"))
        val (processor, transactions) = wire(db)
        runBlocking { processor.drain() }
        assertEquals(2, transactions.count(null, null, false))
        assertEquals(0, runBlocking { processor.drain() })
    }

    @Test
    fun `raw_json keeps fields our model does not know about`() = withTestDb { db ->
        val events = WebhookEventRepository(db)
        val transactions = TransactionRepository(db)
        val ingest = IngestService(
            transactions, CategoryRepository(db, ConduitMccRepository(db)),
            BudgetService(CategoryRepository(db, ConduitMccRepository(db)), TransactionRepository(db)),
            Notifier(FakeTelegramClient(), SettingsRepository(db, Crypto(ByteArray(32))), NotificationEventRepository(db)),
            CounterpartyRepository(db), ConduitMccRepository(db), AccountRepository(db),
        )
        events.record(
            """
            {"type":"StatementItem","data":{"account":"acc-1","statementItem":{
              "id":"tx-raw","time":1787011200,"description":"На картку","mcc":4829,
              "originalMcc":4829,"hold":false,"amount":-250000,"operationAmount":-250000,
              "currencyCode":980,"counterName":"Петренко Іван","comment":"за оренду"}}}
            """.trimIndent(),
        )

        runBlocking { WebhookProcessor(events, ingest).drain() }

        val raw = transactions.byId("tx-raw")!!.rawJson
        assertTrue(raw.contains("counterName"), raw)
        assertTrue(raw.contains("Петренко Іван"), raw)
        assertTrue(raw.contains("за оренду"), raw)
    }
}
