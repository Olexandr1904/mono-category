package app.notify

import app.budget.CategoryRepository
import app.budget.formatMinor
import app.db.LimitPrompts
import app.db.SettingsRepository
import app.i18n.copy
import app.ingest.IngestService
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Instant

/** One unanswered "what amount?" question from /limit. */
data class LimitPrompt(
    val id: Long,
    val categoryId: Long,
    val keyboardMessageId: Long?,
    val replyMessageId: Long?,
)

class LimitPromptRepository(private val db: Database) {

    private fun toPrompt(row: ResultRow) = LimitPrompt(
        id = row[LimitPrompts.id].value,
        categoryId = row[LimitPrompts.categoryId],
        keyboardMessageId = row[LimitPrompts.keyboardMessageId],
        replyMessageId = row[LimitPrompts.replyMessageId],
    )

    fun create(categoryId: Long, keyboardMessageId: Long?): Long = transaction(db) {
        LimitPrompts.insertAndGetId {
            it[LimitPrompts.categoryId] = categoryId
            it[LimitPrompts.keyboardMessageId] = keyboardMessageId
            it[createdAt] = Instant.now().epochSecond
        }.value
    }

    /**
     * Also how a rejected amount re-asks: the new question's id replaces the old one, so a
     * reply to the message we have already answered finds nothing. Two live reply targets
     * for one dialog would let the same question be answered twice.
     */
    fun attachReplyMessage(id: Long, messageId: Long) = transaction(db) {
        LimitPrompts.update({ LimitPrompts.id eq id }) { it[replyMessageId] = messageId }
        Unit
    }

    fun openByReplyMessage(messageId: Long): LimitPrompt? = transaction(db) {
        LimitPrompts.selectAll()
            .where { (LimitPrompts.replyMessageId eq messageId) and LimitPrompts.resolvedAt.isNull() }
            .singleOrNull()?.let(::toPrompt)
    }

    fun listOpen(): List<LimitPrompt> = transaction(db) {
        LimitPrompts.selectAll()
            .where { LimitPrompts.resolvedAt.isNull() }
            .orderBy(LimitPrompts.id)
            .map(::toPrompt)
    }

    fun resolve(id: Long): Boolean = transaction(db) {
        LimitPrompts.update({ (LimitPrompts.id eq id) and LimitPrompts.resolvedAt.isNull() }) {
            it[resolvedAt] = Instant.now().epochSecond
        } > 0
    }
}

private const val LIMIT_PREFIX = "lim"

/** Well inside Telegram's 64 bytes of callback_data. */
fun encodeLimitCallback(categoryId: Long): String = "$LIMIT_PREFIX:$categoryId"

const val LIMIT_CANCEL_DATA = "$LIMIT_PREFIX:x"

sealed class LimitCallback {
    data class Pick(val categoryId: Long) : LimitCallback()
    object Cancel : LimitCallback()
}

/**
 * Null for anything that is not ours, which is what lets the composite dispatcher hand the
 * same query on to [MccPromptService]. The two encodings cannot collide: that one never
 * produces fewer than three colon-separated parts, and this one never produces more than two.
 */
fun decodeLimitCallback(data: String): LimitCallback? {
    val parts = data.split(':')
    if (parts.size != 2 || parts[0] != LIMIT_PREFIX) return null
    if (parts[1] == "x") return LimitCallback.Cancel
    return parts[1].toLongOrNull()?.let(LimitCallback::Pick)
}

/**
 * The `/limit` dialog: a keyboard of categories, then an amount typed as a reply.
 *
 * Two steps rather than one because the amount is free text and a button cannot carry it,
 * and a reply rather than a plain message because under privacy mode a plain message in a
 * group never reaches the bot at all.
 */
class LimitPromptService(
    private val prompts: LimitPromptRepository,
    private val categories: CategoryRepository,
    private val settings: SettingsRepository,
    private val telegram: TelegramClient,
    private val ingestProvider: () -> IngestService,
) : LimitCommands, CallbackHandler, ChatMoveObserver {

    private val log = LoggerFactory.getLogger(LimitPromptService::class.java)

    /**
     * Closes every unfinished dialog rather than re-homing it, which is the opposite of
     * what [MccPromptService] does with a move and deliberately so: an unknown code is
     * still worth asking about wherever the bot now lives, but a half-typed limit dragged
     * into a chat that never started it asks someone to finish a stranger's sentence.
     *
     * The invariant it keeps is the same one: an open prompt's message id always refers to
     * the currently paired chat. That is why this table stores neither a chat id nor an
     * expiry — there is only ever one chat, and nothing open outlives a move.
     */
    override suspend fun onChatMoved(from: String, to: String) {
        val open = prompts.listOpen()
        if (open.isEmpty()) return
        val copy = settings.copy()
        for (prompt in open) {
            // Strips the buttons as well as the text: left live in the abandoned chat they
            // would produce callbacks from an unpaired chat, which are dropped unanswered.
            prompt.keyboardMessageId?.let { edit(from, it, copy.limitDialogMovedAway) }
            prompts.resolve(prompt.id)
        }
    }

    override suspend fun start(chatId: String) {
        val copy = settings.copy()
        val choices = categories.list(includeDisabled = false)
        if (choices.isEmpty()) return send(chatId, copy.limitNoCategories)

        val keyboard = choices.map {
            val face = if (it.monthlyLimitMinor > 0) {
                copy.limitButton(it.label, formatMinor(it.monthlyLimitMinor))
            } else {
                copy.limitButtonNoLimit(it.label)
            }
            listOf(Button(face, encodeLimitCallback(it.id)))
        } + listOf(listOf(Button(copy.limitCancelButton, LIMIT_CANCEL_DATA)))

        runCatching { telegram.sendMessage(chatId, copy.limitPickCategory, keyboard) }
            .onFailure { log.warn("failed to offer the limit keyboard", it.withRedactedTelegramToken()) }
    }

    override suspend fun handle(query: TgCallbackQuery): Boolean {
        val callback = decodeLimitCallback(query.data.orEmpty()) ?: return false
        val chatId = query.message?.chat?.id?.toString() ?: return false
        val keyboardMessageId = query.message.messageId
        val copy = settings.copy()

        when (callback) {
            LimitCallback.Cancel -> {
                closeDialogsFrom(keyboardMessageId)
                edit(chatId, keyboardMessageId, copy.limitCancelled)
            }
            is LimitCallback.Pick -> {
                // Picking again replaces the question rather than adding one. The keyboard
                // survives a pick, so a second category can be pressed on it — and two
                // dialogs owning the same keyboard message would overwrite each other's
                // outcome, while the abandoned one stayed answerable for good.
                closeDialogsFrom(keyboardMessageId)
                val category = categories.byId(callback.categoryId)
                if (category == null) {
                    edit(chatId, keyboardMessageId, copy.limitCategoryGone)
                } else {
                    val question = if (category.monthlyLimitMinor > 0) {
                        copy.limitAmountQuestion(category.label, formatMinor(category.monthlyLimitMinor))
                    } else {
                        copy.limitAmountQuestionNoLimit(category.label)
                    }
                    val promptId = prompts.create(category.id, keyboardMessageId)
                    val asked = ask(chatId, question, query.message.chat, query.from)
                    // Never leave a question nobody was asked: it would sit open and swallow
                    // the next unrelated reply that happened to quote the right message.
                    if (asked == null) prompts.resolve(promptId) else prompts.attachReplyMessage(promptId, asked)
                }
            }
        }
        answer(query.id)
        return true
    }

    override suspend fun handleAmountReply(message: TgMessage): Boolean {
        val replyTo = message.replyToMessage?.messageId ?: return false
        val prompt = prompts.openByReplyMessage(replyTo) ?: return false
        val chatId = message.chat.id.toString()
        val copy = settings.copy()
        val who = message.from?.displayName.orEmpty()

        val amount = when (val parsed = parseLimitAmount(message.text.orEmpty())) {
            is AmountResult.Ok -> parsed.minor
            AmountResult.NotANumber -> return reAsk(chatId, prompt, copy.limitAmountNotANumber, message)
            AmountResult.Negative -> return reAsk(chatId, prompt, copy.limitAmountNegative, message)
            AmountResult.TooLarge ->
                return reAsk(chatId, prompt, copy.limitAmountTooLarge(formatMinor(MAX_LIMIT_MINOR)), message)
        }

        val category = categories.byId(prompt.categoryId)
        if (category == null) {
            prompts.resolve(prompt.id)
            send(chatId, copy.limitCategoryGone)
            return true
        }

        val before = category.monthlyLimitMinor
        // Single-writer rule: the category goes through IngestService, never the repository.
        ingestProvider().updateCategory(category.copy(monthlyLimitMinor = amount))
        prompts.resolve(prompt.id)

        val outcome = if (amount == 0L) {
            copy.limitCleared(category.label, who)
        } else {
            copy.limitChanged(category.label, formatMinor(before), formatMinor(amount), who)
        }
        val keyboardMessageId = prompt.keyboardMessageId
        if (keyboardMessageId != null) edit(chatId, keyboardMessageId, outcome) else send(chatId, outcome)
        return true
    }

    /**
     * A rejection has to open a reply box of its own and take over the dialog. Answering
     * with a plain message would leave the next attempt typed into a room the bot cannot
     * hear — privacy mode delivers only replies to our own messages.
     */
    private suspend fun reAsk(
        chatId: String,
        prompt: LimitPrompt,
        complaint: String,
        answeredWith: TgMessage,
    ): Boolean {
        val asked = ask(chatId, complaint, answeredWith.chat, answeredWith.from)
        if (asked == null) prompts.resolve(prompt.id) else prompts.attachReplyMessage(prompt.id, asked)
        return true
    }

    /** The id of the question asked, or null when it could not be sent. */
    private suspend fun ask(chatId: String, question: String, chat: TgChat?, asker: TgUser?): Long? {
        val addressed = addressQuestion(question, chat, asker)
        return runCatching {
            telegram.sendMessage(
                chatId, addressed.text,
                forceReply = addressed.forceReply, selective = addressed.selective,
            )
        }
            .onFailure { log.warn("failed to ask for a limit amount", it.withRedactedTelegramToken()) }
            .getOrNull()
    }

    /**
     * Closes whatever this keyboard has already opened. Both buttons need it: Cancel must
     * not leave a question answerable behind the cancellation, and a second Pick must not
     * leave the first one running alongside it.
     */
    private fun closeDialogsFrom(keyboardMessageId: Long) {
        prompts.listOpen()
            .filter { it.keyboardMessageId == keyboardMessageId }
            .forEach { prompts.resolve(it.id) }
    }

    private suspend fun send(chatId: String, text: String) {
        runCatching { telegram.sendMessage(chatId, text) }
            .onFailure { log.warn("failed to send to {}", chatId, it.withRedactedTelegramToken()) }
    }

    private suspend fun edit(chatId: String, messageId: Long, text: String) {
        runCatching { telegram.editMessageText(chatId, messageId, text) }
            .onFailure { log.warn("failed to edit message {}", messageId, it.withRedactedTelegramToken()) }
    }

    private suspend fun answer(callbackQueryId: String) {
        runCatching { telegram.answerCallbackQuery(callbackQueryId, null) }
            .onFailure { log.warn("failed to answer callback {}", callbackQueryId, it.withRedactedTelegramToken()) }
    }
}
