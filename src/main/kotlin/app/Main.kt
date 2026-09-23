package app

import app.budget.BudgetService
import app.budget.CategoryRepository
import app.budget.ConduitMccRepository
import app.budget.CounterpartyRepository
import app.budget.TransactionRepository
import app.db.Crypto
import app.db.SettingKeys
import app.db.SettingsRepository
import app.db.connectDb
import app.db.runMigrations
import app.ingest.AccountRepository
import app.ingest.IngestService
import app.ingest.SyncService
import app.ingest.WebhookEventRepository
import app.ingest.WebhookProcessor
import app.mono.HttpMonoClient
import app.notify.CompositeCallbackHandler
import app.notify.CompositeChatMoveObserver
import app.notify.HttpTelegramClient
import app.notify.LimitPromptRepository
import app.notify.LimitPromptService
import app.notify.MccPromptRepository
import app.notify.MccPromptService
import app.notify.NotificationEventRepository
import app.notify.Notifier
import app.notify.TelegramUpdateHandler
import app.notify.withRedactedTelegramToken
import app.web.authRoutes
import app.web.categoryRoutes
import app.web.dashboardRoutes
import app.web.LoginThrottle
import app.web.SessionEpoch
import app.web.installAuth
import app.web.installSecurityHeaders
import app.web.monoWebhookRoutes
import app.web.settingsRoutes
import app.web.staticRoutes
import app.web.telegramWebhookRoutes
import app.web.transactionRoutes
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.host
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory

fun main() {
    val config = Config.fromEnv()
    val db = connectDb(config.dbPath)
    runMigrations(db)
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        module(config, db)
    }.start(wait = true)
}

fun Application.module(config: Config, db: Database) {
    val log = LoggerFactory.getLogger("app.Module")

    install(CallLogging) {
        // The default format logs the request path verbatim, and Monobank's webhook
        // secret lives in that path (POST /webhook/<secret>) — the same class of leak
        // the spec rejected a path secret for with Telegram. Redact everything after
        // "/webhook/" while keeping the rest of the line (method, status, path) useful.
        format { call ->
            val status = call.response.status()?.value?.toString() ?: "-"
            val method = call.request.httpMethod.value
            "$status $method ${redactWebhookPath(call.request.path())}"
        }
    }
    // Before installAuth, so an injected script has no page left to run on and the
    // headers apply to public routes too.
    installSecurityHeaders(hsts = config.secureCookies)

    // Nothing here may escape as a raw 500: StatusPages centralises that, and redacts,
    // because a Telegram exception carries the bot token in its message.
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            log.error(
                "unhandled exception for {} {}",
                call.request.httpMethod.value,
                redactWebhookPath(call.request.path()),
                cause.withRedactedTelegramToken(),
            )
            call.respondText("Something went wrong.", status = HttpStatusCode.InternalServerError)
        }
    }

    val http = HttpClient(CIO) {
        // API_JSON, never an inline Json { } here: the test suite must build its clients
        // from the same value, or the two configurations drift and the suite stops
        // exercising what production actually sends. See ApiJson.kt for the bug that
        // taught us this.
        install(ContentNegotiation) { json(API_JSON) }
        // Carried decision B: the ingest mutex is held across notifier.checkThresholds,
        // which reaches telegram.sendMessage — a real network call. Without a timeout, a
        // hung Telegram connection would stall the application's only writer.
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
        }
    }

    // --- storage -------------------------------------------------------------
    val settings = SettingsRepository(db, Crypto(config.encryptionKey))
    val conduit = ConduitMccRepository(db)
    val counterparties = CounterpartyRepository(db)
    val categories = CategoryRepository(db, conduit)
    val transactions = TransactionRepository(db)
    val accounts = AccountRepository(db)
    val events = WebhookEventRepository(db)
    val prompts = MccPromptRepository(db)
    val limitPrompts = LimitPromptRepository(db)

    // Carried decision A: secureCookies defaults to true; local dev opts out via
    // DEV_INSECURE_COOKIES=1 (see Config.fromEnv). Without threading it through here,
    // the production cookie would silently lose its Secure flag.
    //
    // Installed after `settings` exists because the session epoch is persisted: an
    // in-memory counter would reset on every deploy, which would silently log the owner
    // out on each restart rather than only when they ask for it.
    val sessionEpoch = settingsSessionEpoch(settings)
    val loginThrottle = LoginThrottle()
    installAuth(config.adminPassword, config.encryptionKey, config.secureCookies, sessionEpoch)

    // --- external services ---------------------------------------------------
    val mono = HttpMonoClient(http, { settings.get(SettingKeys.MONO_TOKEN) })
    val telegram = HttpTelegramClient(http, { settings.get(SettingKeys.TELEGRAM_TOKEN) })

    // --- domain --------------------------------------------------------------
    val budget = BudgetService(categories, transactions)
    val notifier = Notifier(telegram, settings, NotificationEventRepository(db))

    // MccPromptService needs the ingest service and vice versa; the provider lambda
    // breaks the cycle without a DI container.
    lateinit var ingest: IngestService
    val promptService = MccPromptService(
        prompts, categories, settings, telegram, conduit, transactions, counterparties,
    ) { ingest }
    ingest = IngestService(
        transactions, categories, budget, notifier, counterparties, conduit, accounts, promptService,
    )

    val processor = WebhookProcessor(events, ingest)
    val sync = SyncService(mono, ingest, accounts, settings)
    val limitService = LimitPromptService(limitPrompts, categories, settings, telegram) { ingest }
    val updateHandler = TelegramUpdateHandler(
        settings, telegram, budget,
        CompositeCallbackHandler(listOf(promptService, limitService)),
        CompositeChatMoveObserver(listOf(promptService, limitService)),
        nameReplies = promptService::handleNameReply,
        limits = limitService,
    )

    // Carried decision C: the public base URL is derived from the request, not a
    // required env var. The project permits exactly two required env vars,
    // ADMIN_PASSWORD and ENCRYPTION_KEY. PUBLIC_URL is an optional override for
    // unusual proxy setups; otherwise prefer X-Forwarded-Host, then the request host.
    //
    // This URL is where Monobank will push every transaction and where Telegram will
    // send every update, and it used to be taken from an unvalidated request header —
    // so whoever could set X-Forwarded-Host chose where the bank's feed went. It is now
    // checked against ALLOWED_HOSTS when that is set, and against a strict hostname
    // shape otherwise. Setting PUBLIC_URL (fly.toml does) skips the header entirely.
    val allowedHosts = System.getenv("ALLOWED_HOSTS").orEmpty()
        .split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    val publicBaseUrl: ApplicationCall.() -> String? = {
        resolvePublicBaseUrl(
            publicUrl = System.getenv("PUBLIC_URL"),
            allowedHosts = allowedHosts,
            forwardedHost = request.headers["X-Forwarded-Host"],
            requestHost = request.host(),
        ).also { if (it == null) log.warn("refusing to derive a public URL from this request") }
    }

    routing {
        get("/health") { call.respondText("ok") }
        // Browsers request this unprompted on every page load. Without a route it falls
        // through to the auth gate, which 302s it to /login, and the browser logs a 404 for
        // an HTML page it asked to be an icon — one console error per navigation, on every
        // screen. 204 answers it honestly: there is no icon, and nothing went wrong.
        get("/favicon.ico") { call.respond(HttpStatusCode.NoContent) }
        staticRoutes()
        authRoutes(
            config.adminPassword, config.encryptionKey, settings, sessionEpoch, loginThrottle,
            secureCookies = config.secureCookies,
        )
        dashboardRoutes(budget, transactions, settings, accounts, secureCookies = config.secureCookies)
        categoryRoutes(categories, ingest, settings, conduit, counterparties, budget, accounts, secureCookies = config.secureCookies)
        transactionRoutes(transactions, categories, ingest, settings, conduit, counterparties, accounts, budget, secureCookies = config.secureCookies)
        // The application's own CoroutineScope: launched syncs outlive the HTTP request
        // that triggered them and must not be tied to it, but they still must not outlive
        // the application (no GlobalScope) so they get cancelled cleanly on shutdown.
        settingsRoutes(
            settings, sync, mono, telegram, accounts, ingest, { block -> launch { block() } },
            secureCookies = config.secureCookies,
            publicBaseUrl = publicBaseUrl,
        )
        monoWebhookRoutes({ settings.get(SettingKeys.MONO_WEBHOOK_SECRET) }, events) {
            // drain() now propagates a transient ingest/database failure instead of
            // swallowing it, so the event stays unprocessed for a later retry (see
            // WebhookProcessor). That exception must not escape this launched coroutine
            // uncaught, or one bad delivery could tear down the application's coroutine scope.
            launch { runCatching { processor.drain() }.onFailure { log.warn("post-webhook drain failed", it) } }
        }
        telegramWebhookRoutes({ settings.get(SettingKeys.TELEGRAM_WEBHOOK_SECRET) }, updateHandler)
    }

    // Drain anything a restart interrupted, then keep the safety net running.
    launch {
        runCatching { processor.drain() }.onFailure { log.warn("startup drain failed", it) }
        // Before the first delay, not after it. Every account gate — which transactions are
        // stored at all, which are counted, which raise a Telegram question — is derived
        // from rows in `accounts`, and an account with no row is read as tracked hryvnia by
        // all of them. The refresh used to happen only inside the hourly tick, so for the
        // first hour after any deploy the gates were armed with whatever the table happened
        // to hold: on the deploy that introduced the currency rule, that meant no row for
        // the dollar account and an hour of its transfers being stored as kopecks, counted
        // as hryvnia and asked about — the exact bug the rule exists to stop, and those
        // rows are permanent (ingest refuses the redelivery, upsert never rewrites a
        // settled row). One client-info call per restart is the whole cost.
        runCatching {
            if (settings.isSet(SettingKeys.MONO_TOKEN)) sync.refreshAccounts()
        }.onFailure { log.warn("startup account refresh failed", it) }
        while (true) {
            delay(HOUR_MILLIS)
            runCatching {
                processor.drain()
                // waitForRateLimit = true: this runs in the background with nobody
                // waiting on it, so a busy month that needs several statement pages
                // should sleep out Monobank's 60s gate and reconcile fully rather than
                // fail outright on page two (item 10).
                if (settings.isSet(SettingKeys.MONO_TOKEN)) sync.syncCurrentMonth(waitForRateLimit = true)
                // Processed deliveries are kept for a month for diagnosis, then dropped:
                // nothing was deleting them, and they share a 1 GB volume with the
                // database itself. Unprocessed rows are never swept — see the repository.
                val removed = events.deleteProcessedBefore(
                    java.time.Instant.now().epochSecond - WEBHOOK_RETENTION_SECONDS,
                )
                if (removed > 0) log.info("pruned {} processed webhook events", removed)
            }.onFailure { log.warn("hourly maintenance failed", it) }
        }
    }
}

/** Monobank's webhook secret lives in the path (`/webhook/<secret>`); this keeps it out of the access log. */
internal fun redactWebhookPath(path: String): String =
    if (path.startsWith("/webhook/")) "/webhook/***" else path

/**
 * A hostname and nothing else: no scheme, no userinfo, no path, no port. Anything with
 * structure in it is rejected rather than parsed, because the value is about to be
 * concatenated into the URL a bank will post transactions to.
 */
private val HOSTNAME = Regex(
    """^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$""",
)

/** Hosts that would make a webhook registration useless or point it back at a developer's machine. */
private val NEVER_PUBLIC = setOf("localhost", "127.0.0.1", "0.0.0.0", "::1")

/**
 * Decides the base URL to register with Monobank and Telegram, or null when no
 * trustworthy one can be determined — in which case the caller shows an error rather
 * than registering a webhook somewhere unintended.
 */
internal fun resolvePublicBaseUrl(
    publicUrl: String?,
    allowedHosts: Set<String>,
    forwardedHost: String?,
    requestHost: String?,
): String? {
    publicUrl?.trim()?.takeIf { it.isNotBlank() }?.let { return it.trimEnd('/') }

    val candidate = (forwardedHost?.takeIf { it.isNotBlank() } ?: requestHost)
        ?.trim()?.substringBefore(':')?.lowercase()
        ?: return null

    if (candidate.isBlank() || candidate in NEVER_PUBLIC) return null
    if (!HOSTNAME.matches(candidate)) return null
    if (allowedHosts.isNotEmpty() && candidate !in allowedHosts) return null
    return "https://$candidate"
}

/** Persists the session revocation counter so it survives a restart or a redeploy. */
internal fun settingsSessionEpoch(settings: SettingsRepository): SessionEpoch = object : SessionEpoch {
    override fun current(): Long = settings.get(SettingKeys.SESSION_EPOCH)?.toLongOrNull() ?: 0L
    override fun bump() = settings.set(SettingKeys.SESSION_EPOCH, (current() + 1).toString())
}

private const val HOUR_MILLIS = 60L * 60 * 1000

/** How long a processed webhook delivery is kept before the hourly sweep drops it. */
private const val WEBHOOK_RETENTION_SECONDS = 30L * 24 * 3600
