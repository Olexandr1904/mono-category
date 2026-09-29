package app.notify

import app.budget.KYIV
import app.budget.MonthSummary
import app.db.Crypto
import app.db.SettingKeys
import app.db.SettingsRepository
import app.i18n.UkCopy
import app.withTestDb
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MonthlyReportServiceTest {

    private fun settings(db: Database, withChat: Boolean = true): SettingsRepository {
        val repo = SettingsRepository(db, Crypto(ByteArray(32) { it.toByte() }))
        if (withChat) repo.set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        return repo
    }

    private fun at(day: Int, hour: Int, minute: Int = 0, month: Int = 10): Clock =
        Clock.fixed(LocalDateTime.of(2026, month, day, hour, minute).atZone(KYIV).toInstant(), KYIV)

    /** One service across several ticks: what it remembers between them is part of the test. */
    private class TickingClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = KYIV
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    private fun summary(month: String, total: Long) = MonthSummary(month, total, 0L, emptyList())

    /** Calls in the order they happened, so a test can say the sync came before the read. */
    private val calls = mutableListOf<String>()

    private fun service(
        telegram: FakeTelegramClient,
        settings: SettingsRepository,
        clock: Clock,
        syncFails: Boolean = false,
        duringSync: () -> Unit = {},
    ) = MonthlyReportService(
        telegram = telegram,
        settings = settings,
        summaryOf = { month ->
            calls += "summary:$month"
            summary(month, if (month == "2026-09") 4_230_000L else 4_540_000L)
        },
        syncMonth = { month ->
            calls += "sync:$month"
            duringSync()
            if (syncFails) throw IllegalStateException("monobank is down")
        },
        clock = clock,
    )

    @Test
    fun `on the first of the month at nine the previous month's report is sent`() = withTestDb { db ->
        val telegram = FakeTelegramClient()
        runBlocking { service(telegram, settings(db), at(1, 9, 5)).sendIfDue() }
        assertEquals(1, telegram.sent.size)
        assertTrue(telegram.sent.single().text.contains(UkCopy.monthLabel("2026-09")), telegram.sent.single().text)
    }

    @Test
    fun `before nine nothing is sent`() = withTestDb { db ->
        val telegram = FakeTelegramClient()
        runBlocking { service(telegram, settings(db), at(1, 8, 59)).sendIfDue() }
        assertEquals(0, telegram.sent.size)
    }

    /**
     * A restart mid-month must not send a stale recap, and neither must the first deploy of
     * this feature, which would otherwise find no record of any report ever being sent.
     */
    @Test
    fun `with no recap ever sent, nothing goes out on any day but the first`() = withTestDb { db ->
        val telegram = FakeTelegramClient()
        runBlocking { service(telegram, settings(db), at(2, 9, 5)).sendIfDue() }
        runBlocking { service(telegram, settings(db), at(29, 12, 0, month = 9)).sendIfDue() }
        assertEquals(0, telegram.sent.size)
    }

    @Test
    fun `later ticks on the same day do not send it again`() = withTestDb { db ->
        val telegram = FakeTelegramClient()
        val repo = settings(db)
        runBlocking {
            service(telegram, repo, at(1, 9, 5)).sendIfDue()
            service(telegram, repo, at(1, 10, 5)).sendIfDue()
            service(telegram, repo, at(1, 23, 5)).sendIfDue()
        }
        assertEquals(1, telegram.sent.size)
    }

    /** Claiming with nobody to tell would burn the month's report silently — Notifier's rule. */
    @Test
    fun `with no paired chat nothing is claimed`() = withTestDb { db ->
        val telegram = FakeTelegramClient()
        val repo = settings(db, withChat = false)
        runBlocking { service(telegram, repo, at(1, 9, 5)).sendIfDue() }
        assertNull(repo.get(SettingKeys.MONTHLY_REPORT_SENT))
        assertEquals(emptyList(), calls, "no chat, so no reason to pull a month from Monobank")

        repo.set(SettingKeys.TELEGRAM_CHAT_ID, "12345")
        runBlocking { service(telegram, repo, at(1, 10, 5)).sendIfDue() }
        assertEquals(1, telegram.sent.size)
    }

    @Test
    fun `a failed send is retried on the next tick`() = withTestDb { db ->
        val telegram = FakeTelegramClient(failSend = true)
        val repo = settings(db)
        runBlocking { service(telegram, repo, at(1, 9, 5)).sendIfDue() }
        assertEquals(0, telegram.sent.size)

        telegram.failNextSends(false)
        runBlocking { service(telegram, repo, at(1, 10, 5)).sendIfDue() }
        assertEquals(1, telegram.sent.size)
    }

    /** Late-settling transactions of the 30th and any missed webhook land before the figures are read. */
    @Test
    fun `the previous month is synced before it is summarised`() = withTestDb { db ->
        runBlocking { service(FakeTelegramClient(), settings(db), at(1, 9, 5)).sendIfDue() }
        // The month before is read too: the comparison needs its total.
        assertEquals(listOf("sync:2026-09", "summary:2026-09", "summary:2026-08"), calls)
    }

    /** Webhooks normally carry the whole month already — a Monobank outage is no reason to stay silent. */
    @Test
    fun `a failed sync still sends the report`() = withTestDb { db ->
        val telegram = FakeTelegramClient()
        runBlocking { service(telegram, settings(db), at(1, 9, 5), syncFails = true).sendIfDue() }
        assertEquals(1, telegram.sent.size)
    }

    @Test
    fun `nothing is synced or read when it is not due`() = withTestDb { db ->
        runBlocking { service(FakeTelegramClient(), settings(db), at(1, 8, 0)).sendIfDue() }
        assertEquals(emptyList(), calls)
    }

    /** Every later tick of the 1st would otherwise re-run a multi-minute rate-limited pull. */
    @Test
    fun `once sent, later ticks neither sync nor read`() = withTestDb { db ->
        val repo = settings(db)
        runBlocking { service(FakeTelegramClient(), repo, at(1, 9, 5)).sendIfDue() }
        calls.clear()
        runBlocking { service(FakeTelegramClient(), repo, at(1, 10, 5)).sendIfDue() }
        assertEquals(emptyList(), calls)
    }

    /**
     * The sync waits out Monobank's rate limit for minutes; a re-pairing in that window must
     * not send the recap to the chat the bot just left.
     */
    @Test
    fun `the recap goes to the chat paired when it is sent, not when the sync began`() = withTestDb { db ->
        val telegram = FakeTelegramClient()
        val repo = settings(db)
        runBlocking {
            service(telegram, repo, at(1, 9, 5), duringSync = { repo.set(SettingKeys.TELEGRAM_CHAT_ID, "999") }).sendIfDue()
        }
        assertEquals("999", telegram.sent.single().chatId)
    }

    /**
     * A 1st lost to downtime is caught up on the 2nd or 3rd — but only once some recap has
     * gone out before, so the feature's first deploy still sends nothing stale.
     */
    @Test
    fun `a missed first is caught up on the second`() = withTestDb { db ->
        val telegram = FakeTelegramClient()
        val repo = settings(db)
        repo.set(SettingKeys.MONTHLY_REPORT_SENT, "2026-08")
        runBlocking { service(telegram, repo, at(2, 0, 30)).sendIfDue() }
        assertEquals(0, telegram.sent.size, "a catch-up is still no reason to buzz a phone at night")
        runBlocking { service(telegram, repo, at(2, 9, 5)).sendIfDue() }
        assertEquals(1, telegram.sent.size)
        assertEquals("2026-09", repo.get(SettingKeys.MONTHLY_REPORT_SENT))
    }

    @Test
    fun `after the third a missed recap is not sent at all`() = withTestDb { db ->
        val telegram = FakeTelegramClient()
        val repo = settings(db)
        repo.set(SettingKeys.MONTHLY_REPORT_SENT, "2026-08")
        runBlocking { service(telegram, repo, at(4, 9, 5)).sendIfDue() }
        assertEquals(0, telegram.sent.size)
    }

    /** A send that keeps failing (bot kicked from the group) must not re-pull the month every hour. */
    @Test
    fun `a retry after a failed send does not sync again`() = withTestDb { db ->
        val telegram = FakeTelegramClient(failSend = true)
        val clock = TickingClock(at(1, 9, 5).instant())
        val svc = service(telegram, settings(db), clock)
        runBlocking { svc.sendIfDue() }
        clock.now = at(1, 10, 5).instant()
        telegram.failNextSends(false)
        runBlocking { svc.sendIfDue() }
        assertEquals(1, telegram.sent.size)
        assertEquals(1, calls.count { it.startsWith("sync:") }, calls.toString())
    }
}
