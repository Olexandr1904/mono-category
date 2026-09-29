package app.notify

import app.budget.Category
import app.budget.CategorySpending
import app.budget.MonthSummary
import app.budget.SpendStatus
import app.budget.formatMinor
import app.i18n.UkCopy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportsTest {

    private fun category(
        id: Long,
        name: String,
        limitMinor: Long,
        countsAsSpending: Boolean = true,
    ) = Category(id, name, "🛒", limitMinor, 80, countsAsSpending = countsAsSpending)

    private fun summary(vararg rows: Pair<Category, Long>) = MonthSummary(
        month = "2026-08",
        totalSpentMinor = rows.filter { it.first.countsAsSpending }.sumOf { it.second },
        uncategorizedMinor = 0L,
        categories = rows.map { (c, spent) -> CategorySpending(c, spent, 0, SpendStatus.NORMAL) },
    )

    @Test
    fun `left lists the tightest category first`() {
        val text = renderLeft(
            summary(
                category(1, "Продукти", 1_000_000L) to 200_000L,
                category(2, "Авто", 500_000L) to 450_000L,
            ),
            UkCopy, "серпень 2026",
        )
        assertTrue(text.indexOf("Авто") < text.indexOf("Продукти"), text)
    }

    @Test
    fun `an overspent category is marked and shows the overspend`() {
        val text = renderLeft(summary(category(1, "Авто", 500_000L) to 620_000L), UkCopy, "серпень 2026")
        assertTrue(text.contains("⚠️"), text)
        assertTrue(text.contains(formatMinor(120_000L)), text)
    }

    /**
     * Spending exclusion is enforced per reader, not upstream: a reader that forgets is a
     * figure quietly disagreeing with /status, not a compile error.
     */
    @Test
    fun `a not-counted category never appears in left`() {
        val text = renderLeft(
            summary(
                category(1, "Продукти", 1_000_000L) to 200_000L,
                category(2, "Свої рахунки", 900_000L, countsAsSpending = false) to 800_000L,
            ),
            UkCopy, "серпень 2026",
        )
        assertFalse(text.contains("Свої рахунки"), text)
    }

    @Test
    fun `categories with no limit are counted, not listed`() {
        val text = renderLeft(
            summary(
                category(1, "Продукти", 1_000_000L) to 200_000L,
                category(2, "Подарунки", 0L) to 50_000L,
                category(3, "Книги", 0L) to 10_000L,
            ),
            UkCopy, "серпень 2026",
        )
        assertFalse(text.contains("Подарунки"), text)
        assertTrue(text.contains(UkCopy.leftNoLimitTail(2)), text)
    }

    @Test
    fun `the Ukrainian tail declines its noun`() {
        assertTrue(UkCopy.leftNoLimitTail(1).endsWith("категорія"))
        assertTrue(UkCopy.leftNoLimitTail(3).endsWith("категорії"))
        assertTrue(UkCopy.leftNoLimitTail(5).endsWith("категорій"))
        assertTrue(UkCopy.leftNoLimitTail(11).endsWith("категорій"), "11 is the band a naive n % 10 gets wrong")
    }

    @Test
    fun `left with no limits anywhere says so`() {
        val text = renderLeft(summary(category(1, "Подарунки", 0L) to 50_000L), UkCopy, "серпень 2026")
        assertTrue(text.contains(UkCopy.leftNothingWithLimits), text)
    }

    private fun exceeded(category: Category, spent: Long) =
        CategorySpending(category, spent, (spent * 100 / category.monthlyLimitMinor).toInt(), SpendStatus.EXCEEDED)

    @Test
    fun `the monthly report names the month and compares the total with the month before`() {
        val text = renderMonthlyReport(
            summary(category(1, "Продукти", 0L) to 4_230_000L),
            previousTotalMinor = 4_540_000L,
            copy = UkCopy,
        )
        assertTrue(text.startsWith(UkCopy.monthlyReportHeader(UkCopy.monthLabel("2026-08"), formatMinor(4_230_000L))), text)
        // 310 000 of 4 540 000 is 6.8% — rounded to the nearest, not truncated to 6.
        assertTrue(text.contains(UkCopy.monthlyReportDelta("↓", formatMinor(310_000L), "−7%")), text)
    }

    @Test
    fun `a rise is shown with an up arrow and a plus`() {
        val text = renderMonthlyReport(
            summary(category(1, "Продукти", 0L) to 1_500_000L),
            previousTotalMinor = 1_000_000L,
            copy = UkCopy,
        )
        assertTrue(text.contains(UkCopy.monthlyReportDelta("↑", formatMinor(500_000L), "+50%")), text)
    }

    /** A percentage of nothing is not a number worth printing — the first month has no baseline. */
    @Test
    fun `no comparison line when the month before spent nothing`() {
        val text = renderMonthlyReport(
            summary(category(1, "Продукти", 0L) to 1_500_000L),
            previousTotalMinor = 0L,
            copy = UkCopy,
        )
        assertFalse(text.contains("↑"), text)
        assertFalse(text.contains("↓"), text)
    }

    @Test
    fun `the monthly report carries the same category lines as status`() {
        val s = summary(category(1, "Продукти", 0L) to 1_500_000L, category(2, "Кафе", 0L) to 200_000L)
        val text = renderMonthlyReport(s, previousTotalMinor = 0L, copy = UkCopy)
        val statusBody = renderStatus(s, UkCopy, "серпень 2026").substringAfter("\n\n")
        assertTrue(text.contains(statusBody), text)
    }

    /**
     * The whole message, pinned: fragment checks stayed green with the delta moved below the
     * lines or the separators swapped. The fixture carries every kind of row the recap
     * treats differently.
     */
    @Test
    fun `the monthly report reads top to bottom as header, lines, overspend`() {
        val s = MonthSummary(
            month = "2026-08",
            totalSpentMinor = 2_730_000L,
            uncategorizedMinor = 30_000L,
            categories = listOf(
                exceeded(category(1, "Продукти", 1_000_000L), 1_200_000L),
                exceeded(category(2, "Оренда", 1_500_000L), 1_500_000L),
                CategorySpending(category(3, "Свої рахунки", 0L, countsAsSpending = false), 500_000L, 0, SpendStatus.NORMAL),
            ),
        )
        val f = ::formatMinor
        assertEquals(
            "📊 Підсумки: Серпень 2026\nВитрачено: ${f(2_730_000L)}\n" +
                "↓ на ${f(270_000L)} (−9%) порівняно з попереднім місяцем\n\n" +
                "🛒 Оренда: ${f(1_500_000L)} / ${f(1_500_000L)}  100% ⚠️\n" +
                "🛒 Продукти: ${f(1_200_000L)} / ${f(1_000_000L)}  120% ⚠️\n" +
                "🛒 Свої рахунки: ${f(500_000L)} — не рахується як витрати\n" +
                "Без категорії: ${f(30_000L)}\n\n" +
                "Перевищено ліміт:\n• 🛒 Продукти",
            renderMonthlyReport(s, previousTotalMinor = 3_000_000L, copy = UkCopy),
        )
    }

    /**
     * Landing exactly on the limit is not going over it — rent that is always the limit to
     * the kopeck would otherwise be named as overspent every single month.
     */
    @Test
    fun `a category spent exactly to its limit is not listed as over it`() {
        val s = MonthSummary("2026-08", 1_500_000L, 0L, listOf(exceeded(category(1, "Оренда", 1_500_000L), 1_500_000L)))
        val text = renderMonthlyReport(s, previousTotalMinor = 0L, copy = UkCopy)
        assertFalse(text.contains(UkCopy.monthlyReportExceededHeader), text)
    }

    /** Notifier never alerts on a not-counted category, so the recap must not call it overspent either. */
    @Test
    fun `a not-counted category is never listed as over its limit`() {
        val s = MonthSummary(
            month = "2026-08",
            totalSpentMinor = 0L,
            uncategorizedMinor = 0L,
            categories = listOf(exceeded(category(1, "Свої рахунки", 100_000L, countsAsSpending = false), 900_000L)),
        )
        val text = renderMonthlyReport(s, previousTotalMinor = 0L, copy = UkCopy)
        assertFalse(text.contains(UkCopy.monthlyReportExceededHeader), text)
    }

    @Test
    fun `no overspend footer when every category stayed within its limit`() {
        val text = renderMonthlyReport(
            summary(category(1, "Продукти", 1_000_000L) to 200_000L),
            previousTotalMinor = 0L,
            copy = UkCopy,
        )
        assertFalse(text.contains(UkCopy.monthlyReportExceededHeader), text)
    }

    /** "↑ на 0 ₴ (+0%)" says nothing and says it with an arrow. */
    @Test
    fun `no comparison line when the total did not change`() {
        val text = renderMonthlyReport(
            summary(category(1, "Продукти", 0L) to 1_500_000L),
            previousTotalMinor = 1_500_000L,
            copy = UkCopy,
        )
        assertFalse(text.contains("↑"), text)
        assertFalse(text.contains("↓"), text)
    }

    /** A real change that rounds to zero percent is shown as under one, never as "−0%". */
    @Test
    fun `a change under half a percent reads as under one percent`() {
        val text = renderMonthlyReport(
            summary(category(1, "Продукти", 0L) to 4_530_000L),
            previousTotalMinor = 4_540_000L,
            copy = UkCopy,
        )
        assertTrue(text.contains(UkCopy.monthlyReportDelta("↓", formatMinor(10_000L), "<1%")), text)
    }

    /**
     * The header total includes money nobody has filed yet; without its own line the
     * category lines under it add up to less, and a month of nothing but unfiled spending
     * read "no categories yet" under a non-zero total.
     */
    @Test
    fun `status shows uncategorized spending as its own last line`() {
        val s = MonthSummary(
            month = "2026-08",
            totalSpentMinor = 530_000L,
            uncategorizedMinor = 30_000L,
            categories = listOf(CategorySpending(category(1, "Продукти", 0L), 500_000L, 0, SpendStatus.NORMAL)),
        )
        val text = renderStatus(s, UkCopy, "серпень 2026")
        assertTrue(text.endsWith("\n" + UkCopy.statusLineNoLimit(UkCopy.uncategorizedLabel, formatMinor(30_000L))), text)
    }

    @Test
    fun `status with only uncategorized spending does not say there are no categories`() {
        val s = MonthSummary("2026-08", 30_000L, 30_000L, emptyList())
        val text = renderStatus(s, UkCopy, "серпень 2026")
        assertFalse(text.contains(UkCopy.noCategoriesYetStatus), text)
    }
}
