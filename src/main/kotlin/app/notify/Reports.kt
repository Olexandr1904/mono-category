package app.notify

import app.budget.MonthSummary
import app.budget.SpendStatus
import app.budget.formatMinor
import app.budget.isTinyShare
import app.budget.sharePercent
import app.i18n.Copy
import kotlin.math.absoluteValue

/**
 * The chat's three reports. Pure functions over a [MonthSummary] so what they say can be
 * tested without a database, a bot or a clock — the handler's job is to pick the month and
 * send the string, not to decide what a category's line looks like.
 */

/** What has been spent, largest first. */
fun renderStatus(summary: MonthSummary, copy: Copy, monthLabel: String): String =
    "${copy.statusHeader(monthLabel, formatMinor(summary.totalSpentMinor))}\n\n${statusLines(summary, copy)}"

/** One line per category that spent anything — shared by /status and the monthly recap so the two never disagree. */
private fun statusLines(summary: MonthSummary, copy: Copy): String =
    summary.categories
        .filter { it.category.enabled }
        // Largest first, and nothing that cost nothing. /status is read on a phone in a few
        // seconds: a category at 0 ₴ says only that it exists, which the categories page
        // already says, and it pushes the figures that matter further down. Same ordering
        // as the dashboard, so the two never disagree.
        .filter { it.spentMinor != 0L }
        .sortedByDescending { it.spentMinor }
        .joinToString("\n") { row ->
            // A category with countsAsSpending = false never alerts (Notifier skips it
            // outright) and never counts toward summary.totalSpentMinor in the header above
            // — so it must not carry a ⚠️/🔔 mark here either, and the line needs its own
            // note, or the header and the largest line under it silently disagree by
            // however much this category spent.
            val mark = if (!row.category.countsAsSpending) "" else when (row.status) {
                SpendStatus.EXCEEDED -> " ⚠️"
                SpendStatus.WARNING -> " 🔔"
                SpendStatus.NORMAL -> ""
            }
            val line = if (row.category.monthlyLimitMinor > 0) {
                copy.statusLine(
                    row.category.label, formatMinor(row.spentMinor),
                    formatMinor(row.category.monthlyLimitMinor), row.pct, mark,
                )
            } else {
                copy.statusLineNoLimit(row.category.label, formatMinor(row.spentMinor))
            }
            if (row.category.countsAsSpending) line else "$line — ${copy.notCountedPill}"
        }
        // Last, whatever its size: it is not a category, and the header total includes it —
        // without this line the lines under the header add up to less than it does.
        .let { lines ->
            if (summary.uncategorizedMinor == 0L) lines
            else listOf(lines, copy.statusLineNoLimit(copy.uncategorizedLabel, formatMinor(summary.uncategorizedMinor)))
                .filter { it.isNotEmpty() }.joinToString("\n")
        }
        .ifBlank { copy.noCategoriesYetStatus }

/**
 * How much room is left — the question /status does not answer, and the one a shared budget
 * asks out loud. Smallest remainder first, because that is the category about to become a
 * problem.
 *
 * Only categories with a limit can appear: one with no limit can never run out, and listing
 * them all would bury the ones that can. They get a count at the end instead.
 *
 * The countsAsSpending filter is not optional. spentByCategory still reports a self-transfer
 * category's real amount, and every reader drops it individually — a reader that forgets is
 * a figure that silently disagrees with /status.
 */
fun renderLeft(summary: MonthSummary, copy: Copy, monthLabel: String): String {
    val counted = summary.categories.filter { it.category.enabled && it.category.countsAsSpending }
    val withLimits = counted.filter { it.category.monthlyLimitMinor > 0 }
    val noLimitCount = counted.count { it.category.monthlyLimitMinor <= 0 }

    val body = withLimits
        .sortedBy { it.category.monthlyLimitMinor - it.spentMinor }
        .joinToString("\n") { row ->
            val limit = row.category.monthlyLimitMinor
            val remaining = limit - row.spentMinor
            if (remaining < 0) {
                copy.leftLineOver(row.category.label, formatMinor(-remaining), formatMinor(limit))
            } else {
                copy.leftLine(row.category.label, formatMinor(remaining), formatMinor(limit))
            }
        }
        .ifBlank { copy.leftNothingWithLimits }

    val tail = if (noLimitCount > 0) "\n\n${copy.leftNoLimitTail(noLimitCount)}" else ""
    return "${copy.leftHeader(monthLabel)}\n\n$body$tail"
}

/**
 * The recap sent on the 1st for the month just closed: /status's lines under a header that
 * compares the total with the month before, and a closing list of every limit that was
 * broken — the one question a finished month still answers.
 *
 * No comparison when the month before spent nothing (a percentage of zero is not a number)
 * or when the total did not move. The overspend list names only categories strictly over
 * their limit — rent landing on it to the kopeck is not overspending — and skips
 * countsAsSpending = false ones for the same reason [Notifier] never alerts on them.
 */
fun renderMonthlyReport(summary: MonthSummary, previousTotalMinor: Long, copy: Copy): String {
    val header = copy.monthlyReportHeader(copy.monthLabel(summary.month), formatMinor(summary.totalSpentMinor))
    val diff = summary.totalSpentMinor - previousTotalMinor
    val delta = if (previousTotalMinor <= 0L || diff == 0L) null else {
        val pct = if (isTinyShare(diff.absoluteValue, previousTotalMinor)) "<1%" else {
            val sign = if (diff < 0) "−" else "+"
            "$sign${sharePercent(diff.absoluteValue, previousTotalMinor)}%"
        }
        copy.monthlyReportDelta(if (diff < 0) "↓" else "↑", formatMinor(diff.absoluteValue), pct)
    }
    // One per line: category names are free text and may themselves contain ", ".
    val overspent = summary.categories
        .filter { it.category.enabled && it.category.countsAsSpending }
        .filter { it.category.monthlyLimitMinor > 0 && it.spentMinor > it.category.monthlyLimitMinor }
        .sortedByDescending { it.spentMinor }
    val footer = if (overspent.isEmpty()) null else
        (listOf(copy.monthlyReportExceededHeader) + overspent.map { "• ${it.category.label}" }).joinToString("\n")

    val top = listOfNotNull(header, delta).joinToString("\n")
    return listOfNotNull(top, statusLines(summary, copy), footer).joinToString("\n\n")
}
