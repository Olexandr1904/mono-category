package app.budget

import app.ingest.AccountRepository
import app.mono.MonoAccount
import app.withTestDb
import kotlin.test.Test
import kotlin.test.assertEquals

class BudgetServiceTest {

    private val august = 1_787_011_200L

    private fun txn(id: String, amountMinor: Long, categoryId: Long?) = Txn(
        id = id, accountId = "acc-1", occurredAt = august, month = monthKeyOf(august),
        amountMinor = amountMinor, currencyCode = 980, description = "x",
        mcc = 5411, originalMcc = 5411, hold = false,
        categoryId = categoryId, manuallyCategorized = false, rawJson = "{}",
    )

    @Test
    fun `statuses follow the threshold and the limit`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val transactions = TransactionRepository(db)
        val normal = categories.create("Auto", "🚗", 1_000_000, 80)
        val warning = categories.create("Restaurants", "🍔", 1_500_000, 80)
        val exceeded = categories.create("Home", "🏠", 1_000_000, 80)
        transactions.upsert(txn("t1", -780_000, normal))     // 78%
        transactions.upsert(txn("t2", -1_243_000, warning))  // 82%
        transactions.upsert(txn("t3", -1_024_000, exceeded)) // 102%

        val summary = BudgetService(categories, transactions).monthSummary(monthKeyOf(august))
        val byId = summary.categories.associateBy { it.category.id }
        assertEquals(SpendStatus.NORMAL, byId[normal]!!.status)
        assertEquals(SpendStatus.WARNING, byId[warning]!!.status)
        assertEquals(SpendStatus.EXCEEDED, byId[exceeded]!!.status)
        assertEquals(82, byId[warning]!!.pct)
    }

    @Test
    fun `a per-category threshold overrides the default`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val transactions = TransactionRepository(db)
        val id = categories.create("Auto", "🚗", 1_000_000, 50)
        transactions.upsert(txn("t1", -600_000, id)) // 60%, above its own 50%
        val summary = BudgetService(categories, transactions).monthSummary(monthKeyOf(august))
        assertEquals(SpendStatus.WARNING, summary.categories.single().status)
    }

    @Test
    fun `total includes uncategorized spending`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val transactions = TransactionRepository(db)
        val id = categories.create("Auto", "🚗", 1_000_000, 80)
        transactions.upsert(txn("t1", -100_000, id))
        transactions.upsert(txn("t2", -50_000, null))
        val summary = BudgetService(categories, transactions).monthSummary(monthKeyOf(august))
        assertEquals(150_000, summary.totalSpentMinor)
        assertEquals(50_000, summary.uncategorizedMinor)
    }

    @Test
    fun `a category with no limit is always normal and reports zero percent`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val transactions = TransactionRepository(db)
        val id = categories.create("Misc", "📦", 0, 80)
        transactions.upsert(txn("t1", -900_000, id))
        val spending = BudgetService(categories, transactions)
            .monthSummary(monthKeyOf(august)).categories.single()
        assertEquals(SpendStatus.NORMAL, spending.status)
        assertEquals(0, spending.pct)
        assertEquals(900_000, spending.spentMinor)
    }

    @Test
    fun `a category holding only incoming money reports zero, not negative`() = withTestDb { db ->
        // Old rule: every sign summed and flipped, so a category with more incoming money
        // than spending produced a negative total, "reported honestly" with only the
        // percent bar clamped to zero. That number was worse than useless — Monobank can't
        // tell a refund from an incoming transfer, and on the owner's real August it turned
        // a transfers category into -120 000,00 ₴, dragging the whole month's headline
        // total below the true spend. New rule: only negative amounts count as spending,
        // so a category holding nothing but incoming money is simply zero, same as an
        // empty category. spentMinor can no longer go negative, so the `.coerceAtLeast(0)`
        // clamp in BudgetService.monthSummary is now defensive/unreachable rather than
        // load-bearing — left in place deliberately as a guard, not deleted.
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val transactions = TransactionRepository(db)
        val id = categories.create("Auto", "🚗", 1_000_000, 80)
        transactions.upsert(txn("t1", 300_000, id))
        val spending = BudgetService(categories, transactions)
            .monthSummary(monthKeyOf(august)).categories.single()
        assertEquals(0, spending.spentMinor)
        assertEquals(0, spending.pct)
    }

    @Test
    fun `categories with no transactions still appear with zero spending`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        categories.create("Auto", "🚗", 1_000_000, 80)
        val summary = BudgetService(categories, TransactionRepository(db))
            .monthSummary(monthKeyOf(august))
        assertEquals(1, summary.categories.size)
        assertEquals(0, summary.categories.single().spentMinor)
    }

    @Test
    fun `a category marked not counted as spending keeps its own amount but leaves the total`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val transactions = TransactionRepository(db)
        val ordinary = categories.create("Groceries", "🛒", 0, 80)
        val selfTransfers = categories.create("Свої рахунки", "🔁", 0, 80)
        categories.update(categories.byId(selfTransfers)!!.copy(countsAsSpending = false))
        transactions.upsert(txn("t1", -100_000, ordinary))
        transactions.upsert(txn("t2", -900_000, selfTransfers))

        val summary = BudgetService(categories, transactions).monthSummary(monthKeyOf(august))

        // The total ("Витрачено") only ever reflects real spending.
        assertEquals(100_000, summary.totalSpentMinor)
        // But the excluded category still reports its real amount — visible, just not
        // counted — rather than silently reading zero as if nothing happened there.
        val byId = summary.categories.associateBy { it.category.id }
        assertEquals(900_000, byId.getValue(selfTransfers).spentMinor)
        assertEquals(100_000, byId.getValue(ordinary).spentMinor)
    }

    @Test
    fun `an inactive account and an uncounted category compose — either one excludes a transaction`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val transactions = TransactionRepository(db)
        val accounts = AccountRepository(db)
        accounts.replaceAll(listOf(MonoAccount(id = "fop", currencyCode = 980), MonoAccount(id = "black-card", currencyCode = 980)))
        accounts.setActive(setOf("black-card")) // the ФОП account is switched off

        val groceries = categories.create("Groceries", "🛒", 0, 80)
        val selfTransfers = categories.create("Свої рахунки", "🔁", 0, 80)
        categories.update(categories.byId(selfTransfers)!!.copy(countsAsSpending = false))

        // Mechanism A: money leaving the inactive ФОП account, in an ordinary category.
        transactions.upsert(
            Txn(
                "t1", "fop", august, monthKeyOf(august), -50_000_000, 980, "На чорну картку", // 500 000,00 ₴
                4829, 4829, false, groceries, false, "{}",
            ),
        )
        // Mechanism B: a black-card-to-yellow-card self transfer — both accounts active,
        // only the category flag catches it.
        transactions.upsert(
            Txn(
                "t2", "black-card", august, monthKeyOf(august), -100_000, 980, "На жовту картку",
                4829, 4829, false, selfTransfers, false, "{}",
            ),
        )
        // Real spend: active account, ordinary category.
        transactions.upsert(txn("t3", -50_000, groceries))

        val summary = BudgetService(categories, transactions).monthSummary(monthKeyOf(august))
        assertEquals(50_000, summary.totalSpentMinor, "only the real spend counts")
    }
}
