package app.notify

import app.budget.CategorySpending
import app.budget.MonthSummary
import app.budget.formatMinor
import app.budget.thresholdsCrossed
import app.db.SettingKeys
import app.db.SettingsRepository
import app.i18n.copy
import org.slf4j.LoggerFactory

class Notifier(
    private val telegram: TelegramClient,
    private val settings: SettingsRepository,
    private val events: NotificationEventRepository,
) {
    private val log = LoggerFactory.getLogger(Notifier::class.java)

    suspend fun checkThresholds(summary: MonthSummary) {
        // No chat means nobody to tell. Claiming now would silently burn the threshold
        // for the whole month, so we do not claim at all.
        val chatId = settings.get(SettingKeys.TELEGRAM_CHAT_ID) ?: return

        for (row in summary.categories) {
            val category = row.category
            // A category the owner marked "not counted as spending" holds money that
            // moved between his own accounts — alerting on it would be a threshold
            // notification about a number the dashboard itself no longer claims.
            if (!category.enabled || category.monthlyLimitMinor <= 0 || !category.countsAsSpending) continue

            val crossed = thresholdsCrossed(row.spentMinor, category.monthlyLimitMinor, category.thresholdPct)
                .filter { if (it >= 100) category.notifyExceeded else category.notifyWarning }
            if (crossed.isEmpty()) continue

            try {
                val claimed = crossed.filter { events.claim(category.id, summary.month, it) }
                if (claimed.isEmpty()) continue

                // One payment can jump both thresholds. Announce only the most severe;
                // the lower one is already claimed and will not fire later.
                val toSend = claimed.max()
                try {
                    telegram.sendMessage(chatId, renderMessage(settings.copy(), row, toSend))
                } catch (e: Exception) {
                    log.warn("Telegram send failed, releasing claims for category {}", category.id, e.withRedactedTelegramToken())
                    claimed.forEach {
                        runCatching { events.release(category.id, summary.month, it) }
                            .onFailure { failure -> log.warn("failed to release claim", failure) }
                    }
                }
            } catch (e: Exception) {
                log.warn("threshold check failed for category {}", category.id, e)
            }
        }
    }

    private fun renderMessage(copy: app.i18n.Copy, row: CategorySpending, threshold: Int): String {
        val category = row.category
        return if (threshold >= 100) {
            copy.budgetExceeded(
                category = category.label,
                limit = formatMinor(category.monthlyLimitMinor),
                spent = formatMinor(row.spentMinor),
                over = formatMinor(row.spentMinor - category.monthlyLimitMinor),
            )
        } else {
            copy.budgetWarning(
                pct = threshold,
                category = category.label,
                limit = formatMinor(category.monthlyLimitMinor),
                spent = formatMinor(row.spentMinor),
                left = formatMinor(category.monthlyLimitMinor - row.spentMinor),
            )
        }
    }
}
