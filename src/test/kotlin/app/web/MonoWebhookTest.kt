package app.web

import app.ingest.WebhookEventRepository
import app.withTestDb
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MonoWebhookTest {

    private val payload = """
        {"type":"StatementItem","data":{"account":"acc-uah","statementItem":{
          "id":"t1","time":1787011200,"description":"ATB","mcc":5411,"originalMcc":5411,
          "hold":false,"amount":-84000,"operationAmount":-84000,"currencyCode":980}}}
    """.trimIndent()

    @Test
    fun `GET on the webhook path returns 200 so Monobank accepts registration`() = withTestDb { db ->
        testApplication {
            application { routing { monoWebhookRoutes({ "s3cret" }, WebhookEventRepository(db)) } }
            val response = client.get("/webhook/s3cret")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("", response.bodyAsText())
        }
    }

    @Test
    fun `POST stores the raw payload and answers 200 immediately`() = withTestDb { db ->
        val events = WebhookEventRepository(db)
        testApplication {
            application { routing { monoWebhookRoutes({ "s3cret" }, events) } }
            val response = client.post("/webhook/s3cret") {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
        val stored = events.unprocessed()
        assertEquals(1, stored.size)
        assertTrue(stored.single().second.contains("\"id\":\"t1\""))
    }

    @Test
    fun `a wrong secret is rejected and stores nothing`() = withTestDb { db ->
        val events = WebhookEventRepository(db)
        testApplication {
            application { routing { monoWebhookRoutes({ "s3cret" }, events) } }
            assertEquals(HttpStatusCode.NotFound, client.post("/webhook/guess") {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }.status)
        }
        assertTrue(events.unprocessed().isEmpty())
    }

    @Test
    fun `an unconfigured secret rejects everything`() = withTestDb { db ->
        testApplication {
            application { routing { monoWebhookRoutes({ null }, WebhookEventRepository(db)) } }
            assertEquals(HttpStatusCode.NotFound, client.get("/webhook/anything").status)
        }
    }

    @Test
    fun `malformed json is still stored and still answered with 200`() = withTestDb { db ->
        val events = WebhookEventRepository(db)
        testApplication {
            application { routing { monoWebhookRoutes({ "s3cret" }, events) } }
            val response = client.post("/webhook/s3cret") {
                contentType(ContentType.Application.Json)
                setBody("not json at all")
            }
            // Answering anything but 200 three times in a row makes Monobank
            // disable the webhook permanently, so we never fail here.
            assertEquals(HttpStatusCode.OK, response.status)
        }
        assertEquals(1, events.unprocessed().size)
    }

    @Test
    fun `a throwing onReceived does not affect the response and the payload is still stored`() = withTestDb { db ->
        val events = WebhookEventRepository(db)
        testApplication {
            application {
                routing {
                    monoWebhookRoutes(
                        { "s3cret" },
                        events,
                        onReceived = { throw IllegalStateException("boom") },
                    )
                }
            }
            val response = client.post("/webhook/s3cret") {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            // The response was already committed before onReceived ran, so a throwing
            // callback must not change what Monobank sees.
            assertEquals(HttpStatusCode.OK, response.status)
        }
        assertEquals(1, events.unprocessed().size)
    }
}
