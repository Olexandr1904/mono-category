package app.ingest

import app.API_JSON
import app.budget.BudgetService
import app.budget.CategoryRepository
import app.budget.ConduitMccRepository
import app.budget.CounterpartyRepository
import app.budget.KYIV
import app.budget.TransactionRepository
import app.db.Crypto
import app.db.SettingKeys
import app.db.SettingsRepository
import app.mono.RawItem
import app.mono.StatementItem
import app.notify.FakeTelegramClient
import app.notify.NotificationEventRepository
import app.notify.Notifier
import app.withTestDb
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.Database
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncServiceTest {

    private val august = 1_787_011_200L
    private val clock = Clock.fixed(Instant.ofEpochSecond(august), KYIV)

    private fun item(id: String, mcc: Int = 5411, amount: Long = -84_000) = StatementItem(
        id = id, time = august, description = "ATB", mcc = mcc, originalMcc = mcc,
        hold = false, amount = amount, operationAmount = amount, currencyCode = 980,
    )

    /** Wraps [StatementItem]s the way [FakeMonoClient.statements] expects, for tests that need per-account differentiation. */
    private fun rawItems(vararg items: StatementItem) = items.map { RawItem(it, API_JSON.encodeToString(it)) }

    private fun wire(db: Database, mono: FakeMonoClient, now: () -> Long = { august }): Triple<SyncService, AccountRepository, TransactionRepository> {
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val transactions = TransactionRepository(db)
        val settings = SettingsRepository(db, Crypto(ByteArray(32) { it.toByte() }))
        val ingest = IngestService(
            transactions, categories,
            BudgetService(categories, transactions),
            Notifier(FakeTelegramClient(), settings, NotificationEventRepository(db)),
            CounterpartyRepository(db), ConduitMccRepository(db), AccountRepository(db),
            clock = clock,
        )
        val accounts = AccountRepository(db)
        return Triple(SyncService(mono, ingest, accounts, settings, clock, now), accounts, transactions)
    }

    /**
     * Non-hryvnia accounts used to be dropped here, which read as "we track UAH only" but
     * meant the application had never heard of the dollar account — and an account with no
     * row is read as tracked by every gate downstream, which is how its transfers kept
     * being counted as hryvnia and asked about in Telegram. It is stored now, switched off,
     * so ingest can recognise and refuse its transactions.
     */
    @Test
    fun `refreshAccounts stores every account, switching the non-hryvnia one off`() = withTestDb { db ->
        val mono = FakeMonoClient()
        val (sync, accounts, _) = wire(db, mono)
        assertEquals(2, runBlocking { sync.refreshAccounts() })

        assertEquals(setOf("acc-uah", "acc-usd"), accounts.ids().toSet())
        val usd = accounts.list().single { it.id == "acc-usd" }
        assertEquals(false, usd.active, "a dollar account must not arrive switched on")
        assertEquals(false, usd.tracked)
        assertEquals(listOf("acc-uah"), accounts.activeIds(), "only the hryvnia account is pulled")
        assertEquals(listOf("acc-uah"), accounts.trackableList().map { it.id }, "Settings offers only that one")
        assertEquals(setOf("acc-usd"), accounts.foreignCurrencyIds())
        assertEquals("537541******1234", accounts.list().single { it.id == "acc-uah" }.maskedPan)
    }

    @Test
    fun `a background sync lets client-info wait out its own gate`() = withTestDb { db ->
        // Client-info has a once-a-minute limit of its own, and the refresh now runs on
        // every sync rather than only on an empty table — so an hourly tick landing within
        // a minute of a manual Sync press hits it. The margin is zero by construction
        // (MIN_SYNC_INTERVAL_SECONDS is 60, the gate is 60s). Failing fast there put
        // "accounts: забагато запитів" on a sync whose every statement pulled fine and left
        // the balances stale — the staleness the every-sync refresh exists to fix.
        val mono = FakeMonoClient().apply { stubStatement(item("t1")) }
        val (sync, _, _) = wire(db, mono)

        runBlocking { sync.syncMonth("2026-08", waitForRateLimit = true) }
        assertEquals(listOf(true), mono.clientInfoWaitCalls)

        runBlocking { sync.syncMonth("2026-08", waitForRateLimit = false) }
        assertEquals(listOf(true, false), mono.clientInfoWaitCalls, "a human-facing sync still fails fast")
    }

    @Test
    fun `a non-hryvnia account cannot be switched on`() = withTestDb { db ->
        // The checkbox is never rendered for one (SettingsPage uses trackableList), so this
        // can only arrive as a hand-made POST — and honouring it would put dollar cents
        // back into a kopecks total.
        val mono = FakeMonoClient()
        val (sync, accounts, _) = wire(db, mono)
        runBlocking { sync.refreshAccounts() }

        accounts.setActive(setOf("acc-uah", "acc-usd"))

        assertEquals(false, accounts.list().single { it.id == "acc-usd" }.active)
        assertEquals(listOf("acc-uah"), accounts.activeIds())
    }

    /**
     * Finding 3 of the 2026-09 branch review: refreshAccounts (via
     * AccountRepository.replaceAll) used to delete every stored account row and reinsert
     * only the ones Monobank returned this time — so an account that leaves the token's
     * scope (closed, expired, narrowed) lost its row entirely, and spentByCategory's LEFT
     * JOIN reads a missing row as active. A brand-new account still gets added, but one
     * that stops being returned must keep its existing row untouched, not vanish.
     */
    @Test
    fun `refreshAccounts adds new accounts but never drops one Monobank stops returning`() = withTestDb { db ->
        val mono = FakeMonoClient()
        val (sync, accounts, _) = wire(db, mono)
        runBlocking { sync.refreshAccounts() }
        mono.accounts = listOf(app.mono.MonoAccount(id = "acc-new", currencyCode = 980))
        runBlocking { sync.refreshAccounts() }
        assertEquals(setOf("acc-uah", "acc-usd", "acc-new"), accounts.ids().toSet())
    }

    @Test
    fun `syncMonth pulls the month window for every stored account`() = withTestDb { db ->
        val mono = FakeMonoClient().apply { stubStatement(item("t1"), item("t2")) }
        val (sync, _, transactions) = wire(db, mono)
        runBlocking { sync.refreshAccounts() }

        val report = runBlocking { sync.syncMonth("2026-08") }
        assertEquals(2, report.inserted)
        assertEquals(1, report.accounts)
        assertEquals("2026-08", report.month, "the caller must be able to say which period this covers")
        assertEquals(august, report.syncedAt, "the caller must be able to say when this ran")
        assertEquals(2, transactions.count("2026-08", null, false))

        val (accountId, from, to) = mono.statementCalls.single()
        assertEquals("acc-uah", accountId)
        assertEquals(Instant.parse("2026-07-31T21:00:00Z").epochSecond, from)
        assertEquals(Instant.parse("2026-08-31T21:00:00Z").epochSecond, to)
    }

    @Test
    fun `syncing twice imports nothing new`() = withTestDb { db ->
        val mono = FakeMonoClient().apply { stubStatement(item("t1")) }
        val (sync, _, transactions) = wire(db, mono)
        runBlocking {
            sync.refreshAccounts()
            sync.syncMonth("2026-08")
            val second = sync.syncMonth("2026-08")
            assertEquals(0, second.inserted)
            assertEquals(1, second.unchanged)
        }
        assertEquals(1, transactions.count("2026-08", null, false))
    }

    @Test
    fun `refreshAccounts runs automatically when nothing is stored yet`() = withTestDb { db ->
        val mono = FakeMonoClient().apply { stubStatement(item("t1")) }
        val (sync, accounts, _) = wire(db, mono)
        runBlocking { sync.syncMonth("2026-08") }
        assertEquals(setOf("acc-uah", "acc-usd"), accounts.ids().toSet())
    }

    /**
     * The refresh used to be guarded by `if (accounts.ids().isEmpty())`, so an account
     * opened after the first sync never got a row — and a row-less account is read as
     * tracked hryvnia everywhere, which is exactly how the owner's dollar account kept
     * being counted and asked about. Balances were frozen at first-run values for the
     * same reason.
     */
    @Test
    fun `every sync refreshes the account list, not only the first`() = withTestDb { db ->
        val mono = FakeMonoClient().apply { stubStatement(item("t1")) }
        val (sync, accounts, _) = wire(db, mono)
        runBlocking { sync.syncMonth("2026-08") }

        mono.accounts = mono.accounts + app.mono.MonoAccount(id = "acc-later", currencyCode = 840)
        runBlocking { sync.syncMonth("2026-08") }

        assertEquals(setOf("acc-uah", "acc-usd", "acc-later"), accounts.ids().toSet())
    }

    @Test
    fun `a failed account refresh does not abort a sync that already knows its accounts`() = withTestDb { db ->
        // Client-info has its own once-a-minute limit. Aborting here would trade a stale
        // balance for a whole missed month; the first sync, with nothing stored to fall
        // back on, still aborts — `a failing account refresh is reported and still records
        // the cooldown` below pins that.
        val mono = FakeMonoClient().apply { stubStatement(item("t1")) }
        val (sync, _, transactions) = wire(db, mono)
        runBlocking { sync.refreshAccounts() }

        mono.failNextClientInfo(FakeMonoClient.rateLimited())
        val report = runBlocking { sync.syncMonth("2026-08") }

        assertEquals(1, report.inserted, "the statement must still have been pulled")
        assertEquals(1, transactions.count("2026-08", null, false))
        assertTrue(report.errors.any { it.startsWith("accounts:") }, "the failure is still reported: ${report.errors}")
    }

    @Test
    fun `a failing account is reported without aborting the others`() = withTestDb { db ->
        val mono = FakeMonoClient(
            accounts = listOf(
                app.mono.MonoAccount(id = "acc-uah-1", currencyCode = 980),
                app.mono.MonoAccount(id = "acc-uah-2", currencyCode = 980),
            ),
            statements = mutableMapOf(
                "acc-uah-1" to rawItems(item("t1")),
                "acc-uah-2" to rawItems(item("t2")),
            ),
        )
        val (sync, _, transactions) = wire(db, mono)
        runBlocking { sync.refreshAccounts() }
        mono.failStatementFor("acc-uah-1", FakeMonoClient.rateLimited())

        val report = runBlocking { sync.syncMonth("2026-08") }

        assertEquals(1, report.errors.size)
        assertTrue(report.errors.single().contains("acc-uah-1"))
        // The reason must be translated through Copy, not the internal English fallback —
        // every user-facing string in this app goes through app.i18n.Copy.
        assertTrue(report.errors.single().contains(app.i18n.UkCopy.syncErrorRateLimited(42)), report.errors.single())
        assertEquals(2, report.accounts)
        assertEquals(1, report.inserted)
        assertEquals(1, transactions.count("2026-08", null, false))
    }

    @Test
    fun `a failing account refresh is reported and still records the cooldown`() = withTestDb { db ->
        val mono = FakeMonoClient()
        mono.failNextClientInfo(FakeMonoClient.rateLimited())
        val (sync, accounts, _) = wire(db, mono)

        val report = runBlocking { sync.syncMonth("2026-08") }

        assertEquals(1, report.errors.size)
        assertTrue(report.errors.single().contains("accounts"))
        assertEquals(0, accounts.ids().size)
        assertEquals(60, sync.secondsUntilSyncAllowed())
    }

    @Test
    fun `the sync cooldown is recorded and reported`() = withTestDb { db ->
        val mono = FakeMonoClient()
        var clockNow = august
        val (sync, _, _) = wire(db, mono) { clockNow }
        assertEquals(0, sync.secondsUntilSyncAllowed())

        runBlocking { sync.syncCurrentMonth() }
        assertEquals(60, sync.secondsUntilSyncAllowed())

        clockNow = august + 45
        assertEquals(15, sync.secondsUntilSyncAllowed())

        clockNow = august + 61
        assertEquals(0, sync.secondsUntilSyncAllowed())
    }

    @Test
    fun `syncCurrentMonth uses the Kyiv month of the clock`() = withTestDb { db ->
        val mono = FakeMonoClient().apply { stubStatement(item("t1")) }
        val (sync, _, transactions) = wire(db, mono)
        runBlocking {
            sync.refreshAccounts()
            sync.syncCurrentMonth()
        }
        assertEquals(1, transactions.count("2026-08", null, false))
    }

    @Test
    fun `syncMonth passes waitForRateLimit through instead of hardcoding it`() = withTestDb { db ->
        val mono = FakeMonoClient().apply { stubStatement(item("t1")) }
        val (sync, _, _) = wire(db, mono)
        runBlocking {
            sync.refreshAccounts()
            sync.syncMonth("2026-08", waitForRateLimit = true)
            sync.syncMonth("2026-08", waitForRateLimit = false)
        }
        assertEquals(listOf(true, false), mono.waitForRateLimitCalls)
    }

    @Test
    fun `syncCurrentMonth defaults to not waiting, the manual-sync caller's mode`() = withTestDb { db ->
        val mono = FakeMonoClient().apply { stubStatement(item("t1")) }
        val (sync, _, _) = wire(db, mono)
        runBlocking {
            sync.refreshAccounts()
            sync.syncCurrentMonth()
        }
        assertEquals(listOf(false), mono.waitForRateLimitCalls)
    }

    @Test
    fun `syncMonth skips an account the owner switched off`() = withTestDb { db ->
        val mono = FakeMonoClient(
            accounts = listOf(
                app.mono.MonoAccount(id = "acc-uah-1", currencyCode = 980),
                app.mono.MonoAccount(id = "acc-uah-2", currencyCode = 980),
            ),
            statements = mutableMapOf(
                "acc-uah-1" to rawItems(item("t1")),
                "acc-uah-2" to rawItems(item("t2")),
            ),
        )
        val (sync, accounts, transactions) = wire(db, mono)
        runBlocking { sync.refreshAccounts() }
        accounts.setActive(setOf("acc-uah-2")) // acc-uah-1 switched off

        val report = runBlocking { sync.syncMonth("2026-08") }

        assertEquals(1, report.accounts, "only the active account counts toward the report")
        assertEquals(listOf("acc-uah-2"), mono.statementCalls.map { it.first })
        assertEquals(1, transactions.count("2026-08", null, false))
    }

    @Test
    fun `the last sync timestamp is stored in settings`() = withTestDb { db ->
        val settings = SettingsRepository(db, Crypto(ByteArray(32) { it.toByte() }))
        val (sync, _, _) = wire(db, FakeMonoClient())
        runBlocking { sync.syncCurrentMonth() }
        assertEquals(august.toString(), settings.get(SettingKeys.LAST_SYNC_AT))
    }

    // 2026-09-03 UX review §10: LAST_SYNC_RESULT used to store copy.lastSyncFailed's
    // rendered sentence, frozen at whatever language and wording were current when the
    // sync ran. structuredSyncFailure captures just the raw shape of the failure instead,
    // so StoredSyncResult.render(copy) can apply the *current* Copy at read time.

    @Test
    fun `a rate-limited failure survives a JSON round trip and renders through the current Copy`() {
        val stored = structuredSyncFailure(app.mono.MonoRateLimitException(42))
        assertEquals(
            StoredSyncResult(ok = false, failureKind = "rate_limited", failureRetryAfterSeconds = 42),
            stored,
        )
        val roundTripped = API_JSON.decodeFromString<StoredSyncResult>(API_JSON.encodeToString(stored))
        assertEquals(app.i18n.UkCopy.lastSyncFailed(app.i18n.UkCopy.syncErrorRateLimited(42)), roundTripped.render(app.i18n.UkCopy))
        assertEquals(app.i18n.EnCopy.lastSyncFailed(app.i18n.EnCopy.syncErrorRateLimited(42)), roundTripped.render(app.i18n.EnCopy))
    }

    @Test
    fun `a rejected token and an HTTP status failure render distinctly, not as the same generic error`() {
        val rejected = structuredSyncFailure(app.mono.MonoAuthException())
        val httpStatus = structuredSyncFailure(app.mono.MonoApiException(503, "unavailable"))
        assertEquals(app.i18n.UkCopy.lastSyncFailed(app.i18n.UkCopy.syncErrorTokenRejected), rejected.render(app.i18n.UkCopy))
        assertEquals(app.i18n.UkCopy.lastSyncFailed(app.i18n.UkCopy.syncErrorHttpStatus(503)), httpStatus.render(app.i18n.UkCopy))
        assertTrue(rejected.render(app.i18n.UkCopy) != httpStatus.render(app.i18n.UkCopy))
    }

    @Test
    fun `a successful outcome renders the same sentence describeSyncFailure's sibling copy would`() {
        val stored = StoredSyncResult(
            ok = true, month = "2026-08", accounts = 5, inserted = 3, updated = 1, unchanged = 2,
            errors = emptyList(), syncedAt = august,
        )
        val expected = app.i18n.UkCopy.lastSyncSummary(
            app.i18n.UkCopy.syncSummary(
                inserted = 3, updated = 1, unchanged = 2, accounts = 5, errors = emptyList(),
            ),
        )
        assertEquals(expected, stored.render(app.i18n.UkCopy))
    }

    @Test
    fun `syncMonth records the result the Settings card reads, not only the cooldown`() = withTestDb { db ->
        // 2026-09-22 production walkthrough, finding 2: LAST_SYNC_RESULT was written only by
        // the /settings/sync handler, so the hourly background loop stamped the cooldown and
        // left the card alone. "Востаннє:" silently meant "last *manual* sync" and sat for
        // three weeks printing a pre-2026-09-03 English string on a Ukrainian page.
        val mono = FakeMonoClient()
        mono.stubStatement(item("t1"))
        val (sync, _, _) = wire(db, mono)
        val settings = SettingsRepository(db, Crypto(ByteArray(32) { it.toByte() }))

        runBlocking { sync.syncCurrentMonth() }

        val raw = settings.get(SettingKeys.LAST_SYNC_RESULT)
        assertTrue(raw != null, "the hourly sync must record its own result, not just the cooldown")
        val stored = API_JSON.decodeFromString<StoredSyncResult>(raw)
        assertTrue(stored.ok, raw)
        assertEquals("2026-08", stored.month)
        assertEquals(1, stored.inserted)
        // Only the hryvnia account is pulled; the dollar one is stored inactive.
        assertEquals(1, stored.accounts)
        assertTrue(settings.get(SettingKeys.LAST_SYNC_AT) != null, "the cooldown stamp must survive")
    }

    @Test
    fun `a sync that cannot even list accounts still records what happened`() = withTestDb { db ->
        // The early-return path: client-info failed and there is nothing stored to fall back
        // on. It used to stamp LAST_SYNC_AT and return without recording anything, so the
        // card kept showing the previous run as though this one had never been attempted.
        val mono = FakeMonoClient()
        mono.failNextClientInfo(RuntimeException("boom"))
        val (sync, _, _) = wire(db, mono)
        val settings = SettingsRepository(db, Crypto(ByteArray(32) { it.toByte() }))

        runBlocking { sync.syncCurrentMonth() }

        val raw = settings.get(SettingKeys.LAST_SYNC_RESULT)
        assertTrue(raw != null, "even a failed first sync must leave a trace on the card")
        val stored = API_JSON.decodeFromString<StoredSyncResult>(raw)
        assertEquals(0, stored.accounts)
        // Recorded as a failure, not as a success carrying its own contradiction. This used
        // to route through `record`, which hardcodes ok = true, so a first sync with a
        // rejected token printed "0 нових · 0 оновлених · 0 без змін · 0 рахунків ·
        // Помилки (1): токен відхилено" through lastSyncSummary. The reason now lives in
        // the structured failure fields rather than the errors list, which is why the card
        // can render lastSyncFailed with it.
        assertEquals(false, stored.ok, "the card must not call this a successful sync")
        assertEquals("generic", stored.failureKind)
        assertEquals("RuntimeException", stored.failureExceptionName)
        assertTrue(
            stored.render(app.i18n.UkCopy) == app.i18n.UkCopy.lastSyncFailed(app.i18n.UkCopy.syncErrorGeneric("RuntimeException")),
            stored.render(app.i18n.UkCopy),
        )
    }

    @Test
    fun `a rejected token on the first sync reads as a failure, not as a summary of nothing`() = withTestDb { db ->
        // The shape the owner would actually hit: paste a bad token, first sync, nothing
        // stored to fall back on.
        val mono = FakeMonoClient()
        mono.failNextClientInfo(FakeMonoClient.tokenRejected())
        val (sync, _, _) = wire(db, mono)
        val settings = SettingsRepository(db, Crypto(ByteArray(32) { it.toByte() }))

        runBlocking { sync.syncCurrentMonth() }

        val stored = API_JSON.decodeFromString<StoredSyncResult>(settings.get(SettingKeys.LAST_SYNC_RESULT)!!)
        assertEquals(false, stored.ok)
        assertEquals("token_rejected", stored.failureKind)
        assertEquals(app.i18n.UkCopy.lastSyncFailed(app.i18n.UkCopy.syncErrorTokenRejected), stored.render(app.i18n.UkCopy))
    }
}
