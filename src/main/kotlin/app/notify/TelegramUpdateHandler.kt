package app.notify

import app.budget.BudgetService
import app.budget.KYIV
import app.budget.currentMonthKey
import app.budget.isMonthKey
import app.db.SettingKeys
import app.db.SettingsRepository
import app.i18n.copy
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Clock

class TelegramUpdateHandler(
    private val settings: SettingsRepository,
    private val telegram: TelegramClient,
    private val budget: BudgetService,
    private val callbacks: CallbackHandler = CallbackHandler.NoOp,
    private val chatMoves: ChatMoveObserver = ChatMoveObserver.NoOp,
    private val nameReplies: suspend (TgMessage) -> Boolean = { false },
    private val limits: LimitCommands = LimitCommands.NoOp,
    private val clock: Clock = Clock.system(KYIV),
) {
    private val log = LoggerFactory.getLogger(TelegramUpdateHandler::class.java)

    /** The token the username was read with, paired with the username itself. */
    @Volatile
    private var cachedBotIdentity: Pair<String?, String>? = null

    suspend fun handle(update: TgUpdate) {
        update.callbackQuery?.let { return handleCallback(it) }
        update.message?.let { return handleMessage(it) }
    }

    private suspend fun handleCallback(query: TgCallbackQuery) {
        val pairedChat = settings.get(SettingKeys.TELEGRAM_CHAT_ID)
        val message = query.message
        if (message == null) {
            // No message to check the chat against, so we cannot authenticate — but only the
            // owner ever receives our buttons, so this is a stale press on an old message.
            // Answering just dismisses the spinner; it changes no state and reveals nothing new.
            runCatching { telegram.answerCallbackQuery(query.id, settings.copy().staleCallback) }
                .onFailure { log.warn("failed to answer stale callback {}", query.id, it.withRedactedTelegramToken()) }
            return
        }
        if (message.chat.id.toString() != pairedChat) return
        val handled = callbacks.handle(query)
        if (!handled) {
            runCatching { telegram.answerCallbackQuery(query.id, null) }
                .onFailure { log.warn("failed to answer unrecognised callback {}", query.id, it.withRedactedTelegramToken()) }
        }
    }

    private suspend fun handleMessage(message: TgMessage) {
        val chatId = message.chat.id.toString()
        val pairedChat = settings.get(SettingKeys.TELEGRAM_CHAT_ID)
        val copy = settings.copy()

        // An upgrade to a supergroup is the same room under a new id, and the old one stops
        // accepting messages the moment it happens. Follow it before anything else looks at
        // the chat — but only from the chat we are actually paired with, so a notice from
        // some other group cannot walk the bot out of its own.
        message.migrateToChatId?.let { newChatId ->
            if (chatId != pairedChat) return
            log.info("paired chat {} was upgraded to supergroup {}", chatId, newChatId)
            settings.set(SettingKeys.TELEGRAM_CHAT_ID, newChatId.toString())
            return
        }

        val text = commandForUs(message.text.orEmpty().trim()) ?: return

        // Not yet paired: /start with the code is the only thing we answer.
        if (pairedChat == null) {
            if (text.startsWith("/start")) return handleStart(chatId, text)
            // A bot freshly added to a group has not been paired yet, and that is exactly
            // when it hears the most slash-commands meant for other bots. Telling the room
            // to go read the Settings page, once per command, forever, is not an option.
            if (!message.chat.isPrivate) return
            return reply(chatId, copy.notPairedBotMessage)
        }

        // Paired: everyone else is a stranger and gets silence, not an error message —
        // except while a pairing code is live, which is the owner deliberately handing the
        // bot a way into another chat (a group he shares with his wife, say). Pairing is
        // what the code is for; refusing it here would mean the only way to move the bot
        // is editing the database by hand. A dead code counts as no code: it must buy a
        // stranger neither a reply nor the chance to clear what the owner has stored.
        if (chatId != pairedChat) {
            // Carrying a code is what makes a stranger's /start worth reading at all; a bare
            // one would only earn a reply that tells an unknown chat the bot is listening.
            if (text.startsWith("/start") && text.removePrefix("/start").isNotBlank() && hasLivePairingCode()) {
                return handleStart(chatId, text, answerOnFailure = false)
            }
            log.info("ignoring message from unpaired chat {}", chatId)
            return
        }

        // An answer to one of our own questions is not a command. Checked before the
        // command names so a category literally called "/status" cannot be swallowed.
        if (message.replyToMessage != null && (nameReplies(message) || limits.handleAmountReply(message))) return

        val command = parseCommand(text)
        when (command?.name) {
            "/status" -> reply(chatId, statusFor(command.args, copy))
            "/left" -> reply(chatId, leftFor(currentMonthKey(clock), copy))
            "/limit" -> limits.start(chatId)
            "/start" -> reply(chatId, copy.alreadyConnectedMessage)
            "/help" -> reply(chatId, copy.helpText)
            // Answering anything unrecognised with the command list is only friendly in a
            // private chat. Telegram delivers every slash-command in a group to the bot even
            // with privacy mode on, so in a group the same branch answers other people's and
            // other bots' commands with our help text, endlessly.
            else -> if (message.chat.isPrivate) {
                reply(chatId, copy.helpText)
            } else {
                log.debug("ignoring unrecognised group message in chat {}", chatId)
            }
        }
    }

    private data class Command(val name: String, val args: String)

    /**
     * Splits `/status 2026-07` into its name and the rest. Matching the name exactly is the
     * point of it: `startsWith("/status")` also matched `/statuses` and answered it with the
     * month's spending. Any `@botname` suffix has already been cut by [commandForUs].
     */
    private fun parseCommand(text: String): Command? {
        if (!text.startsWith("/")) return null
        val end = text.indexOfFirst { it.isWhitespace() }.let { if (it < 0) text.length else it }
        return Command(text.substring(0, end), text.substring(end).trim())
    }

    /**
     * No argument means the current month. A malformed one is refused rather than quietly
     * rounded to it, which is what [app.budget.safeMonthKey] would do — and a report for a
     * month nobody asked about is worse than an error, because it looks like an answer.
     */
    private fun statusFor(arg: String, copy: app.i18n.Copy): String {
        val current = currentMonthKey(clock)
        val month = when {
            arg.isEmpty() -> current
            isMonthKey(arg) -> arg
            else -> return copy.badMonthArgument(current)
        }
        return renderStatus(budget.monthSummary(month), copy, copy.monthLabel(month))
    }

    private fun leftFor(month: String, copy: app.i18n.Copy): String =
        renderLeft(budget.monthSummary(month), copy, copy.monthLabel(month))

    /**
     * Whoever pairs first receives every spending alert and can bind MCCs to categories,
     * so the code is a credential. It used to be a six-character code that never expired
     * and accepted unlimited guesses — a standing invitation to anyone who found the bot.
     * It is now single-use in practice: it dies after [MAX_PAIRING_ATTEMPTS] wrong
     * guesses or [PAIRING_TTL_SECONDS], whichever comes first.
     */
    private suspend fun handleStart(chatId: String, text: String, answerOnFailure: Boolean = true) {
        val copy = settings.copy()
        val expected = settings.get(SettingKeys.PAIRING_CODE)
        val provided = text.removePrefix("/start").trim()

        // A failed attempt from a chat we have no relationship with says nothing back. The
        // reply would be an oracle: it tells whoever is guessing that a pairing window is
        // open right now. Success still answers — by then the chat is the paired one.
        suspend fun replyIfOwed(textToSend: String) { if (answerOnFailure) reply(chatId, textToSend) }

        // Telegram's START button sends a bare /start, and so does anyone who ever opened
        // the bot. Scoring that as a wrong guess meant five taps could burn the code the
        // owner was in the middle of using — from a chat that is not even his. A guess has
        // to contain something to be counted as one.
        if (provided.isEmpty()) return replyIfOwed(copy.notPairedBotMessage)

        if (expected == null) return replyIfOwed(copy.invalidPairingCode)

        val expiresAt = settings.get(SettingKeys.PAIRING_CODE_EXPIRES_AT)?.toLongOrNull()
        if (expiresAt == null || clock.instant().epochSecond > expiresAt) {
            clearPairingCode()
            return replyIfOwed(copy.invalidPairingCode)
        }

        // Uppercased first because the code alphabet is uppercase and the comparison is
        // meant to be case-insensitive; equals(ignoreCase) would short-circuit on the
        // first differing character, which this must not do.
        val matches = MessageDigest.isEqual(
            provided.uppercase().toByteArray(Charsets.UTF_8),
            expected.uppercase().toByteArray(Charsets.UTF_8),
        )
        if (!matches) {
            val attempts = (settings.get(SettingKeys.PAIRING_ATTEMPTS)?.toIntOrNull() ?: 0) + 1
            if (attempts >= MAX_PAIRING_ATTEMPTS) {
                log.warn("pairing code burned after {} failed attempts", attempts)
                clearPairingCode()
            } else {
                settings.set(SettingKeys.PAIRING_ATTEMPTS, attempts.toString())
            }
            return replyIfOwed(copy.invalidPairingCode)
        }

        val previous = settings.get(SettingKeys.TELEGRAM_CHAT_ID)
        settings.set(SettingKeys.TELEGRAM_CHAT_ID, chatId)
        clearPairingCode()
        reply(chatId, "${copy.pairingSuccess}${copy.helpText}")
        // Moving the bot leaves the old chat with nothing to show for it. Say so there:
        // a bot that simply stops talking reads as broken, and an unintended pairing
        // should be visible to whoever just lost the alerts.
        if (previous != null && previous != chatId) {
            reply(previous, copy.chatMovedAway)
            // An unanswered question is stranded by a move: its buttons only work in the
            // chat that no longer holds the bot, and nothing asks again while it is open.
            // Whoever owns such a conversation gets to bring it along.
            runCatching { chatMoves.onChatMoved(previous, chatId) }
                .onFailure { log.warn("failed to hand the move over to {}", chatMoves, it.withRedactedTelegramToken()) }
        }
    }

    /**
     * In a group, Telegram's command autocomplete addresses a bot by name — `/status@my_bot`,
     * `/start@my_bot CODE` — and that suffix is part of the message text. It has to be cut
     * before any command matching, or `removePrefix("/start")` reads `@my_bot CODE` as the
     * pairing code and pairing from a group can never succeed.
     *
     * The name is checked, not merely stripped: privacy mode still delivers *every*
     * slash-command in the group to us, other bots included. Cutting the suffix unread
     * answered `/status@some_other_bot` with this month's spending and let
     * `/start@some_other_bot CODE` spend the owner's pairing code.
     *
     * Returns null when the command names a different bot — nothing for us to do with it.
     */
    private suspend fun commandForUs(text: String): String? {
        if (!text.startsWith("/")) return text
        val commandEnd = text.indexOfFirst { it.isWhitespace() }.let { if (it < 0) text.length else it }
        val at = text.indexOf('@')
        if (at < 0 || at > commandEnd) return text

        val addressed = text.substring(at + 1, commandEnd)
        // Nothing to compare against — getMe has never succeeded. Answering is the lesser
        // failure of the two: a bot that stops responding to its own commands because
        // Telegram was briefly unreachable is worse than one that answers a command meant
        // for a neighbour.
        val me = botUsername() ?: return text.removeRange(at, commandEnd)
        return if (addressed.equals(me, ignoreCase = true)) text.removeRange(at, commandEnd) else null
    }

    /**
     * Cached against the token it was read with, because that is the only thing that can
     * change it — and the token is editable from Settings while the app runs. Caching the
     * name alone left a bot pointed at a new token dropping every command addressed to its
     * own new name, mute until a restart. A failure is not cached, so a later message retries.
     */
    private suspend fun botUsername(): String? {
        val token = settings.get(SettingKeys.TELEGRAM_TOKEN)
        cachedBotIdentity?.let { (cachedToken, username) -> if (cachedToken == token) return username }
        val fetched = runCatching { telegram.getMe() }
            .onFailure { log.warn("failed to read the bot's own username", it.withRedactedTelegramToken()) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        return fetched?.also { cachedBotIdentity = token to it }
    }

    private fun hasLivePairingCode(): Boolean {
        if (!settings.isSet(SettingKeys.PAIRING_CODE)) return false
        val expiresAt = settings.get(SettingKeys.PAIRING_CODE_EXPIRES_AT)?.toLongOrNull() ?: return false
        return clock.instant().epochSecond <= expiresAt
    }

    private fun clearPairingCode() {
        settings.delete(SettingKeys.PAIRING_CODE)
        settings.delete(SettingKeys.PAIRING_CODE_EXPIRES_AT)
        settings.delete(SettingKeys.PAIRING_ATTEMPTS)
    }

    private suspend fun reply(chatId: String, text: String) {
        runCatching { telegram.sendMessage(chatId, text) }
            .onFailure { log.warn("failed to reply to chat {}", chatId, it.withRedactedTelegramToken()) }
    }
}
