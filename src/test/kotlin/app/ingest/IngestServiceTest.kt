package app.ingest

import app.budget.BudgetService
import app.budget.CategoryRepository
import app.budget.ConduitMccRepository
import app.budget.CounterpartyRepository
import app.budget.MccConflictException
import app.budget.TransactionRepository
import app.budget.Txn
import app.budget.UAH_CURRENCY_CODE
import app.budget.monthKeyOf
import app.db.Crypto
import app.db.SettingKeys
import app.db.SettingsRepository
import app.i18n.UkCopy
import app.mono.MonoAccount
import app.notify.FakeTelegramClient
import app.notify.MccPromptRepository
import app.notify.MccPromptService
import app.notify.NotificationEventRepository
import app.notify.Notifier
import app.withTestDb
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.sql.Database
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IngestServiceTest {

    private val august = 1_787_011_200L
    private val fixedClock = Clock.fixed(Instant.ofEpochSecond(august), app.budget.KYIV)

    private fun txn(id: String, amountMinor: Long = -84_000, mcc: Int? = 5411, hold: Boolean = false) = Txn(
        id = id, accountId = "acc-1", occurredAt = august, month = monthKeyOf(august),
        amountMinor = amountMinor, currencyCode = 980, description = "ATB",
        mcc = mcc, originalMcc = mcc, hold = hold,
        categoryId = null, manuallyCategorized = false, rawJson = "{}",
    )

    private class RecordingObserver : UnknownMccObserver {
        val seen = mutableListOf<Int?>()
        override suspend fun onUnknownMcc(txn: Txn) { seen += txn.mcc }
    }

    private fun wire(
        db: Database,
        telegram: FakeTelegramClient = FakeTelegramClient(),
        observer: UnknownMccObserver = UnknownMccObserver.NoOp,
    ): Triple<IngestService, CategoryRepository, TransactionRepository> {
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val transactions = TransactionRepository(db)
        val settings = SettingsRepository(db, Crypto(ByteArray(32) { it.toByte() }))
        settings.set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        val budget = BudgetService(categories, transactions)
        val notifier = Notifier(telegram, settings, NotificationEventRepository(db))
        val ingest = IngestService(
            transactions, categories, budget, notifier,
            CounterpartyRepository(db), ConduitMccRepository(db), AccountRepository(db),
            observer, fixedClock,
        )
        return Triple(ingest, categories, transactions)
    }

    /** Stores [id] as a known account and switches it off, the way the Settings page does. */
    private fun switchOff(db: Database, id: String) {
        val accounts = AccountRepository(db)
        accounts.replaceAll(listOf(MonoAccount(id = id, currencyCode = UAH_CURRENCY_CODE)))
        accounts.setActive(accounts.ids().toSet() - id)
    }

    /** Stores [id] as a known dollar account, the way a refresh does. */
    private fun storeDollarAccount(db: Database, id: String) {
        AccountRepository(db).replaceAll(listOf(MonoAccount(id = id, currencyCode = 840)))
    }

    @Test
    fun `ingest categorizes by mcc and reports what it did`() = withTestDb { db ->
        val (ingest, categories, transactions) = wire(db)
        val groceries = categories.create("Groceries", "🛒", 2_500_000, 80)
        categories.setMcc(groceries, setOf(5411))

        val outcome = runBlocking { ingest.ingest(listOf(txn("t1"))) }
        assertEquals(IngestOutcome(inserted = 1, updated = 0, unchanged = 0), outcome)
        assertEquals(groceries, transactions.byId("t1")!!.categoryId)
    }

    @Test
    fun `concurrent redelivery of one transaction yields one row and one notification`() = withTestDb { db ->
        val telegram = FakeTelegramClient()
        val (ingest, categories, transactions) = wire(db, telegram)
        val groceries = categories.create("Groceries", "🛒", 100_000, 80)
        categories.setMcc(groceries, setOf(5411))

        runBlocking {
            listOf(
                async { ingest.ingest(listOf(txn("t1", amountMinor = -90_000))) },
                async { ingest.ingest(listOf(txn("t1", amountMinor = -90_000))) },
            ).awaitAll()
        }

        assertEquals(1, transactions.count(monthKeyOf(august), null, false))
        assertEquals(1, telegram.sent.size)
    }

    @Test
    fun `the ingest mutex serializes concurrent calls`() = withTestDb { db ->
        val telegram = FakeTelegramClient().apply { delayMillis = 20 }
        val (ingest, categories, _) = wire(db, telegram)
        val groceries = categories.create("Groceries", "🛒", 100_000, 80)
        categories.setMcc(groceries, setOf(5411))

        runBlocking {
            listOf(
                async { ingest.ingest(listOf(txn("c1", amountMinor = -90_000))) },
                async { ingest.ingest(listOf(txn("c2", amountMinor = -20_000))) },
            ).awaitAll()
        }

        // The instrumentation's throw is caught and swallowed inside Notifier.checkThresholds
        // by design (Task 8): an exception escaping the threshold check would reach the
        // Monobank webhook handler, and repeated failures there disable the webhook. So we
        // cannot assert on the exception itself — we assert on its observable symptom instead.
        // A rejected concurrent send drops its claim without retrying, so an unlocked ingest
        // notifies once instead of twice; a properly serialized one always notifies twice.
        assertEquals(2, telegram.sent.size, "both categories must be notified; a dropped send means the sends overlapped")
    }

    @Test
    fun `an unmapped mcc reaches the observer exactly once`() = withTestDb { db ->
        val observer = RecordingObserver()
        val (ingest, _, _) = wire(db, observer = observer)
        runBlocking {
            ingest.ingest(listOf(txn("t1", mcc = 5712)))
            ingest.ingest(listOf(txn("t1", mcc = 5712)))   // redelivery
            ingest.ingest(listOf(txn("t2", mcc = 5712)))   // another purchase, same mcc
        }
        assertEquals(listOf<Int?>(5712, 5712), observer.seen) // one per new transaction
    }

    /**
     * Money coming in is not an expense, so there is nothing to file it under. The owner
     * received a Telegram question about MCC 6012 — a card top-up — which no answer could
     * usefully change: spending ignores positive amounts entirely, so any category picked
     * would alter no figure on any screen. The question was pure noise.
     */
    @Test
    fun `an incoming transaction never reaches the observer`() = withTestDb { db ->
        val observer = RecordingObserver()
        val (ingest, _, _) = wire(db, observer = observer)
        runBlocking {
            // Same unmapped MCC, opposite directions: only the outgoing one is worth asking about.
            ingest.ingest(listOf(txn("in", mcc = 6012, amountMinor = 9_000)))
            ingest.ingest(listOf(txn("out", mcc = 6012, amountMinor = -9_000)))
        }
        assertEquals(listOf<Int?>(6012), observer.seen, "only the outgoing transaction should prompt")
    }

    @Test
    fun `a mapped mcc never reaches the observer`() = withTestDb { db ->
        val observer = RecordingObserver()
        val (ingest, categories, _) = wire(db, observer = observer)
        val groceries = categories.create("Groceries", "🛒", 2_500_000, 80)
        categories.setMcc(groceries, setOf(5411))
        runBlocking { ingest.ingest(listOf(txn("t1", mcc = 5411))) }
        assertTrue(observer.seen.isEmpty())
    }

    @Test
    fun `setTransactionCategory marks the row manual`() = withTestDb { db ->
        val (ingest, categories, transactions) = wire(db)
        val home = categories.create("Home", "🏠", 1_000_000, 80)
        runBlocking {
            ingest.ingest(listOf(txn("t1", mcc = 5712)))
            ingest.setTransactionCategory("t1", home)
        }
        val stored = transactions.byId("t1")!!
        assertEquals(home, stored.categoryId)
        assertTrue(stored.manuallyCategorized)
    }

    @Test
    fun `bindMcc maps the code and rewrites the whole history`() = withTestDb { db ->
        val (ingest, categories, transactions) = wire(db)
        val home = categories.create("Home", "🏠", 1_000_000, 80)
        runBlocking {
            ingest.ingest(listOf(txn("t1", mcc = 5712)))
            ingest.ingest(listOf(txn("t2", mcc = 5712)))
            ingest.bindMcc(5712, home)
        }
        assertEquals(home, transactions.byId("t1")!!.categoryId)
        assertEquals(home, transactions.byId("t2")!!.categoryId)
        assertEquals(mapOf(5712 to home), categories.mccMapping())
    }

    @Test
    fun `recategorizeAll clears categories whose mcc was removed`() = withTestDb { db ->
        val (ingest, categories, transactions) = wire(db)
        val home = categories.create("Home", "🏠", 1_000_000, 80)
        categories.setMcc(home, setOf(5712))
        runBlocking { ingest.ingest(listOf(txn("t1", mcc = 5712))) }
        assertEquals(home, transactions.byId("t1")!!.categoryId)

        categories.setMcc(home, emptySet())
        runBlocking { ingest.recategorizeAll() }
        assertNull(transactions.byId("t1")!!.categoryId)
    }

    @Test
    fun `a disabled category no longer categorizes new transactions`() = withTestDb { db ->
        // Spec §6: enabled = false removes a category from categorization. Notifier already
        // skips disabled categories; mccMapping() must exclude them too, or a disabled
        // category keeps silently absorbing new spend nobody is alerted about.
        val (ingest, categories, transactions) = wire(db)
        val home = categories.create("Home", "🏠", 1_000_000, 80)
        categories.setMcc(home, setOf(5712))
        categories.update(categories.byId(home)!!.copy(enabled = false))

        runBlocking { ingest.ingest(listOf(txn("t1", mcc = 5712))) }

        assertNull(transactions.byId("t1")!!.categoryId)
    }

    @Test
    fun `disabling a category does not erase its already-assigned transactions`() = withTestDb { db ->
        // Spec §6, design doc: "already-assigned transactions keep their category_id.
        // History is not rewritten." Filtering mccMapping() to enabled categories (item 4) is
        // right for NEW transactions, but recategorize() must not let that filtered
        // mapping retroactively clear rows that were already assigned before the category
        // was disabled — that would be undocumented, retroactive data loss on every
        // unrelated recategorization triggered afterward (editing another category,
        // answering one Telegram MCC prompt, ...).
        val (ingest, categories, transactions) = wire(db)
        val home = categories.create("Home", "🏠", 1_000_000, 80)
        categories.setMcc(home, setOf(5712))
        runBlocking { ingest.ingest(listOf(txn("t1", mcc = 5712))) }
        assertEquals(home, transactions.byId("t1")!!.categoryId)

        categories.update(categories.byId(home)!!.copy(enabled = false))
        // Any recategorization trigger, anywhere — not just one touching "Home" — must
        // leave the disabled category's history alone.
        runBlocking { ingest.recategorizeAll() }

        assertEquals(home, transactions.byId("t1")!!.categoryId, "disabling a category must not rewrite its history")
    }

    @Test
    fun `seedDefaultCategories reports counts and skips what is already taken`() = withTestDb { db ->
        val (ingest, categories, _) = wire(db)
        val defaults = listOf(
            app.budget.DefaultCategory("A", "🅰️", listOf(1111, 2222)),
            app.budget.DefaultCategory("B", "🅱️", listOf(3333)),
        )
        val taken = categories.create("Taken", "❓", 100, 80)
        categories.setMcc(taken, setOf(2222))

        val outcome = runBlocking { ingest.seedDefaultCategories(defaults) }

        assertEquals(listOf("A", "B"), outcome.categoriesCreated)
        assertTrue(outcome.categoriesExisting.isEmpty())
        assertEquals(2, outcome.mccAdded) // 1111 and 3333
        assertEquals(listOf(2222), outcome.mccSkipped)

        val a = categories.list().single { it.name == "A" }
        assertEquals(0L, a.monthlyLimitMinor)
        assertEquals(setOf(1111), categories.mccOf(a.id))
    }

    @Test
    fun `seedDefaultCategories run twice is a no-op the second time`() = withTestDb { db ->
        val (ingest, categories, _) = wire(db)
        val defaults = listOf(app.budget.DefaultCategory("A", "🅰️", listOf(1111)))
        runBlocking { ingest.seedDefaultCategories(defaults) }

        val second = runBlocking { ingest.seedDefaultCategories(defaults) }

        assertTrue(second.categoriesCreated.isEmpty())
        assertEquals(listOf("A"), second.categoriesExisting)
        assertEquals(0, second.mccAdded, "re-adding an mcc the category already owns must not count as new")
        assertTrue(second.mccSkipped.isEmpty(), "owning your own mcc is not a conflict")
        assertEquals(1, categories.list().size)
    }

    @Test
    fun `seeding defaults after 4829 is marked conduit skips it instead of aborting the rest`() = withTestDb { db ->
        // The owner is expected to mark 4829 conduit by hand after the deploy (spec §9,
        // step 6); the seed button's own promise is "safe to press repeatedly". Each
        // addMcc is its own SQLite transaction, so an uncaught ConduitMccException used to
        // abort this forEach mid-list — "Різне" is 9th of 11 defaults, so "Подарунки" and
        // "Поповнення мобільного" were never created.
        val (ingest, categories, _) = wire(db)
        ConduitMccRepository(db).replaceAll(setOf(4829))

        val outcome = runBlocking { ingest.seedDefaultCategories(app.budget.DEFAULT_CATEGORIES) }

        // A conduit code is not owned by anyone, so it must not land in mccSkipped —
        // that list is for ownership conflicts and "already taken" would be a lie here.
        assertTrue(outcome.mccConduitSkipped.contains(4829), outcome.mccConduitSkipped.toString())
        assertTrue(outcome.mccSkipped.isEmpty(), outcome.mccSkipped.toString())
        assertEquals(app.budget.DEFAULT_CATEGORIES.map { it.name }, outcome.categoriesCreated)
        assertTrue(categories.list().any { it.name == "Подарунки" })
        assertTrue(categories.list().any { it.name == "Поповнення мобільного" })
    }

    @Test
    fun `seedDefaultCategories recategorizes already-imported history`() = withTestDb { db ->
        val (ingest, categories, transactions) = wire(db)
        transactions.upsert(txn("t1", mcc = 1111))
        val defaults = listOf(app.budget.DefaultCategory("A", "🅰️", listOf(1111)))

        runBlocking { ingest.seedDefaultCategories(defaults) }

        val a = categories.list().single { it.name == "A" }
        assertEquals(a.id, transactions.byId("t1")!!.categoryId)
    }

    // --- setTransactionCategoryChoice: the transactions-page dropdown's entry point ---------
    //
    // Spec: the same decision (choosing a category for a transaction) used to behave
    // differently depending on where it was made — a Telegram button bound the MCC and
    // rewrote history, the web dropdown touched only the one row. These tests pin the
    // unified behaviour, each written so it fails if the old, row-only behaviour crept back.

    @Test
    fun `choosing a category for one of several transactions sharing an mcc moves all of them, including ones already imported`() =
        withTestDb { db ->
            val (ingest, categories, transactions) = wire(db)
            val logistics = categories.create("Пошта", "📦", 500_000, 80)
            runBlocking {
                ingest.ingest(listOf(txn("t1", mcc = 4111)))
                ingest.ingest(listOf(txn("t2", mcc = 4111)))
                ingest.ingest(listOf(txn("t3", mcc = 4111)))
            }

            val outcome = runBlocking { ingest.setTransactionCategoryChoice("t2", logistics) }

            check(outcome is CategoryChoiceOutcome.Bound)
            // A row-only implementation would report movedCount == 1 (just t2) and leave
            // t1/t3 uncategorized below — this assertion fails against that behaviour.
            assertEquals(3, outcome.movedCount)
            assertEquals(4111, outcome.mcc)
            assertEquals("ATB", outcome.merchant)
            assertEquals(logistics, transactions.byId("t1")!!.categoryId, "already-imported t1 must move too")
            assertEquals(logistics, transactions.byId("t2")!!.categoryId)
            assertEquals(logistics, transactions.byId("t3")!!.categoryId)
            assertEquals(mapOf(4111 to logistics), categories.mccMapping(), "the mcc itself must now be bound")
        }

    @Test
    fun `a transaction with no mcc assigns only itself`() = withTestDb { db ->
        val (ingest, categories, transactions) = wire(db)
        val home = categories.create("Home", "🏠", 1_000_000, 80)
        runBlocking { ingest.ingest(listOf(txn("t1", mcc = null))) }

        val outcome = runBlocking { ingest.setTransactionCategoryChoice("t1", home) }

        assertTrue(outcome is CategoryChoiceOutcome.SingleRow, "no mcc means nothing to bind: $outcome")
        assertEquals(home, transactions.byId("t1")!!.categoryId)
        assertTrue(transactions.byId("t1")!!.manuallyCategorized)
        assertTrue(categories.mccMapping().isEmpty(), "nothing should have been bound")
    }

    @Test
    fun `a conflicting mcc reports the owning category and changes nothing`() = withTestDb { db ->
        val (ingest, categories, transactions) = wire(db)
        val restaurants = categories.create("Restaurants", "🍔", 500_000, 80)
        val groceries = categories.create("Groceries", "🛒", 500_000, 80)
        categories.setMcc(restaurants, setOf(5812))
        runBlocking { ingest.ingest(listOf(txn("t1", mcc = 5812))) }

        val outcome = runBlocking { ingest.setTransactionCategoryChoice("t1", groceries) }

        check(outcome is CategoryChoiceOutcome.Conflict)
        assertEquals(5812, outcome.mcc)
        assertEquals("Restaurants", outcome.ownerName)
        assertEquals(restaurants, transactions.byId("t1")!!.categoryId, "the conflict must not steal or clear anything")
        assertEquals(mapOf(5812 to restaurants), categories.mccMapping())
    }

    @Test
    fun `clearing a category leaves the mcc mapping intact`() = withTestDb { db ->
        val (ingest, categories, transactions) = wire(db)
        val logistics = categories.create("Пошта", "📦", 500_000, 80)
        categories.setMcc(logistics, setOf(4111))
        runBlocking {
            ingest.ingest(listOf(txn("t1", mcc = 4111)))
            ingest.ingest(listOf(txn("t2", mcc = 4111)))
        }

        val outcome = runBlocking { ingest.setTransactionCategoryChoice("t1", null) }

        assertTrue(outcome is CategoryChoiceOutcome.Cleared)
        assertEquals(null, transactions.byId("t1")!!.categoryId)
        assertTrue(transactions.byId("t1")!!.manuallyCategorized, "cleared row must be manual or the mapping would repopulate it")
        assertEquals(logistics, transactions.byId("t2")!!.categoryId, "clearing one row must not touch the mapping or its sibling")
        assertEquals(mapOf(4111 to logistics), categories.mccMapping(), "the binding itself must survive a clear")
    }

    @Test
    fun `the only-this-one path unbinds the code, marks the single row, and leaves the others uncategorized`() =
        withTestDb { db ->
            val (ingest, categories, transactions) = wire(db)
            val logistics = categories.create("Пошта", "📦", 500_000, 80)
            runBlocking {
                ingest.ingest(listOf(txn("t1", mcc = 4111)))
                ingest.ingest(listOf(txn("t2", mcc = 4111)))
                ingest.ingest(listOf(txn("t3", mcc = 4111)))
                ingest.setTransactionCategoryChoice("t1", logistics) // binds all three
            }
            assertEquals(mapOf(4111 to logistics), categories.mccMapping())
            val gifts = categories.create("Gifts", "🎁", 200_000, 80)

            runBlocking { ingest.applyCategoryToSingleTransaction("t1", gifts) }

            assertEquals(gifts, transactions.byId("t1")!!.categoryId)
            assertTrue(transactions.byId("t1")!!.manuallyCategorized)
            assertTrue(categories.mccMapping().isEmpty(), "the mcc must be unbound, not just reassigned")
            assertEquals(null, transactions.byId("t2")!!.categoryId, "swept siblings must revert to uncategorized")
            assertEquals(null, transactions.byId("t3")!!.categoryId)
        }

    @Test
    fun `a manually categorized row is not swept up by a later binding of its mcc`() = withTestDb { db ->
        val (ingest, categories, transactions) = wire(db)
        val gifts = categories.create("Gifts", "🎁", 200_000, 80)
        val logistics = categories.create("Пошта", "📦", 500_000, 80)
        runBlocking {
            ingest.ingest(listOf(txn("t1", mcc = 4111)))
            ingest.ingest(listOf(txn("t2", mcc = 4111)))
            ingest.setTransactionCategory("t1", gifts) // e.g. a courier purchase that was really a gift
        }

        val outcome = runBlocking { ingest.setTransactionCategoryChoice("t2", logistics) }

        check(outcome is CategoryChoiceOutcome.Bound)
        assertEquals(gifts, transactions.byId("t1")!!.categoryId, "the manual override must survive the later binding")
        assertTrue(transactions.byId("t1")!!.manuallyCategorized)
        assertEquals(logistics, transactions.byId("t2")!!.categoryId)
    }

    @Test
    fun `bindMcc conflict throws and leaves the mapping unchanged`() = withTestDb { db ->
        val (ingest, categories, _) = wire(db)
        val restaurants = categories.create("Restaurants", "🍔", 500_000, 80)
        val groceries = categories.create("Groceries", "🛒", 500_000, 80)
        categories.setMcc(restaurants, setOf(5812))

        assertFailsWith<MccConflictException> { runBlocking { ingest.bindMcc(5812, groceries) } }
        assertEquals(mapOf(5812 to restaurants), categories.mccMapping())
    }

    @Test
    fun `binding an mcc can push a category over its limit and notify`() = withTestDb { db ->
        val telegram = FakeTelegramClient()
        val (ingest, categories, _) = wire(db, telegram)
        val home = categories.create("Home", "🏠", 50_000, 80)
        runBlocking {
            ingest.ingest(listOf(txn("t1", mcc = 5712, amountMinor = -60_000)))
            assertEquals(0, telegram.sent.size)
            ingest.bindMcc(5712, home)
        }
        assertEquals(1, telegram.sent.size)
        val exceededHeadline = UkCopy.budgetExceeded("🏠 Home", "L", "S", "O").substringBefore("\n\n")
        assertTrue(telegram.sent.single().text.contains(exceededHeadline), telegram.sent.single().text)
    }

    // --- transfers: conduit codes, recipient keys, counterparty rules -----------------------

    private fun transferTxn(id: String, amountMinor: Long, rawJson: String) = Txn(
        id = id, accountId = "acc-1", occurredAt = 1_787_011_200L, month = "2026-08",
        amountMinor = amountMinor, currencyCode = 980, description = "На картку",
        mcc = 4829, originalMcc = 4829, hold = false,
        categoryId = null, manuallyCategorized = false, rawJson = rawJson,
    )

    private class Fixture(db: Database) {
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val transactions = TransactionRepository(db)
        val counterparties = CounterpartyRepository(db)
        val conduit = ConduitMccRepository(db)
        val telegram = FakeTelegramClient()
        val settings = SettingsRepository(db, Crypto(ByteArray(32) { it.toByte() })).apply {
            set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        }
        val budget = BudgetService(categories, transactions)
        val notifier = Notifier(telegram, settings, NotificationEventRepository(db))
        val prompts = MccPromptRepository(db)

        // Mirrors Main.kt: MccPromptService needs the ingest service and vice versa; the
        // provider lambda breaks the cycle without a DI container. A fixture that skipped
        // this and passed UnknownMccObserver.NoOp would prove nothing about the real wiring.
        lateinit var ingest: IngestService
        val promptService = MccPromptService(
            prompts, categories, settings, telegram, conduit, transactions, counterparties,
        ) { ingest }

        init {
            ingest = IngestService(
                transactions, categories, budget, notifier, counterparties, conduit,
                AccountRepository(db), promptService,
            )
        }
    }

    private fun fixture(db: Database) = Fixture(db)

    @Test
    fun `a transfer gets a recipient key and stays uncategorized until asked`() = withTestDb { db ->
        val fixture = fixture(db)
        fixture.conduit.replaceAll(setOf(4829))

        runBlocking {
            fixture.ingest.ingest(listOf(transferTxn("t1", -250_000, """{"counterName":"Петренко Іван"}""")))
        }

        val stored = fixture.transactions.byId("t1")!!
        assertEquals("name:петренко іван", stored.counterpartyKey)
        assertEquals("name", stored.counterpartySource)
        assertNull(stored.categoryId)
    }

    @Test
    fun `answering a transfer creates a rule that also sweeps up the other transfers to the same recipient`() =
        withTestDb { db ->
            val fixture = fixture(db)
            fixture.conduit.replaceAll(setOf(4829))
            val rent = fixture.categories.create("Оренда", "🏠", 0, 80)
            val raw = """{"counterIban":"UA213223130000260072335660001"}"""
            runBlocking {
                fixture.ingest.ingest(
                    listOf(transferTxn("t1", -250_000, raw), transferTxn("t2", -250_000, raw)),
                )
                val outcome = fixture.ingest.setTransactionCategoryChoice("t1", rent)
                assertTrue(outcome is CategoryChoiceOutcome.CounterpartyBound, outcome.toString())
                assertEquals(2, (outcome as CategoryChoiceOutcome.CounterpartyBound).movedCount)
            }

            assertEquals(rent, fixture.transactions.byId("t2")!!.categoryId)
            // Governed by the rule, not frozen by a manual flag: changing the rule must
            // still reach this row.
            assertTrue(!fixture.transactions.byId("t2")!!.manuallyCategorized)
        }

    @Test
    fun `a rule remembers the recipient the question named, not the row's description`() = withTestDb { db ->
        val fixture = fixture(db)
        fixture.conduit.replaceAll(setOf(4829))
        val rent = fixture.categories.create("Оренда", "🏠", 0, 80)
        // description is the generic bank label ("На картку"); the recipient the Telegram
        // question actually rendered comes from counterName in the payload. The rule must
        // record that name, not the description, or the recipient rules list on /categories
        // becomes a column of "На картку" pointing at different categories.
        val raw = """{"counterIban":"UA213223130000260072335660001","counterName":"Петренко Іван"}"""
        runBlocking {
            fixture.ingest.ingest(listOf(transferTxn("t1", -250_000, raw)))
            val outcome = fixture.ingest.setTransactionCategoryChoice("t1", rent)
            assertTrue(outcome is CategoryChoiceOutcome.CounterpartyBound, outcome.toString())
            assertEquals("Петренко Іван", (outcome as CategoryChoiceOutcome.CounterpartyBound).displayName)
        }
        assertEquals("Петренко Іван", fixture.counterparties.list().single().displayName)
    }

    @Test
    fun `a rule falls back to the counterparty key when nothing else names the recipient`() = withTestDb { db ->
        val fixture = fixture(db)
        fixture.conduit.replaceAll(setOf(4829))
        val rent = fixture.categories.create("Оренда", "🏠", 0, 80)
        // counterIban is exact (a rule is created) but the payload carries no counterName,
        // and here even Monobank's own description is blank for this transfer type —
        // counterpartyOf still returns a non-null Counterparty in that case, just one whose
        // displayName is "". Without a floor the rule ends up with an empty label and the
        // /categories rules list renders "→ Оренда" with nobody named on the left.
        val raw = """{"counterIban":"UA213223130000260072335660001"}"""
        runBlocking {
            fixture.ingest.ingest(listOf(transferTxn("t1", -250_000, raw).copy(description = "")))
            val outcome = fixture.ingest.setTransactionCategoryChoice("t1", rent)
            assertTrue(outcome is CategoryChoiceOutcome.CounterpartyBound, outcome.toString())
            assertTrue(
                (outcome as CategoryChoiceOutcome.CounterpartyBound).displayName.isNotBlank(),
                "a rule must always be identifiable by something",
            )
        }
        assertTrue(fixture.counterparties.list().single().displayName.isNotBlank())
    }

    @Test
    fun `a name-derived key never creates a rule, only a manual override of the one row`() = withTestDb { db ->
        val fixture = fixture(db)
        fixture.conduit.replaceAll(setOf(4829))
        val rent = fixture.categories.create("Оренда", "🏠", 0, 80)
        // counterIban and counterEdrpou plausibly never arrive for a personal card (spec
        // §5's own data), so counterName — a string somebody typed, not an account — will
        // be the dominant key in production. Filing a rule off it would sweep a different
        // person with the same name, or a relative, into the same category the moment any
        // single transfer to that name is answered.
        val raw = """{"counterName":"Іван Петренко"}"""
        runBlocking {
            fixture.ingest.ingest(
                listOf(transferTxn("t1", -250_000, raw), transferTxn("t2", -250_000, raw)),
            )
            val outcome = fixture.ingest.setTransactionCategoryChoice("t1", rent)
            assertTrue(outcome is CategoryChoiceOutcome.TransferSingleRow, outcome.toString())
        }
        assertTrue(fixture.transactions.byId("t1")!!.manuallyCategorized)
        assertEquals(rent, fixture.transactions.byId("t1")!!.categoryId)
        assertTrue(fixture.counterparties.list().isEmpty(), "a probable key must never create a silent rule")
        // The second transfer to the same normalised name is untouched: no rule exists to
        // have swept it up, and it was never asked about here.
        assertNull(fixture.transactions.byId("t2")!!.categoryId)
    }

    @Test
    fun `a transfer with no identifiable recipient moves only its own row`() = withTestDb { db ->
        val fixture = fixture(db)
        fixture.conduit.replaceAll(setOf(4829))
        val misc = fixture.categories.create("Різне", "📦", 0, 80)
        runBlocking {
            fixture.ingest.ingest(listOf(transferTxn("t1", -250_000, "{}")))
            val outcome = fixture.ingest.setTransactionCategoryChoice("t1", misc)
            assertTrue(outcome is CategoryChoiceOutcome.TransferSingleRow, outcome.toString())
        }
        assertTrue(fixture.transactions.byId("t1")!!.manuallyCategorized)
        assertTrue(fixture.counterparties.list().isEmpty())
    }

    @Test
    fun `marking a code conduit unbinds it and sends its transactions back to uncategorized`() = withTestDb { db ->
        val fixture = fixture(db)
        val misc = fixture.categories.create("Різне", "📦", 0, 80)
        runBlocking {
            fixture.ingest.setCategoryMcc(misc, setOf(4829))
            fixture.ingest.ingest(listOf(transferTxn("t1", -250_000, "{}")))
            assertEquals(misc, fixture.transactions.byId("t1")!!.categoryId)

            fixture.ingest.setConduitMccs(setOf(4829))
        }
        assertTrue(fixture.categories.mccOf(misc).isEmpty())
        assertNull(fixture.transactions.byId("t1")!!.categoryId)
    }

    @Test
    fun `marking a code conduit backfills keys so rows that predate it can still be filed by recipient`() =
        withTestDb { db ->
            val fixture = fixture(db)
            val iban = """{"counterIban":"UA213223130000260072335660001"}"""
            // Ingested while 4829 carried no special meaning yet — no key was derived even
            // though raw_json has everything needed to derive one, and a settled row's
            // upsert never re-derives it (UNCHANGED, no write). Without a backfill these
            // rows would be stranded forever: uncategorized after the flip, no prompt
            // (prompts fire on insert only), and unable to be linked from the web dropdown.
            runBlocking {
                fixture.ingest.ingest(listOf(transferTxn("t1", -250_000, iban), transferTxn("t2", -250_000, iban)))
            }
            assertNull(fixture.transactions.byId("t1")!!.counterpartyKey)

            val rent = fixture.categories.create("Оренда", "🏠", 0, 80)
            runBlocking {
                fixture.ingest.setConduitMccs(setOf(4829))
                val outcome = fixture.ingest.setTransactionCategoryChoice("t1", rent)
                assertTrue(outcome is CategoryChoiceOutcome.CounterpartyBound, outcome.toString())
                assertEquals(2, (outcome as CategoryChoiceOutcome.CounterpartyBound).movedCount)
            }
            assertEquals(rent, fixture.transactions.byId("t2")!!.categoryId)
        }

    @Test
    fun `deleting a category recategorizes rows whose rule cascaded away`() = withTestDb { db ->
        val fixture = fixture(db)
        fixture.conduit.replaceAll(setOf(4829))
        val rent = fixture.categories.create("Оренда", "🏠", 0, 80)
        val misc = fixture.categories.create("Різне", "📦", 0, 80)
        val raw = """{"counterIban":"UA213223130000260072335660001"}"""
        runBlocking {
            fixture.ingest.ingest(listOf(transferTxn("t1", -250_000, raw)))
            fixture.ingest.setTransactionCategoryChoice("t1", rent) // creates the rule
            // The owner later stops treating 4829 as conduit and binds it directly to a
            // different category. The rule created above still exists — deleteCounterpartyRule
            // was never called — and layer 2 keeps outranking layer 3 for this recipient.
            fixture.ingest.setConduitMccs(emptySet())
            fixture.ingest.setCategoryMcc(misc, setOf(4829))
        }
        assertEquals(rent, fixture.transactions.byId("t1")!!.categoryId, "the rule still outranks the code")

        runBlocking { fixture.ingest.deleteCategory(rent) }

        // The schema cascades the rule away and nulls the row's category_id alongside it;
        // without a recategorize call the row would sit uncategorized until some unrelated
        // mutation happened to sweep it up, even though 4829 is now plainly bound to "Різне".
        assertEquals(misc, fixture.transactions.byId("t1")!!.categoryId, "cascade must not leave the row stranded")
    }

    @Test
    fun `asking about a transfer completes promptly even for a page of many`() = withTestDb { db ->
        val fixture = fixture(db)
        fixture.conduit.replaceAll(setOf(4829))
        fixture.categories.create("Оренда", "🏠", 0, 80)
        // The observer now runs after ingest() releases its mutex — see the KDoc on
        // ingest() and on UnknownMccObserver. This just asserts the question was
        // dispatched and ingest returned, within a timeout so a regression (the fan-out
        // moving back inside the lock, or an observer that blocks) fails loudly instead of
        // hanging the suite. `the unknown-mcc fan-out runs after the mutex is released`
        // below is the test that actually pins the ordering.
        runBlocking {
            withTimeout(5_000) {
                fixture.ingest.ingest(listOf(transferTxn("t1", -250_000, """{"counterName":"Петренко"}""")))
            }
        }
        assertEquals(1, fixture.telegram.sent.size)
    }

    // --- questions are only ever asked about accounts the owner is actually tracking ------

    @Test
    fun `nothing is asked about a transaction on an account the owner switched off`() = withTestDb { db ->
        switchOff(db, "acc-1")
        val observer = RecordingObserver()
        val (ingest, _, _) = wire(db, observer = observer)

        runBlocking { ingest.ingest(listOf(txn("t1", mcc = 5411))) }

        assertEquals(emptyList<Int?>(), observer.seen, "a switched-off account must raise no question")
    }

    @Test
    fun `a transfer on an account the owner switched off sends no Telegram message`() = withTestDb { db ->
        // The owner's own report: only one card is switched on, but topping it up from the
        // FOP card and moving the proceeds of a currency sale across both arrived as
        // transfer questions in Telegram — money that counts towards nothing on any screen.
        switchOff(db, "acc-1")
        val fixture = fixture(db)
        fixture.conduit.replaceAll(setOf(4829))
        fixture.categories.create("Оренда", "🏠", 0, 80)

        runBlocking {
            fixture.ingest.ingest(listOf(transferTxn("t1", -4_460_000, """{"counterName":"Петренко"}""")))
        }

        assertTrue(fixture.telegram.sent.isEmpty(), "a switched-off account must raise no question")
    }

    @Test
    fun `a transaction on a dollar account is refused outright, not merely left unasked`() = withTestDb { db ->
        // The owner's report: the Telegram questions were about his dollar account. Its
        // amounts are cents, and amount_minor is hryvnia kopecks everywhere — storing one
        // puts a number in the database that /transactions prints with a ₴ on it and that
        // spentByCategory adds to the month. Refused on the way in (owner's ruling,
        // 2026-09-22), not stored and filtered at every reader.
        storeDollarAccount(db, "acc-1")
        val observer = RecordingObserver()
        val (ingest, _, transactions) = wire(db, observer = observer)

        // -4 460 000 minor units: $44 600 if read as cents, "44 600 ₴" as this app renders it.
        val outcome = runBlocking { ingest.ingest(listOf(txn("t1", amountMinor = -4_460_000, mcc = 4829))) }

        assertEquals(IngestOutcome(inserted = 0, updated = 0, unchanged = 0, skipped = 1), outcome)
        assertNull(transactions.byId("t1"), "nothing from a dollar account may reach the table")
        assertEquals(emptyList<Int?>(), observer.seen)
    }

    /**
     * End-to-end through the real Main-like wiring. Two independent gates have to fail
     * before this message can be sent — the row is never stored, *and* a dollar account is
     * untracked so nothing on it is ever asked about — which is the point: this stays green
     * if either one is removed, and only goes red if both are.
     */
    @Test
    fun `a dollar transfer sends no Telegram message`() = withTestDb { db ->
        storeDollarAccount(db, "acc-1")
        val fixture = fixture(db)
        fixture.conduit.replaceAll(setOf(4829))
        fixture.categories.create("Оренда", "🏠", 0, 80)

        runBlocking {
            fixture.ingest.ingest(listOf(transferTxn("t1", -4_460_000, """{"counterName":"Петренко"}""")))
        }

        assertTrue(fixture.telegram.sent.isEmpty(), "a dollar account must raise no question")
    }

    @Test
    fun `a hryvnia card used abroad is still stored and still asked about`() = withTestDb { db ->
        // The gate is on the *account's* currency, never the row's. Monobank's
        // transactions.currency_code is the **operation** currency, so a hryvnia card used
        // abroad carries 840 on a row whose amount_minor is perfectly good kopecks —
        // filtering on it would drop every foreign purchase made on the tracked card.
        val accounts = AccountRepository(db)
        accounts.replaceAll(listOf(MonoAccount(id = "acc-1", currencyCode = UAH_CURRENCY_CODE)))
        val observer = RecordingObserver()
        val (ingest, _, transactions) = wire(db, observer = observer)

        val abroad = txn("t1", mcc = 5812).copy(currencyCode = 840, description = "Starbucks")
        runBlocking { ingest.ingest(listOf(abroad)) }

        assertEquals(-84_000L, transactions.byId("t1")?.amountMinor)
        assertEquals(listOf<Int?>(5812), observer.seen)
    }

    @Test
    fun `account edits go through the ingest mutex, not around it`() = withTestDb { db ->
        // The accounts table stopped being read-only as far as ingest is concerned the
        // moment ingestLocked started reading it to decide whether a transaction is stored
        // at all. That puts it in the same position as `categories` (CLAUDE.md, *The
        // single-writer rule*): a Settings save or an hourly refresh landing mid-batch
        // would have half a statement page judged against the old account list. These two
        // methods are what the web route and SyncService call now; a regression that sends
        // either back to AccountRepository directly reopens that window.
        val accounts = AccountRepository(db)
        val (ingest, _, transactions) = wire(db)

        runBlocking {
            ingest.replaceAccounts(
                listOf(
                    MonoAccount(id = "acc-1", currencyCode = UAH_CURRENCY_CODE),
                    MonoAccount(id = "acc-usd", currencyCode = 840),
                ),
            )
        }
        assertEquals(setOf("acc-1", "acc-usd"), accounts.ids().toSet())

        runBlocking { ingest.setAccountsActive(emptySet()) }
        assertEquals(emptySet(), accounts.activeIds().toSet())

        // Proves the lock is actually released, not merely taken: a nested ingest through
        // the same non-reentrant mutex would hang here rather than fail.
        runBlocking { withTimeout(5_000) { ingest.ingest(listOf(txn("t1"))) } }
        assertNull(transactions.byId("t1")?.categoryId)
    }

    @Test
    fun `a transaction on a switched-on account is still asked about`() = withTestDb { db ->
        val accounts = AccountRepository(db)
        accounts.replaceAll(listOf(MonoAccount(id = "acc-1", currencyCode = UAH_CURRENCY_CODE)))
        accounts.setActive(setOf("acc-1"))
        val observer = RecordingObserver()
        val (ingest, _, _) = wire(db, observer = observer)

        runBlocking { ingest.ingest(listOf(txn("t1", mcc = 5411))) }

        assertEquals(listOf<Int?>(5411), observer.seen)
    }

    @Test
    fun `a transaction on an account nobody has synced yet is still asked about`() = withTestDb { db ->
        // Mirrors spentByCategory's LEFT JOIN from the other side: an account with no row
        // counts as active. A webhook can land before the first account sync, and reading
        // "unknown" as "switched off" would silently stop asking about the owner's own card.
        val observer = RecordingObserver()
        val (ingest, _, _) = wire(db, observer = observer)

        runBlocking { ingest.ingest(listOf(txn("t1", mcc = 5411))) }

        assertEquals(listOf<Int?>(5411), observer.seen)
    }

    @Test
    fun `the unknown-mcc fan-out runs after the mutex is released, not while it is held`() = withTestDb { db ->
        // Regression guard for the hoist in ingest(): moving
        // `newlyUnknown.forEach { unknownMcc.onUnknownMcc(it) }` back inside
        // `mutex.withLock` would make this observer's call into setTransactionCategory
        // re-enter the (non-reentrant) mutex from the same coroutine and hang forever.
        // withTimeout turns that hang into a fast, loud failure instead of wedging the
        // suite. Nothing here exercises Telegram at all — a plain observer proves the
        // ordering by itself, independent of MccPromptService's own behaviour.
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val groceries = categories.create("Groceries", "🛒", 0, 80)

        lateinit var ingestHolder: IngestService
        val observer = object : UnknownMccObserver {
            override suspend fun onUnknownMcc(txn: Txn) {
                ingestHolder.setTransactionCategory(txn.id, groceries)
            }
        }
        val (ingest, _, transactions) = wire(db, observer = observer)
        ingestHolder = ingest

        runBlocking {
            withTimeout(5_000) {
                ingest.ingest(listOf(txn("t1")))
            }
        }

        assertEquals(
            groceries,
            transactions.byId("t1")!!.categoryId,
            "the observer's own mutex-taking call must have completed, not deadlocked",
        )
    }
}
