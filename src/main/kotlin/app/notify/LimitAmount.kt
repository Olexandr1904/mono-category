package app.notify

import app.budget.parseAmountToMinor

/**
 * One million hryvnia. Far above any household budget, and far below the point where
 * [parseAmountToMinor]'s `whole * 100` overflows a Long.
 */
const val MAX_LIMIT_MINOR = 100_000_000L

/** Digits before the decimal point that could still overflow a Long once multiplied by 100. */
private const val MAX_WHOLE_DIGITS = 12

/**
 * Built through [java.util.regex.Pattern] rather than with [RegexOption.IGNORE_CASE],
 * which maps to `CASE_INSENSITIVE` alone — and that is ASCII-only. Without `UNICODE_CASE`
 * beside it, "uah" folded in any case while Cyrillic "ГРН" did not fold at all.
 */
private val CURRENCY_SUFFIX = java.util.regex.Pattern.compile(
    """\s*(₴|грн\.?|uah)\s*$""",
    java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.UNICODE_CASE,
).toRegex()

sealed class AmountResult {
    data class Ok(val minor: Long) : AmountResult()
    object NotANumber : AmountResult()
    object Negative : AmountResult()
    object TooLarge : AmountResult()
}

/**
 * Reads a limit typed into a chat window, where the input is a person rather than a form.
 *
 * Deliberately more forgiving than [parseAmountToMinor] in one direction only: the currency
 * suffix is stripped here and not there, because that parser is shared with the web form and
 * loosening it for the chat would loosen it for both.
 *
 * Every rejection is its own case. "That is not a number" and "that is negative" need
 * different answers — showing someone who typed -500 an example of what a number looks like
 * tells them nothing about what was actually wrong.
 */
fun parseLimitAmount(raw: String): AmountResult {
    val cleaned = raw.filter { !it.isISOControl() }.trim().replace(CURRENCY_SUFFIX, "").trim()
    if (cleaned.isEmpty()) return AmountResult.NotANumber
    if (cleaned.startsWith("-")) return AmountResult.Negative

    // Checked on the text, before parsing: the overflow this guards against is silent, so
    // afterwards there would be no exception to catch and no wrong-looking number to spot.
    val wholeDigits = cleaned.substringBefore('.').substringBefore(',').count { it.isDigit() }
    if (wholeDigits > MAX_WHOLE_DIGITS) return AmountResult.TooLarge

    // IllegalArgumentException for junk, NumberFormatException — a subclass — for an integer
    // too large for Long. One catch covers both.
    val minor = runCatching { parseAmountToMinor(cleaned) }
        .getOrElse { return AmountResult.NotANumber }

    if (minor < 0) return AmountResult.Negative
    if (minor > MAX_LIMIT_MINOR) return AmountResult.TooLarge
    return AmountResult.Ok(minor)
}
