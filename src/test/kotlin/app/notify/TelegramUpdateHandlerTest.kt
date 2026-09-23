package app.notify

import app.budget.BudgetService
import app.budget.CategoryRepository
import app.budget.ConduitMccRepository
import app.budget.KYIV
import app.budget.TransactionRepository
import app.budget.Txn
import app.budget.monthKeyOf
import app.db.Crypto
import app.db.SettingKeys
import app.db.SettingsRepository
import app.i18n.UkCopy
import app.withTestDb
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramUpdateHandlerTest {

    private val august = 1_787_011_200L
    private val clock = Clock.fixed(Instant.ofEpochSecond(august), KYIV)

    private fun settings(db: Database) = SettingsRepository(db, Crypto(ByteArray(32) { it.toByte() }))

    private fun handler(
        db: Database,
        telegram: FakeTelegramClient,
        callbacks: CallbackHandler = CallbackHandler.NoOp,
        limits: LimitCommands = LimitCommands.NoOp,
    ): TelegramUpdateHandler {
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val transactions = TransactionRepository(db)
        return TelegramUpdateHandler(
            settings(db), telegram, BudgetService(categories, transactions), callbacks,
            limits = limits, clock = clock,
        )
    }

    private class Fixture(db: Database) {
        val settings = SettingsRepository(db, Crypto(ByteArray(32) { it.toByte() }))
        val telegram = FakeTelegramClient()
        val budget = BudgetService(CategoryRepository(db, ConduitMccRepository(db)), TransactionRepository(db))
        val callbacks: CallbackHandler = CallbackHandler.NoOp
    }

    private fun fixture(db: Database) = Fixture(db)

    private fun message(text: String, chatId: Long = 12345, type: String = "private") =
        TgUpdate(updateId = 1, message = TgMessage(messageId = 1, chat = TgChat(chatId, type), text = text))

    private fun groupMessage(text: String, chatId: Long = -1001234567890) =
        message(text, chatId, type = "supergroup")

    /**
     * A pairing code is only usable while its expiry stamp is live, so every test that
     * expects pairing to succeed has to issue the code the way the Settings page does.
     * [ttlSeconds] is relative to the fixed clock above.
     */
    private fun SettingsRepository.issuePairingCode(code: String, ttlSeconds: Long = PAIRING_TTL_SECONDS) {
        set(SettingKeys.PAIRING_CODE, code)
        set(SettingKeys.PAIRING_CODE_EXPIRES_AT, (august + ttlSeconds).toString())
    }

    @Test
    fun `status takes a month and reports it`() = withTestDb { db ->
        settings(db).set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        val telegram = FakeTelegramClient()
        runBlocking { handler(db, telegram).handle(message("/status 2026-07")) }
        assertTrue(telegram.sent.single().text.contains(UkCopy.monthLabel("2026-07")), telegram.sent.single().text)
    }

    /** safeMonthKey would round a typo to the current month and answer it with numbers for
     *  a month nobody asked about. A refusal is the only honest answer. */
    @Test
    fun `an unparseable month is refused, not read as the current one`() = withTestDb { db ->
        settings(db).set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        val telegram = FakeTelegramClient()
        runBlocking { handler(db, telegram).handle(message("/status 2026-13")) }
        val text = telegram.sent.single().text
        assertTrue(text.contains(UkCopy.badMonthArgument("2026-08")), text)
        assertFalse(text.contains(UkCopy.monthLabel("2026-08")), "a typo must not be answered with numbers")
    }

    @Test
    fun `a command is matched exactly, not by prefix`() = withTestDb { db ->
        settings(db).set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        val telegram = FakeTelegramClient()
        runBlocking { handler(db, telegram).handle(message("/statuses")) }
        assertEquals(UkCopy.helpText, telegram.sent.single().text, "/statuses is not /status")
    }

    @Test
    fun `left is answered`() = withTestDb { db ->
        settings(db).set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        val telegram = FakeTelegramClient()
        runBlocking { handler(db, telegram).handle(message("/left")) }
        assertTrue(
            telegram.sent.single().text.contains(UkCopy.leftHeader(UkCopy.monthLabel("2026-08"))),
            telegram.sent.single().text,
        )
    }

    @Test
    fun `limit is delegated to the limit service`() = withTestDb { db ->
        settings(db).set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        val started = mutableListOf<String>()
        val limits = object : LimitCommands {
            override suspend fun start(chatId: String) { started += chatId }
            override suspend fun handleAmountReply(message: TgMessage) = false
        }
        runBlocking { handler(db, FakeTelegramClient(), limits = limits).handle(message("/limit")) }
        assertEquals(listOf("12345"), started)
    }

    @Test
    fun `a reply carrying a limit amount is not treated as a command`() = withTestDb { db ->
        settings(db).set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        val telegram = FakeTelegramClient()
        val limits = object : LimitCommands {
            override suspend fun start(chatId: String) = Unit
            override suspend fun handleAmountReply(message: TgMessage) = true
        }
        val update = TgUpdate(
            updateId = 1,
            message = TgMessage(
                messageId = 2, chat = TgChat(12345, "private"), text = "8500",
                replyToMessage = TgMessage(messageId = 1, chat = TgChat(12345, "private")),
            ),
        )
        runBlocking { handler(db, telegram, limits = limits).handle(update) }
        assertTrue(telegram.sent.isEmpty(), "the answer was consumed, so no help text: ${telegram.sent}")
    }

    @Test
    fun `start with the right code pairs the chat`() = withTestDb { db ->
        val settings = settings(db)
        settings.issuePairingCode("A7F3K2")
        val telegram = FakeTelegramClient()
        runBlocking { handler(db, telegram).handle(message("/start A7F3K2")) }

        assertEquals("12345", settings.get(SettingKeys.TELEGRAM_CHAT_ID))
        assertNull(settings.get(SettingKeys.PAIRING_CODE), "the code must burn after use")
        assertTrue(telegram.sent.single().text.contains(UkCopy.pairingSuccess.trim()), telegram.sent.single().text)
    }

    @Test
    fun `the pairing code is case insensitive and tolerates whitespace`() = withTestDb { db ->
        val settings = settings(db)
        settings.issuePairingCode("A7F3K2")
        runBlocking { handler(db, FakeTelegramClient()).handle(message("/start  a7f3k2 ")) }
        assertEquals("12345", settings.get(SettingKeys.TELEGRAM_CHAT_ID))
    }

    /**
     * In a group, Telegram's own command autocomplete writes `/start@my_bot CODE`, and
     * that suffix is part of the message text. Stripping it is what makes pairing from a
     * group possible at all: `removePrefix("/start")` left `@my_bot CODE` as the code.
     */
    @Test
    fun `a start command addressed to the bot by name still pairs`() = withTestDb { db ->
        val settings = settings(db)
        settings.issuePairingCode("A7F3K2")
        runBlocking { handler(db, FakeTelegramClient()).handle(message("/start@test_bot A7F3K2")) }
        assertEquals("12345", settings.get(SettingKeys.TELEGRAM_CHAT_ID))
    }

    /**
     * A group is full of other bots, and Telegram hands ours every message beginning with a
     * slash even under privacy mode. Stripping the suffix without reading it turned
     * `/status@some_other_bot` into our own /status — the month's spending, posted into the
     * group by a command nobody addressed to us — and `/start@other_bot CODE` into a pairing.
     */
    @Test
    fun `a command addressed to another bot is not ours to answer`() = withTestDb { db ->
        settings(db).set(SettingKeys.TELEGRAM_CHAT_ID, "-1001234567890")
        val telegram = FakeTelegramClient()
        runBlocking { handler(db, telegram).handle(groupMessage("/status@some_other_bot")) }
        assertTrue(telegram.sent.isEmpty(), "another bot's command must not be answered: ${telegram.sent}")
    }

    @Test
    fun `a pairing code sent to another bot does not pair`() = withTestDb { db ->
        val settings = settings(db)
        settings.issuePairingCode("A7F3K2")
        runBlocking { handler(db, FakeTelegramClient()).handle(groupMessage("/start@some_other_bot A7F3K2")) }
        assertNull(settings.get(SettingKeys.TELEGRAM_CHAT_ID))
        assertEquals("A7F3K2", settings.get(SettingKeys.PAIRING_CODE), "a code nobody offered us must survive")
    }

    @Test
    fun `status addressed to the bot by name is answered`() = withTestDb { db ->
        settings(db).set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        val telegram = FakeTelegramClient()
        runBlocking { handler(db, telegram).handle(message("/status@test_bot")) }
        assertTrue(telegram.sent.single().text.contains(UkCopy.monthLabel("2026-08")), telegram.sent.single().text)
    }

    @Test
    fun `a wrong code does not pair the chat`() = withTestDb { db ->
        val settings = settings(db)
        settings.issuePairingCode("A7F3K2")
        val telegram = FakeTelegramClient()
        runBlocking { handler(db, telegram).handle(message("/start GUESS1")) }

        assertNull(settings.get(SettingKeys.TELEGRAM_CHAT_ID))
        assertEquals("A7F3K2", settings.get(SettingKeys.PAIRING_CODE))
        assertTrue(telegram.sent.single().text.contains(UkCopy.invalidPairingCode), telegram.sent.single().text)
    }

    @Test
    fun `an expired pairing code does not pair the chat and is discarded`() = withTestDb { db ->
        val settings = settings(db)
        // Issued, then left to rot: the stamp is one second behind the fixed clock.
        settings.issuePairingCode("A7F3K2", ttlSeconds = -1)
        val telegram = FakeTelegramClient()
        runBlocking { handler(db, telegram).handle(message("/start A7F3K2")) }

        assertNull(settings.get(SettingKeys.TELEGRAM_CHAT_ID), "an expired code must not pair")
        assertNull(settings.get(SettingKeys.PAIRING_CODE), "an expired code must be cleared, not left to be retried")
        assertTrue(telegram.sent.single().text.contains(UkCopy.invalidPairingCode), telegram.sent.single().text)
    }

    @Test
    fun `a code with no expiry stamp is refused`() = withTestDb { db ->
        // Guards the upgrade path: a code written by the previous build has no stamp, and
        // "no stamp" must read as expired rather than as immortal.
        val settings = settings(db)
        settings.set(SettingKeys.PAIRING_CODE, "A7F3K2")
        runBlocking { handler(db, FakeTelegramClient()).handle(message("/start A7F3K2")) }

        assertNull(settings.get(SettingKeys.TELEGRAM_CHAT_ID))
        assertNull(settings.get(SettingKeys.PAIRING_CODE))
    }

    @Test
    fun `the pairing code burns after too many wrong guesses`() = withTestDb { db ->
        val settings = settings(db)
        settings.issuePairingCode("A7F3K2")
        val telegram = FakeTelegramClient()
        val handler = handler(db, telegram)

        runBlocking {
            repeat(MAX_PAIRING_ATTEMPTS - 1) { handler.handle(message("/start WRONG1")) }
            assertEquals("A7F3K2", settings.get(SettingKeys.PAIRING_CODE), "still alive one guess short of the cap")
            handler.handle(message("/start WRONG1"))
        }

        assertNull(settings.get(SettingKeys.PAIRING_CODE), "the code must burn at the cap")

        // And the burnt code is genuinely dead: the correct value no longer pairs.
        runBlocking { handler.handle(message("/start A7F3K2")) }
        assertNull(settings.get(SettingKeys.TELEGRAM_CHAT_ID))
    }

    /**
     * Telegram's own START button sends a bare /start, and anyone who ever opened the bot
     * can send one. Scoring that as a wrong guess let five taps — from a stranger's chat,
     * at that — burn the code the owner is in the middle of using, while the Settings page
     * went on showing it. A guess has to contain something to be wrong.
     */
    @Test
    fun `a bare start is not a wrong guess and does not burn the code`() = withTestDb { db ->
        val settings = settings(db)
        settings.issuePairingCode("A7F3K2")
        val handler = handler(db, FakeTelegramClient())
        runBlocking { repeat(MAX_PAIRING_ATTEMPTS + 2) { handler.handle(message("/start")) } }

        assertEquals("A7F3K2", settings.get(SettingKeys.PAIRING_CODE), "the code must survive bare /start")
        assertNull(settings.get(SettingKeys.PAIRING_ATTEMPTS))
    }

    @Test
    fun `a successful pairing clears the attempt counter`() = withTestDb { db ->
        val settings = settings(db)
        settings.issuePairingCode("A7F3K2")
        val handler = handler(db, FakeTelegramClient())
        runBlocking {
            handler.handle(message("/start WRONG1"))
            handler.handle(message("/start A7F3K2"))
        }
        assertEquals("12345", settings.get(SettingKeys.TELEGRAM_CHAT_ID))
        assertNull(settings.get(SettingKeys.PAIRING_ATTEMPTS))
        assertNull(settings.get(SettingKeys.PAIRING_CODE_EXPIRES_AT))
    }

    @Test
    fun `an unpaired chat gets no data from other commands`() = withTestDb { db ->
        val telegram = FakeTelegramClient()
        runBlocking { handler(db, telegram).handle(message("/status")) }
        assertEquals(1, telegram.sent.size)
        assertTrue(telegram.sent.single().text.contains("/start"), telegram.sent.single().text)
    }

    /**
     * The whole point of the shared-group feature: the owner generates a fresh code in the
     * web UI, sends it in a group he shares with his wife, and every alert and category
     * question moves there. Before this, [handleMessage] silenced every chat but the paired
     * one, so the group's /start never even reached [handleStart] and re-pairing was
     * impossible without touching the database by hand.
     */
    @Test
    fun `a live pairing code moves the bot to a group chat`() = withTestDb { db ->
        val settings = settings(db)
        settings.set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        settings.issuePairingCode("A7F3K2")
        val telegram = FakeTelegramClient()
        runBlocking { handler(db, telegram).handle(message("/start A7F3K2", chatId = -1001234567890)) }

        assertEquals("-1001234567890", settings.get(SettingKeys.TELEGRAM_CHAT_ID))
        assertNull(settings.get(SettingKeys.PAIRING_CODE), "the code must burn after a move too")
        val welcome = telegram.sent.single { it.chatId == "-1001234567890" }
        assertTrue(welcome.text.contains(UkCopy.pairingSuccess.trim()), welcome.text)
    }

    /**
     * The other half of the move-to-a-group rule above: with no code out, a paired bot is
     * closed. A stranger guessing /start — even with a code that was valid last week — gets
     * silence and changes nothing. The code is the credential; the owner issues it from the
     * authenticated Settings page, it lives [PAIRING_TTL_SECONDS] and dies after
     * [MAX_PAIRING_ATTEMPTS] wrong guesses.
     */
    private class RecordingMoves : ChatMoveObserver {
        val moves = mutableListOf<Pair<String, String>>()
        override suspend fun onChatMoved(from: String, to: String) { moves += from to to }
    }

    @Test
    fun `a move is reported so open questions can follow the bot`() = withTestDb { db ->
        val settings = settings(db)
        settings.set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        settings.issuePairingCode("A7F3K2")
        val moves = RecordingMoves()
        val handler = TelegramUpdateHandler(
            settings, FakeTelegramClient(), BudgetService(CategoryRepository(db, ConduitMccRepository(db)), TransactionRepository(db)),
            chatMoves = moves, clock = clock,
        )
        runBlocking { handler.handle(message("/start A7F3K2", chatId = -1001234567890)) }

        assertEquals(listOf("12345" to "-1001234567890"), moves.moves)
    }

    @Test
    fun `a first pairing is not a move`() = withTestDb { db ->
        val settings = settings(db)
        settings.issuePairingCode("A7F3K2")
        val moves = RecordingMoves()
        val handler = TelegramUpdateHandler(
            settings, FakeTelegramClient(), BudgetService(CategoryRepository(db, ConduitMccRepository(db)), TransactionRepository(db)),
            chatMoves = moves, clock = clock,
        )
        runBlocking { handler.handle(message("/start A7F3K2")) }

        assertTrue(moves.moves.isEmpty(), "there is no earlier chat to move anything out of: ${moves.moves}")
    }

    @Test
    fun `the chat the bot leaves is told where the alerts went`() = withTestDb { db ->
        val settings = settings(db)
        settings.set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        settings.issuePairingCode("A7F3K2")
        val telegram = FakeTelegramClient()
        runBlocking { handler(db, telegram).handle(message("/start A7F3K2", chatId = -1001234567890)) }

        val farewell = telegram.sent.singleOrNull { it.chatId == "12345" }
        assertEquals(UkCopy.chatMovedAway, farewell?.text, "the old chat must not just go quiet")
    }

    /**
     * The code sent back into the chat that already holds the bot is a no-op, not a move:
     * the paired chat never reaches [handleStart] at all, so it cannot be told it lost the
     * bot to itself. The code stays live for the rest of its window, which is what lets the
     * owner then send it where he actually meant to.
     */
    @Test
    fun `the paired chat sending the code back is told it is already connected`() = withTestDb { db ->
        val settings = settings(db)
        settings.set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        settings.issuePairingCode("A7F3K2")
        val telegram = FakeTelegramClient()
        runBlocking { handler(db, telegram).handle(message("/start A7F3K2", chatId = 12345)) }

        assertEquals(UkCopy.alreadyConnectedMessage, telegram.sent.single().text)
        assertEquals("A7F3K2", settings.get(SettingKeys.PAIRING_CODE), "the code is still there to be sent elsewhere")
    }

    @Test
    fun `a stranger cannot take over an already paired bot while no code is out`() = withTestDb { db ->
        val settings = settings(db)
        settings.set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        val telegram = FakeTelegramClient()
        runBlocking { handler(db, telegram).handle(message("/start A7F3K2", chatId = 99999)) }

        assertEquals("12345", settings.get(SettingKeys.TELEGRAM_CHAT_ID))
        assertTrue(telegram.sent.isEmpty(), "an unknown chat gets no reply at all")
    }

    @Test
    fun `an expired code does not let a stranger take over`() = withTestDb { db ->
        val settings = settings(db)
        settings.set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        settings.issuePairingCode("A7F3K2", ttlSeconds = -1)
        val telegram = FakeTelegramClient()
        runBlocking { handler(db, telegram).handle(message("/start A7F3K2", chatId = 99999)) }

        assertEquals("12345", settings.get(SettingKeys.TELEGRAM_CHAT_ID))
        // A dead code is the same as no code, so the silence owed to strangers applies:
        // an "invalid code" reply tells an unknown chat the bot is here and listening.
        assertTrue(telegram.sent.isEmpty(), "a dead code must not buy a stranger a reply: ${telegram.sent}")
        assertEquals("A7F3K2", settings.get(SettingKeys.PAIRING_CODE), "nor let a stranger clear the owner's code")
    }

    /**
     * The live-code window is the one time a stranger's /start is looked at, so it is also
     * the one time silence could slip: a bare /start carries no code to check, and must not
     * earn a reply that says the bot is here and unconnected.
     */
    @Test
    fun `a bare start from a stranger goes unanswered even while a code is live`() = withTestDb { db ->
        val settings = settings(db)
        settings.set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        settings.issuePairingCode("A7F3K2")
        val telegram = FakeTelegramClient()
        runBlocking { handler(db, telegram).handle(message("/start", chatId = 99999)) }
        assertTrue(telegram.sent.isEmpty(), "an unknown chat gets no reply at all: ${telegram.sent}")
    }

    /**
     * The live-code window is the only time a stranger's /start is read at all, and a reply
     * to a wrong guess is an oracle: it tells whoever is trying that the window just opened.
     * The guess is still counted — five of them burn the code, which is the brute-force
     * protection working — but the guesser learns nothing from the silence.
     */
    @Test
    fun `a wrong guess from a stranger is counted but not answered`() = withTestDb { db ->
        val settings = settings(db)
        settings.set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        settings.issuePairingCode("A7F3K2")
        val telegram = FakeTelegramClient()
        runBlocking { handler(db, telegram).handle(message("/start GUESS1", chatId = 99999)) }

        assertTrue(telegram.sent.isEmpty(), "an unknown chat gets no reply at all: ${telegram.sent}")
        assertEquals("1", settings.get(SettingKeys.PAIRING_ATTEMPTS), "the guess must still count against the cap")
        assertEquals("12345", settings.get(SettingKeys.TELEGRAM_CHAT_ID))
    }

    @Test
    fun `a wrong guess in the paired chat is still answered`() = withTestDb { db ->
        val settings = settings(db)
        settings.issuePairingCode("A7F3K2")
        val telegram = FakeTelegramClient()
        runBlocking { handler(db, telegram).handle(message("/start GUESS1")) }
        assertTrue(telegram.sent.single().text.contains(UkCopy.invalidPairingCode), telegram.sent.single().text)
    }

    @Test
    fun `an unpaired bot in a group answers nothing but start`() = withTestDb { db ->
        val telegram = FakeTelegramClient()
        runBlocking { handler(db, telegram).handle(groupMessage("/karma@test_bot")) }
        assertTrue(telegram.sent.isEmpty(), "a bot freshly added to a group must not lecture it: ${telegram.sent}")
    }

    @Test
    fun `status reports every category with its numbers`() = withTestDb { db ->
        val settings = settings(db)
        settings.set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val transactions = TransactionRepository(db)
        val restaurants = categories.create("Restaurants", "🍔", 1_500_000, 80)
        transactions.upsert(
            Txn("t1", "acc-1", august, monthKeyOf(august), -1_243_000, 980, "x",
                5812, 5812, false, restaurants, false, "{}"),
        )
        val telegram = FakeTelegramClient()
        runBlocking { handler(db, telegram).handle(message("/status")) }

        val text = telegram.sent.single().text
        assertTrue(text.contains(UkCopy.monthLabel("2026-08")), text)
        assertTrue(text.contains("🍔 Restaurants"), text)
        assertTrue(text.contains("12 430 ₴"), text)
        assertTrue(text.contains("82%"), text)
    }

    /**
     * /status is read on a phone in a couple of seconds. Listing categories in creation
     * order buried the 697 831 ₴ line below several that cost nothing, and a category at
     * 0 ₴ says only that it exists — which the categories page already says. Ordering
     * matches the dashboard so the two can never tell different stories.
     */
    @Test
    fun `status ranks categories by spend and omits the ones with none`() = withTestDb { db ->
        val settings = settings(db)
        settings.set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val transactions = TransactionRepository(db)
        // Created smallest first, so creation order and spend order disagree.
        val small = categories.create("Small", "🐜", 0, 80)
        categories.create("Empty", "📚", 0, 80)
        val big = categories.create("Big", "🐘", 0, 80)
        transactions.upsert(
            Txn("t1", "acc-1", august, monthKeyOf(august), -100_000, 980, "x",
                5812, 5812, false, small, false, "{}"),
        )
        transactions.upsert(
            Txn("t2", "acc-1", august, monthKeyOf(august), -900_000, 980, "y",
                5411, 5411, false, big, false, "{}"),
        )
        val telegram = FakeTelegramClient()
        runBlocking { handler(db, telegram).handle(message("/status")) }

        val text = telegram.sent.single().text
        assertTrue(!text.contains("Empty"), "a category with no spending must not be listed: $text")
        val bigIdx = text.indexOf("🐘 Big")
        val smallIdx = text.indexOf("🐜 Small")
        assertTrue(bigIdx >= 0 && smallIdx >= 0, text)
        assertTrue(bigIdx < smallIdx, "Big (9 000 ₴) must rank above Small (1 000 ₴): $text")
    }

    /**
     * Finding 2 of the 2026-09 branch review: renderStatus built its lines straight from
     * summary.categories, filtered only on `enabled`/`spentMinor != 0` — never on
     * `countsAsSpending`. A self-transfer category the owner marked not-counted still
     * showed up as the largest, top-ranked line, with a ⚠️/🔔 mark computed from a limit
     * Notifier was explicitly told never to alert on, while the header above (which does
     * go through totalSpentMinor) already excluded it — so the header and its own biggest
     * line disagreed by however much that category spent.
     */
    @Test
    fun `status excludes a not-counted category from its header but still shows the line, unmarked`() = withTestDb { db ->
        val settings = settings(db)
        settings.set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val transactions = TransactionRepository(db)
        val groceries = categories.create("Groceries", "🛒", 0, 80)
        val selfTransfers = categories.create("Own accounts", "🔁", 1_000_000, 80, countsAsSpending = false)
        transactions.upsert(
            Txn("t1", "acc-1", august, monthKeyOf(august), -50_000, 980, "x",
                5411, 5411, false, groceries, false, "{}"),
        )
        // Far past its own limit, so row.status would be EXCEEDED if it were ever computed.
        transactions.upsert(
            Txn("t2", "acc-1", august, monthKeyOf(august), -5_000_000, 980, "y",
                4829, 4829, false, selfTransfers, false, "{}"),
        )
        val telegram = FakeTelegramClient()
        runBlocking { handler(db, telegram).handle(message("/status")) }

        val text = telegram.sent.single().text
        // Header total must match the honest, countsAsSpending-filtered figure (500 ₴), not
        // the sum of every line beneath it (50 000 ₴ + 500 ₴).
        assertTrue(text.contains(UkCopy.statusHeader(UkCopy.monthLabel("2026-08"), "500 ₴")), text)
        assertTrue(text.contains("🔁 Own accounts"), text)
        assertTrue(!text.contains("⚠️"), "a category Notifier never alerts on must not show a threshold mark: $text")
        assertTrue(!text.contains("🔔"), text)
        assertTrue(text.contains(UkCopy.notCountedPill), "the not-counted line must say so, or it reads as an unexplained mismatch with the header: $text")
    }

    @Test
    fun `help lists the commands`() = withTestDb { db ->
        settings(db).set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        val telegram = FakeTelegramClient()
        runBlocking { handler(db, telegram).handle(message("/help")) }
        val text = telegram.sent.single().text
        assertTrue(text.contains("/status"))
        assertTrue(text.contains("/help"))
    }

    /**
     * A private chat only ever contains the two of us, so answering anything unrecognised
     * with the command list is helpful. A group is a room full of other people's
     * conversation — and Telegram delivers every message starting with a slash to the bot
     * even with privacy mode on, so the same branch would answer other bots' commands with
     * our help text, forever.
     */
    @Test
    fun `unrecognised text in a group gets no reply`() = withTestDb { db ->
        settings(db).set(SettingKeys.TELEGRAM_CHAT_ID, "-1001234567890")
        val telegram = FakeTelegramClient()
        runBlocking { handler(db, telegram).handle(groupMessage("/karma@some_other_bot")) }
        assertTrue(telegram.sent.isEmpty(), "the bot must stay quiet in a group unless spoken to: ${telegram.sent}")
    }

    /**
     * The bot's own name is cached — it is read for every addressed command — but the token
     * behind it is editable from Settings at any time. Caching the name for the process's
     * lifetime meant that pointing the app at a different bot left it dropping every
     * `/cmd@the_new_name` as "addressed to someone else", mute in the group until a restart.
     */
    @Test
    fun `rotating the token to a different bot does not leave it mute`() = withTestDb { db ->
        val settings = settings(db)
        settings.set(SettingKeys.TELEGRAM_CHAT_ID, "-1001234567890")
        settings.set(SettingKeys.TELEGRAM_TOKEN, "token-for-the-old-bot", encrypted = true)
        val telegram = FakeTelegramClient()
        val handler = handler(db, telegram)

        runBlocking {
            handler.handle(groupMessage("/help@test_bot"))
            settings.set(SettingKeys.TELEGRAM_TOKEN, "token-for-the-new-bot", encrypted = true)
            telegram.username = "the_new_bot"
            handler.handle(groupMessage("/help@the_new_bot"))
        }

        assertEquals(2, telegram.sent.size, "the new bot must answer its own name: ${telegram.sent}")
    }

    /**
     * "channel" is neither a group nor a private chat, and the private branch is the one
     * that answers anything it does not recognise. Unreachable today — setWebhook asks for
     * message and callback_query only, and a channel's posts arrive as channel_post — but
     * the quiet branch is the right home for every type that is not demonstrably private.
     */
    @Test
    fun `a chat type that is not private gets the quiet treatment`() = withTestDb { db ->
        settings(db).set(SettingKeys.TELEGRAM_CHAT_ID, "-100500")
        val telegram = FakeTelegramClient()
        runBlocking { handler(db, telegram).handle(message("оголошення", chatId = -100500, type = "channel")) }
        assertTrue(telegram.sent.isEmpty(), "only a private chat gets the help text: ${telegram.sent}")
    }

    @Test
    fun `help is answered in a group`() = withTestDb { db ->
        settings(db).set(SettingKeys.TELEGRAM_CHAT_ID, "-1001234567890")
        val telegram = FakeTelegramClient()
        runBlocking { handler(db, telegram).handle(groupMessage("/help@test_bot")) }
        assertTrue(telegram.sent.single().text.contains("/status"), telegram.sent.single().text)
    }

    @Test
    fun `unrecognised text in a private chat still gets the help text`() = withTestDb { db ->
        settings(db).set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        val telegram = FakeTelegramClient()
        runBlocking { handler(db, telegram).handle(message("привіт")) }
        assertTrue(telegram.sent.single().text.contains("/status"), telegram.sent.single().text)
    }

    /**
     * Telegram changes a group's chat id when it upgrades to a supergroup, which happens on
     * its own — adding enough members, or turning on visible history. The id we paired with
     * stops accepting sendMessage from that moment, so without this the bot goes silent for
     * good and the only symptom is alerts that never arrive.
     */
    @Test
    fun `a supergroup upgrade re-points the paired chat`() = withTestDb { db ->
        val settings = settings(db)
        settings.set(SettingKeys.TELEGRAM_CHAT_ID, "-12345")
        val update = TgUpdate(
            updateId = 9,
            message = TgMessage(7, TgChat(-12345, "group"), migrateToChatId = -1001234567890),
        )
        val telegram = FakeTelegramClient()
        runBlocking { handler(db, telegram).handle(update) }

        assertEquals("-1001234567890", settings.get(SettingKeys.TELEGRAM_CHAT_ID))
        assertTrue(telegram.sent.isEmpty(), "the same room in a new shape needs no announcement: ${telegram.sent}")
    }

    @Test
    fun `an upgrade notice from another group does not move the paired chat`() = withTestDb { db ->
        val settings = settings(db)
        settings.set(SettingKeys.TELEGRAM_CHAT_ID, "-12345")
        val update = TgUpdate(
            updateId = 9,
            message = TgMessage(7, TgChat(-99999, "group"), migrateToChatId = -1001234567890),
        )
        runBlocking { handler(db, FakeTelegramClient()).handle(update) }
        assertEquals("-12345", settings.get(SettingKeys.TELEGRAM_CHAT_ID))
    }

    @Test
    fun `callback queries are delegated to the callback handler`() = withTestDb { db ->
        settings(db).set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        val seen = mutableListOf<String>()
        val callbacks = object : CallbackHandler {
            override suspend fun handle(query: TgCallbackQuery): Boolean {
                seen += query.data.orEmpty()
                return true
            }
        }
        val update = TgUpdate(
            updateId = 2,
            callbackQuery = TgCallbackQuery(
                id = "cb1", data = "mcc:5712:cat:3",
                message = TgMessage(messageId = 55, chat = TgChat(12345)),
            ),
        )
        runBlocking { handler(db, FakeTelegramClient(), callbacks).handle(update) }
        assertEquals(listOf("mcc:5712:cat:3"), seen)
    }

    @Test
    fun `an unrecognised callback from the paired chat is still answered`() = withTestDb { db ->
        settings(db).set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        val telegram = FakeTelegramClient()
        val callbacks = object : CallbackHandler {
            override suspend fun handle(query: TgCallbackQuery) = false
        }
        val update = TgUpdate(
            updateId = 4,
            callbackQuery = TgCallbackQuery(
                id = "cb1", data = "garbage",
                message = TgMessage(messageId = 55, chat = TgChat(12345)),
            ),
        )
        runBlocking { handler(db, telegram, callbacks).handle(update) }
        assertEquals(listOf("cb1"), telegram.answered)
    }

    @Test
    fun `a callback with no message is answered as stale, not left spinning`() = withTestDb { db ->
        settings(db).set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        val telegram = FakeTelegramClient()
        var called = false
        val callbacks = object : CallbackHandler {
            override suspend fun handle(query: TgCallbackQuery): Boolean { called = true; return true }
        }
        val update = TgUpdate(
            updateId = 5,
            callbackQuery = TgCallbackQuery(id = "cb1", data = "mcc:5712:cat:3", message = null),
        )
        runBlocking { handler(db, telegram, callbacks).handle(update) }
        assertEquals(listOf("cb1"), telegram.answered)
        assertTrue(!called, "we cannot authenticate a message-less callback, so it must not be delegated")
    }

    @Test
    fun `a reply to an open question is a category name, not a command`() = withTestDb { db ->
        val fixture = fixture(db)
        var seen: TgMessage? = null
        val handler = TelegramUpdateHandler(
            fixture.settings, fixture.telegram, fixture.budget, fixture.callbacks,
            nameReplies = { message -> seen = message; true },
        )
        fixture.settings.set(SettingKeys.TELEGRAM_CHAT_ID, "42")

        runBlocking {
            handler.handle(
                TgUpdate(1, message = TgMessage(2, TgChat(42), "Логопед", replyToMessage = TgMessage(1, TgChat(42)))),
            )
        }

        assertEquals("Логопед", seen?.text)
        assertTrue(fixture.telegram.sent.isEmpty(), "the help text must not be sent for an answer")
    }

    @Test
    fun `a callback from an unknown chat is ignored`() = withTestDb { db ->
        settings(db).set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        var called = false
        val callbacks = object : CallbackHandler {
            override suspend fun handle(query: TgCallbackQuery): Boolean { called = true; return true }
        }
        val update = TgUpdate(
            updateId = 3,
            callbackQuery = TgCallbackQuery(
                id = "cb1", data = "mcc:5712:cat:3",
                message = TgMessage(messageId = 55, chat = TgChat(99999)),
            ),
        )
        runBlocking { handler(db, FakeTelegramClient(), callbacks).handle(update) }
        assertTrue(!called)
    }
}
