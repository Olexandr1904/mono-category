package app.i18n

import app.db.SettingKeys
import app.db.SettingsRepository

/**
 * The one language setting that drives every user-facing string, on the web and in the
 * Telegram bot alike. Deliberately not derived from `Accept-Language`: the bot has no such
 * header, and its alerts must read in the same language as the web UI.
 */
enum class Language(val code: String, val copy: Copy) {
    UK("uk", UkCopy),
    EN("en", EnCopy),
    ;

    companion object {
        fun fromCode(code: String?): Language = entries.firstOrNull { it.code == code } ?: UK
    }
}

/** Ukrainian by default — the owner is Ukrainian and this is who the app is speaking to. */
fun SettingsRepository.language(): Language = Language.fromCode(get(SettingKeys.LANGUAGE))

fun SettingsRepository.setLanguage(language: Language) = set(SettingKeys.LANGUAGE, language.code)

/** The [Copy] for the currently configured language — the single lookup every renderer uses. */
fun SettingsRepository.copy(): Copy = language().copy
