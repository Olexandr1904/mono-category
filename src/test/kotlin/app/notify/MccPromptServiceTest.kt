package app.notify

import app.budget.BudgetService
import app.budget.CategoryRepository
import app.budget.ConduitMccRepository
import app.budget.CounterpartyRepository
import app.budget.KYIV
import app.budget.TransactionRepository
import app.budget.Txn
import app.budget.formatDay
import app.budget.formatMinor
import app.budget.monthKeyOf
import app.db.Crypto
import app.db.SettingKeys
import app.db.SettingsRepository
import app.i18n.UkCopy
import app.ingest.AccountRepository
import app.ingest.IngestService
import app.withTestDb
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MccPromptServiceTest {

    private val august = 1_787_011_200L
    private val clock = Clock.fixed(Instant.ofEpochSecond(august), KYIV)

    private class Wiring(
        val service: MccPromptService,
        val ingest: IngestService,
        val categories: CategoryRepository,
        val transactions: TransactionRepository,
        val telegram: FakeTelegramClient,
        val prompts: MccPromptRepository,
    )

    private fun wire(db: Database, paired: Boolean = true, chatId: String = "12345"): Wiring {
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val transactions = TransactionRepository(db)
        val settings = SettingsRepository(db, Crypto(ByteArray(32) { it.toByte() }))
        if (paired) settings.set(SettingKeys.TELEGRAM_CHAT_ID, chatId)
        val telegram = FakeTelegramClient()
        val prompts = MccPromptRepository(db)
        val conduit = ConduitMccRepository(db)
        val counterparties = CounterpartyRepository(db)
        lateinit var ingest: IngestService
        val service = MccPromptService(
            prompts, categories, settings, telegram, conduit, transactions, counterparties,
        ) { ingest }
        ingest = IngestService(
            transactions, categories,
            BudgetService(categories, transactions),
            Notifier(telegram, settings, NotificationEventRepository(db)),
            counterparties, conduit, AccountRepository(db),
            service, clock,
        )
        return Wiring(service, ingest, categories, transactions, telegram, prompts)
    }

    private fun txn(id: String, mcc: Int = 5712, amount: Long = -243_000) = Txn(
        id, "acc-1", august, monthKeyOf(august), amount, 980, "Epicentr",
        mcc, mcc, false, null, false, "{}",
    )

    /**
     * The bot now lives in a family group, and a force_reply with no `selective` opens the
     * reply keyboard for every member of it. Telegram aims a selective force_reply at the
     * users mentioned in the text, so the mention is what does the targeting — `selective`
     * on its own would target nobody at all.
     */
    @Test
    fun `in a group the new-category question is aimed at whoever pressed the button`() = withTestDb { db ->
        val w = wire(db, chatId = "-100500")
        w.categories.create("Home", "🏠", 1_000_000, 80)
        runBlocking {
            w.ingest.ingest(listOf(txn("t1")))
            w.service.handle(
                TgCallbackQuery(
                    id = "cb1",
                    data = encodeCallback(PromptRef.ByMcc(5712), PromptAction.NewCategory),
                    message = TgMessage(100, TgChat(-100500, "supergroup")),
                    from = TgUser(id = 7, username = "olya", firstName = "Оля"),
                ),
            )
        }
        val ask = w.telegram.sent.last()
        assertTrue(ask.text.startsWith("@olya,"), ask.text)
        assertTrue(ask.selective, "a group question must not open the keyboard for everyone")
        assertTrue(ask.forceReply)
    }

    @Test
    fun `a presser with no username gets no force reply rather than one aimed at the room`() = withTestDb { db ->
        val w = wire(db, chatId = "-100500")
        w.categories.create("Home", "🏠", 1_000_000, 80)
        runBlocking {
            w.ingest.ingest(listOf(txn("t1")))
            w.service.handle(
                TgCallbackQuery(
                    id = "cb1",
                    data = encodeCallback(PromptRef.ByMcc(5712), PromptAction.NewCategory),
                    message = TgMessage(100, TgChat(-100500, "supergroup")),
                    from = TgUser(id = 7, username = null, firstName = "Оля"),
                ),
            )
        }
        val ask = w.telegram.sent.last()
        assertFalse(ask.forceReply, "nobody can be mentioned, so nobody's keyboard is opened")
        assertFalse(ask.selective)
    }

    @Test
    fun `in a private chat the question is a plain force reply with no mention`() = withTestDb { db ->
        val w = wire(db)
        w.categories.create("Home", "🏠", 1_000_000, 80)
        runBlocking {
            w.ingest.ingest(listOf(txn("t1")))
            w.service.handle(
                TgCallbackQuery(
                    id = "cb1",
                    data = encodeCallback(PromptRef.ByMcc(5712), PromptAction.NewCategory),
                    message = TgMessage(100, TgChat(12345, "private")),
                    from = TgUser(id = 7, username = "olya", firstName = "Оля"),
                ),
            )
        }
        val ask = w.telegram.sent.last()
        assertTrue(ask.forceReply)
        assertFalse(ask.selective, "there is only one person here to target")
        assertFalse(ask.text.contains("@olya"), ask.text)
    }

    /**
     * A question re-homed while it was waiting for a typed name is re-asked with buttons,
     * so its old reply target belongs to the chat the bot just left. Message ids restart
     * low in a fresh group, so leaving it would let an unrelated reply there be read as
     * the answer to this question.
     */
    @Test
    fun `a move drops the reply target left behind in the old chat`() = withTestDb { db ->
        val w = wire(db)
        w.categories.create("Home", "🏠", 1_000_000, 80)
        runBlocking {
            w.ingest.ingest(listOf(txn("t1")))
            w.service.handle(
                TgCallbackQuery(
                    id = "cb1",
                    data = encodeCallback(PromptRef.ByMcc(5712), PromptAction.NewCategory),
                    message = TgMessage(100, TgChat(12345, "private")),
                    from = TgUser(id = 7, username = "olya", firstName = "Оля"),
                ),
            )
            val askedInOldChat = w.telegram.sent.last().messageId

            w.service.onChatMoved(from = "12345", to = "-100777")

            val handled = w.service.handleNameReply(
                TgMessage(
                    messageId = 99, chat = TgChat(-100777, "supergroup"), text = "Випадкова відповідь",
                    replyToMessage = TgMessage(askedInOldChat, TgChat(-100777, "supergroup")),
                ),
            )
            assertFalse(handled, "a reply quoting that id must not answer the re-homed question")
        }
        assertTrue(w.categories.list().none { it.name == "Випадкова відповідь" }, w.categories.list().toString())
    }

    @Test
    fun `callback data round-trips and stays inside 64 bytes`() {
        assertEquals("mcc:5712:cat:3", encodeCallback(PromptRef.ByMcc(5712), PromptAction.Choose(3)))
        assertEquals("mcc:5712:skip", encodeCallback(PromptRef.ByMcc(5712), PromptAction.Skip))
        assertEquals(
            PromptCallback(PromptRef.ByMcc(5712), PromptAction.Choose(3)),
            decodeCallback("mcc:5712:cat:3"),
        )
        assertEquals(PromptCallback(PromptRef.ByMcc(5712), PromptAction.Skip), decodeCallback("mcc:5712:skip"))
        assertNull(decodeCallback("garbage"))
        assertTrue(
            encodeCallback(PromptRef.ByMcc(9999), PromptAction.Choose(Long.MAX_VALUE)).toByteArray().size <= 64,
        )
    }

    @Test
    fun `an unknown mcc produces one message with a button per category`() = withTestDb { db ->
        val w = wire(db)
        w.categories.create("Home", "🏠", 1_000_000, 80)
        w.categories.create("Auto", "🚗", 1_000_000, 80)
        runBlocking { w.ingest.ingest(listOf(txn("t1"))) }

        assertEquals(1, w.telegram.sent.size)
        val text = w.telegram.sent.single().text
        assertTrue(text.contains("5712"), text)
        assertTrue(text.contains("Epicentr"), text)
        assertTrue(text.contains("2 430 ₴"), text)

        val buttons = w.telegram.lastKeyboard!!.flatten()
        assertEquals(4, buttons.size) // two categories, New category, Skip
        assertTrue(buttons.any { it.callbackData == "mcc:5712:skip" })
        assertTrue(
            buttons.any { decodeCallback(it.callbackData)?.action == PromptAction.NewCategory },
            "a New category button must be offered alongside the categories",
        )
    }

    /**
     * Moving the bot to a shared group used to strand every unanswered question in the chat
     * it left: the buttons there are rejected without even answering the callback (the
     * spinner just hangs), and `create` refuses to ask again while a question is open, so
     * that MCC was never asked anywhere again and its purchases stayed uncategorised with
     * nothing to show for it. Questions travel with the bot instead.
     */
    @Test
    fun `an open question is asked again in the chat the bot moved to`() = withTestDb { db ->
        val w = wire(db)
        w.categories.create("Home", "🏠", 1_000_000, 80)
        runBlocking { w.ingest.ingest(listOf(txn("t1"))) }
        val asked = w.telegram.sent.single()

        runBlocking { w.service.onChatMoved("12345", "-1001234567890") }

        val reasked = w.telegram.sent.last()
        assertEquals("-1001234567890", reasked.chatId, "the question must follow the alerts")
        assertTrue(reasked.text.contains("5712"), reasked.text)
        assertTrue(
            reasked.keyboard!!.flatten().any { it.callbackData == "mcc:5712:skip" },
            "it is only a question if its buttons came with it",
        )
        assertEquals(
            reasked.messageId, w.prompts.openFor(5712)?.messageId,
            "the open question must point at the message that is actually on screen",
        )
        assertEquals(
            listOf(asked.messageId to UkCopy.promptMovedAway), w.telegram.edits,
            "the message left behind must lose its buttons and say where the question went",
        )
    }

    @Test
    fun `a question whose transaction is gone is closed rather than moved`() = withTestDb { db ->
        val w = wire(db)
        w.categories.create("Home", "🏠", 1_000_000, 80)
        val orphan = w.prompts.create(PromptKind.MCC, 5712, "no-such-transaction")
        assertNotNull(orphan)

        runBlocking { w.service.onChatMoved("12345", "-1001234567890") }

        assertNull(w.prompts.openFor(5712), "a question nothing can be asked about must not block the next one")
        assertTrue(w.telegram.sent.isEmpty(), "and nothing is asked in the new chat: ${w.telegram.sent}")
    }

    /**
     * `create` refuses to open a second question for a code that already has one, so a
     * re-home that fails silently would be the orphaning bug again in a new place: the
     * question stays open, its message sits in a chat the bot no longer talks to, and that
     * MCC is never asked anywhere again. Closing it is what [onUnknownMcc] already does
     * when its own send fails — the next purchase with that code asks afresh.
     */
    @Test
    fun `a question that cannot be re-asked is closed, not left pointing at the old chat`() = withTestDb { db ->
        val w = wire(db)
        w.categories.create("Home", "🏠", 1_000_000, 80)
        runBlocking { w.ingest.ingest(listOf(txn("t1"))) }
        assertNotNull(w.prompts.openFor(5712))

        w.telegram.failNextSends(true)
        runBlocking { w.service.onChatMoved("12345", "-1001234567890") }

        assertNull(w.prompts.openFor(5712), "an unasked question must not block the next one")
    }

    @Test
    fun `several purchases with the same unknown mcc ask only once`() = withTestDb { db ->
        val w = wire(db)
        w.categories.create("Home", "🏠", 1_000_000, 80)
        runBlocking {
            w.ingest.ingest(listOf(txn("t1")))
            w.ingest.ingest(listOf(txn("t2")))
            w.ingest.ingest(listOf(txn("t3")))
        }
        assertEquals(1, w.telegram.sent.size)
    }

    @Test
    fun `an open mcc question and an open transfer question sharing a code do not interfere`() = withTestDb { db ->
        // V3 made "one open question" kind-scoped in the schema (partial unique indexes per
        // kind), but openFor/resolve were not updated to match: undo the conduit flag while
        // a transfer question is open, and the next same-coded MCC question would find (via
        // openFor) the unrelated transfer prompt and never get created; answering the MCC
        // callback would then resolve() every open prompt sharing the code, discarding a
        // transfer question the owner never answered.
        val prompts = MccPromptRepository(db)
        val transferId = prompts.create(PromptKind.TRANSFER, 4829, "t1")
        assertNotNull(transferId)

        val mccId = prompts.create(PromptKind.MCC, 4829, "t2")
        assertNotNull(mccId, "an open transfer question must not block a separate mcc question sharing the code")

        assertNotNull(prompts.openById(transferId!!))
        assertNotNull(prompts.openFor(4829))

        assertTrue(prompts.resolve(4829))
        assertNotNull(prompts.openById(transferId), "resolve(mcc) must not close a transfer question sharing the code")
        assertNull(prompts.openFor(4829))
    }

    @Test
    fun `pressing a category binds the mcc and rewrites history`() = withTestDb { db ->
        val w = wire(db)
        val home = w.categories.create("Home", "🏠", 1_000_000, 80)
        runBlocking {
            w.ingest.ingest(listOf(txn("t1")))
            w.ingest.ingest(listOf(txn("t2")))
            w.service.handle(
                TgCallbackQuery(
                    "cb1",
                    encodeCallback(PromptRef.ByMcc(5712), PromptAction.Choose(home)),
                    TgMessage(100, TgChat(12345)),
                ),
            )
        }
        assertEquals(mapOf(5712 to home), w.categories.mccMapping())
        assertEquals(home, w.transactions.byId("t1")!!.categoryId)
        assertEquals(home, w.transactions.byId("t2")!!.categoryId)
        assertEquals(listOf("cb1"), w.telegram.answered)
        assertTrue(w.telegram.edits.single().second.contains("🏠 Home"), w.telegram.edits.toString())
    }

    @Test
    fun `after answering, the same mcc never asks again`() = withTestDb { db ->
        val w = wire(db)
        val home = w.categories.create("Home", "🏠", 1_000_000, 80)
        runBlocking {
            w.ingest.ingest(listOf(txn("t1")))
            w.service.handle(
                TgCallbackQuery(
                    "cb1",
                    encodeCallback(PromptRef.ByMcc(5712), PromptAction.Choose(home)),
                    TgMessage(100, TgChat(12345)),
                ),
            )
            w.ingest.ingest(listOf(txn("t9")))
        }
        assertEquals(1, w.telegram.sent.size)
    }

    @Test
    fun `pressing the same button twice is answered without a second binding`() = withTestDb { db ->
        val w = wire(db)
        val home = w.categories.create("Home", "🏠", 1_000_000, 80)
        runBlocking {
            w.ingest.ingest(listOf(txn("t1")))
            w.service.handle(
                TgCallbackQuery(
                    "cb1",
                    encodeCallback(PromptRef.ByMcc(5712), PromptAction.Choose(home)),
                    TgMessage(100, TgChat(12345)),
                ),
            )
            w.service.handle(
                TgCallbackQuery(
                    "cb2",
                    encodeCallback(PromptRef.ByMcc(5712), PromptAction.Choose(home)),
                    TgMessage(100, TgChat(12345)),
                ),
            )
        }
        assertEquals(listOf("cb1", "cb2"), w.telegram.answered)
        assertEquals(mapOf(5712 to home), w.categories.mccMapping())
    }

    @Test
    fun `skip resolves the prompt without binding anything`() = withTestDb { db ->
        val w = wire(db)
        w.categories.create("Home", "🏠", 1_000_000, 80)
        runBlocking {
            w.ingest.ingest(listOf(txn("t1")))
            w.service.handle(
                TgCallbackQuery(
                    "cb1",
                    encodeCallback(PromptRef.ByMcc(5712), PromptAction.Skip),
                    TgMessage(100, TgChat(12345)),
                ),
            )
        }
        assertTrue(w.categories.mccMapping().isEmpty())
        assertNull(w.prompts.openFor(5712))
        assertTrue(w.telegram.edits.single().second.contains(UkCopy.mccSkipped(5712)), w.telegram.edits.toString())
    }

    /**
     * Answering replaces the question's whole text, so the amount and the merchant that
     * were in it disappear with it. A chat full of "Пропущено." says nothing about what was
     * skipped — and a page of transfers answered in one sitting produces exactly that.
     * Every outcome now opens with the operation it belongs to.
     */
    @Test
    fun `a skipped question still says which purchase it was`() = withTestDb { db ->
        val w = wire(db)
        w.categories.create("Home", "🏠", 1_000_000, 80)
        runBlocking {
            w.ingest.ingest(listOf(txn("t1")))
            w.service.handle(
                TgCallbackQuery("cb1", encodeCallback(PromptRef.ByMcc(5712), PromptAction.Skip), TgMessage(100, TgChat(12345))),
            )
        }
        val edited = w.telegram.edits.single().second
        assertTrue(edited.contains(formatMinor(-243_000)), "the amount must survive the answer: $edited")
        assertTrue(edited.contains("Epicentr"), "so must the merchant: $edited")
        assertTrue(edited.contains(formatDay(august)), "and the day it happened: $edited")
        assertTrue(edited.contains(UkCopy.mccSkipped(5712)), edited)
    }

    @Test
    fun `a categorised question says which purchase it was`() = withTestDb { db ->
        val w = wire(db)
        val home = w.categories.create("Home", "🏠", 1_000_000, 80)
        runBlocking {
            w.ingest.ingest(listOf(txn("t1")))
            w.service.handle(
                TgCallbackQuery(
                    "cb1", encodeCallback(PromptRef.ByMcc(5712), PromptAction.Choose(home)), TgMessage(100, TgChat(12345)),
                ),
            )
        }
        val edited = w.telegram.edits.single().second
        assertTrue(edited.contains(formatMinor(-243_000)), edited)
        assertTrue(edited.contains("Epicentr"), edited)
        assertTrue(edited.contains(UkCopy.mccBound(5712, "🏠 Home")), edited)
    }

    /**
     * A transfer's outcome was the worst of the three: no MCC in the text either, so
     * "Пропущено." was the entire message. It has to name the recipient the question named,
     * not the bank's own description, or it identifies nothing a person would recognise.
     */
    @Test
    fun `a skipped transfer names the recipient the question named`() = withTestDb { db ->
        val w = wire(db)
        w.categories.create("Home", "🏠", 1_000_000, 80)
        ConduitMccRepository(db).replaceAll(setOf(4829))
        val transfer = transferTxn("t1", "name:петренко іван")
        runBlocking {
            w.ingest.ingest(listOf(transfer))
            val promptId = w.prompts.openForTransaction("t1")!!.id
            w.service.handle(
                TgCallbackQuery("cb1", encodeCallback(PromptRef.ById(promptId), PromptAction.Skip), TgMessage(100, TgChat(12345))),
            )
        }
        val edited = w.telegram.edits.single().second
        assertTrue(edited.contains(formatMinor(transfer.amountMinor)), "the amount must survive, signed: $edited")
        assertTrue(edited.contains("Петренко Іван"), "the recipient, not the raw description: $edited")
        assertTrue(edited.contains(UkCopy.transferSkipped), edited)
    }

    /**
     * The complaint this whole change answers was "I can't tell what was skipped and what
     * was added". The operation line fixes *which* row; this fixes *what happened to it*,
     * so a column of answers can be read without parsing the sentences.
     */
    @Test
    fun `a skipped outcome and an applied one open with different marks`() = withTestDb { db ->
        val w = wire(db)
        val home = w.categories.create("Home", "🏠", 1_000_000, 80)
        runBlocking {
            w.ingest.ingest(listOf(txn("t1", mcc = 5712), txn("t2", mcc = 5999)))
            w.service.handle(
                TgCallbackQuery("cb1", encodeCallback(PromptRef.ByMcc(5712), PromptAction.Skip), TgMessage(100, TgChat(12345))),
            )
            w.service.handle(
                TgCallbackQuery("cb2", encodeCallback(PromptRef.ByMcc(5999), PromptAction.Choose(home)), TgMessage(101, TgChat(12345))),
            )
        }
        // The outcome is the second line now; the first names the operation.
        val outcomes = w.telegram.edits.map { it.second.substringAfter('\n') }
        assertEquals(2, outcomes.size, outcomes.toString())
        assertTrue(outcomes.none { it.first().isLetter() }, "each outcome must open with a mark, not a word: $outcomes")
        assertNotEquals(
            outcomes[0].first(), outcomes[1].first(),
            "skipped and applied must not open with the same mark: $outcomes",
        )
    }

    @Test
    fun `an mcc bound in the web meanwhile is reported, not duplicated`() = withTestDb { db ->
        val w = wire(db)
        val home = w.categories.create("Home", "🏠", 1_000_000, 80)
        val auto = w.categories.create("Auto", "🚗", 1_000_000, 80)
        runBlocking {
            w.ingest.ingest(listOf(txn("t1")))
            w.categories.setMcc(auto, setOf(5712))   // bound in the web UI
            w.service.handle(
                TgCallbackQuery(
                    "cb1",
                    encodeCallback(PromptRef.ByMcc(5712), PromptAction.Choose(home)),
                    TgMessage(100, TgChat(12345)),
                ),
            )
        }
        assertEquals(mapOf(5712 to auto), w.categories.mccMapping())
        assertTrue(w.telegram.edits.single().second.contains("Auto"), w.telegram.edits.toString())
    }

    @Test
    fun `if the code becomes conduit before the mcc answer, the button reports it instead of hanging`() =
        withTestDb { db ->
            val w = wire(db)
            val home = w.categories.create("Home", "🏠", 1_000_000, 80)
            runBlocking {
                w.ingest.ingest(listOf(txn("t1")))
                // A deliberate owner action, not a race: the code becomes conduit while
                // this MCC question is still open. bindMcc's addMcc throws
                // ConduitMccException; without a case for it here, the exception used to
                // unwind out of handle() into the webhook route's blanket runCatching —
                // the button spinner hangs, the prompt stays open, nothing is edited.
                ConduitMccRepository(db).replaceAll(setOf(5712))
                val handled = w.service.handle(
                    TgCallbackQuery(
                        "cb1",
                        encodeCallback(PromptRef.ByMcc(5712), PromptAction.Choose(home)),
                        TgMessage(100, TgChat(12345)),
                    ),
                )
                assertTrue(handled)
            }
            assertTrue(w.categories.mccMapping().isEmpty(), "a conduit code must never be bound")
            assertNull(w.prompts.openFor(5712))
            assertTrue(
                w.telegram.edits.single().second.contains(UkCopy.conduitMccRejected(5712)),
                w.telegram.edits.toString(),
            )
        }

    @Test
    fun `pressing new category on an mcc question asks for a name, creates it, and binds the mcc`() =
        withTestDb { db ->
            // applyChoice was written for the transfer flow and is reused verbatim for MCC
            // prompts (both NewCategory branches funnel into it). Its
            // `transactionCategoryName != null -> transferAlreadyCategorized` guard could,
            // in principle, silently skip the bind an MCC prompt exists to perform — this
            // exercises PromptRef.ByMcc + NewCategory end to end to prove it does not.
            val w = wire(db)
            w.categories.create("Home", "🏠", 1_000_000, 80)
            runBlocking {
                w.ingest.ingest(listOf(txn("t1")))
                w.ingest.ingest(listOf(txn("t2"))) // shares the same open mcc question

                w.service.handle(
                    TgCallbackQuery(
                        "cb1",
                        encodeCallback(PromptRef.ByMcc(5712), PromptAction.NewCategory),
                        TgMessage(100, TgChat(12345)),
                    ),
                )
                val ask = w.telegram.sent.last()
                assertTrue(ask.forceReply, "the name question must open a reply box")

                val handled = w.service.handleNameReply(
                    TgMessage(
                        messageId = 99, chat = TgChat(12345), text = "Господарчі",
                        replyToMessage = TgMessage(ask.messageId, TgChat(12345)),
                    ),
                )
                assertTrue(handled)
            }
            val created = w.categories.list().single { it.name == "Господарчі" }
            assertEquals(mapOf(5712 to created.id), w.categories.mccMapping())
            assertEquals(created.id, w.transactions.byId("t1")!!.categoryId)
            // The mcc bind rewrites history, same as pressing an ordinary category button —
            // a second unrelated purchase sharing the code must move too, not just the row
            // the prompt happened to be attached to.
            assertEquals(created.id, w.transactions.byId("t2")!!.categoryId)
            assertNull(w.prompts.openFor(5712))
        }

    @Test
    fun `nothing is asked when no chat is paired`() = withTestDb { db ->
        val w = wire(db, paired = false)
        w.categories.create("Home", "🏠", 1_000_000, 80)
        runBlocking { w.ingest.ingest(listOf(txn("t1"))) }
        assertTrue(w.telegram.sent.isEmpty())
        assertNull(w.prompts.openFor(5712), "no prompt is recorded, so it will be asked after pairing")
    }

    @Test
    fun `nothing is asked when there are no categories to choose from`() = withTestDb { db ->
        val w = wire(db)
        runBlocking { w.ingest.ingest(listOf(txn("t1"))) }
        assertTrue(w.telegram.sent.isEmpty())
    }

    @Test
    fun `each transfer gets its own question even though they share one code`() = withTestDb { db ->
        val fixture = fixture(db)
        fixture.conduit.replaceAll(setOf(4829))
        fixture.categories.create("Оренда", "🏠", 0, 80)

        runBlocking {
            fixture.service.onUnknownMcc(transferTxn("t1", "name:петренко іван"))
            fixture.service.onUnknownMcc(transferTxn("t2", "name:коваленко"))
        }

        assertEquals(2, fixture.telegram.sent.size)
        assertTrue(fixture.telegram.sent.last().text.contains("2 500 ₴"), fixture.telegram.sent.last().text)
    }

    @Test
    fun `the transfer question says the answer covers this recipient, not the code`() = withTestDb { db ->
        val fixture = fixture(db)
        fixture.conduit.replaceAll(setOf(4829))
        fixture.categories.create("Оренда", "🏠", 0, 80)

        runBlocking { fixture.service.onUnknownMcc(transferTxn("t1", "name:петренко іван")) }

        assertTrue(
            fixture.telegram.sent.single().text.contains(UkCopy.transferScopeHint),
            fixture.telegram.sent.single().text,
        )
    }

    @Test
    fun `two purchases with one unknown code still produce a single question`() = withTestDb { db ->
        val fixture = fixture(db)
        fixture.categories.create("Їжа", "🍔", 0, 80)

        runBlocking {
            fixture.service.onUnknownMcc(shopTxn("s1", 5999))
            fixture.service.onUnknownMcc(shopTxn("s2", 5999))
        }

        // The conduit change must not weaken this: one open question per unknown code is
        // why a new shop does not produce five identical messages.
        assertEquals(1, fixture.telegram.sent.size)
    }

    @Test
    fun `skipping a transfer closes its question without creating a rule`() = withTestDb { db ->
        val fixture = fixture(db)
        fixture.conduit.replaceAll(setOf(4829))
        fixture.categories.create("Оренда", "🏠", 0, 80)
        runBlocking {
            fixture.ingest.ingest(listOf(transferTxn("t1", "name:петренко іван")))
            val promptId = fixture.prompts.openForTransaction("t1")!!.id
            fixture.service.handle(callbackQuery(encodeCallback(PromptRef.ById(promptId), PromptAction.Skip)))
        }
        assertNull(fixture.prompts.openForTransaction("t1"))
        assertNull(fixture.transactions.byId("t1")!!.categoryId)
        assertTrue(fixture.counterparties.list().isEmpty())
    }

    @Test
    fun `answering a transfer question with an exact key files it and remembers the recipient`() = withTestDb { db ->
        val fixture = fixture(db)
        fixture.conduit.replaceAll(setOf(4829))
        val rent = fixture.categories.create("Оренда", "🏠", 0, 80)
        runBlocking {
            fixture.ingest.ingest(listOf(ibanTransferTxn("t1")))
            val promptId = fixture.prompts.openForTransaction("t1")!!.id
            val handled = fixture.service.handle(
                callbackQuery(encodeCallback(PromptRef.ById(promptId), PromptAction.Choose(rent))),
            )
            assertTrue(handled)
        }
        assertEquals(rent, fixture.transactions.byId("t1")!!.categoryId)
        assertEquals(rent, fixture.counterparties.mapping()["iban:UA213223130000260072335660001"])
    }

    @Test
    fun `answering a transfer question with only a name key files just this row, no rule`() = withTestDb { db ->
        // Spec §5: a name is a string somebody typed, not an account — only an exact
        // source (IBAN, EDRPOU, masked card) may link silently. IBAN and EDRPOU plausibly
        // never arrive for a personal card, so a name is the ordinary case, and answering
        // one transfer must not sweep every other transfer sharing that normalised name
        // into the same category.
        val fixture = fixture(db)
        fixture.conduit.replaceAll(setOf(4829))
        val rent = fixture.categories.create("Оренда", "🏠", 0, 80)
        runBlocking {
            fixture.ingest.ingest(listOf(transferTxn("t1", "name:петренко іван")))
            val promptId = fixture.prompts.openForTransaction("t1")!!.id
            val handled = fixture.service.handle(
                callbackQuery(encodeCallback(PromptRef.ById(promptId), PromptAction.Choose(rent))),
            )
            assertTrue(handled)
        }
        assertEquals(rent, fixture.transactions.byId("t1")!!.categoryId)
        assertTrue(fixture.transactions.byId("t1")!!.manuallyCategorized)
        assertTrue(fixture.counterparties.list().isEmpty(), "a name-derived key must never create a silent rule")
    }

    @Test
    fun `if the code stops being conduit before the answer, the button binds the mcc and says so`() = withTestDb { db ->
        val fixture = fixture(db)
        fixture.conduit.replaceAll(setOf(4829))
        val rent = fixture.categories.create("Оренда", "🏠", 0, 80)
        runBlocking {
            fixture.ingest.ingest(listOf(transferTxn("t1", "name:петренко іван")))
            val promptId = fixture.prompts.openForTransaction("t1")!!.id

            // A deliberate owner action, not a race: 4829 stops being conduit while the
            // question is still open. setTransactionCategoryChoice now takes the MCC-bind
            // path instead of the counterparty path, and the reported message must say so.
            fixture.conduit.replaceAll(emptySet())

            val handled = fixture.service.handle(
                callbackQuery(encodeCallback(PromptRef.ById(promptId), PromptAction.Choose(rent))),
            )
            assertTrue(handled)
        }

        assertEquals(rent, fixture.transactions.byId("t1")!!.categoryId)
        assertEquals(mapOf(4829 to rent), fixture.categories.mccMapping())
        assertTrue(
            fixture.counterparties.mapping().isEmpty(),
            "the recipient must not be remembered here — the MCC was bound instead",
        )

        val message = fixture.telegram.edits.single().second
        assertTrue(message.contains("MCC 4829"), message)
        assertTrue(message.contains("Оренда"), message)
        assertFalse(message.contains("не вдалося розпізнати"), message)
    }

    @Test
    fun `pressing new category asks for a name and the reply creates it and files the transfer`() = withTestDb { db ->
        val fixture = fixture(db)
        fixture.conduit.replaceAll(setOf(4829))
        fixture.categories.create("Оренда", "🏠", 0, 80)
        runBlocking {
            fixture.ingest.ingest(listOf(transferTxn("t1", "name:петренко іван")))
            val promptId = fixture.prompts.openForTransaction("t1")!!.id

            fixture.service.handle(callbackQuery(encodeCallback(PromptRef.ById(promptId), PromptAction.NewCategory)))
            val ask = fixture.telegram.sent.last()
            assertTrue(ask.forceReply, "the name question must open a reply box")

            val handled = fixture.service.handleNameReply(
                TgMessage(messageId = 99, chat = TgChat(42), text = "Логопед", replyToMessage = TgMessage(ask.messageId, TgChat(42))),
            )
            assertTrue(handled)
        }
        val created = fixture.categories.list().single { it.name == "Логопед" }
        assertEquals(created.id, fixture.transactions.byId("t1")!!.categoryId)
    }

    @Test
    fun `a name that already exists reuses the category instead of creating a twin`() = withTestDb { db ->
        val fixture = fixture(db)
        fixture.conduit.replaceAll(setOf(4829))
        val rent = fixture.categories.create("Оренда", "🏠", 0, 80)
        runBlocking {
            fixture.ingest.ingest(listOf(transferTxn("t1", "name:петренко іван")))
            val promptId = fixture.prompts.openForTransaction("t1")!!.id
            fixture.service.handle(callbackQuery(encodeCallback(PromptRef.ById(promptId), PromptAction.NewCategory)))
            val ask = fixture.telegram.sent.last()
            fixture.service.handleNameReply(
                TgMessage(99, TgChat(42), "Оренда", replyToMessage = TgMessage(ask.messageId, TgChat(42))),
            )
        }
        assertEquals(1, fixture.categories.list().count { it.name == "Оренда" })
        assertEquals(rent, fixture.transactions.byId("t1")!!.categoryId)
    }

    @Test
    fun `a blank reply creates nothing and asks again`() = withTestDb { db ->
        val fixture = fixture(db)
        fixture.conduit.replaceAll(setOf(4829))
        fixture.categories.create("Оренда", "🏠", 0, 80)
        runBlocking {
            fixture.ingest.ingest(listOf(transferTxn("t1", "name:петренко іван")))
            val promptId = fixture.prompts.openForTransaction("t1")!!.id
            fixture.service.handle(callbackQuery(encodeCallback(PromptRef.ById(promptId), PromptAction.NewCategory)))
            val ask = fixture.telegram.sent.last()

            val handled = fixture.service.handleNameReply(
                TgMessage(99, TgChat(42), "   ", replyToMessage = TgMessage(ask.messageId, TgChat(42))),
            )
            assertTrue(handled)
        }
        // Only the seeded category exists — a blank name must not create a row for itself.
        assertEquals(1, fixture.categories.list().size)
        assertNull(fixture.transactions.byId("t1")!!.categoryId)
        assertTrue(
            fixture.telegram.sent.last().text.contains(UkCopy.categoryNameInvalid),
            fixture.telegram.sent.last().text,
        )
        // The question is still open: whitespace does not burn the owner's one chance to answer.
        assertNotNull(fixture.prompts.openForTransaction("t1"))
    }

    @Test
    fun `control characters embedded in the reply are stripped, not rejected`() = withTestDb { db ->
        val fixture = fixture(db)
        fixture.conduit.replaceAll(setOf(4829))
        fixture.categories.create("Оренда", "🏠", 0, 80)
        runBlocking {
            fixture.ingest.ingest(listOf(transferTxn("t1", "name:петренко іван")))
            val promptId = fixture.prompts.openForTransaction("t1")!!.id
            fixture.service.handle(callbackQuery(encodeCallback(PromptRef.ById(promptId), PromptAction.NewCategory)))
            val ask = fixture.telegram.sent.last()

            // A stray BEL and NUL landing mid-word — e.g. a phone keyboard glitch — must be
            // dropped rather than rejecting the whole reply or ending up inside the name.
            val handled = fixture.service.handleNameReply(
                TgMessage(99, TgChat(42), "Ло\u0007гопед\u0000", replyToMessage = TgMessage(ask.messageId, TgChat(42))),
            )
            assertTrue(handled)
        }
        val created = fixture.categories.list().single { it.name == "Логопед" }
        assertEquals(created.id, fixture.transactions.byId("t1")!!.categoryId)
    }

    @Test
    fun `a reply longer than the limit is truncated, not rejected`() = withTestDb { db ->
        val fixture = fixture(db)
        fixture.conduit.replaceAll(setOf(4829))
        fixture.categories.create("Оренда", "🏠", 0, 80)
        // 9 characters, repeated 6 times — 54 characters, well past MAX_CATEGORY_NAME.
        val longName = "Категорія".repeat(6)
        runBlocking {
            fixture.ingest.ingest(listOf(transferTxn("t1", "name:петренко іван")))
            val promptId = fixture.prompts.openForTransaction("t1")!!.id
            fixture.service.handle(callbackQuery(encodeCallback(PromptRef.ById(promptId), PromptAction.NewCategory)))
            val ask = fixture.telegram.sent.last()

            val handled = fixture.service.handleNameReply(
                TgMessage(99, TgChat(42), longName, replyToMessage = TgMessage(ask.messageId, TgChat(42))),
            )
            assertTrue(handled)
        }
        val created = fixture.categories.list().single { it.name != "Оренда" }
        assertEquals(MAX_CATEGORY_NAME, created.name.length)
        assertEquals(longName.take(MAX_CATEGORY_NAME), created.name)
        assertEquals(created.id, fixture.transactions.byId("t1")!!.categoryId)
    }

    private class Fixture(db: Database) {
        val conduit = ConduitMccRepository(db)
        val categories = CategoryRepository(db, conduit)
        val transactions = TransactionRepository(db)
        val counterparties = CounterpartyRepository(db)
        val prompts = MccPromptRepository(db)
        val telegram = FakeTelegramClient()
        val settings = SettingsRepository(db, Crypto(ByteArray(32) { it.toByte() }))
        val budget = BudgetService(categories, transactions)
        val notifier = Notifier(telegram, settings, NotificationEventRepository(db))
        lateinit var ingest: IngestService
        val service = MccPromptService(
            prompts, categories, settings, telegram, conduit, transactions, counterparties,
        ) { ingest }

        init {
            ingest = IngestService(
                transactions, categories, budget, notifier, counterparties, conduit,
                AccountRepository(db), service,
            )
            settings.set(SettingKeys.TELEGRAM_CHAT_ID, "42")
        }
    }

    private fun fixture(db: Database) = Fixture(db)

    private fun transferTxn(id: String, key: String?) = Txn(
        id = id, accountId = "acc-1", occurredAt = 1_787_011_200L, month = "2026-08",
        amountMinor = -250_000, currencyCode = 980, description = "На картку",
        mcc = 4829, originalMcc = 4829, hold = false,
        categoryId = null, manuallyCategorized = false,
        rawJson = """{"counterName":"Петренко Іван"}""", counterpartyKey = key,
    )

    /** An exact-source transfer — unlike [transferTxn], whose bare counterName is only ever
     *  a probable key, this one carries an IBAN so a rule can actually be created from it. */
    private fun ibanTransferTxn(id: String) = transferTxn(id, key = null).copy(
        rawJson = """{"counterIban":"UA213223130000260072335660001","counterName":"Петренко Іван"}""",
    )

    private fun shopTxn(id: String, mcc: Int) = transferTxn(id, null)
        .copy(mcc = mcc, originalMcc = mcc, description = "Новий магазин", rawJson = "{}")

    private fun callbackQuery(data: String) = TgCallbackQuery(
        id = "cb-$data", data = data, message = TgMessage(messageId = 500, chat = TgChat(42)),
    )
}
