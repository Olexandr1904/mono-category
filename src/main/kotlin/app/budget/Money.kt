package app.budget

import kotlin.math.absoluteValue

/**
 * ISO 4217 for the hryvnia, and the only currency this application can represent.
 *
 * Every `amount_minor` in the database is hryvnia kopecks and [formatMinor] appends "₴"
 * unconditionally, so a dollar amount stored in that column is not an unsupported currency
 * — it is a wrong number that every screen will print as hryvnia. Hence the two gates that
 * read this constant: [TransactionRepository.spentByCategory] refuses to count a row whose
 * account is not hryvnia, and [app.ingest.IngestService] refuses to store one at all.
 *
 * Lives here rather than beside the Monobank models (where it was, as their own account
 * currency field) because `app.budget` is the layer underneath: `app.mono` already imports
 * [Txn] and [monthKeyOf] from here, and importing 980 back the other way would make the
 * two packages mutually dependent.
 */
const val UAH_CURRENCY_CODE = 980

/**
 * Thousands separator inside every rendered amount — design-handoff.md's "Number
 * formatting": a "thin space", not the ordinary-width non-breaking space this used to be.
 * U+202F (NARROW NO-BREAK SPACE) is both: visually thin, and — like the U+00A0 it
 * replaces — never a browser line-break opportunity, so "−201 100 ₴" still cannot wrap
 * mid-number inside a table cell or a row's amount column (2026-09-03 UX review §5).
 */
private const val THIN_SPACE = " "

/**
 * The true minus sign (U+2212), not the ASCII hyphen-minus U+002D — design-handoff.md:
 * expenses take "−", never "-". A search for one code point will not find the other,
 * which is deliberate: nothing in this file should quietly fall back to the typewriter
 * hyphen now that this is the standard.
 */
private const val TRUE_MINUS = "−"

/** Renders minor units as hryvnia. Kopecks appear only when non-zero. */
fun formatMinor(minor: Long): String {
    val sign = if (minor < 0) TRUE_MINUS else ""
    val abs = minor.absoluteValue
    val whole = abs / 100
    val kopecks = abs % 100
    val grouped = whole.toString()
        .reversed()
        .chunked(3)
        .joinToString(THIN_SPACE)
        .reversed()
    return if (kopecks == 0L) "$sign$grouped ₴"
    else "$sign$grouped,${kopecks.toString().padStart(2, '0')} ₴"
}

/**
 * [formatMinor], but a non-negative amount is prefixed "+" instead of rendering with no
 * sign at all — design-handoff.md: "Income is prefixed + and coloured #62D19A". Used by the
 * Операції row amount column and its day-group totals, the only two places that mix
 * expenses and income; every other screen renders amounts already known to be spend
 * (positive by construction, via [TransactionRepository.spentByCategory]'s sign flip) and
 * would gain a meaningless "+".
 */
fun formatSignedMinor(minor: Long): String =
    if (minor < 0) formatMinor(minor) else "+" + formatMinor(minor)

/**
 * [formatMinor] rounded to whole hryvnia, no kopecks — design-handoff.md: "Limit amounts
 * in row meta are rounded to whole ₴: 15 000 ₴". Used only for the small mono meta lines
 * that quote a category's own limit or its remaining/over-limit hint on Огляд and
 * Категорії; the spent amount next to them keeps full [formatMinor] precision.
 */
fun formatMinorWhole(minor: Long): String {
    val sign = if (minor < 0) TRUE_MINUS else ""
    val whole = (minor.absoluteValue + 50) / 100
    val grouped = whole.toString()
        .reversed()
        .chunked(3)
        .joinToString(THIN_SPACE)
        .reversed()
    return "$sign$grouped ₴"
}

/** Accepts "15000", "15 000", "15000.50", "15000,50". */
fun parseAmountToMinor(input: String): Long {
    // Finding 11 of the 2026-09 branch review: stripped here so a value copied from
    // somewhere formatMinor *does* render (a spent amount on the dashboard, say) and
    // pasted into any amount input still parses instead of tripping the regex on an
    // invisible character. U+00A0 is kept alongside U+202F even though formatMinor no
    // longer emits it — a value copied before this change must keep parsing.
    val cleaned = input.trim()
        .replace("\u00A0", "")
        .replace(" ", "")
        .replace(" ", "")
        .replace(",", ".")
    require(cleaned.isNotEmpty() && cleaned.matches(Regex("^-?\\d+(\\.\\d{1,2})?$"))) {
        "cannot parse amount: '$input'"
    }
    val negative = cleaned.startsWith("-")
    val digits = cleaned.removePrefix("-")
    val parts = digits.split(".")
    val whole = parts[0].toLong()
    val kopecks = parts.getOrNull(1)?.padEnd(2, '0')?.toLong() ?: 0L
    val total = whole * 100 + kopecks
    return if (negative) -total else total
}

/** Percent of the limit already spent. Returns 0 when there is no limit. */
fun percentOf(spentMinor: Long, limitMinor: Long): Int =
    if (limitMinor <= 0) 0 else ((spentMinor * 100) / limitMinor).toInt()

/**
 * Whole-percent shares for a column of amounts that together make up [totalMinor], summing
 * to exactly 100 whenever the amounts do.
 *
 * [sharePercent] cannot do this and is not meant to: rounding each entry on its own is a
 * decision made without the others, so six equal categories each land on 17% and the column
 * prints 102%. The leftover points go to the largest remainders, which is the standard
 * apportionment and the one that moves each figure by at most a point from its own rounding.
 * Ties go to the earlier entry — the breakdown arrives sorted by spend, so the rounding is
 * absorbed by the largest category rather than the smallest, where a point is a bigger lie.
 *
 * Negative amounts (a refund filed among the spending) contribute nothing rather than a
 * negative share.
 */
fun sharePercents(amountsMinor: List<Long>, totalMinor: Long): List<Int> {
    if (totalMinor <= 0 || amountsMinor.isEmpty()) return List(amountsMinor.size) { 0 }
    val amounts = amountsMinor.map { it.coerceAtLeast(0) }
    val floors = amounts.map { (it * 100 / totalMinor).toInt() }
    // At most the number of entries, and never negative: the floors can only undershoot.
    val spare = (100 - floors.sum()).coerceIn(0, amounts.size)
    if (spare == 0) return floors

    val byRemainder = amounts.indices.sortedWith(
        // Descending remainder, then ascending index so a tie keeps the list's own order.
        compareByDescending<Int> { amounts[it] * 100 % totalMinor }.thenBy { it },
    )
    val result = floors.toMutableList()
    byRemainder.take(spare).forEach { result[it]++ }
    return result
}

/**
 * One amount's share of the month's total, as a whole percent — hoisted here so
 * `app.web.DashboardPage` and `app.web.TransactionsPage` compute it identically instead of
 * each carrying its own copy that could drift.
 *
 * Rounds half-up. It used to truncate, which is defensible for one number alone and
 * indefensible for a column of them: the 2026-09-22 production walkthrough found Огляд's
 * breakdown summing to 97% for September and 96% for August, with 13,94% printed as "13%"
 * beside an amount that plainly said otherwise. Rounding is only half of that answer, and a
 * column of shares wants [sharePercents] instead — this one is for a figure standing alone,
 * such as the uncategorized banner's share of the month.
 *
 * Deliberately not applied to [percentOf]. That one feeds [thresholdFor], and rounding a
 * limit's percentage *up* would claim a threshold the spend has not crossed — 79,6% of a
 * limit must not fire an 80% alert.
 */
fun sharePercent(amountMinor: Long, totalMinor: Long): Int =
    if (totalMinor <= 0) 0 else {
        val amount = amountMinor.coerceAtLeast(0)
        ((amount * 100 + totalMinor / 2) / totalMinor).toInt()
    }

/**
 * True when there was real spend but [sharePercent] still renders 0% — the UX bug where
 * 11 000,00 ₴ and 0 ₴ both rendered as "0% з місяця" with an identical zero-width bar.
 * Callers should show a distinct "<1%" in this case rather than the plain percent. Now that
 * [sharePercent] rounds, this means "under half a percent" rather than "under one".
 */
fun isTinyShare(amountMinor: Long, totalMinor: Long): Boolean =
    amountMinor > 0 && sharePercent(amountMinor, totalMinor) == 0
