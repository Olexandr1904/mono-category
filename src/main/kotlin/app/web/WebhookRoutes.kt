package app.web

import app.ingest.WebhookEventRepository
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import org.slf4j.LoggerFactory
import java.security.MessageDigest

private val webhookLog = LoggerFactory.getLogger("app.web.WebhookRoutes")

/**
 * A Monobank statement item is a few hundred bytes and a Telegram update a few kilobytes.
 * The cap exists because [WebhookEventRepository.record] writes the body straight to disk
 * on a 1 GB Fly volume: whoever holds the webhook secret could otherwise fill it, taking
 * the database — not just the webhook — down with it.
 */
private const val MAX_WEBHOOK_BODY_BYTES = 64 * 1024

/**
 * Reads at most [maxBytes], returning null when the body is larger. The declared
 * Content-Length is only a shortcut for the common case; the read is capped
 * independently, because a chunked request declares no length at all and a hostile one
 * could lie about it.
 */
private suspend fun ApplicationCall.receiveTextLimited(maxBytes: Int): String? {
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > maxBytes) return null

    // Read one byte past the cap: if that byte materialises, the body is over the limit
    // and the rest is never pulled off the socket.
    val bytes = request.receiveChannel().readRemaining(maxBytes + 1L).readByteArray()
    if (bytes.size > maxBytes) return null
    return String(bytes, Charsets.UTF_8)
}

/**
 * Monobank's personal API does not sign requests; the documented protection is a
 * random path segment. Registration is verified with a GET that must answer 200.
 */
fun Route.monoWebhookRoutes(
    secretProvider: () -> String?,
    events: WebhookEventRepository,
    onReceived: () -> Unit = {},
) {
    get("/webhook/{secret}") {
        if (!call.secretMatches(secretProvider())) return@get call.respond(HttpStatusCode.NotFound)
        call.respondText("", status = HttpStatusCode.OK)
    }

    post("/webhook/{secret}") {
        if (!call.secretMatches(secretProvider())) return@post call.respond(HttpStatusCode.NotFound)
        val body = call.receiveTextLimited(MAX_WEBHOOK_BODY_BYTES)
            ?: return@post call.respond(HttpStatusCode.PayloadTooLarge)
        events.record(body)
        // Answer first, then process. Monobank allows 5 seconds and disables the webhook
        // after three consecutive failures, so nothing that can throw or block may run
        // before the response. The callback is guarded for the same reason.
        call.respondText("", status = HttpStatusCode.OK)
        runCatching { onReceived() }.onFailure { webhookLog.warn("post-response webhook processing failed", it) }
    }
}

private fun ApplicationCall.secretMatches(expected: String?): Boolean {
    if (expected.isNullOrBlank()) return false
    val provided = parameters["secret"].orEmpty()
    return MessageDigest.isEqual(provided.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
}

/**
 * Telegram cannot authenticate either. Its documented protection is the secret_token
 * echoed back in a header — safer than a secret in the path, which leaks into proxy
 * logs and browser history.
 */
fun Route.telegramWebhookRoutes(
    secretProvider: () -> String?,
    handler: app.notify.TelegramUpdateHandler,
) {
    post("/tg/updates") {
        val expected = secretProvider()
        val provided = call.request.headers["X-Telegram-Bot-Api-Secret-Token"].orEmpty()
        if (expected.isNullOrBlank() ||
            !MessageDigest.isEqual(provided.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
        ) {
            return@post call.respond(HttpStatusCode.Unauthorized)
        }

        val body = call.receiveTextLimited(MAX_WEBHOOK_BODY_BYTES)
            ?: return@post call.respond(HttpStatusCode.PayloadTooLarge)
        // Telegram retries on any non-200, so parse failures must not surface as errors.
        runCatching {
            val update = TELEGRAM_JSON.decodeFromString<app.notify.TgUpdate>(body)
            handler.handle(update)
        }.onFailure { webhookLog.warn("failed to handle Telegram update", it) }

        call.respondText("", status = HttpStatusCode.OK)
    }
}

private val TELEGRAM_JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
