package app.notify

import org.slf4j.LoggerFactory

/**
 * Tries each handler until one claims the query. Returning false from all of them is what
 * makes [TelegramUpdateHandler] answer the callback itself, so a button nobody recognises
 * still stops spinning instead of hanging forever.
 */
class CompositeCallbackHandler(private val handlers: List<CallbackHandler>) : CallbackHandler {
    override suspend fun handle(query: TgCallbackQuery): Boolean = handlers.any { it.handle(query) }
}

/**
 * Runs every observer, unlike [CompositeCallbackHandler]. A move is not a question being
 * claimed by one of them: each has its own cleanup to do, and one that throws must not
 * cancel the rest. A failed re-home stranding one service's prompts is bad — that same
 * failure also skipping another service's cleanup is worse, and invisible.
 */
class CompositeChatMoveObserver(private val observers: List<ChatMoveObserver>) : ChatMoveObserver {
    private val log = LoggerFactory.getLogger(CompositeChatMoveObserver::class.java)

    override suspend fun onChatMoved(from: String, to: String) {
        for (observer in observers) {
            runCatching { observer.onChatMoved(from, to) }
                .onFailure { log.warn("chat move observer failed", it.withRedactedTelegramToken()) }
        }
    }
}
