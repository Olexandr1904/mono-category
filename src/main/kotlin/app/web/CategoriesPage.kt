package app.web

import app.budget.BudgetService
import app.budget.Category
import app.budget.CategoryRepository
import app.budget.CategorySpending
import app.budget.ConduitMccException
import app.budget.ConduitMccRepository
import app.budget.CounterpartyRepository
import app.budget.KYIV
import app.budget.MccConflictException
import app.budget.SpendStatus
import app.budget.currentMonthKey
import app.budget.formatMinor
import app.budget.formatMinorWhole
import app.budget.parseAmountToMinor
import app.db.SettingsRepository
import app.i18n.Copy
import app.i18n.copy
import app.ingest.AccountRepository
import app.ingest.IngestService
import io.ktor.server.application.*
import io.ktor.server.html.respondHtml
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.html.*
import java.time.Clock

fun parseMccList(input: String): Set<Int> =
    input.split(',', ' ', '\n', '\r', '\t')
        .mapNotNull { it.trim().takeIf(String::isNotEmpty)?.toIntOrNull() }
        .toSet()

/**
 * Keeps the monthly limit out of the emoji field. The two boxes sit next to each other —
 * one column apart on a desktop, stacked on a phone — and an amount typed into the wrong
 * one used to be stored verbatim, so [app.budget.Category.label] rendered "🍽25000
 * Ресторани та бари" in every Telegram button. Digits and spaces are never part of an
 * emoji, and dropping them turns the slip into a no-op instead of corrupted data.
 * (A keycap emoji such as 1️⃣ is the one casualty; it becomes an ordinary label instead.)
 * `V2__strip_limits_from_emoji.sql` applies the same rule to rows written before this.
 */
fun sanitizeEmoji(input: String): String = input.filterNot { it.isDigit() || it.isWhitespace() }

/**
 * Renders minor units for the editable limit field. `(minor / 100).toString()` used to
 * drop kopecks silently — 1 500 050 rendered as "15000" and a same-value save re-parsed it
 * as 1 500 000. Kopecks are appended with "." only when non-zero, which
 * [app.budget.parseAmountToMinor] already reads back (it accepts both "." and ",").
 */
fun renderAmountInput(minor: Long): String {
    val whole = minor / 100
    val kopecks = minor % 100
    return if (kopecks == 0L) whole.toString() else "$whole.${kopecks.toString().padStart(2, '0')}"
}

// The default a freshly "Додати категорію"'d row starts with (design-handoff.md §2: "a
// category named 'Нова категорія', emoji '•', limit 0, and opens its editor"). Category
// names and emoji are user data, never Copy — same reasoning as DEFAULT_CATEGORIES.kt's
// own hardcoded Ukrainian names.
private const val NEW_CATEGORY_NAME = "Нова категорія"
private const val NEW_CATEGORY_EMOJI = "•"
private const val NEW_CATEGORY_THRESHOLD_PCT = 80

fun Route.categoryRoutes(
    categories: CategoryRepository,
    ingest: IngestService,
    settings: SettingsRepository,
    conduit: ConduitMccRepository,
    counterparties: CounterpartyRepository,
    budget: BudgetService,
    accounts: AccountRepository,
    clock: Clock = Clock.system(KYIV),
    // Mirrors installAuth's flag: the CSRF cookie every form on this page relies on
    // travels under the same Secure rule as the session cookie.
    secureCookies: Boolean = true,
) {

    get("/categories") {
        // Категорії has no month stepper (design-handoff.md §2's header carries only the
        // month kicker and the two action buttons) — it always reflects the current
        // month's limit consumption, the one the Telegram alerts themselves are about.
        val month = currentMonthKey(clock)
        val summary = budget.monthSummary(month)
        // summary.categories walks categories.list(includeDisabled = true) internally
        // (BudgetService.monthSummary), so it is exactly this page's row list already, in
        // the same order — no second categories.list() call needed.
        val mccByCategory = summary.categories.associate { it.category.id to categories.mccOf(it.category.id) }
        val conduitCodes = conduit.list()
        val rules = counterparties.list()
        val categoryLabels = summary.categories.associate { it.category.id to it.category.label }
        val copy = settings.copy()
        val csrfToken = call.csrfToken(secureCookies)
        val openId = call.request.queryParameters["open"]?.toLongOrNull()
        call.respondHtml {
            page(
                copy = copy,
                title = copy.categoriesTitle,
                active = "categories",
                csrfToken = csrfToken,
                flash = call.request.queryParameters["flash"],
                error = call.request.queryParameters["error"],
                headerMeta = copy.headerMeta(accounts.trackableList().size),
            ) {
                div("stack") {
                    pageHeader(copy.monthLabel(month), copy.categoriesTitle) {
                        form(action = "/categories/seed-defaults", method = FormMethod.post, classes = "inline") {
                            csrfField(csrfToken)
                            button(type = ButtonType.submit, classes = "btn btn-outline") { +copy.fillWithDefaults }
                        }
                        form(action = "/categories/new", method = FormMethod.post, classes = "inline") {
                            csrfField(csrfToken)
                            button(type = ButtonType.submit, classes = "btn btn-primary") { +copy.addCategoryButton }
                        }
                    }

                    summaryStrip(copy, summary)

                    if (summary.categories.isEmpty()) {
                        emptyState(copy)
                    } else {
                        categoryList(copy, summary.categories, mccByCategory, openId, csrfToken)
                    }
                }

                conduitSection(copy, conduitCodes, csrfToken)
                counterpartySection(copy, rules, categoryLabels, csrfToken)
            }
        }
    }

    post("/categories/new") {
        val copy = settings.copy()
        val id = ingest.createCategory(NEW_CATEGORY_NAME, NEW_CATEGORY_EMOJI, 0L, NEW_CATEGORY_THRESHOLD_PCT)
        call.redirectWith("/categories?open=$id", copy.categoryCreated)
    }

    post("/categories/{id}") {
        val copy = settings.copy()
        val id = call.parameters["id"]?.toLongOrNull()
            ?: return@post call.redirectWith("/categories", copy.unknownCategory, isError = true)
        val existing = categories.byId(id)
            ?: return@post call.redirectWith("/categories", copy.unknownCategory, isError = true)
        val form = call.formParameters()
        // The editor stays open after a save (success or error) so the row reflects what
        // was just submitted instead of collapsing back to the read-only summary.
        val backTo = "/categories?open=$id"
        val limit = runCatching { parseAmountToMinor(form["limit"].orEmpty().ifBlank { "0" }) }
            .getOrElse { return@post call.redirectWith(backTo, copy.invalidLimit, isError = true) }

        // Both mutations run inside the ingest mutex (spec §3): a web edit is one of the
        // four sources that must be serialised against an in-flight ingest, so a webhook
        // redelivery cannot observe a half-applied edit — new limit with the old MCC
        // mapping, or vice versa — and burn a threshold claim against it.
        try {
            ingest.setCategoryMcc(id, parseMccList(form["mcc"].orEmpty()))
        } catch (e: MccConflictException) {
            return@post call.redirectWith(backTo, copy.mccConflict(e.mcc, e.ownerName), isError = true)
        } catch (e: ConduitMccException) {
            return@post call.redirectWith(backTo, copy.conduitMccRejected(e.mcc), isError = true)
        }

        // Finding 1 (CRITICAL) of the 2026-09 branch review: the threshold input and both
        // notify checkboxes render `disabled` whenever a category's limit is 0 (see
        // categoryEditorForm below), which means a browser never submits them at all.
        // Reading their absence as "false" while the limit is 0 would silently flip a
        // category's stored notify preferences to off the next time its form is saved for
        // an unrelated reason (a name typo, say) — the fields were never really touched,
        // they just weren't on the wire. So those three fields are trusted from the
        // submitted form only when the form was actually rendered with them enabled — i.e.
        // when the *existing* (pre-edit) limit was already positive. Gating on the newly
        // submitted `limit` instead would misread giving a limit-0 category its first-ever
        // limit (existing 0 -> new > 0): the fields were disabled and absent on that exact
        // submit, but `limit > 0` would read that absence as an explicit "turn
        // notifications off", silently disabling alerts the moment they start being able
        // to fire at all.
        val wasLimited = existing.monthlyLimitMinor > 0
        ingest.updateCategory(
            existing.copy(
                name = form["name"].orEmpty().trim().ifBlank { existing.name },
                emoji = sanitizeEmoji(form["emoji"].orEmpty()),
                monthlyLimitMinor = limit,
                thresholdPct = if (wasLimited) form["threshold"]?.toIntOrNull()?.coerceIn(1, 99) ?: existing.thresholdPct else existing.thresholdPct,
                notifyWarning = if (wasLimited) form["notifyWarning"] != null else existing.notifyWarning,
                notifyExceeded = if (wasLimited) form["notifyExceeded"] != null else existing.notifyExceeded,
                enabled = form["enabled"] != null,
                countsAsSpending = form["countsAsSpending"] != null,
            ),
        )
        call.redirectWith(backTo, copy.categorySaved)
    }

    post("/categories/seed-defaults") {
        val copy = settings.copy()
        val outcome = ingest.seedDefaultCategories(app.budget.DEFAULT_CATEGORIES)
        call.redirectWith(
            "/categories",
            copy.defaultsSeeded(
                categoriesCreated = outcome.categoriesCreated.size,
                mccAdded = outcome.mccAdded,
                categoriesSkipped = outcome.categoriesExisting.size,
                mccSkipped = outcome.mccSkipped.size,
                mccConduitSkipped = outcome.mccConduitSkipped.size,
            ),
        )
    }

    post("/categories/{id}/delete") {
        val copy = settings.copy()
        val id = call.parameters["id"]?.toLongOrNull()
            ?: return@post call.redirectWith("/categories", copy.unknownCategory, isError = true)
        ingest.deleteCategory(id)
        call.redirectWith("/categories", copy.categoryDeleted)
    }

    post("/categories/conduit") {
        val copy = settings.copy()
        val form = call.formParameters()
        val newMccs = parseMccList(form["mcc"].orEmpty())
        // An empty submit is easy to make by accident (a stray backspace, a form reset)
        // and un-conduits every code at once; "Saved" alone reads as a no-op, so the
        // owner needs an unambiguous signal that the whole set was just wiped.
        val wasNonEmpty = conduit.list().isNotEmpty()
        val moved = ingest.setConduitMccs(newMccs)
        val message = if (newMccs.isEmpty() && wasNonEmpty) copy.conduitMccCleared(moved) else copy.conduitMccSaved(moved)
        call.redirectWith("/categories", message)
    }

    post("/categories/counterparty/delete") {
        val copy = settings.copy()
        val key = call.formParameters()["key"].orEmpty()
        ingest.deleteCounterpartyRule(key)
        call.redirectWith("/categories", copy.counterpartyRuleRemoved)
    }
}

// --- summary strip ---------------------------------------------------------------------

private fun FlowContent.summaryStrip(copy: Copy, summary: app.budget.MonthSummary) {
    // Finding 3 (CRITICAL) of the 2026-09-04 review: filtered by countsAsSpending, same as
    // BudgetService.monthSummary's own totalSpentMinor (see its doc comment). Without this
    // filter a category marked "not counted" (money moved between the owner's own accounts)
    // still added its amount here, so this figure could render larger than the month total
    // it's captioned "з <month total>" against — a part bigger than its whole. Each
    // category's own row is untouched by this filter and still shows its real spend.
    val categorized = summary.categories.filter { it.category.countsAsSpending }.sumOf { it.spentMinor }
    val withLimit = summary.categories.count { it.category.monthlyLimitMinor > 0 }
    val overLimit = summary.categories.count { it.status == SpendStatus.EXCEEDED }
    div("stat-strip") {
        div("stat-cell") {
            div("stat-label") { +copy.categorizedSpendLabel }
            div("stat-figure-row") { span("stat-figure mono") { +formatMinor(categorized) } }
            div("stat-caption") { +copy.ofMonthTotal(formatMinor(summary.totalSpentMinor)) }
        }
        div("stat-cell") {
            div("stat-label") { +copy.withLimitLabel }
            div("stat-figure-row") { span("stat-figure mono") { +withLimit.toString() } }
            div("stat-caption") { +copy.ofCategoriesCount(summary.categories.size) }
        }
        div("stat-cell") {
            div("stat-label") { +copy.overLimitLabel }
            div("stat-figure-row") {
                // Handoff review: colour only carries meaning — a zero coloured danger-red
                // reads as "something is wrong" when nothing is. Coloured only once there is
                // an actual category over its limit.
                span("stat-figure mono" + if (overLimit > 0) " bad" else "") { +overLimit.toString() }
            }
            div("stat-caption") { +copy.categoriesCountWord(overLimit) }
        }
    }
}

private fun FlowContent.emptyState(copy: Copy) {
    div("empty-state") {
        div("empty-state-title") { +copy.noCategoriesYet }
        div("empty-state-body") { +copy.categoriesEmptyBody }
    }
}

// --- category list -----------------------------------------------------------------------

private fun FlowContent.categoryList(
    copy: Copy,
    rows: List<CategorySpending>,
    mccByCategory: Map<Long, Set<Int>>,
    openId: Long?,
    csrfToken: String,
) {
    div("cat-list") {
        div("cat-list-header") {
            div {}
            div { +copy.categoryColumnHeader }
            div { +copy.limitColumnHeader }
            div("cat-list-header-amount") { +copy.spentColumnHeader }
            div {}
        }
        rows.forEach { row ->
            categoryRow(copy, row, mccByCategory[row.category.id].orEmpty(), row.category.id == openId, csrfToken)
        }
    }
}

private fun FlowContent.categoryRow(
    copy: Copy,
    row: CategorySpending,
    mccs: Set<Int>,
    isOpen: Boolean,
    csrfToken: String,
) {
    val category = row.category
    val hasLimit = category.monthlyLimitMinor > 0
    details("cat-row") {
        if (isOpen) open = true
        summary("cat-row-summary") {
            div("cat-tile") { +category.emoji.ifBlank { category.name.take(1).uppercase() } }

            div("cat-name-block") {
                div("cat-name-line") {
                    span("cat-name") { +category.name }
                    if (!category.enabled) span("pill pill-outline") { +copy.disabledPill }
                    when {
                        row.status == SpendStatus.EXCEEDED -> span("pill pill-bad") { +copy.overLimitPill }
                        row.status == SpendStatus.WARNING -> span("pill pill-accent") { +"${row.pct}%" }
                    }
                }
                div("cat-meta mono") {
                    +if (mccs.isEmpty()) copy.noMccCodesHint else "${copy.mccAbbrev} ${mccs.sorted().joinToString(", ")}"
                }
            }

            div("cat-limit-col") {
                if (hasLimit) {
                    div("cat-limit-text mono") { +"${formatMinorWhole(category.monthlyLimitMinor)} · ${row.pct}%" }
                    div("limit-bar-track") {
                        span("limit-bar-fill ${limitBarClass(row.status)}") {
                            attributes["data-bar-width"] = row.pct.coerceIn(0, 100).toString()
                        }
                    }
                } else {
                    // A plain <span>, not a <button> — design-system.md: with <details> the
                    // whole summary already toggles the editor natively, so this needs no
                    // click handler (and no stopPropagation, unlike the prototype's version
                    // sitting inside a separately-clickable row).
                    span("btn btn-ghost btn-sm cat-limit-ghost") { +copy.setLimitButton }
                }
            }

            div("cat-amount-col") {
                span("cat-amount-spent mono") { +formatMinor(row.spentMinor) }
                if (hasLimit) {
                    div("cat-amount-hint mono") {
                        +if (row.spentMinor >= category.monthlyLimitMinor) {
                            copy.limitOverHint(formatMinorWhole(row.spentMinor - category.monthlyLimitMinor))
                        } else {
                            copy.limitRemainingHint(formatMinorWhole(category.monthlyLimitMinor - row.spentMinor))
                        }
                    }
                }
            }

            span("cat-chevron") {}
        }
        div("cat-editor") {
            categoryEditorForm(copy, category, mccs, csrfToken)
        }
    }
}

private fun limitBarClass(status: SpendStatus): String = when (status) {
    SpendStatus.NORMAL -> ""
    SpendStatus.WARNING -> "warning"
    SpendStatus.EXCEEDED -> "over"
}

private fun FlowContent.categoryEditorForm(copy: Copy, category: Category, mccs: Set<Int>, csrfToken: String) {
    // Item 9 (carried from the pre-redesign form): "0" as a limit silently means "off",
    // and the threshold field plus both notify checkboxes are rendered disabled/inert
    // whenever it is — see the write guard's own long comment in categoryRoutes above.
    val noLimit = category.monthlyLimitMinor <= 0L
    val deleteFormId = "cat-delete-${category.id}"
    form(action = "/categories/${category.id}", method = FormMethod.post, classes = "cat-editor-form") {
        csrfField(csrfToken)
        div("cat-editor-grid") {
            label(classes = "cat-field") {
                span("cat-field-label") { +copy.nameLabel }
                input(type = InputType.text, name = "name") { value = category.name; required = true }
            }
            label(classes = "cat-field") {
                span("cat-field-label") { +copy.emojiLabel }
                input(type = InputType.text, name = "emoji", classes = "cat-emoji-input") { value = category.emoji }
            }
            label(classes = "cat-field") {
                span("cat-field-label") { +copy.limitLabel }
                input(type = InputType.text, name = "limit", classes = "mono") {
                    value = renderAmountInput(category.monthlyLimitMinor)
                    placeholder = copy.limitPlaceholder
                }
            }
            label(classes = "cat-field") {
                span("cat-field-label") { +copy.thresholdLabel }
                input(type = InputType.number, name = "threshold", classes = "mono") {
                    value = category.thresholdPct.toString(); min = "1"; max = "99"
                    disabled = noLimit
                }
            }
        }
        label(classes = "cat-field cat-field-mcc") {
            span("cat-field-label") { +copy.mccLabel }
            input(type = InputType.text, name = "mcc", classes = "mono") {
                value = mccs.sorted().joinToString(", ")
                placeholder = copy.mccPlaceholder
            }
        }
        span("cat-mcc-hint") { +copy.mccFieldHint }

        div("cat-checkbox-row") {
            label(classes = "cat-checkbox") {
                input(type = InputType.checkBox, name = "enabled") { checked = category.enabled }
                +copy.enabledLabel
            }
            label(classes = "cat-checkbox") {
                input(type = InputType.checkBox, name = "countsAsSpending") { checked = category.countsAsSpending }
                +copy.countsAsSpendingLabel
            }
            span("cat-checkbox-divider") {}
            label(classes = "cat-checkbox") {
                input(type = InputType.checkBox, name = "notifyWarning") {
                    checked = category.notifyWarning
                    disabled = noLimit
                }
                +copy.notifyAtLabel(category.thresholdPct)
            }
            label(classes = "cat-checkbox") {
                input(type = InputType.checkBox, name = "notifyExceeded") {
                    checked = category.notifyExceeded
                    disabled = noLimit
                }
                +copy.notifyAt100Label
            }
        }

        div("cat-editor-footer") {
            div("cat-editor-hint") {
                +if (noLimit) copy.notifyFooterHintNoLimit else copy.notifyFooterHint(category.thresholdPct)
            }
            div("cat-editor-footer-actions") {
                // A separate <form> (HTML forbids nesting one inside another) submitted via
                // the button's own `form=` attribute, so it can sit visually next to
                // "Готово" in this same row even though it belongs to a different form —
                // see the sibling <form> right below this one.
                button(type = ButtonType.submit, classes = "btn btn-danger btn-sm") {
                    attributes["form"] = deleteFormId
                    attributes["data-confirm"] = copy.deleteConfirm(category.name)
                    +copy.delete
                }
                button(type = ButtonType.submit, classes = "btn btn-primary btn-sm") { +copy.doneButton }
            }
        }
    }
    form(action = "/categories/${category.id}/delete", method = FormMethod.post) {
        id = deleteFormId
        csrfField(csrfToken)
    }
}

// --- conduit codes + counterparty rules (unchanged by the redesign; see CLAUDE.md) --------

private fun FlowContent.conduitSection(copy: Copy, conduitCodes: Set<Int>, csrfToken: String) {
    section {
        h2 { +copy.conduitMccHeading }
        p(classes = "hint") { +copy.conduitMccHint }
        form(action = "/categories/conduit", method = FormMethod.post) {
            csrfField(csrfToken)
            label(classes = "field") {
                +copy.mccLabel
                input(type = InputType.text, name = "mcc") {
                    value = conduitCodes.sorted().joinToString(", ")
                    placeholder = copy.conduitMccPlaceholder
                }
            }
            button(type = ButtonType.submit) { +copy.save }
        }
    }
}

private fun FlowContent.counterpartySection(
    copy: Copy,
    rules: List<app.budget.CounterpartyRule>,
    categoryLabels: Map<Long, String>,
    csrfToken: String,
) {
    section {
        h2 { +copy.counterpartyRulesHeading }
        if (rules.isEmpty()) {
            p(classes = "hint") { +copy.counterpartyRulesEmpty }
        } else {
            ul {
                rules.forEach { rule ->
                    li {
                        +"${rule.displayName} → ${categoryLabels[rule.categoryId].orEmpty()}"
                        form(action = "/categories/counterparty/delete", method = FormMethod.post) {
                            csrfField(csrfToken)
                            hiddenInput(name = "key") { value = rule.key }
                            button(type = ButtonType.submit) { +copy.delete }
                        }
                    }
                }
            }
        }
    }
}
