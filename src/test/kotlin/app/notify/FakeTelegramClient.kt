package app.notify

import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

class FakeTelegramClient(private var failSend: Boolean = false) : TelegramClient {
    data class Sent(
        val chatId: String,
        val text: String,
        val keyboard: List<List<Button>>?,
        val forceReply: Boolean,
        val selective: Boolean,
        val messageId: Long,
    )

    val sent = mutableListOf<Sent>()
    val edits = mutableListOf<Pair<Long, String>>()
    val answered = mutableListOf<String>()
    var lastKeyboard: List<List<Button>>? = null
    private var nextMessageId = 100L

    /**
     * Opt-in instrumentation, inert at the default of 0. When set > 0, sendMessage
     * suspends for this long and throws if another call is already inside sendMessage —
     * used to prove that a caller's own locking actually serializes access.
     */
    var delayMillis: Long = 0
    private val inSend = AtomicBoolean(false)

    fun failNextSends(fail: Boolean) { failSend = fail }

    override suspend fun sendMessage(
        chatId: String,
        text: String,
        keyboard: List<List<Button>>?,
        forceReply: Boolean,
        selective: Boolean,
    ): Long {
        if (failSend) throw IllegalStateException("telegram is down")
        if (delayMillis > 0) {
            if (inSend.getAndSet(true)) {
                throw IllegalStateException("concurrent entry into sendMessage — the ingest mutex did not serialize")
            }
            try {
                delay(delayMillis)
            } finally {
                inSend.set(false)
            }
        }
        val id = nextMessageId++
        sent += Sent(chatId, text, keyboard, forceReply, selective, id)
        lastKeyboard = keyboard
        return id
    }

    override suspend fun editMessageText(chatId: String, messageId: Long, text: String) {
        edits += messageId to text
    }

    override suspend fun answerCallbackQuery(callbackQueryId: String, text: String?) {
        answered += callbackQueryId
    }

    override suspend fun setWebhook(url: String, secretToken: String) = Unit

    val commands = mutableListOf<List<BotCommand>>()

    override suspend fun setMyCommands(commands: List<BotCommand>) {
        this.commands += commands
    }

    /** Mutable so a test can rotate the token to a different bot and see what follows. */
    var username: String = "test_bot"

    override suspend fun getMe(): String = username
}
