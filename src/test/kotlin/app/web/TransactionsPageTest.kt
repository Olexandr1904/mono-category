package app.web

import app.budget.BudgetService
import app.budget.CategoryRepository
import app.budget.ConduitMccRepository
import app.budget.CounterpartyRepository
import app.budget.KYIV
import app.budget.TransactionRepository
import app.budget.Txn
import app.budget.formatMinor
import app.budget.formatSignedMinor
import app.budget.monthKeyOf
import app.mono.MonoAccount
import app.db.Crypto
import app.db.SettingsRepository
import app.ingest.AccountRepository
import app.ingest.IngestService
import app.i18n.UkCopy
import app.notify.FakeTelegramClient
import app.notify.NotificationEventRepository
import app.notify.Notifier
import app.withTestDb
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.parameters
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.Database
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransactionsPageTest {

    private val august = 1_787_011_200L   // 2026-08-18 in Kyiv
    private val clock = Clock.fixed(Instant.ofEpochSecond(august), KYIV)

    private fun txn(id: String, description: String, amountMinor: Long, categoryId: Long?, at: Long = august, mcc: Int? = 5411) =
        Txn(id, "acc-1", at, monthKeyOf(at), amountMinor, 980, description,
            mcc, mcc, false, categoryId, false, "{}")

    private fun baseTxn(id: String) = Txn(
        id = id, accountId = "acc-1", occurredAt = august, month = "2026-08",
        amountMinor = -250_000, currencyCode = 980, description = "На картку",
        mcc = 4829, originalMcc = 4829, hold = false,
        categoryId = null, manuallyCategorized = false, rawJson = "{}",
    )

    private fun transferTxn(id: String, categoryId: Long?, key: String?, at: Long = august) =
        baseTxn(id).copy(occurredAt = at, categoryId = categoryId, counterpartyKey = key, counterpartySource = "card")

    private fun ApplicationTestBuilder.setup(db: Database) {
        val conduit = ConduitMccRepository(db)
        val counterparties = CounterpartyRepository(db)
        val categories = CategoryRepository(db, conduit)
        val transactions = TransactionRepository(db)
        val settings = SettingsRepository(db, Crypto(ByteArray(32) { it.toByte() }))
        val accounts = AccountRepository(db)
        val budget = BudgetService(categories, transactions)
        val ingest = IngestService(
            transactions, categories, budget,
            Notifier(FakeTelegramClient(), settings, NotificationEventRepository(db)),
            counterparties, conduit, AccountRepository(db),
            clock = clock,
        )
        application {
            routing {
                transactionRoutes(transactions, categories, ingest, settings, conduit, counterparties, accounts, budget, clock)
            }
        }
    }

    /**
     * The test client does not auto-follow redirects, so flash assertions fetch the page
     * the redirect points at explicitly rather than reading an empty body off the 302.
     */
    private suspend fun HttpClient.bodyAfterRedirect(response: HttpResponse): String =
        get(response.headers["Location"]!!).bodyAsText()

    private fun withTransactionsApp(db: Database, block: suspend ApplicationTestBuilder.(HttpClient, String) -> Unit) =
        testApplication {
            setup(db)
            val client = createClient { followRedirects = false }
            val csrf = Regex("name=\"csrf_token\" value=\"([0-9a-f]+)\"")
                .find(client.get("/transactions").bodyAsText())!!.groupValues[1]
            block(client, csrf)
        }

    /** Isolates one row's rendered HTML by its id anchor, up to the next row (or end of
     *  page) — including any rule banner attached right after it. */
    private fun rowFor(body: String, txnId: String): String {
        val marker = "id=\"txn-$txnId\""
        val start = body.indexOf(marker)
        require(start >= 0) { "no row found for transaction $txnId in: $body" }
        val nextRow = body.indexOf("id=\"txn-", start + marker.length)
        return body.substring(start, if (nextRow >= 0) nextRow else body.length)
    }

    @Test
    fun `a row shows its monogram, title, mcc and date meta, and amount`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val groceries = categories.create("Groceries", "🛒", 2_500_000, 80)
        TransactionRepository(db).upsert(txn("t1", "ATB Market", -84_000, groceries))
        testApplication {
            setup(db)
            val body = client.get("/transactions?uncategorized=0").bodyAsText()
            val row = rowFor(body, "t1")
            assertTrue(row.contains("AT"), "monogram missing: $row")
            assertTrue(row.contains("ATB Market"), row)
            assertTrue(row.contains("5411"), row)
            assertTrue(row.contains("18.08.2026"), "full date with year expected in row meta: $row")
            assertTrue(row.contains("🛒 Groceries"), row)
            // True minus (U+2212), not the ASCII hyphen.
            assertTrue(row.contains("−840 ₴"), row)
        }
    }

    @Test
    fun `income renders with a plus sign and the income class, expenses do not`() = withTestDb { db ->
        val transactions = TransactionRepository(db)
        transactions.upsert(txn("spend", "ATB", -84_000, null))
        transactions.upsert(txn("income", "Від Іванни", 9_000, null, mcc = null))
        testApplication {
            setup(db)
            val body = client.get("/transactions?incoming=1").bodyAsText()
            val incomeRow = rowFor(body, "income")
            assertTrue(incomeRow.contains("+90 ₴"), incomeRow)
            assertTrue(incomeRow.contains("row-amount mono income"), incomeRow)
            val spendRow = rowFor(body, "spend")
            assertTrue(spendRow.contains("−840 ₴"), spendRow)
            assertFalse(spendRow.contains("row-amount mono income"), spendRow)
        }
    }

    @Test
    fun `rows are grouped by day with a header and a day total`() = withTestDb { db ->
        val transactions = TransactionRepository(db)
        transactions.upsert(txn("t1", "Day one A", -1_000, null, at = august))
        transactions.upsert(txn("t2", "Day one B", -2_000, null, at = august + 60))
        transactions.upsert(txn("t3", "Day two", -3_000, null, at = august - 86_400))
        testApplication {
            setup(db)
            val body = client.get("/transactions").bodyAsText()
            assertTrue(body.contains("day-group-header"), body)
            // Both same-day rows' amounts summed into one day total: -1000 + -2000 minor = -30.00 UAH.
            assertTrue(body.contains("−30 ₴"), "day total for the earlier day missing: $body")
        }
    }

    @Test
    fun `a day total that nets positive keeps its plus sign, not reading as an expense`() = withTestDb { db ->
        // Finding 12 (MINOR) of the 2026-09-04 review: the day total used to use
        // formatMinor, which renders a non-negative amount with no sign at all — a day
        // where income (shown via "показати надходження") outweighs spend then read like
        // an expense next to the "+"-prefixed income rows above it.
        val transactions = TransactionRepository(db)
        transactions.upsert(txn("spend", "ATB", -1_000, null, mcc = 5411))
        transactions.upsert(txn("income", "Refund", 5_000, null, mcc = null))
        testApplication {
            setup(db)
            val body = client.get("/transactions?incoming=1").bodyAsText()
            assertTrue(body.contains("day-group-total"), body)
            assertTrue(body.contains("+40 ₴"), "a net-positive day total must keep its + sign: $body")
        }
    }

    @Test
    fun `the row select persists only that one row and never binds the mcc`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val home = categories.create("Home", "🏠", 1_000_000, 80)
        val transactions = TransactionRepository(db)
        transactions.upsert(txn("t1", "Epicentr", -243_000, null))
        transactions.upsert(txn("t2", "Epicentr", -100_000, null)) // same mcc, would be a sibling
        testApplication {
            setup(db)
            val response = client.submitForm(
                "/transactions/t1/category",
                parameters { append("categoryId", home.toString()); append("back", "/transactions?month=2026-08") },
            )
            val body = client.bodyAfterRedirect(response)
            assertEquals(home, transactions.byId("t1")!!.categoryId)
            assertTrue(transactions.byId("t1")!!.manuallyCategorized, "the row select must mark the row manual, never bind")
            assertEquals(null, transactions.byId("t2")!!.categoryId, "the sibling must be untouched by a plain select")
            assertTrue(categories.mccMapping().isEmpty(), "no mcc may be bound by the plain select")
            assertTrue(body.contains(UkCopy.categorySavedForRow("🏠 Home")), body)
        }
    }

    @Test
    fun `clearing a row via the select touches only that row and reports it plainly`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val home = categories.create("Home", "🏠", 1_000_000, 80)
        val transactions = TransactionRepository(db)
        transactions.upsert(txn("t1", "Epicentr", -243_000, home))
        testApplication {
            setup(db)
            val response = client.submitForm(
                "/transactions/t1/category",
                parameters { append("categoryId", ""); append("back", "/transactions?month=2026-08") },
            )
            val body = client.bodyAfterRedirect(response)
            assertEquals(null, transactions.byId("t1")!!.categoryId)
            assertTrue(body.contains(UkCopy.categoryClearedForRow), body)
        }
    }

    @Test
    fun `a category picked for a row with at least one mcc sibling offers a rule strip, which stays closed until applied`() =
        withTestDb { db ->
            val categories = CategoryRepository(db, ConduitMccRepository(db))
            val home = categories.create("Home", "🏠", 1_000_000, 80)
            val transactions = TransactionRepository(db)
            transactions.upsert(txn("t1", "Epicentr", -243_000, null)) // mcc 5411 by default
            transactions.upsert(txn("t2", "Epicentr", -50_000, null))
            testApplication {
                setup(db)
                val client = createClient { followRedirects = false }
                val response = client.submitForm(
                    "/transactions/t1/category",
                    parameters { append("categoryId", home.toString()); append("back", "/transactions?month=2026-08") },
                )
                val location = response.headers["Location"]!!
                assertTrue(location.contains("ruleFor=t1"), location)

                val afterPick = client.get(location.substringBefore("#")).bodyAsText()
                assertTrue(afterPick.contains(UkCopy.ruleSuggestionMcc(5411)), afterPick)
                assertTrue(afterPick.contains(UkCopy.ruleNotNow), afterPick)
                assertTrue(afterPick.contains(UkCopy.ruleApply), afterPick)

                // Nothing bound yet — the strip only offers, it has not been clicked.
                assertEquals(null, transactions.byId("t2")!!.categoryId)
                assertTrue(categories.mccMapping().isEmpty())
            }
        }

    @Test
    fun `a category picked for a row with no siblings offers no rule strip`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val home = categories.create("Home", "🏠", 1_000_000, 80)
        val transactions = TransactionRepository(db)
        transactions.upsert(txn("t1", "Epicentr", -243_000, null, mcc = 9999))
        testApplication {
            setup(db)
            val client = createClient { followRedirects = false }
            val response = client.submitForm(
                "/transactions/t1/category",
                parameters { append("categoryId", home.toString()); append("back", "/transactions?month=2026-08") },
            )
            val location = response.headers["Location"]!!
            assertFalse(location.contains("ruleFor"), location)
        }
    }

    @Test
    fun `applying the rule strip binds the mcc and moves every sibling, and only the explicit click can do it`() =
        withTestDb { db ->
            val categories = CategoryRepository(db, ConduitMccRepository(db))
            val home = categories.create("Home", "🏠", 1_000_000, 80)
            val transactions = TransactionRepository(db)
            transactions.upsert(txn("t1", "Epicentr", -243_000, null))
            transactions.upsert(txn("t2", "Epicentr", -50_000, null))
            withTransactionsApp(db) { client, csrf ->
                client.submitForm(
                    "/transactions/t1/category",
                    parameters { append("categoryId", home.toString()); append("back", "/transactions?month=2026-08") },
                )
                val response = client.submitForm(
                    "/transactions/t1/apply-rule",
                    parameters { append(CSRF_FIELD, csrf); append("back", "/transactions?month=2026-08") },
                ) { header(HttpHeaders.Origin, "http://localhost") }
                val body = client.bodyAfterRedirect(response)
                assertEquals(home, transactions.byId("t2")!!.categoryId, "the sibling must move once applied")
                assertEquals(mapOf(5411 to home), categories.mccMapping())
                assertTrue(body.contains(UkCopy.mccBoundFromTransaction("Epicentr", 5411, "🏠 Home", 1)), body)
            }
        }

    @Test
    fun `a transfer with an exact recipient and a sibling offers a counterparty rule strip, applied only on click`() =
        withTestDb { db ->
            val conduit = ConduitMccRepository(db)
            conduit.replaceAll(setOf(4829))
            val categories = CategoryRepository(db, conduit)
            val rent = categories.create("Оренда", "🏠", 0, 80)
            val transactions = TransactionRepository(db)
            transactions.upsert(transferTxn("t1", null, "card:4441**1234"))
            transactions.upsert(transferTxn("t2", null, "card:4441**1234", at = august - 3600))
            testApplication {
                setup(db)
                val client = createClient { followRedirects = false }
                val response = client.submitForm(
                    "/transactions/t1/category",
                    parameters { append("categoryId", rent.toString()); append("back", "/transactions?month=2026-08") },
                )
                val location = response.headers["Location"]!!
                assertTrue(location.contains("ruleFor=t1"), location)
                val afterPick = client.get(location.substringBefore("#")).bodyAsText()
                assertTrue(afterPick.contains(UkCopy.ruleSuggestionCounterparty("На картку", 2)), afterPick)
                // Not applied yet.
                assertEquals(null, transactions.byId("t2")!!.categoryId)
                assertTrue(CounterpartyRepository(db).mapping().isEmpty())
            }
        }

    @Test
    fun `the rule strip's row stays visible under the uncategorized-only filter until the strip is resolved`() =
        withTestDb { db ->
            val categories = CategoryRepository(db, ConduitMccRepository(db))
            val home = categories.create("Home", "🏠", 1_000_000, 80)
            val transactions = TransactionRepository(db)
            transactions.upsert(txn("t1", "Epicentr", -243_000, null))
            transactions.upsert(txn("t2", "Epicentr", -50_000, null))
            testApplication {
                setup(db)
                val client = createClient { followRedirects = false }
                // Default filter is "лише без категорії" ON.
                val response = client.submitForm(
                    "/transactions/t1/category",
                    parameters { append("categoryId", home.toString()); append("back", "/transactions?month=2026-08") },
                )
                val location = response.headers["Location"]!!
                val afterPick = client.get(location.substringBefore("#")).bodyAsText()
                // t1 now has a category, so it no longer matches "лише без категорії" on
                // its own — the ruleFor union must keep it on the page anyway.
                assertTrue(afterPick.contains("id=\"txn-t1\""), "the rule strip's own row must not vanish: $afterPick")
                assertTrue(afterPick.contains(UkCopy.ruleApply), afterPick)
            }
        }

    @Test
    fun `filing a row keeps its original position instead of jumping to the top`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val home = categories.create("Home", "🏠", 1_000_000, 80)
        val transactions = TransactionRepository(db)
        // Each row has a unique mcc, so none of them get a rule strip — this is exactly the
        // "no sibling" shape finding 2 (IMPORTANT) of the 2026-09-04 review is about.
        listOf("t0", "t1", "t2", "t3", "t4").forEachIndexed { i, id ->
            transactions.upsert(txn(id, "Row $i", -1_000L * (i + 1), null, at = august + i * 3600, mcc = 9990 + i))
        }
        testApplication {
            setup(db)
            val client = createClient { followRedirects = false }
            // Default filter: "лише без категорії" ON, not overridden with uncategorized=0
            // — without finding 2's fix, t2 would vanish the instant it got a category, both
            // breaking the ordering assertion below and stranding the redirect's own
            // #txn-t2 anchor at nothing.
            val response = client.submitForm(
                "/transactions/t2/category",
                parameters { append("categoryId", home.toString()); append("back", "/transactions?month=2026-08") },
            )
            val location = response.headers["Location"]!!
            assertTrue(location.endsWith("#txn-t2"), "the redirect must still anchor to the filed row: $location")
            val afterFiling = client.get(location.substringBefore("#")).bodyAsText()

            val order = listOf("t4", "t3", "t2", "t1", "t0").map { id -> afterFiling.indexOf("id=\"txn-$id\"") }
            assertTrue(order.all { it >= 0 }, "every row must still be present: $afterFiling")
            assertEquals(order.sorted(), order, "t2 must reappear at its original rank, not prepended to the top: $afterFiling")
        }
    }

    @Test
    fun `binding via the rule strip anchors the redirect back to the row`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val home = categories.create("Home", "🏠", 1_000_000, 80)
        val transactions = TransactionRepository(db)
        transactions.upsert(txn("t1", "Epicentr", -243_000, null))
        transactions.upsert(txn("t2", "Epicentr", -50_000, null))
        withTransactionsApp(db) { client, csrf ->
            client.submitForm(
                "/transactions/t1/category",
                parameters { append("categoryId", home.toString()); append("back", "/transactions?month=2026-08") },
            )
            val response = client.submitForm(
                "/transactions/t1/apply-rule",
                parameters { append(CSRF_FIELD, csrf); append("back", "/transactions?month=2026-08") },
            ) { header(HttpHeaders.Origin, "http://localhost") }
            val location = response.headers["Location"]!!
            assertTrue(location.endsWith("#txn-t1"), "the redirect must scroll back to the row: $location")
        }
    }

    @Test
    fun `a conflicting mcc reports the owning category and changes nothing`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val restaurants = categories.create("Restaurants", "🍔", 500_000, 80)
        val groceries = categories.create("Groceries", "🛒", 500_000, 80)
        categories.setMcc(restaurants, setOf(5812))
        val transactions = TransactionRepository(db)
        transactions.upsert(txn("t1", "Silpo", -50_000, restaurants, mcc = 5812))
        transactions.upsert(txn("t2", "Silpo", -20_000, restaurants, mcc = 5812))
        testApplication {
            setup(db)
            val client = createClient { followRedirects = false }
            // Re-pick a different category for the row (still single-row, no binding) …
            client.submitForm(
                "/transactions/t1/category",
                parameters { append("categoryId", groceries.toString()); append("back", "/transactions?month=2026-08") },
            )
            // … then try to apply it as a rule, which must fail: the mcc still belongs to
            // Restaurants.
            val response = client.submitForm(
                "/transactions/t1/apply-rule",
                parameters { append("back", "/transactions?month=2026-08") },
            )
            val body = client.bodyAfterRedirect(response)
            assertEquals(restaurants, transactions.byId("t2")!!.categoryId, "the sibling must be untouched on conflict")
            assertTrue(body.contains(UkCopy.mccConflict(5812, "Restaurants")), body)
        }
    }

    @Test
    fun `the category select carries a csrf token and reflects assigned vs unassigned state`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val groceries = categories.create("Groceries", "🛒", 2_500_000, 80)
        val transactions = TransactionRepository(db)
        transactions.upsert(txn("assigned", "ATB", -84_000, groceries))
        transactions.upsert(txn("bare", "Mystery", -1_000, null))
        testApplication {
            setup(db)
            val body = client.get("/transactions?uncategorized=0").bodyAsText()
            assertTrue(body.contains("name=\"csrf_token\""), body)
            assertTrue(rowFor(body, "assigned").contains("select-row is-filled"), rowFor(body, "assigned"))
            assertTrue(rowFor(body, "bare").contains(UkCopy.chooseCategoryOption), rowFor(body, "bare"))
        }
    }

    @Test
    fun `the category select only lists enabled categories, plus a since-disabled current one`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val active = categories.create("Active", "✅", 0, 80)
        val disabled = categories.create("Retired", "🗑", 0, 80)
        categories.update(categories.byId(disabled)!!.copy(enabled = false))
        val transactions = TransactionRepository(db)
        transactions.upsert(txn("t1", "Old purchase", -1_000, disabled, mcc = 1234))
        testApplication {
            setup(db)
            val body = client.get("/transactions?uncategorized=0").bodyAsText()
            val row = rowFor(body, "t1")
            assertTrue(row.contains("🗑 Retired"), "the row's own (disabled) category must still be a real option: $row")
            val filterSection = body.substringBefore("day-group")
            assertTrue(filterSection.contains("✅ Active"), filterSection)
            assertFalse(filterSection.contains("🗑 Retired"), "a disabled category must not appear in the filter: $filterSection")
        }
    }

    @Test
    fun `the summary strip totals month spend, uncategorized amount and unresolved count`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val groceries = categories.create("Groceries", "🛒", 2_500_000, 80)
        val transactions = TransactionRepository(db)
        transactions.upsert(txn("t1", "ATB", -84_000, groceries))
        transactions.upsert(txn("t2", "Mystery", -16_000, null))
        testApplication {
            setup(db)
            val body = client.get("/transactions?uncategorized=0").bodyAsText()
            assertTrue(body.contains("1 000 ₴"), "month total (840+160) missing: $body")
            assertTrue(body.contains(UkCopy.statSpentThisMonth), body)
            assertTrue(body.contains(UkCopy.statUnresolved), body)
            assertTrue(body.contains(UkCopy.ofTransactionsCount(2)), body)
        }
    }

    @Test
    fun `uncategorized-only and show-incoming default the way the handoff specifies`() = withTestDb { db ->
        val transactions = TransactionRepository(db)
        transactions.upsert(txn("spend", "ATB", -84_000, null))
        transactions.upsert(txn("categorized", "Rent", -20_000, null).let {
            val categories = CategoryRepository(db, ConduitMccRepository(db))
            it.copy(categoryId = categories.create("Home", "🏠", 0, 80))
        })
        transactions.upsert(txn("incoming", "Від Іванни", 9_000, null, mcc = null))
        testApplication {
            setup(db)
            val body = client.get("/transactions").bodyAsText()
            assertTrue(body.contains("ATB"), "uncategorized spend must show by default: $body")
            assertFalse(body.contains("Rent"), "a categorized row must be hidden by the default uncategorized-only filter: $body")
            assertFalse(body.contains("Іванни"), "incoming money must be hidden by default: $body")
        }
    }

    @Test
    fun `the reset link only appears once a filter differs from the default`() = withTestDb { db ->
        TransactionRepository(db).upsert(txn("t1", "ATB", -84_000, null))
        testApplication {
            setup(db)
            val default = client.get("/transactions").bodyAsText()
            assertFalse(default.contains("filter-reset"), default)
            val filtered = client.get("/transactions?incoming=1").bodyAsText()
            assertTrue(filtered.contains("filter-reset"), filtered)
        }
    }

    @Test
    fun `only three sort options are offered`() = withTestDb { db ->
        testApplication {
            setup(db)
            val body = client.get("/transactions").bodyAsText()
            assertTrue(body.contains(UkCopy.orderNewest), body)
            assertTrue(body.contains(UkCopy.orderOldest), body)
            assertTrue(body.contains(UkCopy.orderLargest), body)
        }
    }

    @Test
    fun `the order control actually reorders the rendered rows`() = withTestDb { db ->
        val transactions = TransactionRepository(db)
        transactions.upsert(txn("small", "Coffee", -1_000, null, mcc = 1))
        transactions.upsert(txn("big", "Rent", -900_000, null, mcc = 2))
        testApplication {
            setup(db)
            val largest = client.get("/transactions?order=largest&uncategorized=0").bodyAsText()
            assertTrue(largest.indexOf("Rent") in 0..<largest.indexOf("Coffee"), largest)
        }
    }

    @Test
    fun `a row on a switched-off account carries a pill explaining it is not counted`() = withTestDb { db ->
        val transactions = TransactionRepository(db)
        val accounts = AccountRepository(db)
        accounts.replaceAll(listOf(app.mono.MonoAccount(id = "acc-off", currencyCode = 980)))
        accounts.setActive(emptySet())
        transactions.upsert(
            Txn("t1", "acc-off", august, monthKeyOf(august), -5_000_000, 980, "Own transfer",
                4829, 4829, false, null, false, "{}"),
        )
        testApplication {
            setup(db)
            val body = client.get("/transactions").bodyAsText()
            assertTrue(body.contains("Own transfer"), body)
            assertTrue(body.contains(UkCopy.inactiveAccountPill), body)
        }
    }

    @Test
    fun `an empty result renders the empty state, not a bare list card`() = withTestDb { db ->
        testApplication {
            setup(db)
            val body = client.get("/transactions?category=999").bodyAsText()
            assertTrue(body.contains(UkCopy.emptyTransactionsTitle), body)
            assertTrue(body.contains(UkCopy.emptyTransactionsBody), body)
        }
    }

    @Test
    fun `picking a category filter returns its rows even though uncategorized-only defaults on`() =
        withTestDb { db ->
            // Finding 1 (CRITICAL) of the 2026-09-04 review: the default GET request for a
            // category (no explicit "uncategorized" param) used to be read as "category eq X
            // AND category IS NULL" — unsatisfiable — because the default filter state
            // ("лише без категорії" ON) collided with picking any category at all.
            val categories = CategoryRepository(db, ConduitMccRepository(db))
            val groceries = categories.create("Groceries", "🛒", 0, 80)
            val transactions = TransactionRepository(db)
            transactions.upsert(txn("t1", "ATB Market", -84_000, groceries))
            testApplication {
                setup(db)
                val body = client.get("/transactions?category=$groceries").bodyAsText()
                assertTrue(
                    body.contains("ATB Market"),
                    "a plain category filter must not silently collide with the default " +
                        "uncategorized-only filter: $body",
                )
                assertFalse(body.contains(UkCopy.emptyTransactionsTitle), body)
                // The chip must render inactive to match what the filter is actually doing.
                assertFalse(
                    body.substringBefore("day-group").contains("filter-chip active"),
                    "the uncategorized-only chip must not claim to be on once it's been forced off: $body",
                )
            }
        }

    @Test
    fun `no inline style attribute is ever emitted`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val groceries = categories.create("Groceries", "🛒", 2_500_000, 80)
        TransactionRepository(db).upsert(txn("t1", "ATB", -84_000, groceries))
        testApplication {
            setup(db)
            val body = client.get("/transactions?uncategorized=0").bodyAsText()
            assertFalse(body.contains("style=\""), "no inline style attribute may be emitted: $body")
        }
    }

    @Test
    fun `the month stepper clamps at the edges of the available data`() = withTestDb { db ->
        val transactions = TransactionRepository(db)
        // Only this month has data, and the clock is fixed to it too — both edges clamp.
        transactions.upsert(txn("t1", "ATB", -84_000, null))
        testApplication {
            setup(db)
            val body = client.get("/transactions").bodyAsText()
            assertTrue(body.contains("month-stepper-arrow disabled"), body)
        }
    }

    @Test
    fun `sorting by amount renders one flat list, not day cards in a scrambled order`() = withTestDb { db ->
        // 2026-09-22 production walkthrough, finding 6: rows came back amount-sorted and
        // were then regrouped by day, so "За сумою" degraded into "days ordered by their
        // single largest transaction" — each day dragging its small rows up alongside it.
        // Live September rendered headers in the order 14, 1, 22, 3, 10, 15 вересня.
        val transactions = TransactionRepository(db)
        transactions.upsert(txn("big", "Rent", -900_000, null, at = august - 86_400))
        transactions.upsert(txn("small", "Coffee", -1_000, null, at = august))
        testApplication {
            setup(db)
            val byAmount = client.get("/transactions?order=largest&uncategorized=0").bodyAsText()
            assertTrue(byAmount.indexOf("Rent") in 0..<byAmount.indexOf("Coffee"), "amount order lost: $byAmount")
            assertFalse(
                byAmount.contains("day-group-header"),
                "amount order must not render day headers — every row already carries its own date: $byAmount",
            )
            val byDate = client.get("/transactions?uncategorized=0").bodyAsText()
            assertTrue(byDate.contains("day-group-header"), "date order must keep its day cards: $byDate")
        }
    }

    /** The rendered text of every day-group total on the page, top to bottom. */
    private fun dayTotals(body: String): List<String> =
        Regex("class=\"day-group-total mono\">([^<]*)<").findAll(body).map { it.groupValues[1] }.toList()

    @Test
    fun `a day total leaves out a row on a switched-off account`() = withTestDb { db ->
        // 2026-09-22 production walkthrough, finding 1: the header was a raw sumOf over
        // dayRows, so the row it labels "рахунок вимкнено — не рахується" was counted into
        // the very figure above it. On the owner's real September 2026 data that put the
        // day headers 84% above the month total on the same page.
        val transactions = TransactionRepository(db)
        val accounts = AccountRepository(db)
        accounts.replaceAll(
            listOf(
                MonoAccount(id = "acc-1", currencyCode = 980),
                MonoAccount(id = "acc-off", currencyCode = 980),
            ),
        )
        accounts.setActive(setOf("acc-1"))
        transactions.upsert(txn("counted", "ATB", -1_000, null))
        transactions.upsert(
            Txn("ignored", "acc-off", august, monthKeyOf(august), -500_000, 980,
                "На чорну картку", 4829, 4829, false, null, false, "{}"),
        )
        testApplication {
            setup(db)
            val body = client.get("/transactions").bodyAsText()
            assertTrue(body.contains("На чорну картку"), "the row itself must still render: $body")
            assertTrue(body.contains(UkCopy.inactiveAccountPill), body)
            assertEquals(listOf(formatSignedMinor(-1_000)), dayTotals(body))
        }
    }

    @Test
    fun `a day total leaves out a category that does not count as spending`() = withTestDb { db ->
        // The other half of the same finding: BudgetService.monthSummary drops a
        // countsAsSpending = false category from totalSpentMinor, and every reader that
        // builds its own total has to drop it too (CLAUDE.md, Spending truth).
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val ownAccounts = categories.create("Свої рахунки", "🔁", 0, 80, countsAsSpending = false)
        val transactions = TransactionRepository(db)
        transactions.upsert(txn("counted", "ATB", -1_000, null))
        transactions.upsert(txn("moved", "На свій рахунок", -500_000, ownAccounts, mcc = 4829))
        testApplication {
            setup(db)
            val body = client.get("/transactions?uncategorized=0").bodyAsText()
            assertTrue(body.contains("На свій рахунок"), "the row itself must still render: $body")
            assertEquals(listOf(formatSignedMinor(-1_000)), dayTotals(body))
        }
    }

    /**
     * The day header drops these rows, and until now nothing on the row said why: a day
     * holding a −10 ₴ coffee and a −5 000 ₴ transfer to one's own account printed −10 ₴
     * over rows that visibly sum to −5 010 ₴. A switched-off account has carried a pill for
     * exactly this reason since the walkthrough, and `/status` marks the same category with
     * the same words — `/transactions` was the one reader that excluded in silence.
     */
    @Test
    fun `a row the day total leaves out says it is not counted`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val ownAccounts = categories.create("Свої рахунки", "🔁", 0, 80, countsAsSpending = false)
        val transactions = TransactionRepository(db)
        transactions.upsert(txn("moved", "На свій рахунок", -500_000, ownAccounts, mcc = 4829))
        testApplication {
            setup(db)
            val body = client.get("/transactions?uncategorized=0").bodyAsText()
            assertTrue(body.contains(UkCopy.notCountedPill), "the excluded row must say so: $body")
        }
    }

    @Test
    fun `a row that does count carries no such pill`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val groceries = categories.create("Продукти", "🛒", 0, 80)
        val transactions = TransactionRepository(db)
        transactions.upsert(txn("counted", "ATB", -1_000, groceries))
        testApplication {
            setup(db)
            val body = client.get("/transactions?uncategorized=0").bodyAsText()
            assertFalse(body.contains(UkCopy.notCountedPill), body)
        }
    }

    @Test
    fun `day totals add up to the month total printed above them`() = withTestDb { db ->
        // The invariant the two tests above are instances of, and the one the walkthrough
        // measured breaking: summing the day headers must land on exactly the figure the
        // stat strip prints for the month.
        val accounts = AccountRepository(db)
        accounts.replaceAll(
            listOf(
                MonoAccount(id = "acc-1", currencyCode = 980),
                MonoAccount(id = "acc-off", currencyCode = 980),
            ),
        )
        accounts.setActive(setOf("acc-1"))
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val own = categories.create("Свої рахунки", "🔁", 0, 80, countsAsSpending = false)
        val transactions = TransactionRepository(db)
        transactions.upsert(txn("a", "ATB", -1_000, null, at = august))
        transactions.upsert(txn("c", "Own move", -700_000, own, at = august, mcc = 4829))
        transactions.upsert(txn("b", "Moshe", -2_000, null, at = august - 86_400))
        transactions.upsert(
            Txn("d", "acc-off", august - 86_400, monthKeyOf(august - 86_400), -900_000, 980,
                "На чорну картку", 4829, 4829, false, null, false, "{}"),
        )
        testApplication {
            setup(db)
            val body = client.get("/transactions?uncategorized=0").bodyAsText()
            assertEquals(
                listOf(formatSignedMinor(-1_000), formatSignedMinor(-2_000)),
                dayTotals(body),
            )
            assertTrue(
                body.contains(formatMinor(3_000)),
                "the month stat strip must print the same total the day headers sum to: $body",
            )
        }
    }
}
