package app.budget

enum class SpendStatus { NORMAL, WARNING, EXCEEDED }

data class CategorySpending(
    val category: Category,
    val spentMinor: Long,
    val pct: Int,
    val status: SpendStatus,
)

data class MonthSummary(
    val month: String,
    val totalSpentMinor: Long,
    val uncategorizedMinor: Long,
    val categories: List<CategorySpending>,
)

class BudgetService(
    private val categories: CategoryRepository,
    private val transactions: TransactionRepository,
) {
    /**
     * Spending is never materialized — one grouped query per request.
     *
     * [TransactionRepository.spentByCategory] already drops a row on an inactive account
     * before it reaches any category's amount — that exclusion is blanket, so there is
     * nothing left of it to show anywhere. A category with `countsAsSpending = false` is
     * different: its own amount stays in `spent` and rides along on its own
     * [CategorySpending] row (the dashboard card, the limit bar, /transactions all read
     * that unchanged) — it is only left out of `totalSpentMinor`, so the one figure the
     * word "Витрачено" makes a claim about stays honest. `app.web.monthBreakdown` and
     * [Notifier] apply the same category-level filter themselves, since neither reads
     * through here. Категорії's own summary strip must apply it too — see
     * `app.web.summaryStrip`'s `categorized` figure.
     */
    fun monthSummary(month: String): MonthSummary {
        val spent = transactions.spentByCategory(month)
        val rows = categories.list(includeDisabled = true).map { category ->
            val amount = spent[category.id] ?: 0L
            val pct = percentOf(amount.coerceAtLeast(0), category.monthlyLimitMinor)
            CategorySpending(
                category = category,
                spentMinor = amount,
                pct = pct,
                status = statusOf(pct, category),
            )
        }
        val countedTotal = rows.filter { it.category.countsAsSpending }.sumOf { it.spentMinor } + (spent[null] ?: 0L)
        return MonthSummary(
            month = month,
            totalSpentMinor = countedTotal,
            uncategorizedMinor = spent[null] ?: 0L,
            categories = rows,
        )
    }

    private fun statusOf(pct: Int, category: Category): SpendStatus = when {
        category.monthlyLimitMinor <= 0 -> SpendStatus.NORMAL
        pct >= 100 -> SpendStatus.EXCEEDED
        pct >= category.thresholdPct -> SpendStatus.WARNING
        else -> SpendStatus.NORMAL
    }
}
