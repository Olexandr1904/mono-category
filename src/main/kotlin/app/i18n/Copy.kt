package app.i18n

/**
 * Every user-facing string in the application, in one place. This is an interface, not a
 * map keyed by strings, on purpose: a translation nobody wrote yet is a compile error on
 * [UkCopy]/[EnCopy], not an empty label that ships to production.
 *
 * Two rules for callers:
 * - Category names and emoji are the user's own data. Never route them through here.
 * - Money (`app.budget.formatMinor`) and the `dd.MM` day format stay exactly as they are;
 *   only [monthLabel] renders a month key ("2026-08") as words.
 */
interface Copy {

    // --- chrome: nav, page frame, log out ---------------------------------------------

    val appName: String
    val navDashboard: String
    val navCategories: String
    val navTransactions: String
    val navSettings: String
    val logOut: String

    /** Nav bar's right-hand mono caption (design-handoff.md §0): the connected source,
     *  e.g. "Монобанк · 5 рахунків". Passed to [app.web.page]'s `headerMeta` parameter by
     *  every route so it renders on every screen, not just the one that happened to have an
     *  account count in hand. */
    fun headerMeta(accountCount: Int): String

    /** Browser tab title, e.g. "Dashboard — Budget". */
    fun pageTitle(pageName: String): String

    /** Renders a stored month key ("2026-08") as words, e.g. "Серпень 2026" / "August 2026". */
    fun monthLabel(monthKey: String): String

    // --- login page ---------------------------------------------------------------------

    val passwordPlaceholder: String
    val logIn: String
    val wrongPassword: String
    fun tooManyAttempts(waitSeconds: Long): String

    // --- dashboard (Огляд) ------------------------------------------------------------------

    val dashboardTitle: String

    /** Hero card kicker over the 40px month total (design-handoff.md §1) — "Витрачено"
     *  alone, not a full sentence: the amount renders separately, in its own mono span. */
    val heroSpentLabel: String
    val categoriesHeading: String
    val noCategoriesYet: String
    val createOne: String
    val uncategorizedLabel: String

    /** Категорії list row pill: a disabled category is still listed (its history still
     *  counts), just marked as not currently categorizing new spend. */
    val disabledPill: String

    /** Pill on the breakdown list's "Без категорії" row — marks it as not yet sorted, not as a category. */
    val unsortedPill: String

    /** A no-limit category's share of the month's total spend, e.g. "31% of month". */
    fun shareOfMonth(pct: Int): String

    /**
     * The share text for real but sub-1% spend — 11 000,00 ₴ and 0 ₴ used to both render
     * as "0% з місяця" with an identical zero-width bar. Shown whenever
     * [app.budget.isTinyShare] is true, in place of [shareOfMonth].
     */
    val shareOfMonthTiny: String

    /** Same condition as [shareOfMonthTiny], bare — no "з місяця" — for Огляд's breakdown
     *  list, whose percent column is a fixed 58px nowrap cell beside six bare percentages
     *  (design-handoff.md §1's `auto | 58px` grid); the full phrase overflows it. */
    val shareOfMonthTinyBare: String

    /** Pill on a dashboard card for a category with `countsAsSpending = false`: the
     *  amount is real and shown, it just does not feed the total or alerts — money that
     *  moved between the owner's own accounts. */
    val notCountedPill: String

    /**
     * Label for the breakdown list's remainder row: every category beyond the top six,
     * plus any uncategorized spend, folded together. Deliberately not a category name —
     * the row it labels is drawn in a neutral colour, never a palette hue, so it reads as
     * "everything else" rather than as a seventh category.
     */
    val donutOtherLabel: String

    /** Uncategorised banner line 1 (design-handoff.md §1): "<amount> без категорії —
     *  <share text>" — this fragment supplies the lowercase middle words; [uncategorizedLabel]
     *  itself is capitalized for use as a standalone label and does not fit mid-sentence. */
    val uncategorizedBannerSuffix: String

    /** Uncategorised banner line 2, verbatim. */
    val uncategorizedBannerHint: String

    /** Uncategorised banner's primary button — design-handoff.md's "Розібрати <n> операцій"
     *  in spirit; [UkCopy] sidesteps the one-transaction plural quirk the literal wording
     *  has ("Розібрати 1 операцій"), the same way [ruleSuggestionCounterparty] already does.
     *  The banner never renders for n = 0 (it only shows when there is real uncategorized
     *  spend). */
    fun resolveUncategorizedButton(n: Long): String

    /** Огляд's "Ліміти" section kicker. */
    val limitsHeading: String

    /** Ліміти row hint under the spent figure when under the limit. */
    fun limitRemainingHint(amount: String): String

    /** Ліміти row hint under the spent figure when over the limit. */
    fun limitOverHint(amount: String): String

    /** Ліміти block's empty state (the owner's current real state: every limit is 0). */
    val noLimitsTitle: String
    val noLimitsBody: String
    val setLimitsButton: String

    // --- categories page (Категорії) ----------------------------------------------------

    val categoriesTitle: String

    /** List header / hero column labels (the Категорії table head, design-handoff.md §2). */
    val categoryColumnHeader: String
    val limitColumnHeader: String
    val spentColumnHeader: String

    val addCategoryButton: String
    val categorizedSpendLabel: String
    fun ofMonthTotal(amount: String): String
    val withLimitLabel: String
    fun ofCategoriesCount(n: Int): String
    val overLimitLabel: String

    /** Bare hint word under the "Перевищено" summary figure — no "of N" qualifier, unlike
     *  the other two summary cells. Takes the count because the noun has to agree with the
     *  figure standing directly above it: the live page read "2 категорій" until the
     *  2026-09-22 walkthrough (finding 4). */
    fun categoriesCountWord(n: Int): String

    /** Row meta line when a category has no MCC codes at all. */
    val noMccCodesHint: String

    /** Dashed ghost affordance inside a no-limit row's summary — design-handoff.md §2:
     *  sits inside `<summary>` so clicking it opens the editor via the native
     *  `<details>` toggle, with no separate click handler needed. */
    val setLimitButton: String
    val overLimitPill: String
    val mccFieldHint: String
    fun notifyFooterHint(warnPct: Int): String
    val notifyFooterHintNoLimit: String
    val doneButton: String

    /** Empty category list (a fresh install, before "Стандартний набір" or "Додати категорію" is pressed). */
    val categoriesEmptyBody: String

    /** Editor's "Ліміт, ₴" placeholder — design-handoff.md §2's literal "0 — без ліміту". */
    val limitPlaceholder: String

    /**
     * Captions above the editor's text boxes (design-handoff.md §2's field grid). Not
     * decoration: the grid stacks them into one column on a phone, and unlabelled they are
     * guessable only by their current value — which is how a monthly limit ended up in the
     * emoji column and, from there, into every Telegram button.
     */
    val nameLabel: String
    val emojiLabel: String
    val limitLabel: String
    val thresholdLabel: String
    val mccLabel: String
    val save: String
    val delete: String
    val enabledLabel: String

    /**
     * Checkbox in the category editor: unchecked means this category's spend moved between
     * the owner's own accounts and should not feed the dashboard total or threshold alerts
     * — see [app.budget.Category.countsAsSpending]. Checked (the default) is the ordinary
     * case and needs no explaining; this label exists for the unchecked case, so it states
     * the effect plainly rather than naming the field.
     */
    val countsAsSpendingLabel: String

    /** "сповіщення при <pct>%" — the label must read the category's actual warn threshold,
     *  never a hardcoded 80 (a bug this exact wording once had). */
    fun notifyAtLabel(pct: Int): String
    val notifyAt100Label: String
    val mccPlaceholder: String
    fun deleteConfirm(name: String): String
    val invalidLimit: String
    val categoryCreated: String
    val categorySaved: String
    val categoryDeleted: String
    val unknownCategory: String
    fun mccConflict(mcc: Int, ownerName: String): String

    /** Button that seeds the starter categories and MCC codes; see [app.budget.DEFAULT_CATEGORIES]. */
    val fillWithDefaults: String

    /**
     * Flash after seeding: what was actually created, never a lie about what was skipped.
     * [mccSkipped] and [mccConduitSkipped] get their own clause each — a code owned by
     * another category and a conduit code are different situations, and folding a conduit
     * code into "already taken" sends the owner looking for a category that holds it.
     */
    fun defaultsSeeded(categoriesCreated: Int, mccAdded: Int, categoriesSkipped: Int, mccSkipped: Int, mccConduitSkipped: Int): String

    // --- conduit codes and recipient rules -----------------------------------------------

    val conduitMccHeading: String
    val conduitMccHint: String
    val conduitMccPlaceholder: String
    fun conduitMccSaved(movedCount: Int): String

    /**
     * The conduit field was submitted empty when it previously held codes. Plain
     * "Saved" reads as a no-op; an owner who fat-fingered a blank submit needs to see
     * that every code just lost its transit status, not infer it from a moved-count.
     */
    fun conduitMccCleared(movedCount: Int): String
    fun conduitMccRejected(mcc: Int): String
    val counterpartyRulesHeading: String
    val counterpartyRulesEmpty: String
    val counterpartyRuleRemoved: String

    // --- transactions page (Операції) ----------------------------------------------------

    val transactionsTitle: String

    /** Page-header kicker (design-handoff.md §3.1) — the one account this app tracks, not a
     *  computed value, so it is plain final copy rather than built from [accountHeader] etc. */
    val transactionsKicker: String

    // Summary strip (design-handoff.md §3.2) — same 3-cell component as Категорії.
    // Cell 1's own label reuses [totalSpent]'s "Витрачено" language nowhere; it is its own
    // kicker over a bare total, so it gets one here. Cell 2 reuses [uncategorizedLabel] as
    // its kicker and [shareOfMonth]/[shareOfMonthTiny] for its hint — the same "share of
    // month" computation the dashboard uses, never a second one.
    val statSpentThisMonth: String
    val statUnresolved: String
    fun ofTransactionsCount(n: Long): String

    val holdPill: String

    /** Pill on a row whose account is switched off in Settings: the exclusion from every
     *  total (dashboard, donut, /status, threshold alerts) is otherwise entirely invisible
     *  here, and a category's own list can silently contain a huge outlier the owner has no
     *  way to explain from this page alone (finding 4 of the 2026-09 branch review). Kept
     *  from the pre-redesign page — not in the handoff's row spec, but dropping it would
     *  silently regress a fixed bug the handoff never asked to undo. */
    val inactiveAccountPill: String

    /** "MCC" in the row meta line ("MCC 4829 · 03.09.2026") is text a person reads, so it
     *  goes through Copy even though the two languages happen to agree on it. */
    val mccAbbrev: String

    /** First, placeholder-styled option of an unassigned row's category `<select>` —
     *  design-handoff.md §3.4 ("Обрати категорію"), replacing the old select's own reuse of
     *  the dashboard's "Без категорії" bucket name for the same slot. */
    val chooseCategoryOption: String

    /**
     * Row-select flash — design-system.md's "persist immediately": choosing a category now
     * touches only the clicked row (see [app.ingest.IngestService.setTransactionCategory]),
     * never a binding, so this states a plain outcome rather than a blast radius.
     */
    fun categorySavedForRow(categoryName: String): String
    val categoryClearedForRow: String

    /** design-handoff.md §3.5: shown under a row once it has a category *and* at least one
     *  sibling — same counterparty, or same MCC for an MCC-derived description. */
    fun ruleSuggestionCounterparty(name: String, count: Int): String
    fun ruleSuggestionMcc(mcc: Int): String
    val ruleNotNow: String
    val ruleApply: String

    /** Transactions page: choosing a category for a row with an MCC binds the code and
     *  rewrites every row that shares it — the same path the Telegram button takes. States
     *  what actually happened: the merchant, the code, the category, and the blast radius.
     *  Reused by the rule strip's "Застосувати" (the only place on the web page that can
     *  still trigger a bind) and, unchanged, by the Telegram transfer-question flow. */
    fun mccBoundFromTransaction(merchant: String, mcc: Int, categoryName: String, count: Int): String

    /** The chosen row has no MCC, so nothing could be bound; only that row was assigned. */
    fun singleTransactionCategorized(categoryName: String): String

    /**
     * A transfer filed with a known recipient: the rule now covers every row sharing it,
     * same as [app.i18n.Copy.transferBound] on the Telegram side, but phrased for the
     * transaction the rule strip was actually attached to.
     */
    fun counterpartyBoundFromTransaction(recipient: String, categoryName: String, count: Int): String

    val unknownTransaction: String

    /** Shown instead of the list when a filter combination matches nothing — including the
     *  always-empty `category` + `uncategorized=1` pair. */
    val emptyTransactionsTitle: String
    val emptyTransactionsBody: String

    // --- transactions filters -------------------------------------------------------------

    /** aria-label only — design-handoff.md's filter bar shows no visible label text beside
     *  either select, but a screen reader still needs one. */
    val categoryHeader: String
    val orderFilterLabel: String
    val orderNewest: String
    val orderOldest: String

    /** Sort by size of the transaction — one option, not split largest/smallest
     *  (design-handoff.md §3.3 lists exactly three sort options). */
    val orderLargest: String
    val allCategoriesOption: String
    val uncategorizedOnlyLabel: String
    val clearFilters: String

    /**
     * Toggle pill beside the others: incoming money (positive amount_minor) is hidden from
     * the list by default, since it can never be categorized and would otherwise sit
     * permanently in the "uncategorized only" triage view. Turning this on reveals it again
     * — nothing is ever deleted, this only changes what is shown.
     */
    val showIncomingLabel: String

    /** "3 вересня, четвер" — a day-group header (design-handoff.md §3.4). Locale text (month
     *  and weekday names), so it lives here rather than beside the numeric [app.budget.formatDay]. */
    fun dayGroupHeader(date: java.time.LocalDate): String

    // --- settings page --------------------------------------------------------------------

    val settingsTitle: String

    /** Page-header kicker (design-handoff.md §4.1): "МОНОБАНК · TELEGRAM". */
    val settingsKicker: String

    // Status card (§4.2): a dot + headline + a wrapping row of pills, one per wired-up
    // piece. Every pill reads the exact boolean settingsRoutes already computes for its
    // own wiring — nothing here is a second source of truth for "is it set up".
    val statusAllConnected: String
    fun statusIncomplete(missing: List<String>): String
    val statusPillMonoToken: String
    val statusPillTelegramToken: String
    val statusPillTelegramChat: String
    val statusPillTelegramWebhook: String
    val statusPillMonoWebhook: String

    // --- accounts table (§4.3) --------------------------------------------------------

    val accountsHeading: String
    val accountHeader: String
    val typeHeader: String
    val balanceHeader: String
    val activeHeader: String

    /**
     * Finding 6 of the 2026-09 branch review: "Активний" used to mean only "pull future
     * statements from this account" — unchecking it changed nothing about numbers already
     * on the dashboard. Since the spending-truth fix it also means "this account's entire
     * history counts toward every total" (see CLAUDE.md's "Spending truth" section), and
     * unchecking it retroactively drops months of spending from the dashboard, the Огляд
     * breakdown and `/status` with nothing beyond the ordinary "Рахунки збережено" flash to
     * say so. Rendered in the table's footer band (design-handoff.md §4.3).
     */
    val accountActiveHint: String

    /** Shown by a confirm() dialog (via data-confirm-if-unchecked, the same mechanism the
     *  category delete button uses) only on a submission that actually turns an account
     *  off — never on one that only changes which accounts are on. */
    val accountDeactivateConfirm: String

    /** Footer figure under the accounts table. Sums the *included* accounts only, so the
     *  wording has to name that subset — a bare "Разом" under five rows of balances reads
     *  as a broken sum (2026-09-22 walkthrough, finding 5). */
    fun countedTotalLabel(total: String): String
    val accountsSaved: String
    val noAccountsYet: String

    // --- Синхронізація card (§4.4) -----------------------------------------------------

    val syncCardHeading: String
    val webhookActiveLabel: String
    val webhookInactiveLabel: String
    val syncTransactions: String
    fun syncTransactionsCooldown(waitSeconds: Long): String

    /** Ghost button. Registration is idempotent — this is the one button whether the
     *  webhook has never been registered or is being refreshed after Monobank drops it. */
    val reregisterMonobankWebhook: String
    val monobankWebhookRegistered: String
    val monobankRequestFailed: String
    val cannotDeterminePublicUrl: String
    val syncRateLimited: String

    /** Shown when the Sync button launches a detached sync. */
    val syncStarted: String
    fun lastSyncSummary(summary: String): String
    fun lastSyncFailed(reason: String): String

    /** Shown near the Sync button, before it is pressed: the app only ever syncs one month. */
    fun syncPeriodLabel(monthLabel: String): String

    /** The compact result line (design-handoff.md §4.4: "369 нових · 0 оновлених · 0 без
     *  змін · 5 рахунків") — counts only, no timestamp; the hint line under it already
     *  states which month. */
    fun syncSummary(inserted: Int, updated: Int, unchanged: Int, accounts: Int, errors: List<String>): String
    fun syncErrorRateLimited(retryAfterSeconds: Long): String
    val syncErrorTokenRejected: String
    fun syncErrorHttpStatus(status: Int): String
    fun syncErrorGeneric(name: String): String

    /** Shown in place of the result line before the first sync has ever run. */
    val neverSyncedYet: String

    // --- Telegram card (§4.4) ----------------------------------------------------------

    val telegramCardHeading: String
    val chatConnectedLabel: String
    val chatNotConnectedLabel: String
    val telegramCardHint: String

    /** Shown instead of [telegramCardHint] before a chat is paired and no live pairing
     *  code is on hand yet. */
    val telegramNotPairedHint: String
    val sendStartCommand: String

    /** Shown with the same code when a chat is already paired: the command is then a way to
     *  move the bot somewhere else — a group shared with the family, typically — and saying
     *  "send this to your bot" would not explain what pressing Reconnect just offered. */
    val sendStartToMoveChat: String
    val sendTestNotification: String
    val testNotificationSent: String
    val testNotificationText: String
    val noChatPaired: String
    val telegramRequestFailed: String
    val generatePairingCode: String

    /** Same button, once a chat is already paired — pressing "Generate" on an already-paired
     *  install used to hand back a code for a bot that had nothing to do with it (item 13).
     *  Reconnecting also (re)registers the Telegram webhook first — see settingsRoutes'
     *  `/settings/pairing-code` handler: a code Telegram can never deliver to us because the
     *  webhook was never registered is the exact silent-failure bug item 13 fixed. */
    val regeneratePairingCode: String
    fun sendStartToBot(code: String, minutes: Long): String

    // --- tokens (§4.5) -------------------------------------------------------------------

    val tokensHeading: String
    val tokensHelp: String
    val monobankTokenLabel: String
    val telegramTokenLabel: String
    val configured: String
    val monoTokenPlaceholder: String
    val telegramTokenPlaceholder: String
    val saveTokensButton: String
    val tokensSaved: String

    /** Shown when saving the Monobank token for the first time launches the initial sync. */
    val tokensSavedInitialSync: String

    // --- language (§4.6) -----------------------------------------------------------------

    val languageHeading: String
    val languageLabel: String
    val ukrainian: String
    val english: String
    val languageSaved: String

    // --- Telegram bot ---------------------------------------------------------------------

    val notPairedBotMessage: String
    val alreadyConnectedMessage: String
    val invalidPairingCode: String

    /** Sent on a successful pairing; the caller appends [helpText]. */
    val pairingSuccess: String

    /** Sent to the chat the bot just left, so a move is never a bot that silently went
     *  quiet — and so a pairing nobody meant to happen is visible in the chat it took
     *  the alerts away from. */
    val chatMovedAway: String

    /** Replaces an unanswered category question left behind in the chat the bot moved out
     *  of. Editing it also strips the buttons, which would otherwise sit there looking
     *  pressable while the question itself has been asked again in the new chat. */
    val promptMovedAway: String

    // --- /limit ---------------------------------------------------------------------------

    val limitPickCategory: String

    /** Button face. [limit] arrives already rendered by formatMinor — money never goes
     *  through Copy, and this string is what the family sees on the button. */
    fun limitButton(label: String, limit: String): String

    /** The one view in the chat that shows which categories can never warn about an
     *  overspend, because they have no limit to cross. */
    fun limitButtonNoLimit(label: String): String
    val limitCancelButton: String
    val limitCancelled: String
    val limitNoCategories: String

    /** Both carry the "answer with a reply" instruction, and must keep it: under privacy
     *  mode the bot never receives a message that is not a reply to its own, so an amount
     *  typed straight into the room is lost and this sentence is the only thing that
     *  explains why nothing happened. */
    fun limitAmountQuestion(label: String, current: String): String
    fun limitAmountQuestionNoLimit(label: String): String

    val limitAmountNotANumber: String
    val limitAmountNegative: String
    fun limitAmountTooLarge(max: String): String

    /** [who] is a display name and may be blank — Telegram need not tell us either a
     *  username or a first name — in which case the implementation says what changed and
     *  simply does not say by whom. */
    fun limitChanged(label: String, from: String, to: String, who: String): String
    fun limitCleared(label: String, who: String): String
    val limitCategoryGone: String

    /** Replaces the keyboard of an unfinished /limit dialog in the chat the bot moved out
     *  of. The edit also strips the buttons, which would otherwise stay pressable and
     *  produce callbacks from a chat that is no longer the paired one — those are dropped
     *  without an answer, leaving the spinner turning forever. */
    val limitDialogMovedAway: String

    // --- /left ----------------------------------------------------------------------------

    fun leftHeader(monthLabel: String): String
    fun leftLine(label: String, left: String, limit: String): String
    fun leftLineOver(label: String, over: String, limit: String): String

    /** The categories with no limit at all, counted rather than listed — listing them
     *  would bury the ones that can actually run out. */
    fun leftNoLimitTail(count: Int): String
    val leftNothingWithLimits: String

    /** Refused rather than silently read as the current month: safeMonthKey's fallback
     *  would answer a typo with a full report for a month nobody asked about. */
    fun badMonthArgument(example: String): String

    /** What Telegram shows in the chat's input bar. Re-registered when the language
     *  changes, or the menu keeps the language it was first registered in while every
     *  reply switches — the bot disagreeing with itself in the same chat. */
    val botCommands: List<app.notify.BotCommand>

    val helpText: String
    val staleCallback: String
    fun statusHeader(monthLabel: String, total: String): String
    fun statusLine(label: String, spent: String, limit: String, pct: Int, mark: String): String
    fun statusLineNoLimit(label: String, spent: String): String
    val noCategoriesYetStatus: String

    /** The recap sent on the 1st: [monthLabel] is the month just closed. */
    fun monthlyReportHeader(monthLabel: String, total: String): String
    /** [arrow] is ↑ or ↓, [diff] an absolute amount, [signedPct] carries its own sign. */
    fun monthlyReportDelta(arrow: String, diff: String, signedPct: String): String
    /** Heads the recap's closing list, one overspent category per line beneath it. */
    val monthlyReportExceededHeader: String
    fun budgetExceeded(category: String, limit: String, spent: String, over: String): String
    fun budgetWarning(pct: Int, category: String, limit: String, spent: String, left: String): String
    fun unknownMccQuestion(description: String, amount: String, mcc: Int): String
    val skipButton: String
    val alreadyHandledCallback: String
    fun mccSkipped(mcc: Int): String
    fun mccBound(mcc: Int, categoryName: String): String
    fun mccConflictTelegram(mcc: Int, ownerName: String): String

    /** Appended below [unknownMccQuestion]: states what answering actually commits to. */
    val mccScopeHint: String

    /** Appended below a transfer question: the opposite scope from [mccScopeHint] — a
     *  transfer's code (4829) means nothing on its own, so the answer binds the recipient,
     *  not the code. */
    val transferScopeHint: String
    fun transferQuestion(amount: String, recipient: String): String

    /** How many earlier transfers already went to this recipient — shown only when > 0. */
    fun transferQuestionSeenBefore(count: Int): String

    /** A transfer filed with a known recipient: the rule now covers every row sharing it. */
    fun transferBound(recipient: String, categoryName: String, movedCount: Int): String

    /** A transfer filed with no identifiable recipient: only this row changed. */
    fun transferSingleRow(categoryName: String): String

    /** A transfer question answered after the row was already filed some other way. */
    fun transferAlreadyCategorized(categoryName: String): String
    val transferSkipped: String

    /**
     * The conduit set can be edited while a transfer question sits open. If the code was
     * removed from it before the button was pressed, [app.ingest.IngestService.setTransactionCategoryChoice]
     * binds the MCC instead of the recipient — a blast radius of possibly hundreds of rows
     * that the owner must be told about, not the opposite of what happened.
     */
    fun transferMccConflict(mcc: Int, ownerName: String): String

    /**
     * [app.ingest.CategoryChoiceOutcome.Cleared] is structurally unreachable from the
     * transfer button — it only ever passes a real category id — but the sealed class is
     * matched exhaustively on purpose (CLAUDE.md: "so no case is silently dropped"), so this
     * exists as the true, minimal thing to say if that ever changes.
     */
    val transferNothingChanged: String

    val newCategoryButton: String

    /** Sent as a new force_reply message — see [app.notify.MccPromptService.handleNameReply]. */
    val newCategoryPrompt: String
    val categoryNameInvalid: String
    fun categoryAlreadyExists(label: String): String
}
