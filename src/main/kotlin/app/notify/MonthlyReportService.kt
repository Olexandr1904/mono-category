package app.notify

import app.budget.KYIV
import app.budget.MonthSummary
import app.budget.currentMonthKey
import app.budget.previousMonthKey
import app.db.SettingKeys
import app.db.SettingsRepository
import app.i18n.copy
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.ZonedDateTime

/**
 * Sends the recap of the month just closed, once, on the 1st from [SEND_FROM_HOUR] Kyiv time.
 * Called from the hourly loop, so "due" is a window the ticks land in rather than an instant.
 *
 * A 1st lost to downtime or a failing Telegram is caught up through day [CATCH_UP_LAST_DAY],
 * still never before [SEND_FROM_HOUR], and only when some earlier recap was sent — so the
 * feature's first deploy, which finds no record at all, opens with nothing stale. Past
 * that, a recap is old news and is dropped.
 *
 * Claim-then-send like [Notifier]: [SettingKeys.MONTHLY_REPORT_SENT] is written before the
 * message goes out and put back if it fails, so the next tick retries. No chat means no
 * claim — claiming with nobody to tell would burn the month's recap silently.
 */
class MonthlyReportService(
    private val telegram: TelegramClient,
    private val settings: SettingsRepository,
    private val summaryOf: (String) -> MonthSummary,
    private val syncMonth: suspend (String) -> Unit,
    private val clock: Clock = Clock.system(KYIV),
) {
    private val log = LoggerFactory.getLogger(MonthlyReportService::class.java)

    /** The month whose pre-recap sync already ran, so a failing send retries without re-pulling it. */
    private var syncedMonth: String? = null

    suspend fun sendIfDue() {
        val now = ZonedDateTime.now(clock.withZone(KYIV))
        val lastSent = settings.get(SettingKeys.MONTHLY_REPORT_SENT)
        val due = now.hour >= SEND_FROM_HOUR && when (now.dayOfMonth) {
            1 -> true
            in 2..CATCH_UP_LAST_DAY -> lastSent != null
            else -> false
        }
        if (!due) return

        val month = previousMonthKey(currentMonthKey(clock))
        if (lastSent == month) return
        if (!settings.isSet(SettingKeys.TELEGRAM_CHAT_ID)) return

        // Before reading the figures: a card payment on the last evening can settle
        // overnight, and a webhook Monobank gave up on is only recovered by a statement pull.
        // SyncService folds Monobank failures into its report rather than throwing, so what
        // lands here is a local failure. Either way the recap goes out on what is stored —
        // webhooks normally carry the whole month already.
        if (syncedMonth != month) {
            try {
                syncMonth(month)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("pre-recap sync of {} failed, sending on stored data", month, e)
            }
            syncedMonth = month
        }

        // Read after the sync, not before: it can wait out Monobank's rate limit for
        // minutes, and a re-pairing in that window must not send the recap to the chat the
        // bot just left.
        val chatId = settings.get(SettingKeys.TELEGRAM_CHAT_ID) ?: return
        val copy = settings.copy()
        val text = renderMonthlyReport(
            summaryOf(month),
            previousTotalMinor = summaryOf(previousMonthKey(month)).totalSpentMinor,
            copy = copy,
        )

        settings.set(SettingKeys.MONTHLY_REPORT_SENT, month)
        try {
            telegram.sendMessage(chatId, text)
        } catch (e: CancellationException) {
            release(lastSent)
            throw e
        } catch (e: Exception) {
            log.warn("monthly recap send failed, will retry next tick", e.withRedactedTelegramToken())
            release(lastSent)
        }
    }

    private fun release(previous: String?) {
        runCatching {
            if (previous == null) settings.delete(SettingKeys.MONTHLY_REPORT_SENT)
            else settings.set(SettingKeys.MONTHLY_REPORT_SENT, previous)
        }.onFailure { log.warn("failed to release the monthly recap claim", it) }
    }

    private companion object {
        const val SEND_FROM_HOUR = 9
        const val CATCH_UP_LAST_DAY = 3
    }
}
