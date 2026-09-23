package app.notify

import app.budget.Category
import app.budget.CategorySpending
import app.budget.MonthSummary
import app.budget.SpendStatus
import app.db.Crypto
import app.db.SettingKeys
import app.db.SettingsRepository
import app.i18n.EnCopy
import app.i18n.Language
import app.i18n.UkCopy
import app.i18n.setLanguage
import app.withTestDb
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotifierTest {

    private fun settings(db: Database, withChat: Boolean = true): SettingsRepository {
        val repo = SettingsRepository(db, Crypto(ByteArray(32) { it.toByte() }))
        if (withChat) repo.set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        return repo
    }

    private fun summary(spentMinor: Long, limitMinor: Long = 1_500_000, pct: Int = 80) =
        MonthSummary(
            month = "2026-08",
            totalSpentMinor = spentMinor,
            uncategorizedMinor = 0,
            categories = listOf(
                CategorySpending(
                    category = Category(1L, "Restaurants", "🍔", limitMinor, pct),
                    spentMinor = spentMinor,
                    pct = if (limitMinor > 0) ((spentMinor * 100) / limitMinor).toInt() else 0,
                    status = SpendStatus.WARNING,
                ),
            ),
        )

    @Test
    fun `crossing the warning threshold sends exactly one message`() = withTestDb { db ->
        val telegram = FakeTelegramClient()
        val notifier = Notifier(telegram, settings(db), NotificationEventRepository(db))
        runBlocking { notifier.checkThresholds(summary(1_200_000)) }
        assertEquals(1, telegram.sent.size)
        assertTrue(telegram.sent.single().text.contains("80%"))
    }

    @Test
    fun `rising spending never repeats the same threshold`() = withTestDb { db ->
        val telegram = FakeTelegramClient()
        val notifier = Notifier(telegram, settings(db), NotificationEventRepository(db))
        runBlocking {
            notifier.checkThresholds(summary(1_400_000))
            notifier.checkThresholds(summary(1_410_000))
            notifier.checkThresholds(summary(1_420_000))
        }
        assertEquals(1, telegram.sent.size)
    }

    @Test
    fun `jumping past both thresholds sends only the exceeded message`() = withTestDb { db ->
        val telegram = FakeTelegramClient()
        val notifier = Notifier(telegram, settings(db), NotificationEventRepository(db))
        runBlocking { notifier.checkThresholds(summary(1_800_000)) }
        assertEquals(1, telegram.sent.size)
        val exceededHeadline = UkCopy.budgetExceeded("🍔 Restaurants", "L", "S", "O").substringBefore("\n\n")
        assertTrue(telegram.sent.single().text.contains(exceededHeadline), telegram.sent.single().text)
    }

    @Test
    fun `a Telegram alert arrives in the configured language`() = withTestDb { db ->
        val telegram = FakeTelegramClient()
        val settingsRepo = settings(db)
        settingsRepo.setLanguage(Language.EN)
        val notifier = Notifier(telegram, settingsRepo, NotificationEventRepository(db))
        runBlocking { notifier.checkThresholds(summary(1_200_000)) }

        assertEquals(1, telegram.sent.size)
        val warningHeadline = EnCopy.budgetWarning(80, "🍔 Restaurants", "L", "S", "O").substringBefore("\n\n")
        assertTrue(telegram.sent.single().text.contains(warningHeadline), telegram.sent.single().text)
        // Not the Ukrainian wording — the whole point is that the bot follows the setting.
        val ukrainianHeadline = UkCopy.budgetWarning(80, "🍔 Restaurants", "L", "S", "O").substringBefore("\n\n")
        assertTrue(!telegram.sent.single().text.contains(ukrainianHeadline), telegram.sent.single().text)
    }

    @Test
    fun `a new month re-arms the thresholds`() = withTestDb { db ->
        val telegram = FakeTelegramClient()
        val notifier = Notifier(telegram, settings(db), NotificationEventRepository(db))
        runBlocking {
            notifier.checkThresholds(summary(1_200_000))
            notifier.checkThresholds(summary(1_200_000).copy(month = "2026-09"))
        }
        assertEquals(2, telegram.sent.size)
    }

    @Test
    fun `a failed send releases the claim so the next attempt retries`() = withTestDb { db ->
        val telegram = FakeTelegramClient(failSend = true)
        val notifier = Notifier(telegram, settings(db), NotificationEventRepository(db))
        runBlocking { notifier.checkThresholds(summary(1_200_000)) }
        assertEquals(0, telegram.sent.size)

        telegram.failNextSends(false)
        runBlocking { notifier.checkThresholds(summary(1_210_000)) }
        assertEquals(1, telegram.sent.size)
    }

    @Test
    fun `no chat means no claim is recorded`() = withTestDb { db ->
        val telegram = FakeTelegramClient()
        val events = NotificationEventRepository(db)
        val notifier = Notifier(telegram, settings(db, withChat = false), events)
        runBlocking { notifier.checkThresholds(summary(1_200_000)) }
        assertEquals(0, telegram.sent.size)
        assertTrue(events.claim(1L, "2026-08", 80), "threshold must still be claimable after pairing")
    }

    @Test
    fun `a category with notifications disabled stays silent`() = withTestDb { db ->
        val telegram = FakeTelegramClient()
        val notifier = Notifier(telegram, settings(db), NotificationEventRepository(db))
        val quiet = summary(1_200_000).let { base ->
            base.copy(categories = base.categories.map {
                it.copy(category = it.category.copy(notifyWarning = false))
            })
        }
        runBlocking { notifier.checkThresholds(quiet) }
        assertEquals(0, telegram.sent.size)
    }

    @Test
    fun `a disabled category stays silent`() = withTestDb { db ->
        val telegram = FakeTelegramClient()
        val notifier = Notifier(telegram, settings(db), NotificationEventRepository(db))
        val quiet = summary(1_800_000).let { base ->
            base.copy(categories = base.categories.map {
                it.copy(category = it.category.copy(enabled = false))
            })
        }
        runBlocking { notifier.checkThresholds(quiet) }
        assertEquals(0, telegram.sent.size)
    }

    @Test
    fun `a category marked not counted as spending never alerts, even past its limit`() = withTestDb { db ->
        val telegram = FakeTelegramClient()
        val notifier = Notifier(telegram, settings(db), NotificationEventRepository(db))
        val quiet = summary(1_800_000).let { base ->
            base.copy(categories = base.categories.map {
                it.copy(category = it.category.copy(countsAsSpending = false))
            })
        }
        runBlocking { notifier.checkThresholds(quiet) }
        assertEquals(0, telegram.sent.size)
    }

    @Test
    fun `claim is granted once and refused afterwards`() = withTestDb { db ->
        val events = NotificationEventRepository(db)
        assertTrue(events.claim(1L, "2026-08", 80))
        assertTrue(!events.claim(1L, "2026-08", 80))
        events.release(1L, "2026-08", 80)
        assertTrue(events.claim(1L, "2026-08", 80))
    }

    @Test
    fun `release removes only the exact claim`() = withTestDb { db ->
        val events = NotificationEventRepository(db)
        assertTrue(events.claim(1L, "2026-08", 80))
        assertTrue(events.claim(1L, "2026-09", 80))
        assertTrue(events.claim(1L, "2026-08", 100))

        events.release(1L, "2026-08", 80)

        assertTrue(events.claim(1L, "2026-08", 80), "the released claim is available again")
        assertTrue(!events.claim(1L, "2026-09", 80), "another month must be untouched")
        assertTrue(!events.claim(1L, "2026-08", 100), "another threshold must be untouched")
    }
}
