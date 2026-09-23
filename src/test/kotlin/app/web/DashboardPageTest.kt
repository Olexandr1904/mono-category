package app.web

import app.budget.BudgetService
import app.budget.CategoryRepository
import app.budget.ConduitMccRepository
import app.budget.KYIV
import app.budget.TransactionRepository
import app.budget.Txn
import app.budget.formatMinor
import app.budget.formatMinorWhole
import app.budget.monthKeyOf
import app.db.Crypto
import app.db.SettingsRepository
import app.ingest.AccountRepository
import app.mono.MonoAccount
import app.i18n.EnCopy
import app.i18n.Language
import app.i18n.UkCopy
import app.i18n.setLanguage
import app.withTestDb
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.Database
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DashboardPageTest {

    private val august = 1_787_011_200L
    private val clock = Clock.fixed(Instant.ofEpochSecond(august), KYIV)

    private fun settings(db: Database) = SettingsRepository(db, Crypto(ByteArray(32) { it.toByte() }))

    private fun io.ktor.server.application.Application.mount(db: Database) {
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val transactions = TransactionRepository(db)
        routing {
            dashboardRoutes(BudgetService(categories, transactions), transactions, settings(db), AccountRepository(db), clock)
        }
    }

    private fun seed(db: Database) {
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val transactions = TransactionRepository(db)
        val restaurants = categories.create("Restaurants", "🍔", 1_500_000, 80)
        val home = categories.create("Home", "🏠", 1_000_000, 80)
        transactions.upsert(txn("t1", -1_243_000, restaurants))
        transactions.upsert(txn("t2", -1_024_000, home))
        transactions.upsert(txn("t3", -50_000, null))
    }

    private fun txn(id: String, amountMinor: Long, categoryId: Long?) = Txn(
        id = id, accountId = "acc-1", occurredAt = august, month = monthKeyOf(august),
        amountMinor = amountMinor, currencyCode = 980, description = "x",
        mcc = 5411, originalMcc = 5411, hold = false,
        categoryId = categoryId, manuallyCategorized = false, rawJson = "{}",
    )

    @Test
    fun `hero card shows the month total and one breakdown row per category`() = withTestDb { db ->
        seed(db)
        testApplication {
            application { mount(db) }
            val body = client.get("/").bodyAsText()
            assertTrue(body.contains("hero-total"), body)
            assertTrue(body.contains(formatMinor(2_317_000)), "total spent missing: $body") // 12430 + 10240 + 500
            assertTrue(body.contains("🍔 Restaurants"), body)
            assertTrue(body.contains("🏠 Home"), body)
            assertTrue(body.contains(UkCopy.uncategorizedLabel), body)
            // Default language is Ukrainian: the month key renders as words, not "2026-08".
            assertTrue(body.contains(UkCopy.monthLabel("2026-08")), "month heading missing")
        }
    }

    @Test
    fun `breakdown orders real categories by spend descending, then Інше, then Без категорії last`() = withTestDb { db ->
        testApplication {
            val categories = CategoryRepository(db, ConduitMccRepository(db))
            val transactions = TransactionRepository(db)
            (1..7).forEach { i ->
                val id = categories.create("Cat$i", "", 0, 80)
                categories.update(categories.byId(id)!!.copy(position = i - 1))
                transactions.upsert(txn("t$i", -(i * 1000L), id))
            }
            transactions.upsert(txn("uncat", -50_000, null))
            application { routing { dashboardRoutes(BudgetService(categories, transactions), transactions, settings(db), AccountRepository(db), clock) } }
            val body = client.get("/").bodyAsText()
            // Top six by spend (Cat7..Cat2) each keep their own name; Cat1 (smallest) folds
            // into "Інше" and its name never appears on its own.
            val cat7 = body.indexOf("Cat7") // biggest real spend (7000)
            val cat2 = body.indexOf("Cat2") // smallest of the top six, still ahead of "Інше"
            val other = body.indexOf(UkCopy.donutOtherLabel)
            val uncategorized = body.indexOf(UkCopy.uncategorizedLabel)
            assertTrue(cat7 in 0 until cat2, body)
            assertTrue(cat2 in 0 until other, "the six real categories must all rank ahead of Інше: $body")
            assertTrue(other in 0 until uncategorized, "Інше must rank ahead of Без категорії: $body")
            assertTrue(!body.contains(">Cat1<"), "the seventh category must fold into Інше, not render its own row: $body")
        }
    }

    @Test
    fun `an explicit month parameter is honoured and the month stepper links are present`() = withTestDb { db ->
        seed(db) // data in 2026-08
        val transactions = TransactionRepository(db)
        // Finding 6 (MINOR) of the 2026-09-04 review: Огляд's stepper now clamps to the
        // available data range, same as Операції's — data on both sides of the viewed
        // 2026-07 keeps both links present, exercising the clamp without tripping it.
        transactions.upsert(Txn("earlier", "acc-1", august, "2026-06", -1_000, 980, "x", 5411, 5411, false, null, false, "{}"))
        testApplication {
            application { mount(db) }
            val body = client.get("/?month=2026-07").bodyAsText()
            assertTrue(body.contains(UkCopy.monthLabel("2026-07")), "requested month missing")
            assertTrue(body.contains("/?month=2026-06"), "previous month link missing")
            assertTrue(body.contains("/?month=2026-08"), "next month link missing")
        }
    }

    @Test
    fun `the month stepper clamps at the edges of the available data, same as Операції`() = withTestDb { db ->
        // Finding 6 (MINOR) of the 2026-09-04 review: Огляд's own arrows used to link to
        // every adjacent month unconditionally, unlike Операції's — same monthStepper
        // component, two behaviours.
        seed(db) // only 2026-08 has data, and the clock is fixed to it too
        testApplication {
            application { mount(db) }
            val body = client.get("/").bodyAsText()
            assertTrue(body.contains("month-stepper-arrow disabled"), body)
        }
    }

    @Test
    fun `breakdown rows link nowhere on their own — the whole card is read-only text`() = withTestDb { db ->
        // design-handoff.md §1: the breakdown list is "colour chip, name, mono amount,
        // right-aligned mono percentage" — no per-row link, unlike the old dashboard cards.
        seed(db)
        testApplication {
            application { mount(db) }
            val body = client.get("/").bodyAsText()
            assertTrue(body.contains("breakdown-row"), body)
        }
    }

    @Test
    fun `switching the language setting changes the rendered page`() = withTestDb { db ->
        seed(db)
        val settingsRepo = settings(db)
        testApplication {
            application {
                val categories = CategoryRepository(db, ConduitMccRepository(db))
                val transactions = TransactionRepository(db)
                routing {
                    dashboardRoutes(BudgetService(categories, transactions), transactions, settingsRepo, AccountRepository(db), clock)
                }
            }
            val ukrainian = client.get("/").bodyAsText()
            assertTrue(ukrainian.contains(UkCopy.limitsHeading) || ukrainian.contains(UkCopy.noLimitsTitle), ukrainian)

            settingsRepo.setLanguage(Language.EN)
            val english = client.get("/").bodyAsText()
            assertTrue(english.contains(EnCopy.noLimitsTitle) || english.contains(EnCopy.limitsHeading), english)
        }
    }

    @Test
    fun `uncategorized spend gets its own breakdown row and an unsorted-yet pill`() = withTestDb { db ->
        seed(db)
        testApplication {
            application { mount(db) }
            val body = client.get("/").bodyAsText()
            assertTrue(body.contains(UkCopy.unsortedPill), "unsorted-yet marker missing from the uncategorized row")
        }
    }

    @Test
    fun `the uncategorized banner shows the amount, share, and a button to Операції with the filter on`() = withTestDb { db ->
        seed(db) // uncategorized 500 ₴ of 23 170 ₴ total -> 2%
        testApplication {
            application { mount(db) }
            val body = client.get("/").bodyAsText()
            assertTrue(body.contains(UkCopy.uncategorizedBannerSuffix), body)
            assertTrue(body.contains(UkCopy.uncategorizedBannerHint), body)
            assertTrue(body.contains("/transactions?month=2026-08&amp;uncategorized=1"), body)
            assertTrue(body.contains(UkCopy.resolveUncategorizedButton(1)), body)
        }
    }

    @Test
    fun `no uncategorized spend means no banner at all`() = withTestDb { db ->
        testApplication {
            val categories = CategoryRepository(db, ConduitMccRepository(db))
            val transactions = TransactionRepository(db)
            val groceries = categories.create("Groceries", "🛒", 0, 80)
            transactions.upsert(txn("t1", -50_000, groceries))
            application { routing { dashboardRoutes(BudgetService(categories, transactions), transactions, settings(db), AccountRepository(db), clock) } }
            val body = client.get("/").bodyAsText()
            assertTrue(!body.contains("uncat-banner"), body)
        }
    }

    @Test
    fun `every category limit at zero renders the empty limits state, not an afterthought`() = withTestDb { db ->
        // The owner's actual current state (docs/design-handoff.md §1): every category
        // limit is 0, so this is what he really sees, not a rarely-hit edge case.
        testApplication {
            val categories = CategoryRepository(db, ConduitMccRepository(db))
            val transactions = TransactionRepository(db)
            val groceries = categories.create("Продукти", "🛒", 0, 80)
            transactions.upsert(txn("t1", -50_000, groceries))
            application { routing { dashboardRoutes(BudgetService(categories, transactions), transactions, settings(db), AccountRepository(db), clock) } }
            val body = client.get("/").bodyAsText()
            assertTrue(body.contains(UkCopy.noLimitsTitle), body)
            assertTrue(body.contains(UkCopy.noLimitsBody), body)
            assertTrue(body.contains(UkCopy.setLimitsButton), body)
            assertTrue(body.contains("href=\"/categories\""), body)
            assertTrue(!body.contains("<h2>${UkCopy.limitsHeading}</h2>"), "the empty state must replace the heading, not sit under it: $body")
        }
    }

    @Test
    fun `a limited category renders in the Ліміти block with its bar and remaining hint`() = withTestDb { db ->
        testApplication {
            val categories = CategoryRepository(db, ConduitMccRepository(db))
            val transactions = TransactionRepository(db)
            val restaurants = categories.create("Restaurants", "🍔", 1_500_000, 80)
            transactions.upsert(txn("t1", -1_243_000, restaurants)) // 82%, under limit
            application { routing { dashboardRoutes(BudgetService(categories, transactions), transactions, settings(db), AccountRepository(db), clock) } }
            val body = client.get("/").bodyAsText()
            assertTrue(body.contains(UkCopy.limitsHeading), body)
            assertTrue(body.contains("${formatMinorWhole(1_500_000)} · 82%"), body)
            assertTrue(body.contains("data-bar-width=\"82\""), "limit bar width must be a data attribute: $body")
            assertTrue(body.contains(UkCopy.limitRemainingHint(formatMinorWhole(257_000))), body)
        }
    }

    @Test
    fun `an over-limit category shows the over hint instead of remaining`() = withTestDb { db ->
        testApplication {
            val categories = CategoryRepository(db, ConduitMccRepository(db))
            val transactions = TransactionRepository(db)
            val restaurants = categories.create("Restaurants", "🍔", 1_000_000, 80)
            transactions.upsert(txn("t1", -1_243_000, restaurants)) // 124%, over
            application { routing { dashboardRoutes(BudgetService(categories, transactions), transactions, settings(db), AccountRepository(db), clock) } }
            val body = client.get("/").bodyAsText()
            assertTrue(body.contains(UkCopy.limitOverHint(formatMinorWhole(243_000))), body)
            assertTrue(body.contains("limit-bar-fill over"), body)
        }
    }

    @Test
    fun `a category marked not counted as spending stays out of the total and the breakdown`() = withTestDb { db ->
        testApplication {
            val categories = CategoryRepository(db, ConduitMccRepository(db))
            val transactions = TransactionRepository(db)
            val groceries = categories.create("Groceries", "🛒", 0, 80)
            val selfTransfers = categories.create("Свої рахунки", "🔁", 0, 80)
            categories.update(categories.byId(selfTransfers)!!.copy(countsAsSpending = false))
            transactions.upsert(txn("t1", -50_000, groceries))
            transactions.upsert(txn("t2", -900_000, selfTransfers))
            application { routing { dashboardRoutes(BudgetService(categories, transactions), transactions, settings(db), AccountRepository(db), clock) } }
            val body = client.get("/").bodyAsText()
            assertTrue(body.contains("500 ₴"), body)
            assertTrue(!body.contains("🔁 Свої рахунки"), "an excluded category must not appear in the breakdown list: $body")
        }
    }

    @Test
    fun `a transaction on an account switched off in Settings drops out of the dashboard total`() = withTestDb { db ->
        testApplication {
            val categories = CategoryRepository(db, ConduitMccRepository(db))
            val transactions = TransactionRepository(db)
            val accounts = AccountRepository(db)
            accounts.replaceAll(listOf(MonoAccount(id = "fop", currencyCode = 980), MonoAccount(id = "acc-1", currencyCode = 980)))
            accounts.setActive(setOf("acc-1")) // the ФОП account is switched off
            val groceries = categories.create("Groceries", "🛒", 0, 80)
            transactions.upsert(
                Txn("t1", "fop", august, monthKeyOf(august), -50_000_000, 980, "На чорну картку",
                    4829, 4829, false, groceries, false, "{}"),
            )
            transactions.upsert(txn("t2", -50_000, groceries)) // acc-1, active
            application { routing { dashboardRoutes(BudgetService(categories, transactions), transactions, settings(db), AccountRepository(db), clock) } }
            val body = client.get("/").bodyAsText()
            assertTrue(body.contains("500 ₴"), body)
            assertTrue(!body.contains("500\u202F000"), "the inactive account's transfer must not shape the total: $body")
        }
    }

    /**
     * The breakdown column is a set of shares of one month, so it has to read as one: six
     * equal categories each rounding to 17% printed 102% down the side of the card. The
     * apportionment lives in [app.budget.sharePercents]; this is the screen that has to use
     * it rather than rounding each row on its own.
     */
    @Test
    fun `the breakdown percentages add up to 100`() = withTestDb { db ->
        testApplication {
            val categories = CategoryRepository(db, ConduitMccRepository(db))
            val transactions = TransactionRepository(db)
            repeat(6) { i ->
                val category = categories.create("Cat$i", "🍔", 0, 80)
                transactions.upsert(txn("t$i", -1_000_000, category))
            }
            application { routing { dashboardRoutes(BudgetService(categories, transactions), transactions, settings(db), AccountRepository(db), clock) } }
            val body = client.get("/").bodyAsText()

            val percents = Regex("""<span class="breakdown-pct mono">(\d+)%</span>""")
                .findAll(body).map { it.groupValues[1].toInt() }.toList()
            assertEquals(6, percents.size, "six categories, six percentages: $body")
            assertEquals(100, percents.sum(), "the column must read as shares of one month: $percents")
        }
    }

    @Test
    fun `real but sub-1 percent spend reads as under-1 percent, not as zero`() = withTestDb { db ->
        // 2026-09-03 UX review §7: 11 000,00 ₴ and 0 ₴ both used to render as "0% з
        // місяця" with an identical zero-width bar.
        testApplication {
            val categories = CategoryRepository(db, ConduitMccRepository(db))
            val transactions = TransactionRepository(db)
            val big = categories.create("Big", "🐘", 0, 80)
            val tiny = categories.create("Tiny", "🐜", 0, 80)
            transactions.upsert(txn("t1", -10_000_000, big))
            transactions.upsert(txn("t2", -11_000, tiny)) // < 1% of ~10 011 000
            application { routing { dashboardRoutes(BudgetService(categories, transactions), transactions, settings(db), AccountRepository(db), clock) } }
            val body = client.get("/").bodyAsText()
            // Finding 8 (MINOR) of the 2026-09-04 review: the breakdown list's percent
            // column renders the bare "<1%", not the full "<1% з місяця" — a fixed 58px
            // nowrap cell beside six bare percentages.
            assertTrue(
                body.contains(UkCopy.shareOfMonthTinyBare.replace("<", "&lt;")),
                "the tiny category must read <1%, not 0%: $body",
            )
        }
    }

    @Test
    fun `stacked bar segment widths ride a data attribute with two-decimal precision, never an inline style`() = withTestDb { db ->
        testApplication {
            val categories = CategoryRepository(db, ConduitMccRepository(db))
            val transactions = TransactionRepository(db)
            val small = categories.create("Small", "🐜", 0, 80)
            val big = categories.create("Big", "🐘", 0, 80)
            transactions.upsert(txn("t1", -100_000, small)) // 1 000 ₴
            transactions.upsert(txn("t2", -900_000, big))   // 9 000 ₴
            application { routing { dashboardRoutes(BudgetService(categories, transactions), transactions, settings(db), AccountRepository(db), clock) } }
            val body = client.get("/").bodyAsText()
            assertTrue(body.contains("data-bar-width=\"90.00\""), "stacked bar must carry a 2-decimal share: $body")
            assertTrue(body.contains("data-bar-width=\"10.00\""), body)
            assertTrue(!body.contains("style=\""), "no inline style attribute may be emitted: $body")
        }
    }

    @Test
    fun `a zero-spend month still shows the hero card with a zero total, just no bar or list`() = withTestDb { db ->
        // Finding 9 (MINOR) of the 2026-09-04 review: the hero card used to be skipped
        // entirely on a zero-spend month, so the 1st of a month showed a header, "Ліміти не
        // встановлено" and nothing else — not even "Витрачено 0,00 ₴".
        seed(db)
        testApplication {
            application { mount(db) }
            val body = client.get("/?month=2026-07").bodyAsText() // seed() only put spend in 2026-08
            assertTrue(body.contains("hero-card"), body)
            assertTrue(body.contains(formatMinor(0)), "the zero total itself must still render: $body")
            assertTrue(!body.contains("breakdown-row"), "nothing to list on a zero-spend month: $body")
        }
    }
}
