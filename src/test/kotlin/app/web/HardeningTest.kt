package app.web

import app.budget.BudgetService
import app.budget.CategoryRepository
import app.budget.ConduitMccRepository
import app.budget.CounterpartyRepository
import app.budget.TransactionRepository
import app.budget.isMonthKey
import app.budget.safeMonthKey
import app.db.Crypto
import app.db.SettingsRepository
import app.ingest.AccountRepository
import app.ingest.IngestService
import app.notify.FakeTelegramClient
import app.notify.NotificationEventRepository
import app.notify.Notifier
import app.ingest.WebhookEventRepository
import app.resolvePublicBaseUrl
import app.withTestDb
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.Database
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HardeningTest {

    // --- stored XSS in the delete button -------------------------------------------

    /**
     * The exact payload that used to execute. kotlinx.html escapes `<`, `>`, `&` and `"`
     * in an attribute value but *not* `'`, so interpolating a category name into an
     * inline `onclick="return confirm('…')"` let the name close the JavaScript string
     * literal and continue as code.
     *
     * This drives the real route rather than a hand-built snippet, so reverting
     * CategoriesPage.kt fails the test.
     */
    private val breakout = "x'); alert(document.cookie); ('"

    private fun ApplicationTestBuilder.categoriesApp(db: Database) {
        val conduit = ConduitMccRepository(db)
        val counterparties = CounterpartyRepository(db)
        val categories = CategoryRepository(db, conduit)
        val transactions = TransactionRepository(db)
        val settings = SettingsRepository(db, Crypto(ByteArray(32) { it.toByte() }))
        val ingest = IngestService(
            transactions, categories,
            BudgetService(categories, transactions),
            Notifier(FakeTelegramClient(), settings, NotificationEventRepository(db)),
            counterparties, conduit, AccountRepository(db),
        )
        application {
            routing {
                categoryRoutes(
                    categories, ingest, settings, conduit, counterparties,
                    BudgetService(categories, transactions), AccountRepository(db),
                )
            }
        }
    }

    @Test
    fun `a category name cannot break out of the delete confirmation`() = withTestDb { db ->
        testApplication {
            categoriesApp(db)
            CategoryRepository(db, ConduitMccRepository(db)).create(breakout, "", 0, 80)

            val html = client.get("/categories").bodyAsText()

            assertTrue(html.contains("data-confirm=\""), "the confirmation must be carried as data: $html")
            assertFalse(html.contains("onclick"), "an inline handler must not come back: $html")
            // The signature of the old bug: the payload's `');` sitting in the document
            // outside any quoted attribute value, where a parser reads it as code.
            assertFalse(html.contains("confirm('Delete x');"), html)
            // The name is still shown to the user, just inert — escaping turned the only
            // character that could close this attribute into an entity.
            assertTrue(html.contains("alert(document.cookie)"), html)
        }
    }

    @Test
    fun `a category name with a double quote cannot close the attribute either`() = withTestDb { db ->
        testApplication {
            categoriesApp(db)
            CategoryRepository(db, ConduitMccRepository(db)).create("""a" onmouseover="alert(1)""", "", 0, 80)

            val html = client.get("/categories").bodyAsText()

            assertFalse(html.contains("onmouseover=\"alert"), "the quote must be escaped, not closing: $html")
            assertTrue(html.contains("&quot;"), html)
        }
    }

    // --- open redirect ---------------------------------------------------------------

    @Test
    fun `safeBackLink keeps same-origin transaction links`() {
        assertEquals("/transactions", safeBackLink(null))
        assertEquals("/transactions", safeBackLink(""))
        assertEquals("/transactions?month=2026-08", safeBackLink("/transactions?month=2026-08"))
        assertEquals("/transactions?offset=50&category=3", safeBackLink("/transactions?offset=50&category=3"))
    }

    @Test
    fun `safeBackLink refuses anything that could leave the site`() {
        // Protocol-relative: browsers follow this off-site.
        assertEquals("/transactions", safeBackLink("//evil.example"))
        // Backslash variants that several browsers normalise to the above.
        assertEquals("/transactions", safeBackLink("/\\evil.example"))
        assertEquals("/transactions", safeBackLink("/transactions\\@evil.example"))
        assertEquals("/transactions", safeBackLink("https://evil.example"))
        assertEquals("/transactions", safeBackLink("http://evil.example/transactions"))
        // Prefix that merely starts with the allowed path.
        assertEquals("/transactions", safeBackLink("/transactions.evil.example"))
        assertEquals("/transactions", safeBackLink("/transactionsevil"))
        // Header splitting.
        assertEquals("/transactions", safeBackLink("/transactions\r\nLocation: https://evil.example"))
    }

    // --- security headers -------------------------------------------------------------

    @Test
    fun `security headers are present on every response including public ones`() = testApplication {
        application {
            installSecurityHeaders(hsts = true)
            routing {
                get("/health") { call.respondText("ok") }
                get("/") { call.respondText("dashboard") }
            }
        }
        for (path in listOf("/health", "/")) {
            val response = client.get(path)
            assertEquals("nosniff", response.headers["X-Content-Type-Options"], path)
            assertEquals("DENY", response.headers["X-Frame-Options"], path)
            // same-origin, not no-referrer: both keep the query string's flash text from
            // travelling off-origin, but no-referrer also made Chrome post Origin: null on
            // same-origin form submits, which the cross-origin check rejected — every form
            // in the app returned 403. See SecurityHeaders.kt.
            assertEquals("same-origin", response.headers["Referrer-Policy"], path)
            assertTrue(
                response.headers["Strict-Transport-Security"]!!.contains("max-age=31536000"),
                path,
            )
            val csp = response.headers["Content-Security-Policy"]!!
            assertTrue(csp.contains("default-src 'none'"), csp)
            assertTrue(csp.contains("frame-ancestors 'none'"), csp)
            // Without this, default-src 'none' blocks every self-hosted @font-face src and
            // every number on the page falls back to a system font with no visible error.
            assertTrue(csp.contains("font-src 'self'"), csp)
            // The whole point of moving the handlers into app.js:
            assertFalse(csp.contains("unsafe-inline"), csp)
        }
    }

    @Test
    fun `HSTS is withheld in local development`() = testApplication {
        // Serving it from localhost during one HTTPS experiment would pin the developer's
        // browser to HTTPS for localhost for a year.
        application {
            installSecurityHeaders(hsts = false)
            routing { get("/") { call.respondText("ok") } }
        }
        assertNull(client.get("/").headers["Strict-Transport-Security"])
    }

    // --- month parsing ----------------------------------------------------------------

    @Test
    fun `month keys are validated before they reach the date parser`() {
        assertTrue(isMonthKey("2026-08"))
        assertTrue(isMonthKey("1970-01"))
        assertFalse(isMonthKey("2026-13"), "month 13 does not exist")
        assertFalse(isMonthKey("2026-00"))
        assertFalse(isMonthKey("2026-8"), "unpadded month")
        assertFalse(isMonthKey("garbage"))
        assertFalse(isMonthKey("+2026-08"))
        assertFalse(isMonthKey("2026-08-01"))
        assertFalse(isMonthKey(""))
    }

    @Test
    fun `a malformed month falls back to the current one instead of throwing`() {
        val clock = Clock.fixed(Instant.parse("2026-08-21T10:00:00Z"), ZoneOffset.UTC)
        // previousMonthKey/nextMonthKey parse this value, so an unchecked one used to
        // surface as an uncaught DateTimeParseException and a 500.
        assertEquals("2026-08", safeMonthKey("garbage", clock))
        assertEquals("2026-08", safeMonthKey(null, clock))
        assertEquals("2026-08", safeMonthKey("2026-13", clock))
        assertEquals("2026-03", safeMonthKey("2026-03", clock))
    }

    // --- webhook body limit -----------------------------------------------------------

    @Test
    fun `an oversized webhook body is refused instead of being written to disk`() = withTestDb { db ->
        testApplication {
            val events = WebhookEventRepository(db)
            application { routing { monoWebhookRoutes({ "s3cret" }, events) } }

            val oversized = "x".repeat(64 * 1024 + 1)
            val response = client.post("/webhook/s3cret") { setBody(oversized) }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertTrue(events.unprocessed().isEmpty(), "nothing over the cap may reach the volume")
        }
    }

    @Test
    fun `a normal webhook body is still accepted`() = withTestDb { db ->
        testApplication {
            val events = WebhookEventRepository(db)
            application { routing { monoWebhookRoutes({ "s3cret" }, events) } }

            val response = client.post("/webhook/s3cret") { setBody("""{"type":"StatementItem"}""") }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("""{"type":"StatementItem"}""", events.unprocessed().single().second)
        }
    }

    @Test
    fun `a chunked body with no declared length is still capped`() = withTestDb { db ->
        testApplication {
            val events = WebhookEventRepository(db)
            application { routing { monoWebhookRoutes({ "s3cret" }, events) } }

            // No Content-Length at all: a WriteChannelContent of unknown length makes the
            // client use chunked transfer encoding. This is the path the declared-length
            // shortcut cannot cover, and the only thing standing in front of the volume
            // is the capped read itself.
            val chunked = object : OutgoingContent.WriteChannelContent() {
                override val contentLength: Long? = null
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    repeat(65) { channel.writeFully(ByteArray(1024) { 'x'.code.toByte() }) }
                    channel.flushAndClose()
                }
            }
            val response = client.post("/webhook/s3cret") { setBody(chunked) }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertTrue(events.unprocessed().isEmpty(), "an undeclared-length body must not reach the volume either")
        }
    }

    // --- webhook retention ------------------------------------------------------------

    @Test
    fun `the retention sweep drops processed events and keeps unprocessed ones`() = withTestDb { db ->
        val events = WebhookEventRepository(db)
        val old = events.record("""{"old":true}""")
        val stillOwed = events.record("""{"unprocessed":true}""")
        events.markProcessed(old)

        // Everything recorded just now, so a cutoff in the future covers both rows.
        val removed = events.deleteProcessedBefore(Instant.now().epochSecond + 60)

        assertEquals(1, removed)
        assertEquals(
            listOf(stillOwed),
            events.unprocessed().map { it.first },
            "an unprocessed row is still owed work and must survive any sweep",
        )
    }

    @Test
    fun `the retention sweep leaves recent processed events alone`() = withTestDb { db ->
        val events = WebhookEventRepository(db)
        val recent = events.record("""{"recent":true}""")
        events.markProcessed(recent)

        assertEquals(0, events.deleteProcessedBefore(Instant.now().epochSecond - 30L * 24 * 3600))
    }

    // --- public base URL --------------------------------------------------------------

    @Test
    fun `PUBLIC_URL wins and the request headers are never consulted`() {
        assertEquals(
            "https://budget.fly.dev",
            resolvePublicBaseUrl(
                publicUrl = "https://budget.fly.dev/",
                allowedHosts = emptySet(),
                forwardedHost = "evil.example",
                requestHost = "evil.example",
            ),
        )
    }

    @Test
    fun `an unlisted forwarded host is refused when an allowlist is configured`() {
        // This URL is where Monobank pushes every transaction; an attacker-chosen host
        // would redirect the entire feed.
        assertNull(
            resolvePublicBaseUrl(
                publicUrl = null,
                allowedHosts = setOf("budget.fly.dev"),
                forwardedHost = "evil.example",
                requestHost = "budget.fly.dev",
            ),
        )
        assertEquals(
            "https://budget.fly.dev",
            resolvePublicBaseUrl(
                publicUrl = null,
                allowedHosts = setOf("budget.fly.dev"),
                forwardedHost = "budget.fly.dev",
                requestHost = "budget.fly.dev",
            ),
        )
    }

    @Test
    fun `a host with any structure in it is refused rather than parsed`() {
        val hostile = listOf(
            "evil.example/path",
            "budget.fly.dev@evil.example",
            "evil.example#",
            "evil.example?x=1",
            "https://evil.example",
            "evil example",
            "",
        )
        for (host in hostile) {
            assertNull(
                resolvePublicBaseUrl(null, emptySet(), host, null),
                "must refuse to build a webhook URL from '$host'",
            )
        }
    }

    @Test
    fun `localhost is never a public webhook target`() {
        for (host in listOf("localhost", "127.0.0.1", "0.0.0.0", "::1")) {
            assertNull(resolvePublicBaseUrl(null, emptySet(), null, host), host)
        }
    }

    @Test
    fun `a plain hostname is accepted when no allowlist is set`() {
        assertEquals("https://budget.fly.dev", resolvePublicBaseUrl(null, emptySet(), "budget.fly.dev", null))
        // Port is stripped; the scheme is always https because Fly terminates TLS.
        assertEquals("https://budget.fly.dev", resolvePublicBaseUrl(null, emptySet(), "budget.fly.dev:8443", null))
    }
}
