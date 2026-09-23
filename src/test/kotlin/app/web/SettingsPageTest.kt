package app.web

import app.budget.BudgetService
import app.budget.CategoryRepository
import app.budget.ConduitMccRepository
import app.budget.CounterpartyRepository
import app.budget.KYIV
import app.budget.TransactionRepository
import app.budget.formatMinor
import app.db.Crypto
import app.db.SettingKeys
import app.db.SettingsRepository
import app.ingest.AccountRepository
import app.ingest.FakeMonoClient
import app.ingest.IngestService
import app.ingest.StoredSyncResult
import app.ingest.render
import app.ingest.SyncService
import app.i18n.EnCopy
import app.i18n.UkCopy
import app.mono.MonoAccount
import app.mono.MonoClient
import app.mono.MonoClientInfo
import app.mono.RawItem
import app.notify.BotCommand
import app.notify.Button
import app.notify.FakeTelegramClient
import app.notify.NotificationEventRepository
import app.notify.Notifier
import app.notify.TelegramClient
import app.withTestDb
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsPageTest {

    private val august = 1_787_011_200L
    private val clock = Clock.fixed(Instant.ofEpochSecond(august), KYIV)
    private lateinit var mono: FakeMonoClient
    private lateinit var telegram: FakeTelegramClient

    private fun settings(db: Database) = SettingsRepository(db, Crypto(ByteArray(32) { it.toByte() }))

    /**
     * redirectWith URL-encodes the flash/error text into the query string, so a raw
     * substring check against the encoded Location header only works for plain ASCII.
     * This encodes the expected Ukrainian substring the same way so the check still
     * proves the right message was chosen, not just that *some* text landed there.
     */
    private fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8)

    /**
     * [syncJobs] is how the detached-sync tests get determinism without sleeping: the
     * launcher below runs each detached block on the test application's own coroutine
     * scope and records the resulting [Job], so a test can `job.join()` and know the
     * background work has actually finished — no polling, no fixed delay.
     */
    private fun ApplicationTestBuilder.setup(
        db: Database,
        baseUrl: String? = "https://budget.fly.dev",
        syncJobs: MutableList<Job> = mutableListOf(),
    ) {
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val transactions = TransactionRepository(db)
        val settingsRepo = settings(db)
        telegram = FakeTelegramClient()
        mono = FakeMonoClient()
        val ingest = IngestService(
            transactions, categories,
            BudgetService(categories, transactions),
            Notifier(telegram, settingsRepo, NotificationEventRepository(db)),
            CounterpartyRepository(db), ConduitMccRepository(db), AccountRepository(db),
            clock = clock,
        )
        val accounts = AccountRepository(db)
        val sync = SyncService(mono, ingest, accounts, settingsRepo, clock) { august }
        application {
            routing {
                settingsRoutes(settingsRepo, sync, mono, telegram, accounts, ingest, { block -> syncJobs += launch { block() } }) { baseUrl }
            }
        }
    }

    // --- tokens --------------------------------------------------------------------------

    @Test
    fun `tokens are stored encrypted and never rendered back`() = withTestDb { db ->
        testApplication {
            // Saving the Monobank token here is a first-time save, so it launches a
            // detached initial sync (see setup()'s launcher). Capture and join it so it
            // can't outlive this test's temp database and leak an uncaught exception into
            // the next test.
            val jobs = mutableListOf<Job>()
            setup(db, syncJobs = jobs)
            val client = createClient { followRedirects = false }
            val response = client.submitForm(
                "/settings/tokens",
                parameters { append("monoToken", "uMonoSecret"); append("telegramToken", "123:ABCdef") },
            )
            assertEquals(HttpStatusCode.Found, response.status)
            jobs.forEach { it.join() }

            val repo = settings(db)
            assertEquals("uMonoSecret", repo.get(SettingKeys.MONO_TOKEN))
            assertEquals("123:ABCdef", repo.get(SettingKeys.TELEGRAM_TOKEN))

            transaction(db) {
                var stored = ""
                exec("SELECT value FROM settings WHERE key='${SettingKeys.MONO_TOKEN}'") { rs ->
                    if (rs.next()) stored = rs.getString(1)
                }
                assertNotEquals("uMonoSecret", stored, "the token must not be stored in the clear")
            }

            val body = client.get("/settings").bodyAsText()
            assertTrue(!body.contains("uMonoSecret"), "the raw token must never reach the page")
            assertTrue(!body.contains("123:ABCdef"), "the raw token must never reach the page")
            assertTrue(body.contains(UkCopy.configured), body)
        }
    }

    @Test
    fun `a blank token field leaves the stored value alone`() = withTestDb { db ->
        testApplication {
            // The first submitForm below is a first-time token save and launches a
            // detached initial sync; join it before the test (and its temp database) tears
            // down, same reasoning as the test above.
            val jobs = mutableListOf<Job>()
            setup(db, syncJobs = jobs)
            val client = createClient { followRedirects = false }
            client.submitForm("/settings/tokens", parameters { append("monoToken", "uMonoSecret") })
            jobs.forEach { it.join() }
            client.submitForm("/settings/tokens", parameters { append("monoToken", ""); append("telegramToken", "123:ABC") })
            assertEquals("uMonoSecret", settings(db).get(SettingKeys.MONO_TOKEN))
        }
    }

    @Test
    fun `saving the Monobank token for the first time runs an initial sync`() = withTestDb { db ->
        testApplication {
            val jobs = mutableListOf<Job>()
            setup(db, syncJobs = jobs)
            val client = createClient { followRedirects = false }
            val response = client.submitForm("/settings/tokens", parameters { append("monoToken", "uMonoSecret") })
            // Distinguishes the initial-sync flash from the plain "tokens saved" one.
            assertTrue(response.headers["Location"]!!.contains(enc("Перша синхронізація")), response.headers["Location"]!!)
            jobs.forEach { it.join() }
            // FakeMonoClient's default fixture includes a USD account too. Both are stored
            // — ingest has to recognise the dollar account to refuse its transactions — but
            // only the hryvnia one is tracked, so only it gets a statement pulled.
            val accounts = AccountRepository(db)
            assertEquals(setOf("acc-uah", "acc-usd"), accounts.ids().toSet())
            assertEquals(listOf("acc-uah"), accounts.activeIds())
            assertEquals(1, mono.statementCalls.size)
            assertEquals(listOf(true), mono.waitForRateLimitCalls, "the initial sync must wait out the gate, not fail fast")
        }
    }

    @Test
    fun `saving tokens again does not re-run the initial sync`() = withTestDb { db ->
        testApplication {
            val jobs = mutableListOf<Job>()
            setup(db, syncJobs = jobs)
            val client = createClient { followRedirects = false }
            client.submitForm("/settings/tokens", parameters { append("monoToken", "first") })
            jobs.forEach { it.join() }
            val callsAfterFirst = mono.statementCalls.size
            client.submitForm("/settings/tokens", parameters { append("monoToken", "second") })
            jobs.forEach { it.join() }
            assertEquals(callsAfterFirst, mono.statementCalls.size)
        }
    }

    /**
     * design-handoff.md §4.5 + the 2026-09-03 fix this screen must not undo: the caption
     * used to be the input's *sibling*, not its parent, so at 1440px the two labels and two
     * boxes wrapped into an order where the Monobank box sat between both captions and the
     * Telegram box had no caption beside it at all — a token pasted into the wrong box saved
     * silently, since both fields are type=password. Asserted here as a structural fact
     * about the markup (the input is nested *inside* its own label), not just that both
     * strings appear somewhere on the page.
     */
    @Test
    fun `each token label wraps only its own input, not the other one`() = withTestDb { db ->
        testApplication {
            setup(db)
            val body = client.get("/settings").bodyAsText()

            val firstStart = body.indexOf("class=\"token-field\"")
            assertTrue(firstStart >= 0, body)
            val firstEnd = body.indexOf("</label>", firstStart)
            val firstLabel = body.substring(firstStart, firstEnd)
            assertTrue(firstLabel.contains(UkCopy.monobankTokenLabel), firstLabel)
            assertTrue(firstLabel.contains("name=\"monoToken\""), firstLabel)
            assertTrue(!firstLabel.contains("name=\"telegramToken\""), firstLabel)
            assertTrue(!firstLabel.contains(UkCopy.telegramTokenLabel), firstLabel)

            val secondStart = body.indexOf("class=\"token-field\"", firstEnd)
            assertTrue(secondStart >= 0, body)
            val secondEnd = body.indexOf("</label>", secondStart)
            val secondLabel = body.substring(secondStart, secondEnd)
            assertTrue(secondLabel.contains(UkCopy.telegramTokenLabel), secondLabel)
            assertTrue(secondLabel.contains("name=\"telegramToken\""), secondLabel)
            assertTrue(!secondLabel.contains("name=\"monoToken\""), secondLabel)
        }
    }

    @Test
    fun `a token field only shows the configured pill once a value is stored`() = withTestDb { db ->
        testApplication {
            val jobs = mutableListOf<Job>()
            setup(db, syncJobs = jobs)
            val client = createClient { followRedirects = false }

            val before = client.get("/settings").bodyAsText()
            assertTrue(!before.contains(UkCopy.configured), before)

            client.submitForm("/settings/tokens", parameters { append("telegramToken", "123:ABC") })
            jobs.forEach { it.join() }
            val after = client.get("/settings").bodyAsText()
            assertTrue(after.contains(UkCopy.configured), after)
        }
    }

    // --- status card -----------------------------------------------------------------------

    @Test
    fun `the status card lists exactly what is still missing when nothing is configured`() = withTestDb { db ->
        testApplication {
            setup(db)
            val body = client.get("/settings").bodyAsText()
            assertTrue(!body.contains(UkCopy.statusAllConnected), body)
            assertTrue(body.contains("status-dot muted"), body)
            assertTrue(
                body.contains(
                    UkCopy.statusIncomplete(
                        listOf(
                            UkCopy.statusPillMonoToken,
                            UkCopy.statusPillTelegramToken,
                            UkCopy.statusPillTelegramChat,
                            UkCopy.statusPillTelegramWebhook,
                            UkCopy.statusPillMonoWebhook,
                        ),
                    ),
                ),
                body,
            )
        }
    }

    @Test
    fun `the status card says everything is connected once every piece is wired up`() = withTestDb { db ->
        val repo = settings(db)
        repo.set(SettingKeys.MONO_TOKEN, "u...", encrypted = true)
        repo.set(SettingKeys.TELEGRAM_TOKEN, "123:ABC", encrypted = true)
        repo.set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        repo.set(SettingKeys.TELEGRAM_WEBHOOK_REGISTERED_AT, august.toString())
        repo.set(SettingKeys.MONO_WEBHOOK_REGISTERED_AT, august.toString())
        testApplication {
            setup(db)
            val body = client.get("/settings").bodyAsText()
            assertTrue(body.contains(UkCopy.statusAllConnected), body)
            assertTrue(!body.contains("status-dot muted"), body)
        }
    }

    @Test
    fun `the status card reflects real progress, not just what was pressed`() = withTestDb { db ->
        testApplication {
            val jobs = mutableListOf<Job>()
            setup(db, syncJobs = jobs)
            val client = createClient { followRedirects = false }

            fun missing(vararg labels: String) = UkCopy.statusIncomplete(labels.toList())

            // Nothing done yet: every piece missing.
            var body = client.get("/settings").bodyAsText()
            assertTrue(
                body.contains(
                    missing(
                        UkCopy.statusPillMonoToken, UkCopy.statusPillTelegramToken,
                        UkCopy.statusPillTelegramChat, UkCopy.statusPillTelegramWebhook,
                        UkCopy.statusPillMonoWebhook,
                    ),
                ),
                body,
            )

            // Saving the Monobank token (first time) flips only its own pill.
            client.submitForm("/settings/tokens", parameters { append("monoToken", "u...") })
            jobs.forEach { it.join() }
            body = client.get("/settings").bodyAsText()
            assertTrue(
                body.contains(
                    missing(
                        UkCopy.statusPillTelegramToken, UkCopy.statusPillTelegramChat,
                        UkCopy.statusPillTelegramWebhook, UkCopy.statusPillMonoWebhook,
                    ),
                ),
                body,
            )

            // Reconnecting flips the Telegram-webhook pill, independent of Monobank's.
            client.submitForm("/settings/pairing-code", parameters { })
            body = client.get("/settings").bodyAsText()
            assertTrue(
                body.contains(missing(UkCopy.statusPillTelegramToken, UkCopy.statusPillTelegramChat, UkCopy.statusPillMonoWebhook)),
                body,
            )

            client.submitForm("/settings/webhook", parameters { })
            body = client.get("/settings").bodyAsText()
            assertTrue(body.contains(missing(UkCopy.statusPillTelegramToken, UkCopy.statusPillTelegramChat)), body)
        }
    }

    // --- Telegram: reconnect (pairing code + webhook registration merged) ------------------

    /**
     * design-handoff.md §4.4 lists exactly two buttons on the Telegram card: a test
     * notification and "Перепід'єднати". This app used to have a third, separate
     * "register Telegram webhook" button — folding it into "Перепід'єднати" removes the
     * button without removing the capability, and closes the exact gap the 2026-09-03
     * review flagged: a pairing code that Telegram can never deliver to us because the
     * webhook was never registered.
     */
    @Test
    fun `generating a pairing code also registers the command menu`() = withTestDb { db ->
        testApplication {
            setup(db)
            val client = createClient { followRedirects = false }
            client.submitForm("/settings/pairing-code", parameters { })

            assertEquals(
                listOf("/status", "/left", "/limit", "/help"),
                telegram.commands.last().map { it.command },
            )
        }
    }

    /**
     * Otherwise the input bar keeps the language it was first registered in while every
     * reply switches — the bot disagreeing with itself in the same chat.
     */
    @Test
    fun `changing the language re-registers the menu in that language`() = withTestDb { db ->
        testApplication {
            setup(db)
            val client = createClient { followRedirects = false }
            client.submitForm("/settings/language", parameters { append("language", "en") })

            assertEquals(
                EnCopy.botCommands.map { it.description },
                telegram.commands.last().map { it.description },
            )
        }
    }

    @Test
    fun `reconnecting registers the Telegram webhook and shows the start command`() = withTestDb { db ->
        testApplication {
            setup(db)
            val client = createClient { followRedirects = false }
            client.submitForm("/settings/pairing-code", parameters { })
            val repo = settings(db)
            val code = repo.get(SettingKeys.PAIRING_CODE)
            assertNotNull(code)
            assertEquals(6, code.length)
            assertTrue(repo.isSet(SettingKeys.TELEGRAM_WEBHOOK_REGISTERED_AT))
            assertTrue(client.get("/settings").bodyAsText().contains("/start $code"))
        }
    }

    /**
     * The card used to show the code only while no chat was paired, which made the
     * "Reconnect" button on a paired install hand out a code the owner could never read —
     * and moving the bot to a group shared with the family impossible from the UI.
     */
    @Test
    fun `an already paired install still shows the code needed to move the bot`() = withTestDb { db ->
        testApplication {
            setup(db)
            settings(db).set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
            val client = createClient { followRedirects = false }
            client.submitForm("/settings/pairing-code", parameters { })
            val code = settings(db).get(SettingKeys.PAIRING_CODE)
            assertNotNull(code)

            val body = client.get("/settings").bodyAsText()
            assertTrue(body.contains("/start $code"), body)
            assertTrue(body.contains(UkCopy.sendStartToMoveChat), body)
        }
    }

    @Test
    fun `reconnecting without a resolvable public url reports an error and hands out no code`() = withTestDb { db ->
        testApplication {
            setup(db, baseUrl = null)
            val client = createClient { followRedirects = false }
            val response = client.submitForm("/settings/pairing-code", parameters { })
            assertTrue(response.headers["Location"]!!.contains("error="), response.headers["Location"]!!)
            assertNull(settings(db).get(SettingKeys.PAIRING_CODE))
        }
    }

    @Test
    fun `reconnecting never puts the bot token in the redirect when Telegram rejects the webhook`() = withTestDb { db ->
        val settingsRepo = settings(db)
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val transactions = TransactionRepository(db)
        val ingest = IngestService(
            transactions, categories,
            BudgetService(categories, transactions),
            Notifier(FakeTelegramClient(), settingsRepo, NotificationEventRepository(db)),
            CounterpartyRepository(db), ConduitMccRepository(db), AccountRepository(db),
            clock = clock,
        )
        val accounts = AccountRepository(db)
        val mono = FakeMonoClient()
        val syncService = SyncService(mono, ingest, accounts, settingsRepo, clock) { august }

        // A stand-in for Ktor's real behaviour: HttpTimeout exceptions embed the full
        // request URL — bot token included — in their message. This is the shape that
        // must never reach a redirect Location header.
        val tokenShapedMessage =
            "HttpRequestTimeoutException: Request timeout has expired [url=https://api.telegram.org/bot123456789:AAHrealtokenlooking-abcDEF1234/setWebhook]"
        val leakyTelegram = object : TelegramClient {
            override suspend fun sendMessage(
                chatId: String,
                text: String,
                keyboard: List<List<Button>>?,
                forceReply: Boolean,
                selective: Boolean,
            ): Long = throw RuntimeException(tokenShapedMessage)
            override suspend fun editMessageText(chatId: String, messageId: Long, text: String) = Unit
            override suspend fun answerCallbackQuery(callbackQueryId: String, text: String?) = Unit
            override suspend fun setWebhook(url: String, secretToken: String): Unit = throw RuntimeException(tokenShapedMessage)
            override suspend fun setMyCommands(commands: List<BotCommand>): Unit =
                throw RuntimeException(tokenShapedMessage)
            override suspend fun getMe(): String = "test_bot"
        }

        testApplication {
            application {
                routing {
                    settingsRoutes(settingsRepo, syncService, mono, leakyTelegram, accounts, ingest, { block -> launch { block() } }) {
                        "https://budget.fly.dev"
                    }
                }
            }
            val client = createClient { followRedirects = false }
            val response = client.submitForm("/settings/pairing-code", parameters { })
            val location = response.headers["Location"].orEmpty()
            assertTrue(!location.contains("123456789:AAHrealtokenlooking"), location)
            assertTrue(!location.contains("bot123456789"), location)
            assertTrue(location.contains("error="), location)
            assertNull(settingsRepo.get(SettingKeys.PAIRING_CODE), "no code should be handed out for a webhook that can't be registered")
        }
    }

    // --- Monobank webhook --------------------------------------------------------------------

    @Test
    fun `registering the Monobank webhook stores a secret and calls the API`() = withTestDb { db ->
        testApplication {
            setup(db)
            val client = createClient { followRedirects = false }
            client.submitForm("/settings/webhook", parameters { })
            val secret = settings(db).get(SettingKeys.MONO_WEBHOOK_SECRET)
            assertNotNull(secret)
            assertEquals("https://budget.fly.dev/webhook/$secret", mono.registeredWebhook)
            assertTrue(settings(db).isSet(SettingKeys.MONO_WEBHOOK_REGISTERED_AT))
            assertTrue(client.get("/settings").bodyAsText().contains(UkCopy.webhookActiveLabel))
        }
    }

    @Test
    fun `a failed Monobank webhook registration must not claim it is registered`() = withTestDb { db ->
        mono = FakeMonoClient()
        testApplication {
            application {
                routing {
                    val failingMono = object : MonoClient {
                        override suspend fun clientInfo(waitForRateLimit: Boolean) = mono.clientInfo(waitForRateLimit)
                        override suspend fun statement(accountId: String, from: Instant, to: Instant, waitForRateLimit: Boolean) = emptyList<RawItem>()
                        override suspend fun registerWebhook(url: String): Unit = throw RuntimeException("boom")
                    }
                    val categories = CategoryRepository(db, ConduitMccRepository(db))
                    val transactions = TransactionRepository(db)
                    val settingsRepo = settings(db)
                    telegram = FakeTelegramClient()
                    val ingest = IngestService(
                        transactions, categories,
                        BudgetService(categories, transactions),
                        Notifier(telegram, settingsRepo, NotificationEventRepository(db)),
                        CounterpartyRepository(db), ConduitMccRepository(db), AccountRepository(db),
                        clock = clock,
                    )
                    val accounts = AccountRepository(db)
                    val syncService = SyncService(failingMono, ingest, accounts, settingsRepo, clock) { august }
                    settingsRoutes(settingsRepo, syncService, failingMono, telegram, accounts, ingest, { block -> launch { block() } }) {
                        "https://budget.fly.dev"
                    }
                }
            }
            val client = createClient { followRedirects = false }
            client.submitForm("/settings/webhook", parameters { })
            assertFalse(settings(db).isSet(SettingKeys.MONO_WEBHOOK_REGISTERED_AT))
            val body = client.get("/settings").bodyAsText()
            assertTrue(body.contains(UkCopy.webhookInactiveLabel), body)
        }
    }

    @Test
    fun `registering without a resolvable public url reports an error`() = withTestDb { db ->
        testApplication {
            setup(db, baseUrl = null)
            val client = createClient { followRedirects = false }
            val response = client.submitForm("/settings/webhook", parameters { })
            assertTrue(response.headers["Location"]!!.contains("error="), response.headers["Location"]!!)
            assertEquals(null, mono.registeredWebhook)
        }
    }

    // --- sync ----------------------------------------------------------------------------

    @Test
    fun `the sync button reports its cooldown`() = withTestDb { db ->
        // Pair a chat up front: the Telegram card's test-notification button is disabled
        // until a chat is paired, and this test's "disabled" assertions are about the sync
        // button specifically.
        settings(db).set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        testApplication {
            val jobs = mutableListOf<Job>()
            setup(db, syncJobs = jobs)
            val client = createClient { followRedirects = false }
            assertTrue(!client.get("/settings").bodyAsText().contains("disabled"))
            client.submitForm("/settings/sync", parameters { })
            // The cooldown is stamped synchronously before the detached job is launched
            // (see SettingsPage.kt), so this assertion does not need to wait on the job —
            // but the job is still joined so it can't leak past this test's temp database.
            val body = client.get("/settings").bodyAsText()
            assertTrue(body.contains("disabled"), "the button must be locked during the cooldown")
            assertTrue(body.contains("60"), body)
            jobs.forEach { it.join() }
        }
    }

    @Test
    fun `the sync period is stated before the button is pressed`() = withTestDb { db ->
        testApplication {
            setup(db)
            val body = client.get("/settings").bodyAsText()
            assertTrue(
                body.contains(UkCopy.syncPeriodLabel(UkCopy.monthLabel("2026-08"))),
                "the page must say which period a sync covers before the owner presses the button; body=$body",
            )
        }
    }

    @Test
    fun `a sync attempted during the cooldown does not launch another detached job`() = withTestDb { db ->
        testApplication {
            val jobs = mutableListOf<Job>()
            setup(db, syncJobs = jobs)
            val client = createClient { followRedirects = false }

            client.submitForm("/settings/sync", parameters { })
            jobs.forEach { it.join() }
            val jobsAfterFirst = jobs.size

            val response = client.submitForm("/settings/sync", parameters { })
            assertTrue(response.headers["Location"]!!.contains("error="), response.headers["Location"]!!)
            assertEquals(jobsAfterFirst, jobs.size, "a refused sync must not launch a detached job")
        }
    }

    @Test
    fun `pressing Sync redirects immediately without waiting for the sync to finish`() = withTestDb { db ->
        testApplication {
            // A gate the test controls: statement() suspends here until the test resolves
            // it. This is what proves the HTTP request does not block on the sync — a real
            // network delay would be flaky to assert against, this is not.
            val gate = CompletableDeferred<Unit>()
            val gatedMono = object : MonoClient {
                override suspend fun clientInfo(waitForRateLimit: Boolean) = MonoClientInfo(
                    clientId = "c1",
                    name = "Test",
                    accounts = listOf(
                        MonoAccount(id = "acc-uah", currencyCode = 980, maskedPan = listOf("537541******1234"), type = "black"),
                    ),
                )
                override suspend fun statement(
                    accountId: String,
                    from: Instant,
                    to: Instant,
                    waitForRateLimit: Boolean,
                ): List<RawItem> {
                    gate.await()
                    return emptyList()
                }
                override suspend fun registerWebhook(url: String) = Unit
            }
            val categories = CategoryRepository(db, ConduitMccRepository(db))
            val transactions = TransactionRepository(db)
            val settingsRepo = settings(db)
            val telegramFake = FakeTelegramClient()
            val ingest = IngestService(
                transactions, categories,
                BudgetService(categories, transactions),
                Notifier(telegramFake, settingsRepo, NotificationEventRepository(db)),
                CounterpartyRepository(db), ConduitMccRepository(db), AccountRepository(db),
                clock = clock,
            )
            val accounts = AccountRepository(db)
            val syncService = SyncService(gatedMono, ingest, accounts, settingsRepo, clock) { august }
            val jobs = mutableListOf<Job>()
            application {
                routing {
                    settingsRoutes(settingsRepo, syncService, gatedMono, telegramFake, accounts, ingest, { block -> jobs += launch { block() } }) {
                        "https://budget.fly.dev"
                    }
                }
            }

            val client = createClient { followRedirects = false }
            val response = client.submitForm("/settings/sync", parameters { })

            // The response already came back, but the sync job is still parked on the gate:
            // the request handler did not wait for it.
            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(response.headers["Location"]!!.contains(enc("Синхронізація почалась")), response.headers["Location"]!!)
            assertEquals(1, jobs.size)
            assertFalse(jobs.single().isCompleted, "the sync must still be running after the redirect is sent")

            gate.complete(Unit)
            jobs.forEach { it.join() }
            // Item 10: the settings table now stores the sync outcome as structured JSON
            // (StoredSyncResult), not a rendered sentence — Copy is applied at read time
            // instead, so a later language switch or wording fix reaches even an old run.
            val stored = settingsRepo.get(SettingKeys.LAST_SYNC_RESULT)
            assertNotNull(stored)
            val decoded = app.API_JSON.decodeFromString<StoredSyncResult>(stored)
            assertEquals(StoredSyncResult(ok = true, month = "2026-08", accounts = 1, syncedAt = august), decoded)
            val expected = UkCopy.syncSummary(inserted = 0, updated = 0, unchanged = 0, accounts = 1, errors = emptyList())
            assertEquals(UkCopy.lastSyncSummary(expected), decoded.render(UkCopy))
        }
    }

    @Test
    fun `a completed detached sync's outcome is persisted and rendered on the page`() = withTestDb { db ->
        testApplication {
            val jobs = mutableListOf<Job>()
            setup(db, syncJobs = jobs)
            val client = createClient { followRedirects = false }
            client.submitForm("/settings/sync", parameters { })
            jobs.forEach { it.join() }

            // Item 10: stored as structured JSON, not the rendered sentence — the page
            // renders it back through Copy at read time (see the next test for why that
            // matters: a language switch must reach even an old run's summary).
            val stored = settings(db).get(SettingKeys.LAST_SYNC_RESULT)
            assertNotNull(stored)
            val decoded = app.API_JSON.decodeFromString<StoredSyncResult>(stored)
            assertTrue(decoded.ok, stored)
            assertEquals(1, decoded.accounts, "the compact result line names how many accounts were synced")

            val body = client.get("/settings").bodyAsText()
            assertTrue(body.contains(decoded.render(UkCopy)), body)
        }
    }

    @Test
    fun `switching language after a sync changes how its stored result reads, not just new pages`() = withTestDb { db ->
        // Item 10: this used to store `copy.lastSyncSummary(...)`'s rendered sentence and
        // print it back verbatim, frozen in whatever language was current when the sync
        // ran. Storing StoredSyncResult as JSON and applying Copy at read time means a
        // language switch reaches even a sync that ran before the switch.
        testApplication {
            val jobs = mutableListOf<Job>()
            setup(db, syncJobs = jobs)
            val client = createClient { followRedirects = false }
            client.submitForm("/settings/sync", parameters { })
            jobs.forEach { it.join() }

            val ukrainianBody = client.get("/settings").bodyAsText()
            assertTrue(ukrainianBody.contains(UkCopy.lastSyncSummary("")), ukrainianBody)

            client.submitForm("/settings/language", parameters { append("language", "en") })
            val englishBody = client.get("/settings").bodyAsText()
            assertTrue(
                englishBody.contains(app.i18n.EnCopy.lastSyncSummary("")),
                "the same stored sync result must re-render in the new language: $englishBody",
            )
            assertTrue(!englishBody.contains(UkCopy.lastSyncSummary("")), englishBody)
        }
    }

    // --- Telegram: test notification ------------------------------------------------------

    @Test
    fun `the test notification goes to the paired chat`() = withTestDb { db ->
        settings(db).set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        testApplication {
            setup(db)
            val client = createClient { followRedirects = false }
            client.submitForm("/settings/test-notification", parameters { })
            assertEquals(1, telegram.sent.size)
        }
    }

    @Test
    fun `a test notification without a paired chat is an error, not a crash`() = withTestDb { db ->
        testApplication {
            setup(db)
            val client = createClient { followRedirects = false }
            val response = client.submitForm("/settings/test-notification", parameters { })
            assertTrue(response.headers["Location"]!!.contains("error="))
            assertTrue(telegram.sent.isEmpty())
        }
    }

    @Test
    fun `the test-notification button is disabled until a chat is paired`() = withTestDb { db ->
        testApplication {
            setup(db)
            val withoutChat = client.get("/settings").bodyAsText()
            assertTrue(withoutChat.contains("disabled"), withoutChat)
        }
    }

    // --- accounts --------------------------------------------------------------------------

    @Test
    fun `the page lists the stored accounts`() = withTestDb { db ->
        testApplication {
            val jobs = mutableListOf<Job>()
            setup(db, syncJobs = jobs)
            val client = createClient { followRedirects = false }
            client.submitForm("/settings/sync", parameters { })
            jobs.forEach { it.join() }
            assertTrue(client.get("/settings").bodyAsText().contains("537541******1234"))
        }
    }

    @Test
    fun `the accounts table renders a checkbox per account and saving persists it`() = withTestDb { db ->
        testApplication {
            val jobs = mutableListOf<Job>()
            setup(db, syncJobs = jobs)
            val client = createClient { followRedirects = false }
            client.submitForm("/settings/sync", parameters { })
            jobs.forEach { it.join() }

            val accounts = AccountRepository(db)
            val accountId = accounts.trackableList().single().id
            val before = client.get("/settings").bodyAsText()
            assertTrue(before.contains("name=\"active\""), before)
            assertTrue(before.contains("value=\"$accountId\""), before)
            // The dollar account is stored but never offered: a checkbox for it could only
            // switch on an account whose cents would then be totalled as kopecks.
            assertFalse(before.contains("value=\"acc-usd\""), before)

            // Uncheck the only account by submitting the form without it.
            client.submitForm("/settings/accounts", parameters { })
            assertFalse(accounts.trackableList().single().active, "unchecking must switch the account off")

            client.submitForm("/settings/accounts", parameters { append("active", accountId) })
            assertTrue(accounts.trackableList().single().active)
        }
    }

    @Test
    fun `the accounts footer states the total of the counted balances only`() = withTestDb { db ->
        mono = FakeMonoClient(
            accounts = listOf(
                MonoAccount(id = "acc-a", currencyCode = 980, maskedPan = listOf("111111******1111"), type = "black", balance = 150_000),
                MonoAccount(id = "acc-b", currencyCode = 980, maskedPan = listOf("222222******2222"), type = "black", balance = 50_000),
            ),
        )
        testApplication {
            val categories = CategoryRepository(db, ConduitMccRepository(db))
            val transactions = TransactionRepository(db)
            val settingsRepo = settings(db)
            telegram = FakeTelegramClient()
            val ingest = IngestService(
                transactions, categories,
                BudgetService(categories, transactions),
                Notifier(telegram, settingsRepo, NotificationEventRepository(db)),
                CounterpartyRepository(db), ConduitMccRepository(db), AccountRepository(db),
                clock = clock,
            )
            val accounts = AccountRepository(db)
            val sync = SyncService(mono, ingest, accounts, settingsRepo, clock) { august }
            val jobs = mutableListOf<Job>()
            application {
                routing {
                    settingsRoutes(settingsRepo, sync, mono, telegram, accounts, ingest, { block -> jobs += launch { block() } }) {
                        "https://budget.fly.dev"
                    }
                }
            }
            val client = createClient { followRedirects = false }
            client.submitForm("/settings/sync", parameters { })
            jobs.forEach { it.join() }

            // Only "acc-a" stays counted — its balance alone should show in the total.
            client.submitForm("/settings/accounts", parameters { append("active", "acc-a") })

            val body = client.get("/settings").bodyAsText()
            assertTrue(body.contains(UkCopy.countedTotalLabel(formatMinor(150_000))), body)
            assertTrue(!body.contains(UkCopy.countedTotalLabel(formatMinor(200_000))), body)
        }
    }

    @Test
    fun `the accounts total names the subset it summed`() {
        // 2026-09-22 production walkthrough, finding 5: "Разом 12 000,00 ₴" sat under five
        // rows whose visible balances added to 20 000,00 ₴ and read as a broken sum. It is
        // the included-accounts total; the label has to say so.
        assertTrue(UkCopy.countedTotalLabel("1 ₴").contains("врахован"), UkCopy.countedTotalLabel("1 ₴"))
        assertTrue(EnCopy.countedTotalLabel("1 ₴").contains("Included"), EnCopy.countedTotalLabel("1 ₴"))
    }

    /**
     * Finding 6 of the 2026-09 branch review: "Активний"/"Врахований" changed meaning from
     * "pull future statements" to "this account's whole history counts toward every total"
     * when the spending-truth fix landed, but the page still said nothing beyond the
     * routine "Рахунки збережено" flash. The fix is a stated hint plus a conditional
     * confirm — data-driven (CSP: no inline script), and wired to only fire on a real
     * uncheck via app.js's data-confirm-if-unchecked, not on every save.
     */
    @Test
    fun `the accounts footer states the retroactive semantics and carries a conditional confirm`() = withTestDb { db ->
        testApplication {
            val jobs = mutableListOf<Job>()
            setup(db, syncJobs = jobs)
            val client = createClient { followRedirects = false }
            client.submitForm("/settings/sync", parameters { })
            jobs.forEach { it.join() }

            val body = client.get("/settings").bodyAsText()
            assertTrue(body.contains(UkCopy.accountActiveHint), "the new semantics must be stated up front: $body")
            assertTrue(body.contains("data-confirm-if-unchecked=\"active\""), body)
            assertTrue(body.contains(UkCopy.accountDeactivateConfirm), body)
            // The confirm must be conditional (data-confirm-if-unchecked), not the
            // unconditional data-confirm the category delete button uses — a save that
            // changes nothing about which accounts are active must not interrupt every
            // unrelated settings save with a dialog.
            assertTrue(!body.contains("data-confirm=\"${UkCopy.accountDeactivateConfirm}\""), body)
        }
    }

    @Test
    fun `switching an account off does not delete its transactions`() = withTestDb { db ->
        testApplication {
            val jobs = mutableListOf<Job>()
            setup(db, syncJobs = jobs)
            val client = createClient { followRedirects = false }
            mono.stubStatement(
                app.mono.StatementItem(id = "t1", time = august, amount = -1000, mcc = 5411),
            )
            client.submitForm("/settings/sync", parameters { })
            jobs.forEach { it.join() }
            val transactions = TransactionRepository(db)
            assertEquals(1, transactions.count(null, null, false))

            client.submitForm("/settings/accounts", parameters { }) // switch every account off

            assertEquals(1, transactions.count(null, null, false), "history must survive deactivation")
        }
    }

    @Test
    fun `a pre-JSON sync result is dropped rather than printed in a frozen language`() = withTestDb { db ->
        // 2026-09-22 production walkthrough, finding 2: the legacy passthrough printed
        // "369 new, 0 updated, 0 unchanged across 5 account(s)" verbatim on a Ukrainian
        // page for three weeks, because nothing but a button press could replace it.
        settings(db).set(
            SettingKeys.LAST_SYNC_RESULT,
            "Востаннє: 369 new, 0 updated, 0 unchanged across 5 account(s)",
        )
        testApplication {
            setup(db)
            val body = client.get("/settings").bodyAsText()
            assertFalse(body.contains("unchanged across"), "a frozen legacy sentence must not be printed: $body")
            assertTrue(body.contains(UkCopy.neverSyncedYet), body)
        }
    }

    // --- language --------------------------------------------------------------------------

    @Test
    fun `saving the language updates the stored setting`() = withTestDb { db ->
        testApplication {
            setup(db)
            val client = createClient { followRedirects = false }
            client.submitForm("/settings/language", parameters { append("language", "en") })
            assertEquals("en", settings(db).get(SettingKeys.LANGUAGE))
        }
    }

    // --- CSP ---------------------------------------------------------------------------------

    /**
     * CSP is `default-src 'none'` / `style-src 'self'` (CLAUDE.md): a browser silently
     * discards an inline `style="…"` attribute, which is exactly how every progress bar
     * once rendered full width in production. DashboardPageTest/CategoriesPageTest/
     * TransactionsPageTest each carry this same check; Налаштування gets its own now that
     * it is off the legacy shim too.
     */
    @Test
    fun `no inline style attribute is ever emitted`() = withTestDb { db ->
        testApplication {
            val jobs = mutableListOf<Job>()
            setup(db, syncJobs = jobs)
            val client = createClient { followRedirects = false }
            client.submitForm("/settings/sync", parameters { })
            jobs.forEach { it.join() }
            val body = client.get("/settings").bodyAsText()
            assertTrue(!body.contains("style=\""), "no inline style attribute may be emitted: $body")
        }
    }

    // --- CSRF --------------------------------------------------------------------------------

    @Test
    fun `every form on the page carries a CSRF token`() = withTestDb { db ->
        // Without a synced account there are 7: logout, sync, re-register webhook,
        // test-notification, reconnect, tokens, language. Syncing an account in adds the
        // accounts table's own save form, for 8 — that form is conditional on having
        // something to show (design-handoff.md §4.3's "Рахунків ще нема" state renders no
        // table and therefore no form at all).
        testApplication {
            val jobs = mutableListOf<Job>()
            setup(db, syncJobs = jobs)
            val client = createClient { followRedirects = false }

            val withoutAccounts = client.get("/settings").bodyAsText()
            assertEquals(7, Regex("name=\"csrf_token\"").findAll(withoutAccounts).count(), withoutAccounts)

            client.submitForm("/settings/sync", parameters { })
            jobs.forEach { it.join() }
            val withAccounts = client.get("/settings").bodyAsText()
            assertEquals(8, Regex("name=\"csrf_token\"").findAll(withAccounts).count(), withAccounts)
        }
    }
}
