package app.web

import app.budget.BudgetService
import app.budget.CategorySpending
import app.budget.KYIV
import app.budget.MonthSummary
import app.budget.SpendStatus
import app.budget.TransactionRepository
import app.budget.currentMonthKey
import app.budget.formatMinor
import app.budget.formatMinorWhole
import app.budget.isTinyShare
import app.budget.nextMonthKey
import app.budget.previousMonthKey
import app.budget.safeMonthKey
import app.budget.sharePercent
import app.budget.sharePercents
import app.db.SettingsRepository
import app.i18n.Copy
import app.i18n.copy
import app.ingest.AccountRepository
import io.ktor.server.application.*
import io.ktor.server.html.respondHtml
import io.ktor.server.routing.*
import kotlinx.html.*
import java.time.Clock
import java.util.Locale

fun Route.dashboardRoutes(
    budget: BudgetService,
    transactions: TransactionRepository,
    settings: SettingsRepository,
    accounts: AccountRepository,
    clock: Clock = Clock.system(KYIV),
    // Mirrors installAuth's flag: the CSRF cookie the dashboard's shared logout form
    // relies on travels under the same Secure rule as the session cookie.
    secureCookies: Boolean = true,
) {
    get("/") {
        // Validated, not taken verbatim: previousMonthKey/nextMonthKey below parse this,
        // so "?month=garbage" used to surface as an uncaught DateTimeParseException.
        val month = safeMonthKey(call.request.queryParameters["month"], clock)
        val summary = budget.monthSummary(month)
        // Same count TransactionsPage's summary strip uses for "Не розібрано" — the
        // uncategorized banner's button label needs a real transaction count, not just
        // the amount MonthSummary carries. Account-aware (see countUnresolved): the banner
        // is gated on `summary.uncategorizedMinor > 0`, which already drops untracked
        // accounts, so a plain count would put a number on a banner that never renders.
        val uncategorizedCount = transactions.countUnresolved(month)
        val copy = settings.copy()
        val csrfToken = call.csrfToken(secureCookies)

        // Finding 6 (MINOR) of the 2026-09-04 review: Огляд's own arrows used to link to
        // every adjacent month unconditionally, unlike Операції's — same monthStepper
        // component, one clamped and one didn't. Mirrors TransactionsPage's own clamp
        // exactly: distinctMonths() plus "or later than the current month" so the stepper
        // never proposes a future month with no data either.
        val monthsWithData = transactions.distinctMonths()
        val oldestAllowed = monthsWithData.minOrNull() ?: month
        val newestAllowed = maxOf(monthsWithData.maxOrNull() ?: month, currentMonthKey(clock))

        call.respondHtml {
            page(
                copy = copy,
                title = copy.dashboardTitle,
                active = "dashboard",
                csrfToken = csrfToken,
                flash = call.request.queryParameters["flash"],
                error = call.request.queryParameters["error"],
                headerMeta = copy.headerMeta(accounts.trackableList().size),
            ) {
                div("stack") {
                    pageHeader(copy.monthLabel(month), copy.dashboardTitle) {
                        val prevMonth = previousMonthKey(month).takeIf { it >= oldestAllowed }
                        val nextMonth = nextMonthKey(month).takeIf { it <= newestAllowed }
                        monthStepper(
                            prevMonth?.let { "/?month=$it" },
                            copy.monthLabel(month),
                            nextMonth?.let { "/?month=$it" },
                        )
                    }

                    val breakdown = monthBreakdown(summary, copy.donutOtherLabel, copy.uncategorizedLabel)
                    // Finding 9 (MINOR) of the 2026-09-04 review: the hero card must always
                    // render, even for a zero-spend month (breakdown is empty exactly when
                    // totalSpentMinor <= 0) — otherwise the 1st of a month shows a header,
                    // "Ліміти не встановлено" and nothing else, not even "Витрачено 0,00 ₴".
                    // heroCard itself skips the bar/list when breakdown is empty.
                    heroCard(copy, summary, breakdown)

                    if (summary.uncategorizedMinor > 0) uncategorizedBanner(copy, summary, month, uncategorizedCount)

                    limitsBlock(copy, summary)
                }
            }
        }
    }
}

// --- hero card: 40px total, stacked bar, breakdown list -----------------------------------

private fun FlowContent.heroCard(copy: Copy, summary: MonthSummary, breakdown: List<BreakdownEntry>) {
    div("hero-card") {
        div("hero-kicker") { +copy.heroSpentLabel }
        div("hero-total mono") { +formatMinor(summary.totalSpentMinor) }
        // Finding 9: nothing to bar-chart or list on a zero-spend month — the total above
        // is the whole story, and monthBreakdown itself returns empty exactly when
        // totalSpentMinor <= 0.
        if (breakdown.isNotEmpty()) {
            stackedBar(breakdown, summary.totalSpentMinor)
            // Apportioned across the whole column at once, never row by row: these are
            // shares of one month and have to read as such. Six equal categories each
            // rounding to 17% printed 102% down the side of the card.
            val percents = sharePercents(breakdown.map { it.amountMinor }, summary.totalSpentMinor)
            div("breakdown-list") {
                breakdown.forEachIndexed { i, entry -> breakdownRow(copy, entry, percents[i]) }
            }
        }
    }
}

private fun FlowContent.stackedBar(breakdown: List<BreakdownEntry>, totalMinor: Long) {
    div("stacked-bar") {
        breakdown.forEach { entry ->
            span("stacked-bar-segment ${entry.colorClass()}") {
                attributes["data-bar-width"] = sharePercentPrecise(entry.amountMinor, totalMinor)
            }
        }
    }
}

private fun FlowContent.breakdownRow(copy: Copy, entry: BreakdownEntry, percent: Int) {
    div("breakdown-row") {
        span("breakdown-chip ${entry.colorClass()}") {}
        div("breakdown-name") {
            span("breakdown-name-text") { +entry.label }
            if (entry.isUncategorized) span("pill pill-accent") { +copy.unsortedPill }
        }
        span("breakdown-amount mono") { +formatMinor(entry.amountMinor) }
        span("breakdown-pct mono") {
            // Read off the apportioned figure rather than re-deriving it: a row that is
            // awarded a point by the apportionment is no longer "too small to show".
            +if (percent == 0 && entry.amountMinor > 0) {
                // Finding 8 (MINOR): the bare "<1%", not shareOfMonthTiny's full "<1% з
                // місяця" — this column is a fixed 58px nowrap cell next to six bare
                // percentages (design-handoff.md §1's `auto | 58px` grid); the full phrase
                // overflowed the card and read as prose beside the others.
                copy.shareOfMonthTinyBare
            } else {
                "$percent%"
            }
        }
    }
}

/** Two-decimal share, for the stacked bar's own segment widths only — design-handoff.md
 *  §1: "width: <share>% (2 decimals)". A purely visual proportion, never rendered as text:
 *  the percentages a person reads are whole numbers apportioned across the column by
 *  [app.budget.sharePercents], which these widths deliberately do not have to match. */
private fun sharePercentPrecise(amountMinor: Long, totalMinor: Long): String {
    if (totalMinor <= 0) return "0"
    val pct = amountMinor.coerceAtLeast(0).toDouble() * 100.0 / totalMinor.toDouble()
    return String.format(Locale.ROOT, "%.2f", pct)
}

// --- uncategorized banner ------------------------------------------------------------------

private fun FlowContent.uncategorizedBanner(copy: Copy, summary: MonthSummary, month: String, count: Long) {
    div("uncat-banner") {
        div("uncat-banner-text") {
            div("uncat-banner-line1") {
                +"${formatMinor(summary.uncategorizedMinor)} ${copy.uncategorizedBannerSuffix} — "
                +if (isTinyShare(summary.uncategorizedMinor, summary.totalSpentMinor)) {
                    copy.shareOfMonthTiny
                } else {
                    copy.shareOfMonth(sharePercent(summary.uncategorizedMinor, summary.totalSpentMinor))
                }
            }
            div("uncat-banner-line2") { +copy.uncategorizedBannerHint }
        }
        // "лише без категорії" on, category filter reset — design-handoff.md §1.3.
        a(href = "/transactions?month=$month&uncategorized=1", classes = "btn btn-primary") {
            +copy.resolveUncategorizedButton(count)
        }
    }
}

// --- ліміти block ---------------------------------------------------------------------------

private fun FlowContent.limitsBlock(copy: Copy, summary: MonthSummary) {
    val limited = summary.categories.filter { it.category.monthlyLimitMinor > 0 }
    if (limited.isEmpty()) {
        div("limits-empty") {
            div("limits-empty-text") {
                div("limits-empty-title") { +copy.noLimitsTitle }
                div("limits-empty-body") { +copy.noLimitsBody }
            }
            a(href = "/categories", classes = "btn btn-ghost") { +copy.setLimitsButton }
        }
        return
    }
    h2 { +copy.limitsHeading }
    div("limit-list") {
        limited.forEach { row -> limitRow(copy, row) }
    }
}

private fun FlowContent.limitRow(copy: Copy, row: CategorySpending) {
    div("limit-row") {
        div("limit-row-name") { +row.category.label }
        div("limit-row-limit") {
            div("limit-row-limit-text mono") {
                +"${formatMinorWhole(row.category.monthlyLimitMinor)} · ${row.pct}%"
            }
            div("limit-bar-track") {
                span("limit-bar-fill ${limitBarClass(row.status)}") {
                    attributes["data-bar-width"] = row.pct.coerceIn(0, 100).toString()
                }
            }
        }
        div("limit-row-amount") {
            span("limit-row-spent mono") { +formatMinor(row.spentMinor) }
            div("limit-row-hint mono") {
                +if (row.spentMinor >= row.category.monthlyLimitMinor) {
                    copy.limitOverHint(formatMinorWhole(row.spentMinor - row.category.monthlyLimitMinor))
                } else {
                    copy.limitRemainingHint(formatMinorWhole(row.category.monthlyLimitMinor - row.spentMinor))
                }
            }
        }
    }
}

private fun limitBarClass(status: SpendStatus): String = when (status) {
    SpendStatus.NORMAL -> ""
    SpendStatus.WARNING -> "warning"
    SpendStatus.EXCEEDED -> "over"
}
