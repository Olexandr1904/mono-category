package app.ingest

import app.API_JSON
import app.budget.KYIV
import app.budget.currentMonthKey
import app.budget.monthRange
import app.db.SettingKeys
import app.db.SettingsRepository
import app.i18n.Copy
import app.i18n.copy
import app.mono.MonoApiException
import app.mono.MonoAuthException
import app.mono.MonoClient
import app.mono.MonoRateLimitException
import app.mono.toTxn
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant

/**
 * Raw facts about one sync, with no wording baked in — [Copy.syncSummary] turns this into
 * the sentence a person reads. `month`/`syncedAt` exist because "369 new, 0 updated"
 * told the owner nothing about *which* period that covered or *when* it ran. Only
 * current-month syncs are recorded (the recap's closed-month pull is not — see
 * [SyncService.syncMonth]); a person reading this must be able to tell what they have
 * without guessing.
 */
data class SyncReport(
    val month: String,
    val accounts: Int,
    val inserted: Int,
    val updated: Int,
    val unchanged: Int,
    val errors: List<String>,
    val syncedAt: Long,
)

/**
 * A short reason safe to show the user and to put in a URL, already translated through
 * [copy] — every user-facing string in this app goes through [Copy].
 *
 * The result is rendered on the Settings page and carried in the redirect query string,
 * so anything folded into it lands in the browser's address bar and history. `e.message`
 * is not safe for that: Ktor bakes the full request URL into its timeout and connection
 * exceptions, which for Monobank means the statement URL — complete with the account id.
 * Monobank's own `errorDescription` is echoed server text and is left out for the same
 * reason. The stack trace still reaches the log, where the detail belongs.
 */
internal fun describeSyncFailure(copy: Copy, e: Throwable): String = when (e) {
    is MonoRateLimitException -> copy.syncErrorRateLimited(e.retryAfterSeconds)
    is MonoAuthException -> copy.syncErrorTokenRejected
    is MonoApiException -> copy.syncErrorHttpStatus(e.status)
    else -> copy.syncErrorGeneric(e::class.simpleName ?: "error")
}

/**
 * What the Settings page persists after a detached sync — plain data, no [Copy] baked in.
 *
 * 2026-09-03 UX review §10: the page used to store [Copy.lastSyncSummary]'s *rendered
 * sentence* in the `settings` table and print it back verbatim. That sentence is immune to
 * both a later language switch and any future wording fix — the string frozen at write
 * time outlives both. Storing these fields as JSON instead (via [app.API_JSON]) and running
 * them through `settings.copy()` again at read time (see `SettingsPage.kt`) means the page
 * always reflects the *current* language and the *current* copy, the same as everything
 * else in the app.
 */
@Serializable
data class StoredSyncResult(
    val ok: Boolean,
    val month: String = "",
    val accounts: Int = 0,
    val inserted: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
    val errors: List<String> = emptyList(),
    val syncedAt: Long = 0,
    // Failure case only — the same branches describeSyncFailure matches, kept as raw
    // fields instead of a rendered string for the same reason as above.
    val failureKind: String = "",
    val failureRetryAfterSeconds: Long = 0,
    val failureHttpStatus: Int = 0,
    val failureExceptionName: String = "",
)

internal fun structuredSyncFailure(e: Throwable): StoredSyncResult = when (e) {
    is MonoRateLimitException -> StoredSyncResult(ok = false, failureKind = "rate_limited", failureRetryAfterSeconds = e.retryAfterSeconds)
    is MonoAuthException -> StoredSyncResult(ok = false, failureKind = "token_rejected")
    is MonoApiException -> StoredSyncResult(ok = false, failureKind = "http_status", failureHttpStatus = e.status)
    else -> StoredSyncResult(ok = false, failureKind = "generic", failureExceptionName = e::class.simpleName ?: "error")
}

/** Renders a [StoredSyncResult] the same way [describeSyncFailure] would have, but from
 *  the current [Copy] rather than whatever was current when the sync ran. */
fun StoredSyncResult.render(copy: Copy): String {
    if (ok) {
        return copy.lastSyncSummary(
            copy.syncSummary(
                inserted = inserted,
                updated = updated,
                unchanged = unchanged,
                accounts = accounts,
                errors = errors,
            ),
        )
    }
    val reason = when (failureKind) {
        "rate_limited" -> copy.syncErrorRateLimited(failureRetryAfterSeconds)
        "token_rejected" -> copy.syncErrorTokenRejected
        "http_status" -> copy.syncErrorHttpStatus(failureHttpStatus)
        else -> copy.syncErrorGeneric(failureExceptionName)
    }
    return copy.lastSyncFailed(reason)
}

/**
 * Pulls statements as a safety net for the webhook. Monobank caps a statement request
 * at 31 days + 1 hour, so a calendar month always fits into a single window.
 */
class SyncService(
    private val mono: MonoClient,
    private val ingest: IngestService,
    private val accounts: AccountRepository,
    private val settings: SettingsRepository,
    private val clock: Clock = Clock.system(KYIV),
    private val now: () -> Long = { Instant.now().epochSecond },
) {
    private val log = LoggerFactory.getLogger(SyncService::class.java)

    suspend fun refreshAccounts(waitForRateLimit: Boolean = false): Int =
        ingest.replaceAccounts(mono.clientInfo(waitForRateLimit).accounts)

    /**
     * The month [syncCurrentMonth] would sync right now, using this service's own clock
     * (system time in production, fixed in tests) rather than a fresh `Clock.system(KYIV)`
     * grabbed elsewhere — so the period the Settings page states *before* the button is
     * pressed always matches what pressing it actually does.
     */
    fun currentMonth(): String = currentMonthKey(clock)

    /**
     * [waitForRateLimit] is threaded straight through to [MonoClient.statement]. Callers
     * decide, not this service: the hourly background scheduler passes `true` (nobody is
     * waiting, so a busy month reconciling over a few minutes is fine); the manual
     * Settings button passes `false` (a human is holding an open HTTP request).
     *
     * [recordResult] = false leaves Settings' sync card alone. The card shows no month and
     * says sync covers only the current one, so the monthly recap's pull of the month just
     * closed must not land there — it would read as the current month's result and hide
     * that month's own errors.
     */
    suspend fun syncCurrentMonth(waitForRateLimit: Boolean = false): SyncReport =
        syncMonth(currentMonthKey(clock), waitForRateLimit)

    suspend fun syncMonth(month: String, waitForRateLimit: Boolean = false, recordResult: Boolean = true): SyncReport {
        val errors = mutableListOf<String>()
        val copy = settings.copy()

        // Refreshed on every sync, not just when the table is empty as it used to be.
        // An account opened after the first sync never got a row under the old rule, and
        // an account with no row is read as tracked hryvnia by every gate downstream
        // (`spentByCategory`'s LEFT JOIN, `AccountRepository.untrackedIds`,
        // `foreignCurrencyIds`) — which is exactly how a dollar account went on being
        // counted and asked about. It also keeps the balances the Settings card prints
        // from being frozen at whatever they were on first run.
        //
        // The refresh can throw (bad token, rate limit, network error) and must not
        // escape: this is often the very first sync after pasting a token, and an
        // uncaught exception here would skip recording the cooldown, leaving the Settings
        // button enabled to fire another request straight into the 429.
        try {
            refreshAccounts(waitForRateLimit)
        } catch (e: CancellationException) {
            // A Fly machine shutting down mid-request cancels this coroutine, and
            // CancellationException is an ordinary Exception — swallowed by the catch below
            // it would fall through into the statement loop, where every iteration would
            // throw at its first suspension point and be swallowed again, producing one
            // bogus "sync failed for account" per account and then writing a sync result to
            // SQLite on the way down. Under the old `if (ids().isEmpty())` guard clientInfo
            // was never reached on the hourly path at all, so this catch was effectively
            // dead; it is live now. Same shape as MccPromptService.onUnknownMcc.
            throw e
        } catch (e: Exception) {
            log.warn("account refresh failed", e)
            errors += "accounts: ${describeSyncFailure(copy, e)}"
            // Only fatal when there is nothing stored to fall back on — read here rather
            // than up front, because nothing was written: clientInfo throwing wrote no row,
            // and replaceAll throwing rolled its own transaction back, so the answer is the
            // same and the happy path pays for no extra table scan. Client-info has its own
            // once-a-minute limit, and letting a 429 there abort an hourly sync that has a
            // perfectly good list of accounts would trade a stale balance for a whole
            // missed month.
            if (accounts.ids().isEmpty()) {
                val report = SyncReport(month, 0, 0, 0, 0, errors, now())
                return if (recordResult) recordFailure(e, report) else report
            }
        }

        val (from, to) = monthRange(month)

        var inserted = 0
        var updated = 0
        var unchanged = 0
        // Only accounts the owner has left switched on, and only hryvnia ones — a
        // deactivated FOP account or eAid card must not keep pulling in new transactions,
        // even though its history stays put (see AccountRepository.setActive), and a
        // dollar account's statement would be refused by ingest anyway.
        val accountIds = accounts.activeIds()

        for (accountId in accountIds) {
            try {
                val items = mono.statement(accountId, from, to, waitForRateLimit)
                val outcome = ingest.ingest(items.map { it.item.toTxn(accountId, it.raw) })
                inserted += outcome.inserted
                updated += outcome.updated
                unchanged += outcome.unchanged
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("sync failed for account {}", accountId, e)
                errors += "$accountId: ${describeSyncFailure(copy, e)}"
            }
        }

        val report = SyncReport(month, accountIds.size, inserted, updated, unchanged, errors, now())
        return if (recordResult) record(report) else report
    }

    /**
     * The one place a finished sync is recorded: the cooldown stamp and the sentence the
     * Settings card prints are written together, so the hourly background loop leaves
     * exactly the trace a button press does.
     *
     * 2026-09-22 production walkthrough, finding 2: [SettingKeys.LAST_SYNC_RESULT] used to
     * be written only by `SettingsPage`'s `/settings/sync` handler, while this service
     * stamped [SettingKeys.LAST_SYNC_AT] alone. So "Востаннє:" silently meant "last
     * *manual* sync" and could print a weeks-old result while the app reconciled hourly.
     */
    /**
     * The total-failure counterpart of [record]: nothing was synced, so the card must print
     * [Copy.lastSyncFailed] with the real reason rather than a success summary.
     *
     * [record] hardcodes `ok = true`, which was right while every caller had actually
     * synced something. The "no accounts stored and the refresh failed" abort then started
     * routing through it, so a first sync with a rejected token rendered as "0 нових · 0
     * оновлених · 0 без змін · 0 рахунків · Помилки (1): токен відхилено" — a success
     * sentence carrying its own contradiction. `runDetachedSync` only writes `ok = false`
     * when the call *throws*, and this path deliberately returns instead.
     */
    private fun recordFailure(e: Throwable, report: SyncReport): SyncReport {
        settings.set(SettingKeys.LAST_SYNC_AT, report.syncedAt.toString())
        settings.set(SettingKeys.LAST_SYNC_RESULT, API_JSON.encodeToString(structuredSyncFailure(e)))
        return report
    }

    private fun record(report: SyncReport): SyncReport {
        settings.set(SettingKeys.LAST_SYNC_AT, report.syncedAt.toString())
        settings.set(
            SettingKeys.LAST_SYNC_RESULT,
            API_JSON.encodeToString(
                StoredSyncResult(
                    ok = true,
                    month = report.month,
                    accounts = report.accounts,
                    inserted = report.inserted,
                    updated = report.updated,
                    unchanged = report.unchanged,
                    errors = report.errors,
                    syncedAt = report.syncedAt,
                ),
            ),
        )
        return report
    }

    /**
     * Stamps the cooldown immediately, before a detached sync is launched. `syncMonth`
     * stamps [SettingKeys.LAST_SYNC_AT] again once it finishes, but that can be minutes
     * away for a busy month waiting out the rate limit — without this early stamp, the
     * cooldown check would stay open the whole time and a second click could launch an
     * overlapping sync.
     */
    fun markSyncStarted() {
        settings.set(SettingKeys.LAST_SYNC_AT, now().toString())
    }

    /** Zero when the Sync button may be pressed; otherwise how long it stays disabled. */
    fun secondsUntilSyncAllowed(): Long {
        val last = settings.get(SettingKeys.LAST_SYNC_AT)?.toLongOrNull() ?: return 0
        val elapsed = now() - last
        return (MIN_SYNC_INTERVAL_SECONDS - elapsed).coerceAtLeast(0)
    }

    private companion object {
        const val MIN_SYNC_INTERVAL_SECONDS = 60L
    }
}
