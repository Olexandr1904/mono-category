package app.i18n

/**
 * Ukrainian copy, informal "ти". This is how Monobank's own app talks: short sentences,
 * says what happened and what to do next, no apologising, no bureaucratic voice. Emoji
 * sparingly — at most one, and only where it earns its place.
 */
object UkCopy : Copy {

    override val appName = "Бюджет"
    override val navDashboard = "Огляд"
    override val navCategories = "Категорії"
    override val navTransactions = "Операції"
    override val navSettings = "Налаштування"
    override val logOut = "Вийти"
    override fun headerMeta(accountCount: Int) =
        "Монобанк · $accountCount ${ukPlural(accountCount, "рахунок", "рахунки", "рахунків")}"

    override fun pageTitle(pageName: String) = "$pageName — $appName"

    override fun monthLabel(monthKey: String): String {
        val year = monthKey.substring(0, 4)
        val month = monthKey.substring(5, 7).toInt()
        return "${UK_MONTHS[month - 1]} $year"
    }

    override val passwordPlaceholder = "Пароль"
    override val logIn = "Увійти"
    override val wrongPassword = "Пароль не той. Спробуй ще раз"
    override fun tooManyAttempts(waitSeconds: Long) = "Забагато спроб. Спробуй через ${waitSeconds} с"

    override val dashboardTitle = "Огляд"
    override val heroSpentLabel = "Витрачено"
    override val categoriesHeading = "Категорії"
    override val noCategoriesYet = "Категорій ще нема."
    override val createOne = "Створи першу"
    override val uncategorizedLabel = "Без категорії"
    override val disabledPill = "вимкнена"
    override val unsortedPill = "ще не розібрано"
    override fun shareOfMonth(pct: Int) = "$pct% з місяця"
    override val shareOfMonthTiny = "<1% з місяця"
    override val shareOfMonthTinyBare = "<1%"
    override val notCountedPill = "не рахується як витрати"
    override val donutOtherLabel = "Інше"
    override val uncategorizedBannerSuffix = "без категорії"
    override val uncategorizedBannerHint = "Поки ці операції не розібрані, ліміти й статистика неповні."
    // "Розібрати N операцій" reads correctly for 5 but not for 1 ("Розібрати 1 операцій") —
    // the same three-way plural problem ruleSuggestionCounterparty already sidesteps with
    // "операцій: N" two lines away from here in the interface; applied the same way here.
    override fun resolveUncategorizedButton(n: Long) = "Розібрати — операцій: $n"
    override val limitsHeading = "Ліміти"
    override fun limitRemainingHint(amount: String) = "залишилось $amount"
    override fun limitOverHint(amount: String) = "+$amount понад"
    override val noLimitsTitle = "Ліміти не встановлено"
    override val noLimitsBody = "Без ліміту сповіщення в Telegram не надсилаються."
    override val setLimitsButton = "Задати ліміти"

    override val categoriesTitle = "Категорії"
    override val categoryColumnHeader = "Категорія"
    override val limitColumnHeader = "Ліміт на місяць"
    override val spentColumnHeader = "Витрачено"
    override val addCategoryButton = "Додати категорію"
    override val categorizedSpendLabel = "Розібрано за категоріями"
    override fun ofMonthTotal(amount: String) = "з $amount"
    override val withLimitLabel = "З лімітом"
    // "з" governs the genitive: singular for 1, 21, 101; plural for everything else.
    override fun ofCategoriesCount(n: Int) = "з $n ${ukPlural(n, "категорії", "категорій", "категорій")}"
    override val overLimitLabel = "Перевищено"
    override fun categoriesCountWord(n: Int) = ukPlural(n, "категорія", "категорії", "категорій")
    override val noMccCodesHint = "без кодів MCC · тільки вручну"
    override val setLimitButton = "Встановити ліміт"
    override val overLimitPill = "перевищено"
    override val mccFieldHint = "Операції з цими кодами потраплятимуть у категорію автоматично."
    override fun notifyFooterHint(warnPct: Int) = "Сповіщення в Telegram при $warnPct% і 100% ліміту."
    override val notifyFooterHintNoLimit = "Ліміт 0 — сповіщення для цієї категорії вимкнені."
    override val doneButton = "Готово"
    override val categoriesEmptyBody = "Натисни «Додати категорію» вгорі."
    override val limitPlaceholder = "0 — без ліміту"

    override val nameLabel = "Назва"
    override val emojiLabel = "Емодзі"
    override val limitLabel = "Ліміт, ₴"
    override val thresholdLabel = "Попереджати на, %"
    override val mccLabel = "Коди MCC"
    override val save = "Зберегти"
    override val delete = "Видалити"
    override val enabledLabel = "увімкнена"
    override val countsAsSpendingLabel = "рахувати як витрати"
    override fun notifyAtLabel(pct: Int) = "сповіщення при $pct%"
    override val notifyAt100Label = "сповіщення при 100%"
    override val mccPlaceholder = "5411, 5422, 5499"
    override fun deleteConfirm(name: String) = "Видалити «$name»? Її операції стануть без категорії."
    override val invalidLimit = "Не схоже на суму. Перевір ліміт"
    override val categoryCreated = "Категорію створено"
    override val categorySaved = "Збережено"
    override val categoryDeleted = "Категорію видалено"
    override val unknownCategory = "Такої категорії нема"
    override fun mccConflict(mcc: Int, ownerName: String) = "MCC $mcc вже належить категорії «$ownerName»"
    override val fillWithDefaults = "Стандартний набір"
    override fun defaultsSeeded(
        categoriesCreated: Int,
        mccAdded: Int,
        categoriesSkipped: Int,
        mccSkipped: Int,
        mccConduitSkipped: Int,
    ): String {
        val base = "Додано категорій: $categoriesCreated, кодів MCC: $mccAdded"
        val skipped = mutableListOf<String>()
        if (categoriesSkipped > 0) skipped += "категорій вже було: $categoriesSkipped"
        if (mccSkipped > 0) skipped += "кодів вже зайнято: $mccSkipped"
        if (mccConduitSkipped > 0) skipped += "кодів позначено транзитними: $mccConduitSkipped"
        return if (skipped.isEmpty()) base else "$base. Пропущено — ${skipped.joinToString(", ")}"
    }

    override val conduitMccHeading = "Транзитні коди"
    override val conduitMccHint =
        "Коди, які нічого не кажуть про призначення платежу — переказ на чужу картку, наприклад. " +
            "Такий код не належить жодній категорії, а бот питає про кожну операцію окремо."
    override val conduitMccPlaceholder = "4829"
    override fun conduitMccSaved(movedCount: Int) = "Збережено. Переміщено операцій: $movedCount."
    override fun conduitMccCleared(movedCount: Int) =
        "Список транзитних кодів очищено — жоден код більше не транзитний. Переміщено операцій: $movedCount."
    override fun conduitMccRejected(mcc: Int) = "Код $mcc позначений транзитним — його не можна прив'язати до категорії."
    override val counterpartyRulesHeading = "Правила по отримувачах"
    override val counterpartyRulesEmpty =
        "Поки що порожньо. Правила з'являються, коли ти обираєш категорію для переказу — в боті або в списку операцій."
    override val counterpartyRuleRemoved = "Правило прибрано."

    override val transactionsTitle = "Операції"
    override val transactionsKicker = "УСІ РАХУНКИ · UAH"
    override val statSpentThisMonth = "Витрати за місяць"
    override val statUnresolved = "Не розібрано"
    override fun ofTransactionsCount(n: Long) = "з $n ${ukPlural(n, "операції", "операцій", "операцій")}"
    override val holdPill = "холд"
    override val inactiveAccountPill = "рахунок вимкнено — не рахується"
    override val mccAbbrev = "MCC"
    override val chooseCategoryOption = "Обрати категорію"
    override fun categorySavedForRow(categoryName: String) = "Категорію змінено на «$categoryName»"
    override val categoryClearedForRow = "Категорію знято для цієї операції"

    // "N операцій" reads correctly for 5 but not for 1 ("1 операцій"), and Ukrainian's
    // three-way plural (операція/операції/операцій) isn't worth a declension helper for one
    // string — "операцій: N" sidesteps it entirely, the same construction mccBoundFromTransaction
    // already uses for exactly this reason.
    override fun ruleSuggestionCounterparty(name: String, count: Int) =
        "Створити правило для «$name» — операцій: $count"
    override fun ruleSuggestionMcc(mcc: Int) = "Застосувати до всіх операцій з MCC $mcc"
    override val ruleNotNow = "Не зараз"
    override val ruleApply = "Застосувати"

    override fun mccBoundFromTransaction(merchant: String, mcc: Int, categoryName: String, count: Int) =
        "«$merchant» (MCC $mcc) → $categoryName. Оновлено операцій: $count"
    override fun singleTransactionCategorized(categoryName: String) =
        "У цієї операції нема MCC, тож прив'язати код не вдалось — позначили лише її як «$categoryName»"
    override fun counterpartyBoundFromTransaction(recipient: String, categoryName: String, count: Int) =
        "$recipient → $categoryName. Перенесено операцій: $count."
    override val unknownTransaction = "Операції не знайдено"

    override val emptyTransactionsTitle = "Нічого не знайдено"
    // "Спробуй", not the handoff's own "Спробуйте" — the app's Ukrainian voice is informal
    // "ти" everywhere else (see UkCopy's own doc comment and e.g. wrongPassword's "Спробуй
    // ще раз"); the handoff's copy is otherwise verbatim, this one word aside.
    override val emptyTransactionsBody = "За цими фільтрами операцій немає. Спробуй інший місяць або скинь фільтри."

    override val categoryHeader = "Категорія"
    override val orderFilterLabel = "Сортування"
    override val orderNewest = "Спочатку нові"
    override val orderOldest = "Спочатку старі"
    override val orderLargest = "За сумою"
    override val allCategoriesOption = "Усі категорії"
    override val uncategorizedOnlyLabel = "Лише без категорії"
    override val clearFilters = "Скинути"
    override val showIncomingLabel = "Показати надходження"

    override fun dayGroupHeader(date: java.time.LocalDate): String =
        "${date.dayOfMonth} ${UK_MONTHS_GENITIVE[date.monthValue - 1]}, ${UK_WEEKDAYS[date.dayOfWeek.value - 1]}"

    override val settingsTitle = "Налаштування"
    override val settingsKicker = "Монобанк · Telegram"

    override val statusAllConnected = "Усе підключено"
    override fun statusIncomplete(missing: List<String>) = "Ще не готово: ${missing.joinToString(", ")}"
    override val statusPillMonoToken = "Токен Монобанку"
    override val statusPillTelegramToken = "Токен Telegram-бота"
    override val statusPillTelegramChat = "Telegram-чат"
    override val statusPillTelegramWebhook = "Вебхук Telegram"
    override val statusPillMonoWebhook = "Вебхук Монобанку"

    override val accountsHeading = "Рахунки Монобанку"
    override val accountHeader = "Рахунок"
    override val typeHeader = "Тип"
    override val balanceHeader = "Баланс"
    override val activeHeader = "Врахований"
    override val accountActiveHint =
        "Знятий прапорець прибирає весь рахунок з витрат — і минулі місяці теж, не тільки майбутні синхронізації."
    override val accountDeactivateConfirm =
        "Вимкнути рахунок? Це прибере його історію з усіх сум на дашборді, діаграмі й /status — не тільки з майбутніх синхронізацій."
    override fun countedTotalLabel(total: String) = "Разом врахованих $total"
    override val accountsSaved = "Рахунки збережено"
    override val noAccountsYet = "Рахунків ще нема — тисни Синхронізувати."

    override val syncCardHeading = "Синхронізація"
    override val webhookActiveLabel = "вебхук активний"
    override val webhookInactiveLabel = "вебхук неактивний"
    override val syncTransactions = "Синхронізувати операції"
    override fun syncTransactionsCooldown(waitSeconds: Long) = "Синхронізувати операції (почекай ${waitSeconds} с)"
    override val reregisterMonobankWebhook = "Перереєструвати вебхук"
    override val monobankWebhookRegistered = "Вебхук Монобанку зареєстровано"
    override val monobankRequestFailed = "Монобанк не відповів. Перевір токен і спробуй ще раз"
    override val cannotDeterminePublicUrl = "Не вдалось визначити публічну адресу"
    override val syncRateLimited = "Моно дозволяє одну синхронізацію на хвилину"
    override val syncStarted =
        "Синхронізація почалась. Якщо за місяць багато операцій, Моно віддає їх по 500 на хвилину — тож почекай трохи."
    override fun lastSyncSummary(summary: String) = "Востаннє: $summary"
    override fun lastSyncFailed(reason: String) = "Остання синхронізація не вдалась: $reason"
    override fun syncPeriodLabel(monthLabel: String) = "Синхронізація охоплює лише $monthLabel — так влаштований застосунок."
    override fun syncSummary(
        inserted: Int,
        updated: Int,
        unchanged: Int,
        accounts: Int,
        errors: List<String>,
    ): String {
        val base = "$inserted нових · $updated оновлених · $unchanged без змін · $accounts рахунків"
        return if (errors.isEmpty()) base else "$base · Помилки (${errors.size}): ${errors.joinToString("; ")}"
    }
    override fun syncErrorRateLimited(retryAfterSeconds: Long) = "забагато запитів, спробуй через ${retryAfterSeconds} с"
    override val syncErrorTokenRejected = "токен відхилено"
    override fun syncErrorHttpStatus(status: Int) = "Монобанк повернув HTTP $status"
    override fun syncErrorGeneric(name: String) = "помилка ($name)"
    override val neverSyncedYet = "Ще не синхронізовано."

    override val telegramCardHeading = "Telegram"
    override val chatConnectedLabel = "чат під'єднано"
    override val chatNotConnectedLabel = "чат не під'єднано"
    override val telegramCardHint = "Сповіщення про перевищення лімітів надходять у під'єднаний чат."
    override val telegramNotPairedHint = "Ще не під'єднано. Тисни «Перепід'єднати», щоб отримати код."
    override val sendStartCommand = "Надішли це своєму боту:"
    override val sendStartToMoveChat =
        "Надішли це в чат або групу, куди перенести сповіщення — наприклад, у спільну групу з родиною:"
    override val sendTestNotification = "Надіслати тестове сповіщення"
    override val testNotificationSent = "Тестове сповіщення надіслано"
    override val testNotificationText = "✅ Тестове сповіщення з твого трекера бюджету."
    override val noChatPaired = "Чат ще не під'єднано"
    override val telegramRequestFailed = "Telegram не відповів. Перевір токен і спробуй ще раз"
    override val generatePairingCode = "Згенерувати код"
    override val regeneratePairingCode = "Перепід'єднати"
    override fun sendStartToBot(code: String, minutes: Long) =
        "Надішли /start $code своєму боту протягом $minutes хв"

    override val tokensHeading = "Токени"
    override val tokensHelp =
        "Залиш поле порожнім, щоб не чіпати збережене значення. Токени зберігаються в зашифрованому вигляді."
    override val monobankTokenLabel = "Токен Монобанку"
    override val telegramTokenLabel = "Токен Telegram-бота"
    override val configured = "налаштовано"
    override val monoTokenPlaceholder = "u..."
    override val telegramTokenPlaceholder = "123456:ABC..."
    override val saveTokensButton = "Зберегти токени"
    override val tokensSaved = "Токени збережено"
    override val tokensSavedInitialSync =
        "Токени збережено. Перша синхронізація вже почалась — якщо операцій багато, це займе кілька хвилин: " +
            "Моно віддає по 500 за хвилину."

    override val languageHeading = "Мова"
    override val languageLabel = "Мова інтерфейсу"
    override val ukrainian = "Українська"
    override val english = "English"
    override val languageSaved = "Мову змінено"

    override val notPairedBotMessage =
        "Цей бот ще ні з чим не з'єднаний. Відкрий Налаштування у вебці й надішли /start із кодом звідти."
    override val alreadyConnectedMessage = "Уже під'єднано. Напиши /status, щоб побачити цифри."
    override val invalidPairingCode = "Код не той або вже прострочений. Візьми свіжий у Налаштуваннях."
    override val pairingSuccess = "✅ Чат під'єднано. Сповіщення про бюджет тепер приходитимуть сюди.\n\n"
    override val chatMovedAway = "ℹ️ Бота під'єднано до іншого чату. Сповіщення про бюджет тепер приходять туди."
    override val promptMovedAway = "↪️ Це питання перенесено в чат, куди тепер приходять сповіщення."
    override val limitPickCategory = "Який ліміт змінюємо?"
    override fun limitButton(label: String, limit: String) = "$label ($limit)"
    override fun limitButtonNoLimit(label: String) = "$label (без ліміту)"
    override val limitCancelButton = "✖️ Скасувати"
    override val limitCancelled = "Скасовано."
    override val limitNoCategories = "Ще немає жодної категорії — створи їх на сторінці Категорії."
    override fun limitAmountQuestion(label: String, current: String) =
        "$label — зараз $current.\nНадішли нову суму відповіддю на це повідомлення. 0 — прибрати ліміт."
    override fun limitAmountQuestionNoLimit(label: String) =
        "$label — ліміту немає.\nНадішли суму відповіддю на це повідомлення. 0 — залишити без ліміту."
    override val limitAmountNotANumber =
        "Не зрозумів суму. Надішли число відповіддю на це повідомлення — наприклад 8000 або 8500,50. 0 — прибрати ліміт."
    override val limitAmountNegative =
        "Ліміт не може бути відʼємним. Надішли додатне число відповіддю на це повідомлення, або 0, щоб прибрати ліміт."
    override fun limitAmountTooLarge(max: String) =
        "Забагато. Найбільший ліміт — $max. Надішли меншу суму відповіддю на це повідомлення."
    override fun limitChanged(label: String, from: String, to: String, who: String) =
        "$label: $from → $to" + if (who.isBlank()) "" else " ($who)"
    override fun limitCleared(label: String, who: String) =
        "$label: ліміт прибрано" + if (who.isBlank()) "" else " ($who)"
    override val limitCategoryGone = "Цієї категорії вже немає."
    override val limitDialogMovedAway = "↪️ Змінення ліміту скасовано — бот тепер пише в інший чат."
    override fun leftHeader(monthLabel: String) = "Залишок, $monthLabel:"
    override fun leftLine(label: String, left: String, limit: String) = "$label — $left з $limit"
    override fun leftLineOver(label: String, over: String, limit: String) =
        "⚠️ $label — перевитрата $over (ліміт $limit)"
    // The one place a count stands directly next to its noun, so it declines: "2 категорій"
    // is what a frozen genitive plural looks like, and it was a real production finding.
    override fun leftNoLimitTail(count: Int) =
        "Без ліміту: $count " + ukPlural(count, "категорія", "категорії", "категорій")
    override val leftNothingWithLimits = "Жодна категорія ще не має ліміту."
    override fun badMonthArgument(example: String) = "Не зрозумів місяць. Формат: /status $example"
    override val botCommands = listOf(
        app.notify.BotCommand("/status", "Витрати за місяць"),
        app.notify.BotCommand("/left", "Скільки лишилось по категоріях"),
        app.notify.BotCommand("/limit", "Змінити місячний ліміт"),
        app.notify.BotCommand("/help", "Список команд"),
    )
    override val helpText = "Команди:\n" +
        "/status [РРРР-ММ] — витрати за місяць\n" +
        "/left — скільки лишилось по категоріях\n" +
        "/limit — змінити місячний ліміт\n" +
        "/help — це повідомлення"
    override val staleCallback = "Це повідомлення застаріло — зачекай наступного."
    override fun statusHeader(monthLabel: String, total: String) = "$monthLabel\nВитрачено: $total"
    override fun statusLine(label: String, spent: String, limit: String, pct: Int, mark: String) =
        "$label: $spent / $limit  $pct%$mark"
    override fun statusLineNoLimit(label: String, spent: String) = "$label: $spent"
    override val noCategoriesYetStatus = "Категорій ще нема."
    override fun budgetExceeded(category: String, limit: String, spent: String, over: String) =
        "⚠️ «$category» вийшла за ліміт.\n\nЛіміт: $limit\nВитрачено: $spent\nПеревитрата: $over"
    override fun budgetWarning(pct: Int, category: String, limit: String, spent: String, left: String) =
        "🔔 «$category»: вже $pct% ліміту.\n\nЛіміт: $limit\nВитрачено: $spent\nЗалишилось: $left"
    override fun unknownMccQuestion(description: String, amount: String, mcc: Int) =
        "🤔 Незнайомий MCC $mcc\n\n$description\n$amount\n\n" +
            "Яку категорію обрати? Відповідь застосується до всіх майбутніх операцій із цим MCC, не лише до цієї."
    override val skipButton = "Пропустити"
    override val alreadyHandledCallback = "Вже оброблено"
    override fun mccSkipped(mcc: Int) = "MCC $mcc — пропущено"
    override fun mccBound(mcc: Int, categoryName: String) = "MCC $mcc → $categoryName"
    override fun mccConflictTelegram(mcc: Int, ownerName: String) = "MCC $mcc вже належить категорії «$ownerName»"
    override val mccScopeHint = "Застосується до всіх майбутніх покупок з цим кодом."
    override val transferScopeHint = "Стосується тільки цього отримувача."
    override fun transferQuestion(amount: String, recipient: String) = "Переказ $amount · $recipient — куди це?"
    override fun transferQuestionSeenBefore(count: Int) = "Раніше було ще $count таких переказів."
    override fun transferBound(recipient: String, categoryName: String, movedCount: Int) =
        "$recipient → $categoryName. Перенесено операцій: $movedCount."
    override fun transferSingleRow(categoryName: String) =
        "Перенесено в $categoryName. Отримувача не вдалося розпізнати, тому правило не створено."
    override fun transferAlreadyCategorized(categoryName: String) = "Уже в категорії $categoryName."
    override val transferSkipped = "Пропущено."
    override fun transferMccConflict(mcc: Int, ownerName: String) =
        "Код MCC $mcc вже належить категорії «$ownerName». Нічого не змінилося."
    override val transferNothingChanged = "Нічого не змінилося."
    override val newCategoryButton = "➕ Нова категорія"
    override val newCategoryPrompt = "Введи назву нової категорії"
    override val categoryNameInvalid = "Порожня назва не годиться. Спробуй ще раз."
    override fun categoryAlreadyExists(label: String) = "Категорія $label вже є — беру її."
}

private val UK_MONTHS = listOf(
    "Січень", "Лютий", "Березень", "Квітень", "Травень", "Червень",
    "Липень", "Серпень", "Вересень", "Жовтень", "Листопад", "Грудень",
)

/** Genitive case ("3 вересня", not "3 Вересень") — [dayGroupHeader]'s day-group date,
 *  the one place a month name is glued to a day-of-month rather than standing alone. */
private val UK_MONTHS_GENITIVE = listOf(
    "січня", "лютого", "березня", "квітня", "травня", "червня",
    "липня", "серпня", "вересня", "жовтня", "листопада", "грудня",
)

/** [java.time.DayOfWeek.getValue] is 1=Monday..7=Sunday — same order here. */
private val UK_WEEKDAYS = listOf(
    "понеділок", "вівторок", "середа", "четвер", "п'ятниця", "субота", "неділя",
)
