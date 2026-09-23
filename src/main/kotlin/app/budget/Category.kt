package app.budget

import app.db.Categories
import app.db.CategoryMcc
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

data class Category(
    val id: Long,
    val name: String,
    val emoji: String,
    val monthlyLimitMinor: Long,
    val thresholdPct: Int,
    val notifyWarning: Boolean = true,
    val notifyExceeded: Boolean = true,
    val enabled: Boolean = true,
    val position: Int = 0,
    /**
     * False means: money in this category moved between the owner's own accounts and is
     * not spending. [app.budget.TransactionRepository.spentByCategory] still reports the
     * real amount for this category — the dashboard card, /transactions and the category
     * form all keep showing it — but [BudgetService.monthSummary] leaves it out of
     * `totalSpentMinor`, `app.web.monthBreakdown` leaves it out of Огляд's breakdown list,
     * and [app.notify.Notifier] never alerts on it. Default true: every category counts
     * until the owner opts one out.
     */
    val countsAsSpending: Boolean = true,
) {
    val label: String get() = if (emoji.isBlank()) name else "$emoji $name"
}

class MccConflictException(
    val mcc: Int,
    val ownerCategoryId: Long,
    val ownerName: String,
) : RuntimeException("MCC $mcc already belongs to '$ownerName'")

class CategoryRepository(
    private val db: Database,
    private val conduit: ConduitMccRepository,
) {

    fun list(includeDisabled: Boolean = true): List<Category> = transaction(db) {
        Categories.selectAll()
            .apply { if (!includeDisabled) andWhere { Categories.enabled eq true } }
            .orderBy(Categories.position to SortOrder.ASC, Categories.id to SortOrder.ASC)
            .map(::toCategory)
    }

    fun byId(id: Long): Category? = transaction(db) {
        Categories.selectAll().where { Categories.id eq id }.singleOrNull()?.let(::toCategory)
    }

    fun create(
        name: String,
        emoji: String,
        monthlyLimitMinor: Long,
        thresholdPct: Int,
        countsAsSpending: Boolean = true,
    ): Long =
        transaction(db) {
            Categories.insert {
                it[Categories.name] = name
                it[Categories.emoji] = emoji
                it[Categories.monthlyLimitMinor] = monthlyLimitMinor
                it[Categories.thresholdPct] = thresholdPct
                it[notifyWarning] = true
                it[notifyExceeded] = true
                it[enabled] = true
                it[position] = 0
                it[Categories.countsAsSpending] = countsAsSpending
                it[createdAt] = Instant.now().epochSecond
            }[Categories.id].value
        }

    fun update(category: Category) = transaction(db) {
        Categories.update({ Categories.id eq category.id }) {
            it[name] = category.name
            it[emoji] = category.emoji
            it[monthlyLimitMinor] = category.monthlyLimitMinor
            it[thresholdPct] = category.thresholdPct
            it[notifyWarning] = category.notifyWarning
            it[notifyExceeded] = category.notifyExceeded
            it[enabled] = category.enabled
            it[position] = category.position
            it[countsAsSpending] = category.countsAsSpending
        }
        Unit
    }

    fun delete(id: Long) = transaction(db) {
        CategoryMcc.deleteWhere { it.run { categoryId eq id } }
        Categories.deleteWhere { it.run { Categories.id eq id } }
        Unit
    }

    /**
     * Spec §6: `enabled = false` removes a category from categorization. A disabled
     * category's MCCs must not appear here, or new transactions would keep silently
     * accumulating spend nobody is alerted about. History is not rewritten by this method —
     * already-categorized rows keep their category_id — but the next recategorizeAll clears
     * them, since the mapping no longer contains their MCCs.
     */
    fun mccMapping(): Map<Int, Long> = transaction(db) {
        val enabledIds = Categories.selectAll()
            .where { Categories.enabled eq true }
            .map { it[Categories.id].value }
            .toSet()
        CategoryMcc.selectAll()
            .associate { it[CategoryMcc.mcc] to it[CategoryMcc.categoryId] }
            .filterValues { it in enabledIds }
    }

    /**
     * Ids of categories with `enabled = false`. Passed to
     * [TransactionRepository.recategorize] so it can leave a disabled category's already-
     * assigned rows alone — computed once by the caller, not queried per row.
     */
    fun disabledIds(): Set<Long> = transaction(db) {
        Categories.selectAll().where { Categories.enabled eq false }.map { it[Categories.id].value }.toSet()
    }

    fun mccOf(categoryId: Long): Set<Int> = transaction(db) {
        CategoryMcc.selectAll().where { CategoryMcc.categoryId eq categoryId }
            .map { it[CategoryMcc.mcc] }
            .toSet()
    }

    /** Replaces the category's MCC set. Throws if any MCC belongs to a different category. */
    fun setMcc(categoryId: Long, mccs: Set<Int>) = transaction(db) {
        mccs.firstOrNull { conduit.contains(it) }?.let { throw ConduitMccException(it) }
        assertNoConflicts(categoryId, mccs)
        CategoryMcc.deleteWhere { it.run { CategoryMcc.categoryId eq categoryId } }
        mccs.forEach { code ->
            CategoryMcc.insert {
                it[mcc] = code
                it[CategoryMcc.categoryId] = categoryId
            }
        }
        Unit
    }

    /** Adds a single MCC. Throws if it belongs to a different category. */
    fun addMcc(categoryId: Long, mcc: Int) = transaction(db) {
        if (conduit.contains(mcc)) throw ConduitMccException(mcc)
        assertNoConflicts(categoryId, setOf(mcc))
        CategoryMcc.insertIgnore {
            it[CategoryMcc.mcc] = mcc
            it[CategoryMcc.categoryId] = categoryId
        }
        Unit
    }

    /**
     * Drops a single MCC, whichever category currently owns it (`mcc` is this table's
     * primary key, so at most one row can ever match). Used by the transactions-page
     * "apply to this one only" override to undo an earlier bind: the caller doesn't need
     * to know which category the bind pointed at, only that the code should no longer be
     * bound to anything.
     */
    fun removeMcc(mcc: Int) = transaction(db) {
        CategoryMcc.deleteWhere { it.run { CategoryMcc.mcc eq mcc } }
        Unit
    }

    private fun assertNoConflicts(categoryId: Long, mccs: Set<Int>) {
        if (mccs.isEmpty()) return
        val owners = CategoryMcc.selectAll().where { CategoryMcc.mcc inList mccs }
            .associate { it[CategoryMcc.mcc] to it[CategoryMcc.categoryId] }
        val conflict = owners.entries.firstOrNull { it.value != categoryId } ?: return
        val ownerName = Categories.selectAll().where { Categories.id eq conflict.value }
            .singleOrNull()?.get(Categories.name) ?: "unknown"
        throw MccConflictException(conflict.key, conflict.value, ownerName)
    }

    private fun toCategory(row: ResultRow) = Category(
        id = row[Categories.id].value,
        name = row[Categories.name],
        emoji = row[Categories.emoji],
        monthlyLimitMinor = row[Categories.monthlyLimitMinor],
        thresholdPct = row[Categories.thresholdPct],
        notifyWarning = row[Categories.notifyWarning],
        notifyExceeded = row[Categories.notifyExceeded],
        enabled = row[Categories.enabled],
        position = row[Categories.position],
        countsAsSpending = row[Categories.countsAsSpending],
    )
}
