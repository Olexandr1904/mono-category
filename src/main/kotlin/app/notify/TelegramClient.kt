package app.notify

interface TelegramClient {
    /**
     * Returns the sent message_id.
     *
     * [forceReply] opens a reply box bound to the sent message. It cannot be attached to an
     * edit — editMessageText accepts an inline keyboard and nothing else — so the caller
     * sends a new message and keeps its id to recognise the answer.
     *
     * [selective] narrows that reply box to the people @-mentioned in [text]. Without it, a
     * force reply in a group springs the keyboard open for every member. It is the mention
     * that does the targeting, not this flag: Telegram aims a selective force reply at the
     * users named in the text, so setting it on a message that mentions nobody targets
     * nobody at all. Always set the two together.
     */
    suspend fun sendMessage(
        chatId: String,
        text: String,
        keyboard: List<List<Button>>? = null,
        forceReply: Boolean = false,
        selective: Boolean = false,
    ): Long
    suspend fun editMessageText(chatId: String, messageId: Long, text: String)
    suspend fun answerCallbackQuery(callbackQueryId: String, text: String? = null)
    suspend fun setWebhook(url: String, secretToken: String)

    /** Registers the command list Telegram shows in the chat's input bar. Needs only the
     *  token — it does not read the bot's username — so it is independent of getMe. */
    suspend fun setMyCommands(commands: List<BotCommand>)
    /** Returns the bot username. Used to validate the token in Settings. */
    suspend fun getMe(): String
}
