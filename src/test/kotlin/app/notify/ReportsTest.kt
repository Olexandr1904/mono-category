package app.notify

import app.budget.Category
import app.budget.CategorySpending
import app.budget.MonthSummary
import app.budget.SpendStatus
import app.budget.formatMinor
import app.i18n.UkCopy
import kotlin.test.Test
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
}
