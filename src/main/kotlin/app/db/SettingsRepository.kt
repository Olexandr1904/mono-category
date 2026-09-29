package app.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert

object SettingKeys {
    const val MONO_TOKEN = "mono_token"
    const val TELEGRAM_TOKEN = "telegram_token"
    const val TELEGRAM_CHAT_ID = "telegram_chat_id"
    const val MONO_WEBHOOK_SECRET = "mono_webhook_secret"
    const val TELEGRAM_WEBHOOK_SECRET = "telegram_webhook_secret"

    /**
     * Set only once the registration call to the respective API actually succeeds — unlike
     * the *_WEBHOOK_SECRET keys above, which are generated (and persisted) the moment the
     * button is pressed, before the network call is even attempted. Reading a secret's
     * presence as "registered" was believable but wrong: it stayed true across a failed
     * call, and the Settings page had no way to tell the owner the webhook never actually
     * registered. See setup-flow-report for the incident this covers (Telegram /start went
     * nowhere because the webhook was never registered, and nothing on the page said so).
     */
    const val MONO_WEBHOOK_REGISTERED_AT = "mono_webhook_registered_at"
    const val TELEGRAM_WEBHOOK_REGISTERED_AT = "telegram_webhook_registered_at"
    const val PAIRING_CODE = "pairing_code"
    const val PAIRING_CODE_EXPIRES_AT = "pairing_code_expires_at"
    const val PAIRING_ATTEMPTS = "pairing_attempts"
    const val DEFAULT_THRESHOLD_PCT = "default_threshold_pct"
    const val LAST_SYNC_AT = "last_sync_at"
    const val LAST_SYNC_RESULT = "last_sync_result"

    /** Month key ("2026-09") of the last recap claimed by [app.notify.MonthlyReportService]. */
    const val MONTHLY_REPORT_SENT = "monthly_report_sent"

    /** "uk" or "en". Missing means Ukrainian — see [app.i18n.Language.fromCode]. */
    const val LANGUAGE = "language"

    /**
     * Revocation counter for issued session cookies. The cookie payload carries no
     * per-login randomness, so there is nothing session-specific to invalidate;
     * comparing a counter stored here against the one baked into the cookie is what
     * makes logout actually revoke access. See [app.web.SessionEpoch].
     */
    const val SESSION_EPOCH = "session_epoch"
}

class SettingsRepository(private val db: Database, private val crypto: Crypto) {

    fun get(key: String): String? = transaction(db) {
        Settings.selectAll().where { Settings.key eq key }.singleOrNull()?.let { row ->
            val raw = row[Settings.value]
            if (row[Settings.encrypted]) crypto.decrypt(raw) else raw
        }
    }

    fun set(key: String, value: String, encrypted: Boolean = false) = transaction(db) {
        Settings.upsert(Settings.key) {
            it[Settings.key] = key
            it[Settings.value] = if (encrypted) crypto.encrypt(value) else value
            it[Settings.encrypted] = encrypted
        }
        Unit
    }

    fun delete(key: String) = transaction(db) {
        Settings.deleteWhere { it.run { Settings.key eq key } }
        Unit
    }

    fun isSet(key: String): Boolean = transaction(db) {
        Settings.selectAll().where { Settings.key eq key }.limit(1).any()
    }
}
