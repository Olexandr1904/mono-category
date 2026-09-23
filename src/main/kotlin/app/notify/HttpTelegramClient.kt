package app.notify

import app.API_JSON
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TelegramTokenMissingException : RuntimeException("Telegram bot token is not configured")

class TelegramApiException(message: String) : RuntimeException(message)

// Telegram's API puts the bot token in the request path ($baseUrl/bot$token/$method), which
// is how the API works and is fine by itself — but Ktor's timeout and connection exceptions
// embed the full request URL in their message. Left alone, that message flows into redirect
// URLs (browser history, access logs) and log lines with a stack trace. Every site that logs
// or displays an exception from a Telegram call must redact through this first.
private val BOT_TOKEN_PATTERN = Regex("""bot\d+:[\w-]+""")

/** Replaces a Telegram bot token wherever it appears in [text], e.g. inside a URL baked into an exception message. */
fun redactTelegramToken(text: String): String = BOT_TOKEN_PATTERN.replace(text, "bot***:REDACTED")

/**
 * A throwable equivalent to [this] with the token scrubbed from its message, safe to pass to
 * a logger. The original stack trace is preserved for diagnosis; the cause chain is
 * deliberately dropped so a nested exception's unredacted `toString()` cannot leak the token
 * back in via "Caused by:".
 */
fun Throwable.withRedactedTelegramToken(): Throwable =
    RuntimeException(redactTelegramToken(message ?: toString())).also { it.stackTrace = this.stackTrace }

@Serializable
private data class TgResponse(
    val ok: Boolean,
    val description: String? = null,
    val result: JsonElement? = null,
)

@Serializable
private data class SendMessageRequest(
    @SerialName("chat_id") val chatId: String,
    val text: String,
    @SerialName("reply_markup") val replyMarkup: JsonElement? = null,
)

@Serializable
private data class InlineKeyboardMarkup(
    @SerialName("inline_keyboard") val inlineKeyboard: List<List<InlineKeyboardButton>>,
)

@Serializable
private data class ForceReplyMarkup(
    @SerialName("force_reply") val forceReply: Boolean = true,
    val selective: Boolean = false,
)

@Serializable
private data class InlineKeyboardButton(
    val text: String,
    @SerialName("callback_data") val callbackData: String,
)

@Serializable
private data class EditMessageRequest(
    @SerialName("chat_id") val chatId: String,
    @SerialName("message_id") val messageId: Long,
    val text: String,
)

@Serializable
private data class AnswerCallbackRequest(
    @SerialName("callback_query_id") val callbackQueryId: String,
    val text: String? = null,
)

@Serializable
private data class SetWebhookRequest(
    val url: String,
    @SerialName("secret_token") val secretToken: String,
    @SerialName("allowed_updates") val allowedUpdates: List<String> = listOf("message", "callback_query"),
)

@Serializable
private data class BotCommandPayload(val command: String, val description: String)

@Serializable
private data class SetMyCommandsRequest(val commands: List<BotCommandPayload>)

class HttpTelegramClient(
    private val http: HttpClient,
    private val tokenProvider: () -> String?,
    private val baseUrl: String = "https://api.telegram.org",
) : TelegramClient {

    override suspend fun sendMessage(
        chatId: String,
        text: String,
        keyboard: List<List<Button>>?,
        forceReply: Boolean,
        selective: Boolean,
    ): Long {
        val markup: JsonElement? = when {
            keyboard != null -> API_JSON.encodeToJsonElement(
                InlineKeyboardMarkup(keyboard.map { row -> row.map { InlineKeyboardButton(it.text, it.callbackData) } }),
            )
            forceReply -> API_JSON.encodeToJsonElement(ForceReplyMarkup(selective = selective))
            else -> null
        }
        val result = call("sendMessage", SendMessageRequest(chatId, text, markup))
        return result?.jsonObject?.get("message_id")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
    }

    override suspend fun editMessageText(chatId: String, messageId: Long, text: String) {
        call("editMessageText", EditMessageRequest(chatId, messageId, text))
    }

    override suspend fun answerCallbackQuery(callbackQueryId: String, text: String?) {
        call("answerCallbackQuery", AnswerCallbackRequest(callbackQueryId, text))
    }

    override suspend fun setWebhook(url: String, secretToken: String) {
        call("setWebhook", SetWebhookRequest(url, secretToken))
    }

    override suspend fun setMyCommands(commands: List<BotCommand>) {
        call(
            "setMyCommands",
            SetMyCommandsRequest(commands.map { BotCommandPayload(it.command.removePrefix("/"), it.description) }),
        )
    }

    override suspend fun getMe(): String {
        val result = call("getMe", JsonObject(emptyMap()))
        return result?.jsonObject?.get("username")?.jsonPrimitive?.content.orEmpty()
    }

    private suspend inline fun <reified T> call(method: String, body: T): JsonElement? {
        val token = tokenProvider() ?: throw TelegramTokenMissingException()
        val response: TgResponse = http.post("$baseUrl/bot$token/$method") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()
        if (!response.ok) throw TelegramApiException(response.description ?: "Telegram rejected $method")
        return response.result
    }
}
