package app

import app.db.connectDb
import app.db.runMigrations
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModuleSmokeTest {

    private val config = Config(
        adminPassword = "hunter2",
        encryptionKey = ByteArray(32) { it.toByte() },
        dbPath = "unused",
        port = 8080,
    )

    @Test
    fun `the assembled module serves health, login and the stylesheet`() {
        val dir = Files.createTempDirectory("smoke")
        try {
            val db = connectDb(dir.resolve("smoke.db").toString())
            runMigrations(db)
            testApplication {
                application { module(config, db) }
                val client = createClient { followRedirects = false }

                assertEquals(HttpStatusCode.OK, client.get("/health").status)
                assertEquals(HttpStatusCode.OK, client.get("/login").status)

                val css = client.get("/static/app.css")
                assertEquals(HttpStatusCode.OK, css.status)
                assertTrue(css.bodyAsText().contains("--bg"))

                // Every real page is behind the session gate.
                listOf("/", "/categories", "/transactions", "/settings").forEach { path ->
                    assertEquals(HttpStatusCode.Found, client.get(path).status, "$path must be protected")
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `Config rejects a key that is not 32 bytes`() {
        val error = runCatching {
            Config.fromEnv(mapOf("ADMIN_PASSWORD" to "x", "ENCRYPTION_KEY" to "c2hvcnQ="))
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException, "expected a clear failure, got $error")
    }

    @Test
    fun `Config explains which variable is missing`() {
        val error = runCatching { Config.fromEnv(emptyMap()) }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("ADMIN_PASSWORD"), error.message)
    }

    @Test
    fun `redactWebhookPath hides the Monobank secret from the access log`() {
        assertEquals("/webhook/***", redactWebhookPath("/webhook/s3cret-value"))
        assertEquals("/health", redactWebhookPath("/health"))
        assertEquals("/settings", redactWebhookPath("/settings"))
    }
}
