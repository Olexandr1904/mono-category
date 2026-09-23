package app.ingest

import app.budget.BudgetService
import app.budget.Category
import app.budget.CategoryRepository
import app.budget.CategoryRules
import app.budget.ConduitMccException
import app.budget.ConduitMccRepository
import app.budget.CounterpartyRepository
import app.budget.CounterpartySource
import app.budget.DEFAULT_SEED_THRESHOLD_PCT
import app.budget.DefaultCategory
import app.budget.KYIV
import app.budget.MccConflictException
import app.budget.TransactionRepository
import app.budget.Txn
import app.budget.UpsertResult
import app.budget.counterpartyOf
import app.budget.currentMonthKey
import app.budget.decideCategory
import app.notify.Notifier
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * Notified about transactions whose MCC has no category. Task 17 wires the Telegram prompt here.
 *
 * Dispatched after [IngestService.ingest] releases its mutex, not while holding it — see that
 * method's own KDoc for why. Implementations must still fire and forget: dispatch the question
 * and return immediately. The answer to that question arrives as a separate top-level call (a
 * fresh webhook) that takes the mutex itself to apply the choice, so an implementation that
 * blocks inside [onUnknownMcc] waiting for it has nothing that will ever wake it up.
 */
interface UnknownMccObserver {
    suspend fun onUnknownMcc(txn: Txn)

    object NoOp : UnknownMccObserver {
        override suspend fun onUnknownMcc(txn: Txn) = Unit
    }
}

/**
 * [skipped] counts transactions refused outright — a non-hryvnia account's, see
 * [IngestService.ingest]. It defaults to zero and is deliberately absent from
 * [SyncReport]: [SyncService] only ever pulls statements for tracked accounts, so nothing
 * it ingests can be skipped, and only the webhook — whose caller discards this value —
 * ever sees a non-zero one.
 */
data class IngestOutcome(val inserted: Int, val updated: Int, val unchanged: Int, val skipped: Int = 0)

/**
 * Result of [IngestService.setTransactionCategoryChoice] — the web dropdown's entry point.
 * A category chosen from the transactions page used to touch only the one row clicked
 * ([app.budget.TransactionRepository.setCategory]) while the identical decision made in the
 * Telegram prompt bound the MCC and rewrote history ([IngestService.bindMcc]). This type
 * makes the four possible outcomes of unifying those two paths explicit, so the web layer
 * cannot forget one of them.
 */
sealed class CategoryChoiceOutcome {
    /**
     * The MCC was bound to the category and every automatic row sharing it — including
     * ones imported before this choice was made — was rewritten. [movedCount] is the
     * total number of rows [app.budget.TransactionRepository.recategorize] actually
     * changed, so the flash can state the blast radius rather than merely "done".
     */
    data class Bound(
        val mcc: Int,
        val merchant: String,
        val categoryId: Long,
        val categoryName: String,
        val movedCount: Int,
    ) : CategoryChoiceOutcome()

    /** The transaction carries no MCC, so nothing could be bound; only this row was assigned. */
    data class SingleRow(val categoryName: String) : CategoryChoiceOutcome()

    /** The MCC already belongs to a different category. Nothing was changed. */
    data class Conflict(val mcc: Int, val ownerName: String) : CategoryChoiceOutcome()

    /**
     * The selection was cleared. Only this row was cleared, as a manual override — the
     * MCC mapping (if any) is left exactly as it was, so one careless click cannot destroy
     * a binding that covers hundreds of other rows.
     */
    object Cleared : CategoryChoiceOutcome()

    /** The transaction id did not resolve to a row. */
    object NotFound : CategoryChoiceOutcome()

    /**
     * A transfer was filed and its recipient remembered, so every other automatic row with
     * the same recipient key followed — past ones included. [movedCount] is what
     * recategorize actually changed, so the flash can state the blast radius.
     */
    data class CounterpartyBound(
        val displayName: String,
        val categoryName: String,
        val movedCount: Int,
    ) : CategoryChoiceOutcome()

    /**
     * A transfer whose recipient the payload does not identify. Only this row was filed,
     * as a manual override — there is nothing to remember, and guessing from the
     * description would merge different people under one rule.
     */
    data class TransferSingleRow(val categoryName: String) : CategoryChoiceOutcome()
}

/**
 * Result of [IngestService.seedDefaultCategories], for the flash message on /categories.
 * [mccSkipped] and [mccConduitSkipped] are kept apart rather than merged into one count:
 * a code in [mccSkipped] is owned by a different category (an ownership conflict the owner
 * could resolve by hand), while one in [mccConduitSkipped] is not taken by anyone — it is a
 * conduit code, a different kind of thing entirely, and "already taken" would send the owner
 * looking for a category that holds it.
 */
data class SeedOutcome(
    val categoriesCreated: List<String>,
    val categoriesExisting: List<String>,
    val mccAdded: Int,
    val mccSkipped: List<Int>,
    val mccConduitSkipped: List<Int>,
)

/**
 * The only writer in the system. SQLite allows a single writer, and the sequence
 * "store transaction → recompute spending → check thresholds" must be atomic:
 * two parallel webhook redeliveries would otherwise produce two notifications.
 */
class IngestService(
    private val transactions: TransactionRepository,
    private val categories: CategoryRepository,
    private val budget: BudgetService,
    private val notifier: Notifier,
    private val counterparties: CounterpartyRepository,
    private val conduit: ConduitMccRepository,
    private val accounts: AccountRepository,
    private val unknownMcc: UnknownMccObserver = UnknownMccObserver.NoOp,
    private val clock: Clock = Clock.system(KYIV),
) {
    private val log = LoggerFactory.getLogger(IngestService::class.java)
    private val mutex = Mutex()

    private fun rules(): CategoryRules = CategoryRules(
        mccMapping = categories.mccMapping(),
        counterpartyRules = counterparties.mapping(),
        conduitMccs = conduit.list(),
    )

    /**
     * The observer fan-out used to be the last statement inside [mutex.withLock] and stayed
     * bounded because it only ran once per distinct unknown *code* — a handful per batch.
     * A conduit code broke that assumption: every transfer is its own question, and
     * [app.ingest.SyncService.syncMonth] ingests a whole statement page in one call, so a
     * forty-transfer page meant forty sequential `sendMessage` round-trips (up to Telegram's
     * 15s HTTP timeout each) holding the application's only writer for up to ten minutes —
     * blocking every web edit and webhook drain in the meantime. [UnknownMccObserver] takes
     * no lock and needs none, so the fan-out runs after the lock is released instead.
     */
    suspend fun ingest(items: List<Txn>): IngestOutcome {
        val newlyUnknown = mutableListOf<Txn>()
        val outcome = mutex.withLock { ingestLocked(items, newlyUnknown) }
        newlyUnknown.forEach { unknownMcc.onUnknownMcc(it) }
        return outcome
    }

    private suspend fun ingestLocked(items: List<Txn>, newlyUnknown: MutableList<Txn>): IngestOutcome {
        val currentRules = rules()
        // One read, two sets. These were two calls to untrackedIds()/foreignCurrencyIds(),
        // each opening its own transaction and materialising every column of every account
        // row to keep the ids — and WebhookProcessor.drain() calls ingest() once per event,
        // so a full 200-event drain paid 400 extra queries inside the application's only
        // writer mutex. One snapshot also makes the two sets consistent by construction.
        val knownAccounts = accounts.list()
        val untrackedAccounts = knownAccounts.filterNot { it.tracked }.mapTo(mutableSetOf()) { it.id }
        val foreignCurrencyAccounts = knownAccounts.filterNot { it.isUah }.mapTo(mutableSetOf()) { it.id }
        var inserted = 0
        var updated = 0
        var unchanged = 0
        var skipped = 0

        for (item in items) {
            // A dollar account's amounts are cents. `amount_minor` is hryvnia kopecks
            // everywhere and nothing downstream carries a currency, so storing one would
            // not be "an unsupported currency sitting harmlessly in a table" — it would be
            // a wrong number that /transactions prints with a ₴ on it and that
            // spentByCategory adds to the month. The owner's ruling (2026-09-22) was to
            // refuse it on the way in rather than store it and filter it at every reader.
            // Only the webhook can deliver one: Monobank registers it per *client*, so it
            // pushes every account the token can see, while SyncService asks only for the
            // tracked ones.
            if (item.accountId in foreignCurrencyAccounts) {
                log.info("skipping transaction {} on non-hryvnia account {}", item.id, item.accountId)
                skipped++
                continue
            }
            // Only conduit codes get a recipient key. A shop charge can carry a counterName
            // too, but there the code decides the category; a key beside it would be a
            // second source of truth for one and the same case.
            val withKey = if (item.mcc != null && item.mcc in currentRules.conduitMccs) {
                counterpartyOf(item.rawJson, item.description)
                    ?.let { item.copy(counterpartyKey = it.key, counterpartySource = it.source.code) }
                    ?: item
            } else {
                item
            }
            val decided = decideCategory(
                mcc = withKey.mcc,
                counterpartyKey = withKey.counterpartyKey,
                manuallyCategorized = withKey.manuallyCategorized,
                currentCategoryId = withKey.categoryId,
                rules = currentRules,
            )
            val categorized = withKey.copy(categoryId = decided.categoryId)
            when (transactions.upsert(categorized)) {
                UpsertResult.INSERTED -> {
                    inserted++
                    // Only outgoing transactions are worth asking about. Money coming in —
                    // a card top-up, a transfer from someone — is not an expense, and
                    // spending already ignores positive amounts, so a category for one
                    // would change no number on any screen. Asking anyway produced a
                    // Telegram question about MCC 6012, a top-up, which the owner has no
                    // reason to file: the tool analyses what leaves the account.
                    //
                    // The same reasoning covers an account switched off in Settings, and
                    // for the same reason: `spentByCategory` drops its rows outright, so
                    // filing one changes no number either. The owner keeps one card on and
                    // still got a question for each top-up from his FOP card. Unlike the
                    // non-hryvnia case above, these rows are still stored: switching an
                    // account off has never meant discarding its history (see
                    // AccountRepository.setActive), only that it stops shaping anything.
                    if (categorized.categoryId == null &&
                        categorized.mcc != null &&
                        categorized.amountMinor < 0 &&
                        categorized.accountId !in untrackedAccounts
                    ) {
                        newlyUnknown += categorized
                    }
                }
                UpsertResult.UPDATED -> updated++
                UpsertResult.UNCHANGED -> unchanged++
            }
        }

        if (inserted > 0 || updated > 0) checkThresholds()

        return IngestOutcome(inserted, updated, unchanged, skipped)
    }

    suspend fun setTransactionCategory(txnId: String, categoryId: Long?) = mutex.withLock {
        transactions.setCategory(txnId, categoryId, manual = true)
        checkThresholds()
    }

    /** Returns the number of rows [app.budget.TransactionRepository.recategorize] changed. */
    suspend fun bindMcc(mcc: Int, categoryId: Long): Int = mutex.withLock { bindMccLocked(mcc, categoryId) }

    /** Body of [bindMcc], factored out so [setTransactionCategoryChoice] can reuse it without
     *  re-entering [mutex] — kotlinx.coroutines' Mutex is not reentrant, so a nested
     *  `mutex.withLock` call from the same coroutine would deadlock. */
    private suspend fun bindMccLocked(mcc: Int, categoryId: Long): Int {
        categories.addMcc(categoryId, mcc)
        val moved = transactions.recategorize(rules(), categories.disabledIds())
        checkThresholds()
        return moved
    }

    /**
     * The transactions-page dropdown's entry point (spec: "the same decision behaves
     * differently depending on where it is made"). Pressing a category button in the
     * Telegram prompt calls [bindMcc], which binds the MCC and rewrites history; this used
     * to call [setTransactionCategory] instead, which touched only the one row. This method
     * routes the web choice through the same binding logic, so both paths behave alike —
     * with three cases handled explicitly rather than left to fall through, see
     * [CategoryChoiceOutcome].
     */
    suspend fun setTransactionCategoryChoice(txnId: String, categoryId: Long?): CategoryChoiceOutcome =
        mutex.withLock {
            val txn = transactions.byId(txnId) ?: return@withLock CategoryChoiceOutcome.NotFound

            if (categoryId == null) {
                // Never unbind the MCC here: that would let one careless click destroy a
                // mapping covering hundreds of transactions. Only this row is cleared, and
                // it is marked manual so recategorize() does not immediately repopulate it.
                transactions.setCategory(txnId, null, manual = true)
                checkThresholds()
                return@withLock CategoryChoiceOutcome.Cleared
            }

            val categoryName = categories.byId(categoryId)?.label ?: "category $categoryId"
            val mcc = txn.mcc
            if (mcc != null && conduit.contains(mcc)) {
                // The stored key/source (from ingest time, or backfilled by
                // setConduitMccs) is the one recategorize will actually match against —
                // recomputing here could only ever agree with it, so there is no reason to
                // duplicate the parse. counterpartyOf is still used just below, purely for
                // the human-readable name.
                val key = txn.counterpartyKey
                val source = CounterpartySource.entries.firstOrNull { it.code == txn.counterpartySource }
                // Spec §5: only an EXACT source (IBAN, EDRPOU, masked card) may link
                // silently. A name is a string somebody typed, not an account — IBAN and
                // EDRPOU do not arrive for personal cards at all, so `name:` is the
                // dominant key in production, and filing a rule off it would sweep every
                // other transfer with the same normalised name into one category the
                // moment any single one of them gets answered. A probable key still gets
                // stored on the row (the "seen before" hint is allowed to use it) — it just
                // may not create a rule.
                val exact = key != null && source != null && source != CounterpartySource.NAME
                if (!exact) {
                    transactions.setCategory(txnId, categoryId, manual = true)
                    checkThresholds()
                    return@withLock CategoryChoiceOutcome.TransferSingleRow(categoryName)
                }
                // Recomputed from the verbatim payload rather than reusing the row's own
                // description: the Telegram question rendered counterpartyOf's displayName
                // ("Петренко Іван"), and the rule must record the recipient the question
                // actually named, not "На картку" or whatever description text happened to
                // be on the row.
                // ifBlank floor: counterpartyOf can return a non-null Counterparty whose
                // displayName is itself blank (counterIban present, no counterName, and
                // Monobank's own description empty for this transfer type). Falling through
                // only to txn.description does not catch that case — the rule still needs
                // some label, or the /categories rules list renders "→ Оренда" with nobody
                // named as the recipient.
                val display = (counterpartyOf(txn.rawJson, txn.description)?.displayName ?: txn.description)
                    .ifBlank { key!! }
                val moved = setCounterpartyRuleLocked(key!!, categoryId, display)
                return@withLock CategoryChoiceOutcome.CounterpartyBound(display, categoryName, moved)
            }
            if (mcc == null) {
                // Nothing can be bound; assign this single row and say so plainly, rather
                // than letting the owner believe a rule was created.
                transactions.setCategory(txnId, categoryId, manual = true)
                checkThresholds()
                return@withLock CategoryChoiceOutcome.SingleRow(categoryName)
            }

            val moved = try {
                bindMccLocked(mcc, categoryId)
            } catch (e: MccConflictException) {
                return@withLock CategoryChoiceOutcome.Conflict(mcc, e.ownerName)
            }
            CategoryChoiceOutcome.Bound(mcc, txn.description, categoryId, categoryName, moved)
        }

    /** The label of the category a transaction currently sits in, or null if it has none. */
    fun transactionCategoryName(txnId: String): String? =
        transactions.byId(txnId)?.categoryId?.let { categories.byId(it)?.label }

    suspend fun setCounterpartyRule(key: String, categoryId: Long, displayName: String): Int =
        mutex.withLock { setCounterpartyRuleLocked(key, categoryId, displayName) }

    /** Body of [setCounterpartyRule]; the mutex is not reentrant, see [bindMccLocked]. */
    private suspend fun setCounterpartyRuleLocked(key: String, categoryId: Long, displayName: String): Int {
        counterparties.upsert(key, categoryId, displayName)
        val moved = transactions.recategorize(rules(), categories.disabledIds())
        checkThresholds()
        return moved
    }

    suspend fun deleteCounterpartyRule(key: String): Int = mutex.withLock {
        counterparties.delete(key)
        val moved = transactions.recategorize(rules(), categories.disabledIds())
        checkThresholds()
        moved
    }

    /**
     * The single-transaction escape hatch offered alongside the flash after
     * [setTransactionCategoryChoice] binds an MCC (spec: "a supermarket charge that was
     * really a gift"). Unbinds the MCC — so every other row the earlier bind swept up
     * reverts to uncategorized, rather than staying incorrectly attributed to this one-off
     * exception — and marks just this row with the chosen category, manually. A manually
     * categorized row is immune to [app.budget.TransactionRepository.recategorize], so this
     * override survives any later re-binding of the same MCC.
     */
    suspend fun applyCategoryToSingleTransaction(txnId: String, categoryId: Long) = mutex.withLock {
        val txn = transactions.byId(txnId)
        if (txn?.mcc != null) {
            categories.removeMcc(txn.mcc)
            transactions.recategorize(rules(), categories.disabledIds())
        }
        transactions.setCategory(txnId, categoryId, manual = true)
        checkThresholds()
    }

    /** Called after the MCC mapping changes anywhere. Rewrites every automatic row, all months. */
    suspend fun recategorizeAll() = mutex.withLock {
        transactions.recategorize(rules(), categories.disabledIds())
        checkThresholds()
    }

    /**
     * Fills in the starter set of categories and their MCC codes (spec: the owner faced an
     * empty MCC list with no basics at all). Safe to press repeatedly and safe to press
     * after the owner has already made changes:
     *
     * - a category is matched by name; an existing one is reused as-is (its limit, emoji
     *   and threshold are never touched — the owner may already have set a real limit)
     * - an MCC already owned by a *different* category is left alone, not stolen
     * - new categories are created with `monthlyLimitMinor = 0` — guessing a limit would
     *   be wrong, the owner sets those
     *
     * Runs as one ingest-mutex operation (not a loop of the individually-locked
     * `createCategory`/`bindMcc` calls, which would deadlock reentering this mutex) and
     * finishes by recategorizing existing history through the same lock, so already-
     * imported transactions land in these categories too.
     */
    suspend fun seedDefaultCategories(defaults: List<DefaultCategory>): SeedOutcome = mutex.withLock {
        val existingByName = categories.list().associateBy { it.name }
        val createdNames = mutableListOf<String>()
        val existingNames = mutableListOf<String>()
        var mccAdded = 0
        val mccSkipped = mutableListOf<Int>()
        val mccConduitSkipped = mutableListOf<Int>()

        defaults.forEach { def ->
            val category = existingByName[def.name]?.also { existingNames += def.name }
                ?: run {
                    val id = categories.create(def.name, def.emoji, 0L, DEFAULT_SEED_THRESHOLD_PCT)
                    createdNames += def.name
                    categories.byId(id)!!
                }
            // mccOf, not the accumulated conflict exception alone: addMcc's insertIgnore is a
            // silent no-op when the code already belongs to *this* category (a second press
            // of the button, or a category the owner already wired up by hand), and that must
            // not be counted as newly added.
            val already = categories.mccOf(category.id)
            def.mccs.forEach { mcc ->
                if (mcc in already) return@forEach
                try {
                    categories.addMcc(category.id, mcc)
                    mccAdded++
                } catch (e: MccConflictException) {
                    mccSkipped += mcc
                } catch (e: ConduitMccException) {
                    // The owner can mark a default's code (4829 under "Різне") conduit by
                    // hand at any time — the button's own promise is "safe to press
                    // repeatedly". Each addMcc is its own SQLite transaction, so an
                    // uncaught throw here would abort this forEach mid-list and leave
                    // every category after the one that hit it never created. Record it
                    // separately from mccSkipped — this code is not owned by anyone, it is
                    // a conduit code, and reporting it as "already taken" would send the
                    // owner looking for a category that has it.
                    mccConduitSkipped += mcc
                }
            }
        }

        transactions.recategorize(rules(), categories.disabledIds())
        checkThresholds()
        SeedOutcome(createdNames, existingNames, mccAdded, mccSkipped, mccConduitSkipped)
    }

    // --- category edits (spec §3: one of the four sources that must be serialised) ---------
    //
    // A web edit that mutates categories without the lock can interleave with an in-flight
    // ingest: lowering a limit while a webhook redelivery drains lets ingest run
    // checkThresholds against the NEW limit but the OLD mapping, claim the 100% row and send
    // — after which the correct alert can never fire, because the threshold for that category
    // and month is already burned. Routing every mutation through the same mutex ingest()
    // uses closes that window. Reads (list/byId/mccOf/mccMapping) stay outside the lock.

    suspend fun createCategory(name: String, emoji: String, monthlyLimitMinor: Long, thresholdPct: Int): Long =
        mutex.withLock {
            // A brand-new category owns no MCCs yet, so nothing to recategorize; still
            // re-check thresholds so a limit set above zero is evaluated immediately.
            categories.create(name, emoji, monthlyLimitMinor, thresholdPct).also { checkThresholds() }
        }

    suspend fun updateCategory(category: Category) = mutex.withLock {
        categories.update(category)
        checkThresholds()
    }

    // --- account edits (the third input the mutex has to serialise) -------------------------
    //
    // The `accounts` table used to be read-only as far as ingest was concerned. It is not
    // any more: `ingestLocked` reads it to decide whether a transaction is stored at all
    // and whether it raises a Telegram question, which puts it in exactly the position
    // `categories` is in — a web save or an hourly refresh landing mid-batch would have
    // half a statement page judged against the old account list and half against the new.
    // So both writers come through here, for the same reason `updateCategory` exists, and
    // nothing calls `accounts.setActive`/`replaceAll` directly any more.

    /** The Settings checkboxes. Thresholds are re-checked: switching an account on or off
     *  changes every total the alerts are measured against (see `spentByCategory`). */
    suspend fun setAccountsActive(activeIds: Set<String>) = mutex.withLock {
        accounts.setActive(activeIds)
        checkThresholds()
    }

    /** [SyncService.refreshAccounts]. Returns what [AccountRepository.replaceAll] returned. */
    suspend fun replaceAccounts(list: List<app.mono.MonoAccount>): Int = mutex.withLock {
        accounts.replaceAll(list).also { checkThresholds() }
    }

    suspend fun deleteCategory(id: Long) = mutex.withLock {
        // The category's MCC rows and its counterparty rules cascade away in the schema,
        // and any of its transactions' category_id is set to NULL alongside them — but a
        // row that lands on NULL that way can still have a home: its counterparty rule may
        // have been overriding a still-valid MCC binding (layer 2 beats layer 3), or its
        // MCC may no longer be a conduit and belong to a *different* category. Recategorize
        // so those rows are reclaimed immediately rather than sitting uncategorized until
        // some unrelated mutation happens to sweep them up.
        categories.delete(id)
        transactions.recategorize(rules(), categories.disabledIds())
        checkThresholds()
    }

    /** Throws [app.budget.MccConflictException] if any MCC belongs to a different category. */
    suspend fun setCategoryMcc(categoryId: Long, mccs: Set<Int>) = mutex.withLock {
        categories.setMcc(categoryId, mccs)
        transactions.recategorize(rules(), categories.disabledIds())
        checkThresholds()
    }

    /**
     * Replaces the set of conduit codes. A conduit code must not also be bound to a
     * category — a bound code would keep the MCC layer filing transfers and the question
     * that marking it conduit exists to provoke would never be asked — so any binding for a
     * newly conduit code is dropped here rather than left to be noticed later.
     */
    suspend fun setConduitMccs(mccs: Set<Int>): Int = mutex.withLock {
        // Codes joining the conduit set for the first time may already own settled
        // transactions with a complete verbatim raw_json but no counterparty_key — keys are
        // otherwise only ever derived at ingest time, and only for a code that is already
        // conduit at that moment. Backfilling before recategorize means those rows can be
        // picked up by a counterparty rule (or counted by countByCounterparty) instead of
        // being permanently stuck as if the payload had never carried a recipient.
        val newlyConduit = mccs - conduit.list()
        mccs.forEach { categories.removeMcc(it) }
        conduit.replaceAll(mccs)
        if (newlyConduit.isNotEmpty()) transactions.backfillCounterpartyKeys(newlyConduit)
        val moved = transactions.recategorize(rules(), categories.disabledIds())
        checkThresholds()
        moved
    }

    /**
     * Thresholds are evaluated for the current month only. Recategorizing history must not
     * fire notifications about months that are already over.
     */
    private suspend fun checkThresholds() {
        notifier.checkThresholds(budget.monthSummary(currentMonthKey(clock)))
    }
}
