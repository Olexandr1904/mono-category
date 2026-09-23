package app.mono

import app.API_JSON
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import java.time.Instant

class MonoRateLimitException(val retryAfterSeconds: Long) :
    RuntimeException("Monobank allows one request per 60 seconds; retry in ${retryAfterSeconds}s")

class MonoAuthException(message: String = "Monobank token is missing or rejected") :
    RuntimeException(message)

class MonoApiException(val status: Int, message: String) : RuntimeException(message)

interface MonoClient {
    /**
     * [waitForRateLimit] means the same thing here as in [statement], and exists for the
     * same caller. Client-info has its own once-a-minute gate, and `SyncService` now
     * refreshes accounts on *every* sync rather than only when the table is empty — so an
     * hourly tick landing within a minute of a manual Sync press hits that gate. The margin
     * is zero by construction (`MIN_SYNC_INTERVAL_SECONDS` is 60, the gate is 60s), and
     * failing fast there put "accounts: забагато запитів" on a sync whose every statement
     * pulled correctly, while leaving the balances stale — the exact staleness the
     * every-sync refresh was added to fix.
     */
    suspend fun clientInfo(waitForRateLimit: Boolean = false): MonoClientInfo
    /**
     * A window holding more than 500 items needs more than one request to Monobank's
     * statement endpoint, and Monobank permits only one statement request per 60 seconds.
     * How the second-and-later pages behave depends on [waitForRateLimit]:
     * - `false` (default): the second page raises [MonoRateLimitException] immediately and
     *   the whole call fails — it does not return the partial results already collected.
     *   Callers are expected to surface that as a sync error and retry later, not to treat
     *   it as truncation. This is the right mode for a caller a human is waiting on.
     * - `true`: each page that would be rejected by the gate instead suspends until the
     *   window reopens, then proceeds. A busy month with several thousand rows completes
     *   in a few minutes instead of failing outright. This is the right mode for a
     *   background caller nobody is blocked on.
     */
    suspend fun statement(
        accountId: String,
        from: Instant,
        to: Instant,
        waitForRateLimit: Boolean = false,
    ): List<RawItem>
    suspend fun registerWebhook(url: String)
}

@Serializable
private data class WebhookRequest(val webHookUrl: String)

@Serializable
private data class MonoError(val errorDescription: String = "")

class HttpMonoClient(
    private val http: HttpClient,
    private val tokenProvider: () -> String?,
    private val baseUrl: String = "https://api.monobank.ua",
    private val minIntervalMillis: Long = 60_000,
    private val now: () -> Long = System::currentTimeMillis,
) : MonoClient {

    private val gate = Mutex()
    private val lastCallAt = mutableMapOf<String, Long>()

    override suspend fun clientInfo(waitForRateLimit: Boolean): MonoClientInfo =
        rateLimited(ENDPOINT_CLIENT_INFO, waitForRateLimit) {
            http.get("$baseUrl/personal/client-info") { auth() }
        }.decode()

    override suspend fun statement(
        accountId: String,
        from: Instant,
        to: Instant,
        waitForRateLimit: Boolean,
    ): List<RawItem> {
        val collected = mutableListOf<RawItem>()
        val seen = mutableSetOf<String>()
        val windowStart = from.epochSecond
        var windowEnd = to.epochSecond

        while (windowStart < windowEnd) {
            val array: JsonArray = rateLimited(ENDPOINT_STATEMENT, waitForRateLimit) {
                http.get("$baseUrl/personal/statement/$accountId/$windowStart/$windowEnd") { auth() }
            }.decode()
            val page = array.map { element ->
                RawItem(API_JSON.decodeFromJsonElement<StatementItem>(element), element.toString())
            }
            val fresh = page.filter { seen.add(it.item.id) }
            collected += fresh
            // Monobank returns at most 500 items, newest first. A full page means the
            // window still hides older history, so walk the UPPER bound down to just
            // before the oldest item we just saw. Raising the LOWER bound instead would
            // re-request the newest slice forever and never reach the older history.
            if (page.size < PAGE_SIZE || fresh.isEmpty()) break
            val oldest = page.minOf { it.item.time }
            if (oldest <= windowStart) break
            windowEnd = oldest - 1
        }
        return collected
    }

    override suspend fun registerWebhook(url: String) {
        rateLimited(ENDPOINT_WEBHOOK) {
            http.post("$baseUrl/personal/webhook") {
                auth()
                contentType(ContentType.Application.Json)
                setBody(WebhookRequest(url))
            }
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth() {
        val token = tokenProvider() ?: throw MonoAuthException()
        header("X-Token", token)
    }

    /**
     * Monobank meters each endpoint separately, so the gate is keyed by endpoint.
     * A single shared gate would make a sync impossible: it needs client-info and
     * statement back to back.
     */
    private suspend fun rateLimited(
        endpoint: String,
        waitForRateLimit: Boolean = false,
        call: suspend () -> HttpResponse,
    ): HttpResponse {
        if (tokenProvider() == null) throw MonoAuthException()
        // `gate` is ONE Mutex shared by every endpoint (see the class doc below), so the
        // sleep for waitForRateLimit=true must happen OUTSIDE it: holding the lock across
        // delay() would block an unrelated endpoint's independently-metered call — e.g. a
        // manual-sync clientInfo() — for up to the whole wait, defeating the reason
        // waitForRateLimit=false exists at all. Instead: acquire, compute how long (if any)
        // remains, release; if a wait is needed, sleep it outside the lock, then loop back
        // to re-acquire and RE-CHECK — another caller may have taken the slot while we
        // slept, so this is a real check, not a formality.
        while (true) {
            val remaining = gate.withLock {
                val last = lastCallAt[endpoint] ?: 0L
                val elapsed = now() - last
                if (last != 0L && elapsed < minIntervalMillis) {
                    val left = minIntervalMillis - elapsed
                    // waitForRateLimit=false: fail fast, as today — for a caller a human is
                    // blocked on (the manual Sync button). waitForRateLimit=true: report how
                    // long remains so the caller can sleep it out and proceed — for the
                    // background hourly reconciliation, where nobody is waiting and a few
                    // minutes to finish a busy month beats returning nothing at all.
                    if (!waitForRateLimit) throw MonoRateLimitException((left + 999) / 1000)
                    left
                } else {
                    lastCallAt[endpoint] = now()
                    0L
                }
            }
            if (remaining <= 0L) break
            delay(remaining)
        }
        val response = call()
        when (response.status) {
            HttpStatusCode.OK -> return response
            HttpStatusCode.TooManyRequests -> throw MonoRateLimitException(60)
            HttpStatusCode.Forbidden, HttpStatusCode.Unauthorized ->
                throw MonoAuthException(response.errorText())
            else -> throw MonoApiException(response.status.value, response.errorText())
        }
    }

    private suspend fun HttpResponse.errorText(): String =
        runCatching { body<MonoError>().errorDescription }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: status.description

    private suspend inline fun <reified T> HttpResponse.decode(): T = body()

    private companion object {
        const val PAGE_SIZE = 500
        const val ENDPOINT_CLIENT_INFO = "client-info"
        const val ENDPOINT_STATEMENT = "statement"
        const val ENDPOINT_WEBHOOK = "webhook"
    }
}
