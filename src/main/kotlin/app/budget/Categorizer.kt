package app.budget

/** Which layer decided a transaction's category. Rendered on the transactions page. */
enum class CategorySource { MANUAL, COUNTERPARTY, MCC, NONE }

/**
 * Everything the three layers need, read once per operation so a single ingest or
 * recategorization sees one consistent view.
 */
data class CategoryRules(
    val mccMapping: Map<Int, Long>,
    val counterpartyRules: Map<String, Long>,
    val conduitMccs: Set<Int>,
)

data class CategoryDecision(val categoryId: Long?, val source: CategorySource)

/**
 * Categorization, in three layers of decreasing specificity:
 *
 * 1. a manual decision about this one row wins outright;
 * 2. otherwise a rule about the recipient, if we identified one;
 * 3. otherwise the MCC binding — unless the code is a conduit, in which case it carries no
 *    meaning and must not assign anything;
 * 4. otherwise the row stays uncategorized, which is what makes the bot ask.
 *
 * A row assigned by layer 2 is NOT marked manual: the rule has to keep governing it, or
 * changing the rule would leave the row behind.
 */
fun decideCategory(
    mcc: Int?,
    counterpartyKey: String?,
    manuallyCategorized: Boolean,
    currentCategoryId: Long?,
    rules: CategoryRules,
): CategoryDecision {
    if (manuallyCategorized) return CategoryDecision(currentCategoryId, CategorySource.MANUAL)

    counterpartyKey?.let { key ->
        rules.counterpartyRules[key]?.let { return CategoryDecision(it, CategorySource.COUNTERPARTY) }
    }
    if (mcc != null && mcc !in rules.conduitMccs) {
        rules.mccMapping[mcc]?.let { return CategoryDecision(it, CategorySource.MCC) }
    }
    return CategoryDecision(null, CategorySource.NONE)
}
