package app.budget

import app.ingest.AccountRepository
import app.mono.MonoAccount
import app.withTestDb
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransactionRepositoryTest {

    // transactions.category_id carries a real FK to categories(id), enforced on every
    // connection (see Database.kt). Seed the ids a test references before using them,
    // rather than disabling enforcement or routing through CategoryRepository.create(),
    // which would hand out autoincrement ids instead of the fixed ids these tests assert
    // against (e.g. 7, 9). Raw SQL with explicit ids; INSERT OR IGNORE keeps it safe to
    // call more than once per test.
    private fun seedCategories(db: Database, vararg ids: Long) = transaction(db) {
        ids.forEach { cid ->
            exec(
                """INSERT OR IGNORE INTO categories
                   (id, name, emoji, monthly_limit_minor, threshold_pct,
                    notify_warning, notify_exceeded, enabled, position, created_at)
                   VALUES ($cid, 'seed $cid', '', 0, 80, 1, 1, 1, 0, 0)"""
            )
        }
    }

    private fun txn(
        id: String,
        amountMinor: Long = -84_000,
        mcc: Int? = 5411,
        hold: Boolean = false,
        description: String = "ATB",
        occurredAt: Long = 1_787_011_200,
        categoryId: Long? = null,
        manual: Boolean = false,
        accountId: String = "acc-1",
    ) = Txn(
        id = id,
        accountId = accountId,
        occurredAt = occurredAt,
        month = monthKeyOf(occurredAt),
        amountMinor = amountMinor,
        currencyCode = 980,
        description = description,
        mcc = mcc,
        originalMcc = mcc,
        hold = hold,
        categoryId = categoryId,
        manuallyCategorized = manual,
        rawJson = "{}",
    )

    @Test
    fun `first insert reports INSERTED and stores the row`() = withTestDb { db ->
        val repo = TransactionRepository(db)
        assertEquals(UpsertResult.INSERTED, repo.upsert(txn("t1")))
        val stored = repo.byId("t1")!!
        assertEquals(-84_000, stored.amountMinor)
        assertEquals(5411, stored.mcc)
    }

    @Test
    fun `redelivering the same finalized transaction changes nothing`() = withTestDb { db ->
        val repo = TransactionRepository(db)
        repo.upsert(txn("t1", amountMinor = -84_000, hold = false))
        assertEquals(UpsertResult.UNCHANGED, repo.upsert(txn("t1", amountMinor = -99_900, hold = false)))
        assertEquals(-84_000, repo.byId("t1")!!.amountMinor)
    }

    @Test
    fun `settlement updates a held transaction`() = withTestDb { db ->
        val repo = TransactionRepository(db)
        repo.upsert(txn("t1", amountMinor = -84_000, hold = true, description = "HOLD"))
        assertEquals(
            UpsertResult.UPDATED,
            repo.upsert(txn("t1", amountMinor = -85_500, hold = false, description = "ATB 123")),
        )
        val stored = repo.byId("t1")!!
        assertEquals(-85_500, stored.amountMinor)
        assertFalse(stored.hold)
        assertEquals("ATB 123", stored.description)
    }

    @Test
    fun `settlement preserves a manual category`() = withTestDb { db ->
        seedCategories(db, 7L)
        val repo = TransactionRepository(db)
        repo.upsert(txn("t1", hold = true))
        repo.setCategory("t1", 7L, manual = true)
        repo.upsert(txn("t1", amountMinor = -85_500, hold = false, categoryId = null))
        val stored = repo.byId("t1")!!
        assertEquals(7L, stored.categoryId)
        assertTrue(stored.manuallyCategorized)
    }

    @Test
    fun `spentByCategory sums by category and flips the sign`() = withTestDb { db ->
        seedCategories(db, 1L, 2L)
        val repo = TransactionRepository(db)
        repo.upsert(txn("t1", amountMinor = -84_000, categoryId = 1L))
        repo.upsert(txn("t2", amountMinor = -32_000, categoryId = 1L))
        repo.upsert(txn("t3", amountMinor = -243_000, categoryId = 2L))
        val month = monthKeyOf(1_787_011_200)
        assertEquals(mapOf<Long?, Long>(1L to 116_000L, 2L to 243_000L), repo.spentByCategory(month))
    }

    @Test
    fun `a positive amount does not reduce spending in its category`() = withTestDb { db ->
        // Monobank reports incoming transfers as positive amounts with the same shape as a
        // refund — MCC 4829, no field that tells them apart. Summing every sign (the
        // original spec) let one large incoming transfer drag a category's total below
        // zero and understate real spending; on the owner's real August it turned a
        // -120 000,00 ₴ transfers category into a headline number that was confidently
        // wrong rather than merely incomplete. The owner's ruling: only negative amounts
        // count as spending. Under-counting a genuine refund is an accepted trade-off.
        seedCategories(db, 1L)
        val repo = TransactionRepository(db)
        repo.upsert(txn("t1", amountMinor = -84_000, categoryId = 1L))
        repo.upsert(txn("t2", amountMinor = 20_000, categoryId = 1L))
        assertEquals(84_000L, repo.spentByCategory(monthKeyOf(1_787_011_200))[1L])
    }

    @Test
    fun `a category with only incoming money reports zero, not negative`() = withTestDb { db ->
        // Pins the new rule directly: a category holding nothing but incoming money
        // (transfers, salary, refunds) must not show up as negative spending. It's simply
        // absent from spending, same as an empty category.
        seedCategories(db, 1L)
        val repo = TransactionRepository(db)
        repo.upsert(txn("t1", amountMinor = 200_000, categoryId = 1L))
        assertEquals(null, repo.spentByCategory(monthKeyOf(1_787_011_200))[1L])
    }

    @Test
    fun `spentByCategory reports the expense, not the larger incoming amount`() = withTestDb { db ->
        // Would fail under the old sign-flip-everything rule: -20_000 + 500_000 flipped
        // and summed comes out negative (-480_000), hiding the real 20_000 expense
        // entirely. Under the new rule only the expense counts.
        seedCategories(db, 1L)
        val repo = TransactionRepository(db)
        repo.upsert(txn("t1", amountMinor = -20_000, categoryId = 1L))
        repo.upsert(txn("t2", amountMinor = 500_000, categoryId = 1L))
        assertEquals(20_000L, repo.spentByCategory(monthKeyOf(1_787_011_200))[1L])
    }

    @Test
    fun `uncategorized rows are grouped under a null key`() = withTestDb { db ->
        val repo = TransactionRepository(db)
        repo.upsert(txn("t1", amountMinor = -50_000, categoryId = null))
        assertEquals(50_000L, repo.spentByCategory(monthKeyOf(1_787_011_200))[null])
    }

    @Test
    fun `a transaction on an account the owner switched off no longer counts as spending`() = withTestDb { db ->
        // The bug that started all this: a ФОП account whose only August activity was
        // eleven transfers to the owner's own black card counted as spending twice — once
        // leaving the ФОП account, once again when the black card's own spend was tallied.
        // Switching the account off is the fix (spec §A): its history stops shaping any
        // category total, not just future syncs.
        seedCategories(db, 1L)
        val accounts = AccountRepository(db)
        accounts.replaceAll(listOf(MonoAccount(id = "acc-1", currencyCode = 980)))
        accounts.setActive(emptySet()) // switch off acc-1
        val repo = TransactionRepository(db)
        repo.upsert(txn("t1", amountMinor = -50_000_000, categoryId = 1L)) // 500 000,00 ₴
        assertEquals(null, repo.spentByCategory(monthKeyOf(1_787_011_200))[1L])
    }

    /** acc-1 tracked, acc-off a switched-off hryvnia card, acc-usd a dollar account. */
    private fun threeAccounts(db: Database): AccountRepository {
        val accounts = AccountRepository(db)
        accounts.replaceAll(
            listOf(
                MonoAccount(id = "acc-1", currencyCode = 980),
                MonoAccount(id = "acc-off", currencyCode = 980),
                MonoAccount(id = "acc-usd", currencyCode = 840),
            ),
        )
        accounts.setActive(setOf("acc-1"))
        return accounts
    }

    @Test
    fun `countUnresolved drops what filing could never change, count does not`() = withTestDb { db ->
        // The Операції strip printed "Без категорії 0,00 ₴" beside "Не розібрано 12": the
        // amount came from spentByCategory, which drops untracked accounts, while the count
        // was unaware of accounts entirely. countUnresolved applies spentByCategory's own
        // predicate so the two figures are filtered by one rule; plain count() stays
        // account-blind because it backs "Усього: N", which must match the rendered rows.
        threeAccounts(db)
        val repo = TransactionRepository(db)
        repo.upsert(txn("t1", accountId = "acc-1"))
        repo.upsert(txn("t2", accountId = "acc-off"))
        repo.upsert(txn("t3", accountId = "acc-usd"))
        val month = monthKeyOf(1_787_011_200)

        assertEquals(1, repo.countUnresolved(month), "only the tracked account's row is worth filing")
        assertEquals(3, repo.count(month, null, true), "plain count stays account-blind on purpose")
    }

    @Test
    fun `excludeAccountIds hides dollar rows from page and count, keeping switched-off ones`() = withTestDb { db ->
        // A switched-off hryvnia row stays listed under inactiveAccountPill — its amount is
        // at least true. A dollar row's is not: formatMinor appends ₴ unconditionally, so
        // $44 600 renders "44 600 ₴" and no pill repairs a wrong number.
        val accounts = threeAccounts(db)
        val repo = TransactionRepository(db)
        repo.upsert(txn("t1", accountId = "acc-1"))
        repo.upsert(txn("t2", accountId = "acc-off"))
        repo.upsert(txn("t3", amountMinor = -4_460_000, accountId = "acc-usd"))
        val month = monthKeyOf(1_787_011_200)
        val foreign = accounts.foreignCurrencyIds()

        val listed = repo.page(month, null, false, 50, 0, excludeAccountIds = foreign).map { it.id }
        assertEquals(setOf("t1", "t2"), listed.toSet())
        assertEquals(
            listed.size.toLong(),
            repo.count(month, null, false, excludeAccountIds = foreign),
            "\"Усього: N\" must match the rows rendered beneath it",
        )
    }

    @Test
    fun `excludeAccountIds outranks includeId, which may only re-admit the owner's own filters`() = withTestDb { db ->
        // includeId exists to keep a just-filed row visible under ?uncategorized=1. It ORs
        // over the whole condition, so the account exclusion has to be ANDed on afterwards
        // or a dollar row named as includeId would walk straight back into the list.
        val accounts = threeAccounts(db)
        val repo = TransactionRepository(db)
        repo.upsert(txn("t3", accountId = "acc-usd"))

        val listed = repo.page(
            monthKeyOf(1_787_011_200), null, true, 50, 0,
            includeId = "t3", excludeAccountIds = accounts.foreignCurrencyIds(),
        )
        assertTrue(listed.isEmpty(), "a dollar row must not be re-admitted by includeId: $listed")
    }

    @Test
    fun `a transaction on a dollar account never counts as spending`() = withTestDb { db ->
        // IngestService refuses to store these now, but rows imported before that gate
        // existed are still in the owner's database — cents sitting in a kopecks column,
        // being added to the month as hryvnia. This is the reader-side half of that rule,
        // and the currency alone has to carry it: `active` is forced true here, straight
        // into the column, precisely because every ordinary path refuses to do that
        // (replaceAll inserts a dollar account switched off, setActive skips it). If this
        // passed only thanks to `active = false` it would be pinning the wrong rule and
        // would keep passing with the currency check deleted.
        seedCategories(db, 1L)
        AccountRepository(db).replaceAll(listOf(MonoAccount(id = "acc-1", currencyCode = 840)))
        transaction(db) { exec("UPDATE accounts SET active = 1 WHERE id = 'acc-1'") }

        val repo = TransactionRepository(db)
        repo.upsert(txn("t1", amountMinor = -4_460_000, categoryId = 1L)) // $44 600, not 44 600 ₴
        assertEquals(null, repo.spentByCategory(monthKeyOf(1_787_011_200))[1L])
    }

    @Test
    fun `a transaction on an active account still counts, right alongside an inactive one`() = withTestDb { db ->
        seedCategories(db, 1L)
        val accounts = AccountRepository(db)
        accounts.replaceAll(listOf(MonoAccount(id = "acc-1", currencyCode = 980), MonoAccount(id = "acc-2", currencyCode = 980)))
        accounts.setActive(setOf("acc-2")) // acc-1 switched off, acc-2 stays on
        val repo = TransactionRepository(db)
        repo.upsert(txn("t1", amountMinor = -50_000, categoryId = 1L)) // acc-1, excluded
        repo.upsert(
            Txn(
                id = "t2", accountId = "acc-2", occurredAt = 1_787_011_200, month = monthKeyOf(1_787_011_200),
                amountMinor = -30_000, currencyCode = 980, description = "x", mcc = 5411, originalMcc = 5411,
                hold = false, categoryId = 1L, manuallyCategorized = false, rawJson = "{}",
            ),
        )
        assertEquals(30_000L, repo.spentByCategory(monthKeyOf(1_787_011_200))[1L])
    }

    @Test
    fun `a transaction on an account never synced still counts — unknown is not excluded`() = withTestDb { db ->
        // No AccountRepository.replaceAll call at all here: the accounts table is empty,
        // the same shape every pre-existing test in this file is in. Treating "no matching
        // account row" as excluded would make every one of those spending totals vanish.
        seedCategories(db, 1L)
        val repo = TransactionRepository(db)
        repo.upsert(txn("t1", amountMinor = -50_000, categoryId = 1L))
        assertEquals(50_000L, repo.spentByCategory(monthKeyOf(1_787_011_200))[1L])
    }

    @Test
    fun `spending is scoped to its month`() = withTestDb { db ->
        seedCategories(db, 1L)
        val repo = TransactionRepository(db)
        val august = 1_787_011_200L      // 2026-08-18
        val september = 1_789_011_200L   // 2026-09-10
        repo.upsert(txn("t1", amountMinor = -84_000, categoryId = 1L, occurredAt = august))
        repo.upsert(txn("t2", amountMinor = -10_000, categoryId = 1L, occurredAt = september))
        assertEquals(84_000L, repo.spentByCategory(monthKeyOf(august))[1L])
        assertEquals(10_000L, repo.spentByCategory(monthKeyOf(september))[1L])
    }

    @Test
    fun `recategorize rewrites automatic rows across all months`() = withTestDb { db ->
        seedCategories(db, 3L)
        val repo = TransactionRepository(db)
        repo.upsert(txn("t1", mcc = 5712, occurredAt = 1_787_011_200))
        repo.upsert(txn("t2", mcc = 5712, occurredAt = 1_720_000_000))
        assertEquals(2, repo.recategorize(CategoryRules(mapOf(5712 to 3L), emptyMap(), emptySet())))
        assertEquals(3L, repo.byId("t1")!!.categoryId)
        assertEquals(3L, repo.byId("t2")!!.categoryId)
    }

    @Test
    fun `recategorize never touches manually categorized rows`() = withTestDb { db ->
        seedCategories(db, 9L)
        val repo = TransactionRepository(db)
        repo.upsert(txn("t1", mcc = 5712))
        repo.setCategory("t1", 9L, manual = true)
        repo.recategorize(CategoryRules(mapOf(5712 to 3L), emptyMap(), emptySet()))
        assertEquals(9L, repo.byId("t1")!!.categoryId)
    }

    @Test
    fun `recategorize clears the category when the mcc is unmapped`() = withTestDb { db ->
        seedCategories(db, 3L)
        val repo = TransactionRepository(db)
        repo.upsert(txn("t1", mcc = 5712, categoryId = 3L))
        repo.recategorize(CategoryRules(emptyMap(), emptyMap(), emptySet()))
        assertNull(repo.byId("t1")!!.categoryId)
    }

    @Test
    fun `recategorize leaves a row alone when its category is disabled`() = withTestDb { db ->
        // Spec §6: disabling a category must not rewrite already-assigned history. A row
        // pointing at a disabled category id is skipped even when the mapping no longer
        // has its mcc — the exact situation item 4's mccMapping() filtering produces.
        seedCategories(db, 3L)
        val repo = TransactionRepository(db)
        repo.upsert(txn("t1", mcc = 5712, categoryId = 3L))
        val changed = repo.recategorize(CategoryRules(emptyMap(), emptyMap(), emptySet()), disabledCategoryIds = setOf(3L))
        assertEquals(0, changed)
        assertEquals(3L, repo.byId("t1")!!.categoryId)
    }

    @Test
    fun `page filters and orders newest first`() = withTestDb { db ->
        seedCategories(db, 1L, 2L)
        val repo = TransactionRepository(db)
        repo.upsert(txn("t1", occurredAt = 1_786_911_200, categoryId = 1L))
        repo.upsert(txn("t2", occurredAt = 1_787_011_200, categoryId = 1L))
        repo.upsert(txn("t3", occurredAt = 1_786_961_200, categoryId = 2L))
        val month = monthKeyOf(1_787_011_200)
        assertEquals(listOf("t2", "t1"), repo.page(month, 1L, false, 50, 0).map { it.id })
        assertEquals(3, repo.count(month, null, false))
        assertEquals(1, repo.count(month, 2L, false))
    }

    @Test
    fun `onlyUncategorized selects rows without a category`() = withTestDb { db ->
        seedCategories(db, 1L)
        val repo = TransactionRepository(db)
        repo.upsert(txn("t1", categoryId = 1L))
        repo.upsert(txn("t2", categoryId = null))
        assertEquals(listOf("t2"), repo.page(null, null, true, 50, 0).map { it.id })
    }

    @Test
    fun `order defaults to newest first, and oldest reverses it`() = withTestDb { db ->
        val repo = TransactionRepository(db)
        repo.upsert(txn("t1", occurredAt = 1_786_911_200))
        repo.upsert(txn("t2", occurredAt = 1_787_011_200))
        repo.upsert(txn("t3", occurredAt = 1_786_961_200))
        assertEquals(listOf("t2", "t3", "t1"), repo.page(null, null, false, 50, 0).map { it.id })
        assertEquals(listOf("t2", "t3", "t1"), repo.page(null, null, false, 50, 0, TxnOrder.NEWEST).map { it.id })
        assertEquals(listOf("t1", "t3", "t2"), repo.page(null, null, false, 50, 0, TxnOrder.OLDEST).map { it.id })
    }

    @Test
    fun `largest amount sorts the biggest expense first — amounts are negative, so ascending`() = withTestDb { db ->
        val repo = TransactionRepository(db)
        repo.upsert(txn("small", amountMinor = -1_000))
        repo.upsert(txn("big", amountMinor = -50_000))
        repo.upsert(txn("refund", amountMinor = 2_000))
        // Incoming money (the "refund" row) is hidden by default (see the dedicated
        // includeIncoming tests below), so it has to be asked for explicitly here to
        // exercise ordering across the full sign range.
        assertEquals(
            listOf("big", "small", "refund"),
            repo.page(null, null, false, 50, 0, TxnOrder.LARGEST, includeIncoming = true).map { it.id },
        )
        // Smallest is the exact reverse: the refund (a positive amount) is the "smallest"
        // outgoing spend, so it sorts first.
        assertEquals(
            listOf("refund", "small", "big"),
            repo.page(null, null, false, 50, 0, TxnOrder.SMALLEST, includeIncoming = true).map { it.id },
        )
    }

    @Test
    fun `ordering combines with the existing filters`() = withTestDb { db ->
        seedCategories(db, 1L, 2L)
        val repo = TransactionRepository(db)
        repo.upsert(txn("t1", amountMinor = -1_000, categoryId = 1L))
        repo.upsert(txn("t2", amountMinor = -50_000, categoryId = 1L))
        repo.upsert(txn("t3", amountMinor = -90_000, categoryId = 2L))
        assertEquals(
            listOf("t2", "t1"),
            repo.page(null, 1L, false, 50, 0, TxnOrder.LARGEST).map { it.id },
        )
    }

    @Test
    fun `ordering is stable across pages — no row skipped or repeated`() = withTestDb { db ->
        val repo = TransactionRepository(db)
        repeat(5) { i -> repo.upsert(txn("t$i", amountMinor = -(i + 1) * 1_000L)) }
        val firstPage = repo.page(null, null, false, 3, 0, TxnOrder.LARGEST).map { it.id }
        val secondPage = repo.page(null, null, false, 3, 3, TxnOrder.LARGEST).map { it.id }
        assertEquals(listOf("t4", "t3", "t2"), firstPage)
        assertEquals(listOf("t1", "t0"), secondPage)
    }

    @Test
    fun `incoming money is hidden by default, from both page and count together`() = withTestDb { db ->
        val repo = TransactionRepository(db)
        repo.upsert(txn("spend", amountMinor = -84_000))
        repo.upsert(txn("incoming", amountMinor = 9_000)) // e.g. "Від: Pavlovska Yuliia"
        assertEquals(listOf("spend"), repo.page(null, null, false, 50, 0).map { it.id })
        assertEquals(1, repo.count(null, null, false))

        assertEquals(
            setOf("spend", "incoming"),
            repo.page(null, null, false, 50, 0, includeIncoming = true).map { it.id }.toSet(),
        )
        assertEquals(2, repo.count(null, null, false, includeIncoming = true))
    }

    @Test
    fun `an incoming transaction never counts toward the uncategorized triage view by default`() = withTestDb { db ->
        val repo = TransactionRepository(db)
        repo.upsert(txn("uncategorized-spend", amountMinor = -1_000, categoryId = null))
        repo.upsert(txn("uncategorized-incoming", amountMinor = 9_000, categoryId = null))
        assertEquals(listOf("uncategorized-spend"), repo.page(null, null, true, 50, 0).map { it.id })
        assertEquals(1, repo.count(null, null, true))
        assertEquals(2, repo.count(null, null, true, includeIncoming = true))
    }
}
