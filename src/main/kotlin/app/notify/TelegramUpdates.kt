package app.notify

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.Base64

@Serializable
data class TgChat(val id: Long, val type: String? = null) {
    /**
     * Telegram sends "private", "group", "supergroup" or "channel". The question asked
     * everywhere is whether this is a *private* chat, because that is the one place the bot
     * may answer a message it does not recognise — a room with other people in it must not
     * be told the command list every time someone types a slash. Asking the opposite
     * ("is it a group?") quietly filed "channel", and any type Telegram adds later, under
     * private; a payload with no type at all is the one case read as private, since that is
     * how every private-chat fixture and every pre-group caller looks.
     */
    val isPrivate: Boolean get() = type == null || type == "private"
}

@Serializable
data class TgMessage(
    @SerialName("message_id") val messageId: Long,
    val chat: TgChat,
    val text: String? = null,
    @SerialName("reply_to_message") val replyToMessage: TgMessage? = null,
    val from: TgUser? = null,

    /**
     * Set on the service message Telegram posts in a basic group at the moment it is
     * upgraded to a supergroup, and carries the chat's new id. The old id stops accepting
     * sendMessage, so a paired chat that ignores this goes permanently silent with nothing
     * to show for it.
     */
    @SerialName("migrate_to_chat_id") val migrateToChatId: Long? = null,
)

/**
 * Whoever sent a message or pressed a button. Used only to *address* a person — to mention
 * them in a question aimed at them, and to say who changed a limit — never to authorise
 * one. Callbacks are authorised per chat, which is exactly what lets anyone in the shared
 * group answer a category question.
 */
@Serializable
data class TgUser(
    val id: Long,
    val username: String? = null,
    @SerialName("first_name") val firstName: String = "",
) {
    /** What a confirmation calls this person. Blank when Telegram sent us neither, in
     *  which case the message says what changed and simply does not say by whom. */
    val displayName: String get() = username ?: firstName
}

@Serializable
data class TgCallbackQuery(
    val id: String,
    val data: String? = null,
    val message: TgMessage? = null,
    val from: TgUser? = null,
)

@Serializable
data class TgUpdate(
    @SerialName("update_id") val updateId: Long,
    val message: TgMessage? = null,
    @SerialName("callback_query") val callbackQuery: TgCallbackQuery? = null,
)

/**
 * Told when the bot changes which chat it talks to, so whatever is holding an unanswered
 * conversation in the old one can bring it along. Not called for a first pairing (there is
 * no chat to move out of) nor for a supergroup upgrade (the room and its message ids
 * survive that) — only for a deliberate re-pairing into a different chat.
 *
 * The invariant it exists to keep: an open question's `message_id` always refers to the
 * chat the bot is paired with right now. That is why nothing here stores a chat id.
 */
interface ChatMoveObserver {
    suspend fun onChatMoved(from: String, to: String)

    object NoOp : ChatMoveObserver {
        override suspend fun onChatMoved(from: String, to: String) = Unit
    }
}

/**
 * The two entry points `/limit` needs from the update handler: the command itself, and the
 * chance to claim a reply before it is read as anything else.
 */
interface LimitCommands {
    suspend fun start(chatId: String)

    /** True when [message] was an answer to an open "what amount?" question. */
    suspend fun handleAmountReply(message: TgMessage): Boolean

    object NoOp : LimitCommands {
        override suspend fun start(chatId: String) = Unit
        override suspend fun handleAmountReply(message: TgMessage) = false
    }
}

/** Implemented in Task 18. Returns true when the callback was recognised. */
interface CallbackHandler {
    suspend fun handle(query: TgCallbackQuery): Boolean

    object NoOp : CallbackHandler {
        override suspend fun handle(query: TgCallbackQuery) = false
    }
}

fun generateSecret(bytes: Int = 24): String {
    val raw = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
}

/** Short, unambiguous, easy to retype on a phone. */
fun generatePairingCode(length: Int = 6): String {
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val random = SecureRandom()
    return (1..length).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
}

/** How long a freshly generated pairing code stays usable. */
const val PAIRING_TTL_SECONDS = 15L * 60

/** Wrong guesses that burn the code and force a new one to be generated. */
const val MAX_PAIRING_ATTEMPTS = 5
