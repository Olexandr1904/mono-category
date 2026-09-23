package app.notify

import app.budget.BudgetService
import app.budget.CategoryRepository
import app.budget.ConduitMccRepository
import app.budget.CounterpartyRepository
import app.budget.formatMinor
import app.budget.TransactionRepository
import app.db.Crypto
import app.db.SettingKeys
import app.db.SettingsRepository
import app.i18n.UkCopy
import app.ingest.AccountRepository
import app.ingest.IngestService
import app.withTestDb
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LimitPromptServiceTest {

    private val chat = "-100500"

    private class Wiring(db: Database) {
        val settings = SettingsRepository(db, Crypto(ByteArray(32) { it.toByte() }))
        val telegram = FakeTelegramClient()
        val conduit = ConduitMccRepository(db)
        val categories = CategoryRepository(db, conduit)
        val transactions = TransactionRepository(db)
        val prompts = LimitPromptRepository(db)
        val ingest = IngestService(
            transactions, categories,
            BudgetService(categories, transactions),
            Notifier(telegram, settings, NotificationEventRepository(db)),
            CounterpartyRepository(db), conduit, AccountRepository(db),
        )
        val service = LimitPromptService(prompts, categories, settings, telegram) { ingest }
    }

    private fun wire(db: Database) = Wiring(db).also {
        it.settings.set(SettingKeys.TELEGRAM_CHAT_ID, chat)
    }

    private fun press(data: String, username: String? = "olya") = TgCallbackQuery(
        id = "cb1",
        data = data,
        message = TgMessage(messageId = 50L, chat = TgChat(-100500, "supergroup")),
        from = TgUser(id = 7, username = username, firstName = "Оля"),
    )

    private fun reply(text: String, replyTo: Long) = TgMessage(
        messageId = 60L,
        chat = TgChat(-100500, "supergroup"),
        text = text,
        replyToMessage = TgMessage(messageId = replyTo, chat = TgChat(-100500, "supergroup")),
        from = TgUser(id = 7, username = "olya", firstName = "Оля"),
    )

    @Test
    fun `the keyboard shows every category with its current limit`() = withTestDb { db ->
        val w = wire(db)
        w.categories.create("Продукти", "🛒", 800_000L, 80)
        w.categories.create("Подарунки", "🎁", 0L, 80)
        runBlocking { w.service.start(chat) }

        val faces = w.telegram.sent.single().keyboard!!.flatten().map { it.text }
        assertTrue(faces.any { it.contains("Продукти") && it.contains(formatMinor(800_000L)) }, faces.toString())
        assertTrue(faces.any { it.contains("Подарунки") && it.contains("без ліміту") }, faces.toString())
        assertTrue(faces.last().contains(UkCopy.limitCancelButton), faces.toString())
    }

    @Test
    fun `pressing a category asks for the amount, aimed at whoever pressed`() = withTestDb { db ->
        val w = wire(db)
        val id = w.categories.create("Продукти", "🛒", 800_000L, 80)
        runBlocking {
            w.service.start(chat)
            w.service.handle(press(encodeLimitCallback(id)))
        }

        val question = w.telegram.sent.last()
        assertTrue(question.text.startsWith("@olya,"), question.text)
        assertTrue(question.selective)
        assertTrue(question.forceReply)
        assertTrue(question.text.contains(formatMinor(800_000L)), question.text)
    }

    @Test
    fun `a valid amount is written through the ingest service and confirmed with who did it`() = withTestDb { db ->
        val w = wire(db)
        val id = w.categories.create("Продукти", "🛒", 800_000L, 80)
        runBlocking {
            w.service.start(chat)
            w.service.handle(press(encodeLimitCallback(id)))
            val asked = w.telegram.sent.last().messageId
            assertTrue(w.service.handleAmountReply(reply("8500", asked)))
        }

        assertEquals(850_000L, w.categories.byId(id)?.monthlyLimitMinor)
        val confirmation = w.telegram.edits.last().second
        assertTrue(
            confirmation.contains(formatMinor(800_000L)) && confirmation.contains(formatMinor(850_000L)),
            confirmation,
        )
        assertTrue(confirmation.contains("olya"), confirmation)
    }

    @Test
    fun `zero removes the limit`() = withTestDb { db ->
        val w = wire(db)
        val id = w.categories.create("Продукти", "🛒", 800_000L, 80)
        runBlocking {
            w.service.start(chat)
            w.service.handle(press(encodeLimitCallback(id)))
            w.service.handleAmountReply(reply("0", w.telegram.sent.last().messageId))
        }
        assertEquals(0L, w.categories.byId(id)?.monthlyLimitMinor)
        assertTrue(w.telegram.edits.last().second.contains(UkCopy.limitCleared("", "").trim()))
    }

    /**
     * The re-ask must itself open a reply box. Under privacy mode a plain complaint would
     * leave the next attempt typed into a room the bot cannot hear.
     */
    @Test
    fun `a rejected amount re-asks and the superseded question stops answering`() = withTestDb { db ->
        val w = wire(db)
        val id = w.categories.create("Продукти", "🛒", 800_000L, 80)
        runBlocking {
            w.service.start(chat)
            w.service.handle(press(encodeLimitCallback(id)))
            val first = w.telegram.sent.last().messageId
            assertTrue(w.service.handleAmountReply(reply("вісім тисяч", first)))

            val reAsked = w.telegram.sent.last()
            assertTrue(reAsked.text.contains(UkCopy.limitAmountNotANumber), reAsked.text)
            assertTrue(reAsked.forceReply, "a plain message would be invisible in a group")
            assertTrue(reAsked.selective)

            assertFalse(w.service.handleAmountReply(reply("8500", first)), "the old question is spent")
            assertEquals(800_000L, w.categories.byId(id)?.monthlyLimitMinor)

            assertTrue(w.service.handleAmountReply(reply("8500", reAsked.messageId)))
        }
        assertEquals(850_000L, w.categories.byId(id)?.monthlyLimitMinor)
    }

    @Test
    fun `a negative amount says so rather than being accepted as no limit`() = withTestDb { db ->
        val w = wire(db)
        val id = w.categories.create("Продукти", "🛒", 800_000L, 80)
        runBlocking {
            w.service.start(chat)
            w.service.handle(press(encodeLimitCallback(id)))
            w.service.handleAmountReply(reply("-500", w.telegram.sent.last().messageId))
        }
        assertEquals(800_000L, w.categories.byId(id)?.monthlyLimitMinor)
        assertTrue(w.telegram.sent.last().text.contains(UkCopy.limitAmountNegative))
    }

    @Test
    fun `an amount past the ceiling names the ceiling`() = withTestDb { db ->
        val w = wire(db)
        val id = w.categories.create("Продукти", "🛒", 800_000L, 80)
        runBlocking {
            w.service.start(chat)
            w.service.handle(press(encodeLimitCallback(id)))
            w.service.handleAmountReply(reply("99999999999999999", w.telegram.sent.last().messageId))
        }
        assertEquals(800_000L, w.categories.byId(id)?.monthlyLimitMinor)
        assertTrue(w.telegram.sent.last().text.contains(formatMinor(MAX_LIMIT_MINOR)), w.telegram.sent.last().text)
    }

    @Test
    fun `cancel closes the dialog and leaves no pressable keyboard`() = withTestDb { db ->
        val w = wire(db)
        w.categories.create("Продукти", "🛒", 800_000L, 80)
        runBlocking {
            w.service.start(chat)
            assertTrue(w.service.handle(press(LIMIT_CANCEL_DATA)))
        }
        assertEquals(UkCopy.limitCancelled, w.telegram.edits.last().second)
        assertTrue(w.prompts.listOpen().isEmpty())
    }

    /**
     * The keyboard stays on screen after a category is picked, so Cancel is still there to
     * press. Leaving the question open would let a reply hours later change a limit the
     * owner had just cancelled.
     */
    @Test
    fun `cancelling after picking a category closes the question already opened`() = withTestDb { db ->
        val w = wire(db)
        val id = w.categories.create("Продукти", "🛒", 800_000L, 80)
        runBlocking {
            w.service.start(chat)
            w.service.handle(press(encodeLimitCallback(id)))
            val asked = w.telegram.sent.last().messageId
            w.service.handle(press(LIMIT_CANCEL_DATA))

            assertTrue(w.prompts.listOpen().isEmpty())
            assertFalse(w.service.handleAmountReply(reply("8500", asked)))
        }
        assertEquals(800_000L, w.categories.byId(id)?.monthlyLimitMinor)
    }

    /**
     * The keyboard survives a pick, so a second category can be pressed on it. Both dialogs
     * would then own the same keyboard message and write their outcome over each other's —
     * and the abandoned one would still be answerable hours later. Picking again replaces
     * the question rather than adding one.
     */
    @Test
    fun `picking a second category abandons the first question instead of running both`() = withTestDb { db ->
        val w = wire(db)
        val food = w.categories.create("Продукти", "🛒", 800_000L, 80)
        val car = w.categories.create("Авто", "🚗", 400_000L, 80)
        runBlocking {
            w.service.start(chat)
            w.service.handle(press(encodeLimitCallback(food)))
            val firstAsked = w.telegram.sent.last().messageId
            w.service.handle(press(encodeLimitCallback(car)))

            assertEquals(1, w.prompts.listOpen().size, "only the question on screen may be open")
            assertFalse(w.service.handleAmountReply(reply("9999", firstAsked)), "the abandoned question is spent")
        }
        assertEquals(800_000L, w.categories.byId(food)?.monthlyLimitMinor)
    }

    @Test
    fun `a category deleted mid-dialog is reported, not thrown`() = withTestDb { db ->
        val w = wire(db)
        val id = w.categories.create("Продукти", "🛒", 800_000L, 80)
        runBlocking {
            w.service.start(chat)
            w.service.handle(press(encodeLimitCallback(id)))
            val asked = w.telegram.sent.last().messageId
            w.ingest.deleteCategory(id)
            assertTrue(w.service.handleAmountReply(reply("8500", asked)))
        }
        assertTrue(w.telegram.sent.last().text.contains(UkCopy.limitCategoryGone))
        assertTrue(w.prompts.listOpen().isEmpty())
    }

    /**
     * The opposite of what MccPromptService does with a move, and deliberately so: an
     * unknown code is still worth asking about wherever the bot now lives, but a half-typed
     * limit dragged into a chat that never started it is an invitation to finish someone
     * else's sentence.
     */
    @Test
    fun `a chat move closes open dialogs and strips the keyboard left behind`() = withTestDb { db ->
        val w = wire(db)
        val id = w.categories.create("Продукти", "🛒", 800_000L, 80)
        runBlocking {
            w.service.start(chat)
            w.service.handle(press(encodeLimitCallback(id)))
            val asked = w.telegram.sent.last().messageId

            w.service.onChatMoved(from = chat, to = "-100777")

            assertTrue(w.prompts.listOpen().isEmpty(), "a half-finished dialog is not worth dragging along")
            assertEquals(UkCopy.limitDialogMovedAway, w.telegram.edits.last().second)
            assertFalse(w.service.handleAmountReply(reply("8500", asked)))
        }
        assertEquals(800_000L, w.categories.byId(id)?.monthlyLimitMinor)
    }

    @Test
    fun `an MCC callback is not ours to handle`() = withTestDb { db ->
        val w = wire(db)
        val handled = runBlocking {
            w.service.handle(press(encodeCallback(PromptRef.ByMcc(5411), PromptAction.Skip)))
        }
        assertFalse(handled, "the composite must be free to pass this to MccPromptService")
    }

    @Test
    fun `a reply to something that is not our question is not ours`() = withTestDb { db ->
        val w = wire(db)
        assertFalse(runBlocking { w.service.handleAmountReply(reply("8500", 999L)) })
    }

    @Test
    fun `with no categories there is nothing to offer`() = withTestDb { db ->
        val w = wire(db)
        runBlocking { w.service.start(chat) }
        assertEquals(UkCopy.limitNoCategories, w.telegram.sent.single().text)
        assertEquals(null, w.telegram.sent.single().keyboard)
    }
}
