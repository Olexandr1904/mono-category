package app.ingest

import app.API_JSON
import app.mono.MonoAccount
import app.mono.MonoClient
import app.mono.MonoClientInfo
import app.mono.MonoRateLimitException
import app.mono.RawItem
import app.mono.StatementItem
import kotlinx.serialization.encodeToString
import java.time.Instant

class FakeMonoClient(
    var accounts: List<MonoAccount> = listOf(
        MonoAccount(id = "acc-uah", currencyCode = 980, maskedPan = listOf("537541******1234"), type = "black"),
        MonoAccount(id = "acc-usd", currencyCode = 840, maskedPan = listOf("537541******9999"), type = "black"),
    ),
    var statements: MutableMap<String, List<RawItem>> = mutableMapOf(),
) : MonoClient {
    val statementCalls = mutableListOf<Triple<String, Long, Long>>()
    /** One entry per [statement] call, recording the mode it was actually invoked with. */
    val waitForRateLimitCalls = mutableListOf<Boolean>()
    var registeredWebhook: String? = null
    var failStatementWith: Exception? = null
    var failClientInfoWith: Exception? = null
    private val failStatementForAccount = mutableMapOf<String, Exception>()
    /** Fallback returned for any account not given its own entry in [statements]. */
    private var statementResult: List<RawItem> = emptyList()

    /** One entry per [clientInfo] call, recording the mode it was actually invoked with. */
    val clientInfoWaitCalls = mutableListOf<Boolean>()

    override suspend fun clientInfo(waitForRateLimit: Boolean): MonoClientInfo {
        clientInfoWaitCalls += waitForRateLimit
        // Cleared as it fires, so the name tells the truth: `failNext…`, one call, then
        // back to normal. It used to be sticky, which meant a test reading as "the refresh
        // fails once and the sync recovers" only ever proved the first failure was
        // survived — extend it with a second syncMonth and the refresh is silently still
        // broken while the test stays green.
        failClientInfoWith?.let { failClientInfoWith = null; throw it }
        return MonoClientInfo(clientId = "c1", name = "Test", accounts = accounts)
    }

    override suspend fun statement(
        accountId: String,
        from: Instant,
        to: Instant,
        waitForRateLimit: Boolean,
    ): List<RawItem> {
        statementCalls += Triple(accountId, from.epochSecond, to.epochSecond)
        waitForRateLimitCalls += waitForRateLimit
        failStatementForAccount[accountId]?.let { throw it }
        failStatementWith?.let { throw it }
        return statements[accountId] ?: statementResult
    }

    override suspend fun registerWebhook(url: String) { registeredWebhook = url }

    fun failNextStatements(e: Exception?) { failStatementWith = e }

    fun failNextClientInfo(e: Exception?) { failClientInfoWith = e }

    fun failStatementFor(accountId: String, e: Exception) { failStatementForAccount[accountId] = e }

    fun stubStatement(vararg items: StatementItem) {
        statementResult = items.map { RawItem(it, API_JSON.encodeToString(it)) }
    }

    companion object {
        fun tokenRejected() = app.mono.MonoAuthException()

        fun rateLimited() = MonoRateLimitException(42)
    }
}
