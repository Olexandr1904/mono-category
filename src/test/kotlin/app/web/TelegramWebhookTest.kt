package app.web

import app.budget.BudgetService
import app.budget.CategoryRepository
import app.budget.ConduitMccRepository
import app.budget.KYIV
import app.budget.TransactionRepository
import app.db.Crypto
import app.db.SettingKeys
import app.db.SettingsRepository
import app.notify.CallbackHandler
import app.notify.FakeTelegramClient
import app.notify.TelegramUpdateHandler
import app.notify.TgCallbackQuery
import app.withTestDb
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramWebhookTest {

    private val clock = Clock.fixed(Instant.ofEpochSecond(1_787_011_200), KYIV)
    private val update = """
        {"update_id":1,"message":{"message_id":9,"chat":{"id":12345},"text":"/help"}}
    """.trimIndent()

    @Test
    fun `a matching secret header is accepted and the update is handled`() = withTestDb { db ->
        val settings = SettingsRepository(db, Crypto(ByteArray(32) { it.toByte() }))
        settings.set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        val telegram = FakeTelegramClient()
        val handler = TelegramUpdateHandler(
            settings, telegram,
            BudgetService(CategoryRepository(db, ConduitMccRepository(db)), TransactionRepository(db)),
            clock = clock,
        )
        testApplication {
            application { routing { telegramWebhookRoutes({ "s3cret" }, handler) } }
            val response = client.post("/tg/updates") {
                header("X-Telegram-Bot-Api-Secret-Token", "s3cret")
                contentType(ContentType.Application.Json)
                setBody(update)
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
        assertTrue(telegram.sent.isNotEmpty())
    }

    @Test
    fun `a callback_query update is decoded with message_id and chat id intact`() = withTestDb { db ->
        val settings = SettingsRepository(db, Crypto(ByteArray(32) { it.toByte() }))
        settings.set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        val seen = mutableListOf<TgCallbackQuery>()
        val callbacks = object : CallbackHandler {
            override suspend fun handle(query: TgCallbackQuery): Boolean {
                seen += query
                return true
            }
        }
        val handler = TelegramUpdateHandler(
            settings, FakeTelegramClient(),
            BudgetService(CategoryRepository(db, ConduitMccRepository(db)), TransactionRepository(db)),
            callbacks, clock = clock,
        )
        val callbackUpdate = """
            {"update_id":2,"callback_query":{"id":"cb1","data":"mcc:5712:cat:3","message":{"message_id":424242,"chat":{"id":12345}}}}
        """.trimIndent()
        testApplication {
            application { routing { telegramWebhookRoutes({ "s3cret" }, handler) } }
            val response = client.post("/tg/updates") {
                header("X-Telegram-Bot-Api-Secret-Token", "s3cret")
                contentType(ContentType.Application.Json)
                setBody(callbackUpdate)
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
        assertEquals(424242L, seen.single().message?.messageId)
        assertEquals(12345L, seen.single().message?.chat?.id)
    }

    @Test
    fun `a wrong secret header is rejected with 401`() = withTestDb { db ->
        val settings = SettingsRepository(db, Crypto(ByteArray(32) { it.toByte() }))
        val telegram = FakeTelegramClient()
        val handler = TelegramUpdateHandler(
            settings, telegram,
            BudgetService(CategoryRepository(db, ConduitMccRepository(db)), TransactionRepository(db)),
            clock = clock,
        )
        testApplication {
            application { routing { telegramWebhookRoutes({ "s3cret" }, handler) } }
            val response = client.post("/tg/updates") {
                header("X-Telegram-Bot-Api-Secret-Token", "wrong")
                contentType(ContentType.Application.Json)
                setBody(update)
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
        assertTrue(telegram.sent.isEmpty())
    }

    @Test
    fun `a malformed update still returns 200 so Telegram stops retrying`() = withTestDb { db ->
        val settings = SettingsRepository(db, Crypto(ByteArray(32) { it.toByte() }))
        val handler = TelegramUpdateHandler(
            settings, FakeTelegramClient(),
            BudgetService(CategoryRepository(db, ConduitMccRepository(db)), TransactionRepository(db)),
            clock = clock,
        )
        testApplication {
            application { routing { telegramWebhookRoutes({ "s3cret" }, handler) } }
            val response = client.post("/tg/updates") {
                header("X-Telegram-Bot-Api-Secret-Token", "s3cret")
                contentType(ContentType.Application.Json)
                setBody("nonsense")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }
}
