package app.budget

import app.db.Accounts
import app.db.Transactions
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

data class Txn(
    val id: String,
    val accountId: String,
    val occurredAt: Long,
    val month: String,
    val amountMinor: Long,
    val currencyCode: Int,
    val description: String,
    val mcc: Int?,
    val originalMcc: Int?,
    val hold: Boolean,
    val categoryId: Long?,
    val manuallyCategorized: Boolean,
    val rawJson: String = "",
    val counterpartyKey: String? = null,
    val counterpartySource: String? = null,
)

enum class UpsertResult { INSERTED, UPDATED, UNCHANGED }

/**
 * Ordering for [TransactionRepository.page]. Lives here, not in the route, so paging
 * stays correct across the whole filtered result set rather than just within one page.
 *
 * "Largest amount" means the biggest expense first. Amounts are stored negative for
 * outgoing money, so "largest" sorts `amount_minor` ascending (most negative first) and
 * "smallest" sorts it descending — the two are exact opposites of each other.
 */
enum class TxnOrder(val param: String) {
    NEWEST("newest"),
    OLDEST("oldest"),
    LARGEST("largest"),
    SMALLEST("smallest");

    /**
     * Whether `/transactions` may group its rows into day cards under this order.
     *
     * 2026-09-22 production walkthrough, finding 6: amount order says "the big ones
     * first", and running that through `rows.groupBy { kyivDate(...) }` afterwards silently
     * regrouped it into "days ordered by their single largest transaction", dragging every
     * small row of that day up with it. An amount-ordered list is rendered flat instead —
     * no information is lost, because each row's meta line already carries its full date.
     */
    val groupsByDay: Boolean get() = this == NEWEST || this == OLDEST

    companion object {
        fun fromParam(value: String?): TxnOrder = entries.firstOrNull { it.param == value } ?: NEWEST
    }
}

class TransactionRepository(private val db: Database) {

    /**
     * Idempotent by construction: the primary key is Monobank's own id.
     * A stored row with hold=true is provisional and gets refreshed;
     * a settled row is final and is never overwritten.
     */
    fun upsert(txn: Txn): UpsertResult = transaction(db) {
        val existing = Transactions.selectAll().where { Transactions.id eq txn.id }.singleOrNull()
        when {
            existing == null -> {
                Transactions.insert {
                    it[id] = txn.id
                    it[accountId] = txn.accountId
                    it[occurredAt] = txn.occurredAt
                    it[month] = txn.month
                    it[amountMinor] = txn.amountMinor
                    it[currencyCode] = txn.currencyCode
                    it[description] = txn.description
                    it[mcc] = txn.mcc
                    it[originalMcc] = txn.originalMcc
                    it[hold] = txn.hold
                    it[categoryId] = txn.categoryId
                    it[manuallyCategorized] = txn.manuallyCategorized
                    it[rawJson] = txn.rawJson
                    it[counterpartyKey] = txn.counterpartyKey
                    it[counterpartySource] = txn.counterpartySource
                    it[createdAt] = Instant.now().epochSecond
                }
                UpsertResult.INSERTED
            }
            existing[Transactions.hold] -> {
                Transactions.update({ Transactions.id eq txn.id }) {
                    it[amountMinor] = txn.amountMinor
                    it[hold] = txn.hold
                    it[description] = txn.description
                    it[mcc] = txn.mcc
                    it[originalMcc] = txn.originalMcc
                    it[occurredAt] = txn.occurredAt
                    it[month] = txn.month
                    it[rawJson] = txn.rawJson
                    it[counterpartyKey] = txn.counterpartyKey
                    it[counterpartySource] = txn.counterpartySource
                    // category and the manual flag are deliberately preserved
                }
                UpsertResult.UPDATED
            }
            else -> UpsertResult.UNCHANGED
        }
    }

    fun byId(id: String): Txn? = transaction(db) {
        Transactions.selectAll().where { Transactions.id eq id }.singleOrNull()?.let(::toTxn)
    }

    /**
     * Every month key with at least one stored transaction, newest first — populates the
     * month filter on `/transactions`. A native `<input type=month>` used to be there
     * instead; it is localised by the *browser's* UI language, not the app's, so an
     * English Chrome showed "August 2026" two lines under the app's own Ukrainian
     * "Показано: Серпень 2026" (2026-09-03 UX review §11).
     */
    fun distinctMonths(): List<String> = transaction(db) {
        Transactions.select(Transactions.month).withDistinct()
            .orderBy(Transactions.month to SortOrder.DESC)
            .map { it[Transactions.month] }
    }

    /** How many rows share this recipient — the "you have sent here before" hint. */
    fun countByCounterparty(key: String): Int = transaction(db) {
        Transactions.selectAll().where { Transactions.counterpartyKey eq key }.count().toInt()
    }

    /**
     * How many rows carry this MCC, across every month — the Операції rule strip's
     * "операцій: N" (design-handoff.md §3.5). Mirrors [countByCounterparty]'s scope: the
     * strip's "Застосувати" ultimately calls [app.ingest.IngestService.bindMcc], whose
     * [recategorize] sweeps every month, not just the one on screen, so the count shown
     * before the click must match what the click is actually about to change.
     */
    fun countByMcc(mcc: Int): Int = transaction(db) {
        Transactions.selectAll().where { Transactions.mcc eq mcc }.count().toInt()
    }

    /**
     * Fills in `counterparty_key` / `counterparty_source` for rows that were stored before
     * their MCC became a conduit code. Keys are otherwise only computed at ingest time, and
     * only for a code that is *already* conduit at that moment (spec §5: "setting up a second
     * rule alongside it would mean two sources of truth"), so every row imported between
     * deploy and the owner flipping a code to conduit sits with `counterparty_key = NULL`
     * despite carrying a complete verbatim `raw_json` — the bank payload is right there, it
     * was just never asked. Re-syncing does not repair this on its own: `upsert` returns
     * UNCHANGED for a settled row and never re-derives the key. Only rows still missing a
     * key are touched, so this is safe to call repeatedly and never overwrites a key a real
     * ingest-time computation already produced.
     */
    fun backfillCounterpartyKeys(mccs: Set<Int>): Int {
        if (mccs.isEmpty()) return 0
        return transaction(db) {
            var changed = 0
            Transactions.selectAll()
                .where { (Transactions.mcc inList mccs) and Transactions.counterpartyKey.isNull() }
                .forEach { row ->
                    val counterparty = counterpartyOf(row[Transactions.rawJson], row[Transactions.description])
                        ?: return@forEach
                    Transactions.update({ Transactions.id eq row[Transactions.id] }) {
                        it[counterpartyKey] = counterparty.key
                        it[counterpartySource] = counterparty.source.code
                    }
                    changed++
                }
            changed
        }
    }

    fun setCategory(id: String, categoryId: Long?, manual: Boolean): Boolean = transaction(db) {
        Transactions.update({ Transactions.id eq id }) {
            it[Transactions.categoryId] = categoryId
            it[manuallyCategorized] = manual
        } > 0
    }

    /**
     * Reapplies the mapping to every automatically categorized row, in every month.
     *
     * A row currently pointing at a category in [disabledCategoryIds] is left exactly as
     * it is. Spec §6: disabling a category removes it from categorizing NEW transactions —
     * "already-assigned transactions keep their category_id. History is not rewritten." A row
     * is only eligible for reassignment when it is currently uncategorized or belongs to an
     * enabled category; manually-categorized rows are excluded from this query entirely, as
     * before.
     */
    fun recategorize(rules: CategoryRules, disabledCategoryIds: Set<Long> = emptySet()): Int = transaction(db) {
        var changed = 0
        Transactions.selectAll().where { Transactions.manuallyCategorized eq false }
            .forEach { row ->
                val current = row[Transactions.categoryId]
                if (current != null && current in disabledCategoryIds) return@forEach
                val desired = decideCategory(
                    mcc = row[Transactions.mcc],
                    counterpartyKey = row[Transactions.counterpartyKey],
                    manuallyCategorized = false,
                    currentCategoryId = current,
                    rules = rules,
                ).categoryId
                if (desired != current) {
                    Transactions.update({ Transactions.id eq row[Transactions.id] }) {
                        it[categoryId] = desired
                    }
                    changed++
                }
            }
        changed
    }

    /**
     * Spending in minor units, sign flipped so that expenses are positive, per category.
     *
     * Only negative `amount_minor` rows count. Monobank reports incoming transfers
     * (MCC 4829) as positive amounts, indistinguishable from a refund without data we
     * don't have. Summing every sign, as the original spec did, let one large incoming
     * transfer drag a category's total below zero and understate the month's real
     * spending — confidently wrong rather than merely incomplete. The owner's ruling:
     * under-counting a genuine refund is an acceptable trade-off; presenting income as
     * negative spending is not.
     *
     * A row on an account the owner has switched off in Settings is dropped before it
     * ever reaches a category total — that account's history no longer shapes his
     * numbers at all (an inactive account is a blanket exclusion, unlike
     * [app.budget.Category.countsAsSpending], which [BudgetService] applies afterwards
     * so an excluded category's real amount stays visible on its own card). The join is
     * `LEFT`, and a row whose account isn't in `accounts` at all counts as active: several
     * tests, and any transaction stored before its account was ever synced, carry an
     * `account_id` with no matching row, and treating "unknown" as "excluded" would make
     * spending vanish rather than merely fail to be excluded.
     *
     * A row on a *non-hryvnia* account is dropped on the same terms, and this is the
     * second half of a rule whose first half lives in [app.ingest.IngestService]: nothing
     * non-hryvnia is stored any more, but rows imported before that gate existed are
     * still here, holding cents in a kopecks column — a dollar account's transfers were
     * being added to the month as hryvnia. The check is on the *account's* currency, never
     * `transactions.currency_code`: that column is Monobank's **operation** currency, so a
     * hryvnia card used abroad carries 840 on a row whose `amount_minor` is perfectly good
     * kopecks, and filtering on it would silently drop every foreign purchase the owner
     * made on the card he actually tracks.
     */
    fun spentByCategory(month: String): Map<Long?, Long> = transaction(db) {
        val total = Transactions.amountMinor.sum()
        val join = Transactions.join(
            Accounts, JoinType.LEFT,
            onColumn = Transactions.accountId, otherColumn = Accounts.id,
        )
        join
            .select(Transactions.categoryId, total)
            .where {
                (Transactions.month eq month) and
                    (Transactions.amountMinor less 0L) and
                    (
                        ((Accounts.active eq true) and (Accounts.currencyCode eq UAH_CURRENCY_CODE)) or
                            Accounts.active.isNull()
                        )
            }
            .groupBy(Transactions.categoryId)
            .associate { row -> row[Transactions.categoryId] to -(row[total] ?: 0L) }
    }

    /**
     * Rows in [month] that still need filing *and* would change a number once filed:
     * uncategorized, outgoing, and on a tracked account.
     *
     * Not [count] with `onlyUncategorized = true`, which is unaware of accounts: that
     * counted every uncategorized row on a switched-off or non-hryvnia account too, while
     * the amount beside it came from [spentByCategory], which drops exactly those. The
     * Операції strip printed "Без категорії 0,00 ₴" next to "Не розібрано 12", and Огляд's
     * banner — gated on `uncategorizedMinor > 0` — vanished entirely while `/transactions`
     * went on claiming twelve. The predicate below is [spentByCategory]'s, verbatim, for
     * the same reason it is there: these two numbers are read side by side, so they have to
     * be filtered by one rule.
     *
     * [count] is deliberately left alone: it backs "Усього: N", which must keep matching
     * the rows actually rendered underneath it, pills and all.
     */
    fun countUnresolved(month: String): Long = transaction(db) {
        val join = Transactions.join(
            Accounts, JoinType.LEFT,
            onColumn = Transactions.accountId, otherColumn = Accounts.id,
        )
        join.selectAll()
            .where {
                (Transactions.month eq month) and
                    (Transactions.amountMinor less 0L) and
                    Transactions.categoryId.isNull() and
                    (
                        ((Accounts.active eq true) and (Accounts.currencyCode eq UAH_CURRENCY_CODE)) or
                            Accounts.active.isNull()
                        )
            }
            .count()
    }

    /**
     * [includeId], when set, ORs `id = includeId` on top of the ordinary filter so that one
     * specific row is never excluded by it — used to keep the just-filed row on
     * `/transactions?uncategorized=1` after filing defeats that very filter (finding 5 of
     * the 2026-09 branch review). The row is not prepended to the result afterwards: it's
     * spliced into the query itself, under the same ORDER BY/LIMIT/OFFSET as everything
     * else, so the database places it at the sort position its own occurredAt/amount
     * already gave it — exactly where it sat before the owner filed it, since neither of
     * those columns changes when only categoryId does. [count] deliberately has no
     * equivalent parameter: the "Усього: N" total must stay the honest, fully-filtered
     * count even while this one row is visibly included above it.
     *
     * [excludeAccountIds] takes the non-hryvnia accounts. A switched-off hryvnia account's
     * rows stay listed, carrying `inactiveAccountPill` to say they count towards nothing —
     * their amounts are at least *true*. A dollar row's is not: `formatMinor` appends ₴
     * unconditionally, so $44 600 renders as "44 600 ₴" and no pill fixes a wrong number.
     * Nothing non-hryvnia is stored any more; what remains is what landed before that gate
     * existed, and it cannot be rendered honestly at any price. [count] takes the same set
     * so "Усього: N" keeps matching the rows beneath it.
     */
    fun page(
        month: String?,
        categoryId: Long?,
        onlyUncategorized: Boolean,
        limit: Int,
        offset: Int,
        order: TxnOrder = TxnOrder.NEWEST,
        includeIncoming: Boolean = false,
        includeId: String? = null,
        excludeAccountIds: Set<String> = emptySet(),
    ): List<Txn> = transaction(db) {
        Transactions.selectAll()
            .where { filter(month, categoryId, onlyUncategorized, includeIncoming, includeId, excludeAccountIds) }
            .orderBy(*orderClauses(order))
            .limit(limit).offset(offset.toLong())
            .map(::toTxn)
    }

    // The id tie-break always shares its own column's direction, so paging stays stable
    // (no row skipped or repeated across pages) when several rows land on the same
    // occurredAt or amount.
    private fun orderClauses(order: TxnOrder): Array<Pair<Expression<*>, SortOrder>> = when (order) {
        TxnOrder.NEWEST -> arrayOf(Transactions.occurredAt to SortOrder.DESC, Transactions.id to SortOrder.DESC)
        TxnOrder.OLDEST -> arrayOf(Transactions.occurredAt to SortOrder.ASC, Transactions.id to SortOrder.ASC)
        TxnOrder.LARGEST -> arrayOf(Transactions.amountMinor to SortOrder.ASC, Transactions.id to SortOrder.ASC)
        TxnOrder.SMALLEST -> arrayOf(Transactions.amountMinor to SortOrder.DESC, Transactions.id to SortOrder.ASC)
    }

    fun count(
        month: String?,
        categoryId: Long?,
        onlyUncategorized: Boolean,
        includeIncoming: Boolean = false,
        excludeAccountIds: Set<String> = emptySet(),
    ): Long = transaction(db) {
        Transactions.selectAll()
            .where { filter(month, categoryId, onlyUncategorized, includeIncoming, null, excludeAccountIds) }
            .count()
    }

    // Exposed 0.57: isNull() is a member of ISqlExpressionBuilder, not a free
    // extension function, so the condition has to be built inside Op.build { ... }
    // to get that receiver in scope.
    //
    // includeIncoming defaults to false: the list is meant for triaging spend, and the
    // "лише без категорії" filter in particular is where an incoming transfer becomes
    // noise — it can never be categorized, so left visible it sits there permanently.
    // page and count take the identical filter set on purpose, so "Усього: N" always
    // matches exactly the rows rendered beneath it.
    private fun filter(
        month: String?,
        categoryId: Long?,
        onlyUncategorized: Boolean,
        includeIncoming: Boolean = false,
        includeId: String? = null,
        excludeAccountIds: Set<String> = emptySet(),
    ): Op<Boolean> = Op.build {
        var condition: Op<Boolean> = Op.TRUE
        if (month != null) condition = condition and (Transactions.month eq month)
        if (categoryId != null) condition = condition and (Transactions.categoryId eq categoryId)
        if (onlyUncategorized) condition = condition and Transactions.categoryId.isNull()
        if (!includeIncoming) condition = condition and (Transactions.amountMinor less 0L)
        // After the OR, never before it. Kotlin's infix `and`/`or` are one precedence level,
        // left-associative, so this lands as `(everything else OR id = includeId) AND
        // account not excluded` — the exclusion outranks includeId rather than being
        // escaped by it. That clause exists to re-admit one row the *owner's* filters would
        // hide, never an account whose money this app cannot render at all. An empty set
        // adds no clause: an empty NOT IN is not false-for-every-row, it is a no-op worth
        // not generating.
        if (includeId != null) condition = condition or (Transactions.id eq includeId)
        if (excludeAccountIds.isNotEmpty()) {
            condition = condition and (Transactions.accountId notInList excludeAccountIds)
        }
        condition
    }

    private fun toTxn(row: ResultRow) = Txn(
        id = row[Transactions.id],
        accountId = row[Transactions.accountId],
        occurredAt = row[Transactions.occurredAt],
        month = row[Transactions.month],
        amountMinor = row[Transactions.amountMinor],
        currencyCode = row[Transactions.currencyCode],
        description = row[Transactions.description],
        mcc = row[Transactions.mcc],
        originalMcc = row[Transactions.originalMcc],
        hold = row[Transactions.hold],
        categoryId = row[Transactions.categoryId],
        manuallyCategorized = row[Transactions.manuallyCategorized],
        rawJson = row[Transactions.rawJson],
        counterpartyKey = row[Transactions.counterpartyKey],
        counterpartySource = row[Transactions.counterpartySource],
    )
}
