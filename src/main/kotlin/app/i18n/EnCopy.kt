package app.i18n

/**
 * English copy, in the same voice as [UkCopy] — not a formal translation of it. Short,
 * plain, says what happened and what to do.
 */
object EnCopy : Copy {

    override val appName = "Budget"
    override val navDashboard = "Dashboard"
    override val navCategories = "Categories"
    override val navTransactions = "Transactions"
    override val navSettings = "Settings"
    override val logOut = "Log out"
    override fun headerMeta(accountCount: Int) = "Monobank · $accountCount account" + if (accountCount == 1) "" else "s"

    override fun pageTitle(pageName: String) = "$pageName — $appName"

    override fun monthLabel(monthKey: String): String {
        val year = monthKey.substring(0, 4)
        val month = monthKey.substring(5, 7).toInt()
        return "${EN_MONTHS[month - 1]} $year"
    }

    override val passwordPlaceholder = "Password"
    override val logIn = "Log in"
    override val wrongPassword = "Wrong password. Try again"
    override fun tooManyAttempts(waitSeconds: Long) = "Too many attempts. Try again in ${waitSeconds}s"

    override val dashboardTitle = "Dashboard"
    override val heroSpentLabel = "Spent"
    override val categoriesHeading = "Categories"
    override val noCategoriesYet = "No categories yet."
    override val createOne = "Create one"
    override val uncategorizedLabel = "Uncategorized"
    override val disabledPill = "off"
    override val unsortedPill = "not sorted yet"
    override fun shareOfMonth(pct: Int) = "$pct% of month"
    override val shareOfMonthTiny = "<1% of month"
    override val shareOfMonthTinyBare = "<1%"
    override val notCountedPill = "not counted as spending"
    override val donutOtherLabel = "Other"
    override val uncategorizedBannerSuffix = "uncategorized"
    override val uncategorizedBannerHint = "Until these are sorted, limits and stats are incomplete."
    override fun resolveUncategorizedButton(n: Long) = "Sort $n transaction" + if (n == 1L) "" else "s"
    override val limitsHeading = "Limits"
    override fun limitRemainingHint(amount: String) = "$amount left"
    override fun limitOverHint(amount: String) = "+$amount over"
    override val noLimitsTitle = "No limits set"
    override val noLimitsBody = "With no limit, Telegram never sends an alert."
    override val setLimitsButton = "Set limits"

    override val categoriesTitle = "Categories"
    override val categoryColumnHeader = "Category"
    override val limitColumnHeader = "Monthly limit"
    override val spentColumnHeader = "Spent"
    override val addCategoryButton = "Add category"
    override val categorizedSpendLabel = "Sorted into categories"
    override fun ofMonthTotal(amount: String) = "of $amount"
    override val withLimitLabel = "With a limit"
    override fun ofCategoriesCount(n: Int) = "of $n ${categoriesCountWord(n)}"
    override val overLimitLabel = "Over limit"
    override fun categoriesCountWord(n: Int) = if (n == 1) "category" else "categories"
    override val noMccCodesHint = "no MCC codes · manual only"
    override val setLimitButton = "Set a limit"
    override val overLimitPill = "over limit"
    override val mccFieldHint = "Transactions with these codes will land in this category automatically."
    override fun notifyFooterHint(warnPct: Int) = "Telegram alerts fire at $warnPct% and 100% of the limit."
    override val notifyFooterHintNoLimit = "Limit is 0 — alerts are off for this category."
    override val doneButton = "Done"
    override val categoriesEmptyBody = "Press \"Add category\" above."
    override val limitPlaceholder = "0 — no limit"

    override val nameLabel = "Name"
    override val emojiLabel = "Emoji"
    override val limitLabel = "Limit, ₴"
    override val thresholdLabel = "Warn at, %"
    override val mccLabel = "MCC codes"
    override val save = "Save"
    override val delete = "Delete"
    override val enabledLabel = "enabled"
    override val countsAsSpendingLabel = "counts as spending"
    override fun notifyAtLabel(pct: Int) = "notify at $pct%"
    override val notifyAt100Label = "notify at 100%"
    override val mccPlaceholder = "5411, 5422, 5499"
    override fun deleteConfirm(name: String) = "Delete $name? Its transactions become uncategorized."
    override val invalidLimit = "That doesn't look like an amount. Check the limit"
    override val categoryCreated = "Category created"
    override val categorySaved = "Saved"
    override val categoryDeleted = "Category deleted"
    override val unknownCategory = "No such category"
    override fun mccConflict(mcc: Int, ownerName: String) = "MCC $mcc already belongs to \"$ownerName\""
    override val fillWithDefaults = "Default set"
    override fun defaultsSeeded(
        categoriesCreated: Int,
        mccAdded: Int,
        categoriesSkipped: Int,
        mccSkipped: Int,
        mccConduitSkipped: Int,
    ): String {
        val base = "Added $categoriesCreated categories, $mccAdded MCC codes"
        val skipped = mutableListOf<String>()
        if (categoriesSkipped > 0) skipped += "$categoriesSkipped categories already existed"
        if (mccSkipped > 0) skipped += "$mccSkipped codes already taken"
        if (mccConduitSkipped > 0) skipped += "$mccConduitSkipped codes marked conduit"
        return if (skipped.isEmpty()) base else "$base. Skipped — ${skipped.joinToString(", ")}"
    }

    override val conduitMccHeading = "Conduit codes"
    override val conduitMccHint =
        "Codes that say nothing about what the money was for — a transfer to someone else's card, " +
            "for instance. Such a code belongs to no category; the bot asks about each transaction instead."
    override val conduitMccPlaceholder = "4829"
    override fun conduitMccSaved(movedCount: Int) = "Saved. Moved $movedCount transactions."
    override fun conduitMccCleared(movedCount: Int) =
        "Conduit code list cleared — no code is a conduit anymore. Moved $movedCount transactions."
    override fun conduitMccRejected(mcc: Int) = "Code $mcc is marked as a conduit and cannot belong to a category."
    override val counterpartyRulesHeading = "Recipient rules"
    override val counterpartyRulesEmpty =
        "Nothing yet. Rules appear when you pick a category for a transfer — in the bot or from the transactions list."
    override val counterpartyRuleRemoved = "Rule removed."

    override val transactionsTitle = "Transactions"
    override val transactionsKicker = "ALL ACCOUNTS · UAH"
    override val statSpentThisMonth = "Spent this month"
    override val statUnresolved = "Unresolved"
    override fun ofTransactionsCount(n: Long) = "of $n transaction" + if (n == 1L) "" else "s"
    override val holdPill = "hold"
    override val inactiveAccountPill = "account off — not counted"
    override val mccAbbrev = "MCC"
    override val chooseCategoryOption = "Choose a category"
    override fun categorySavedForRow(categoryName: String) = "Category changed to \"$categoryName\""
    override val categoryClearedForRow = "Category cleared for this transaction"

    override fun ruleSuggestionCounterparty(name: String, count: Int) =
        "Create a rule for \"$name\" — transactions: $count"
    override fun ruleSuggestionMcc(mcc: Int) = "Apply to every transaction with MCC $mcc"
    override val ruleNotNow = "Not now"
    override val ruleApply = "Apply"

    override fun mccBoundFromTransaction(merchant: String, mcc: Int, categoryName: String, count: Int) =
        "\"$merchant\" (MCC $mcc) → $categoryName. Updated $count transactions"
    override fun singleTransactionCategorized(categoryName: String) =
        "This transaction has no MCC, so nothing could be bound — only this row is now \"$categoryName\""
    override fun counterpartyBoundFromTransaction(recipient: String, categoryName: String, count: Int) =
        "$recipient → $categoryName. Moved $count transactions."
    override val unknownTransaction = "Transaction not found"

    override val emptyTransactionsTitle = "Nothing found"
    override val emptyTransactionsBody = "No transactions match these filters. Try another month or clear the filters."

    override val categoryHeader = "Category"
    override val orderFilterLabel = "Sort by"
    override val orderNewest = "Newest first"
    override val orderOldest = "Oldest first"
    override val orderLargest = "By amount"
    override val allCategoriesOption = "All categories"
    override val uncategorizedOnlyLabel = "Uncategorized only"
    override val clearFilters = "Clear"
    override val showIncomingLabel = "Show incoming"

    override fun dayGroupHeader(date: java.time.LocalDate): String =
        "${EN_MONTHS[date.monthValue - 1]} ${date.dayOfMonth}, ${EN_WEEKDAYS[date.dayOfWeek.value - 1]}"

    override val settingsTitle = "Settings"
    override val settingsKicker = "Monobank · Telegram"

    override val statusAllConnected = "All connected"
    override fun statusIncomplete(missing: List<String>) = "Still missing: ${missing.joinToString(", ")}"
    override val statusPillMonoToken = "Monobank token"
    override val statusPillTelegramToken = "Telegram bot token"
    override val statusPillTelegramChat = "Telegram chat"
    override val statusPillTelegramWebhook = "Telegram webhook"
    override val statusPillMonoWebhook = "Monobank webhook"

    override val accountsHeading = "Monobank accounts"
    override val accountHeader = "Account"
    override val typeHeader = "Type"
    override val balanceHeader = "Balance"
    override val activeHeader = "Included"
    override val accountActiveHint =
        "Unchecking an account drops it from spending entirely — past months too, not just future syncs."
    override val accountDeactivateConfirm =
        "Turn this account off? It will remove its history from every dashboard, breakdown and /status total — not just future syncs."
    override fun countedTotalLabel(total: String) = "Included total $total"
    override val accountsSaved = "Accounts saved"
    override val noAccountsYet = "No accounts yet — press Sync."

    override val syncCardHeading = "Sync"
    override val webhookActiveLabel = "webhook active"
    override val webhookInactiveLabel = "webhook inactive"
    override val syncTransactions = "Sync transactions"
    override fun syncTransactionsCooldown(waitSeconds: Long) = "Sync transactions (wait ${waitSeconds}s)"
    override val reregisterMonobankWebhook = "Re-register webhook"
    override val monobankWebhookRegistered = "Monobank webhook registered"
    override val monobankRequestFailed = "Monobank request failed, check the token and try again"
    override val cannotDeterminePublicUrl = "Cannot determine the public URL"
    override val syncRateLimited = "Monobank allows one sync per minute"
    override val syncStarted =
        "Sync started. If the month's a busy one, Mono only hands over 500 transactions a minute, so it might take a bit."
    override fun lastSyncSummary(summary: String) = "Last sync: $summary"
    override fun lastSyncFailed(reason: String) = "Last sync failed: $reason"
    override fun syncPeriodLabel(monthLabel: String) = "Sync covers $monthLabel only — that's how the app works."
    override fun syncSummary(
        inserted: Int,
        updated: Int,
        unchanged: Int,
        accounts: Int,
        errors: List<String>,
    ): String {
        val base = "$inserted new · $updated updated · $unchanged unchanged · $accounts accounts"
        return if (errors.isEmpty()) base else "$base · Errors (${errors.size}): ${errors.joinToString("; ")}"
    }
    override fun syncErrorRateLimited(retryAfterSeconds: Long) = "rate limited, retry in ${retryAfterSeconds}s"
    override val syncErrorTokenRejected = "token rejected"
    override fun syncErrorHttpStatus(status: Int) = "Monobank returned HTTP $status"
    override fun syncErrorGeneric(name: String) = "error ($name)"
    override val neverSyncedYet = "Not synced yet."

    override val telegramCardHeading = "Telegram"
    override val chatConnectedLabel = "chat connected"
    override val chatNotConnectedLabel = "chat not connected"
    override val telegramCardHint = "Limit alerts land in the connected chat."
    override val telegramNotPairedHint = "Not connected yet. Press Reconnect to get a code."
    override val sendStartCommand = "Send this to your bot:"
    override val sendStartToMoveChat =
        "Send this in the chat or group the alerts should move to — a group shared with the family, say:"
    override val sendTestNotification = "Send test notification"
    override val testNotificationSent = "Test notification sent"
    override val testNotificationText = "✅ Test notification from your budget tracker."
    override val noChatPaired = "No chat is paired yet"
    override val telegramRequestFailed = "Telegram request failed, check the token and try again"
    override val generatePairingCode = "Generate pairing code"
    override val regeneratePairingCode = "Reconnect"
    override fun sendStartToBot(code: String, minutes: Long) = "Send /start $code to your bot within $minutes minutes"

    override val tokensHeading = "Tokens"
    override val tokensHelp = "Leave a field empty to keep the stored value. Tokens are encrypted at rest."
    override val monobankTokenLabel = "Monobank token"
    override val telegramTokenLabel = "Telegram bot token"
    override val configured = "configured"
    override val monoTokenPlaceholder = "u..."
    override val telegramTokenPlaceholder = "123456:ABC..."
    override val saveTokensButton = "Save tokens"
    override val tokensSaved = "Tokens saved"
    override val tokensSavedInitialSync =
        "Tokens saved. First sync is under way — a busy month can take a few minutes, since Mono only " +
            "releases 500 transactions a minute."

    override val languageHeading = "Language"
    override val languageLabel = "Interface language"
    override val ukrainian = "Українська"
    override val english = "English"
    override val languageSaved = "Language updated"

    override val notPairedBotMessage =
        "This bot isn't connected to anything yet. Open Settings in the web app and send /start with the code from there."
    override val alreadyConnectedMessage = "Already connected. Send /status for the current numbers."
    override val invalidPairingCode = "That code's wrong or expired. Grab a fresh one from Settings."
    override val pairingSuccess = "✅ Chat connected. You'll get budget alerts here from now on.\n\n"
    override val chatMovedAway = "ℹ️ The bot is now connected to another chat. Budget alerts go there from now on."
    override val promptMovedAway = "↪️ This question moved to the chat that now gets the alerts."
    override val limitPickCategory = "Which limit are we changing?"
    override fun limitButton(label: String, limit: String) = "$label ($limit)"
    override fun limitButtonNoLimit(label: String) = "$label (no limit)"
    override val limitCancelButton = "✖️ Cancel"
    override val limitCancelled = "Cancelled."
    override val limitNoCategories = "There are no categories yet — create some on the Categories page."
    override fun limitAmountQuestion(label: String, current: String) =
        "$label — currently $current.\nReply to this message with the new amount. 0 removes the limit."
    override fun limitAmountQuestionNoLimit(label: String) =
        "$label — no limit set.\nReply to this message with an amount. 0 leaves it unlimited."
    override val limitAmountNotANumber =
        "That is not an amount. Reply to this message with a number — 8000 or 8500.50, say. 0 removes the limit."
    override val limitAmountNegative =
        "A limit cannot be negative. Reply to this message with a positive number, or 0 to remove the limit."
    override fun limitAmountTooLarge(max: String) =
        "Too large. The highest limit is $max. Reply to this message with a smaller amount."
    override fun limitChanged(label: String, from: String, to: String, who: String) =
        "$label: $from → $to" + if (who.isBlank()) "" else " ($who)"
    override fun limitCleared(label: String, who: String) =
        "$label: limit removed" + if (who.isBlank()) "" else " ($who)"
    override val limitCategoryGone = "That category no longer exists."
    override val limitDialogMovedAway = "↪️ Limit change cancelled — the bot now posts to another chat."
    override fun leftHeader(monthLabel: String) = "Left, $monthLabel:"
    override fun leftLine(label: String, left: String, limit: String) = "$label — $left of $limit"
    override fun leftLineOver(label: String, over: String, limit: String) =
        "⚠️ $label — $over over (limit $limit)"
    override fun leftNoLimitTail(count: Int) = "No limit: $count ${if (count == 1) "category" else "categories"}"
    override val leftNothingWithLimits = "No category has a limit yet."
    override fun badMonthArgument(example: String) = "I did not understand that month. Format: /status $example"
    override val botCommands = listOf(
        app.notify.BotCommand("/status", "Spending for a month"),
        app.notify.BotCommand("/left", "How much room is left per category"),
        app.notify.BotCommand("/limit", "Change a monthly limit"),
        app.notify.BotCommand("/help", "List the commands"),
    )
    override val helpText = "Commands:\n" +
        "/status [YYYY-MM] — spending for a month\n" +
        "/left — how much room is left per category\n" +
        "/limit — change a monthly limit\n" +
        "/help — this message"
    override val staleCallback = "This message is stale — wait for the next one."
    override fun statusHeader(monthLabel: String, total: String) = "$monthLabel\nTotal spent: $total"
    override fun statusLine(label: String, spent: String, limit: String, pct: Int, mark: String) =
        "$label: $spent / $limit  $pct%$mark"
    override fun statusLineNoLimit(label: String, spent: String) = "$label: $spent"
    override val noCategoriesYetStatus = "No categories yet."
    override fun budgetExceeded(category: String, limit: String, spent: String, over: String) =
        "⚠️ You've gone over on \"$category\".\n\nLimit: $limit\nSpent: $spent\nOver by: $over"
    override fun budgetWarning(pct: Int, category: String, limit: String, spent: String, left: String) =
        "🔔 \"$category\" is at $pct% of its limit.\n\nLimit: $limit\nSpent: $spent\nLeft: $left"
    override fun unknownMccQuestion(description: String, amount: String, mcc: Int) =
        "🤔 Unknown MCC $mcc\n\n$description\n$amount\n\n" +
            "Which category should this go to? Your answer applies to every future transaction with this MCC, not just this one."
    override val skipButton = "Skip"
    override val alreadyHandledCallback = "Already handled"
    override fun mccSkipped(mcc: Int) = "MCC $mcc — skipped"
    override fun mccBound(mcc: Int, categoryName: String) = "MCC $mcc → $categoryName"
    override fun mccConflictTelegram(mcc: Int, ownerName: String) = "MCC $mcc already belongs to \"$ownerName\""
    override val mccScopeHint = "This applies to every future purchase with this code."
    override val transferScopeHint = "This applies to this recipient only."
    override fun transferQuestion(amount: String, recipient: String) = "Transfer $amount · $recipient — what is it?"
    override fun transferQuestionSeenBefore(count: Int) = "$count earlier transfers went to the same recipient."
    override fun transferBound(recipient: String, categoryName: String, movedCount: Int) =
        "$recipient → $categoryName. Moved $movedCount transactions."
    override fun transferSingleRow(categoryName: String) =
        "Filed under $categoryName. The recipient could not be identified, so no rule was created."
    override fun transferAlreadyCategorized(categoryName: String) = "Already filed under $categoryName."
    override val transferSkipped = "Skipped."
    override fun transferMccConflict(mcc: Int, ownerName: String) =
        "MCC $mcc already belongs to \"$ownerName\". Nothing changed."
    override val transferNothingChanged = "Nothing changed."
    override val newCategoryButton = "➕ New category"
    override val newCategoryPrompt = "Type a name for the new category"
    override val categoryNameInvalid = "An empty name will not do. Try again."
    override fun categoryAlreadyExists(label: String) = "Category $label already exists — using it."
}

private val EN_MONTHS = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

/** [java.time.DayOfWeek.getValue] is 1=Monday..7=Sunday — same order here. */
private val EN_WEEKDAYS = listOf(
    "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
)
