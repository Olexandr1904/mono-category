package app.mono

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MonoClientTest {

    private val urls = mutableListOf<String>()
    private val requestBodies = mutableListOf<String>()

    private fun monoClient(
        minIntervalMillis: Long = 0,
        respond: (path: String) -> Pair<HttpStatusCode, String>,
    ): MonoClient {
        val engine = MockEngine { request ->
            urls += request.url.encodedPath
            requestBodies += request.body.toByteArray().decodeToString()
            assertEquals("test-token", request.headers["X-Token"])
            val (status, body) = respond(request.url.encodedPath)
            respond(
                content = body,
                status = status,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(app.API_JSON) }
        }
        return HttpMonoClient(http, { "test-token" }, minIntervalMillis = minIntervalMillis)
    }

    private val clientInfoBody = """
        {"clientId":"c1","name":"Oleksandr","webHookUrl":"",
         "accounts":[
           {"id":"acc-1","sendId":"s","balance":1000000,"creditLimit":0,
            "type":"black","currencyCode":980,"maskedPan":["537541******1234"]},
           {"id":"acc-2","sendId":"s","balance":500,"creditLimit":0,
            "type":"black","currencyCode":840,"maskedPan":["537541******9999"]}
         ]}
    """.trimIndent()

    private fun statementBody(vararg ids: String) = ids.joinToString(
        prefix = "[", postfix = "]", separator = ",",
    ) { id ->
        """{"id":"$id","time":1787011200,"description":"ATB","mcc":5411,"originalMcc":5411,
            "hold":false,"amount":-84000,"operationAmount":-84000,"currencyCode":980,
            "commissionRate":0,"cashbackAmount":0,"balance":1000000}"""
    }

    @Test
    fun `clientInfo parses accounts including currency`() {
        val mono = monoClient { HttpStatusCode.OK to clientInfoBody }
        val info = runBlocking { mono.clientInfo() }
        assertEquals("Oleksandr", info.name)
        assertEquals(2, info.accounts.size)
        assertEquals(980, info.accounts.first().currencyCode)
        assertEquals("537541******1234", info.accounts.first().maskedPan.first())
        assertEquals("/personal/client-info", urls.single())
    }

    @Test
    fun `statement parses items and builds the right url`() {
        val mono = monoClient { HttpStatusCode.OK to statementBody("t1", "t2") }
        val items = runBlocking {
            mono.statement("acc-1", Instant.ofEpochSecond(1_786_000_000), Instant.ofEpochSecond(1_787_600_000))
        }
        assertEquals(listOf("t1", "t2"), items.map { it.item.id })
        assertEquals(-84_000, items.first().item.amount)
        assertEquals("/personal/statement/acc-1/1786000000/1787600000", urls.single())
        // History cannot be re-fetched (31-day window, one request per minute), so a field
        // StatementItem does not declare — commissionRate, here — must still survive in
        // raw. Losing it silently on this path is the whole reason RawItem exists.
        assertTrue(items.first().raw.contains("commissionRate"), items.first().raw)
    }

    @Test
    fun `a full page triggers pagination on the next window`() {
        var call = 0
        val mono = monoClient {
            call++
            val ids = if (call == 1) Array(500) { "a$it" } else arrayOf("b1")
            HttpStatusCode.OK to statementBody(*ids)
        }
        val items = runBlocking {
            mono.statement("acc-1", Instant.ofEpochSecond(1_786_000_000), Instant.ofEpochSecond(1_787_600_000))
        }
        assertEquals(501, items.size)
        assertEquals(2, urls.size)
        // Pins the pagination DIRECTION: the second request must narrow the upper bound
        // toward older history. Raising the lower bound would ask for the newest slice again.
        assertEquals("/personal/statement/acc-1/1786000000/1787011199", urls[1])
    }

    @Test
    fun `waitForRateLimit=false still fails fast on the second page`() {
        var call = 0
        val mono = monoClient(minIntervalMillis = 50_000) {
            call++
            val ids = if (call == 1) Array(500) { "a$it" } else arrayOf("b1")
            HttpStatusCode.OK to statementBody(*ids)
        }
        assertFailsWith<MonoRateLimitException> {
            runBlocking {
                mono.statement(
                    "acc-1", Instant.ofEpochSecond(1_786_000_000), Instant.ofEpochSecond(1_787_600_000),
                    waitForRateLimit = false,
                )
            }
        }
        assertEquals(1, urls.size, "the failure must happen before a second request is even made")
    }

    @Test
    fun `waitForRateLimit=true sleeps out the gate instead of failing`() {
        // A short configured interval keeps the test fast — real behaviour (60s in
        // production), just compressed, per HttpMonoClient's own minIntervalMillis seam.
        var call = 0
        val mono = monoClient(minIntervalMillis = 50) {
            call++
            val ids = if (call == 1) Array(500) { "a$it" } else arrayOf("b1")
            HttpStatusCode.OK to statementBody(*ids)
        }
        val items = runBlocking {
            mono.statement(
                "acc-1", Instant.ofEpochSecond(1_786_000_000), Instant.ofEpochSecond(1_787_600_000),
                waitForRateLimit = true,
            )
        }
        assertEquals(501, items.size)
        assertEquals(2, urls.size)
    }

    @Test
    fun `waitForRateLimit=true on statement does not block an independent clientInfo call`() {
        // Regression for the concurrency defect item 10 introduced: the sleep must not
        // hold the shared gate mutex, or a statement call sleeping out its window blocks
        // every other endpoint's independently-metered call too — including the very
        // clientInfo() a manual sync needs back to back with statement.
        val mono = monoClient(minIntervalMillis = 300) { path ->
            HttpStatusCode.OK to if (path.contains("statement")) statementBody("t1") else clientInfoBody
        }
        runBlocking {
            // Prime the statement gate so the next statement call must wait out the window.
            mono.statement("acc-1", Instant.ofEpochSecond(1_786_000_000), Instant.ofEpochSecond(1_786_000_100))

            val waitingStatement = async {
                mono.statement(
                    "acc-1", Instant.ofEpochSecond(1_786_000_000), Instant.ofEpochSecond(1_786_000_100),
                    waitForRateLimit = true,
                )
            }
            delay(20) // let the statement call claim the gate briefly and start sleeping
            val clientInfoTime = measureTimeMillis { mono.clientInfo() }
            waitingStatement.await()

            assertTrue(
                clientInfoTime < 150,
                "clientInfo must not be blocked by statement's independent 300ms wait, took ${clientInfoTime}ms",
            )
        }
    }

    @Test
    fun `429 surfaces as a rate limit exception`() {
        val mono = monoClient { HttpStatusCode.TooManyRequests to """{"errorDescription":"too many"}""" }
        val error = assertFailsWith<MonoRateLimitException> { runBlocking { mono.clientInfo() } }
        assertEquals(60L, error.retryAfterSeconds)
    }

    @Test
    fun `registerWebhook posts the url to the webhook endpoint`() {
        val mono = monoClient { HttpStatusCode.OK to "{}" }
        runBlocking { mono.registerWebhook("https://budget.fly.dev/webhook/s3cret") }
        assertEquals("/personal/webhook", urls.single())
        assertTrue(requestBodies.single().contains(""""webHookUrl":"https://budget.fly.dev/webhook/s3cret""""))
    }

    @Test
    fun `403 surfaces as an auth exception`() {
        val mono = monoClient { HttpStatusCode.Forbidden to """{"errorDescription":"invalid token"}""" }
        assertFailsWith<MonoAuthException> { runBlocking { mono.clientInfo() } }
    }

    @Test
    fun `calls closer than the minimum interval are rejected locally`() {
        val engine = MockEngine {
            respond(
                content = clientInfoBody,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(app.API_JSON) }
        }
        val mono = HttpMonoClient(http, { "test-token" }, minIntervalMillis = 60_000)
        runBlocking {
            mono.clientInfo()
            assertFailsWith<MonoRateLimitException> { mono.clientInfo() }
        }
    }

    @Test
    fun `each endpoint has its own rate limit window`() {
        val engine = MockEngine { request ->
            val body = if (request.url.encodedPath.contains("statement")) statementBody("t1") else clientInfoBody
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(app.API_JSON) }
        }
        val mono = HttpMonoClient(http, { "test-token" }, minIntervalMillis = 60_000)
        runBlocking {
            mono.clientInfo()
            // A statement request must not be blocked by the client-info call above.
            val items = mono.statement("acc-1", Instant.ofEpochSecond(1_786_000_000), Instant.ofEpochSecond(1_787_600_000))
            assertEquals(1, items.size)
            assertFailsWith<MonoRateLimitException> { mono.clientInfo() }
        }
    }

    @Test
    fun `a missing token is an auth error, not a crash`() {
        val engine = MockEngine { respond("{}", HttpStatusCode.OK) }
        val mono = HttpMonoClient(HttpClient(engine), { null }, minIntervalMillis = 0)
        assertFailsWith<MonoAuthException> { runBlocking { mono.clientInfo() } }
    }

    @Test
    fun `toTxn maps a statement item into the domain model`() {
        val item = StatementItem(
            id = "t1", time = 1_787_011_200, description = "ATB", mcc = 5411,
            originalMcc = 5411, hold = false, amount = -84_000,
            operationAmount = -84_000, currencyCode = 980,
        )
        val txn = item.toTxn("acc-1", """{"raw":true}""")
        assertEquals("t1", txn.id)
        assertEquals("acc-1", txn.accountId)
        assertEquals("2026-08", txn.month)
        assertEquals(-84_000, txn.amountMinor)
        assertEquals(5411, txn.mcc)
        assertEquals(false, txn.hold)
        assertEquals(null, txn.categoryId)
        assertEquals(false, txn.manuallyCategorized)
    }
}
