package app.web

import app.API_JSON
import app.budget.formatMinor
import app.db.SettingKeys
import app.db.SettingsRepository
import app.i18n.Copy
import app.i18n.Language
import app.i18n.copy
import app.i18n.language
import app.i18n.setLanguage
import app.ingest.AccountRepository
import app.ingest.IngestService
import app.ingest.StoredAccount
import app.ingest.StoredSyncResult
import app.ingest.SyncReport
import app.ingest.render
import app.ingest.structuredSyncFailure
import app.ingest.SyncService
import app.mono.MonoClient
import app.notify.TelegramClient
import app.notify.PAIRING_TTL_SECONDS
import app.notify.generatePairingCode
import app.notify.generateSecret
import app.notify.withRedactedTelegramToken
import io.ktor.server.application.*
import io.ktor.server.html.respondHtml
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.html.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("app.web.SettingsPage")

fun Route.settingsRoutes(
    settings: SettingsRepository,
    sync: SyncService,
    mono: MonoClient,
    telegram: TelegramClient,
    accounts: AccountRepository,
    ingest: IngestService,
    // The seam that lets a sync run detached without reaching for a global scope. Production
    // wires this to the Application's own CoroutineScope (`{ block -> launch { block() } }`);
    // tests wire it to a scope they control so the launched job can be awaited deterministically
    // instead of slept on.
    launchSync: (suspend () -> Unit) -> Unit,
    // Mirrors installAuth's flag: the CSRF cookie every form on this page relies on
    // travels under the same Secure rule as the session cookie. Placed before
    // publicBaseUrl (not after) so callers can still pass that as a trailing lambda.
    secureCookies: Boolean = true,
    publicBaseUrl: ApplicationCall.() -> String?,
) {
    get("/settings") {
        val cooldown = sync.secondsUntilSyncAllowed()
        val copy = settings.copy()
        val currentLanguage = settings.language()
        val csrfToken = call.csrfToken(secureCookies)

        val monoTokenSet = settings.isSet(SettingKeys.MONO_TOKEN)
        val telegramTokenSet = settings.isSet(SettingKeys.TELEGRAM_TOKEN)
        val chatId = settings.get(SettingKeys.TELEGRAM_CHAT_ID)
        val telegramWebhookSet = settings.isSet(SettingKeys.TELEGRAM_WEBHOOK_REGISTERED_AT)
        val monoWebhookSet = settings.isSet(SettingKeys.MONO_WEBHOOK_REGISTERED_AT)
        // Only the accounts whose checkbox means something. A dollar account is stored
        // (ingest needs to recognise it to refuse its transactions) but is never trackable,
        // so listing it would offer a switch that changes nothing and add its balance —
        // dollars — to a total the card prints in ₴.
        val storedAccounts = accounts.trackableList()

        call.respondHtml {
            page(
                copy = copy,
                title = copy.settingsTitle,
                active = "settings",
                csrfToken = csrfToken,
                flash = call.request.queryParameters["flash"],
                error = call.request.queryParameters["error"],
                headerMeta = copy.headerMeta(storedAccounts.size),
            ) {
                div("stack") {
                    pageHeader(copy.settingsKicker, copy.settingsTitle)

                    statusCard(copy, monoTokenSet, telegramTokenSet, chatId != null, telegramWebhookSet, monoWebhookSet)

                    accountsCard(copy, storedAccounts, csrfToken)

                    div("settings-cards") {
                        syncCard(copy, settings, sync, monoWebhookSet, cooldown, csrfToken)
                        telegramCard(copy, settings, chatId, csrfToken)
                    }

                    tokensCard(copy, monoTokenSet, telegramTokenSet, csrfToken)

                    languageBanner(copy, currentLanguage, csrfToken)
                }
            }
        }
    }

    post("/settings/language") {
        val form = call.formParameters()
        settings.setLanguage(Language.fromCode(form["language"]))
        val copy = settings.copy()
        // Re-registered in the *new* language. Without this the command menu in the input
        // bar keeps whichever language it was first registered in while every reply
        // switches — the bot disagreeing with itself in the same chat. Best effort: a stale
        // menu is cosmetic and must not fail the language change.
        runCatching { telegram.setMyCommands(copy.botCommands) }
            .onFailure { log.warn("failed to re-register the command menu", it.withRedactedTelegramToken()) }
        call.redirectWith("/settings", copy.languageSaved)
    }

    post("/settings/tokens") {
        val copy = settings.copy()
        val form = call.formParameters()
        val hadMonoToken = settings.isSet(SettingKeys.MONO_TOKEN)
        form["monoToken"]?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { settings.set(SettingKeys.MONO_TOKEN, it, encrypted = true) }
        form["telegramToken"]?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { settings.set(SettingKeys.TELEGRAM_TOKEN, it, encrypted = true) }

        // Spec §11: configuring the Monobank token for the first time pulls the
        // accounts and the current month, so the dashboard is not empty on arrival. This
        // used to run inline (blocking the request); a card used for everything can hold
        // more than 500 transactions in a month, and the second statement page would then
        // hit Monobank's 60s gate. Run it detached with waitForRateLimit = true instead, so
        // a busy first sync reconciles fully in the background rather than failing fast in
        // front of the user (item 10).
        if (!hadMonoToken && settings.isSet(SettingKeys.MONO_TOKEN)) {
            sync.markSyncStarted()
            launchSync { runDetachedSync(settings, log) { sync.syncCurrentMonth(waitForRateLimit = true) } }
            return@post call.redirectWith("/settings", copy.tokensSavedInitialSync)
        }
        call.redirectWith("/settings", copy.tokensSaved)
    }

    post("/settings/pairing-code") {
        val copy = settings.copy()
        // design-handoff.md §4.4: "Перепід'єднати" is one button, not two. It now also
        // (re)registers the Telegram webhook before handing out a code — see
        // Copy.regeneratePairingCode's doc comment. A pairing code Telegram can never
        // deliver to us because the webhook was never registered is the exact
        // silent-failure bug the 2026-09-03 review fixed with a status line; folding both
        // actions into one button makes the gap structurally impossible to repeat, rather
        // than relying on the owner reading two separate cards in the right order.
        val base = call.publicBaseUrl()
            ?: return@post call.redirectWith("/settings", copy.cannotDeterminePublicUrl, isError = true)
        val webhookSecret = settings.get(SettingKeys.TELEGRAM_WEBHOOK_SECRET) ?: generateSecret().also {
            settings.set(SettingKeys.TELEGRAM_WEBHOOK_SECRET, it)
        }
        try {
            telegram.setWebhook("$base/tg/updates", webhookSecret)
            settings.set(SettingKeys.TELEGRAM_WEBHOOK_REGISTERED_AT, java.time.Instant.now().epochSecond.toString())
        } catch (e: Exception) {
            log.warn("Telegram webhook registration failed", e.withRedactedTelegramToken())
            return@post call.redirectWith("/settings", copy.telegramRequestFailed, isError = true)
        }

        // Best effort, after the webhook is up: a missing command menu is cosmetic, and
        // failing the pairing over it would cost the owner the code they just asked for.
        runCatching { telegram.setMyCommands(copy.botCommands) }
            .onFailure { log.warn("failed to register the command menu", it.withRedactedTelegramToken()) }

        val code = generatePairingCode()
        settings.set(SettingKeys.PAIRING_CODE, code)
        // A code with no deadline is a standing credential for whoever finds the bot;
        // TelegramUpdateHandler refuses one without a live expiry stamp. Generating a new
        // code also clears the old attempt counter.
        settings.set(
            SettingKeys.PAIRING_CODE_EXPIRES_AT,
            (java.time.Instant.now().epochSecond + PAIRING_TTL_SECONDS).toString(),
        )
        settings.delete(SettingKeys.PAIRING_ATTEMPTS)
        call.redirectWith("/settings", copy.sendStartToBot(code, PAIRING_TTL_SECONDS / 60))
    }

    post("/settings/webhook") {
        val copy = settings.copy()
        val base = call.publicBaseUrl()
            ?: return@post call.redirectWith("/settings", copy.cannotDeterminePublicUrl, isError = true)
        val secret = settings.get(SettingKeys.MONO_WEBHOOK_SECRET) ?: generateSecret().also {
            settings.set(SettingKeys.MONO_WEBHOOK_SECRET, it)
        }
        try {
            mono.registerWebhook("$base/webhook/$secret")
            settings.set(SettingKeys.MONO_WEBHOOK_REGISTERED_AT, java.time.Instant.now().epochSecond.toString())
            call.redirectWith("/settings", copy.monobankWebhookRegistered)
        } catch (e: Exception) {
            // Never put a raw exception message in the redirect: Ktor's timeout/connection
            // exceptions embed the full request URL, and for Telegram that URL contains the
            // bot token — it would land in the browser's address bar and history. The real
            // detail stays in the server log, not the URL.
            log.warn("Monobank webhook registration failed", e)
            call.redirectWith("/settings", copy.monobankRequestFailed, isError = true)
        }
    }

    post("/settings/test-notification") {
        val copy = settings.copy()
        val chatId = settings.get(SettingKeys.TELEGRAM_CHAT_ID)
            ?: return@post call.redirectWith("/settings", copy.noChatPaired, isError = true)
        try {
            telegram.sendMessage(chatId, copy.testNotificationText)
            call.redirectWith("/settings", copy.testNotificationSent)
        } catch (e: Exception) {
            log.warn("test notification failed", e.withRedactedTelegramToken())
            call.redirectWith("/settings", copy.telegramRequestFailed, isError = true)
        }
    }

    post("/settings/accounts") {
        val copy = settings.copy()
        val form = call.formParameters()
        // Through ingest, never accounts.setActive directly — the accounts table is one of
        // ingest's inputs now (CLAUDE.md, *The single-writer rule*).
        ingest.setAccountsActive(form.getAll("active")?.toSet().orEmpty())
        call.redirectWith("/settings", copy.accountsSaved)
    }

    post("/settings/sync") {
        val copy = settings.copy()
        if (sync.secondsUntilSyncAllowed() > 0) {
            return@post call.redirectWith("/settings", copy.syncRateLimited, isError = true)
        }
        // Stamp the cooldown here, synchronously, before launching — not inside the job.
        // waitForRateLimit = true means a busy month can take minutes to reconcile, and
        // syncMonth only re-stamps LAST_SYNC_AT once it finishes; without this early stamp
        // the button would read as enabled for that whole window and a second click could
        // launch an overlapping sync.
        sync.markSyncStarted()
        // waitForRateLimit = true: this HTTP request no longer waits for the sync to
        // finish, so there is no reason to fail fast on a busy month's second statement
        // page. Launching detached and waiting out the 60s gate instead means an active
        // card's first sync reconciles fully rather than surfacing a rate-limit error and
        // an empty dashboard (item 10; the hourly background sync already did this).
        launchSync { runDetachedSync(settings, log) { sync.syncMonth(sync.currentMonth(), waitForRateLimit = true) } }
        call.redirectWith("/settings", copy.syncStarted)
    }
}

/**
 * Runs a detached sync and records a *failure* the Settings page can show. The success path
 * belongs to `SyncService.syncMonth` now (see its `record`), so the hourly background sync
 * leaves the same trace this button does; writing it here as well would only put the same
 * JSON under the same key twice.
 */
private suspend fun runDetachedSync(
    settings: SettingsRepository,
    log: Logger,
    block: suspend () -> SyncReport,
) {
    runCatching { block() }
        .onFailure { e ->
            log.warn("detached sync failed", e)
            // structuredSyncFailure, not e.message: Ktor's timeout exceptions carry the
            // full Monobank statement URL (account id included) in their message, and
            // this is rendered on the Settings page.
            settings.set(SettingKeys.LAST_SYNC_RESULT, API_JSON.encodeToString(structuredSyncFailure(e)))
        }
}

// --- status card (design-handoff.md §4.2) --------------------------------------------------

private fun FlowContent.statusCard(
    copy: Copy,
    monoToken: Boolean,
    telegramToken: Boolean,
    chatPaired: Boolean,
    telegramWebhook: Boolean,
    monoWebhook: Boolean,
) {
    val items = listOf(
        copy.statusPillMonoToken to monoToken,
        copy.statusPillTelegramToken to telegramToken,
        copy.statusPillTelegramChat to chatPaired,
        copy.statusPillTelegramWebhook to telegramWebhook,
        copy.statusPillMonoWebhook to monoWebhook,
    )
    val allDone = items.all { it.second }
    div("status-card") {
        div("status-card-head") {
            span(if (allDone) "status-dot good" else "status-dot muted") {}
            span("status-headline") {
                +if (allDone) copy.statusAllConnected else copy.statusIncomplete(items.filterNot { it.second }.map { it.first })
            }
        }
        div("status-pills") {
            items.forEach { (label, done) ->
                span(statusPillClass(done)) {
                    if (done) span("status-pill-mark") { +"✓" }
                    +label
                }
            }
        }
    }
}

private fun statusPillClass(done: Boolean): String = if (done) "status-pill good" else "status-pill muted"

// --- accounts card (design-handoff.md §4.3) ------------------------------------------------

private fun FlowContent.accountsCard(copy: Copy, stored: List<StoredAccount>, csrfToken: String) {
    h2 { +copy.accountsHeading }
    div("acct-card") {
        if (stored.isEmpty()) {
            // Finding 10 (MINOR) of the 2026-09-04 review: every other bit of text in this
            // card gets its padding from a row/header/footer class — the empty state has
            // none of those, so its text sat flush against the card border. This is the
            // first screen after first-run setup.
            p("hint acct-empty") { +copy.noAccountsYet }
        } else {
            form(action = "/settings/accounts", method = FormMethod.post) {
                csrfField(csrfToken)
                div("acct-header") {
                    div { +copy.accountHeader }
                    div { +copy.typeHeader }
                    div("acct-col-balance") { +copy.balanceHeader }
                    div("acct-col-active") { +copy.activeHeader }
                }
                stored.forEach { account -> acctRow(account) }
                div("acct-footer") {
                    p("acct-footer-hint hint") { +copy.accountActiveHint }
                    div("acct-footer-actions") {
                        span("acct-total mono") {
                            +copy.countedTotalLabel(formatMinor(stored.filter { it.active }.sumOf { it.balanceMinor }))
                        }
                        // Finding 6 of the 2026-09 branch review: this checkbox stopped
                        // meaning "pull future statements from this account" the moment
                        // spentByCategory started joining on it — unchecking one now
                        // retroactively drops months of history from every total, with
                        // nothing beyond the ordinary "Рахунки збережено" flash to say
                        // so. The hint above states the new semantics up front; the
                        // confirm (data-confirm-if-unchecked, read by the same delegated
                        // submit listener the category delete button already uses) only
                        // fires on a submission that actually turns one off, not on
                        // every save.
                        button(type = ButtonType.submit, classes = "btn btn-primary btn-sm") {
                            attributes["data-confirm-if-unchecked"] = "active"
                            attributes["data-confirm-message"] = copy.accountDeactivateConfirm
                            +copy.save
                        }
                    }
                }
            }
        }
    }
}

private fun FlowContent.acctRow(account: StoredAccount) {
    div("acct-row") {
        div("acct-pan mono") { +account.maskedPan.ifEmpty { "—" } }
        div("acct-type") { +account.type }
        div("acct-balance mono") { +formatMinor(account.balanceMinor) }
        div("acct-col-active") {
            input(type = InputType.checkBox, name = "active") {
                value = account.id
                checked = account.active
            }
        }
    }
}

// --- Синхронізація + Telegram cards (design-handoff.md §4.4) ------------------------------

private fun FlowContent.syncCard(
    copy: Copy,
    settings: SettingsRepository,
    sync: SyncService,
    webhookActive: Boolean,
    cooldown: Long,
    csrfToken: String,
) {
    div("settings-card") {
        div("settings-card-head") {
            span("settings-card-title") { +copy.syncCardHeading }
            span(statusPillClass(webhookActive)) {
                +if (webhookActive) copy.webhookActiveLabel else copy.webhookInactiveLabel
            }
        }
        div("settings-card-body") {
            // 2026-09-03 UX review §10: the stored result is structured JSON, not a
            // rendered sentence, so it re-renders in whatever language is current now —
            // not whatever was current when the sync ran.
            //
            // A row written before that fix is a frozen sentence instead. It used to be
            // printed as-is, on the theory it would be shown "once" — but until finding 2
            // of the 2026-09-22 walkthrough nothing except a button press replaced it, so
            // production spent three weeks printing "369 new, 0 updated, 0 unchanged
            // across 5 account(s)" on a Ukrainian page. Every sync records its own result
            // now, so the next hourly pass overwrites it; an unparseable row is dropped
            // rather than shown in a language nobody chose.
            val rendered = settings.get(SettingKeys.LAST_SYNC_RESULT)
                ?.let { raw -> runCatching { API_JSON.decodeFromString<StoredSyncResult>(raw) }.getOrNull() }
                ?.render(copy)
            p("settings-card-result mono") { +(rendered ?: copy.neverSyncedYet) }
            // Stated before the button is pressed, not just inferred from the result
            // afterward — nothing here used to say a sync covers one month only.
            p("settings-card-hint hint") { +copy.syncPeriodLabel(copy.monthLabel(sync.currentMonth())) }
        }
        div("settings-card-actions") {
            form(action = "/settings/sync", method = FormMethod.post, classes = "inline") {
                csrfField(csrfToken)
                button(type = ButtonType.submit, classes = "btn btn-primary") {
                    if (cooldown > 0) {
                        disabled = true
                        +copy.syncTransactionsCooldown(cooldown)
                    } else {
                        +copy.syncTransactions
                    }
                }
            }
            form(action = "/settings/webhook", method = FormMethod.post, classes = "inline") {
                csrfField(csrfToken)
                button(type = ButtonType.submit, classes = "btn btn-ghost") { +copy.reregisterMonobankWebhook }
            }
        }
    }
}

private fun FlowContent.telegramCard(copy: Copy, settings: SettingsRepository, chatId: String?, csrfToken: String) {
    div("settings-card") {
        div("settings-card-head") {
            span("settings-card-title") { +copy.telegramCardHeading }
            span(statusPillClass(chatId != null)) {
                +if (chatId != null) copy.chatConnectedLabel else copy.chatNotConnectedLabel
            }
        }
        div("settings-card-body") {
            val code = settings.get(SettingKeys.PAIRING_CODE)
                ?.takeIf {
                    val expiresAt = settings.get(SettingKeys.PAIRING_CODE_EXPIRES_AT)?.toLongOrNull()
                    expiresAt != null && java.time.Instant.now().epochSecond <= expiresAt
                }
            when {
                // A live code outranks the paired state: on a paired install the code is how
                // the bot is moved to another chat — a group shared with the family — and
                // hiding it there left "Reconnect" handing out something unreadable.
                code != null -> {
                    p("settings-card-hint hint") { +if (chatId != null) copy.sendStartToMoveChat else copy.sendStartCommand }
                    p("settings-card-code mono") { +"/start $code" }
                }
                chatId != null -> p("settings-card-hint hint") { +copy.telegramCardHint }
                else -> p("settings-card-hint hint") { +copy.telegramNotPairedHint }
            }
        }
        div("settings-card-actions") {
            form(action = "/settings/test-notification", method = FormMethod.post, classes = "inline") {
                csrfField(csrfToken)
                button(type = ButtonType.submit, classes = "btn btn-outline") {
                    // A test notification needs somewhere to send to; disabled rather than
                    // hidden so the two-button layout stays stable across both states.
                    if (chatId == null) disabled = true
                    +copy.sendTestNotification
                }
            }
            form(action = "/settings/pairing-code", method = FormMethod.post, classes = "inline") {
                csrfField(csrfToken)
                button(type = ButtonType.submit, classes = "btn btn-ghost") {
                    +if (chatId != null) copy.regeneratePairingCode else copy.generatePairingCode
                }
            }
        }
    }
}

// --- tokens (design-handoff.md §4.5) -------------------------------------------------------

private fun FlowContent.tokensCard(copy: Copy, monoTokenSet: Boolean, telegramTokenSet: Boolean, csrfToken: String) {
    h2 { +copy.tokensHeading }
    // 2026-09-03 UX review §4: the caption used to be the input's *sibling*, not its
    // parent, so at 1440px the two labels and two boxes wrapped into an order where the
    // Monobank box sat between both captions and the Telegram box had no caption beside it
    // at all. Both fields are type=password, so a token pasted into the wrong (unlabelled)
    // box saved silently — this is the same shape as the emoji/limit mixup on /categories,
    // which is why the fix is the same pattern: each input nests *inside* its own label
    // (`.token-field`), so the association survives any wrap at any width, including the
    // 2-column grid the handoff asks for, down to 390px.
    div("token-card") {
        form(action = "/settings/tokens", method = FormMethod.post) {
            csrfField(csrfToken)
            p("hint") { +copy.tokensHelp }
            div("token-grid") {
                label(classes = "token-field") {
                    div("token-field-label") {
                        span { +copy.monobankTokenLabel }
                        if (monoTokenSet) span(statusPillClass(true)) { +copy.configured }
                    }
                    // Never render a stored token value — placeholder only.
                    input(type = InputType.password, name = "monoToken", classes = "mono") {
                        placeholder = copy.monoTokenPlaceholder
                    }
                }
                label(classes = "token-field") {
                    div("token-field-label") {
                        span { +copy.telegramTokenLabel }
                        if (telegramTokenSet) span(statusPillClass(true)) { +copy.configured }
                    }
                    input(type = InputType.password, name = "telegramToken", classes = "mono") {
                        placeholder = copy.telegramTokenPlaceholder
                    }
                }
            }
            div("token-card-actions") {
                button(type = ButtonType.submit, classes = "btn btn-primary") { +copy.saveTokensButton }
            }
        }
    }
}

// --- language (design-handoff.md §4.6) -----------------------------------------------------

private fun FlowContent.languageBanner(copy: Copy, currentLanguage: Language, csrfToken: String) {
    h2 { +copy.languageHeading }
    form(action = "/settings/language", method = FormMethod.post, classes = "lang-banner") {
        csrfField(csrfToken)
        label(classes = "lang-banner-label") {
            +copy.languageLabel
            select {
                name = "language"
                option { value = Language.UK.code; selected = currentLanguage == Language.UK; +copy.ukrainian }
                option { value = Language.EN.code; selected = currentLanguage == Language.EN; +copy.english }
            }
        }
        button(type = ButtonType.submit, classes = "btn btn-ghost") { +copy.save }
    }
}
