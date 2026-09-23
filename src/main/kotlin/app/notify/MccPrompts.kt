package app.notify

import app.budget.CategoryRepository
import app.budget.ConduitMccException
import app.budget.ConduitMccRepository
import app.budget.CounterpartyRepository
import app.budget.DEFAULT_SEED_THRESHOLD_PCT
import app.budget.MccConflictException
import app.budget.TransactionRepository
import app.budget.Txn
import app.budget.counterpartyOf
import app.budget.formatMinor
import app.db.MccPrompts
import app.db.SettingKeys
import app.db.SettingsRepository
import app.i18n.copy
import app.ingest.CategoryChoiceOutcome
import app.ingest.IngestService
import app.ingest.UnknownMccObserver
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Instant

enum class PromptKind(val code: String) {
    MCC("mcc"),
    TRANSFER("transfer");

    companion object {
        fun fromCode(code: String): PromptKind = entries.firstOrNull { it.code == code } ?: MCC
    }
}

data class MccPrompt(
    val id: Long,
    val kind: PromptKind,
    val mcc: Int,
    val transactionId: String,
    val messageId: Long?,
    val resolved: Boolean,
)

/**
 * How a button identifies the question it belongs to. An unknown-code question is keyed by
 * the code, because there is exactly one open question per code. Transfers all share one
 * code, so theirs are keyed by the question's own id.
 */
sealed class PromptRef {
    data class ByMcc(val mcc: Int) : PromptRef()
    data class ById(val promptId: Long) : PromptRef()
}

sealed class PromptAction {
    data class Choose(val categoryId: Long) : PromptAction()
    object Skip : PromptAction()
    object NewCategory : PromptAction()
}

data class PromptCallback(val ref: PromptRef, val action: PromptAction)

/** Telegram allows 64 bytes of callback_data; the longest form here is well under half that. */
fun encodeCallback(ref: PromptRef, action: PromptAction): String {
    val head = when (ref) {
        is PromptRef.ByMcc -> "mcc:${ref.mcc}"
        is PromptRef.ById -> "p:${ref.promptId}"
    }
    val tail = when (action) {
        is PromptAction.Choose -> "cat:${action.categoryId}"
        PromptAction.Skip -> "skip"
        PromptAction.NewCategory -> "new"
    }
    return "$head:$tail"
}

fun decodeCallback(data: String): PromptCallback? {
    val parts = data.split(':')
    if (parts.size < 3) return null
    val ref = when (parts[0]) {
        "mcc" -> parts[1].toIntOrNull()?.let(PromptRef::ByMcc)
        "p" -> parts[1].toLongOrNull()?.let(PromptRef::ById)
        else -> null
    } ?: return null
    val action = when {
        parts.size == 3 && parts[2] == "skip" -> PromptAction.Skip
        parts.size == 3 && parts[2] == "new" -> PromptAction.NewCategory
        parts.size == 4 && parts[2] == "cat" -> parts[3].toLongOrNull()?.let(PromptAction::Choose)
        else -> null
    } ?: return null
    return PromptCallback(ref, action)
}

class MccPromptRepository(private val db: Database) {

    private fun toPrompt(row: ResultRow) = MccPrompt(
        id = row[MccPrompts.id].value,
        kind = PromptKind.fromCode(row[MccPrompts.kind]),
        mcc = row[MccPrompts.mcc],
        transactionId = row[MccPrompts.transactionId],
        messageId = row[MccPrompts.messageId],
        resolved = row[MccPrompts.resolvedAt] != null,
    )

    /**
     * `kind = 'mcc'` is not redundant with the mcc filter: every transfer question also
     * carries MCC 4829, so without it this would find (and [resolve] would close) an
     * unrelated transfer prompt whenever a code shares a number with a still-open transfer.
     */
    fun openFor(mcc: Int): MccPrompt? = transaction(db) {
        MccPrompts.selectAll()
            .where { (MccPrompts.mcc eq mcc) and (MccPrompts.kind eq PromptKind.MCC.code) and MccPrompts.resolvedAt.isNull() }
            .singleOrNull()?.let(::toPrompt)
    }

    /** Every unanswered question, in the order they were asked. */
    fun listOpen(): List<MccPrompt> = transaction(db) {
        MccPrompts.selectAll()
            .where { MccPrompts.resolvedAt.isNull() }
            .orderBy(MccPrompts.id)
            .map(::toPrompt)
    }

    fun openById(id: Long): MccPrompt? = transaction(db) {
        MccPrompts.selectAll()
            .where { (MccPrompts.id eq id) and MccPrompts.resolvedAt.isNull() }
            .singleOrNull()?.let(::toPrompt)
    }

    fun openForTransaction(transactionId: String): MccPrompt? = transaction(db) {
        MccPrompts.selectAll()
            .where { (MccPrompts.transactionId eq transactionId) and MccPrompts.resolvedAt.isNull() }
            .singleOrNull()?.let(::toPrompt)
    }

    /**
     * Returns null when a question of this kind is already open. An MCC question is unique
     * per code — five purchases in an unknown shop produce one message, not five. A transfer
     * question is unique per transaction instead, because every transfer shares MCC 4829 and
     * each one is its own question. The partial unique indexes are the backstop for both.
     */
    fun create(kind: PromptKind, mcc: Int, transactionId: String): Long? = transaction(db) {
        val alreadyOpen = when (kind) {
            PromptKind.MCC -> openFor(mcc) != null
            PromptKind.TRANSFER -> openForTransaction(transactionId) != null
        }
        if (alreadyOpen) return@transaction null
        runCatching {
            MccPrompts.insertAndGetId {
                it[MccPrompts.mcc] = mcc
                it[MccPrompts.transactionId] = transactionId
                it[MccPrompts.kind] = kind.code
                it[createdAt] = Instant.now().epochSecond
            }.value
        }.getOrNull()
    }

    fun attachMessage(id: Long, messageId: Long) = transaction(db) {
        MccPrompts.update({ MccPrompts.id eq id }) { it[MccPrompts.messageId] = messageId }
        Unit
    }

    /** [messageId] is the id of the force_reply question, not the original prompt message. */
    fun attachReplyMessage(id: Long, messageId: Long) = transaction(db) {
        MccPrompts.update({ MccPrompts.id eq id }) { it[replyMessageId] = messageId }
        Unit
    }

    /**
     * Drops the reply target when a question is re-homed into another chat. The re-sent
     * question carries buttons, not a reply box, so the old target is not merely stale —
     * message ids restart low in a fresh group, and a collision would make an unrelated
     * reply there answer this question, or make the real answer vanish.
     */
    fun clearReplyMessage(id: Long) = transaction(db) {
        MccPrompts.update({ MccPrompts.id eq id }) { it[replyMessageId] = null }
        Unit
    }

    fun openByReplyMessage(messageId: Long): MccPrompt? = transaction(db) {
        MccPrompts.selectAll()
            .where { (MccPrompts.replyMessageId eq messageId) and MccPrompts.resolvedAt.isNull() }
            .singleOrNull()?.let(::toPrompt)
    }

    /** Scoped to `kind = 'mcc'`, see [openFor] — otherwise this would also close every
     *  open transfer question sharing the same code (every transfer shares MCC 4829). */
    fun resolve(mcc: Int): Boolean = transaction(db) {
        MccPrompts.update({
            (MccPrompts.mcc eq mcc) and (MccPrompts.kind eq PromptKind.MCC.code) and MccPrompts.resolvedAt.isNull()
        }) {
            it[resolvedAt] = Instant.now().epochSecond
        } > 0
    }

    fun resolveById(id: Long): Boolean = transaction(db) {
        MccPrompts.update({ (MccPrompts.id eq id) and MccPrompts.resolvedAt.isNull() }) {
            it[resolvedAt] = Instant.now().epochSecond
        } > 0
    }
}

/** Telegram messages render fine well past this; it just keeps button labels and lists tidy. */
const val MAX_CATEGORY_NAME = 40

class MccPromptService(
    private val prompts: MccPromptRepository,
    private val categories: CategoryRepository,
    private val settings: SettingsRepository,
    private val telegram: TelegramClient,
    private val conduit: ConduitMccRepository,
    private val transactions: TransactionRepository,
    private val counterparties: CounterpartyRepository,
    private val ingestProvider: () -> IngestService,
) : UnknownMccObserver, CallbackHandler, ChatMoveObserver {

    private val log = LoggerFactory.getLogger(MccPromptService::class.java)

    override suspend fun onUnknownMcc(txn: Txn) {
        val mcc = txn.mcc ?: return
        val chatId = settings.get(SettingKeys.TELEGRAM_CHAT_ID) ?: return
        val choices = categories.list(includeDisabled = false)
        if (choices.isEmpty()) return
        val copy = settings.copy()

        val kind = if (conduit.contains(mcc)) PromptKind.TRANSFER else PromptKind.MCC
        val promptId = prompts.create(kind, mcc, txn.id) ?: return
        val keyboard = keyboardFor(refFor(kind, promptId, mcc), choices, copy)
        val question = renderQuestion(kind, txn, mcc, copy)

        try {
            val messageId = telegram.sendMessage(chatId, question, keyboard)
            prompts.attachMessage(promptId, messageId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("failed to ask about MCC {}", mcc, e.withRedactedTelegramToken())
            // do not leave a question nobody was asked; resolve exactly the one that failed,
            // not every other open question sharing this MCC (all transfers share 4829).
            if (kind == PromptKind.TRANSFER) prompts.resolveById(promptId) else prompts.resolve(mcc)
        }
    }

    /**
     * A transfer question is keyed by its own id (every transfer shares MCC 4829), an
     * unknown-code question by the code itself.
     */
    private fun refFor(kind: PromptKind, promptId: Long, mcc: Int): PromptRef =
        if (kind == PromptKind.TRANSFER) PromptRef.ById(promptId) else PromptRef.ByMcc(mcc)

    private fun keyboardFor(ref: PromptRef, choices: List<app.budget.Category>, copy: app.i18n.Copy) =
        choices.map { listOf(Button(it.label, encodeCallback(ref, PromptAction.Choose(it.id)))) } +
            listOf(
                listOf(Button(copy.newCategoryButton, encodeCallback(ref, PromptAction.NewCategory))),
                listOf(Button(copy.skipButton, encodeCallback(ref, PromptAction.Skip))),
            )

    private fun renderQuestion(kind: PromptKind, txn: Txn, mcc: Int, copy: app.i18n.Copy) = when (kind) {
        PromptKind.MCC -> copy.unknownMccQuestion(txn.description, formatMinor(-txn.amountMinor), mcc) +
            "\n\n" + copy.mccScopeHint
        PromptKind.TRANSFER -> renderTransferQuestion(copy, txn)
    }

    /**
     * Brings every unanswered question along when the bot is re-paired into another chat.
     * The message left behind is edited — which also strips its buttons, since
     * [TelegramClient.editMessageText] sends no markup — and the same question is asked
     * again where the alerts now go, so the prompt's `message_id` still refers to the chat
     * the bot is paired with.
     *
     * Each question is handled on its own: one dead message must not strand the rest. A
     * question whose transaction has since vanished is closed instead of moved — there is
     * nothing left to ask about, and leaving it open would block the next question for that
     * code forever.
     */
    override suspend fun onChatMoved(from: String, to: String) {
        val open = prompts.listOpen()
        if (open.isEmpty()) return
        val copy = settings.copy()
        val choices = categories.list(includeDisabled = false)

        for (prompt in open) {
            val txn = transactions.byId(prompt.transactionId)
            if (txn == null || choices.isEmpty()) {
                log.info("closing prompt {} on a chat move: nothing left to ask about", prompt.id)
                prompts.resolveById(prompt.id)
                continue
            }

            prompt.messageId?.let { messageId ->
                runCatching { telegram.editMessageText(from, messageId, copy.promptMovedAway) }
                    .onFailure { log.warn("failed to retire prompt message {}", messageId, it.withRedactedTelegramToken()) }
            }

            // The question is re-asked with buttons, never with a reply box, so any reply
            // target it had belongs to the chat being left. Left in place it is not just
            // stale: message ids restart low in a fresh group, so a collision would let an
            // unrelated reply there name this category, or swallow the real answer.
            prompts.clearReplyMessage(prompt.id)

            val keyboard = keyboardFor(refFor(prompt.kind, prompt.id, prompt.mcc), choices, copy)
            runCatching { telegram.sendMessage(to, renderQuestion(prompt.kind, txn, prompt.mcc, copy), keyboard) }
                .onSuccess { prompts.attachMessage(prompt.id, it) }
                .onFailure {
                    // Leaving it open would be the orphaning this method exists to fix, only
                    // one chat further along: `create` never opens a second question for a
                    // code that already has one, so an unasked question that stays open is a
                    // code nobody is ever asked about again. Close it and let the next
                    // purchase ask afresh — the same call [onUnknownMcc] makes when its own
                    // send fails.
                    log.warn("failed to re-ask prompt {} in {}; closing it", prompt.id, to, it.withRedactedTelegramToken())
                    prompts.resolveById(prompt.id)
                }
        }
    }

    override suspend fun handle(query: TgCallbackQuery): Boolean {
        val callback = decodeCallback(query.data.orEmpty()) ?: return false
        val chatId = query.message?.chat?.id?.toString() ?: return false
        val messageId = query.message.messageId
        val copy = settings.copy()

        return when (val ref = callback.ref) {
            is PromptRef.ByMcc -> {
                val mcc = ref.mcc
                val open = prompts.openFor(mcc)
                if (open == null) {
                    answer(query.id, copy.alreadyHandledCallback)
                    return true
                }

                val outcome = when (val action = callback.action) {
                    PromptAction.Skip -> {
                        prompts.resolve(mcc)
                        copy.mccSkipped(mcc)
                    }
                    is PromptAction.Choose -> try {
                        ingestProvider().bindMcc(mcc, action.categoryId)
                        prompts.resolve(mcc)
                        val name = categories.byId(action.categoryId)?.label ?: "category ${action.categoryId}"
                        copy.mccBound(mcc, name)
                    } catch (e: MccConflictException) {
                        prompts.resolve(mcc)
                        copy.mccConflictTelegram(mcc, e.ownerName)
                    } catch (e: ConduitMccException) {
                        // The code became conduit after this question was asked (the owner
                        // flipped it in the web form while the message sat unanswered).
                        // Without this case the exception unwound out of handle() into the
                        // webhook route's blanket runCatching: the button spinner would hang
                        // forever and the prompt would stay open. The transfer path already
                        // has an explicit case for exactly this race (Conflict); give the MCC
                        // path the same treatment instead of a silent swallow.
                        prompts.resolve(mcc)
                        copy.conduitMccRejected(mcc)
                    }
                    // Opens a reply box instead of resolving anything, so it falls out of
                    // the outcome/edit path shared by Skip and Choose below.
                    PromptAction.NewCategory -> {
                        askForName(query, open, copy)
                        answer(query.id, null)
                        return true
                    }
                }

                answer(query.id, null)
                runCatching { telegram.editMessageText(chatId, messageId, outcome) }
                    .onFailure { log.warn("failed to edit prompt message {}", messageId, it.withRedactedTelegramToken()) }
                true
            }
            is PromptRef.ById -> {
                val prompt = prompts.openById(ref.promptId)
                if (prompt == null) {
                    answer(query.id, copy.alreadyHandledCallback)
                    return true
                }

                when (val action = callback.action) {
                    // Closes the question and touches nothing else: no rule, no category.
                    // The next transfer to the same recipient is asked about again, because
                    // transfer questions are keyed by transaction, not by recipient.
                    PromptAction.Skip -> {
                        prompts.resolveById(prompt.id)
                        answer(query.id, null)
                        runCatching { telegram.editMessageText(chatId, messageId, copy.transferSkipped) }
                            .onFailure { log.warn("failed to edit prompt message {}", messageId, it.withRedactedTelegramToken()) }
                        true
                    }
                    is PromptAction.Choose -> {
                        applyChoice(chatId, prompt, action.categoryId, copy)
                        answer(query.id, null)
                        true
                    }
                    PromptAction.NewCategory -> {
                        askForName(query, prompt, copy)
                        answer(query.id, null)
                        true
                    }
                }
            }
        }
    }

    /**
     * Applies a chosen category to a transfer question and reports what happened. The
     * single place both entry points meet: a category button here, and a freshly created
     * category from the reply flow (Task 8).
     */
    private suspend fun applyChoice(chatId: String, prompt: MccPrompt, categoryId: Long, copy: app.i18n.Copy) {
        // Refuse to overwrite silently: a rule created while answering a neighbouring
        // question may already have filed this row, and quietly reassigning it would
        // hide that from the person who pressed the button.
        val current = ingestProvider().transactionCategoryName(prompt.transactionId)
        val message = if (current != null) {
            copy.transferAlreadyCategorized(current)
        } else {
            when (val outcome = ingestProvider().setTransactionCategoryChoice(prompt.transactionId, categoryId)) {
                is CategoryChoiceOutcome.CounterpartyBound ->
                    copy.transferBound(outcome.displayName, outcome.categoryName, outcome.movedCount)
                is CategoryChoiceOutcome.TransferSingleRow -> copy.transferSingleRow(outcome.categoryName)
                // The owner can un-mark a code as conduit while its question is still open.
                // When that happens, the choice binds the MCC instead of the recipient and
                // can move many rows — the message must say that happened, not the opposite.
                is CategoryChoiceOutcome.Bound -> copy.mccBoundFromTransaction(
                    outcome.merchant, outcome.mcc, outcome.categoryName, outcome.movedCount,
                )
                is CategoryChoiceOutcome.Conflict -> copy.transferMccConflict(outcome.mcc, outcome.ownerName)
                // The transaction carries no MCC at all — the same edge case the web
                // dropdown already has a message for.
                is CategoryChoiceOutcome.SingleRow -> copy.singleTransactionCategorized(outcome.categoryName)
                CategoryChoiceOutcome.NotFound -> copy.unknownTransaction
                // Unreachable via this call site (categoryId is always non-null here), but
                // matched explicitly rather than folded into an `else` — CLAUDE.md: the
                // sealed class exists "so no case is silently dropped."
                CategoryChoiceOutcome.Cleared -> copy.transferNothingChanged
            }
        }
        prompts.resolveById(prompt.id)
        prompt.messageId?.let { messageId ->
            runCatching { telegram.editMessageText(chatId, messageId, message) }
                .onFailure { log.warn("failed to edit prompt message {}", messageId, it.withRedactedTelegramToken()) }
        }
    }

    /**
     * "New category" cannot ride on the prompt's own message — editMessageText only accepts
     * an inline keyboard, never force_reply — so this sends a second message and remembers
     * its id. Whatever the owner types back arrives carrying that id as reply_to_message,
     * which [handleNameReply] uses to find its way back here.
     *
     * Takes the whole query rather than a chat id because the question is for one person:
     * in a group it has to be aimed at whoever pressed the button, or the reply keyboard
     * opens for the entire family. See [addressQuestion].
     */
    private suspend fun askForName(query: TgCallbackQuery, prompt: MccPrompt, copy: app.i18n.Copy) {
        val chatId = query.message?.chat?.id?.toString() ?: return
        val addressed = addressQuestion(copy.newCategoryPrompt, query.message.chat, query.from)
        val asked = runCatching {
            telegram.sendMessage(
                chatId, addressed.text,
                forceReply = addressed.forceReply, selective = addressed.selective,
            )
        }
            .getOrElse {
                log.warn("failed to ask for a category name", it.withRedactedTelegramToken())
                return
            }
        prompts.attachReplyMessage(prompt.id, asked)
    }

    /** Returns true when [message] was an answer to an open "what shall I call it?" question. */
    suspend fun handleNameReply(message: TgMessage): Boolean {
        val replyTo = message.replyToMessage?.messageId ?: return false
        val prompt = prompts.openByReplyMessage(replyTo) ?: return false
        val chatId = message.chat.id.toString()
        val copy = settings.copy()

        val name = message.text.orEmpty().filter { !it.isISOControl() }.trim().take(MAX_CATEGORY_NAME)
        if (name.isEmpty()) {
            reply(chatId, copy.categoryNameInvalid)
            return true
        }

        val existing = categories.list().firstOrNull { it.name.equals(name, ignoreCase = true) }
        val categoryId = existing?.id ?: ingestProvider().createCategory(name, "", 0L, DEFAULT_SEED_THRESHOLD_PCT)
        if (existing != null) reply(chatId, copy.categoryAlreadyExists(existing.label))

        applyChoice(chatId, prompt, categoryId, copy)
        return true
    }

    private suspend fun reply(chatId: String, text: String) {
        runCatching { telegram.sendMessage(chatId, text) }
            .onFailure { log.warn("failed to send {}", chatId, it.withRedactedTelegramToken()) }
    }

    private suspend fun answer(callbackQueryId: String, text: String?) {
        runCatching { telegram.answerCallbackQuery(callbackQueryId, text) }
            .onFailure { log.warn("failed to answer callback {}", callbackQueryId, it.withRedactedTelegramToken()) }
    }

    /** Adds the "seen before" line only when we actually have a key to count by. */
    private fun renderTransferQuestion(copy: app.i18n.Copy, txn: Txn): String {
        val recipient = counterpartyOf(txn.rawJson, txn.description)?.displayName ?: txn.description
        val head = copy.transferQuestion(formatMinor(-txn.amountMinor), recipient)
        val seenBefore = txn.counterpartyKey
            ?.let { transactions.countByCounterparty(it) - 1 }
            ?.takeIf { it > 0 }
            ?.let { "\n" + copy.transferQuestionSeenBefore(it) }
            .orEmpty()
        return "$head$seenBefore\n\n${copy.transferScopeHint}"
    }
}
