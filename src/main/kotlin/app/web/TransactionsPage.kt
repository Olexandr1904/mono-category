package app.web

import app.budget.BudgetService
import app.budget.Category
import app.budget.CategoryRepository
import app.budget.CategoryRules
import app.budget.ConduitMccRepository
import app.budget.CounterpartyRepository
import app.budget.CounterpartySource
import app.budget.KYIV
import app.budget.TransactionRepository
import app.budget.TxnOrder
import app.budget.Txn
import app.budget.counterpartyOf
import app.budget.currentMonthKey
import app.budget.formatFullDay
import app.budget.formatMinor
import app.budget.formatSignedMinor
import app.budget.isTinyShare
import app.budget.kyivDate
import app.budget.MonthSummary
import app.budget.nextMonthKey
import app.budget.previousMonthKey
import app.budget.safeMonthKey
import app.budget.sharePercent
import app.db.SettingsRepository
import app.i18n.Copy
import app.i18n.copy
import app.ingest.AccountRepository
import app.ingest.CategoryChoiceOutcome
import app.ingest.IngestService
import io.ktor.server.application.*
import io.ktor.server.html.respondHtml
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.html.*
import java.time.Clock
import java.time.LocalDate

/**
 * The month stepper scopes the page to one month at a time (design-handoff.md §3 +
 * "Interactions": "the app only tracks one month at a time"), so there is no cross-month
 * pagination to bound — this just keeps one pathological month (thousands of rows) from
 * loading unbounded.
 */
private const val MAX_ROWS_PER_MONTH = 4000

fun Route.transactionRoutes(
    transactions: TransactionRepository,
    categories: CategoryRepository,
    ingest: IngestService,
    settings: SettingsRepository,
    conduit: ConduitMccRepository,
    counterparties: CounterpartyRepository,
    accounts: AccountRepository,
    budget: BudgetService,
    clock: Clock = Clock.system(KYIV),
    // Mirrors installAuth's flag: the CSRF cookie every form on this page relies on
    // travels under the same Secure rule as the session cookie.
    secureCookies: Boolean = true,
) {
    get("/transactions") {
        val query = call.request.queryParameters
        // Validated, not taken verbatim — same guard as the dashboard's own month stepper
        // (previousMonthKey/nextMonthKey throw on a malformed key).
        val month = safeMonthKey(query["month"], clock)
        val categoryId = query["category"]?.toLongOrNull()
        val order = TxnOrder.fromParam(query["order"])
        // Tri-state by design (design-handoff.md §3.3): "лише без категорії" defaults ON,
        // so only an explicit "0" turns it off — absence is not the same as "off".
        //
        // Finding 1 (CRITICAL) of the 2026-09-04 review: forced off whenever a category is
        // selected. "category eq X" AND "category IS NULL" is unsatisfiable, and the
        // default is ON, so the plain default submission of the category filter (which
        // carries no uncategorized param at all) used to collide into "Нічого не знайдено"
        // for every single category. This also renders the "лише без категорії" chip
        // inactive whenever a category is picked, matching what the filter is actually
        // doing rather than what the query string happened to say.
        val onlyUncategorized = query["uncategorized"] != "0" && categoryId == null
        val includeIncoming = query["incoming"] == "1"
        // The rule strip's pending target (design-system.md conflict #2): lives only in the
        // query string, never persisted, and re-validated below rather than trusted — a
        // hand-edited URL must not be able to resurrect a stale or already-resolved strip.
        val ruleForId = query["ruleFor"]?.takeIf { it.isNotBlank() }
        // Finding 2 (IMPORTANT) of the 2026-09-04 review: the row a POST just filed, kept
        // visible (and its redirect anchor resolvable) for exactly one render regardless of
        // whether a rule strip is open — see the /category handler below. Independent of
        // ruleForId/suggestion on purpose: a row with no sibling gets no rule strip at all,
        // but must still not vanish out from under its own anchor.
        val filedId = query["filed"]?.takeIf { it.isNotBlank() }

        val allCategoriesIncludingDisabled = categories.list(includeDisabled = true)
        // Categories §5: the enabled set on /categories is the only source for this
        // dropdown — both the filter and every row's own select.
        val enabledCategories = allCategoriesIncludingDisabled.filter { it.enabled }
        val enabledIds = enabledCategories.map { it.id }.toSet()
        val categoriesById = allCategoriesIncludingDisabled.associateBy { it.id }
        val rules = CategoryRules(categories.mccMapping(), counterparties.mapping(), conduit.list())
        // Must be the predicate spentByCategory actually applies, not just `active`: that
        // query keeps a row only when its account is `active AND currency = UAH` (an absent
        // account row counts as included). `filterNot { it.active }` left a non-hryvnia
        // account that was still switched on rendering with no pill and counted into the
        // day total, while every real total dropped it. untrackedIds() is exactly
        // StoredAccount.tracked inverted.
        val untrackedAccountIds = accounts.untrackedIds()
        // A strict subset of the above, kept apart because the two get different treatment:
        // a switched-off hryvnia account's rows stay listed under a pill saying they count
        // towards nothing, while a non-hryvnia row is dropped from the list entirely. Its
        // amount is cents rendered by formatMinor, which appends ₴ unconditionally — there
        // is no pill that makes "44 600 ₴" an honest way to show $44 600. Nothing
        // non-hryvnia is stored any more; this is the residue from before that gate.
        val foreignAccountIds = accounts.foreignCurrencyIds()

        // Re-derived from the database, not trusted from the query string: a suggestion
        // shown for a row that no longer qualifies (category cleared since, or swept up by
        // some other change) must not render a strip that promises something stale.
        val ruleForTxn = ruleForId?.let(transactions::byId)
        val suggestion = ruleForTxn?.categoryId?.let { ruleSuggestionFor(ruleForTxn, rules, transactions) }
        // OR'd into the query itself (not prepended afterwards) so the filed row keeps its
        // ordinary sort position even when the active filter would otherwise exclude it —
        // see TransactionRepository.page's own doc and design-system.md's conflict #2.
        val includeId = filedId

        val rows = transactions.page(
            month, categoryId, onlyUncategorized, MAX_ROWS_PER_MONTH, 0, order, includeIncoming, includeId,
            excludeAccountIds = foreignAccountIds,
        )

        val summary = budget.monthSummary(month)
        // "Усього" takes the same exclusion as `rows` so it keeps matching what is rendered
        // beneath it; "Не розібрано" takes countUnresolved, which drops every untracked
        // account — the switched-off ones too, since filing one of those changes no number.
        val totalTxnCount = transactions.count(
            month, null, false, includeIncoming = false, excludeAccountIds = foreignAccountIds,
        )
        val unresolvedCount = transactions.countUnresolved(month)

        // Interactions: "arrows clamp at the ends of the available month range".
        val monthsWithData = transactions.distinctMonths()
        val oldestAllowed = monthsWithData.minOrNull() ?: month
        val newestAllowed = maxOf(monthsWithData.maxOrNull() ?: month, currentMonthKey(clock))

        val copy = settings.copy()
        val csrfToken = call.csrfToken(secureCookies)
        val backLink = pageLink(month, categoryId, onlyUncategorized, includeIncoming, order)
        val filtersActive = categoryId != null || order != TxnOrder.NEWEST || !onlyUncategorized || includeIncoming

        call.respondHtml {
            page(
                copy = copy,
                title = copy.transactionsTitle,
                active = "transactions",
                csrfToken = csrfToken,
                flash = query["flash"],
                error = query["error"],
                headerMeta = copy.headerMeta(accounts.trackableList().size),
            ) {
                div("stack") {
                    pageHeader(copy.transactionsKicker, copy.transactionsTitle) {
                        val prevMonth = previousMonthKey(month).takeIf { it >= oldestAllowed }
                        val nextMonth = nextMonthKey(month).takeIf { it <= newestAllowed }
                        monthStepper(
                            prevMonth?.let { pageLink(it, categoryId, onlyUncategorized, includeIncoming, order) },
                            copy.monthLabel(month),
                            nextMonth?.let { pageLink(it, categoryId, onlyUncategorized, includeIncoming, order) },
                        )
                    }

                    statStrip(copy, summary, unresolvedCount, totalTxnCount)

                    filterBar(
                        copy, month, categoryId, enabledCategories, order,
                        onlyUncategorized, includeIncoming, filtersActive,
                    )

                    if (rows.isEmpty()) {
                        emptyState(copy)
                    } else if (order.groupsByDay) {
                        val groups = rows.groupBy { kyivDate(it.occurredAt) }
                        groups.forEach { (date, dayRows) ->
                            dayGroup(
                                copy, date, dayRows, enabledCategories, categoriesById, enabledIds,
                                backLink, csrfToken, untrackedAccountIds, ruleForId, suggestion,
                            )
                        }
                    } else {
                        dayGroup(
                            copy, null, rows, enabledCategories, categoriesById, enabledIds,
                            backLink, csrfToken, untrackedAccountIds, ruleForId, suggestion,
                        )
                    }
                }
            }
        }
    }

    // design-system.md conflict #1: the select persists immediately and touches only this
    // one row — app.ingest.IngestService.setTransactionCategory, never
    // setTransactionCategoryChoice. Nothing here can bind an MCC or create a counterparty
    // rule; that only happens in the /apply-rule handler below, behind an explicit click.
    post("/transactions/{id}/category") {
        val id = call.parameters["id"].orEmpty()
        val form = call.formParameters()
        val categoryId = form["categoryId"]?.takeIf { it.isNotBlank() }?.toLongOrNull()
        val copy = settings.copy()
        val back = safeBackLink(form["back"])
        val anchor = "txn-$id"

        ingest.setTransactionCategory(id, categoryId)

        val extra = mutableMapOf<String, String>()
        // Finding 2 (IMPORTANT) of the 2026-09-04 review: supplied unconditionally, not
        // only when a rule strip is offered. A row with no sibling gets no strip and no
        // "ruleFor" param, but it still just stopped matching "лише без категорії" — without
        // this it vanishes from the very render its own #txn-$id anchor points at, so the
        // browser sits at the top of the list instead of scrolling back to it.
        extra["filed"] = id
        val categoryName = categoryId?.let { categories.byId(it)?.label }
        if (categoryId != null && categoryName != null) {
            // Only re-evaluated when a category was actually chosen (not cleared) — a
            // suggestion offers to spread a category *onto* other rows, which a clear
            // can never do.
            val updated = transactions.byId(id)
            val rules = CategoryRules(categories.mccMapping(), counterparties.mapping(), conduit.list())
            val suggestion = updated?.let { ruleSuggestionFor(it, rules, transactions) }
            if (suggestion != null) extra["ruleFor"] = id
        }

        val message = if (categoryId != null && categoryName != null) {
            copy.categorySavedForRow(categoryName)
        } else {
            copy.categoryClearedForRow
        }
        call.redirectWith(back, message, extraParams = extra, fragment = anchor)
    }

    // The rule strip's "Застосувати" — the only place left on this page that can bind an
    // MCC or create a counterparty rule. Routes into the exact same method the Telegram
    // prompt and the pre-redesign dropdown used (setTransactionCategoryChoice), rather than
    // a third path, so the blast-radius logic (conflicts, exact-recipient-only counterparty
    // binding, conduit codes) is defined in exactly one place.
    post("/transactions/{id}/apply-rule") {
        val id = call.parameters["id"].orEmpty()
        val form = call.formParameters()
        val copy = settings.copy()
        val back = safeBackLink(form["back"])
        val anchor = "txn-$id"
        val txn = transactions.byId(id)
        val categoryId = txn?.categoryId
        if (txn == null || categoryId == null) {
            call.redirectWith(back, copy.unknownTransaction, isError = true)
            return@post
        }
        when (val outcome = ingest.setTransactionCategoryChoice(id, categoryId)) {
            is CategoryChoiceOutcome.Bound -> call.redirectWith(
                back,
                copy.mccBoundFromTransaction(outcome.merchant, outcome.mcc, outcome.categoryName, outcome.movedCount),
                fragment = anchor,
            )
            is CategoryChoiceOutcome.CounterpartyBound -> call.redirectWith(
                back,
                copy.counterpartyBoundFromTransaction(outcome.displayName, outcome.categoryName, outcome.movedCount),
                fragment = anchor,
            )
            is CategoryChoiceOutcome.SingleRow ->
                call.redirectWith(back, copy.singleTransactionCategorized(outcome.categoryName), fragment = anchor)
            is CategoryChoiceOutcome.TransferSingleRow ->
                call.redirectWith(back, copy.transferSingleRow(outcome.categoryName), fragment = anchor)
            is CategoryChoiceOutcome.Conflict ->
                call.redirectWith(back, copy.mccConflict(outcome.mcc, outcome.ownerName), isError = true, fragment = anchor)
            CategoryChoiceOutcome.NotFound -> call.redirectWith(back, copy.unknownTransaction, isError = true)
            // Structurally unreachable — categoryId is always non-null here (guarded
            // above) — but matched exhaustively rather than folded into an `else`, per
            // CLAUDE.md: the sealed class exists "so no case is silently dropped."
            CategoryChoiceOutcome.Cleared -> call.redirectWith(back, copy.categoryClearedForRow, fragment = anchor)
        }
    }
}

/**
 * The `back` field is submitted by the client, so it decides where the response
 * redirects to. Unvalidated, it is an open redirect: `//evil.example` is a
 * protocol-relative URL that browsers follow off-site, and `/\evil.example` is treated
 * the same way by several of them.
 *
 * Only same-origin paths under `/transactions` are ever legitimate here — that is the
 * only value the page itself ever puts in the field — so anything else falls back to
 * the plain listing rather than being sanitised into something that might still escape.
 */
internal fun safeBackLink(raw: String?): String {
    val candidate = raw?.takeIf { it.isNotBlank() } ?: return "/transactions"
    if (candidate.any { it == '\\' || it == '\n' || it == '\r' || it.code < 0x20 }) return "/transactions"
    if (!candidate.startsWith("/transactions")) return "/transactions"
    // "/transactions" must end there or continue with a path/query/fragment separator,
    // so "/transactions.evil.example" cannot ride in on the prefix check above.
    val rest = candidate.removePrefix("/transactions")
    if (rest.isNotEmpty() && rest[0] !in "/?#") return "/transactions"
    return candidate
}

private fun pageLink(
    month: String,
    categoryId: Long?,
    onlyUncategorized: Boolean,
    includeIncoming: Boolean,
    order: TxnOrder,
): String =
    buildString {
        append("/transactions?month=").append(month)
        categoryId?.let { append("&category=").append(it) }
        // Absence already means "on" (the default) — an explicit "0" is only written when
        // the toggle is off, so a plain /transactions?month=... link still reproduces the
        // default filter state instead of silently turning it off.
        if (!onlyUncategorized) append("&uncategorized=0")
        if (includeIncoming) append("&incoming=1")
        if (order != TxnOrder.NEWEST) append("&order=").append(order.param)
    }

// --- summary strip -----------------------------------------------------------------------

private fun FlowContent.statStrip(copy: Copy, summary: MonthSummary, unresolvedCount: Long, totalTxnCount: Long) {
    div("stat-strip") {
        div("stat-cell") {
            div("stat-label") { +copy.statSpentThisMonth }
            div("stat-figure-row") { span("stat-figure mono") { +formatMinor(summary.totalSpentMinor) } }
        }
        div("stat-cell") {
            div("stat-label") { +copy.uncategorizedLabel }
            div("stat-figure-row") { span("stat-figure mono") { +formatMinor(summary.uncategorizedMinor) } }
            div("stat-caption") {
                +if (isTinyShare(summary.uncategorizedMinor, summary.totalSpentMinor)) {
                    copy.shareOfMonthTiny
                } else {
                    copy.shareOfMonth(sharePercent(summary.uncategorizedMinor, summary.totalSpentMinor))
                }
            }
        }
        div("stat-cell") {
            div("stat-label") { +copy.statUnresolved }
            div("stat-figure-row") { span("stat-figure mono") { +unresolvedCount.toString() } }
            div("stat-caption") { +copy.ofTransactionsCount(totalTxnCount) }
        }
    }
}

// --- filter bar ----------------------------------------------------------------------------

private fun FlowContent.filterBar(
    copy: Copy,
    month: String,
    categoryId: Long?,
    enabledCategories: List<Category>,
    order: TxnOrder,
    onlyUncategorized: Boolean,
    includeIncoming: Boolean,
    filtersActive: Boolean,
) {
    div("filter-bar") {
        form(action = "/transactions", method = FormMethod.get) {
            hiddenInput(name = "month") { value = month }
            if (!onlyUncategorized) hiddenInput(name = "uncategorized") { value = "0" }
            if (includeIncoming) hiddenInput(name = "incoming") { value = "1" }
            if (order != TxnOrder.NEWEST) hiddenInput(name = "order") { value = order.param }
            div("filter-select-wrap") {
                select(classes = "filter-select") {
                    name = "category"
                    attributes["data-submit-on-change"] = "true"
                    attributes["aria-label"] = copy.categoryHeader
                    option { value = ""; selected = categoryId == null; +copy.allCategoriesOption }
                    enabledCategories.forEach { c ->
                        option { value = c.id.toString(); selected = c.id == categoryId; +c.label }
                    }
                }
            }
        }
        form(action = "/transactions", method = FormMethod.get) {
            hiddenInput(name = "month") { value = month }
            categoryId?.let { hiddenInput(name = "category") { value = it.toString() } }
            if (!onlyUncategorized) hiddenInput(name = "uncategorized") { value = "0" }
            if (includeIncoming) hiddenInput(name = "incoming") { value = "1" }
            div("filter-select-wrap") {
                select(classes = "filter-select") {
                    name = "order"
                    attributes["data-submit-on-change"] = "true"
                    attributes["aria-label"] = copy.orderFilterLabel
                    sortOption(copy, TxnOrder.NEWEST, order)
                    sortOption(copy, TxnOrder.OLDEST, order)
                    sortOption(copy, TxnOrder.LARGEST, order)
                }
            }
        }
        div("filter-divider") {}
        a(
            href = pageLink(month, categoryId, !onlyUncategorized, includeIncoming, order),
            classes = "filter-chip" + if (onlyUncategorized) " active" else "",
        ) {
            span("chip-dot") {}
            +copy.uncategorizedOnlyLabel
        }
        a(
            href = pageLink(month, categoryId, onlyUncategorized, !includeIncoming, order),
            classes = "filter-chip" + if (includeIncoming) " active" else "",
        ) {
            span("chip-dot") {}
            +copy.showIncomingLabel
        }
        if (filtersActive) {
            a(href = "/transactions?month=$month", classes = "filter-reset") { +copy.clearFilters }
        }
    }
}

private fun SELECT.sortOption(copy: Copy, value: TxnOrder, selectedOrder: TxnOrder) {
    option {
        this.value = value.param
        selected = value == selectedOrder
        +when (value) {
            TxnOrder.NEWEST -> copy.orderNewest
            TxnOrder.OLDEST -> copy.orderOldest
            TxnOrder.LARGEST -> copy.orderLargest
            TxnOrder.SMALLEST -> copy.orderLargest // never rendered; exhaustive only
        }
    }
}

// --- day grouping + rows -------------------------------------------------------------------

/**
 * The day header's figure, filtered the same way every other reader of spending truth
 * filters its own (CLAUDE.md, *Spending truth*): a row is out if its account is untracked
 * **or** its category does not count as spending.
 *
 * It used to be a plain `dayRows.sumOf { it.amountMinor }`. On the owner's real September
 * 2026 data that printed day headers summing to 84% more than the month total on the
 * same page, and put the very row it had just labelled
 * "рахунок вимкнено — не рахується" into that row's own day figure.
 *
 * Net, not spend-only: the sign is deliberate (see `formatSignedMinor` at the call site),
 * so income shown via "показати надходження" still offsets the day it landed on.
 */
internal fun countedDayTotal(
    rows: List<Txn>,
    untrackedAccountIds: Set<String>,
    categoriesById: Map<Long, Category>,
): Long = rows
    .filterNot { it.accountId in untrackedAccountIds }
    .filterNot { txn ->
        val category = txn.categoryId?.let(categoriesById::get)
        category != null && !category.countsAsSpending
    }
    .sumOf { it.amountMinor }

private fun FlowContent.dayGroup(
    copy: Copy,
    date: LocalDate?,
    dayRows: List<Txn>,
    enabledCategories: List<Category>,
    categoriesById: Map<Long, Category>,
    enabledIds: Set<Long>,
    backLink: String,
    csrfToken: String,
    untrackedAccountIds: Set<String>,
    ruleForId: String?,
    suggestion: RuleSuggestion?,
) {
    div("day-group") {
        // Null date: an amount-ordered flat list (see TxnOrder.groupsByDay). No header, so
        // no day label and no day total — a figure over an arbitrary slice of the month
        // would mean nothing.
        if (date != null) {
            div("day-group-header") {
                span("day-group-label") { +copy.dayGroupHeader(date) }
                // Finding 12 (MINOR) of the 2026-09-04 review: formatSignedMinor, not
                // formatMinor — a day can net positive when income (shown via "показати
                // надходження") outweighs that day's spend, and formatMinor renders a
                // non-negative amount with no sign at all, reading like an expense next to
                // the "+"-prefixed income rows above it.
                span("day-group-total mono") {
                    +formatSignedMinor(countedDayTotal(dayRows, untrackedAccountIds, categoriesById))
                }
            }
        }
        div("list-card") {
            dayRows.forEach { txn ->
                transactionRow(copy, txn, enabledCategories, categoriesById, enabledIds, backLink, csrfToken, untrackedAccountIds)
                if (suggestion != null && txn.id == ruleForId) {
                    ruleBanner(copy, txn, suggestion, backLink, csrfToken)
                }
            }
        }
    }
}

private fun FlowContent.transactionRow(
    copy: Copy,
    txn: Txn,
    enabledCategories: List<Category>,
    categoriesById: Map<Long, Category>,
    enabledIds: Set<Long>,
    backLink: String,
    csrfToken: String,
    untrackedAccountIds: Set<String>,
) {
    div("row") {
        id = "txn-${txn.id}"
        div("row-monogram") { +monogramOf(txn.description) }
        div("row-main") {
            div("row-title-line") {
                span("row-title") { +txn.description.trim().ifEmpty { txn.description } }
                if (txn.hold) span("pill pill-hold") { +copy.holdPill }
                if (txn.accountId in untrackedAccountIds) span("pill pill-outline") { +copy.inactiveAccountPill }
                // The second way a row stops counting, and the one that used to be silent:
                // countedDayTotal drops it, so without this the header sits above rows that
                // visibly sum to something else. Same words /status marks the category with.
                val category = txn.categoryId?.let(categoriesById::get)
                if (category != null && !category.countsAsSpending) {
                    span("pill pill-outline") { +copy.notCountedPill }
                }
            }
            div("row-meta mono") {
                +buildString {
                    txn.mcc?.let { append(copy.mccAbbrev).append(' ').append(it).append(" · ") }
                    append(formatFullDay(txn.occurredAt))
                }
            }
        }
        // The current category, even a since-disabled one, must always be a real option —
        // never a select whose "selected" value silently isn't among its own <option>s.
        val current = txn.categoryId?.let(categoriesById::get)
        val options = if (current != null && current.id !in enabledIds) enabledCategories + current else enabledCategories
        form(action = "/transactions/${txn.id}/category", method = FormMethod.post, classes = "select-wrap") {
            csrfField(csrfToken)
            hiddenInput(name = "back") { value = backLink }
            select(classes = if (txn.categoryId != null) "select-row is-filled" else "select-row") {
                name = "categoryId"
                // Safe to auto-submit on change again (unlike the pre-redesign per-row
                // select): this now touches only this one row (setTransactionCategory,
                // never setTransactionCategoryChoice), so an ArrowDown-fires-change can
                // cost at most one mis-keyed row, recoverable by re-picking — never a
                // binding across dozens of rows. See design-system.md conflict #1.
                attributes["data-submit-on-change"] = "true"
                option { value = ""; selected = txn.categoryId == null; +copy.chooseCategoryOption }
                options.forEach { c -> option { value = c.id.toString(); selected = c.id == txn.categoryId; +c.label } }
            }
        }
        span("row-amount mono" + if (txn.amountMinor >= 0) " income" else "") { +formatSignedMinor(txn.amountMinor) }
    }
}

/** First letters of the first two words, or the first two characters of a one-word
 *  description — mirrors the design reference's own `monogram()`. */
private fun monogramOf(description: String): String {
    val trimmed = description.trim()
    val words = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
    val letters = if (words.size > 1) words[0].take(1) + words[1].take(1) else trimmed.take(2)
    return letters.uppercase().ifBlank { "?" }
}

private fun FlowContent.ruleBanner(copy: Copy, txn: Txn, suggestion: RuleSuggestion, backLink: String, csrfToken: String) {
    div("rule-banner") {
        span("rule-banner-text") {
            +when (suggestion) {
                is RuleSuggestion.Mcc -> copy.ruleSuggestionMcc(suggestion.mcc)
                is RuleSuggestion.Counterparty -> copy.ruleSuggestionCounterparty(suggestion.name, suggestion.count)
            }
        }
        div("rule-banner-actions") {
            a(href = "$backLink#txn-${txn.id}", classes = "btn btn-ghost btn-sm") { +copy.ruleNotNow }
            form(action = "/transactions/${txn.id}/apply-rule", method = FormMethod.post, classes = "inline") {
                csrfField(csrfToken)
                hiddenInput(name = "back") { value = backLink }
                button(type = ButtonType.submit, classes = "btn btn-primary btn-sm") { +copy.ruleApply }
            }
        }
    }
}

private fun FlowContent.emptyState(copy: Copy) {
    div("empty-state") {
        div("empty-state-title") { +copy.emptyTransactionsTitle }
        div("empty-state-body") { +copy.emptyTransactionsBody }
    }
}

// --- rule suggestion -------------------------------------------------------------------------

/** What the rule strip would apply, computed but never acted on until the explicit
 *  "Застосувати" click in [Route.transactionRoutes]'s /apply-rule handler. */
private sealed class RuleSuggestion {
    data class Mcc(val mcc: Int, val count: Int) : RuleSuggestion()
    data class Counterparty(val name: String, val count: Int) : RuleSuggestion()
}

/**
 * design-handoff.md §3.5: shown only once a row has a category *and* at least one sibling
 * — same counterparty, or same MCC for an MCC-derived description. Mirrors the branching
 * in [app.ingest.IngestService.setTransactionCategoryChoice] (mcc vs. conduit-with-
 * identifiable-recipient vs. everything else) purely to describe what applying the
 * suggestion would do — nothing here writes anything.
 */
private fun ruleSuggestionFor(txn: Txn, rules: CategoryRules, transactions: TransactionRepository): RuleSuggestion? {
    val mcc = txn.mcc ?: return null
    if (mcc in rules.conduitMccs) {
        val key = txn.counterpartyKey ?: return null
        val source = CounterpartySource.entries.firstOrNull { it.code == txn.counterpartySource } ?: return null
        // Spec §5 (mirrored from setTransactionCategoryChoice): only an EXACT source may
        // link silently — a `name:` key is a string somebody typed, not an account.
        if (source == CounterpartySource.NAME) return null
        val count = transactions.countByCounterparty(key)
        if (count < 2) return null
        val display = (counterpartyOf(txn.rawJson, txn.description)?.displayName ?: txn.description).ifBlank { key }
        return RuleSuggestion.Counterparty(display, count)
    }
    val count = transactions.countByMcc(mcc)
    if (count < 2) return null
    return RuleSuggestion.Mcc(mcc, count)
}
