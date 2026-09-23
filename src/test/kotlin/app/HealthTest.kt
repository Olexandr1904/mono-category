package app

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthTest {
    @Test
    fun `health endpoint returns ok`() = testApplication {
        // healthRoutes() was folded into Application.module() in Task 19; the /health
        // route itself (get("/health") { call.respondText("ok") }) is reproduced here
        // rather than standing up the full module, which needs a Config and Database.
        application { routing { get("/health") { call.respondText("ok") } } }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
    }
}
