package app.ingest

import app.budget.UAH_CURRENCY_CODE
import app.db.Accounts
import app.mono.MonoAccount
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

data class StoredAccount(
    val id: String,
    val maskedPan: String,
    val type: String,
    val currencyCode: Int,
    val balanceMinor: Long,
    val active: Boolean,
) {
    val isUah: Boolean get() = currencyCode == UAH_CURRENCY_CODE

    /**
     * Whether this account shapes anything at all: statements pulled, spending counted,
     * Telegram questions asked. [active] alone is not enough — a dollar account's amounts
     * are cents, not kopecks, and nothing downstream carries a currency (see
     * [UAH_CURRENCY_CODE]), so it is untracked no matter what the switch says.
     */
    val tracked: Boolean get() = active && isUah
}

class AccountRepository(private val db: Database) {

    /**
     * Records every account Monobank reports, hryvnia or not.
     *
     * Non-hryvnia accounts used to be filtered out here, which read as "we only track UAH"
     * but actually meant the application had never heard of the dollar account at all —
     * and an account with no row is indistinguishable from one that predates the first
     * sync, which everything downstream counts as active (see [untrackedIds] and
     * [app.budget.TransactionRepository.spentByCategory]). Monobank registers its webhook
     * per *client*, so it pushed every dollar operation regardless; each one was stored
     * with its cents in a kopecks column, counted towards the month's spending as hryvnia,
     * and asked about in Telegram. Storing the account is what lets those transactions be
     * recognised and refused. They are inserted `active = false` on top of that, so the
     * two gates ([StoredAccount.tracked] and [setActive]) both have to fail before a
     * dollar amount can be read as hryvnia again.
     *
     * `active` used to be hardcoded `true` for every row, so a refresh silently re-enabled
     * an account the owner had switched off in Settings (a FOP account, an eAid card). That
     * was fixed by carrying `active` forward for any known id — but the carry-forward ran
     * through `deleteAll()` + re-insert, so an account that stops coming back from Monobank
     * at all (closed, expired, scope narrowed) lost its row entirely. `spentByCategory`'s
     * `LEFT JOIN` then read the missing row as `active IS NULL`, which counts as active —
     * silently resurrecting every historical total the owner had switched off, with no row
     * left in Settings to switch off again. So this never deletes: accounts Monobank
     * returns are inserted (new) or updated in place (known, `active` left untouched);
     * accounts it stops returning simply aren't touched this pass and keep whatever
     * `active` they already had. An account the owner switched off stays off through any
     * number of syncs that don't mention it again.
     */
    fun replaceAll(accounts: List<MonoAccount>): Int = transaction(db) {
        val existingIds = Accounts.selectAll().map { it[Accounts.id] }.toSet()
        accounts.forEach { account ->
            if (account.id in existingIds) {
                Accounts.update({ Accounts.id eq account.id }) {
                    it[maskedPan] = account.maskedPan.firstOrNull().orEmpty()
                    it[type] = account.type
                    it[currencyCode] = account.currencyCode
                    it[balanceMinor] = account.balance
                    it[updatedAt] = Instant.now().epochSecond
                    // active intentionally untouched: preserved exactly as the owner set it.
                }
            } else {
                Accounts.insert {
                    it[id] = account.id
                    it[maskedPan] = account.maskedPan.firstOrNull().orEmpty()
                    it[type] = account.type
                    it[currencyCode] = account.currencyCode
                    it[balanceMinor] = account.balance
                    // A hryvnia account starts switched on (the owner switches off what he
                    // does not want); anything else starts off and stays off — its switch
                    // is not a preference, see [StoredAccount.tracked].
                    it[active] = account.isUah
                    it[updatedAt] = Instant.now().epochSecond
                }
            }
        }
        accounts.size
    }

    fun list(): List<StoredAccount> = transaction(db) {
        Accounts.selectAll().map { row ->
            StoredAccount(
                id = row[Accounts.id],
                maskedPan = row[Accounts.maskedPan],
                type = row[Accounts.type],
                currencyCode = row[Accounts.currencyCode],
                balanceMinor = row[Accounts.balanceMinor],
                active = row[Accounts.active],
            )
        }
    }

    fun ids(): List<String> = list().map { it.id }

    /** Accounts [SyncService] is allowed to pull statements for. */
    fun activeIds(): List<String> = list().filter { it.tracked }.map { it.id }

    /** The accounts whose switch means anything, and so the only ones Settings offers. */
    fun trackableList(): List<StoredAccount> = list().filter { it.isUah }

    /**
     * Known accounts that shape nothing — switched off, or not hryvnia. [IngestService]
     * raises no Telegram question about these.
     *
     * Deliberately the *untracked* set rather than the complement of [activeIds]: an
     * account with no row here at all is absent from this set and therefore treated as
     * tracked, exactly as [app.budget.TransactionRepository.spentByCategory]'s `LEFT JOIN`
     * treats it. A webhook can arrive before the first account sync ever runs, and reading
     * "unknown" as "switched off" would silently stop asking about the owner's real card.
     */
    fun untrackedIds(): Set<String> = list().filterNot { it.tracked }.map { it.id }.toSet()

    /**
     * Known accounts whose amounts are not hryvnia kopecks, so [IngestService] must not
     * store their transactions at all. Separate from [untrackedIds] because the two
     * failures differ in kind: a switched-off hryvnia account holds numbers that are
     * merely uncounted, while a dollar account holds numbers that are *wrong* in every
     * column that reads them — see [UAH_CURRENCY_CODE].
     */
    fun foreignCurrencyIds(): Set<String> = list().filterNot { it.isUah }.map { it.id }.toSet()

    /**
     * Persists which accounts are switched on, from a Settings-page checkbox save. Call it
     * through [IngestService.setAccountsActive], never directly: `accounts` is one of
     * ingest's inputs now.
     *
     * Every known **hryvnia** account not in the [activeIds] argument is turned off; a
     * non-hryvnia row is skipped entirely, neither switched on nor off (see below).
     * Nothing here deletes the
     * transactions already imported from a deactivated account — they stay in the
     * database and still render on /transactions — but as of
     * [app.budget.TransactionRepository.spentByCategory] they no longer count toward any
     * spending total, the donut or a threshold alert. This used to be the opposite: only
     * future syncs stopped, and history kept shaping every number on the dashboard. That
     * was wrong on the owner's real data — a sole-trader account whose only August
     * activity was eleven transfers to his own black card stayed counted as spending even
     * after he switched it off, because "off" hadn't meant "stop counting" yet, and those
     * eleven transfers alone were most of a headline total that should never have included
     * them. The owner's ruling: an account he has switched off should not shape his
     * numbers, past or future.
     */
    fun setActive(activeIds: Set<String>) = transaction(db) {
        Accounts.selectAll().map { it[Accounts.id] to it[Accounts.currencyCode] }.forEach { (id, currency) ->
            // A non-hryvnia account is skipped rather than switched off: the form never
            // offers one (see [trackableList]), so its id can only arrive here in a
            // hand-made POST, and honouring that would put cents back into a kopecks
            // total. Skipping also means its stored `false` is never rewritten by an
            // ordinary save of the accounts the owner *can* see.
            if (currency != UAH_CURRENCY_CODE) return@forEach
            Accounts.update({ Accounts.id eq id }) { it[active] = id in activeIds }
        }
        Unit
    }
}
