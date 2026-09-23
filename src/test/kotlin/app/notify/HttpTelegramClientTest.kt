package app.notify

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpTelegramClientTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun client(body: String = """{"ok":true,"result":{"message_id":77,"username":"budget_bot"}}"""): TelegramClient {
        val engine = MockEngine { request ->
            requests += request
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(app.API_JSON) }
        }
        return HttpTelegramClient(http, { "bot-token" })
    }

    private fun lastBody(): String = (requests.last().body as TextContent).text

    private fun httpClient(engine: MockEngine): HttpClient = HttpClient(engine) {
        install(ContentNegotiation) { json(app.API_JSON) }
    }

    private val jsonHeaders = headersOf("Content-Type", ContentType.Application.Json.toString())

    @Test
    fun `sendMessage posts to the token-scoped url and returns the message id`() {
        val messageId = runBlocking { client().sendMessage("12345", "hello") }
        assertEquals(77, messageId)
        assertTrue(requests.last().url.encodedPath.endsWith("/botbot-token/sendMessage"), requests.last().url.toString())
        assertTrue(lastBody().contains("\"chat_id\":\"12345\""), lastBody())
        assertTrue(lastBody().contains("hello"))
        // reply_markup must be ABSENT, not null. Telegram answers "Bad Request: object
        // expected as reply markup" for an explicit null, which silently broke every
        // plain-text message the bot sent in production. This assertion only means
        // anything because the client is now built from app.API_JSON, the same
        // configuration production uses.
        assertTrue(!lastBody().contains("reply_markup"), lastBody())
    }

    @Test
    fun `a keyboard is serialized as an inline keyboard`() {
        runBlocking {
            client().sendMessage("1", "pick", listOf(listOf(Button("🏠 Home", "mcc:5712:cat:3"))))
        }
        val body = lastBody()
        assertTrue(body.contains("inline_keyboard"), body)
        assertTrue(body.contains("callback_data"))
        assertTrue(body.contains("mcc:5712:cat:3"))
    }

    @Test
    fun `forceReply is serialised into the request body`() = runBlocking {
        var body = ""
        val engine = MockEngine { request ->
            body = (request.body as TextContent).text
            respond("""{"ok":true,"result":{"message_id":11}}""", headers = jsonHeaders)
        }
        val client = HttpTelegramClient(httpClient(engine), { "bot-token" })

        client.sendMessage("42", "Введи назву", forceReply = true)

        assertTrue(body.contains("\"force_reply\":true"), body)
        assertTrue(!body.contains("inline_keyboard"), body)
    }

    @Test
    fun `setWebhook passes the secret token`() {
        runBlocking { client().setWebhook("https://app.fly.dev/tg/updates", "s3cret") }
        val body = lastBody()
        assertTrue(requests.last().url.encodedPath.endsWith("/setWebhook"))
        assertTrue(body.contains("secret_token"), body)
        assertTrue(body.contains("s3cret"))
    }

    @Test
    fun `getMe returns the bot username`() {
        assertEquals("budget_bot", runBlocking { client().getMe() })
    }

    @Test
    fun `a missing token fails fast instead of calling the api`() {
        val engine = MockEngine { respond("{}", HttpStatusCode.OK) }
        val telegram = HttpTelegramClient(HttpClient(engine), { null })
        assertFailsWith<TelegramTokenMissingException> { runBlocking { telegram.sendMessage("1", "x") } }
    }

    @Test
    fun `an api error is raised, not swallowed`() {
        val telegram = client("""{"ok":false,"description":"chat not found"}""")
        val error = assertFailsWith<RuntimeException> { runBlocking { telegram.sendMessage("1", "x") } }
        assertTrue(error.message!!.contains("chat not found"))
    }
}
