package app.web

import app.budget.BudgetService
import app.budget.CategoryRepository
import app.budget.ConduitMccRepository
import app.budget.CounterpartyRepository
import app.budget.KYIV
import app.budget.TransactionRepository
import app.budget.Txn
import app.budget.formatMinor
import app.budget.formatMinorWhole
import app.budget.monthKeyOf
import app.db.Crypto
import app.db.SettingKeys
import app.db.SettingsRepository
import app.ingest.AccountRepository
import app.ingest.IngestService
import app.notify.FakeTelegramClient
import app.notify.NotificationEventRepository
import app.notify.Notifier
import app.withTestDb
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.Database
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CategoriesPageTest {

    private val august = 1_787_011_200L
    private val clock = Clock.fixed(Instant.ofEpochSecond(august), KYIV)

    private fun ApplicationTestBuilder.setup(db: Database) {
        val conduit = ConduitMccRepository(db)
        val counterparties = CounterpartyRepository(db)
        val categories = CategoryRepository(db, conduit)
        val transactions = TransactionRepository(db)
        val settings = SettingsRepository(db, Crypto(ByteArray(32) { it.toByte() }))
        settings.set(SettingKeys.TELEGRAM_CHAT_ID, "1")
        val ingest = IngestService(
            transactions, categories,
            BudgetService(categories, transactions),
            Notifier(FakeTelegramClient(), settings, NotificationEventRepository(db)),
            counterparties, conduit, AccountRepository(db),
            clock = clock,
        )
        application {
            routing {
                categoryRoutes(
                    categories, ingest, settings, conduit, counterparties,
                    BudgetService(categories, transactions), AccountRepository(db), clock,
                )
            }
        }
    }

    /**
     * Mounts categoryRoutes and hands the test a real CSRF token lifted from the rendered
     * page, mirroring how a browser would carry it.
     */
    private fun withCategoriesApp(db: Database, block: suspend ApplicationTestBuilder.(HttpClient, String) -> Unit) =
        testApplication {
            setup(db)
            val client = createClient { followRedirects = false }
            val csrf = Regex("name=\"csrf_token\" value=\"([0-9a-f]+)\"")
                .find(client.get("/categories").bodyAsText())!!.groupValues[1]
            block(client, csrf)
        }

    private fun txn(id: String, amountMinor: Long, categoryId: Long?) = Txn(
        id = id, accountId = "acc-1", occurredAt = august, month = monthKeyOf(august),
        amountMinor = amountMinor, currencyCode = 980, description = "x",
        mcc = 5411, originalMcc = 5411, hold = false,
        categoryId = categoryId, manuallyCategorized = false, rawJson = "{}",
    )

    @Test
    fun `parseMccList accepts commas, spaces and newlines`() {
        assertEquals(setOf(5812, 5814, 5411), parseMccList("5812, 5814\n5411"))
        assertEquals(emptySet(), parseMccList("   "))
        assertEquals(setOf(5812), parseMccList("5812,,5812"))
    }

    @Test
    fun `sanitizeEmoji drops a limit typed into the emoji box`() {
        assertEquals("🍽", sanitizeEmoji("🍽25000"))
        assertEquals("🍽", sanitizeEmoji("🍽 25 000"))
        assertEquals("", sanitizeEmoji("10000"))
        assertEquals("🛍", sanitizeEmoji("🛍"))
        assertEquals("", sanitizeEmoji(""))
    }

    @Test
    fun `every category limit at zero is the empty state — the owner's actual current reality`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        categories.create("Продукти", "🛒", 0, 80)
        categories.create("Авто", "🚗", 0, 80)
        testApplication {
            setup(db)
            val body = client.get("/categories").bodyAsText()
            assertTrue(body.contains(app.i18n.UkCopy.ofCategoriesCount(2)), body)
            assertTrue(body.contains("cat-limit-ghost"), "a no-limit category must show the dashed ghost button: $body")
            assertTrue(body.contains(app.i18n.UkCopy.setLimitButton), body)
            assertEquals(0, Regex("limit-bar-track").findAll(body).count(), "no category has a limit, so no bar should render: $body")
        }
    }

    @Test
    fun `the summary strip counts spend, limited categories, and over-limit categories`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val transactions = TransactionRepository(db)
        val restaurants = categories.create("Restaurants", "🍔", 1_000_000, 80) // limit 10 000
        val home = categories.create("Home", "🏠", 0, 80) // no limit
        transactions.upsert(txn("t1", -1_243_000, restaurants)) // 12 430, over its 10 000 limit
        transactions.upsert(txn("t2", -50_000, home))
        testApplication {
            setup(db)
            val body = client.get("/categories").bodyAsText()
            assertTrue(body.contains(formatMinor(1_293_000)), "categorized total missing (12430+500): $body")
            assertTrue(body.contains(app.i18n.UkCopy.ofMonthTotal(formatMinor(1_293_000))), body)
            assertTrue(body.contains(app.i18n.UkCopy.ofCategoriesCount(2)), body)
            // One category is over its limit, so the caption must agree with "1".
            assertTrue(body.contains(app.i18n.UkCopy.categoriesCountWord(1)), body)
            // "1" appears both as "з лімітом" count and "перевищено" count here (one
            // category, over) — assert the pill and hint text landed rather than counting
            // digits.
            assertTrue(body.contains(app.i18n.UkCopy.overLimitPill), body)
        }
    }

    @Test
    fun `the summary strip's categorized total excludes a category marked not counted as spending`() =
        withTestDb { db ->
            // Finding 3 (CRITICAL) of the 2026-09-04 review: this figure used to sum every
            // category's spend unconditionally, so a "Перекази" category holding real money
            // but marked countsAsSpending = false made "РОЗІБРАНО ЗА КАТЕГОРІЯМИ" render
            // larger than "з <month total>" right next to it — a part bigger than its whole.
            // BudgetService.monthSummary's own totalSpentMinor already excludes such a
            // category; this figure must agree with it.
            val categories = CategoryRepository(db, ConduitMccRepository(db))
            val transactions = TransactionRepository(db)
            val groceries = categories.create("Groceries", "🛒", 0, 80)
            val transfers = categories.create("Перекази", "🔁", 0, 80)
            categories.update(categories.byId(transfers)!!.copy(countsAsSpending = false))
            transactions.upsert(txn("t1", -50_000, groceries)) // 500 ₴, counted
            transactions.upsert(txn("t2", -4_000_000, transfers)) // 40 000 ₴, not counted
            testApplication {
                setup(db)
                val body = client.get("/categories").bodyAsText()
                assertTrue(
                    body.contains(app.i18n.UkCopy.ofMonthTotal(formatMinor(50_000))),
                    "the categorized figure must match the month total, not include the excluded category's 40 000: $body",
                )
                // The excluded category's own row is untouched by this filter — its real
                // spend still shows on its own line.
                assertTrue(body.contains(formatMinor(4_000_000)), "the not-counted category's own row must still show its real spend: $body")
            }
        }

    @Test
    fun `a row shows its emoji tile, mcc codes, limit bar and spent amount`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val transactions = TransactionRepository(db)
        val id = categories.create("Ресторани та бари", "🍽", 1_500_000, 80)
        categories.setMcc(id, setOf(5812, 5814))
        transactions.upsert(txn("t1", -1_243_000, id)) // 82%
        testApplication {
            setup(db)
            val body = client.get("/categories").bodyAsText()
            assertTrue(body.contains("🍽"), body)
            assertTrue(body.contains("Ресторани та бари"), body)
            assertTrue(body.contains("${app.i18n.UkCopy.mccAbbrev} 5812, 5814"), body)
            assertTrue(body.contains("${formatMinorWhole(1_500_000)} · 82%"), body)
            assertTrue(body.contains("data-bar-width=\"82\""), "limit bar width must be a data attribute: $body")
            assertTrue(body.contains(formatMinor(1_243_000)), body)
            assertTrue(body.contains(app.i18n.UkCopy.limitRemainingHint(formatMinorWhole(257_000))), body)
        }
    }

    @Test
    fun `a category with no mcc codes says so instead of an empty line`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        categories.create("Оренда", "🏠", 0, 80)
        testApplication {
            setup(db)
            val body = client.get("/categories").bodyAsText()
            assertTrue(body.contains(app.i18n.UkCopy.noMccCodesHint), body)
        }
    }

    @Test
    fun `a disabled category shows the disabled pill and an over-limit one shows перевищено`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val transactions = TransactionRepository(db)
        val off = categories.create("Вимкнена", "🚫", 0, 80)
        categories.update(categories.byId(off)!!.copy(enabled = false))
        val over = categories.create("Перевитрачена", "💸", 100_000, 80) // limit 1 000
        transactions.upsert(txn("t1", -200_000, over)) // 2 000, 200%
        testApplication {
            setup(db)
            val body = client.get("/categories").bodyAsText()
            assertTrue(body.contains(app.i18n.UkCopy.disabledPill), body)
            assertTrue(body.contains(app.i18n.UkCopy.overLimitPill), body)
        }
    }

    @Test
    fun `a warning-threshold category shows its live percent as a pill`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val transactions = TransactionRepository(db)
        val warn = categories.create("На межі", "⚠️", 100_000, 80) // limit 1 000
        transactions.upsert(txn("t1", -85_000, warn)) // 850 = 85%, >= 80% threshold
        testApplication {
            setup(db)
            val body = client.get("/categories").bodyAsText()
            assertTrue(body.contains(">85%<"), body)
        }
    }

    @Test
    fun `Додати категорію creates a stub category and opens its editor`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        withCategoriesApp(db) { client, csrf ->
            val response = client.submitForm(
                "/categories/new",
                parameters { append(CSRF_FIELD, csrf) },
            ) { header(HttpHeaders.Origin, "http://localhost") }
            assertEquals(HttpStatusCode.Found, response.status)
            val created = categories.list().single()
            assertEquals("Нова категорія", created.name)
            assertEquals("•", created.emoji)
            assertEquals(0L, created.monthlyLimitMinor)
            val location = response.headers[HttpHeaders.Location]!!
            assertTrue(location.contains("open=${created.id}"), location)

            val body = client.get(location).bodyAsText()
            assertTrue(Regex("""<details class="cat-row" open""").containsMatchIn(body), body)
        }
    }

    @Test
    fun `a row not asked to open renders its details closed`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        categories.create("Restaurants", "🍔", 1_500_000, 80)
        testApplication {
            setup(db)
            val body = client.get("/categories").bodyAsText()
            assertTrue(!Regex("""<details class="cat-row" open""").containsMatchIn(body), body)
        }
    }

    @Test
    fun `saving the editor updates the category and keeps the row open`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val id = categories.create("Restaurants", "🍔", 1_500_000, 80)
        testApplication {
            setup(db)
            val client = createClient { followRedirects = false }
            val response = client.submitForm(
                "/categories/$id",
                parameters {
                    append("name", "Restaurants"); append("emoji", "🍔")
                    append("limit", "20000"); append("threshold", "70")
                    append("mcc", "5812, 5814"); append("enabled", "on")
                    append("notifyExceeded", "on")
                },
            )
            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(response.headers["Location"]!!.contains("open=$id"), response.headers["Location"].toString())
            val updated = categories.byId(id)!!
            assertEquals(2_000_000, updated.monthlyLimitMinor)
            assertEquals(70, updated.thresholdPct)
            assertTrue(updated.enabled)
            assertTrue(updated.notifyExceeded)
            assertTrue(!updated.notifyWarning, "unchecked boxes must turn the flag off")
            assertEquals(setOf(5812, 5814), categories.mccOf(id))
        }
    }

    /**
     * Finding 1 (CRITICAL) of the 2026-09 branch review: the threshold input and both
     * notify checkboxes render `disabled` while a category's limit is 0 — a browser never
     * submits a disabled control — but the write guard used to key off the *newly
     * submitted* limit (`limit > 0`) instead of the limit the form was actually rendered
     * against. Giving a limit-0 category its first-ever limit in one save means the form
     * was rendered disabled (existing limit 0) but the guard saw the new limit > 0 and read
     * the fields' absence as an explicit "turn notifications off" — silently disabling
     * alerts on the exact save that makes them start mattering. This must still hold true
     * on the redesigned editor.
     */
    @Test
    fun `giving a category its first limit does not silently turn its notifications off`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val id = categories.create("Продукти", "🛒", 0, 80)
        assertTrue(categories.byId(id)!!.notifyWarning)
        assertTrue(categories.byId(id)!!.notifyExceeded)
        testApplication {
            setup(db)
            val client = createClient { followRedirects = false }
            // Mirrors exactly what a browser sends when the limit box goes from 0 to 15000
            // in one submit: the threshold/notify fields were disabled at render time, so
            // they are absent from the wire.
            client.submitForm(
                "/categories/$id",
                parameters {
                    append("name", "Продукти"); append("emoji", "🛒")
                    append("limit", "15000")
                },
            )
            val updated = categories.byId(id)!!
            assertEquals(1_500_000, updated.monthlyLimitMinor)
            assertTrue(updated.notifyWarning, "notifyWarning must survive giving the category its first limit")
            assertTrue(updated.notifyExceeded, "notifyExceeded must survive giving the category its first limit")
        }
    }

    @Test
    fun `the notify-at checkbox label reads the category's real warn threshold, never a hardcoded 80`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        categories.create("Restaurants", "🍔", 1_500_000, 65)
        testApplication {
            setup(db)
            val body = client.get("/categories").bodyAsText()
            assertTrue(body.contains(app.i18n.UkCopy.notifyAtLabel(65)), body)
            assertTrue(!body.contains(app.i18n.UkCopy.notifyAtLabel(80)), body)
        }
    }

    @Test
    fun `the footer hint says notifications are off when the limit is zero`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        categories.create("Продукти", "🛒", 0, 80)
        testApplication {
            setup(db)
            val body = client.get("/categories").bodyAsText()
            assertTrue(body.contains(app.i18n.UkCopy.notifyFooterHintNoLimit), body)
        }
    }

    @Test
    fun `the footer hint states both thresholds when a limit is set`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        categories.create("Restaurants", "🍔", 1_500_000, 75)
        testApplication {
            setup(db)
            val body = client.get("/categories").bodyAsText()
            assertTrue(body.contains(app.i18n.UkCopy.notifyFooterHint(75)), body)
        }
    }

    @Test
    fun `updating mcc recategorizes existing transactions`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val transactions = TransactionRepository(db)
        val id = categories.create("Home", "🏠", 1_000_000, 80)
        transactions.upsert(
            Txn("t1", "acc-1", august, monthKeyOf(august), -50_000, 980, "Epicentr",
                5712, 5712, false, null, false, "{}"),
        )
        testApplication {
            setup(db)
            val client = createClient { followRedirects = false }
            client.submitForm(
                "/categories/$id",
                parameters {
                    append("name", "Home"); append("emoji", "🏠")
                    append("limit", "10000"); append("threshold", "80")
                    append("mcc", "5712"); append("enabled", "on")
                },
            )
            assertEquals(id, transactions.byId("t1")!!.categoryId)
        }
    }

    @Test
    fun `an mcc owned by another category is refused with an error and keeps the row open`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val restaurants = categories.create("Restaurants", "🍔", 1_500_000, 80)
        val groceries = categories.create("Groceries", "🛒", 2_500_000, 80)
        categories.setMcc(restaurants, setOf(5812))
        testApplication {
            setup(db)
            val client = createClient { followRedirects = false }
            val response = client.submitForm(
                "/categories/$groceries",
                parameters {
                    append("name", "Groceries"); append("emoji", "🛒")
                    append("limit", "25000"); append("threshold", "80")
                    append("mcc", "5812"); append("enabled", "on")
                },
            )
            assertEquals(HttpStatusCode.Found, response.status)
            val location = response.headers["Location"]!!
            assertTrue(location.contains("error="), location)
            assertTrue(location.contains("open=$groceries"), location)
            assertEquals(mapOf(5812 to restaurants), categories.mccMapping())
        }
    }

    @Test
    fun `delete removes the category and orphans its transactions`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val transactions = TransactionRepository(db)
        val id = categories.create("Home", "🏠", 1_000_000, 80)
        transactions.upsert(
            Txn("t1", "acc-1", august, monthKeyOf(august), -50_000, 980, "Epicentr",
                5712, 5712, false, id, false, "{}"),
        )
        testApplication {
            setup(db)
            val client = createClient { followRedirects = false }
            client.submitForm("/categories/$id/delete", parameters { })
            assertNull(categories.byId(id))
            assertNull(transactions.byId("t1")!!.categoryId)
        }
    }

    @Test
    fun `a limit with kopecks survives an unchanged save`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val id = categories.create("Restaurants", "🍔", 1_500_050, 80)
        testApplication {
            setup(db)
            val body = client.get("/categories").bodyAsText()
            assertTrue(body.contains("15000.50"), body)

            val client2 = createClient { followRedirects = false }
            client2.submitForm(
                "/categories/$id",
                parameters {
                    append("name", "Restaurants"); append("emoji", "🍔")
                    append("limit", "15000.50"); append("threshold", "80")
                    append("enabled", "on")
                },
            )
            assertEquals(1_500_050, categories.byId(id)!!.monthlyLimitMinor)
        }
    }

    @Test
    fun `every form on the page carries a CSRF token`() = withTestDb { db ->
        // Logout (page header) + Стандартний набір + Додати категорію + conduit codes +
        // one category's edit form + its separate delete form = 6.
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        categories.create("Restaurants", "🍔", 1_500_000, 80)
        testApplication {
            setup(db)
            val body = client.get("/categories").bodyAsText()
            val tokenFields = Regex("name=\"csrf_token\"").findAll(body).count()
            assertEquals(6, tokenFields, body)
        }
    }

    @Test
    fun `an empty category list shows the empty state`() = withTestDb { db ->
        testApplication {
            setup(db)
            val body = client.get("/categories").bodyAsText()
            assertTrue(body.contains(app.i18n.UkCopy.noCategoriesYet), body)
            assertTrue(body.contains(app.i18n.UkCopy.categoriesEmptyBody), body)
        }
    }

    @Test
    fun `no inline style attribute is emitted anywhere on the page`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val transactions = TransactionRepository(db)
        val id = categories.create("Restaurants", "🍔", 1_500_000, 80)
        transactions.upsert(txn("t1", -1_243_000, id))
        testApplication {
            setup(db)
            val body = client.get("/categories").bodyAsText()
            assertTrue(body.contains("cat-row"), "expected the category list to actually render: $body")
            assertTrue(!body.contains("style=\""), "no inline style attribute may be emitted: $body")
        }
    }

    @Test
    fun `filling with defaults creates the starter categories and their mcc codes`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        testApplication {
            setup(db)
            val client = createClient { followRedirects = false }
            val response = client.submitForm("/categories/seed-defaults", parameters { })
            assertEquals(HttpStatusCode.Found, response.status)

            val created = categories.list()
            assertEquals(11, created.size)
            val auto = created.single { it.name == "Авто" }
            assertEquals(0L, auto.monthlyLimitMinor, "seeded categories must start with no limit")
            assertEquals(setOf(5511, 5521, 5532, 5533, 5541), categories.mccOf(auto.id))
        }
    }

    @Test
    fun `filling with defaults never steals an mcc or overwrites a limit the owner set`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val transactions = TransactionRepository(db)
        val myAuto = categories.create("Авто", "🚙", 5_000_000, 90)
        val other = categories.create("Custom", "❓", 1_000_000, 80)
        categories.setMcc(other, setOf(5511))
        transactions.upsert(
            Txn("t1", "acc-1", august, monthKeyOf(august), -50_000, 980, "Fuel",
                5521, 5521, false, null, false, "{}"),
        )
        testApplication {
            setup(db)
            val client = createClient { followRedirects = false }
            client.submitForm("/categories/seed-defaults", parameters { })

            val auto = categories.byId(myAuto)!!
            assertEquals(5_000_000, auto.monthlyLimitMinor, "an existing category's limit must survive seeding")
            assertEquals(90, auto.thresholdPct)
            assertEquals(setOf(5511), categories.mccOf(other), "an mcc owned elsewhere must not be stolen")
            assertTrue(5511 !in categories.mccOf(myAuto), "the contested mcc must not land on Авто either")
            assertTrue(5521 in categories.mccOf(myAuto))
            assertEquals(myAuto, transactions.byId("t1")!!.categoryId)
        }
    }

    @Test
    fun `seeding defaults reports a conduit code separately from an ownership conflict`() = withTestDb { db ->
        val conduit = ConduitMccRepository(db)
        conduit.replaceAll(setOf(4829))

        withCategoriesApp(db) { client, csrf ->
            val response = client.submitForm(
                "/categories/seed-defaults",
                parameters { append(CSRF_FIELD, csrf) },
            ) { header(HttpHeaders.Origin, "http://localhost") }
            val location = response.headers[HttpHeaders.Location]!!
            val flash = java.net.URLDecoder.decode(location.substringAfter("flash="), "UTF-8")
            assertTrue(flash.contains("транзитними: 1"), flash)
            assertTrue(!flash.contains("зайнято"), flash)
        }
    }

    @Test
    fun `filling with defaults twice does not duplicate categories or mcc codes`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        testApplication {
            setup(db)
            val client = createClient { followRedirects = false }
            client.submitForm("/categories/seed-defaults", parameters { })
            client.submitForm("/categories/seed-defaults", parameters { })

            assertEquals(11, categories.list().size)
            val auto = categories.list().single { it.name == "Авто" }
            assertEquals(setOf(5511, 5521, 5532, 5533, 5541), categories.mccOf(auto.id))
        }
    }

    @Test
    fun `the conduit codes field saves and is shown back`() = withTestDb { db ->
        withCategoriesApp(db) { client, csrf ->
            val response = client.submitForm(
                "/categories/conduit",
                parameters { append("mcc", "4829, 6051"); append(CSRF_FIELD, csrf) },
            ) { header(HttpHeaders.Origin, "http://localhost") }
            assertEquals(HttpStatusCode.Found, response.status)

            val body = client.get("/categories").bodyAsText()
            assertTrue(body.contains("4829, 6051"), body)
        }
    }

    @Test
    fun `emptying the conduit field is announced, not reported as an ordinary save`() = withTestDb { db ->
        val conduit = ConduitMccRepository(db)
        conduit.replaceAll(setOf(4829))

        withCategoriesApp(db) { client, csrf ->
            val response = client.submitForm(
                "/categories/conduit",
                parameters { append("mcc", ""); append(CSRF_FIELD, csrf) },
            ) { header(HttpHeaders.Origin, "http://localhost") }
            val location = response.headers[HttpHeaders.Location]!!
            val flash = java.net.URLDecoder.decode(location.substringAfter("flash="), "UTF-8")
            assertEquals(app.i18n.UkCopy.conduitMccCleared(0), flash)
        }
        assertTrue(conduit.list().isEmpty())
    }

    @Test
    fun `saving a non-empty conduit field keeps the ordinary saved flash`() = withTestDb { db ->
        withCategoriesApp(db) { client, csrf ->
            val response = client.submitForm(
                "/categories/conduit",
                parameters { append("mcc", "4829"); append(CSRF_FIELD, csrf) },
            ) { header(HttpHeaders.Origin, "http://localhost") }
            val location = response.headers[HttpHeaders.Location]!!
            val flash = java.net.URLDecoder.decode(location.substringAfter("flash="), "UTF-8")
            assertEquals(app.i18n.UkCopy.conduitMccSaved(0), flash)
        }
    }

    @Test
    fun `a conduit code cannot be typed into a category and the error says so`() = withTestDb { db ->
        val conduit = ConduitMccRepository(db)
        conduit.replaceAll(setOf(4829))
        val categories = CategoryRepository(db, conduit)
        val misc = categories.create("Різне", "📦", 0, 80)

        withCategoriesApp(db) { client, csrf ->
            val response = client.submitForm(
                "/categories/$misc",
                parameters { append("mcc", "4829"); append(CSRF_FIELD, csrf) },
            ) { header(HttpHeaders.Origin, "http://localhost") }
            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(response.headers[HttpHeaders.Location]!!.contains("error"), response.headers.toString())
        }
        assertTrue(categories.mccOf(misc).isEmpty())
    }

    @Test
    fun `a counterparty rule is listed and can be removed`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val rent = categories.create("Оренда", "🏠", 0, 80)
        CounterpartyRepository(db).upsert("card:4441**1234", rent, "Петренко Іван")

        withCategoriesApp(db) { client, csrf ->
            assertTrue(client.get("/categories").bodyAsText().contains("Петренко Іван"))
            client.submitForm(
                "/categories/counterparty/delete",
                parameters { append("key", "card:4441**1234"); append(CSRF_FIELD, csrf) },
            ) { header(HttpHeaders.Origin, "http://localhost") }
            assertTrue(!client.get("/categories").bodyAsText().contains("Петренко Іван"))
        }
    }
}
